package com.vasundhara.atf.web;

import com.vasundhara.atf.device.LogcatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST surface for the Live Console Logcat panel.
 *
 * <pre>
 * POST /api/logcat/start   {serial?}     — begin (or reuse) capture for a device
 * GET  /api/logcat?since=N               — delta poll: lines newer than cursor N
 * POST /api/logcat/clear                 — clear the buffer + device log buffer
 * POST /api/logcat/stop                  — stop the capture
 * GET  /api/logcat/export                — download the current buffer as text
 * </pre>
 */
@RestController
@RequestMapping("/api/logcat")
public class LogcatController {

    private final LogcatService logcat;

    public LogcatController(LogcatService logcat) { this.logcat = logcat; }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, String> body) {
        return logcat.start(body == null ? null : body.get("serial"));
    }

    @GetMapping
    public Map<String, Object> poll(@RequestParam(value = "since", defaultValue = "0") long since) {
        return logcat.poll(since);
    }

    @PostMapping("/clear")
    public Map<String, Object> clear() { return logcat.clear(); }

    @PostMapping("/stop")
    public Map<String, Object> stop() { logcat.stop(); return Map.of("stopped", true); }

    @GetMapping("/export")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header("Content-Disposition", "attachment; filename=logcat.txt")
                .body(logcat.export());
    }
}
