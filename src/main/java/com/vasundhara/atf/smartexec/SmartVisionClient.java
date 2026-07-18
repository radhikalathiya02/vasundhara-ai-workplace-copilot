package com.vasundhara.atf.smartexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.config.AtfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart Execution's own AI-vision client — independent implementation from {@code ai.AiVisionClient}.
 * Reuses only the app-wide AI settings ({@code atf.ai-provider/base-url/api-key/model} — user-facing
 * Settings configuration, not module logic) so it can call a local Ollama model (free, no key), or
 * Anthropic/OpenAI. Used for (a) choosing a tap point on opaque screens with no readable a11y tree,
 * and (b) generating the AI Review report prose. Every failure resolves to null — never blocks a run.
 */
@Component
public class SmartVisionClient {

    private static final Logger log = LoggerFactory.getLogger(SmartVisionClient.class);
    private static final Pattern COORD =
            Pattern.compile("(?i)x\\s*=\\s*(\\d{1,3}(?:\\.\\d+)?)\\D+?y\\s*=\\s*(\\d{1,3}(?:\\.\\d+)?)");

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public SmartVisionClient(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public record Tap(int x, int y, String label) {}

    private String provider() {
        String p = props.getAiProvider();
        return (p == null || p.isBlank()) ? "anthropic" : p.trim().toLowerCase();
    }

    private String baseUrl() {
        String b = props.getAiBaseUrl();
        if (b != null && !b.isBlank()) return b.trim().replaceAll("/+$", "");
        return switch (provider()) {
            case "ollama" -> "http://localhost:11434";
            case "openai" -> "https://api.openai.com";
            default -> "https://api.anthropic.com";
        };
    }

    public boolean isEnabled() {
        if (!props.isAiReviewEnabled()) return false;
        if ("ollama".equals(provider())) return true;
        return props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    public Tap chooseTapPoint(byte[] png, int w, int h, String appLabel, List<int[]> triedPercent) {
        if (!isEnabled() || png == null || png.length == 0 || w <= 0 || h <= 0) return null;
        String text = complete(navPrompt(appLabel, triedPercent), png, 200);
        if (text == null || text.isBlank()) return null;
        return parseTap(text, w, h);
    }

    /** Free-form AI Review text (execution summary / root cause / risk / suggestions). Null on failure. */
    public String reviewText(String prompt) {
        if (!isEnabled()) return null;
        return complete(prompt, null, 700);
    }

    private String complete(String prompt, byte[] png, int maxTokens) {
        try {
            return "anthropic".equals(provider()) ? anthropic(prompt, png, maxTokens) : openAiCompatible(prompt, png, maxTokens);
        } catch (Exception e) {
            log.debug("Smart vision call failed: {}", e.toString());
            return null;
        }
    }

    private String anthropic(String prompt, byte[] png, int maxTokens) throws Exception {
        Object content = (png == null || png.length == 0)
                ? List.of(Map.of("type", "text", "text", prompt))
                : List.of(
                        Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/png",
                                "data", Base64.getEncoder().encodeToString(png))),
                        Map.of("type", "text", "text", prompt));
        Map<String, Object> body = Map.of("model", props.getAiModel(), "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", content)));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v1/messages")).timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json").header("x-api-key", props.getAiApiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) { log.warn("Smart vision (anthropic) HTTP {}", resp.statusCode()); return null; }
        JsonNode c = mapper.readTree(resp.body()).path("content");
        return c.isArray() && !c.isEmpty() ? c.get(0).path("text").asText("") : "";
    }

    private String openAiCompatible(String prompt, byte[] png, int maxTokens) throws Exception {
        Object content = (png == null || png.length == 0)
                ? List.of(Map.of("type", "text", "text", prompt))
                : List.of(Map.of("type", "text", "text", prompt),
                        Map.of("type", "image_url", "image_url", Map.of("url",
                                "data:image/png;base64," + Base64.getEncoder().encodeToString(png))));
        Map<String, Object> body = Map.of("model", props.getAiModel(), "max_tokens", maxTokens, "stream", false,
                "messages", List.of(Map.of("role", "user", "content", content)));
        String key = props.getAiApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v1/chat/completions")).timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + (key == null || key.isBlank() ? "ollama" : key))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) { log.warn("Smart vision ({}) HTTP {}", provider(), resp.statusCode()); return null; }
        JsonNode choices = mapper.readTree(resp.body()).path("choices");
        return choices.isArray() && !choices.isEmpty() ? choices.get(0).path("message").path("content").asText("") : "";
    }

    private static String navPrompt(String appLabel, List<int[]> tried) {
        StringBuilder avoid = new StringBuilder();
        if (tried != null && !tried.isEmpty()) {
            avoid.append(" Already tapped with no progress:");
            for (int[] p : tried) avoid.append(" (").append(p[0]).append("%,").append(p[1]).append("%)");
        }
        return "You are testing an Android app" + (appLabel == null || appLabel.isBlank() ? "" : " (" + appLabel + ")")
                + " by tapping its screen (it renders on a canvas with no accessible controls). Pick the SINGLE best "
                + "control to tap to make forward progress (a primary CTA like Continue/Start/Next/Allow, or a "
                + "distinct feature tile). NEVER choose ads, purchase/subscribe controls, external/social links, or "
                + "Back/Close/Exit." + avoid + "\nRespond with EXACTLY one line: TAP x=<0-100> y=<0-100> | <label>. "
                + "If nothing safe to tap, respond exactly: NONE";
    }

    static Tap parseTap(String text, int w, int h) {
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("NONE")) return null;
            Matcher m = COORD.matcher(line);
            if (!m.find()) continue;
            double xp = Double.parseDouble(m.group(1)), yp = Double.parseDouble(m.group(2));
            if (xp < 0 || xp > 100 || yp < 0 || yp > 100) continue;
            int x = Math.max(1, Math.min(w - 1, (int) Math.round(w * xp / 100.0)));
            int y = Math.max(1, Math.min(h - 1, (int) Math.round(h * yp / 100.0)));
            String label = "";
            int bar = line.indexOf('|');
            if (bar >= 0 && bar + 1 < line.length()) label = line.substring(bar + 1).trim();
            return new Tap(x, y, label);
        }
        return null;
    }
}
