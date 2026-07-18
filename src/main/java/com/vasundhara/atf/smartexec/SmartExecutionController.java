package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.engine.ExecutionLockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the Smart Execution module. Entirely independent of the New Test
 * ({@code /api/runs}) controller/endpoints.
 *
 * <pre>
 * POST /api/smartexec/runs                    — upload APK + selected categories, start async run
 * GET  /api/smartexec/runs                    — list all sessions
 * GET  /api/smartexec/runs/{id}                — poll one session's live state
 * POST /api/smartexec/runs/{id}/stop           — request stop
 * GET  /api/smartexec/runs/{id}/report.html    — full report
 * GET  /api/smartexec/runs/{id}/functional-issues.html
 * GET  /api/smartexec/runs/{id}/ui-issues.html
 * GET  /api/smartexec/runs/{id}/crash-issues.html
 * GET  /api/smartexec/runs/{id}/admob-report.html
 * GET  /api/smartexec/runs/{id}/figma-report.html
 * GET  /api/smartexec/runs/{id}/security-report.html
 * GET  /api/smartexec/runs/{id}/performance-report.html
 * GET  /api/smartexec/runs/{id}/artifacts/{file} — screenshots/video evidence
 * </pre>
 */
@RestController
@RequestMapping("/api/smartexec")
public class SmartExecutionController {

    private final SmartSessionStore store;
    private final SmartExecutionRunner runner;
    private final AtfProperties props;
    private final ExecutionLockService execLock;
    private final AdbClient adb;
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> liveScreenCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SmartExecutionController(SmartSessionStore store, SmartExecutionRunner runner,
                                    AtfProperties props, ExecutionLockService execLock, AdbClient adb) {
        this.store = store;
        this.runner = runner;
        this.props = props;
        this.execLock = execLock;
        this.adb = adb;
    }

    /** Live device preview for the Smart Execution running page — plain adb screencap, independent
     *  of the Appium session driving the run, so polling it never interferes with execution. */
    @GetMapping(value = "/runs/{id}/live-screen", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> liveScreen(@PathVariable String id) {
        SmartSession s = need(id);
        String serial = s.getDeviceSerial();
        if (serial != null && !serial.isBlank()) {
            byte[] png = adb.screencapPng(serial);
            if (png != null && png.length > 100) {
                liveScreenCache.put(id, png);
                return ResponseEntity.ok().header("Cache-Control", "no-cache, no-store, must-revalidate").body(png);
            }
        }
        byte[] cached = liveScreenCache.get(id);
        if (cached != null) return ResponseEntity.ok().header("Cache-Control", "no-cache, no-store, must-revalidate").body(cached);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> start(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "deviceSerial", required = false) String deviceSerial,
            @RequestParam(value = "figmaUrl", required = false) String figmaUrl) throws Exception {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");

        List<String> selected = (categories == null || categories.isEmpty())
                ? SmartOrchestrator.CATEGORY_ORDER : categories.stream().filter(SmartOrchestrator.CATEGORY_ORDER::contains).toList();
        if (selected.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No valid category selected.");

        String id = UUID.randomUUID().toString();
        SmartSession session = new SmartSession(id);
        session.setApkFileName(original);
        session.setSelectedCategories(selected);
        if (deviceSerial != null && !deviceSerial.isBlank()) session.setDeviceSerial(deviceSerial.trim());
        if (figmaUrl != null && !figmaUrl.isBlank()) session.setFigmaUrl(figmaUrl.trim());
        session.addStep("Session created for: " + original);

        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("smartexec-" + id + ".apk");
        try (var in = file.getInputStream()) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
        session.addStep("APK saved (" + target.toFile().length() + " bytes).");

        store.save(session);
        if (!execLock.tryAcquire("Smart Execution", id)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait before starting a new one.");
        }
        runner.run(session, target.toFile());
        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    @GetMapping("/runs")
    public List<SmartSession> list() { return store.all(); }

    @GetMapping("/runs/{id}")
    public SmartSession get(@PathVariable String id) {
        SmartSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return s;
    }

    @PostMapping("/runs/{id}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable String id) {
        SmartSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        s.requestStop();
        s.addStep("Stop requested by user.");
        store.save(s);
        return ResponseEntity.ok(Map.of("id", id, "state", s.getState().name()));
    }

    @GetMapping(value = "/runs/{id}/functional-issues.html", produces = MediaType.TEXT_HTML_VALUE)
    public String functionalIssues(@PathVariable String id) { return SmartIssueReportBuilder.generate(need(id), SmartIssueReportBuilder.ReportType.FUNCTIONAL); }

    @GetMapping(value = "/runs/{id}/ui-issues.html", produces = MediaType.TEXT_HTML_VALUE)
    public String uiIssues(@PathVariable String id) { return SmartIssueReportBuilder.generate(need(id), SmartIssueReportBuilder.ReportType.UI); }

    @GetMapping(value = "/runs/{id}/crash-issues.html", produces = MediaType.TEXT_HTML_VALUE)
    public String crashIssues(@PathVariable String id) { return SmartIssueReportBuilder.generate(need(id), SmartIssueReportBuilder.ReportType.CRASH); }

    @GetMapping(value = "/runs/{id}/admob-report.html", produces = MediaType.TEXT_HTML_VALUE)
    public String admobReport(@PathVariable String id) { return SmartIssueReportBuilder.generate(need(id), SmartIssueReportBuilder.ReportType.ADS); }

    @GetMapping(value = "/runs/{id}/figma-report.html", produces = MediaType.TEXT_HTML_VALUE)
    public String figmaReport(@PathVariable String id) { return com.vasundhara.atf.smartexec.figma.FigmaReportBuilder.generate(need(id)); }

    @GetMapping(value = "/runs/{id}/security-report.html", produces = MediaType.TEXT_HTML_VALUE)
    public String securityReport(@PathVariable String id) { return com.vasundhara.atf.smartexec.security.SecurityReportBuilder.generate(need(id)); }

    @GetMapping(value = "/runs/{id}/performance-report.html", produces = MediaType.TEXT_HTML_VALUE)
    public String performanceReport(@PathVariable String id) { return com.vasundhara.atf.smartexec.performance.PerformanceReportBuilder.generate(need(id)); }

    @GetMapping("/runs/{id}/artifacts/{file}")
    public ResponseEntity<byte[]> artifact(@PathVariable String id, @PathVariable String file) throws Exception {
        SmartSession s = need(id);
        File f = new File(new File(props.getWorkDir(), "smartexec-" + s.getId()), "evidence/" + file);
        if (!f.isFile()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found.");
        String ct = file.endsWith(".mp4") ? "video/mp4" : "image/png";
        return ResponseEntity.ok().header("Content-Type", ct).body(Files.readAllBytes(f.toPath()));
    }

    private SmartSession need(String id) {
        SmartSession s = store.get(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        return s;
    }
}
