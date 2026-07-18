package com.vasundhara.atf.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle state for a Remote Config validation workflow. Progresses through
 * SETUP → FETCHING → FETCHED → PUBLISHING → PUBLISHED → TESTING → COMPLETED
 * (or FAILED at any step). Sensitive fields are annotated with {@link JsonIgnore}
 * so they are never returned over the REST API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteConfigSession {

    public enum State {
        SETUP, FETCHING, FETCHED, PUBLISHING, PUBLISHED, TESTING, COMPLETED, FAILED, STOPPED
    }

    // id/createdAt are conceptually immutable (set once, never reassigned by any caller) but are
    // NOT `final` — the @JsonCreator constructor below needs to assign them when Jackson restores
    // a session from its persisted snapshot after a server restart (see SessionPersistenceService).
    private String id;
    private volatile State state = State.SETUP;
    private volatile boolean stopRequested = false;

    private String projectId;
    @JsonIgnore private String serviceAccountJson;
    @JsonIgnore private String templateJson;
    private String etag;

    private List<RemoteConfigFlag> flags = new ArrayList<>();
    private Map<String, String> pendingChanges = new LinkedHashMap<>();

    private final List<String> logs = new CopyOnWriteArrayList<>();

    private String apkFileName;
    private String packageName;
    private List<RemoteConfigTestResult> testResults = Collections.synchronizedList(new ArrayList<>());

    private long createdAt = System.currentTimeMillis();
    private long finishedAt;
    private String error;

    public RemoteConfigSession(String id) {
        this.id = id;
    }

    /**
     * Restores a session from its persisted JSON snapshot (see {@code SessionPersistenceService}).
     * Property names match this class's own getters exactly (note: {@code serviceAccountJson}/
     * {@code templateJson} are {@link JsonIgnore}d and were never in the persisted JSON to begin
     * with, so they are not restored here — same as before this fix, since the session was never
     * round-tripped through JSON at all previously). Not used by normal (live) session creation —
     * only by {@code RemoteConfigSessionStore}'s startup restore.
     */
    @JsonCreator
    private RemoteConfigSession(
            @JsonProperty("id") String id,
            @JsonProperty("state") State state,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("projectId") String projectId,
            @JsonProperty("etag") String etag,
            @JsonProperty("flags") List<RemoteConfigFlag> flags,
            @JsonProperty("pendingChanges") Map<String, String> pendingChanges,
            @JsonProperty("logs") List<String> logs,
            @JsonProperty("apkFileName") String apkFileName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("testResults") List<RemoteConfigTestResult> testResults,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("finishedAt") long finishedAt,
            @JsonProperty("error") String error) {
        this.id = id;
        if (state != null) this.state = state;
        this.stopRequested = stopRequested;
        this.projectId = projectId;
        this.etag = etag;
        if (flags != null) this.flags = flags;
        if (pendingChanges != null) this.pendingChanges = pendingChanges;
        if (logs != null) this.logs.addAll(logs);
        this.apkFileName = apkFileName;
        this.packageName = packageName;
        if (testResults != null) this.testResults = Collections.synchronizedList(new ArrayList<>(testResults));
        this.createdAt = createdAt;
        this.finishedAt = finishedAt;
        this.error = error;
    }

    public void addLog(String msg) {
        logs.add("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
    }

    public String getId() { return id; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public void requestStop() { this.stopRequested = true; }
    public boolean isStopRequested() { return stopRequested; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    /** Used internally only; {@link JsonIgnore} prevents serialisation. */
    public String getServiceAccountJson() { return serviceAccountJson; }
    public void setServiceAccountJson(String serviceAccountJson) { this.serviceAccountJson = serviceAccountJson; }

    /** Used internally only; {@link JsonIgnore} prevents serialisation. */
    public String getTemplateJson() { return templateJson; }
    public void setTemplateJson(String templateJson) { this.templateJson = templateJson; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public List<RemoteConfigFlag> getFlags() { return flags; }
    public void setFlags(List<RemoteConfigFlag> flags) { this.flags = flags; }

    public Map<String, String> getPendingChanges() { return pendingChanges; }
    public void setPendingChanges(Map<String, String> pendingChanges) { this.pendingChanges = pendingChanges; }

    public List<String> getLogs() { return logs; }

    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String apkFileName) { this.apkFileName = apkFileName; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public List<RemoteConfigTestResult> getTestResults() { return testResults; }

    public long getCreatedAt() { return createdAt; }

    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
