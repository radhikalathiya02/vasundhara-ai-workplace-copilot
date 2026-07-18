package com.vasundhara.atf.webtest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the Website Testing module (prefix {@code atf.web.*}).
 * Kept separate from {@code AtfProperties} so the large, widely-referenced Android config
 * class is untouched — reducing regression risk — while still overridable via
 * {@code application.yml} or {@code -Datf.web.*} system properties.
 */
@Component
@ConfigurationProperties(prefix = "atf.web")
public class WebScanProperties {

    /** Hard ceiling on how many pages a single scan will crawl+analyze. */
    private int maxPages = 40;

    /** Maximum crawl depth (link hops) from the seed URL. */
    private int maxDepth = 3;

    /** Wall-clock budget for a whole scan, in seconds. */
    private long maxScanSeconds = 900;

    /** Politeness delay between requests to the same host, in milliseconds. */
    private long politenessDelayMs = 200;

    /** Per-request connect/read timeout, in milliseconds. */
    private int requestTimeoutMs = 15000;

    /** Honour robots.txt disallow rules during crawling. */
    private boolean respectRobots = true;

    /** Follow and analyze subdomains of the seed host (false = same host only). */
    private boolean includeSubdomains = false;

    /** Master switch for the real-browser (Playwright) tier. Auto-disables if the browser
     *  can't be launched — the jsoup baseline still runs. */
    private boolean browserEnabled = true;

    /** How many pages get the full (expensive) browser + accessibility + responsive pass.
     *  The cheap HTTP analyzers always run on every crawled page. */
    private int browserSamplePages = 12;

    /** Run accessibility (axe-core) scanning in the browser tier. */
    private boolean accessibilityEnabled = true;

    /** Run responsive multi-viewport checks in the browser tier. */
    private boolean responsiveEnabled = true;

    /** Master switch for the Lighthouse/PageSpeed Insights performance tier. */
    private boolean pageSpeedEnabled = true;

    /** Google PageSpeed Insights API key (optional — raises quota; blank works for low volume). */
    private String pageSpeedApiKey = "";

    /** How many pages (homepage first) get a PageSpeed Insights audit, to stay within quota. */
    private int pageSpeedSamplePages = 3;

    /** PageSpeed strategy: {@code mobile} (Google's default ranking lens) or {@code desktop}. */
    private String pageSpeedStrategy = "mobile";

    /** Probe well-known sensitive paths (.git/.env/backup files) during the security scan. */
    private boolean securityProbeSensitivePaths = true;

    /** Analyze forms and interactive inputs (labels, validation, a11y, keyboard/tab order). */
    private boolean formValidationEnabled = true;

    /** In the browser tier, capture an annotated (highlighted-element) screenshot as evidence
     *  for findings that point at a specific DOM element. */
    private boolean annotatedEvidence = true;

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
    public long getMaxScanSeconds() { return maxScanSeconds; }
    public void setMaxScanSeconds(long maxScanSeconds) { this.maxScanSeconds = maxScanSeconds; }
    public long getPolitenessDelayMs() { return politenessDelayMs; }
    public void setPolitenessDelayMs(long politenessDelayMs) { this.politenessDelayMs = politenessDelayMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public boolean isRespectRobots() { return respectRobots; }
    public void setRespectRobots(boolean respectRobots) { this.respectRobots = respectRobots; }
    public boolean isIncludeSubdomains() { return includeSubdomains; }
    public void setIncludeSubdomains(boolean includeSubdomains) { this.includeSubdomains = includeSubdomains; }
    public boolean isBrowserEnabled() { return browserEnabled; }
    public void setBrowserEnabled(boolean browserEnabled) { this.browserEnabled = browserEnabled; }
    public int getBrowserSamplePages() { return browserSamplePages; }
    public void setBrowserSamplePages(int browserSamplePages) { this.browserSamplePages = browserSamplePages; }
    public boolean isAccessibilityEnabled() { return accessibilityEnabled; }
    public void setAccessibilityEnabled(boolean accessibilityEnabled) { this.accessibilityEnabled = accessibilityEnabled; }
    public boolean isResponsiveEnabled() { return responsiveEnabled; }
    public void setResponsiveEnabled(boolean responsiveEnabled) { this.responsiveEnabled = responsiveEnabled; }
    public boolean isPageSpeedEnabled() { return pageSpeedEnabled; }
    public void setPageSpeedEnabled(boolean pageSpeedEnabled) { this.pageSpeedEnabled = pageSpeedEnabled; }
    public String getPageSpeedApiKey() { return pageSpeedApiKey; }
    public void setPageSpeedApiKey(String pageSpeedApiKey) { this.pageSpeedApiKey = pageSpeedApiKey; }
    public int getPageSpeedSamplePages() { return pageSpeedSamplePages; }
    public void setPageSpeedSamplePages(int pageSpeedSamplePages) { this.pageSpeedSamplePages = pageSpeedSamplePages; }
    public String getPageSpeedStrategy() { return pageSpeedStrategy; }
    public void setPageSpeedStrategy(String pageSpeedStrategy) { this.pageSpeedStrategy = pageSpeedStrategy; }
    public boolean isSecurityProbeSensitivePaths() { return securityProbeSensitivePaths; }
    public void setSecurityProbeSensitivePaths(boolean securityProbeSensitivePaths) { this.securityProbeSensitivePaths = securityProbeSensitivePaths; }
    public boolean isFormValidationEnabled() { return formValidationEnabled; }
    public void setFormValidationEnabled(boolean formValidationEnabled) { this.formValidationEnabled = formValidationEnabled; }
    public boolean isAnnotatedEvidence() { return annotatedEvidence; }
    public void setAnnotatedEvidence(boolean annotatedEvidence) { this.annotatedEvidence = annotatedEvidence; }
}
