package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.report.RunBridgeService;
import com.vasundhara.atf.smartexec.figma.FigmaComparisonRunner;
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
            List.of("functional", "uiux", "monkey", "ads", "regression", "network", "security", "performance");
    public static final java.util.Map<String, String> CATEGORY_LABEL = new java.util.LinkedHashMap<>();
    static {
        CATEGORY_LABEL.put("functional", "Functional testing");
        CATEGORY_LABEL.put("uiux", "UI quality and accessibility");
        CATEGORY_LABEL.put("monkey", "Monkey testing");
        CATEGORY_LABEL.put("ads", "AdMob / Firebase ad testing");
        CATEGORY_LABEL.put("regression", "Regression testing");
        CATEGORY_LABEL.put("network", "Online/offline views testing");
        CATEGORY_LABEL.put("security", "Security testing");
        CATEGORY_LABEL.put("performance", "Performance testing");
    }

    private final AtfProperties props;
    private final ApkAnalyzer apkAnalyzer;
    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final SmartOcrEngine ocr;
    private final SmartVisionClient vision;
    private final SmartSessionStore store;
    private final RunBridgeService runBridge;
    private final FigmaComparisonRunner figmaRunner;

    public SmartOrchestrator(AtfProperties props, ApkAnalyzer apkAnalyzer, AdbClient adb,
                             DeviceManager deviceManager, DriverFactory driverFactory,
                             SmartOcrEngine ocr, SmartVisionClient vision,
                             SmartSessionStore store, RunBridgeService runBridge,
                             FigmaComparisonRunner figmaRunner) {
        this.props = props;
        this.apkAnalyzer = apkAnalyzer;
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.ocr = ocr;
        this.vision = vision;
        this.store = store;
        this.runBridge = runBridge;
        this.figmaRunner = figmaRunner;
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
                runCategory(category, session, apk, serial, runDir, graph, dedupeKeys, apkFile);
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

        if (!session.isStopRequested() && session.getFigmaUrl() != null && !session.getFigmaUrl().isBlank()) {
            try {
                figmaRunner.run(session, runDir, session.getScreenShots(), ocr);
            } catch (Exception e) {
                log.warn("Figma comparison errored: {}", e.toString());
                session.addStep("Figma comparison — error: " + e.getMessage());
            }
            store.save(session);
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
                             SmartNavigationGraph graph, Set<String> dedupeKeys, File apkFile) throws Exception {
        String pkg = apk.getPackageName();
        if ("monkey".equals(category)) {
            runMonkey(session, apk, serial, runDir, graph, dedupeKeys);
            return;
        }
        if ("security".equals(category)) {
            runSecurity(session, apk, serial, apkFile, dedupeKeys);
            return;
        }
        if ("performance".equals(category)) {
            runPerformance(session, apk, serial, apkFile, dedupeKeys);
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
                mergeScreenshots(session, crawler.screenshotFiles());

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
                    mergeScreenshots(session, crawler2.screenshotFiles());
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

    // Monkey testing now runs through the same accessibility-tree crawler as every other category
    // (SmartCrawler.exploreMonkey) instead of shelling out to the native `adb shell monkey` tool —
    // that tool is a blind coordinate-random event injector with no concept of screens or widgets,
    // so it couldn't spread interactions across the app, couldn't avoid purchase/logout/delete-
    // account controls, and only ever produced two generic findings from grepping its own console
    // output. The crawler-based version explores real screens, performs randomized taps/long-
    // presses/swipes/scrolls/back/text-input weighted across them, applies the same foreign-app and
    // sensitive-action safeguards as the rest of Smart Execution, and reports evidenced, deduped
    // findings (including UI-freeze detection) exactly like every other category.
    private void runMonkey(SmartSession session, ApkInfo apk, String serial, File runDir,
                          SmartNavigationGraph graph, Set<String> dedupeKeys) throws Exception {
        String pkg = apk.getPackageName();
        adb.launchAndWaitForeground(serial, pkg);
        sleep(1500);
        for (int attempt = 0; attempt < 4; attempt++) {
            String fg = adb.currentForegroundPackage(serial);
            if (pkg.equals(fg)) break;
            session.addStep("Monkey testing — app did not reach the foreground yet (currently '" + fg + "'); relaunching (attempt " + (attempt + 1) + "/4)…");
            adb.forceStop(serial, pkg);
            if (fg != null && !fg.isBlank() && SmartForeignAppPolicy.shouldForceStopForeign(fg, pkg)) {
                try { adb.forceStop(serial, fg); } catch (Exception ignored) {}
            }
            adb.pressHome(serial);
            sleep(500);
            adb.launchAndWaitForeground(serial, pkg);
            sleep(2000 + attempt * 1000L);
        }
        if (!pkg.equals(adb.currentForegroundPackage(serial))) {
            session.addStep("Monkey testing — app never reached the foreground after 4 attempts; skipping.");
            String crashBuf = safeCrashBuffer(serial);
            boolean genuineCrash = crashBuf != null && !crashBuf.isBlank();
            SmartFinding launchFail = withEvidence(session, serial, runDir, "crash", "CRITICAL", "App Launch",
                    "Application startup", List.of("Install and launch " + pkg, "Wait for the app to reach the foreground"),
                    "The app should launch and reach the foreground within a few seconds.",
                    "The app did not reach the foreground after 4 launch attempts" + (genuineCrash ? " and the crash log shows it terminating on startup." : "."),
                    genuineCrash ? excerpt(crashBuf) : null);
            List<SmartFinding> accepted = SmartBugValidator.validate(List.of(launchFail), dedupeKeys);
            for (SmartFinding f : accepted) session.addFinding(f);
            return;
        }
        if (!driverFactory.isAppiumReachable()) {
            session.addStep("Monkey testing — Appium not reachable, skipping.");
            return;
        }
        Set<String> ownPids = new HashSet<>();
        capturePid(serial, pkg, ownPids);
        adb.clearLogcat(serial);
        AndroidDriver driver = driverFactory.create(serial, apk);
        try {
            SmartCrawler crawler = new SmartCrawler();
            SmartCrawler.Params params = new SmartCrawler.Params(adb, driver, serial, pkg, session.getAppLabel(),
                    runDir, props.getMonkeyEvents(), false, false, ocr, vision, session, graph, session::isStopRequested);
            SmartCrawler.Result result = crawler.exploreMonkey(params);
            List<SmartFinding> raw = new ArrayList<>(crawler.findings());

            capturePid(serial, pkg, ownPids); // catch any relaunch (crash-recovery) pid change
            String logcat = adb.dumpLogcat(serial);
            for (SmartLogRuleEngine.Hit h : SmartLogRuleEngine.analyze(logcat, pkg, ownPids)) {
                raw.add(withEvidence(session, serial, runDir, "crash", h.severity(), "App", h.title(),
                        List.of("Run Monkey testing (randomized taps/swipes/long-presses/scrolls/back/text input)"),
                        "App should tolerate randomized input without crashing or ANRs.", h.detail(), h.snippet()));
            }

            List<SmartFinding> accepted = SmartBugValidator.validate(raw, dedupeKeys);
            for (SmartFinding f : accepted) session.addFinding(f);
            session.addStep("Monkey testing — " + result.screensFound() + " screen(s), " + result.actionsPerformed()
                    + " action(s), " + accepted.size() + " finding(s).");
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
        adb.forceStop(serial, pkg);
    }

    // Security Testing — mostly static (APK manifest/bytecode analysis, no device interaction
    // needed at all for most checks) plus a short best-effort runtime slice (logcat plaintext-
    // secret scanning, and a private-data-directory inspection that only works on a debuggable
    // build — see SecurityScanner's own doc comments for exactly why each check is scoped as it is).
    private void runSecurity(SmartSession session, ApkInfo apk, String serial, File apkFile, Set<String> dedupeKeys) {
        String pkg = apk.getPackageName();
        session.addStep("Security testing — running static APK analysis…");
        List<SmartFinding> raw = new ArrayList<>();
        raw.addAll(com.vasundhara.atf.smartexec.security.SecurityScanner.scanApkInfo(apk));
        raw.addAll(com.vasundhara.atf.smartexec.security.SecurityScanner.scanApkBytes(apkFile));
        raw.addAll(com.vasundhara.atf.smartexec.security.SecurityScanner.scanOverlayRisk(apk));
        raw.addAll(com.vasundhara.atf.smartexec.security.SecurityScanner.scanThirdPartySdks(apk));
        session.addStep("Security testing — static analysis found " + raw.size() + " candidate finding(s); running device checks…");

        try {
            adb.launchApp(serial, pkg);
            sleep(1500);
            adb.clearLogcat(serial);
            sleep(2000); // let the app run briefly so any early plaintext logging surfaces
            String logcat = adb.dumpLogcat(serial);
            raw.addAll(com.vasundhara.atf.smartexec.security.SecurityScanner.scanLogcatForSecrets(logcat));
            raw.addAll(com.vasundhara.atf.smartexec.security.SecurityScanner.scanDeviceStorage(adb, serial, pkg, apk.isDebuggable()));
        } catch (Exception e) {
            session.addStep("Security testing — device checks skipped: " + e.getMessage());
        } finally {
            adb.forceStop(serial, pkg);
        }

        List<SmartFinding> accepted = SmartBugValidator.validate(raw, dedupeKeys);
        for (SmartFinding f : accepted) session.addFinding(f);
        session.addStep("Security testing — " + accepted.size() + " finding(s).");
    }

    // Performance Testing — built entirely on standard Android platform instrumentation
    // (`am start -W`, `dumpsys meminfo/gfxinfo`, logcat) so it's generic across any runtime
    // (native/Compose/Flutter/RN/Xamarin/WebView); no hardcoded package/activity/screen names.
    // Findings from this category's OWN logcat capture are tagged category="performance" (not the
    // shared "crash" bucket every other category routes ANR/FATAL hits to) — deliberate, since the
    // product spec asks for ANR/jank/restart visibility inside the Performance report specifically;
    // this doesn't remove anything from other categories' own crash detection.
    private void runPerformance(SmartSession session, ApkInfo apk, String serial, File apkFile, Set<String> dedupeKeys) {
        String pkg = apk.getPackageName();
        List<SmartFinding> raw = new ArrayList<>();
        String activity = null;
        try { activity = adb.resolveLauncherActivity(serial, pkg); } catch (Exception ignored) {}
        boolean haveActivity = activity != null && !activity.isBlank();

        // ── App Launch Performance + Background Behavior ──
        session.addStep("Performance testing — measuring launch times…");
        long coldMs = -1, warmMs = -1, hotMs = -1, backgroundResumeMs = -1;
        try {
            adb.forceStop(serial, pkg);
            sleep(500);
            coldMs = haveActivity ? adb.launchActivityTimed(serial, pkg, activity) : timeFallbackLaunch(serial, pkg);
            sleep(1500);

            adb.pressHome(serial);
            sleep(1200);
            warmMs = haveActivity ? adb.launchActivityTimed(serial, pkg, activity) : timeFallbackLaunch(serial, pkg);
            sleep(1000);

            long bgStart = System.currentTimeMillis();
            adb.pressHome(serial);
            sleep(300);
            hotMs = haveActivity ? adb.launchActivityTimed(serial, pkg, activity) : timeFallbackLaunch(serial, pkg);
            backgroundResumeMs = System.currentTimeMillis() - bgStart;
            sleep(1000);
        } catch (Exception e) {
            session.addStep("Performance testing — launch timing error: " + e.getMessage());
        }
        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanLaunch(
                new com.vasundhara.atf.smartexec.performance.PerformanceScanner.LaunchTimes(coldMs, warmMs, hotMs)));
        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanBackgroundResume(backgroundResumeMs));

        // ── Resource Usage / Rendering / Stability / Stress — one bounded interaction+stress loop,
        // sampling memory each step and finishing with a gfxinfo (jank) snapshot ──
        session.addStep("Performance testing — sampling resource usage and stress-testing…");
        Set<String> ownPidsBefore = new HashSet<>();
        capturePid(serial, pkg, ownPidsBefore);
        try { adb.resetGfxinfo(serial, pkg); } catch (Exception ignored) {}
        adb.clearLogcat(serial);

        List<Long> pssSamples = new ArrayList<>();
        long foregroundPss = -1;
        int actions = 0;
        boolean frozen = false;
        int freezeStreak = 0;
        long lastCrc = -1;
        int[] size = null;
        try { size = adb.screenSize(serial); } catch (Exception ignored) {}
        int sw = size != null && size.length >= 2 ? size[0] : 1080;
        int sh = size != null && size.length >= 2 ? size[1] : 1920;
        java.util.Random rnd = new java.util.Random();
        int stressSteps = Math.min(40, Math.max(12, props.getMonkeyEvents() / 30));

        for (int i = 0; i < stressSteps; i++) {
            if (session.isStopRequested()) break;
            try {
                if (rnd.nextInt(3) == 0) {
                    adb.swipe(serial, sw / 2, (int) (sh * 0.75), sw / 2, (int) (sh * 0.25), 250);
                } else {
                    adb.tap(serial, 40 + rnd.nextInt(Math.max(1, sw - 80)), 60 + rnd.nextInt(Math.max(1, sh - 120)));
                }
                actions++;
                sleep(350);

                String meminfoDump = adb.meminfo(serial, pkg);
                long pss = com.vasundhara.atf.smartexec.performance.PerformanceScanner.parseTotalPssKb(meminfoDump);
                if (pss > 0) { pssSamples.add(pss); if (foregroundPss < 0) foregroundPss = pss; }

                byte[] shot = adb.screencapPng(serial);
                if (shot != null && shot.length > 0) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(shot);
                    long h = crc.getValue();
                    if (h == lastCrc) freezeStreak++; else freezeStreak = 0;
                    lastCrc = h;
                    if (freezeStreak >= 8) { frozen = true; break; }
                }
            } catch (Exception ignored) {}
        }

        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanMemory(pssSamples));
        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanFreeze(frozen, freezeStreak));

        Set<String> ownPidsAfter = new HashSet<>();
        capturePid(serial, pkg, ownPidsAfter);
        boolean pidChanged = !ownPidsBefore.isEmpty() && !ownPidsAfter.isEmpty() && !ownPidsBefore.equals(ownPidsAfter);
        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanUnexpectedRestart(pidChanged, pidChanged ? "pid changed from " + ownPidsBefore + " to " + ownPidsAfter : null));

        try {
            String gfxDump = adb.gfxinfo(serial, pkg);
            var gfx = com.vasundhara.atf.smartexec.performance.PerformanceScanner.parseGfxinfo(gfxDump);
            boolean isFlutter = zipEntryExists(apkFile, "assets/flutter_assets/");
            // WebView usage has no distinctive bundled asset the way Flutter does (it's a plain
            // framework class) — a reliable check needs the same dex-string scan Security Testing
            // already performs; left false here to avoid a misleading heuristic. Run Security
            // Testing alongside Performance for WebView-specific findings.
            raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanRendering(gfx, false, isFlutter));
        } catch (Exception ignored) {}

        // Background-service impact: brief background sample vs. the foreground samples above.
        try {
            adb.pressHome(serial);
            sleep(3000);
            long bgPss = com.vasundhara.atf.smartexec.performance.PerformanceScanner.parseTotalPssKb(adb.meminfo(serial, pkg));
            raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanBackgroundServiceImpact(foregroundPss, bgPss));
        } catch (Exception ignored) {}

        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanStorageAndBattery(apk.getApkSizeBytes() / (1024 * 1024)));
        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanNetworkNote());
        raw.addAll(com.vasundhara.atf.smartexec.performance.PerformanceScanner.scanStressSummary(actions, 1, !frozen && !pidChanged));

        // ANR/jank/crash signal from this category's own logcat window — tagged "performance" (see
        // class-level comment above) so it surfaces in this run's Performance report.
        try {
            String logcat = adb.dumpLogcat(serial);
            for (SmartLogRuleEngine.Hit h : SmartLogRuleEngine.analyze(logcat, pkg, ownPidsAfter.isEmpty() ? ownPidsBefore : ownPidsAfter)) {
                raw.add(new SmartFinding(java.util.UUID.randomUUID().toString(), "performance", h.severity(),
                        SmartFinding.priorityFor(h.severity()), "Stability", h.title(), h.title(),
                        List.of("Run Performance testing (launch timing + stress interaction)"),
                        "App should not crash/ANR/jank under normal + stress interaction.", h.detail(),
                        null, null, h.snippet(), System.currentTimeMillis(), SmartFinding.keyOf("Stability", h.title(), h.title())));
            }
        } catch (Exception ignored) {}

        adb.forceStop(serial, pkg);

        List<SmartFinding> accepted = SmartBugValidator.validate(raw, dedupeKeys);
        for (SmartFinding f : accepted) session.addFinding(f);
        session.addStep("Performance testing — " + actions + " stress action(s), " + accepted.size() + " finding(s).");
    }

    private boolean zipEntryExists(File apkFile, String prefix) {
        try (var zip = new java.util.zip.ZipFile(apkFile)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith(prefix)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Best-effort launch timing when the launcher activity can't be resolved by name — falls back
     *  to wall-clock time until the package's own PID appears, a generic (if slightly coarser) proxy. */
    private long timeFallbackLaunch(String serial, String pkg) {
        long t0 = System.currentTimeMillis();
        try { adb.launchApp(serial, pkg); } catch (Exception ignored) {}
        long deadline = t0 + 8000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String fg = adb.currentForegroundPackage(serial);
                if (fg != null && fg.contains(pkg)) return System.currentTimeMillis() - t0;
            } catch (Exception ignored) {}
            sleep(150);
        }
        return -1;
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

    /** First-seen-wins merge of a crawler's per-screen screenshots into the session's cumulative
     *  map (screens get re-discovered across categories; the earliest capture is kept). */
    private void mergeScreenshots(SmartSession session, java.util.Map<String, String> fromCrawler) {
        if (fromCrawler == null || fromCrawler.isEmpty()) return;
        for (var e : fromCrawler.entrySet()) session.getScreenShots().putIfAbsent(e.getKey(), e.getValue());
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
