package com.vasundhara.atf.webtest.model;

/**
 * Lightweight per-page record exposed in the scan report for the drill-down view: what was
 * crawled, its HTTP status, title, crawl depth, an optional evidence screenshot, and how many
 * issues were attributed to it. The heavy analysis output lives in the flat {@link WebIssue}
 * list on the session (deduped/cross-page), not here.
 */
public class PageInfo {

    private final String url;
    private int status;
    private String title;
    private int depth;
    private String screenshot;      // relative artifact path, browser tier only
    private int issueCount;
    private long loadTimeMs;

    public PageInfo(String url) { this.url = url; }

    public String getUrl() { return url; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public String getScreenshot() { return screenshot; }
    public void setScreenshot(String screenshot) { this.screenshot = screenshot; }
    public int getIssueCount() { return issueCount; }
    public void setIssueCount(int issueCount) { this.issueCount = issueCount; }
    public long getLoadTimeMs() { return loadTimeMs; }
    public void setLoadTimeMs(long loadTimeMs) { this.loadTimeMs = loadTimeMs; }
}
