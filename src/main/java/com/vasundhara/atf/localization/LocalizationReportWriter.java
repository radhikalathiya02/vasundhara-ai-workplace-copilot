package com.vasundhara.atf.localization;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes a single, self-contained, developer-friendly HTML report for ONE language's localization
 * test result — screenshots and reproducible steps included — saved into that language's own run
 * directory the moment its testing finishes, before the app is reset for the next language. This
 * is in addition to (not instead of) the consolidated cross-language summary
 * {@code LocalizationRunner} appends to the session log at the very end.
 */
final class LocalizationReportWriter {

    private LocalizationReportWriter() {}

    /** Writes {@code report.html} into {@code runDir} and returns the file (best-effort — logs and
     *  returns null on any I/O failure rather than failing the run). */
    static File write(File runDir, String appLabel, LanguageResult lr) {
        try {
            runDir.mkdirs();
            File f = new File(runDir, "report.html");
            Files.writeString(f.toPath(), build(appLabel, lr), StandardCharsets.UTF_8);
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    private static String build(String appLabel, LanguageResult lr) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'>")
         .append("<title>Localization Report — ").append(esc(lr.getName())).append("</title>")
         .append("<style>")
         .append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0b0e14;color:#e6e8ee;margin:0;padding:24px}")
         .append("h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:24px 0 8px;color:#9aa4b2}")
         .append(".sub{color:#9aa4b2;font-size:13px;margin-bottom:20px}")
         .append(".pills{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0 20px}")
         .append(".pill{padding:4px 10px;border-radius:999px;font-size:12px;font-weight:600}")
         .append(".pass{background:#123d2b;color:#4ade80}.warn{background:#3d3312;color:#fbbf24}")
         .append(".fail{background:#3d1414;color:#f87171}.info{background:#1a2438;color:#7dd3fc}")
         .append("table{width:100%;border-collapse:collapse;font-size:13px;margin-bottom:24px}")
         .append("th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #1f2532;vertical-align:top}")
         .append("th{color:#9aa4b2;font-weight:600;font-size:12px}")
         .append("img{max-width:220px;border-radius:6px;border:1px solid #1f2532;display:block}")
         .append(".sev-CRITICAL{color:#f87171;font-weight:700}.sev-HIGH{color:#fb923c;font-weight:700}")
         .append(".sev-MEDIUM{color:#fbbf24}.sev-LOW{color:#7dd3fc}.sev-INFO{color:#9aa4b2}")
         .append("ol{margin:0;padding-left:18px}li{margin-bottom:2px}")
         .append("</style></head><body>");

        h.append("<h1>Localization Report — ").append(esc(appLabel)).append("</h1>")
         .append("<div class='sub'>Language: <b>").append(esc(lr.getName())).append(" (")
         .append(esc(lr.getCode())).append(")</b> &middot; Switch method: ").append(esc(lr.getSwitchMethod()))
         .append("</div>");

        h.append("<div class='pills'>")
         .append(pill(lr.getTranslationStatus())).append(pill(lr.getFunctionalityStatus()))
         .append("<span class='pill info'>Screens: ").append(lr.getScreensExplored()).append("</span>")
         .append("<span class='pill info'>Visible strings: ").append(lr.getVisibleStrings()).append("</span>")
         .append("<span class='pill info'>Untranslated: ").append(lr.getUntranslated().size()).append("</span>")
         .append("<span class='pill info'>Mixed-language: ").append(lr.getMixedLanguage().size()).append("</span>")
         .append("<span class='pill info'>Issues: ").append(lr.getLocalizationIssues().size()).append("</span>")
         .append("</div>");

        h.append("<h2>Issues</h2>");
        if (lr.getLocalizationIssues().isEmpty()) {
            h.append("<p style='color:#9aa4b2'>No localization issues found for this language.</p>");
        } else {
            h.append("<table><tr><th>Screen</th><th>Type</th><th>Severity</th><th>Description</th>")
             .append("<th>Expected</th><th>Actual</th><th>Reproducible steps</th><th>Screenshot</th></tr>");
            for (LanguageResult.LocalizationIssue i : lr.getLocalizationIssues()) {
                h.append("<tr>")
                 .append("<td>").append(esc(i.screen())).append("</td>")
                 .append("<td>").append(esc(i.issueType())).append("</td>")
                 .append("<td class='sev-").append(esc(i.severity())).append("'>").append(esc(i.severity())).append("</td>")
                 .append("<td>").append(esc(i.description())).append("</td>")
                 .append("<td>").append(esc(i.expectedResult())).append("</td>")
                 .append("<td>").append(esc(i.actualResult())).append("</td>")
                 .append("<td>").append(steps(lr.getName(), i.screen())).append("</td>")
                 .append("<td>").append(shot(i.screenshotUrl())).append("</td>")
                 .append("</tr>");
            }
            h.append("</table>");
        }

        if (!lr.getUntranslated().isEmpty()) {
            h.append("<h2>Untranslated strings</h2><table><tr><th>Screen</th><th>Text</th><th>Screenshot</th></tr>");
            for (LanguageResult.Untranslated u : lr.getUntranslated()) {
                h.append("<tr><td>").append(esc(u.screen())).append("</td><td>").append(esc(u.text()))
                 .append("</td><td>").append(shot(u.screenshotUrl())).append("</td></tr>");
            }
            h.append("</table>");
        }

        if (lr.getError() != null && !lr.getError().isBlank()) {
            h.append("<h2>Error</h2><p class='sev-CRITICAL'>").append(esc(lr.getError())).append("</p>");
        }

        h.append("</body></html>");
        return h.toString();
    }

    private static String pill(String status) {
        String cls = switch (status == null ? "" : status.toUpperCase()) {
            case "PASS", "COMPLETE" -> "pass";
            case "FAIL", "MOSTLY UNTRANSLATED", "ERROR" -> "fail";
            case "WARNING", "PARTIAL" -> "warn";
            default -> "info";
        };
        return "<span class='pill " + cls + "'>" + esc(status == null ? "-" : status) + "</span>";
    }

    private static String steps(String langName, String screen) {
        return "<ol>"
                + "<li>Install and launch the app.</li>"
                + "<li>Open the in-app Language Selection screen.</li>"
                + "<li>Select \"" + esc(langName) + "\".</li>"
                + "<li>Navigate to the \"" + esc(screen) + "\" screen.</li>"
                + "<li>Observe the actual result described in this row against the expected result.</li>"
                + "</ol>";
    }

    private static String shot(String url) {
        return (url == null || url.isBlank()) ? "" : "<a href='" + esc(url) + "' target='_blank'><img src='"
                + esc(url) + "'></a>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
