package com.vasundhara.atf.report;

import com.vasundhara.atf.model.RunState;
import com.vasundhara.atf.model.TestRun;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Creates and maintains shadow {@link TestRun} records for non-New-Test module sessions
 * (Localization, Remote Config, Test Case, Priority &amp; Logs, Compatibility Testing)
 * so every execution from every module appears in the Test Run history.
 */
@Service
public class RunBridgeService {

    private final ReportStore store;

    public RunBridgeService(ReportStore store) {
        this.store = store;
    }

    /**
     * Register a new in-progress run for a module session. Call at the start of execution
     * before the main work begins.
     */
    public void start(String sessionId, String module, String apkFileName, String deviceSerial) {
        String name = (apkFileName != null && !apkFileName.isBlank()) ? apkFileName : module;
        TestRun run = new TestRun(sessionId, name, List.of());
        run.setModule(module);
        run.setState(RunState.RUNNING);
        run.setStartedAtMillis(System.currentTimeMillis());
        if (deviceSerial != null && !deviceSerial.isBlank()) run.setDeviceSerial(deviceSerial);
        store.save(run);
    }

    /**
     * Mark the run as terminal and persist it. {@code stateStr} is the module session's own
     * state name (e.g. "COMPLETED", "FAILED", "STOPPED") which is mapped to {@link RunState}.
     */
    public void finish(String sessionId, String stateStr, String errorMessage) {
        TestRun run = store.get(sessionId);
        if (run == null) return;
        RunState rs = switch (stateStr != null ? stateStr.toUpperCase() : "") {
            case "COMPLETED" -> RunState.COMPLETED;
            case "FAILED"    -> RunState.FAILED;
            default          -> RunState.CANCELLED;
        };
        run.setState(rs);
        if (errorMessage != null && !errorMessage.isBlank()) run.setError(errorMessage);
        run.setFinishedAtMillis(System.currentTimeMillis());
        store.save(run);
    }
}
