package com.vasundhara.atf.aiproduct;

public class BuildResponse {

    private String jobId;
    private BuildStatus status;
    private Integer queuePosition;
    private Integer estimatedWaitSeconds;
    private String error;

    public static BuildResponse ok(String jobId, BuildStatus status) {
        BuildResponse r = new BuildResponse();
        r.jobId = jobId;
        r.status = status;
        return r;
    }

    public static BuildResponse queued(String jobId, int position, int waitSeconds) {
        BuildResponse r = new BuildResponse();
        r.jobId = jobId;
        r.status = BuildStatus.QUEUED;
        r.queuePosition = position;
        r.estimatedWaitSeconds = waitSeconds;
        return r;
    }

    public static BuildResponse error(String message) {
        BuildResponse r = new BuildResponse();
        r.error = message;
        return r;
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public BuildStatus getStatus() { return status; }
    public void setStatus(BuildStatus status) { this.status = status; }
    public Integer getQueuePosition() { return queuePosition; }
    public void setQueuePosition(Integer queuePosition) { this.queuePosition = queuePosition; }
    public Integer getEstimatedWaitSeconds() { return estimatedWaitSeconds; }
    public void setEstimatedWaitSeconds(Integer estimatedWaitSeconds) { this.estimatedWaitSeconds = estimatedWaitSeconds; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
