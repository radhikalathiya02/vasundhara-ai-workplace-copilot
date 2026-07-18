package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.report.RunBridgeService;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sequences a Smart Execution run: for each selected category, in order, reset the app to a
 * clean state, launch, run that category's behaviour, capture findings/evidence, then move to the
 * next category. Entirely independent of {@code engine.TestOrchestrator}.
 */
@Service
public class SmartOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SmartOrchestrator.class);

    /** Canonical execution order, matching the product spec exactly. */
    public static final List<String> CATEGORY_ORDER =
            List.of("functional", "uiux", "monkey", "ads", "regression", "network");
    public static final java.util.Map<String, String> CATEGORY_LABEL = java.util.Map.of(
            "functional", "Functional testing",
            "uiux", "UI quality and accessibility",
            "monkey", "Monkey testing",
            "ads", "AdMob / Firebase ad testing",
            "regression", "Regression testing",
            "network", "Online/offline views testing");

    private final AtfProperties props;
    private final ApkAnalyzer apkAnalyzer;
    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final SmartOcrEngine ocr;
    private final SmartVisionClient vision;
    private final SmartSessionStore store;
    private final RunBridgeService runBridge;

    public SmartOrchestrator(AtfProperties props, ApkAnalyzer apkAnalyzer, AdbClient adb,
                             DeviceManager deviceManager, DriverFactory driverFactory,
                             SmartOcrEngine ocr, SmartVisionClient vision,
                             SmartSessionStore store, RunBridgeService runBridge) {
        this.props = props;
        this.apkAnalyzer = apkAnalyzer;
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.ocr = ocr;
        this.vision = vision;
        this.store = store;
        this.runBridge = runBridge;
    }

    public void run(SmartSession session, File apkFile) {
        session.setState(SmartSession.State.ANALYZING);
        session.addStep("Analyzing APK…");
        ApkInfo apk;
        try {
            apk = apkAnalyzer.analyze(apkFile);
        } catch (Exception e) {
            fail(session, "Could not analyze APK: " + e.getMessage());
            return;
        }
        session.setPackageName(apk.getPackageName());
        session.setAppLabel(apk.getApplicationLabel() != null ? apk.getApplicationLabel() : apk.getPackageName());
        session.setApkReport(SmartApkReportBuilder.build(apk));
        session.addStep("APK analyzed — " + apk.getPackageName()
                + (apk.getVersionName() != null ? " v" + apk.getVersionName() : ""));

        Optional<String> serialOpt = deviceManager.selectDevice(session.getDeviceSerial());
        if (serialOpt.isEmpty()) { fail(session, "No online device or emulator found."); return; }
        String serial = serialOpt.get();
        session.setDeviceSerial(serial);
        session.addStep("Device selected: " + serial);

        session.setState(SmartSession.State.INSTALLING);
        var install = adb.install(serial, apkFile, apk.getPackageName());
        if (!install.combined().contains("Success")) { fail(session, "APK install failed: " + install.combined()); return; }
        session.addStep("APK installed on " + serial);

        File runDir = new File(props.getWorkDir(), "smartexec-" + session.getId());
        runDir.mkdirs();

        session.setState(SmartSession.State.RUNNING);
        session.setStartedAt(System.currentTimeMillis());

        List<String> categories = CATEGORY_ORDER.stream().filter(session.getSelectedCategories()::contains).toList();
        for (String c : categories) session.setCategoryStatus(c, "PENDING");
        store.save(session);

        SmartNavigationGraph graph = new SmartNavigationGraph();
        Set<String> dedupeKeys = new HashSet<>();
        List<Long> categoryDurations = new ArrayList<>();

        for (String category : categories) {
            if (session.isStopRequested()) { session.setCategoryStatus(category, "SKIPPED"); continue; }
            long t0 = System.currentTimeMillis();
            session.setCurrentCategory(category);
            session.setCategoryStatus(category, "RUNNING");
            session.addStep("── " + CATEGORY_LABEL.getOrDefault(category, category) + " — starting");
            store.save(session);

            // 1) Reset to a clean state, 2) relaunch — every category starts fresh.
            adb.forceStop(serial, apk.getPackageName());
            adb.clearAppData(serial, apk.getPackageName());
            adb.clearLogcat(serial);
            sleep(600);

            ScheduledFuture<?> guard = startForegroundGuard(serial, apk.getPackageName());
            try {
                runCategory(category, session, apk, serial, runDir, graph, dedupeKeys);
            } catch (Exception e) {
                log.warn("Smart Execution category {} errored: {}", category, e.toString());
                session.addStep(CATEGORY_LABEL.getOrDefault(category, category) + " — error: " + e.getMessage());
            } finally {
                guard.cancel(true);
            }

            long dur = System.currentTimeMillis() - t0;
            categoryDurations.add(dur);
            session.setCategoryDuration(category, dur);
            session.setCategoryStatus(category, "COMPLETED");
            SmartCoverageEngine.apply(session, graph);
            store.save(session);
            session.addStep(CATEGORY_LABEL.getOrDefault(category, category) + " — completed in " + (dur / 1000) + "s");
        }

        adb.forceStop(serial, apk.getPackageName());
        session.setCurrentCategory("");
        if (session.isStopRequested()) {
            session.setState(SmartSession.State.CANCELLED);
        } else {
            session.setState(SmartSession.State.COMPLETED);
        }
        session.setFinishedAt(System.currentTimeMillis());
        session.addStep(graph.summary());
        store.save(session);
    }

    private void runCategory(String category, SmartSession session, ApkInfo apk, String serial, File runDir,
                             SmartNavigationGraph graph, Set<String> dedupeKeys) throws Exception {
        String pkg = apk.getPackageName();
        if ("monkey".equals(category)) {
            runMonkey(session, apk, serial, dedupeKeys);
            return;
        }

        adb.launchAndWaitForeground(serial, pkg);
        sleep(1500);
        // Pre-flight ground-truth check: confirm the app ACTUALLY reached the foreground (via ADB
        // dumpsys, not an Appium-level call — see SmartCrawler's own foreground check for why)
        // before starting the crawl, with bounded retries for a slow cold start (large APK). Without
        // this, a launch that silently failed to take focus (verified live: the device sat on the
        // home launcher for a whole category) would otherwise have the crawler "test" whatever
        // surface is actually in front — the launcher, or a leftover app — and misreport it as
        // complete, real coverage of the uploaded APK.
        for (int attempt = 0; attempt < 4; attempt++) {
            String fg = adb.currentForegroundPackage(serial);
            if (pkg.equals(fg)) break;
            session.addStep(CATEGORY_LABEL.getOrDefault(category, category) + " — app did not reach the "
                    + "foreground yet (currently '" + fg + "'); relaunching (attempt " + (attempt + 1) + "/4)…");
            adb.forceStop(serial, pkg);
            if (fg != null && !fg.isBlank() && SmartForeignAppPolicy.shouldForceStopForeign(fg, pkg)) {
                try { adb.forceStop(serial, fg); } catch (Exception ignored) {}
            }
            adb.pressHome(serial);
            sleep(500);
            adb.launchAndWaitForeground(serial, pkg);
            sleep(2000 + attempt * 1000L); // progressively longer settle for a slow cold start
        }
        if (!pkg.equals(adb.currentForegroundPackage(serial))) {
            session.addStep(CATEGORY_LABEL.getOrDefault(category, category)
                    + " — app never reached the foreground after 4 attempts; skipping this category's crawl.");
            // This is a real, developer-actionable defect (repeated crash-on-launch or failure to
            // start), not a framework limitation — report it rather than silently skipping. Evidence
            // is the crash buffer if a genuine crash is present there.
            String crashBuf = safeCrashBuffer(serial);
            boolean genuineCrash = crashBuf != null && !crashBuf.isBlank();
            SmartFinding launchFail = withEvidence(session, serial, runDir, "crash", "CRITICAL", "App Launch",
                    "Application startup",
                    List.of("Install and launch " + pkg, "Wait for the app to reach the foreground"),
                    "The app should launch and reach the foreground within a few seconds.",
                    "The app did not reach the foreground after 4 launch attempts" + (genuineCrash ? " and the crash log shows it terminating on startup." : "."),
                    genuineCrash ? excerpt(crashBuf) : null);
            List<SmartFinding> accepted = SmartBugValidator.validate(List.of(launchFail), dedupeKeys);
            for (SmartFinding f : accepted) session.addFinding(f);
            return;
        }
        if (!driverFactory.isAppiumReachable()) {
            session.addStep(CATEGORY_LABEL.get(category) + " — Appium not reachable, skipping interactive crawl.");
            return;
        }
        // Track the app's OWN process id(s) throughout this category so log-based findings can be
        // positively attributed to it — `adb logcat` returns the ENTIRE device's log, and without
        // this a crash/error from a completely different app or system process (verified live: a
        // UiAutomationService crash from the test-automation infrastructure itself) would otherwise
        // be misattributed as a bug in the app under test.
        Set<String> ownPids = new HashSet<>();
        capturePid(serial, pkg, ownPids);
        AndroidDriver driver = driverFactory.create(serial, apk);
        try {
            boolean allowAds = "ads".equals(category);
            boolean uiChecks = "uiux".equals(category);
            int maxSteps = "network".equals(category) ? Math.max(40, props.getCrawlMaxSteps() / 3) : props.getCrawlMaxSteps();

            if ("network".equals(category)) {
                adb.setWifi(serial, false);
                sleep(1000);
            }
            try {
                SmartCrawler crawler = new SmartCrawler();
                SmartCrawler.Params params = new SmartCrawler.Params(adb, driver, serial, pkg, session.getAppLabel(),
                        runDir, maxSteps, allowAds, uiChecks, ocr, vision, session, graph, session::isStopRequested);
                SmartCrawler.Result result = crawler.explore(params);
                List<SmartFinding> raw = new ArrayList<>(crawler.findings());

                if ("network".equals(category)) {
                    adb.setWifi(serial, true);
                    sleep(1500);
                    adb.launchApp(serial, pkg);
                    sleep(1500);
                    SmartCrawler crawler2 = new SmartCrawler();
                    SmartCrawler.Params p2 = new SmartCrawler.Params(adb, driver, serial, pkg, session.getAppLabel(),
                            runDir, maxSteps, false, false, ocr, vision, session, graph, session::isStopRequested);
                    crawler2.explore(p2);
                    raw.addAll(crawler2.findings());
                }

                capturePid(serial, pkg, ownPids); // catch any relaunch (crash-recovery / network toggle) pid change
                String logcat = adb.dumpLogcat(serial);
                for (SmartLogRuleEngine.Hit h : SmartLogRuleEngine.analyze(logcat, pkg, ownPids)) {
                    String cat = h.severity().equals("CRITICAL") || h.title().toLowerCase().contains("crash")
                            || h.title().toLowerCase().contains("anr") ? "crash" : category;
                    raw.add(withEvidence(session, serial, runDir, cat, h.severity(), "App", h.title(),
                            List.of("Run " + CATEGORY_LABEL.getOrDefault(category, category)), "No runtime defect.",
                            h.detail(), h.snippet()));
                }

                if ("regression".equals(category)) {
                    SmartSession baseline = store.findBaseline(pkg, session.getId());
                    if (baseline == null) {
                        session.addStep("Regression — no prior completed run for this package; this run becomes the baseline.");
                    } else {
                        long prevCrit = baseline.getFindings().stream().filter(f -> "CRITICAL".equals(f.severity())).count();
                        long curCrit = raw.stream().filter(f -> "CRITICAL".equals(f.severity())).count();
                        if (curCrit > prevCrit) {
                            raw.add(finding("regression", "HIGH", "App", "Regression",
                                    "New critical findings vs. baseline",
                                    List.of("Compare this run's findings to the previous completed run for " + pkg),
                                    "Critical-finding count should not increase vs. baseline (" + prevCrit + ").",
                                    "This run has " + curCrit + " critical finding(s).", null));
                        }
                    }
                }

                List<SmartFinding> accepted = SmartBugValidator.validate(raw, dedupeKeys);
                for (SmartFinding f : accepted) session.addFinding(f);
                session.addStep(CATEGORY_LABEL.getOrDefault(category, category) + " — " + result.screensFound()
                        + " screen(s), " + result.actionsPerformed() + " action(s), " + accepted.size() + " finding(s).");
            } finally {
                if ("network".equals(category)) { try { adb.setWifi(serial, true); } catch (Exception ignored) {} }
            }
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    private void runMonkey(SmartSession session, ApkInfo apk, String serial, Set<String> dedupeKeys) {
        String pkg = apk.getPackageName();
        adb.launchApp(serial, pkg);
        sleep(1200);
        adb.clearLogcat(serial);
        var result = adb.monkey(serial, pkg, 300, System.currentTimeMillis() % 100000, 50);
        String out = result.combined();
        List<SmartFinding> raw = new ArrayList<>();
        if (out.contains("CRASH") || out.contains("Exception")) {
            raw.add(withEvidence(session, serial, null, "crash", "CRITICAL", "App", "Monkey testing",
                    List.of("Run adb monkey with 300 random events against " + pkg),
                    "App should tolerate randomized input without crashing.",
                    "Monkey run reported a crash/exception.", excerpt(out)));
        }
        if (out.contains("NOT RESPONDING") || out.contains("ANR")) {
            raw.add(finding("crash", "HIGH", "App", "Monkey testing", "ANR during monkey stress",
                    List.of("Run adb monkey with 300 random events against " + pkg),
                    "App should remain responsive under randomized input.", "Monkey run reported an ANR.", null));
        }
        List<SmartFinding> accepted = SmartBugValidator.validate(raw, dedupeKeys);
        for (SmartFinding f : accepted) session.addFinding(f);
        session.addStep("Monkey testing — 300 events, " + accepted.size() + " finding(s).");
        adb.forceStop(serial, pkg);
    }

    // ── evidence-on-critical-only capture ────────────────────────────────────

    private SmartFinding withEvidence(SmartSession session, String serial, File runDir, String category, String severity,
                                      String screenName, String feature, List<String> steps, String expected,
                                      String actual, String logsExcerpt) {
        String screenshotPath = null, videoPath = null;
        boolean critical = "CRITICAL".equals(severity) || "HIGH".equals(severity);
        if (critical && runDir != null) {
            try {
                byte[] png = adb.screencapPng(serial);
                if (png != null && png.length > 0) {
                    File dir = new File(runDir, "evidence"); dir.mkdirs();
                    String name = "finding-" + System.currentTimeMillis() + ".png";
                    java.nio.file.Files.write(new File(dir, name).toPath(), png);
                    screenshotPath = name;
                }
                if ("CRITICAL".equals(severity)) {
                    SmartScreenRecorder recorder = new SmartScreenRecorder(adb, serial);
                    recorder.startOnFinding();
                    videoPath = recorder.stopAndPull(new File(runDir, "evidence"), "finding-" + System.currentTimeMillis());
                }
            } catch (Exception ignored) {}
        }
        return new SmartFinding(java.util.UUID.randomUUID().toString(), category, severity,
                SmartFinding.priorityFor(severity), screenName, feature, feature, steps, expected, actual,
                screenshotPath, videoPath, logsExcerpt, System.currentTimeMillis(),
                SmartFinding.keyOf(screenName, feature, feature));
    }

    private SmartFinding finding(String category, String severity, String screenName, String feature, String title,
                                 List<String> steps, String expected, String actual, String logs) {
        return new SmartFinding(java.util.UUID.randomUUID().toString(), category, severity,
                SmartFinding.priorityFor(severity), screenName, feature, title, steps, expected, actual,
                null, null, logs, System.currentTimeMillis(), SmartFinding.keyOf(screenName, feature, title));
    }

    private static String excerpt(String s) { return s == null ? "" : (s.length() > 1500 ? s.substring(0, 1500) + "…" : s); }

    private String safeCrashBuffer(String serial) {
        try { return adb.dumpCrashBuffer(serial); } catch (Exception e) { return null; }
    }

    /** Adds the app's current live process id(s) (if any) to {@code ownPids}, for log attribution. */
    private void capturePid(String serial, String pkg, Set<String> ownPids) {
        try {
            String out = adb.shellStr(serial, "pidof " + pkg);
            if (out == null) return;
            for (String tok : out.trim().split("\\s+")) if (!tok.isBlank()) ownPids.add(tok.trim());
        } catch (Exception ignored) {}
    }

    // ── continuous foreground guard (defence-in-depth; SmartCrawler already checks per-step) ────

    private final ScheduledExecutorService guardExec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "smartexec-foreground-guard"); t.setDaemon(true); return t;
    });

    private ScheduledFuture<?> startForegroundGuard(String serial, String pkg) {
        return guardExec.scheduleWithFixedDelay(() -> {
            try {
                String fg = adb.currentForegroundPackage(serial);
                if (SmartForeignAppPolicy.shouldForceStopForeign(fg, pkg)) {
                    adb.forceStop(serial, fg);
                    // HOME before the crawler's own recovery relaunches — verified live: without
                    // this, force-stopping the foreign app can leave Android free to resume
                    // whatever task sits behind it in the recents stack (e.g. a previously-tested
                    // app, or one triggered by an OEM background service/notification) instead of
                    // the home screen, silently landing back in a stale unrelated app.
                    adb.pressHome(serial);
                }
            } catch (Exception ignored) {}
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void fail(SmartSession session, String message) {
        session.setError(message);
        session.setState(SmartSession.State.FAILED);
        session.addStep("FAILED: " + message);
        store.save(session);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
