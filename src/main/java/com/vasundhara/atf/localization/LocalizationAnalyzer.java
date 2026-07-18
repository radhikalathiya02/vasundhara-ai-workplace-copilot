package com.vasundhara.atf.localization;

import com.vasundhara.atf.compat.CompatAnalyzer;
import com.vasundhara.atf.compat.CompatVersionResult;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Translation validation by comparing the app's visible text before and after a
 * language switch. A visible string that stays identical after switching to the
 * target language (and is translatable — not a number, URL, version, etc.) is
 * reported as untranslated, tagged with the screen it appears on.
 */
public final class LocalizationAnalyzer {

    private LocalizationAnalyzer() {}

    // Numeric / non-translatable tokens to ignore (the user asked to exclude numerics).
    private static final Pattern NUMERIC = Pattern.compile("^[\\d\\s.,:%+\\-/()$€£¥₹*#°]+$");
    private static final Pattern URL_EMAIL = Pattern.compile("(?i)(https?://|www\\.|@\\w+\\.|\\.com|\\.io)");
    private static final Pattern VERSION = Pattern.compile("^[vV]?\\d+(\\.\\d+)+.*$");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

    /**
     * Return a copy of the exploration containing only real app content — the Splash screen
     * and the Language Selection screen are removed so they are never validated (they are used
     * only to navigate / pick a language).
     */
    public static ExplorationResult contentOnly(ExplorationResult exp, Map<String, String> dict) {
        ExplorationResult c = new ExplorationResult();
        if (exp == null) return c;
        c.setCrashSuspected(exp.isCrashSuspected());
        c.setLeftAppDuringRun(exp.isLeftAppDuringRun());
        for (ScreenCapture s : exp.getScreens())
            if (!isLanguageOrSplash(s, dict)) c.addScreen(s);
        for (int i = 0; i < exp.getActionsPerformed(); i++) c.incrementActions();
        return c;
    }

    /** True if the screen is the language picker (many language labels / picker phrasing) or a splash. */
    private static boolean isLanguageOrSplash(ScreenCapture s, Map<String, String> dict) {
        int langHits = 0;
        StringBuilder sb = new StringBuilder();
        for (Widget w : s.widgets()) {
            if (!w.displayed() || w.text() == null || w.text().isBlank()) continue;
            String n = norm(w.text());
            sb.append(n).append(' ');
            if (dict != null) {
                String code = dict.get(n);
                if (code == null) {
                    String stripped = n.replaceAll("\\s*[\\(\\[].*?[\\)\\]]\\s*", "").trim();
                    code = dict.get(stripped);
                }
                if (code != null) langHits++;
            }
        }
        if (langHits >= 3) return true;                       // language selection screen
        String joined = sb.toString();
        if (joined.contains("preferred language") || joined.contains("select language")
                || joined.contains("choose language") || joined.contains("select your language")
                || joined.contains("choose your language")) return true;
        // Splash/transition: the first captured screen with no interactive elements.
        return s.index() == 0 && s.actionableWidgets().isEmpty();
    }

    /** Visible, translatable (non-numeric/URL/version) display text on ONE screen, in document
     *  order, de-duplicated — feeds the LLM translation-quality reviewer one screen at a time. */
    public static List<String> translatableTexts(ScreenCapture s) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (s == null) return out;
        for (Widget w : s.widgets()) {
            if (!w.displayed() || w.text() == null || w.text().isBlank()) continue;
            if (isImageWidget(w) || isAdWidget(w)) continue;
            String raw = w.text().trim();
            if (!translatable(raw)) continue;
            if (seen.add(norm(raw))) out.add(raw);
        }
        return out;
    }

    /** Collect normalized visible text strings from a run (for the baseline set). */
    public static Set<String> collectText(ExplorationResult exp) {
        Set<String> out = new LinkedHashSet<>();
        if (exp == null) return out;
        for (ScreenCapture s : exp.getScreens())
            for (Widget w : s.widgets())
                if (w.displayed() && w.text() != null && !w.text().isBlank()
                        && !isImageWidget(w) && !isAdWidget(w))
                    out.add(norm(w.text()));
        return out;
    }

    /**
     * Find strings in the target-language run that are unchanged from the baseline
     * (i.e. not translated). Returns one entry per (screen, text), with the screen's
     * screenshot URL for evidence.
     *
     * @param baseline       normalized text seen in the default-locale run
     * @param exp            the target-language exploration
     * @param artifactBase   URL prefix to build screenshot links, e.g. "/api/runs/<id>/artifacts/"
     */
    public static List<LanguageResult.Untranslated> findUntranslated(
            Set<String> baseline, ExplorationResult exp, String artifactBase) {
        List<LanguageResult.Untranslated> out = new ArrayList<>();
        if (exp == null) return out;
        Set<String> reported = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens()) {
            String screen = CompatAnalyzer.screenName(s);
            String shot = s.screenshotPath();
            String url = (shot != null && !shot.isBlank() && artifactBase != null) ? artifactBase + shot : null;
            for (Widget w : s.widgets()) {
                if (!w.displayed() || w.text() == null || w.text().isBlank()) continue;
                if (isImageWidget(w) || isAdWidget(w)) continue;
                String raw = w.text().trim();
                if (!translatable(raw)) continue;
                String n = norm(raw);
                if (!baseline.contains(n)) continue;          // it changed → translated
                String key = screen + "||" + n;
                if (reported.add(key)) out.add(new LanguageResult.Untranslated(screen, raw, url));
            }
        }
        return out;
    }

    /** Count distinct visible translatable strings in a run (denominator for completeness). */
    public static int countVisibleStrings(ExplorationResult exp) {
        if (exp == null) return 0;
        Set<String> set = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens())
            for (Widget w : s.widgets())
                if (w.displayed() && w.text() != null && translatable(w.text().trim())
                        && !isImageWidget(w) && !isAdWidget(w))
                    set.add(norm(w.text()));
        return set.size();
    }

    // A Latin "word" — a run of 3+ ASCII letters (short tokens like "OK"/IDs cause false positives).
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{3,}");

    /** Script-based findings for one language: text in the wrong script, and mixed-script text. */
    public static final class ScriptFindings {
        public final List<LanguageResult.Untranslated> untranslated = new ArrayList<>(); // expected script absent
        public final List<LanguageResult.Untranslated> mixed = new ArrayList<>();        // expected + Latin together
    }

    /**
     * Unicode ranges for a language's expected script, or {@code null} for Latin-script /
     * unknown languages (where a script check cannot distinguish translated from untranslated —
     * the unchanged-string heuristic in {@link #findUntranslated} handles those instead).
     */
    private static int[][] scriptRanges(String code) {
        if (code == null) return null;
        String base = code.split("[-_]")[0].toLowerCase();
        return switch (base) {
            case "hi", "mr", "ne", "sa", "kok" -> new int[][]{{0x0900, 0x097F}};            // Devanagari
            case "bn", "as" -> new int[][]{{0x0980, 0x09FF}};                                // Bengali
            case "pa" -> new int[][]{{0x0A00, 0x0A7F}};                                       // Gurmukhi
            case "gu" -> new int[][]{{0x0A80, 0x0AFF}};                                       // Gujarati
            case "or" -> new int[][]{{0x0B00, 0x0B7F}};                                       // Odia
            case "ta" -> new int[][]{{0x0B80, 0x0BFF}};                                       // Tamil
            case "te" -> new int[][]{{0x0C00, 0x0C7F}};                                       // Telugu
            case "kn" -> new int[][]{{0x0C80, 0x0CFF}};                                       // Kannada
            case "ml" -> new int[][]{{0x0D00, 0x0D7F}};                                       // Malayalam
            case "si" -> new int[][]{{0x0D80, 0x0DFF}};                                       // Sinhala
            case "th" -> new int[][]{{0x0E00, 0x0E7F}};                                       // Thai
            case "lo" -> new int[][]{{0x0E80, 0x0EFF}};                                       // Lao
            case "my" -> new int[][]{{0x1000, 0x109F}};                                       // Myanmar
            case "km" -> new int[][]{{0x1780, 0x17FF}};                                       // Khmer
            case "ka" -> new int[][]{{0x10A0, 0x10FF}};                                       // Georgian
            case "am", "ti" -> new int[][]{{0x1200, 0x137F}};                                 // Ethiopic
            case "ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg"
                    -> new int[][]{{0x0400, 0x04FF}};                                         // Cyrillic
            case "el" -> new int[][]{{0x0370, 0x03FF}};                                       // Greek
            case "he", "yi" -> new int[][]{{0x0590, 0x05FF}};                                 // Hebrew
            case "ar", "fa", "ur", "ps", "sd", "ckb"
                    -> new int[][]{{0x0600, 0x06FF}, {0x0750, 0x077F}, {0xFB50, 0xFDFF}, {0xFE70, 0xFEFF}}; // Arabic
            case "zh" -> new int[][]{{0x4E00, 0x9FFF}, {0x3400, 0x4DBF}};                     // Han
            case "ja" -> new int[][]{{0x3040, 0x30FF}, {0x4E00, 0x9FFF}};                     // Kana + Han
            case "ko" -> new int[][]{{0xAC00, 0xD7A3}, {0x1100, 0x11FF}};                     // Hangul
            default -> null;                                                                  // Latin / unknown
        };
    }

    /** True if the language uses a non-Latin script we can check for. */
    public static boolean hasCheckableScript(String code) {
        return scriptRanges(code) != null;
    }

    /**
     * Script-based validation for a non-Latin language: a visible translatable string with NO
     * character of the expected script but containing a Latin word is reported as untranslated
     * (English/hardcoded text left in); a string mixing the expected script WITH a Latin word is
     * reported as mixed-language content. Returns empty for Latin-script languages.
     */
    public static ScriptFindings analyzeScript(ExplorationResult exp, String langCode, String artifactBase) {
        ScriptFindings f = new ScriptFindings();
        int[][] ranges = scriptRanges(langCode);
        if (ranges == null || exp == null) return f;
        Set<String> reportedU = new LinkedHashSet<>(), reportedM = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens()) {
            String screen = CompatAnalyzer.screenName(s);
            String shot = s.screenshotPath();
            String url = (shot != null && !shot.isBlank() && artifactBase != null) ? artifactBase + shot : null;
            for (Widget w : s.widgets()) {
                if (!w.displayed() || w.text() == null || w.text().isBlank()) continue;
                if (isImageWidget(w) || isAdWidget(w)) continue;
                String raw = w.text().trim();
                if (!translatable(raw)) continue;
                boolean hasTarget = hasScript(raw, ranges);
                boolean hasLatin = LATIN_WORD.matcher(raw).find();
                if (!hasTarget && hasLatin) {
                    if (reportedU.add(screen + "||" + norm(raw)))
                        f.untranslated.add(new LanguageResult.Untranslated(screen, raw, url));
                } else if (hasTarget && hasLatin) {
                    if (reportedM.add(screen + "||" + norm(raw)))
                        f.mixed.add(new LanguageResult.Untranslated(screen, raw, url));
                }
            }
        }
        return f;
    }

    private static boolean hasScript(String s, int[][] ranges) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            for (int[] r : ranges) if (c >= r[0] && c <= r[1]) return true;
        }
        return false;
    }

    private static boolean translatable(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() < 2) return false;
        if (!HAS_LETTER.matcher(s).find()) return false; // must contain a letter
        if (NUMERIC.matcher(s).matches()) return false;   // pure numeric/punct
        if (VERSION.matcher(s).matches()) return false;   // version strings
        if (URL_EMAIL.matcher(s).find()) return false;    // urls/emails
        return true;
    }

    // ── New Senior-QA issue detectors ────────────────────────────────────────

    /** True for languages that use a right-to-left script. */
    public static boolean isRtlLanguage(String code) {
        if (code == null) return false;
        String base = code.split("[-_]")[0].toLowerCase();
        return switch (base) {
            case "ar", "he", "fa", "ur", "ps", "sd", "ckb", "yi", "dv" -> true;
            default -> false;
        };
    }

    /**
     * Detect text truncation: visible text ending in a horizontal ellipsis (…) or three
     * ASCII dots (...) indicates the widget was too narrow to show the full translated string.
     */
    public static List<LanguageResult.LocalizationIssue> detectTruncation(
            ExplorationResult exp, String langName, String artifactBase) {
        List<LanguageResult.LocalizationIssue> out = new ArrayList<>();
        if (exp == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens()) {
            String screen = CompatAnalyzer.screenName(s);
            String url    = shotUrl(s.screenshotPath(), artifactBase);
            for (Widget w : s.widgets()) {
                if (!w.displayed() || w.text() == null || w.text().isBlank()) continue;
                if (isImageWidget(w) || isAdWidget(w)) continue;
                String text = w.text().trim();
                if (!translatable(text)) continue;
                boolean ellipsis  = text.endsWith("…");                     // …
                boolean dotdotdot = text.endsWith("...") && text.length() > 5;  // ...
                if (!(ellipsis || dotdotdot)) continue;
                String key = screen + "||truncate||" + norm(text);
                if (seen.add(key)) {
                    out.add(new LanguageResult.LocalizationIssue(
                            screen,
                            "TEXT_TRUNCATION",
                            "Text is truncated — the " + langName + " translation is longer than the UI widget allows: \""
                                    + text + "\"",
                            "The full translated text should be visible for " + langName
                                    + " without any clipping or ellipsis.",
                            "Text is cut off with ellipsis: \"" + text + "\"",
                            url, "MEDIUM"));
                }
            }
        }
        return out;
    }

    /**
     * Detect encoding / rendering failures: Unicode replacement characters (U+FFFD),
     * empty box glyphs (□ U+25A1), or three or more consecutive question marks in text
     * that otherwise contains alphabetic characters.
     */
    public static List<LanguageResult.LocalizationIssue> detectEncodingIssues(
            ExplorationResult exp, String langName, String artifactBase) {
        List<LanguageResult.LocalizationIssue> out = new ArrayList<>();
        if (exp == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens()) {
            String screen = CompatAnalyzer.screenName(s);
            String url    = shotUrl(s.screenshotPath(), artifactBase);
            for (Widget w : s.widgets()) {
                if (!w.displayed() || w.text() == null || w.text().isBlank()) continue;
                if (isImageWidget(w) || isAdWidget(w)) continue;
                String text = w.text().trim();
                if (!hasEncodingIssue(text)) continue;
                String key = screen + "||enc||" + norm(text).substring(0, Math.min(40, norm(text).length()));
                if (seen.add(key)) {
                    out.add(new LanguageResult.LocalizationIssue(
                            screen,
                            "ENCODING_ISSUE",
                            "Text contains encoding or font-rendering failure for " + langName + ": \""
                                    + text + "\"",
                            "All characters in " + langName
                                    + " should render correctly — no replacement glyphs or garbled text.",
                            "Garbled, missing, or replacement characters detected: \"" + text + "\"",
                            url, "HIGH"));
                }
            }
        }
        return out;
    }

    /**
     * Detect RTL/LTR layout mismatches for right-to-left languages. In a correct RTL layout,
     * most text widgets are anchored to the right side of the screen. If the majority are
     * left-anchored (x < 30 % of screen width), the app is using an LTR layout for an RTL language.
     */
    public static List<LanguageResult.LocalizationIssue> detectRtlIssues(
            ExplorationResult exp, String langCode, String langName, String artifactBase, int screenW) {
        List<LanguageResult.LocalizationIssue> out = new ArrayList<>();
        if (exp == null || screenW <= 0 || !isRtlLanguage(langCode)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens()) {
            String screen = CompatAnalyzer.screenName(s);
            String url    = shotUrl(s.screenshotPath(), artifactBase);
            // Only consider wide text widgets (>15 % of screen width) to avoid short centered labels.
            // Image widgets are excluded — their bounds reflect the image asset, not a text layout.
            List<Widget> wide = s.widgets().stream()
                    .filter(w -> w.displayed() && w.text() != null && !w.text().isBlank()
                            && w.width() > screenW * 0.15 && !isImageWidget(w) && !isAdWidget(w))
                    .toList();
            if (wide.size() < 3) continue;
            long leftAnchored = wide.stream().filter(w -> w.x() >= 0 && w.x() < screenW * 0.30).count();
            // If >70 % of wide text widgets are left-anchored, flag as probable LTR layout.
            if (leftAnchored > wide.size() * 0.70) {
                String key = screen + "||rtl";
                if (seen.add(key)) {
                    out.add(new LanguageResult.LocalizationIssue(
                            screen,
                            "RTL_LAYOUT_ISSUE",
                            langName + " is a right-to-left language but the layout on screen '"
                                    + screen + "' appears left-to-right ("
                                    + leftAnchored + " of " + wide.size() + " text elements are left-anchored).",
                            "Layout should be mirrored for RTL language " + langName
                                    + ": text right-aligned, navigation icons swapped, and UI elements reversed.",
                            leftAnchored + " / " + wide.size() + " text elements positioned at x < "
                                    + Math.round(screenW * 0.30) + "px — LTR layout suspected.",
                            url, "HIGH"));
                }
            }
        }
        return out;
    }

    /**
     * Convert the existing untranslated, mixed-language and functionality findings into the
     * unified {@link LanguageResult.LocalizationIssue} Senior-QA format (with explicit
     * expectedResult / actualResult fields).  Should only be called for non-reference languages.
     */
    public static List<LanguageResult.LocalizationIssue> toQaIssues(
            List<LanguageResult.Untranslated> untranslated,
            List<LanguageResult.Untranslated> mixed,
            List<CompatVersionResult.Issue> funcIssues,
            String langName) {
        List<LanguageResult.LocalizationIssue> out = new ArrayList<>();
        for (LanguageResult.Untranslated u : untranslated) {
            out.add(new LanguageResult.LocalizationIssue(
                    u.screen(),
                    "MISSING_TRANSLATION",
                    "String \"" + u.text() + "\" was not translated into " + langName + ".",
                    "All visible strings should be translated into " + langName + ".",
                    "String is still displayed in the source language: \"" + u.text() + "\"",
                    u.screenshotUrl(), "MEDIUM"));
        }
        for (LanguageResult.Untranslated m : mixed) {
            out.add(new LanguageResult.LocalizationIssue(
                    m.screen(),
                    "MIXED_LANGUAGE",
                    "String mixes " + langName + " script with untranslated words: \"" + m.text() + "\"",
                    "Text should be entirely in " + langName + " — no mixed-language content.",
                    "Mixed content detected: " + langName + " script and Latin characters appear together: \""
                            + m.text() + "\"",
                    m.screenshotUrl(), "MEDIUM"));
        }
        for (CompatVersionResult.Issue i : funcIssues) {
            String sev = switch (i.severity() == null ? "" : i.severity().toUpperCase()) {
                case "CRITICAL" -> "CRITICAL";
                case "HIGH"     -> "HIGH";
                case "LOW"      -> "LOW";
                default         -> "MEDIUM";
            };
            out.add(new LanguageResult.LocalizationIssue(
                    i.screen(),
                    "FUNCTIONALITY_ISSUE",
                    i.description(),
                    "Application should function correctly after switching to " + langName + ".",
                    i.description(),
                    i.screenshotUrl(), sev));
        }
        return out;
    }

    /**
     * Detect overlapping text elements: two displayed text widgets whose bounding rectangles
     * intersect significantly (>20% overlap area relative to the smaller widget) indicate
     * a layout collision — typically caused by a translated string being too long for its
     * fixed-size container, or RTL mirroring applied inconsistently.
     */
    public static List<LanguageResult.LocalizationIssue> detectOverlappingText(
            ExplorationResult exp, String langName, String artifactBase) {
        List<LanguageResult.LocalizationIssue> out = new ArrayList<>();
        if (exp == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ScreenCapture s : exp.getScreens()) {
            String screen = CompatAnalyzer.screenName(s);
            String url    = shotUrl(s.screenshotPath(), artifactBase);
            List<Widget> textWidgets = s.widgets().stream()
                    .filter(w -> w.displayed() && w.text() != null && !w.text().isBlank()
                            && w.width() > 0 && w.height() > 0 && translatable(w.text().trim())
                            && !isImageWidget(w) && !isAdWidget(w))
                    .toList();
            for (int i = 0; i < textWidgets.size(); i++) {
                Widget a = textWidgets.get(i);
                for (int j = i + 1; j < textWidgets.size(); j++) {
                    Widget b = textWidgets.get(j);
                    int overlapW = Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x());
                    int overlapH = Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
                    if (overlapW <= 0 || overlapH <= 0) continue;
                    int overlapArea = overlapW * overlapH;
                    int smallerArea = Math.min(a.width() * a.height(), b.width() * b.height());
                    if (smallerArea <= 0 || overlapArea < smallerArea * 0.20) continue;
                    String key = screen + "||overlap||" + norm(a.text()) + "||" + norm(b.text());
                    if (seen.add(key)) {
                        out.add(new LanguageResult.LocalizationIssue(
                                screen,
                                "OVERLAPPING_TEXT",
                                "Two text elements overlap on screen '" + screen + "' in " + langName
                                        + ": \"" + a.text().trim() + "\" and \"" + b.text().trim() + "\"",
                                "All text elements should be fully visible without overlapping each other in "
                                        + langName + ".",
                                "\"" + a.text().trim() + "\" overlaps with \"" + b.text().trim()
                                        + "\" — " + overlapW + "×" + overlapH + "px intersection.",
                                url, "HIGH"));
                    }
                }
            }
        }
        return out;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns true when the widget is a pure image container whose text attribute, if set at all,
     * reflects content drawn into the image asset — not independently translatable UI text.
     *
     * Covers standard Android image views and any custom subclass whose class name ends with
     * "ImageView" (e.g. AppCompatImageView, RoundedImageView, etc.).
     * ImageButton is intentionally excluded: it is a tappable control that may carry a text label.
     */
    private static boolean isImageWidget(Widget w) {
        if (w.className() == null) return false;
        String cls = w.className().toLowerCase();
        return cls.endsWith("imageview") && !cls.contains("button");
    }

    /**
     * Returns true when the widget originates from an advertisement (banner, interstitial, native,
     * rewarded, app-open). Ad content is served by ad networks and is not translated by the app —
     * it must never be validated for missing translations, truncation, encoding issues, RTL layout,
     * or overlapping text. Defense-in-depth guard applied in every analysis method; the primary
     * exclusion happens in LocalizationCrawler.toWidgets() before widgets are stored.
     */
    private static boolean isAdWidget(Widget w) {
        String cls  = w.className()   == null ? "" : w.className().toLowerCase();
        String id   = w.resourceId()  == null ? "" : w.resourceId().toLowerCase();
        String desc = w.contentDesc() == null ? "" : w.contentDesc().toLowerCase();
        String txt  = w.text()        == null ? "" : w.text().toLowerCase();
        if (id.startsWith("com.google.android.gms") || id.startsWith("com.google.ads")) return true;
        if (cls.contains("adview") || cls.contains("nativeadview") || cls.contains("adiconview")
                || cls.contains("mediaview") || cls.contains("adchoicesview")
                || cls.contains("unifiedadview")) return true;
        if (id.contains("adview") || id.contains("ad_view") || id.contains("banner_ad")
                || id.contains("ad_banner") || id.contains("ad_container")
                || id.contains("admob") || id.contains("native_ad")
                || id.contains("rewarded_ad") || id.contains("app_open_ad")
                || id.contains("ad_frame") || id.contains("ad_overlay")) return true;
        if (desc.equals("advertisement") || desc.contains("sponsored")
                || desc.contains("close ad") || desc.contains("skip ad")
                || desc.contains("test ad") || desc.contains("admob")) return true;
        if (txt.equals("advertisement") || txt.equals("sponsored")
                || txt.contains("test ad") || txt.contains("admob test")) return true;
        return false;
    }

    /** Build a serveable screenshot URL from a relative path and an artifact base prefix. */
    private static String shotUrl(String path, String base) {
        return (path != null && !path.isBlank() && base != null) ? base + path : null;
    }

    /** True when a string contains known encoding-failure glyphs. */
    private static boolean hasEncodingIssue(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '�') return true;   // Unicode replacement character
            if (c == '□') return true;   // White square □
        }
        // Three or more consecutive '?' in otherwise-alphabetic text
        if (HAS_LETTER.matcher(text).find() && text.contains("???")) return true;
        return false;
    }

    private static String norm(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
