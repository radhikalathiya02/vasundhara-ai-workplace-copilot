package com.vasundhara.atf.smartexec.performance;

import com.vasundhara.atf.smartexec.SmartFinding;
import com.vasundhara.atf.smartexec.SmartSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Server-rendered Performance Testing report — same visual language as
 * {@code SmartIssueReportBuilder}/{@code SecurityReportBuilder} (independent implementation, own
 * report so it can show an overall score plus the per-bucket summaries the generic per-category
 * report doesn't compute), filtering {@code session.getFindings()} by {@code category="performance"}
 * the same way every other category's report does.
 */
public final class PerformanceReportBuilder {
    private PerformanceReportBuilder() {}

    public static String generate(SmartSession session) {
        List<SmartFinding> findings = session.getFindings().stream()
                .filter(f -> "performance".equals(f.category())).collect(Collectors.toList());

        long critical = count(findings, "CRITICAL"), high = count(findings, "HIGH"),
             medium = count(findings, "MEDIUM"), low = count(findings, "LOW");
        int score = score(critical, high, medium, low);

        // Bucket findings by their "screenName" field (used to carry the Performance Metric
        // bucket — App Launch / Runtime / Resource Usage / Stability / Network / Rendering /
        // Background / Stress — see PerformanceScanner's class doc).
        Map<String, List<SmartFinding>> byBucket = new LinkedHashMap<>();
        for (SmartFinding f : findings) byBucket.computeIfAbsent(f.screenName(), k -> new java.util.ArrayList<>()).add(f);

        StringBuilder body = new StringBuilder();
        body.append("<div class='score-row'>")
            .append(scoreCard(score))
            .append(countCard("CRITICAL", critical)).append(countCard("HIGH", high))
            .append(countCard("MEDIUM", medium)).append(countCard("LOW", low))
            .append("</div>");
        body.append("<div class='summary'>").append(esc(summaryText(findings.size(), critical, high, medium, low, score))).append("</div>");

        body.append(bucketSummary("App Launch Summary", byBucket.get("App Launch Performance")));
        body.append(bucketSummary("CPU / Memory Usage Summary", concatBuckets(byBucket, "Resource Usage")));
        body.append(bucketSummary("Network Performance Summary", byBucket.get("Network Performance")));
        body.append(bucketSummary("Slow Screens / Rendering Summary", byBucket.get("Rendering Performance")));
        body.append(bucketSummary("ANR / Stability Summary", byBucket.get("Stability")));
        body.append(bucketSummary("Background Behavior Summary", byBucket.get("Background Behavior")));
        body.append(bucketSummary("Stress Validation Summary", byBucket.get("Stress Validation")));

        if (findings.isEmpty()) {
            body.append("<div class='pass-box'><span class='badge'>PASS</span> No performance findings for this run.</div>");
        } else {
            body.append("<h3 style='margin-top:20px'>All Findings</h3>");
            for (SmartFinding f : findings) {
                body.append("<div class='finding sev-").append(esc(f.severity())).append("'>")
                    .append("<div class='fh'><span class='sev-chip'>").append(esc(f.severity())).append("</span> ")
                    .append(esc(f.feature())).append(" <span class='pri'>").append(esc(f.priority())).append("</span></div>")
                    .append("<table>")
                    .append(row("Performance Bucket", f.screenName()))
                    .append(row("Metric", f.feature()))
                    .append(row("Expected / Recommendation", f.expectedResult()))
                    .append(row("Actual", f.actualResult()))
                    .append(row("Timestamp", java.time.Instant.ofEpochMilli(f.timestamp()).toString()));
                if (f.logsExcerpt() != null && !f.logsExcerpt().isBlank())
                    body.append("<tr><td>Logs</td><td><pre>").append(esc(f.logsExcerpt())).append("</pre></td></tr>");
                if (f.screenshotPath() != null) body.append(row("Screenshot", "evidence/" + f.screenshotPath()));
                body.append("</table></div>");
            }
        }

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Performance Report</title><style>"
                + "body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f1f5f9;color:#0f172a;padding:24px}"
                + ".finding{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:14px 18px;margin-bottom:14px}"
                + ".fh{font-weight:700;margin-bottom:8px}.sev-chip{font-size:11px;font-weight:800;padding:2px 8px;border-radius:6px;background:#eee}"
                + ".pri{font-size:11px;color:#64748b;margin-left:6px}"
                + "table{width:100%;font-size:13px}td{padding:4px 8px;vertical-align:top}td:first-child{color:#64748b;width:190px}"
                + ".pass-box{background:#ecfdf5;border:1px solid #a7f3d0;color:#065f46;border-radius:10px;padding:14px 18px}"
                + "pre{white-space:pre-wrap;background:#0f172a;color:#e2e8f0;padding:10px;border-radius:6px;font-size:11px}"
                + ".score-row{display:flex;gap:12px;margin-bottom:14px;flex-wrap:wrap}"
                + ".score-card,.count-card{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:14px 18px;flex:1;min-width:110px}"
                + ".score-card .v{font-size:28px;font-weight:800}.count-card .v{font-size:22px;font-weight:800}"
                + ".score-card .l,.count-card .l{font-size:11px;color:#64748b;text-transform:uppercase;font-weight:700}"
                + ".summary{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:14px 18px;margin-bottom:14px;font-size:13.5px;line-height:1.6}"
                + ".bucket{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:12px 16px;margin-bottom:10px}"
                + ".bucket h4{margin:0 0 8px;font-size:13px}"
                + ".bucket .row{display:flex;justify-content:space-between;font-size:12.5px;padding:3px 0;border-bottom:1px solid #f1f5f9}"
                + "</style></head><body><h2>Performance Report</h2>"
                + "<div class='muted' style='color:#64748b'>" + esc(session.getAppLabel()) + " · " + esc(session.getPackageName()) + "</div>"
                + "<div style='margin-top:16px'>" + body + "</div></body></html>";
    }

    private static List<SmartFinding> concatBuckets(Map<String, List<SmartFinding>> byBucket, String key) {
        return byBucket.getOrDefault(key, List.of());
    }

    private static String bucketSummary(String title, List<SmartFinding> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div class='bucket'><h4>").append(esc(title)).append("</h4>");
        for (SmartFinding f : items) {
            sb.append("<div class='row'><span>").append(esc(f.feature())).append("</span><span>")
              .append("<b>").append(esc(f.severity())).append("</b> — ").append(esc(truncate(f.actualResult(), 90))).append("</span></div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static long count(List<SmartFinding> findings, String sev) {
        return findings.stream().filter(f -> sev.equals(f.severity())).count();
    }

    /** 100 minus a weighted penalty per finding, floored at 0 — same transparent model as the
     *  Security report's scoring (not a claim of parity with a specific commercial tool). */
    public static int score(long critical, long high, long medium, long low) {
        int s = 100 - (int) (critical * 20 + high * 10 + medium * 4 + low * 1);
        return Math.max(0, Math.min(100, s));
    }

    private static String summaryText(int total, long c, long h, long m, long l, int score) {
        if (total == 0) return "No performance findings were produced for this run.";
        String grade = score >= 90 ? "excellent" : score >= 70 ? "good" : score >= 40 ? "needs attention" : "poor";
        return "This run produced " + total + " performance finding(s) — " + c + " critical, " + h + " high, " + m + " medium, " + l + " low — "
                + "for an overall performance score of " + score + "/100 (" + grade + "). "
                + (h + c > 0 ? "Review the launch/rendering/memory findings above before release — they represent measurable, reproducible slowdowns."
                             : "No high-impact performance issues were found this run.");
    }

    private static String scoreCard(int score) {
        return "<div class='score-card'><div class='l'>Performance Score</div><div class='v'>" + score + "/100</div></div>";
    }
    private static String countCard(String label, long v) {
        return "<div class='count-card'><div class='l'>" + esc(label) + "</div><div class='v'>" + v + "</div></div>";
    }

    private static String row(String k, String v) {
        if (v == null || v.isBlank()) return "";
        return "<tr><td>" + esc(k) + "</td><td>" + esc(v) + "</td></tr>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
