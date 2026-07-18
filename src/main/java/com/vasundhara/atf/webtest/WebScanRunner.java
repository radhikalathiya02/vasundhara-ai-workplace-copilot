package com.vasundhara.atf.webtest;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.analyze.FormAnalyzer;
import com.vasundhara.atf.webtest.analyze.HtmlBestPracticesAnalyzer;
import com.vasundhara.atf.webtest.analyze.IssueAggregator;
import com.vasundhara.atf.webtest.analyze.LinkChecker;
import com.vasundhara.atf.webtest.analyze.PageSpeedAnalyzer;
import com.vasundhara.atf.webtest.analyze.SecurityAnalyzer;
import com.vasundhara.atf.webtest.analyze.SeoAnalyzer;
import com.vasundhara.atf.webtest.analyze.WebBrowserService;
import com.vasundhara.atf.webtest.crawl.CrawledPage;
import com.vasundhara.atf.webtest.crawl.WebCrawler;
import com.vasundhara.atf.webtest.model.PageInfo;
import com.vasundhara.atf.webtest.model.ScanSummary;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import com.vasundhara.atf.webtest.model.WebScanSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives one end-to-end website scan asynchronously — the web counterpart of the Android
 * {@code AnalysisRunner}. Pipeline: crawl (jsoup baseline) → per-page static analyzers →
 * site-level checks (broken links, duplicate metadata, sensitive-path probe) → optional browser
 * tier (screenshots, a11y, JS/network errors, responsive) → optional Lighthouse/PageSpeed →
 * aggregate/dedup/score. Every tier degrades gracefully; a failure in one never aborts the scan,
 * and partial results are always preserved. Runs on the existing {@code testRunExecutor} pool
 * and is fully independent of the Android device/execution lock, so it never affects APK runs.
 */
@Component
public class WebScanRunner {

    private static final Logger log = LoggerFactory.getLogger(WebScanRunner.class);

    private final WebScanProperties props;
    private final AtfProperties atf;
    private final SeoAnalyzer seo;
    private final SecurityAnalyzer security;
    private final HtmlBestPracticesAnalyzer htmlBp;
    private final FormAnalyzer forms;
    private final LinkChecker linkChecker;
    private final PageSpeedAnalyzer pageSpeed;
    private final WebBrowserService browser;
    private final IssueAggregator aggregator;

    public WebScanRunner(WebScanProperties props, AtfProperties atf, SeoAnalyzer seo,
                         SecurityAnalyzer security, HtmlBestPracticesAnalyzer htmlBp,
                         FormAnalyzer forms, LinkChecker linkChecker, PageSpeedAnalyzer pageSpeed,
                         WebBrowserService browser, IssueAggregator aggregator) {
        this.props = props;
        this.atf = atf;
        this.seo = seo;
        this.security = security;
        this.htmlBp = htmlBp;
        this.forms = forms;
        this.linkChecker = linkChecker;
        this.pageSpeed = pageSpeed;
        this.browser = browser;
        this.aggregator = aggregator;
    }

    @Async("testRunExecutor")
    public void run(WebScanSession session) {
        List<WebIssue> raw = new ArrayList<>();
        // url -> pages that reference it (broken-link mapping)
        Map<String, Set<String>> linkToReferrers = new LinkedHashMap<>();
        // metadata dup detection
        Map<String, List<String>> titleToPages = new LinkedHashMap<>();
        Map<String, List<String>> descToPages = new LinkedHashMap<>();

        try {
            Path artifactDir = Path.of(atf.getWorkDir(), "web-" + session.getId()).toAbsolutePath();
            Files.createDirectories(artifactDir);

            session.setState(WebScanSession.State.CRAWLING);
            session.setPhase("Crawling & analyzing pages");
            session.log("Scan started for " + session.getUrl());

            WebCrawler crawler = new WebCrawler(props, session.getUrl());
            String origin = crawler.getOrigin();

            crawler.crawl(session.getUrl(), page -> {
                if (session.isStopRequested()) return;
                session.setCurrentPage(page.getFinalUrl());
                analyzePage(page, raw, linkToReferrers, titleToPages, descToPages, session);
                session.setPagesAnalyzed(session.getPagesAnalyzed() + 1);
                int discovered = Math.max(session.getPagesDiscovered(), session.getPagesAnalyzed());
                session.setPercent(discovered == 0 ? 5
                        : 5 + (int) (45.0 * session.getPagesAnalyzed() / Math.max(discovered, props.getMaxPages())));
            }, discovered -> session.setPagesDiscovered(discovered), session::isStopRequested);

            session.log("Crawl complete: " + session.getPagesAnalyzed() + " page(s) analyzed.");

            // ---- Site-level checks ----
            session.setState(WebScanSession.State.ANALYZING);
            session.setPhase("Validating links & site-level checks");
            session.setPercent(55);

            addDuplicateMetadataIssues(titleToPages, "title", raw);
            addDuplicateMetadataIssues(descToPages, "meta description", raw);

            if (!session.isStopRequested()) {
                session.log("Validating " + linkToReferrers.size() + " discovered link(s)…");
                raw.addAll(linkChecker.check(linkToReferrers, session::isStopRequested));
            }
            if (!session.isStopRequested() && props.isSecurityProbeSensitivePaths()) {
                raw.addAll(security.probeSensitivePaths(origin, props.getRequestTimeoutMs()));
            }
            session.setPercent(65);

            // ---- Browser tier (optional, graceful) ----
            if (props.isBrowserEnabled() && !session.isStopRequested()) {
                session.setPhase("Deep browser analysis (accessibility, JS, responsive)");
                List<String> sample = samplePages(session, props.getBrowserSamplePages());
                session.log("Browser tier: analyzing " + sample.size() + " sampled page(s)…");
                WebBrowserService.BrowserResult br = browser.analyze(
                        sample, artifactDir, "artifacts",
                        msg -> { session.setCurrentPage(msg); session.log(msg); },
                        // Live screenshot push — surface each page the instant it's captured.
                        (url, rel) -> { session.setScreenshot(rel); setPageShotLive(session, url, rel); },
                        session::isStopRequested,
                        // Element-anchored static findings so they get real annotated screenshots too.
                        raw);
                raw.addAll(br.issues);
                session.setBrowserUsed(browser.wasAvailable());
                if (browser.wasAvailable()) {
                    attachScreenshots(session, br.screenshots);
                    session.log("Browser tier complete (" + br.issues.size() + " finding(s)).");
                } else {
                    session.log("Browser tier unavailable — Chromium not installed; used the HTTP baseline instead.");
                }
            }
            session.setPercent(80);

            // ---- PageSpeed / Lighthouse tier (optional, graceful) ----
            ScanSummary summary = new ScanSummary();
            if (props.isPageSpeedEnabled() && !session.isStopRequested()) {
                session.setPhase("Running Lighthouse performance audit");
                List<String> psSample = samplePages(session, props.getPageSpeedSamplePages());
                boolean first = true;
                for (String url : psSample) {
                    if (session.isStopRequested()) break;
                    session.log("Lighthouse audit: " + url);
                    PageSpeedAnalyzer.Result r = pageSpeed.audit(url);
                    raw.addAll(r.issues());
                    if (first && r.performanceScore() >= 0) {
                        summary.setPerformanceScore(r.performanceScore());
                        summary.getCoreWebVitals().putAll(r.coreWebVitals());
                        session.setPageSpeedUsed(true);
                        first = false;
                    }
                }
            }
            session.setPercent(92);

            // ---- Aggregate, score, finalize ----
            session.setState(WebScanSession.State.ENRICHING);
            session.setPhase("Aggregating & scoring findings");
            List<WebIssue> finalIssues = aggregator.aggregate(raw);
            summary.compute(finalIssues, session.getPagesAnalyzed());
            summary.setTechProfile(detectTech(session));
            countPerPageIssues(session, finalIssues);

            synchronized (session.getIssues()) {
                session.getIssues().clear();
                session.getIssues().addAll(finalIssues);
            }
            session.setSummary(summary);
            session.setPercent(100);
            session.setFinishedAtMillis(System.currentTimeMillis());
            session.setState(session.isStopRequested()
                    ? WebScanSession.State.STOPPED : WebScanSession.State.COMPLETED);
            session.setPhase(session.isStopRequested() ? "Stopped" : "Completed");
            session.log("Scan finished: " + finalIssues.size() + " issue(s), score "
                    + summary.getOverallScore() + "/100.");
        } catch (Exception e) {
            log.warn("Website scan failed for session {}: {}", session.getId(), e.toString(), e);
            session.setError("Scan failed: " + e.getMessage());
            session.setState(WebScanSession.State.FAILED);
            session.setPhase("Failed");
            session.setFinishedAtMillis(System.currentTimeMillis());
        }
    }

    private void analyzePage(CrawledPage page, List<WebIssue> raw,
                             Map<String, Set<String>> linkToReferrers,
                             Map<String, List<String>> titleToPages,
                             Map<String, List<String>> descToPages,
                             WebScanSession session) {
        String url = page.getFinalUrl();

        PageInfo info = new PageInfo(url);
        info.setStatus(page.getStatusCode());
        info.setDepth(page.getDepth());
        info.setLoadTimeMs(page.getLoadTimeMs());

        // The page itself failed to load.
        if (page.getError() != null) {
            raw.add(WebIssue.of(WebIssueCategory.NETWORK, Severity.HIGH, "Page failed to load",
                    "Requesting " + url + " failed: " + page.getError(),
                    "Ensure the page is reachable and returns valid HTML.", url, "CRAWL")
                    .impact("The page is completely unavailable to users and search engines — a total loss of the content and any conversions it drives.")
                    .rootCause("The HTTP request did not complete: " + page.getError())
                    .standard("HTTP semantics (RFC 9110)")
                    .repro("Request " + url + " (browser or curl -i " + url + ").",
                            "Observe the failure: " + page.getError()));
        } else if (page.getStatusCode() >= 400) {
            Severity sev = page.getStatusCode() >= 500 ? Severity.HIGH : Severity.MEDIUM;
            raw.add(WebIssue.of(WebIssueCategory.NETWORK, sev,
                    "HTTP " + page.getStatusCode() + " error page",
                    "The page " + url + " returned HTTP " + page.getStatusCode() + ".",
                    "Fix the server error or the route that produced it.", url, "CRAWL")
                    .impact(page.getStatusCode() >= 500
                            ? "A server error means the page is broken for all users; repeated 5xx responses also damage crawlability and rankings."
                            : "Users reaching this URL get an error instead of content, dead-ending their journey and wasting crawl budget.")
                    .rootCause("The server responded with HTTP " + page.getStatusCode()
                            + (page.getStatusCode() >= 500 ? " — an application/server-side failure." : " — the resource is missing or access is denied."))
                    .standard("HTTP semantics (RFC 9110)")
                    .repro("Open " + url + ".", "Observe the HTTP " + page.getStatusCode() + " response."));
        }

        if (page.isHtml()) {
            info.setTitle(page.getDoc().title());
            raw.addAll(seo.analyze(page));
            raw.addAll(security.analyze(page));
            raw.addAll(htmlBp.analyze(page));
            if (props.isFormValidationEnabled()) raw.addAll(forms.analyze(page));

            String title = page.getDoc().title();
            if (title != null && !title.isBlank()) {
                titleToPages.computeIfAbsent(title.trim(), k -> new ArrayList<>()).add(url);
            }
            var desc = page.getDoc().selectFirst("meta[name=description]");
            if (desc != null && !desc.attr("content").isBlank()) {
                descToPages.computeIfAbsent(desc.attr("content").trim(), k -> new ArrayList<>()).add(url);
            }
        }

        // Accumulate link → referrers for the broken-link checker (bounded).
        if (linkToReferrers.size() < props.getMaxPages() * 60) {
            for (String link : page.getAllLinks()) {
                linkToReferrers.computeIfAbsent(link, k -> new LinkedHashSet<>()).add(url);
            }
        }

        session.getPages().add(info);
    }

    private void addDuplicateMetadataIssues(Map<String, List<String>> map, String what, List<WebIssue> raw) {
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            if (e.getValue().size() > 1) {
                WebIssue issue = WebIssue.of(WebIssueCategory.SEO, Severity.LOW,
                        "Duplicate " + what + " across pages",
                        e.getValue().size() + " pages share the same " + what + ": \""
                                + truncate(e.getKey(), 80) + "\".",
                        "Give each page a unique " + what + ".",
                        e.getValue().get(0), "SEO")
                        .impact("Search engines can't distinguish these pages, so they compete for the same queries and may be treated as near-duplicates — diluting rankings and cannibalising traffic.")
                        .rootCause(e.getValue().size() + " pages output an identical " + what
                                + ", usually from a shared template that doesn't set a per-page value.")
                        .standard("Google Search Essentials — unique titles/descriptions")
                        .codeFix("Render a unique, page-specific " + what + " from each page's content/metadata.");
                issue.setElement("dup-" + what + "-" + Integer.toHexString(e.getKey().hashCode()));
                e.getValue().forEach(issue::setPage);
                raw.add(issue);
            }
        }
    }

    /** Homepage first, then the earliest-crawled distinct pages, up to {@code n}. */
    private List<String> samplePages(WebScanSession session, int n) {
        List<String> urls = new ArrayList<>();
        synchronized (session.getPages()) {
            for (PageInfo p : session.getPages()) {
                if (p.getStatus() > 0 && p.getStatus() < 400 && p.getTitle() != null) {
                    if (!urls.contains(p.getUrl())) urls.add(p.getUrl());
                }
                if (urls.size() >= Math.max(1, n)) break;
            }
        }
        if (urls.isEmpty()) urls.add(session.getUrl());
        return urls;
    }

    /** Immediately record a freshly-captured screenshot on its page so the live view updates. */
    private void setPageShotLive(WebScanSession session, String url, String rel) {
        synchronized (session.getPages()) {
            for (PageInfo p : session.getPages()) {
                if (p.getUrl().equals(url)) { p.setScreenshot(rel); return; }
            }
        }
    }

    private void attachScreenshots(WebScanSession session, Map<String, String> screenshots) {
        boolean firstSet = false;
        synchronized (session.getPages()) {
            for (PageInfo p : session.getPages()) {
                String shot = screenshots.get(p.getUrl());
                if (shot != null) {
                    p.setScreenshot(shot);
                    if (!firstSet) { session.setScreenshot(shot); firstSet = true; }
                }
            }
        }
    }

    private void countPerPageIssues(WebScanSession session, List<WebIssue> issues) {
        synchronized (session.getPages()) {
            for (PageInfo p : session.getPages()) {
                int c = 0;
                for (WebIssue i : issues) if (i.getAffectedPages().contains(p.getUrl())) c++;
                p.setIssueCount(c);
            }
        }
    }

    private String detectTech(WebScanSession session) {
        // Cheap fingerprint from the homepage response server header, when available.
        return null; // reserved; kept null to avoid over-claiming. Server header shown per-issue instead.
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
