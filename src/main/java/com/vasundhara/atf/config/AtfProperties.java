package com.vasundhara.atf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the framework. Override any of these in
 * {@code application.yml} or via {@code -Datf.*} system properties / environment
 * variables so the same build runs against different machines and CI agents.
 */
@Component
@ConfigurationProperties(prefix = "atf")
public class AtfProperties {

    /** Absolute path to the {@code adb} executable. */
    private String adbPath = "adb";

    /** Base URL of an already-running Appium server. */
    private String appiumServerUrl = "http://127.0.0.1:4723";

    /** Optionally pin a specific device/emulator serial; blank = auto-pick first online. */
    private String deviceSerial = "";

    /** Directory where uploaded APKs are stored. */
    private String uploadDir = "data/uploads";

    /** Directory where run artifacts (reports, screenshots, logs) are written. */
    private String workDir = "data/runs";

    /** Path to Android SDK build-tools 'aapt'/'aapt2'; blank disables aapt enrichment. */
    private String aaptPath = "";

    /** Global ceiling on how long a single APK run may take. */
    private long maxRunSeconds = 1800;

    /** Default UI-crawler step budget for exploratory/E2E crawling. */
    private int crawlMaxSteps = 120;

    /** Default monkey event count. */
    private int monkeyEvents = 1500;

    /** Dashboard sign-in username. */
    private String authUsername = "admin";

    /** Dashboard sign-in password (override in production via env/CLI). */
    private String authPassword = "admin";

    // ---- AI screen review (optional, off by default) ------------------------
    // Heuristics (crash/ANR/UI-structure detection) always run and need no configuration.
    // This layer additionally sends screenshots to a vision-capable LLM to judge things
    // heuristics can't express (garbled/misaligned content, unreadable text, broken images).
    // It only activates when both the toggle is on AND an API key is present, and every call
    // site treats failures as "no issues found" — it can never block or break a run.

    /** Master switch for LLM-based screen review. Off by default; heuristics are unaffected either way. */
    private boolean aiReviewEnabled = false;

    /** API key for the vision-capable LLM. Blank disables AI review even if the toggle is on. */
    private String aiApiKey = "";

    /** Model identifier to call for screen review / vision navigation. */
    private String aiModel = "claude-sonnet-4-5-20250929";

    /**
     * Which vision-capable backend to call: {@code anthropic} (default, paid API — needs
     * {@code aiApiKey}), {@code ollama} (local, FREE — no key, needs Ollama running with a vision
     * model pulled, e.g. llama3.2-vision / qwen2-vl), or {@code openai} (OpenAI-compatible API).
     */
    private String aiProvider = "anthropic";

    /**
     * Base URL of the AI backend. Blank uses the provider default
     * (anthropic → https://api.anthropic.com, ollama → http://localhost:11434,
     * openai → https://api.openai.com). Set this to point at a self-hosted/remote Ollama.
     */
    private String aiBaseUrl = "";

    /** Ceiling on how many screenshots get sent to the LLM per run, to bound cost/latency. */
    private int aiMaxScreensPerRun = 8;

    // ---- Compatibility module / emulator matrix ----------------------------

    /** Android SDK root; blank → auto-detect ANDROID_HOME/ANDROID_SDK_ROOT/~/Library/Android/sdk. */
    private String androidSdkPath = "";

    /** Android API levels for the compatibility matrix (Android 9–16). */
    private String compatApiLevels = "28,29,30,31,33,34,35,36";

    /** System-image ABI to use for created AVDs (x86_64 on Intel, arm64-v8a on Apple Silicon). */
    private String emulatorAbi = "x86_64";

    /** System-image tag, e.g. google_apis or google_apis_playstore. */
    private String emulatorImageTag = "google_apis";

    /** Run emulators headless (-no-window). */
    private boolean emulatorHeadless = true;

    /** Auto-download missing system images via sdkmanager (large first-time downloads). */
    private boolean autoDownloadSystemImages = true;

    /** Max seconds to wait for a single emulator to finish booting. */
    private int emulatorBootTimeoutSec = 300;

    /** Crawl step budget per emulator in the compatibility matrix (balances coverage vs. matrix runtime). */
    private int compatCrawlSteps = 60;

    // ---- Automation preferences --------------------------------------------

    /** Throttle (ms) between monkey events; higher = gentler stress, more stable. */
    private int monkeyThrottleMs = 100;

    /** Default test categories pre-selected for a new run (CSV of keys); blank = all. */
    private String defaultCategories = "";

    /** Show all test categories in New Test (true) or only those individually checked in Settings (false). */
    private boolean showAllCategories = true;

    // ---- Report settings ----------------------------------------------------

    /** Max screenshots embedded in the clean/QA reports (caps very large galleries). */
    private int reportMaxScreenshots = 24;

    /** Include raw logcat / stack traces in generated reports. */
    private boolean reportIncludeLogcat = true;

    // ---- Localization settings ---------------------------------------------

    /** Max languages tested per localization run (0 = all detected). */
    private int localizationMaxLanguages = 0;

    // ---- Notifications & branding ------------------------------------------

    /** Master switch for all dashboard notifications. */
    private boolean notificationsEnabled = true;

    /** Per-type notification toggles (test lifecycle, device, reports, errors, system). */
    private boolean notifyTest = true;
    private boolean notifyDevice = true;
    private boolean notifyReports = true;
    private boolean notifyErrors = true;
    private boolean notifySystem = true;

    /** Organisation name shown in the dashboard and reports. */
    private String organizationName = "Vasundhara Infotech";

    /** Default dashboard theme for new browsers: light | dark. */
    private String defaultTheme = "light";

    public String getAdbPath() { return adbPath; }
    public void setAdbPath(String adbPath) { this.adbPath = adbPath; }

    public String getAppiumServerUrl() { return appiumServerUrl; }
    public void setAppiumServerUrl(String appiumServerUrl) { this.appiumServerUrl = appiumServerUrl; }

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }

    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }

    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }

    public String getAaptPath() { return aaptPath; }
    public void setAaptPath(String aaptPath) { this.aaptPath = aaptPath; }

    public long getMaxRunSeconds() { return maxRunSeconds; }
    public void setMaxRunSeconds(long maxRunSeconds) { this.maxRunSeconds = maxRunSeconds; }

    public int getCrawlMaxSteps() { return crawlMaxSteps; }
    public void setCrawlMaxSteps(int crawlMaxSteps) { this.crawlMaxSteps = crawlMaxSteps; }

    public int getMonkeyEvents() { return monkeyEvents; }
    public void setMonkeyEvents(int monkeyEvents) { this.monkeyEvents = monkeyEvents; }

    public String getAuthUsername() { return authUsername; }
    public void setAuthUsername(String authUsername) { this.authUsername = authUsername; }

    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }

    public String getAndroidSdkPath() { return androidSdkPath; }
    public void setAndroidSdkPath(String androidSdkPath) { this.androidSdkPath = androidSdkPath; }

    public String getCompatApiLevels() { return compatApiLevels; }
    public void setCompatApiLevels(String compatApiLevels) { this.compatApiLevels = compatApiLevels; }

    public String getEmulatorAbi() { return emulatorAbi; }
    public void setEmulatorAbi(String emulatorAbi) { this.emulatorAbi = emulatorAbi; }

    public String getEmulatorImageTag() { return emulatorImageTag; }
    public void setEmulatorImageTag(String emulatorImageTag) { this.emulatorImageTag = emulatorImageTag; }

    public boolean isEmulatorHeadless() { return emulatorHeadless; }
    public void setEmulatorHeadless(boolean emulatorHeadless) { this.emulatorHeadless = emulatorHeadless; }

    public boolean isAutoDownloadSystemImages() { return autoDownloadSystemImages; }
    public void setAutoDownloadSystemImages(boolean v) { this.autoDownloadSystemImages = v; }

    public int getEmulatorBootTimeoutSec() { return emulatorBootTimeoutSec; }
    public void setEmulatorBootTimeoutSec(int v) { this.emulatorBootTimeoutSec = v; }

    public int getCompatCrawlSteps() { return compatCrawlSteps; }
    public void setCompatCrawlSteps(int v) { this.compatCrawlSteps = v; }

    public int getMonkeyThrottleMs() { return monkeyThrottleMs; }
    public void setMonkeyThrottleMs(int v) { this.monkeyThrottleMs = v; }

    public String getDefaultCategories() { return defaultCategories; }
    public void setDefaultCategories(String v) { this.defaultCategories = v; }

    public boolean isShowAllCategories() { return showAllCategories; }
    public void setShowAllCategories(boolean v) { this.showAllCategories = v; }

    public int getReportMaxScreenshots() { return reportMaxScreenshots; }
    public void setReportMaxScreenshots(int v) { this.reportMaxScreenshots = v; }

    public boolean isReportIncludeLogcat() { return reportIncludeLogcat; }
    public void setReportIncludeLogcat(boolean v) { this.reportIncludeLogcat = v; }

    public int getLocalizationMaxLanguages() { return localizationMaxLanguages; }
    public void setLocalizationMaxLanguages(int v) { this.localizationMaxLanguages = v; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean v) { this.notificationsEnabled = v; }

    public boolean isNotifyTest() { return notifyTest; }
    public void setNotifyTest(boolean v) { this.notifyTest = v; }

    public boolean isNotifyDevice() { return notifyDevice; }
    public void setNotifyDevice(boolean v) { this.notifyDevice = v; }

    public boolean isNotifyReports() { return notifyReports; }
    public void setNotifyReports(boolean v) { this.notifyReports = v; }

    public boolean isNotifyErrors() { return notifyErrors; }
    public void setNotifyErrors(boolean v) { this.notifyErrors = v; }

    public boolean isNotifySystem() { return notifySystem; }
    public void setNotifySystem(boolean v) { this.notifySystem = v; }

    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String v) { this.organizationName = v; }

    public String getDefaultTheme() { return defaultTheme; }
    public void setDefaultTheme(String v) { this.defaultTheme = v; }

    public boolean isAiReviewEnabled() { return aiReviewEnabled; }
    public void setAiReviewEnabled(boolean v) { this.aiReviewEnabled = v; }

    public String getAiApiKey() { return aiApiKey; }
    public void setAiApiKey(String v) { this.aiApiKey = v; }

    public String getAiModel() { return aiModel; }
    public void setAiModel(String v) { this.aiModel = v; }

    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String v) { this.aiProvider = v; }

    public String getAiBaseUrl() { return aiBaseUrl; }
    public void setAiBaseUrl(String v) { this.aiBaseUrl = v; }

    public int getAiMaxScreensPerRun() { return aiMaxScreensPerRun; }
    public void setAiMaxScreensPerRun(int v) { this.aiMaxScreensPerRun = v; }
}
