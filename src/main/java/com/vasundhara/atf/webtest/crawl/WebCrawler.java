package com.vasundhara.atf.webtest.crawl;

import com.vasundhara.atf.webtest.WebScanProperties;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breadth-first, single-host website crawler built on jsoup — the always-on baseline discovery
 * tier (no browser required). Resolves scope from the seed URL, honours robots.txt, seeds from
 * sitemap.xml, and enforces hard budgets (max pages / max depth / wall-clock) so a site of any
 * size terminates cleanly rather than exploding. Each fetched HTML page is streamed to the
 * supplied consumer for immediate analysis, then its DOM is released.
 */
public class WebCrawler {

    public static final String USER_AGENT =
            "Mozilla/5.0 (compatible; VasundharaWebTester/1.0; +https://vasundhara.io)";

    private static final Logger log = LoggerFactory.getLogger(WebCrawler.class);

    private final WebScanProperties props;
    private final String origin;          // scheme://host[:port]
    private final String host;
    private final String registrableRoot; // for subdomain matching
    private final RobotsTxt robots;

    private final Set<String> seen = new HashSet<>();
    private int pagesFetched = 0;

    public WebCrawler(WebScanProperties props, String seedUrl) {
        this.props = props;
        URI u = URI.create(seedUrl);
        String scheme = u.getScheme() == null ? "https" : u.getScheme();
        this.host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        int port = u.getPort();
        this.origin = scheme + "://" + host + (port > 0 ? ":" + port : "");
        this.registrableRoot = registrableRoot(host);
        this.robots = props.isRespectRobots()
                ? RobotsTxt.fetch(origin, props.getRequestTimeoutMs())
                : new RobotsTxt();
    }

    public RobotsTxt getRobots() { return robots; }
    public String getOrigin() { return origin; }

    /**
     * Crawl from the seed. {@code onDiscovered} is fired each time the total discovered count
     * grows (for live progress); {@code consumer} receives every fetched in-scope HTML page.
     */
    public void crawl(String seedUrl, Consumer<CrawledPage> consumer,
                      Consumer<Integer> onDiscovered, BooleanSupplier stopped) {
        long deadline = System.currentTimeMillis() + props.getMaxScanSeconds() * 1000L;
        Deque<String[]> queue = new ArrayDeque<>(); // {url, depth}
        String seedNorm = normalize(seedUrl, seedUrl);
        if (seedNorm == null) return;
        enqueue(queue, seedNorm, 0);

        // Seed from sitemap.xml (robots-declared or the well-known location).
        for (String sm : sitemapUrls()) {
            for (String loc : SitemapParser.fetchLocations(sm, props.getRequestTimeoutMs())) {
                String n = normalize(loc, seedUrl);
                if (n != null && inScope(n)) enqueue(queue, n, 1);
            }
        }
        onDiscovered.accept(queue.size());

        while (!queue.isEmpty()) {
            if (stopped.getAsBoolean()) { log.debug("Web crawl stopped by request"); return; }
            if (pagesFetched >= props.getMaxPages()) { log.debug("Web crawl hit maxPages cap"); return; }
            if (System.currentTimeMillis() > deadline) { log.debug("Web crawl hit time budget"); return; }

            String[] item = queue.poll();
            String url = item[0];
            int depth = Integer.parseInt(item[1]);

            CrawledPage page = fetch(url, depth, seedUrl);
            pagesFetched++;
            consumer.accept(page);

            // Enqueue newly discovered in-scope links within the depth budget.
            if (depth < props.getMaxDepth() && page.isHtml()) {
                for (String link : page.getInScopeLinks()) {
                    if (enqueue(queue, link, depth + 1)) {
                        onDiscovered.accept(pagesFetched + queue.size());
                    }
                }
            }
            politeness();
        }
    }

    private boolean enqueue(Deque<String[]> queue, String url, int depth) {
        if (url == null || seen.contains(url)) return false;
        if (!inScope(url)) return false;
        if (props.isRespectRobots() && !robots.isAllowed(pathOf(url))) return false;
        if (seen.size() >= props.getMaxPages() * 8) return false; // frontier safety cap
        seen.add(url);
        queue.add(new String[]{url, String.valueOf(depth)});
        return true;
    }

    private CrawledPage fetch(String url, int depth, String seedUrl) {
        CrawledPage page = new CrawledPage(url, depth);
        long t0 = System.currentTimeMillis();
        try {
            Connection.Response resp = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(props.getRequestTimeoutMs())
                    .followRedirects(true)
                    .ignoreHttpErrors(true)     // capture 4xx/5xx instead of throwing
                    .ignoreContentType(true)    // allow non-HTML (assets) without failing
                    .maxBodySize(4 * 1024 * 1024)
                    .execute();
            page.setLoadTimeMs(System.currentTimeMillis() - t0);
            page.setStatusCode(resp.statusCode());
            page.setContentType(resp.contentType());
            page.setFinalUrl(resp.url() == null ? url : resp.url().toString());

            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : resp.headers().entrySet()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
            page.setResponseHeaders(headers);

            if (resp.contentType() != null && resp.contentType().toLowerCase().contains("html")) {
                Document doc = resp.parse();
                page.setDoc(doc);
                extractLinks(doc, page, seedUrl);
            }
        } catch (Exception e) {
            page.setLoadTimeMs(System.currentTimeMillis() - t0);
            page.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return page;
    }

    private void extractLinks(Document doc, CrawledPage page, String seedUrl) {
        String base = page.getFinalUrl();
        // Anchor hrefs → crawl frontier + link check.
        for (Element a : doc.select("a[href]")) {
            String abs = a.absUrl("href");
            String n = normalize(abs, seedUrl);
            if (n == null) continue;
            page.getAllLinks().add(n);
            if (inScope(n) && isHtmlLikely(n)) page.getInScopeLinks().add(n);
        }
        // Asset references → link check only (never crawled).
        for (Element el : doc.select("img[src], script[src], link[href], source[src]")) {
            String attr = el.hasAttr("src") ? "src" : "href";
            String abs = el.absUrl(attr);
            String n = normalize(abs, seedUrl);
            if (n != null) page.getAllLinks().add(n);
        }
    }

    // ---- scope + normalization ------------------------------------------------

    private boolean inScope(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            if (h.equals(host)) return true;
            if (props.isIncludeSubdomains()) return h.endsWith("." + registrableRoot) || h.equals(registrableRoot);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Normalise a URL: absolutise, drop fragment, keep only http(s), strip trailing slash. */
    private String normalize(String url, String seedUrl) {
        if (url == null || url.isBlank()) return null;
        try {
            String u = url.trim();
            if (u.startsWith("mailto:") || u.startsWith("tel:") || u.startsWith("javascript:")
                    || u.startsWith("data:") || u.startsWith("#")) return null;
            URI uri = URI.create(u);
            if (!uri.isAbsolute()) uri = URI.create(seedUrl).resolve(uri);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
            String h = uri.getHost();
            if (h == null) return null;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            int port = uri.getPort();
            String q = uri.getRawQuery();
            return scheme + "://" + h.toLowerCase(Locale.ROOT)
                    + (port > 0 ? ":" + port : "") + path + (q != null ? "?" + q : "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String pathOf(String url) {
        try {
            String p = URI.create(url).getRawPath();
            return p == null || p.isEmpty() ? "/" : p;
        } catch (Exception e) { return "/"; }
    }

    /** Cheap heuristic: skip obvious non-HTML file extensions when choosing crawl frontier. */
    private static boolean isHtmlLikely(String url) {
        String p = pathOf(url).toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        if (dot < 0) return true;
        String ext = p.substring(dot + 1);
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "svg", "webp", "ico", "css", "js", "json", "xml",
                 "pdf", "zip", "gz", "mp4", "webm", "mp3", "woff", "woff2", "ttf", "eot", "map" -> false;
            default -> true;
        };
    }

    private List<String> sitemapUrls() {
        List<String> l = new java.util.ArrayList<>(robots.getSitemaps());
        l.add(origin + "/sitemap.xml");
        return l;
    }

    private static String registrableRoot(String host) {
        if (host == null) return "";
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private void politeness() {
        if (props.getPolitenessDelayMs() <= 0) return;
        try { Thread.sleep(props.getPolitenessDelayMs()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
