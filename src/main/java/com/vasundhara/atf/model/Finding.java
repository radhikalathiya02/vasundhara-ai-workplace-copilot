package com.vasundhara.atf.model;

import java.util.UUID;

/**
 * A single observation produced by a test category — a bug, risk, warning or
 * informational data point. {@code evidence} optionally points at an artifact
 * (screenshot path, log excerpt) relative to the run directory.
 *
 * <p>{@code occurrenceCount} is incremented by {@code TestResult.addFinding()} each time
 * an identical defect is detected again on the same screen; only the first instance is
 * retained in the findings list so reports stay concise.
 */
public record Finding(
        String id,
        Severity severity,
        String title,
        String detail,
        String evidence,
        long timestampMillis,
        int occurrenceCount) {

    public static Finding of(Severity severity, String title, String detail) {
        return new Finding(UUID.randomUUID().toString(), severity, title, detail, null,
                System.currentTimeMillis(), 1);
    }

    public static Finding of(Severity severity, String title, String detail, String evidence) {
        return new Finding(UUID.randomUUID().toString(), severity, title, detail, evidence,
                System.currentTimeMillis(), 1);
    }

    /** Return a copy of this finding with the updated occurrence count. */
    public Finding withOccurrenceCount(int count) {
        return new Finding(id, severity, title, detail, evidence, timestampMillis, count);
    }
}
