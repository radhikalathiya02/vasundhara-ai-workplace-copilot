package com.vasundhara.atf.apk;

import com.vasundhara.atf.model.ApkInfo;
import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure static analysis of an APK: manifest attributes, declared components and
 * their export state, permissions, ABIs/locales, signature schemes and detected
 * third-party SDKs. Uses {@code net.dongliu:apk-parser} plus direct manifest XML
 * and zip inspection — no device required.
 */
@Component
public class ApkAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ApkAnalyzer.class);
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** A subset of Android "dangerous" permission groups worth flagging. */
    private static final Set<String> DANGEROUS_PERMISSIONS = Set.of(
            "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
            "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
            "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
            "android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS");

    /** Marker package paths searched inside DEX bytes -> friendly SDK name. */
    private static final Map<String, String> SDK_MARKERS = new LinkedHashMap<>();
    static {
        SDK_MARKERS.put("com/google/android/gms/ads", "Google AdMob / GMA Ads");
        SDK_MARKERS.put("com/google/firebase", "Firebase");
        SDK_MARKERS.put("com/google/android/gms/measurement", "Firebase / GA Analytics");
        SDK_MARKERS.put("com/google/android/gms/common", "Google Play Services");
        SDK_MARKERS.put("com/facebook/ads", "Meta Audience Network");
        SDK_MARKERS.put("com/facebook", "Facebook SDK");
        SDK_MARKERS.put("com/unity3d/ads", "Unity Ads");
        SDK_MARKERS.put("com/unity3d/services", "Unity Services");
        SDK_MARKERS.put("com/applovin", "AppLovin / MAX");
        SDK_MARKERS.put("com/ironsource", "ironSource");
        SDK_MARKERS.put("com/mopub", "MoPub");
        SDK_MARKERS.put("com/vungle", "Vungle");
        SDK_MARKERS.put("com/chartboost", "Chartboost");
        SDK_MARKERS.put("com/adcolony", "AdColony");
        SDK_MARKERS.put("io/branch", "Branch");
        SDK_MARKERS.put("com/appsflyer", "AppsFlyer");
        SDK_MARKERS.put("com/onesignal", "OneSignal");
        SDK_MARKERS.put("com/crashlytics", "Crashlytics");
        SDK_MARKERS.put("io/sentry", "Sentry");
        SDK_MARKERS.put("retrofit2", "Retrofit (HTTP)");
        SDK_MARKERS.put("okhttp3", "OkHttp (HTTP)");
        SDK_MARKERS.put("com/squareup/picasso", "Picasso");
        SDK_MARKERS.put("com/bumptech/glide", "Glide");
    }

    private static final Pattern LOCALE_DIR =
            Pattern.compile("res/values-([a-z]{2})(?:-r[A-Z]{2})?/");

    /** assets/.../{locale|i18n|lang|translations}/<lang>.{json|arb|xml|properties} — capture the language code. */
    private static final Pattern ASSET_I18N_DIR = Pattern.compile(
            "assets/(?:.*/)?(?:locales?|i18n|lang|languages|translations?)/([a-z]{2})(?:[-_][a-z]{2})?\\.(?:json|arb|xml|properties)");

    public ApkInfo analyze(File apk) throws Exception {
        ApkInfo info = new ApkInfo();
        info.setApkSizeBytes(apk.length());

        String manifestXml = null;
        try (ApkFile apkFile = new ApkFile(apk)) {
            ApkMeta meta = apkFile.getApkMeta();
            info.setPackageName(meta.getPackageName());
            info.setApplicationLabel(meta.getLabel());
            info.setVersionName(meta.getVersionName());
            if (meta.getVersionCode() != null) info.setVersionCode(meta.getVersionCode());
            info.setMinSdkVersion(parseInt(meta.getMinSdkVersion()));
            info.setTargetSdkVersion(parseInt(meta.getTargetSdkVersion()));
            info.setMaxSdkVersion(parseInt(meta.getMaxSdkVersion()));

            for (String p : new TreeSet<>(meta.getUsesPermissions())) {
                info.getPermissions().add(p);
                if (DANGEROUS_PERMISSIONS.contains(p)) {
                    info.getDangerousPermissions().add(p);
                }
            }

            detectSignatureSchemes(apkFile, info);
            extractLocales(apkFile, info);
            extractIcon(apkFile, info);
            manifestXml = apkFile.getManifestXml();
        } catch (Exception e) {
            log.warn("apk-parser failed on {}: {}", apk.getName(), e.toString());
        }

        if (manifestXml != null) {
            parseManifest(manifestXml, info);
        }
        scanZip(apk, info);
        return info;
    }

    /**
     * Read the locales the APK actually ships from {@code resources.arsc} (the locale
     * configurations of the resource table). This is the correct source: a compiled APK
     * has no {@code res/values-xx/} directories — those are merged into resources.arsc.
     * Filters the root/any locale and Android pseudo-locales (en-XC, ar-XB).
     */
    private void extractLocales(ApkFile apkFile, ApkInfo info) {
        try {
            java.util.Set<java.util.Locale> locales = apkFile.getLocales();
            if (locales == null) return;
            Set<String> tags = new TreeSet<>();
            for (java.util.Locale l : locales) {
                if (l == null) continue;
                String lang = l.getLanguage();
                if (lang == null || lang.isBlank()) continue;        // root/any locale
                String tag = l.toLanguageTag();
                if (tag == null || tag.isBlank() || "und".equals(tag)) continue;
                if (tag.equalsIgnoreCase("en-XC") || tag.equalsIgnoreCase("ar-XB")) continue; // pseudo-locales
                tags.add(tag);
            }
            info.getSupportedLocales().addAll(tags);
        } catch (Exception e) {
            log.warn("Locale extraction from resources.arsc failed: {}", e.toString());
        }
    }

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * Extract the highest-resolution launcher icon, base64-encoded for direct use in an
     * &lt;img&gt; src. Only raster formats (PNG/WebP) can be used as-is; apps whose launcher
     * icon is a fully vector/adaptive-icon drawable (compiled Android Binary XML, common after
     * R8 resource shrinking) have no extractable bitmap — {@code iconBase64} is left null and
     * the UI falls back to a letter avatar.
     */
    private void extractIcon(ApkFile apkFile, ApkInfo info) {
        try {
            java.util.List<net.dongliu.apk.parser.bean.IconFace> icons = apkFile.getAllIcons();
            byte[] best = null;
            String bestMime = null;
            int bestDensity = -1;
            for (net.dongliu.apk.parser.bean.IconFace face : icons) {
                try {
                    net.dongliu.apk.parser.bean.Icon plain;
                    if (face instanceof net.dongliu.apk.parser.bean.AdaptiveIcon) {
                        // Adaptive icons (API 26+) are layered; the foreground (or background as
                        // a fallback) may itself be null if the drawable couldn't be resolved.
                        net.dongliu.apk.parser.bean.AdaptiveIcon adaptive = (net.dongliu.apk.parser.bean.AdaptiveIcon) face;
                        plain = adaptive.getForeground() != null ? adaptive.getForeground() : adaptive.getBackground();
                    } else if (face instanceof net.dongliu.apk.parser.bean.Icon) {
                        plain = (net.dongliu.apk.parser.bean.Icon) face;
                    } else {
                        continue; // e.g. ColorIcon — no bitmap data available
                    }
                    if (plain == null) continue;
                    byte[] data = plain.getData();
                    String mime = detectRasterMime(data);
                    if (mime == null) continue; // vector/binary-XML icon layer — not renderable as an <img>
                    int density = plain.getDensity();
                    if (density >= bestDensity) {
                        best = data;
                        bestMime = mime;
                        bestDensity = density;
                    }
                } catch (Exception inner) {
                    log.debug("Skipping unreadable icon entry for {}: {}", info.getPackageName(), inner.toString());
                }
            }
            if (best != null) {
                info.setIconBase64(java.util.Base64.getEncoder().encodeToString(best));
                info.setIconMimeType(bestMime);
            }
        } catch (Exception e) {
            log.warn("Icon extraction failed for {}: {}", info.getPackageName(), e.toString());
        }
    }

    /** Returns the MIME type if {@code data} is a PNG or WebP image, else null. */
    private String detectRasterMime(byte[] data) {
        if (data == null) return null;
        if (data.length >= PNG_SIGNATURE.length && isPng(data)) return "image/png";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "image/webp";
        return null;
    }

    private boolean isPng(byte[] data) {
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) return false;
        }
        return true;
    }

    private void detectSignatureSchemes(ApkFile apkFile, ApkInfo info) {
        try {
            if (apkFile.getApkSingers() != null && !apkFile.getApkSingers().isEmpty()) {
                info.getSignatureSchemes().add("v1 (JAR)");
            }
        } catch (Exception ignored) { }
        try {
            if (apkFile.getApkV2Singers() != null && !apkFile.getApkV2Singers().isEmpty()) {
                info.getSignatureSchemes().add("v2");
            }
        } catch (Exception ignored) { }
    }

    private void parseManifest(String manifestXml, ApkInfo info) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc;
            try (InputStream in = new ByteArrayInputStream(manifestXml.getBytes(StandardCharsets.UTF_8))) {
                doc = db.parse(in);
            }

            NodeList apps = doc.getElementsByTagName("application");
            if (apps.getLength() > 0 && apps.item(0) instanceof Element app) {
                info.setDebuggable(boolAttr(app, "debuggable", false));
                info.setAllowBackup(boolAttr(app, "allowBackup", true));
                // Cleartext default: true for targetSdk <= 27, false for 28+.
                boolean defaultCleartext = info.getTargetSdkVersion() == null
                        || info.getTargetSdkVersion() <= 27;
                info.setUsesCleartextTraffic(boolAttr(app, "usesCleartextTraffic", defaultCleartext));
                info.setHasNetworkSecurityConfig(
                        app.getAttributeNS(ANDROID_NS, "networkSecurityConfig") != null
                                && !app.getAttributeNS(ANDROID_NS, "networkSecurityConfig").isEmpty());
            }

            collectComponents(doc, "activity", info.getActivities(), info);
            collectComponents(doc, "service", info.getServices(), info);
            collectComponents(doc, "receiver", info.getReceivers(), info);
            collectComponents(doc, "provider", info.getProviders(), info);

            // Resolve the launcher (main) activity from intent-filters.
            info.setMainActivity(findMainActivity(doc, info.getPackageName()));
        } catch (Exception e) {
            log.warn("Manifest parse failed: {}", e.toString());
        }
    }

    private void collectComponents(Document doc, String tag, java.util.List<String> sink, ApkInfo info) {
        NodeList nodes = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            String name = el.getAttributeNS(ANDROID_NS, "name");
            if (name == null || name.isEmpty()) continue;
            sink.add(name);
            if (isExported(el)) {
                info.getExportedComponents().add(tag + ": " + name);
            }
            // Android 12 (API 31) requires android:exported to be set explicitly on any
            // component that declares an intent-filter; flag those that omit it.
            String exportedAttr = el.getAttributeNS(ANDROID_NS, "exported");
            boolean explicit = "true".equals(exportedAttr) || "false".equals(exportedAttr);
            if (!explicit && el.getElementsByTagName("intent-filter").getLength() > 0) {
                info.getComponentsMissingExported().add(tag + ": " + name);
            }
        }
    }

    /** Exported if explicitly true, or has an intent-filter and is not explicitly false. */
    private boolean isExported(Element component) {
        String exported = component.getAttributeNS(ANDROID_NS, "exported");
        if ("true".equals(exported)) return true;
        if ("false".equals(exported)) return false;
        return component.getElementsByTagName("intent-filter").getLength() > 0;
    }

    private String findMainActivity(Document doc, String pkg) {
        NodeList activities = doc.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            if (!(activities.item(i) instanceof Element act)) continue;
            NodeList filters = act.getElementsByTagName("intent-filter");
            for (int f = 0; f < filters.getLength(); f++) {
                Element filter = (Element) filters.item(f);
                boolean main = hasChildWithName(filter, "action", "android.intent.action.MAIN");
                boolean launcher = hasChildWithName(filter, "category", "android.intent.category.LAUNCHER");
                if (main && launcher) {
                    String name = act.getAttributeNS(ANDROID_NS, "name");
                    if (name != null && name.startsWith(".") && pkg != null) name = pkg + name;
                    return name;
                }
            }
        }
        return null;
    }

    private boolean hasChildWithName(Element filter, String tag, String value) {
        NodeList kids = filter.getElementsByTagName(tag);
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element e
                    && value.equals(e.getAttributeNS(ANDROID_NS, "name"))) {
                return true;
            }
        }
        return false;
    }

    private boolean boolAttr(Element el, String localName, boolean def) {
        String v = el.getAttributeNS(ANDROID_NS, localName);
        if (v == null || v.isEmpty()) return def;
        return "true".equalsIgnoreCase(v);
    }

    /** Scan zip entries for ABIs, locales, signature files and DEX-based SDK markers. */
    private void scanZip(File apk, ApkInfo info) {
        Set<String> abis = new LinkedHashSet<>();
        Set<String> locales = new TreeSet<>();
        Set<String> sdks = new LinkedHashSet<>();
        Set<String> assetLangs = new TreeSet<>();
        boolean flutter = false, reactNative = false, assetI18n = false;
        StringBuilder dexBytes = new StringBuilder();
        long scannedDex = 0;
        final long maxDexScan = 96L * 1024 * 1024; // cap memory use

        try (ZipFile zip = new ZipFile(apk)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("lib/")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 2 && !parts[1].isEmpty()) abis.add(parts[1]);
                } else if (name.startsWith("res/values-")) {
                    Matcher m = LOCALE_DIR.matcher(name);
                    if (m.find()) locales.add(m.group(1));
                } else if (name.startsWith("assets/")) {
                    if (name.startsWith("assets/flutter_assets/")) flutter = true;
                    // Asset-based i18n: translation files under locale/i18n/lang/translations folders.
                    Matcher dir = ASSET_I18N_DIR.matcher(name.toLowerCase());
                    if (dir.find()) {
                        assetI18n = true;
                        if (dir.group(1) != null) assetLangs.add(dir.group(1));
                    }
                } else if (name.equals("assets/index.android.bundle") || name.endsWith(".jsbundle")) {
                    reactNative = true;
                } else if (name.endsWith(".dex") && scannedDex < maxDexScan) {
                    try (InputStream in = zip.getInputStream(e)) {
                        byte[] data = in.readAllBytes();
                        scannedDex += data.length;
                        dexBytes.append(new String(data, StandardCharsets.ISO_8859_1));
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ex) {
            log.warn("Zip scan failed: {}", ex.toString());
        }

        if (dexBytes.length() > 0) {
            String haystack = dexBytes.toString();
            for (Map.Entry<String, String> marker : SDK_MARKERS.entrySet()) {
                if (haystack.contains(marker.getKey())) {
                    sdks.add(marker.getValue());
                }
            }
        }
        info.getSupportedAbis().addAll(abis);
        info.getSupportedLocales().addAll(locales);
        info.getDetectedSdks().addAll(sdks);

        // Record alternative localization mechanisms for the report.
        if (assetI18n) {
            info.getLocalizationSources().add("Asset-based translations (JSON/i18n files in assets)"
                    + (assetLangs.isEmpty() ? "" : " — languages: " + String.join(", ", assetLangs)));
            // These languages aren't in resources.arsc; include them so they're listed/tested too.
            info.getSupportedLocales().addAll(assetLangs);
        }
        if (flutter) info.getLocalizationSources().add(
                "Flutter app — translations are typically in-bundle (intl/arb); follow the system locale.");
        if (reactNative) info.getLocalizationSources().add(
                "React Native app — translations may be bundled in the JS bundle or fetched dynamically.");
    }

    private Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
