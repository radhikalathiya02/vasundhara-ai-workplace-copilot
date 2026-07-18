package com.vasundhara.atf.web;

import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.EmulatorManager;
import com.vasundhara.atf.dm.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST API for the Device Manager module.
 *
 * <pre>
 * GET  /api/dm/devices                                 — all adb devices with hardware details
 * GET  /api/dm/avds                                    — all AVDs (running + stopped)
 * POST /api/dm/avds                                    — create AVD
 * DELETE /api/dm/avds/{name}                           — delete AVD
 * PATCH /api/dm/avds/{name}                            — edit AVD RAM / sdcard
 * POST /api/dm/avds/{name}/start                       — start emulator (async)
 * POST /api/dm/devices/{serial}/stop                   — stop emulator / disconnect wireless device
 * POST /api/dm/devices/{serial}/restart                — restart emulator or reboot physical device
 * GET  /api/dm/devices/{serial}/screenshot             — live PNG screencap
 * POST /api/dm/multi-run                               — start parallel smoke test
 * GET  /api/dm/multi-run/{id}                          — poll session state
 * GET  /api/dm/multi-run/{id}/devices/{serial}/screenshot — per-device live PNG
 * GET  /api/dm/multi-run/{id}/report.html              — consolidated HTML report
 * </pre>
 */
@RestController
@RequestMapping("/api/dm")
public class DeviceManagerController {

    private final AdbClient adb;
    private final EmulatorManager emulators;
    private final MultiDeviceRunner runner;
    private final MultiDeviceSessionStore store;

    /** Screen-cache for live screenshots per serial (used by the device screenshot endpoint). */
    private final ConcurrentHashMap<String, byte[]> screenCache = new ConcurrentHashMap<>();

    public DeviceManagerController(AdbClient adb, EmulatorManager emulators,
                                    MultiDeviceRunner runner, MultiDeviceSessionStore store) {
        this.adb = adb;
        this.emulators = emulators;
        this.runner = runner;
        this.store = store;
    }

    // ---- Devices -----------------------------------------------------------

    @GetMapping("/devices")
    public List<DeviceInfoDto> listDevices() {
        return adb.allDevices().stream().map(entry -> {
            String serial = entry.serial();
            String status = switch (entry.state()) {
                case "device"       -> "online";
                case "offline"      -> "offline";
                case "unauthorized" -> "unauthorized";
                default             -> entry.state();
            };
            if (!entry.isOnline()) {
                return new DeviceInfoDto(serial,
                        entry.isEmulator() ? serial : "Device (" + status + ")",
                        "", "", "", 0, status, 0, "", "", entry.isEmulator(), null);
            }
            String model        = adb.getProp(serial, "ro.product.model");
            String manufacturer = adb.getProp(serial, "ro.product.manufacturer");
            String androidVer   = adb.getProp(serial, "ro.build.version.release");
            int    api          = parseInt(adb.getProp(serial, "ro.build.version.sdk"));
            String abi          = adb.getProp(serial, "ro.product.cpu.abi");
            long   ramMb        = adb.ramMb(serial);
            int[]  size         = adb.screenSize(serial);
            String resolution   = size[0] + "x" + size[1];
            String avdName      = entry.isEmulator() ? adb.emulatorAvdName(serial) : null;
            String displayName  = entry.isEmulator()
                    ? ((avdName != null && !avdName.isBlank()) ? avdName + " (API " + api + ")" : serial)
                    : ((manufacturer + " " + model).trim());
            return new DeviceInfoDto(serial, displayName, model, manufacturer, androidVer, api,
                    status, ramMb, resolution, abi, entry.isEmulator(),
                    (avdName == null || avdName.isBlank()) ? null : avdName);
        }).toList();
    }

    // ---- AVDs --------------------------------------------------------------

    @GetMapping("/avds")
    public List<AvdInfo> listAvds() {
        // Build map of running AVD name → serial for cross-referencing.
        Map<String, String> runningByAvd = new HashMap<>();
        for (AdbClient.DeviceEntry e : adb.allDevices()) {
            if (e.isEmulator() && e.isOnline()) {
                String name = adb.emulatorAvdName(e.serial());
                if (!name.isBlank()) runningByAvd.put(name, e.serial());
            }
        }

        return emulators.listAvdNames().stream().map(name -> {
            File ini = emulators.avdConfigIni(name);
            Map<String, String> cfg = parseIni(ini);
            String sysDir = cfg.getOrDefault("image.sysdir.1", "");
            int    api    = parseApiFromSysDir(sysDir);
            String abi    = parseAbiFromSysDir(sysDir);
            int    ramMb  = parseInt(cfg.getOrDefault("hw.ramSize", "0"));
            String device = cfg.getOrDefault("hw.device.name", "");
            String sdcard = cfg.getOrDefault("sdcard.size", "");
            String serial = runningByAvd.get(name);
            String displayName = device.isBlank() ? name : device + " (API " + api + ")";
            return new AvdInfo(name, displayName, api, apiToAndroidVersion(api),
                    device, abi, sdcard, ramMb, serial != null, serial);
        }).toList();
    }

    @PostMapping("/avds")
    public ResponseEntity<Map<String, String>> createAvd(@RequestBody Map<String, Object> body) {
        String name   = str(body, "name");
        int    api    = num(body, "api");
        String device = str(body, "device");
        int    ramMb  = num(body, "ramMb");
        String sdcard = str(body, "sdcard");
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required.");
        if (api <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "api level is required.");
        List<String> log = new ArrayList<>();
        try {
            emulators.createAvd(name, api, device, ramMb, sdcard, log::add);
        } catch (EmulatorManager.EmulatorException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "log", String.join("\n", log)));
        }
        return ResponseEntity.ok(Map.of("name", name, "log", String.join("\n", log)));
    }

    @DeleteMapping("/avds/{name}")
    public ResponseEntity<Map<String, String>> deleteAvd(@PathVariable String name) {
        List<String> log = new ArrayList<>();
        try {
            emulators.deleteAvd(name, log::add);
        } catch (EmulatorManager.EmulatorException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("deleted", name));
    }

    @PatchMapping("/avds/{name}")
    public ResponseEntity<Map<String, String>> editAvd(@PathVariable String name,
                                                        @RequestBody Map<String, Object> body) {
        File ini = emulators.avdConfigIni(name);
        if (!ini.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AVD not found.");
        int    ramMb  = num(body, "ramMb");
        String sdcard = str(body, "sdcard");
        if (ramMb  > 0)       emulators.patchIniKey(ini, "hw.ramSize",   String.valueOf(ramMb));
        if (sdcard != null && !sdcard.isBlank()) emulators.patchIniKey(ini, "sdcard.size", sdcard);
        return ResponseEntity.ok(Map.of("updated", name));
    }

    @PostMapping("/avds/{name}/start")
    public ResponseEntity<Map<String, String>> startEmulator(@PathVariable String name) {
        List<String> log = new ArrayList<>();
        try {
            String serial = emulators.startEmulatorAsync(name, log::add);
            return ResponseEntity.accepted().body(Map.of("serial", serial, "log", String.join("\n", log)));
        } catch (EmulatorManager.EmulatorException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ---- Device actions ----------------------------------------------------

    @PostMapping("/devices/{serial}/stop")
    public ResponseEntity<Map<String, String>> stopDevice(@PathVariable String serial) {
        try {
            if (serial.startsWith("emulator-")) {
                adb.adb(serial, 20, "emu", "kill");
            } else {
                // For physical devices connected over TCP/IP, disconnect.
                // For USB devices we can't power them off, so just return a graceful message.
                if (serial.contains(":")) {
                    adb.disconnect(serial);
                } else {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "USB physical devices cannot be stopped via Device Manager."));
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("stopped", serial));
    }

    @PostMapping("/devices/{serial}/restart")
    public ResponseEntity<Map<String, String>> restartDevice(@PathVariable String serial) {
        try {
            if (serial.startsWith("emulator-")) {
                // Get AVD name before killing
                String avdName = adb.emulatorAvdName(serial);
                adb.adb(serial, 20, "emu", "kill");
                // Brief pause then relaunch
                Thread.sleep(1500);
                if (!avdName.isBlank()) {
                    List<String> log = new ArrayList<>();
                    String newSerial = emulators.startEmulatorAsync(avdName, log::add);
                    return ResponseEntity.accepted().body(Map.of("restarting", avdName, "serial", newSerial));
                }
                return ResponseEntity.ok(Map.of("stopped", serial, "note", "AVD name unknown; relaunch manually."));
            } else {
                adb.adb(serial, 30, "reboot");
                return ResponseEntity.ok(Map.of("rebooting", serial));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/devices/{serial}/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> deviceScreenshot(@PathVariable String serial) {
        try { adb.shell(serial, 5, "input", "keyevent", "224"); } catch (Exception ignored) {}
        byte[] png = adb.screencapPng(serial);
        if (png != null && png.length > 100) {
            screenCache.put(serial, png);
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .body(png);
        }
        byte[] cached = screenCache.get(serial);
        return cached != null
                ? ResponseEntity.ok().header("Cache-Control", "no-cache").body(cached)
                : ResponseEntity.noContent().build();
    }

    // ---- Multi-device runs -------------------------------------------------

    @PostMapping(value = "/multi-run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> startMultiRun(
            @RequestParam("file")    MultipartFile file,
            @RequestParam("serials") String serialsCsv) throws Exception {

        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");

        List<String> serials = Arrays.stream(serialsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (serials.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one device serial is required.");

        // Verify all serials are online
        Set<String> online = new HashSet<>(adb.onlineDevices());
        List<String> offline = serials.stream().filter(s -> !online.contains(s)).toList();
        if (!offline.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "These devices are not online: " + String.join(", ", offline));

        String id = UUID.randomUUID().toString();
        // Save APK
        Path apkPath = Path.of(System.getProperty("java.io.tmpdir"), "dm-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, apkPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Build device display names
        Map<String, String> displayNames = new HashMap<>();
        for (DeviceInfoDto d : listDevices()) displayNames.put(d.serial(), d.displayName());

        List<DeviceSlotResult> slots = serials.stream()
                .map(s -> new DeviceSlotResult(s, displayNames.getOrDefault(s, s)))
                .toList();

        MultiDeviceSession session = new MultiDeviceSession(id, original, slots,
                System.currentTimeMillis());
        store.save(session);
        runner.run(session, apkPath.toFile());

        return ResponseEntity.ok(Map.of("id", id, "devices", String.valueOf(serials.size())));
    }

    @GetMapping("/multi-run/{id}")
    public ResponseEntity<MultiDeviceSession> getMultiRun(@PathVariable String id) {
        MultiDeviceSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return ResponseEntity.ok(s);
    }

    @GetMapping("/multi-run")
    public List<MultiDeviceSession> listMultiRuns() {
        return store.all().stream()
                .sorted(Comparator.comparingLong(MultiDeviceSession::getCreatedAt).reversed())
                .toList();
    }

    @GetMapping(value = "/multi-run/{id}/devices/{serial}/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> multiRunScreenshot(@PathVariable String id,
                                                      @PathVariable String serial) {
        MultiDeviceSession session = store.get(id);
        if (session == null) return ResponseEntity.notFound().build();

        DeviceSlotResult slot = session.getSlots().stream()
                .filter(s -> s.getSerial().equals(serial)).findFirst().orElse(null);
        if (slot == null) return ResponseEntity.notFound().build();

        // Live screencap during active states
        String state = slot.getState();
        if ("INSTALLING".equals(state) || "LAUNCHING".equals(state) || "RUNNING".equals(state)) {
            try { adb.shell(serial, 5, "input", "keyevent", "224"); } catch (Exception ignored) {}
            byte[] png = adb.screencapPng(serial);
            if (png != null && png.length > 100) return ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate").body(png);
        }
        // Fall back to the final screenshot saved at end of run
        byte[] saved = slot.getFinalScreenshot();
        return saved != null
                ? ResponseEntity.ok().header("Cache-Control", "no-cache").body(saved)
                : ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/multi-run/{id}/report.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> multiRunReport(@PathVariable String id) {
        MultiDeviceSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return ResponseEntity.ok(buildHtmlReport(session));
    }

    // ---- Helpers -----------------------------------------------------------

    private Map<String, String> parseIni(File ini) {
        Map<String, String> map = new LinkedHashMap<>();
        if (ini == null || !ini.exists()) return map;
        try {
            for (String line : Files.readAllLines(ini.toPath())) {
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) {}
        return map;
    }

    private int parseApiFromSysDir(String sysDir) {
        Matcher m = Pattern.compile("android-(\\d+)").matcher(sysDir);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private String parseAbiFromSysDir(String sysDir) {
        // e.g. system-images/android-35/google_apis/arm64-v8a/
        String[] parts = sysDir.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (!p.isBlank() && !p.equals("system-images") && !p.startsWith("android-")
                    && !p.startsWith("google")) return p;
        }
        return "";
    }

    private String apiToAndroidVersion(int api) {
        return switch (api) {
            case 28 -> "Android 9";   case 29 -> "Android 10";  case 30 -> "Android 11";
            case 31, 32 -> "Android 12"; case 33 -> "Android 13"; case 34 -> "Android 14";
            case 35 -> "Android 15";  case 36 -> "Android 16";
            default -> api > 0 ? "API " + api : "";
        };
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key); return v == null ? null : v.toString();
    }

    private int num(Map<String, Object> m, String key) {
        try { Object v = m.get(key); return v == null ? 0 : Integer.parseInt(v.toString()); }
        catch (Exception e) { return 0; }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private String buildHtmlReport(MultiDeviceSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
          .append("<title>Multi-Device Report</title>")
          .append("<style>body{font-family:system-ui,sans-serif;margin:32px;background:#0e1117;color:#e2e8f0}")
          .append("h1{font-size:22px;margin-bottom:4px}table{border-collapse:collapse;width:100%;margin-top:20px}")
          .append("th,td{padding:10px 14px;text-align:left;border-bottom:1px solid #2d3748;font-size:13px}")
          .append("th{background:#1a202c;font-weight:600;color:#a0aec0}")
          .append(".pass{color:#48bb78}.fail{color:#fc8181}.err{color:#fbb6ce}.pen{color:#a0aec0}")
          .append(".badge{display:inline-block;padding:3px 9px;border-radius:20px;font-size:11px;font-weight:700}")
          .append(".b-pass{background:rgba(72,187,120,.18);color:#48bb78}")
          .append(".b-fail{background:rgba(252,129,129,.18);color:#fc8181}")
          .append(".b-err{background:rgba(251,182,206,.18);color:#fbb6ce}")
          .append(".b-run{background:rgba(160,174,192,.12);color:#a0aec0}")
          .append("</style></head><body>")
          .append("<h1>Multi-Device Smoke Test Report</h1>")
          .append("<p style='color:#718096;font-size:13px'>APK: <b>").append(esc(session.getApkFileName()))
          .append("</b> &nbsp;·&nbsp; Devices: ").append(session.getSlots().size())
          .append(" &nbsp;·&nbsp; Overall: <b>").append(esc(session.getOverallStatus())).append("</b></p>");

        sb.append("<table><thead><tr>")
          .append("<th>Device</th><th>Serial</th><th>Status</th><th>Score</th>")
          .append("<th>Crashes</th><th>ANRs</th><th>Screens</th><th>Findings</th>")
          .append("</tr></thead><tbody>");

        for (DeviceSlotResult slot : session.getSlots()) {
            String bdg = switch (slot.getStatus()) {
                case "PASS"  -> "b-pass"; case "FAIL" -> "b-fail";
                case "ERROR" -> "b-err";  default      -> "b-run";
            };
            sb.append("<tr>")
              .append("<td>").append(esc(slot.getDisplayName())).append("</td>")
              .append("<td style='color:#718096;font-family:monospace'>").append(esc(slot.getSerial())).append("</td>")
              .append("<td><span class='badge ").append(bdg).append("'>").append(esc(slot.getStatus())).append("</span></td>")
              .append("<td>").append(slot.getScore()).append("/100</td>")
              .append("<td>").append(slot.getCrashCount()).append("</td>")
              .append("<td>").append(slot.getAnrCount()).append("</td>")
              .append("<td>").append(slot.getScreensExplored()).append("</td>")
              .append("<td style='font-size:12px;color:#a0aec0'>")
              .append(slot.getFindings().isEmpty() ? "None" : String.join("; ", slot.getFindings()))
              .append("</td></tr>");
        }

        sb.append("</tbody></table>")
          .append("<h2 style='margin-top:32px;font-size:16px'>Session Log</h2>")
          .append("<pre style='font-size:12px;color:#718096;line-height:1.6;background:#1a202c;")
          .append("padding:16px;border-radius:8px;overflow:auto'>");
        session.getLogs().forEach(l -> sb.append(esc(l)).append("\n"));
        sb.append("</pre></body></html>");
        return sb.toString();
    }
}
