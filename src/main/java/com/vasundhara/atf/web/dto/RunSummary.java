package com.vasundhara.atf.web.dto;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.model.TestResult;
import com.vasundhara.atf.model.TestRun;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight projection of a run for the dashboard list (no findings/screenshots). */
public record RunSummary(
        String id,
        String apkFileName,
        String packageName,
        String versionName,
        String state,
        long createdAtMillis,
        long durationMillis,
        int categoryCount,
        String module,
        Map<String, Integer> severity,
        int scenariosTested) {

    public static RunSummary from(TestRun run) {
        Map<String, Integer> sev = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            sev.put(s.name(), run.getSeverityTally().getOrDefault(s, 0));
        }
        return new RunSummary(
                run.getId(),
                run.getApkFileName(),
                run.getApkInfo() != null ? run.getApkInfo().getPackageName() : null,
                run.getApkInfo() != null ? run.getApkInfo().getVersionName() : null,
                run.getState().name(),
                run.getCreatedAtMillis(),
                run.getDurationMillis(),
                run.getResults().size(),
                run.getModule() != null ? run.getModule() : "New Test",
                sev,
                scenariosTestedIn(run));
    }

    /**
     * Total AI-guided test scenarios (positive/negative/boundary/edge-case — generated
     * heuristically per-app by AppIntelligenceAnalyzer, no API key required, and executed by
     * FunctionalTest's Phase 11) actually run for this APK: passed + failed + skipped, summed
     * across every category result that recorded them. Generic across any app/category — reads
     * whichever category populated the metric, never assumes "functional" specifically.
     */
    private static int scenariosTestedIn(TestRun run) {
        int total = 0;
        for (TestResult r : run.getResults()) {
            Map<String, Object> m = r.getMetrics();
            total += intOf(m.get("scenariosPassed"))
                   + intOf(m.get("scenariosFailed"))
                   + intOf(m.get("scenariosSkipped"));
        }
        return total;
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
