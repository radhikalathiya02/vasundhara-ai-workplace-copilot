package com.vasundhara.atf.vlegal.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

public class DocumentRequest {

    @NotBlank(message = "App name is required")
    private String appName;

    private String appType = "Mobile App";
    private List<String> platforms = new ArrayList<>(List.of("Android"));
    private List<String> dataCollected = new ArrayList<>();
    private List<String> thirdPartyServices = new ArrayList<>();
    private List<String> targetRegions = new ArrayList<>(List.of("Global"));
    private boolean hasInAppPurchases = false;
    private boolean hasUserAccounts = false;
    private boolean hasAds = false;
    private boolean hasSocialFeatures = false;
    private String targetAgeGroup = "All Ages";
    private String contactEmail = "contact@vasundharaapps.com";

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getAppType() { return appType; }
    public void setAppType(String appType) { this.appType = appType; }
    public List<String> getPlatforms() { return platforms; }
    public void setPlatforms(List<String> platforms) { this.platforms = platforms; }
    public List<String> getDataCollected() { return dataCollected; }
    public void setDataCollected(List<String> dataCollected) { this.dataCollected = dataCollected; }
    public List<String> getThirdPartyServices() { return thirdPartyServices; }
    public void setThirdPartyServices(List<String> thirdPartyServices) { this.thirdPartyServices = thirdPartyServices; }
    public List<String> getTargetRegions() { return targetRegions; }
    public void setTargetRegions(List<String> targetRegions) { this.targetRegions = targetRegions; }
    public boolean isHasInAppPurchases() { return hasInAppPurchases; }
    public void setHasInAppPurchases(boolean hasInAppPurchases) { this.hasInAppPurchases = hasInAppPurchases; }
    public boolean isHasUserAccounts() { return hasUserAccounts; }
    public void setHasUserAccounts(boolean hasUserAccounts) { this.hasUserAccounts = hasUserAccounts; }
    public boolean isHasAds() { return hasAds; }
    public void setHasAds(boolean hasAds) { this.hasAds = hasAds; }
    public boolean isHasSocialFeatures() { return hasSocialFeatures; }
    public void setHasSocialFeatures(boolean hasSocialFeatures) { this.hasSocialFeatures = hasSocialFeatures; }
    public String getTargetAgeGroup() { return targetAgeGroup; }
    public void setTargetAgeGroup(String targetAgeGroup) { this.targetAgeGroup = targetAgeGroup; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
}
