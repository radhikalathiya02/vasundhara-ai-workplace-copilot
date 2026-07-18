package com.vasundhara.atf.localization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle state for an automatic localization run: detects the app's supported
 * languages and validates each one (translation + functionality). Progresses
 * SETUP → RUNNING → COMPLETED (or FAILED).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocalizationSession {

    public enum State { SETUP, RUNNING, COMPLETED, FAILED, STOPPED }

    // id/createdAt are conceptually immutable (set once, never reassigned by any caller) but are
    // NOT `final` — the @JsonCreator constructor below needs to assign them when Jackson restores
    // a session from its persisted snapshot after a server restart (see SessionPersistenceService).
    private String id;
    private volatile State state = State.SETUP;
    private volatile boolean stopRequested;
    private String apkFileName;
    private String packageName;
    private String baselineLocale = "default";
    /** Where the language list came from: in-app Language Settings screen, or APK resources. */
    private String languageSource = "—";
    /** The in-app Language Settings screen name, when detected. */
    private String languageScreen;
    private final List<LanguageResult> languages = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private String error;
    private long createdAt = System.currentTimeMillis();
    private long finishedAt;

    public LocalizationSession(String id) { this.id = id; }

    /**
     * Restores a session from its persisted JSON snapshot (see {@code SessionPersistenceService}).
     * Property names match this class's own getters exactly, since that's what produced the JSON
     * being read back in. Not used by normal (live) session creation — only by
     * {@code LocalizationSessionStore}'s startup restore.
     */
    @JsonCreator
    private LocalizationSession(
            @JsonProperty("id") String id,
            @JsonProperty("state") State state,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("apkFileName") String apkFileName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("baselineLocale") String baselineLocale,
            @JsonProperty("languageSource") String languageSource,
            @JsonProperty("languageScreen") String languageScreen,
            @JsonProperty("languages") List<LanguageResult> languages,
            @JsonProperty("logs") List<String> logs,
            @JsonProperty("error") String error,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("finishedAt") long finishedAt) {
        this.id = id;
        if (state != null) this.state = state;
        this.stopRequested = stopRequested;
        this.apkFileName = apkFileName;
        this.packageName = packageName;
        if (baselineLocale != null) this.baselineLocale = baselineLocale;
        if (languageSource != null) this.languageSource = languageSource;
        this.languageScreen = languageScreen;
        if (languages != null) this.languages.addAll(languages);
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
    /** Cooperative stop: set by the API, polled by the runner/crawler to halt promptly. */
    public void requestStop() { this.stopRequested = true; }
    public boolean isStopRequested() { return stopRequested; }
    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String n) { this.apkFileName = n; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String p) { this.packageName = p; }
    public String getBaselineLocale() { return baselineLocale; }
    public void setBaselineLocale(String b) { this.baselineLocale = b; }
    public String getLanguageSource() { return languageSource; }
    public void setLanguageSource(String s) { this.languageSource = s; }
    public String getLanguageScreen() { return languageScreen; }
    public void setLanguageScreen(String s) { this.languageScreen = s; }
    public List<LanguageResult> getLanguages() { return languages; }
    public List<String> getLogs() { return logs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getCreatedAt() { return createdAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long t) { this.finishedAt = t; }
}
