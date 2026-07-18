package com.vasundhara.atf.smartexec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vasundhara.atf.smartexec.figma.FigmaFinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live + persisted state for one Smart Execution run. Entirely independent of the old New Test
 * module's {@code TestRun}/{@code TestResult} — its own session model, following the same
 * store/persistence shape as the framework's other module sessions (e.g. {@code AdAnalysisSession}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmartSession {

    public enum State { QUEUED, ANALYZING, INSTALLING, RUNNING, COMPLETED, FAILED, CANCELLED }

    private final String id;
    private volatile State state = State.QUEUED;
    private volatile boolean stopRequested = false;
    private String apkFileName;
    private String packageName;
    private String appLabel;
    private String deviceSerial;
    private List<String> selectedCategories = new CopyOnWriteArrayList<>();
    private String currentCategory = "";
    private final Map<String, String> categoryStatus = new LinkedHashMap<>();      // PENDING/RUNNING/COMPLETED/FAILED/SKIPPED
    private final Map<String, Long> categoryDurationMs = new LinkedHashMap<>();
    private String liveScreenName = "";
    private final List<String> executionSteps = new CopyOnWriteArrayList<>();
    private final List<String> consoleLog = new CopyOnWriteArrayList<>();
    private final List<SmartFinding> findings = new CopyOnWriteArrayList<>();
    // Coverage snapshot, refreshed by SmartCoverageEngine after each category.
    private int totalScreens;
    private int testedScreens;
    private int screenCoveragePct;
    private int featureCoveragePct;
    private List<String> untestedFlows = new CopyOnWriteArrayList<>();
    private Map<String, Object> apkReport = new LinkedHashMap<>();
    private String error;
    private long createdAt = System.currentTimeMillis();
    private long startedAt;
    private long finishedAt;

    // ── Figma design comparison (optional — set only when a Figma URL was pasted) ──────────────
    private String figmaUrl = "";
    // null (not requested) / SKIPPED_NO_TOKEN / FAILED / COMPLETED
    private String figmaStatus;
    private String figmaNote = "";
    private List<FigmaFinding> figmaFindings = new CopyOnWriteArrayList<>();
    // screenName -> evidence filename; every screen Smart Execution captured a screenshot of
    // during this run's crawl, accumulated across whichever categories ran (first seen wins).
    private final Map<String, String> screenShots = new ConcurrentHashMap<>();

    public SmartSession(String id) { this.id = id; }

    @JsonCreator
    private SmartSession(
            @JsonProperty("id") String id,
            @JsonProperty("state") State state,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("apkFileName") String apkFileName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("appLabel") String appLabel,
            @JsonProperty("deviceSerial") String deviceSerial,
            @JsonProperty("selectedCategories") List<String> selectedCategories,
            @JsonProperty("currentCategory") String currentCategory,
            @JsonProperty("categoryStatus") Map<String, String> categoryStatus,
            @JsonProperty("categoryDurationMs") Map<String, Long> categoryDurationMs,
            @JsonProperty("liveScreenName") String liveScreenName,
            @JsonProperty("executionSteps") List<String> executionSteps,
            @JsonProperty("consoleLog") List<String> consoleLog,
            @JsonProperty("findings") List<SmartFinding> findings,
            @JsonProperty("totalScreens") int totalScreens,
            @JsonProperty("testedScreens") int testedScreens,
            @JsonProperty("screenCoveragePct") int screenCoveragePct,
            @JsonProperty("featureCoveragePct") int featureCoveragePct,
            @JsonProperty("untestedFlows") List<String> untestedFlows,
            @JsonProperty("apkReport") Map<String, Object> apkReport,
            @JsonProperty("error") String error,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("startedAt") long startedAt,
            @JsonProperty("finishedAt") long finishedAt,
            @JsonProperty("figmaUrl") String figmaUrl,
            @JsonProperty("figmaStatus") String figmaStatus,
            @JsonProperty("figmaNote") String figmaNote,
            @JsonProperty("figmaFindings") List<FigmaFinding> figmaFindings,
            @JsonProperty("screenShots") Map<String, String> screenShots) {
        this.id = id;
        if (state != null) this.state = state;
        this.stopRequested = stopRequested;
        this.apkFileName = apkFileName;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.deviceSerial = deviceSerial;
        if (selectedCategories != null) this.selectedCategories.addAll(selectedCategories);
        this.currentCategory = currentCategory == null ? "" : currentCategory;
        if (categoryStatus != null) this.categoryStatus.putAll(categoryStatus);
        if (categoryDurationMs != null) this.categoryDurationMs.putAll(categoryDurationMs);
        this.liveScreenName = liveScreenName == null ? "" : liveScreenName;
        if (executionSteps != null) this.executionSteps.addAll(executionSteps);
        if (consoleLog != null) this.consoleLog.addAll(consoleLog);
        if (findings != null) this.findings.addAll(findings);
        this.totalScreens = totalScreens;
        this.testedScreens = testedScreens;
        this.screenCoveragePct = screenCoveragePct;
        this.featureCoveragePct = featureCoveragePct;
        if (untestedFlows != null) this.untestedFlows.addAll(untestedFlows);
        if (apkReport != null) this.apkReport.putAll(apkReport);
        this.error = error;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.figmaUrl = figmaUrl == null ? "" : figmaUrl;
        this.figmaStatus = figmaStatus;
        this.figmaNote = figmaNote == null ? "" : figmaNote;
        if (figmaFindings != null) this.figmaFindings.addAll(figmaFindings);
        if (screenShots != null) this.screenShots.putAll(screenShots);
    }

    // ── mutation helpers ────────────────────────────────────────────────────
    public void addStep(String msg) {
        executionSteps.add("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
    }
    public void addConsoleLine(String line) {
        consoleLog.add(line);
        while (consoleLog.size() > 500) consoleLog.remove(0);
    }
    public void addFinding(SmartFinding f) { findings.add(f); }
    public void setCategoryStatus(String key, String status) { categoryStatus.put(key, status); }
    public void setCategoryDuration(String key, long ms) { categoryDurationMs.put(key, ms); }
    public void requestStop() { stopRequested = true; }

    // ── getters/setters ─────────────────────────────────────────────────────
    public String getId() { return id; }
    public State getState() { return state; }
    public void setState(State s) { this.state = s; }
    public boolean isStopRequested() { return stopRequested; }
    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String v) { this.apkFileName = v; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String v) { this.packageName = v; }
    public String getAppLabel() { return appLabel; }
    public void setAppLabel(String v) { this.appLabel = v; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String v) { this.deviceSerial = v; }
    public List<String> getSelectedCategories() { return selectedCategories; }
    public void setSelectedCategories(List<String> v) { this.selectedCategories = new CopyOnWriteArrayList<>(v); }
    public String getCurrentCategory() { return currentCategory; }
    public void setCurrentCategory(String v) { this.currentCategory = v; }
    public Map<String, String> getCategoryStatus() { return categoryStatus; }
    public Map<String, Long> getCategoryDurationMs() { return categoryDurationMs; }
    public String getLiveScreenName() { return liveScreenName; }
    public void setLiveScreenName(String v) { this.liveScreenName = v; }
    public List<String> getExecutionSteps() { return executionSteps; }
    public List<String> getConsoleLog() { return consoleLog; }
    public List<SmartFinding> getFindings() { return findings; }
    public int getTotalScreens() { return totalScreens; }
    public void setTotalScreens(int v) { this.totalScreens = v; }
    public int getTestedScreens() { return testedScreens; }
    public void setTestedScreens(int v) { this.testedScreens = v; }
    public int getScreenCoveragePct() { return screenCoveragePct; }
    public void setScreenCoveragePct(int v) { this.screenCoveragePct = v; }
    public int getFeatureCoveragePct() { return featureCoveragePct; }
    public void setFeatureCoveragePct(int v) { this.featureCoveragePct = v; }
    public List<String> getUntestedFlows() { return untestedFlows; }
    public void setUntestedFlows(List<String> v) { this.untestedFlows = new CopyOnWriteArrayList<>(v); }
    public Map<String, Object> getApkReport() { return apkReport; }
    public void setApkReport(Map<String, Object> v) { this.apkReport = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long v) { this.startedAt = v; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long v) { this.finishedAt = v; }

    public String getFigmaUrl() { return figmaUrl; }
    public void setFigmaUrl(String v) { this.figmaUrl = v == null ? "" : v; }
    public String getFigmaStatus() { return figmaStatus; }
    public void setFigmaStatus(String v) { this.figmaStatus = v; }
    public String getFigmaNote() { return figmaNote; }
    public void setFigmaNote(String v) { this.figmaNote = v == null ? "" : v; }
    public List<FigmaFinding> getFigmaFindings() { return figmaFindings; }
    public void setFigmaFindings(List<FigmaFinding> v) { this.figmaFindings = new CopyOnWriteArrayList<>(v == null ? List.of() : v); }
    public Map<String, String> getScreenShots() { return screenShots; }
}
