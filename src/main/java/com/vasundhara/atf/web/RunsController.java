package com.vasundhara.atf.web;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.device.QrPairingService;
import com.vasundhara.atf.engine.ExecutionLockService;
import com.vasundhara.atf.model.TestRun;
import com.vasundhara.atf.report.ReportStore;
import com.vasundhara.atf.web.dto.RunSummary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Shared REST API backing the dashboard: device status/connection, the unified Test Runs list
 * (every module's finished runs, including "shadow" records {@link com.vasundhara.atf.report.RunBridgeService}
 * writes for non-New-Test modules), and generic run artifacts. Deliberately module-agnostic — it
 * has no knowledge of any specific test category or orchestrator; each module (Smart Execution,
 * Compatibility, Localization, Ads, Remote Config, Test Case) owns its own start/stop/report
 * endpoints under its own {@code /api/<module>} prefix.
 */
@RestController
@RequestMapping("/api")
public class RunsController {

    private final ReportStore store;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final AdbClient adb;
    private final QrPairingService qrPairing;
    private final AtfProperties props;
    private final ExecutionLockService execLock;

    public RunsController(ReportStore store, DeviceManager deviceManager, DriverFactory driverFactory,
                          AdbClient adb, QrPairingService qrPairing, AtfProperties props,
                          ExecutionLockService execLock) {
        this.store = store;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.adb = adb;
        this.qrPairing = qrPairing;
        this.props = props;
        this.execLock = execLock;
    }

    /** Current execution lock state — polled by the frontend to enforce single-execution UX. */
    @GetMapping("/execution-lock")
    public Map<String, Object> executionLock() {
        return execLock.getLockInfo()
                .<Map<String, Object>>map(li -> Map.of("locked", true, "module", li.module(), "id", li.id()))
                .orElse(Map.of("locked", false));
    }

    @GetMapping("/me")
    public Map<String, String> me(java.security.Principal principal) {
        return Map.of("username", principal != null ? principal.getName() : "");
    }

    @GetMapping("/device")
    public Map<String, Object> device() {
        List<AdbClient.DeviceEntry> allEntries = adb.allDevices();
        List<String> online = allEntries.stream().filter(AdbClient.DeviceEntry::isOnline)
                .map(AdbClient.DeviceEntry::serial).toList();
        Optional<String> selected = deviceManager.selectDevice();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("online", online);
        out.put("appiumReachable", driverFactory.isAppiumReachable());
        out.put("appiumUrl", props.getAppiumServerUrl());

        // Profiling each online device is ~7 sequential adb shell round-trips (model, manufacturer,
        // release, sdk, screen size, density, abilist). Profiling every device in parallel bounds
        // the wall-clock cost to the single slowest device instead of the sum of all of them.
        Map<String, Map<String, Object>> profiled = allEntries.parallelStream()
                .filter(AdbClient.DeviceEntry::isOnline)
                .collect(Collectors.toConcurrentMap(AdbClient.DeviceEntry::serial, e -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    try {
                        DeviceManager.DeviceInfo di = deviceManager.profile(e.serial());
                        m.put("model", (di.manufacturer() + " " + di.model()).trim());
                        m.put("android", di.androidRelease() + " (API " + di.sdkInt() + ")");
                        m.put("screen", di.widthPx() + "x" + di.heightPx() + " @" + di.densityDpi() + "dpi");
                        m.put("abis", di.abis());
                    } catch (Exception ignored) {
                        // Profiling failed (transient adb hiccup) — still report the serial below.
                    }
                    return m;
                }));

        List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (AdbClient.DeviceEntry e : allEntries) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("serial", e.serial());
            m.put("state", e.state());
            m.put("isEmulator", e.isEmulator());
            m.put("isOnline", e.isOnline());
            if (e.isOnline()) m.putAll(profiled.getOrDefault(e.serial(), Map.of()));
            all.add(m);
        }
        out.put("all", all);

        if (selected.isPresent()) {
            Map<String, Object> p = profiled.get(selected.get());
            Map<String, Object> sel = new java.util.LinkedHashMap<>();
            sel.put("serial", selected.get());
            if (p != null) sel.putAll(p);
            out.put("selected", sel);
        }
        return out;
    }

    /**
     * Connect a real device over wireless ADB. The PAIRING port and the CONNECTION port are
     * distinct on Android 11+ — pairing uses the port from the "Pair device with code" screen,
     * while {@code adb connect} must use the port from the "Wireless debugging" main screen.
     */
    @PostMapping("/device/connect")
    public ResponseEntity<Map<String, Object>> connectDevice(@RequestBody Map<String, String> body) {
        String host = body.getOrDefault("host", "").trim();
        String legacy = body.getOrDefault("port", "").trim();
        String pairingPort = firstNonBlank(body.get("pairingPort"), legacy);
        String connectionPort = firstNonBlank(body.get("connectionPort"), legacy);
        String code = body.getOrDefault("code", "").trim();
        if (host.isEmpty() || !host.matches("[\\w.\\-:]+")) {
            return ResponseEntity.badRequest().body(Map.of("status", "failed", "reason", "A valid device IP address is required."));
        }

        StringBuilder out = new StringBuilder();

        if (!code.isEmpty()) {
            if (!pairingPort.matches("\\d{1,5}")) {
                return ResponseEntity.badRequest().body(Map.of("status", "failed",
                        "reason", "Pairing Port is required for code pairing — use the port shown on the "
                                + "'Pair device with pairing code' screen."));
            }
            var pair = adb.pair(host, pairingPort, code);
            out.append("$ adb pair ").append(host).append(":").append(pairingPort).append(" ").append(code).append('\n')
               .append(pair.combined().trim()).append('\n');
            if (!pair.combined().toLowerCase().contains("successfully paired")) {
                return ResponseEntity.ok(Map.of("status", "failed",
                        "reason", "Pairing code expired ⏱ — Open Wireless Debugging on the device → "
                                + "Pair device with pairing code → get a FRESH code and try again immediately "
                                + "(also confirm the pairing IP and Pairing Port match that screen).",
                        "output", out.toString().trim()));
            }
        }

        String connectPort = connectionPort;
        if (!connectPort.matches("\\d{1,5}")) {
            String discovered = discoverConnectPort(host, out);
            if (discovered != null) connectPort = discovered;
        }
        if (!connectPort.matches("\\d{1,5}")) {
            return ResponseEntity.ok(Map.of("status", "failed",
                    "reason", "Connection Port is required. Open 'Wireless debugging' on the device and read the "
                            + "port from 'IP address & Port' (e.g. 30.0.255.92:39123 → use 39123). It is different "
                            + "from the pairing port and could not be auto-discovered via adb mDNS.",
                    "output", out.toString().trim()));
        }

        var connect = adb.connect(host, connectPort);
        out.append("$ adb connect ").append(host).append(":").append(connectPort).append('\n')
           .append(connect.combined().trim()).append('\n');

        List<String> online = adb.onlineDevices();
        out.append("$ adb devices\n").append(String.join("\n", online));
        final String cport = connectPort;
        String serial = online.stream().filter(s -> s.equals(host + ":" + cport)).findFirst()
                .orElseGet(() -> online.stream().filter(s -> s.startsWith(host + ":")).findFirst()
                .orElseGet(() -> online.stream().filter(s -> s.startsWith(host)).findFirst().orElse(null)));

        if (serial != null) {
            String model = adb.getProp(serial, "ro.product.model");
            String maker = adb.getProp(serial, "ro.product.manufacturer");
            String rel = adb.getProp(serial, "ro.build.version.release");
            String name = ((maker + " " + model).trim().isEmpty() ? serial : (maker + " " + model).trim())
                    + (rel.isEmpty() ? "" : " · Android " + rel);
            return ResponseEntity.ok(Map.of("status", "connected", "serial", serial, "name", name,
                    "output", out.toString().trim()));
        }
        String low = connect.combined().toLowerCase();
        String reason = (low.contains("fail") || low.contains("refused") || low.contains("cannot"))
                ? connect.combined().trim()
                : "Connected command ran but the device did not appear in `adb devices`. Ensure the Connection Port "
                  + "matches the 'Wireless debugging' main screen and the device & PC are on the same WiFi network.";
        return ResponseEntity.ok(Map.of("status", "failed", "reason", reason, "output", out.toString().trim()));
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    /** Discover the adb-connect TCP port for {@code host} from `adb mdns services`. */
    private String discoverConnectPort(String host, StringBuilder out) {
        try {
            String res = adb.adb(null, 10, "mdns", "services").combined();
            out.append("$ adb mdns services\n").append(res.trim()).append('\n');
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote(host) + ":(\\d{1,5})").matcher("");
            for (String line : res.split("\\R")) {
                if (line.contains("_adb-tls-connect") && line.contains(host)) {
                    m.reset(line);
                    if (m.find()) {
                        out.append("Discovered connection port via mDNS: ").append(m.group(1)).append('\n');
                        return m.group(1);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Discover devices advertising adb wireless services on the LAN via {@code adb mdns services}.
     * Auto-fills the pairing IP/port and connection port so the tester only needs the 6-digit code.
     */
    @GetMapping("/device/discover")
    public Map<String, Object> discoverDevices() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String res;
        try {
            res = adb.adb(null, 10, "mdns", "services").combined();
        } catch (Exception e) {
            out.put("supported", false);
            out.put("reason", "adb mdns is not available on this host.");
            return out;
        }
        out.put("raw", res.trim());
        boolean supported = !res.toLowerCase().contains("unknown command") && !res.toLowerCase().contains("not support");
        out.put("supported", supported);

        java.util.regex.Pattern ipPort = java.util.regex.Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})");
        String pairIp = null, pairPort = null, connIp = null, connPort = null;
        for (String line : res.split("\\R")) {
            java.util.regex.Matcher m = ipPort.matcher(line);
            if (!m.find()) continue;
            if (line.contains("_adb-tls-pairing") && pairIp == null) { pairIp = m.group(1); pairPort = m.group(2); }
            else if (line.contains("_adb-tls-connect") && connIp == null) { connIp = m.group(1); connPort = m.group(2); }
        }
        if (pairIp != null) { out.put("pairingIp", pairIp); out.put("pairingPort", pairPort); }
        if (connIp != null) { out.put("connectIp", connIp); out.put("connectionPort", connPort); }
        out.put("found", pairIp != null || connIp != null);
        return out;
    }

    /** Start a QR-code pairing session — returns a 250x250 QR PNG (base64) + status info. */
    @PostMapping("/device/qr/start")
    public Map<String, Object> qrStart() {
        return qrPairing.start();
    }

    /** Poll QR pairing status — reports a newly connected device, or waiting/expired. */
    @GetMapping("/device/qr/status")
    public Map<String, Object> qrStatus() {
        return qrPairing.status();
    }

    /** Disconnect a wireless device by serial (IP:port). */
    @PostMapping("/device/disconnect")
    public ResponseEntity<Map<String, Object>> disconnectDevice(@RequestBody Map<String, String> body) {
        String serial = body.getOrDefault("serial", "").trim();
        if (serial.isEmpty()) return ResponseEntity.badRequest().body(Map.of("status", "failed", "reason", "serial required"));
        var r = adb.disconnect(serial);
        return ResponseEntity.ok(Map.of("status", "ok", "output", r.combined().trim()));
    }

    /** Unified Test Runs list / Dashboard stats / Reports KPIs — every module's runs, including
     *  shadow records {@link com.vasundhara.atf.report.RunBridgeService} writes on their behalf. */
    @GetMapping("/runs")
    public List<RunSummary> runs() {
        return store.all().stream().map(RunSummary::from).toList();
    }

    @GetMapping("/runs/{id}")
    public TestRun run(@PathVariable("id") String id) {
        TestRun run = store.get(id);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        return run;
    }

    /** Delete a single test run from the store and database. Running runs cannot be deleted. */
    @DeleteMapping("/runs/{id}")
    public ResponseEntity<Map<String, String>> deleteRun(@PathVariable("id") String id) {
        TestRun run = store.get(id);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        if (run.getState() == com.vasundhara.atf.model.RunState.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a run that is actively RUNNING. Stop it first.");
        }
        store.delete(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    /** Bulk-delete multiple test runs by id. Skips any that are actively RUNNING. */
    @DeleteMapping("/runs")
    public ResponseEntity<Map<String, Object>> deleteRuns(@RequestBody List<String> ids) {
        List<String> deleted = ids.stream()
                .filter(id -> {
                    TestRun r = store.get(id);
                    if (r == null) return false;
                    return r.getState() != com.vasundhara.atf.model.RunState.RUNNING;
                })
                .filter(store::delete)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("deleted", deleted, "count", deleted.size()));
    }

    /** Last-good screenshot per run — served when a fresh capture fails (e.g. between versions).
     *  Consumed by the Compatibility Testing live-view, keyed off the run's {@code compatCurrentSerial}
     *  live-progress entry. */
    private final ConcurrentHashMap<String, byte[]> liveScreenCache = new ConcurrentHashMap<>();

    @GetMapping(value = "/runs/{id}/live-screen", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> liveScreen(@PathVariable("id") String id) {
        TestRun run = store.get(id);
        if (run == null) return ResponseEntity.notFound().build();

        Object serialObj = run.getLiveProgress().get("compatCurrentSerial");
        if (serialObj instanceof String serial && !serial.isBlank()) {
            try { adb.shell(serial, 5, "input", "keyevent", "224"); } catch (Exception ignored) {}
            byte[] png = adb.screencapPng(serial);
            if (png != null && png.length > 100) {
                liveScreenCache.put(id, png);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-cache, no-store, must-revalidate")
                        .header("Pragma", "no-cache")
                        .body(png);
            }
        }
        byte[] cached = liveScreenCache.get(id);
        if (cached != null) {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .body(cached);
        }
        return ResponseEntity.noContent().build();
    }

    /** Live device screen for the shared "Test Running" fallback page's Live Device panel, for any
     *  run/category. Monitoring only: a plain {@code adb screencap}, entirely independent of the
     *  Appium session driving the run, so polling it can never interfere with execution. Uses the
     *  run's own {@code deviceSerial} (unlike {@link #liveScreen}, which is specific to the
     *  Compatibility Testing live-view and keyed off {@code compatCurrentSerial}). */
    private final ConcurrentHashMap<String, byte[]> deviceScreenCache = new ConcurrentHashMap<>();

    @GetMapping(value = "/runs/{id}/device-screen", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> deviceScreen(@PathVariable("id") String id) {
        TestRun run = store.get(id);
        if (run == null) return ResponseEntity.notFound().build();

        String serial = run.getDeviceSerial();
        if (serial != null && !serial.isBlank() && adb.onlineDevices().contains(serial)) {
            try { adb.shell(serial, 5, "input", "keyevent", "224"); } catch (Exception ignored) {}
            byte[] png = adb.screencapPng(serial);
            if (png != null && png.length > 100) {
                deviceScreenCache.put(id, png);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-cache, no-store, must-revalidate")
                        .header("Pragma", "no-cache")
                        .body(png);
            }
        }
        byte[] cached = deviceScreenCache.get(id);
        if (cached != null) {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .body(cached);
        }
        return ResponseEntity.noContent().build();
    }

    /** Serve a run artifact (e.g. screenshot, or Localization's per-language report.html) by
     *  relative path, guarding against traversal. */
    @GetMapping("/runs/{id}/artifacts/{*relative}")
    public ResponseEntity<Resource> artifactByPath(@PathVariable("id") String id,
                                                   @PathVariable("relative") String relative) throws Exception {
        Path base = Path.of(props.getWorkDir(), id).toAbsolutePath().normalize();
        Path target = base.resolve(relative.startsWith("/") ? relative.substring(1) : relative)
                .normalize();
        if (!target.startsWith(base) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        String name = target.toString();
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : name.endsWith(".html") || name.endsWith(".htm") ? MediaType.TEXT_HTML
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(target));
    }
}
