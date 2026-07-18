package com.vasundhara.atf.testcase;

import java.util.ArrayList;
import java.util.List;

/** Full execution result for a single test case. */
public class TcItemResult {

    private String tcId;
    private String tcName;
    private String module;
    private String feature;
    private String priority;
    /** PASS | FAIL | BLOCKED | SKIPPED | NOT_EXECUTED */
    private String status = "NOT_EXECUTED";
    private List<TcStepResult> stepResults = new ArrayList<>();
    private String screenshotPath;  // last screenshot taken during this TC
    private String logcatSnippet;   // relevant logcat lines
    private String crashLog;
    private long   startTime;
    private long   endTime;
    /** AI-generated human-readable explanation of the outcome. */
    private String aiNotes;

    public String getTcId()               { return tcId; }
    public void   setTcId(String v)       { this.tcId = v; }

    public String getTcName()             { return tcName; }
    public void   setTcName(String v)     { this.tcName = v; }

    public String getModule()             { return module; }
    public void   setModule(String v)     { this.module = v; }

    public String getFeature()            { return feature; }
    public void   setFeature(String v)    { this.feature = v; }

    public String getPriority()           { return priority; }
    public void   setPriority(String v)   { this.priority = v; }

    public String getStatus()             { return status; }
    public void   setStatus(String v)     { this.status = v; }

    public List<TcStepResult> getStepResults()              { return stepResults; }
    public void               setStepResults(List<TcStepResult> v) { this.stepResults = v; }

    public String getScreenshotPath()          { return screenshotPath; }
    public void   setScreenshotPath(String v)  { this.screenshotPath = v; }

    public String getLogcatSnippet()           { return logcatSnippet; }
    public void   setLogcatSnippet(String v)   { this.logcatSnippet = v; }

    public String getCrashLog()                { return crashLog; }
    public void   setCrashLog(String v)        { this.crashLog = v; }

    public long   getStartTime()               { return startTime; }
    public void   setStartTime(long v)         { this.startTime = v; }

    public long   getEndTime()                 { return endTime; }
    public void   setEndTime(long v)           { this.endTime = v; }

    public long   getDurationMs()              { return endTime > 0 ? endTime - startTime : 0; }

    public String getAiNotes()                 { return aiNotes; }
    public void   setAiNotes(String v)         { this.aiNotes = v; }

    public long getPassedSteps()   { return stepResults.stream().filter(TcStepResult::passed).count(); }
    public long getFailedSteps()   { return stepResults.stream().filter(TcStepResult::failed).count(); }
    public long getBlockedSteps()  { return stepResults.stream().filter(TcStepResult::blocked).count(); }
}
