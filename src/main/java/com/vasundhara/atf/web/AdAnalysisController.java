package com.vasundhara.atf.web;

import com.vasundhara.atf.ads.AdAnalysisRunner;
import com.vasundhara.atf.ads.AdAnalysisSession;
import com.vasundhara.atf.ads.AdAnalysisSessionStore;
import com.vasundhara.atf.config.AtfProperties;
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

/**
 * REST endpoints for the Ad Monetisation Analysis ("Priority &amp; Logs") module.
 *
 * <pre>
 * POST /api/ads/analyze            — upload APK, create session, start async analysis
 * GET  /api/ads/sessions/{id}      — poll session state + result
 * </pre>
 */
@RestController
@RequestMapping("/api/ads")
public class AdAnalysisController {

    private final AdAnalysisRunner runner;
    private final AdAnalysisSessionStore store;
    private final AtfProperties props;
    private final ExecutionLockService execLock;

    public AdAnalysisController(AdAnalysisRunner runner, AdAnalysisSessionStore store,
                                AtfProperties props, ExecutionLockService execLock) {
        this.runner = runner;
        this.store = store;
        this.props = props;
        this.execLock = execLock;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", required = false) String mode) throws Exception {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        }
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");
        }

        // Ad Mode: LIVE detects real ad loading; anything else (default) is TEST.
        String adMode = "LIVE".equalsIgnoreCase(mode) ? "LIVE" : "TEST";

        String id = UUID.randomUUID().toString();
        AdAnalysisSession session = new AdAnalysisSession(id);
        session.setApkFileName(original);
        session.setAdMode(adMode);
        session.addLog("Session created for: " + original + " · Ad Mode: " + adMode + " ADS");

        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("ads-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        session.addLog("APK saved (" + target.toFile().length() + " bytes).");

        store.save(session);
        if (!execLock.tryAcquire("Priority & Logs", id)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        runner.run(session, target.toFile());

        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    @GetMapping("/sessions/{id}")
    public AdAnalysisSession getSession(@PathVariable("id") String id) {
        AdAnalysisSession session = store.get(id);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        return session;
    }

    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<Map<String, String>> stopSession(@PathVariable("id") String id) {
        AdAnalysisSession session = store.get(id);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        if (session.getState() == AdAnalysisSession.State.ANALYZING
                || session.getState() == AdAnalysisSession.State.SETUP) {
            session.requestStop();
            session.addLog("Stop requested by user — finishing current action and stopping.");
        }
        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }
}
