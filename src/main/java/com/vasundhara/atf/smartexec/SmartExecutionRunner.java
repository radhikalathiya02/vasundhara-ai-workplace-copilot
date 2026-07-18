package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.engine.ExecutionLockService;
import com.vasundhara.atf.report.RunBridgeService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;

/** Async entry point for a Smart Execution run — mirrors AdAnalysisRunner's finally-block shape. */
@Component
public class SmartExecutionRunner {

    private final SmartOrchestrator orchestrator;
    private final RunBridgeService runBridge;
    private final ExecutionLockService execLock;

    public SmartExecutionRunner(SmartOrchestrator orchestrator, RunBridgeService runBridge,
                                ExecutionLockService execLock) {
        this.orchestrator = orchestrator;
        this.runBridge = runBridge;
        this.execLock = execLock;
    }

    @Async("testRunExecutor")
    public void run(SmartSession session, File apkFile) {
        runBridge.start(session.getId(), "Smart Execution", session.getApkFileName(), session.getDeviceSerial());
        try {
            orchestrator.run(session, apkFile);
        } finally {
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }
}
