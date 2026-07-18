package com.vasundhara.atf.ads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle state for an Ad Analysis session.
 * Progresses: SETUP → ANALYZING → COMPLETED (or FAILED at any step).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdAnalysisSession {

    public enum State { SETUP, ANALYZING, COMPLETED, STOPPED, FAILED }

    // id/createdAt are conceptually immutable (set once, never reassigned by any caller) but are
    // NOT `final` — the @JsonCreator constructor below needs to assign them when Jackson restores
    // a session from its persisted snapshot after a server restart (see SessionPersistenceService).
    private String id;
    private volatile State state = State.SETUP;
    private volatile boolean stopRequested = false;
    private String apkFileName;
    private String packageName;
    private String adMode = "TEST"; // TEST | LIVE
    private AdAnalysisResult result;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private String error;
    private long createdAt = System.currentTimeMillis();
    private long finishedAt;

    public AdAnalysisSession(String id) { this.id = id; }

    /**
     * Restores a session from its persisted JSON snapshot (see {@code SessionPersistenceService}).
     * Property names match this class's own getters exactly, since that's what produced the JSON
     * being read back in. Not used by normal (live) session creation — only by
     * {@code AdAnalysisSessionStore}'s startup restore.
     */
    @JsonCreator
    private AdAnalysisSession(
            @JsonProperty("id") String id,
            @JsonProperty("state") State state,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("apkFileName") String apkFileName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("adMode") String adMode,
            @JsonProperty("result") AdAnalysisResult result,
            @JsonProperty("logs") List<String> logs,
            @JsonProperty("error") String error,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("finishedAt") long finishedAt) {
        this.id = id;
        if (state != null) this.state = state;
        this.stopRequested = stopRequested;
        this.apkFileName = apkFileName;
        this.packageName = packageName;
        if (adMode != null) this.adMode = adMode;
        this.result = result;
        if (logs != null) this.logs.addAll(logs);
        this.error = error;
        this.createdAt = createdAt;
        this.finishedAt = finishedAt;
    }

    public void addLog(String msg) {
        logs.add("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
    }

    public String getId() { return id; }
    public State getState() { return state; }
    public void setState(State s) { this.state = s; }
    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String n) { this.apkFileName = n; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String p) { this.packageName = p; }
    public String getAdMode() { return adMode; }
    public void setAdMode(String adMode) { this.adMode = adMode; }
    public AdAnalysisResult getResult() { return result; }
    public void setResult(AdAnalysisResult r) { this.result = r; }
    public List<String> getLogs() { return logs; }
    public String getError() { return error; }
    public void setError(String e) { this.error = e; }
    public long getCreatedAt() { return createdAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long t) { this.finishedAt = t; }

    public void requestStop() { this.stopRequested = true; }
    public boolean isStopRequested() { return stopRequested; }
}
