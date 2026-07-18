package com.vasundhara.atf.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an Android {@code FATAL EXCEPTION} logcat block (threadtime format,
 * {@code -b crash}) into a structured {@link CrashInfo}: exception type, message,
 * process/PID, thread, stack frames and the first application frame (root cause).
 */
public final class CrashParser {

    private CrashParser() {}

    /** Strip the "… E AndroidRuntime: " (or "…AndroidRuntime(123): ") logcat prefix. */
    private static final Pattern PREFIX = Pattern.compile("^.*?AndroidRuntime(?:\\(\\d+\\))?:\\s?");
    private static final Pattern PROCESS = Pattern.compile("Process:\\s*([^,]+),\\s*PID:\\s*(\\d+)");
    private static final Pattern FRAME = Pattern.compile("^at\\s+(.+)$");

    /**
     * @param block raw logcat text starting at (or containing) FATAL EXCEPTION
     * @param pkg   application package (used to flag the app's own frames); may be null
     * @return parsed crash, or {@code null} if no FATAL EXCEPTION block is present
     */
    public static CrashInfo parse(String block, String pkg) {
        if (block == null || block.isBlank()) return null;

        // Clean each line down to its AndroidRuntime content; keep only crash lines.
        List<String> lines = new ArrayList<>();
        boolean started = false;
        for (String raw : block.split("\\R")) {
            String c;
            Matcher pm = PREFIX.matcher(raw);
            if (pm.find()) {
                c = raw.substring(pm.end());
            } else {
                String t = raw.trim();
                // Allow already-clean continuation lines (stack frames / causes).
                if (t.startsWith("at ") || t.startsWith("Caused by:") || t.startsWith("...")) {
                    c = t;
                } else {
                    if (started) break; // crash block ended
                    continue;
                }
            }
            if (c.contains("FATAL EXCEPTION")) started = true;
            if (!started) continue;
            lines.add(c.trim());
        }
        if (lines.isEmpty()) return null;

        String thread = "", process = "", pid = "", pkgName = pkg == null ? "" : pkg;
        String exceptionType = "", message = "";
        List<CrashInfo.Frame> frames = new ArrayList<>();
        String crashedAt = null;
        boolean haveThrowable = false;

        for (String line : lines) {
            if (line.startsWith("FATAL EXCEPTION:")) {
                thread = line.substring("FATAL EXCEPTION:".length()).trim();
                continue;
            }
            Matcher procM = PROCESS.matcher(line);
            if (procM.find()) {
                process = procM.group(1).trim();
                pid = procM.group(2).trim();
                if (pkgName.isBlank()) pkgName = process;
                continue;
            }
            Matcher fm = FRAME.matcher(line);
            if (fm.matches()) {
                String text = fm.group(1).trim();
                boolean app = pkg != null && !pkg.isBlank() && text.contains(pkg);
                frames.add(new CrashInfo.Frame(text, app, false));
                if (app && crashedAt == null) crashedAt = text;
                continue;
            }
            if (line.startsWith("Caused by:")) {
                frames.add(new CrashInfo.Frame(line, false, true));
                continue;
            }
            if (line.startsWith("...")) { // "... N more"
                frames.add(new CrashInfo.Frame(line, false, false));
                continue;
            }
            // First throwable line: "<type>: <message>" or just "<type>"
            if (!haveThrowable && looksLikeThrowable(line)) {
                int colon = line.indexOf(": ");
                if (colon > 0) {
                    exceptionType = line.substring(0, colon).trim();
                    message = line.substring(colon + 2).trim();
                } else {
                    exceptionType = line.trim();
                }
                haveThrowable = true;
            }
        }

        if (exceptionType.isBlank() && frames.isEmpty()) return null;
        if (crashedAt == null && !frames.isEmpty()) crashedAt = frames.get(0).text();
        return new CrashInfo(exceptionType, message, process, pid, thread, pkgName, crashedAt, frames);
    }

    private static boolean looksLikeThrowable(String line) {
        // e.g. java.lang.IllegalStateException: ...   or   com.x.MyException
        return line.matches("^[\\w.$]+(Exception|Error|Throwable)\\b.*")
                || line.matches("^[\\w.$]+\\.[\\w$]+:.*");
    }
}
