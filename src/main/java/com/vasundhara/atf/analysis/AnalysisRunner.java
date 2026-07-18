package com.vasundhara.atf.analysis;

import com.vasundhara.atf.ai.AiScreenReviewer;
import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.engine.AppIntelligenceAnalyzer;
import com.vasundhara.atf.engine.AppIntelligenceReport;
import com.vasundhara.atf.engine.ExecutionLockService;
import com.vasundhara.atf.engine.ExplorationEngine;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.TestContext;
import com.vasundhara.atf.localization.LocalizationCrawler;
import com.vasundhara.atf.engine.ScrollReport;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.model.TestRun;
import com.vasundhara.atf.util.LogcatAnalyzer;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drives the "Analyze APK" workflow in the New Test module: static APK analysis + app
 * intelligence inference (domain, features, user journeys, seed scenarios) — optionally
 * enriched with a bounded LIVE crawl of the app on a connected device, so generated test
 * cases can reference the app's real screens/buttons/labels and actual functional flows
 * instead of generic templates — fed into a senior-QA-style {@link TestCaseGenerator} to
 * produce a full end-to-end test matrix, ending in a downloadable .xlsx.
 *
 * <p>Two crawl tiers, mirroring exactly how {@code TestOrchestrator} itself chooses between
 * them for real test execution: when Appium is reachable, the same {@link ExplorationEngine}
 * used by Functional Testing drives the crawl (deep multi-screen navigation, permission
 * handling, dead-end recovery, ad avoidance) — this is what actually finds real app
 * functionality, not just visible text. Falls back to the lighter ADB-only
 * {@link LocalizationCrawler} when Appium isn't available. Both are best-effort: no device
 * connected, or the shared execution lock already held by a real test run, fall back to
 * static-only analysis rather than failing or blocking.
 *
 * <p>Alongside the generated {@link TestCaseRow} scenarios (things a human should go verify),
 * the live crawl also captures real defects actually observed on the device — crashes, ANRs
 * and native crashes from logcat, plus structural UI issues (missing labels, small touch
 * targets, off-screen/overlapping elements) from the crawler's own scroll sweeps — into
 * {@link IssueRow}s. These are never guessed from static analysis; each one reflects something
 * that was genuinely seen to happen during the run.
 */
@Component
public class AnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);
    // "Review the entire app, even if it takes 5 minutes" — bounded by wall-clock time (the real
    // constraint the user cares about) rather than a small fixed step count, so the crawl keeps
    // going as long as it's still finding new ground, up to a generous step ceiling as a backstop.
    private static final long LIVE_CRAWL_MAX_MILLIS = 5 * 60 * 1000L;
    private static final int LIVE_CRAWL_MAX_STEPS = 600;

    private final ApkAnalyzer apkAnalyzer;
    private final AppIntelligenceAnalyzer intelligence;
    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final ExplorationEngine explorationEngine;
    private final ExecutionLockService execLock;
    private final AtfProperties props;
    private final AiScreenReviewer aiReviewer;
    private final TestCaseSheetWriter sheetWriter = new TestCaseSheetWriter();
    private final DeepLinkScanner deepLinkScanner = new DeepLinkScanner();

    private final com.vasundhara.atf.ai.LlmTestCaseGenerator llmTestCaseGenerator;

    public AnalysisRunner(ApkAnalyzer apkAnalyzer, AppIntelligenceAnalyzer intelligence, AdbClient adb,
                           DeviceManager deviceManager, DriverFactory driverFactory, ExplorationEngine explorationEngine,
                           ExecutionLockService execLock, AtfProperties props, AiScreenReviewer aiReviewer,
                           com.vasundhara.atf.ai.LlmTestCaseGenerator llmTestCaseGenerator) {
        this.apkAnalyzer = apkAnalyzer;
        this.intelligence = intelligence;
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.explorationEngine = explorationEngine;
        this.execLock = execLock;
        this.props = props;
        this.aiReviewer = aiReviewer;
        this.llmTestCaseGenerator = llmTestCaseGenerator;
    }

    @Async("testRunExecutor")
    public void run(AnalysisSession session, File apkFile) {
        try {
            stage(session, 0); // Reading APK
            sleep(300);

            stage(session, 1); // Extracting Manifest
            ApkInfo apkInfo = apkAnalyzer.analyze(apkFile);
            sleep(300);

            stage(session, 2); // Detecting Activities & Fragments
            sleep(250);

            stage(session, 3); // Identifying Screens
            stage(session, 4); // Discovering Navigation Flow
            List<IssueRow> issues = new ArrayList<>();
            ExplorationResult liveExploration = tryLiveCrawl(session, apkInfo, apkFile, issues);
            List<DeepLinkScanner.DeepLink> deepLinks = deepLinkScanner.scan(apkFile);

            stage(session, 5); // Detecting Features & Modules
            AppIntelligenceReport report = intelligence.analyze(apkInfo, liveExploration);
            sleep(200);

            stage(session, 6); // Identifying Permissions
            sleep(200);

            stage(session, 7); // Detecting Ads
            sleep(200);

            stage(session, 8); // Understanding Business Flow
            sleep(300);

            stage(session, 9); // Building Screen Flow
            sleep(200);

            stage(session, 10); // Generating Test Scenarios
            List<TestCaseRow> rows = null;
            // Preferred path: LLM writes realistic, app-specific, deduped, flow-sequenced cases
            // grounded in the observed app model. Only used when AI Review is configured AND it
            // actually returns cases; otherwise we fall back to the heuristic generator below.
            if (llmTestCaseGenerator.isEnabled()) {
                try {
                    List<String> dlStrings = deepLinks.stream()
                            .map(dl -> dl.scheme() + "://" + (dl.host() == null || dl.host().isBlank() ? "" : dl.host()))
                            .toList();
                    var ghCases = llmTestCaseGenerator.generate(apkInfo, report, dlStrings, liveExploration);
                    if (ghCases != null && !ghCases.isEmpty()) {
                        rows = new ArrayList<>();
                        for (var g : ghCases) {
                            rows.add(new TestCaseRow(g.module(), g.feature(), "", g.scenario(),
                                    "App " + apkInfo.getPackageName() + " is installed and launched.",
                                    g.steps(), g.expected(),
                                    "P1".equalsIgnoreCase(g.priority()) ? "P1" : "P3".equalsIgnoreCase(g.priority()) ? "P3" : "P2",
                                    "", g.type(), "Functional", "", "AI-generated from live app model"));
                        }
                        session.setStage("Generating Test Scenarios — AI wrote " + rows.size() + " app-specific case(s)");
                    }
                } catch (Exception e) {
                    log.debug("LLM test-case generation failed for session {}; using heuristic generator: {}",
                            session.getId(), e.toString());
                }
            }
            if (rows == null || rows.isEmpty()) {
                TestCaseGenerator generator = new TestCaseGenerator(apkInfo);
                rows = generator.generate(apkInfo, report, deepLinks, liveExploration);
            }
            byte[] xlsx = sheetWriter.write(apkInfo.getApplicationLabel(), rows, issues);

            session.setSheetBytes(xlsx);
            session.setTestCaseCount(rows.size());
            session.setIssueCount(issues.size());
            session.setAppDomain(report.getAppDomain());
            session.setPercent(100);
            session.setDone(true);
        } catch (Exception e) {
            log.warn("Analyze APK failed for session {}: {}", session.getId(), e.toString());
            session.setError("Analysis failed: " + e.getMessage());
            session.setDone(true);
        }
    }

    /**
     * Best-effort bounded live crawl. Installs the app, then prefers the real
     * {@link ExplorationEngine} (Appium) — the same crawler Functional Testing uses, capable of
     * genuinely navigating the app's business flows, not just reading whatever text is on the
     * first screen — and falls back to the lighter ADB-only {@link LocalizationCrawler} only when
     * Appium isn't reachable. Never throws: returns {@code null} on any failure so the caller
     * falls back to static-only analysis.
     */
    private ExplorationResult tryLiveCrawl(AnalysisSession session, ApkInfo apkInfo, File apkFile, List<IssueRow> issues) {
        if (!execLock.tryAcquire("New Test — Analyze APK", session.getId())) {
            log.debug("Analyze APK {}: device busy with a real test run — using static-only analysis.", session.getId());
            return null;
        }
        String serial = null;
        try {
            Optional<String> serialOpt = deviceManager.selectDevice();
            if (serialOpt.isEmpty()) return null;
            serial = serialOpt.get();
            String pkg = apkInfo.getPackageName();

            var install = adb.install(serial, apkFile, pkg);
            if (!install.combined().contains("Success")) {
                session.setStage("Discovering Navigation Flow — could not install the app for a live crawl; using static analysis");
                return null;
            }

            DeviceManager.DeviceInfo deviceInfo = deviceManager.profile(serial);
            File runDir = new File(props.getWorkDir(), "analyze-" + session.getId());
            runDir.mkdirs();

            // Launch ONLY the uploaded app and confirm it is actually in the foreground before any
            // crawling begins. Installing an APK does not launch it, so without this the crawl
            // would start on whatever happened to be on screen (the launcher, or another app) and
            // could interact with it. Force-stop first so we get a clean cold start of this app,
            // then poll the foreground package until it is the app under test. Generic — uses only
            // the uploaded package name, no hardcoded app/activity.
            adb.launchAndWaitForeground(serial, pkg);

            // Clear logcat right before the crawl starts so any crash/ANR/native-crash signal
            // captured afterward can only have come from this run, not stale history.
            try { adb.clearLogcat(serial); } catch (Exception ignored) {}

            ExplorationResult result = driverFactory.isAppiumReachable()
                    ? runAppiumCrawl(session, apkInfo, apkFile, serial, deviceInfo, runDir)
                    : runAdbCrawl(session, apkInfo, serial, deviceInfo, runDir);

            collectCrashIssues(serial, pkg, result, issues);
            collectUiIssues(result, issues);
            collectAiIssues(session, apkInfo, result, runDir, issues);
            return result;
        } catch (Exception e) {
            log.warn("Analyze APK {}: live crawl skipped due to an error", session.getId(), e);
            return null;
        } finally {
            if (serial != null) {
                try { adb.forceStop(serial, apkInfo.getPackageName()); adb.uninstall(serial, apkInfo.getPackageName()); }
                catch (Exception ignored) {}
            }
            execLock.release(session.getId());
        }
    }

    /**
     * Parses logcat for crash/ANR/native-crash/StrictMode incidents that occurred during the
     * crawl — the same {@link LogcatAnalyzer} the Negative Testing category uses — and turns
     * each one into a real, evidence-backed {@link IssueRow}. Falls back to a generic "crash
     * suspected" row if the exploration engine detected the app process died but no matching
     * logcat signal was found (e.g. a silent native abort logcat didn't capture in time).
     */
    private void collectCrashIssues(String serial, String pkg, ExplorationResult result, List<IssueRow> issues) {
        if (result == null) return;
        String lastScreen = result.getScreens().isEmpty() ? "Unknown screen"
                : result.getScreens().get(result.getScreens().size() - 1).activity();
        boolean foundCrashSignal = false;
        try {
            String logcat = adb.dumpLogcat(serial) + "\n" + adb.dumpCrashBuffer(serial);
            for (LogcatAnalyzer.Incident incident : LogcatAnalyzer.analyze(logcat)) {
                // The device logcat/crash-buffer is shared by every process — only attribute an
                // incident to this app if its captured text actually names the package, otherwise
                // a crash in some unrelated app on the shared test device gets misreported here.
                if (!incident.snippet().contains(pkg) && !incident.header().contains(pkg)) continue;
                String severity = switch (incident.kind()) {
                    case FATAL_EXCEPTION, NATIVE_CRASH -> "Critical";
                    case ANR -> "High";
                    case STRICT_MODE -> "Low";
                };
                if (incident.kind() == LogcatAnalyzer.Kind.FATAL_EXCEPTION
                        || incident.kind() == LogcatAnalyzer.Kind.NATIVE_CRASH) foundCrashSignal = true;
                issues.add(new IssueRow(lastScreen, incident.kind().name().replace('_', ' '), severity,
                        incident.header(), truncate(incident.snippet(), 800)));
            }
        } catch (Exception e) {
            log.debug("Analyze APK: logcat crash scan failed: {}", e.toString());
        }
        if (result.isCrashSuspected() && !foundCrashSignal) {
            issues.add(new IssueRow(lastScreen, "CRASH SUSPECTED", "Critical",
                    "App process disappeared during the crawl (suspected crash)",
                    "No matching logcat stack trace was captured — the process likely died faster than logcat could flush."));
        }
    }

    /**
     * Turns the crawler's own per-screen scroll-sweep findings (missing accessibility labels,
     * sub-48dp touch targets, off-screen actionable elements, overlapping controls) into
     * IssueRows. These are real, measured structural defects, not generated scenarios.
     */
    private void collectUiIssues(ExplorationResult result, List<IssueRow> issues) {
        if (result == null) return;
        for (ScrollReport r : result.getScrollReports()) {
            if (!r.hasIssues()) continue;
            String severity = (r.offScreen + r.overlaps) > 0 ? "High" : "Medium";
            StringBuilder desc = new StringBuilder("Structural UI issues found during scroll sweep: ");
            List<String> parts = new ArrayList<>();
            if (r.missingLabels > 0) parts.add(r.missingLabels + " element(s) missing an accessible label");
            if (r.smallTargets > 0) parts.add(r.smallTargets + " touch target(s) smaller than 48x48dp");
            if (r.offScreen > 0) parts.add(r.offScreen + " actionable element(s) rendered off-screen");
            if (r.overlaps > 0) parts.add(r.overlaps + " pair(s) of overlapping controls");
            desc.append(String.join("; ", parts));
            issues.add(new IssueRow(r.activity, "UI/UX", severity, desc.toString(), null));
        }
    }

    /**
     * Optional second-opinion pass: sends up to {@code atf.ai-max-screens-per-run} distinct
     * screenshots from the crawl to {@link AiScreenReviewer} for subjective defects heuristics
     * can't express (garbled content, unreadable text, broken images, misaligned layout). A
     * complete no-op when AI review isn't enabled/configured — every screenshot review failure
     * already resolves to "no issues" inside the reviewer itself.
     */
    private void collectAiIssues(AnalysisSession session, ApkInfo apkInfo, ExplorationResult result,
                                  File runDir, List<IssueRow> issues) {
        if (result == null || !aiReviewer.isEnabled() || result.getScreens().isEmpty()) return;
        String appContext = apkInfo.getApplicationLabel();
        int cap = Math.max(1, props.getAiMaxScreensPerRun());
        int reviewed = 0;
        for (ScreenCapture screen : result.getScreens()) {
            if (reviewed >= cap) break;
            if (screen.screenshotPath() == null || screen.screenshotPath().isBlank()) continue;
            File shot = new File(runDir, screen.screenshotPath());
            if (!shot.isFile()) continue;
            reviewed++;
            String screenName = simpleActivityName(screen.activity());
            session.setStage("Generating Test Scenarios — AI reviewing " + screenName + " ("
                    + reviewed + "/" + cap + ")");
            for (AiScreenReviewer.Verdict v : aiReviewer.review(shot, screenName, appContext)) {
                issues.add(new IssueRow(screenName, "AI Review", capitalize(v.severity()), v.description(), null));
            }
        }
    }

    private static String simpleActivityName(String activity) {
        if (activity == null || activity.isBlank()) return "Unknown Screen";
        String s = activity.startsWith(".") ? activity.substring(1) : activity;
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Primary tier: the same deep-navigation crawler Functional Testing itself uses. */
    private ExplorationResult runAppiumCrawl(AnalysisSession session, ApkInfo apkInfo, File apkFile,
                                              String serial, DeviceManager.DeviceInfo deviceInfo, File runDir) throws Exception {
        AndroidDriver driver = driverFactory.create(serial, apkInfo);
        // Throwaway, never persisted (no store.save) — exists only to satisfy TestContext's API
        // (cancel-checks, live-progress, execution-step log) for this one bounded analysis crawl.
        TestRun shadowRun = new TestRun(session.getId(), apkFile.getName(), List.of());
        shadowRun.setStartedAtMillis(System.currentTimeMillis());
        TestContext ctx = new TestContext(props, adb, serial, apkFile, apkInfo, runDir, shadowRun, deviceInfo);
        ctx.setDriver(driver);

        // Wall-clock backstop: ExplorationEngine only understands step-count/convergence limits,
        // so a watchdog thread requests cancellation once the 5-minute budget is spent — the
        // engine's own interrupt/cancel check (already in its loop) then stops it cleanly.
        Thread deadlineWatcher = new Thread(() -> {
            try { Thread.sleep(LIVE_CRAWL_MAX_MILLIS); shadowRun.requestCancel(); } catch (InterruptedException ignored) {}
        }, "analyze-apk-deadline-" + session.getId());
        deadlineWatcher.setDaemon(true);
        deadlineWatcher.start();

        Thread progressWatcher = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (!shadowRun.isCancelRequested()) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                int screens = shadowRun.getExecutionSteps().size();
                long elapsedSec = (System.currentTimeMillis() - start) / 1000;
                session.setStage("Discovering Navigation Flow — " + screens + " step(s) so far (" + elapsedSec + "s)");
            }
        }, "analyze-apk-progress-" + session.getId());
        progressWatcher.setDaemon(true);
        progressWatcher.start();

        try {
            return explorationEngine.explore(ctx, LIVE_CRAWL_MAX_STEPS);
        } finally {
            deadlineWatcher.interrupt();
            progressWatcher.interrupt();
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    /** Fallback tier when Appium is not reachable — lighter ADB-only crawl. */
    private ExplorationResult runAdbCrawl(AnalysisSession session, ApkInfo apkInfo, String serial,
                                           DeviceManager.DeviceInfo deviceInfo, File runDir) {
        String pkg = apkInfo.getPackageName();
        int w = deviceInfo != null ? deviceInfo.widthPx() : 0;
        int h = deviceInfo != null ? deviceInfo.heightPx() : 0;

        long deadline = System.currentTimeMillis() + LIVE_CRAWL_MAX_MILLIS;
        int[] screensFound = {0};
        LocalizationCrawler.Result cr = LocalizationCrawler.explore(
                adb, serial, pkg, runDir, LIVE_CRAWL_MAX_STEPS, w, h,
                () -> System.currentTimeMillis() >= deadline, 400L,
                msg -> {
                    if (msg != null && msg.startsWith("▶ Screen")) screensFound[0]++;
                    long elapsedSec = (LIVE_CRAWL_MAX_MILLIS - (deadline - System.currentTimeMillis())) / 1000;
                    session.setStage("Discovering Navigation Flow — " + screensFound[0]
                            + " screen(s) explored so far (" + elapsedSec + "s, ADB fallback — Appium not reachable)");
                }, true);

        ExplorationResult exploration = new ExplorationResult();
        exploration.setCrashSuspected(cr.crashSuspected());
        exploration.setLeftAppDuringRun(cr.leftApp());
        for (ScreenCapture s : cr.screens()) exploration.addScreen(s);
        for (int i = 0; i < cr.actions(); i++) exploration.incrementActions();
        return exploration;
    }

    private void stage(AnalysisSession session, int index) {
        session.advanceTo(index);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
