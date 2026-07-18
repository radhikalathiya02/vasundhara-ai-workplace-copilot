package com.vasundhara.atf.engine;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.model.TestRun;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Shared state passed to every test category for a single run: the target device,
 * the analysed APK, the (optional) live Appium driver, the shared exploration result
 * and the run's artifact directory.
 */
public class TestContext {

    private static final Logger log = LoggerFactory.getLogger(TestContext.class);

    private final AtfProperties props;
    private final AdbClient adb;
    private final String serial;
    private final File apkFile;
    private final ApkInfo apkInfo;
    private final File runDir;
    private final TestRun run;
    private final DeviceManager.DeviceInfo deviceInfo;

    // Volatile: the orchestrator's cancel() reads/clears this from the API-handling thread
    // while the worker thread that owns the run may be concurrently setting/clearing it.
    private volatile AndroidDriver driver;
    private ExplorationResult exploration;
    /**
     * Optional extra sink for screen-navigation log entries. When set, every
     * "▶ Screen — X" event produced by the exploration engine is also forwarded
     * here in addition to {@link TestRun#addExecutionStep}. Callers that run
     * exploration against a stub TestRun (e.g. Compatibility, Remote Config) set
     * this to route screen nav entries into their session's own log.
     */
    private Consumer<String> screenNavLogger;

    public TestContext(AtfProperties props, AdbClient adb, String serial, File apkFile,
                       ApkInfo apkInfo, File runDir, TestRun run,
                       DeviceManager.DeviceInfo deviceInfo) {
        this.props = props;
        this.adb = adb;
        this.serial = serial;
        this.apkFile = apkFile;
        this.apkInfo = apkInfo;
        this.runDir = runDir;
        this.run = run;
        this.deviceInfo = deviceInfo;
    }

    /** Persist a screenshot under {@code <runDir>/screenshots} and return its relative path. */
    public String saveScreenshot(byte[] png, String name) {
        if (png == null || png.length == 0) return null;
        try {
            Path dir = runDir.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            String fileName = name.endsWith(".png") ? name : name + ".png";
            Path target = dir.resolve(fileName);
            Files.write(target, png);
            return "screenshots/" + fileName;
        } catch (Exception e) {
            log.warn("Failed to save screenshot {}: {}", name, e.toString());
            return null;
        }
    }

    /** Convert dp to pixels for this device's density (for touch-target checks). */
    public int dpToPx(int dp) {
        int dpi = deviceInfo != null && deviceInfo.densityDpi() > 0 ? deviceInfo.densityDpi() : 160;
        return Math.round(dp * dpi / 160f);
    }

    public AtfProperties props() { return props; }
    public AdbClient adb() { return adb; }
    public String serial() { return serial; }
    public File apkFile() { return apkFile; }
    public ApkInfo apkInfo() { return apkInfo; }
    public File runDir() { return runDir; }
    public TestRun run() { return run; }
    public DeviceManager.DeviceInfo deviceInfo() { return deviceInfo; }

    public AndroidDriver driver() { return driver; }
    public void setDriver(AndroidDriver driver) { this.driver = driver; }
    public boolean hasDriver() { return driver != null; }

    /**
     * Atomically takes ownership of the current driver, clearing the field so any other holder
     * of this context sees {@code hasDriver() == false} immediately. Returns {@code null} if
     * another caller already claimed it. Use this (instead of a separate {@code driver()} read
     * followed by {@code setDriver(null)}) whenever the worker thread and {@link
     * com.vasundhara.atf.engine.TestOrchestrator#cancel} might race to quit the same session —
     * only the caller that wins the compare-and-set is responsible for calling
     * {@code driver.quit()}, so the session is never quit twice.
     */
    public synchronized AndroidDriver takeDriver() {
        AndroidDriver d = this.driver;
        this.driver = null;
        return d;
    }

    public ExplorationResult exploration() { return exploration; }
    public void setExploration(ExplorationResult exploration) { this.exploration = exploration; }

    public void setLiveProgress(String key, Object value) { run.setLiveProgress(key, value); }
    public void clearLiveProgress() { run.clearLiveProgress(); }

    public void setScreenNavLogger(Consumer<String> logger) { this.screenNavLogger = logger; }

    /** Log a screen-navigation event to the run's execution steps and, if set, to the session log. */
    public void logScreen(String readableName) {
        String entry = "▶ Screen — " + readableName;
        run.addExecutionStep(entry);
        if (screenNavLogger != null) screenNavLogger.accept(entry);
    }
}
