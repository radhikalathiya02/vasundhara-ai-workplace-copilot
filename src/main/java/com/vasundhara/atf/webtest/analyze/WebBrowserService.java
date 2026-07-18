package com.vasundhara.atf.webtest.analyze;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.WebScanProperties;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Optional real-browser analysis tier built on Playwright for Java + Deque axe-core. Adds the
 * checks a static HTML pass cannot do: rendered-DOM accessibility (WCAG via axe), JavaScript
 * console/runtime errors, failed network requests, full-page screenshots, responsive layout
 * checks across viewports, and a <b>non-destructive</b> live form-validation probe (it reads
 * {@code checkValidity()} and never submits a form or mutates server state).
 *
 * <p>It also captures <b>annotated evidence screenshots</b>: for a finding that points at a
 * concrete DOM element, the element is scrolled into view, outlined in red, and the viewport is
 * captured — giving developers a picture of the actual page and the exact spot of the problem.
 *
 * <p><b>Graceful degradation</b> — exactly like the Android side's AI/Appium tiers: everything
 * here is best-effort. If the Chromium binaries can't be provisioned or a launch fails, the
 * service reports itself unavailable and the scan proceeds with the jsoup baseline. A single
 * page failing never aborts the scan.
 *
 * <p>Not thread-safe: Playwright objects are single-threaded. The scan runner drives this from
 * one thread, processing sampled pages sequentially.
 */
@Component
public class WebBrowserService {

    private static final Logger log = LoggerFactory.getLogger(WebBrowserService.class);

    // Responsive breakpoints: {width, height}.
    private static final int[][] BREAKPOINTS = {
            {360, 640},   // Mobile
            {768, 1024},  // Tablet
    };

    /** Max annotated evidence captures per page — keeps a heavy page from ballooning the run. */
    private static final int EVIDENCE_CAP_PER_PAGE = 6;

    private final WebScanProperties props;
    private volatile Boolean available; // null = not yet probed

    public WebBrowserService(WebScanProperties props) {
        this.props = props;
    }

    /** Per-scan browser output: all findings plus per-URL screenshot artifact paths. */
    public static class BrowserResult {
        public final List<WebIssue> issues = new ArrayList<>();
        public final java.util.Map<String, String> screenshots = new java.util.LinkedHashMap<>();
    }

    /**
     * Run the browser tier over the given page URLs. Opens one Chromium instance for the whole
     * batch. Returns partial results on any failure. {@code artifactDir} receives screenshots;
     * {@code screenshotRelBase} is prefixed to returned relative paths so the controller can
     * serve them.
     */
    public BrowserResult analyze(List<String> urls, Path artifactDir, String screenshotRelBase,
                                 java.util.function.Consumer<String> progress,
                                 java.util.function.BiConsumer<String, String> onScreenshot,
                                 BooleanSupplier stopped, List<WebIssue> staticCandidates) {
        BrowserResult result = new BrowserResult();
        if (!props.isBrowserEnabled() || urls.isEmpty()) return result;

        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            available = true;

            int idx = 0;
            for (String url : urls) {
                if (stopped.getAsBoolean()) break;
                idx++;
                if (progress != null) progress.accept("Browser analysis " + idx + "/" + urls.size() + ": " + url);
                try {
                    analyzeOnePage(browser, url, idx, artifactDir, screenshotRelBase, result, onScreenshot, staticCandidates);
                } catch (Exception e) {
                    log.debug("Browser analysis failed for {}: {}", url, e.toString());
                }
            }
        } catch (Throwable t) {
            // Chromium not installed / launch failed — disable the tier, keep the baseline.
            available = false;
            log.info("Browser tier unavailable ({}). Continuing with the HTTP/jsoup baseline.", t.toString());
        } finally {
            try { if (browser != null) browser.close(); } catch (Exception ignored) {}
            try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    public boolean wasAvailable() {
        return Boolean.TRUE.equals(available);
    }

    /**
     * Render a self-contained HTML document to a print-quality PDF using headless Chromium.
     * Reuses the same Playwright engine that powers the scan (so it works fully offline once the
     * browser is provisioned). Returns {@code null} if Chromium is unavailable — the caller then
     * falls back to the HTML report. The HTML must already inline its assets (our report embeds
     * every screenshot as a base64 data URI), so no network is needed to lay it out.
     */
    public byte[] renderPdf(String html) {
        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setContent(html, new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.LOAD).setTimeout(60000));
            return page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true)
                    .setMargin(new com.microsoft.playwright.options.Margin()
                            .setTop("14mm").setBottom("16mm").setLeft("12mm").setRight("12mm")));
        } catch (Throwable t) {
            log.info("PDF rendering unavailable ({}).", t.toString());
            return null;
        } finally {
            try { if (browser != null) browser.close(); } catch (Exception ignored) {}
            try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        }
    }

    private void analyzeOnePage(Browser browser, String url, int idx, Path artifactDir,
                                String relBase, BrowserResult result,
                                java.util.function.BiConsumer<String, String> onScreenshot,
                                List<WebIssue> staticCandidates) {
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                // Scale 1: full-page captures of long pages stay a reasonable size (they are
                // base64-embedded into the HTML/PDF report). 1366px wide is still sharp on screen.
                .setDeviceScaleFactor(1)
                .setUserAgent(com.vasundhara.atf.webtest.crawl.WebCrawler.USER_AGENT));
        Page page = ctx.newPage();

        List<String> consoleErrors = new ArrayList<>();
        List<String> pageErrors = new ArrayList<>();
        List<String> networkErrors = new ArrayList<>();

        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) consoleErrors.add(truncate(msg.text(), 300));
        });
        page.onPageError(err -> pageErrors.add(truncate(err, 400)));
        page.onResponse(resp -> {
            int s = resp.status();
            if (s >= 400) networkErrors.add(s + " " + truncate(resp.url(), 200));
        });
        page.onRequestFailed(req -> {
            String f = req.failure();
            if (f != null) networkErrors.add("FAILED " + truncate(req.url(), 200));
        });

        // Findings for THIS page are collected locally first, so we can attach annotated
        // element screenshots before folding them into the shared result.
        List<WebIssue> pageIssues = new ArrayList<>();

        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(25000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            try { page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(8000)); } catch (Exception ignored) {}

            // --- Screenshot (published live the moment it's captured) ---
            // A FULL-PAGE capture (header → footer, not just the viewport) is taken and streamed
            // immediately so the report shows the whole rendered page. setFullPage(true) also
            // auto-scrolls the page, which triggers lazy-loaded images to render before capture.
            try {
                String file = "shot-" + idx + ".png";
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(artifactDir.resolve(file)).setFullPage(true));
                String rel = relBase + "/" + file;
                result.screenshots.put(url, rel);
                if (onScreenshot != null) onScreenshot.accept(url, rel);  // live push
            } catch (Exception e) {
                log.debug("Screenshot failed for {}: {}", url, e.toString());
            }

            // --- Accessibility (axe-core / WCAG) ---
            if (props.isAccessibilityEnabled()) {
                collectAxe(page, url, pageIssues);
            }

            // --- Live, non-destructive form-validation probe ---
            if (props.isFormValidationEnabled()) {
                collectForms(page, url, pageIssues);
            }

            // --- Responsive layout ---
            if (props.isResponsiveEnabled()) {
                collectResponsive(ctx, url, pageIssues);
            }

            // --- Annotated evidence screenshots for element-anchored findings ---
            // Cover both this tier's findings AND element-anchored static findings (forms, SEO,
            // security) that landed on this same page, so a developer sees the real page with the
            // exact element highlighted regardless of which analyzer raised the issue.
            if (props.isAnnotatedEvidence()) {
                List<WebIssue> candidates = new ArrayList<>(pageIssues);
                if (staticCandidates != null) {
                    for (WebIssue si : staticCandidates) {
                        if (si.getEvidence() == null && isCssSelector(si.getElement())
                                && si.getAffectedPages().contains(url)) {
                            candidates.add(si);
                        }
                    }
                }
                captureEvidence(page, url, idx, artifactDir, relBase, candidates);
            }
        } finally {
            // --- JS console + runtime errors ---
            Set<String> seenJs = new HashSet<>();
            for (String pe : pageErrors) {
                if (seenJs.add(pe)) {
                    pageIssues.add(WebIssue.of(WebIssueCategory.JS_ERROR, Severity.HIGH,
                            "Uncaught JavaScript error", pe,
                            "Fix the runtime exception; it may break page functionality.", url, "BROWSER")
                            .impact("An uncaught exception can halt the script that raised it, leaving buttons, forms or widgets non-functional for every visitor on this page.")
                            .rootCause("The browser reported an uncaught error while executing the page's JavaScript.")
                            .standard("ECMAScript runtime error handling")
                            .repro("Open " + url + " with the DevTools Console open.",
                                    "Observe the error: " + truncate(pe, 160)));
                }
            }
            for (String ce : consoleErrors) {
                if (seenJs.add(ce)) {
                    pageIssues.add(WebIssue.of(WebIssueCategory.JS_ERROR, Severity.MEDIUM,
                            "Console error logged", ce,
                            "Investigate and resolve the console error.", url, "BROWSER")
                            .impact("Console errors often signal failed requests, missing resources or swallowed exceptions that degrade functionality or performance.")
                            .rootCause("The page logged an error to the browser console during load.")
                            .standard("Browser console diagnostics")
                            .repro("Open " + url + " with the DevTools Console open.",
                                    "Observe the logged error: " + truncate(ce, 160)));
                }
            }
            // --- Network errors ---
            Set<String> seenNet = new HashSet<>();
            for (String ne : networkErrors) {
                if (seenNet.add(ne)) {
                    pageIssues.add(WebIssue.of(WebIssueCategory.NETWORK, Severity.MEDIUM,
                            "Failed network request",
                            "A resource request failed or returned an error: " + ne,
                            "Ensure the resource exists and is reachable.", url, "BROWSER")
                            .impact("A failed request can mean a missing script, style, image or API call — often the direct cause of broken layout or features on the page.")
                            .rootCause("A sub-resource requested by the page returned an error or failed to load: " + ne)
                            .standard("HTTP semantics (RFC 9110)")
                            .repro("Open " + url + " with the DevTools Network tab open.",
                                    "Observe the failed request: " + ne));
                }
            }
            result.issues.addAll(pageIssues);
            try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    private void collectAxe(Page page, String url, List<WebIssue> issues) {
        try {
            AxeResults axe = new AxeBuilder(page)
                    .withTags(List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa"))
                    .analyze();
            for (Rule v : axe.getViolations()) {
                Severity sev = switch (v.getImpact() == null ? "" : v.getImpact()) {
                    case "critical" -> Severity.CRITICAL;
                    case "serious" -> Severity.HIGH;
                    case "moderate" -> Severity.MEDIUM;
                    default -> Severity.LOW;
                };
                int nodeCount = v.getNodes() == null ? 0 : v.getNodes().size();
                String target = "";
                String snippet = "";
                if (nodeCount > 0) {
                    var node = v.getNodes().get(0);
                    if (node.getTarget() != null) {
                        target = String.valueOf(node.getTarget()).replaceAll("^\\[|\\]$", "");
                    }
                    try { snippet = truncate(String.valueOf(node.getHtml()), 200); } catch (Exception ignored) {}
                }
                WebIssue issue = WebIssue.of(WebIssueCategory.ACCESSIBILITY, sev,
                        "A11y: " + v.getHelp(),
                        v.getDescription() + (nodeCount > 0 ? " (" + nodeCount + " element(s) affected)" : ""),
                        v.getHelp() + " — see " + v.getHelpUrl(),
                        url, "AXE")
                        .impact("Users relying on assistive technology (screen readers, keyboard, high-contrast modes) may be unable to perceive or operate this content. Accessibility defects also carry legal/compliance exposure (ADA, EN 301 549).")
                        .rootCause(v.getDescription() + (snippet.isBlank() ? "" : " Offending markup: " + snippet))
                        .standard(wcagStandard(v.getTags()) + " · Deque axe rule \"" + v.getId() + "\"")
                        .codeFix("Follow the fix guidance: " + v.getHelpUrl())
                        .confidence(0.98);
                issue.setElement(target.isBlank() ? v.getId() : target);
                issue.setWcag(wcagFromTags(v.getTags()));
                issues.add(issue);
            }
        } catch (Exception e) {
            log.debug("axe-core scan failed for {}: {}", url, e.toString());
        }
    }

    /**
     * Non-destructive live form check. Reads each form's constraint-validation state via
     * {@code checkValidity()} (which does <b>not</b> submit or navigate) to detect meaningful
     * forms that would accept an empty submission — i.e. required-field validation is missing.
     */
    @SuppressWarnings("unchecked")
    private void collectForms(Page page, String url, List<WebIssue> issues) {
        try {
            Object raw = page.evaluate(FORM_PROBE_JS);
            if (!(raw instanceof List<?> list)) return;
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> f = (Map<String, Object>) o;
                boolean allEmpty = Boolean.TRUE.equals(f.get("allEmpty"));
                boolean acceptsEmpty = Boolean.TRUE.equals(f.get("acceptsEmpty"));
                boolean hasPassword = Boolean.TRUE.equals(f.get("hasPassword"));
                String selector = String.valueOf(f.getOrDefault("selector", "form"));
                if (allEmpty && acceptsEmpty) {
                    Severity sev = hasPassword ? Severity.MEDIUM : Severity.LOW;
                    issues.add(WebIssue.of(WebIssueCategory.FORM_VALIDATION, sev,
                            "Form accepts empty submission (no required-field validation)",
                            "A meaningful form (" + (hasPassword ? "login/credential" : "data-entry")
                                    + ") reports valid via the browser's constraint API while every text field is empty.",
                            "Mark the fields users must complete as required (and mirror the check server-side).", url, "FORM")
                            .impact("Users can submit the form with no data, producing empty records, failed logins with unclear errors, or wasted round-trips — and no immediate client-side feedback tells them what went wrong.")
                            .rootCause("No control in the form carries a required (or otherwise blocking) constraint, so form.checkValidity() returns true when empty.")
                            .standard("HTML Living Standard — constraint validation · WCAG 3.3.1/3.3.3")
                            .element(selector)
                            .repro("Open " + url + " and, without typing anything, submit the form.",
                                    "Observe that submission is not blocked by client-side validation.")
                            .codeFix("<input name=\"email\" type=\"email\" required>\n<input name=\"password\" type=\"password\" required minlength=\"8\">")
                            .confidence(0.9));
                }
            }
        } catch (Exception e) {
            log.debug("Form probe failed for {}: {}", url, e.toString());
        }
    }

    /** JS run in the page to read form validity WITHOUT submitting anything. */
    private static final String FORM_PROBE_JS =
            "() => {\n" +
            "  const out = [];\n" +
            "  const EDIT = ['text','email','password','tel','number','url','search'];\n" +
            "  for (let i=0;i<document.forms.length;i++){\n" +
            "    const f=document.forms[i];\n" +
            "    const editable=[...f.elements].filter(e=>e.willValidate && !e.disabled &&\n" +
            "      (e.tagName==='TEXTAREA' || EDIT.includes((e.type||'').toLowerCase())));\n" +
            "    if(!editable.length) continue;\n" +
            "    const sig=((f.action||'')+' '+(f.id||'')+' '+(f.className||'')+' '+(f.getAttribute('name')||'')).toLowerCase();\n" +
            "    const hasPassword=!!f.querySelector('input[type=password]');\n" +
            "    const hasEmail=!!f.querySelector('input[type=email]');\n" +
            "    const meaningful=/login|log-in|signin|sign-in|signup|sign-up|register|contact|subscribe|newsletter|checkout|payment|search|account/.test(sig) || hasPassword || hasEmail;\n" +
            "    if(!meaningful) continue;\n" +
            "    const allEmpty=editable.every(e=>!e.value);\n" +
            "    let acceptsEmpty=false;\n" +
            "    try { if(allEmpty) acceptsEmpty=f.checkValidity(); } catch(e){}\n" +
            "    const selector = f.id?('#'+CSS.escape(f.id)):('form:nth-of-type('+(i+1)+')');\n" +
            "    out.push({index:i, selector, allEmpty, acceptsEmpty, hasPassword, editableCount:editable.length});\n" +
            "  }\n" +
            "  return out;\n" +
            "}";

    private void collectResponsive(BrowserContext ctx, String url, List<WebIssue> issues) {
        for (int[] bp : BREAKPOINTS) {
            Page p = null;
            try {
                p = ctx.newPage();
                p.setViewportSize(bp[0], bp[1]);
                p.navigate(url, new Page.NavigateOptions()
                        .setTimeout(20000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                Object overflow = p.evaluate(
                        "() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 2");
                if (Boolean.TRUE.equals(overflow)) {
                    WebIssue issue = WebIssue.of(WebIssueCategory.RESPONSIVE, Severity.MEDIUM,
                            "Horizontal overflow at " + bp[0] + "px width",
                            "Content extends beyond the viewport at " + bp[0] + "×" + bp[1]
                                    + ", forcing horizontal scrolling on that screen size.",
                            "Use responsive units, max-width:100%, and flexible layouts to fit the viewport.",
                            url, "BROWSER")
                            .impact("On phones (" + bp[0] + "px), part of the content is cut off and users must scroll sideways or pinch-zoom — a major mobile-usability problem that also hurts mobile-first ranking.")
                            .rootCause("An element is wider than the viewport (fixed pixel width, un-wrapped table/pre, oversized image, or a negative margin).")
                            .standard("Responsive design · Google mobile-friendliness · WCAG 1.4.10 (Reflow)")
                            .repro("Open " + url + " in a browser at " + bp[0] + "×" + bp[1] + " (or a phone).",
                                    "Observe horizontal scrolling / content extending past the screen edge.")
                            .codeFix("img, video, table { max-width: 100%; }\n/* find the overflow: [...document.querySelectorAll('*')].filter(e=>e.scrollWidth>document.documentElement.clientWidth) */");
                    issue.setElement("viewport:" + bp[0]);
                    issues.add(issue);
                }
            } catch (Exception e) {
                log.debug("Responsive check failed for {} @ {}px: {}", url, bp[0], e.toString());
            } finally {
                if (p != null) try { p.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * For findings on this page that point at a real DOM element, capture a screenshot of the
     * actual page with that element highlighted (red outline). Bounded by
     * {@link #EVIDENCE_CAP_PER_PAGE}, most-severe first, so it never dominates the scan.
     */
    private void captureEvidence(Page page, String url, int pageIdx, Path artifactDir,
                                 String relBase, List<WebIssue> pageIssues) {
        int captured = 0;
        // Highest severity first — that's where evidence matters most.
        List<WebIssue> ordered = new ArrayList<>(pageIssues);
        ordered.sort((a, b) -> Integer.compare(
                b.getSeverity() == null ? -1 : b.getSeverity().weight(),
                a.getSeverity() == null ? -1 : a.getSeverity().weight()));
        int n = 0;
        for (WebIssue issue : ordered) {
            if (captured >= EVIDENCE_CAP_PER_PAGE) break;
            if (issue.getEvidence() != null) continue;
            String sel = issue.getElement();
            if (!isCssSelector(sel)) continue;
            n++;
            String file = "evi-" + pageIdx + "-" + n + ".png";
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() == 0) continue;
                loc.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(2500));
                loc.evaluate("el => { el.__pw_o = el.style.outline; el.__pw_s = el.style.boxShadow;"
                        + " el.style.outline='3px solid #ff2d55'; el.style.outlineOffset='2px';"
                        + " el.style.boxShadow='0 0 0 6px rgba(255,45,85,.30)'; }");
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(artifactDir.resolve(file)).setFullPage(false));
                loc.evaluate("el => { el.style.outline=el.__pw_o||''; el.style.boxShadow=el.__pw_s||''; }");
                issue.evidence(relBase + "/" + file, true);
                captured++;
            } catch (Exception e) {
                log.debug("Evidence capture failed for {} @ {}: {}", url, sel, e.toString());
            }
        }
    }

    /** True if the string looks like a usable CSS selector (not an axe rule-id fallback). */
    private static boolean isCssSelector(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.startsWith("viewport:")) return false;
        if (s.startsWith("http://") || s.startsWith("https://")) return false;   // link-check targets
        if (s.startsWith("dup-")) return false;
        // A CSS selector has structure (#, ., [, >, space) or is a bare id/class/tag token we can try.
        return s.matches(".*[#.\\[> ].*") || s.matches("[a-zA-Z][a-zA-Z0-9_-]*");
    }

    private static String wcagStandard(List<String> tags) {
        String c = wcagFromTags(tags);
        return c == null ? "WCAG 2.1 (A/AA)" : "WCAG " + c.replace("WCAG", "").trim();
    }

    private static String wcagFromTags(List<String> tags) {
        if (tags == null) return null;
        for (String t : tags) {
            if (t.startsWith("wcag") && t.length() > 4 && Character.isDigit(t.charAt(4))) {
                return t.toUpperCase();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
