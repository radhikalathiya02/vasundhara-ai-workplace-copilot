package com.vasundhara.atf.testcase;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle state for a Test Case Execution session.
 *
 * <p>State machine: SETUP → RUNNING → COMPLETED | STOPPED | FAILED
 * (RUNNING ⇄ PAUSED while a pause/resume cycle is in progress)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TcSession {

    public enum State { SETUP, RUNNING, PAUSED, COMPLETED, STOPPED, FAILED }

    // id is conceptually immutable (set once, never reassigned by any caller) but is NOT `final`
    // — the @JsonCreator constructor below needs to assign it when Jackson restores a session
    // from its persisted snapshot after a server restart (see SessionPersistenceService).
    private String id;
    private volatile State  state = State.SETUP;
    private String apkFileName;
    private String sheetFileName;
    private String packageName;

    private List<TcItem>       testCases  = new ArrayList<>();
    private final List<TcItemResult> results = new CopyOnWriteArrayList<>();
    private final List<String>       logs    = new CopyOnWriteArrayList<>();

    private volatile boolean stopRequested;
    private volatile boolean pauseRequested;

    private volatile int currentIndex = -1;  // index of TC currently executing (-1 = none)
    private volatile int totalCases   = 0;

    // Live-progress detail for the execution page — which case/step is running right now and on
    // which device. Additive: consumers that ignore these fields behave exactly as before.
    private volatile String serial;
    private volatile String currentTcId;
    private volatile String currentTcName;
    private volatile String currentStep;

    // Rolling pass/fail counters updated after each TC completes.
    private volatile int passed, failed, blocked, skipped, notExecuted;

    private long   startTime;
    private long   finishedAt;
    private String error;

    public TcSession(String id) { this.id = id; }

    /**
     * Restores a session from its persisted JSON snapshot (see {@code SessionPersistenceService}).
     * Property names match this class's own getters exactly, since that's what produced the JSON
     * being read back in. Not used by normal (live) session creation — only by
     * {@code TcSessionStore}'s startup restore.
     */
    @JsonCreator
    private TcSession(
            @JsonProperty("id") String id,
            @JsonProperty("state") State state,
            @JsonProperty("apkFileName") String apkFileName,
            @JsonProperty("sheetFileName") String sheetFileName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("testCases") List<TcItem> testCases,
            @JsonProperty("results") List<TcItemResult> results,
            @JsonProperty("logs") List<String> logs,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("pauseRequested") boolean pauseRequested,
            @JsonProperty("currentIndex") int currentIndex,
            @JsonProperty("totalCases") int totalCases,
            @JsonProperty("serial") String serial,
            @JsonProperty("currentTcId") String currentTcId,
            @JsonProperty("currentTcName") String currentTcName,
            @JsonProperty("currentStep") String currentStep,
            @JsonProperty("passed") int passed,
            @JsonProperty("failed") int failed,
            @JsonProperty("blocked") int blocked,
            @JsonProperty("skipped") int skipped,
            @JsonProperty("notExecuted") int notExecuted,
            @JsonProperty("startTime") long startTime,
            @JsonProperty("finishedAt") long finishedAt,
            @JsonProperty("error") String error) {
        this.id = id;
        if (state != null) this.state = state;
        this.apkFileName = apkFileName;
        this.sheetFileName = sheetFileName;
        this.packageName = packageName;
        if (testCases != null) this.testCases = testCases;
        if (results != null) this.results.addAll(results);
        if (logs != null) this.logs.addAll(logs);
        this.stopRequested = stopRequested;
        this.pauseRequested = pauseRequested;
        this.currentIndex = currentIndex;
        this.totalCases = totalCases;
        this.serial = serial;
        this.currentTcId = currentTcId;
        this.currentTcName = currentTcName;
        this.currentStep = currentStep;
        this.passed = passed;
        this.failed = failed;
        this.blocked = blocked;
        this.skipped = skipped;
        this.notExecuted = notExecuted;
        this.startTime = startTime;
        this.finishedAt = finishedAt;
        this.error = error;
    }

    public void addLog(String msg) {
        logs.add("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
    }

    // ── identity ──────────────────────────────────────────────────────────────
    public String getId()                 { return id; }

    // ── state ─────────────────────────────────────────────────────────────────
    public State  getState()              { return state; }
    public void   setState(State s)       { this.state = s; }

    public void requestStop()             { stopRequested  = true; }
    public boolean isStopRequested()      { return stopRequested; }

    public void requestPause()            { pauseRequested = true; }
    public void requestResume()           { pauseRequested = false; }
    public boolean isPauseRequested()     { return pauseRequested; }

    // ── files ─────────────────────────────────────────────────────────────────
    public String getApkFileName()             { return apkFileName; }
    public void   setApkFileName(String v)     { this.apkFileName = v; }

    public String getSheetFileName()           { return sheetFileName; }
    public void   setSheetFileName(String v)   { this.sheetFileName = v; }

    public String getPackageName()             { return packageName; }
    public void   setPackageName(String v)     { this.packageName = v; }

    // ── test cases & results ──────────────────────────────────────────────────
    public List<TcItem>       getTestCases()                    { return testCases; }
    public void               setTestCases(List<TcItem> v)     { this.testCases = v; totalCases = v.size(); }

    public List<TcItemResult> getResults()                      { return results; }
    public void               addResult(TcItemResult r)        {
        results.add(r);
        switch (r.getStatus()) {
            case "PASS"         -> passed++;
            case "FAIL"         -> failed++;
            case "BLOCKED"      -> blocked++;
            case "SKIPPED"      -> skipped++;
            default             -> notExecuted++;
        }
    }

    public List<String> getLogs()  { return logs; }

    // ── progress ──────────────────────────────────────────────────────────────
    public int getCurrentIndex()            { return currentIndex; }
    public void setCurrentIndex(int v)      { this.currentIndex = v; }

    public String getSerial()               { return serial; }
    public void   setSerial(String v)       { this.serial = v; }

    public String getCurrentTcId()          { return currentTcId; }
    public String getCurrentTcName()        { return currentTcName; }
    public void   setCurrentTc(String id, String name) { this.currentTcId = id; this.currentTcName = name; }

    public String getCurrentStep()          { return currentStep; }
    public void   setCurrentStep(String v)  { this.currentStep = v; }

    public int getTotalCases()              { return totalCases; }
    public int getExecuted()                { return results.size(); }
    public int getPassed()                  { return passed; }
    public int getFailed()                  { return failed; }
    public int getBlocked()                 { return blocked; }
    public int getSkipped()                 { return skipped; }
    public int getNotExecuted()             { return notExecuted; }
    public double getPassPct() {
        int ex = results.size();
        return ex == 0 ? 0.0 : (passed * 100.0 / ex);
    }

    /** Estimated ms remaining based on current average time per TC. */
    public long getEtaMs() {
        int done = results.size();
        if (done == 0 || startTime == 0) return -1;
        long elapsed = System.currentTimeMillis() - startTime;
        long avgPerTc = elapsed / done;
        int remaining = totalCases - done;
        return remaining > 0 ? remaining * avgPerTc : 0;
    }

    // ── timestamps ────────────────────────────────────────────────────────────
    public long   getStartTime()            { return startTime; }
    public void   setStartTime(long v)      { this.startTime = v; }

    public long   getFinishedAt()           { return finishedAt; }
    public void   setFinishedAt(long v)     { this.finishedAt = v; }

    // ── error ─────────────────────────────────────────────────────────────────
    public String getError()                { return error; }
    public void   setError(String v)        { this.error = v; }
}
