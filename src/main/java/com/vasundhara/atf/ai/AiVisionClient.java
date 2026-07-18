package com.vasundhara.atf.ai;

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

/**
 * Single provider-aware entry point for vision+text LLM calls, shared by {@link VisionNavigator}
 * and {@link AiScreenReviewer}. It hides the wire-format differences between backends so a caller
 * just supplies a prompt (+ optional screenshot) and gets back the model's text.
 *
 * <p>Backends (Settings → AI Review, {@code atf.ai-provider}):
 * <ul>
 *   <li><b>anthropic</b> (default) — the paid Claude Messages API; requires {@code atf.ai-api-key}.</li>
 *   <li><b>ollama</b> — a LOCAL model via Ollama's OpenAI-compatible endpoint
 *       ({@code http://localhost:11434/v1/chat/completions}); FREE, needs no key, requires only a
 *       vision-capable model pulled (e.g. {@code llama3.2-vision}, {@code qwen2-vl}). This is how
 *       the framework gets vision-quality navigation/bug-detection with no paid API.</li>
 *   <li><b>openai</b> — any OpenAI-compatible API; requires a key.</li>
 * </ul>
 *
 * <p>Every failure (disabled, unreachable, non-200, malformed) resolves to {@code null} rather
 * than throwing, so callers degrade gracefully to heuristics.
 */
@Component
public class AiVisionClient {

    private static final Logger log = LoggerFactory.getLogger(AiVisionClient.class);

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public AiVisionClient(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** Normalised provider id: anthropic (default) | ollama | openai. */
    private String provider() {
        String p = props.getAiProvider();
        return (p == null || p.isBlank()) ? "anthropic" : p.trim().toLowerCase();
    }

    /** Backend base URL — explicit override, else the provider default. No trailing slash. */
    private String baseUrl() {
        String b = props.getAiBaseUrl();
        if (b != null && !b.isBlank()) return b.trim().replaceAll("/+$", "");
        return switch (provider()) {
            case "ollama" -> "http://localhost:11434";
            case "openai" -> "https://api.openai.com";
            default -> "https://api.anthropic.com";
        };
    }

    /**
     * True when a vision call would actually be attempted: AI review is enabled AND the chosen
     * provider has what it needs — a local Ollama needs no key; anthropic/openai need one.
     */
    public boolean isConfigured() {
        if (!props.isAiReviewEnabled()) return false;
        if ("ollama".equals(provider())) return true;               // local, no key required
        return props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    /** Short human-readable description of the active backend, for logs/notes. */
    public String describe() {
        return provider() + " (" + props.getAiModel() + ")";
    }

    /**
     * Run one vision+text completion. {@code imagePng} may be null for a text-only call. Returns
     * the model's response text, or {@code null} on any failure or when not configured.
     */
    public String complete(String prompt, byte[] imagePng, int maxTokens) {
        if (!isConfigured() || prompt == null) return null;
        try {
            return "anthropic".equals(provider())
                    ? anthropic(prompt, imagePng, maxTokens)
                    : openAiCompatible(prompt, imagePng, maxTokens);
        } catch (Exception e) {
            log.debug("AI vision call failed ({}): {}", describe(), e.toString());
            return null;
        }
    }

    // ── Anthropic Messages API ──────────────────────────────────────────────────────────────
    private String anthropic(String prompt, byte[] imagePng, int maxTokens) throws Exception {
        Object content = imagePng == null || imagePng.length == 0
                ? List.of(Map.of("type", "text", "text", prompt))
                : List.of(
                        Map.of("type", "image", "source", Map.of(
                                "type", "base64", "media_type", "image/png",
                                "data", Base64.getEncoder().encodeToString(imagePng))),
                        Map.of("type", "text", "text", prompt));
        Map<String, Object> body = Map.of(
                "model", props.getAiModel(),
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", content)));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v1/messages"))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("x-api-key", props.getAiApiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("AI ({}) HTTP {}: {}", describe(), resp.statusCode(), truncate(resp.body()));
            return null;
        }
        JsonNode c = mapper.readTree(resp.body()).path("content");
        return c.isArray() && !c.isEmpty() ? c.get(0).path("text").asText("") : "";
    }

    // ── OpenAI-compatible chat completions (Ollama + OpenAI) ──────────────────────────────────
    private String openAiCompatible(String prompt, byte[] imagePng, int maxTokens) throws Exception {
        Object content = imagePng == null || imagePng.length == 0
                ? List.of(Map.of("type", "text", "text", prompt))
                : List.of(
                        Map.of("type", "text", "text", prompt),
                        Map.of("type", "image_url", "image_url", Map.of(
                                "url", "data:image/png;base64,"
                                        + Base64.getEncoder().encodeToString(imagePng))));
        Map<String, Object> body = Map.of(
                "model", props.getAiModel(),
                "max_tokens", maxTokens,
                "stream", false,
                "messages", List.of(Map.of("role", "user", "content", content)));
        String key = props.getAiApiKey();
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(120))   // local models can be slow on CPU
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        // Ollama ignores auth; OpenAI needs it. Send a bearer token when a key is present.
        b.header("authorization", "Bearer " + (key == null || key.isBlank() ? "ollama" : key));
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("AI ({}) HTTP {}: {}", describe(), resp.statusCode(), truncate(resp.body()));
            return null;
        }
        JsonNode choices = mapper.readTree(resp.body()).path("choices");
        return choices.isArray() && !choices.isEmpty()
                ? choices.get(0).path("message").path("content").asText("") : "";
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
