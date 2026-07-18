package com.vasundhara.atf.ads;

import java.util.*;

/**
 * Full result of an ad-monetisation analysis run. Contains both static-analysis
 * findings (SDK detection, ad types, placement counts) and dynamic findings
 * (lifecycle events captured from device logcat when a device is available).
 */
public class AdAnalysisResult {

    /* ---- inner types ---- */

    /** A discovered ad placement (ad unit ID + inferred type + where we found it). */
    public record AdPlacement(String adUnitId, String adType, String source, boolean testId) {}

    /** A quality or integration issue found during analysis. */
    public record AdIssue(String severity, String category, String title, String description) {}

    /** One row in the category-wise Pass/Fail report. */
    public record AdCategoryReport(String name, String status, int found, int checked, String details) {}

    /** One ad-lifecycle event parsed from logcat. */
    public record AdLifecycleEvent(String timestamp, String eventType, String adType, String detail, boolean error) {}

    /** Per-placement end-to-end result: the actual observed behaviour of one ad placement. */
    public record AdPlacementResult(String adUnitId, String adType, String screen, String status,
                                    boolean loaded, boolean failed, boolean displayed,
                                    boolean impression, boolean clicked,
                                    String errorCode, String notes) {}

    /* ---- run mode ---- */
    private String adMode = "TEST"; // TEST (Google test ad units) | LIVE (real ad loading)

    /* ---- SDK detection ---- */
    private boolean admobSdkDetected;
    private boolean firebaseSdkDetected;
    private List<String> mediationSdks = new ArrayList<>();
    private String admobAppId;

    /* ---- static analysis ---- */
    private int totalAdsFound;
    private Map<String, Integer> typeDistribution = new LinkedHashMap<>();
    private List<AdPlacement> placements = new ArrayList<>();

    /* ---- dynamic (logcat) lifecycle counts ---- */
    private int requestCount;
    private int loadSuccessCount;
    private int loadFailureCount;
    private int impressionCount;
    private int clickCount;
    private int revenueEventCount;
    private int errorCount;
    private boolean deviceTestRun;
    private boolean adClicksSkipped; // true in LIVE mode — ad elements are never tapped

    /* ---- issues ---- */
    private List<AdIssue> issues = new ArrayList<>();

    /* ---- overall health ---- */
    private String overallHealth = "UNKNOWN";
    private String healthSummary;

    /* ---- per-category pass/fail report ---- */
    private List<AdCategoryReport> categoryReport = new ArrayList<>();

    /* ---- raw lifecycle event log ---- */
    private List<AdLifecycleEvent> lifecycleEvents = new ArrayList<>();

    /* ---- per-placement end-to-end results ---- */
    private List<AdPlacementResult> placementResults = new ArrayList<>();

    /* ---- getters / setters ---- */

    public String getAdMode() { return adMode; }
    public void setAdMode(String v) { this.adMode = v; }

    public boolean isAdmobSdkDetected() { return admobSdkDetected; }
    public void setAdmobSdkDetected(boolean v) { this.admobSdkDetected = v; }

    public boolean isFirebaseSdkDetected() { return firebaseSdkDetected; }
    public void setFirebaseSdkDetected(boolean v) { this.firebaseSdkDetected = v; }

    public List<String> getMediationSdks() { return mediationSdks; }
    public void setMediationSdks(List<String> v) { this.mediationSdks = v; }

    public String getAdmobAppId() { return admobAppId; }
    public void setAdmobAppId(String v) { this.admobAppId = v; }

    public int getTotalAdsFound() { return totalAdsFound; }
    public void setTotalAdsFound(int v) { this.totalAdsFound = v; }

    public Map<String, Integer> getTypeDistribution() { return typeDistribution; }
    public void setTypeDistribution(Map<String, Integer> v) { this.typeDistribution = v; }

    public List<AdPlacement> getPlacements() { return placements; }
    public void setPlacements(List<AdPlacement> v) { this.placements = v; }

    public int getRequestCount() { return requestCount; }
    public void setRequestCount(int v) { this.requestCount = v; }

    public int getLoadSuccessCount() { return loadSuccessCount; }
    public void setLoadSuccessCount(int v) { this.loadSuccessCount = v; }

    public int getLoadFailureCount() { return loadFailureCount; }
    public void setLoadFailureCount(int v) { this.loadFailureCount = v; }

    public int getImpressionCount() { return impressionCount; }
    public void setImpressionCount(int v) { this.impressionCount = v; }

    public int getClickCount() { return clickCount; }
    public void setClickCount(int v) { this.clickCount = v; }

    public int getRevenueEventCount() { return revenueEventCount; }
    public void setRevenueEventCount(int v) { this.revenueEventCount = v; }

    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int v) { this.errorCount = v; }

    public boolean isDeviceTestRun() { return deviceTestRun; }
    public void setDeviceTestRun(boolean v) { this.deviceTestRun = v; }

    public boolean isAdClicksSkipped() { return adClicksSkipped; }
    public void setAdClicksSkipped(boolean v) { this.adClicksSkipped = v; }

    public List<AdIssue> getIssues() { return issues; }
    public void setIssues(List<AdIssue> v) { this.issues = v; }

    public String getOverallHealth() { return overallHealth; }
    public void setOverallHealth(String v) { this.overallHealth = v; }

    public String getHealthSummary() { return healthSummary; }
    public void setHealthSummary(String v) { this.healthSummary = v; }

    public List<AdCategoryReport> getCategoryReport() { return categoryReport; }
    public void setCategoryReport(List<AdCategoryReport> v) { this.categoryReport = v; }

    public List<AdLifecycleEvent> getLifecycleEvents() { return lifecycleEvents; }
    public void setLifecycleEvents(List<AdLifecycleEvent> v) { this.lifecycleEvents = v; }

    public List<AdPlacementResult> getPlacementResults() { return placementResults; }
    public void setPlacementResults(List<AdPlacementResult> v) { this.placementResults = v; }

    /* convenience summary counts derived from per-placement results */
    public long getPlacementsLoaded()     { return placementResults.stream().filter(AdPlacementResult::loaded).count(); }
    public long getPlacementsFailed()     { return placementResults.stream().filter(AdPlacementResult::failed).count(); }
    public long getPlacementsDisplayed()  { return placementResults.stream().filter(AdPlacementResult::displayed).count(); }
    public long getPlacementsImpression() { return placementResults.stream().filter(AdPlacementResult::impression).count(); }
    public long getPlacementsClicked()    { return placementResults.stream().filter(AdPlacementResult::clicked).count(); }
}
