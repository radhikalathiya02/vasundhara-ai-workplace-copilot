package com.vasundhara.atf.webtest.analyze;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.crawl.CrawledPage;
import com.vasundhara.atf.webtest.crawl.WebCrawler;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Passive (non-intrusive) security-posture analysis: HTTPS enforcement, presence of the core
 * security response headers, mixed-content detection, cookie flags, information-exposure
 * banners, and (once per scan) a probe of well-known sensitive paths. Strictly read-only — it
 * never submits data, mutates state, or performs injection/attack traffic. Every finding is
 * enriched with impact, root cause, the standard being violated and a concrete configuration fix.
 */
@Component
public class SecurityAnalyzer {

    /** Per-page checks over the response headers and rendered HTML. */
    public List<WebIssue> analyze(CrawledPage page) {
        List<WebIssue> out = new ArrayList<>();
        Map<String, String> h = page.getResponseHeaders();
        String url = page.getFinalUrl();
        if (h == null) return out;

        boolean https = url != null && url.startsWith("https://");

        // --- HTTPS ---
        if (!https) {
            out.add(issue(Severity.HIGH, "Page served over HTTP",
                    "This page is not served over HTTPS; traffic can be intercepted or modified.",
                    "Serve all pages over HTTPS and redirect HTTP → HTTPS.", url)
                    .impact("On plain HTTP, anyone on the network path (public Wi-Fi, ISP, proxy) can read and tamper with the page and any data entered. Browsers also mark the page \"Not secure\".")
                    .rootCause("The response was served over the http:// scheme with no redirect to https://.")
                    .standard("OWASP Transport Layer Protection · Google — HTTPS as a ranking signal")
                    .codeFix("# nginx\nserver {\n  listen 80;\n  return 301 https://$host$request_uri;\n}"));
        }

        // --- Security headers ---
        if (missing(h, "content-security-policy")) {
            out.add(issue(Severity.MEDIUM, "Missing Content-Security-Policy header",
                    "No CSP header — increases exposure to XSS and content-injection attacks.",
                    "Define a Content-Security-Policy restricting allowed script/style/resource origins.", url)
                    .impact("Without a CSP, an injected <script> (via XSS or a compromised third-party asset) runs with full privileges — able to steal sessions, exfiltrate data, or deface the page.")
                    .rootCause("The server sends no Content-Security-Policy response header.")
                    .standard("OWASP Secure Headers · CSP Level 3 (W3C)")
                    .codeFix("Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'self'"));
        }
        if (https && missing(h, "strict-transport-security")) {
            out.add(issue(Severity.MEDIUM, "Missing HSTS header",
                    "No Strict-Transport-Security header — browsers may still attempt insecure HTTP.",
                    "Add 'Strict-Transport-Security: max-age=31536000; includeSubDomains'.", url)
                    .impact("The first request (or a typed http:// URL) can be downgraded and intercepted (SSL-strip). HSTS forces the browser to use HTTPS for all future visits.")
                    .rootCause("No Strict-Transport-Security header on the HTTPS response.")
                    .standard("OWASP Secure Headers · RFC 6797 (HSTS)")
                    .codeFix("Strict-Transport-Security: max-age=31536000; includeSubDomains; preload"));
        }
        if (missing(h, "x-content-type-options")) {
            out.add(issue(Severity.LOW, "Missing X-Content-Type-Options header",
                    "Without 'nosniff', browsers may MIME-sniff responses, enabling some attacks.",
                    "Add 'X-Content-Type-Options: nosniff'.", url)
                    .impact("Browsers may guess (\"sniff\") a response's type and execute an uploaded file (e.g. a .txt containing script) as JavaScript, enabling content-injection attacks.")
                    .rootCause("No X-Content-Type-Options header is set.")
                    .standard("OWASP Secure Headers")
                    .codeFix("X-Content-Type-Options: nosniff"));
        }
        if (missing(h, "x-frame-options") && !cspHasFrameAncestors(h)) {
            out.add(issue(Severity.LOW, "Missing clickjacking protection",
                    "Neither X-Frame-Options nor CSP frame-ancestors is set — the page can be framed (clickjacking).",
                    "Add 'X-Frame-Options: SAMEORIGIN' or a CSP 'frame-ancestors' directive.", url)
                    .impact("An attacker can embed this page in an invisible <iframe> over their own UI and trick users into clicking real buttons (clickjacking), e.g. to change settings or confirm actions.")
                    .rootCause("Neither X-Frame-Options nor a CSP frame-ancestors directive is present.")
                    .standard("OWASP Clickjacking Defense")
                    .codeFix("Content-Security-Policy: frame-ancestors 'self'\n# or the legacy header:\nX-Frame-Options: SAMEORIGIN"));
        }
        if (missing(h, "referrer-policy")) {
            out.add(issue(Severity.INFO, "Missing Referrer-Policy header",
                    "No Referrer-Policy — full URLs may leak to third parties via the Referer header.",
                    "Add e.g. 'Referrer-Policy: strict-origin-when-cross-origin'.", url)
                    .impact("Full URLs (which may contain tokens or IDs) are sent to external sites and analytics in the Referer header, leaking potentially sensitive path/query data.")
                    .rootCause("No Referrer-Policy header is set.")
                    .standard("OWASP Secure Headers · W3C Referrer Policy")
                    .codeFix("Referrer-Policy: strict-origin-when-cross-origin"));
        }

        // --- Information exposure ---
        String powered = h.get("x-powered-by");
        if (powered != null && !powered.isBlank()) {
            out.add(issue(Severity.LOW, "Technology disclosed via X-Powered-By",
                    "Response advertises '" + powered + "', helping attackers target known vulnerabilities.",
                    "Remove or obscure the X-Powered-By header.", url)
                    .impact("Advertising the exact server stack lets attackers look up version-specific CVEs and skip straight to known exploits.")
                    .rootCause("The framework/runtime emits an X-Powered-By: " + powered + " header by default.")
                    .standard("OWASP — fingerprinting / information exposure")
                    .element("X-Powered-By: " + powered)
                    .codeFix("# Express: app.disable('x-powered-by')\n# PHP: expose_php = Off\n# nginx: proxy_hide_header X-Powered-By;"));
        }
        String server = h.get("server");
        if (server != null && server.matches(".*\\d+\\.\\d+.*")) {
            out.add(issue(Severity.INFO, "Server version disclosed",
                    "The Server header exposes a version ('" + server + "').",
                    "Suppress the version in the Server header.", url)
                    .impact("A precise server version narrows the attacker's search for applicable exploits.")
                    .rootCause("The Server header includes a version string: " + server + ".")
                    .standard("OWASP — fingerprinting / information exposure")
                    .element("Server: " + server)
                    .codeFix("# nginx: server_tokens off;\n# Apache: ServerTokens Prod"));
        }

        // --- Cookie flags ---
        String setCookie = h.get("set-cookie");
        if (setCookie != null) {
            String lc = setCookie.toLowerCase();
            if (https && !lc.contains("secure")) {
                out.add(issue(Severity.MEDIUM, "Cookie without Secure flag",
                        "A Set-Cookie was issued without the Secure flag on an HTTPS page.",
                        "Add the 'Secure' attribute to cookies.", url)
                        .impact("Without Secure, the cookie is also sent over any http:// request, where it can be intercepted — defeating HTTPS for session security.")
                        .rootCause("A Set-Cookie header lacks the Secure attribute.")
                        .standard("OWASP Session Management · RFC 6265")
                        .codeFix("Set-Cookie: session=…; Secure; HttpOnly; SameSite=Lax"));
            }
            if (!lc.contains("httponly")) {
                out.add(issue(Severity.LOW, "Cookie without HttpOnly flag",
                        "A cookie lacks HttpOnly, so it is readable by JavaScript (XSS risk).",
                        "Add the 'HttpOnly' attribute to session cookies.", url)
                        .impact("If an XSS flaw exists anywhere on the site, script can read this cookie (e.g. document.cookie) and hijack the session. HttpOnly blocks that.")
                        .rootCause("A Set-Cookie header lacks the HttpOnly attribute.")
                        .standard("OWASP Session Management · RFC 6265")
                        .codeFix("Set-Cookie: session=…; HttpOnly; Secure; SameSite=Lax"));
            }
            if (!lc.contains("samesite")) {
                out.add(issue(Severity.INFO, "Cookie without SameSite attribute",
                        "A cookie has no SameSite attribute (CSRF hardening).",
                        "Set 'SameSite=Lax' or 'Strict' on cookies.", url)
                        .impact("Without SameSite, the cookie is sent on cross-site requests, widening exposure to cross-site request forgery (CSRF).")
                        .rootCause("A Set-Cookie header lacks the SameSite attribute.")
                        .standard("OWASP CSRF Prevention · RFC 6265bis")
                        .codeFix("Set-Cookie: session=…; SameSite=Lax; Secure; HttpOnly"));
            }
        }

        // --- Mixed content ---
        if (https && page.isHtml()) {
            long mixed = page.getDoc().select("[src], [href]").stream()
                    .map(e -> e.hasAttr("src") ? e.absUrl("src") : e.absUrl("href"))
                    .filter(u -> u.startsWith("http://"))
                    .count();
            if (mixed > 0) {
                out.add(issue(Severity.MEDIUM, "Mixed content on HTTPS page",
                        mixed + " resource(s) are loaded over insecure HTTP on an HTTPS page.",
                        "Load all resources over HTTPS (or protocol-relative // URLs).", url)
                        .impact("Browsers block active mixed content (scripts/styles) and warn on passive content, breaking layout/behaviour and downgrading the page's security indicator.")
                        .rootCause(mixed + " element(s) reference http:// resources from an https:// page.")
                        .standard("W3C Mixed Content · OWASP TLS")
                        .element("[src^='http://'], [href^='http://']")
                        .codeFix("<!-- change http:// to https:// (or //) --><script src=\"https://cdn.example.com/app.js\"></script>"));
            }
        }

        // --- Password form over HTTP ---
        if (!https && page.isHtml() && !page.getDoc().select("input[type=password]").isEmpty()) {
            out.add(issue(Severity.CRITICAL, "Password field on non-HTTPS page",
                    "A password input is served over insecure HTTP — credentials can be sniffed.",
                    "Serve any page with credential inputs strictly over HTTPS.", url)
                    .impact("Credentials typed into this form are transmitted in clear text and can be captured by anyone on the network path. This is a critical account-takeover vector.")
                    .rootCause("An <input type=\"password\"> is present on a page served over http://.")
                    .standard("OWASP Transport Layer Protection · PCI-DSS 4.0 §4")
                    .element("input[type=password]")
                    .codeFix("Serve the login page over HTTPS and set the form action to an https:// endpoint."));
        }

        return out;
    }

    /**
     * One-time probe of well-known sensitive paths at the site origin. Only issues a finding
     * when a path returns 200 with plausible content — a real exposure, not a soft-404.
     */
    public List<WebIssue> probeSensitivePaths(String origin, int timeoutMs) {
        List<WebIssue> out = new ArrayList<>();
        String[][] targets = {
                {"/.git/config", "Exposed .git repository", "CRITICAL",
                        "The full source code (and its history, which often contains secrets and credentials) can be reconstructed and downloaded by anyone."},
                {"/.env", "Exposed .env configuration file", "CRITICAL",
                        "Environment files typically hold database passwords, API keys and app secrets — a direct path to full compromise."},
                {"/.htaccess", "Exposed .htaccess file", "MEDIUM",
                        "Server rewrite/security rules are revealed, helping an attacker map protections and bypass them."},
                {"/backup.zip", "Exposed backup archive", "HIGH",
                        "A downloadable backup can contain source code, databases and credentials."},
                {"/phpinfo.php", "Exposed phpinfo() page", "HIGH",
                        "phpinfo() discloses the full server configuration, paths, loaded modules and environment — a reconnaissance goldmine."},
        };
        for (String[] t : targets) {
            try {
                var resp = Jsoup.connect(origin + t[0])
                        .userAgent(WebCrawler.USER_AGENT).timeout(timeoutMs)
                        .ignoreContentType(true).ignoreHttpErrors(true)
                        .followRedirects(false).maxBodySize(64 * 1024).execute();
                if (resp.statusCode() == 200 && resp.body() != null && resp.body().length() > 5) {
                    out.add(issue(Severity.valueOf(t[2]), t[1],
                            "The path " + t[0] + " is publicly accessible (HTTP 200) and may leak sensitive data.",
                            "Block public access to " + t[0] + " at the web server / deployment layer.",
                            origin + t[0])
                            .impact(t[3])
                            .rootCause("The web server serves " + t[0] + " as a static file instead of denying it.")
                            .standard("OWASP — sensitive data exposure / security misconfiguration")
                            .element(origin + t[0])
                            .repro("Open " + origin + t[0] + " in a browser or run: curl -i " + origin + t[0],
                                    "Observe an HTTP 200 response returning the file contents.")
                            .codeFix("# nginx\nlocation ~ /\\.(git|env|htaccess) { deny all; return 404; }\n"
                                    + "# Do not deploy backup archives or phpinfo.php to production."));
                }
            } catch (Exception ignored) {
                // unreachable / not present → good
            }
        }
        return out;
    }

    private static boolean cspHasFrameAncestors(Map<String, String> h) {
        String csp = h.get("content-security-policy");
        return csp != null && csp.toLowerCase().contains("frame-ancestors");
    }

    private static boolean missing(Map<String, String> h, String key) {
        String v = h.get(key);
        return v == null || v.isBlank();
    }

    private static WebIssue issue(Severity sev, String title, String detail, String rec, String url) {
        return WebIssue.of(WebIssueCategory.SECURITY, sev, title, detail, rec, url, "SECURITY");
    }
}
