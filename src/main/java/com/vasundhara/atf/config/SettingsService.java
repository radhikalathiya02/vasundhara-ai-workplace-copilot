package com.vasundhara.atf.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Persists user-editable framework configuration and applies it onto the live
 * {@link AtfProperties} bean. Because every component reads {@code props.getX()} at
 * call time (never caches at construction), mutating the bean here makes a setting
 * change take effect on the very next run — no restart, no pipeline plumbing.
 *
 * <p>Settings are stored as {@code data/settings.json}. The values bound from
 * {@code application.yml} / env / CLI at startup are captured as the reset baseline.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final Path file = Path.of("data", "settings.json");

    /** Editable setting definitions, in display order, grouped by section. */
    private final List<Setting> settings = new ArrayList<>();
    /** Config-bound baseline (yml/env/CLI) captured before overrides are applied. */
    private Map<String, Object> defaults;

    public SettingsService(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        register();
    }

    private record Setting(String key, String group, String type,
                           Supplier<Object> getter, Consumer<Object> setter) {}

    private void reg(String key, String group, String type, Supplier<Object> g, Consumer<Object> s) {
        settings.add(new Setting(key, group, type, g, s));
    }

    private void register() {
        // General
        reg("workDir", "general", "string", props::getWorkDir, v -> props.setWorkDir(str(v)));
        reg("uploadDir", "general", "string", props::getUploadDir, v -> props.setUploadDir(str(v)));
        reg("maxRunSeconds", "general", "long", props::getMaxRunSeconds,
                v -> props.setMaxRunSeconds(lng(v, props.getMaxRunSeconds(), 30, 86_400)));
        reg("aaptPath", "general", "string", props::getAaptPath, v -> props.setAaptPath(str(v)));
        reg("organizationName", "general", "string", props::getOrganizationName, v -> props.setOrganizationName(str(v)));
        reg("defaultTheme", "general", "string", props::getDefaultTheme,
                v -> props.setDefaultTheme("dark".equalsIgnoreCase(str(v)) ? "dark" : "light"));

        // Device
        reg("adbPath", "device", "string", props::getAdbPath, v -> keepIfBlank(str(v), props::getAdbPath, props::setAdbPath));
        reg("deviceSerial", "device", "string", props::getDeviceSerial, v -> props.setDeviceSerial(str(v)));
        reg("appiumServerUrl", "device", "string", props::getAppiumServerUrl,
                v -> keepIfBlank(str(v), props::getAppiumServerUrl, props::setAppiumServerUrl));
        reg("androidSdkPath", "device", "string", props::getAndroidSdkPath, v -> props.setAndroidSdkPath(str(v)));

        // Automation
        reg("crawlMaxSteps", "automation", "int", props::getCrawlMaxSteps,
                v -> props.setCrawlMaxSteps(intv(v, props.getCrawlMaxSteps(), 1, 5000)));
        reg("monkeyEvents", "automation", "int", props::getMonkeyEvents,
                v -> props.setMonkeyEvents(intv(v, props.getMonkeyEvents(), 0, 1_000_000)));
        reg("monkeyThrottleMs", "automation", "int", props::getMonkeyThrottleMs,
                v -> props.setMonkeyThrottleMs(intv(v, props.getMonkeyThrottleMs(), 0, 10_000)));
        reg("defaultCategories", "automation", "string", props::getDefaultCategories, v -> props.setDefaultCategories(str(v)));
        reg("showAllCategories", "automation", "bool", props::isShowAllCategories, v -> props.setShowAllCategories(bool(v)));

        // Reports
        reg("reportMaxScreenshots", "reports", "int", props::getReportMaxScreenshots,
                v -> props.setReportMaxScreenshots(intv(v, props.getReportMaxScreenshots(), 0, 500)));
        reg("reportIncludeLogcat", "reports", "bool", props::isReportIncludeLogcat, v -> props.setReportIncludeLogcat(bool(v)));

        // Localization
        reg("localizationMaxLanguages", "localization", "int", props::getLocalizationMaxLanguages,
                v -> props.setLocalizationMaxLanguages(intv(v, props.getLocalizationMaxLanguages(), 0, 200)));

        // Emulator / compatibility
        reg("compatApiLevels", "emulator", "string", props::getCompatApiLevels, v -> props.setCompatApiLevels(str(v)));
        reg("compatCrawlSteps", "emulator", "int", props::getCompatCrawlSteps,
                v -> props.setCompatCrawlSteps(intv(v, props.getCompatCrawlSteps(), 1, 5000)));
        reg("emulatorAbi", "emulator", "string", props::getEmulatorAbi, v -> props.setEmulatorAbi(str(v)));
        reg("emulatorImageTag", "emulator", "string", props::getEmulatorImageTag, v -> props.setEmulatorImageTag(str(v)));
        reg("emulatorHeadless", "emulator", "bool", props::isEmulatorHeadless, v -> props.setEmulatorHeadless(bool(v)));
        reg("autoDownloadSystemImages", "emulator", "bool", props::isAutoDownloadSystemImages,
                v -> props.setAutoDownloadSystemImages(bool(v)));
        reg("emulatorBootTimeoutSec", "emulator", "int", props::getEmulatorBootTimeoutSec,
                v -> props.setEmulatorBootTimeoutSec(intv(v, props.getEmulatorBootTimeoutSec(), 30, 3600)));

        // Notifications
        reg("notificationsEnabled", "notifications", "bool", props::isNotificationsEnabled, v -> props.setNotificationsEnabled(bool(v)));
        reg("notifyTest", "notifications", "bool", props::isNotifyTest, v -> props.setNotifyTest(bool(v)));
        reg("notifyDevice", "notifications", "bool", props::isNotifyDevice, v -> props.setNotifyDevice(bool(v)));
        reg("notifyReports", "notifications", "bool", props::isNotifyReports, v -> props.setNotifyReports(bool(v)));
        reg("notifyErrors", "notifications", "bool", props::isNotifyErrors, v -> props.setNotifyErrors(bool(v)));
        reg("notifySystem", "notifications", "bool", props::isNotifySystem, v -> props.setNotifySystem(bool(v)));

        // Auth (username editable; password is write-only, handled separately)
        reg("authUsername", "auth", "string", props::getAuthUsername,
                v -> keepIfBlank(str(v), props::getAuthUsername, props::setAuthUsername));

        // AI screen review (optional, off by default — heuristics run regardless).
        // aiApiKey is intentionally NOT registered here — like authPassword, it's write-only
        // (see update() below) so the key is never echoed back to the dashboard.
        reg("aiReviewEnabled", "ai", "bool", props::isAiReviewEnabled, v -> props.setAiReviewEnabled(bool(v)));
        reg("aiHasApiKey", "ai", "bool", () -> props.getAiApiKey() != null && !props.getAiApiKey().isBlank(), v -> {});
        // Provider: "anthropic" (paid, needs key), "ollama" (local, FREE, no key), or "openai".
        reg("aiProvider", "ai", "string", props::getAiProvider, v -> keepIfBlank(str(v), props::getAiProvider, props::setAiProvider));
        // Base URL override; blank = provider default (Ollama → http://localhost:11434).
        reg("aiBaseUrl", "ai", "string", props::getAiBaseUrl, v -> props.setAiBaseUrl(str(v)));
        reg("aiModel", "ai", "string", props::getAiModel, v -> keepIfBlank(str(v), props::getAiModel, props::setAiModel));
        reg("aiMaxScreensPerRun", "ai", "int", props::getAiMaxScreensPerRun,
                v -> props.setAiMaxScreensPerRun(intv(v, props.getAiMaxScreensPerRun(), 1, 50)));
    }

    @PostConstruct
    void init() {
        defaults = snapshot();   // capture yml/env/CLI baseline before applying the saved file
        load();
    }

    /** Current value of every editable setting, in display order. */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Setting s : settings) out.put(s.key(), s.getter().get());
        return out;
    }

    /** Setting key → its section, so the UI can group consistently. */
    public Map<String, String> groups() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Setting s : settings) out.put(s.key(), s.group());
        return out;
    }

    public Map<String, Object> defaults() {
        return defaults == null ? Map.of() : defaults;
    }

    /** Apply a partial update from the dashboard, then persist. Unknown keys are ignored. */
    public synchronized Map<String, Object> update(Map<String, Object> patch) {
        if (patch != null) {
            for (Setting s : settings) {
                if (patch.containsKey(s.key())) {
                    try { s.setter().accept(patch.get(s.key())); }
                    catch (Exception e) { log.warn("Ignoring invalid setting {}: {}", s.key(), e.toString()); }
                }
            }
            // Password is write-only: set only when a non-blank value is supplied; never returned.
            Object pw = patch.get("authPassword");
            if (pw != null && !str(pw).isEmpty()) props.setAuthPassword(str(pw));

            // AI API key is write-only for the same reason — never echoed back to the dashboard.
            Object aiKey = patch.get("aiApiKey");
            if (aiKey != null && !str(aiKey).isEmpty()) props.setAiApiKey(str(aiKey));
        }
        persist();
        return snapshot();
    }

    /** Restore the config baseline (application.yml / env / CLI) and remove the override file. */
    public synchronized Map<String, Object> reset() {
        if (defaults != null) {
            for (Setting s : settings) {
                if (defaults.containsKey(s.key())) {
                    try { s.setter().accept(defaults.get(s.key())); } catch (Exception ignored) {}
                }
            }
        }
        try { Files.deleteIfExists(file); } catch (Exception e) { log.warn("Could not delete {}: {}", file, e.toString()); }
        log.info("Settings reset to configuration defaults.");
        return snapshot();
    }

    private void load() {
        try {
            if (!Files.exists(file)) { log.info("No saved settings ({}); using configuration defaults.", file); return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = mapper.readValue(Files.readAllBytes(file), Map.class);
            int applied = 0;
            for (Setting s : settings) {
                if (saved.containsKey(s.key())) {
                    try { s.setter().accept(saved.get(s.key())); applied++; } catch (Exception ignored) {}
                }
            }
            log.info("Loaded {} saved setting(s) from {}.", applied, file);
        } catch (Exception e) {
            log.warn("Could not load settings from {}: {}", file, e.toString());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot());
            log.info("Settings saved to {}.", file);
        } catch (Exception e) {
            log.error("Failed to persist settings to {}: {}", file, e.toString());
            throw new RuntimeException("Could not save settings: " + e.getMessage(), e);
        }
    }

    // ---- value coercion ----------------------------------------------------

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(str(o));
    }

    private static int intv(Object o, int def, int min, int max) {
        int v = def;
        if (o instanceof Number n) v = n.intValue();
        else { try { v = Integer.parseInt(str(o)); } catch (Exception e) { return def; } }
        return Math.max(min, Math.min(max, v));
    }

    private static long lng(Object o, long def, long min, long max) {
        long v = def;
        if (o instanceof Number n) v = n.longValue();
        else { try { v = Long.parseLong(str(o)); } catch (Exception e) { return def; } }
        return Math.max(min, Math.min(max, v));
    }

    /** Keep the current value when the incoming one is blank (avoids wiping required paths/URLs). */
    private static void keepIfBlank(String v, Supplier<String> current, Consumer<String> set) {
        set.accept(v.isEmpty() ? current.get() : v);
    }
}
