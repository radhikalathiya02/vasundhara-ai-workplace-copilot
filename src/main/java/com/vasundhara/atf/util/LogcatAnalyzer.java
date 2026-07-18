package com.vasundhara.atf.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts crash, ANR, native-crash and StrictMode signals from a logcat dump.
 * Shared by the crash and negative-testing categories.
 */
public final class LogcatAnalyzer {

    private LogcatAnalyzer() { }

    public enum Kind { FATAL_EXCEPTION, ANR, NATIVE_CRASH, STRICT_MODE }

    public record Incident(Kind kind, String header, String snippet) { }

    /** A non-crash runtime defect mined from logcat (jank, TLS, DB, security, resource, etc.). */
    public record DefectHit(String severity, String title, String detail, String snippet) { }

    /** One runtime-defect rule: a marker to match, its severity, and a developer-facing message. */
    private record Rule(Pattern pattern, String severity, String title, String detail, int cap) { }

    // Generic, high-signal runtime-defect rules. Deliberately excludes crash/ANR/native/StrictMode
    // (handled by analyze()) and anything that is usually benign noise. Every pattern names a real
    // defect a developer would act on. Applied to per-category logcat, which is time-scoped to the
    // app under test (the buffer is cleared before each category), so attribution is sound.
    private static final List<Rule> RUNTIME_RULES = List.of(
            new Rule(Pattern.compile("(?i)input dispatching timed out"),
                    "HIGH", "Input dispatching timed out (ANR precursor)",
                    "The system reported input dispatching timed out — the UI thread was blocked long "
                            + "enough that Android nearly raised an ANR. Move heavy work off the main thread.", 2),
            new Rule(Pattern.compile("(?i)java\\.lang\\.OutOfMemoryError|Out of memory"),
                    "HIGH", "OutOfMemoryError",
                    "The app hit an OutOfMemoryError — likely an oversized bitmap/allocation or a leak. "
                            + "Investigate memory usage on the affected screen.", 2),
            new Rule(Pattern.compile("(?i)SQLiteException|no such table|database disk image is malformed|database is locked"),
                    "HIGH", "Database (SQLite) error",
                    "A SQLite error was logged (missing table, corruption, or lock contention) — data "
                            + "operations may be failing for the user.", 3),
            new Rule(Pattern.compile("(?i)SSLHandshakeException|CertPathValidatorException|Trust anchor for certification path"),
                    "MEDIUM", "TLS/SSL error",
                    "A TLS/SSL handshake or certificate-validation error was logged — HTTPS requests may "
                            + "be failing (bad cert, pinning misconfig, or a blocked network).", 3),
            new Rule(Pattern.compile("(?i)java\\.lang\\.SecurityException|Permission Denial"),
                    "MEDIUM", "SecurityException / permission denial",
                    "A SecurityException or permission denial was logged — a runtime permission is likely "
                            + "missing or requested too late for the feature that needs it.", 3),
            new Rule(Pattern.compile("(?i)Resources\\$NotFoundException"),
                    "MEDIUM", "Resource not found",
                    "A Resources.NotFoundException was logged — a referenced string/drawable/layout id is "
                            + "missing, which can blank out or break part of a screen.", 3),
            new Rule(Pattern.compile("(?i)ActivityNotFoundException"),
                    "MEDIUM", "ActivityNotFoundException (broken navigation/intent)",
                    "An ActivityNotFoundException was logged — a navigation intent or deep link targets a "
                            + "component that doesn't resolve, so that action dead-ends for the user.", 3),
            new Rule(Pattern.compile("(?i)chromium:\\s*\\[ERROR|net::ERR_|Uncaught (TypeError|ReferenceError)"),
                    "LOW", "WebView / embedded-web error",
                    "A WebView console/network error was logged — embedded web content failed to load or "
                            + "threw a script error.", 3),
            new Rule(Pattern.compile("(?i)UnknownHostException|SocketTimeoutException|java\\.net\\.ConnectException|Failed to connect to"),
                    "LOW", "Network request failure",
                    "A network request failed (unknown host / timeout / connection refused). May reflect "
                            + "the test environment, but repeated failures indicate a broken endpoint or "
                            + "missing offline handling.", 3));

    private static final Pattern SKIPPED_FRAMES = Pattern.compile("Skipped (\\d+) frames");

    public static List<Incident> analyze(String logcat) {
        List<Incident> incidents = new ArrayList<>();
        if (logcat == null || logcat.isBlank()) return incidents;
        String[] lines = logcat.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("FATAL EXCEPTION")) {
                incidents.add(new Incident(Kind.FATAL_EXCEPTION, line.trim(), snippet(lines, i, 18)));
            } else if (line.contains("ANR in ") || line.contains("ANR ")) {
                incidents.add(new Incident(Kind.ANR, line.trim(), snippet(lines, i, 10)));
            } else if (line.contains("signal 11 (SIGSEGV)") || line.contains("signal 6 (SIGABRT)")
                    || line.contains("*** *** *** ***")) {
                incidents.add(new Incident(Kind.NATIVE_CRASH, line.trim(), snippet(lines, i, 14)));
            } else if (line.contains("StrictMode policy violation")) {
                incidents.add(new Incident(Kind.STRICT_MODE, line.trim(), snippet(lines, i, 6)));
            }
        }
        return incidents;
    }

    /**
     * Mines the logcat for non-crash RUNTIME DEFECTS (jank, TLS, DB, security, resource, network,
     * WebView) that a senior QA would report but a crash scan misses. Each rule is capped and the
     * results are deduped by title, so a noisy repeated error yields one finding with a sample
     * snippet — not a flood. Returns an empty list for null/blank input.
     */
    public static List<DefectHit> analyzeRuntimeDefects(String logcat) {
        List<DefectHit> hits = new ArrayList<>();
        if (logcat == null || logcat.isBlank()) return hits;
        String[] lines = logcat.split("\\R");
        Map<String, Integer> perRuleCount = new HashMap<>();
        java.util.Set<String> seenTitles = new java.util.HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Main-thread jank: "Skipped N frames!" — only flag genuinely severe stalls to avoid
            // noise (60 frames ≈ ~1s at 60fps). 120+ frames is a hard hitch → MEDIUM, else LOW.
            Matcher fm = SKIPPED_FRAMES.matcher(line);
            if (fm.find()) {
                int frames = parseIntSafe(fm.group(1));
                if (frames >= 60) {
                    String sev = frames >= 120 ? "MEDIUM" : "LOW";
                    String title = "Main-thread jank (" + (frames >= 120 ? "severe" : "noticeable") + ")";
                    if (perRuleCount.merge("jank", 1, Integer::sum) <= 2 && seenTitles.add(title)) {
                        hits.add(new DefectHit(sev, title,
                                "Choreographer reported " + frames + " skipped frames — the UI thread was "
                                        + "blocked, causing a visible freeze/stutter. Move heavy work off the "
                                        + "main thread (I/O, decoding, large layouts).",
                                snippet(lines, i, 3)));
                    }
                }
                continue;
            }

            for (Rule r : RUNTIME_RULES) {
                if (!r.pattern().matcher(line).find()) continue;
                if (perRuleCount.merge(r.title(), 1, Integer::sum) > r.cap()) break;
                if (seenTitles.add(r.title())) {
                    hits.add(new DefectHit(r.severity(), r.title(), r.detail(), snippet(lines, i, 6)));
                }
                break; // one rule per line
            }
        }
        return hits;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static String snippet(String[] lines, int from, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(lines.length, from + count); i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }
}
