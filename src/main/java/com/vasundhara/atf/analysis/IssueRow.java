package com.vasundhara.atf.analysis;

/**
 * A real defect found while crawling the app during "Analyze APK" — as opposed to
 * {@link TestCaseRow}, which describes a scenario a human should go verify. Populated from
 * crash/ANR logcat signals and structural UI issues ({@code ScrollReport}) observed live on
 * the device, never guessed from static analysis.
 */
public class IssueRow {

    public final String screen;
    public final String type;
    public final String severity;
    public final String description;
    public final String evidence;

    public IssueRow(String screen, String type, String severity, String description, String evidence) {
        this.screen = screen;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.evidence = evidence;
    }
}
