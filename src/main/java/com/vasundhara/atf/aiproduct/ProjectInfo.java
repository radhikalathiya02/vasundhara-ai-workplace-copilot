package com.vasundhara.atf.aiproduct;

import java.util.List;

public class ProjectInfo {

    private boolean androidProject;
    private String packageName;
    private String appName;
    private List<String> existingScreens;
    private String buildGradlePath;
    private String error;

    public boolean isAndroidProject() { return androidProject; }
    public void setAndroidProject(boolean androidProject) { this.androidProject = androidProject; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public List<String> getExistingScreens() { return existingScreens; }
    public void setExistingScreens(List<String> existingScreens) { this.existingScreens = existingScreens; }
    public String getBuildGradlePath() { return buildGradlePath; }
    public void setBuildGradlePath(String buildGradlePath) { this.buildGradlePath = buildGradlePath; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
