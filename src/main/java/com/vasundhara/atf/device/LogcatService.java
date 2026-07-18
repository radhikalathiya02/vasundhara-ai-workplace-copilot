package com.vasundhara.atf.device;

import com.vasundhara.atf.config.AtfProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams Android logcat from a device into an in-memory ring buffer for the dashboard's
 * Live Console panel. A single background {@code adb logcat -v threadtime} process feeds the
 * buffer; the UI polls for the delta since a cursor, so updates are cheap regardless of volume.
 *
 * <p>The framework tests one device at a time, so a single active capture is kept. It coexists
 * with the test run (logcat is read-only) and is bounded to {@link #MAX} lines.
 */
@Service
public class LogcatService {

    private static final Logger log = LoggerFactory.getLogger(LogcatService.class);
    private static final int MAX = 5000;

    /** threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message" */
    private static final Pattern TT = Pattern.compile(
            "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEFA])\\s+(.*?):\\s?(.*)$");

    private final AtfProperties props;
    private final DeviceManager deviceManager;
    private volatile Capture active;

    public LogcatService(AtfProperties props, DeviceManager deviceManager) {
        this.props = props;
        this.deviceManager = deviceManager;
    }

    public record Line(long id, String ts, String level, String tag, String msg) {}

    private static final class Capture {
        final String serial;
        Process process;
        Thread reader;
        volatile boolean running = true;
        final ArrayDeque<Line> buf = new ArrayDeque<>();
        final AtomicLong seq = new AtomicLong();
        Capture(String serial) { this.serial = serial; }
    }

    /** Start (or reuse) a capture for the given/auto-selected device. */
    public synchronized Map<String, Object> start(String serialIn) {
        String serial = resolve(serialIn);
        Map<String, Object> out = new LinkedHashMap<>();
        if (serial == null || serial.isBlank()) {
            out.put("started", false);
            out.put("reason", "No device connected.");
            return out;
        }
        if (active != null && active.serial.equals(serial) && active.running
                && active.process != null && active.process.isAlive()) {
            out.put("started", true); out.put("serial", serial); out.put("reused", true);
            return out;
        }
        stopInternal();
        try {
            // Fresh buffer so the console starts clean for this device.
            try { new ProcessBuilder(adbCmd(serial, "logcat", "-c")).start().waitFor(3, TimeUnit.SECONDS); }
            catch (Exception ignored) {}
            Capture c = new Capture(serial);
            ProcessBuilder pb = new ProcessBuilder(adbCmd(serial, "logcat", "-v", "threadtime"));
            pb.redirectErrorStream(true);
            c.process = pb.start();
            c.reader = new Thread(() -> pump(c), "logcat-" + serial);
            c.reader.setDaemon(true);
            c.reader.start();
            active = c;
            log.info("Logcat capture started for {}", serial);
            out.put("started", true); out.put("serial", serial);
        } catch (Exception e) {
            log.warn("Logcat start failed: {}", e.toString());
            out.put("started", false); out.put("reason", e.getMessage() == null ? "could not start adb logcat" : e.getMessage());
        }
        return out;
    }

    private void pump(Capture c) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.process.getInputStream(), StandardCharsets.UTF_8))) {
            String ln;
            while (c.running && (ln = br.readLine()) != null) {
                Line parsed = parse(c.seq.incrementAndGet(), ln);
                synchronized (c.buf) {
                    c.buf.addLast(parsed);
                    while (c.buf.size() > MAX) c.buf.removeFirst();
                }
            }
        } catch (Exception e) {
            // process ended / stream closed — normal on stop or device disconnect
        }
    }

    private Line parse(long id, String raw) {
        Matcher m = TT.matcher(raw);
        if (m.matches()) {
            String lvl = m.group(2);
            if (lvl.equals("F") || lvl.equals("A")) lvl = "E"; // fatal/assert shown as error
            return new Line(id, m.group(1), lvl, m.group(3).trim(), m.group(4));
        }
        return new Line(id, "", "", "", raw); // banners like "--------- beginning of main"
    }

    /** Return lines newer than {@code since} plus the new cursor. */
    public synchronized Map<String, Object> poll(long since) {
        Map<String, Object> out = new LinkedHashMap<>();
        Capture c = active;
        if (c == null) {
            out.put("running", false); out.put("lines", List.of()); out.put("lastId", since);
            return out;
        }
        List<Line> ls = new ArrayList<>();
        long last = since;
        synchronized (c.buf) {
            for (Line l : c.buf) if (l.id() > since) { ls.add(l); last = l.id(); }
        }
        out.put("running", c.running && c.process != null && c.process.isAlive());
        out.put("serial", c.serial);
        out.put("lines", ls);
        out.put("lastId", last);
        return out;
    }

    public synchronized Map<String, Object> clear() {
        Capture c = active;
        if (c != null) {
            synchronized (c.buf) { c.buf.clear(); }
            try { new ProcessBuilder(adbCmd(c.serial, "logcat", "-c")).start().waitFor(3, TimeUnit.SECONDS); }
            catch (Exception ignored) {}
        }
        return Map.of("cleared", true);
    }

    /** Full current buffer as plain text (for export/download). */
    public synchronized String export() {
        Capture c = active;
        if (c == null) return "";
        StringBuilder sb = new StringBuilder();
        synchronized (c.buf) {
            for (Line l : c.buf) {
                if (l.level().isEmpty()) sb.append(l.msg());
                else sb.append(l.ts()).append(' ').append(l.level()).append('/').append(l.tag()).append(": ").append(l.msg());
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized void stop() { stopInternal(); }

    private void stopInternal() {
        Capture c = active;
        active = null;
        if (c == null) return;
        c.running = false;
        try { if (c.process != null) c.process.destroyForcibly(); } catch (Exception ignored) {}
        if (c.reader != null) c.reader.interrupt();
    }

    private String resolve(String s) {
        if (s != null && !s.isBlank()) return s.trim();
        return deviceManager.selectDevice().orElse(props.getDeviceSerial());
    }

    private List<String> adbCmd(String serial, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getAdbPath());
        if (serial != null && !serial.isBlank()) { cmd.add("-s"); cmd.add(serial); }
        for (String a : args) cmd.add(a);
        return cmd;
    }

    @PreDestroy
    void shutdown() { stopInternal(); }
}
