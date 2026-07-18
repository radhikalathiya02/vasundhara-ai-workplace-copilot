package com.vasundhara.atf.webtest.crawl;

import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal robots.txt parser — reads the {@code Disallow} rules that apply to the wildcard
 * user-agent group ({@code User-agent: *}) so the crawler can honour them (politeness &amp;
 * authorization). Also surfaces any declared {@code Sitemap:} URLs to seed discovery.
 *
 * <p>Deliberately conservative and best-effort: any parse/network failure yields an empty
 * rule set (allow-all) rather than blocking the scan.
 */
public class RobotsTxt {

    private final List<String> disallow = new ArrayList<>();
    private final List<String> sitemaps = new ArrayList<>();

    public static RobotsTxt fetch(String origin, int timeoutMs) {
        RobotsTxt r = new RobotsTxt();
        try {
            String body = Jsoup.connect(origin + "/robots.txt")
                    .ignoreContentType(true)
                    .timeout(timeoutMs)
                    .userAgent(WebCrawler.USER_AGENT)
                    .execute().body();
            r.parse(body);
        } catch (Exception ignored) {
            // No robots.txt or unreachable — treat as allow-all.
        }
        return r;
    }

    void parse(String body) {
        if (body == null) return;
        boolean appliesToUs = false;
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> appliesToUs = val.equals("*");
                case "disallow" -> { if (appliesToUs && !val.isEmpty()) disallow.add(val); }
                case "sitemap" -> sitemaps.add(val);
                default -> { /* allow, crawl-delay, etc. ignored */ }
            }
        }
    }

    /** True if the given path prefix is disallowed for the wildcard agent group. */
    public boolean isAllowed(String path) {
        if (path == null || path.isEmpty()) path = "/";
        for (String rule : disallow) {
            if (rule.equals("/")) return false;
            if (path.startsWith(rule)) return false;
        }
        return true;
    }

    public List<String> getSitemaps() { return sitemaps; }
}
