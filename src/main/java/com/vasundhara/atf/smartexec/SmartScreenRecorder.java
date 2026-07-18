package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.device.AdbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Wraps {@code adb shell screenrecord} so Smart Execution can capture a short video ONLY when a
 * Critical/High finding is detected — per the product decision to keep runs light (no full-run
 * recording). Capped duration; best-effort throughout (a recording failure never fails the run).
 */
public final class SmartScreenRecorder {

    private static final Logger log = LoggerFactory.getLogger(SmartScreenRecorder.class);
    private static final int MAX_SECONDS = 12;
    private static final String REMOTE_PATH = "/sdcard/smartexec_evidence.mp4";

    private final AdbClient adb;
    private final String serial;
    private Thread recordThread;
    private volatile boolean started;

    public SmartScreenRecorder(AdbClient adb, String serial) {
        this.adb = adb;
        this.serial = serial;
    }

    /** Starts a capped-duration background recording; safe to call even if one is already running. */
    public synchronized void startOnFinding() {
        if (started) return;
        started = true;
        recordThread = new Thread(() -> {
            try {
                adb.shell(serial, MAX_SECONDS + 10L, "screenrecord", "--time-limit",
                        String.valueOf(MAX_SECONDS), REMOTE_PATH);
            } catch (Exception e) {
                log.debug("Screen recording failed for {}: {}", serial, e.toString());
            }
        }, "smartexec-screenrecord-" + serial);
        recordThread.setDaemon(true);
        recordThread.start();
    }

    /** Stops (if still running), waits briefly, and pulls the recording into {@code destDir}. Returns the local path, or null. */
    public synchronized String stopAndPull(File destDir, String fileBaseName) {
        if (!started) return null;
        try {
            // screenrecord has no clean "stop" over adb shell without a PID signal; the time-limit
            // cap above ensures it self-terminates. Give it a moment to flush the file, then pull.
            if (recordThread != null) recordThread.join(2000);
            destDir.mkdirs();
            File local = new File(destDir, fileBaseName + ".mp4");
            adb.pullFile(serial, REMOTE_PATH, local);
            return local.isFile() && local.length() > 0 ? local.getName() : null;
        } catch (Exception e) {
            log.debug("Could not pull screen recording for {}: {}", serial, e.toString());
            return null;
        } finally {
            started = false;
        }
    }
}
