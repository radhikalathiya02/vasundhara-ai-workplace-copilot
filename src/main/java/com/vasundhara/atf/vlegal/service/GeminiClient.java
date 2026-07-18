package com.vasundhara.atf.vlegal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.vlegal.config.VLegalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;

    public GeminiClient(VLegalProperties props, WebClient.Builder builder, ObjectMapper mapper) {
        this.apiKey = props.getGemini().getApiKey();
        this.model  = props.getGemini().getModel();
        this.mapper = mapper;
        this.webClient = builder
                .baseUrl(props.getGemini().getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Sends prompt to Gemini and returns the raw text of the first candidate.
     * Instructs Gemini to return JSON directly via responseMimeType.
     */
    public String generate(String prompt) {
        Map<String, Object> body = Map.of(
            "contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))
            ),
            "generationConfig", Map.of(
                "temperature", 0.3,
                "topK", 40,
                "topP", 0.95,
                "responseMimeType", "application/json"
            )
        );

        try {
            String raw = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", apiKey)
                            .build(model))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(90))
                    .block();

            JsonNode root = mapper.readTree(raw);
            String text = root.path("candidates")
                              .path(0)
                              .path("content")
                              .path("parts")
                              .path(0)
                              .path("text")
                              .asText();

            // Strip markdown code fences if Gemini wraps despite responseMimeType
            return stripMarkdownFences(text);

        } catch (WebClientResponseException ex) {
            log.error("Gemini API error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new RuntimeException("Gemini API returned " + ex.getStatusCode() + ": " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            log.error("Gemini call failed", ex);
            throw new RuntimeException("Gemini call failed: " + ex.getMessage(), ex);
        }
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return "{}";
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("```(json)?\\s*", "");
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end).trim();
        }
        return t;
    }
}
