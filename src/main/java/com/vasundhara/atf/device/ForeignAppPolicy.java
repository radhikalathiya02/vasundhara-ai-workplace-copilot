package com.vasundhara.atf.device;

/**
 * Single, generic source of truth for classifying a foreground package during a test run —
 * with no hardcoded app-specific names. It decides whether a package is the app under test, a
 * benign transient system surface that must be left alone, or a FOREIGN app the framework must
 * never operate inside and must leave/force-stop the instant it appears (Chrome, the Play Store,
 * Settings, Gallery, Camera, a file picker, any other installed app).
 *
 * <p>Extracted into the {@code device} package so every place that has to make this call applies
 * the exact same rule: the Appium crawler ({@code ExplorationEngine}), the ADB-fallback crawler
 * ({@code LocalizationCrawler}), the keyword step engine, and — critically — the continuous
 * device-level foreground guard in {@link DeviceWatchdog} that catches a foreign app appearing
 * mid-step, between the crawler's own per-iteration checks.
 */
public final class ForeignAppPolicy {

    private ForeignAppPolicy() {}

    /**
     * A foreground package the framework must never be seen operating inside and must leave
     * instantly: the Play Store's own listing (where an ad's "Install"/"Open" lands) and any web
     * browser (an ad click-through / a "Rate us"/social link destination). For these, pressing
     * Back would navigate <em>within</em> that app, so callers force-stop them outright rather
     * than navigating them.
     */
    public static boolean isExternalSurface(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.equals("com.android.vending")
                || p.contains("chrome") || p.contains("firefox")
                || p.contains("browser") || p.contains("webview")
                || p.contains("sbrowser") || p.contains("opera") || p.contains("brave");
    }

    /**
     * The ONLY foreign foreground surface the framework is allowed to interact with: a genuine
     * runtime-permission grant dialog, which Android renders in its own permission-controller /
     * package-installer process. Tapping "Allow"/"While using the app" here lets the feature under
     * test complete its flow. Deliberately narrow — Settings, launchers, the Play Store, browsers
     * and every other app are excluded — matched by the OS package that hosts permission UI, never
     * by an app-specific name.
     */
    public static boolean isPermissionDialog(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("permissioncontroller") || p.contains("packageinstaller")
                || p.contains("permission");
    }

    /**
     * System UI that must never be force-stopped (doing so breaks the device/session): the home
     * launcher and SystemUI (status bar / nav bar / recents). When the app-under-test hands the
     * foreground to one of these, callers relaunch the app rather than killing the surface.
     */
    public static boolean isProtectedSystemUi(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.equals("com.android.systemui") || p.contains("systemui")
                || p.contains("launcher") || p.contains("nexuslauncher") || p.contains("trebuchet");
    }

    /**
     * True when {@code foreground} is a package the framework must leave by force-stopping it —
     * i.e. anything that is NOT the app under test, NOT a genuine permission dialog (transient,
     * handled by tapping/Back), and NOT protected system UI (launcher/SystemUI, which is relaunched
     * out of rather than killed). This is what makes "never operate inside any other app — Chrome,
     * Play Store, Settings, Gallery, or any installed app" hold generically, with no app-specific
     * names involved. A blank/unknown foreground (e.g. a momentary null during a self-restart) is
     * NOT treated as foreign, so it is never force-stopped.
     */
    public static boolean shouldForceStopForeign(String foreground, String appPkg) {
        if (foreground == null || foreground.isBlank()) return false;
        if (foreground.equals(appPkg)) return false;
        if (isProtectedSystemUi(foreground)) return false;
        if (isPermissionDialog(foreground)) return false;
        return true;
    }
}
