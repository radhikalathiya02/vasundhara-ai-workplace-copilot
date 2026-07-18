package com.vasundhara.atf.webtest.analyze;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.WebScanProperties;
import com.vasundhara.atf.webtest.crawl.WebCrawler;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Site-level broken-link and missing-resource validation. Revalidates every unique URL
 * discovered during the crawl (internal pages, external links, and assets) with a HEAD request,
 * falling back to a ranged GET for servers that reject HEAD, then reports 4xx/5xx/unreachable
 * targets — each mapped back to the pages that reference it. Runs concurrently with a bounded
 * pool and honours the overall scan deadline and stop flag.
 */
@Component
public class LinkChecker {

    private static final Logger log = LoggerFactory.getLogger(LinkChecker.class);
    private static final int MAX_URLS = 800;   // hard cap so huge sites stay bounded

    private final WebScanProperties props;

    public LinkChecker(WebScanProperties props) {
        this.props = props;
    }

    /**
     * @param linkToReferrers unique URL → the set of pages that link to it
     * @param stopped         cooperative cancel supplier
     */
    public List<WebIssue> check(Map<String, java.util.Set<String>> linkToReferrers,
                                java.util.function.BooleanSupplier stopped) {
        List<WebIssue> out = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<WebIssue>> futures = new ArrayList<>();
        int submitted = 0;
        try {
            for (Map.Entry<String, java.util.Set<String>> e : linkToReferrers.entrySet()) {
                if (submitted >= MAX_URLS || stopped.getAsBoolean()) break;
                submitted++;
                String url = e.getKey();
                var referrers = e.getValue();
                futures.add(pool.submit(() -> validate(client, url, referrers)));
            }
            for (Future<WebIssue> f : futures) {
                try {
                    WebIssue issue = f.get(props.getRequestTimeoutMs() + 5000L, TimeUnit.MILLISECONDS);
                    if (issue != null) out.add(issue);
                } catch (Exception ex) {
                    // individual check failed to resolve within budget — skip
                }
            }
        } finally {
            pool.shutdownNow();
        }
        log.debug("Link check complete: {} URLs checked, {} broken", submitted, out.size());
        return out;
    }

    private WebIssue validate(HttpClient client, String url, java.util.Set<String> referrers) {
        try {
            URI uri = URI.create(url);
            int status = request(client, uri, "HEAD");
            // Many servers mis-handle HEAD (405/501) — retry with GET before judging.
            if (status == 405 || status == 501 || status == -1) {
                status = request(client, uri, "GET");
            }
            if (status >= 400) {
                Severity sev = status >= 500 ? Severity.HIGH
                        : status == 404 ? Severity.MEDIUM : Severity.LOW;
                WebIssue issue = WebIssue.of(WebIssueCategory.BROKEN_LINK, sev,
                        "Broken link (HTTP " + status + ")",
                        "The URL " + url + " returned HTTP " + status + ".",
                        "Fix or remove the link, or restore the target resource.",
                        referrers.isEmpty() ? url : referrers.iterator().next(), "LINKCHECK")
                        .impact("Users clicking this link hit " + (status == 404 ? "a \"page not found\" error"
                                : status >= 500 ? "a server error" : "an error response")
                                + ", eroding trust and dead-ending the journey. Broken links also waste search-engine crawl budget and can hurt rankings.")
                        .rootCause("The target responded with HTTP " + status
                                + (status == 404 ? " — the resource was moved or deleted without updating the link."
                                : status >= 500 ? " — the target server failed to handle the request." : "."))
                        .standard("HTTP semantics (RFC 9110) · SEO — avoid broken links")
                        .element(url)
                        .repro("From " + (referrers.isEmpty() ? "the site" : referrers.iterator().next())
                                + ", click the link to " + url + ".", "Observe the HTTP " + status + " response.")
                        .codeFix("Update the href to the correct URL, add a 301 redirect from the old path, or remove the link.");
                referrers.forEach(issue::setPage);
                return issue;
            } else if (status == -2) {
                WebIssue issue = WebIssue.of(WebIssueCategory.BROKEN_LINK, Severity.MEDIUM,
                        "Unreachable link",
                        "The URL " + url + " could not be reached (DNS/connection/timeout).",
                        "Verify the target host is reachable and the URL is correct.",
                        referrers.isEmpty() ? url : referrers.iterator().next(), "LINKCHECK")
                        .impact("The linked resource never loads for users — same dead-end effect as a 404, but caused by DNS, TLS or connectivity failure rather than an HTTP status.")
                        .rootCause("The host did not resolve or the connection timed out within the request budget.")
                        .standard("SEO — avoid broken links · availability best practice")
                        .element(url)
                        .repro("Attempt to open " + url + " directly.", "Observe a connection/DNS failure or timeout.")
                        .codeFix("Confirm the domain is correct and resolvable; fix the hostname or restore the target service.");
                referrers.forEach(issue::setPage);
                return issue;
            }
        } catch (Exception ignored) {
            // malformed URL — ignore
        }
        return null;
    }

    private int request(HttpClient client, URI uri, String method) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                    .header("User-Agent", WebCrawler.USER_AGENT)
                    .header("Range", "bytes=0-2048")
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        } catch (java.net.http.HttpTimeoutException te) {
            return -2;
        } catch (java.io.IOException | InterruptedException io) {
            if (io instanceof InterruptedException) Thread.currentThread().interrupt();
            return -2;
        } catch (Exception e) {
            return -1;
        }
    }
}
