package com.vasundhara.atf.web;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.engine.ExecutionLockService;
import com.vasundhara.atf.localization.LocalizationRunner;
import com.vasundhara.atf.localization.LocalizationSession;
import com.vasundhara.atf.localization.LocalizationSessionStore;
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

/**
 * REST endpoints for automatic localization testing.
 *
 * <pre>
 * POST /api/l10n/analyze        — upload APK, create session, start per-language testing
 * GET  /api/l10n/sessions/{id}  — poll session state + per-language results
 * </pre>
 */
@RestController
@RequestMapping("/api/l10n")
public class LocalizationController {

    private final LocalizationRunner runner;
    private final LocalizationSessionStore store;
    private final AtfProperties props;
    private final ExecutionLockService execLock;

    public LocalizationController(LocalizationRunner runner, LocalizationSessionStore store,
                                  AtfProperties props, ExecutionLockService execLock) {
        this.runner = runner;
        this.store = store;
        this.props = props;
        this.execLock = execLock;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> analyze(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "serial", required = false) String serial) throws Exception {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");

        String id = UUID.randomUUID().toString();
        LocalizationSession session = new LocalizationSession(id);
        session.setApkFileName(original);
        session.addLog("Session created for: " + original);

        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("l10n-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        session.addLog("APK saved (" + target.toFile().length() + " bytes).");

        store.save(session);
        if (!execLock.tryAcquire("Localization Testing", id)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        runner.run(session, target.toFile(), serial);

        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    @GetMapping("/sessions/{id}")
    public LocalizationSession getSession(@PathVariable("id") String id) {
        LocalizationSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return session;
    }

    /** Request a cooperative stop: the runner finishes the current step, saves results, and ends. */
    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable("id") String id) {
        LocalizationSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        if (session.getState() == LocalizationSession.State.RUNNING
                || session.getState() == LocalizationSession.State.SETUP) {
            session.requestStop();
            session.addLog("Stop requested by user — halting after the current step and saving results collected so far…");
        }
        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }
}
