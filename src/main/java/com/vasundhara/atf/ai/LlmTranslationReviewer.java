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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Second-opinion translation-QUALITY reviewer for the Localization module. The pattern/script
 * checks in {@link LocalizationAnalyzer} (untranslated / mixed-script / truncation / encoding /
 * RTL) catch strings that are unchanged, in the wrong script, or geometrically broken — but they
 * cannot tell whether a string that WAS translated is actually a correct, sensible translation
 * (as opposed to garbled machine-translation output, a placeholder, or text in the wrong target
 * language entirely). This is the one check in the pipeline that requires actual language
 * understanding, so it is the one check delegated to an LLM.
 *
 * <p>Consulted once per SCREEN (not per string) to keep cost/latency bounded. Fully opt-in,
 * sharing the same "AI Review" toggle as {@link AiScreenReviewer}/{@link LlmAdClassifier}/
 * {@link LlmNavigator}. Disabled, unconfigured, or any failure ⇒ returns an empty list — this
 * check can only ever ADD findings on top of the heuristic checks, never replace or suppress them.
 */
@Component
public class LlmTranslationReviewer {

    private static final Logger log = LoggerFactory.getLogger(LlmTranslationReviewer.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_STRINGS = 40;

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmTranslationReviewer(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.isAiReviewEnabled() && props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    /** One flagged translation with the reviewer's reason. */
    public record Flag(String text, String reason) {}

    /**
     * Reviews the visible, translated strings on ONE screen for correctness. Strings that are
     * identical to the reference (untranslated) or in the wrong script are already caught by the
     * heuristic checks and are not re-flagged here unless {@code targetTexts} still contains them.
     *
     * @param languageName   e.g. "Spanish" — for prompt context only
     * @param languageCode   e.g. "es" — for prompt context only
     * @param referenceTexts a sample of the app's REFERENCE-language (baseline) vocabulary, so the
     *                       model knows what the app is about and can judge whether a target string
     *                       plausibly corresponds to genuine app UI copy, not just "is this valid text"
     * @param targetTexts    the translated strings actually visible on this screen
     * @return strings judged to be a wrong/garbled/nonsensical/wrong-language translation, with a
     *         short reason each. Empty on disabled/unconfigured/failure/no-issue-found — never throws.
     */
    public List<Flag> reviewScreen(String languageName, String languageCode,
                                   List<String> referenceTexts, List<String> targetTexts) {
        if (!isEnabled() || targetTexts == null || targetTexts.isEmpty()) return List.of();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Target language: ").append(languageName).append(" (").append(languageCode).append(")\n\n");
            sb.append("Reference-language (baseline) UI vocabulary from the SAME app, for context:\n");
            int n = 0;
            for (String t : referenceTexts) {
                if (n >= MAX_STRINGS) break;
                sb.append("- ").append(trim(t)).append('\n');
                n++;
            }
            sb.append("\nStrings visible on ONE screen after switching to the target language "
                    + "(index: text):\n");
            n = 0;
            for (String t : targetTexts) {
                if (n >= MAX_STRINGS) break;
                sb.append(n).append(": ").append(trim(t)).append('\n');
                n++;
            }

            Map<String, Object> body = Map.of(
                    "model", props.getAiModel(),
                    "max_tokens", 700,
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
                log.debug("LLM translation reviewer: HTTP {} — skipping this screen.", response.statusCode());
                return List.of();
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode contentArr = root.path("content");
            String text = contentArr.isArray() && !contentArr.isEmpty()
                    ? contentArr.get(0).path("text").asText("") : "";
            return parseFlags(text, targetTexts);
        } catch (Exception e) {
            log.debug("LLM translation reviewer failed: {}", e.toString());
            return List.of();
        }
    }

    private static String prompt(String evidence) {
        return "You are a senior localization QA engineer reviewing an Android app's UI text after "
                + "it was switched to a target language. Flag ONLY strings that are clearly WRONG: "
                + "garbled/nonsensical machine-translation output, text in a language OTHER than the "
                + "target language (and not a proper noun/brand name), a raw placeholder/template "
                + "token left untranslated (e.g. \"%s\", \"{0}\"), or a translation that plainly does "
                + "not correspond to any plausible UI copy for this app. Do NOT flag: proper nouns, "
                + "brand names, numbers, short abbreviations, or strings that are merely a stylistic "
                + "choice you'd word differently — only flag things a native speaker would call "
                + "actually wrong. Most strings are correct and should NOT be flagged.\n\n" + evidence + "\n"
                + "Respond with STRICT JSON ONLY, no markdown, no commentary: "
                + "{\"flagged\":[{\"index\":N,\"reason\":\"...\"}]}";
    }

    private static List<Flag> parseFlags(String text, List<String> targetTexts) {
        List<Flag> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int firstNl = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) cleaned = cleaned.substring(firstNl + 1, lastFence).trim();
            }
            JsonNode root = new ObjectMapper().readTree(cleaned);
            for (JsonNode f : root.path("flagged")) {
                int idx = f.path("index").asInt(-1);
                if (idx < 0 || idx >= targetTexts.size()) continue;
                String reason = f.path("reason").asText("Flagged as a likely incorrect translation.");
                out.add(new Flag(targetTexts.get(idx), reason));
            }
        } catch (Exception e) {
            log.debug("LLM translation reviewer: could not parse response as JSON: {}", e.toString());
        }
        return out;
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
