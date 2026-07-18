package com.vasundhara.atf.webtest.analyze;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.crawl.CrawledPage;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * On-page SEO analysis over the parsed DOM: title, meta description, headings, canonical,
 * robots directives, Open Graph/Twitter cards, {@code lang}, structured data, image alt
 * coverage and viewport. Deterministic (source = SEO); each finding maps to a {@link WebIssue}
 * enriched with impact, root cause, the standard being violated and a concrete code fix so the
 * report reads like a professional SEO audit. Cross-page duplicate title/description detection
 * is handled at site level by the runner.
 */
@Component
public class SeoAnalyzer {

    public List<WebIssue> analyze(CrawledPage page) {
        List<WebIssue> out = new ArrayList<>();
        if (!page.isHtml()) return out;
        Document doc = page.getDoc();
        String url = page.getFinalUrl();

        // --- Title ---
        String title = doc.title();
        if (title == null || title.isBlank()) {
            out.add(issue(WebIssueCategory.SEO, Severity.HIGH, "Missing page title",
                    "The page has no <title> element, or it is empty.",
                    "Add a unique, descriptive <title> (50–60 characters) to every page.", url)
                    .impact("The <title> is the clickable headline in search results and browser tabs. "
                            + "Without it, search engines invent one from page content, hurting rankings and click-through rate.")
                    .rootCause("The document <head> has no <title> element (or a template rendered it empty).")
                    .standard("Google Search Essentials · HTML Living Standard (§ the title element)")
                    .element("head > title")
                    .codeFix("<head>\n  <title>Primary Keyword — Brand Name</title>\n</head>"));
        } else if (title.length() > 65) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Title too long",
                    "Title is " + title.length() + " characters; search engines typically truncate around 60.",
                    "Shorten the title to ~50–60 characters.", url)
                    .impact("A truncated title (\"…\") in search results looks unprofessional and can cut off the key message, lowering click-through rate.")
                    .rootCause("The title text exceeds the ~600px width Google renders (≈60 characters).")
                    .standard("Google Search Essentials — title best practices")
                    .element("head > title")
                    .codeFix("<title>" + trunc(title, 55) + "</title>"));
        } else if (title.length() < 15) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Title too short",
                    "Title is only " + title.length() + " characters — likely not descriptive enough.",
                    "Expand the title to describe the page content (~50–60 characters).", url)
                    .impact("Very short titles carry few keywords and give users little reason to click, reducing organic traffic.")
                    .rootCause("The title text is under 15 characters — usually a placeholder or brand-only title.")
                    .standard("Google Search Essentials — title best practices")
                    .element("head > title"));
        }

        // --- Meta description ---
        Element desc = doc.selectFirst("meta[name=description]");
        String descContent = desc == null ? null : desc.attr("content");
        if (descContent == null || descContent.isBlank()) {
            out.add(issue(WebIssueCategory.SEO, Severity.MEDIUM, "Missing meta description",
                    "No <meta name=\"description\"> — search engines will auto-generate the snippet.",
                    "Add a compelling 120–160 character meta description.", url)
                    .impact("The meta description is the snippet shown under the title in search results. When missing, Google auto-extracts arbitrary text, often producing a poor snippet that lowers click-through rate.")
                    .rootCause("No <meta name=\"description\"> tag is present in the <head>.")
                    .standard("Google Search Essentials — meta description")
                    .element("head > meta[name=description]")
                    .codeFix("<meta name=\"description\" content=\"A concise 120–160 character summary of this page's content and value.\">"));
        } else if (descContent.length() > 170) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Meta description too long",
                    "Description is " + descContent.length() + " characters; it will be truncated in results.",
                    "Keep the meta description under ~160 characters.", url)
                    .impact("The tail of the description is cut off in search results, potentially hiding the call-to-action.")
                    .rootCause("The description content exceeds ~160 characters.")
                    .standard("Google Search Essentials — meta description")
                    .element("head > meta[name=description]"));
        }

        // --- Headings ---
        Elements h1s = doc.select("h1");
        if (h1s.isEmpty()) {
            out.add(issue(WebIssueCategory.SEO, Severity.MEDIUM, "Missing H1 heading",
                    "The page has no <h1>. A single, clear H1 helps both users and search engines.",
                    "Add exactly one descriptive <h1> per page.", url)
                    .impact("The H1 signals the page's main topic to search engines and assistive technology. Missing it weakens topical relevance and the accessible document outline.")
                    .rootCause("No <h1> element exists in the rendered markup (headings may start at <h2> or use styled <div>s).")
                    .standard("WCAG 2.1 SC 1.3.1 (Info and Relationships) · SEO heading hierarchy")
                    .element("h1")
                    .codeFix("<h1>The single, descriptive main heading of this page</h1>"));
        } else if (h1s.size() > 1) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Multiple H1 headings",
                    "Found " + h1s.size() + " <h1> elements. Prefer a single top-level heading.",
                    "Use one <h1> and structure the rest with <h2>–<h6>.", url)
                    .impact("Multiple H1s dilute the page's primary topic signal and can confuse the document outline used by screen readers.")
                    .rootCause(h1s.size() + " elements use the <h1> tag — often section titles that should be <h2>.")
                    .standard("SEO heading hierarchy · WCAG 2.1 SC 1.3.1")
                    .element("h1 (×" + h1s.size() + ")"));
        }

        // --- Canonical ---
        if (doc.selectFirst("link[rel=canonical]") == null) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Missing canonical link",
                    "No <link rel=\"canonical\">. Canonicals prevent duplicate-content dilution.",
                    "Add a self-referencing canonical URL to each page.", url)
                    .impact("Without a canonical, query-string and duplicate variants of this URL compete against each other, splitting ranking signals.")
                    .rootCause("No <link rel=\"canonical\"> in the <head>.")
                    .standard("Google — consolidate duplicate URLs (rel=canonical)")
                    .element("head > link[rel=canonical]")
                    .codeFix("<link rel=\"canonical\" href=\"" + esc(url) + "\">"));
        }

        // --- Robots noindex (accidental de-indexing) ---
        Element robots = doc.selectFirst("meta[name=robots]");
        if (robots != null && robots.attr("content").toLowerCase().contains("noindex")) {
            out.add(issue(WebIssueCategory.SEO, Severity.HIGH, "Page set to noindex",
                    "A robots meta tag marks this page 'noindex' — it will be excluded from search results.",
                    "Remove 'noindex' if this page should be discoverable.", url)
                    .impact("Search engines will drop this page entirely. If unintentional (e.g. a staging directive shipped to production), it silently kills all organic traffic to the page.")
                    .rootCause("<meta name=\"robots\" content=\"…noindex…\"> is present, often left over from a staging or draft configuration.")
                    .standard("Google — robots meta tag / noindex")
                    .element("head > meta[name=robots]")
                    .codeFix("<!-- Remove noindex, or narrow it: -->\n<meta name=\"robots\" content=\"index, follow\">"));
        }

        // --- lang attribute ---
        Element html = doc.selectFirst("html");
        String lang = html != null ? html.attr("lang") : "";
        if (lang == null || lang.isBlank()) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Missing lang attribute",
                    "The <html> element has no lang attribute — affects SEO and accessibility.",
                    "Set <html lang=\"en\"> (or the correct language code).", url)
                    .impact("Screen readers cannot select the correct pronunciation voice, and search engines cannot reliably determine the page language for geo/language targeting.")
                    .rootCause("The root <html> element is missing the lang attribute.")
                    .standard("WCAG 2.1 SC 3.1.1 (Language of Page)")
                    .element("html")
                    .codeFix("<html lang=\"en\">"));
        }

        // --- Open Graph (social sharing) ---
        if (doc.selectFirst("meta[property=og:title]") == null
                && doc.selectFirst("meta[property=og:image]") == null) {
            out.add(issue(WebIssueCategory.SEO, Severity.INFO, "Missing Open Graph tags",
                    "No Open Graph metadata — social shares (Facebook/LinkedIn) will lack a rich preview.",
                    "Add og:title, og:description and og:image meta tags.", url)
                    .impact("Links shared on social platforms and chat apps render as bare URLs with no image or title, sharply reducing engagement.")
                    .rootCause("No og:* meta tags found in the <head>.")
                    .standard("Open Graph protocol (ogp.me)")
                    .element("head > meta[property^=og:]")
                    .codeFix("<meta property=\"og:title\" content=\"Page title\">\n"
                            + "<meta property=\"og:description\" content=\"Short summary\">\n"
                            + "<meta property=\"og:image\" content=\"https://example.com/preview.png\">"));
        }

        // --- Structured data ---
        if (doc.select("script[type=application/ld+json]").isEmpty()
                && doc.select("[itemscope]").isEmpty()) {
            out.add(issue(WebIssueCategory.SEO, Severity.INFO, "No structured data",
                    "No JSON-LD or microdata found — structured data enables rich results.",
                    "Add schema.org structured data (JSON-LD) where relevant.", url)
                    .impact("The page is ineligible for rich results (star ratings, breadcrumbs, FAQ, product cards), which occupy more space and earn higher click-through in search.")
                    .rootCause("No JSON-LD <script> or microdata attributes are present.")
                    .standard("schema.org · Google — structured data")
                    .element("head > script[type=application/ld+json]")
                    .codeFix("<script type=\"application/ld+json\">\n{\n  \"@context\": \"https://schema.org\",\n  \"@type\": \"WebPage\",\n  \"name\": \"Page title\"\n}\n</script>"));
        }

        // --- Image alt coverage ---
        Elements imgs = doc.select("img");
        long missingAlt = imgs.stream().filter(i -> !i.hasAttr("alt") || i.attr("alt").isBlank()).count();
        if (missingAlt > 0) {
            out.add(issue(WebIssueCategory.SEO, Severity.LOW, "Images missing alt text",
                    missingAlt + " of " + imgs.size() + " images have no alt attribute (affects SEO & accessibility).",
                    "Add descriptive alt text to all meaningful images.", url)
                    .impact("Search engines cannot index these images (lost Google Images traffic), and screen-reader users hear nothing or the raw file name.")
                    .rootCause(missingAlt + " <img> element(s) have a missing or empty alt attribute.")
                    .standard("WCAG 2.1 SC 1.1.1 (Non-text Content)")
                    .element("img:not([alt])")
                    .codeFix("<img src=\"logo.png\" alt=\"Brand name logo\">\n<!-- decorative images: use alt=\"\" -->"));
        }

        // --- Viewport (mobile-friendliness) ---
        if (doc.selectFirst("meta[name=viewport]") == null) {
            out.add(issue(WebIssueCategory.SEO, Severity.MEDIUM, "Missing viewport meta tag",
                    "No responsive viewport meta tag — the page won't render well on mobile, hurting mobile ranking.",
                    "Add <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">.", url)
                    .impact("Mobile browsers render the page at desktop width and zoom out, making text unreadable. Google uses mobile-first indexing, so this directly harms rankings.")
                    .rootCause("No <meta name=\"viewport\"> in the <head>.")
                    .standard("Google — mobile-friendly / responsive design")
                    .element("head > meta[name=viewport]")
                    .codeFix("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"));
        }

        return out;
    }

    private static WebIssue issue(WebIssueCategory cat, Severity sev, String title,
                                  String detail, String rec, String url) {
        return WebIssue.of(cat, sev, title, detail, rec, url, "SEO");
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "%22");
    }
}
