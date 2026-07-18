package com.vasundhara.atf.report;

import com.vasundhara.atf.db.service.RunPersistenceService;
import com.vasundhara.atf.model.RunState;
import com.vasundhara.atf.model.TestRun;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory registry of all runs in this server's lifetime. Backs the dashboard run
 * list and supplies the regression category with the previous completed run for an app.
 *
 * <p>On startup, completed runs are restored from the database so run history survives
 * server restarts. On every {@link #save}, the run is also persisted to the database.
 */
@Component
public class ReportStore {

    private static final Logger log = LoggerFactory.getLogger(ReportStore.class);

    private final ConcurrentHashMap<String, TestRun> runs = new ConcurrentHashMap<>();
    private final RunPersistenceService persistence;

    public ReportStore(RunPersistenceService persistence) {
        this.persistence = persistence;
    }

    /** Restore all previously persisted runs into the in-memory cache on startup. */
    @PostConstruct
    void restoreFromDatabase() {
        try {
            List<TestRun> restored = persistence.loadAll();
            restored.forEach(r -> runs.put(r.getId(), r));
            if (!restored.isEmpty()) {
                log.info("Restored {} run(s) from database.", restored.size());
            }
        } catch (Exception e) {
            log.warn("Could not restore runs from database on startup: {}", e.getMessage());
        }
    }

    public void save(TestRun run) {
        runs.put(run.getId(), run);
        persistence.syncRun(run);
    }

    public TestRun get(String id) {
        return runs.get(id);
    }

    /** All runs, newest first. */
    public List<TestRun> all() {
        return runs.values().stream()
                .sorted(Comparator.comparingLong(TestRun::getCreatedAtMillis).reversed())
                .collect(Collectors.toList());
    }

    /** Remove a run from the in-memory cache and the database. Returns true if it existed. */
    public boolean delete(String id) {
        TestRun removed = runs.remove(id);
        if (removed != null) {
            persistence.deleteRun(id);
        }
        return removed != null;
    }

    /** The most recent completed run for the same package, excluding the given run id. */
    public Optional<TestRun> findBaseline(String packageName, String excludeRunId) {
        if (packageName == null) return Optional.empty();
        return all().stream()
                .filter(r -> r.getState() == RunState.COMPLETED)
                .filter(r -> !r.getId().equals(excludeRunId))
                .filter(r -> r.getApkInfo() != null
                        && packageName.equals(r.getApkInfo().getPackageName()))
                .findFirst();
    }
}
