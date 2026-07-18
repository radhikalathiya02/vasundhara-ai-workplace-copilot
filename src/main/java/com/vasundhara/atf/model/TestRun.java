package com.vasundhara.atf.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A complete automated test run against one uploaded APK. Holds the static
 * analysis result, the per-category results and overall lifecycle state. Mutated
 * by the orchestrator as the run progresses and polled by the dashboard.
 */
public class TestRun {

    private final String id;
    private final String apkFileName;
    private final long createdAtMillis;

    private volatile RunState state = RunState.QUEUED;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private long startedAtMillis;
    private long finishedAtMillis;
    private String deviceSerial;
    private String error;
    private String module;

    private ApkInfo apkInfo;
    private String compatVersions; // comma-separated API levels for emulator matrix; null/empty = device-based
    private final List<String> selectedCategories;
    private final List<TestResult> results = Collections.synchronizedList(new ArrayList<>());
    private final List<String> executionSteps = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> liveProgress = new ConcurrentHashMap<>();

    public TestRun(String id, String apkFileName, List<String> selectedCategories) {
        this.id = id;
        this.apkFileName = apkFileName;
        this.selectedCategories = selectedCategories;
        this.createdAtMillis = System.currentTimeMillis();
    }

    /** Restores a persisted run preserving its original creation timestamp. */
    public TestRun(String id, String apkFileName, List<String> selectedCategories, long createdAtMillis) {
        this.id = id;
        this.apkFileName = apkFileName;
        this.selectedCategories = selectedCategories;
        this.createdAtMillis = createdAtMillis;
    }

    /** Aggregate count of categories by their final status. */
    public Map<TestStatus, Integer> getStatusTally() {
        Map<TestStatus, Integer> tally = new ConcurrentHashMap<>();
        synchronized (results) {
            for (TestResult r : results) {
                tally.merge(r.getStatus(), 1, Integer::sum);
            }
        }
        return tally;
    }

    /** Aggregate count of findings across all categories by severity. */
    public Map<Severity, Integer> getSeverityTally() {
        Map<Severity, Integer> tally = new ConcurrentHashMap<>();
        synchronized (results) {
            for (TestResult r : results) {
                for (Finding f : r.getFindings()) {
                    tally.merge(f.severity(), 1, Integer::sum);
                }
            }
        }
        return tally;
    }

    public long getDurationMillis() {
        if (startedAtMillis == 0) return 0;
        long end = finishedAtMillis == 0 ? System.currentTimeMillis() : finishedAtMillis;
        return end - startedAtMillis;
    }

    public String getId() { return id; }
    public String getApkFileName() { return apkFileName; }
    public long getCreatedAtMillis() { return createdAtMillis; }

    public RunState getState() { return state; }
    public void setState(RunState state) { this.state = state; }

    public void requestCancel() { cancelRequested.set(true); }
    public boolean isCancelRequested() { return cancelRequested.get(); }

    public long getStartedAtMillis() { return startedAtMillis; }
    public void setStartedAtMillis(long startedAtMillis) { this.startedAtMillis = startedAtMillis; }

    public long getFinishedAtMillis() { return finishedAtMillis; }
    public void setFinishedAtMillis(long finishedAtMillis) { this.finishedAtMillis = finishedAtMillis; }

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }

    public ApkInfo getApkInfo() { return apkInfo; }
    public void setApkInfo(ApkInfo apkInfo) { this.apkInfo = apkInfo; }

    public String getCompatVersions() { return compatVersions; }
    public void setCompatVersions(String compatVersions) { this.compatVersions = compatVersions; }

    public List<String> getSelectedCategories() { return selectedCategories; }
    public List<TestResult> getResults() { return results; }

    public void addExecutionStep(String step) { executionSteps.add(step); }
    public List<String> getExecutionSteps() { return executionSteps; }

    public Map<String, Object> getLiveProgress() { return liveProgress; }

    public void setLiveProgress(String key, Object value) {
        if (value == null) liveProgress.remove(key);
        else liveProgress.put(key, value);
    }

    public void clearLiveProgress() { liveProgress.clear(); }
}
