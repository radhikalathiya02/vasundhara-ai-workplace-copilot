package com.vasundhara.atf.smartexec.figma;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Matches each app screen (identified only by its inferred on-screen name — the same generic,
 * no-hardcoded-names heuristic {@code SmartCrawler.inferScreenName} already uses elsewhere) to the
 * Figma screen whose frame name reads most similarly. Purely generic string similarity — no
 * app-specific or file-specific mapping table, so it works for any APK/Figma file pairing.
 */
public final class FigmaScreenMatcher {
    private FigmaScreenMatcher() {}

    public record Match(String appScreenName, FigmaScreen figmaScreen, double confidence) {}

    /** Minimum normalized similarity to accept a match — below this, the app screen is reported as
     *  having no corresponding Figma screen, rather than guessing wrong. */
    private static final double MIN_CONFIDENCE = 0.45;

    public static List<Match> match(List<String> appScreenNames, List<FigmaScreen> figmaScreens) {
        List<Match> out = new ArrayList<>();
        if (appScreenNames == null || figmaScreens == null || figmaScreens.isEmpty()) return out;
        Map<FigmaScreen, Boolean> claimed = new LinkedHashMap<>();
        for (String appName : appScreenNames) {
            FigmaScreen best = null;
            double bestScore = 0;
            for (FigmaScreen fs : figmaScreens) {
                if (claimed.containsKey(fs)) continue; // one Figma screen matches at most one app screen
                double score = similarity(appName, fs.name());
                if (score > bestScore) { bestScore = score; best = fs; }
            }
            if (best != null && bestScore >= MIN_CONFIDENCE) {
                claimed.put(best, true);
                out.add(new Match(appName, best, bestScore));
            }
        }
        return out;
    }

    /** Token-overlap (Jaccard) similarity over normalized words — robust to punctuation/case/word-order
     *  differences between how a screen reads on-device vs. how its Figma frame happens to be named. */
    private static double similarity(String a, String b) {
        List<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        long shared = ta.stream().filter(tb::contains).count();
        int union = (int) (ta.size() + tb.size() - shared);
        return union == 0 ? 0 : (double) shared / union;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split("\\s+")) {
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }
}
