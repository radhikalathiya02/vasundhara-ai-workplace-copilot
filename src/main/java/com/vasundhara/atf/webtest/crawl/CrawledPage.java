package com.vasundhara.atf.webtest.crawl;

import org.jsoup.nodes.Document;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One fetched page handed to the analyzers as the crawl streams. Carries the parsed DOM,
 * response metadata (status, headers, content type, final URL after redirects) and the
 * classified out-links (in-scope pages to enqueue, plus every link/asset URL for the
 * broken-link checker). The {@link Document} is analysed immediately then released, so the
 * crawler never holds the whole site's DOM in memory at once.
 */
public class CrawledPage {

    private final String requestedUrl;
    private String finalUrl;
    private int depth;
    private int statusCode;
    private String contentType;
    private long loadTimeMs;
    private Document doc;
    private Map<String, String> responseHeaders;
    private String error;

    /** In-scope, same-site HTML page URLs discovered on this page (crawl frontier). */
    private final Set<String> inScopeLinks = new LinkedHashSet<>();
    /** Every href/src URL found (internal + external + assets) for broken-link validation. */
    private final Set<String> allLinks = new LinkedHashSet<>();

    public CrawledPage(String requestedUrl, int depth) {
        this.requestedUrl = requestedUrl;
        this.finalUrl = requestedUrl;
        this.depth = depth;
    }

    public boolean isHtml() {
        return contentType != null && contentType.toLowerCase().contains("html") && doc != null;
    }

    public String getRequestedUrl() { return requestedUrl; }
    public String getFinalUrl() { return finalUrl; }
    public void setFinalUrl(String finalUrl) { this.finalUrl = finalUrl; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getLoadTimeMs() { return loadTimeMs; }
    public void setLoadTimeMs(long loadTimeMs) { this.loadTimeMs = loadTimeMs; }
    public Document getDoc() { return doc; }
    public void setDoc(Document doc) { this.doc = doc; }
    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Set<String> getInScopeLinks() { return inScopeLinks; }
    public Set<String> getAllLinks() { return allLinks; }
}
