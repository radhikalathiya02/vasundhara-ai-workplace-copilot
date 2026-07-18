package com.vasundhara.atf.webtest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lifecycle + live state for one website scan. In-memory and streamed to the SPA via polling —
 * findings, page list and progress accumulate here as the scan runs, mirroring the Android
 * modules' session pattern (e.g. {@code AnalysisSession}/{@code CompatSession}). Not persisted
 * to the database, so a scan is self-contained and can never affect existing run history.
 *
 * <p>All mutable fields are {@code volatile} / backed by synchronised collections because the
 * async scan thread writes while the web tier reads for polling.
 */
public class WebScanSession {

    public enum State { QUEUED, CRAWLING, ANALYZING, ENRICHING, COMPLETED, FAILED, STOPPED }

    private final String id;
    private final String url;
    private volatile State state = State.QUEUED;
    private volatile String phase = "Queued";
    private volatile int percent = 0;
    private volatile String currentPage;
    private volatile String error;
    private volatile String screenshot;         // latest live-screen artifact path
    private volatile boolean browserUsed = false;
    private volatile boolean pageSpeedUsed = false;
    private final long createdAtMillis = System.currentTimeMillis();
    private volatile long finishedAtMillis = 0;

    private volatile int pagesDiscovered = 0;
    private volatile int pagesAnalyzed = 0;

    private final List<WebIssue> issues = Collections.synchronizedList(new ArrayList<>());
    private final List<PageInfo> pages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private volatile ScanSummary summary = new ScanSummary();

    /** Cooperative stop flag, honoured at page boundaries by the runner. */
    @JsonIgnore
    private volatile boolean stopRequested = false;

    public WebScanSession(String id, String url) {
        this.id = id;
        this.url = url;
    }

    public void log(String msg) {
        logs.add(msg);
        // Bound memory on very large scans — keep the most recent entries.
        if (logs.size() > 500) logs.remove(0);
    }

    public String getId() { return id; }
    public String getUrl() { return url; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = Math.max(0, Math.min(100, percent)); }
    public String getCurrentPage() { return currentPage; }
    public void setCurrentPage(String currentPage) { this.currentPage = currentPage; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getScreenshot() { return screenshot; }
    public void setScreenshot(String screenshot) { this.screenshot = screenshot; }
    public boolean isBrowserUsed() { return browserUsed; }
    public void setBrowserUsed(boolean browserUsed) { this.browserUsed = browserUsed; }
    public boolean isPageSpeedUsed() { return pageSpeedUsed; }
    public void setPageSpeedUsed(boolean pageSpeedUsed) { this.pageSpeedUsed = pageSpeedUsed; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getFinishedAtMillis() { return finishedAtMillis; }
    public void setFinishedAtMillis(long finishedAtMillis) { this.finishedAtMillis = finishedAtMillis; }
    public int getPagesDiscovered() { return pagesDiscovered; }
    public void setPagesDiscovered(int pagesDiscovered) { this.pagesDiscovered = pagesDiscovered; }
    public int getPagesAnalyzed() { return pagesAnalyzed; }
    public void setPagesAnalyzed(int pagesAnalyzed) { this.pagesAnalyzed = pagesAnalyzed; }
    public List<WebIssue> getIssues() { return issues; }
    public List<PageInfo> getPages() { return pages; }
    public List<String> getLogs() { return logs; }
    public ScanSummary getSummary() { return summary; }
    public void setSummary(ScanSummary summary) { this.summary = summary; }

    @JsonIgnore
    public boolean isStopRequested() { return stopRequested; }
    public void requestStop() { this.stopRequested = true; }

    @JsonIgnore
    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.FAILED || state == State.STOPPED;
    }
}
