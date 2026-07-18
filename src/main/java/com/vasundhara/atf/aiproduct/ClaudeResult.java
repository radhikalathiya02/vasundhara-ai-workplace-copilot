package com.vasundhara.atf.aiproduct;

public class ClaudeResult {

    private final boolean success;
    private final String sessionId;
    private final int exitCode;

    public ClaudeResult(boolean success, String sessionId, int exitCode) {
        this.success = success;
        this.sessionId = sessionId;
        this.exitCode = exitCode;
    }

    public boolean isSuccess() { return success; }
    public String getSessionId() { return sessionId; }
    public int getExitCode() { return exitCode; }
}
