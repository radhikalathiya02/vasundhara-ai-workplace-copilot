package com.vasundhara.atf.smartexec.figma;

/**
 * One design-vs-app mismatch, in exactly the shape the product spec asks the report to show:
 * screen name, component name, expected (Figma), actual (app), a human difference description,
 * severity, and evidence (the app screenshot, the Figma render, and a diff overlay — all optional,
 * whichever apply to this finding type — for the side-by-side + screenshot-comparison view).
 */
public record FigmaFinding(
        String id,
        String screenName,
        String componentName,
        String expected,
        String actual,
        String difference,
        String severity,         // CRITICAL/HIGH/MEDIUM/LOW
        String appScreenshotPath,
        String figmaScreenshotPath,
        String diffOverlayPath,
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
}
