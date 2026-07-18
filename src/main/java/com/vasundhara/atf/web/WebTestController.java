package com.vasundhara.atf.web;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.webtest.WebScanRunner;
import com.vasundhara.atf.webtest.WebScanSessionStore;
import com.vasundhara.atf.webtest.analyze.WebBrowserService;
import com.vasundhara.atf.webtest.model.WebScanSession;
import com.vasundhara.atf.webtest.report.WebReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the Website Testing module — the web counterpart of the APK analysis
 * controllers. One input (a URL) starts a whole-site scan; the rest is polling for live progress
 * and retrieving the consolidated report/artifacts.
 *
 * <pre>
 * POST   /api/web/scan                         — { "url": "..." } → start scan, returns { id }
 * GET    /api/web/sessions                     — scan history (light summaries)
 * GET    /api/web/sessions/{id}                — full live session (progress + findings)
 * POST   /api/web/sessions/{id}/stop           — cooperative stop
 * DELETE /api/web/sessions/{id}                — delete a scan from history
 * GET    /api/web/sessions/{id}/report.html    — consolidated HTML report
 * GET    /api/web/sessions/{id}/artifacts/{f}  — screenshots (path-traversal guarded)
 * GET    /api/web/sessions/{id}/live-screen    — latest screenshot (PNG) for the live view
 * </pre>
 *
 * Independent of the Android device/execution lock — a website scan uses no device and never
 * contends with an APK run.
 */
@RestController
@RequestMapping("/api/web")
public class WebTestController {

    private final WebScanRunner runner;
    private final WebScanSessionStore store;
    private final WebReportService reportService;
    private final WebBrowserService browser;
    private final AtfProperties atf;

    public WebTestController(WebScanRunner runner, WebScanSessionStore store,
                            WebReportService reportService, WebBrowserService browser, AtfProperties atf) {
        this.runner = runner;
        this.store = store;
        this.reportService = reportService;
        this.browser = browser;
        this.atf = atf;
    }

    public record ScanRequest(String url) { }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> scan(@RequestBody ScanRequest req) {
        String normalized = normalizeUrl(req == null ? null : req.url());
        String id = UUID.randomUUID().toString();
        WebScanSession session = new WebScanSession(id, normalized);
        store.save(session);
        runner.run(session);
        return ResponseEntity.ok(Map.of("id", id, "url", normalized, "state", session.getState().name()));
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return store.all().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("url", s.getUrl());
            m.put("state", s.getState().name());
            m.put("percent", s.getPercent());
            m.put("createdAtMillis", s.getCreatedAtMillis());
            m.put("finishedAtMillis", s.getFinishedAtMillis());
            m.put("pagesAnalyzed", s.getPagesAnalyzed());
            m.put("totalIssues", s.getSummary() == null ? 0 : s.getSummary().getTotalIssues());
            m.put("overallScore", s.getSummary() == null ? null : s.getSummary().getOverallScore());
            // Per-severity tally so the Dashboard can fold web scans into its aggregate
            // "Findings by Severity" / "Critical + High" / "Bugs Found" metrics.
            m.put("severity", s.getSummary() == null ? Map.of() : s.getSummary().getCountsBySeverity());
            return m;
        }).toList();
    }

    @GetMapping("/sessions/{id}")
    public WebScanSession getSession(@PathVariable("id") String id) {
        WebScanSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scan not found.");
        return s;
    }

    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable("id") String id) {
        WebScanSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scan not found.");
        if (!s.isTerminal()) {
            s.requestStop();
            s.log("Stop requested by user — halting after the current step and saving results.");
        }
        return ResponseEntity.ok(Map.of("id", id, "state", s.getState().name()));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/sessions/{id}/report.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable("id") String id) {
        WebScanSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scan not found.");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(reportService.render(s));
    }

    @GetMapping(value = "/sessions/{id}/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportPdf(@PathVariable("id") String id) {
        WebScanSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scan not found.");
        byte[] pdf = browser.renderPdf(reportService.render(s));
        if (pdf == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PDF export needs the browser engine (Chromium), which isn't available. Use the HTML report instead.");
        }
        String host = "report";
        try { host = URI.create(s.getUrl()).getHost(); } catch (Exception ignored) {}
        String filename = "website-qa-report-" + (host == null ? "site" : host.replaceAll("[^a-zA-Z0-9.-]", "_")) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @GetMapping("/sessions/{id}/artifacts/{file}")
    public ResponseEntity<byte[]> artifact(@PathVariable("id") String id, @PathVariable("file") String file) {
        WebScanSession s = store.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            Path base = Path.of(atf.getWorkDir(), "web-" + id).toAbsolutePath().normalize();
            Path target = base.resolve(file).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target)) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = Files.readAllBytes(target);
            MediaType ct = file.toLowerCase(Locale.ROOT).endsWith(".png")
                    ? MediaType.IMAGE_PNG : MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok().contentType(ct).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping(value = "/sessions/{id}/live-screen", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> liveScreen(@PathVariable("id") String id) {
        WebScanSession s = store.get(id);
        if (s == null || s.getScreenshot() == null) return ResponseEntity.noContent().build();
        try {
            Path base = Path.of(atf.getWorkDir(), "web-" + id).toAbsolutePath().normalize();
            // session.screenshot is stored as "artifacts/shot-N.png"; take the file name only.
            String fileName = s.getScreenshot().substring(s.getScreenshot().lastIndexOf('/') + 1);
            Path target = base.resolve(fileName).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target)) return ResponseEntity.noContent().build();
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(target));
        } catch (Exception e) {
            return ResponseEntity.noContent().build();
        }
    }

    /** Validate + normalise the submitted URL. Adds https:// when the scheme is omitted. */
    private static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please enter a website URL.");
        }
        String u = raw.trim();
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        try {
            URI uri = URI.create(u);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("no host");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("bad scheme");
            }
            return u;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That doesn't look like a valid URL: " + raw);
        }
    }
}
