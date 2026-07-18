package com.vasundhara.atf.device;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.util.ProcessRunner;
import com.vasundhara.atf.util.ProcessRunner.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps the {@code adb} command-line for everything the framework needs that the
 * Appium driver does not cover: device discovery, install/uninstall, logcat, dumpsys
 * metrics, the monkey stress tool and activity inspection.
 */
@Component
public class AdbClient {

    private static final Logger log = LoggerFactory.getLogger(AdbClient.class);

    private final AtfProperties props;

    public AdbClient(AtfProperties props) {
        this.props = props;
    }

    // ---- low-level helpers ------------------------------------------------

    private List<String> base(String serial) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getAdbPath());
        if (serial != null && !serial.isBlank()) {
            cmd.add("-s");
            cmd.add(serial);
        }
        return cmd;
    }

    public CommandResult adb(String serial, long timeoutSeconds, String... args) {
        List<String> cmd = base(serial);
        for (String a : args) cmd.add(a);
        return ProcessRunner.run(cmd, timeoutSeconds);
    }

    /** Run a device-side shell command. */
    public CommandResult shell(String serial, long timeoutSeconds, String... shellArgs) {
        List<String> cmd = base(serial);
        cmd.add("shell");
        for (String a : shellArgs) cmd.add(a);
        return ProcessRunner.run(cmd, timeoutSeconds);
    }

    // ---- device discovery -------------------------------------------------

    /**
     * One row from {@code adb devices}: the serial and its reported state
     * ({@code device}, {@code offline}, {@code unauthorized}, {@code connecting}, …).
     */
    public record DeviceEntry(String serial, String state) {
        /** True when the serial starts with {@code emulator-} (AVD-based emulator). */
        public boolean isEmulator() { return serial.startsWith("emulator-"); }
        /** True when the device is ready for commands ({@code device} state). */
        public boolean isOnline()   { return "device".equals(state); }
    }

    /**
     * All entries currently shown by {@code adb devices}, including offline,
     * unauthorized, and still-booting emulators — not just online ones.
     */
    public List<DeviceEntry> allDevices() {
        List<DeviceEntry> out = new ArrayList<>();
        CommandResult r = ProcessRunner.run(List.of(props.getAdbPath(), "devices"), 15);
        for (String line : r.stdout().split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) out.add(new DeviceEntry(parts[0], parts[1]));
        }
        return out;
    }

    /** Serials of all devices/emulators currently in the {@code device} state. */
    public List<String> onlineDevices() {
        return allDevices().stream().filter(DeviceEntry::isOnline).map(DeviceEntry::serial).toList();
    }

    public String getProp(String serial, String key) {
        return shell(serial, 10, "getprop", key).stdout().trim();
    }

    /** The device's {@code Settings.Secure.ANDROID_ID} (supplementary test-device info). */
    public String androidId(String serial) {
        return shell(serial, 10, "settings", "get", "secure", "android_id").stdout().trim();
    }

    // ---- wireless (TCP/IP) connection -------------------------------------

    /**
     * Pair with an Android 11+ device using its pairing IP:port and one-time code.
     * Note: {@code adb pair} has no timeout flag — the code's validity is enforced by the device
     * (a short window). We give the host PROCESS up to 5 minutes so we never abort the handshake early.
     */
    public CommandResult pair(String host, String port, String code) {
        return adb(null, 300, "pair", host + ":" + port, code);
    }

    /** Connect to a device over TCP/IP at IP:port (no pairing needed for Android 10 and below). */
    public CommandResult connect(String host, String port) {
        return adb(null, 30, "connect", host + ":" + port);
    }

    /** Disconnect a wireless device by its serial (IP:port). */
    public CommandResult disconnect(String serial) {
        return adb(null, 15, "disconnect", serial);
    }

    // ---- app lifecycle ----------------------------------------------------

    /**
     * Install without a known package name — same as {@link #install(String, File, String)} but
     * can't auto-recover from a version-downgrade failure since there's nothing to uninstall by.
     * Prefer the 3-arg overload wherever the package name is available (it always should be,
     * since {@code ApkInfo} is parsed before every install call site).
     */
    public CommandResult install(String serial, File apk) {
        return install(serial, apk, null);
    }

    /**
     * Installs {@code apk}, automatically recovering from the one failure class that {@code -d}
     * (allow-downgrade) doesn't reliably cover: on production/"user"-build devices, Android only
     * honors INSTALL_ALLOW_DOWNGRADE when the device or the app itself is debuggable, so a leftover
     * newer build of the SAME app already on the device (e.g. from a previous, later test run, or
     * a manually-installed build) still fails with INSTALL_FAILED_VERSION_DOWNGRADE despite -d.
     * Generic for any APK: on that specific failure, uninstall the existing {@code packageName}
     * (its versionCode is irrecoverably higher than what's being tested, so there's no other way
     * forward) and retry once as a clean install.
     */
    public CommandResult install(String serial, File apk, String packageName) {
        CommandResult result = installOnce(serial, apk);
        if (packageName != null && !packageName.isBlank()
                && !result.combined().contains("Success")
                && result.combined().contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            log.info("Install of {} hit VERSION_DOWNGRADE — uninstalling the existing (newer) build "
                    + "on {} and retrying.", packageName, serial);
            uninstall(serial, packageName);
            result = installOnce(serial, apk);
        }
        return result;
    }

    private CommandResult installOnce(String serial, File apk) {
        // Locked-down OEM ROMs (ColorOS/OPPO, MIUI/Xiaomi, etc.) intercept `adb install` with a
        // PackageInstaller confirmation/scan dialog that blocks until it times out. Pushing the APK
        // and installing via `pm install` from the shell uid bypasses that GUI entirely.
        bestEffortDisableInstallVerification(serial);
        String remote = "/data/local/tmp/atf-install.apk";
        CommandResult push = adb(serial, 180, "push", apk.getAbsolutePath(), remote);
        if (!push.combined().contains("pushed")) {
            // Fall back to a plain install if the push failed for some reason.
            return adb(serial, 300, "install", "-r", "-g", "-t", "-d", apk.getAbsolutePath());
        }
        // -r reinstall, -g grant runtime permissions, -t allow test packages, -d allow downgrade.
        CommandResult result = shell(serial, 240, "pm", "install", "-r", "-g", "-t", "-d", remote);
        shell(serial, 30, "rm", "-f", remote);
        return result;
    }

    /** Disable Play Protect / adb-install verification so installs proceed without prompts. */
    private void bestEffortDisableInstallVerification(String serial) {
        shell(serial, 10, "settings", "put", "global", "verifier_verify_adb_installs", "0");
        shell(serial, 10, "settings", "put", "global", "package_verifier_enable", "0");
    }

    public CommandResult uninstall(String serial, String pkg) {
        return adb(serial, 60, "uninstall", pkg);
    }

    public boolean isInstalled(String serial, String pkg) {
        return shell(serial, 15, "pm", "list", "packages", pkg)
                .stdout().contains("package:" + pkg);
    }

    /** True if the app currently has a live process (used to tell crashes from Appium hiccups). */
    public boolean isAppRunning(String serial, String pkg) {
        return !shell(serial, 10, "pidof", pkg).stdout().trim().isEmpty();
    }

    public void forceStop(String serial, String pkg) {
        shell(serial, 15, "am", "force-stop", pkg);
    }

    public void clearAppData(String serial, String pkg) {
        shell(serial, 30, "pm", "clear", pkg);
    }

    /** Launch the app's launcher activity via monkey (works without knowing the activity). */
    public CommandResult launchApp(String serial, String pkg) {
        return shell(serial, 30, "monkey", "-p", pkg,
                "-c", "android.intent.category.LAUNCHER", "1");
    }

    /**
     * Cold-starts ONLY the given package and waits (up to ~8s) until it is actually the foreground
     * app. Force-stops first for a clean start; if a foreign app/system screen is in front after
     * launch, force-stops that and relaunches once. Uses only the caller-supplied package name — no
     * hardcoded app/activity/package. Best-effort: returns after the timeout regardless. Shared by
     * every module that starts a crawl on a freshly-installed APK, so "launch into the wrong app"
     * is fixed in one place instead of drifting per module.
     */
    public void launchAndWaitForeground(String serial, String pkg) {
        try { forceStop(serial, pkg); } catch (Exception ignored) {}
        try { launchApp(serial, pkg); } catch (Exception ignored) {}
        long deadline = System.currentTimeMillis() + 8000;
        boolean recovered = false;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            String fg = null;
            try { fg = currentForegroundPackage(serial); } catch (Exception ignored) {}
            if (fg != null && fg.contains(pkg)) return;   // app is in the foreground — ready to crawl
            // Any other surface is in front (launcher, Settings, a browser, the Play Store, …) —
            // force it away and relaunch our app, once, so the crawl starts strictly inside the
            // uploaded app. Generic: works for any foreground package by name.
            if (!recovered && fg != null && !fg.isBlank() && !fg.contains(pkg)) {
                recovered = true;
                try { forceStop(serial, fg); } catch (Exception ignored) {}
                // Press HOME before relaunching — verified live (OPPO/ColorOS device): simply
                // force-stopping the foreign app can leave Android free to resume WHATEVER task
                // sits behind it in the recents stack (e.g. the app tested just before this one)
                // instead of the home screen, so the next launch attempt can silently land back in
                // a stale, unrelated app. Pressing HOME first breaks that chain unconditionally.
                try { pressHome(serial); } catch (Exception ignored) {}
                try { launchApp(serial, pkg); } catch (Exception ignored) {}
            }
        }
    }

    /** Ask the package manager for the app's launcher activity, e.g. {@code pkg/.MainActivity}. */
    public String resolveLauncherActivity(String serial, String pkg) {
        String out = shell(serial, 20, "cmd", "package", "resolve-activity", "--brief", pkg).stdout();
        for (String line : out.split("\\R")) {
            line = line.trim();
            if (line.startsWith(pkg + "/")) {
                String activity = line.substring(line.indexOf('/') + 1);
                if (activity.startsWith(".")) activity = pkg + activity;
                return activity;
            }
        }
        return "";
    }

    /** Launch timing via {@code am start -W}: returns the reported TotalTime in ms, or -1. */
    public long launchActivityTimed(String serial, String pkg, String activity) {
        String component = activity.contains("/") ? activity : pkg + "/" + activity;
        CommandResult r = shell(serial, 45, "am", "start", "-W", "-n", component);
        Matcher m = Pattern.compile("TotalTime:\\s*(\\d+)").matcher(r.stdout());
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    // ---- logcat / crash detection ----------------------------------------

    public void clearLogcat(String serial) {
        adb(serial, 15, "logcat", "-c");
        adb(serial, 15, "logcat", "-b", "crash", "-c");
    }

    public String dumpLogcat(String serial) {
        return adb(serial, 30, "logcat", "-d", "-v", "threadtime").stdout();
    }

    public String dumpCrashBuffer(String serial) {
        return adb(serial, 30, "logcat", "-d", "-b", "crash", "-v", "threadtime").stdout();
    }

    // ---- monkey -----------------------------------------------------------

    public CommandResult monkey(String serial, String pkg, int events, long seed, int throttleMs) {
        return shell(serial, 600,
                "monkey", "-p", pkg,
                "-s", String.valueOf(seed),
                "--throttle", String.valueOf(throttleMs),
                "--ignore-security-exceptions",
                "--monitor-native-crashes",
                "--pct-syskeys", "0",
                "-v", "-v",
                String.valueOf(events));
    }

    // ---- performance metrics ---------------------------------------------

    public String dumpsys(String serial, String... what) {
        String[] args = new String[what.length + 1];
        args[0] = "dumpsys";
        System.arraycopy(what, 0, args, 1, what.length);
        return shell(serial, 45, args).stdout();
    }

    public String meminfo(String serial, String pkg) {
        return dumpsys(serial, "meminfo", pkg);
    }

    public String gfxinfo(String serial, String pkg) {
        return dumpsys(serial, "gfxinfo", pkg);
    }

    /** Resets the framestats/gfxinfo histogram so the next measurement is clean. */
    public void resetGfxinfo(String serial, String pkg) {
        shell(serial, 20, "dumpsys", "gfxinfo", pkg, "reset");
    }

    // ---- screen / window inspection --------------------------------------

    /** Capture the current screen as PNG bytes (empty array on failure). */
    public byte[] screencapPng(String serial) {
        List<String> cmd = base(serial);
        cmd.add("exec-out");
        cmd.add("screencap");
        cmd.add("-p");
        return ProcessRunner.runBinary(cmd, 30);
    }

    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)");

    public int[] screenSize(String serial) {
        String out = shell(serial, 10, "wm", "size").stdout();
        Matcher m = SIZE.matcher(out);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return new int[]{0, 0};
    }

    public int density(String serial) {
        String out = shell(serial, 10, "wm", "density").stdout();
        Matcher m = Pattern.compile("(\\d+)").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Name of the package currently in the foreground (best-effort). */
    public String currentForegroundPackage(String serial) {
        String out = shell(serial, 15, "dumpsys", "activity", "activities").stdout();
        // "mResumedActivity" is the pre-Android-13 key; newer platforms (verified live on Android
        // 16 / API 36) renamed it to "topResumedActivity" and dropped "windows" as a `dumpsys
        // window` sub-argument entirely (it now returns nothing), which together silently made
        // this method return "" on modern devices — every caller that gates on "is this really
        // the app under test" was therefore blind on those devices. Try both key names before
        // falling back to `dumpsys window` (no "windows" argument, which still works everywhere).
        Matcher m = Pattern.compile("(?:mResumedActivity|topResumedActivity).*?\\{[^}]*\\s([\\w.]+)/([\\w.$]+)").matcher(out);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("mCurrentFocus=.*?\\s([\\w.]+)/").matcher(
                shell(serial, 15, "dumpsys", "window").stdout());
        return m2.find() ? m2.group(1) : "";
    }

    public boolean isForeground(String serial, String pkg) {
        return pkg != null && pkg.equals(currentForegroundPackage(serial));
    }

    // ---- connectivity (for negative testing) -----------------------------

    public void setAirplaneMode(String serial, boolean on) {
        shell(serial, 15, "cmd", "connectivity", "airplane-mode", on ? "enable" : "disable");
    }

    public void setWifi(String serial, boolean on) {
        shell(serial, 15, "svc", "wifi", on ? "enable" : "disable");
    }

    /** True when the device's WiFi radio is currently on. Used to detect and correct a device
     *  left with WiFi disabled by an interrupted prior run (see TestOrchestrator.execute()). */
    public boolean isWifiEnabled(String serial) {
        try {
            String out = shell(serial, 10, "settings", "get", "global", "wifi_on").stdout();
            return out != null && out.trim().equals("1");
        } catch (Exception e) {
            return true; // unknown — don't force a toggle on a read failure
        }
    }

    public void setData(String serial, boolean on) {
        shell(serial, 15, "svc", "data", on ? "enable" : "disable");
    }

    /** Rotate the device; orientation 0=portrait,1=landscape (requires auto-rotate off). */
    public void setRotation(String serial, int orientation) {
        shell(serial, 10, "settings", "put", "system", "accelerometer_rotation", "0");
        shell(serial, 10, "settings", "put", "system", "user_rotation",
                orientation == 1 ? "1" : "0");
    }

    public void pressBack(String serial) {
        shell(serial, 10, "input", "keyevent", "KEYCODE_BACK");
    }

    public void pressHome(String serial) {
        shell(serial, 10, "input", "keyevent", "KEYCODE_HOME");
    }

    /** Tap the screen at the given pixel coordinates. */
    public void tap(String serial, int x, int y) {
        shell(serial, 10, "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    /** Long-press the screen at the given pixel coordinates for {@code durationMs} (a swipe with identical start/end points). */
    public void longPress(String serial, int x, int y, int durationMs) {
        shell(serial, 10, "input", "swipe", String.valueOf(x), String.valueOf(y), String.valueOf(x), String.valueOf(y), String.valueOf(durationMs));
    }

    /** Swipe from one point to another over {@code durationMs}. */
    public void swipe(String serial, int x1, int y1, int x2, int y2, int durationMs) {
        shell(serial, 10, "input", "swipe", String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
    }

    /** Dump the current UI hierarchy (uiautomator) and return its XML, or "" on failure. */
    private static final String UI_DUMP_PATH = "/sdcard/atf_ui_dump.xml";

    /**
     * Dumps the current UI hierarchy. `uiautomator dump` intermittently fails outright (a known
     * flake on several devices/OEM skins, especially mid-transition) WITHOUT any usable error
     * signal from its own exit code — and critically, on failure it leaves the previous dump file
     * untouched. Verified live: without deleting the target file first, a single failed dump
     * silently served the SAME stale file from a much earlier run/app for every subsequent poll —
     * the crawler was reading a completely different app's screen and never knew it. Deleting the
     * file before each dump turns "dump silently failed" into "empty content" (a state every
     * caller already treats as no-tree/opaque and falls back on OCR/vision for), instead of quietly
     * serving stale, wrong data. One retry on an empty/too-short result absorbs the common
     * transient failure.
     */
    public String uiDump(String serial) {
        String xml = tryUiDump(serial);
        if (xml.length() < 50) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            xml = tryUiDump(serial);
        }
        return xml;
    }

    private String tryUiDump(String serial) {
        try { shell(serial, 10, "rm", "-f", UI_DUMP_PATH); } catch (Exception ignored) {}
        shell(serial, 20, "uiautomator", "dump", UI_DUMP_PATH);
        String xml = shell(serial, 15, "cat", UI_DUMP_PATH).stdout();
        return xml == null ? "" : xml;
    }

    /**
     * Run a raw shell command string via {@code adb shell "<cmd>"}.
     * Use this for commands that contain pipes, redirections, or glob patterns that
     * cannot be expressed as clean varargs (e.g. {@code logcat -d *:E | grep FATAL}).
     */
    public String shellStr(String serial, String command) {
        return adb(serial, 30, "shell", command).stdout();
    }

    /** Pull a remote file from the device to a local destination. */
    public void pullFile(String serial, String remote, File local) {
        adb(serial, 60, "pull", remote, local.getAbsolutePath());
    }

    /**
     * Returns the AVD name for a running emulator ({@code adb -s emulator-N emu avd name}).
     * Returns an empty string if the command fails or the device is not an emulator.
     */
    public String emulatorAvdName(String serial) {
        CommandResult r = adb(serial, 10, "emu", "avd", "name");
        String[] lines = r.stdout().split("\\R");
        // Output is: avd name\nOK  — we want the first non-blank line before "OK"
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.equalsIgnoreCase("OK")) return line;
        }
        return "";
    }

    /**
     * Total RAM of a device in MB, parsed from {@code /proc/meminfo}.
     * Returns 0 if the device is not online or the value cannot be parsed.
     */
    public long ramMb(String serial) {
        String out = shell(serial, 10, "cat", "/proc/meminfo").stdout();
        Matcher m = Pattern.compile("MemTotal:\\s*(\\d+)").matcher(out);
        return m.find() ? Long.parseLong(m.group(1)) / 1024 : 0;
    }

    // ── Runtime permission helpers ─────────────────────────────────────────

    /** Grant a runtime permission to the given package via {@code pm grant}. */
    public void grantPermission(String serial, String pkg, String permission) {
        shell(serial, 8, "pm", "grant", pkg, permission);
    }

    /** Revoke a runtime permission from the given package via {@code pm revoke}. */
    public void revokePermission(String serial, String pkg, String permission) {
        shell(serial, 8, "pm", "revoke", pkg, permission);
    }

    /**
     * Open the system App Info (Application Details Settings) page for the given package.
     * This is the entry point for navigating to the per-app Permissions screen.
     */
    public void openAppSettings(String serial, String pkg) {
        shell(serial, 8,
              "am", "start",
              "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
              "-d", "package:" + pkg);
    }
}
