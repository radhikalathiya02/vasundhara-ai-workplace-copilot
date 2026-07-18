package com.vasundhara.atf.ads;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.engine.ExplorationEngine;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.localization.LocalizationCrawler;
import com.vasundhara.atf.model.ApkInfo;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;

/**
 * Drives an ad-monetisation analysis end to end.
 *
 * <p>Static analysis (Phase 1) always runs. When a device is available, Phase 2 runs in
 * one of two independent execution paths:
 * <ul>
 *   <li><b>TEST ADS mode</b> — full ad interaction: clears app data, crawls with ad clicks
 *       enabled, validates the complete lifecycle including click events and browser-return flow.
 *   <li><b>LIVE ADS mode</b> — observation only: preserves app data, crawls with ad clicks
 *       completely disabled, validates load + display + impression events only.
 * </ul>
 */
@Component
public class AdAnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AdAnalysisRunner.class);

    private final AdAnalysisService service;
    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final ExplorationEngine explorationEngine;
    private final AtfProperties props;
    private final com.vasundhara.atf.engine.ExecutionLockService execLock;
    private final com.vasundhara.atf.report.RunBridgeService runBridge;
    private final com.vasundhara.atf.device.DeviceWatchdog watchdog;
    private final AdAnalysisSessionStore sessionStore;

    public AdAnalysisRunner(AdAnalysisService service, AdbClient adb, DeviceManager deviceManager,
                            DriverFactory driverFactory, ExplorationEngine explorationEngine,
                            AtfProperties props,
                            com.vasundhara.atf.engine.ExecutionLockService execLock,
                            com.vasundhara.atf.report.RunBridgeService runBridge,
                            com.vasundhara.atf.device.DeviceWatchdog watchdog,
                            AdAnalysisSessionStore sessionStore) {
        this.service = service;
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.explorationEngine = explorationEngine;
        this.props = props;
        this.execLock = execLock;
        this.runBridge = runBridge;
        this.watchdog = watchdog;
        this.sessionStore = sessionStore;
    }

    @Async("testRunExecutor")
    public void run(AdAnalysisSession session, File apkFile) {
        runBridge.start(session.getId(), "Priority & Logs", session.getApkFileName(), null);
        AndroidDriver driver = null;
        String serial = null;
        String pkg = null;
        AdAnalysisResult result = new AdAnalysisResult();

        try {
            session.setState(AdAnalysisSession.State.ANALYZING);
            result.setAdMode(session.getAdMode());
            session.addLog("Starting ad monetisation analysis — " + session.getAdMode() + " ADS mode.");

            // ── Phase 1: static APK analysis (always) ──────────────────────
            ApkInfo info = service.staticAnalyze(apkFile, result, session::addLog);
            pkg = info.getPackageName();
            session.setPackageName(pkg);

            // ── Phase 2: dynamic capture — delegates to mode-specific path ─
            Optional<String> serialOpt = deviceManager.selectDevice();
            if (serialOpt.isPresent()) {
                serial = serialOpt.get();
                session.addLog("Device: " + serial);
                watchdog.startWatch(session.getId(), serial, () -> {
                    if (session.isStopRequested()) return; // manual stop already in flight
                    session.setError("Device disconnected during test execution.");
                    session.requestStop();
                    session.addLog("DEVICE DISCONNECTED — stopping ad analysis.");
                });
                try {
                    var install = adb.install(serial, apkFile, pkg);
                    if (!install.combined().contains("Success")) {
                        session.addLog("Install failed; skipping runtime capture: " + install.combined());
                    } else {
                        DeviceManager.DeviceInfo deviceInfo = deviceManager.profile(serial);
                        int w = deviceInfo != null ? deviceInfo.widthPx() : 0;
                        int h = deviceInfo != null ? deviceInfo.heightPx() : 0;
                        File runDir = new File(props.getWorkDir(), session.getId());
                        runDir.mkdirs();

                        if ("LIVE".equalsIgnoreCase(result.getAdMode())) {
                            executeLiveModeCapture(session, serial, pkg, result, runDir, w, h);
                        } else {
                            executeTestModeCapture(session, serial, pkg, result, runDir, w, h);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Runtime ad capture failed for {}: {}", session.getId(), e.toString());
                    session.addLog("Runtime capture error (static results still valid): " + e.getMessage());
                } finally {
                    if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} driver = null; }
                    if (serial != null && pkg != null) {
                        try { adb.forceStop(serial, pkg); adb.uninstall(serial, pkg); } catch (Exception ignored) {}
                    }
                }
            } else {
                session.addLog("No device available — producing static-only ad report.");
            }

            // ── Phase 3: issue detection + category report ─────────────────
            if (session.isStopRequested()) {
                session.setResult(result);
                session.setState(AdAnalysisSession.State.STOPPED);
                session.addLog("Analysis stopped by user. Partial results saved.");
            } else {
                service.generateReport(result, session::addLog);
                session.setResult(result);
                session.setState(AdAnalysisSession.State.COMPLETED);
                session.addLog("Analysis complete. Overall health: " + result.getOverallHealth() + ".");
            }

        } catch (Exception e) {
            log.error("AdAnalysisRunner error for session {}: {}", session.getId(), e.getMessage(), e);
            session.setError("Analysis error: " + e.getMessage());
            session.setState(AdAnalysisSession.State.FAILED);
            session.addLog("FAILED: " + e.getMessage());
            if (session.getResult() == null) session.setResult(result);
        } finally {
            watchdog.stopWatch(session.getId());
            if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} }
            session.setFinishedAt(System.currentTimeMillis());
            // Persist the FINAL session (with its full ad-analysis result) — the only earlier save
            // (in AdAnalysisController, at creation) only ever wrote the initial empty SETUP
            // snapshot, so without this the Reports module would show stale/empty data for this
            // session after any server restart even though it completed successfully.
            sessionStore.save(session);
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    // ── TEST ADS mode ──────────────────────────────────────────────────────────
    //
    // Full ad interaction: clears app data for a pristine run, crawls with ad clicks
    // enabled (skipAdClicks=false), validates the complete ad lifecycle including click
    // events, browser-open/return flow, and GMA test-device ID extraction from logcat.

    private void executeTestModeCapture(AdAnalysisSession session, String serial, String pkg,
                                        AdAnalysisResult result, File runDir, int w, int h) throws Exception {
        session.addLog("━━ TEST ADS MODE: Full ad interaction — click testing, browser return, and complete lifecycle validation.");

        // Capture the device ANDROID_ID for test-device setup reference.
        // The GMA SDK also prints the exact hash to register via setTestDeviceIds(); surfaced from logcat below.
        try {
            String aid = adb.androidId(serial);
            if (aid != null && !aid.isBlank()) {
                session.addLog("Device ANDROID_ID: " + aid
                        + " (test-device registration requires the app to call setTestDeviceIds() — cannot be injected into a prebuilt APK).");
            }
        } catch (Exception ignored) {}

        // Clear app data + logcat for a fresh start so all SDK lifecycle events from first launch are captured.
        session.addLog("Clearing app data + logcat for fresh run, then launching…");
        adb.clearAppData(serial, pkg);
        adb.clearLogcat(serial);
        adb.launchApp(serial, pkg);
        Thread.sleep(1500);

        int steps = Math.max(props.getCrawlMaxSteps(), 250);
        session.addLog("Exploring app with full ad interaction — up to " + steps + " steps, 3.5 s settle per screen for ads to load…");

        // skipAdClicks=false: tap through ad containers, validate click → browser → return flow.
        LocalizationCrawler.Result cr = LocalizationCrawler.explore(
                adb, serial, pkg, runDir, steps, w, h,
                session::isStopRequested, 3500L, session::addLog, false);

        if (session.isStopRequested()) return;
        session.addLog("Exploration complete: " + cr.screens().size() + " screen(s), " + cr.actions() + " action(s).");

        String logcat = adb.dumpLogcat(serial);

        // Surface the GMA SDK's own test-device hash so the QA team can register it.
        java.util.regex.Matcher tdm = java.util.regex.Pattern
                .compile("setTestDeviceIds\\([^)]*\"([0-9A-Fa-f]{16,})\"").matcher(logcat);
        if (tdm.find()) {
            session.addLog("AdMob test-device ID for this device: " + tdm.group(1)
                    + " — register via RequestConfiguration.setTestDeviceIds(...) or the AdMob console.");
        }

        // Parse ALL logcat events including CLICK, then correlate per placement.
        service.parseLogcat(logcat, result, session::addLog);
        service.correlatePlacements(result, logcat, toExploration(cr), session::addLog);
    }

    // ── LIVE ADS mode ─────────────────────────────────────────────────────────
    //
    // Observation only: preserves app data so real ads behave as in production (frequency
    // caps, user-state targeting), crawls with ad clicks completely disabled (skipAdClicks=true),
    // and validates only load + display + impression events.  No click events are counted.

    private void executeLiveModeCapture(AdAnalysisSession session, String serial, String pkg,
                                        AdAnalysisResult result, File runDir, int w, int h) throws Exception {
        result.setAdClicksSkipped(true);
        session.addLog("━━ LIVE ADS MODE: Observation only — validating ad load, display, and impressions.");
        session.addLog("Ad interactions are completely disabled to protect your AdMob account from policy violations.");

        // Preserve app data so frequency caps and user-state-based ad targeting behave as in production.
        // Only clear logcat so events from this run are cleanly captured.
        session.addLog("Clearing logcat and launching app (app data preserved for authentic ad serving)…");
        adb.clearLogcat(serial);
        adb.launchApp(serial, pkg);
        Thread.sleep(2500); // real ad networks take longer to initialize than test ads

        int steps = Math.max(props.getCrawlMaxSteps(), 250);
        session.addLog("Exploring app without ad interaction — up to " + steps + " steps, 3.5 s settle per screen…");

        // skipAdClicks=true: ad container nodes and ad-looking blocker buttons are never tapped.
        LocalizationCrawler.Result cr = LocalizationCrawler.explore(
                adb, serial, pkg, runDir, steps, w, h,
                session::isStopRequested, 3500L, session::addLog, true);

        if (session.isStopRequested()) return;
        session.addLog("Exploration complete: " + cr.screens().size() + " screen(s), " + cr.actions() + " action(s).");

        String logcat = adb.dumpLogcat(serial);

        // Parse load / impression / revenue events only — no CLICK events (never triggered by us).
        service.parseLogcatLiveMode(logcat, result, session::addLog);
        service.correlatePlacements(result, logcat, toExploration(cr), session::addLog);
    }

    // ── shared utility ────────────────────────────────────────────────────────

    private ExplorationResult toExploration(LocalizationCrawler.Result cr) {
        ExplorationResult e = new ExplorationResult();
        e.setCrashSuspected(cr.crashSuspected());
        e.setLeftAppDuringRun(cr.leftApp());
        for (ScreenCapture s : cr.screens()) e.addScreen(s);
        for (int i = 0; i < cr.actions(); i++) e.incrementActions();
        return e;
    }
}
