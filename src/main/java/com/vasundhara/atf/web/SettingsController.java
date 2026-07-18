package com.vasundhara.atf.web;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.config.SettingsService;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.device.EmulatorManager;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and updates persistent framework configuration. Changes are written to disk
 * and applied to the live {@link AtfProperties} bean, so they take effect on the next
 * run and survive restarts. Also exposes environment diagnostics and a live log tail
 * for the dashboard's Settings module.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final AdbClient adb;
    private final DriverFactory driverFactory;
    private final DeviceManager deviceManager;
    private final EmulatorManager emulators;
    private final AtfProperties props;

    public SettingsController(SettingsService settings, AdbClient adb, DriverFactory driverFactory,
                              DeviceManager deviceManager, EmulatorManager emulators, AtfProperties props) {
        this.settings = settings;
        this.adb = adb;
        this.driverFactory = driverFactory;
        this.deviceManager = deviceManager;
        this.emulators = emulators;
        this.props = props;
    }

    /** Current settings + their section grouping + config defaults + environment diagnostics. */
    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings.snapshot());
        out.put("groups", settings.groups());
        out.put("defaults", settings.defaults());
        out.put("diagnostics", diagnostics());
        return out;
    }

    /** Apply a partial settings update; returns the new full snapshot. */
    @PutMapping
    public Map<String, Object> update(@RequestBody(required = false) Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings.update(patch));
        out.put("diagnostics", diagnostics());
        return out;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings.reset());
        out.put("diagnostics", diagnostics());
        return out;
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = new LinkedHashMap<>();
        List<String> online;
        boolean adbOk;
        try { online = adb.onlineDevices(); adbOk = true; }
        catch (Exception e) { online = List.of(); adbOk = false; }
        d.put("adbPath", props.getAdbPath());
        d.put("adbWorking", adbOk);
        d.put("onlineDevices", online);
        d.put("appiumUrl", props.getAppiumServerUrl());
        d.put("appiumReachable", safe(driverFactory::isAppiumReachable));
        d.put("selectedDevice", deviceManager.selectDevice().orElse(""));
        d.put("sdkRoot", emulators.sdkRoot() == null ? "" : emulators.sdkRoot().getAbsolutePath());
        d.put("emulatorToolingAvailable", safeBool(emulators::toolingAvailable));
        d.put("emulatorDiagnostics", safeStr(emulators::toolingDiagnostics));
        d.put("logFile", logFile().toAbsolutePath().toString());
        d.put("settingsFile", Path.of("data", "settings.json").toAbsolutePath().toString());
        return d;
    }

    /** Tail the application log file for the Settings → Logs panel. */
    @GetMapping("/logs")
    public ResponseEntity<String> logs(@RequestParam(value = "lines", defaultValue = "300") int lines,
                                       @RequestParam(value = "download", defaultValue = "false") boolean download) {
        Path f = logFile();
        String body;
        try {
            body = Files.exists(f) ? tail(f, Math.max(1, Math.min(5000, lines)))
                                   : "No log file yet (" + f.toAbsolutePath() + ").";
        } catch (Exception e) {
            body = "Could not read log file: " + e.getMessage();
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN);
        if (download) b.header("Content-Disposition", "attachment; filename=atf.log");
        return b.body(body);
    }

    private Path logFile() {
        return Path.of("data", "logs", "atf.log");
    }

    /** Read the last {@code n} lines of a (potentially large) text file efficiently. */
    private String tail(Path path, int n) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            long pos = len - 1;
            int newlines = 0;
            Deque<Byte> buf = new ArrayDeque<>();
            while (pos >= 0 && newlines <= n) {
                raf.seek(pos);
                int c = raf.read();
                if (c == '\n') {
                    newlines++;
                    if (newlines > n) break;
                }
                buf.addFirst((byte) c);
                pos--;
            }
            byte[] bytes = new byte[buf.size()];
            int i = 0;
            for (Byte bb : buf) bytes[i++] = bb;
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private interface BoolSupplier { boolean get(); }
    private interface StrSupplier { String get(); }

    private boolean safe(BoolSupplier s) { try { return s.get(); } catch (Exception e) { return false; } }
    private boolean safeBool(BoolSupplier s) { try { return s.get(); } catch (Exception e) { return false; } }
    private String safeStr(StrSupplier s) { try { return s.get(); } catch (Exception e) { return ""; } }
}
