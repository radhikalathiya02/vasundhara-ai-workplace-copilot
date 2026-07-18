package com.vasundhara.atf.ads;

import com.vasundhara.atf.ads.AdAnalysisResult.*;
import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;
import com.vasundhara.atf.model.ApkInfo;
import net.dongliu.apk.parser.ApkFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Core ad-monetisation analysis engine.
 *
 * <p>Phase 1 (always): static APK analysis — SDK detection, ad-type class scanning,
 * ad unit ID extraction, layout AdView detection and AdMob App ID lookup.
 *
 * <p>Phase 2 (when a device is available): logcat capture after a short live
 * run to observe real ad requests, loads, impressions, errors and revenue events.
 */
@Service
public class AdAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AdAnalysisService.class);

    /* Ad unit ID:  ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY */
    private static final Pattern AD_UNIT_ID = Pattern.compile("ca-app-pub-(\\d{16})/(\\d{10})");
    /* AdMob App ID: ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY */
    private static final Pattern ADMOB_APP_ID = Pattern.compile("ca-app-pub-(\\d{16})~(\\d{10})");
    /* Test publisher prefix used in Google's test ad unit IDs */
    private static final String TEST_PUB = "3940256099942544";

    /** DEX class-path markers → ad type name */
    private static final Map<String, String> AD_TYPE_MARKERS = new LinkedHashMap<>();
    static {
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/AdView",                              "Banner");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/banner/BannerView",                   "Banner");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/admanager/AdManagerAdView",           "Banner");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/interstitial/InterstitialAd",         "Interstitial");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/admanager/AdManagerInterstitialAd",   "Interstitial");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/rewarded/RewardedAd",                 "Rewarded");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAd", "RewardedInterstitial");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/nativead/NativeAd",                   "Native");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/nativead/NativeAdView",               "Native");
        AD_TYPE_MARKERS.put("com/google/android/gms/ads/appopen/AppOpenAd",                   "AppOpen");
    }

    /** Mediation adapters: DEX path fragment → friendly name */
    private static final Map<String, String> MEDIATION_MARKERS = new LinkedHashMap<>();
    static {
        MEDIATION_MARKERS.put("com/facebook/ads",           "Meta Audience Network");
        MEDIATION_MARKERS.put("com/unity3d/ads",            "Unity Ads");
        MEDIATION_MARKERS.put("com/applovin",               "AppLovin / MAX");
        MEDIATION_MARKERS.put("com/ironsource",             "ironSource");
        MEDIATION_MARKERS.put("com/vungle",                 "Vungle / Liftoff");
        MEDIATION_MARKERS.put("com/chartboost",             "Chartboost");
        MEDIATION_MARKERS.put("com/adcolony",               "AdColony / Digital Turbine");
        MEDIATION_MARKERS.put("com/mopub",                  "MoPub");
        MEDIATION_MARKERS.put("com/inmobi",                 "InMobi");
        MEDIATION_MARKERS.put("com/smaato",                 "Smaato");
    }

    /** Logcat line patterns for lifecycle events: {regex, eventType, isError} */
    private static final List<Object[]> LOGCAT_PATTERNS = List.of(
        new Object[]{ Pattern.compile("(?i)onAdLoaded|Ad finished loading|ad load success"),           "LOAD_SUCCESS",  false },
        new Object[]{ Pattern.compile("(?i)onAdFailedToLoad|Failed to load ad|Failed to receive ad"),  "LOAD_FAILURE",  true  },
        new Object[]{ Pattern.compile("(?i)onAdImpression|Ad impression|onAdShowedFullScreenContent|Ad shown"), "IMPRESSION", false },
        new Object[]{ Pattern.compile("(?i)onAdClicked|Ad clicked|adClicked"),                         "CLICK",         false },
        new Object[]{ Pattern.compile("(?i)onUserEarnedReward|onRewardedItem|earned reward"),          "REWARD",        false },
        new Object[]{ Pattern.compile("(?i)onPaidEvent|paid_impression|revenue_event|ad_revenue"),     "REVENUE",       false },
        new Object[]{ Pattern.compile("(?i)ad request|AdRequest|Starting ad request|Loading ad|GAD-MFC"), "REQUEST",    false },
        new Object[]{ Pattern.compile("(?i)\\bAds\\b.*[Ee]rror|AdError|error code|ERROR_CODE"),        "AD_ERROR",      true  }
    );

    private final ApkAnalyzer apkAnalyzer;

    public AdAnalysisService(ApkAnalyzer apkAnalyzer) {
        this.apkAnalyzer = apkAnalyzer;
    }

    /* ---- Phase 1: static analysis ---------------------------------------- */

    public ApkInfo staticAnalyze(File apkFile, AdAnalysisResult result, Consumer<String> log) throws Exception {
        log.accept("Running APK static analysis…");
        ApkInfo info = apkAnalyzer.analyze(apkFile);
        log.accept("Package: " + info.getPackageName() + " v" + info.getVersionName());

        // 1. SDK detection from ApkInfo (already populated by ApkAnalyzer)
        List<String> sdks = info.getDetectedSdks();
        boolean admob = sdks.stream().anyMatch(s -> s.contains("AdMob") || s.contains("GMA"));
        boolean firebase = sdks.stream().anyMatch(s -> s.startsWith("Firebase") || s.contains("Analytics"));
        result.setAdmobSdkDetected(admob);
        result.setFirebaseSdkDetected(firebase);
        log.accept("AdMob SDK " + (admob ? "detected" : "not detected") + "; Firebase " + (firebase ? "detected" : "not detected") + ".");

        // 2. Deep APK ZIP scan: ad types, ad unit IDs, App ID, mediation, layouts
        log.accept("Scanning APK for ad unit IDs, ad types and mediation…");
        deepScanApk(apkFile, result, log);

        return info;
    }

    private void deepScanApk(File apkFile, AdAnalysisResult result, Consumer<String> logger) {
        Set<String> detectedTypes = new LinkedHashSet<>();
        Set<String> mediationSdks = new LinkedHashSet<>();
        Set<String> adUnitIds = new LinkedHashSet<>();
        String admobAppId = null;
        StringBuilder dexContent = new StringBuilder();

        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".dex")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] data = in.readAllBytes();
                        String dexStr = new String(data, StandardCharsets.ISO_8859_1);
                        dexContent.append(dexStr);

                        // Ad type detection
                        for (Map.Entry<String, String> marker : AD_TYPE_MARKERS.entrySet()) {
                            if (dexStr.contains(marker.getKey())) {
                                detectedTypes.add(marker.getValue());
                            }
                        }
                        // Mediation detection
                        for (Map.Entry<String, String> marker : MEDIATION_MARKERS.entrySet()) {
                            if (dexStr.contains(marker.getKey())) {
                                mediationSdks.add(marker.getValue());
                            }
                        }
                        // Ad unit IDs from DEX string pool
                        Matcher m = AD_UNIT_ID.matcher(dexStr);
                        while (m.find()) adUnitIds.add(m.group());

                        // AdMob App ID from DEX string pool
                        Matcher appIdM = ADMOB_APP_ID.matcher(dexStr);
                        if (admobAppId == null && appIdM.find()) admobAppId = appIdM.group();

                    } catch (Exception ignored) {}

                } else if (name.endsWith(".xml") && name.startsWith("res/layout")) {
                    // Layout XML (binary; scan as bytes for class name strings)
                    try (InputStream in = zip.getInputStream(entry)) {
                        String xml = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        if (xml.contains("AdView") || xml.contains("admob")) {
                            detectedTypes.add("Banner"); // layout declares a banner
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("ZIP scan failed: {}", e.toString());
        }

        // Also extract manifest for AdMob App ID via apk-parser decoded XML
        if (admobAppId == null) {
            try (ApkFile apkF = new ApkFile(apkFile)) {
                String manifest = apkF.getManifestXml();
                if (manifest != null) {
                    Matcher m = Pattern.compile("APPLICATION_ID[^>]*value=[\"']([^\"']+)[\"']").matcher(manifest);
                    if (m.find()) admobAppId = m.group(1);
                    if (admobAppId == null) {
                        Matcher m2 = ADMOB_APP_ID.matcher(manifest);
                        if (m2.find()) admobAppId = m2.group();
                    }
                }
            } catch (Exception ignored) {}
        }

        // Build placements list
        List<AdPlacement> placements = new ArrayList<>();
        for (String uid : adUnitIds) {
            String type = inferTypeFromUnitId(uid, detectedTypes);
            boolean isTest = uid.contains(TEST_PUB);
            placements.add(new AdPlacement(uid, type, "APK static scan", isTest));
        }

        // Ensure at least one placement per detected type when no unit IDs found
        if (placements.isEmpty() && !detectedTypes.isEmpty()) {
            for (String type : detectedTypes) {
                placements.add(new AdPlacement("(unit ID not found in APK binary)", type, "APK static scan", false));
            }
        }

        // Build type distribution
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (String type : List.of("Banner", "Interstitial", "Rewarded", "RewardedInterstitial", "Native", "AppOpen")) {
            int count = (int) placements.stream().filter(p -> type.equals(p.adType())).count();
            if (count > 0 || detectedTypes.contains(type)) dist.put(type, Math.max(count, detectedTypes.contains(type) ? 1 : 0));
        }

        result.setPlacements(placements);
        result.setTotalAdsFound(Math.max(placements.size(), detectedTypes.size()));
        result.setTypeDistribution(dist);
        result.setAdmobAppId(admobAppId);
        result.setMediationSdks(new ArrayList<>(mediationSdks));

        logger.accept("Found " + placements.size() + " ad unit ID(s), " + detectedTypes.size() + " ad type(s), " + mediationSdks.size() + " mediation adapter(s).");
    }

    private String inferTypeFromUnitId(String uid, Set<String> detectedTypes) {
        // If only one type detected, assign all to it
        if (detectedTypes.size() == 1) return detectedTypes.iterator().next();
        // Otherwise label as Mixed
        return detectedTypes.isEmpty() ? "Unknown" : detectedTypes.iterator().next();
    }

    /* ---- Phase 2: logcat parsing ----------------------------------------- */

    public void parseLogcat(String rawLogcat, AdAnalysisResult result, Consumer<String> logger) {
        logger.accept("Parsing logcat for ad lifecycle events…");

        List<AdLifecycleEvent> events = new ArrayList<>();
        int requests = 0, loads = 0, failures = 0, impressions = 0, clicks = 0, revenue = 0, errors = 0;

        for (String line : rawLogcat.split("\\R")) {
            for (Object[] pattern : LOGCAT_PATTERNS) {
                Pattern p = (Pattern) pattern[0];
                String eventType = (String) pattern[1];
                boolean isError = (Boolean) pattern[2];

                if (p.matcher(line).find()) {
                    String ts = extractTimestamp(line);
                    String adType = extractAdType(line);
                    events.add(new AdLifecycleEvent(ts, eventType, adType, trimLine(line), isError));
                    switch (eventType) {
                        case "REQUEST"      -> requests++;
                        case "LOAD_SUCCESS" -> loads++;
                        case "LOAD_FAILURE" -> failures++;
                        case "IMPRESSION"   -> impressions++;
                        case "CLICK"        -> clicks++;
                        case "REVENUE"      -> revenue++;
                        case "AD_ERROR"     -> errors++;
                    }
                    break; // one event per line
                }
            }
        }

        result.setRequestCount(requests);
        result.setLoadSuccessCount(loads);
        result.setLoadFailureCount(failures);
        result.setImpressionCount(impressions);
        result.setClickCount(clicks);
        result.setRevenueEventCount(revenue);
        result.setErrorCount(errors);
        result.setLifecycleEvents(events.size() > 500 ? events.subList(0, 500) : events);
        result.setDeviceTestRun(true);

        logger.accept("Logcat: " + requests + " requests, " + loads + " loads, " + impressions + " impressions, " + failures + " failures, " + errors + " errors.");
    }

    /* ---- Phase 2b: per-placement correlation ----------------------------- */

    private static final Pattern ERR_CODE = Pattern.compile(
            "(?i)(ERROR_CODE_[A-Z_]+|error\\s*code[:= ]+\\w+|\\bcode[:= ]+\\d+|domain=[^,]+,\\s*code=\\d+)");

    /**
     * Correlate the captured logcat lifecycle to EACH detected placement, so every ad placement
     * gets its own observed result (loaded / failed / displayed / impression / clicked / error +
     * the screen it was seen on). Unit-ID-level correlation is used when the ad unit ID appears in
     * the callback; otherwise it falls back to ad-type-level correlation (noted on the row), since
     * AdMob callbacks do not always carry the unit ID.
     */
    public void correlatePlacements(AdAnalysisResult r, String logcat, ExplorationResult exploration,
                                    Consumer<String> logger) {
        if (!r.isDeviceTestRun()) return;
        logger.accept("Correlating ad lifecycle to each of the " + r.getPlacements().size() + " placement(s)…");

        Map<String, Set<String>> unitEvents = new LinkedHashMap<>();
        Map<String, Set<String>> typeEvents = new LinkedHashMap<>();
        Map<String, String> unitErr = new LinkedHashMap<>();
        Map<String, String> typeErr = new LinkedHashMap<>();
        Map<String, String> unitType = new LinkedHashMap<>();
        boolean fullScreenShown = false;

        for (String line : logcat.split("\\R")) {
            String evt = classify(line);
            if (evt == null) continue;
            String type = extractAdType(line);
            Matcher um = AD_UNIT_ID.matcher(line);
            String unit = um.find() ? um.group() : null;
            if (unit != null) unitEvents.computeIfAbsent(unit, k -> new LinkedHashSet<>()).add(evt);
            if (unit != null && !type.isEmpty()) unitType.putIfAbsent(unit, type);
            if (!type.isEmpty()) typeEvents.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(evt);
            String low = line.toLowerCase();
            if (low.contains("onadshowedfullscreencontent") || low.contains("showed full screen")) fullScreenShown = true;
            if (evt.equals("LOAD_FAILURE") || evt.equals("AD_ERROR")) {
                Matcher em = ERR_CODE.matcher(line);
                if (em.find()) {
                    String code = em.group().trim();
                    if (unit != null) unitErr.putIfAbsent(unit, code);
                    if (!type.isEmpty()) typeErr.putIfAbsent(type, code);
                }
            }
        }

        // Discover placements that actually fired at runtime but were NOT in the static APK scan
        // (e.g. ad unit IDs delivered via server / Firebase Remote Config). This is what makes the
        // report reflect EVERY reachable placement, not only those hard-coded in the binary.
        Set<String> staticIds = new LinkedHashSet<>();
        for (AdPlacement p : r.getPlacements()) if (p.adUnitId() != null) staticIds.add(p.adUnitId());
        int discovered = 0;
        for (String uid : unitEvents.keySet()) {
            if (uid == null || !uid.startsWith("ca-app-pub") || staticIds.contains(uid)) continue;
            r.getPlacements().add(new AdPlacement(uid, unitType.getOrDefault(uid, "Unknown"),
                    "Observed at runtime", uid.contains(TEST_PUB)));
            discovered++;
        }
        if (discovered > 0) {
            r.setTotalAdsFound(r.getPlacements().size());
            rebuildTypeDistribution(r);
            logger.accept("Discovered " + discovered + " additional placement(s) at runtime (not in the APK binary — likely server / Remote Config driven).");
        }

        // Screens where a (visible) ad view was rendered during the crawl.
        List<String> adScreens = new ArrayList<>();
        if (exploration != null) {
            for (ScreenCapture s : exploration.getScreens()) {
                if (s.widgets().stream().anyMatch(this::looksLikeAdView)) {
                    String a = activityName(s.activity());
                    if (!adScreens.contains(a)) adScreens.add(a);
                }
            }
        }

        List<AdPlacementResult> out = new ArrayList<>();
        int visibleIdx = 0;
        for (AdPlacement p : r.getPlacements()) {
            String uid = p.adUnitId();
            boolean realId = uid != null && uid.startsWith("ca-app-pub");
            Set<String> unitEv = realId ? unitEvents.get(uid) : null;
            boolean unitLevel = unitEv != null && !unitEv.isEmpty();
            Set<String> ev = unitLevel ? unitEv : typeEvents.getOrDefault(p.adType(), Set.of());

            boolean loaded = ev.contains("LOAD_SUCCESS");
            boolean failed = ev.contains("LOAD_FAILURE") || ev.contains("AD_ERROR");
            boolean impression = ev.contains("IMPRESSION") || ev.contains("REVENUE");
            boolean clicked = ev.contains("CLICK");
            String errCode = unitLevel ? unitErr.get(uid) : typeErr.get(p.adType());

            boolean visible = "Banner".equals(p.adType()) || "Native".equals(p.adType());
            String screen;
            boolean displayed;
            if (visible) {
                screen = adScreens.isEmpty() ? "—"
                        : adScreens.get(Math.min(visibleIdx++, adScreens.size() - 1));
                displayed = impression || !adScreens.isEmpty();
            } else {
                displayed = impression || fullScreenShown;
                screen = displayed ? "Full-screen overlay" : "—";
            }

            String status;
            if (failed && !loaded) status = "Failed to load" + (errCode != null ? " (" + errCode + ")" : "");
            else if (impression) status = "Impression recorded";
            else if (displayed) status = "Displayed";
            else if (loaded) status = "Loaded (no impression)";
            else status = "Not triggered";

            StringBuilder notes = new StringBuilder();
            if (!unitLevel && !ev.isEmpty()) notes.append("Type-level correlation (unit ID not present in the callback). ");
            if ("Not triggered".equals(status)) notes.append("Screen not reached during the crawl, or no fill/network. ");
            if (p.testId()) notes.append("Google test ad unit. ");

            out.add(new AdPlacementResult(uid, p.adType(), screen, status, loaded, failed, displayed,
                    impression, clicked, errCode == null ? "" : errCode, notes.toString().trim()));
        }
        r.setPlacementResults(out);
        logger.accept("Per-placement: " + r.getPlacementsLoaded() + " loaded, " + r.getPlacementsFailed()
                + " failed, " + r.getPlacementsImpression() + " impression(s), " + r.getPlacementsClicked()
                + " click(s) across " + out.size() + " placement(s).");
    }

    private String classify(String line) {
        for (Object[] pat : LOGCAT_PATTERNS) {
            if (((Pattern) pat[0]).matcher(line).find()) return (String) pat[1];
        }
        return null;
    }

    private void rebuildTypeDistribution(AdAnalysisResult r) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (String t : List.of("Banner", "Interstitial", "Rewarded", "RewardedInterstitial", "Native", "AppOpen", "Unknown")) {
            int c = (int) r.getPlacements().stream().filter(p -> t.equals(p.adType())).count();
            if (c > 0) dist.put(t, c);
        }
        r.setTypeDistribution(dist);
    }

    private boolean looksLikeAdView(Widget w) {
        String c = w.simpleClass() == null ? "" : w.simpleClass().toLowerCase();
        String rid = w.resourceId() == null ? "" : w.resourceId().toLowerCase();
        return c.contains("adview") || c.contains("nativeadview") || c.contains("mediaview") || c.contains("admanagerad")
                || rid.contains("ad_view") || rid.contains("adview") || rid.endsWith("/ad")
                || rid.contains("banner") || rid.contains("admob") || rid.contains("native_ad");
    }

    private String activityName(String activity) {
        if (activity == null || activity.isBlank()) return "—";
        int dot = activity.lastIndexOf('.');
        return dot >= 0 ? activity.substring(dot + 1) : activity;
    }

    /* ---- Phase 3: issue detection + category report ----------------------- */

    public void generateReport(AdAnalysisResult r, Consumer<String> logger) {
        logger.accept("Generating ad health report…");

        List<AdIssue> issues = new ArrayList<>();
        List<AdAnalysisResult.AdCategoryReport> cats = new ArrayList<>();

        // ─── Category 1: SDK Integration ───────────────────────────────────
        if (!r.isAdmobSdkDetected()) {
            issues.add(new AdIssue("CRITICAL", "SDK Integration",
                    "AdMob SDK not detected",
                    "No Google Mobile Ads (com.google.android.gms.ads) classes found in the APK. "
                    + "AdMob SDK may not be integrated or may be obfuscated beyond detection."));
            cats.add(new AdAnalysisResult.AdCategoryReport("SDK Integration", "FAIL", 0, 1,
                    "AdMob SDK not found in APK binary."));
        } else {
            String detail = "AdMob SDK present." +
                    (r.getAdmobAppId() != null ? " App ID: " + r.getAdmobAppId() + "." : " App ID not found in APK binary.") +
                    (!r.getMediationSdks().isEmpty() ? " Mediation: " + String.join(", ", r.getMediationSdks()) + "." : "");
            if (r.getAdmobAppId() == null) {
                issues.add(new AdIssue("HIGH", "SDK Integration",
                        "AdMob App ID not found",
                        "The AdMob App ID (ca-app-pub-xxx~yyy) was not found in the APK binary. "
                        + "Ensure it is declared in AndroidManifest.xml as meta-data with key "
                        + "'com.google.android.gms.ads.APPLICATION_ID'."));
            }
            cats.add(new AdAnalysisResult.AdCategoryReport("SDK Integration", "PASS",
                    r.getTypeDistribution().size(), r.getTypeDistribution().size(), detail));
        }

        // ─── Category 2: Ad Discovery ──────────────────────────────────────
        if (r.getTotalAdsFound() == 0) {
            issues.add(new AdIssue("HIGH", "Ad Discovery",
                    "No ad placements found",
                    "No AdMob ad types or ad unit IDs were detected in the APK."));
            cats.add(new AdAnalysisResult.AdCategoryReport("Ad Discovery", "FAIL", 0, 6,
                    "No Banner, Interstitial, Rewarded, Rewarded Interstitial, Native or App Open ad types found."));
        } else {
            cats.add(new AdAnalysisResult.AdCategoryReport("Ad Discovery", "PASS",
                    r.getTotalAdsFound(), r.getTypeDistribution().size(),
                    r.getTypeDistribution().entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("")));
        }

        // ─── Category 3: Ad Unit IDs (interpreted against the selected Ad Mode) ──
        boolean testMode = "TEST".equalsIgnoreCase(r.getAdMode());
        long testIds = r.getPlacements().stream().filter(AdPlacement::testId).count();
        long realIds = r.getPlacements().stream().filter(p -> !p.testId()).count();
        long totalIds = testIds + realIds;
        if (testMode) {
            // TEST mode: the app SHOULD use Google test ad units; production IDs won't serve test ads.
            if (realIds > 0 && testIds == 0) {
                issues.add(new AdIssue("HIGH", "Ad Unit IDs (TEST mode)",
                        "TEST mode but app uses production ad unit IDs",
                        realIds + " production ad unit ID(s) found and no Google test IDs. In TEST mode, test ads "
                        + "(ca-app-pub-3940256099942544/...) are expected so ads reliably fill during QA. Switch the app "
                        + "to test ad units, or run the analysis in LIVE mode to validate the production IDs."));
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Unit IDs", "WARNING", (int) testIds, (int) totalIds,
                        "TEST mode: " + realIds + " production ID(s), 0 test ID(s). Test ads expected for QA fill."));
            } else {
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Unit IDs", "PASS", (int) testIds, (int) totalIds,
                        "TEST mode: " + testIds + " Google test ad unit(s)"
                        + (realIds > 0 ? " + " + realIds + " production ID(s)" : "") + " — test ads should fill reliably."));
            }
        } else {
            // LIVE mode: production IDs are expected; test IDs would mean real ads do not serve.
            if (testIds > 0 && realIds == 0) {
                issues.add(new AdIssue("HIGH", "Ad Unit IDs (LIVE mode)",
                        "LIVE mode but app uses Google test ad unit IDs",
                        testIds + " Google test ad unit ID(s) detected and no production IDs. Real ads will NOT serve. "
                        + "Replace test IDs with production ad unit IDs before release."));
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Unit IDs", "WARNING", (int) realIds, (int) totalIds,
                        "LIVE mode: all " + testIds + " unit ID(s) are Google test IDs — real ads won't serve."));
            } else if (testIds > 0) {
                issues.add(new AdIssue("MEDIUM", "Ad Unit IDs (LIVE mode)",
                        "Mix of test and production ad unit IDs",
                        testIds + " test ID(s) and " + realIds + " production ID(s) found. Remove test IDs before release."));
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Unit IDs", "WARNING", (int) realIds, (int) totalIds,
                        "LIVE mode: " + realIds + " production ID(s) + " + testIds + " test ID(s)."));
            } else {
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Unit IDs", "PASS", (int) realIds, (int) realIds,
                        "LIVE mode: " + realIds + " production ad unit ID(s) detected."));
            }
        }

        // ─── Categories 4-8: lifecycle (only meaningful if device test ran) ─
        if (r.isDeviceTestRun()) {
            // Ad Requests
            cats.add(new AdAnalysisResult.AdCategoryReport("Ad Requests", r.getRequestCount() > 0 ? "PASS" : "WARNING",
                    r.getRequestCount(), r.getRequestCount(),
                    r.getRequestCount() > 0 ? r.getRequestCount() + " request(s) observed in logcat."
                            : "No ad requests detected in logcat. Check that ads are being requested on app launch."));
            if (r.getRequestCount() == 0 && r.isAdmobSdkDetected()) {
                issues.add(new AdIssue("HIGH", "Ad Requests",
                        "No ad requests observed at runtime",
                        "AdMob SDK is integrated but no ad requests were detected in logcat during the test run. "
                        + "Ads may be gated behind user interactions or unreachable flows."));
            }

            // Ad Loading
            String loadStatus = r.getLoadFailureCount() > 0 && r.getLoadSuccessCount() == 0 ? "FAIL"
                    : r.getLoadFailureCount() > 0 ? "WARNING" : (r.getLoadSuccessCount() > 0 ? "PASS" : "WARNING");
            cats.add(new AdAnalysisResult.AdCategoryReport("Ad Loading",  loadStatus,
                    r.getLoadSuccessCount(), r.getLoadSuccessCount() + r.getLoadFailureCount(),
                    r.getLoadSuccessCount() + " success / " + r.getLoadFailureCount() + " failure(s)."));
            if (r.getLoadFailureCount() > 0) {
                issues.add(new AdIssue(r.getLoadSuccessCount() == 0 ? "HIGH" : "MEDIUM", "Ad Loading",
                        r.getLoadFailureCount() + " ad load failure(s) detected",
                        "Ad load failures observed in logcat. Check network connectivity, ad unit IDs, and fill rate. Error codes may appear in logcat under tag 'Ads'."));
            }

            // Impressions
            cats.add(new AdAnalysisResult.AdCategoryReport("Impressions", r.getImpressionCount() > 0 ? "PASS" : "WARNING",
                    r.getImpressionCount(), r.getImpressionCount(),
                    r.getImpressionCount() > 0 ? r.getImpressionCount() + " impression(s) recorded."
                            : "No impression events detected — ads may not be rendering or visible."));
            if (r.getLoadSuccessCount() > 0 && r.getImpressionCount() == 0) {
                issues.add(new AdIssue("MEDIUM", "Impressions",
                        "Ad loaded but no impressions recorded",
                        "Ads loaded successfully but no impression events were captured. "
                        + "Check visibility constraints (ad may be loaded but off-screen), lifecycle timing, or AdListener implementation."));
            }

            // Clicks — in LIVE mode ad elements are never tapped to avoid AdMob policy violations
            if (r.isAdClicksSkipped()) {
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Clicks", "INFO", 0, 0,
                        "Ad clicks skipped in LIVE ADS mode to prevent AdMob policy violation. "
                        + "Use TEST ADS mode for click testing."));
            } else {
                cats.add(new AdAnalysisResult.AdCategoryReport("Clicks", "INFO",
                        r.getClickCount(), r.getClickCount(),
                        r.getClickCount() > 0 ? r.getClickCount() + " click(s) observed (simulated/real)."
                                : "No clicks observed (expected for automated testing)."));
            }

            // Revenue Events
            cats.add(new AdAnalysisResult.AdCategoryReport("Revenue Events", r.getRevenueEventCount() > 0 ? "PASS" : "WARNING",
                    r.getRevenueEventCount(), r.getRevenueEventCount(),
                    r.getRevenueEventCount() > 0 ? r.getRevenueEventCount() + " paid impression / revenue event(s)."
                            : "No paid impression events detected. Verify onPaidEvent callback is implemented."));
            if (r.getImpressionCount() > 0 && r.getRevenueEventCount() == 0) {
                issues.add(new AdIssue("MEDIUM", "Revenue Events",
                        "Impressions recorded but no revenue events",
                        "Ad impressions were observed but no onPaidEvent callbacks were captured. "
                        + "Ensure AdMob's paid event listener is registered to track revenue."));
            }

            // Errors
            if (r.getErrorCount() > 0) {
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Errors", "FAIL",
                        r.getErrorCount(), r.getErrorCount(),
                        r.getErrorCount() + " error(s) in logcat. Check error codes and failure reasons."));
                issues.add(new AdIssue("HIGH", "Ad Errors",
                        r.getErrorCount() + " ad error(s) detected in logcat",
                        "Runtime ad errors observed. Review logcat under 'Ads' and 'GAD' tags for error codes and resolution steps."));
            } else {
                cats.add(new AdAnalysisResult.AdCategoryReport("Ad Errors", "PASS", 0, 0,
                        "No ad error events in logcat."));
            }

        } else {
            cats.add(new AdAnalysisResult.AdCategoryReport("Ad Lifecycle (Runtime)",
                    "INFO", 0, 0, "No device available — runtime lifecycle analysis skipped."));
        }

        // ─── Per-Placement Coverage ───────────────────────────────────────
        if (r.isDeviceTestRun() && !r.getPlacementResults().isEmpty()) {
            int total = r.getPlacementResults().size();
            long triggered = r.getPlacementResults().stream().filter(p -> !"Not triggered".equals(p.status())).count();
            long loaded = r.getPlacementsLoaded(), failed = r.getPlacementsFailed(), impr = r.getPlacementsImpression();
            String st = failed > 0 && loaded == 0 ? "FAIL" : (triggered < total ? "WARNING" : "PASS");
            cats.add(new AdAnalysisResult.AdCategoryReport("Placement Coverage", st, (int) triggered, total,
                    triggered + "/" + total + " placement(s) exercised — " + loaded + " loaded, " + failed
                            + " failed, " + impr + " with impressions."));
            long notTriggered = total - triggered;
            if (notTriggered > 0) {
                issues.add(new AdIssue("MEDIUM", "Placement Coverage",
                        notTriggered + " of " + total + " placement(s) not triggered at runtime",
                        "These placements were not exercised during the automated crawl — their screen may not have "
                        + "been reached, or the ad did not fill. Ensure the placement's screen is reachable and re-run."));
            }
        }

        // ─── Category: Firebase Analytics ─────────────────────────────────
        cats.add(new AdAnalysisResult.AdCategoryReport("Firebase Analytics", r.isFirebaseSdkDetected() ? "PASS" : "INFO",
                r.isFirebaseSdkDetected() ? 1 : 0, 1,
                r.isFirebaseSdkDetected() ? "Firebase SDK detected. ad_impression and purchase events can be tracked." :
                        "Firebase SDK not detected. Consider integrating Firebase Analytics for ad revenue tracking."));

        // ─── Category: Mediation ───────────────────────────────────────────
        cats.add(new AdAnalysisResult.AdCategoryReport("Mediation", r.getMediationSdks().isEmpty() ? "INFO" : "PASS",
                r.getMediationSdks().size(), r.getMediationSdks().size(),
                r.getMediationSdks().isEmpty() ? "No mediation adapters detected (AdMob only)."
                        : "Mediation adapters: " + String.join(", ", r.getMediationSdks())));

        // ─── Overall health ────────────────────────────────────────────────
        long critical = issues.stream().filter(i -> "CRITICAL".equals(i.severity())).count();
        long high = issues.stream().filter(i -> "HIGH".equals(i.severity())).count();
        long medium = issues.stream().filter(i -> "MEDIUM".equals(i.severity())).count();

        String health, summary;
        if (critical > 0 || (high >= 2)) {
            health = "FAIL";
            summary = critical + " critical + " + high + " high-severity issues require attention before release.";
        } else if (high > 0 || medium > 0) {
            health = "WARNING";
            summary = high + " high + " + medium + " medium issues detected — review before production.";
        } else if (!r.isAdmobSdkDetected()) {
            health = "FAIL";
            summary = "AdMob SDK not integrated.";
        } else {
            health = "PASS";
            summary = "Ad integration looks healthy. " + r.getTotalAdsFound() + " placement(s), " + r.getTypeDistribution().size() + " type(s).";
        }

        r.setIssues(issues);
        r.setCategoryReport(cats);
        r.setOverallHealth(health);
        r.setHealthSummary(summary);
        logger.accept("Health: " + health + " — " + summary);
    }

    /* ---- Phase 2 (LIVE): logcat parsing — load/impression/revenue only ------- */

    /**
     * LIVE ADS mode logcat parser.
     *
     * <p>Identical to {@link #parseLogcat} except CLICK events are excluded: the framework
     * never taps ad elements in LIVE mode, so any CLICK line in logcat is a stale artifact
     * (leftover from a previous manual session), not a framework-driven interaction.
     * Counting such lines would give a false picture of click performance.
     */
    public void parseLogcatLiveMode(String rawLogcat, AdAnalysisResult result, Consumer<String> logger) {
        logger.accept("LIVE ADS — parsing logcat for load, display, and impression events (click testing skipped)…");

        List<AdLifecycleEvent> events = new ArrayList<>();
        int requests = 0, loads = 0, failures = 0, impressions = 0, revenue = 0, errors = 0;

        for (String line : rawLogcat.split("\\R")) {
            for (Object[] pattern : LOGCAT_PATTERNS) {
                Pattern p = (Pattern) pattern[0];
                String eventType = (String) pattern[1];
                boolean isError = (Boolean) pattern[2];

                if ("CLICK".equals(eventType)) continue; // never count in LIVE mode

                if (p.matcher(line).find()) {
                    String ts = extractTimestamp(line);
                    String adType = extractAdType(line);
                    events.add(new AdLifecycleEvent(ts, eventType, adType, trimLine(line), isError));
                    switch (eventType) {
                        case "REQUEST"      -> requests++;
                        case "LOAD_SUCCESS" -> loads++;
                        case "LOAD_FAILURE" -> failures++;
                        case "IMPRESSION"   -> impressions++;
                        case "REVENUE"      -> revenue++;
                        case "AD_ERROR"     -> errors++;
                    }
                    break;
                }
            }
        }

        result.setRequestCount(requests);
        result.setLoadSuccessCount(loads);
        result.setLoadFailureCount(failures);
        result.setImpressionCount(impressions);
        result.setClickCount(0); // explicitly zero — no clicks were performed
        result.setRevenueEventCount(revenue);
        result.setErrorCount(errors);
        result.setLifecycleEvents(events.size() > 500 ? events.subList(0, 500) : events);
        result.setDeviceTestRun(true);

        logger.accept("LIVE ADS: " + requests + " request(s), " + loads + " load(s), "
                + impressions + " impression(s), " + failures + " failure(s), " + errors
                + " error(s). Click testing: skipped (LIVE mode).");
    }

    /* ---- helpers ---------------------------------------------------------- */

    private String extractTimestamp(String line) {
        if (line.length() > 18 && Character.isDigit(line.charAt(0))) return line.substring(0, 18).trim();
        return "";
    }

    private String extractAdType(String line) {
        String l = line.toLowerCase();
        if (l.contains("banner") || l.contains("adview")) return "Banner";
        if (l.contains("interstitial")) return "Interstitial";
        if (l.contains("rewarded interstitial") || l.contains("rewardedinterstitial")) return "RewardedInterstitial";
        if (l.contains("rewarded")) return "Rewarded";
        if (l.contains("native")) return "Native";
        if (l.contains("appopen") || l.contains("app_open") || l.contains("app open")) return "AppOpen";
        return "";
    }

    private String trimLine(String line) {
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
