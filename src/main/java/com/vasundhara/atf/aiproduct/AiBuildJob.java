package com.vasundhara.atf.aiproduct;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AiBuildJob {

    private String id;
    private BuildStatus status = BuildStatus.QUEUED;
    private int progress = 0;
    private String currentStep = "Waiting…";
    private String referenceUrl;
    private String description;
    private String appName;
    private String packageName;
    private List<String> screens;
    private String themeColor;
    private String projectPath;           // original user path
    private String workspacePath;         // /tmp/ai-builds/{id}
    private String artifactPath;          // final APK path
    private String logPath;               // build.log path
    private GeminiSpec geminiSpec;
    private String geminiSpecJson;
    private List<String> existingScreens;
    private MergeStrategy mergeStrategy;
    private QueueBehavior queueBehavior;
    private String claudeModel = "claude-sonnet-4-6";
    private String claudeEffort = "medium";
    private Integer queuePosition;
    private Integer estimatedWaitSeconds;
    private String errorMessage;
    private String sessionId;             // Claude session ID for --resume
    private int healAttempts = 0;
    private Instant createdAt = Instant.now();
    private Instant completedAt;

    // Not serialized — runtime SSE emitters
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    // Rolling log tail (last 20 lines)
    private final CopyOnWriteArrayList<String> logTail = new CopyOnWriteArrayList<>();

    public void addEmitter(SseEmitter emitter) { emitters.add(emitter); }
    public void removeEmitter(SseEmitter emitter) { emitters.remove(emitter); }
    public CopyOnWriteArrayList<SseEmitter> getEmitters() { return emitters; }

    public void appendLog(String line) {
        logTail.add(line);
        if (logTail.size() > 20) logTail.remove(0);
    }
    public List<String> getLogTail() { return logTail; }

    // ── Getters / Setters ──
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public BuildStatus getStatus() { return status; }
    public void setStatus(BuildStatus status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public String getReferenceUrl() { return referenceUrl; }
    public void setReferenceUrl(String referenceUrl) { this.referenceUrl = referenceUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public List<String> getScreens() { return screens; }
    public void setScreens(List<String> screens) { this.screens = screens; }
    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }
    public GeminiSpec getGeminiSpec() { return geminiSpec; }
    public void setGeminiSpec(GeminiSpec geminiSpec) { this.geminiSpec = geminiSpec; }
    public String getGeminiSpecJson() { return geminiSpecJson; }
    public void setGeminiSpecJson(String geminiSpecJson) { this.geminiSpecJson = geminiSpecJson; }
    public List<String> getExistingScreens() { return existingScreens; }
    public void setExistingScreens(List<String> existingScreens) { this.existingScreens = existingScreens; }
    public MergeStrategy getMergeStrategy() { return mergeStrategy; }
    public void setMergeStrategy(MergeStrategy mergeStrategy) { this.mergeStrategy = mergeStrategy; }
    public QueueBehavior getQueueBehavior() { return queueBehavior; }
    public void setQueueBehavior(QueueBehavior queueBehavior) { this.queueBehavior = queueBehavior; }
    public String getClaudeModel() { return claudeModel; }
    public void setClaudeModel(String claudeModel) { this.claudeModel = claudeModel; }
    public String getClaudeEffort() { return claudeEffort; }
    public void setClaudeEffort(String claudeEffort) { this.claudeEffort = claudeEffort; }
    public Integer getQueuePosition() { return queuePosition; }
    public void setQueuePosition(Integer queuePosition) { this.queuePosition = queuePosition; }
    public Integer getEstimatedWaitSeconds() { return estimatedWaitSeconds; }
    public void setEstimatedWaitSeconds(Integer estimatedWaitSeconds) { this.estimatedWaitSeconds = estimatedWaitSeconds; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public int getHealAttempts() { return healAttempts; }
    public void setHealAttempts(int healAttempts) { this.healAttempts = healAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
