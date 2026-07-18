package com.vasundhara.atf.smartexec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart Execution's own heuristic rule engine over a logcat dump — independent implementation
 * from {@code util.LogcatAnalyzer}. Detects crashes/ANRs plus non-crash runtime defects a senior
 * QA would flag. Every rule is capped + deduped so a noisy repeated error yields one finding.
 */
public final class SmartLogRuleEngine {

    private SmartLogRuleEngine() {}

    public record Hit(String severity, String title, String detail, String snippet) {}

    private record Rule(Pattern pattern, String severity, String title, String detail, int cap) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("FATAL EXCEPTION"), "CRITICAL", "Fatal exception (crash)",
                    "The app crashed with an uncaught exception on the main or a worker thread.", 3),
            new Rule(Pattern.compile("(?i)ANR in |Input dispatching timed out"), "HIGH", "Application Not Responding (ANR)",
                    "The system detected the UI thread was blocked long enough to raise (or nearly raise) an ANR.", 3),
            new Rule(Pattern.compile("signal 11 \\(SIGSEGV\\)|signal 6 \\(SIGABRT\\)|\\*\\*\\* \\*\\*\\* \\*\\*\\* \\*\\*\\*"),
                    "CRITICAL", "Native crash", "A native (C/C++) crash signal was logged.", 2),
            new Rule(Pattern.compile("StrictMode policy violation"), "LOW", "StrictMode violation",
                    "A StrictMode policy violation was logged (disk/network on main thread, leaked resource, etc.).", 5),
            new Rule(Pattern.compile("(?i)java\\.lang\\.OutOfMemoryError|Out of memory"), "HIGH", "OutOfMemoryError",
                    "The app hit an OutOfMemoryError — likely an oversized allocation/bitmap or a leak.", 2),
            new Rule(Pattern.compile("(?i)SQLiteException|no such table|database disk image is malformed|database is locked"),
                    "HIGH", "Database (SQLite) error", "A SQLite error was logged — data operations may be failing.", 3),
            new Rule(Pattern.compile("(?i)SSLHandshakeException|CertPathValidatorException|Trust anchor for certification path"),
                    "MEDIUM", "TLS/SSL error", "A TLS handshake or certificate-validation error was logged.", 3),
            new Rule(Pattern.compile("(?i)java\\.lang\\.SecurityException|Permission Denial"), "MEDIUM",
                    "SecurityException / permission denial", "A permission is likely missing or requested too late.", 3),
            new Rule(Pattern.compile("Resources\\$NotFoundException"), "MEDIUM", "Resource not found",
                    "A referenced string/drawable/layout id is missing.", 3),
            new Rule(Pattern.compile("ActivityNotFoundException"), "MEDIUM", "Broken navigation (ActivityNotFoundException)",
                    "A navigation intent/deep link targets a component that doesn't resolve.", 3),
            new Rule(Pattern.compile("(?i)chromium:\\s*\\[ERROR|net::ERR_|Uncaught (TypeError|ReferenceError)"),
                    "LOW", "WebView / embedded-web error", "A WebView console/network error was logged.", 3),
            new Rule(Pattern.compile("(?i)UnknownHostException|SocketTimeoutException|java\\.net\\.ConnectException|Failed to connect to"),
                    "LOW", "Network request failure", "A network request failed (host/timeout/refused).", 3));

    private static final Pattern SKIPPED_FRAMES = Pattern.compile("Skipped (\\d+) frames");

    /**
     * @deprecated unscoped — {@code adb logcat} returns the ENTIRE device's log, not just the app
     * under test, so this can misattribute another app's or a system process's crash to the app
     * being tested. Use {@link #analyze(String, String, Set)} instead, which requires each hit to
     * be positively attributed to the app's own process before it's reported.
     */
    @Deprecated
    public static List<Hit> analyze(String logcat) {
        return analyze(logcat, null, Set.of());
    }

    private static final Pattern PROCESS_LINE = Pattern.compile("Process:\\s*([\\w.]+)\\s*,\\s*PID:\\s*(\\d+)");

    /**
     * Same detection as {@link #analyze(String)} but ATTRIBUTION-CHECKED: a hit is only reported
     * when it can be positively tied to the app under test — either its logcat line's PID column
     * is in {@code ownPids} (captured via {@code pidof} at launch/relaunch), or the standard
     * Android crash header ({@code Process: <pkg>, PID: <n>}) naming {@code ownPkg} appears within
     * a few lines of it. If neither can be determined, the hit is DROPPED rather than risked as a
     * false positive — verified live: without this check, a UiAutomationService crash from the
     * Appium/UiAutomator2 test-automation process itself was misattributed to the app under test.
     */
    public static List<Hit> analyze(String logcat, String ownPkg, Set<String> ownPids) {
        List<Hit> hits = new ArrayList<>();
        if (logcat == null || logcat.isBlank()) return hits;
        String[] lines = logcat.split("\\R");
        Map<String, Integer> count = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fm = SKIPPED_FRAMES.matcher(line);
            if (fm.find()) {
                if (!attributed(lines, i, ownPkg, ownPids)) continue;
                int frames = parseIntSafe(fm.group(1));
                if (frames >= 60) {
                    String sev = frames >= 120 ? "MEDIUM" : "LOW";
                    String title = "Main-thread jank (" + (frames >= 120 ? "severe" : "noticeable") + ")";
                    if (count.merge("jank", 1, Integer::sum) <= 2 && seen.add(title)) {
                        hits.add(new Hit(sev, title, "Choreographer reported " + frames
                                + " skipped frames — a visible freeze/stutter.", snippet(lines, i, 3)));
                    }
                }
                continue;
            }
            for (Rule r : RULES) {
                if (!r.pattern().matcher(line).find()) continue;
                if (!attributed(lines, i, ownPkg, ownPids)) break;
                if (count.merge(r.title(), 1, Integer::sum) > r.cap()) break;
                if (seen.add(r.title())) hits.add(new Hit(r.severity(), r.title(), r.detail(), snippet(lines, i, 8)));
                break;
            }
        }
        return hits;
    }

    /** True when the flagged line's PID is known to be the app's, or a nearby "Process:" header names it. */
    private static boolean attributed(String[] lines, int i, String ownPkg, Set<String> ownPids) {
        if ((ownPids == null || ownPids.isEmpty()) && (ownPkg == null || ownPkg.isBlank())) return true; // no info to check against — permissive
        String pid = pidOf(lines[i]);
        if (pid != null && ownPids != null && ownPids.contains(pid)) return true;
        for (int j = Math.max(0, i - 3); j < Math.min(lines.length, i + 3); j++) {
            Matcher m = PROCESS_LINE.matcher(lines[j]);
            if (m.find()) {
                if (ownPkg != null && ownPkg.equals(m.group(1))) return true;
                if (ownPids != null && ownPids.contains(m.group(2))) return true;
                if (!m.group(1).equals(ownPkg)) return false; // a DIFFERENT process is explicitly named — not ours
            }
        }
        // No process header nearby and PID didn't match a known one — can't positively attribute;
        // drop rather than risk a false positive.
        return false;
    }

    private static String pidOf(String threadtimeLine) {
        String[] t = threadtimeLine.trim().split("\\s+");
        return t.length >= 3 ? t[2] : null;
    }

    private static int parseIntSafe(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }

    private static String snippet(String[] lines, int from, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(lines.length, from + count); i++) sb.append(lines[i]).append('\n');
        return sb.toString().trim();
    }
}
