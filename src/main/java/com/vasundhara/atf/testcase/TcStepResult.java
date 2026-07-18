package com.vasundhara.atf.testcase;

/**
 * Result of executing one step.
 *
 * <p>Status values: PASS | FAIL | BLOCKED | SKIPPED | NOT_EXECUTED | ERROR
 */
public record TcStepResult(
        int    num,
        String description,
        String expectedResult,
        String actualResult,
        String status,
        String screenshotPath,
        String screenshotBefore,
        String notes,
        long   durationMs) {

    public boolean passed()  { return "PASS".equals(status); }
    public boolean failed()  { return "FAIL".equals(status) || "ERROR".equals(status); }
    public boolean blocked() { return "BLOCKED".equals(status); }
}
