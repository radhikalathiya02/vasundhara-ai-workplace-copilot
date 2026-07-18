package com.vasundhara.atf.device;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.model.ApkInfo;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * Builds {@link AndroidDriver} sessions against an already-running Appium server.
 * The APK is installed up-front via adb, so sessions attach with {@code noReset}
 * to avoid a costly reinstall before each interactive category.
 */
@Component
public class DriverFactory {

    private static final Logger log = LoggerFactory.getLogger(DriverFactory.class);

    private final AtfProperties props;
    private final AdbClient adb;

    public DriverFactory(AtfProperties props, AdbClient adb) {
        this.props = props;
        this.adb = adb;
    }

    /** Quick check so categories can degrade gracefully when no Appium server is up. */
    public boolean isAppiumReachable() {
        try {
            URI uri = URI.create(props.getAppiumServerUrl() + "/status");
            var conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public AndroidDriver create(String serial, ApkInfo apkInfo) throws Exception {
        String pkg = apkInfo.getPackageName();
        String activity = apkInfo.getMainActivity();
        if (activity == null || activity.isBlank()) {
            activity = adb.resolveLauncherActivity(serial, pkg);
        }

        UiAutomator2Options options = new UiAutomator2Options()
                .setUdid(serial)
                .setAppPackage(pkg)
                .setAutoGrantPermissions(true)
                .setNoReset(true)
                .setFullReset(false)
                .setNewCommandTimeout(Duration.ofSeconds(180))
                .setUiautomator2ServerLaunchTimeout(Duration.ofSeconds(90))
                .setUiautomator2ServerInstallTimeout(Duration.ofSeconds(90))
                .setAppWaitDuration(Duration.ofSeconds(30))
                // Tolerate landing on a different activity than the declared launcher.
                .setAppWaitActivity("*")
                .setAutoGrantPermissions(true)
                .amend("appium:ignoreHiddenApiPolicyError", true)
                .amend("appium:disableWindowAnimation", true)
                // ── Accessibility-tree maximization (Tier-1 coverage) ──────────────────────
                // Expose as much of the app's own UI hierarchy as possible so the crawler can see
                // and tap more controls. This is the single highest-leverage, generic coverage
                // lever — it directly helps Compose / custom-view / Flutter apps that otherwise
                // report a sparse or empty tree, and it strengthens every existing node-based
                // detector at once. Applies to any APK; no app-specific logic.
                //   • ignoreUnimportantViews=false — do NOT compress the tree; keep nodes Android
                //     marks "unimportant for accessibility" (most custom-drawn controls).
                //   • allowInvisibleElements=true — include off-screen / not-yet-visible nodes so
                //     below-the-fold discovery sees them (they are never tapped: actionable()
                //     requires displayed=true).
                //   • disableSuppressAccessibilityService=true — don't suppress other active
                //     accessibility services, so UI toolkits that only build their semantics tree
                //     while accessibility is active (Flutter) expose real, queryable nodes.
                // enableMultiWindows is deliberately NOT enabled: it injects IME/system-UI window
                // nodes into the page source, which this crawler (which does not filter tap
                // candidates by package) could tap by mistake.
                .amend("appium:settings[ignoreUnimportantViews]", false)
                .amend("appium:settings[allowInvisibleElements]", true)
                .amend("appium:disableSuppressAccessibilityService", true);

        if (activity != null && !activity.isBlank()) {
            options.setAppActivity(activity);
        }

        log.info("Creating Appium session for {} (activity={}) on {}", pkg, activity, serial);
        return new AndroidDriver(URI.create(props.getAppiumServerUrl()).toURL(), options);
    }
}
