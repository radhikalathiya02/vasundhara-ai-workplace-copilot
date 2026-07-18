package com.vasundhara.atf.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accumulating result for one test category. Categories push findings, free-form
 * step log lines and numeric/string metrics into this object as they run.
 */
public class TestResult {

    private final String categoryKey;
    private final String categoryName;
    private TestStatus status = TestStatus.PENDING;
    private long startedAtMillis;
    private long finishedAtMillis;
    private String summary = "";

    private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());
    private final List<String> steps = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> metrics = Collections.synchronizedMap(new LinkedHashMap<>());

    // Dedup index: key → the Finding currently in the findings list.
    // Key = "SEVERITY|normalizedTitle|screenHint" so the same defect type on the same screen
    // is counted as one occurrence regardless of how many widgets triggered it.
    private final Map<String, Finding> findingIndex = new ConcurrentHashMap<>();
    private static final Pattern ACTIVITY_PAT =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:Activity|Fragment|Screen|Dialog)");

    public TestResult(String categoryKey, String categoryName) {
        this.categoryKey = categoryKey;
        this.categoryName = categoryName;
    }

    public void start() {
        if (startedAtMillis == 0) {
            this.startedAtMillis = System.currentTimeMillis();
        }
        this.status = TestStatus.RUNNING;
    }

    public void finish() {
        this.finishedAtMillis = System.currentTimeMillis();
        if (status == TestStatus.RUNNING) {
            status = deriveStatusFromFindings();
        }
    }

    /** PASSED if no findings worse than INFO; WARNING for LOW/MEDIUM; FAILED for HIGH/CRITICAL. */
    private TestStatus deriveStatusFromFindings() {
        TestStatus derived = TestStatus.PASSED;
        synchronized (findings) {
            for (Finding f : findings) {
                switch (f.severity()) {
                    case CRITICAL, HIGH -> {
                        return TestStatus.FAILED;
                    }
                    case MEDIUM, LOW -> derived = TestStatus.WARNING;
                    default -> { /* INFO does not change status */ }
                }
            }
        }
        return derived;
    }

    public void step(String message) {
        steps.add(message);
    }

    /**
     * Add a finding, deduplicating by (severity, title, screen).
     * If an identical defect has already been reported for the same screen and issue type,
     * its occurrence count is incremented and no new entry is created. Only the first
     * screenshot is kept as evidence.
     */
    public synchronized void addFinding(Finding finding) {
        String key = dedupKey(finding.severity(), finding.title(), finding.detail());
        Finding prev = findingIndex.get(key);
        if (prev != null) {
            Finding updated = prev.withOccurrenceCount(prev.occurrenceCount() + 1);
            findingIndex.put(key, updated);
            int idx = findings.indexOf(prev);
            if (idx >= 0) findings.set(idx, updated);
        } else {
            findingIndex.put(key, finding);
            findings.add(finding);
        }
    }

    public synchronized void addFinding(Severity severity, String title, String detail) {
        addFinding(Finding.of(severity, title, detail));
    }

    public synchronized void addFinding(Severity severity, String title, String detail, String evidence) {
        addFinding(Finding.of(severity, title, detail, evidence));
    }

    private String dedupKey(Severity severity, String title, String detail) {
        String norm = (title == null ? "" : title).trim().toLowerCase().replaceAll("\\s+", " ");
        // Include the first Activity/Fragment/Screen/Dialog class name found in either title
        // or detail so that the same issue on two different screens remains separate.
        String screen = firstActivityName(title, detail);
        return severity.name() + "|" + norm + "|" + screen;
    }

    private static String firstActivityName(String title, String detail) {
        for (String s : new String[]{title == null ? "" : title, detail == null ? "" : detail}) {
            Matcher m = ACTIVITY_PAT.matcher(s);
            if (m.find()) return m.group();
        }
        return "";
    }

    /**
     * Cross-category de-duplication. Removes any finding whose (severity | normalized-title |
     * screen) key is already present in {@code globalSeen} — i.e. an identical defect that an
     * earlier-running category in the run already reported — so the aggregated run surfaces each
     * real bug exactly once instead of Functional + UI/UX + Regression all re-reporting it.
     *
     * <p>The kept findings' keys are added into {@code globalSeen} so later categories dedup
     * against them too. INFO findings are never cross-deduped: they are per-category summaries
     * (coverage %, screens explored, "no issues found" notes), not defects, and each category's
     * summary is legitimately its own. Returns the number of findings removed.
     */
    public synchronized int pruneCrossCategoryDuplicates(Set<String> globalSeen) {
        List<Finding> kept = new ArrayList<>();
        int removed = 0;
        synchronized (findings) {
            for (Finding f : findings) {
                if (f.severity() == Severity.INFO) { kept.add(f); continue; }
                String key = dedupKey(f.severity(), f.title(), f.detail());
                if (globalSeen.add(key)) kept.add(f); else removed++;
            }
            findings.clear();
            findings.addAll(kept);
        }
        findingIndex.clear();
        for (Finding f : kept) {
            findingIndex.put(dedupKey(f.severity(), f.title(), f.detail()), f);
        }
        return removed;
    }

    public void metric(String key, Object value) {
        metrics.put(key, value);
    }

    public long getDurationMillis() {
        if (startedAtMillis == 0) return 0;
        long end = finishedAtMillis == 0 ? System.currentTimeMillis() : finishedAtMillis;
        return end - startedAtMillis;
    }

    public int findingCount(Severity severity) {
        int count = 0;
        synchronized (findings) {
            for (Finding f : findings) {
                if (f.severity() == severity) count++;
            }
        }
        return count;
    }

    public String getCategoryKey() { return categoryKey; }
    public String getCategoryName() { return categoryName; }

    public TestStatus getStatus() { return status; }
    public void setStatus(TestStatus status) { this.status = status; }

    public long getStartedAtMillis() { return startedAtMillis; }
    public void setStartedAtMillis(long startedAtMillis) { this.startedAtMillis = startedAtMillis; }

    public long getFinishedAtMillis() { return finishedAtMillis; }
    public void setFinishedAtMillis(long finishedAtMillis) { this.finishedAtMillis = finishedAtMillis; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<Finding> getFindings() { return findings; }
    public List<String> getSteps() { return steps; }
    public Map<String, Object> getMetrics() { return metrics; }
}
