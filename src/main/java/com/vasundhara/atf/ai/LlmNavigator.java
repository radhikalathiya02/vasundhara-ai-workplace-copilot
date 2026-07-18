package com.vasundhara.atf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.engine.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM-guided live navigator: given the widgets currently on screen and a target the crawler is
 * trying to reach (a feature name / screen keyword), returns the index of the on-screen widget
 * most likely to move one hop closer to that target. This turns the heuristic crawl into a
 * goal-directed one for the hard cases — reaching a specific feature buried several taps deep,
 * where a single keyword match on the current screen isn't enough.
 *
 * <p>Purely advisory and safety-bounded: it only ever returns an INDEX into the widget list the
 * caller already deemed safe to tap (the caller filters ads/purchase CTAs out first and re-checks
 * the chosen widget), so it can never direct a tap onto ad or payment content. Shares the same
 * opt-in "AI Review" toggle and API key as {@link LlmAdClassifier}; disabled / unconfigured /
 * any failure returns -1, meaning "no suggestion — fall back to heuristics".
 */
@Component
public class LlmNavigator {

    private static final Logger log = LoggerFactory.getLogger(LlmNavigator.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_WIDGETS = 60;

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmNavigator(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.isAiReviewEnabled() && props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    /**
     * Returns the index (into {@code candidates}) of the widget to tap next to progress toward
     * {@code target}, or -1 if the model has no confident suggestion / on any failure.
     *
     * @param activity   current activity name, for context
     * @param target     the feature/screen the crawler is trying to reach (e.g. "Settings",
     *                   "Add Card", "Profile")
     * @param candidates the tappable widgets currently on screen — already filtered by the caller
     *                   to exclude ads and purchase/subscription CTAs
     */
    public int chooseNextTap(String activity, String target, List<Widget> candidates) {
        if (!isEnabled() || target == null || target.isBlank()
                || candidates == null || candidates.isEmpty()) return -1;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Current activity: ").append(activity == null ? "(unknown)" : activity).append('\n');
            sb.append("Goal: reach the \"").append(target).append("\" feature/screen.\n");
            sb.append("Tappable controls on the current screen (index: class | text | contentDesc | resourceId):\n");
            int n = Math.min(candidates.size(), MAX_WIDGETS);
            for (int i = 0; i < n; i++) {
                Widget w = candidates.get(i);
                sb.append(i).append(": ")
                        .append(w.simpleClass()).append(" | ")
                        .append(trim(w.text())).append(" | ")
                        .append(trim(w.contentDesc())).append(" | ")
                        .append(trim(w.resourceId())).append('\n');
            }

            Map<String, Object> body = Map.of(
                    "model", props.getAiModel(),
                    "max_tokens", 200,
                    "messages", List.of(Map.of("role", "user", "content", prompt(sb.toString()))));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json")
                    .header("x-api-key", props.getAiApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("LLM navigator: HTTP {} — falling back to heuristics.", response.statusCode());
                return -1;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode contentArr = root.path("content");
            String text = contentArr.isArray() && !contentArr.isEmpty()
                    ? contentArr.get(0).path("text").asText("") : "";
            return parseIndex(text, n);
        } catch (Exception e) {
            log.debug("LLM navigator failed: {}", e.toString());
            return -1;
        }
    }

    private static String prompt(String evidence) {
        return "You are guiding an automated UI crawler through an Android app to reach a specific "
                + "feature. Pick the SINGLE control most likely to move one step closer to the goal "
                + "(a navigation tab, menu item, list row, or button that leads toward it). Prefer "
                + "in-app navigation; never pick something that looks like it leaves the app.\n\n"
                + evidence + "\n"
                + "Respond with STRICT JSON ONLY, no markdown: {\"index\": <n>} where n is the index of "
                + "the control to tap, or {\"index\": -1} if none of them plausibly lead toward the goal.";
    }

    private static int parseIndex(String text, int bound) {
        if (text == null || text.isBlank()) return -1;
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int firstNl = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) cleaned = cleaned.substring(firstNl + 1, lastFence).trim();
            }
            JsonNode root = new ObjectMapper().readTree(cleaned);
            int idx = root.path("index").asInt(-1);
            return (idx >= 0 && idx < bound) ? idx : -1;
        } catch (Exception e) {
            log.debug("LLM navigator: could not parse response as JSON: {}", e.toString());
            return -1;
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }
}
