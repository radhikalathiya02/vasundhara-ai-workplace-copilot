package com.vasundhara.atf.analysis;

import com.vasundhara.atf.engine.AppIntelligenceReport;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;
import com.vasundhara.atf.model.ApkInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a comprehensive, senior-QA-style end-to-end test case list from static APK
 * analysis + the app-intelligence report (domain, features, user journeys, seed scenarios).
 *
 * <p>This is heuristic/rule-based (pattern matching against the manifest, permissions, SDKs,
 * and discovered features) — consistent with the rest of the framework's "no external LLM
 * calls" design. It does not require a live device/Appium session, so it produces a real,
 * concrete, non-generic test matrix (positive/negative/boundary/edge/navigation/permission/
 * security/network/session/orientation/accessibility/localization/performance) purely from
 * what the APK itself declares, in the app's natural screen order.
 */
public class TestCaseGenerator {

    private final String pkg;

    public TestCaseGenerator(ApkInfo apkInfo) {
        this.pkg = apkInfo.getPackageName();
    }

    public List<TestCaseRow> generate(ApkInfo apkInfo, AppIntelligenceReport report) {
        return generate(apkInfo, report, java.util.List.of(), null);
    }

    public List<TestCaseRow> generate(ApkInfo apkInfo, AppIntelligenceReport report, List<DeepLinkScanner.DeepLink> deepLinks) {
        return generate(apkInfo, report, deepLinks, null);
    }

    /**
     * @param liveExploration real screens/widgets captured by a bounded live crawl of the app on
     *                        a connected device, or {@code null} when no device was available —
     *                        when present, generates additional per-screen/per-button test cases
     *                        that reference the app's actual observed labels (e.g. "Tap 'Grant
     *                        Permission'") instead of only generic screen-type templates.
     */
    public List<TestCaseRow> generate(ApkInfo apkInfo, AppIntelligenceReport report,
                                       List<DeepLinkScanner.DeepLink> deepLinks, ExplorationResult liveExploration) {
        List<TestCaseRow> rows = new ArrayList<>();
        String precond = "App " + pkg + " is installed and launched; device is unlocked.";

        // ── Evidence gathered from the ACTUAL app, used to gate the cross-cutting blocks below so
        // the sheet only contains modules the app genuinely has. Without this gating every app —
        // even a trivial one — got Online/Offline, Forms, Deep Link, Localization, etc. modules it
        // never uses, which read as "modules that aren't in my app" and bloated the sheet with
        // duplicates. Each block below now appears only when there is real evidence for it. ──
        boolean crawled = liveExploration != null && !liveExploration.getScreens().isEmpty();
        int screenCount = crawled ? liveExploration.getScreens().size() : 0;
        boolean sawInput = false, sawMenuOrDialog = false;
        if (crawled) {
            for (ScreenCapture s : liveExploration.getScreens()) {
                String act = s.activity() == null ? "" : s.activity().toLowerCase();
                if (act.contains("dialog") || act.contains("bottomsheet") || act.contains("popup")) sawMenuOrDialog = true;
                for (Widget w : s.widgets()) {
                    if (w.editable()) sawInput = true;
                    String cls = (w.simpleClass() == null ? "" : w.simpleClass().toLowerCase());
                    String lbl = widgetLabel(w).toLowerCase();
                    if (cls.contains("menu") || cls.contains("drawer") || cls.contains("dialog")
                            || lbl.equals("menu") || lbl.contains("more options")) sawMenuOrDialog = true;
                }
            }
        }
        boolean isNetworked = apkInfo.getPermissions().stream().anyMatch(p -> p.contains("INTERNET"))
                || apkInfo.getDetectedSdks().stream().anyMatch(s -> {
                    String x = s.toLowerCase();
                    return x.contains("retrofit") || x.contains("okhttp") || x.contains("volley")
                            || x.contains("http") || x.contains("firebase") || x.contains("api");
                });
        // Forms are relevant if the crawl actually saw an input, or a feature/screen clearly implies one.
        boolean formsRelevant = sawInput || report.getFeatures().stream().anyMatch(f -> {
            String n = f.getName().toLowerCase();
            return n.contains("login") || n.contains("sign") || n.contains("register") || n.contains("search")
                    || n.contains("form") || n.contains("checkout") || n.contains("contact")
                    || n.contains("feedback") || n.contains("profile") || n.contains("setting");
        });
        boolean hasNavigation = screenCount >= 2 || report.getFeatures().size() >= 2;

        // ── 1) Launch / entry point — always first, matches the app's real first screen ──
        rows.add(row("Launch", "App Launch", firstScreenName(apkInfo), "Verify app launches successfully",
                "APK installed on a compatible device", "Launch the app from the launcher icon",
                "App launches within an acceptable time and the first screen is displayed without crashing",
                "P1", "Critical", "Positive", "Functional", "High", "Entry-point smoke test"));
        rows.add(row("Launch", "App Launch", firstScreenName(apkInfo), "Verify app icon and label are correct",
                precond, "Locate the app on the device launcher; inspect its icon and label",
                "App icon and display name match " + safe(apkInfo.getApplicationLabel(), "the expected branding"),
                "P3", "Low", "UI Validation", "UI", "Medium", "Auto-generated from manifest metadata"));
        if (hasOnboardingOrSplash(apkInfo)) {
            rows.add(row("Initial Flow", "Onboarding / Splash", firstScreenName(apkInfo), "Verify splash/onboarding screen transitions correctly",
                    precond, "Launch the app; observe the splash/onboarding screen; wait for it to complete",
                    "Splash/onboarding screen displays for a reasonable duration then transitions to the next screen without hanging",
                    "P2", "Medium", "Positive", "Functional", "High", "Detected via activity name pattern"));
            rows.add(row("Initial Flow", "Onboarding / Splash", firstScreenName(apkInfo), "Verify onboarding can be skipped (if a Skip control is present)",
                    precond, "Launch the app for the first time; look for and tap a Skip/Next-to-end control on the onboarding flow",
                    "User is taken directly to the app's main entry point without being forced through every onboarding page",
                    "P3", "Low", "Positive", "Functional", "Medium", "Applies only if onboarding offers a Skip option"));
            rows.add(row("Initial Flow", "Onboarding / Splash", firstScreenName(apkInfo), "Verify onboarding is not shown again after first completion",
                    precond, "Complete onboarding once; force-close and relaunch the app",
                    "App goes directly to its main screen on subsequent launches; onboarding is not repeated",
                    "P2", "Medium", "Positive", "Functional", "High", "First-launch-only flow verification"));
        }

        // Corpus of text actually seen on the device (activity names + control labels), used to
        // corroborate inferred features. The app-intelligence layer guesses features/domain from
        // manifest keywords and can hallucinate features the app doesn't have (e.g. "Maps &
        // Location" or "Notifications" on a notes app). When a crawl ran, we keep a feature only if
        // the app's real screens/controls corroborate it — this is the fix for "modules that aren't
        // in my app appearing in the sheet". When no crawl ran, corpus is empty and we keep all
        // inferred features (nothing better to go on than the manifest inference).
        Set<String> observed = new LinkedHashSet<>();
        if (crawled) {
            for (ScreenCapture s : liveExploration.getScreens()) {
                observed.add(norm(simpleActivityName(s.activity())));
                for (Widget w : s.widgets()) { String l = norm(widgetLabel(w)); if (!l.isBlank()) observed.add(l); }
            }
        }

        // ── 2) Per-feature test cases, in discovery order (mirrors the app's real flow) ──
        for (AppIntelligenceReport.DiscoveredFeature feature : report.getFeatures()) {
            if (crawled && !featureCorroborated(feature, observed)) continue;   // drop phantom features
            String screen = feature.getRelatedScreens() != null && !feature.getRelatedScreens().isEmpty()
                    ? feature.getRelatedScreens().get(0) : feature.getName();
            addFeatureTestCases(rows, feature.getName(), screen, precond);
        }

        // ── 2b) Live-screen test cases — only when a bounded device crawl actually ran ──
        // Uses the app's REAL observed screens/buttons/fields (not generic screen-type
        // templates), in the exact order the crawl visited them, so steps read like
        // "Tap 'Grant Permission'" instead of "perform its primary action".
        if (liveExploration != null && !liveExploration.getScreens().isEmpty()) {
            addLiveScreenTestCases(rows, liveExploration, precond);
        }

        // Seed scenarios already synthesized by AppIntelligenceAnalyzer per feature — richer,
        // feature-specific wording than the generic ones above; included as additional distinct
        // cases (not duplicates — different scenario text/steps).
        for (AppIntelligenceReport.TestScenario sc : report.getTestScenarios()) {
            // Same corroboration gate — a seed scenario for a feature the crawl never corroborated
            // is dropped so phantom-feature scenarios don't reappear here.
            if (crawled && !tokensCorroborated(sc.getFeatureName(), observed)
                    && !tokensCorroborated(sc.getScreenKeyword(), observed)) continue;
            String category = switch (sc.getType()) {
                case POSITIVE -> "Functional";
                case NEGATIVE -> "Negative";
                case BOUNDARY -> "Boundary";
                case EDGE_CASE -> "Edge Case";
            };
            String screen = safe(sc.getScreenKeyword(), "App");
            rows.add(row(sc.getFeatureName(), sc.getFeatureName(), screen, sc.getScenarioName(),
                    precond, String.join("; ", sc.getSteps()), sc.getExpectedResult(),
                    sc.getType() == AppIntelligenceReport.TestType.NEGATIVE ? "P2" : "P1",
                    sc.getType() == AppIntelligenceReport.TestType.NEGATIVE ? "Medium" : "High",
                    category, "Functional", "High", "Auto-generated from app-intelligence scenario templates"));
        }

        // ── 3) Navigation — only when the app actually has multiple screens to navigate ──
        if (hasNavigation) {
        rows.add(row("Navigation", "Global Navigation", "All screens", "Verify Back button navigates to the previous screen",
                precond, "From any non-root screen, press the device Back button",
                "App returns to the previous screen in the navigation stack without crashing",
                "P1", "High", "Navigation", "Functional", "High", "Applies to every screen reachable from Home"));
        rows.add(row("Navigation", "Global Navigation", "Home / Root screen", "Verify Back button on the root screen exits or prompts confirmation",
                precond, "From the Home/root screen, press the device Back button",
                "App either exits gracefully or shows an exit-confirmation dialog — it does not crash or freeze",
                "P1", "High", "Navigation", "Functional", "High", "Root screen back-stack boundary case"));
        rows.add(row("Navigation", "Deep Navigation", "Multi-level screens", "Verify deep navigation (3+ levels) and return to Home",
                precond, "Navigate through at least 3 nested screens; then repeatedly press Back until Home is reached",
                "Each Back press returns exactly one level; Home is reached without skipped or duplicated screens",
                "P2", "Medium", "Navigation", "Functional", "Medium", "Covers multi-level navigation stack integrity"));
        }

        // ── 4) Menus / bottom sheets / dialogs / popups — only if the crawl actually saw them ──
        if (sawMenuOrDialog) {
        rows.add(row("UI Components", "Menus", "Screens with an overflow/hamburger menu", "Verify overflow/navigation menu opens and lists expected items",
                precond, "Tap the menu/hamburger icon on a screen that has one", "Menu opens with visible, correctly labeled items; tapping outside closes it",
                "P2", "Medium", "Positive", "UI", "High", "Applies where a drawer/overflow menu is present"));
        rows.add(row("UI Components", "Bottom Sheets", "Screens with a bottom sheet", "Verify bottom sheet opens, is scrollable if needed, and dismisses correctly",
                precond, "Trigger the bottom sheet; attempt to scroll its content; swipe down or tap outside to dismiss",
                "Bottom sheet renders correctly, content is fully accessible, and it dismisses without leaving residual UI artifacts",
                "P2", "Medium", "Positive", "UI", "Medium", "Applies where a bottom sheet component is present"));
        rows.add(row("UI Components", "Dialogs & Popups", "Any screen triggering a dialog", "Verify confirmation/alert dialogs render correctly and both actions work",
                precond, "Trigger a dialog (e.g. delete/confirm action); verify both the positive and negative buttons",
                "Dialog displays the correct message and both buttons perform their respective actions without crashing",
                "P1", "High", "Positive", "UI", "Medium", "Applies to any confirmation/alert dialog in the app"));
        }

        // ── 5) Forms / input validation — only if the app actually has input fields ──
        if (formsRelevant) {
        rows.add(row("Forms & Validation", "Input Fields", "Any screen with input fields", "Verify mandatory field validation on empty submit",
                precond, "Navigate to a screen with a form; leave a required field empty; tap Submit/Save",
                "A clear validation error is shown for the empty required field; the form is not submitted",
                "P1", "High", "Negative", "Functional", "High", "Applies to every form screen in the app"));
        rows.add(row("Forms & Validation", "Input Fields", "Any screen with input fields", "Verify boundary-length input is accepted or rejected correctly",
                precond, "Enter the maximum allowed character count in a text field, then one character beyond the limit",
                "Field accepts input up to its documented limit and rejects/truncates input beyond it, with a clear message if rejected",
                "P2", "Medium", "Boundary", "Functional", "High", "Applies to every bounded input field"));
        rows.add(row("Forms & Validation", "Input Fields", "Any screen with input fields", "Verify invalid characters/format are rejected with a clear message",
                precond, "Enter invalid data (e.g. letters in a numeric field, malformed email) into a validated field",
                "App shows a specific, user-understandable validation message and does not proceed or crash",
                "P1", "High", "Negative", "Functional", "High", "Applies to every format-validated input field"));
        }

        // ── 6) Permissions — one concrete row per dangerous permission actually declared ──
        for (String perm : apkInfo.getDangerousPermissions()) {
            String short_ = simplePermissionName(perm);
            rows.add(row("Permissions", short_, "Permission prompt", "Verify " + short_ + " permission is requested with a clear rationale and Allow works",
                    precond, "Trigger the feature requiring " + short_ + "; grant the permission when prompted",
                    "System permission dialog appears at the point of use; granting it allows the dependent feature to work correctly",
                    "P1", "High", "Positive", "Security", "Medium", "Declared in AndroidManifest.xml"));
            rows.add(row("Permissions", short_, "Permission prompt", "Verify graceful degradation when " + short_ + " permission is denied",
                    precond, "Trigger the feature requiring " + short_ + "; deny the permission when prompted",
                    "App does not crash; it shows a clear message and disables only the dependent feature, leaving the rest of the app usable",
                    "P1", "Critical", "Negative", "Security", "Medium", "Declared in AndroidManifest.xml"));
        }
        if (apkInfo.getDangerousPermissions().isEmpty()) {
            rows.add(row("Permissions", "Permissions", "N/A", "Verify app functions without requesting dangerous permissions",
                    precond, "Use the app through its main flows", "No unexpected runtime permission prompts appear",
                    "P3", "Low", "Positive", "Security", "High", "No dangerous permissions declared in the manifest"));
        }

        // ── 7) Ads (only if an ad SDK was detected — never instructs clicking the ad itself) ──
        boolean hasAds = apkInfo.getDetectedSdks().stream().anyMatch(s -> s.toLowerCase().contains("ad")
                || s.toLowerCase().contains("mobile ads") || s.toLowerCase().contains("audience network"));
        if (hasAds) {
            rows.add(row("Monetization", "Ads", "Screens showing ads", "Verify ad content loads without blocking core app functionality",
                    precond, "Navigate to a screen expected to show an ad; observe load behavior",
                    "Ad loads asynchronously and does not freeze the UI or delay the screen's own content; app remains usable if the ad fails to load",
                    "P2", "Medium", "Positive", "Functional", "Low", "Ad-network SDK detected in APK"));
            rows.add(row("Monetization", "Ads", "Screens showing ads", "Verify closing/skipping an ad returns to the correct app screen",
                    precond, "Trigger an interstitial/rewarded ad if present; use its Close/Skip control",
                    "App returns to the exact screen the ad was triggered from, with no lost state",
                    "P2", "Medium", "Positive", "Functional", "Low", "Manual validation recommended (click behavior is out of scope for automated crawling)"));
        }

        // ── 7b) Deep links — only if the manifest actually declares a VIEW+BROWSABLE intent-filter ──
        for (DeepLinkScanner.DeepLink dl : deepLinks) {
            String target = dl.scheme() + "://" + (dl.host().isBlank() ? "..." : dl.host());
            rows.add(row("Deep Links", "Deep Link Handling", simpleActivityName(dl.activity()),
                    "Verify deep link '" + target + "' opens the correct in-app screen",
                    "App is installed (may or may not already be running)",
                    "From an external source (browser/notes app/adb), open a link matching " + target,
                    "App opens directly to the screen/content the deep link targets, with the correct data loaded — not just the app's default launch screen",
                    "P1", "High", "Positive", "Functional", "Medium", "Declared via intent-filter on " + simpleActivityName(dl.activity())));
            rows.add(row("Deep Links", "Deep Link Handling", simpleActivityName(dl.activity()),
                    "Verify a malformed/incomplete '" + dl.scheme() + "://' link is handled gracefully",
                    "App is installed", "Open a deep link using the '" + dl.scheme() + "' scheme with missing or invalid parameters",
                    "App falls back to a sensible default screen (or shows an error) instead of crashing",
                    "P2", "Medium", "Negative", "Security", "Low", "Deep-link input validation check"));
        }
        // (No speculative "confirm whether the app supports deep links" row — deep-link cases are
        // emitted only for intent-filters the manifest actually declares.)

        // ── 8) Security observations, derived directly from manifest facts ──
        if (apkInfo.isUsesCleartextTraffic()) {
            rows.add(row("Security", "Network Security", "N/A", "Verify no sensitive data is transmitted over unencrypted HTTP",
                    precond, "Inspect network traffic (proxy/mitm tool) while using login/payment/profile screens",
                    "All sensitive data (credentials, tokens, personal data) is sent over HTTPS even though cleartext traffic is technically permitted",
                    "P1", "High", "Security", "Security", "Low", "Manifest declares usesCleartextTraffic=true"));
        }
        if (apkInfo.isDebuggable()) {
            rows.add(row("Security", "Build Configuration", "N/A", "Verify the release build is not debuggable",
                    precond, "Inspect the build under test", "android:debuggable is false in the production release build",
                    "P1", "High", "Security", "Security", "High", "This build has android:debuggable=true — flag before release"));
        }
        if (!apkInfo.getExportedComponents().isEmpty()) {
            rows.add(row("Security", "Exported Components", "N/A", "Verify exported components cannot be exploited by other apps",
                    precond, "Attempt to launch each exported component (" + String.join(", ", capList(apkInfo.getExportedComponents(), 5)) + ") from an external app/adb",
                    "Exported components validate their inputs and do not expose sensitive data or actions to untrusted callers",
                    "P1", "High", "Security", "Security", "Low", apkInfo.getExportedComponents().size() + " exported component(s) declared"));
        }

        // ── 9) Online/Offline flow — only for apps that actually use the network ──
        if (isNetworked) {
        rows.add(row("Online/Offline Flow", "Connectivity", "All network-dependent screens", "Verify app behavior when launched with no network connection",
                precond + " Device is in Airplane mode.", "Enable Airplane mode; launch the app; navigate to a network-dependent screen",
                "App shows a clear offline/no-connection message instead of crashing or hanging indefinitely",
                "P1", "High", "Negative", "Network", "High", "Applies to any screen that fetches remote data"));
        rows.add(row("Online/Offline Flow", "Connectivity", "All network-dependent screens", "Verify app recovers when connectivity is restored mid-session",
                precond, "Start a network operation; disable connectivity mid-operation; then re-enable it",
                "App retries or allows the user to retry the operation once connectivity returns, without requiring a full restart",
                "P2", "Medium", "Positive", "Network", "Medium", "Covers connectivity-recovery handling"));
        rows.add(row("Online/Offline Flow", "Connectivity", "All screens with cached/local data", "Verify previously loaded content remains viewable while offline",
                precond, "Load a data-driven screen while online; go offline; revisit the same screen",
                "Previously fetched content remains visible (if the app caches it) or a clear offline message replaces it — no blank/broken screen",
                "P2", "Medium", "Edge Case", "Network", "Medium", "Covers offline-cache behavior"));
        rows.add(row("Online/Offline Flow", "Connectivity", "Screens with a submit/save action", "Verify submitting data while offline is handled gracefully",
                precond + " Device is in Airplane mode.", "Fill a form/complete an action while offline; attempt to submit",
                "App queues the action for retry or clearly informs the user the action requires connectivity — no silent data loss",
                "P1", "High", "Negative", "Network", "Medium", "Covers offline write-operation handling"));
        }

        // ── 10-14) Cross-cutting non-functional checks (session, orientation, accessibility,
        // localization, performance). Included only when the app was actually launched/crawled —
        // for a real running app these are legitimate senior-QA coverage; we skip them entirely
        // for static-only analysis so a can't-even-launch APK doesn't get a wall of non-functional
        // modules that read as features it doesn't have. ──
        if (crawled) {
        rows.add(row("Session", "App Lifecycle", "All screens", "Verify app state is preserved after backgrounding and resuming",
                precond, "Open the app to any screen with in-progress data (e.g. a partially filled form); press Home; reopen the app",
                "App resumes to the same screen with the in-progress data intact, not reset to the launch screen",
                "P1", "High", "Positive", "Functional", "High", "Background/foreground state-retention check"));
        rows.add(row("Session", "App Lifecycle", "All screens", "Verify app recovers correctly after being killed by the OS and relaunched",
                precond, "Force-stop the app from device settings while on a non-root screen; relaunch it from the launcher",
                "App restarts cleanly to its normal entry point without crashing or showing corrupted state",
                "P2", "Medium", "Edge Case", "Functional", "High", "Simulates OS-initiated process death"));

        // ── 11) Orientation ──
        rows.add(row("UI/Compatibility", "Orientation", "Primary screens", "Verify UI adapts correctly on device rotation",
                precond, "Rotate the device from portrait to landscape (and back) on the main screens",
                "Layout re-flows correctly with no clipped/overlapping content and no data loss on in-progress screens",
                "P2", "Medium", "UI Validation", "UI", "Medium", "Skip if the app locks orientation — verify manifest first"));

        // ── 12) Accessibility ──
        rows.add(row("Accessibility", "Screen Reader Support", "All interactive screens", "Verify all interactive controls have accessible labels",
                precond, "Enable TalkBack (or a screen reader); navigate through primary screens using swipe gestures",
                "Every interactive control (buttons, icons, inputs) is announced with a meaningful label; nothing is silently skipped",
                "P2", "Medium", "Accessibility", "Accessibility", "Medium", "WCAG/Google Accessibility Test Framework alignment"));
        rows.add(row("Accessibility", "Touch Target Size", "All interactive screens", "Verify touch targets meet the minimum recommended size",
                precond, "Inspect small icon buttons and checkboxes on dense screens", "Touch targets are at least 48x48dp or have adequate padding to avoid mis-taps",
                "P3", "Low", "Accessibility", "Accessibility", "Low", "Common accessibility audit item"));

        // ── 13) Localization ──
        List<String> locales = apkInfo.getSupportedLocales();
        if (locales != null && !locales.isEmpty()) {
            for (String loc : capList(locales, 5)) {
                rows.add(row("Localization", "Language Support", "All screens", "Verify UI renders correctly in locale: " + loc,
                        precond, "Set the device system language to " + loc + "; relaunch the app; review all primary screens",
                        "All strings are translated (no untranslated fallback text), and layouts accommodate longer/shorter translated text without truncation or overlap",
                        "P3", "Low", "Localization", "Localization", "Medium", "Declared supported locale"));
            }
        }
        // (No single-locale "verify default text" filler — it added a Localization module to every
        // single-language app without testing anything the functional cases don't already cover.)

        // ── 14) Performance observations ──
        rows.add(row("Performance", "Startup", firstScreenName(apkInfo), "Verify cold-start time is within an acceptable range",
                precond, "Force-stop the app; launch it and measure time to first interactive frame",
                "Cold start completes in a reasonable time (guideline: under ~2-3s on a mid-range device) without an ANR",
                "P2", "Medium", "Performance", "Performance", "High", "Benchmark-style observation"));
        rows.add(row("Performance", "Scrolling & Rendering", "List/feed screens", "Verify smooth scrolling on content-heavy screens",
                precond, "Scroll rapidly through a long list/feed screen", "Scrolling remains smooth with no visible jank or frame drops",
                "P3", "Low", "Performance", "Performance", "Medium", "Rendering-performance observation"));
        }  // end crawled-only cross-cutting block

        return dedupe(rows);
    }

    /**
     * Removes duplicate/near-duplicate test cases. Two rows are the same when their scenario text
     * normalizes identically, or when scenario + steps normalize identically — the overlap that
     * previously arose between the per-feature, seed-scenario, and live-screen passes (e.g. three
     * "verify Login screen loads" variants). First occurrence wins, preserving flow order.
     */
    private List<TestCaseRow> dedupe(List<TestCaseRow> rows) {
        List<TestCaseRow> out = new ArrayList<>();
        Set<String> seenScenario = new LinkedHashSet<>();
        Set<String> seenPair = new LinkedHashSet<>();
        for (TestCaseRow r : rows) {
            String scen = norm(r.scenario);
            String pair = scen + "||" + norm(r.steps);
            if (scen.isBlank()) { out.add(r); continue; }
            if (!seenScenario.add(scen)) continue;   // same scenario wording already covered
            if (!seenPair.add(pair)) continue;        // identical scenario+steps
            out.add(r);
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", " ").replaceAll("\\s+", " ").trim();
    }

    // Words too generic to corroborate a feature by themselves — matching on these would let any
    // feature "corroborate" against almost any screen.
    private static final Set<String> STOPWORDS = Set.of("and","the","of","app","screen","view",
            "activity","main","home","page","test","support","help","settings","setting","more","menu");

    /** A feature is corroborated if any meaningful token of its name/keyword, or one of its related
     *  screen names, matches text actually observed on the device. */
    private boolean featureCorroborated(AppIntelligenceReport.DiscoveredFeature f, Set<String> observed) {
        if (tokensCorroborated(f.getName(), observed) || tokensCorroborated(f.getScreenKeyword(), observed)) return true;
        if (f.getRelatedScreens() != null)
            for (String rs : f.getRelatedScreens())
                if (tokensCorroborated(simpleActivityName(rs), observed)) return true;
        return false;
    }

    /** True if any meaningful (non-stopword, ≥3 char) token of {@code phrase} appears within any
     *  observed screen/label string. */
    private boolean tokensCorroborated(String phrase, Set<String> observed) {
        String n = norm(phrase);
        if (n.isBlank()) return false;
        for (String tok : n.split(" ")) {
            if (tok.length() < 3 || STOPWORDS.contains(tok)) continue;
            for (String o : observed) if (o.contains(tok)) return true;
        }
        return false;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * One test case per real, labeled, actionable widget the live crawl actually found, in visit
     * order — mirrors how a senior manual QA engineer writes cases while actually using the app
     * ("Tap 'Grant Permission'", not "perform its primary action"). Capped per screen so a very
     * dense screen doesn't explode the sheet; unlabeled/decorative widgets are skipped since a
     * test step referencing them wouldn't be reproducible by a human reader.
     */
    private void addLiveScreenTestCases(List<TestCaseRow> rows, ExplorationResult exploration, String precond) {
        final int MAX_WIDGETS_PER_SCREEN = 20;
        Set<String> seenLabels = new LinkedHashSet<>();
        for (ScreenCapture screen : exploration.getScreens()) {
            String screenName = simpleActivityName(screen.activity());
            rows.add(row(screenName, screenName, screenName, "Verify " + screenName + " screen renders as expected",
                    precond, "Navigate to the " + screenName + " screen (as reached during exploration); observe its content",
                    "Screen displays real content matching its purpose — no blank areas, placeholder text, or layout overlap",
                    "P1", "High", "UI Validation", "UI", "High", "Captured via live device crawl"));

            int added = 0;
            for (Widget w : screen.widgets()) {
                if (added >= MAX_WIDGETS_PER_SCREEN) break;
                String label = widgetLabel(w);
                if (label.isEmpty()) continue;
                String dedupeKey = screenName + "|" + label;
                if (!seenLabels.add(dedupeKey)) continue; // same control already covered on this screen

                if (w.editable()) {
                    rows.add(row(screenName, screenName, screenName, "Verify entering valid data into '" + label + "' is accepted",
                            precond, "On the " + screenName + " screen, tap the '" + label + "' field and enter a valid value",
                            "Field accepts the value, shows no validation error, and the entered value is retained",
                            "P1", "High", "Positive", "Functional", "High", "Captured via live device crawl"));
                    rows.add(row(screenName, screenName, screenName, "Verify '" + label + "' rejects invalid/empty input where applicable",
                            precond, "On the " + screenName + " screen, leave '" + label + "' empty or enter clearly invalid data; attempt to proceed",
                            "A clear validation message is shown (if the field is required/validated) and no crash occurs",
                            "P2", "Medium", "Negative", "Functional", "High", "Captured via live device crawl"));
                } else if (w.clickable()) {
                    rows.add(row(screenName, screenName, screenName, "Verify tapping '" + label + "' works as expected",
                            precond, "On the " + screenName + " screen, tap '" + label + "'",
                            "'" + label + "' performs its expected action (navigation, state change, or confirmation) without crashing or freezing the app",
                            "P1", "High", "Positive", "Functional", "High", "Captured via live device crawl"));
                }
                added++;
            }
        }
    }

    /** Prefers visible text, falling back to content-description (accessibility label). */
    private String widgetLabel(Widget w) {
        if (w.text() != null && !w.text().isBlank()) return w.text().trim();
        if (w.contentDesc() != null && !w.contentDesc().isBlank()) return w.contentDesc().trim();
        return "";
    }

    private void addFeatureTestCases(List<TestCaseRow> rows, String featureName, String screen, String precond) {
        rows.add(row(featureName, featureName, screen, "Verify " + featureName + " screen loads with all expected elements",
                precond, "Navigate to the " + featureName + " screen; verify all expected UI elements are present and visible",
                featureName + " screen renders completely with correct content, no missing elements or placeholder text",
                "P1", "High", "Positive", "Functional", "High", "Auto-generated core screen-load case"));
        rows.add(row(featureName, featureName, screen, "Verify primary action(s) on " + featureName + " complete successfully",
                precond, "On the " + featureName + " screen, perform its primary action (e.g. submit/save/confirm) with valid data",
                "The action completes successfully and the app transitions to the expected next state",
                "P1", "High", "Positive", "Functional", "High", "Auto-generated primary-action case"));
        rows.add(row(featureName, featureName, screen, "Verify " + featureName + " handles an unexpected error gracefully",
                precond, "On the " + featureName + " screen, trigger a failure condition (e.g. simulate a server error or invalid state) if applicable",
                "App shows a clear, actionable error message and remains stable — no crash or blank screen",
                "P2", "Medium", "Negative", "Functional", "Medium", "Auto-generated error-handling case"));
    }

    private TestCaseRow row(String module, String feature, String screen, String scenario, String precond,
                             String steps, String expected, String priority, String severity,
                             String testType, String category, String automation, String remarks) {
        return new TestCaseRow(module, feature, screen, scenario, precond, steps, expected,
                priority, severity, testType, category, automation, remarks);
    }

    private String firstScreenName(ApkInfo apkInfo) {
        if (apkInfo.getMainActivity() != null && !apkInfo.getMainActivity().isBlank()) {
            String a = apkInfo.getMainActivity();
            int idx = Math.max(a.lastIndexOf('.'), a.lastIndexOf('$'));
            return idx >= 0 ? a.substring(idx + 1) : a;
        }
        return "Launch Screen";
    }

    private String simpleActivityName(String activity) {
        if (activity == null || activity.isBlank()) return "App";
        int idx = Math.max(activity.lastIndexOf('.'), activity.lastIndexOf('$'));
        return idx >= 0 ? activity.substring(idx + 1) : activity;
    }

    private boolean hasOnboardingOrSplash(ApkInfo apkInfo) {
        return apkInfo.getActivities().stream().anyMatch(a -> {
            String l = a.toLowerCase();
            return l.contains("splash") || l.contains("onboard") || l.contains("intro") || l.contains("welcome");
        });
    }

    private String simplePermissionName(String perm) {
        int idx = perm.lastIndexOf('.');
        String name = idx >= 0 ? perm.substring(idx + 1) : perm;
        return name.replace('_', ' ');
    }

    private String safe(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private List<String> capList(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
