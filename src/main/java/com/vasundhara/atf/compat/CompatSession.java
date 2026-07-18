package com.vasundhara.atf.compat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle state for an automatic multi-emulator compatibility run across
 * Android 9–16. Progresses SETUP → RUNNING → COMPLETED (or FAILED).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompatSession {

    public enum State { SETUP, RUNNING, COMPLETED, FAILED, STOPPED }

    // id/createdAt are conceptually immutable (set once, never reassigned by any caller) but are
    // NOT `final` — the @JsonCreator constructor below needs to assign them when Jackson restores
    // a session from its persisted snapshot after a server restart (see SessionPersistenceService).
    private String id;
    private volatile State state = State.SETUP;
    private volatile boolean stopRequested = false;
    private String apkFileName;
    private String packageName;
    private String scope = "All Android versions (9–16)";
    private int overallScore;
    private String overallStatus = "—";
    private final List<CompatVersionResult> versions = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> preCheckItems = new CopyOnWriteArrayList<>();
    private String error;
    private long createdAt = System.currentTimeMillis();
    private long finishedAt;

    public CompatSession(String id) { this.id = id; }

    /**
     * Restores a session from its persisted JSON snapshot (see {@code SessionPersistenceService}).
     * Property names match this class's own getters exactly, since that's what produced the JSON
     * being read back in. Not used by normal (live) session creation — only by
     * {@code CompatSessionStore}'s startup restore.
     */
    @JsonCreator
    private CompatSession(
            @JsonProperty("id") String id,
            @JsonProperty("state") State state,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("apkFileName") String apkFileName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("scope") String scope,
            @JsonProperty("overallScore") int overallScore,
            @JsonProperty("overallStatus") String overallStatus,
            @JsonProperty("versions") List<CompatVersionResult> versions,
            @JsonProperty("logs") List<String> logs,
            @JsonProperty("preCheckItems") List<Map<String, Object>> preCheckItems,
            @JsonProperty("error") String error,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("finishedAt") long finishedAt) {
        this.id = id;
        if (state != null) this.state = state;
        this.stopRequested = stopRequested;
        this.apkFileName = apkFileName;
        this.packageName = packageName;
        if (scope != null) this.scope = scope;
        this.overallScore = overallScore;
        if (overallStatus != null) this.overallStatus = overallStatus;
        if (versions != null) this.versions.addAll(versions);
        if (logs != null) this.logs.addAll(logs);
        if (preCheckItems != null) this.preCheckItems.addAll(preCheckItems);
        this.error = error;
        this.createdAt = createdAt;
        this.finishedAt = finishedAt;
    }

    public void addLog(String msg) {
        logs.add("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
    }

    public String getId() { return id; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public void requestStop() { this.stopRequested = true; }
    public boolean isStopRequested() { return stopRequested; }
    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String n) { this.apkFileName = n; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String p) { this.packageName = p; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int s) { this.overallScore = s; }
    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String s) { this.overallStatus = s; }
    public List<CompatVersionResult> getVersions() { return versions; }
    public List<String> getLogs() { return logs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getCreatedAt() { return createdAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long t) { this.finishedAt = t; }
    public List<Map<String, Object>> getPreCheckItems() { return preCheckItems; }
    public void setPreCheckItems(List<Map<String, Object>> items) {
        preCheckItems.clear();
        preCheckItems.addAll(items);
    }
}
