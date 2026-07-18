package com.vasundhara.atf.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Optional second opinion layered on top of the framework's heuristic detectors. Heuristics
 * (crash/ANR logcat mining, structural UI checks) catch objective, measurable defects and
 * always run — this class exists for the subjective, contextual defects a rule can't express:
 * garbled or misaligned content, unreadable text, a broken/placeholder image, a screen that
 * looks wrong for what it's supposed to show.
 *
 * <p>Fully opt-in: inactive unless both {@code atf.ai-review-enabled} is true and an API key is
 * configured (Settings → AI Review, or {@code atf.ai-api-key}). Every failure mode — disabled,
 * misconfigured, network error, malformed response — resolves to "no issues found" rather than
 * throwing, so this can never block or break a run; it can only ever add findings on top of
 * what heuristics already produce.
 *
 * <p>The prompt is deliberately conservative (report only concrete, visible defects; default to
 * "looks fine" when uncertain) to keep false positives down — the goal is a trustworthy short
 * list of real issues, not a flood of subjective opinions.
 */
@Component
public class AiScreenReviewer {

    private static final Logger log = LoggerFactory.getLogger(AiScreenReviewer.class);
    private static final Set<String> VALID_SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final AiVisionClient client;

    public AiScreenReviewer(AiVisionClient client) {
        this.client = client;
    }

    public boolean isEnabled() {
        return client.isConfigured();
    }

    public record Verdict(String severity, String description) {}

    /**
     * Reviews one screenshot. Returns an empty list if AI review is disabled, the screenshot is
     * missing/unreadable, the screen looks correct, or the API call fails for any reason.
     *
     * @param screenshotFile the PNG captured for this screen
     * @param screenLabel    human-readable screen name, e.g. "Home Screen"
     * @param appContext     short app description for grounding, e.g. "a photo-editing app"
     */
    public List<Verdict> review(File screenshotFile, String screenLabel, String appContext) {
        if (!isEnabled() || screenshotFile == null || !screenshotFile.isFile()) return List.of();
        try {
            byte[] png = Files.readAllBytes(screenshotFile.toPath());
            String text = client.complete(prompt(screenLabel, appContext), png, 500);
            return text == null ? List.of() : parse(text);
        } catch (Exception e) {
            log.debug("AI screen review failed for '{}': {}", screenLabel, e.toString());
            return List.of();
        }
    }

    private static String prompt(String screenLabel, String appContext) {
        return "You are a senior QA engineer reviewing a single screenshot from an Android app"
                + (appContext == null || appContext.isBlank() ? "" : " (" + appContext + ")")
                + ". Screen: " + screenLabel + ".\n\n"
                + "Look ONLY for concrete, visible defects: overlapping or clipped/cut-off text or "
                + "elements, unreadable text against its background, broken or placeholder images, "
                + "obviously misaligned or overflowing layout, garbled/mojibake text, duplicated "
                + "content, or blank areas where content should clearly be.\n"
                + "Do NOT report subjective design opinions, color/branding choices, or missing "
                + "features — only defects a human tester would flag as clearly wrong.\n"
                + "If you are not confident something is actually broken, do not report it.\n\n"
                + "Respond with exactly one line per real issue found, in this exact format:\n"
                + "SEVERITY|short description\n"
                + "SEVERITY must be one of: CRITICAL, HIGH, MEDIUM, LOW.\n"
                + "If the screen looks correct, respond with exactly: NONE";
    }

    private static List<Verdict> parse(String text) {
        List<Verdict> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.equalsIgnoreCase("NONE")) continue;
            int sep = line.indexOf('|');
            if (sep < 0) continue;
            String severity = line.substring(0, sep).trim().toUpperCase();
            String description = line.substring(sep + 1).trim();
            if (description.isEmpty()) continue;
            if (!VALID_SEVERITIES.contains(severity)) severity = "MEDIUM";
            out.add(new Verdict(severity, description));
        }
        return out;
    }
}
