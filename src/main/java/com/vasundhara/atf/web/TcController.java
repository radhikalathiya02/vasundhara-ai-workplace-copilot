package com.vasundhara.atf.web;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.engine.ExecutionLockService;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.testcase.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST layer for the Test Case Execution module.
 *
 * <p>All endpoints are under {@code /api/tc}.
 */
@RestController
@RequestMapping("/api/tc")
public class TcController {

    private static final Logger log = LoggerFactory.getLogger(TcController.class);

    private final TcParser           parser;
    private final TcRunner           runner;
    private final TcSessionStore     store;
    private final AdbClient          adb;
    private final AtfProperties      props;
    private final ApkAnalyzer        apkAnalyzer;
    private final ExecutionLockService execLock;

    public TcController(TcParser parser, TcRunner runner,
                        TcSessionStore store, AdbClient adb,
                        AtfProperties props, ApkAnalyzer apkAnalyzer,
                        ExecutionLockService execLock) {
        this.parser      = parser;
        this.runner      = runner;
        this.store       = store;
        this.adb         = adb;
        this.props       = props;
        this.apkAnalyzer = apkAnalyzer;
        this.execLock    = execLock;
    }

    // ── execute ───────────────────────────────────────────────────────────────

    /**
     * POST /api/tc/execute
     *
     * Accepts:
     *   - apk   (optional MultipartFile) — if not supplied, packageName must match installed app
     *   - sheet (required MultipartFile) — .xlsx / .xls / .csv
     *   - packageName (optional) — override; auto-detected from APK if omitted
     *   - serial (optional) — ADB serial; defaults to props.getDeviceSerial()
     */
    @PostMapping(value = "/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> execute(
            @RequestParam(value = "apk",         required = false) MultipartFile apkFile,
            @RequestParam("sheet") MultipartFile sheetFile,
            @RequestParam(value = "packageName", required = false, defaultValue = "") String packageName,
            @RequestParam(value = "serial",      required = false, defaultValue = "") String serialParam) {

        if (sheetFile == null || sheetFile.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test case sheet is required.");

        String serial = serialParam.isBlank() ? props.getDeviceSerial() : serialParam;
        if (serial == null || serial.isBlank()) {
            List<String> online = adb.onlineDevices();
            if (online.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No Android device connected. Connect a device via USB or wireless ADB and try again.");
            serial = online.get(0);
        }

        // ── save files ────────────────────────────────────────────────────────
        // getAbsoluteFile() resolves relative paths against user.dir (the project root),
        // not against Tomcat's work directory — required for MultipartFile I/O to work.
        File uploadsRoot = new File(props.getUploadDir()).getAbsoluteFile();
        uploadsRoot.mkdirs();
        log.info("TC execute: uploadsRoot={} serial={}", uploadsRoot, serial);

        String apkName = null;
        if (apkFile != null && !apkFile.isEmpty()) {
            apkName = "tc_" + System.currentTimeMillis() + "_" + sanitize(apkFile.getOriginalFilename());
            File apkDest = new File(uploadsRoot, apkName);
            try (InputStream in = apkFile.getInputStream()) {
                Files.copy(in, apkDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("TC execute: APK saved → {}", apkDest);
            } catch (Exception e) {
                log.error("TC execute: Failed to save APK", e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save APK: " + e.getMessage());
            }
        }

        String sheetName = "tc_" + System.currentTimeMillis() + "_" + sanitize(sheetFile.getOriginalFilename());
        File sheetSaved;
        try {
            sheetSaved = new File(uploadsRoot, sheetName);
            try (InputStream in = sheetFile.getInputStream()) {
                Files.copy(in, sheetSaved.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("TC execute: sheet saved → {}", sheetSaved);
        } catch (Exception e) {
            log.error("TC execute: Failed to save sheet", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save sheet: " + e.getMessage());
        }

        // ── analyse APK (one call reused for package name + sheet validation) ──
        ApkInfo apkInfo = null;
        if (apkName != null) {
            try {
                apkInfo = apkAnalyzer.analyze(new File(uploadsRoot, apkName));
                log.info("TC execute: APK analysed — label='{}' pkg='{}'",
                        apkInfo.getApplicationLabel(), apkInfo.getPackageName());
            } catch (Exception e) {
                log.warn("TC execute: APK analysis failed: {}", e.getMessage());
            }
        }

        // ── resolve package name ──────────────────────────────────────────────
        String pkg = packageName.isBlank() ? "" : packageName;
        if ((pkg == null || pkg.isBlank()) && apkInfo != null) {
            pkg = apkInfo.getPackageName();
            if (pkg != null && !pkg.isBlank())
                log.info("TC execute: detected packageName='{}' from APK", pkg);
        }
        if (pkg == null || pkg.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Package name not found. Provide packageName parameter or upload an APK.");

        // ── parse test cases ──────────────────────────────────────────────────
        List<TcItem> items;
        try {
            items = parser.parse(sheetSaved);
            parser.analyze(items); // duplicate + quality warnings
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse sheet: " + e.getMessage());
        }

        if (items.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No test cases found in the sheet. Check column headers (TC ID, Step, Expected Result).");

        // ── validate APK ↔ sheet match ────────────────────────────────────────
        if (apkInfo != null) {
            List<String> hints = parser.extractAppHints(sheetSaved, sheetFile.getOriginalFilename());
            MatchResult mr = matchApkToSheet(apkInfo, hints);
            if (!mr.matched()) {
                log.warn("TC execute: APK–sheet mismatch. APK='{}' ({}), hints={}",
                        apkInfo.getApplicationLabel(), apkInfo.getPackageName(), hints);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status",           422);
                err.put("error",            "Unprocessable Entity");
                err.put("validationCode",   "APK_SHEET_MISMATCH");
                err.put("message",          mr.message());
                err.put("apkAppName",       nvl(apkInfo.getApplicationLabel()));
                err.put("packageName",      pkg);
                err.put("sheetHints",       hints);
                return ResponseEntity.unprocessableEntity().body(err);
            }
            log.info("TC execute: APK–sheet match OK ({})", mr.message());
        }

        // ── device screen size ────────────────────────────────────────────────
        int[] wh = getScreenSize(serial);

        // ── create session ────────────────────────────────────────────────────
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TcSession session = new TcSession(sessionId);
        session.setApkFileName(apkName);
        session.setSheetFileName(sheetName);
        session.setPackageName(pkg);
        session.setTestCases(items);
        store.save(session);

        // ── launch async ──────────────────────────────────────────────────────
        if (!execLock.tryAcquire("Test Case Execution", sessionId)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        runner.run(session, serial, wh[0], wh[1]);

        // ── quick quality summary ─────────────────────────────────────────────
        long warnings  = items.stream().mapToLong(t -> t.getWarnings().size()).sum();
        long dupes     = items.stream().filter(TcItem::isDuplicate).count();
        long ambiguous = items.stream().filter(t -> t.getWarnings().stream()
                .anyMatch(w -> w.contains("vague") || w.contains("expected result"))).count();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId",    sessionId);
        resp.put("totalCases",   items.size());
        resp.put("duplicates",   dupes);
        resp.put("warnings",     warnings);
        resp.put("ambiguous",    ambiguous);
        resp.put("packageName",  pkg);
        resp.put("serial",       serial);
        return ResponseEntity.ok(resp);
    }

    // ── session status ─────────────────────────────────────────────────────────

    @GetMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("id") String id) {
        TcSession s = require(id);
        return ResponseEntity.ok(sessionView(s));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(store.all().stream().map(this::sessionView).toList());
    }

    // ── control ───────────────────────────────────────────────────────────────

    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable("id") String id) {
        TcSession s = require(id);
        if (s.getState() == TcSession.State.RUNNING || s.getState() == TcSession.State.PAUSED) {
            s.requestStop();
            s.addLog("Stop requested by user.");
        }
        return ResponseEntity.ok(Map.of("id", id, "state", s.getState().name()));
    }

    @PostMapping("/sessions/{id}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable("id") String id) {
        TcSession s = require(id);
        if (s.getState() == TcSession.State.RUNNING) {
            s.requestPause();
            s.addLog("Pause requested by user.");
        }
        return ResponseEntity.ok(Map.of("id", id, "state", s.getState().name()));
    }

    @PostMapping("/sessions/{id}/resume")
    public ResponseEntity<Map<String, String>> resume(@PathVariable("id") String id) {
        TcSession s = require(id);
        if (s.getState() == TcSession.State.PAUSED) {
            s.requestResume();
            s.addLog("Resumed by user.");
        }
        return ResponseEntity.ok(Map.of("id", id, "state", s.getState().name()));
    }

    @PostMapping("/sessions/{id}/rerun-failed")
    public ResponseEntity<Map<String, Object>> rerunFailed(
            @PathVariable("id") String id,
            @RequestParam(value = "serial", required = false, defaultValue = "") String serialParam) {
        TcSession original = require(id);
        if (original.getState() != TcSession.State.COMPLETED
                && original.getState() != TcSession.State.STOPPED)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Session must be COMPLETED or STOPPED before re-running failed cases.");

        List<String> failedIds = original.getResults().stream()
                .filter(r -> "FAIL".equals(r.getStatus()) || "BLOCKED".equals(r.getStatus()))
                .map(TcItemResult::getTcId).toList();

        if (failedIds.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No FAIL or BLOCKED test cases to re-run.");

        List<TcItem> failedItems = original.getTestCases().stream()
                .filter(tc -> failedIds.contains(tc.getId())).toList();

        String serial = serialParam.isBlank() ? props.getDeviceSerial() : serialParam;
        if (serial == null || serial.isBlank()) {
            List<String> online = adb.onlineDevices();
            if (online.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No Android device connected. Connect a device via USB or wireless ADB and try again.");
            serial = online.get(0);
        }
        int[] wh = getScreenSize(serial);

        String newId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TcSession rerunSession = new TcSession(newId);
        rerunSession.setApkFileName(original.getApkFileName());
        rerunSession.setSheetFileName(original.getSheetFileName());
        rerunSession.setPackageName(original.getPackageName());
        rerunSession.setTestCases(new ArrayList<>(failedItems));
        store.save(rerunSession);

        if (!execLock.tryAcquire("Test Case Execution", newId)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        runner.rerunFailed(rerunSession, failedItems, serial, wh[0], wh[1]);

        return ResponseEntity.ok(Map.of(
                "rerunSessionId", newId,
                "rerunCount",     failedItems.size()));
    }

    // ── full report (JSON) ────────────────────────────────────────────────────

    @GetMapping("/sessions/{id}/report")
    public ResponseEntity<Map<String, Object>> report(@PathVariable("id") String id) {
        TcSession s = require(id);
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("session",   sessionView(s));
        rep.put("results",   s.getResults());
        rep.put("testCases", s.getTestCases());
        rep.put("logs",      s.getLogs());

        // Module-wise breakdown
        Map<String, Map<String, Long>> moduleBreakdown = s.getResults().stream()
                .collect(Collectors.groupingBy(
                        r -> r.getModule() != null && !r.getModule().isBlank() ? r.getModule() : "Unknown",
                        Collectors.groupingBy(TcItemResult::getStatus, Collectors.counting())));
        rep.put("moduleBreakdown", moduleBreakdown);

        // Feature-wise breakdown
        Map<String, Map<String, Long>> featureBreakdown = s.getResults().stream()
                .collect(Collectors.groupingBy(
                        r -> r.getFeature() != null && !r.getFeature().isBlank() ? r.getFeature() : "Unknown",
                        Collectors.groupingBy(TcItemResult::getStatus, Collectors.counting())));
        rep.put("featureBreakdown", featureBreakdown);

        return ResponseEntity.ok(rep);
    }

    // ── HTML export ───────────────────────────────────────────────────────────

    @GetMapping("/sessions/{id}/export/html")
    public ResponseEntity<String> exportHtml(@PathVariable("id") String id) {
        TcSession s = require(id);
        String html = buildHtmlReport(s);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "attachment; filename=\"tc-report-" + id + ".html\"")
                .body(html);
    }

    // ── Test Case Testing Bug Report (HTML) ───────────────────────────────────

    /**
     * GET /api/tc/sessions/{id}/bug-report/html
     *
     * <p>Additive endpoint — a developer-facing bug report covering ONLY the test cases whose
     * actual behaviour did not match the sheet's Expected Result (FAIL/BLOCKED). Each entry
     * carries TC ID, Module, Scenario, Expected vs Actual, an issue description, derived
     * Severity, the sheet's Priority, the failure screenshot (embedded, so the file is
     * self-contained when shared with a developer) and full steps to reproduce.
     */
    @GetMapping("/sessions/{id}/bug-report/html")
    public ResponseEntity<String> bugReportHtml(@PathVariable("id") String id) {
        TcSession s = require(id);
        String html = buildBugReportHtml(s);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "attachment; filename=\"tc-bug-report-" + id + ".html\"")
                .body(html);
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    @GetMapping("/sessions/{id}/export/csv")
    public ResponseEntity<String> exportCsv(@PathVariable("id") String id) {
        TcSession s = require(id);
        StringBuilder csv = new StringBuilder();
        csv.append("TC ID,TC Name,Module,Feature,Priority,Status,Steps Passed,Steps Failed,Duration (ms),AI Notes,Crash\n");
        for (TcItemResult r : s.getResults()) {
            csv.append(q(r.getTcId())).append(",")
               .append(q(r.getTcName())).append(",")
               .append(q(r.getModule())).append(",")
               .append(q(r.getFeature())).append(",")
               .append(q(r.getPriority())).append(",")
               .append(q(r.getStatus())).append(",")
               .append(r.getPassedSteps()).append(",")
               .append(r.getFailedSteps()).append(",")
               .append(r.getDurationMs()).append(",")
               .append(q(r.getAiNotes())).append(",")
               .append(q(r.getCrashLog() != null ? "YES" : "")).append("\n");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"tc-report-" + id + ".csv\"")
                .body(csv.toString());
    }

    // ── screenshot fetch ──────────────────────────────────────────────────────

    @GetMapping("/screenshot")
    public ResponseEntity<byte[]> screenshot(@RequestParam("path") String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TcSession require(String id) {
        TcSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + id);
        return s;
    }

    private Map<String, Object> sessionView(TcSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           s.getId());
        m.put("state",        s.getState().name());
        m.put("packageName",  s.getPackageName());
        m.put("apkFileName",  s.getApkFileName());
        m.put("sheetFileName",s.getSheetFileName());
        m.put("totalCases",   s.getTotalCases());
        m.put("executed",     s.getExecuted());
        m.put("currentIndex", s.getCurrentIndex());
        m.put("passed",       s.getPassed());
        m.put("failed",       s.getFailed());
        m.put("blocked",      s.getBlocked());
        m.put("skipped",      s.getSkipped());
        m.put("notExecuted",  s.getNotExecuted());
        m.put("passPct",      String.format("%.1f", s.getPassPct()));
        m.put("etaMs",        s.getEtaMs());
        m.put("startTime",    s.getStartTime());
        m.put("finishedAt",   s.getFinishedAt());
        m.put("error",        s.getError());
        m.put("logs",         s.getLogs());
        // Include warning count from test cases
        long warns = s.getTestCases().stream().mapToLong(tc -> tc.getWarnings().size()).sum();
        m.put("warnings",     warns);
        // Additive live-progress detail for the execution page: which device, which case, which
        // step — and the per-case results the results table/bug list renders. Existing consumers
        // that ignore these keys behave exactly as before.
        m.put("serial",        s.getSerial());
        m.put("currentTcId",   s.getCurrentTcId());
        m.put("currentTcName", s.getCurrentTcName());
        m.put("currentStep",   s.getCurrentStep());
        m.put("results",       s.getResults());
        return m;
    }

    private int[] getScreenSize(String serial) {
        try {
            int[] size = adb.screenSize(serial);
            if (size[0] > 0 && size[1] > 0) return size;
        } catch (Exception ignored) {}
        return new int[]{1080, 1920};
    }

    // ── APK ↔ sheet matching ──────────────────────────────────────────────────

    private record MatchResult(boolean matched, String message) {}

    /**
     * Normalized strings from both APK and sheet are compared.
     * Rules:
     * <ul>
     *   <li>Normalize = lowercase + keep only [a-z0-9].</li>
     *   <li>Skip any candidate shorter than {@value MIN_MATCH_LEN} chars — too short to be distinctive.</li>
     *   <li>Skip generic tokens (e.g. "test", "app", "sheet") that appear in virtually every sheet.</li>
     *   <li>Match if any non-generic sheetHint *contains* or *is contained by* any APK identifier.</li>
     *   <li>If no non-generic hints are available → PASS (cannot determine mismatch).</li>
     * </ul>
     */
    private static final int MIN_MATCH_LEN = 4;

    private static final Set<String> GENERIC_TOKENS = Set.of(
            "test", "tests", "testcase", "testcases", "testcasedoc", "testcasedocument",
            "automation", "autotest", "qa", "qatests",
            "sheet", "sheet1", "sheet2", "workbook", "untitled",
            "document", "template", "sample", "demo",
            "regression", "functional", "manual", "smoke",
            "cases", "scenarios", "scripts", "script",
            "android", "mobile", "app", "application", "apk",
            "module", "feature", "project"
    );

    private static final Set<String> GENERIC_PKG_SEGMENTS = Set.of(
            "app", "android", "mobile", "main", "core", "lib",
            "com", "org", "net", "io", "in"
    );

    private MatchResult matchApkToSheet(ApkInfo apkInfo, List<String> rawHints) {
        // Build APK identifier set
        Set<String> apkIds = new LinkedHashSet<>();
        String label = apkInfo.getApplicationLabel();
        String pkg   = apkInfo.getPackageName();

        if (label != null && !label.isBlank()) apkIds.add(norm(label));

        if (pkg != null && !pkg.isBlank()) {
            String[] segs = pkg.split("\\.");
            // last segment  (most specific)
            if (segs.length > 0) apkIds.add(norm(segs[segs.length - 1]));
            // second-to-last (e.g., "acme" from com.acme.someapp)
            if (segs.length > 1) {
                String s2 = norm(segs[segs.length - 2]);
                if (!GENERIC_PKG_SEGMENTS.contains(s2)) apkIds.add(s2);
            }
        }
        apkIds.removeIf(id -> id.length() < MIN_MATCH_LEN || GENERIC_TOKENS.contains(id));

        if (apkIds.isEmpty()) {
            return new MatchResult(true, "APK identifiers too generic — skipping validation");
        }

        // Evaluate each hint
        boolean hasSignalHint = false;
        for (String raw : rawHints) {
            String h = norm(raw);
            if (h.length() < MIN_MATCH_LEN || GENERIC_TOKENS.contains(h)) continue;
            hasSignalHint = true;

            for (String id : apkIds) {
                if (h.contains(id) || id.contains(h)) {
                    return new MatchResult(true,
                            "Hint '" + raw + "' matched APK identifier '" + id + "'");
                }
            }
        }

        if (!hasSignalHint) {
            return new MatchResult(true, "All sheet hints are generic — skipping validation");
        }

        // Meaningful hints present but none matched
        String displayName = (label != null && !label.isBlank()) ? label : pkg;
        List<String> nonGenericHints = rawHints.stream()
                .filter(r -> norm(r).length() >= MIN_MATCH_LEN && !GENERIC_TOKENS.contains(norm(r)))
                .distinct().collect(Collectors.toList());
        return new MatchResult(false,
                "The uploaded test case sheet does not appear to belong to \""
                + displayName + "\". "
                + "Sheet hints detected: [" + String.join(", ", nonGenericHints) + "]. "
                + "Please upload the correct test case sheet for this application, "
                + "or enter the package name manually to skip this check.");
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String sanitize(String name) {
        if (name == null) return "upload";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String q(String v) {
        if (v == null) return "";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    // ── HTML report builder ───────────────────────────────────────────────────

    private String buildHtmlReport(TcSession s) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>")
          .append("<title>TC Report — ").append(s.getId()).append("</title>")
          .append("<style>")
          .append("body{font-family:sans-serif;margin:24px;background:#f5f5f5;color:#222}")
          .append("h1{color:#1a1a2e}table{width:100%;border-collapse:collapse;background:#fff}")
          .append("th{background:#1a1a2e;color:#fff;padding:8px;text-align:left}")
          .append("td{padding:8px;border-bottom:1px solid #ddd}")
          .append(".PASS{color:#22c55e;font-weight:bold}.FAIL{color:#ef4444;font-weight:bold}")
          .append(".BLOCKED{color:#f97316;font-weight:bold}.SKIPPED{color:#6b7280;font-weight:bold}")
          .append(".NOT_EXECUTED{color:#9ca3af}.summary{display:flex;gap:24px;margin:16px 0}")
          .append(".card{background:#fff;border-radius:8px;padding:16px;min-width:100px;text-align:center}")
          .append(".card h2{font-size:2rem;margin:0}.card p{margin:4px 0;color:#666}")
          .append("</style></head><body>")
          .append("<h1>Test Case Execution Report</h1>")
          .append("<p>Session: <b>").append(s.getId()).append("</b> | Package: <b>")
          .append(s.getPackageName()).append("</b> | Status: <b>").append(s.getState()).append("</b></p>")
          .append("<div class='summary'>")
          .append(card(s.getTotalCases(), "Total"))
          .append(card(s.getPassed(),     "Passed",  "#22c55e"))
          .append(card(s.getFailed(),     "Failed",  "#ef4444"))
          .append(card(s.getBlocked(),    "Blocked", "#f97316"))
          .append(card(s.getSkipped(),    "Skipped", "#6b7280"))
          .append(card(s.getNotExecuted(),"Not Run", "#9ca3af"))
          .append(String.format("<div class='card'><h2>%.1f%%</h2><p>Pass Rate</p></div>", s.getPassPct()))
          .append("</div>")
          .append("<table><thead><tr>")
          .append("<th>TC ID</th><th>Name</th><th>Module</th><th>Feature</th><th>Priority</th>")
          .append("<th>Status</th><th>Steps P/F</th><th>Duration</th><th>Notes</th></tr></thead><tbody>");

        for (TcItemResult r : s.getResults()) {
            sb.append("<tr>")
              .append("<td>").append(r.getTcId()).append("</td>")
              .append("<td>").append(r.getTcName()).append("</td>")
              .append("<td>").append(nvl(r.getModule())).append("</td>")
              .append("<td>").append(nvl(r.getFeature())).append("</td>")
              .append("<td>").append(nvl(r.getPriority())).append("</td>")
              .append("<td class='").append(r.getStatus()).append("'>").append(r.getStatus()).append("</td>")
              .append("<td>").append(r.getPassedSteps()).append("/").append(r.getFailedSteps()).append("</td>")
              .append("<td>").append(r.getDurationMs()).append(" ms</td>")
              .append("<td>").append(nvl(r.getAiNotes())).append("</td>")
              .append("</tr>");
        }
        sb.append("</tbody></table></body></html>");
        return sb.toString();
    }

    private String card(int val, String label) { return card(val, label, "#1a1a2e"); }
    private String card(int val, String label, String color) {
        return "<div class='card'><h2 style='color:" + color + "'>" + val + "</h2><p>" + label + "</p></div>";
    }
    private String nvl(String v) { return v != null ? v : ""; }

    // ── Test Case Testing Bug Report builder ─────────────────────────────────

    /**
     * Severity is DERIVED from observed evidence, not invented: a crash during the case is
     * CRITICAL; a BLOCKED case (couldn't proceed) is HIGH; a plain expected≠actual mismatch
     * inherits the sheet's own Priority as its severity (HIGH priority case failing = HIGH).
     */
    private static String deriveSeverity(TcItemResult r) {
        if (r.getCrashLog() != null && !r.getCrashLog().isBlank()) return "CRITICAL";
        if ("BLOCKED".equals(r.getStatus())) return "HIGH";
        String p = r.getPriority() == null ? "" : r.getPriority().toUpperCase();
        return p.startsWith("H") ? "HIGH" : p.startsWith("L") ? "LOW" : "MEDIUM";
    }

    private String buildBugReportHtml(TcSession s) {
        List<TcItemResult> bugs = s.getResults().stream()
                .filter(r -> "FAIL".equals(r.getStatus()) || "BLOCKED".equals(r.getStatus()))
                .toList();

        // tcId → parsed TcItem, for full steps-to-reproduce (results only hold executed steps).
        Map<String, TcItem> byId = new LinkedHashMap<>();
        for (TcItem tc : s.getTestCases()) byId.putIfAbsent(tc.getId(), tc);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>")
          .append("<title>Test Case Testing Bug Report — ").append(esc(s.getId())).append("</title>")
          .append("<style>")
          .append("body{font-family:sans-serif;margin:24px;background:#f5f5f5;color:#222;line-height:1.5}")
          .append("h1{color:#1a1a2e}h2{margin:0 0 2px;font-size:1.05rem}")
          .append(".bug{background:#fff;border:1px solid #ddd;border-left:5px solid #ef4444;border-radius:8px;padding:16px 18px;margin-bottom:18px}")
          .append(".bug.CRITICAL{border-left-color:#b91c1c}.bug.HIGH{border-left-color:#ef4444}")
          .append(".bug.MEDIUM{border-left-color:#f97316}.bug.LOW{border-left-color:#eab308}")
          .append(".meta{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0 12px}")
          .append(".chip{border-radius:4px;padding:3px 10px;font-size:12px;font-weight:700;background:#eef;color:#1a1a2e}")
          .append(".chip.sev-CRITICAL{background:#b91c1c;color:#fff}.chip.sev-HIGH{background:#ef4444;color:#fff}")
          .append(".chip.sev-MEDIUM{background:#f97316;color:#fff}.chip.sev-LOW{background:#eab308;color:#222}")
          .append(".kv{margin:6px 0}.kv b{display:inline-block;min-width:130px;vertical-align:top;color:#555}")
          .append(".kv span{display:inline-block;max-width:75%;vertical-align:top;white-space:pre-wrap}")
          .append(".exp{color:#166534}.act{color:#b91c1c}")
          .append("ol{margin:6px 0 0 18px;padding:0}ol li{margin:3px 0}")
          .append("img.shot{max-width:260px;border:1px solid #ccc;border-radius:6px;margin-top:10px}")
          .append("pre{background:#1a1a2e;color:#e2e8f0;padding:10px;border-radius:6px;overflow:auto;font-size:12px;max-height:260px}")
          .append(".ok{background:#fff;border:1px solid #ddd;border-left:5px solid #22c55e;border-radius:8px;padding:18px;color:#166534;font-weight:600}")
          .append("</style></head><body>")
          .append("<h1>Test Case Testing Bug Report</h1>")
          .append("<p>Session: <b>").append(esc(s.getId()))
          .append("</b> | Package: <b>").append(esc(nvl(s.getPackageName())))
          .append("</b> | Sheet: <b>").append(esc(nvl(s.getSheetFileName())))
          .append("</b> | Executed: <b>").append(s.getExecuted()).append("/").append(s.getTotalCases())
          .append("</b> | Bugs: <b style='color:#ef4444'>").append(bugs.size()).append("</b></p>");

        if (bugs.isEmpty()) {
            sb.append("<div class='ok'>No bugs — every executed test case's actual behaviour matched its Expected Result.</div>");
            sb.append("</body></html>");
            return sb.toString();
        }

        for (TcItemResult r : bugs) {
            // The first failing/blocked step carries the concrete expected-vs-actual evidence.
            TcStepResult failStep = r.getStepResults().stream()
                    .filter(st -> st.failed() || st.blocked()).findFirst().orElse(null);
            String severity = deriveSeverity(r);
            String expected = failStep != null && failStep.expectedResult() != null && !failStep.expectedResult().isBlank()
                    ? failStep.expectedResult() : "(see test case sheet)";
            String actual = failStep != null && failStep.actualResult() != null && !failStep.actualResult().isBlank()
                    ? failStep.actualResult() : ("Test case " + r.getStatus() + " — actual behaviour did not match the expected result.");
            String issueDesc = firstNonBlank(
                    failStep != null ? failStep.notes() : null,
                    r.getAiNotes(),
                    (r.getCrashLog() != null && !r.getCrashLog().isBlank())
                            ? "The application crashed while executing this test case." : null,
                    "BLOCKED".equals(r.getStatus())
                            ? "Execution could not proceed past step " + (failStep != null ? failStep.num() : "?")
                              + " — the expected screen/control was not reachable." : null,
                    "Actual behaviour did not match the Expected Result at step " + (failStep != null ? failStep.num() : "?") + ".");

            sb.append("<div class='bug ").append(severity).append("'>")
              .append("<h2>").append(esc(nvl(r.getTcId()))).append(" — ").append(esc(nvl(r.getTcName()))).append("</h2>")
              .append("<div class='meta'>")
              .append("<span class='chip sev-").append(severity).append("'>Severity: ").append(severity).append("</span>")
              .append("<span class='chip'>Priority: ").append(esc(nvl(r.getPriority()))).append("</span>")
              .append("<span class='chip'>Status: ").append(esc(r.getStatus())).append("</span>")
              .append(r.getModule() != null && !r.getModule().isBlank()
                      ? "<span class='chip'>Module: " + esc(r.getModule()) + "</span>" : "")
              .append("</div>")
              .append("<div class='kv'><b>Scenario</b><span>").append(esc(nvl(r.getTcName()))).append("</span></div>")
              .append("<div class='kv'><b>Expected Result</b><span class='exp'>").append(esc(expected)).append("</span></div>")
              .append("<div class='kv'><b>Actual Result</b><span class='act'>").append(esc(actual)).append("</span></div>")
              .append("<div class='kv'><b>Issue Description</b><span>").append(esc(issueDesc)).append("</span></div>");

            // Steps to Reproduce — the sheet's own step list (preconditions first when present).
            sb.append("<div class='kv'><b>Steps to Reproduce</b><span><ol>");
            TcItem tc = byId.get(r.getTcId());
            if (tc != null && !tc.getSteps().isEmpty()) {
                if (tc.getPreconditions() != null && !tc.getPreconditions().isBlank())
                    sb.append("<li><i>Precondition:</i> ").append(esc(tc.getPreconditions())).append("</li>");
                for (TcStep st : tc.getSteps()) sb.append("<li>").append(esc(st.description())).append("</li>");
            } else {
                for (TcStepResult st : r.getStepResults()) sb.append("<li>").append(esc(nvl(st.description()))).append("</li>");
            }
            sb.append("</ol></span></div>");

            // Failure screenshot — embedded base64 so the exported file stands alone.
            String shotPath = failStep != null && failStep.screenshotPath() != null && !failStep.screenshotPath().isBlank()
                    ? failStep.screenshotPath() : r.getScreenshotPath();
            String b64 = screenshotBase64(shotPath);
            if (b64 != null) sb.append("<img class='shot' alt='Failure screenshot' src='data:image/png;base64,").append(b64).append("'/>");

            if (r.getCrashLog() != null && !r.getCrashLog().isBlank())
                sb.append("<pre>").append(esc(r.getCrashLog())).append("</pre>");

            sb.append("</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Reads a PNG from disk as base64, or null if missing/unreadable/oversized (>4 MB). */
    private String screenshotBase64(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            File f = new File(path);
            if (!f.exists() || f.length() > 4L * 1024 * 1024) return null;
            return Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    /** Minimal HTML escaping for report fields sourced from sheet/device text. */
    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
