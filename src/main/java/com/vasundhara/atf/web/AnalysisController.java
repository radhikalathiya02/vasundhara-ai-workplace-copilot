package com.vasundhara.atf.web;

import com.vasundhara.atf.analysis.AnalysisRunner;
import com.vasundhara.atf.analysis.AnalysisSession;
import com.vasundhara.atf.analysis.AnalysisSessionStore;
import com.vasundhara.atf.config.AtfProperties;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
 * REST endpoints for the "Analyze APK" workflow (New Test module, pre-execution).
 *
 * <pre>
 * POST /api/analyze                 — upload APK, create session, start async analysis
 * GET  /api/analyze/{id}            — poll progress (stage/percent/done)
 * GET  /api/analyze/{id}/sheet.csv  — download the generated Test Case Sheet once done
 * </pre>
 *
 * Intentionally independent of {@code ExecutionLockService} and any device/Appium session —
 * this is a lightweight static-analysis aid that must work even without a connected device
 * and must never contend with an in-progress test run.
 */
@RestController
@RequestMapping("/api/analyze")
public class AnalysisController {

    private final AnalysisRunner runner;
    private final AnalysisSessionStore store;
    private final AtfProperties props;

    public AnalysisController(AnalysisRunner runner, AnalysisSessionStore store, AtfProperties props) {
        this.runner = runner;
        this.store = store;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> analyze(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        }
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");
        }

        String id = UUID.randomUUID().toString();
        AnalysisSession session = new AnalysisSession(id, original);

        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("analyze-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        store.save(session);
        runner.run(session, target.toFile());

        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/{id}")
    public AnalysisSession getSession(@PathVariable("id") String id) {
        AnalysisSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return session;
    }

    @GetMapping("/{id}/sheet.xlsx")
    public ResponseEntity<byte[]> downloadSheet(@PathVariable("id") String id) {
        AnalysisSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        if (!session.isDone() || session.getSheetBytes() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Test Case Sheet is not ready yet.");
        }
        String base = session.getApkFileName().replaceAll("(?i)\\.apk$", "");
        String filename = "TestCaseSheet-" + base + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(session.getSheetBytes());
    }
}
