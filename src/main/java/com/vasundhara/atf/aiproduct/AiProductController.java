package com.vasundhara.atf.aiproduct;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Controller
public class AiProductController {

    private final AiBuildService buildService;
    private final GeminiAnalysisService geminiAnalysis;
    private final QueueService queueService;

    public AiProductController(AiBuildService buildService,
                               GeminiAnalysisService geminiAnalysis,
                               QueueService queueService) {
        this.buildService  = buildService;
        this.geminiAnalysis = geminiAnalysis;
        this.queueService  = queueService;
    }

    // ── SPA routes ──────────────────────────────────────────────────────────

    @GetMapping("/ai-product")
    public String redirect() { return "redirect:/ai-product/"; }

    @GetMapping("/ai-product/")
    public String index() { return "forward:/ai-product/index.html"; }

    // ── REST API ─────────────────────────────────────────────────────────────

    /** Analyze a reference URL with Gemini */
    @PostMapping("/api/ai-product/analyze")
    @ResponseBody
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("referenceUrl", "").trim();
        if (url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "referenceUrl is required"));
        }
        try {
            GeminiSpec spec = geminiAnalysis.analyzeReferenceUrl(url);
            return ResponseEntity.ok(spec);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Detect Android project at a path */
    @PostMapping("/api/ai-product/detect-project")
    @ResponseBody
    public ResponseEntity<ProjectInfo> detectProject(@RequestBody Map<String, String> body) {
        String path = body.getOrDefault("path", "").trim();
        ProjectInfo info = buildService.detectProjectPublic(path);
        return ResponseEntity.ok(info);
    }

    /** Submit a build job */
    @PostMapping("/api/ai-product/build")
    @ResponseBody
    public ResponseEntity<BuildResponse> submitBuild(@RequestBody BuildRequest req) {
        BuildResponse resp = buildService.submitBuild(req);
        if (resp.getError() != null) {
            // 409 if busy + REJECT, 400 for other errors
            return resp.getError().contains("busy")
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body(resp)
                    : ResponseEntity.badRequest().body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    /** Get full job status */
    @GetMapping("/api/ai-product/build/{id}")
    @ResponseBody
    public ResponseEntity<?> getJob(@PathVariable String id) {
        AiBuildJob job = buildService.getJob(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
            "id", job.getId(),
            "status", job.getStatus(),
            "progress", job.getProgress(),
            "currentStep", job.getCurrentStep(),
            "appName", job.getAppName() != null ? job.getAppName() : "",
            "errorMessage", job.getErrorMessage() != null ? job.getErrorMessage() : "",
            "queuePosition", job.getQueuePosition() != null ? job.getQueuePosition() : 0,
            "estimatedWaitSeconds", job.getEstimatedWaitSeconds() != null ? job.getEstimatedWaitSeconds() : 0,
            "createdAt", job.getCreatedAt().toString(),
            "completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : ""
        ));
    }

    /** SSE stream for live build progress */
    @GetMapping(value = "/api/ai-product/build/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamBuild(@PathVariable String id) {
        SseEmitter emitter = buildService.addEmitter(id);
        if (emitter == null) {
            SseEmitter dead = new SseEmitter();
            dead.completeWithError(new RuntimeException("Job not found: " + id));
            return dead;
        }
        return emitter;
    }

    /** Download the built APK */
    @GetMapping("/api/ai-product/build/{id}/download")
    public ResponseEntity<Resource> downloadApk(@PathVariable String id) {
        Resource artifact = buildService.getArtifact(id);
        if (artifact == null) return ResponseEntity.notFound().build();

        String filename = "app-debug-" + id.substring(0, 8) + ".apk";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(artifact);
    }

    /** Full build log */
    @GetMapping("/api/ai-product/build/{id}/logs")
    @ResponseBody
    public ResponseEntity<String> getBuildLog(@PathVariable String id) {
        AiBuildJob job = buildService.getJob(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(buildService.getLog(id));
    }

    /** Queue status */
    @GetMapping("/api/ai-product/queue")
    @ResponseBody
    public ResponseEntity<?> getQueue() {
        List<QueueEntry> entries = queueService.getQueueStatus();
        return ResponseEntity.ok(Map.of(
            "jobs", entries,
            "size", queueService.size(),
            "avgBuildSeconds", queueService.getAvgBuildSeconds()
        ));
    }
}
