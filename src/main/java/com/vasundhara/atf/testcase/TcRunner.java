package com.vasundhara.atf.testcase;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Async orchestrator for Test Case Execution sessions.
 *
 * <p>Runs one TC at a time, honouring pause / stop signals. Execution strategy:
 * <ol>
 *   <li>Install APK and launch the app <b>once</b> at session start.</li>
 *   <li>Execute each TC in sequence from the current application state — the app
 *       is never force-stopped or relaunched between test cases.</li>
 *   <li>On step FAIL: capture screenshot + logcat snippet, continue to next TC.</li>
 *   <li>After all steps: determine TC-level status and collect logcat / crash info.</li>
 * </ol>
 */
@Component
public class TcRunner {

    private final AdbClient     adb;
    private final StepEngine    engine;
    private final AtfProperties props;
    private final com.vasundhara.atf.engine.ExecutionLockService execLock;
    private final com.vasundhara.atf.report.RunBridgeService runBridge;
    private final com.vasundhara.atf.device.DeviceWatchdog watchdog;
    private final TcSessionStore sessionStore;

    public TcRunner(AdbClient adb, StepEngine engine, AtfProperties props,
                    com.vasundhara.atf.engine.ExecutionLockService execLock,
                    com.vasundhara.atf.report.RunBridgeService runBridge,
                    com.vasundhara.atf.device.DeviceWatchdog watchdog,
                    TcSessionStore sessionStore) {
        this.adb    = adb;
        this.engine = engine;
        this.props  = props;
        this.execLock = execLock;
        this.runBridge = runBridge;
        this.watchdog = watchdog;
        this.sessionStore = sessionStore;
    }

    // ── async entry point ─────────────────────────────────────────────────────

    @Async("testRunExecutor")
    public void run(TcSession session, String serial, int w, int h) {
        runBridge.start(session.getId(), "Test Case", session.getApkFileName(), serial);
        watchdog.startWatch(session.getId(), serial, () -> {
            if (session.isStopRequested()) return; // manual stop already in flight
            session.setError("Device disconnected during test execution.");
            session.requestStop();
            session.addLog("DEVICE DISCONNECTED — stopping test case execution.");
        });
        try {
        session.setState(TcSession.State.RUNNING);
        session.setStartTime(System.currentTimeMillis());
        session.setSerial(serial);
        session.addLog("Execution started on device: " + serial);

        File runDir = new File(props.getWorkDir(), "tc-" + session.getId()).getAbsoluteFile();
        runDir.mkdirs();

        try {
            installApk(session, serial);
        } catch (Exception e) {
            session.setState(TcSession.State.FAILED);
            session.setError("APK install failed: " + e.getMessage());
            session.addLog("FATAL: APK install failed — " + e.getMessage());
            return;
        }

        // Launch app once for the entire session; all TCs run from this live state.
        String pkg = session.getPackageName();
        if (pkg != null && !pkg.isBlank()) {
            try {
                adb.launchApp(serial, pkg);
                sleep(2000);
                session.addLog("App launched (" + pkg + "). Executing all test cases in sequence without restart.");
            } catch (Exception e) {
                session.addLog("! Initial app launch failed — " + e.getMessage()
                        + ". Continuing; individual LAUNCH_APP steps will retry.");
            }
        }

        List<TcItem> tcs = session.getTestCases();
        String lastActivity = "";
        for (int i = 0; i < tcs.size(); i++) {

            if (session.isStopRequested()) {
                session.addLog("Stop requested — halting after " + i + " test cases.");
                markRemaining(session, tcs, i, "NOT_EXECUTED");
                session.setState(TcSession.State.STOPPED);
                return;
            }

            if (session.isPauseRequested()) {
                session.setState(TcSession.State.PAUSED);
                session.addLog("Paused before TC[" + (i + 1) + "].");
                while (session.isPauseRequested() && !session.isStopRequested()) sleep(500);
                if (session.isStopRequested()) {
                    markRemaining(session, tcs, i, "NOT_EXECUTED");
                    session.setState(TcSession.State.STOPPED);
                    return;
                }
                session.setState(TcSession.State.RUNNING);
                session.addLog("Resumed.");
            }

            // Log the current screen before executing the test case so the console shows
            // which screen the framework is on at each step.
            String curActivity = currentActivityName(serial, session.getPackageName());
            if (!curActivity.isBlank() && !curActivity.equals(lastActivity)) {
                session.addLog("▶ Screen — " + toReadableName(curActivity));
                lastActivity = curActivity;
            }

            TcItem tc = tcs.get(i);
            session.setCurrentIndex(i);
            session.setCurrentTc(tc.getId(), tc.getName());
            session.addLog("── TC[" + (i + 1) + "/" + tcs.size() + "] " + tc.getId() + ": " + tc.getName());

            TcItemResult result = executeOne(tc, session, serial, session.getPackageName(), w, h, runDir, false);
            session.setCurrentStep(null);
            session.addResult(result);
            session.addLog("   → " + result.getStatus()
                    + " | P=" + result.getPassedSteps() + " F=" + result.getFailedSteps()
                    + " dur=" + result.getDurationMs() + "ms");

            // Log again after the TC in case it navigated to a new screen.
            String afterActivity = currentActivityName(serial, session.getPackageName());
            if (!afterActivity.isBlank() && !afterActivity.equals(lastActivity)) {
                session.addLog("▶ Screen — " + toReadableName(afterActivity));
                lastActivity = afterActivity;
            }
        }

        session.setFinishedAt(System.currentTimeMillis());
        session.setState(TcSession.State.COMPLETED);
        session.addLog("Execution complete — Pass:" + session.getPassed()
                + " Fail:" + session.getFailed()
                + " Blocked:" + session.getBlocked()
                + " Skipped:" + session.getSkipped()
                + " PassPct:" + String.format("%.1f%%", session.getPassPct()));
        } finally {
            // Persist the FINAL session (with all test-case results) — the only earlier save (in
            // TcController, at creation) only ever wrote the initial empty SETUP snapshot, so
            // without this the Reports module would show stale/empty data for this session after
            // any server restart even though it completed successfully.
            sessionStore.save(session);
            restoreConnectivity(session, serial);
            watchdog.stopWatch(session.getId());
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    /**
     * Sheets legitimately contain "Disable internet" steps; if the run stops/crashes before a
     * matching "Enable internet" step executes, the device would be left offline for every
     * subsequent run (the same WiFi-state leak fixed for the New Test orchestrator earlier).
     * Only touches the radio when it is actually off, so normal runs are unaffected.
     */
    private void restoreConnectivity(TcSession session, String serial) {
        try {
            if (!adb.isWifiEnabled(serial)) {
                adb.setWifi(serial, true);
                try { adb.setData(serial, true); } catch (Exception ignored) {}
                session.addLog("Connectivity restored (a test step had disabled the internet).");
            }
        } catch (Exception ignored) {}
    }

    // ── re-run failed ─────────────────────────────────────────────────────────

    @Async("testRunExecutor")
    public void rerunFailed(TcSession session, List<TcItem> failedItems, String serial, int w, int h) {
        runBridge.start(session.getId(), "Test Case", session.getApkFileName(), serial);
        watchdog.startWatch(session.getId(), serial, () -> {
            if (session.isStopRequested()) return; // manual stop already in flight
            session.setError("Device disconnected during test execution.");
            session.requestStop();
            session.addLog("DEVICE DISCONNECTED — stopping re-run.");
        });
        try {
        session.setState(TcSession.State.RUNNING);
        session.setStartTime(System.currentTimeMillis());
        session.setSerial(serial);
        session.addLog("Re-run of " + failedItems.size() + " failed test case(s) started.");

        File runDir = new File(props.getWorkDir(), "tc-" + session.getId() + "-rerun").getAbsoluteFile();
        runDir.mkdirs();

        String pkg = session.getPackageName();
        for (int i = 0; i < failedItems.size(); i++) {
            if (session.isStopRequested()) {
                markRemaining(session, failedItems, i, "NOT_EXECUTED");
                session.setState(TcSession.State.STOPPED);
                return;
            }
            TcItem tc = failedItems.get(i);
            session.setCurrentIndex(i);
            session.setCurrentTc(tc.getId(), tc.getName());
            session.addLog("Re-running [" + (i + 1) + "/" + failedItems.size() + "] " + tc.getId());
            // Re-runs reset to clean state so each failed TC is independent.
            TcItemResult result = executeOne(tc, session, serial, pkg, w, h, runDir, true);
            session.setCurrentStep(null);
            session.addResult(result);
        }

        session.setFinishedAt(System.currentTimeMillis());
        session.setState(TcSession.State.COMPLETED);
        session.addLog("Re-run complete.");
        } finally {
            // Persist the FINAL session (with all test-case results) — the only earlier save (in
            // TcController, at creation) only ever wrote the initial empty SETUP snapshot, so
            // without this the Reports module would show stale/empty data for this session after
            // any server restart even though it completed successfully.
            sessionStore.save(session);
            restoreConnectivity(session, serial);
            watchdog.stopWatch(session.getId());
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    // ── single TC execution ───────────────────────────────────────────────────

    private TcItemResult executeOne(TcItem tc, TcSession session,
                                    String serial, String pkg,
                                    int w, int h, File runDir,
                                    boolean resetAppBefore) {
        TcItemResult result = new TcItemResult();
        result.setTcId(tc.getId());
        result.setTcName(tc.getName());
        result.setModule(tc.getModule());
        result.setFeature(tc.getFeature());
        result.setPriority(tc.getPriority());
        result.setStartTime(System.currentTimeMillis());

        if (tc.isDuplicate()) {
            result.setStatus("SKIPPED");
            result.setAiNotes("Duplicate of " + tc.getDuplicateOf() + " — skipped.");
            result.setEndTime(System.currentTimeMillis());
            return result;
        }

        if (tc.getSteps().isEmpty()) {
            result.setStatus("BLOCKED");
            result.setAiNotes("Test case has no steps defined — blocked.");
            result.setEndTime(System.currentTimeMillis());
            return result;
        }

        // Re-run mode: reset to a clean state before each individual TC.
        // Normal mode: app stays open from where the previous TC left off.
        if (resetAppBefore && pkg != null && !pkg.isBlank()) {
            try {
                adb.forceStop(serial, pkg);
                sleep(600);
                adb.launchApp(serial, pkg);
                sleep(1500);
            } catch (Exception e) {
                session.addLog("   ! App reset failed: " + e.getMessage());
            }
        }

        // Clear logcat before TC
        try { adb.clearLogcat(serial); } catch (Exception ignored) {}

        List<TcStepResult> stepResults = new ArrayList<>();
        boolean blocked = false;

        for (TcStep step : tc.getSteps()) {
            if (session.isStopRequested()) {
                stepResults.add(notExecuted(step, "Execution stopped by user."));
                continue;
            }
            if (blocked) {
                stepResults.add(notExecuted(step, "Blocked by earlier step failure."));
                continue;
            }

            long stepStart = System.currentTimeMillis();
            session.setCurrentStep("Step " + step.num() + ": " + step.description());
            StepEngine.StepOutcome outcome;
            try {
                outcome = engine.execute(step, serial, pkg, w, h, adb, runDir);
            } catch (Exception ex) {
                outcome = StepEngine.StepOutcome.fail("ERROR", ex.getMessage());
            }
            long stepDur = System.currentTimeMillis() - stepStart;

            String notes  = outcome.passed() ? null : engine.generateNotes(step, outcome);
            String status = outcome.passed() ? "PASS" : "FAIL";

            // Early step failure likely leaves the app in an unexpected state — block the rest of this TC
            if (!outcome.passed() && step.num() <= 2) {
                blocked = true;
                status  = "BLOCKED";
                notes   = (notes != null ? notes + " " : "") + "Step " + step.num() + " failure blocks subsequent steps.";
            }

            stepResults.add(new TcStepResult(step.num(), step.description(),
                    step.expectedResult(), outcome.actualResult(),
                    status, outcome.screenshotAfter(), outcome.screenshotBefore(),
                    notes, stepDur));

            session.addLog("   Step " + step.num() + ": " + status
                    + (notes != null ? " — " + truncate(notes, 80) : ""));
        }

        result.setStepResults(stepResults);

        boolean anyFail    = stepResults.stream().anyMatch(s -> "FAIL".equals(s.status()));
        boolean anyBlocked = stepResults.stream().anyMatch(s -> "BLOCKED".equals(s.status()));
        boolean allPass    = stepResults.stream().allMatch(s -> "PASS".equals(s.status()));
        String tcStatus;
        if (allPass)         tcStatus = "PASS";
        else if (anyBlocked) tcStatus = "BLOCKED";
        else if (anyFail)    tcStatus = "FAIL";
        else                 tcStatus = "NOT_EXECUTED";
        result.setStatus(tcStatus);

        // Logcat snippet
        try {
            String logcat = adb.dumpLogcat(serial);
            result.setLogcatSnippet(logcat.length() > 4000 ? logcat.substring(0, 4000) + "…" : logcat);
        } catch (Exception ignored) {}

        // Crash detection
        try {
            String crash = adb.shellStr(serial, "logcat -d -b crash *:E | grep -E 'FATAL|AndroidRuntime|CRASH'");
            if (!crash.isBlank()) result.setCrashLog(crash.length() > 2000 ? crash.substring(0, 2000) : crash);
        } catch (Exception ignored) {}

        if (!"PASS".equals(tcStatus)) {
            result.setAiNotes(String.format("%s — %d step(s) failed, %d blocked. %s",
                    tc.getName(), result.getFailedSteps(), result.getBlockedSteps(),
                    result.getCrashLog() != null ? "A crash was detected." : "No crash detected."));
        }

        // Last failure screenshot as TC thumbnail
        stepResults.stream().filter(s -> s.screenshotPath() != null)
                .reduce((a, b) -> b)
                .ifPresent(s -> result.setScreenshotPath(s.screenshotPath()));

        result.setEndTime(System.currentTimeMillis());
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void installApk(TcSession session, String serial) throws Exception {
        String apkName = session.getApkFileName();
        if (apkName == null || apkName.isBlank()) return;
        File apk = new File(props.getUploadDir(), apkName).getAbsoluteFile();
        if (!apk.exists()) throw new IOException("APK not found: " + apk.getAbsolutePath());
        session.addLog("Installing APK: " + apkName);
        adb.install(serial, apk, session.getPackageName());
        sleep(2000);
        session.addLog("APK installed.");
    }

    private TcStepResult notExecuted(TcStep step, String reason) {
        return new TcStepResult(step.num(), step.description(), step.expectedResult(),
                "Not executed", "NOT_EXECUTED", null, null, reason, 0L);
    }

    private void markRemaining(TcSession session, List<TcItem> tcs, int fromIdx, String status) {
        for (int j = fromIdx; j < tcs.size(); j++) {
            TcItem tc = tcs.get(j);
            TcItemResult r = new TcItemResult();
            r.setTcId(tc.getId()); r.setTcName(tc.getName());
            r.setModule(tc.getModule()); r.setFeature(tc.getFeature());
            r.setPriority(tc.getPriority());
            r.setStatus(status);
            r.setAiNotes("Not executed — session was stopped.");
            r.setStartTime(System.currentTimeMillis());
            r.setEndTime(r.getStartTime());
            session.addResult(r);
        }
    }

    /** Return the simple class name of the currently resumed activity, or empty string on failure. */
    private String currentActivityName(String serial, String pkg) {
        try {
            String out = adb.shell(serial, 10, "dumpsys", "activity", "activities").stdout();
            if (out == null) return "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("mResumedActivity.*?\\{[^}]*\\s[\\w.]+/\\.?([\\w.$]+)").matcher(out);
            if (m.find()) {
                String cls = m.group(1);
                int dot = cls.lastIndexOf('.');
                return dot >= 0 ? cls.substring(dot + 1) : cls;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Convert "NoteDetailsActivity" → "Note Details", "HomeFragment" → "Home", etc. */
    private static String toReadableName(String className) {
        if (className == null || className.isBlank()) return "Unknown Screen";
        String s = className.startsWith(".") ? className.substring(1) : className;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        for (String sfx : new String[]{"Activity", "Fragment", "Screen", "Page", "View"}) {
            if (s.endsWith(sfx) && s.length() > sfx.length()) { s = s.substring(0, s.length() - sfx.length()); break; }
        }
        s = s.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2").trim();
        return s.isEmpty() ? className : s;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
