package com.vasundhara.atf.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of static analysis of the uploaded APK. Populated once up-front and then
 * consumed by the security, compatibility, ads and other static-leaning categories.
 */
public class ApkInfo {

    private String packageName;
    private String applicationLabel;
    private String versionName;
    private long versionCode;
    private Integer minSdkVersion;
    private Integer targetSdkVersion;
    private Integer maxSdkVersion;

    private String mainActivity;
    private long apkSizeBytes;

    /** Base64-encoded launcher icon extracted from the APK, or null if no raster icon could be read
     *  (e.g. the app ships only vector/adaptive-icon XML layers, which are not rasterized). */
    private String iconBase64;
    /** MIME type of {@link #iconBase64} — "image/png" or "image/webp". Null when no icon is set. */
    private String iconMimeType;

    private boolean debuggable;
    private boolean allowBackup = true;       // Android default is true
    private boolean usesCleartextTraffic;      // effective value after defaults
    private boolean hasNetworkSecurityConfig;

    private final List<String> permissions = new ArrayList<>();
    private final List<String> dangerousPermissions = new ArrayList<>();
    private final List<String> activities = new ArrayList<>();
    private final List<String> exportedComponents = new ArrayList<>();
    /** Components that declare an intent-filter but no explicit android:exported (illegal for targetSdk 31+). */
    private final List<String> componentsMissingExported = new ArrayList<>();
    private final List<String> services = new ArrayList<>();
    private final List<String> receivers = new ArrayList<>();
    private final List<String> providers = new ArrayList<>();
    private final List<String> supportedAbis = new ArrayList<>();
    private final List<String> supportedLocales = new ArrayList<>();
    /** Notes on non-resource localization mechanisms detected (asset JSON/i18n, Flutter, React Native, etc.). */
    private final List<String> localizationSources = new ArrayList<>();
    private final List<String> signatureSchemes = new ArrayList<>();
    /** Third-party SDKs detected from package/class names (e.g. AdMob, Firebase). */
    private final List<String> detectedSdks = new ArrayList<>();

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getApplicationLabel() { return applicationLabel; }
    public void setApplicationLabel(String applicationLabel) { this.applicationLabel = applicationLabel; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public long getVersionCode() { return versionCode; }
    public void setVersionCode(long versionCode) { this.versionCode = versionCode; }

    public Integer getMinSdkVersion() { return minSdkVersion; }
    public void setMinSdkVersion(Integer minSdkVersion) { this.minSdkVersion = minSdkVersion; }

    public Integer getTargetSdkVersion() { return targetSdkVersion; }
    public void setTargetSdkVersion(Integer targetSdkVersion) { this.targetSdkVersion = targetSdkVersion; }

    public Integer getMaxSdkVersion() { return maxSdkVersion; }
    public void setMaxSdkVersion(Integer maxSdkVersion) { this.maxSdkVersion = maxSdkVersion; }

    public String getMainActivity() { return mainActivity; }
    public void setMainActivity(String mainActivity) { this.mainActivity = mainActivity; }

    public long getApkSizeBytes() { return apkSizeBytes; }
    public void setApkSizeBytes(long apkSizeBytes) { this.apkSizeBytes = apkSizeBytes; }

    public String getIconBase64() { return iconBase64; }
    public void setIconBase64(String iconBase64) { this.iconBase64 = iconBase64; }

    public String getIconMimeType() { return iconMimeType; }
    public void setIconMimeType(String iconMimeType) { this.iconMimeType = iconMimeType; }

    public boolean isDebuggable() { return debuggable; }
    public void setDebuggable(boolean debuggable) { this.debuggable = debuggable; }

    public boolean isAllowBackup() { return allowBackup; }
    public void setAllowBackup(boolean allowBackup) { this.allowBackup = allowBackup; }

    public boolean isUsesCleartextTraffic() { return usesCleartextTraffic; }
    public void setUsesCleartextTraffic(boolean usesCleartextTraffic) { this.usesCleartextTraffic = usesCleartextTraffic; }

    public boolean isHasNetworkSecurityConfig() { return hasNetworkSecurityConfig; }
    public void setHasNetworkSecurityConfig(boolean hasNetworkSecurityConfig) { this.hasNetworkSecurityConfig = hasNetworkSecurityConfig; }

    public List<String> getPermissions() { return permissions; }
    public List<String> getDangerousPermissions() { return dangerousPermissions; }
    public List<String> getActivities() { return activities; }
    public List<String> getExportedComponents() { return exportedComponents; }
    public List<String> getComponentsMissingExported() { return componentsMissingExported; }
    public List<String> getServices() { return services; }
    public List<String> getReceivers() { return receivers; }
    public List<String> getProviders() { return providers; }
    public List<String> getSupportedAbis() { return supportedAbis; }
    public List<String> getSupportedLocales() { return supportedLocales; }
    public List<String> getLocalizationSources() { return localizationSources; }
    public List<String> getSignatureSchemes() { return signatureSchemes; }
    public List<String> getDetectedSdks() { return detectedSdks; }
}
