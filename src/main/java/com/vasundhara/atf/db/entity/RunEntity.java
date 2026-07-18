package com.vasundhara.atf.db.entity;

import com.vasundhara.atf.model.RunState;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "runs")
public class RunEntity {

    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "apk_file_name")
    private String apkFileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunState state;

    @Column(name = "device_serial", length = 100)
    private String deviceSerial;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    /** Comma-separated list of selected test category keys. */
    @Column(columnDefinition = "TEXT")
    private String categories;

    @Column(name = "compat_versions", length = 255)
    private String compatVersions;

    @Column(name = "source_module", length = 100)
    private String module;

    // Flattened APK manifest info (populated once static analysis completes)
    @Column(name = "package_name", length = 255)
    private String packageName;

    @Column(name = "app_label", length = 255)
    private String appLabel;

    @Column(name = "version_name", length = 100)
    private String versionName;

    @Column(name = "version_code")
    private Long versionCode;

    @Column(name = "min_sdk")
    private Integer minSdk;

    @Column(name = "target_sdk")
    private Integer targetSdk;

    @Column(name = "apk_size_bytes")
    private Long apkSizeBytes;

    /** JSON array of execution-step strings (populated on terminal state). */
    @Column(name = "steps_json", columnDefinition = "TEXT")
    private String stepsJson;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<TestResultEntity> testResults = new ArrayList<>();

    public RunEntity() {}

    public RunEntity(String runId) {
        this.runId = runId;
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String apkFileName) { this.apkFileName = apkFileName; }

    public RunState getState() { return state; }
    public void setState(RunState state) { this.state = state; }

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }

    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }

    public String getCategories() { return categories; }
    public void setCategories(String categories) { this.categories = categories; }

    public String getCompatVersions() { return compatVersions; }
    public void setCompatVersions(String compatVersions) { this.compatVersions = compatVersions; }

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getAppLabel() { return appLabel; }
    public void setAppLabel(String appLabel) { this.appLabel = appLabel; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public Long getVersionCode() { return versionCode; }
    public void setVersionCode(Long versionCode) { this.versionCode = versionCode; }

    public Integer getMinSdk() { return minSdk; }
    public void setMinSdk(Integer minSdk) { this.minSdk = minSdk; }

    public Integer getTargetSdk() { return targetSdk; }
    public void setTargetSdk(Integer targetSdk) { this.targetSdk = targetSdk; }

    public Long getApkSizeBytes() { return apkSizeBytes; }
    public void setApkSizeBytes(Long apkSizeBytes) { this.apkSizeBytes = apkSizeBytes; }

    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }

    public List<TestResultEntity> getTestResults() { return testResults; }
}
