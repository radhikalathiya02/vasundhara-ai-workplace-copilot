package com.vasundhara.atf.aiproduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class AiBuildService {

    private static final Logger log = LoggerFactory.getLogger(AiBuildService.class);
    private static final int MAX_HEAL_ATTEMPTS = 3;

    @Value("${ai.build.workspace.base:/tmp/ai-builds}")
    private String workspaceBase;

    @Value("${ai.build.java-home:}")
    private String javaHome;

    private final GeminiAnalysisService geminiAnalysis;
    private final ClaudeCliService claudeCli;
    private final QueueService queueService;
    private final ObjectMapper mapper;

    private final ConcurrentHashMap<String, AiBuildJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "ai-build-thread"); t.setDaemon(true); return t; }
    );
    private final AtomicBoolean buildActive = new AtomicBoolean(false);

    public AiBuildService(GeminiAnalysisService geminiAnalysis,
                          ClaudeCliService claudeCli, QueueService queueService, ObjectMapper mapper) {
        this.geminiAnalysis = geminiAnalysis;
        this.claudeCli = claudeCli;
        this.queueService = queueService;
        this.mapper = mapper;
    }

    // ── Submit ──────────────────────────────────────────────────────────────

    public BuildResponse submitBuild(BuildRequest req) {
        if (!req.getProjectPath().isBlank() && !new File(req.getProjectPath()).exists()) {
            return BuildResponse.error("Project path does not exist: " + req.getProjectPath());
        }

        String id = UUID.randomUUID().toString();
        AiBuildJob job = new AiBuildJob();
        job.setId(id);
        job.setStatus(BuildStatus.QUEUED);
        job.setAppName(req.getAppName());
        job.setPackageName(req.getPackageName());
        job.setDescription(req.getDescription());
        job.setScreens(req.getScreens());
        job.setThemeColor(req.getThemeColor());
        job.setProjectPath(req.getProjectPath());
        job.setReferenceUrl(req.getReferenceUrl());
        job.setMergeStrategy(req.getMergeStrategy());
        job.setQueueBehavior(req.getQueueBehavior());
        job.setGeminiSpecJson(req.getGeminiSpecJson());
        if (req.getClaudeModel() != null && !req.getClaudeModel().isBlank()) job.setClaudeModel(req.getClaudeModel());
        if (req.getClaudeEffort() != null && !req.getClaudeEffort().isBlank()) job.setClaudeEffort(req.getClaudeEffort());
        job.setWorkspacePath(workspaceBase + "/" + id);
        job.setLogPath(job.getWorkspacePath() + "/build.log");

        jobs.put(id, job);

        if (buildActive.compareAndSet(false, true)) {
            executor.submit(() -> runBuild(job));
            return BuildResponse.ok(id, BuildStatus.ANALYZING);
        }

        if (req.getQueueBehavior() == QueueBehavior.REJECT) {
            jobs.remove(id);
            return BuildResponse.error("Build server busy. Try again later or use QUEUE mode.");
        }

        int pos = queueService.enqueue(job);
        return BuildResponse.queued(id, pos, queueService.getEstimatedWait(pos));
    }

    // ── Core build pipeline ─────────────────────────────────────────────────

    private void runBuild(AiBuildJob job) {
        long startMs = System.currentTimeMillis();
        try {
            // Phase 1: Setup workspace
            advance(job, BuildStatus.ANALYZING, 5, "Preparing workspace…");
            setupWorkspace(job);

            // Phase 2: Gemini analysis (optional)
            if (job.getReferenceUrl() != null && !job.getReferenceUrl().isBlank()) {
                advance(job, BuildStatus.ANALYZING, 10, "Analyzing reference app with Gemini…");
                GeminiSpec spec = geminiAnalysis.analyzeReferenceUrl(job.getReferenceUrl());
                job.setGeminiSpec(spec);
                if (job.getGeminiSpecJson() == null) {
                    job.setGeminiSpecJson(mapper.writeValueAsString(spec));
                }
            }

            // Phase 3: Detect existing project info
            advance(job, BuildStatus.ANALYZING, 15, "Scanning project structure…");
            ProjectInfo info = detectProject(job.getWorkspacePath());
            if (info.getExistingScreens() != null) job.setExistingScreens(info.getExistingScreens());
            if (job.getPackageName() == null && info.getPackageName() != null) job.setPackageName(info.getPackageName());
            if (job.getAppName() == null && info.getAppName() != null) job.setAppName(info.getAppName());

            // Phase 4: Claude code generation
            advance(job, BuildStatus.GENERATING, 20, "Claude is writing code…");
            String prompt = claudeCli.buildPrompt(job);
            ClaudeResult claudeResult = claudeCli.run(
                    job.getWorkspacePath(), prompt, null,
                    line -> writeLog(job, line),
                    job.getClaudeModel(), job.getClaudeEffort()
            );
            if (claudeResult.getSessionId() != null) {
                job.setSessionId(claudeResult.getSessionId());
            }

            // Phase 5: Gradle build + self-heal loop
            boolean built = false;
            String lastError = "";
            for (int attempt = 1; attempt <= MAX_HEAL_ATTEMPTS; attempt++) {
                advance(job, BuildStatus.BUILDING, 50 + (attempt - 1) * 12,
                        attempt == 1 ? "Running Gradle assembleDebug (first run downloads dependencies — may take 10-15 min)…"
                                     : "Retrying build after Claude fixes (attempt " + attempt + "/" + MAX_HEAL_ATTEMPTS + ")…");

                lastError = runGradle(job);
                if (lastError == null) { built = true; break; }

                // Heal
                if (attempt < MAX_HEAL_ATTEMPTS) {
                    job.setHealAttempts(attempt);
                    advance(job, BuildStatus.GENERATING, 50 + attempt * 12,
                            "Build failed. Claude is fixing errors (attempt " + attempt + ")…");
                    String healPrompt = claudeCli.buildHealingPrompt(job, lastError);
                    claudeCli.run(job.getWorkspacePath(), healPrompt, job.getSessionId(),
                            line -> writeLog(job, line),
                            job.getClaudeModel(), job.getClaudeEffort());
                }
            }

            if (!built) {
                fail(job, "Build failed after " + MAX_HEAL_ATTEMPTS + " attempts. Check logs for details.");
                return;
            }

            // Phase 6: Locate APK
            advance(job, BuildStatus.SIGNING, 92, "Locating signed APK…");
            String apkPath = findApk(job.getWorkspacePath());
            if (apkPath == null) {
                fail(job, "Build succeeded but APK not found in expected location.");
                return;
            }
            job.setArtifactPath(apkPath);

            // Done
            long durationMs = System.currentTimeMillis() - startMs;
            queueService.recordCompletedBuild(durationMs);
            advance(job, BuildStatus.DONE, 100, "Build complete! APK ready.");
            job.setCompletedAt(Instant.now());
            broadcast(job, null);

        } catch (Exception ex) {
            log.error("Build {} failed unexpectedly: {}", job.getId(), ex.getMessage(), ex);
            fail(job, "Unexpected error: " + sanitize(ex.getMessage()));
        } finally {
            // Start next queued job, or release the active flag
            AiBuildJob next = queueService.dequeue();
            if (next != null) {
                executor.submit(() -> runBuild(next));
            } else {
                buildActive.set(false);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void setupWorkspace(AiBuildJob job) throws IOException {
        Path workspace = Paths.get(job.getWorkspacePath());
        Files.createDirectories(workspace);

        // Copy user's project to workspace
        Path src = Paths.get(job.getProjectPath());
        if (Files.exists(src)) {
            copyDirectory(src, workspace);
            writeLog(job, "Copied project to workspace: " + workspace);
        }
    }

    private void copyDirectory(Path src, Path dest) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> {
                try {
                    Path target = dest.resolve(src.relativize(source));
                    if (Files.isDirectory(source)) {
                        // Skip .gradle cache and build output dirs entirely
                        String dirName = source.getFileName().toString();
                        String rel = src.relativize(source).toString();
                        if (dirName.equals(".gradle") || dirName.equals("build") && rel.contains("/")) return;
                        Files.createDirectories(target);
                    } else {
                        // Skip files inside .gradle/ or build/ dirs
                        String rel = src.relativize(source).toString();
                        if (rel.startsWith(".gradle/") || rel.contains("/.gradle/")
                                || rel.startsWith("build/") || rel.contains("/build/")) return;
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ex) {
                    log.warn("Copy failed for {}: {}", source, ex.getMessage());
                }
            });
        }
    }

    private String resolveJavaHome() {
        // Use explicit config first
        if (javaHome != null && !javaHome.isBlank()) return javaHome;
        // Try /usr/libexec/java_home for Java 17 then 21 (avoids Java 25 incompatibility with old Kotlin DSL compiler)
        for (String v : List.of("17", "21")) {
            try {
                Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", v)
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                if (!out.isBlank() && !out.contains("Unable") && new File(out).isDirectory()) {
                    log.info("Using JAVA_HOME={} for Gradle build", out);
                    return out;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String runGradle(AiBuildJob job) {
        StringBuilder stderr = new StringBuilder();
        try {
            // Try ./gradlew first, fall back to gradle
            File gradlew = new File(job.getWorkspacePath(), "gradlew");
            String gradleCmd = gradlew.exists() ? "./gradlew" : "gradle";

            ProcessBuilder pb = new ProcessBuilder(gradleCmd, "assembleDebug", "--stacktrace");
            pb.directory(new File(job.getWorkspacePath()));
            pb.redirectErrorStream(true);

            // Override JAVA_HOME so Gradle uses Java 17/21 (Java 25 breaks Kotlin DSL compiler)
            String resolvedJavaHome = resolveJavaHome();
            if (resolvedJavaHome != null) {
                pb.environment().put("JAVA_HOME", resolvedJavaHome);
                // Also ensure the resolved java is first on PATH
                String javabin = resolvedJavaHome + "/bin";
                String path = pb.environment().getOrDefault("PATH", System.getenv("PATH"));
                pb.environment().put("PATH", javabin + File.pathSeparator + path);
            }

            Process p = pb.start();

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writeLog(job, line);
                    stderr.append(line).append("\n");
                }
            }

            int exit = p.waitFor();
            return exit == 0 ? null : stderr.toString();

        } catch (Exception ex) {
            return "Gradle execution failed: " + ex.getMessage();
        }
    }

    private String findApk(String workspacePath) {
        // Standard debug APK location
        String[] candidates = {
            workspacePath + "/app/build/outputs/apk/debug/app-debug.apk",
            workspacePath + "/app/build/outputs/apk/debug/app-debug-unsigned.apk"
        };
        for (String c : candidates) {
            if (new File(c).exists()) return c;
        }

        // Recursive search
        try (Stream<Path> stream = Files.walk(Paths.get(workspacePath))) {
            return stream
                    .filter(p -> p.toString().endsWith(".apk"))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private ProjectInfo detectProject(String path) {
        ProjectInfo info = new ProjectInfo();
        Path root = Paths.get(path);

        boolean hasKts = Files.exists(root.resolve("app/build.gradle.kts"));
        boolean hasGroovy = Files.exists(root.resolve("app/build.gradle"));
        info.setAndroidProject(hasKts || hasGroovy);
        info.setBuildGradlePath(hasKts ? "app/build.gradle.kts" : "app/build.gradle");

        // Extract package from AndroidManifest.xml
        Path manifest = root.resolve("app/src/main/AndroidManifest.xml");
        if (Files.exists(manifest)) {
            try {
                String content = Files.readString(manifest);
                var matcher = java.util.regex.Pattern.compile("package=\"([^\"]+)\"").matcher(content);
                if (matcher.find()) info.setPackageName(matcher.group(1));
            } catch (IOException ignored) {}
        }

        // Scan for existing screens
        List<String> screens = new ArrayList<>();
        try {
            Path srcDir = root.resolve("app/src/main/java");
            if (Files.exists(srcDir)) {
                try (Stream<Path> stream = Files.walk(srcDir)) {
                    stream.filter(p -> p.toString().endsWith("Screen.kt") || p.toString().endsWith("Activity.kt"))
                          .forEach(p -> screens.add(p.getFileName().toString().replace(".kt", "")));
                }
            }
        } catch (IOException ignored) {}
        info.setExistingScreens(screens);

        return info;
    }

    private void writeLog(AiBuildJob job, String line) {
        job.appendLog(line);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(job.getLogPath(), true))) {
            w.write(line);
            w.newLine();
        } catch (IOException ignored) {}
    }

    private void advance(AiBuildJob job, BuildStatus status, int progress, String step) {
        job.setStatus(status);
        job.setProgress(progress);
        job.setCurrentStep(step);
        log.info("[{}] {} ({}%)", job.getId(), step, progress);
        broadcast(job, null);
    }

    private void fail(AiBuildJob job, String message) {
        job.setStatus(BuildStatus.FAILED);
        job.setProgress(100);
        job.setCurrentStep("Build failed");
        job.setErrorMessage(message);
        job.setCompletedAt(Instant.now());
        broadcast(job, null);
    }

    private void broadcast(AiBuildJob job, String extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", job.getStatus());
        payload.put("progress", job.getProgress());
        payload.put("currentStep", job.getCurrentStep());
        payload.put("logTail", String.join("\n", job.getLogTail()));
        if (job.getStatus() == BuildStatus.DONE) {
            payload.put("downloadUrl", "/api/ai-product/build/" + job.getId() + "/download");
        }
        if (job.getStatus() == BuildStatus.FAILED) {
            payload.put("error", job.getErrorMessage());
        }
        if (job.getQueuePosition() != null) {
            payload.put("queuePosition", job.getQueuePosition());
            payload.put("estimatedWaitSeconds", job.getEstimatedWaitSeconds());
        }

        String data;
        try { data = mapper.writeValueAsString(payload); } catch (Exception e) { data = "{}"; }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : job.getEmitters()) {
            try {
                emitter.send(SseEmitter.event().data(data));
            } catch (Exception ex) {
                dead.add(emitter);
            }
        }
        dead.forEach(job::removeEmitter);
    }

    private String sanitize(String msg) {
        if (msg == null) return "Unknown error";
        // Remove absolute paths for user-facing messages
        return msg.replaceAll("/tmp/ai-builds/[a-f0-9-]+", "[workspace]")
                  .replaceAll("/Users/[^/]+", "[home]");
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public AiBuildJob getJob(String id) { return jobs.get(id); }

    public SseEmitter addEmitter(String id) {
        AiBuildJob job = jobs.get(id);
        if (job == null) return null;

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        job.addEmitter(emitter);
        emitter.onCompletion(() -> job.removeEmitter(emitter));
        emitter.onTimeout(() -> job.removeEmitter(emitter));

        // Send current state immediately
        broadcast(job, null);
        return emitter;
    }

    public Resource getArtifact(String id) {
        AiBuildJob job = jobs.get(id);
        if (job == null || job.getArtifactPath() == null) return null;
        File f = new File(job.getArtifactPath());
        return f.exists() ? new FileSystemResource(f) : null;
    }

    public String getLog(String id) {
        AiBuildJob job = jobs.get(id);
        if (job == null || job.getLogPath() == null) return "No log available.";
        try {
            Path p = Paths.get(job.getLogPath());
            return Files.exists(p) ? Files.readString(p) : "Log not yet available.";
        } catch (IOException ex) { return "Error reading log."; }
    }

    public ProjectInfo detectProjectPublic(String path) {
        if (!new File(path).exists()) {
            ProjectInfo pi = new ProjectInfo();
            pi.setError("Path does not exist: " + path);
            return pi;
        }
        return detectProject(path);
    }

    public Collection<AiBuildJob> allJobs() { return jobs.values(); }
}
