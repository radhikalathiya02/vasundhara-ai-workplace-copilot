package com.vasundhara.atf.engine;

import com.vasundhara.atf.ai.LlmAppProfiler;
import com.vasundhara.atf.model.ApkInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds an {@link AppIntelligenceReport} by fusing APK static metadata with the runtime UI
 * exploration result. The baseline analysis is entirely on-device pattern-matching against
 * activity names, permissions, detected SDKs and widget labels — this always runs, for every
 * APK, with no network dependency, and is what every existing caller could already rely on.
 *
 * <p>When AI Review is enabled (Settings → AI Review — the same toggle {@code AiScreenReviewer}
 * uses), {@link LlmAppProfiler} runs as a second, additive pass: it reasons from the same kind of
 * evidence (manifest signals + real on-screen text from the crawl) rather than a fixed keyword
 * dictionary, so it can name a domain/feature the heuristic's dictionary simply doesn't have an
 * entry for. Its results are merged on top of the heuristic's, never substituted — if AI review
 * is off, misconfigured, or the call fails for any reason, the heuristic result is already
 * complete and correct on its own.
 */
@Component
public class AppIntelligenceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AppIntelligenceAnalyzer.class);

    private final LlmAppProfiler llmProfiler;

    public AppIntelligenceAnalyzer(LlmAppProfiler llmProfiler) {
        this.llmProfiler = llmProfiler;
    }

    public AppIntelligenceReport analyze(ApkInfo apkInfo, ExplorationResult exploration) {
        AppIntelligenceReport report = new AppIntelligenceReport();
        String aiNote = null;
        try {
            inferDomain(apkInfo, report);
            discoverFeatures(apkInfo, exploration, report);
            buildUserJourneys(exploration, report);
            aiNote = mergeLlmProfile(apkInfo, exploration, report);
            generateTestScenarios(report);
            buildAnalysisNotes(apkInfo, exploration, report, aiNote);
        } catch (Exception e) {
            log.warn("AppIntelligenceAnalyzer error: {}", e.toString());
            report.setAnalysisNotes("App intelligence analysis completed with warnings: " + e.getMessage());
        }
        return report;
    }

    /**
     * Merges {@link LlmAppProfiler}'s data-driven analysis on top of the heuristic result above.
     * The domain/category are replaced with the LLM's (evidence-based, not a fixed dictionary)
     * whenever it returns one; features and journeys are merged additively (deduped by name) so
     * heuristic findings the LLM's evidence sample happened to miss — e.g. a widget-mined "Share"
     * button — are never lost. Runs AFTER buildUserJourneys() and BEFORE generateTestScenarios()
     * so any newly-merged features still get scenarios generated for them below.
     *
     * @return a note describing what the AI pass did, for {@link #buildAnalysisNotes} to append —
     *         {@code null} when AI review is off/unconfigured or the call failed, since
     *         buildAnalysisNotes() rebuilds analysisNotes from scratch and would otherwise
     *         silently discard anything written here directly.
     */
    private String mergeLlmProfile(ApkInfo apkInfo, ExplorationResult exploration, AppIntelligenceReport report) {
        if (!llmProfiler.isEnabled()) return null;
        LlmAppProfiler.Profile profile = llmProfiler.profile(apkInfo, exploration);
        if (profile == null) return null; // disabled, unconfigured, or the call failed — heuristic stands alone

        report.setAppDomain(profile.appDomain());
        report.setAppCategory(profile.appCategory());

        Set<String> existingFeatureNames = new HashSet<>();
        for (AppIntelligenceReport.DiscoveredFeature f : report.getFeatures()) existingFeatureNames.add(f.getName());
        for (LlmAppProfiler.ProfiledFeature f : profile.features()) {
            if (!existingFeatureNames.add(f.name())) continue; // heuristic already found this one
            report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                    f.name(), f.description(), f.relatedScreens(), f.screenKeyword()));
        }

        Set<String> existingJourneyNames = new HashSet<>();
        for (AppIntelligenceReport.UserJourney j : report.getUserJourneys()) existingJourneyNames.add(j.getName());
        for (LlmAppProfiler.ProfiledJourney j : profile.userJourneys()) {
            if (!existingJourneyNames.add(j.name())) continue;
            report.getUserJourneys().add(new AppIntelligenceReport.UserJourney(j.name(), j.steps()));
        }

        log.debug("LLM app profile merged for {}: domain={} category={}",
                apkInfo.getPackageName(), report.getAppDomain(), report.getAppCategory());
        return "AI-assisted analysis merged" + (profile.notes() == null || profile.notes().isBlank()
                ? "." : " — " + profile.notes());
    }

    // ── Domain inference ──────────────────────────────────────────────────────

    /**
     * Activity-name fragments that signal freemium/monetization plumbing (paywalls, ad-removal
     * upsells, billing SDK screens) rather than the app's actual domain. Almost every category of
     * app — notes, games, photo editors, utilities — ships one of these once it has any premium
     * tier or ad-supported free tier, so their names must never feed domain inference. Concretely:
     * an activity named "PaywallActivity" contains "pay" as a bare substring, which used to be
     * enough to misclassify a plain notes app as "Finance & Banking" — verified live this session.
     */
    private static final java.util.regex.Pattern MONETIZATION_NOISE = java.util.regex.Pattern.compile(
            "paywall|premium|subscri|billing|upgrade|removeads|remove_ads|purchase|inapp|in_app|unlock|goPro|adfree|ad_free|upsell",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** One candidate domain: display name, category, and the keywords that count as evidence for it. */
    private record DomainRule(String domain, String category, String... keywords) {}

    private static final List<DomainRule> DOMAIN_RULES = List.of(
            new DomainRule("E-commerce & Shopping", "Shopping", "shop", "store", "buy", "market", "cart", "product", "order", "checkout"),
            new DomainRule("Finance & Banking", "Finance", "bank", "banking", "finance", "wallet", "invest", "loan", "credit score", "netbanking", "upi"),
            new DomainRule("Social Networking & Communication", "Social", "chat", "message", "social", "friend", "feed", "follow"),
            new DomainRule("Health & Fitness", "Health", "health", "fitness", "workout", "gym", "exercise", "calories", "steps", "heart rate", "medic"),
            new DomainRule("News & Reading", "News", "news", "article", "blog", "rss", "headline"),
            new DomainRule("Music & Audio", "Entertainment", "music", "audio", "player", "playlist", "song", "radio", "podcast"),
            new DomainRule("Video & Streaming", "Entertainment", "video", "watch", "stream", "movie", "episode", "youtube"),
            new DomainRule("Photo & Camera", "Photography", "photo", "camera", "gallery", "picture", "image", "album", "filter"),
            new DomainRule("Food & Dining", "Food", "food", "recipe", "restaurant", "delivery", "meal", "cook", "dish"),
            new DomainRule("Travel & Booking", "Travel", "travel", "hotel", "flight", "booking", "trip", "tourism"),
            new DomainRule("Navigation & Maps", "Navigation", "map", "navigation", "route", "direction", "location", "gps", "commute", "transit"),
            new DomainRule("Productivity & Task Management", "Productivity", "todo", "task", "note", "remind", "calendar", "planner", "agenda"),
            new DomainRule("Education & Learning", "Education", "learn", "education", "school", "course", "quiz", "lesson", "tutor", "student"),
            new DomainRule("Gaming", "Games", "game", "arcade", "puzzle level", "gameplay"),
            new DomainRule("Security & Privacy", "Tools", "vpn", "antivirus", "firewall"),
            new DomainRule("Weather", "Weather", "weather", "forecast", "climate"),
            new DomainRule("File Management", "Tools", "file manager", "explorer", "document", "pdf", "storage")
    );

    private void inferDomain(ApkInfo apkInfo, AppIntelligenceReport report) {
        String label = lower(apkInfo.getApplicationLabel());
        String pkg   = lower(apkInfo.getPackageName());
        Set<String> perms = Set.copyOf(apkInfo.getPermissions());
        List<String> sdks = apkInfo.getDetectedSdks();
        List<String> acts = ownActivities(apkInfo);
        String activityText = acts.stream().map(String::toLowerCase).reduce("", (a, b) -> a + " " + b);

        if (perms.contains("android.permission.CAMERA") && perms.contains("android.permission.RECORD_AUDIO")
                && matches(label, pkg, activityText, "meet", "call", "video conference", "conference")) {
            report.setAppDomain("Video Calling & Conferencing");
            report.setAppCategory("Communication");
            return;
        }

        // Score every candidate domain instead of committing to the first substring hit anywhere.
        // A match in the app's own label/package name is a strong, deliberate signal (the
        // developer named the app after its purpose); a match only inside an activity name is
        // weaker corroborating evidence, since a single incidental screen name is easy to trip
        // by coincidence. Picking the highest-scoring domain (with a minimum bar) instead of
        // "first branch that matches anything" prevents one unrelated activity name from
        // hijacking the whole app's classification.
        DomainRule best = null;
        int bestScore = 0;
        for (DomainRule rule : DOMAIN_RULES) {
            int score = 0;
            for (String kw : rule.keywords()) {
                if (label.contains(kw)) score += 5;
                if (pkg.contains(kw)) score += 5;
                if (activityText.contains(kw)) score += 1;
            }
            if (score > bestScore) { bestScore = score; best = rule; }
        }

        if (best != null && bestScore >= 3) {
            report.setAppDomain(best.domain());
            report.setAppCategory(best.category());
        } else if (sdks.stream().anyMatch(s -> s.contains("AdMob") || s.contains("Firebase"))) {
            report.setAppDomain("Ad-Supported Android Application");
            report.setAppCategory("General");
        } else {
            report.setAppDomain("General Android Application");
            report.setAppCategory("General");
        }
    }

    /**
     * The manifest declares every {@code <activity>} the APK ships, including ones bundled
     * third-party SDKs contribute (ad networks, billing/subscription SDKs, Google Play Services)
     * — those are not real app screens and must never feed domain/feature inference. A generic
     * English word in a vendor SDK's class name (e.g. RevenueCat's billing SDK ships an activity
     * literally named {@code SimulatedStoreErrorDialogActivity}) previously caused false-positive
     * domain matches — an unrelated app got misclassified as "E-commerce & Shopping" purely
     * because that bundled *billing* library's activity name happens to contain "Store". Only an
     * activity whose declared name starts with the app's own package name is a real app screen.
     *
     * <p>Also drops freemium/monetization plumbing (paywall, ad-removal upsell, billing-SDK
     * screens — see {@link #MONETIZATION_NOISE}) even when it IS one of the app's own activities:
     * almost every category of app ships one of these once it has any premium tier or ad-supported
     * free tier, so their names must never feed domain/feature inference either (a "PurchaseActivity"
     * for removing ads previously invented a phantom "Shopping Cart & Checkout" feature on apps
     * that have no shopping functionality at all).
     */
    private List<String> ownActivities(ApkInfo apkInfo) {
        String pkg = apkInfo.getPackageName();
        List<String> activities = apkInfo.getActivities();
        return activities.stream()
                .filter(a -> a != null && (pkg == null || pkg.isBlank() || a.startsWith(pkg) || a.startsWith(".")))
                .filter(a -> !MONETIZATION_NOISE.matcher(a).find())
                .toList();
    }

    private boolean matches(String label, String pkg, String activityText, String... keywords) {
        for (String kw : keywords) {
            if (label.contains(kw) || pkg.contains(kw) || activityText.contains(kw)) return true;
        }
        return false;
    }

    // ── Feature discovery ─────────────────────────────────────────────────────

    private void discoverFeatures(ApkInfo apkInfo, ExplorationResult exploration, AppIntelligenceReport report) {
        List<String> activities = ownActivities(apkInfo);

        // Map activity name fragments → feature definitions
        addFeatureIfPresent(activities, exploration, report, "login|signin|sign_in|authenticate",
                "User Authentication", "Login / Sign-In screen where users enter credentials to access the app", "login");
        addFeatureIfPresent(activities, exploration, report, "register|signup|sign_up|createaccount|create_account",
                "User Registration", "Registration flow for creating a new account", "register");
        addFeatureIfPresent(activities, exploration, report, "forgot|resetpass|forgotpassword|reset_pass",
                "Password Reset", "Forgot Password / password recovery flow", "forgot");
        addFeatureIfPresent(activities, exploration, report, "home|main|dashboard|feed|home_screen",
                "Home Screen / Dashboard", "Main application home screen with primary content and navigation", "home");
        addFeatureIfPresent(activities, exploration, report, "profile|account|myaccount|user_profile",
                "User Profile", "User profile view and editing capabilities", "profile");
        addFeatureIfPresent(activities, exploration, report, "settings|preferences|config|setup",
                "Settings & Preferences", "Application settings and user preference management", "settings");
        addFeatureIfPresent(activities, exploration, report, "search|find|discover|query",
                "Search", "Search functionality to find content within the app", "search");
        addFeatureIfPresent(activities, exploration, report, "cart|basket|checkout|purchase|payment|order",
                "Shopping Cart & Checkout", "Cart management and checkout/payment flow", "cart");
        addFeatureIfPresent(activities, exploration, report, "product|item|detail|listing",
                "Product / Item Details", "Product or item detail view", "product");
        addFeatureIfPresent(activities, exploration, report, "chat|message|inbox|conversation",
                "Messaging / Chat", "In-app messaging or chat functionality", "chat");
        addFeatureIfPresent(activities, exploration, report, "notification|alert|inbox",
                "Notifications", "In-app notification center or alert management", "notification");
        addFeatureIfPresent(activities, exploration, report, "map|location|navigation|route",
                "Maps & Location", "Map view and location-based features", "map");
        addFeatureIfPresent(activities, exploration, report, "camera|photo|capture|scan",
                "Camera & Media Capture", "Camera interface for photo/video capture", "camera");
        addFeatureIfPresent(activities, exploration, report, "gallery|photo|album|media",
                "Media Gallery", "Photo or media gallery browser", "gallery");
        addFeatureIfPresent(activities, exploration, report, "filter|sort|category",
                "Content Filtering & Sorting", "Filtering, sorting and category selection", "filter");
        addFeatureIfPresent(activities, exploration, report, "onboard|welcome|intro|tour|splash|guide",
                "Onboarding / Welcome", "App introduction or onboarding flow for new users", "onboard");
        addFeatureIfPresent(activities, exploration, report, "about|info|help|faq|support|contact",
                "Help & Support", "Help center, FAQ or contact support", "help");
        addFeatureIfPresent(activities, exploration, report, "privacy|terms|legal",
                "Privacy & Terms", "Privacy policy and terms of service", "privacy");

        // Also mine exploration screen widget labels for feature signals
        mineWidgetsForFeatures(exploration, report);

        // Always include Home if no features were found
        if (report.getFeatures().isEmpty()) {
            List<String> screens = exploration != null
                    ? exploration.getScreens().stream().map(ScreenCapture::activity).distinct().toList()
                    : List.of();
            report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                    "Home Screen", "Main application screen",
                    screens.isEmpty() ? List.of("MainActivity") : screens.subList(0, 1),
                    "main"));
        }
    }

    private void addFeatureIfPresent(List<String> activities, ExplorationResult exploration,
                                     AppIntelligenceReport report, String pattern,
                                     String featureName, String description, String screenKeyword) {
        String[] parts = pattern.split("\\|");
        List<String> matching = new ArrayList<>();
        for (String a : activities) {
            String simple = a.toLowerCase();
            for (String p : parts) {
                if (simple.contains(p)) { matching.add(simpleName(a)); break; }
            }
        }
        if (!matching.isEmpty()) {
            report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                    featureName, description, matching, screenKeyword));
            return;
        }
        // Also check exploration screen activities
        if (exploration != null) {
            for (ScreenCapture sc : exploration.getScreens()) {
                String act = lower(sc.activity());
                for (String p : parts) {
                    if (act.contains(p)) {
                        report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                                featureName, description, List.of(simpleName(sc.activity())), screenKeyword));
                        return;
                    }
                }
            }
        }
    }

    private void mineWidgetsForFeatures(ExplorationResult exploration, AppIntelligenceReport report) {
        if (exploration == null) return;
        Set<String> existingNames = new java.util.HashSet<>();
        for (AppIntelligenceReport.DiscoveredFeature f : report.getFeatures()) existingNames.add(f.getName());

        for (ScreenCapture sc : exploration.getScreens()) {
            for (Widget w : sc.widgets()) {
                String label = lower(w.text() == null ? "" : w.text()) + " " + lower(w.contentDesc() == null ? "" : w.contentDesc());
                if (!existingNames.contains("Shopping Cart & Checkout")
                        && (label.contains("add to cart") || label.contains("buy now") || label.contains("purchase"))) {
                    report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                            "Shopping Cart & Checkout", "Purchase / checkout flow", List.of(simpleName(sc.activity())), "cart"));
                    existingNames.add("Shopping Cart & Checkout");
                }
                if (!existingNames.contains("Share")
                        && (label.contains("share") || label.equals("share"))) {
                    report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                            "Share", "Content sharing functionality", List.of(simpleName(sc.activity())), "share"));
                    existingNames.add("Share");
                }
                if (!existingNames.contains("Download / Save")
                        && (label.contains("download") || label.contains("save"))) {
                    report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                            "Download / Save", "Content download or save functionality", List.of(simpleName(sc.activity())), "download"));
                    existingNames.add("Download / Save");
                }
                if (!existingNames.contains("Favorites / Bookmarks")
                        && (label.contains("favorite") || label.contains("bookmark") || label.contains("wishlist") || label.contains("save for later"))) {
                    report.getFeatures().add(new AppIntelligenceReport.DiscoveredFeature(
                            "Favorites / Bookmarks", "Favorites or bookmarks management", List.of(simpleName(sc.activity())), "favorite"));
                    existingNames.add("Favorites / Bookmarks");
                }
            }
        }
    }

    // ── User journeys ─────────────────────────────────────────────────────────

    private void buildUserJourneys(ExplorationResult exploration, AppIntelligenceReport report) {
        List<AppIntelligenceReport.DiscoveredFeature> features = report.getFeatures();
        if (features.isEmpty()) return;

        // Build journeys by chaining related features
        boolean hasAuth = features.stream().anyMatch(f -> f.getName().equals("User Authentication"));
        boolean hasReg  = features.stream().anyMatch(f -> f.getName().equals("User Registration"));
        boolean hasHome = features.stream().anyMatch(f -> f.getName().contains("Home"));
        boolean hasCart = features.stream().anyMatch(f -> f.getName().contains("Cart"));
        boolean hasProf = features.stream().anyMatch(f -> f.getName().contains("Profile"));
        boolean hasSrch = features.stream().anyMatch(f -> f.getName().equals("Search"));

        if (hasAuth && hasHome) {
            List<String> steps = new ArrayList<>();
            steps.add("Launch app");
            steps.add("Enter valid credentials on Login screen");
            steps.add("Tap Login/Sign In button");
            steps.add("Verify Home screen loads successfully");
            if (hasProf) steps.add("Navigate to Profile");
            report.getUserJourneys().add(new AppIntelligenceReport.UserJourney("Happy Path Login", steps));
        }

        if (hasReg) {
            List<String> steps = new ArrayList<>(List.of(
                    "Launch app", "Navigate to Registration screen", "Enter valid details",
                    "Submit registration form", "Verify account creation success"));
            report.getUserJourneys().add(new AppIntelligenceReport.UserJourney("New User Registration", steps));
        }

        if (hasCart) {
            List<String> steps = new ArrayList<>(List.of(
                    "Browse products", "Open product details", "Add item to cart",
                    "View cart contents", "Proceed to checkout", "Verify order summary"));
            report.getUserJourneys().add(new AppIntelligenceReport.UserJourney("Product Purchase Flow", steps));
        }

        if (hasSrch) {
            List<String> steps = new ArrayList<>(List.of(
                    "Open search interface", "Enter a search term", "View search results",
                    "Select a result", "Verify detail screen loads"));
            report.getUserJourneys().add(new AppIntelligenceReport.UserJourney("Search & Discovery", steps));
        }

        // Generic exploration journey
        if (exploration != null && exploration.getUniqueScreenCount() > 0) {
            List<String> steps = new ArrayList<>();
            steps.add("Launch app");
            int limit = Math.min(exploration.getTransitions().size(), 5);
            for (int i = 0; i < limit; i++) steps.add("Navigate: " + exploration.getTransitions().get(i));
            steps.add("Return to home screen");
            report.getUserJourneys().add(new AppIntelligenceReport.UserJourney("Full App Exploration", steps));
        }
    }

    // ── Scenario generation ───────────────────────────────────────────────────

    private void generateTestScenarios(AppIntelligenceReport report) {
        for (AppIntelligenceReport.DiscoveredFeature feature : report.getFeatures()) {
            String fname = feature.getName();
            String skw = feature.getScreenKeyword();

            switch (fname) {
                case "User Authentication" -> {
                    addScenario(report, fname, "Login with valid credentials", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open login screen", "Enter valid username/email", "Enter valid password", "Tap Login"),
                            "User logs in successfully and is redirected to home screen", skw);
                    addScenario(report, fname, "Login with empty username", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open login screen", "Leave username field empty", "Enter any password", "Tap Login"),
                            "App shows validation error — username is required", skw);
                    addScenario(report, fname, "Login with empty password", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open login screen", "Enter valid username", "Leave password field empty", "Tap Login"),
                            "App shows validation error — password is required", skw);
                    addScenario(report, fname, "Login with wrong credentials", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open login screen", "Enter wrong credentials", "Tap Login"),
                            "App shows 'Invalid credentials' or similar error message", skw);
                    addScenario(report, fname, "Login with max-length input", AppIntelligenceReport.TestType.BOUNDARY,
                            List.of("Open login screen", "Enter 255-character username string", "Enter 255-character password", "Tap Login"),
                            "App handles max-length input gracefully without crashing", skw);
                    addScenario(report, fname, "Back button on login screen", AppIntelligenceReport.TestType.EDGE_CASE,
                            List.of("Open login screen", "Press hardware Back button"),
                            "App navigates back gracefully without crashing", skw);
                }
                case "User Registration" -> {
                    addScenario(report, fname, "Register with valid details", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open registration screen", "Fill all fields with valid data", "Submit form"),
                            "Account created successfully; user redirected or confirmation shown", skw);
                    addScenario(report, fname, "Register with invalid email format", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open registration screen", "Enter 'notanemail' in email field", "Submit form"),
                            "App shows email validation error", skw);
                    addScenario(report, fname, "Register with empty required fields", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open registration screen", "Leave required fields blank", "Submit form"),
                            "App highlights all required fields with validation errors", skw);
                    addScenario(report, fname, "Register with special characters in name", AppIntelligenceReport.TestType.BOUNDARY,
                            List.of("Open registration screen", "Enter '<script>alert(1)</script>' in name field", "Submit form"),
                            "App sanitizes or rejects dangerous input without crashing", skw);
                }
                case "Search" -> {
                    addScenario(report, fname, "Search with valid query", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open search", "Type a known valid term", "Submit search"),
                            "Relevant results displayed", skw);
                    addScenario(report, fname, "Search with empty query", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open search", "Submit without typing anything"),
                            "App shows hint, prompt, or empty-state message without crashing", skw);
                    addScenario(report, fname, "Search with special characters", AppIntelligenceReport.TestType.BOUNDARY,
                            List.of("Open search", "Type '!@#$%^&*()' and submit"),
                            "App handles special characters gracefully, shows results or empty state", skw);
                    addScenario(report, fname, "Search with very long query", AppIntelligenceReport.TestType.BOUNDARY,
                            List.of("Open search", "Type 300-character string and submit"),
                            "App handles long input without crashing or UI overflow", skw);
                }
                case "Shopping Cart & Checkout" -> {
                    addScenario(report, fname, "Add item to cart", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Browse to a product", "Tap 'Add to Cart'", "Open cart"),
                            "Item appears in cart with correct details and quantity", skw);
                    addScenario(report, fname, "View empty cart", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open cart when no items added"),
                            "App shows 'Cart is empty' or equivalent message", skw);
                    addScenario(report, fname, "Checkout without login", AppIntelligenceReport.TestType.EDGE_CASE,
                            List.of("Add items to cart", "Proceed to checkout without logging in"),
                            "App redirects to login or shows guest checkout option", skw);
                }
                case "User Profile" -> {
                    addScenario(report, fname, "View user profile", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Navigate to Profile screen"),
                            "Profile screen loads with user info displayed correctly", skw);
                    addScenario(report, fname, "Edit profile with valid data", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open profile edit", "Modify a text field", "Save changes"),
                            "Changes saved and reflected in profile view", skw);
                    addScenario(report, fname, "Save profile with empty required field", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Open profile edit", "Clear a required field", "Tap Save"),
                            "App shows validation error for empty required field", skw);
                }
                case "Settings & Preferences" -> {
                    addScenario(report, fname, "Navigate to settings", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open settings screen", "Verify all settings options visible"),
                            "Settings screen loads and all options are accessible", skw);
                    addScenario(report, fname, "Toggle a setting on and off", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open settings", "Toggle any switch", "Toggle it back"),
                            "Setting toggles correctly and state persists", skw);
                }
                case "Password Reset" -> {
                    addScenario(report, fname, "Request password reset", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Navigate to Forgot Password", "Enter valid registered email", "Submit"),
                            "App confirms reset email sent or navigates to next step", skw);
                    addScenario(report, fname, "Reset with unregistered email", AppIntelligenceReport.TestType.NEGATIVE,
                            List.of("Navigate to Forgot Password", "Enter non-existent email", "Submit"),
                            "App shows appropriate error or generic 'check your email' response", skw);
                }
                case "Onboarding / Welcome" -> {
                    addScenario(report, fname, "Complete onboarding flow", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Launch app fresh", "Swipe/tap through all onboarding screens", "Reach home screen"),
                            "All onboarding screens display correctly; user reaches home", skw);
                    addScenario(report, fname, "Skip onboarding", AppIntelligenceReport.TestType.EDGE_CASE,
                            List.of("Launch app", "Tap 'Skip' if available"),
                            "App skips to home or main screen without crashing", skw);
                }
                case "Notifications" -> {
                    addScenario(report, fname, "View notifications list", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Navigate to notifications screen"),
                            "Notification list loads; shows items or empty state", skw);
                    addScenario(report, fname, "Mark notification as read", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Open notifications", "Tap on a notification"),
                            "Notification marked as read and detail opens", skw);
                }
                default -> {
                    // Generic positive test for any other discovered feature
                    addScenario(report, fname, "Verify " + fname + " screen loads", AppIntelligenceReport.TestType.POSITIVE,
                            List.of("Navigate to " + fname + " screen", "Verify UI elements are present and visible"),
                            fname + " screen renders correctly with expected content", skw);
                    addScenario(report, fname, "Verify back navigation from " + fname, AppIntelligenceReport.TestType.EDGE_CASE,
                            List.of("Navigate to " + fname + " screen", "Press Back button"),
                            "App navigates back without crashing", skw);
                }
            }
        }
    }

    private void addScenario(AppIntelligenceReport report, String featureName, String scenarioName,
                             AppIntelligenceReport.TestType type, List<String> steps,
                             String expectedResult, String screenKeyword) {
        report.getTestScenarios().add(new AppIntelligenceReport.TestScenario(
                featureName, scenarioName, type, steps, expectedResult, screenKeyword));
    }

    // ── Analysis notes ────────────────────────────────────────────────────────

    private void buildAnalysisNotes(ApkInfo apkInfo, ExplorationResult exploration,
                                     AppIntelligenceReport report, String aiNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(apkInfo.getApplicationLabel()).append(" (").append(apkInfo.getPackageName()).append(")");
        sb.append(" | Domain: ").append(report.getAppDomain());
        sb.append(" | Category: ").append(report.getAppCategory());

        if (exploration != null) {
            sb.append(" | Screens explored: ").append(exploration.getUniqueScreenCount());
            sb.append(" | Interactions: ").append(exploration.getActionsPerformed());
        }

        sb.append(" | Features identified: ").append(report.getFeatures().size());
        sb.append(" | User journeys: ").append(report.getUserJourneys().size());
        sb.append(" | Test scenarios: ").append(report.getTestScenarios().size());

        if (!apkInfo.getDetectedSdks().isEmpty()) {
            sb.append(" | SDKs: ").append(String.join(", ", apkInfo.getDetectedSdks()));
        }
        if (aiNote != null && !aiNote.isBlank()) {
            sb.append(" | ").append(aiNote);
        }

        report.setAnalysisNotes(sb.toString());
        log.info("App intelligence: {}", sb);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String simpleName(String activity) {
        if (activity == null) return "";
        int dot = activity.lastIndexOf('.');
        return dot >= 0 ? activity.substring(dot + 1) : activity;
    }
}
