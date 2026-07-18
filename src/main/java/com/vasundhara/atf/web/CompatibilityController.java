package com.vasundhara.atf.web;

import com.vasundhara.atf.compat.CompatMatrixRunner;
import com.vasundhara.atf.compat.CompatSession;
import com.vasundhara.atf.compat.CompatSessionStore;
import com.vasundhara.atf.compat.CompatVersionResult;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.engine.ExecutionLockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST endpoints for the automatic Android 9–16 compatibility matrix.
 *
 * <pre>
 * POST /api/compat/analyze       — upload APK, create session, start the emulator matrix
 * GET  /api/compat/sessions/{id} — poll session state + per-version results
 * </pre>
 */
@RestController
@RequestMapping("/api/compat")
public class CompatibilityController {

    private final CompatMatrixRunner runner;
    private final CompatSessionStore store;
    private final AtfProperties props;
    private final AdbClient adb;
    private final ExecutionLockService execLock;
    private final ConcurrentHashMap<String, byte[]> liveScreenCache = new ConcurrentHashMap<>();

    public CompatibilityController(CompatMatrixRunner runner, CompatSessionStore store,
                                   AtfProperties props, AdbClient adb, ExecutionLockService execLock) {
        this.runner = runner;
        this.store = store;
        this.props = props;
        this.adb = adb;
        this.execLock = execLock;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "versions", required = false) String versions,
            @RequestParam(value = "serial", required = false) String serial) throws Exception {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");

        boolean onDevice = serial != null && !serial.isBlank();

        // Resolve the requested scope. Blank or "all" → full matrix; otherwise a single API level.
        boolean all = versions == null || versions.isBlank() || versions.equalsIgnoreCase("all");
        String apiCsv = all ? null : versions.trim();
        if (!onDevice && apiCsv != null && !apiCsv.matches("\\d+(,\\d+)*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "versions must be 'all' or comma-separated API levels (e.g. 33).");
        }

        String id = UUID.randomUUID().toString();
        CompatSession session = new CompatSession(id);
        session.setApkFileName(original);
        session.setScope(onDevice ? "Device " + serial.trim() : scopeLabel(all, apiCsv));
        session.addLog("Session created for: " + original + " · scope: " + session.getScope());

        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("compat-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        session.addLog("APK saved (" + target.toFile().length() + " bytes).");

        store.save(session);
        if (!execLock.tryAcquire("Compatibility Testing", id)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        if (onDevice) {
            // Multiple-Devices flow — run only on the selected device at its own API level.
            runner.runOnDevice(session, target.toFile(), serial.trim());
        } else {
            runner.run(session, target.toFile(), apiCsv);
        }

        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    private String scopeLabel(boolean all, String apiCsv) {
        if (all) return "All Android versions (9–16)";
        String[] parts = apiCsv.split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(CompatMatrixRunner.versionLabel(Integer.parseInt(p.trim())))
              .append(" (API ").append(p.trim()).append(")");
        }
        return sb.toString();
    }

    @GetMapping("/sessions/{id}")
    public CompatSession getSession(@PathVariable("id") String id) {
        CompatSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return session;
    }

    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<Map<String, String>> stopSession(@PathVariable("id") String id) {
        CompatSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        CompatSession.State s = session.getState();
        if (s == CompatSession.State.COMPLETED || s == CompatSession.State.FAILED || s == CompatSession.State.STOPPED)
            return ResponseEntity.ok(Map.of("id", id, "state", s.name()));
        session.requestStop();
        session.addLog("Stop requested by user — halting after the current step and saving results.");
        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    /**
     * Capture the current emulator screen for a running compatibility session.
     * Reads the active emulator serial from whichever {@link CompatVersionResult}
     * currently has a non-blank serial (set by the matrix runner during execution).
     * Falls back to the last cached frame between version transitions.
     */
    @GetMapping(value = "/sessions/{id}/live-screen", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> liveScreen(@PathVariable("id") String id) {
        CompatSession session = store.get(id);
        if (session == null) return ResponseEntity.notFound().build();

        CompatVersionResult active = session.getVersions().stream()
                .filter(v -> v.getSerial() != null && !v.getSerial().isBlank())
                .findFirst().orElse(null);

        if (active != null) {
            try { adb.shell(active.getSerial(), 5, "input", "keyevent", "224"); } catch (Exception ignored) {}
            byte[] png = adb.screencapPng(active.getSerial());
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
}
