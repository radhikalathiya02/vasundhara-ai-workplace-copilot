package com.vasundhara.atf.dm;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.util.LogcatAnalyzer;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a parallel smoke test on a set of devices: install → launch → explore → logcat analysis.
 * Each device runs in its own thread; all devices run concurrently.
 */
@Component
public class MultiDeviceRunner {

    private final AdbClient adb;
    private final ApkAnalyzer apkAnalyzer;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dm-runner");
        t.setDaemon(true);
        return t;
    });

    public MultiDeviceRunner(AdbClient adb, ApkAnalyzer apkAnalyzer) {
        this.adb = adb;
        this.apkAnalyzer = apkAnalyzer;
    }

    /** Start parallel execution. Returns immediately; completion updates session state. */
    public void run(MultiDeviceSession session, File apk) {
        List<DeviceSlotResult> slots = session.getSlots();
        session.addLog("Starting multi-device smoke test on " + slots.size() + " device(s)…");

        String pkg;
        try {
            pkg = apkAnalyzer.analyze(apk).getPackageName();
            session.addLog("APK package: " + pkg);
        } catch (Exception e) {
            session.addLog("ERROR: could not parse APK — " + e.getMessage());
            session.setState("ERROR");
            session.setOverallStatus("ERROR");
            return;
        }
        if (pkg == null || pkg.isBlank()) {
            session.addLog("ERROR: could not determine package name from APK.");
            session.setState("ERROR");
            session.setOverallStatus("ERROR");
            return;
        }

        final String finalPkg = pkg;
        List<CompletableFuture<Void>> futures = slots.stream()
                .map(slot -> CompletableFuture.runAsync(
                        () -> runOnDevice(session, slot, apk, finalPkg), pool))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> finalise(session));
    }

    private void runOnDevice(MultiDeviceSession session, DeviceSlotResult slot, File apk, String pkg) {
        String serial = slot.getSerial();
        try {
            // --- Install ---
            slot.setState("INSTALLING");
            slot.addLog("Installing " + apk.getName() + "…");
            session.addLog("[" + slot.getDisplayName() + "] Installing APK…");
            var install = adb.install(serial, apk, pkg);
            boolean ok = install.stdout().contains("Success") || install.combined().contains("Success");
            if (!ok) throw new RuntimeException("Install failed: " + trim(install.combined()));
            slot.addLog("APK installed.");

            // --- Launch ---
            slot.setState("LAUNCHING");
            slot.addLog("Launching " + pkg + "…");
            adb.clearLogcat(serial);
            adb.launchApp(serial, pkg);
            sleep(3000);

            // --- Smoke exploration (no Appium needed) ---
            slot.setState("RUNNING");
            slot.setScreensExplored(1);
            wakeScreen(serial);

            for (int i = 0; i < 4; i++) {
                // Scroll down on current screen
                adb.shell(serial, 10, "input", "swipe", "540", "1400", "540", "600", "300");
                sleep(900);
                // Back to previous screen
                adb.pressBack(serial);
                sleep(700);
                slot.setScreensExplored(slot.getScreensExplored() + 1);
            }
            // Return to app root
            adb.launchApp(serial, pkg);
            sleep(1500);
            wakeScreen(serial);

            // Take final screenshot
            byte[] shot = adb.screencapPng(serial);
            if (shot != null && shot.length > 100) slot.setFinalScreenshot(shot);

            // --- Logcat analysis ---
            // The device logcat/crash-buffer is shared by every process, so only incidents whose
            // captured text actually names this app's package are counted — otherwise a crash in
            // some unrelated app on the shared test device would be misattributed here.
            String logcat = adb.dumpLogcat(serial) + "\n" + adb.dumpCrashBuffer(serial);
            int crashes = 0, anrs = 0;
            for (LogcatAnalyzer.Incident incident : LogcatAnalyzer.analyze(logcat)) {
                if (!incident.snippet().contains(pkg) && !incident.header().contains(pkg)) continue;
                switch (incident.kind()) {
                    case FATAL_EXCEPTION, NATIVE_CRASH -> crashes++;
                    case ANR -> anrs++;
                    default -> {}
                }
            }
            slot.setCrashCount(crashes);
            slot.setAnrCount(anrs);

            if (crashes > 0) slot.addFinding("CRASH: " + crashes + " fatal exception(s) detected in logcat");
            if (anrs > 0)    slot.addFinding("ANR: Application Not Responding " + anrs + " time(s)");

            // Check if app is still running (didn't crash on exit)
            boolean running = adb.isAppRunning(serial, pkg);
            if (!running && crashes == 0) slot.addFinding("App was not running at end of smoke test");

            int score = Math.max(0, 100 - crashes * 25 - anrs * 15 - (running ? 0 : 10));
            slot.setScore(score);
            slot.setStatus(crashes > 0 || anrs > 0 ? "FAIL" : "PASS");
            slot.setState("DONE");
            slot.addLog("Done — " + slot.getStatus() + " · score: " + score
                    + " · crashes: " + crashes + " · ANRs: " + anrs);
            session.addLog("[" + slot.getDisplayName() + "] " + slot.getStatus()
                    + " (" + score + "/100)");

        } catch (Exception e) {
            slot.setState("ERROR");
            slot.setStatus("ERROR");
            slot.setError(e.getMessage());
            slot.addLog("ERROR: " + e.getMessage());
            session.addLog("[" + slot.getDisplayName() + "] ERROR — " + e.getMessage());
        }
    }

    private void finalise(MultiDeviceSession session) {
        List<DeviceSlotResult> slots = session.getSlots();
        long pass  = slots.stream().filter(s -> "PASS".equals(s.getStatus())).count();
        long fail  = slots.stream().filter(s -> "FAIL".equals(s.getStatus())).count();
        long error = slots.stream().filter(s -> "ERROR".equals(s.getStatus())).count();
        String overall = fail > 0 ? "FAIL" : error > 0 ? "PARTIAL" : "PASS";
        session.setOverallStatus(overall);
        session.setState("DONE");
        session.addLog("Completed: " + pass + " PASS, " + fail + " FAIL, " + error
                + " ERROR across " + slots.size() + " device(s). Overall: " + overall);
    }

    private void wakeScreen(String serial) {
        try { adb.shell(serial, 5, "input", "keyevent", "224"); } catch (Exception ignored) {}
    }

    private String trim(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 200 ? s.substring(s.length() - 200) : s;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
