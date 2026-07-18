package com.vasundhara.atf.remoteconfig;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.engine.ExplorationEngine;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.TestContext;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.model.RemoteConfigSession;
import com.vasundhara.atf.model.RemoteConfigSession.State;
import com.vasundhara.atf.model.RemoteConfigTestResult;
import com.vasundhara.atf.model.TestRun;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drives the end-to-end Remote Config validation flow: installs the APK on a
 * device, clears app data to force a fresh Remote Config fetch, explores the app
 * with Appium, reads logcat for Firebase activity and then validates that each
 * published flag value is visible in the UI or logcat.
 */
@Component
public class RemoteConfigTestRunner {

    private static final Logger log = LoggerFactory.getLogger(RemoteConfigTestRunner.class);

    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final ExplorationEngine explorationEngine;
    private final AtfProperties props;
    private final ApkAnalyzer apkAnalyzer;
    private final com.vasundhara.atf.engine.ExecutionLockService execLock;
    private final com.vasundhara.atf.report.RunBridgeService runBridge;
    private final com.vasundhara.atf.device.DeviceWatchdog watchdog;
    private final RemoteConfigSessionStore sessionStore;

    public RemoteConfigTestRunner(AdbClient adb, DeviceManager deviceManager,
                                  DriverFactory driverFactory, ExplorationEngine explorationEngine,
                                  AtfProperties props, ApkAnalyzer apkAnalyzer,
                                  com.vasundhara.atf.engine.ExecutionLockService execLock,
                                  com.vasundhara.atf.report.RunBridgeService runBridge,
                                  com.vasundhara.atf.device.DeviceWatchdog watchdog,
                                  RemoteConfigSessionStore sessionStore) {
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.explorationEngine = explorationEngine;
        this.props = props;
        this.apkAnalyzer = apkAnalyzer;
        this.execLock = execLock;
        this.runBridge = runBridge;
        this.watchdog = watchdog;
        this.sessionStore = sessionStore;
    }

    @Async("testRunExecutor")
    public void run(RemoteConfigSession session, File apkFile) {
        runBridge.start(session.getId(), "Remote Config", session.getApkFileName(),null);
        try {
            runInternal(session, apkFile, null);
        } finally {
            // Persist the FINAL session (with all test results) — the only earlier save (in
            // RemoteConfigController, at creation) only ever wrote the initial empty SETUP
            // snapshot, so without this the Reports module would show stale/empty data for this
            // session after any server restart even though it completed successfully.
            sessionStore.save(session);
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    /**
     * Run the validation test targeting a specific device (multi-device execution).
     * {@code preferredSerial} is honoured by {@link DeviceManager#selectDevice(String)}.
     */
    @Async("testRunExecutor")
    public void run(RemoteConfigSession session, File apkFile, String preferredSerial) {
        runBridge.start(session.getId(), "Remote Config", session.getApkFileName(),preferredSerial);
        try {
            runInternal(session, apkFile, preferredSerial);
        } finally {
            sessionStore.save(session);
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    private void runInternal(RemoteConfigSession session, File apkFile, String preferredSerial) {
        AndroidDriver driver = null;
        String serial = null;
        String pkg = null;

        try {
            session.setState(State.TESTING);
            session.addLog("Starting Remote Config validation test…");

            // 1. Analyze APK for package name
            session.addLog("Analyzing APK…");
            ApkInfo info = apkAnalyzer.analyze(apkFile);
            pkg = info.getPackageName();
            session.setPackageName(pkg);
            session.addLog("Package: " + pkg + " v" + info.getVersionName());

            // 2. Select device (honour the caller's chosen serial for multi-device runs)
            Optional<String> serialOpt = deviceManager.selectDevice(preferredSerial);
            if (serialOpt.isEmpty()) {
                fail(session, "No device found.");
                return;
            }
            serial = serialOpt.get();
            session.addLog("Device: " + serial);
            watchdog.startWatch(session.getId(), serial, () -> {
                if (session.isStopRequested()) return; // manual stop already in flight
                session.setError("Device disconnected during test execution.");
                session.requestStop();
                session.addLog("DEVICE DISCONNECTED — stopping Remote Config validation.");
            });

            // 3. Install APK
            session.addLog("Installing APK…");
            var install = adb.install(serial, apkFile, pkg);
            if (!install.combined().contains("Success")) {
                fail(session, "Install failed: " + install.combined());
                return;
            }
            session.addLog("Installed successfully.");

            // 4. Clear app data to force fresh Remote Config fetch
            session.addLog("Clearing app data (forces fresh RC fetch)…");
            adb.clearAppData(serial, pkg);
            adb.clearLogcat(serial);
            Thread.sleep(1500);

            // 5. Launch app and explore with Appium
            session.addLog("Launching app with Appium…");
            if (!driverFactory.isAppiumReachable()) {
                fail(session, "Appium not reachable.");
                return;
            }
            driver = driverFactory.create(serial, info);
            DeviceManager.DeviceInfo deviceInfo = deviceManager.profile(serial);
            File runDir = new File(props.getWorkDir(), session.getId());
            runDir.mkdirs();

            // Build a TestRun stub so ExplorationEngine's cancel-check never NPEs.
            TestRun stub = new TestRun(session.getId(), session.getApkFileName(),
                    List.of());

            TestContext ctx = new TestContext(
                    props, adb, serial, apkFile, info, runDir, stub, deviceInfo);
            ctx.setDriver(driver);

            session.addLog("Exploring app (up to " + props.getCrawlMaxSteps() + " steps)…");
            ExplorationResult exploration = explorationEngine.explore(ctx, props.getCrawlMaxSteps());
            session.addLog("Exploration complete: " + exploration.getUniqueScreenCount()
                    + " screens, " + exploration.getActionsPerformed() + " actions.");

            // 6. Read logcat for Firebase RC activity
            session.addLog("Reading logcat for Remote Config activity…");
            String logcat = adb.dumpLogcat(serial).toLowerCase();
            boolean rcFetched = logcat.contains("remoteconfig")
                    || logcat.contains("remote_config")
                    || logcat.contains("firebase");
            session.addLog(rcFetched
                    ? "Firebase/Remote Config activity detected in logcat."
                    : "No Firebase Remote Config log entries found.");

            // 7. Collect all UI text from explored screens
            Set<String> allUiText = new HashSet<>();
            String lastShot = null;
            for (var screen : exploration.getScreens()) {
                for (var w : screen.widgets()) {
                    if (w.text() != null && !w.text().isBlank()) {
                        allUiText.add(w.text().toLowerCase().trim());
                    }
                }
                if (screen.screenshotPath() != null) {
                    lastShot = screen.screenshotPath();
                }
            }

            // 8. Validate each modified flag
            Map<String, String> changes = session.getPendingChanges();
            session.addLog("Validating " + changes.size() + " modified flag(s)…");
            for (Map.Entry<String, String> entry : changes.entrySet()) {
                String key = entry.getKey();
                String expected = entry.getValue();
                String expectedLc = expected.toLowerCase().trim();

                boolean inUi = allUiText.stream().anyMatch(t -> t.contains(expectedLc));
                boolean inLog = logcat.contains(key.toLowerCase())
                        || logcat.contains(expectedLc);

                String status;
                String evidence;
                String detail;
                if (inUi) {
                    status = "PASS";
                    evidence = "(found in app UI)";
                    detail = "Value \"" + expected + "\" found in live UI text.";
                } else if (inLog) {
                    status = "PASS";
                    evidence = "(found in logcat)";
                    detail = "Flag key or value detected in device logcat.";
                } else {
                    status = "UNKNOWN";
                    evidence = "(not found in UI or logcat)";
                    detail = "Value not visible in UI. Config may control non-visible behavior.";
                }
                session.addLog("[" + key + "] → " + status + " " + evidence);
                session.getTestResults().add(
                        new RemoteConfigTestResult(key, expected, evidence, status, detail, lastShot));
            }

            // 9. Cleanup
            try {
                driver.quit();
            } catch (Exception ignored) {}
            driver = null;
            try {
                adb.forceStop(serial, pkg);
                adb.uninstall(serial, pkg);
            } catch (Exception ignored) {}

            if (session.isStopRequested()) {
                session.addLog("Test stopped. Partial results saved.");
                session.setState(State.STOPPED);
            } else {
                session.addLog("Test complete.");
                session.setState(State.COMPLETED);
            }

        } catch (Exception e) {
            log.error("RemoteConfigTestRunner error for session {}: {}", session.getId(), e.getMessage(), e);
            if (session.isStopRequested()) {
                if (session.getError() == null || session.getError().isBlank()) session.setError("Test stopped by user.");
                session.setState(State.STOPPED);
                session.addLog("STOPPED: " + session.getError());
            } else {
                fail(session, "Test error: " + e.getMessage());
            }
        } finally {
            watchdog.stopWatch(session.getId());
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
            session.setFinishedAt(System.currentTimeMillis());
        }
    }

    private void fail(RemoteConfigSession session, String msg) {
        session.setError(msg);
        session.setState(State.FAILED);
        session.addLog("FAILED: " + msg);
    }
}
