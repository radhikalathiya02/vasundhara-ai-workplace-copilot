package com.vasundhara.atf.dm;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Per-device result within a {@link MultiDeviceSession}. */
public class DeviceSlotResult {

    private final String serial;
    private final String displayName;
    private volatile String state  = "PENDING"; // PENDING|INSTALLING|LAUNCHING|RUNNING|DONE|ERROR
    private volatile String status = "—";       // PASS|FAIL|ERROR
    private volatile int    score  = 0;
    private volatile int    crashCount = 0;
    private volatile int    anrCount   = 0;
    private volatile int    screensExplored = 0;
    private volatile String error;

    @JsonIgnore
    private volatile byte[] finalScreenshot;   // captured at end of run; served by screenshot endpoint

    private final List<String> logs     = new CopyOnWriteArrayList<>();
    private final List<String> findings = new CopyOnWriteArrayList<>();

    public DeviceSlotResult(String serial, String displayName) {
        this.serial = serial;
        this.displayName = displayName;
    }

    public String getSerial()         { return serial; }
    public String getDisplayName()    { return displayName; }
    public String getState()          { return state; }
    public void   setState(String s)  { this.state = s; }
    public String getStatus()         { return status; }
    public void   setStatus(String s) { this.status = s; }
    public int    getScore()          { return score; }
    public void   setScore(int v)     { this.score = v; }
    public int    getCrashCount()     { return crashCount; }
    public void   setCrashCount(int v){ this.crashCount = v; }
    public int    getAnrCount()       { return anrCount; }
    public void   setAnrCount(int v)  { this.anrCount = v; }
    public int    getScreensExplored(){ return screensExplored; }
    public void   setScreensExplored(int v){ this.screensExplored = v; }
    public String getError()          { return error; }
    public void   setError(String e)  { this.error = e; }

    public byte[] getFinalScreenshot()        { return finalScreenshot; }
    public void   setFinalScreenshot(byte[] b){ this.finalScreenshot = b; }

    public List<String> getLogs()     { return logs; }
    public List<String> getFindings() { return findings; }
    public void addLog(String msg)    { logs.add(msg); }
    public void addFinding(String f)  { findings.add(f); }
}
