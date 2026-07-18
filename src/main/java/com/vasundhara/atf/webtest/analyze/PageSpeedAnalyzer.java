package com.vasundhara.atf.webtest.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.WebScanProperties;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performance + best-practice auditing via Google's official PageSpeed Insights REST API, which
 * runs Lighthouse server-side and returns lab metrics, Core Web Vitals and category scores as
 * JSON — reachable from pure Java over HTTP (no Node.js required). This is the recommended
 * Lighthouse integration for public URLs; it degrades gracefully (returns nothing) when the
 * target isn't publicly reachable, the network is unavailable, or the quota is exceeded.
 */
@Component
public class PageSpeedAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PageSpeedAnalyzer.class);
    private static final String ENDPOINT = "https://www.googleapis.com/pagespeedonline/v5/runPagespeed";

    private final WebScanProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    public PageSpeedAnalyzer(WebScanProperties props) {
        this.props = props;
    }

    /** Result of one PSI audit: category scores + Core Web Vitals + derived issues. */
    public record Result(int performanceScore, Map<String, String> coreWebVitals, List<WebIssue> issues) { }

    public Result audit(String pageUrl) {
        List<WebIssue> issues = new ArrayList<>();
        Map<String, String> cwv = new LinkedHashMap<>();
        int perfScore = -1;
        try {
            StringBuilder q = new StringBuilder(ENDPOINT)
                    .append("?url=").append(URLEncoder.encode(pageUrl, StandardCharsets.UTF_8))
                    .append("&strategy=").append("desktop".equalsIgnoreCase(props.getPageSpeedStrategy()) ? "desktop" : "mobile")
                    .append("&category=PERFORMANCE&category=ACCESSIBILITY&category=BEST_PRACTICES&category=SEO");
            if (props.getPageSpeedApiKey() != null && !props.getPageSpeedApiKey().isBlank()) {
                q.append("&key=").append(URLEncoder.encode(props.getPageSpeedApiKey(), StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(q.toString()))
                    .timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("PageSpeed Insights returned HTTP {} for {}", resp.statusCode(), pageUrl);
                return new Result(-1, cwv, issues);
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode lh = root.path("lighthouseResult");
            JsonNode cats = lh.path("categories");

            perfScore = scorePct(cats.path("performance"));
            emitCategory(issues, WebIssueCategory.PERFORMANCE, "Performance", perfScore, pageUrl);
            emitCategory(issues, WebIssueCategory.ACCESSIBILITY, "Accessibility",
                    scorePct(cats.path("accessibility")), pageUrl);
            emitCategory(issues, WebIssueCategory.BEST_PRACTICE, "Best-Practices",
                    scorePct(cats.path("best-practices")), pageUrl);
            emitCategory(issues, WebIssueCategory.SEO, "SEO", scorePct(cats.path("seo")), pageUrl);

            // Core Web Vitals (lab).
            JsonNode audits = lh.path("audits");
            putMetric(cwv, audits, "largest-contentful-paint", "LCP");
            putMetric(cwv, audits, "cumulative-layout-shift", "CLS");
            putMetric(cwv, audits, "total-blocking-time", "TBT");
            putMetric(cwv, audits, "first-contentful-paint", "FCP");
            putMetric(cwv, audits, "speed-index", "Speed Index");

            // Top performance opportunities (things with measurable savings).
            int opp = 0;
            var it = audits.fields();
            while (it.hasNext() && opp < 6) {
                Map.Entry<String, JsonNode> a = it.next();
                JsonNode node = a.getValue();
                double score = node.path("score").asDouble(1.0);
                boolean isOpportunity = "opportunity".equals(node.path("details").path("type").asText(""));
                if (isOpportunity && score < 0.9 && !node.path("score").isNull()) {
                    opp++;
                    String title = node.path("title").asText(a.getKey());
                    String disp = node.path("displayValue").asText("");
                    issues.add(WebIssue.of(WebIssueCategory.PERFORMANCE,
                            score < 0.5 ? Severity.MEDIUM : Severity.LOW,
                            "Performance: " + title,
                            (disp.isBlank() ? "" : disp + " — ") + node.path("description").asText(""),
                            "Address the Lighthouse opportunity: " + title + ".",
                            pageUrl, "LIGHTHOUSE")
                            .impact("Lighthouse estimates measurable savings here"
                                    + (disp.isBlank() ? "" : " (" + disp + ")")
                                    + ". Slower loads increase bounce rate and hurt Core Web Vitals / search ranking.")
                            .rootCause("Google Lighthouse flagged this as a performance opportunity with a score of "
                                    + Math.round(score * 100) + "/100 for this page.")
                            .standard("Google Lighthouse — Performance · Core Web Vitals")
                            .confidence(0.95));
                }
            }
        } catch (Exception e) {
            log.debug("PageSpeed Insights audit failed for {}: {}", pageUrl, e.toString());
            return new Result(-1, cwv, issues);
        }
        return new Result(perfScore, cwv, issues);
    }

    private void emitCategory(List<WebIssue> issues, WebIssueCategory cat, String label, int score, String url) {
        if (score < 0) return;
        if (score >= 90) return;
        Severity sev = score < 50 ? Severity.HIGH : Severity.MEDIUM;
        issues.add(WebIssue.of(cat, sev, "Low Lighthouse " + label + " score (" + score + "/100)",
                "Google Lighthouse (via PageSpeed Insights) scored " + label + " at " + score + "/100 for this page.",
                "Review the Lighthouse " + label + " audits and address the highest-impact items.",
                url, "LIGHTHOUSE")
                .impact("A " + label + " score of " + score + "/100 is below Google's \"good\" threshold (90). "
                        + "This category directly affects " + categoryImpact(cat) + ".")
                .rootCause("Aggregate of the individual Lighthouse " + label + " audits for this URL, run server-side by Google.")
                .standard("Google Lighthouse — " + label)
                .confidence(0.95));
    }

    private static String categoryImpact(WebIssueCategory cat) {
        return switch (cat) {
            case PERFORMANCE -> "load speed, bounce rate and Core Web Vitals ranking";
            case ACCESSIBILITY -> "usability for people with disabilities and legal compliance";
            case SEO -> "how well search engines can crawl, index and rank the page";
            default -> "code quality, security and modern-web conformance";
        };
    }

    private static int scorePct(JsonNode category) {
        JsonNode s = category.path("score");
        if (s.isMissingNode() || s.isNull()) return -1;
        return (int) Math.round(s.asDouble() * 100);
    }

    private static void putMetric(Map<String, String> cwv, JsonNode audits, String id, String label) {
        String v = audits.path(id).path("displayValue").asText("");
        if (!v.isBlank()) cwv.put(label, v);
    }
}
