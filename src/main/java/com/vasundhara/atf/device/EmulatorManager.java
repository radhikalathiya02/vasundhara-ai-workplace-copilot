package com.vasundhara.atf.device;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.util.ProcessRunner;
import com.vasundhara.atf.util.ProcessRunner.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Automates the Android emulator lifecycle so compatibility testing needs no
 * manually connected devices: it locates the SDK, ensures the right system image
 * and AVD exist (downloading/creating them on demand), boots an emulator headless,
 * waits for it to finish booting, and tears it down afterwards.
 *
 * <p>All operations fail soft: any step that cannot complete throws
 * {@link EmulatorException} with a human-readable reason so the caller can record
 * an "environment error" for that Android version and continue with the next one.
 */
@Component
public class EmulatorManager {

    private static final Logger log = LoggerFactory.getLogger(EmulatorManager.class);

    private final AtfProperties props;
    private final AdbClient adb;

    public EmulatorManager(AtfProperties props, AdbClient adb) {
        this.props = props;
        this.adb = adb;
    }

    /** A running emulator: its adb serial and the OS process to kill on shutdown. */
    public record BootedEmulator(String serial, Process process, String avdName) {}

    public static class EmulatorException extends Exception {
        public EmulatorException(String msg) { super(msg); }
        public EmulatorException(String msg, Throwable t) { super(msg, t); }
    }

    /* ---- SDK / tool discovery -------------------------------------------- */

    public File sdkRoot() {
        String p = props.getAndroidSdkPath();
        if (p != null && !p.isBlank()) return new File(p);
        String env = System.getenv("ANDROID_HOME");
        if (env == null || env.isBlank()) env = System.getenv("ANDROID_SDK_ROOT");
        if (env != null && !env.isBlank()) return new File(env);
        return new File(System.getProperty("user.home"), "Library/Android/sdk");
    }

    private File tool(String relative) { return new File(sdkRoot(), relative); }

    private File emulatorBin() { return tool("emulator/emulator"); }
    private File avdManager()  { return tool("cmdline-tools/latest/bin/avdmanager"); }
    private File sdkManager()  { return tool("cmdline-tools/latest/bin/sdkmanager"); }

    /** True if the core emulator tooling is present on this machine. */
    public boolean toolingAvailable() {
        return emulatorBin().canExecute() && avdManager().exists() && sdkManager().exists();
    }

    public String toolingDiagnostics() {
        return "sdk=" + sdkRoot()
                + " emulator=" + emulatorBin().exists()
                + " avdmanager=" + avdManager().exists()
                + " sdkmanager=" + sdkManager().exists();
    }

    /* ---- system image + AVD provisioning --------------------------------- */

    private String packageName(int api) {
        return "system-images;android-" + api + ";" + props.getEmulatorImageTag() + ";" + props.getEmulatorAbi();
    }

    private File systemImageDir(int api) {
        return new File(sdkRoot(), "system-images/android-" + api + "/"
                + props.getEmulatorImageTag() + "/" + props.getEmulatorAbi());
    }

    private String avdName(int api) { return "atf_compat_" + api; }

    /**
     * Run a full environment pre-check for all requested API levels.  Returns a list of
     * structured pass/fail items suitable for display in the dashboard pre-check panel.
     */
    public List<Map<String, Object>> preCheck(List<Integer> apis) {
        List<Map<String, Object>> items = new ArrayList<>();
        // Core tooling
        File sdk = sdkRoot();
        items.add(checkItem("Android SDK Root", sdk.isDirectory(), sdk.getAbsolutePath(),
                sdk.isDirectory() ? null : "Set atf.android-sdk-path or ANDROID_HOME env variable."));
        File emu = emulatorBin();
        items.add(checkItem("Emulator Binary", emu.canExecute(), emu.getAbsolutePath(),
                emu.canExecute() ? null : "Install Android Emulator via Android Studio > SDK Manager."));
        File avm = avdManager();
        items.add(checkItem("avdmanager", avm.exists(), avm.getAbsolutePath(),
                avm.exists() ? null : "Install cmdline-tools via Android Studio > SDK Manager."));
        File sdkm = sdkManager();
        items.add(checkItem("sdkmanager", sdkm.exists(), sdkm.getAbsolutePath(),
                sdkm.exists() ? null : "Install cmdline-tools via Android Studio > SDK Manager."));
        // ADB
        CommandResult adbVer = ProcessRunner.run(List.of("adb", "version"), 10);
        boolean adbOk = adbVer.exitCode() == 0 && adbVer.stdout().contains("Android Debug Bridge");
        items.add(checkItem("ADB (adb)", adbOk,
                adbOk ? adbVer.stdout().lines().findFirst().orElse("ok") : adbVer.combined(),
                adbOk ? null : "Ensure adb is in PATH or set atf.adb-path in application.yml."));
        // Java
        CommandResult javaVer = ProcessRunner.run(List.of("java", "-version"), 10);
        boolean javaOk = javaVer.exitCode() == 0 || javaVer.stderr().contains("version");
        items.add(checkItem("Java Runtime", javaOk,
                javaOk ? javaVer.stderr().lines().findFirst().orElse("ok") : javaVer.combined(),
                javaOk ? null : "Install JDK 11+ and ensure 'java' is in PATH."));
        // Per-API system images
        for (int api : apis) {
            File imgDir = systemImageDir(api);
            boolean imgOk = imgDir.isDirectory();
            items.add(checkItem("System Image API " + api,
                    imgOk, imgOk ? imgDir.getAbsolutePath() : packageName(api),
                    imgOk ? null : "Install via: sdkmanager \"" + packageName(api) + "\""));
        }
        return items;
    }

    private Map<String, Object> checkItem(String name, boolean pass, String detail, String fix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", pass ? "PASS" : "FAIL");
        m.put("detail", detail != null ? detail : "");
        if (fix != null) m.put("fix", fix);
        return m;
    }

    /** Ensure the system image and an AVD exist for {@code api}; returns the AVD name. */
    public String ensureAvd(int api, Consumer<String> logger) throws EmulatorException {
        if (!toolingAvailable())
            throw new EmulatorException("Android emulator tooling not found (" + toolingDiagnostics() + ").");

        // 1. System image
        if (!systemImageDir(api).isDirectory()) {
            if (!props.isAutoDownloadSystemImages())
                throw new EmulatorException("System image " + packageName(api)
                        + " not installed and auto-download is disabled.");
            logger.accept("Downloading system image " + packageName(api) + " (this can be large)…");
            CommandResult r = runWithYes(List.of(sdkManager().getAbsolutePath(), packageName(api)),
                    20 * 60); // up to 20 min for a large download
            if (!systemImageDir(api).isDirectory())
                throw new EmulatorException("Failed to install system image " + packageName(api)
                        + (r.timedOut() ? " (timed out)." : ": " + tail(r.combined())));
            logger.accept("System image ready.");
        } else {
            logger.accept("System image already installed.");
        }

        // 2. AVD — create if missing, with one sdkmanager --update retry for stale-repo failures.
        String avdName = avdName(api);
        if (!avdExists(avdName)) {
            logger.accept("Creating AVD " + avdName + "…");
            CommandResult r = createAvdWithFallback(avdName, api, logger);
            // emulator -list-avds may lag a beat after avdmanager create — retry once before failing.
            if (!avdExistsWithRetry(avdName))
                throw new EmulatorException(buildAvdCreateError(avdName, api, r));
            logger.accept("AVD created.");
        } else {
            logger.accept("AVD " + avdName + " already exists.");
        }
        return avdName;
    }

    /**
     * Create the AVD; if avdmanager reports "Package path is not valid" but the system-image
     * directory is present on disk, the local SDK repository is stale.  Refresh via
     * {@code sdkmanager --update} and retry.
     */
    private CommandResult createAvdWithFallback(String avdName, int api, Consumer<String> logger) {
        CommandResult r = runWithNo(List.of(
                avdManager().getAbsolutePath(), "create", "avd",
                "-n", avdName, "-k", packageName(api), "-d", "pixel", "--force"), 120);
        if (r.exitCode() == 0) return r;
        String combined = r.combined() != null ? r.combined() : "";
        if ((combined.contains("Package path is not valid") || combined.contains("not valid"))
                && systemImageDir(api).isDirectory()) {
            logger.accept("SDK repository out of date for API " + api
                    + " — refreshing metadata via sdkmanager (this may take ~30 s)…");
            runWithYes(List.of(sdkManager().getAbsolutePath(), "--update"), 180);
            return runWithNo(List.of(
                    avdManager().getAbsolutePath(), "create", "avd",
                    "-n", avdName, "-k", packageName(api), "-d", "pixel", "--force"), 120);
        }
        return r;
    }

    private boolean avdExists(String name) {
        CommandResult r = ProcessRunner.run(List.of(emulatorBin().getAbsolutePath(), "-list-avds"), 30);
        for (String line : r.stdout().split("\\R")) if (line.trim().equals(name)) return true;
        return false;
    }

    /** Check once, then wait 2 s and check again to absorb the emulator list-avds update lag. */
    private boolean avdExistsWithRetry(String name) {
        if (avdExists(name)) return true;
        sleep(2000);
        return avdExists(name);
    }

    private String buildAvdCreateError(String avdName, int api, CommandResult r) {
        String combined = r.combined() != null ? r.combined() : "";
        for (String line : combined.split("\\R")) {
            line = line.trim();
            if (line.startsWith("Error:") && !line.equalsIgnoreCase("Error: null")
                    && !line.equals("Error:")) {
                if (line.contains("Package path is not valid")) {
                    return "System image '" + packageName(api) + "' is not registered in the local SDK "
                            + "repository (files are present on disk but metadata is stale). "
                            + "Fix: run 'sdkmanager --update' and retry, or reinstall with: "
                            + "sdkmanager \"" + packageName(api) + "\"";
                }
                return "Failed to create AVD " + avdName + ": " + line
                        + " (exit " + r.exitCode() + ")";
            }
        }
        return "Failed to create AVD " + avdName + " (exit " + r.exitCode() + "). "
                + "Re-run: echo no | avdmanager create avd -n " + avdName
                + " -k \"" + packageName(api) + "\" -d pixel --force";
    }

    /* ---- boot / shutdown ------------------------------------------------- */

    /** Boot an emulator for {@code avdName} headless and wait for it to finish booting. */
    /** Boot an emulator. Convenience overload — no pre-boot callback. */
    public BootedEmulator boot(String avdName, Consumer<String> logger) throws EmulatorException {
        return boot(avdName, logger, null);
    }

    /**
     * Launch the emulator process and wait for a full boot.
     *
     * @param onSerialAllocated optional callback fired <em>immediately after the OS process
     *   starts</em> (before {@code waitForBoot} blocks).  Receives the ADB serial so callers
     *   can expose it for live-screen polling while the device is still booting.
     */
    public BootedEmulator boot(String avdName, Consumer<String> logger,
                               Consumer<String> onSerialAllocated) throws EmulatorException {
        int port = pickFreeEvenPort();
        String serial = "emulator-" + port;

        List<String> cmd = new ArrayList<>(List.of(
                emulatorBin().getAbsolutePath(),
                "-avd", avdName,
                "-port", String.valueOf(port),
                "-no-audio", "-no-boot-anim", "-no-snapshot-save",
                "-gpu", "angle_indirect",
                "-accel", "auto"));
        if (props.isEmulatorHeadless()) cmd.add("-no-window");

        logger.accept("Launching emulator " + avdName + " on " + serial + "…");
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
        } catch (Exception e) {
            throw new EmulatorException("Could not start emulator process: " + e.getMessage(), e);
        }

        // Fire the callback immediately so the caller can expose the serial for live-screen
        // polling while the device is still booting.
        if (onSerialAllocated != null) {
            try { onSerialAllocated.accept(serial); } catch (Exception ignored) {}
        }

        try {
            waitForBoot(serial, process, logger);
        } catch (EmulatorException e) {
            try { process.destroyForcibly(); } catch (Exception ignored) {}
            try { adb.adb(serial, 15, "emu", "kill"); } catch (Exception ignored) {}
            throw e;
        }
        logger.accept("Emulator " + serial + " booted and ready.");
        return new BootedEmulator(serial, process, avdName);
    }

    private void waitForBoot(String serial, Process process, Consumer<String> logger) throws EmulatorException {
        int timeoutSec = props.getEmulatorBootTimeoutSec();
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        long nextProgressLog = System.currentTimeMillis() + 30_000L;
        // 1. Wait for the device to appear and adbd to be ready.
        adb.adb(serial, 120, "wait-for-device");
        // 2. Poll sys.boot_completed.
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive())
                throw new EmulatorException("Emulator process exited before boot completed.");
            String booted = adb.shell(serial, 10, "getprop", "sys.boot_completed").stdout().trim();
            String bootanim = adb.shell(serial, 10, "getprop", "init.svc.bootanim").stdout().trim();
            if ("1".equals(booted) && ("stopped".equals(bootanim) || bootanim.isEmpty())) {
                // Give the launcher a moment to settle.
                sleep(1500);
                // Wake the screen explicitly (KEYCODE_WAKEUP) before dismissing keyguard (KEYCODE_MENU).
                adb.shell(serial, 10, "input", "keyevent", "224");
                sleep(400);
                adb.shell(serial, 10, "input", "keyevent", "82");
                // Keep the screen on for the entire test run so screencap returns real frames.
                adb.shell(serial, 10, "settings", "put", "system", "screen_off_timeout", "2147483647");
                adb.shell(serial, 10, "settings", "put", "global", "stay_on_while_plugged_in", "7");
                adb.shell(serial, 10, "settings", "put", "secure", "screensaver_enabled", "0");
                return;
            }
            long now = System.currentTimeMillis();
            if (now >= nextProgressLog) {
                long remaining = (deadline - now) / 1000;
                logger.accept(serial + ": still booting (boot_completed=" + booted
                        + ", bootanim=" + bootanim + ", " + remaining + "s remaining)…");
                nextProgressLog = now + 30_000L;
            }
            sleep(1000);
        }
        throw new EmulatorException("Emulator did not finish booting within " + timeoutSec + "s.");
    }

    // ---- Device Manager AVD CRUD ----------------------------------------

    /** Names of all AVDs registered with the emulator (one per line from {@code emulator -list-avds}). */
    public List<String> listAvdNames() {
        CommandResult r = ProcessRunner.run(
                List.of(emulatorBin().getAbsolutePath(), "-list-avds"), 30);
        return r.stdout().lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .toList();
    }

    /**
     * Create a new AVD with the given parameters.
     * Device defaults to {@code pixel_9} if blank; sdcard defaults to {@code 512M}; ram to 2048.
     */
    public void createAvd(String name, int api, String device, int ramMb, String sdcard,
                           Consumer<String> logger) throws EmulatorException {
        if (!toolingAvailable())
            throw new EmulatorException("Android emulator tooling not found (" + toolingDiagnostics() + ").");
        if (!systemImageDir(api).isDirectory())
            throw new EmulatorException("System image not installed for API " + api
                    + ". Run: sdkmanager \"" + packageName(api) + "\"");

        String dev = (device == null || device.isBlank()) ? "pixel_9" : device;
        String sdc = (sdcard == null || sdcard.isBlank()) ? "512M" : sdcard;

        logger.accept("Creating AVD '" + name + "' for API " + api + "…");
        CommandResult r = runWithNo(List.of(
                avdManager().getAbsolutePath(), "create", "avd",
                "-n", name, "-k", packageName(api), "-d", dev, "--force"), 120);
        if (!avdExistsWithRetry(name))
            throw new EmulatorException(buildAvdCreateError(name, api, r));

        // Patch config.ini with requested RAM and sdcard size.
        File configIni = avdConfigIni(name);
        if (configIni.exists()) {
            patchIniKey(configIni, "hw.ramSize", String.valueOf(ramMb > 0 ? ramMb : 2048));
            patchIniKey(configIni, "sdcard.size", sdc);
        }
        logger.accept("AVD '" + name + "' created.");
    }

    /** Delete an AVD by name. */
    public void deleteAvd(String name, Consumer<String> logger) throws EmulatorException {
        if (!toolingAvailable())
            throw new EmulatorException("Android emulator tooling not found.");
        logger.accept("Deleting AVD '" + name + "'…");
        CommandResult r = ProcessRunner.run(
                List.of(avdManager().getAbsolutePath(), "delete", "avd", "-n", name), 60);
        if (r.exitCode() != 0 && avdExists(name))
            throw new EmulatorException("Failed to delete AVD '" + name + "': " + tail(r.combined()));
        logger.accept("AVD '" + name + "' deleted.");
    }

    /**
     * Start an emulator for the given AVD without waiting for boot.
     * Returns the allocated serial so the caller can track when it comes online.
     * Uses snapshot restore (no {@code -no-snapshot-save}) for fast re-launch in Device Manager.
     */
    public String startEmulatorAsync(String avdName, Consumer<String> logger) throws EmulatorException {
        if (!toolingAvailable())
            throw new EmulatorException("Android emulator tooling not found.");
        int port = pickFreeEvenPort();
        String serial = "emulator-" + port;
        List<String> cmd = new ArrayList<>(List.of(
                emulatorBin().getAbsolutePath(),
                "-avd", avdName,
                "-port", String.valueOf(port),
                "-no-audio", "-no-boot-anim",
                "-gpu", "angle_indirect",
                "-accel", "auto"));
        if (props.isEmulatorHeadless()) cmd.add("-no-window");
        logger.accept("Starting emulator '" + avdName + "' on " + serial + "…");
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            throw new EmulatorException("Could not start emulator: " + e.getMessage(), e);
        }
        return serial;
    }

    /** Absolute path to the config.ini of an AVD, regardless of whether it exists. */
    public File avdConfigIni(String name) {
        return new File(System.getProperty("user.home"), ".android/avd/" + name + ".avd/config.ini");
    }

    /** Update a single key in an INI file; appends it if not present. */
    public void patchIniKey(File ini, String key, String value) {
        try {
            String content = ini.exists()
                    ? java.nio.file.Files.readString(ini.toPath())
                    : "";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$");
            String updated = p.matcher(content).find()
                    ? p.matcher(content).replaceFirst(key + "=" + value)
                    : content + "\n" + key + "=" + value;
            java.nio.file.Files.writeString(ini.toPath(), updated);
        } catch (Exception ignored) {}
    }

    // ---- shutdown ----------------------------------------------------------

    /** Kill the emulator and wait briefly for the process to die. */
    public void shutdown(BootedEmulator emu, Consumer<String> logger) {
        if (emu == null) return;
        logger.accept("Shutting down emulator " + emu.serial() + "…");
        try { adb.adb(emu.serial(), 20, "emu", "kill"); } catch (Exception ignored) {}
        try {
            if (emu.process() != null) {
                emu.process().destroy();
                if (!emu.process().waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    emu.process().destroyForcibly();
                }
            }
        } catch (Exception ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /* ---- helpers --------------------------------------------------------- */

    /** Emulator console ports are even numbers in 5554..5680. */
    private int pickFreeEvenPort() {
        List<String> online = adb.onlineDevices();
        for (int port = 5554; port <= 5680; port += 2) {
            if (!online.contains("emulator-" + port)) return port;
        }
        return 5554;
    }

    /** Run a command piping "y" to stdin repeatedly (for sdkmanager licence prompts). */
    private CommandResult runWithYes(List<String> cmd, long timeoutSec) {
        return ProcessRunner.run(List.of("/bin/sh", "-c", "yes | " + shellJoin(cmd)), timeoutSec);
    }

    /** Run a command piping "no" to stdin (for avdmanager custom-hardware prompt). */
    private CommandResult runWithNo(List<String> cmd, long timeoutSec) {
        return ProcessRunner.run(List.of("/bin/sh", "-c", "echo no | " + shellJoin(cmd)), timeoutSec);
    }

    private String shellJoin(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('\'').append(c.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
    }

    private String tail(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 300 ? "…" + s.substring(s.length() - 300) : s;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
