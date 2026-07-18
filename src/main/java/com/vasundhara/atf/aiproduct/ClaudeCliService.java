package com.vasundhara.atf.aiproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class ClaudeCliService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliService.class);

    private final ObjectMapper mapper;

    public ClaudeCliService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Runs Claude CLI in print mode with stream-json output.
     * Captures session ID from the init event for later --resume calls.
     *
     * @param workDir       directory to run in
     * @param prompt        the instruction prompt
     * @param resumeId      nullable; if set, uses --resume to continue a session
     * @param logConsumer   called for each line of output (for log tail / file)
     */
    public ClaudeResult run(String workDir, String prompt, String resumeId, Consumer<String> logConsumer) {
        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        cmd.add("-p");
        cmd.add(prompt);
        cmd.add("--dangerously-skip-permissions");
        cmd.add("--allowedTools");
        cmd.add("Bash,Edit,Read,Write");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        if (resumeId != null && !resumeId.isBlank()) {
            cmd.add("--resume");
            cmd.add(resumeId);
        }

        log.info("Spawning Claude CLI in {}: {}", workDir, String.join(" ", cmd.subList(0, 3)) + " …");

        String sessionId = null;
        int exitCode = -1;

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    logConsumer.accept(line);

                    // Extract session ID from system init event
                    if (sessionId == null && line.contains("session_id")) {
                        try {
                            JsonNode node = mapper.readTree(line);
                            String type = node.path("type").asText();
                            if ("system".equals(type) || "result".equals(type)) {
                                String sid = node.path("session_id").asText("");
                                if (!sid.isBlank()) sessionId = sid;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            exitCode = process.waitFor();
            log.info("Claude CLI exited with code {}, sessionId={}", exitCode, sessionId);

        } catch (Exception ex) {
            log.error("Claude CLI invocation failed: {}", ex.getMessage());
            logConsumer.accept("[Claude CLI error] " + ex.getMessage());
        }

        return new ClaudeResult(exitCode == 0, sessionId, exitCode);
    }

    /**
     * Builds the Claude prompt for existing project modification.
     */
    public String buildPrompt(AiBuildJob job) {
        String screens = job.getScreens() != null ? String.join(", ", job.getScreens()) : "Home, Settings";
        String existing = job.getExistingScreens() != null ? String.join(", ", job.getExistingScreens()) : "none detected";
        String spec = job.getGeminiSpecJson() != null ? job.getGeminiSpecJson() : "{}";
        String packagePath = job.getPackageName() != null
                ? job.getPackageName().replace('.', '/')
                : "com/example/app";

        return switch (job.getMergeStrategy()) {
            case SKIP_DUPLICATES -> """
You are working on an existing Android Jetpack Compose project at: %s

App: %s | Package: %s
Existing screens: %s
Requested screens to ADD: %s
Theme color: %s
Reference app analysis: %s

INSTRUCTIONS:
1. Add ONLY screens that do not already exist (check the existing screens list above)
2. Create new screen Composables in app/src/main/java/%s/ui/screens/
3. Update NavGraph or MainActivity.kt to include routes for new screens only
4. Do NOT modify: build.gradle, build.gradle.kts, AndroidManifest.xml, gradle wrapper, existing screen files
5. Do NOT run any gradle or build commands
6. Use Kotlin + Jetpack Compose + Material3
7. Keep each screen simple: header, content area, basic navigation
8. ICONS: Only use Icons.Default.* from the base material-icons set (Home, Settings, ArrowBack, Menu, Add, Close, Search, Check, Star, Favorite, Share, Delete, Edit, Info, Warning, Person, Lock, Visibility, VisibilityOff, PlayArrow, Pause, Stop). Do NOT use icons that require material-icons-extended (SmartToy, Public, People, EmojiEvents, Leaderboard, ContentCopy, SportsEsports, etc.)

Complete the task, then stop.
""".formatted(job.getWorkspacePath(), job.getAppName(), job.getPackageName(),
                    existing, screens, job.getThemeColor(), spec, packagePath);

            case OVERWRITE -> """
You are working on an existing Android Jetpack Compose project at: %s

App: %s | Package: %s
Requested screens: %s
Theme color: %s
Reference app analysis: %s

INSTRUCTIONS:
1. Overwrite all files in app/src/main/java/%s/ui/screens/ with new Composable screens
2. Overwrite app/src/main/java/%s/ui/theme/ with new theme using color %s
3. Update MainActivity.kt with new NavGraph for all requested screens
4. Do NOT modify: build.gradle, build.gradle.kts, AndroidManifest.xml, gradle wrapper
5. Do NOT run any gradle or build commands
6. Use Kotlin + Jetpack Compose + Material3
7. ICONS: Only use Icons.Default.* from the base material-icons set (Home, Settings, ArrowBack, Menu, Add, Close, Search, Check, Star, Favorite, Share, Delete, Edit, Info, Warning, Person, Lock, Visibility, VisibilityOff, PlayArrow, Pause, Stop). Do NOT use icons that require material-icons-extended.

Complete the task, then stop.
""".formatted(job.getWorkspacePath(), job.getAppName(), job.getPackageName(),
                    screens, job.getThemeColor(), spec, packagePath, packagePath, job.getThemeColor());

            case READ_AND_MERGE -> """
You are working on an existing Android Jetpack Compose project at: %s

App: %s | Package: %s
Existing screens: %s
New screens to merge in: %s
Theme color: %s
Reference app analysis: %s

INSTRUCTIONS:
1. READ all existing screen files in app/src/main/java/%s/ui/screens/
2. Understand the existing business logic and UI patterns
3. Add new screens that integrate naturally with existing code style
4. Merge new functionality without breaking existing features
5. Update NavGraph to include new routes alongside existing ones
6. Do NOT modify: build.gradle, build.gradle.kts, AndroidManifest.xml, gradle wrapper
7. Do NOT run any gradle or build commands
8. Use Kotlin + Jetpack Compose + Material3
9. ICONS: Only use Icons.Default.* from the base material-icons set (Home, Settings, ArrowBack, Menu, Add, Close, Search, Check, Star, Favorite, Share, Delete, Edit, Info, Warning, Person, Lock, Visibility, VisibilityOff, PlayArrow, Pause, Stop). Do NOT use icons that require material-icons-extended.

Complete the task, then stop.
""".formatted(job.getWorkspacePath(), job.getAppName(), job.getPackageName(),
                    existing, screens, job.getThemeColor(), spec, packagePath);
        };
    }

    /**
     * Builds the self-healing prompt after a failed Gradle build.
     */
    public String buildHealingPrompt(AiBuildJob job, String buildError) {
        String truncatedError = buildError.length() > 3000
                ? buildError.substring(buildError.length() - 3000)
                : buildError;
        return """
The Android project at %s failed to build. Attempt %d of 3.

BUILD ERROR:
---
%s
---

Fix the compilation errors. Rules:
1. Only fix what's broken — minimal changes
2. Do NOT change build.gradle, build.gradle.kts, AndroidManifest.xml, gradle wrapper
3. Do NOT run gradle or any build commands
4. Fix Kotlin/Compose syntax errors, missing imports, unresolved references
5. ICONS: Replace any Icons.Default.* that require material-icons-extended (SmartToy, Public, People, EmojiEvents, Leaderboard, ContentCopy, SportsEsports, etc.) with equivalent basic Icons.Default.* available in the base set (Home, Settings, ArrowBack, Add, Star, Person, Info, etc.)
6. If a screen is unfixable, remove it and clean up its NavGraph entry

Complete fixes, then stop.
""".formatted(job.getWorkspacePath(), job.getHealAttempts(), truncatedError);
    }
}
