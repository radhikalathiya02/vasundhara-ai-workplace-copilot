package com.vasundhara.atf.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Forwards all client-side SPA routes to index.html so that the browser can navigate
 * directly to a URL (bookmark, share, browser refresh) and the JavaScript router takes
 * over once the page loads.
 *
 * <p>Only the named application routes are listed here — API paths, static assets
 * (*.html, *.svg, *.js, *.css) are never matched by these mappings.
 */
@Controller
public class SpaController {

    /** Top-level pages each sidebar item navigates to. */
    @GetMapping({
        "/overview",
        "/runs",
        "/test-running",
        "/smart-execution",
        "/smart-execution-running",
        "/remote-config",
        "/priority-logs",
        "/localization",
        "/test-cases",
        "/generate-test-case",
        "/settings",
        "/compat",
        "/devices",
        "/reports"
    })
    public String spaPage() {
        return "forward:/index.html";
    }

    /** Run detail page — /runs/{id} where id is a UUID with no dots. */
    @GetMapping("/runs/{id:[^.]+}")
    public String spaRunDetail(@PathVariable String id) {
        return "forward:/index.html";
    }
}
