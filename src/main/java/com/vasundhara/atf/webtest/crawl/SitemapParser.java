package com.vasundhara.atf.webtest.crawl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts {@code <loc>} URLs from a sitemap.xml (or a sitemap index, one level deep). Used to
 * seed the crawl frontier with pages the site itself advertises — improving coverage of pages
 * that aren't linked from the homepage. Best-effort: failures yield an empty list.
 */
public class SitemapParser {

    private static final int MAX_LOCS = 500;

    public static List<String> fetchLocations(String sitemapUrl, int timeoutMs) {
        List<String> out = new ArrayList<>();
        try {
            String xml = Jsoup.connect(sitemapUrl)
                    .ignoreContentType(true)
                    .timeout(timeoutMs)
                    .userAgent(WebCrawler.USER_AGENT)
                    .maxBodySize(8 * 1024 * 1024)
                    .execute().body();
            Document doc = Jsoup.parse(xml, "", Parser.xmlParser());

            // Sitemap index → follow child sitemaps (one level).
            if (!doc.select("sitemapindex > sitemap > loc").isEmpty()) {
                for (Element loc : doc.select("sitemapindex > sitemap > loc")) {
                    if (out.size() >= MAX_LOCS) break;
                    out.addAll(fetchLocations(loc.text().trim(), timeoutMs));
                }
                return out;
            }
            for (Element loc : doc.select("urlset > url > loc")) {
                if (out.size() >= MAX_LOCS) break;
                String u = loc.text().trim();
                if (!u.isEmpty()) out.add(u);
            }
        } catch (Exception ignored) {
            // no sitemap / parse error → nothing to seed
        }
        return out;
    }
}
