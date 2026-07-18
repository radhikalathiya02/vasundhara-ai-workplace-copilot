package com.vasundhara.atf.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility result for a single Android version in the matrix. Carries the
 * per-version status, score, mode of execution, per-screen Pass/Fail breakdown
 * and the full flat list of issues found.
 */
public class CompatVersionResult {

    /**
     * @param type         Crash | ANR | UI Issue | Functionality Issue
     * @param logcat       relevant logcat excerpt (exception/stack trace for crashes)
     * @param screenshotUrl serveable URL of the affected screen's screenshot, or null
     */
    public record Issue(String screen, String type, String description, String severity,
                        String logcat, String screenshotUrl, CrashInfo crash) {}

    /** Per-screen Pass/Fail summary with its subset of issues and a screenshot thumbnail. */
    public static class ScreenResult {
        private final String activity;
        private final String screenshotUrl;
        private String status = "PASS"; // PASS | WARNING | FAIL
        private final List<Issue> issues = new ArrayList<>();

        public ScreenResult(String activity, String screenshotUrl) {
            this.activity = activity;
            this.screenshotUrl = screenshotUrl;
        }
        public String     getActivity()    { return activity; }
        public String     getScreenshotUrl() { return screenshotUrl; }
        public String     getStatus()      { return status; }
        public void       setStatus(String s) { this.status = s; }
        public List<Issue> getIssues()     { return issues; }
    }

    private final String version;   // e.g. "Android 13"
    private final int api;          // e.g. 33
    private volatile String state = "PENDING"; // PENDING | PROVISIONING | LAUNCHING_EMU | BOOTING | EMULATOR_READY | INSTALLING | LAUNCHING | TESTING | DONE | ERROR
    private String status = "—";    // PASS | WARNING | FAIL | ENV ERROR
    private int score;
    private int screensExplored;
    private int actions;
    private String mode = "";       // "UI crawl (Appium)" | "launch + logcat" | ""
    private String error;           // environment/setup error for this version, if any
    private volatile String serial; // adb serial of the currently booted emulator, or null
    private final List<Issue> issues = new ArrayList<>();
    private final List<ScreenResult> screenResults = new ArrayList<>();

    public CompatVersionResult(String version, int api) {
        this.version = version;
        this.api = api;
    }

    public String getVersion()           { return version; }
    public int    getApi()               { return api; }
    public String getState()             { return state; }
    public void   setState(String state) { this.state = state; }
    public String getStatus()            { return status; }
    public void   setStatus(String s)    { this.status = s; }
    public int    getScore()             { return score; }
    public void   setScore(int score)    { this.score = score; }
    public int    getScreensExplored()   { return screensExplored; }
    public void   setScreensExplored(int v) { this.screensExplored = v; }
    public int    getActions()           { return actions; }
    public void   setActions(int v)      { this.actions = v; }
    public String getMode()              { return mode; }
    public void   setMode(String mode)   { this.mode = mode; }
    public String getError()             { return error; }
    public void   setError(String e)     { this.error = e; }
    public String getSerial()            { return serial; }
    public void   setSerial(String s)    { this.serial = s; }
    public List<Issue>        getIssues()        { return issues; }
    public List<ScreenResult> getScreenResults() { return screenResults; }
}
