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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Second-opinion ad/UI classifier: the heuristic checks in {@code ExplorationEngine}
 * (isAdWidget/isAdScreen/isSubscriptionWidget) run first, always, and catch the overwhelming
 * majority of ad SDKs by class name, resource-id convention, or content-desc/text marker. This
 * exists only for the residual case heuristics can't resolve — a screen with no recognized ad-SDK
 * fingerprint that still LOOKS like it could be showing a native ad (an unfamiliar/custom-rendered
 * ad network, or a legitimate-looking screen that's actually ad content). It is consulted per
 * SCREEN STRUCTURE, once, and its verdict is cached by the caller — never per-tap — so it never
 * adds latency to the normal interaction loop, only to the first visit of an ambiguous screen.
 *
 * <p>Fully opt-in, sharing the same "AI Review" toggle as {@link AiScreenReviewer} and
 * {@link com.vasundhara.atf.engine.AppIntelligenceAnalyzer}'s {@link LlmAppProfiler}. Disabled,
 * unconfigured, or any failure ⇒ returns an empty set, meaning "defer entirely to the heuristic
 * result" — this can only ever ADD widgets to the ad-avoidance list, never remove heuristic
 * protection.
 */
@Component
public class LlmAdClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmAdClassifier.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_WIDGETS = 60;

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmAdClassifier(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.isAiReviewEnabled() && props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    /**
     * Returns the {@link Widget#signature()} of every widget on this screen the model judges to
     * be advertisement content (a native ad card, its CTA, or a banner/promo slot) rather than the
     * app's own UI. Empty set on disabled/unconfigured/failure/no-ambiguity — never throws.
     *
     * @param activity current activity, for context only
     * @param widgets  full widget list for the current screen
     */
    public Set<String> flagAdWidgetSignatures(String activity, List<Widget> widgets) {
        if (!isEnabled() || widgets == null || widgets.isEmpty()) return Set.of();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Activity: ").append(activity == null ? "(unknown)" : activity).append('\n');
            sb.append("Widgets (index: class | text | contentDesc | resourceId | clickable):\n");
            int n = 0;
            for (Widget w : widgets) {
                if (n >= MAX_WIDGETS) break;
                sb.append(n).append(": ")
                        .append(w.simpleClass()).append(" | ")
                        .append(trim(w.text())).append(" | ")
                        .append(trim(w.contentDesc())).append(" | ")
                        .append(trim(w.resourceId())).append(" | ")
                        .append(w.clickable()).append('\n');
                n++;
            }

            Map<String, Object> body = Map.of(
                    "model", props.getAiModel(),
                    "max_tokens", 600,
                    "messages", List.of(Map.of("role", "user", "content", prompt(sb.toString())))
            );
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
                log.debug("LLM ad classifier: HTTP {} — deferring to heuristic result.", response.statusCode());
                return Set.of();
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode contentArr = root.path("content");
            String text = contentArr.isArray() && !contentArr.isEmpty()
                    ? contentArr.get(0).path("text").asText("") : "";
            return parseFlaggedIndices(text, widgets);
        } catch (Exception e) {
            log.debug("LLM ad classifier failed: {}", e.toString());
            return Set.of();
        }
    }

    private static String prompt(String evidence) {
        return "You are a senior mobile QA engineer. Below is the widget list for ONE screen of an "
                + "Android app under automated testing. Some widgets may be part of a NATIVE ADVERTISEMENT "
                + "(e.g. an ad network's sponsored card: a small icon, a headline, a description, and a "
                + "call-to-action button like Install/Download/Play/Shop Now — rendered with the app's own "
                + "generic view classes, so it looks like ordinary UI). Most widgets are genuine app UI and "
                + "should NOT be flagged.\n\n" + evidence + "\n"
                + "List ONLY the indices of widgets that are part of an advertisement (empty array if none). "
                + "Respond with STRICT JSON ONLY, no markdown, no commentary: {\"adWidgetIndices\":[...]}";
    }

    private static Set<String> parseFlaggedIndices(String text, List<Widget> widgets) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int firstNl = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) cleaned = cleaned.substring(firstNl + 1, lastFence).trim();
            }
            JsonNode root = new ObjectMapper().readTree(cleaned);
            for (JsonNode idxNode : root.path("adWidgetIndices")) {
                int idx = idxNode.asInt(-1);
                if (idx >= 0 && idx < widgets.size()) out.add(widgets.get(idx).signature());
            }
        } catch (Exception e) {
            log.debug("LLM ad classifier: could not parse response as JSON: {}", e.toString());
        }
        return out;
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }
}
