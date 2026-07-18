package com.vasundhara.atf.aiproduct;

public class QueueEntry {

    private String jobId;
    private int position;
    private int estimatedWaitSeconds;
    private BuildStatus status;
    private String appName;

    public QueueEntry(AiBuildJob job, int position) {
        this.jobId = job.getId();
        this.position = position;
        this.estimatedWaitSeconds = job.getEstimatedWaitSeconds() != null ? job.getEstimatedWaitSeconds() : 0;
        this.status = job.getStatus();
        this.appName = job.getAppName();
    }

    public String getJobId() { return jobId; }
    public int getPosition() { return position; }
    public int getEstimatedWaitSeconds() { return estimatedWaitSeconds; }
    public BuildStatus getStatus() { return status; }
    public String getAppName() { return appName; }
}
