package com.vasundhara.atf.webtest.report;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.model.PageInfo;
import com.vasundhara.atf.webtest.model.ScanSummary;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import com.vasundhara.atf.webtest.model.WebScanSession;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Renders a completed website scan into a self-contained, downloadable, enterprise-grade QA
 * report — executive summary, quality scorecards, severity breakdown, Core Web Vitals, and a
 * per-category set of richly-detailed issue cards (impact, root cause, violated standard, code
 * fix, reproduction steps, the exact element, the affected page URLs, detection time, and an
 * <b>embedded real screenshot</b> of the affected page with the offending element highlighted).
 * All images are inlined as base64 data URIs, so the report is fully portable offline.
 */
@Component
public class WebReportService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AtfProperties atf;

    public WebReportService(AtfProperties atf) {
        this.atf = atf;
    }

    public String render(WebScanSession s) {
        ScanSummary sum = s.getSummary();
        List<WebIssue> issues = s.getIssues();
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>Website QA Report — ").append(esc(s.getUrl())).append("</title>")
         .append(css())
         .append("</head><body><div class=\"wrap\">");

        // Header
        h.append("<header><div class=\"muted\">Website QA Report</div>")
         .append("<h1>").append(esc(s.getUrl())).append("</h1>")
         .append("<div class=\"meta\">")
         .append("Scanned ").append(FMT.format(Instant.ofEpochMilli(s.getCreatedAtMillis())))
         .append(" · ").append(sum.getPagesAnalyzed()).append(" pages analyzed")
         .append(" · ").append(sum.getTotalIssues()).append(" issues")
         .append(" · Browser tier: ").append(s.isBrowserUsed() ? "on" : "baseline only")
         .append(" · Lighthouse: ").append(s.isPageSpeedUsed() ? "on" : "n/a")
         .append("</div></header>");

        // Executive summary
        h.append(executiveSummary(s, sum, issues));

        // Scorecard
        h.append("<h2>Quality scores</h2><section class=\"cards\">");
        h.append(scoreCard("Overall", sum.getOverallScore()));
        if (sum.getPerformanceScore() >= 0) h.append(scoreCard("Performance (Lighthouse)", sum.getPerformanceScore()));
        for (Map.Entry<String, Integer> e : sum.getCategoryScores().entrySet()) {
            int count = sum.getCountsByCategory().getOrDefault(e.getKey(), 0);
            if (count == 0) continue;
            h.append(scoreCard(label(e.getKey()), e.getValue()));
        }
        h.append("</section>");

        // Severity distribution
        h.append("<section class=\"sev\">");
        for (Severity sev : Severity.values()) {
            int c = sum.getCountsBySeverity().getOrDefault(sev.name(), 0);
            h.append("<span class=\"chip ").append(sev.name().toLowerCase()).append("\">")
             .append(cap(sev.name())).append(": <b>").append(c).append("</b></span>");
        }
        h.append("</section>");

        // Core Web Vitals
        if (!sum.getCoreWebVitals().isEmpty()) {
            h.append("<section class=\"card\"><h2>Core Web Vitals</h2><div class=\"cwv\">");
            for (Map.Entry<String, String> e : sum.getCoreWebVitals().entrySet()) {
                h.append("<div><span class=\"muted\">").append(esc(e.getKey())).append("</span><b>")
                 .append(esc(e.getValue())).append("</b></div>");
            }
            h.append("</div></section>");
        }

        // Issues grouped by category
        h.append("<h2>Detailed findings (").append(issues.size()).append(")</h2>");
        for (WebIssueCategory cat : WebIssueCategory.values()) {
            List<WebIssue> group = issues.stream().filter(i -> i.getCategory() == cat).toList();
            if (group.isEmpty()) continue;
            h.append("<h3 class=\"cat\">").append(cat.getLabel()).append(" <span class=\"muted\">(")
             .append(group.size()).append(")</span></h3>");
            for (WebIssue i : group) h.append(issueRow(s, i));
        }
        if (issues.isEmpty()) h.append("<p class=\"muted\">No issues detected. 🎉</p>");

        // Full-page previews — the whole rendered page (header → footer), not just the viewport.
        List<PageInfo> shots = s.getPages().stream().filter(p -> p.getScreenshot() != null).toList();
        if (!shots.isEmpty()) {
            h.append("<h2>Page previews (").append(shots.size()).append(")</h2>")
             .append("<section class=\"previews\">");
            for (PageInfo p : shots) {
                String full = thumb(s, p.getScreenshot(), 860);
                if (full == null) continue;
                h.append("<figure class=\"preview\"><div class=\"shotbox\"><img src=\"").append(full).append("\" alt=\"\"></div>")
                 .append("<figcaption class=\"muted small\">").append(esc(p.getUrl())).append("</figcaption></figure>");
            }
            h.append("</section>");
        }

        // Pages table (with thumbnails)
        h.append("<h2>Pages analyzed (").append(s.getPages().size()).append(")</h2>")
         .append("<section class=\"card\"><table><thead><tr><th></th><th>URL</th><th>Status</th><th>Issues</th><th>Load</th></tr></thead><tbody>");
        for (PageInfo p : s.getPages()) {
            String thumb = thumb(s, p.getScreenshot(), 300);
            h.append("<tr><td class=\"thumbcell\">")
             .append(thumb != null ? "<img class=\"thumb\" src=\"" + thumb + "\" alt=\"\">" : "")
             .append("</td>")
             .append("<td class=\"url\">").append(esc(p.getUrl())).append("</td>")
             .append("<td>").append(p.getStatus()).append("</td>")
             .append("<td>").append(p.getIssueCount()).append("</td>")
             .append("<td>").append(p.getLoadTimeMs()).append(" ms</td></tr>");
        }
        h.append("</tbody></table></section>");

        h.append("<footer class=\"muted\">Generated by Vasundhara Website Testing · ")
         .append(FMT.format(Instant.now())).append("</footer>");
        h.append("</div></body></html>");
        return h.toString();
    }

    private String executiveSummary(WebScanSession s, ScanSummary sum, List<WebIssue> issues) {
        int critical = sum.getCountsBySeverity().getOrDefault("CRITICAL", 0);
        int high = sum.getCountsBySeverity().getOrDefault("HIGH", 0);
        int score = sum.getOverallScore();
        String verdict = score >= 90 ? "in good health"
                : score >= 70 ? "in fair health with clear areas to improve"
                : score >= 50 ? "showing significant quality gaps"
                : "at high risk with serious quality and reliability problems";
        StringBuilder b = new StringBuilder("<section class=\"card exec\"><h2>Executive summary</h2>");
        b.append("<p>This automated QA scan crawled <b>").append(sum.getPagesAnalyzed())
         .append("</b> page(s) of <b>").append(esc(s.getUrl())).append("</b> and surfaced <b>")
         .append(sum.getTotalIssues()).append("</b> distinct issue(s). The site scores <b>")
         .append(score).append("/100</b> overall — ").append(verdict).append(".</p>");
        if (critical + high > 0) {
            b.append("<p><b>").append(critical).append("</b> critical and <b>").append(high)
             .append("</b> high-severity issue(s) need prompt attention.</p>");
        } else {
            b.append("<p>No critical or high-severity issues were detected — remaining items are quality and best-practice improvements.</p>");
        }
        // Top 5 most severe as a priority list.
        List<WebIssue> top = issues.stream().limit(5).toList();
        if (!top.isEmpty()) {
            b.append("<div class=\"muted\" style=\"margin:8px 0 4px\">Top priorities</div><ol class=\"toplist\">");
            for (WebIssue i : top) {
                b.append("<li><span class=\"chip ").append(sevClass(i)).append("\">").append(cap(sevClass(i)))
                 .append("</span> ").append(esc(i.getTitle()))
                 .append(" <span class=\"muted\">(").append(i.getCategory().getLabel()).append(")</span></li>");
            }
            b.append("</ol>");
        }
        b.append("</section>");
        return b.toString();
    }

    private String issueRow(WebScanSession s, WebIssue i) {
        String sev = sevClass(i);
        StringBuilder b = new StringBuilder("<div class=\"issue\">");
        b.append("<div class=\"issue-h\"><span class=\"chip ").append(sev).append("\">")
         .append(cap(sev)).append("</span><b>").append(esc(i.getTitle())).append("</b>");
        if (i.getAffectedPageCount() > 1) {
            b.append("<span class=\"badge\">").append(i.getAffectedPageCount()).append(" pages</span>");
        }
        if (i.getWcag() != null) b.append("<span class=\"badge\">").append(esc(i.getWcag())).append("</span>");
        if (!i.getSources().isEmpty())
            b.append("<span class=\"badge src\">").append(esc(String.join("+", i.getSources()))).append("</span>");
        b.append("</div>");

        if (i.getDetail() != null) b.append("<div class=\"issue-d\">").append(esc(i.getDetail())).append("</div>");

        // Evidence screenshot (real page, highlighted element)
        String evi = dataUri(s, i.getEvidence());
        if (evi != null) {
            b.append("<figure class=\"evi\"><img src=\"").append(evi).append("\" alt=\"Evidence screenshot\">")
             .append("<figcaption class=\"muted small\">")
             .append(i.isEvidenceAnnotated() ? "Screenshot of the affected page — the problem element is outlined in red."
                     : "Screenshot of the affected page.")
             .append("</figcaption></figure>");
        }

        // Structured detail grid
        b.append("<div class=\"kv\">");
        kv(b, "Impact", i.getImpact());
        kv(b, "Root cause", i.getRootCause());
        kv(b, "Standard / guideline", i.getStandard());
        b.append("</div>");

        if (i.getElement() != null && !i.getElement().startsWith("dup-"))
            b.append("<div class=\"lbl\">Element / locator</div><code>").append(esc(trunc(i.getElement(), 220))).append("</code>");

        if (!i.getReproSteps().isEmpty()) {
            b.append("<div class=\"lbl\">Steps to reproduce</div><ol class=\"repro\">");
            for (String step : i.getReproSteps()) b.append("<li>").append(esc(step)).append("</li>");
            b.append("</ol>");
        }

        if (i.getCodeFix() != null)
            b.append("<div class=\"lbl\">Suggested fix</div><pre class=\"fix\">").append(esc(i.getCodeFix())).append("</pre>");

        if (i.getRecommendation() != null)
            b.append("<div class=\"rec\">▸ ").append(esc(i.getRecommendation())).append("</div>");

        // Affected pages + detection time
        b.append("<div class=\"foot muted small\">");
        List<String> pages = i.getAffectedPages();
        if (pages.size() == 1) {
            b.append("Page: ").append(esc(pages.get(0)));
        } else if (pages.size() > 1) {
            b.append("Affected pages: ").append(esc(pages.get(0)));
            if (pages.size() > 1) b.append(" +").append(pages.size() - 1).append(" more");
        }
        b.append(" · Detected ").append(TIME.format(Instant.ofEpochMilli(i.getDetectedAtMillis())));
        if (i.getConfidence() < 1.0) b.append(" · Confidence ").append(Math.round(i.getConfidence() * 100)).append("%");
        b.append("</div>");

        b.append("</div>");
        return b.toString();
    }

    private static void kv(StringBuilder b, String k, String v) {
        if (v == null || v.isBlank()) return;
        b.append("<div class=\"kvrow\"><span class=\"k\">").append(k).append("</span><span class=\"v\">")
         .append(esc(v)).append("</span></div>");
    }

    /**
     * Read a (potentially large full-page) screenshot, downscale it to {@code maxWidth} and embed
     * it as a base64 JPEG. Full-page captures can be several MB each; embedding them verbatim
     * would make the self-contained report/PDF enormous. Downscaling keeps the whole page visible
     * at a readable size while keeping the document small. Falls back to the raw PNG data URI if
     * the image is already narrow or can't be decoded.
     */
    private String thumb(WebScanSession s, String rel, int maxWidth) {
        if (rel == null || rel.isBlank()) return null;
        try {
            Path target = resolveArtifact(s, rel);
            if (target == null) return null;
            BufferedImage src = ImageIO.read(target.toFile());
            if (src == null) return dataUri(s, rel);
            int w = src.getWidth(), h = src.getHeight();
            if (w <= maxWidth) return dataUri(s, rel);
            int nw = maxWidth;
            int nh = (int) Math.max(1, Math.round(h * (maxWidth / (double) w)));
            BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(dst, "jpg", bos);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return dataUri(s, rel);
        }
    }

    /** Resolve a relative artifact path (e.g. "artifacts/shot-1.png") to a validated file. */
    private Path resolveArtifact(WebScanSession s, String rel) {
        if (rel == null || rel.isBlank()) return null;
        try {
            Path base = Path.of(atf.getWorkDir(), "web-" + s.getId()).toAbsolutePath().normalize();
            String fileName = rel.substring(rel.lastIndexOf('/') + 1);
            Path target = base.resolve(fileName).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target)) return null;
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read an artifact file (relative path like "artifacts/shot-1.png") into a base64 data URI. */
    private String dataUri(WebScanSession s, String rel) {
        Path target = resolveArtifact(s, rel);
        if (target == null) return null;
        try {
            byte[] bytes = Files.readAllBytes(target);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private String scoreCard(String label, int score) {
        String cls = score >= 90 ? "good" : score >= 50 ? "warn" : "bad";
        return "<div class=\"scard " + cls + "\"><div class=\"score\">" + score + "</div>"
                + "<div class=\"slabel\">" + esc(label) + "</div></div>";
    }

    private static String sevClass(WebIssue i) {
        return i.getSeverity() == null ? "info" : i.getSeverity().name().toLowerCase();
    }

    private static String label(String enumName) {
        try { return WebIssueCategory.valueOf(enumName).getLabel(); }
        catch (Exception e) { return enumName; }
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String css() {
        return "<style>"
            + ":root{--bg:#f8fafc;--surface:#fff;--line:#e4e4e7;--txt:#18181b;--mut:#71717a;--brand:#4f46e5;}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--txt);"
            + "font-family:Inter,-apple-system,Segoe UI,Roboto,sans-serif;font-size:14px;line-height:1.55}"
            + ".wrap{max-width:980px;margin:0 auto;padding:32px 20px}"
            + "header{border-bottom:1px solid var(--line);padding-bottom:16px;margin-bottom:20px}"
            + "h1{font-size:22px;margin:4px 0;word-break:break-all}h2{font-size:18px;margin:26px 0 10px}"
            + "h3.cat{font-size:14px;margin:22px 0 8px;color:var(--brand);border-top:1px solid var(--line);padding-top:16px}"
            + ".muted{color:var(--mut)}.small{font-size:12px}"
            + ".meta{color:var(--mut);font-size:12.5px;margin-top:6px}"
            + ".exec p{margin:6px 0}.exec .toplist{margin:6px 0 0;padding-left:18px}.exec .toplist li{margin:4px 0}"
            + ".cards{display:flex;flex-wrap:wrap;gap:10px;margin:8px 0}"
            + ".scard{flex:1;min-width:120px;background:var(--surface);border:1px solid var(--line);"
            + "border-radius:12px;padding:14px;text-align:center}"
            + ".scard .score{font-size:28px;font-weight:800}.scard .slabel{font-size:12px;color:var(--mut);margin-top:4px}"
            + ".scard.good .score{color:#16a34a}.scard.warn .score{color:#d97706}.scard.bad .score{color:#dc2626}"
            + ".sev{display:flex;gap:8px;flex-wrap:wrap;margin:8px 0 4px}"
            + ".chip{font-size:12px;padding:3px 10px;border-radius:20px;border:1px solid var(--line);background:var(--surface)}"
            + ".chip.critical{background:#fee2e2;border-color:#fecaca;color:#991b1b}"
            + ".chip.high{background:#ffedd5;border-color:#fed7aa;color:#9a3412}"
            + ".chip.medium{background:#fef9c3;border-color:#fde68a;color:#854d0e}"
            + ".chip.low{background:#e0e7ff;border-color:#c7d2fe;color:#3730a3}"
            + ".chip.info{background:#f4f4f5;color:#52525b}"
            + ".card{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:16px;margin:14px 0}"
            + ".cwv{display:flex;gap:22px;flex-wrap:wrap}.cwv div{display:flex;flex-direction:column}.cwv b{font-size:16px}"
            + ".issue{background:var(--surface);border:1px solid var(--line);border-left:3px solid var(--brand);"
            + "border-radius:8px;padding:14px 16px;margin:10px 0}"
            + ".issue-h{display:flex;align-items:center;gap:8px;flex-wrap:wrap}"
            + ".issue-d{margin:8px 0;color:#3f3f46}"
            + ".evi{margin:10px 0;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:#0b0b0f}"
            + ".evi img{display:block;max-width:100%;height:auto}"
            + ".evi figcaption{padding:6px 10px;background:var(--surface)}"
            + ".kv{margin:8px 0}.kvrow{display:flex;gap:10px;margin:4px 0}"
            + ".kvrow .k{flex:0 0 150px;color:var(--mut);font-size:12.5px}.kvrow .v{flex:1;font-size:13px}"
            + ".lbl{font-size:11.5px;text-transform:uppercase;letter-spacing:.04em;color:var(--mut);margin:10px 0 4px}"
            + ".rec{margin-top:8px;color:#3730a3;font-size:13px}"
            + ".repro{margin:0;padding-left:20px;font-size:13px}.repro li{margin:2px 0}"
            + ".badge{font-size:11px;padding:2px 8px;border-radius:12px;background:#eef2ff;color:#3730a3}"
            + ".badge.src{background:#f4f4f5;color:#52525b}"
            + "code{display:inline-block;background:#f4f4f5;padding:3px 7px;border-radius:5px;"
            + "font-size:12px;word-break:break-all;font-family:'JetBrains Mono',ui-monospace,monospace}"
            + "pre.fix{background:#0b1020;color:#e5e7eb;padding:12px 14px;border-radius:8px;overflow-x:auto;"
            + "font-size:12.5px;font-family:'JetBrains Mono',ui-monospace,monospace;white-space:pre-wrap;word-break:break-word}"
            + ".foot{margin-top:10px;border-top:1px dashed var(--line);padding-top:8px;word-break:break-all}"
            + "table{width:100%;border-collapse:collapse;font-size:13px}"
            + "th,td{text-align:left;padding:7px 8px;border-bottom:1px solid var(--line);vertical-align:middle}"
            + "th{color:var(--mut);font-weight:600}td.url{word-break:break-all;max-width:480px}"
            + ".thumbcell{width:96px}.thumb{width:84px;height:52px;object-fit:cover;object-position:top center;border-radius:6px;border:1px solid var(--line)}"
            + ".previews{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:16px;margin:6px 0}"
            + ".preview{margin:0}.preview .shotbox{border:1px solid var(--line);border-radius:10px;overflow:auto;max-height:520px;background:#0b0b0f}"
            + ".preview .shotbox img{display:block;width:100%;height:auto}.preview figcaption{margin-top:6px;word-break:break-all}"
            + "footer{margin-top:28px;padding-top:14px;border-top:1px solid var(--line);font-size:12px}"
            // Print / PDF pagination — keep cards and evidence from splitting across pages.
            + "@page{size:A4;margin:14mm}"
            + "@media print{.wrap{max-width:none;padding:0}"
            + ".issue,.scard,.card,.evi,figure,pre.fix,.kvrow{break-inside:avoid;page-break-inside:avoid}"
            + "h1,h2,h3{break-after:avoid;page-break-after:avoid}"
            + ".preview .shotbox{max-height:none;overflow:visible}"
            + "a[href]{color:inherit;text-decoration:none}}"
            + "</style>";
    }
}
