package com.vasundhara.atf.webtest.analyze;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.crawl.CrawledPage;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static HTML quality / best-practice checks over the parsed DOM: doctype, charset, deprecated
 * elements, duplicate IDs, unsafe {@code target="_blank"} links, oversized DOM, and basic
 * UI/UX signals (missing favicon, images without explicit dimensions → layout shift). These
 * complement the Lighthouse "Best Practices" category with checks observable from the DOM alone.
 * Each finding is enriched with impact, root cause, the relevant standard and a concrete fix.
 */
@Component
public class HtmlBestPracticesAnalyzer {

    public List<WebIssue> analyze(CrawledPage page) {
        List<WebIssue> out = new ArrayList<>();
        if (!page.isHtml()) return out;
        Document doc = page.getDoc();
        String url = page.getFinalUrl();

        // --- Doctype ---
        boolean hasDoctype = doc.childNodes().stream().anyMatch(n -> n instanceof DocumentType);
        if (!hasDoctype) {
            out.add(bp(Severity.LOW, "Missing DOCTYPE",
                    "No <!DOCTYPE html> — the browser may render in quirks mode.",
                    "Add <!DOCTYPE html> as the first line of the document.", url)
                    .impact("Without a doctype, browsers use \"quirks mode\", applying legacy box-model and layout rules that cause subtle, hard-to-debug rendering differences across browsers.")
                    .rootCause("The document does not begin with a <!DOCTYPE html> declaration.")
                    .standard("HTML Living Standard — the DOCTYPE")
                    .element("<!DOCTYPE html>")
                    .codeFix("<!DOCTYPE html>\n<html lang=\"en\">\n  …"));
        }

        // --- Charset ---
        if (doc.selectFirst("meta[charset]") == null
                && doc.selectFirst("meta[http-equiv=Content-Type]") == null) {
            out.add(bp(Severity.LOW, "Missing charset declaration",
                    "No <meta charset> — character encoding is left to the browser to guess.",
                    "Add <meta charset=\"utf-8\"> early in <head>.", url)
                    .impact("Special characters (accents, symbols, emoji) can render as mojibake, and there is a minor XSS risk from encoding confusion.")
                    .rootCause("No <meta charset> (or equivalent http-equiv) declaration in the <head>.")
                    .standard("HTML Living Standard — charset · WHATWG Encoding")
                    .element("head > meta[charset]")
                    .codeFix("<head>\n  <meta charset=\"utf-8\">\n  …"));
        }

        // --- Deprecated elements ---
        Elements deprecated = doc.select("center, font, marquee, blink, big, strike, tt");
        if (!deprecated.isEmpty()) {
            out.add(bp(Severity.LOW, "Deprecated HTML elements",
                    "Found " + deprecated.size() + " deprecated element(s) (e.g. <center>, <font>, <marquee>).",
                    "Replace deprecated tags with CSS/modern equivalents.", url)
                    .impact("Deprecated presentational tags are unreliable across browsers and mix content with styling, hurting maintainability and accessibility.")
                    .rootCause(deprecated.size() + " element(s) use tags removed from the HTML standard: "
                            + sampleTags(deprecated) + ".")
                    .standard("HTML Living Standard — obsolete features")
                    .element(sampleTags(deprecated))
                    .codeFix("<!-- <center>…</center> --><div style=\"text-align:center\">…</div>\n"
                            + "<!-- <font color> --> use CSS: <span style=\"color:#333\">…</span>"));
        }

        // --- Duplicate IDs ---
        Map<String, Integer> ids = new HashMap<>();
        for (Element el : doc.select("[id]")) {
            String id = el.id();
            if (!id.isBlank()) ids.merge(id, 1, Integer::sum);
        }
        String dupId = ids.entrySet().stream().filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        long dupes = ids.values().stream().filter(c -> c > 1).count();
        if (dupes > 0) {
            out.add(bp(Severity.LOW, "Duplicate element IDs",
                    dupes + " id value(s) are used more than once. IDs must be unique per document.",
                    "Ensure every id attribute is unique.", url)
                    .impact("Duplicate IDs break getElementById(), in-page #anchor links, and label[for]/aria references — causing JavaScript bugs and accessibility failures that only affect the second element onward.")
                    .rootCause("The same id value is applied to multiple elements" + (dupId != null ? " (e.g. id=\"" + dupId + "\")" : "") + ".")
                    .standard("HTML Living Standard — the id attribute · WCAG 4.1.1")
                    .element(dupId != null ? "#" + dupId : "[id]")
                    .codeFix("<!-- make each id unique --><div id=\"item-1\">…</div><div id=\"item-2\">…</div>"));
        }

        // --- target=_blank without rel=noopener ---
        long unsafeBlank = doc.select("a[target=_blank]").stream()
                .filter(a -> !a.attr("rel").toLowerCase().contains("noopener"))
                .count();
        if (unsafeBlank > 0) {
            out.add(bp(Severity.LOW, "Unsafe target=\"_blank\" links",
                    unsafeBlank + " link(s) open in a new tab without rel=\"noopener\" (reverse-tabnabbing risk).",
                    "Add rel=\"noopener noreferrer\" to target=\"_blank\" links.", url)
                    .impact("The newly opened page can use window.opener to redirect the original tab to a phishing page (\"tabnabbing\"). rel=noopener severs that reference.")
                    .rootCause(unsafeBlank + " <a target=\"_blank\"> link(s) lack rel=\"noopener\".")
                    .standard("OWASP — reverse tabnabbing · HTML Living Standard rel=noopener")
                    .element("a[target=_blank]:not([rel*=noopener])")
                    .codeFix("<a href=\"https://external.example\" target=\"_blank\" rel=\"noopener noreferrer\">…</a>"));
        }

        // --- Oversized DOM ---
        int nodeCount = countNodes(doc);
        if (nodeCount > 1500) {
            out.add(bp(Severity.LOW, "Excessive DOM size",
                    "The page has ~" + nodeCount + " DOM nodes; large DOMs hurt rendering performance.",
                    "Reduce DOM depth/size; lazy-render off-screen content.", url)
                    .impact("Large DOMs increase memory use and make style/layout recalculation slow, hurting interaction responsiveness (INP) especially on low-end mobile devices.")
                    .rootCause("The rendered document contains ~" + nodeCount + " nodes (Lighthouse warns above ~1,500).")
                    .standard("Lighthouse — DOM size · Core Web Vitals (INP)")
                    .codeFix("Virtualise long lists (render only visible rows), lazy-mount off-screen sections, and remove wrapper divs."));
        }

        // --- Favicon ---
        if (doc.selectFirst("link[rel~=(?i)icon]") == null) {
            out.add(uiux(Severity.INFO, "Missing favicon",
                    "No favicon <link> declared — browsers show a generic placeholder.",
                    "Add a <link rel=\"icon\"> to the document head.", url)
                    .impact("Browser tabs, bookmarks and history show a blank/generic icon, weakening brand recognition and making the site look unfinished.")
                    .rootCause("No <link rel=\"icon\"> is declared in the <head>.")
                    .standard("HTML Living Standard — link type icon")
                    .element("head > link[rel=icon]")
                    .codeFix("<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"any\">\n<link rel=\"icon\" href=\"/icon.svg\" type=\"image/svg+xml\">"));
        }

        // --- Images without dimensions (layout shift / CLS) ---
        long imgNoDim = doc.select("img").stream()
                .filter(i -> (!i.hasAttr("width") || !i.hasAttr("height"))
                        && !i.attr("style").contains("aspect-ratio"))
                .count();
        if (imgNoDim > 3) {
            out.add(uiux(Severity.LOW, "Images without explicit dimensions",
                    imgNoDim + " image(s) lack width/height attributes, which can cause layout shift (CLS).",
                    "Set width/height (or CSS aspect-ratio) on images to reserve space.", url)
                    .impact("As images load, surrounding content jumps around (Cumulative Layout Shift), causing mis-clicks and a janky experience — and CLS is a Core Web Vital that affects Google ranking.")
                    .rootCause(imgNoDim + " <img> element(s) have neither width/height attributes nor a CSS aspect-ratio, so the browser can't reserve space before the image loads.")
                    .standard("Core Web Vitals — CLS · web.dev/optimize-cls")
                    .element("img:not([width]), img:not([height])")
                    .codeFix("<img src=\"photo.jpg\" width=\"800\" height=\"600\" alt=\"…\">\n/* or in CSS */ img { aspect-ratio: 4 / 3; width: 100%; height: auto; }"));
        }

        return out;
    }

    private int countNodes(Node node) {
        int count = 1;
        for (Node child : node.childNodes()) count += countNodes(child);
        return count;
    }

    private static String sampleTags(Elements els) {
        return els.stream().map(e -> "<" + e.tagName() + ">").distinct().limit(4)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static WebIssue bp(Severity sev, String title, String detail, String rec, String url) {
        return WebIssue.of(WebIssueCategory.BEST_PRACTICE, sev, title, detail, rec, url, "HTML");
    }

    private static WebIssue uiux(Severity sev, String title, String detail, String rec, String url) {
        return WebIssue.of(WebIssueCategory.UI_UX, sev, title, detail, rec, url, "HTML");
    }
}
