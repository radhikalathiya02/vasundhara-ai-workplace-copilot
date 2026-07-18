package com.vasundhara.atf.aiproduct;

import java.util.List;

public class BuildRequest {

    private String referenceUrl;
    private String projectPath;
    private String appName;
    private String packageName;
    private String description;
    private List<String> screens;
    private String themeColor = "#1565C0";
    private MergeStrategy mergeStrategy = MergeStrategy.SKIP_DUPLICATES;
    private QueueBehavior queueBehavior = QueueBehavior.QUEUE;
    private String geminiSpecJson;

    public String getReferenceUrl() { return referenceUrl; }
    public void setReferenceUrl(String referenceUrl) { this.referenceUrl = referenceUrl; }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getScreens() { return screens; }
    public void setScreens(List<String> screens) { this.screens = screens; }
    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }
    public MergeStrategy getMergeStrategy() { return mergeStrategy; }
    public void setMergeStrategy(MergeStrategy mergeStrategy) { this.mergeStrategy = mergeStrategy; }
    public QueueBehavior getQueueBehavior() { return queueBehavior; }
    public void setQueueBehavior(QueueBehavior queueBehavior) { this.queueBehavior = queueBehavior; }
    public String getGeminiSpecJson() { return geminiSpecJson; }
    public void setGeminiSpecJson(String geminiSpecJson) { this.geminiSpecJson = geminiSpecJson; }
}
