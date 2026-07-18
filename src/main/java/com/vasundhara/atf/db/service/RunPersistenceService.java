package com.vasundhara.atf.db.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.db.entity.FindingEntity;
import com.vasundhara.atf.db.entity.RunEntity;
import com.vasundhara.atf.db.entity.TestResultEntity;
import com.vasundhara.atf.db.repository.RunRepository;
import com.vasundhara.atf.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Converts between in-memory {@link TestRun} objects and the JPA entity graph
 * ({@link RunEntity} → {@link TestResultEntity} → {@link FindingEntity}).
 *
 * <p>All methods are wrapped in try/catch so a DB failure never propagates to the
 * test-execution or HTTP-response layer. The in-memory {@code ReportStore} remains
 * the authoritative live cache; this service provides durable persistence.
 */
@Service
public class RunPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RunPersistenceService.class);

    private final RunRepository runRepo;
    private final ObjectMapper mapper;

    public RunPersistenceService(RunRepository runRepo, ObjectMapper mapper) {
        this.runRepo = runRepo;
        this.mapper = mapper;
    }

    /**
     * Upsert a run into the database.
     * For active (non-terminal) runs only the scalar fields are updated.
     * For completed/failed/cancelled runs the full result + findings graph is written.
     */
    @Transactional
    public void syncRun(TestRun run) {
        try {
            RunEntity entity = runRepo.findById(run.getId()).orElse(new RunEntity(run.getId()));
            mapScalars(run, entity);
            if (isTerminal(run.getState())) {
                mapResultsAndFindings(run, entity);
            }
            runRepo.save(entity);
        } catch (Exception e) {
            log.debug("DB sync failed for run {}: {}", run.getId(), e.getMessage());
        }
    }

    /**
     * Load all persisted runs and reconstruct {@link TestRun} objects for the in-memory store.
     * Called once on application startup so runs survive server restarts.
     */
    @Transactional(readOnly = true)
    public List<TestRun> loadAll() {
        try {
            return runRepo.findAllByOrderByCreatedAtDesc().stream()
                    .map(this::toModel)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not restore runs from database: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── entity ← model ──────────────────────────────────────────────────────

    private void mapScalars(TestRun run, RunEntity e) {
        e.setState(run.getState());
        e.setApkFileName(run.getApkFileName());
        e.setDeviceSerial(run.getDeviceSerial());
        e.setError(run.getError());
        e.setCreatedAt(run.getCreatedAtMillis());
        e.setStartedAt(run.getStartedAtMillis() == 0 ? null : run.getStartedAtMillis());
        e.setFinishedAt(run.getFinishedAtMillis() == 0 ? null : run.getFinishedAtMillis());
        e.setCategories(String.join(",", run.getSelectedCategories()));
        e.setCompatVersions(run.getCompatVersions());
        e.setModule(run.getModule());

        ApkInfo apk = run.getApkInfo();
        if (apk != null) {
            e.setPackageName(apk.getPackageName());
            e.setAppLabel(apk.getApplicationLabel());
            e.setVersionName(apk.getVersionName());
            e.setVersionCode(apk.getVersionCode());
            e.setMinSdk(apk.getMinSdkVersion());
            e.setTargetSdk(apk.getTargetSdkVersion());
            e.setApkSizeBytes(apk.getApkSizeBytes());
        }
    }

    private void mapResultsAndFindings(TestRun run, RunEntity e) {
        try {
            e.setStepsJson(mapper.writeValueAsString(run.getExecutionSteps()));
        } catch (Exception ignored) {}

        e.getTestResults().clear();
        for (TestResult result : run.getResults()) {
            TestResultEntity re = new TestResultEntity();
            re.setRun(e);
            re.setCategoryKey(result.getCategoryKey());
            re.setCategoryName(result.getCategoryName());
            re.setStatus(result.getStatus());
            re.setStartedAt(result.getStartedAtMillis() == 0 ? null : result.getStartedAtMillis());
            re.setFinishedAt(result.getFinishedAtMillis() == 0 ? null : result.getFinishedAtMillis());
            re.setSummary(result.getSummary());

            for (Finding f : result.getFindings()) {
                FindingEntity fe = new FindingEntity(f.id());
                fe.setTestResult(re);
                fe.setSeverity(f.severity());
                fe.setTitle(f.title());
                fe.setDetail(f.detail());
                fe.setEvidence(f.evidence());
                fe.setTsMillis(f.timestampMillis());
                fe.setOccurrenceCount(f.occurrenceCount());
                re.getFindings().add(fe);
            }
            e.getTestResults().add(re);
        }
    }

    // ── model ← entity ──────────────────────────────────────────────────────

    private TestRun toModel(RunEntity e) {
        List<String> cats = (e.getCategories() == null || e.getCategories().isBlank())
                ? new ArrayList<>()
                : Arrays.asList(e.getCategories().split(","));

        TestRun run = new TestRun(e.getRunId(), e.getApkFileName(), cats, e.getCreatedAt());
        run.setState(e.getState());
        run.setDeviceSerial(e.getDeviceSerial());
        run.setError(e.getError());
        if (e.getStartedAt() != null) run.setStartedAtMillis(e.getStartedAt());
        if (e.getFinishedAt() != null) run.setFinishedAtMillis(e.getFinishedAt());
        run.setCompatVersions(e.getCompatVersions());
        run.setModule(e.getModule());

        if (e.getPackageName() != null) {
            ApkInfo apk = new ApkInfo();
            apk.setPackageName(e.getPackageName());
            apk.setApplicationLabel(e.getAppLabel());
            apk.setVersionName(e.getVersionName());
            if (e.getVersionCode() != null) apk.setVersionCode(e.getVersionCode());
            if (e.getMinSdk() != null) apk.setMinSdkVersion(e.getMinSdk());
            if (e.getTargetSdk() != null) apk.setTargetSdkVersion(e.getTargetSdk());
            if (e.getApkSizeBytes() != null) apk.setApkSizeBytes(e.getApkSizeBytes());
            run.setApkInfo(apk);
        }

        for (TestResultEntity re : e.getTestResults()) {
            TestResult result = new TestResult(re.getCategoryKey(), re.getCategoryName());
            result.setStatus(re.getStatus() != null ? re.getStatus() : TestStatus.SKIPPED);
            if (re.getStartedAt() != null) result.setStartedAtMillis(re.getStartedAt());
            if (re.getFinishedAt() != null) result.setFinishedAtMillis(re.getFinishedAt());
            if (re.getSummary() != null) result.setSummary(re.getSummary());

            for (FindingEntity fe : re.getFindings()) {
                result.addFinding(new Finding(
                        fe.getFindingId(),
                        fe.getSeverity(),
                        fe.getTitle(),
                        fe.getDetail(),
                        fe.getEvidence(),
                        fe.getTsMillis(),
                        fe.getOccurrenceCount()));
            }
            run.getResults().add(result);
        }

        // Restore execution steps from JSON if present
        if (e.getStepsJson() != null) {
            try {
                @SuppressWarnings("unchecked")
                List<String> steps = mapper.readValue(e.getStepsJson(), List.class);
                steps.forEach(run::addExecutionStep);
            } catch (Exception ignored) {}
        }

        return run;
    }

    /** Delete a run and all its child entities from the database. */
    @Transactional
    public void deleteRun(String id) {
        try {
            runRepo.deleteById(id);
        } catch (Exception e) {
            log.debug("DB delete failed for run {}: {}", id, e.getMessage());
        }
    }

    private static boolean isTerminal(RunState state) {
        return state == RunState.COMPLETED || state == RunState.FAILED || state == RunState.CANCELLED;
    }
}
