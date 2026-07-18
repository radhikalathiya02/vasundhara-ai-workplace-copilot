package com.vasundhara.atf.smartexec.figma;

import com.vasundhara.atf.smartexec.SmartSession;

/**
 * Server-rendered Figma design-comparison report — same visual language as
 * {@code SmartIssueReportBuilder}'s per-category reports, extended with the side-by-side
 * app/Figma screenshots + diff overlay the product spec asks for.
 */
public final class FigmaReportBuilder {
    private FigmaReportBuilder() {}

    public static String generate(SmartSession session) {
        String artifactBase = "/api/smartexec/runs/" + session.getId() + "/artifacts/";
        StringBuilder body = new StringBuilder();

        String status = session.getFigmaStatus();
        if (status == null || session.getFigmaUrl() == null || session.getFigmaUrl().isBlank()) {
            body.append("<div class='pass-box' style='background:#f1f5f9;border-color:#cbd5e1;color:#475569'>"
                    + "No Figma link was provided for this run.</div>");
        } else if ("SKIPPED_NO_TOKEN".equals(status) || "FAILED".equals(status)) {
            body.append("<div class='pass-box' style='background:#fff7ed;border-color:#fed7aa;color:#9a3412'>"
                    + esc(session.getFigmaNote()) + "</div>");
        } else {
            var findings = session.getFigmaFindings();
            if (findings == null || findings.isEmpty()) {
                body.append("<div class='pass-box'><span class='badge'>PASS</span> ")
                        .append(esc(session.getFigmaNote() == null ? "No mismatches found." : session.getFigmaNote()))
                        .append("</div>");
            } else {
                body.append("<div class='muted' style='margin-bottom:14px'>").append(esc(session.getFigmaNote())).append("</div>");
                for (FigmaFinding f : findings) {
                    body.append("<div class='finding sev-").append(esc(f.severity())).append("'>")
                        .append("<div class='fh'><span class='sev-chip'>").append(esc(f.severity())).append("</span> ")
                        .append(esc(f.componentName())).append(" <span class='pri'>").append(esc(FigmaFinding.priorityFor(f.severity()))).append("</span></div>")
                        .append("<table>")
                        .append(row("Screen Name", f.screenName()))
                        .append(row("Component Name", f.componentName()))
                        .append(row("Expected (Figma)", f.expected()))
                        .append(row("Actual (Application)", f.actual()))
                        .append(row("Difference", f.difference()))
                        .append(row("Timestamp", java.time.Instant.ofEpochMilli(f.timestamp()).toString()))
                        .append("</table>");
                    boolean hasImgs = f.appScreenshotPath() != null || f.figmaScreenshotPath() != null || f.diffOverlayPath() != null;
                    if (hasImgs) {
                        body.append("<div class='shot-row'>");
                        if (f.appScreenshotPath() != null) body.append(shotCol("Application", artifactBase + esc(f.appScreenshotPath())));
                        if (f.figmaScreenshotPath() != null) body.append(shotCol("Figma", artifactBase + esc(f.figmaScreenshotPath())));
                        if (f.diffOverlayPath() != null) body.append(shotCol("Diff", artifactBase + esc(f.diffOverlayPath())));
                        body.append("</div>");
                    }
                    body.append("</div>");
                }
            }
        }

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Figma Design Comparison Report</title><style>"
                + "body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f1f5f9;color:#0f172a;padding:24px}"
                + ".finding{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:14px 18px;margin-bottom:14px}"
                + ".fh{font-weight:700;margin-bottom:8px}.sev-chip{font-size:11px;font-weight:800;padding:2px 8px;border-radius:6px;background:#eee}"
                + ".pri{font-size:11px;color:#64748b;margin-left:6px}"
                + "table{width:100%;font-size:13px}td{padding:4px 8px;vertical-align:top}td:first-child{color:#64748b;width:170px}"
                + ".pass-box{background:#ecfdf5;border:1px solid #a7f3d0;color:#065f46;border-radius:10px;padding:14px 18px}"
                + ".muted{color:#64748b}"
                + ".shot-row{display:flex;gap:12px;margin-top:10px;flex-wrap:wrap}"
                + ".shot-col{flex:1;min-width:160px}.shot-col img{width:100%;border-radius:6px;border:1px solid #e2e8f0}"
                + ".shot-col .lbl{font-size:11px;color:#64748b;margin-bottom:4px;font-weight:700;text-transform:uppercase}"
                + "</style></head><body><h2>Figma Design Comparison Report</h2>"
                + "<div class='muted'>" + esc(session.getAppLabel()) + " · " + esc(session.getPackageName()) + "</div>"
                + "<div style='margin-top:16px'>" + body + "</div></body></html>";
    }

    private static String shotCol(String label, String src) {
        return "<div class='shot-col'><div class='lbl'>" + esc(label) + "</div><img src='" + src + "' loading='lazy'/></div>";
    }

    private static String row(String k, String v) {
        return "<tr><td>" + esc(k) + "</td><td>" + esc(v == null || v.isBlank() ? "—" : v) + "</td></tr>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
