package com.vasundhara.atf.smartexec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Server-rendered per-category HTML report for a Smart Execution run. Independent implementation
 * from {@code report.IssueReportService} — same "no issues found" honesty guarantee.
 */
public final class SmartIssueReportBuilder {

    private SmartIssueReportBuilder() {}

    public enum ReportType { FUNCTIONAL, UI, CRASH, ADS }

    public static String generate(SmartSession session, ReportType type) {
        String categoryKey = switch (type) {
            case FUNCTIONAL -> "functional";
            case UI -> "uiux";
            case CRASH -> "crash";
            case ADS -> "ads";
        };
        String title = switch (type) {
            case FUNCTIONAL -> "Functional Issues Report";
            case UI -> "UI Issues Report";
            case CRASH -> "Crash Issues Report";
            case ADS -> "AdMob Report";
        };
        List<SmartFinding> findings = session.getFindings().stream()
                .filter(f -> categoryKey.equals(f.category()))
                .collect(Collectors.toList());

        StringBuilder body = new StringBuilder();
        if (findings.isEmpty()) {
            body.append("<div class='pass-box'><span class='badge'>PASS</span> Bug Report Not Available.</div>");
        } else {
            for (SmartFinding f : findings) {
                body.append("<div class='finding sev-").append(esc(f.severity())).append("'>")
                    .append("<div class='fh'><span class='sev-chip'>").append(esc(f.severity())).append("</span> ")
                    .append(esc(f.title())).append(" <span class='pri'>").append(esc(f.priority())).append("</span></div>")
                    .append("<table>")
                    .append(row("Screen Name", f.screenName()))
                    .append(row("Feature", f.feature()))
                    .append(row("Steps to Reproduce", f.stepsToReproduce() == null ? "" : String.join(" → ", f.stepsToReproduce())))
                    .append(row("Expected Result", f.expectedResult()))
                    .append(row("Actual Result", f.actualResult()))
                    .append(row("Timestamp", java.time.Instant.ofEpochMilli(f.timestamp()).toString()));
                if (f.screenshotPath() != null) body.append(row("Screenshot", "evidence/" + f.screenshotPath()));
                if (f.videoPath() != null) body.append(row("Video Recording", "evidence/" + f.videoPath()));
                if (f.logsExcerpt() != null && !f.logsExcerpt().isBlank())
                    body.append("<tr><td>Logs</td><td><pre>").append(esc(f.logsExcerpt())).append("</pre></td></tr>");
                body.append("</table></div>");
            }
        }

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>" + esc(title) + "</title><style>"
                + "body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f1f5f9;color:#0f172a;padding:24px}"
                + ".finding{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:14px 18px;margin-bottom:14px}"
                + ".fh{font-weight:700;margin-bottom:8px}.sev-chip{font-size:11px;font-weight:800;padding:2px 8px;border-radius:6px;background:#eee}"
                + ".pri{font-size:11px;color:#64748b;margin-left:6px}"
                + "table{width:100%;font-size:13px}td{padding:4px 8px;vertical-align:top}td:first-child{color:#64748b;width:160px}"
                + ".pass-box{background:#ecfdf5;border:1px solid #a7f3d0;color:#065f46;border-radius:10px;padding:14px 18px}"
                + "pre{white-space:pre-wrap;background:#0f172a;color:#e2e8f0;padding:10px;border-radius:6px;font-size:11px}"
                + "</style></head><body><h2>" + esc(title) + "</h2>"
                + "<div class='muted'>" + esc(session.getAppLabel()) + " · " + esc(session.getPackageName()) + "</div>"
                + "<div style='margin-top:16px'>" + body + "</div></body></html>";
    }

    private static String row(String k, String v) {
        return "<tr><td>" + esc(k) + "</td><td>" + esc(v == null || v.isBlank() ? "—" : v) + "</td></tr>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
