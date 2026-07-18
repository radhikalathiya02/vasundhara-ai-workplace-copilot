package com.vasundhara.atf.model;

/**
 * Outcome of validating a single Remote Config flag after publishing the change
 * and exercising the app. The {@code status} field reflects how strongly the
 * expected value was confirmed: PASS = found in UI or logcat, UNKNOWN = not
 * visible (non-UI flag), FAIL = explicit mismatch detected.
 */
public class RemoteConfigTestResult {

    private String flagKey;
    private String expectedValue;
    private String foundEvidence;
    /** PASS | UNKNOWN | FAIL */
    private String status;
    private String detail;
    /** Absolute or relative path to a screenshot artifact, or null. */
    private String screenshot;

    public RemoteConfigTestResult() {}

    public RemoteConfigTestResult(String flagKey, String expectedValue, String foundEvidence,
                                  String status, String detail, String screenshot) {
        this.flagKey = flagKey;
        this.expectedValue = expectedValue;
        this.foundEvidence = foundEvidence;
        this.status = status;
        this.detail = detail;
        this.screenshot = screenshot;
    }

    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }

    public String getExpectedValue() { return expectedValue; }
    public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }

    public String getFoundEvidence() { return foundEvidence; }
    public void setFoundEvidence(String foundEvidence) { this.foundEvidence = foundEvidence; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getScreenshot() { return screenshot; }
    public void setScreenshot(String screenshot) { this.screenshot = screenshot; }
}
