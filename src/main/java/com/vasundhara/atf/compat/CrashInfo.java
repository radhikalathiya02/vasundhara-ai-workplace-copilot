package com.vasundhara.atf.compat;

import java.util.List;

/**
 * Structured representation of an Android crash parsed from the logcat
 * {@code FATAL EXCEPTION} block, so the report can render it cleanly
 * (Android-Studio style) rather than as a raw dump.
 *
 * @param exceptionType fully-qualified throwable, e.g. {@code java.lang.IllegalStateException}
 * @param message       the exception message
 * @param process       crashing process name (from {@code Process: <pkg>, PID: <n>})
 * @param pid           process id
 * @param thread        crashing thread name (from {@code FATAL EXCEPTION: <thread>})
 * @param packageName   application package
 * @param crashedAt     the first application stack frame — the likely root cause line
 * @param frames        the full ordered stack trace (incl. "Caused by" lines)
 */
public record CrashInfo(String exceptionType, String message, String process, String pid,
                        String thread, String packageName, String crashedAt, List<Frame> frames) {

    /** One stack-trace line. {@code appFrame} marks frames inside the app's own package. */
    public record Frame(String text, boolean appFrame, boolean cause) {}
}
