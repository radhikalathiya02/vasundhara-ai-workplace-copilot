package com.vasundhara.atf.web;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.engine.ExecutionLockService;
import com.vasundhara.atf.model.RemoteConfigFlag;
import com.vasundhara.atf.model.RemoteConfigSession;
import com.vasundhara.atf.model.RemoteConfigSession.State;
import com.vasundhara.atf.model.RemoteConfigTestResult;
import com.vasundhara.atf.remoteconfig.RemoteConfigService;
import com.vasundhara.atf.remoteconfig.RemoteConfigSessionStore;
import com.vasundhara.atf.remoteconfig.RemoteConfigTestRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the Remote Config validation workflow.
 *
 * <pre>
 * POST  /api/rc/fetch                 — create session, start async fetch
 * GET   /api/rc/sessions/{id}         — poll session state
 * POST  /api/rc/sessions/{id}/publish — push pending changes to Firebase
 * POST  /api/rc/sessions/{id}/test    — upload APK and run validation
 * GET   /api/rc/sessions/{id}/report.html — HTML validation report
 * </pre>
 */
@RestController
@RequestMapping("/api/rc")
public class RemoteConfigController {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RemoteConfigService rcService;
    private final RemoteConfigTestRunner rcTestRunner;
    private final RemoteConfigSessionStore store;
    private final AtfProperties props;
    private final ExecutionLockService execLock;

    public RemoteConfigController(RemoteConfigService rcService,
                                  RemoteConfigTestRunner rcTestRunner,
                                  RemoteConfigSessionStore store,
                                  AtfProperties props,
                                  ExecutionLockService execLock) {
        this.rcService = rcService;
        this.rcTestRunner = rcTestRunner;
        this.store = store;
        this.props = props;
        this.execLock = execLock;
    }

    // ---- Create session + start async fetch --------------------------------

    /**
     * Body: {@code { "projectId": "...", "serviceAccountJson": "..." }}
     * Creates a new session and immediately starts fetching the RC template.
     */
    @PostMapping("/fetch")
    public ResponseEntity<Map<String, String>> startFetch(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        String saJson = body.get("serviceAccountJson");
        if (projectId == null || projectId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required.");
        }
        if (saJson == null || saJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "serviceAccountJson is required.");
        }

        String id = UUID.randomUUID().toString();
        RemoteConfigSession session = new RemoteConfigSession(id);
        session.setProjectId(projectId);
        session.setServiceAccountJson(saJson);
        session.addLog("Session created for project: " + projectId);
        store.save(session);

        runFetchAsync(session);

        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    @Async("testRunExecutor")
    void runFetchAsync(RemoteConfigSession session) {
        try {
            session.setState(State.FETCHING);
            RemoteConfigService.FetchResult result = rcService.fetch(
                    session.getProjectId(), session.getServiceAccountJson(), session::addLog);
            session.setFlags(result.flags());
            session.setTemplateJson(result.templateJson());
            session.setEtag(result.etag());
            session.setState(State.FETCHED);
            session.addLog("Ready to edit. " + result.flags().size() + " flag(s) loaded.");
        } catch (Exception e) {
            session.setError(e.getMessage());
            session.setState(State.FAILED);
            session.addLog("Fetch failed: " + e.getMessage());
        }
    }

    // ---- Poll session state ------------------------------------------------

    @GetMapping("/sessions/{id}")
    public RemoteConfigSession getSession(@PathVariable("id") String id) {
        RemoteConfigSession session = store.get(id);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        return session;
    }

    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<Map<String, String>> stopSession(@PathVariable("id") String id) {
        RemoteConfigSession session = store.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        RemoteConfigSession.State s = session.getState();
        if (s == RemoteConfigSession.State.COMPLETED || s == RemoteConfigSession.State.FAILED
                || s == RemoteConfigSession.State.STOPPED)
            return ResponseEntity.ok(Map.of("id", id, "state", s.name()));
        session.requestStop();
        session.addLog("Stop requested by user — finishing current step and saving results.");
        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    // ---- Publish pending changes -------------------------------------------

    /**
     * Body: {@code { "changes": { "flagKey": "newValue", ... } }}
     */
    @PostMapping("/sessions/{id}/publish")
    public ResponseEntity<Map<String, String>> publish(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {

        RemoteConfigSession session = requireSession(id);

        Object rawChanges = body.get("changes");
        if (!(rawChanges instanceof Map<?, ?> rawMap)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Body must contain a 'changes' object.");
        }

        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            changes.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        session.setPendingChanges(changes);
        session.addLog("Pending changes recorded: " + changes.size() + " flag(s).");

        runPublishAsync(session);

        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    @Async("testRunExecutor")
    void runPublishAsync(RemoteConfigSession session) {
        try {
            session.setState(State.PUBLISHING);
            rcService.publish(
                    session.getProjectId(),
                    session.getServiceAccountJson(),
                    session.getTemplateJson(),
                    session.getPendingChanges(),
                    session.getEtag(),
                    session::addLog);
            session.setState(State.PUBLISHED);
            session.addLog("Published successfully. Ready to upload APK.");
        } catch (Exception e) {
            session.setError(e.getMessage());
            session.setState(State.FAILED);
            session.addLog("Publish failed: " + e.getMessage());
        }
    }

    // ---- Upload APK + start validation test --------------------------------

    @PostMapping(value = "/sessions/{id}/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> startTest(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "serial", required = false) String serial) throws Exception {

        RemoteConfigSession session = requireSession(id);

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        }
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");
        }

        session.setApkFileName(original);

        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("rc-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        session.addLog("APK saved: " + original + " (" + target.toFile().length() + " bytes)");
        if (!execLock.tryAcquire("Remote Config", id)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        rcTestRunner.run(session, target.toFile(), serial);

        return ResponseEntity.ok(Map.of("id", id, "state", session.getState().name()));
    }

    /**
     * Multi-device validation. For each requested serial we spawn an independent child session that
     * inherits the parent's fetched/published Remote Config (projectId, template, etag, flags) and
     * runs the validation test on that one device. The single-device {@code /test} flow above is
     * untouched. Returns {@code { devices: [ { serial, id } ] }} so the dashboard can poll each child.
     */
    @PostMapping(value = "/sessions/{id}/test-multi", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> startTestMulti(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("serials") String serialsCsv) throws Exception {

        RemoteConfigSession parent = requireSession(id);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APK uploaded.");
        }
        String original = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".apk")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apk");
        }
        List<String> serials = Arrays.stream(serialsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (serials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one device serial is required.");
        }

        // Persist the APK once; every child run reads the same file.
        Path uploadDir = Path.of(props.getUploadDir()).toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path target = uploadDir.resolve("rc-multi-" + id + ".apk");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        if (!execLock.tryAcquire("Remote Config", id)) {
            String who = execLock.getLockInfo().map(ExecutionLockService.LockInfo::module).orElse("another module");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A test execution is already running in the " + who + " module. Please stop or wait for the current execution to complete before starting a new one.");
        }
        List<Map<String, String>> devices = new ArrayList<>();
        for (String serial : serials) {
            String childId = UUID.randomUUID().toString();
            RemoteConfigSession child = new RemoteConfigSession(childId);
            // Inherit the resolved config context from the parent so the child can validate independently.
            child.setProjectId(parent.getProjectId());
            child.setServiceAccountJson(parent.getServiceAccountJson());
            child.setTemplateJson(parent.getTemplateJson());
            child.setEtag(parent.getEtag());
            child.setFlags(new ArrayList<>(parent.getFlags()));
            child.setPendingChanges(new LinkedHashMap<>(parent.getPendingChanges()));
            child.setApkFileName(original);
            child.setState(State.PUBLISHED);
            child.addLog("Multi-device child session for " + serial + " (parent " + id + ").");
            store.save(child);
            rcTestRunner.run(child, target.toFile(), serial);
            devices.add(Map.of("serial", serial, "id", childId));
        }
        return ResponseEntity.ok(Map.of("parent", id, "devices", devices));
    }

    // ---- HTML Report -------------------------------------------------------

    @GetMapping(value = "/sessions/{id}/report.html", produces = MediaType.TEXT_HTML_VALUE)
    public String report(@PathVariable("id") String id) {
        RemoteConfigSession session = requireSession(id);
        return generateHtml(session);
    }

    // ---- helpers -----------------------------------------------------------

    private RemoteConfigSession requireSession(String id) {
        RemoteConfigSession s = store.get(id);
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        return s;
    }

    private String generateHtml(RemoteConfigSession s) {
        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html><html lang='en'><head>")
          .append("<meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
          .append("<title>RC Report — ").append(esc(s.getProjectId())).append("</title>")
          .append("<style>").append(css()).append("</style>")
          .append("</head><body>");

        // Header
        sb.append("<header>")
          .append("<h1>Remote Config Validation Report</h1>")
          .append("<p class='sub'>")
          .append("Project: ").append(esc(s.getProjectId()))
          .append(" &middot; Session: ").append(esc(s.getId()));
        if (s.getPackageName() != null) {
            sb.append(" &middot; Package: ").append(esc(s.getPackageName()));
        }
        sb.append(" &middot; ")
          .append(FMT.format(Instant.ofEpochMilli(s.getCreatedAt())))
          .append("</p></header>");

        // Summary cards
        long passed = s.getTestResults().stream().filter(r -> "PASS".equals(r.getStatus())).count();
        long unknown = s.getTestResults().stream().filter(r -> "UNKNOWN".equals(r.getStatus())).count();
        long failed = s.getTestResults().stream().filter(r -> "FAIL".equals(r.getStatus())).count();

        sb.append("<section class='cards'>");
        card(sb, "State", s.getState().name());
        card(sb, "Flags Fetched", String.valueOf(s.getFlags().size()));
        card(sb, "Flags Modified", String.valueOf(s.getPendingChanges().size()));
        card(sb, "PASS", String.valueOf(passed));
        card(sb, "UNKNOWN", String.valueOf(unknown));
        card(sb, "FAIL", String.valueOf(failed));
        sb.append("</section>");

        if (s.getError() != null) {
            sb.append("<div class='err'>Error: ").append(esc(s.getError())).append("</div>");
        }

        // Fetched Remote Config flags
        if (!s.getFlags().isEmpty()) {
            sb.append("<section><h2>Fetched Remote Config Parameters</h2>")
              .append("<table><thead><tr>")
              .append("<th>Key</th><th>Default Value</th><th>Type</th><th>Description</th>")
              .append("</tr></thead><tbody>");
            for (RemoteConfigFlag flag : s.getFlags()) {
                sb.append("<tr>")
                  .append("<td>").append(esc(flag.getKey())).append("</td>")
                  .append("<td>").append(esc(flag.getDefaultValue())).append("</td>")
                  .append("<td>").append(esc(flag.getValueType())).append("</td>")
                  .append("<td>").append(esc(flag.getDescription())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></section>");
        }

        // Modified flags
        if (!s.getPendingChanges().isEmpty()) {
            sb.append("<section><h2>Modified Flags</h2>")
              .append("<table><thead><tr>")
              .append("<th>Key</th><th>New Value</th>")
              .append("</tr></thead><tbody>");
            for (Map.Entry<String, String> entry : s.getPendingChanges().entrySet()) {
                sb.append("<tr>")
                  .append("<td>").append(esc(entry.getKey())).append("</td>")
                  .append("<td>").append(esc(entry.getValue())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></section>");
        }

        // Validation results
        if (!s.getTestResults().isEmpty()) {
            sb.append("<section><h2>Validation Results</h2>")
              .append("<table><thead><tr>")
              .append("<th>Flag Key</th><th>Expected Value</th><th>Evidence</th>")
              .append("<th>Status</th><th>Detail</th>")
              .append("</tr></thead><tbody>");
            for (RemoteConfigTestResult r : s.getTestResults()) {
                String cls = switch (r.getStatus()) {
                    case "PASS" -> "pass";
                    case "FAIL" -> "fail";
                    default -> "unknown";
                };
                sb.append("<tr class='").append(cls).append("'>")
                  .append("<td>").append(esc(r.getFlagKey())).append("</td>")
                  .append("<td>").append(esc(r.getExpectedValue())).append("</td>")
                  .append("<td>").append(esc(r.getFoundEvidence())).append("</td>")
                  .append("<td><span class='badge ").append(cls).append("'>")
                  .append(esc(r.getStatus())).append("</span></td>")
                  .append("<td>").append(esc(r.getDetail())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></section>");
        }

        // Screenshots
        boolean hasShots = s.getTestResults().stream()
                .anyMatch(r -> r.getScreenshot() != null);
        if (hasShots) {
            sb.append("<section><h2>Screenshots</h2><div class='shots'>");
            s.getTestResults().stream()
                    .filter(r -> r.getScreenshot() != null)
                    .map(RemoteConfigTestResult::getScreenshot)
                    .distinct()
                    .forEach(shot -> sb.append("<figure>")
                            .append("<img src='").append(esc(shot))
                            .append("' alt='screenshot' loading='lazy'>")
                            .append("</figure>"));
            sb.append("</div></section>");
        }

        // Console log
        sb.append("<section><h2>Console Log</h2><pre class='log'>");
        for (String line : s.getLogs()) {
            sb.append(esc(line)).append("\n");
        }
        sb.append("</pre></section>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private void card(StringBuilder sb, String label, String value) {
        sb.append("<div class='card'><div class='card-label'>").append(esc(label))
          .append("</div><div class='card-value'>").append(esc(value))
          .append("</div></div>");
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String css() {
        return """
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
                       sans-serif; font-size: 14px; color: #1a1a2e; background: #fff;
                       padding: 0 0 40px; }
                header { background: #0f3460; color: #fff; padding: 24px 40px; }
                header h1 { font-size: 22px; font-weight: 700; margin-bottom: 6px; }
                header .sub { font-size: 13px; opacity: 0.8; }
                section { margin: 28px 40px 0; }
                h2 { font-size: 16px; font-weight: 600; color: #0f3460;
                     border-bottom: 2px solid #e2e8f0; padding-bottom: 6px;
                     margin-bottom: 14px; }
                table { width: 100%; border-collapse: collapse; font-size: 13px; }
                th { background: #f1f5f9; text-align: left; padding: 8px 12px;
                     font-weight: 600; color: #475569; border: 1px solid #e2e8f0; }
                td { padding: 7px 12px; border: 1px solid #e2e8f0; vertical-align: top;
                     word-break: break-word; }
                tr:nth-child(even) td { background: #f8fafc; }
                tr.pass td { background: #f0fdf4; }
                tr.fail td { background: #fff1f2; }
                tr.unknown td { background: #fffbeb; }
                .cards { display: flex; flex-wrap: wrap; gap: 14px;
                         margin: 24px 40px 0; }
                .card { background: #f8fafc; border: 1px solid #e2e8f0;
                        border-radius: 8px; padding: 16px 20px; min-width: 110px; }
                .card-label { font-size: 11px; font-weight: 600; color: #64748b;
                              text-transform: uppercase; letter-spacing: 0.05em;
                              margin-bottom: 6px; }
                .card-value { font-size: 22px; font-weight: 700; color: #0f3460; }
                .badge { display: inline-block; padding: 2px 8px; border-radius: 4px;
                         font-size: 11px; font-weight: 700; text-transform: uppercase; }
                .badge.pass    { background: #dcfce7; color: #166534; }
                .badge.fail    { background: #fee2e2; color: #991b1b; }
                .badge.unknown { background: #fef3c7; color: #92400e; }
                .err { margin: 16px 40px 0; background: #fee2e2; color: #991b1b;
                       padding: 12px 16px; border-radius: 6px; font-size: 13px; }
                .shots { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 8px; }
                .shots figure { border: 1px solid #e2e8f0; border-radius: 6px;
                                overflow: hidden; }
                .shots img { display: block; max-width: 220px; max-height: 400px; }
                .log { background: #0f172a; color: #94a3b8; padding: 16px 20px;
                       border-radius: 8px; font-family: 'Courier New', Courier, monospace;
                       font-size: 12px; line-height: 1.6; overflow-x: auto;
                       white-space: pre-wrap; word-break: break-all; }
                @media print {
                  body { padding: 0; }
                  header { background: #000; -webkit-print-color-adjust: exact; }
                  .log { background: #111; -webkit-print-color-adjust: exact; }
                }
                """;
    }
}
