package com.vasundhara.atf.smartexec.security;

import com.vasundhara.atf.smartexec.SmartFinding;
import com.vasundhara.atf.smartexec.SmartSession;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Server-rendered Security Testing report — same visual language as {@code SmartIssueReportBuilder}
 * (independent implementation, own report so it can show an overall score/summary header the
 * generic per-category report doesn't compute), filtering {@code session.getFindings()} by
 * {@code category="security"} same as every other category's report.
 */
public final class SecurityReportBuilder {
    private SecurityReportBuilder() {}

    public static String generate(SmartSession session) {
        List<SmartFinding> findings = session.getFindings().stream()
                .filter(f -> "security".equals(f.category())).collect(Collectors.toList());

        long critical = count(findings, "CRITICAL"), high = count(findings, "HIGH"),
             medium = count(findings, "MEDIUM"), low = count(findings, "LOW");
        int score = securityScore(critical, high, medium, low);

        StringBuilder body = new StringBuilder();
        body.append("<div class='score-row'>")
            .append(scoreCard(score))
            .append(countCard("CRITICAL", critical)).append(countCard("HIGH", high))
            .append(countCard("MEDIUM", medium)).append(countCard("LOW", low))
            .append("</div>");
        body.append("<div class='summary'>").append(esc(summaryText(findings.size(), critical, high, medium, low, score))).append("</div>");

        if (findings.isEmpty()) {
            body.append("<div class='pass-box'><span class='badge'>PASS</span> Bug Report Not Available.</div>");
        } else {
            for (SmartFinding f : findings) {
                body.append("<div class='finding sev-").append(esc(f.severity())).append("'>")
                    .append("<div class='fh'><span class='sev-chip'>").append(esc(f.severity())).append("</span> ")
                    .append(esc(f.title())).append(" <span class='pri'>").append(esc(f.priority())).append("</span></div>")
                    .append("<table>")
                    .append(row("Security Category", f.screenName()))
                    .append(row("Description / OWASP Mapping", f.actualResult()))
                    .append(row("Recommendation", f.expectedResult()))
                    .append(row("Steps to Reproduce", f.stepsToReproduce() == null || f.stepsToReproduce().isEmpty() ? null : String.join(" → ", f.stepsToReproduce())))
                    .append(row("Timestamp", java.time.Instant.ofEpochMilli(f.timestamp()).toString()));
                if (f.logsExcerpt() != null && !f.logsExcerpt().isBlank())
                    body.append("<tr><td>Evidence</td><td><pre>").append(esc(f.logsExcerpt())).append("</pre></td></tr>");
                if (f.screenshotPath() != null) body.append(row("Screenshot", "evidence/" + f.screenshotPath()));
                body.append("</table></div>");
            }
        }

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Security Report</title><style>"
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
                + "</style></head><body><h2>Security Report</h2>"
                + "<div class='muted' style='color:#64748b'>" + esc(session.getAppLabel()) + " · " + esc(session.getPackageName()) + "</div>"
                + "<div style='margin-top:16px'>" + body + "</div></body></html>";
    }

    private static long count(List<SmartFinding> findings, String sev) {
        return findings.stream().filter(f -> sev.equals(f.severity())).count();
    }

    /** 100 minus a weighted penalty per finding, floored at 0 — a simple, transparent scoring
     *  model (not a claim of parity with any specific commercial scanner's methodology). */
    public static int securityScore(long critical, long high, long medium, long low) {
        int score = 100 - (int) (critical * 20 + high * 10 + medium * 4 + low * 1);
        return Math.max(0, Math.min(100, score));
    }

    private static String summaryText(int total, long c, long h, long m, long l, int score) {
        if (total == 0) return "No security findings were produced for this run.";
        String grade = score >= 90 ? "low overall risk" : score >= 70 ? "moderate risk" : score >= 40 ? "elevated risk" : "high risk";
        return "This run produced " + total + " security finding(s) — " + c + " critical, " + h + " high, " + m + " medium, " + l + " low — "
                + "for an overall security score of " + score + "/100 (" + grade + "). "
                + (c > 0 ? "Address critical findings before release; they represent the highest-confidence, highest-impact issues. "
                         : h > 0 ? "No critical findings, but the high-severity items should be resolved before release. "
                         : "No critical or high-severity findings this run.");
    }

    private static String scoreCard(int score) {
        return "<div class='score-card'><div class='l'>Security Score</div><div class='v'>" + score + "/100</div></div>";
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
