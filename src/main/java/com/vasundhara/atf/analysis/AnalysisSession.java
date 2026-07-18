package com.vasundhara.atf.analysis;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Lifecycle state for one "Analyze APK" run in the New Test module. Purely in-memory and
 * short-lived — this is a pre-execution aid (progress + a downloadable Test Case Sheet), not
 * a persisted test run, so it does not go through {@code ReportStore}/the database.
 */
public class AnalysisSession {

    public static final String[] STAGES = {
            "Reading APK",
            "Extracting Manifest",
            "Detecting Activities & Fragments",
            "Identifying Screens",
            "Discovering Navigation Flow",
            "Detecting Features & Modules",
            "Identifying Permissions",
            "Detecting Ads",
            "Understanding Business Flow",
            "Building Screen Flow",
            "Generating Test Scenarios"
    };

    private final String id;
    private final String apkFileName;
    private volatile int percent = 0;
    private volatile String stage = STAGES[0];
    private volatile boolean done = false;
    private volatile String error;
    private volatile int testCaseCount = 0;
    private volatile int issueCount = 0;
    private volatile String appDomain;
    private final long createdAtMillis = System.currentTimeMillis();

    @JsonIgnore
    private volatile byte[] sheetBytes;

    public AnalysisSession(String id, String apkFileName) {
        this.id = id;
        this.apkFileName = apkFileName;
    }

    public String getId() { return id; }
    public String getApkFileName() { return apkFileName; }
    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getTestCaseCount() { return testCaseCount; }
    public void setTestCaseCount(int testCaseCount) { this.testCaseCount = testCaseCount; }
    public int getIssueCount() { return issueCount; }
    public void setIssueCount(int issueCount) { this.issueCount = issueCount; }
    public String getAppDomain() { return appDomain; }
    public void setAppDomain(String appDomain) { this.appDomain = appDomain; }
    public long getCreatedAtMillis() { return createdAtMillis; }

    public byte[] getSheetBytes() { return sheetBytes; }
    public void setSheetBytes(byte[] sheetBytes) { this.sheetBytes = sheetBytes; }

    /** Advance to the given stage (by index into {@link #STAGES}) and its proportional percent. */
    public void advanceTo(int stageIndex) {
        this.stage = STAGES[stageIndex];
        // Reserve the last 5% for the final "done" tick set explicitly by the runner.
        this.percent = Math.min(95, Math.round((stageIndex + 1) * 95f / STAGES.length));
    }
}
