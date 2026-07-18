package com.vasundhara.atf.aiproduct;

import java.util.List;

public class GeminiSpec {

    private String appName;
    private String description;
    private String suggestedPackageName;
    private String primaryColor;
    private List<String> screens;
    private List<String> features;
    private String navigationPattern;
    private String rawJson;

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSuggestedPackageName() { return suggestedPackageName; }
    public void setSuggestedPackageName(String suggestedPackageName) { this.suggestedPackageName = suggestedPackageName; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public List<String> getScreens() { return screens; }
    public void setScreens(List<String> screens) { this.screens = screens; }
    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }
    public String getNavigationPattern() { return navigationPattern; }
    public void setNavigationPattern(String navigationPattern) { this.navigationPattern = navigationPattern; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
}
