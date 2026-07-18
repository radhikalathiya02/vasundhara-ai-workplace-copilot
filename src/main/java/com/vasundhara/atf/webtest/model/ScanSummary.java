package com.vasundhara.atf.webtest.model;

import com.vasundhara.atf.model.Severity;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate scorecard for a completed scan: overall + per-category health scores (0–100),
 * counts by severity and by category, and headline metadata. Computed once by the aggregator
 * from the final deduped {@link WebIssue} list and rendered at the top of the report.
 */
public class ScanSummary {

    private int overallScore = 100;
    private final Map<String, Integer> categoryScores = new LinkedHashMap<>();
    private final Map<String, Integer> countsBySeverity = new LinkedHashMap<>();
    private final Map<String, Integer> countsByCategory = new LinkedHashMap<>();
    private int totalIssues;
    private int pagesAnalyzed;

    /** Detected technology / server hints, if any (e.g. "nginx", "WordPress"). */
    private String techProfile;
    /** Performance headline from PageSpeed (0–100), or -1 when unavailable. */
    private int performanceScore = -1;
    /** Core Web Vitals headline strings for the homepage (LCP/CLS/INP), when available. */
    private final Map<String, String> coreWebVitals = new LinkedHashMap<>();

    public void compute(List<WebIssue> issues, int pagesAnalyzed) {
        this.pagesAnalyzed = pagesAnalyzed;
        this.totalIssues = issues.size();

        Map<Severity, Integer> sev = new EnumMap<>(Severity.class);
        Map<WebIssueCategory, Integer> cat = new EnumMap<>(WebIssueCategory.class);
        // Weighted penalty per category, converted to a 0–100 score.
        Map<WebIssueCategory, Integer> penalty = new EnumMap<>(WebIssueCategory.class);

        for (WebIssue i : issues) {
            sev.merge(i.getSeverity(), 1, Integer::sum);
            cat.merge(i.getCategory(), 1, Integer::sum);
            penalty.merge(i.getCategory(), severityPenalty(i.getSeverity()), Integer::sum);
        }

        for (Severity s : Severity.values()) {
            countsBySeverity.put(s.name(), sev.getOrDefault(s, 0));
        }
        for (WebIssueCategory c : WebIssueCategory.values()) {
            int count = cat.getOrDefault(c, 0);
            countsByCategory.put(c.name(), count);
            int p = penalty.getOrDefault(c, 0);
            categoryScores.put(c.name(), Math.max(0, 100 - p));
        }

        int totalPenalty = penalty.values().stream().mapToInt(Integer::intValue).sum();
        this.overallScore = Math.max(0, 100 - Math.min(100, totalPenalty / Math.max(1, WebIssueCategory.values().length / 3)));
    }

    /** Penalty points a single issue subtracts from its category score, by severity. */
    private static int severityPenalty(Severity s) {
        if (s == null) return 1;
        return switch (s) {
            case CRITICAL -> 20;
            case HIGH -> 10;
            case MEDIUM -> 4;
            case LOW -> 2;
            case INFO -> 0;
        };
    }

    public int getOverallScore() { return overallScore; }
    public Map<String, Integer> getCategoryScores() { return categoryScores; }
    public Map<String, Integer> getCountsBySeverity() { return countsBySeverity; }
    public Map<String, Integer> getCountsByCategory() { return countsByCategory; }
    public int getTotalIssues() { return totalIssues; }
    public int getPagesAnalyzed() { return pagesAnalyzed; }
    public String getTechProfile() { return techProfile; }
    public void setTechProfile(String techProfile) { this.techProfile = techProfile; }
    public int getPerformanceScore() { return performanceScore; }
    public void setPerformanceScore(int performanceScore) { this.performanceScore = performanceScore; }
    public Map<String, String> getCoreWebVitals() { return coreWebVitals; }
}
