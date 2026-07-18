package com.vasundhara.atf.localization;

import com.vasundhara.atf.compat.CompatAnalyzer;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects the application's in-app <b>Language Settings</b> screen from a UI crawl and
 * reads the languages offered in its selection list — by recognising on-screen labels
 * (in English names and native endonyms) against a language dictionary. This reflects
 * the languages a user can actually choose in the app, which may differ from the raw
 * resources packed in the APK.
 */
public final class LanguageScreenDetector {

    private LanguageScreenDetector() {}

    /** A language option found in the in-app selection list. */
    public record Option(String label, String code, int x, int y, int width, int height) {
        public int centerX() { return x + width / 2; }
        public int centerY() { return y + height / 2; }
    }

    /** Detection outcome. {@code found} is true only when a real selection list is present. */
    public record Result(boolean found, String screen, List<Option> options) {}

    /** Common language codes to recognise even if the APK does not pack them as resources. */
    private static final String[] COMMON = {
            "en","es","fr","de","it","pt","ru","ja","ko","zh","ar","hi","bn","pa","gu","ta","te",
            "mr","ur","tr","nl","pl","sv","da","fi","nb","no","cs","el","he","th","vi","id","ms",
            "uk","ro","hu","fa","sw","fil","tl","sr","hr","sk","sl","bg","lt","lv","et","ka","km",
            "my","si","ne","kn","ml","or","as","am","az","be","bs","ca","eu","gl","hy","is","kk","ky"
    };

    /** Minimum recognised language labels on one screen to consider it a language selector. */
    private static final int MIN_LANGUAGES = 3;

    public static Result detect(ExplorationResult exp, Set<String> apkLocales) {
        if (exp == null || exp.getScreens().isEmpty()) return new Result(false, null, List.of());
        Map<String, String> dict = buildDictionary(apkLocales);

        ScreenCapture best = null;
        List<Option> bestOptions = List.of();
        for (ScreenCapture s : exp.getScreens()) {
            Map<String, Option> byCode = new LinkedHashMap<>();
            for (Widget w : s.widgets()) {
                if (!w.displayed() || w.text() == null) continue;
                String label = w.text().trim();
                String code = dict.get(norm(label));
                if (code == null) continue;
                // Prefer a clickable row; keep the first occurrence per language.
                byCode.putIfAbsent(code, new Option(label, code, w.x(), w.y(), w.width(), w.height()));
            }
            if (byCode.size() > bestOptions.size()) {
                bestOptions = new ArrayList<>(byCode.values());
                best = s;
            }
        }
        if (best == null || bestOptions.size() < MIN_LANGUAGES)
            return new Result(false, null, List.of());
        return new Result(true, CompatAnalyzer.screenName(best), bestOptions);
    }

    /** Public accessor: normalized language label (English name + native endonym) → code. */
    public static Map<String, String> dictionary(Set<String> apkLocales) {
        return buildDictionary(apkLocales);
    }

    /** Map normalized label (English name + native endonym) → BCP47 code. */
    private static Map<String, String> buildDictionary(Set<String> apkLocales) {
        Set<String> codes = new LinkedHashSet<>();
        for (String c : COMMON) codes.add(c);
        if (apkLocales != null)
            for (String tag : apkLocales) {
                String base = tag.split("[-_]")[0].toLowerCase();
                if (!base.isBlank()) codes.add(base);
            }
        Map<String, String> dict = new LinkedHashMap<>();
        for (String code : codes) {
            try {
                Locale loc = Locale.forLanguageTag(code);
                if (loc.getLanguage().isBlank()) loc = new Locale(code);
                String english = loc.getDisplayLanguage(Locale.ENGLISH);
                String endonym = loc.getDisplayLanguage(loc);
                if (english != null && !english.isBlank() && !english.equalsIgnoreCase(code))
                    dict.putIfAbsent(norm(english), code);
                if (endonym != null && !endonym.isBlank() && !endonym.equalsIgnoreCase(code))
                    dict.putIfAbsent(norm(endonym), code);
            } catch (Exception ignored) {}
        }
        return dict;
    }

    private static String norm(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
