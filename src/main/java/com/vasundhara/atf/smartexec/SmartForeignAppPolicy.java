package com.vasundhara.atf.smartexec;

/**
 * Smart Execution's own foreign-app classification — independent of {@code device.ForeignAppPolicy}.
 * Decides whether a foreground package is the app under test, a benign transient system surface
 * that must be left alone, or a FOREIGN app that must be force-stopped instantly (Chrome, the Play
 * Store, Settings, Gallery, or any other installed app) so the crawl never wanders outside the
 * uploaded APK's own package. Purely generic — no hardcoded app names.
 */
public final class SmartForeignAppPolicy {

    private SmartForeignAppPolicy() {}

    /** Play Store listing / any browser — pressing Back would navigate WITHIN them, so force-stop outright. */
    public static boolean isExternalSurface(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.equals("com.android.vending")
                || p.contains("chrome") || p.contains("firefox")
                || p.contains("browser") || p.contains("webview")
                || p.contains("sbrowser") || p.contains("opera") || p.contains("brave");
    }

    /** The ONLY foreign surface Smart Execution is allowed to interact with: the OS permission dialog. */
    public static boolean isPermissionDialog(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("permissioncontroller") || p.contains("packageinstaller") || p.contains("permission");
    }

    /** Home launcher / SystemUI — never force-stopped (would break the device session); relaunch past instead. */
    public static boolean isProtectedSystemUi(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.equals("com.android.systemui") || p.contains("systemui")
                || p.contains("launcher") || p.contains("nexuslauncher") || p.contains("trebuchet");
    }

    /** True when {@code foreground} must be force-stopped: anything that isn't the app, a permission dialog, or protected system UI. */
    public static boolean shouldForceStopForeign(String foreground, String appPkg) {
        if (foreground == null || foreground.isBlank()) return false;
        if (foreground.equals(appPkg)) return false;
        if (isProtectedSystemUi(foreground)) return false;
        if (isPermissionDialog(foreground)) return false;
        return true;
    }

    // ── ad / subscription content (never tapped, except the sanctioned single close-and-return) ──

    private static final java.util.regex.Pattern AD_MARKERS = java.util.regex.Pattern.compile(
            "(?i)admob|adview|admanagerad|doubleclick|vungle|applovin|ironsource|chartboost|mopub|" +
            "adcolony|inmobi|startapp|tapjoy|smaato|pubmatic|criteo|fyber|unityads|audience_network|" +
            "mintegral|flurry|millennialmedia|tremor|verizonads|interstitial|rewarded_ad|native_ad|banner_ad");

    public static boolean isAdWidget(SmartWidget w) {
        if (w == null) return false;
        String probe = (w.resourceId() + " " + w.className()).toLowerCase();
        return AD_MARKERS.matcher(probe).find();
    }

    // Ad creatives rendered with generic view classes (a plain Button/TextView, no SDK-branded
    // resource-id) are invisible to the resource-id/className check above — verified live: a
    // "Test Ad" banner's own "INSTALL" button had no recognizable marker in either field, so it
    // was tapped like ordinary app content, leaving the app entirely. These two work together:
    // a disclosure label ("Ad", "Sponsored", "Test Ad", ...) marks the SCREEN as containing ad
    // content, and only THEN is a generic call-to-action label ("Install", "Download", "Play
    // Now", ...) treated as part of that ad — so a legitimate in-app "Download" button on a
    // screen with no ad disclosure is never affected.
    private static final java.util.regex.Pattern AD_DISCLOSURE = java.util.regex.Pattern.compile(
            "(?i)^ad$|\\btest ad\\b|\\bsponsored\\b|\\badvertisement\\b|\\bgoogle ads?\\b|\\bwhy this ad\\b");
    private static final java.util.regex.Pattern AD_CTA = java.util.regex.Pattern.compile(
            "(?i)^(install|install now|download|download now|get|get app|play now|shop now|" +
            "open app|visit site|learn more|try now|book now|watch now|start now|use app|play)$");

    public static boolean screenHasAdDisclosure(java.util.List<SmartWidget> widgets) {
        for (SmartWidget w : widgets) {
            String t = (w.text() == null ? "" : w.text()) + " " + (w.contentDesc() == null ? "" : w.contentDesc());
            if (AD_DISCLOSURE.matcher(t.trim()).find()) return true;
        }
        return false;
    }

    /** Screen-aware ad check: also true for a generic CTA-labeled widget, but only on a screen
     *  that itself discloses ad content — see {@link #screenHasAdDisclosure}. */
    public static boolean isAdWidget(SmartWidget w, boolean screenHasAdDisclosure) {
        if (isAdWidget(w)) return true;
        if (!screenHasAdDisclosure || w == null) return false;
        String label = w.text() == null ? "" : w.text().trim();
        return AD_CTA.matcher(label).matches();
    }

    private static final java.util.regex.Pattern SUBSCRIPTION_MARKERS = java.util.regex.Pattern.compile(
            "(?i)\\b(subscribe|subscription|upgrade to premium|go premium|buy now|purchase|" +
            "start free trial|unlock premium|\\$\\d|₹\\d|€\\d|£\\d)\\b");

    public static boolean isSubscriptionWidget(SmartWidget w) {
        if (w == null) return false;
        String label = (w.text() == null ? "" : w.text()) + " " + (w.contentDesc() == null ? "" : w.contentDesc());
        return SUBSCRIPTION_MARKERS.matcher(label).find();
    }

    private static final java.util.regex.Pattern EXIT_INTENT = java.util.regex.Pattern.compile(
            "(?i)^(exit|exit app|quit|quit app|close app)$");
    private static final java.util.regex.Pattern STAY_INTENT = java.util.regex.Pattern.compile(
            "(?i)^(cancel|no|stay|not now|no thanks)$");

    /** True only when BOTH an exit-confirm label and a stay-in-app label are present (never tap the exit side). */
    public static boolean looksLikeExitDialog(java.util.List<SmartWidget> widgets) {
        boolean hasExit = false, hasStay = false;
        for (SmartWidget w : widgets) {
            String t = w.text() == null ? "" : w.text().trim();
            if (EXIT_INTENT.matcher(t).matches()) hasExit = true;
            if (STAY_INTENT.matcher(t).matches()) hasStay = true;
        }
        return hasExit && hasStay;
    }
}
