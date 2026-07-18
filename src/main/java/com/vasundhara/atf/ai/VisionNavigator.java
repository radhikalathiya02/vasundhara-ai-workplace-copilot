package com.vasundhara.atf.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vision-based navigation for opaque / canvas-rendered screens. Flutter, game-engine and
 * fully-custom-canvas apps paint their entire UI (text, buttons, tiles) inside a handful of
 * full-screen {@code View} nodes that expose no accessibility tree, so the node-based crawler
 * has nothing to find or tap and stalls (see {@code ExplorationEngine.looksOpaqueCanvas}). This
 * navigator hands the actual <em>screenshot</em> to a vision-capable model and asks it for the
 * single best control to tap to keep exploring — returned as absolute device pixel coordinates,
 * which the crawler taps directly. This is the only mechanism that can drive such apps, because
 * the tap target simply does not exist as a queryable node.
 *
 * <p>Backend-agnostic: it delegates the actual model call to {@link AiVisionClient}, so it works
 * with a local Ollama vision model (FREE, no key) exactly as with the paid Anthropic API — the
 * only difference is the {@code atf.ai-provider} setting. A complete no-op when AI review is
 * disabled/unconfigured, and every failure mode (network, malformed response, out-of-range
 * coordinates) resolves to "no suggestion" rather than throwing, so it can only ever help.
 *
 * <p>The prompt explicitly forbids choosing ads, purchase/subscribe controls, external links and
 * Back/Close/Exit, so the framework's standing "never tap ads / never purchase / never leave the
 * app" guarantees still hold on screens where those controls can't be recognised structurally.
 */
@Component
public class VisionNavigator {

    private static final Pattern COORD =
            Pattern.compile("(?i)x\\s*=\\s*(\\d{1,3}(?:\\.\\d+)?)\\D+?y\\s*=\\s*(\\d{1,3}(?:\\.\\d+)?)");

    private final AiVisionClient client;

    public VisionNavigator(AiVisionClient client) {
        this.client = client;
    }

    public boolean isEnabled() {
        return client.isConfigured();
    }

    /** A chosen tap target in absolute device pixels, plus the model's short label for logging. */
    public record Tap(int x, int y, String label) {}

    /**
     * Ask the vision model for the best control to tap on the given screenshot to make exploration
     * progress. Returns {@code null} when disabled, on any failure, or when the model declines.
     *
     * @param pngBytes    the current screen captured as a PNG
     * @param screenW     device screen width in pixels (used to map the model's % answer to pixels)
     * @param screenH     device screen height in pixels
     * @param appContext  short app description for grounding (e.g. the application label)
     * @param triedPercent points already tapped on THIS screen with no progress, as {x%,y%} pairs,
     *                     so the model is told to pick something different
     */
    public Tap chooseTapPoint(byte[] pngBytes, int screenW, int screenH,
                              String appContext, List<int[]> triedPercent) {
        if (!isEnabled() || pngBytes == null || pngBytes.length == 0 || screenW <= 0 || screenH <= 0) {
            return null;
        }
        String text = client.complete(prompt(appContext, triedPercent), pngBytes, 200);
        if (text == null || text.isBlank()) return null;
        return parse(text, screenW, screenH);
    }

    private static String prompt(String appContext, List<int[]> tried) {
        StringBuilder avoid = new StringBuilder();
        if (tried != null && !tried.isEmpty()) {
            avoid.append(" You already tapped these points on this exact screen with NO progress, so pick a "
                    + "clearly different control:");
            for (int[] p : tried) avoid.append(" (").append(p[0]).append("%,").append(p[1]).append("%)");
        }
        return "You are a QA engineer exploring an Android app"
                + (appContext == null || appContext.isBlank() ? "" : " (" + appContext + ")")
                + " by tapping the screen. This app renders its UI on a canvas, so I can only tap by "
                + "coordinate. Look at this screenshot and choose the SINGLE best control to tap to make "
                + "forward progress — advance onboarding, dismiss a first-run dialog, open a feature, or "
                + "navigate to a screen not yet seen. Prefer a primary call-to-action button (e.g. "
                + "Continue, Start, Next, Get Started, Allow, Create) or a distinct feature tile.\n"
                + "NEVER choose: advertisements, purchase / subscribe / upgrade / 'go premium' controls, "
                + "external links (rate us, share, social media, privacy policy, visit website), or "
                + "Back / Close / Exit / X controls." + avoid + "\n\n"
                + "Respond with EXACTLY one line and nothing else, in this format:\n"
                + "TAP x=<0-100> y=<0-100> | <short label of what you are tapping>\n"
                + "where x and y are the horizontal and vertical position of the control's CENTRE as a "
                + "percentage of the screen width and height (top-left is 0,0). If there is no safe, "
                + "useful control to tap, respond with exactly: NONE";
    }

    static Tap parse(String text, int screenW, int screenH) {
        if (text == null) return null;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("NONE")) return null;
            Matcher m = COORD.matcher(line);
            if (!m.find()) continue;
            double xp = Double.parseDouble(m.group(1));
            double yp = Double.parseDouble(m.group(2));
            if (xp < 0 || xp > 100 || yp < 0 || yp > 100) continue;
            int x = (int) Math.round(screenW * xp / 100.0);
            int y = (int) Math.round(screenH * yp / 100.0);
            x = Math.max(1, Math.min(screenW - 1, x));
            y = Math.max(1, Math.min(screenH - 1, y));
            String label = "";
            int bar = line.indexOf('|');
            if (bar >= 0 && bar + 1 < line.length()) label = line.substring(bar + 1).trim();
            return new Tap(x, y, label);
        }
        return null;
    }
}
