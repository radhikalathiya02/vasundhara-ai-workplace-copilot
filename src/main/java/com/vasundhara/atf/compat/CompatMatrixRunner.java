package com.vasundhara.atf.compat;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.device.EmulatorManager;
import com.vasundhara.atf.device.EmulatorManager.BootedEmulator;
import com.vasundhara.atf.engine.ExplorationEngine;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.TestContext;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.model.TestRun;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs the automatic Android 9–16 compatibility matrix: for every configured API
 * level it provisions/boots an emulator, installs and launches the APK, performs
 * UI/functionality validation, then tears the emulator down — all without any
 * manually connected device. Each version is independent; an environment failure
 * on one version is recorded and the run continues.
 */
@Component
public class CompatMatrixRunner {

    private static final Logger log = LoggerFactory.getLogger(CompatMatrixRunner.class);

    private final AtfProperties props;
    private final ApkAnalyzer apkAnalyzer;
    private final EmulatorManager emulators;
    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final ExplorationEngine explorationEngine;
    private final com.vasundhara.atf.engine.ExecutionLockService execLock;
    private final com.vasundhara.atf.report.RunBridgeService runBridge;
    private final com.vasundhara.atf.device.DeviceWatchdog watchdog;
    private final CompatSessionStore sessionStore;

    public CompatMatrixRunner(AtfProperties props, ApkAnalyzer apkAnalyzer, EmulatorManager emulators,
                              AdbClient adb, DeviceManager deviceManager, DriverFactory driverFactory,
                              ExplorationEngine explorationEngine,
                              com.vasundhara.atf.engine.ExecutionLockService execLock,
                              com.vasundhara.atf.report.RunBridgeService runBridge,
                              com.vasundhara.atf.device.DeviceWatchdog watchdog,
                              CompatSessionStore sessionStore) {
        this.props = props;
        this.apkAnalyzer = apkAnalyzer;
        this.emulators = emulators;
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.explorationEngine = explorationEngine;
        this.execLock = execLock;
        this.runBridge = runBridge;
        this.watchdog = watchdog;
        this.sessionStore = sessionStore;
    }

    public static String versionLabel(int api) {
        return switch (api) {
            case 28 -> "Android 9"; case 29 -> "Android 10"; case 30 -> "Android 11";
            case 31, 32 -> "Android 12"; case 33 -> "Android 13"; case 34 -> "Android 14";
            case 35 -> "Android 15"; case 36 -> "Android 16"; default -> "API " + api;
        };
    }

    @Async("testRunExecutor")
    public void run(CompatSession session, File apkFile) {
        runSync(session, apkFile, null);
    }

    /**
     * Async wrapper — called from {@link com.vasundhara.atf.web.CompatibilityController}.
     * @param apiCsv comma-separated API levels to test; blank/null → all configured versions (full matrix).
     */
    @Async("testRunExecutor")
    public void run(CompatSession session, File apkFile, String apiCsv) {
        runBridge.start(session.getId(), "Compatibility Testing", session.getApkFileName(), null);
        try {
            runSync(session, apkFile, apiCsv);
        } finally {
            // Persist the FINAL session (with all version results) — the only earlier save (in
            // CompatibilityController, at creation) only ever wrote the initial empty SETUP
            // snapshot, so without this the Reports module would show stale/empty data for this
            // session after any server restart even though it completed successfully.
            sessionStore.save(session);
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    /**
     * Synchronous entry point used by {@link com.vasundhara.atf.tests.CompatibilityTest} when the
     * emulator matrix is requested as part of a main test run (no separate APK upload needed).
     */
    public void runSync(CompatSession session, File apkFile, String apiCsv) {
        runSync(session, apkFile, apiCsv, null);
    }

    /**
     * Synchronous entry point with a live-progress callback.  {@code onUpdate} is called after
     * every major state transition inside each version run so callers can push live progress to the
     * owning {@link com.vasundhara.atf.model.TestRun}.
     */
    public void runSync(CompatSession session, File apkFile, String apiCsv,
                        Consumer<CompatVersionResult> onUpdate) {
        try {
            session.setState(CompatSession.State.RUNNING);
            boolean single = apiCsv != null && !apiCsv.isBlank();
            session.addLog(single
                    ? "Starting compatibility testing for " + session.getScope() + "…"
                    : "Starting automatic Android 9–16 compatibility matrix…");

            if (!emulators.toolingAvailable()) {
                session.setError("Android emulator tooling not found. " + emulators.toolingDiagnostics());
                session.setState(CompatSession.State.FAILED);
                session.addLog("FAILED: " + session.getError());
                return;
            }

            // Static parse once for package name + driver capabilities.
            ApkInfo info = apkAnalyzer.analyze(apkFile);
            String pkg = info.getPackageName();
            session.setPackageName(pkg);
            session.addLog("APK package: " + pkg);

            boolean appium = driverFactory.isAppiumReachable();
            session.addLog(appium
                    ? "Appium reachable — full UI crawl enabled per emulator."
                    : "Appium not reachable — falling back to launch + logcat crash/ANR detection per emulator.");

            List<Integer> apis = parseApis(single ? apiCsv : props.getCompatApiLevels());
            if (apis.isEmpty()) apis = parseApis(props.getCompatApiLevels());

            // Environment pre-check — validate SDK, tools, and per-API system images.
            List<Map<String, Object>> checkItems = emulators.preCheck(apis);
            session.setPreCheckItems(checkItems);
            long failCount = checkItems.stream().filter(c -> "FAIL".equals(c.get("status"))).count();
            session.addLog("Environment pre-check: "
                    + (failCount == 0 ? "all " + checkItems.size() + " item(s) OK"
                                      : failCount + "/" + checkItems.size() + " item(s) failed"));
            if (failCount > 0) {
                String failed = checkItems.stream()
                        .filter(c -> "FAIL".equals(c.get("status")))
                        .map(c -> c.get("name") + (c.containsKey("fix") ? " [Fix: " + c.get("fix") + "]" : ""))
                        .collect(Collectors.joining("; "));
                session.addLog("Pre-check failures: " + failed);
            }

            // Pre-create the version rows so the UI shows the full matrix immediately.
            for (int api : apis) session.getVersions().add(new CompatVersionResult(versionLabel(api), api));

            for (CompatVersionResult vr : session.getVersions()) {
                if (session.isStopRequested()) {
                    session.addLog("Stop requested — skipping remaining version(s).");
                    break;
                }
                runOneVersion(session, vr, apkFile, info, pkg, appium, onUpdate);
            }

            // Overall score = average of versions actually tested (exclude ENV ERROR).
            List<CompatVersionResult> tested = session.getVersions().stream()
                    .filter(v -> !"ENV ERROR".equals(v.getStatus())).toList();
            int score = tested.isEmpty() ? 0
                    : (int) Math.round(tested.stream().mapToInt(CompatVersionResult::getScore).average().orElse(0));
            session.setOverallScore(score);
            boolean anyFail = tested.stream().anyMatch(v -> "FAIL".equals(v.getStatus()));
            boolean anyWarn = tested.stream().anyMatch(v -> "WARNING".equals(v.getStatus()));
            session.setOverallStatus(tested.isEmpty() ? "NOT TESTED" : anyFail ? "FAIL" : anyWarn ? "WARNING" : "PASS");

            if (session.isStopRequested()) {
                session.setState(CompatSession.State.STOPPED);
                session.addLog("Compatibility testing stopped. Partial results saved for "
                        + tested.size() + "/" + session.getVersions().size() + " version(s).");
            } else {
                session.setState(CompatSession.State.COMPLETED);
                session.addLog("Compatibility matrix complete. Overall: " + session.getOverallStatus()
                        + " (" + score + "/100), " + tested.size() + "/" + session.getVersions().size() + " version(s) tested.");
            }
        } catch (Exception e) {
            log.error("Compatibility matrix error for {}: {}", session.getId(), e.getMessage(), e);
            if (session.getError() == null || session.getError().isBlank()) session.setError("Matrix error: " + e.getMessage());
            session.setState(CompatSession.State.FAILED);
            session.addLog("FAILED: " + session.getError());
        } finally {
            watchdog.stopWatch(session.getId());
            session.setFinishedAt(System.currentTimeMillis());
        }
    }

    private void runOneVersion(CompatSession session, CompatVersionResult vr, File apkFile,
                               ApkInfo info, String pkg, boolean appium,
                               Consumer<CompatVersionResult> onUpdate) {
        BootedEmulator emu = null;
        String tag = vr.getVersion() + " (API " + vr.getApi() + ")";
        try {
            session.addLog("── " + tag + " ──");
            vr.setState("PROVISIONING");
            if (onUpdate != null) onUpdate.accept(vr);
            String avd = emulators.ensureAvd(vr.getApi(), session::addLog);

            // ── Phase 1: Launch emulator process ───────────────────────────────
            vr.setState("LAUNCHING_EMU");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": launching emulator…");

            // Boot with an early callback so the serial is exposed (and live-screen polling
            // begins) the instant the OS process starts — before waitForBoot blocks.
            final Consumer<CompatVersionResult> finalOnUpdate = onUpdate;
            emu = emulators.boot(avd, session::addLog, serial -> {
                vr.setSerial(serial);
                vr.setState("BOOTING");
                if (finalOnUpdate != null) finalOnUpdate.accept(vr);
                session.addLog(tag + ": emulator process started (" + serial + "), waiting for boot…");
            });
            String serial = emu.serial();

            // ── Phase 2: Emulator fully booted ────────────────────────────────
            vr.setState("EMULATOR_READY");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": emulator ready — beginning installation.");
            sleep(800); // brief pause so "Emulator Ready" is visible in the UI

            runTestPhasesOnSerial(session, vr, serial, apkFile, info, pkg, appium, onUpdate);
            vr.setSerial(null);  // emulator is about to shut down

        } catch (EmulatorManager.EmulatorException e) {
            vr.setStatus("ENV ERROR");
            vr.setError(e.getMessage());
            vr.setState("ERROR");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": ENV ERROR — " + e.getMessage());
        } catch (Exception e) {
            vr.setStatus("ENV ERROR");
            vr.setError(e.getMessage());
            vr.setState("ERROR");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": error — " + e.getMessage());
        } finally {
            if (emu != null) emulators.shutdown(emu, session::addLog);
        }
    }

    /**
     * Install → launch → crawl → analyse on an already-running device identified by {@code serial}.
     * Shared by the booted-emulator matrix ({@link #runOneVersion}) and the on-device flow
     * ({@link #runOnDeviceSync}). Operates on the supplied {@code vr}; uninstalls the app on exit.
     */
    private void runTestPhasesOnSerial(CompatSession session, CompatVersionResult vr, String serial,
                                       File apkFile, ApkInfo info, String pkg, boolean appium,
                                       Consumer<CompatVersionResult> onUpdate) {
        String tag = vr.getVersion() + " (API " + vr.getApi() + ")";
        AndroidDriver driver = null;
        try {
            // ── Install APK ────────────────────────────────────────────────────
            vr.setState("INSTALLING");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": installing APK…");
            var install = adb.install(serial, apkFile, pkg);
            if (!install.combined().contains("Success")) {
                vr.setStatus("FAIL");
                vr.getIssues().add(new CompatVersionResult.Issue("Installation", "Functionality Issue",
                        "APK failed to install on " + tag + ".", "CRITICAL",
                        tailLine(install.combined()), null, null));
                vr.setScore(0);
                vr.setState("DONE");
                if (onUpdate != null) onUpdate.accept(vr);
                return;
            }
            adb.clearLogcat(serial);
            vr.setState("LAUNCHING");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": launching app…");
            // Launch-then-verify (not just launch-then-sleep): if a leftover app, permission prompt,
            // or system screen is in front after the initial launch, force it away and relaunch once
            // so the crawl/monkey pass below always starts strictly inside the uploaded app, not
            // whatever else happened to be in the foreground.
            adb.launchAndWaitForeground(serial, pkg);
            boolean launched = adb.isForeground(serial, pkg) || adb.isAppRunning(serial, pkg);

            vr.setState("TESTING");
            if (onUpdate != null) onUpdate.accept(vr);

            ExplorationResult exp = null;
            DeviceManager.DeviceInfo devInfo = deviceManager.profile(serial);
            if (appium && launched) {
                try {
                    driver = driverFactory.create(serial, info);
                    File runDir = new File(props.getWorkDir(), session.getId() + "-api" + vr.getApi());
                    runDir.mkdirs();
                    TestRun stub = new TestRun(session.getId(), session.getApkFileName(), List.of());
                    TestContext ctx = new TestContext(props, adb, serial, apkFile, info, runDir, stub, devInfo);
                    ctx.setDriver(driver);
                    final String vtag = tag;
                    ctx.setScreenNavLogger(msg -> session.addLog(vtag + ": " + msg));
                    session.addLog(tag + ": exploring UI (up to " + props.getCompatCrawlSteps() + " steps)…");
                    exp = explorationEngine.explore(ctx, props.getCompatCrawlSteps());
                    vr.setMode("UI crawl (Appium)");
                } catch (Exception e) {
                    session.addLog(tag + ": UI crawl failed (" + e.getMessage() + "); using logcat-only analysis.");
                    vr.setMode("launch + logcat");
                } finally {
                    if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} driver = null; }
                }
            } else {
                // Light interaction to trigger flows, then rely on logcat.
                try { adb.monkey(serial, pkg, 80, 42, 250); } catch (Exception ignored) {}
                sleep(1500);
                vr.setMode("launch + logcat");
            }

            String crashLog = safe(() -> adb.dumpCrashBuffer(serial)) + "\n" + safe(() -> adb.dumpLogcat(serial));
            CompatAnalyzer.Result a = CompatAnalyzer.analyze(
                    exp, crashLog, launched, vr.getVersion().replace("Android ", ""),
                    devInfo != null ? devInfo.widthPx() : 0, devInfo != null ? devInfo.heightPx() : 0, pkg);

            // Orientation test — briefly rotate to landscape and detect layout regressions.
            List<CompatAnalyzer.UiIssue> orientIssues = new ArrayList<>();
            int portraitWidgetCount = exp != null
                    ? exp.getScreens().stream().mapToInt(sc -> sc.widgets().size()).sum() : 0;
            if (launched && portraitWidgetCount > 0) {
                try {
                    session.addLog(tag + ": orientation test (landscape)…");
                    adb.setRotation(serial, 1);
                    sleep(1200);
                    String landXml = adb.uiDump(serial);
                    adb.setRotation(serial, 0);
                    sleep(800);
                    String firstAct = (exp != null && !exp.getScreens().isEmpty())
                            ? CompatAnalyzer.screenName(exp.getScreens().get(0)) : "App";
                    orientIssues.addAll(CompatAnalyzer.orientationIssues(landXml, firstAct, portraitWidgetCount));
                    if (!orientIssues.isEmpty())
                        session.addLog(tag + ": landscape check — " + orientIssues.size() + " issue(s).");
                } catch (Exception e) {
                    session.addLog(tag + ": orientation test skipped — " + e.getMessage());
                }
            }

            // Merge analysis + orientation issues; recompute score/status with the full set.
            List<CompatAnalyzer.UiIssue> allIssues = new ArrayList<>(a.issues());
            allIssues.addAll(orientIssues);
            String finalStatus = allIssues == a.issues() ? a.status() : CompatAnalyzer.statusFor(allIssues);
            int finalScore    = allIssues == a.issues() ? a.score()  : CompatAnalyzer.scoreFor(allIssues);

            vr.setStatus(finalStatus);
            vr.setScore(finalScore);
            vr.setScreensExplored(a.screensExplored());
            vr.setActions(a.actions());

            // Add all issues to the flat list; screenshots live under the per-version artifact dir.
            String artifactId   = session.getId() + "-api" + vr.getApi();
            String artifactBase = "/api/runs/" + artifactId + "/artifacts/";
            for (CompatAnalyzer.UiIssue i : allIssues) {
                String shotUrl = (i.screenshot() != null && !i.screenshot().isBlank())
                        ? artifactBase + i.screenshot() : null;
                vr.getIssues().add(new CompatVersionResult.Issue(
                        i.screen(), i.type(), i.description(), i.severity().name(), i.logcat(), shotUrl, i.crash()));
            }

            // Per-screen Pass/Fail breakdown.
            vr.getScreenResults().addAll(CompatAnalyzer.buildScreenResults(exp, allIssues, artifactBase));

            vr.setState("DONE");
            if (onUpdate != null) onUpdate.accept(vr);
            session.addLog(tag + ": " + finalStatus + " (" + finalScore + "/100), " + allIssues.size() + " issue(s).");

            try { adb.forceStop(serial, pkg); adb.uninstall(serial, pkg); } catch (Exception ignored) {}
        } finally {
            if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} }
        }
    }

    // ── On-device compatibility (multi-device flow) ─────────────────────────────
    @Async("testRunExecutor")
    public void runOnDevice(CompatSession session, File apkFile, String serial) {
        runBridge.start(session.getId(), "Compatibility Testing", session.getApkFileName(), serial);
        watchdog.startWatch(session.getId(), serial, () -> {
            if (session.isStopRequested()) return; // manual stop already in flight
            session.setError("Device disconnected during compatibility test.");
            session.requestStop();
            session.addLog("DEVICE DISCONNECTED — stopping compatibility test.");
        });
        try {
            runOnDeviceSync(session, apkFile, serial, null);
        } finally {
            // Persist the FINAL session (with all version results) — see the note in run() above.
            sessionStore.save(session);
            watchdog.stopWatch(session.getId());
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    /**
     * Run compatibility testing on a single already-online device/emulator (no boot, no teardown).
     * One {@link CompatVersionResult} row is produced for the device's own API level. Used by the
     * Multiple-Devices flow so each selected device gets an independent session, progress and report.
     */
    public void runOnDeviceSync(CompatSession session, File apkFile, String serial,
                                Consumer<CompatVersionResult> onUpdate) {
        try {
            session.setState(CompatSession.State.RUNNING);
            session.addLog("Starting compatibility testing on device " + serial + "…");

            ApkInfo info = apkAnalyzer.analyze(apkFile);
            String pkg = info.getPackageName();
            session.setPackageName(pkg);
            session.addLog("APK package: " + pkg);

            int api = parseInt(adb.getProp(serial, "ro.build.version.sdk"));
            String version = versionLabel(api);
            session.addLog("Device API level: " + api + " (" + version + ")");

            boolean appium = driverFactory.isAppiumReachable();
            session.addLog(appium
                    ? "Appium reachable — full UI crawl enabled."
                    : "Appium not reachable — falling back to launch + logcat crash/ANR detection.");

            CompatVersionResult vr = new CompatVersionResult(version, api);
            vr.setSerial(serial);
            session.getVersions().add(vr);
            vr.setState("EMULATOR_READY");   // device is already running
            if (onUpdate != null) onUpdate.accept(vr);

            runTestPhasesOnSerial(session, vr, serial, apkFile, info, pkg, appium, onUpdate);

            // Single-version score/status for this device.
            session.setOverallScore(vr.getScore());
            session.setOverallStatus("ENV ERROR".equals(vr.getStatus()) ? "NOT TESTED" : vr.getStatus());
            if (session.isStopRequested()) {
                session.setState(CompatSession.State.STOPPED);
                session.addLog("Compatibility testing stopped. Partial results saved.");
            } else {
                session.setState(CompatSession.State.COMPLETED);
                session.addLog("Compatibility testing complete on " + serial + ". Overall: "
                        + session.getOverallStatus() + " (" + vr.getScore() + "/100).");
            }
        } catch (Exception e) {
            log.error("On-device compatibility error for {} on {}: {}", session.getId(), serial, e.getMessage(), e);
            if (session.getError() == null || session.getError().isBlank()) session.setError("Device error: " + e.getMessage());
            session.setState(session.isStopRequested() ? CompatSession.State.STOPPED : CompatSession.State.FAILED);
            session.addLog((session.getState() == CompatSession.State.STOPPED ? "STOPPED: " : "FAILED: ") + session.getError());
        } finally {
            session.setFinishedAt(System.currentTimeMillis());
        }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private List<Integer> parseApis(String csv) {
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try { out.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private String tailLine(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private interface ThrowingSupplier { String get() throws Exception; }
    private String safe(ThrowingSupplier s) {
        try { String v = s.get(); return v == null ? "" : v; } catch (Exception e) { return ""; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
