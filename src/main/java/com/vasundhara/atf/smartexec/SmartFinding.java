package com.vasundhara.atf.smartexec;

import java.util.List;

/**
 * One developer-facing bug finding produced by Smart Execution. {@code category} is one of:
 * functional, uiux, crash, ads. {@code severity}: CRITICAL/HIGH/MEDIUM/LOW/INFO.
 * {@code priority}: P1/P2/P3/P4 (derived from severity).
 */
public record SmartFinding(
        String id,
        String category,
        String severity,
        String priority,
        String screenName,
        String feature,
        String title,
        List<String> stepsToReproduce,
        String expectedResult,
        String actualResult,
        String screenshotPath,
        String videoPath,
        String logsExcerpt,
        long timestamp,
        String dedupeKey) {

    public static String priorityFor(String severity) {
        if (severity == null) return "P3";
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "P1";
            case "HIGH" -> "P2";
            case "MEDIUM" -> "P3";
            default -> "P4";
        };
    }

    public static String keyOf(String screenName, String feature, String title) {
        return norm(screenName) + "|" + norm(feature) + "|" + norm(title);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
