package com.vasundhara.atf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;
import com.vasundhara.atf.model.ApkInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data-driven replacement for the keyword-dictionary domain/feature guess in
 * {@code AppIntelligenceAnalyzer}: instead of matching activity names against a fixed list of
 * English keywords per vertical (which only recognizes domains someone thought to add ahead of
 * time), this sends the LLM the actual evidence gathered for THIS specific APK — package name,
 * app label, permissions, bundled SDKs, the app's own activity names, and a sample of real
 * on-screen text/labels seen during the live crawl — and asks it to infer domain, features and
 * user journeys from that evidence alone. No app-specific prompting: the same prompt runs for
 * every APK, and it only ever reasons from what was actually observed in THIS app.
 *
 * <p>Fully opt-in and additive, sharing the same "AI Review" toggle as {@link AiScreenReviewer}
 * (Settings → AI Review) rather than introducing a second switch. {@code
 * AppIntelligenceAnalyzer}'s existing keyword-based heuristic always runs first and unconditionally
 * — this is a second pass that merges richer results on top when enabled, never a replacement, so
 * every existing behavior is preserved when AI review is off or the call fails for any reason.
 */
@Component
public class LlmAppProfiler {

    private static final Logger log = LoggerFactory.getLogger(LlmAppProfiler.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    /** Caps prompt size — plenty of signal without an unbounded token cost on content-heavy apps. */
    private static final int MAX_ACTIVITIES = 60;
    private static final int MAX_SCREEN_TEXTS = 90;

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public LlmAppProfiler(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.isAiReviewEnabled() && props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    public record ProfiledFeature(String name, String description, List<String> relatedScreens, String screenKeyword) {}
    public record ProfiledJourney(String name, List<String> steps) {}
    public record Profile(String appDomain, String appCategory, List<ProfiledFeature> features,
                           List<ProfiledJourney> userJourneys, String notes) {}

    /**
     * Infers domain/features/journeys for one APK from real evidence. Returns {@code null} if AI
     * review is disabled/unconfigured, there's nothing to analyze, or the call fails for any
     * reason — callers must treat that as "fall back to the heuristic result", never as an error.
     */
    public Profile profile(ApkInfo apkInfo, ExplorationResult exploration) {
        if (!isEnabled() || apkInfo == null) return null;
        try {
            String evidence = buildEvidence(apkInfo, exploration);
            Map<String, Object> body = Map.of(
                    "model", props.getAiModel(),
                    "max_tokens", 1500,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt(evidence)
                    ))
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(45))
                    .header("content-type", "application/json")
                    .header("x-api-key", props.getAiApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LLM app profiler: API returned HTTP {} for {} — falling back to heuristic analysis.",
                        response.statusCode(), apkInfo.getPackageName());
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode contentArr = root.path("content");
            String text = contentArr.isArray() && !contentArr.isEmpty()
                    ? contentArr.get(0).path("text").asText("") : "";
            return parse(text);
        } catch (Exception e) {
            log.debug("LLM app profiler failed for {}: {}", apkInfo.getPackageName(), e.toString());
            return null;
        }
    }

    /** Everything the model is allowed to reason from — real, observed evidence, nothing assumed. */
    private static String buildEvidence(ApkInfo apkInfo, ExplorationResult exploration) {
        StringBuilder sb = new StringBuilder();
        sb.append("Package: ").append(nullSafe(apkInfo.getPackageName())).append('\n');
        sb.append("App label: ").append(nullSafe(apkInfo.getApplicationLabel())).append('\n');
        if (apkInfo.getDangerousPermissions() != null && !apkInfo.getDangerousPermissions().isEmpty()) {
            sb.append("Dangerous permissions requested: ")
                    .append(String.join(", ", apkInfo.getDangerousPermissions())).append('\n');
        }
        if (apkInfo.getDetectedSdks() != null && !apkInfo.getDetectedSdks().isEmpty()) {
            sb.append("Bundled SDKs detected: ").append(String.join(", ", apkInfo.getDetectedSdks())).append('\n');
        }
        String pkg = apkInfo.getPackageName();
        List<String> ownActivities = (apkInfo.getActivities() == null ? List.<String>of() : apkInfo.getActivities())
                .stream()
                .filter(a -> a != null && pkg != null && (a.startsWith(pkg) || a.startsWith(".")))
                .distinct()
                .limit(MAX_ACTIVITIES)
                .toList();
        if (!ownActivities.isEmpty()) {
            sb.append("App's own screens/activities (from the manifest): ")
                    .append(String.join(", ", ownActivities)).append('\n');
        }
        if (exploration != null && !exploration.getScreens().isEmpty()) {
            Set<String> texts = new LinkedHashSet<>();
            for (ScreenCapture sc : exploration.getScreens()) {
                for (Widget w : sc.widgets()) {
                    addIfMeaningful(texts, w.text());
                    addIfMeaningful(texts, w.contentDesc());
                    if (texts.size() >= MAX_SCREEN_TEXTS) break;
                }
                if (texts.size() >= MAX_SCREEN_TEXTS) break;
            }
            if (!texts.isEmpty()) {
                sb.append("Real text/labels observed on screen during a live crawl of the running app: ")
                        .append(String.join(" | ", texts)).append('\n');
            }
            sb.append("Screens actually reached during the crawl: ").append(exploration.getUniqueScreenCount()).append('\n');
        } else {
            sb.append("(No live crawl data available — base the analysis on the manifest evidence above only.)\n");
        }
        return sb.toString();
    }

    private static void addIfMeaningful(Set<String> out, String s) {
        if (s == null) return;
        String t = s.trim();
        // Skip empty, purely numeric (counters/timestamps), and unreasonably long strings — none
        // of those carry domain signal and long ones would bloat the prompt for no benefit.
        if (t.isEmpty() || t.length() > 40 || t.chars().allMatch(Character::isDigit)) return;
        out.add(t);
    }

    private static String nullSafe(String s) { return s == null || s.isBlank() ? "(unknown)" : s; }

    private static String prompt(String evidence) {
        return "You are a senior QA engineer doing initial recon on an Android app you have never seen "
                + "before, using only the evidence below — no assumptions about what kind of app this is "
                + "ahead of time.\n\n"
                + "EVIDENCE:\n" + evidence + "\n"
                + "Based ONLY on this evidence, infer:\n"
                + "1. appDomain — a SPECIFIC business domain phrase (e.g. \"Food Delivery\", \"Personal "
                + "Banking\", \"Note-Taking Productivity Tool\", \"Fitness Tracking\"). Only use a generic "
                + "label like \"General Android Application\" if the evidence genuinely gives no signal.\n"
                + "2. appCategory — one or two words summarizing the category.\n"
                + "3. features — 3 to 8 real features actually evidenced by the screen names or on-screen "
                + "text above (not generic guesses). For each: name, one-sentence description, the related "
                + "screen name(s) from the evidence if identifiable, and a short lowercase keyword fragment "
                + "that would appear in that screen's activity name or on-screen text (for navigating to it).\n"
                + "4. userJourneys — 1 to 3 realistic end-to-end journeys a real user would follow, as an "
                + "ordered list of short step descriptions, using only features/screens from the evidence.\n"
                + "5. notes — one sentence noting anything low-confidence or ambiguous in this inference.\n\n"
                + "Respond with STRICT JSON ONLY, no markdown fences, no commentary, exactly this shape:\n"
                + "{\"appDomain\":\"...\",\"appCategory\":\"...\","
                + "\"features\":[{\"name\":\"...\",\"description\":\"...\",\"relatedScreens\":[\"...\"],\"screenKeyword\":\"...\"}],"
                + "\"userJourneys\":[{\"name\":\"...\",\"steps\":[\"...\"]}],"
                + "\"notes\":\"...\"}";
    }

    private Profile parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // Models occasionally wrap JSON in a fenced code block despite instructions not to.
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int firstNl = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) cleaned = cleaned.substring(firstNl + 1, lastFence).trim();
            }
            JsonNode root = mapper.readTree(cleaned);
            String domain = root.path("appDomain").asText(null);
            String category = root.path("appCategory").asText(null);
            if (domain == null || domain.isBlank()) return null; // malformed/unusable response

            List<ProfiledFeature> features = new ArrayList<>();
            for (JsonNode f : root.path("features")) {
                List<String> related = new ArrayList<>();
                for (JsonNode s : f.path("relatedScreens")) related.add(s.asText(""));
                related.removeIf(String::isBlank);
                String name = f.path("name").asText("");
                if (name.isBlank()) continue;
                features.add(new ProfiledFeature(name, f.path("description").asText(""),
                        related, f.path("screenKeyword").asText("")));
            }
            List<ProfiledJourney> journeys = new ArrayList<>();
            for (JsonNode j : root.path("userJourneys")) {
                List<String> steps = new ArrayList<>();
                for (JsonNode s : j.path("steps")) steps.add(s.asText(""));
                steps.removeIf(String::isBlank);
                String name = j.path("name").asText("");
                if (name.isBlank() || steps.isEmpty()) continue;
                journeys.add(new ProfiledJourney(name, steps));
            }
            return new Profile(domain.trim(), category == null || category.isBlank() ? "General" : category.trim(),
                    features, journeys, root.path("notes").asText(""));
        } catch (Exception e) {
            log.debug("LLM app profiler: could not parse response as JSON: {}", e.toString());
            return null;
        }
    }
}
