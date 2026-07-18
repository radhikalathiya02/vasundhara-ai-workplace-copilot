package com.vasundhara.atf.smartexec.security;

import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.smartexec.SmartFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Security Testing category for Smart Execution — generic across any Android APK (native,
 * Flutter, Compose, React Native, Xamarin, WebView/hybrid): every check reasons over the APK's
 * own manifest/bytecode/resources or the device's live state, never a hardcoded package/activity
 * name or app-specific rule. Produces ordinary {@link SmartFinding}s tagged {@code category="security"}
 * — reuses the exact same finding model, dedup (via the caller's {@code dedupeKeys}), evidence and
 * report-rendering plumbing every other category already has (see {@code SmartIssueReportBuilder}'s
 * pattern), just with a dedicated score/summary report on top (see {@link SecurityReportBuilder}).
 *
 * <p>Findings' {@code feature} field carries the product-spec "Security Category" sub-bucket
 * (APK Security / Data Security / Network Security / WebView Security / Runtime Security /
 * Privacy &amp; Compliance); the OWASP Mobile Top 10 mapping and developer recommendation are
 * woven into {@code actualResult}/{@code expectedResult} respectively, since the generic finding
 * model has no dedicated columns for them (see {@link SecurityReportBuilder} for full-fidelity
 * rendering of the same data).
 */
public final class SecurityScanner {
    private SecurityScanner() {}

    private static final Logger log = LoggerFactory.getLogger(SecurityScanner.class);

    private static SmartFinding finding(String severity, String category, String title, String owasp,
                                        String description, String recommendation, List<String> steps,
                                        String logsExcerpt, String screenshotPath) {
        String actual = (owasp != null ? "[OWASP " + owasp + "] " : "") + description;
        return new SmartFinding(java.util.UUID.randomUUID().toString(), "security", severity,
                SmartFinding.priorityFor(severity), category, category, title,
                steps == null ? List.of() : steps, recommendation, actual, screenshotPath, null,
                logsExcerpt, System.currentTimeMillis(), SmartFinding.keyOf(category, category, title));
    }

    // ── APK Security + Network Security (manifest-derived, already parsed by ApkAnalyzer) ──────

    // ── Runtime: plaintext-secret detection in the app's own logcat output ─────────────────────
    // "Sensitive Data Sent in Plain Text" / a runtime slice of Token/API Key Exposure: some apps
    // log request/response bodies or auth headers at debug level — a real, common leak vector.

    private static final Pattern LOG_AUTH_HEADER = Pattern.compile("(?i)authorization\\s*:\\s*bearer\\s+[A-Za-z0-9\\-_.]{10,}");
    private static final Pattern LOG_PLAIN_CRED = Pattern.compile("(?i)\\b(password|passwd|pwd)\\b\\s*[=:]\\s*\\S{3,}");

    public static List<SmartFinding> scanLogcatForSecrets(String logcat) {
        List<SmartFinding> out = new ArrayList<>();
        if (logcat == null || logcat.isBlank()) return out;
        Matcher auth = LOG_AUTH_HEADER.matcher(logcat);
        if (auth.find()) {
            out.add(finding("HIGH", "Network Security", "Sensitive Data Sent in Plain Text", "M3: Insecure Communication",
                    "An Authorization: Bearer token was found written to the device log — logged auth tokens can be read by any app with READ_LOGS on older Android versions, or via `adb logcat` during development/testing.",
                    "Never log Authorization headers or bearer tokens, even at debug level; strip sensitive headers before logging HTTP traffic.",
                    List.of("Run the app and exercise a login/authenticated request while logcat is captured"),
                    excerpt(auth.group()), null));
        }
        Matcher cred = LOG_PLAIN_CRED.matcher(logcat);
        if (cred.find()) {
            out.add(finding("HIGH", "Authentication & Session", "Sensitive Data Sent in Plain Text", "M4: Insecure Authentication",
                    "A password-like value was found written to the device log in plain text.",
                    "Never log password/credential fields, even partially or at debug level.",
                    List.of("Run the app and exercise a login flow while logcat is captured"),
                    excerpt(cred.group()), null));
        }
        return out;
    }

    public static List<SmartFinding> scanApkInfo(ApkInfo apk) {
        List<SmartFinding> out = new ArrayList<>();
        String cat = "APK Security";

        if (apk.getSignatureSchemes() == null || apk.getSignatureSchemes().isEmpty()) {
            out.add(finding("CRITICAL", cat, "APK Signature Validation", "M10: Extraneous Functionality",
                    "No APK signing scheme could be detected — the APK may be unsigned or the signature could not be verified.",
                    "Ensure release builds are signed (v2/v3 signing scheme) before distribution.", null, null, null));
        } else if (!apk.getSignatureSchemes().contains("v2")) {
            out.add(finding("MEDIUM", cat, "APK Signature Validation", "M10: Extraneous Functionality",
                    "Only legacy v1 (JAR) signing was detected — v1-only APKs are vulnerable to APK tampering (the \"Janus\" class of vulnerabilities) on older Android versions.",
                    "Add APK Signature Scheme v2/v3 signing (the default with modern Android Gradle Plugin/Play App Signing).", null, null, null));
        }

        if (apk.isDebuggable()) {
            out.add(finding("HIGH", cat, "Debuggable Build Detection", "M10: Extraneous Functionality",
                    "The app declares android:debuggable=\"true\" — a debuggable release build lets anyone attach a debugger, read process memory, and bypass many client-side protections.",
                    "Ensure android:debuggable is false (the default) in release builds — do not set it explicitly true outside a debug build type.", null, null, null));
        }

        if (apk.isAllowBackup()) {
            out.add(finding("MEDIUM", cat, "Backup Enabled Check", "M2: Insecure Data Storage",
                    "The app allows android:allowBackup=\"true\" (or omits it, which defaults to true) — app data can be extracted via adb backup on a debug-enabled device without root.",
                    "Set android:allowBackup=\"false\", or provide a android:fullBackupContent rule that excludes sensitive files.", null, null, null));
        }

        if (apk.getComponentsMissingExported() != null && !apk.getComponentsMissingExported().isEmpty()) {
            out.add(finding("MEDIUM", cat, "Exported Components Validation", "M1: Improper Platform Usage",
                    apk.getComponentsMissingExported().size() + " component(s) declare an intent-filter without an explicit android:exported attribute: "
                            + String.join(", ", cap(apk.getComponentsMissingExported(), 15))
                            + " — Android 12+ requires this to be explicit, and an implicit default can silently expose a component.",
                    "Add an explicit android:exported=\"true|false\" to every component with an intent-filter, defaulting to false unless it must be reachable from other apps.",
                    null, null, null));
        }
        if (apk.getExportedComponents() != null && !apk.getExportedComponents().isEmpty()) {
            out.add(finding("MEDIUM", cat, "Exported Components Validation", "M1: Improper Platform Usage",
                    apk.getExportedComponents().size() + " component(s) are exported (reachable by other apps): "
                            + String.join(", ", cap(apk.getExportedComponents(), 15)) + ".",
                    "Verify each exported component genuinely needs to be reachable by other apps, and that it validates/authorizes incoming Intents rather than trusting them — add android:permission where appropriate.",
                    null, null, null));
        }

        if (apk.getDangerousPermissions() != null && !apk.getDangerousPermissions().isEmpty()) {
            String sev = apk.getDangerousPermissions().size() > 5 ? "MEDIUM" : "LOW";
            out.add(finding(sev, cat, "Dangerous Permissions", "M1: Improper Platform Usage",
                    apk.getDangerousPermissions().size() + " dangerous permission(s) declared: " + String.join(", ", cap(apk.getDangerousPermissions(), 20)) + ".",
                    "Request only permissions the app's actual features need, request them at time-of-use rather than all at launch, and document the justification for each in the store listing / privacy policy.",
                    null, null, null));
        }
        out.add(finding("INFO", cat, "Unused Permissions", "M1: Improper Platform Usage",
                "Not evaluated — determining whether a declared permission is actually exercised by app code requires call-graph/code-coverage analysis beyond static manifest/string scanning.",
                "Review declared permissions manually against actual feature usage; remove any that are no longer needed.", null, null, null));

        if (apk.getMinSdkVersion() != null && apk.getMinSdkVersion() < 21) {
            out.add(finding("LOW", cat, "SDK Version Validation", "M1: Improper Platform Usage",
                    "minSdkVersion is " + apk.getMinSdkVersion() + " — very old Android versions lack many platform security hardening features (e.g. modern TLS defaults, scoped storage, permission runtime model).",
                    "Raise minSdkVersion to a currently-supported baseline (API 24+ recommended) unless legacy device support is a hard requirement.", null, null, null));
        }
        if (apk.getTargetSdkVersion() != null && apk.getTargetSdkVersion() < 33) {
            out.add(finding("MEDIUM", cat, "SDK Version Validation", "M1: Improper Platform Usage",
                    "targetSdkVersion is " + apk.getTargetSdkVersion() + " — apps targeting older API levels don't benefit from newer platform security defaults (scoped storage, exported-component enforcement, notification permission, etc.) and may be rejected by Play Store's minimum target requirement.",
                    "Raise targetSdkVersion to the current Play Store requirement (API 33+ at time of writing) and re-test.", null, null, null));
        }

        // ── Network Security ──
        String netCat = "Network Security";
        if (apk.isUsesCleartextTraffic()) {
            out.add(finding("HIGH", netCat, "Cleartext Traffic Detection", "M3: Insecure Communication",
                    "The app permits cleartext (unencrypted HTTP) network traffic (android:usesCleartextTraffic=\"true\", or implicitly true because targetSdkVersion <= 27).",
                    "Set android:usesCleartextTraffic=\"false\" and use a Network Security Config to allow only specific, justified cleartext exceptions (e.g. a local dev server).",
                    null, null, null));
        }
        if (!apk.isHasNetworkSecurityConfig()) {
            out.add(finding(apk.isUsesCleartextTraffic() ? "MEDIUM" : "LOW", netCat, "Network Security Configuration", "M3: Insecure Communication",
                    "No android:networkSecurityConfig is declared — the app relies entirely on Android's built-in defaults for TLS trust/cleartext policy, with no per-domain control or certificate pinning.",
                    "Add a Network Security Config (res/xml/network_security_config.xml) to explicitly control cleartext exceptions, trusted CAs, and optionally pin certificates for sensitive API domains.",
                    null, null, null));
        }

        return out;
    }

    // ── Data/Network/WebView/Runtime/Privacy checks from the APK's own dex bytes/resources ─────
    // Independent zip scan from ApkAnalyzer's (which only looks for SDK-marker package paths) —
    // this one looks for secret-like patterns, risky WebView API usage, and root/FLAG_SECURE markers.

    private static final Pattern AWS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern GOOGLE_API_KEY = Pattern.compile("AIza[0-9A-Za-z\\-_]{35}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (RSA |EC )?PRIVATE KEY-----");
    private static final Pattern GENERIC_SECRET_ASSIGN = Pattern.compile(
            "(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|client[_-]?secret)\\s*[=:]\\s*[\"']([A-Za-z0-9_\\-./+]{16,})[\"']");
    private static final Pattern FIREBASE_URL = Pattern.compile("[a-z0-9-]+\\.firebaseio\\.com");
    private static final Pattern HTTP_URL = Pattern.compile("http://[a-zA-Z0-9.\\-]+(?:/[\\w\\-./%?=&]*)?");
    private static final Pattern NAMESPACE_HOST = Pattern.compile(
            "(?i)schemas\\.android\\.com|ns\\.adobe\\.com|www\\.w3\\.org|schemas\\.xmlsoap\\.org|xmlpull\\.org|purl\\.org"
            + "|(?:www\\.)?example\\.(?:com|org|net)" // RFC 2606 reserved placeholder domains — never real endpoints
            + "|www\\.apache\\.org/licenses|www\\.gnu\\.org|opensource\\.org|www\\.jacoco\\.org"); // open-source license/notice boilerplate embedded by nearly every library
    private static final Pattern TRUST_ALL = Pattern.compile("checkServerTrusted|X509TrustManager");
    private static final Pattern HOSTNAME_BYPASS = Pattern.compile("ALLOW_ALL_HOSTNAME_VERIFIER|NullHostnameVerifier|return true.{0,20}verify");
    private static final Set<String> ROOT_DETECTION_MARKERS = Set.of(
            "com/scottyab/rootbeer", "rootbeer", "jailmonkey", "com/lookout/rootdetection", "isEmulator", "magisk", "supersu");

    public static List<SmartFinding> scanApkBytes(File apkFile) {
        List<SmartFinding> out = new ArrayList<>();
        String haystack = readDexAndAssetText(apkFile);
        boolean hasGoogleServicesJson = zipContains(apkFile, "google-services.json");
        if (haystack == null) return out;

        // Data Security
        String dataCat = "Data Security";
        List<String> secrets = new ArrayList<>();
        addMatches(AWS_KEY.matcher(haystack), secrets, 3);
        addMatches(GOOGLE_API_KEY.matcher(haystack), secrets, 3);
        if (PRIVATE_KEY.matcher(haystack).find()) secrets.add("embedded PEM private key");
        if (!secrets.isEmpty()) {
            out.add(finding("CRITICAL", dataCat, "Hardcoded Secrets Detection", "M9: Reverse Engineering",
                    "Found " + secrets.size() + " likely hardcoded secret(s) embedded in the app's compiled code: " + redact(secrets) + ".",
                    "Remove hardcoded keys/secrets from client code — use a backend proxy, Android Keystore, or a secrets-management SDK; rotate any exposed credential immediately.",
                    null, null, null));
        }
        Matcher tokenMatcher = GENERIC_SECRET_ASSIGN.matcher(haystack);
        int tokenHits = 0;
        List<String> tokenNames = new ArrayList<>();
        while (tokenMatcher.find() && tokenHits < 5) { tokenNames.add(tokenMatcher.group(1)); tokenHits++; }
        if (tokenHits > 0) {
            out.add(finding("HIGH", dataCat, "Token/API Key Exposure", "M9: Reverse Engineering",
                    "Found " + tokenHits + " string-literal assignment(s) resembling API keys/tokens/secrets in app code: " + String.join(", ", tokenNames) + ".",
                    "Move these values out of client code (server-side, remote config with access control, or Android Keystore-backed storage) — anything shipped in the APK can be extracted.",
                    null, null, null));
        }
        if (hasGoogleServicesJson || FIREBASE_URL.matcher(haystack).find()) {
            out.add(finding("LOW", dataCat, "Firebase Configuration Exposure", "M2: Insecure Data Storage",
                    "The app bundles Firebase configuration (google-services.json and/or a *.firebaseio.com database URL) — normal for Firebase apps, but only safe if Firebase Security Rules actually restrict access; the config itself is not a secret.",
                    "Confirm Firebase Realtime Database/Firestore Security Rules deny unauthenticated/unauthorized reads-writes — a bundled config with open rules exposes all app data.",
                    null, null, null));
        }

        // Network Security (byte-scan-derived)
        String netCat = "Network Security";
        List<String> httpUrlsRaw = new ArrayList<>();
        addMatches(HTTP_URL.matcher(haystack), httpUrlsRaw, 30);
        // Filter out XML namespace/schema URIs (schemas.android.com, ns.adobe.com, w3.org, ...) —
        // these are embedded in every compiled Android resource/manifest and are never actually
        // fetched over the network, so flagging them as "insecure network calls" is a pure false
        // positive that would otherwise show up in nearly every APK regardless of real behavior.
        List<String> httpUrls = new ArrayList<>();
        int httpUrlTotal = 0;
        for (String u : httpUrlsRaw) {
            if (NAMESPACE_HOST.matcher(u).find()) continue;
            httpUrlTotal++;
            if (httpUrls.size() < 5) httpUrls.add(u);
        }
        if (!httpUrls.isEmpty()) {
            out.add(finding("MEDIUM", netCat, "HTTP vs HTTPS Validation", "M3: Insecure Communication",
                    "Found " + httpUrlTotal + " plain http:// URL(s) referenced in app code, e.g.: " + String.join(", ", httpUrls) + ".",
                    "Use https:// for all network endpoints; if a specific host genuinely cannot support TLS, scope the exception narrowly in a Network Security Config rather than allowing cleartext broadly.",
                    null, null, null));
        }
        if (TRUST_ALL.matcher(haystack).find() && HOSTNAME_BYPASS.matcher(haystack).find()) {
            out.add(finding("CRITICAL", netCat, "SSL/TLS Configuration", "M3: Insecure Communication",
                    "Found indicators of a custom TrustManager/HostnameVerifier alongside an \"allow all\"-style bypass pattern — a common way apps accidentally disable certificate validation entirely.",
                    "Never implement a TrustManager/HostnameVerifier that accepts all certificates/hostnames, even for debug builds shipped to testers; use certificate pinning or the platform defaults instead.",
                    null, null, null));
        }

        // WebView Security
        String webCat = "WebView Security";
        if (haystack.contains("addJavascriptInterface")) {
            out.add(finding("HIGH", webCat, "Insecure WebView Configuration", "M7: Client Code Quality",
                    "The app calls addJavascriptInterface on a WebView — on API < 17 this allows arbitrary native code execution from any page the WebView loads, and is risky at any API level if the WebView ever loads untrusted content.",
                    "Only bridge JavaScript interfaces on WebViews that exclusively load trusted, bundled content; annotate exposed methods with @JavascriptInterface and ensure minSdkVersion >= 17.",
                    null, null, null));
        }
        if (haystack.contains("setJavaScriptEnabled")) {
            out.add(finding("LOW", webCat, "JavaScript Enabled Check", "M7: Client Code Quality",
                    "The app calls WebView.setJavaScriptEnabled — enabling JavaScript is expected for many hybrid apps, but widens the attack surface if the WebView can ever load untrusted/remote content.",
                    "Only enable JavaScript on WebViews that load trusted content; if remote URLs are loaded, restrict navigation to an allow-list of your own domains.",
                    null, null, null));
        }
        if (haystack.contains("setAllowUniversalAccessFromFileURLs") || haystack.contains("setAllowFileAccessFromFileURLs")) {
            out.add(finding("HIGH", webCat, "File Access Validation", "M7: Client Code Quality",
                    "The app configures WebView file-URL access (setAllowUniversalAccessFromFileURLs/setAllowFileAccessFromFileURLs) — if enabled, a malicious page can read local files or reach other origins.",
                    "Keep these disabled (the modern default) unless there's a specific, narrow reason; never enable universal access on a WebView that can load remote/untrusted content.",
                    null, null, null));
        } else if (haystack.contains("setAllowFileAccess")) {
            out.add(finding("MEDIUM", webCat, "File Access Validation", "M7: Client Code Quality",
                    "The app calls WebView.setAllowFileAccess — verify it isn't combined with loading untrusted remote content, which could allow local file access from a malicious page.",
                    "Disable file access on any WebView that loads remote/untrusted content; keep it only where the WebView exclusively renders bundled app assets.",
                    null, null, null));
        }
        if (haystack.contains("setMixedContentMode")) {
            out.add(finding("LOW", webCat, "Mixed Content Validation", "M3: Insecure Communication",
                    "The app configures WebView mixed-content mode explicitly — verify it's set to MIXED_CONTENT_NEVER_ALLOW (the secure choice) rather than ALWAYS_ALLOW.",
                    "Use WebSettings.MIXED_CONTENT_NEVER_ALLOW so an HTTPS page can't be downgraded by loading HTTP sub-resources.", null, null, null));
        }

        // Runtime Security (presence markers only — this is static detection of whether these
        // protections/anti-tamper measures appear to be implemented at all, not a live bypass test)
        String runCat = "Runtime Security";
        boolean hasRootDetection = ROOT_DETECTION_MARKERS.stream().anyMatch(haystack::contains);
        out.add(finding("INFO", runCat, "Root Detection (if implemented)", "M8: Code Tampering",
                hasRootDetection ? "A root/emulator-detection library marker was found in the app's compiled code."
                        : "No known root/emulator-detection library marker was found in the app's compiled code.",
                hasRootDetection ? "Root detection appears to be present — verify it's actually enforced (e.g. blocks or warns on rooted devices) rather than only logged."
                        : "For apps handling sensitive data (finance, health, credentials), consider adding root/emulator detection (e.g. RootBeer, Play Integrity API) as defense-in-depth.",
                null, null, null));
        boolean hasFlagSecure = haystack.contains("FLAG_SECURE");
        out.add(finding("INFO", runCat, "Screenshot Protection", "M2: Insecure Data Storage",
                hasFlagSecure ? "A FLAG_SECURE usage marker was found — the app likely blocks screenshots/screen-recording on at least one screen."
                        : "No FLAG_SECURE usage was found anywhere in the app's compiled code.",
                hasFlagSecure ? "Confirm FLAG_SECURE is applied specifically to screens showing sensitive data (payment, credentials, personal info), not just anywhere."
                        : "Consider applying FLAG_SECURE to any screen displaying sensitive data (payment details, credentials, personal/health information) to block screenshots and screen recording.",
                null, null, null));
        // Privacy & Compliance
        String privCat = "Privacy & Compliance";
        boolean mentionsPrivacyPolicy = haystack.toLowerCase(java.util.Locale.ROOT).contains("privacy") &&
                (haystack.contains("http://") || haystack.contains("https://"));
        if (!mentionsPrivacyPolicy) {
            out.add(finding("LOW", privCat, "Privacy Policy Availability", "M1: Improper Platform Usage",
                    "No privacy-policy URL reference was found in the app's strings/resources/compiled code.",
                    "Ensure the app links to a privacy policy (in-app and on the store listing) describing what data is collected and why — required by Play Store policy for most apps.",
                    null, null, null));
        }

        return out;
    }

    private static boolean zipContains(File apkFile, String entrySuffix) {
        try (ZipFile zip = new ZipFile(apkFile)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().endsWith(entrySuffix)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static final long MAX_SCAN_BYTES = 96L * 1024 * 1024;

    private static String readDexAndAssetText(File apkFile) {
        StringBuilder sb = new StringBuilder();
        long scanned = 0;
        try (ZipFile zip = new ZipFile(apkFile)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                boolean relevant = name.endsWith(".dex") || name.endsWith(".json") || name.endsWith(".xml")
                        || name.endsWith(".properties") || name.startsWith("assets/") || name.startsWith("res/raw/");
                if (!relevant || scanned >= MAX_SCAN_BYTES) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] data = in.readAllBytes();
                    scanned += data.length;
                    sb.append(new String(data, StandardCharsets.ISO_8859_1)).append('\n');
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Security byte scan failed on {}: {}", apkFile.getName(), e.toString());
            return null;
        }
        return sb.toString();
    }

    private static void addMatches(Matcher m, List<String> out, int cap) {
        int n = 0;
        while (m.find() && n < cap) { out.add(m.group()); n++; }
    }

    private static List<String> cap(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }

    /** Masks all but the first 4 characters of each secret so evidence doesn't itself leak the value. */
    private static String redact(List<String> secrets) {
        List<String> masked = new ArrayList<>();
        for (String s : secrets) masked.add(s.length() > 4 ? s.substring(0, 4) + "…(redacted)" : "(redacted)");
        return String.join(", ", masked);
    }

    // ── Data Security: best-effort runtime inspection of the app's private data directory ──────
    // Only works on a debuggable build (Android restricts `run-as` to debuggable apps without root)
    // — a real, common limitation, reported transparently rather than silently skipped.

    private static final Set<String> SENSITIVE_KEY_MARKERS = Set.of("password", "passwd", "secret", "token", "apikey", "api_key", "auth", "session");

    public static List<SmartFinding> scanDeviceStorage(AdbClient adb, String serial, String pkg, boolean debuggable) {
        List<SmartFinding> out = new ArrayList<>();
        String dataCat = "Data Security";
        if (!debuggable) {
            out.add(finding("INFO", dataCat, "SharedPreferences / SQLite Inspection", "M2: Insecure Data Storage",
                    "Skipped — the app's private data directory can only be inspected via `adb run-as` on a debuggable build (or a rooted device, which this framework doesn't assume).",
                    "For a full on-device data-at-rest audit, run this scan against a debug build, or use a rooted test device.", null, null, null));
            return out;
        }
        try {
            String prefsList = adb.shellStr(serial, "run-as " + pkg + " ls shared_prefs 2>/dev/null");
            if (prefsList != null && !prefsList.isBlank()) {
                for (String file : prefsList.split("\\R")) {
                    file = file.trim();
                    if (file.isEmpty() || !file.endsWith(".xml")) continue;
                    String content = adb.shellStr(serial, "run-as " + pkg + " cat shared_prefs/" + file + " 2>/dev/null");
                    if (content == null) continue;
                    String lower = content.toLowerCase(java.util.Locale.ROOT);
                    for (String marker : SENSITIVE_KEY_MARKERS) {
                        if (lower.contains("name=\"" + marker) || lower.contains(marker + "\"")) {
                            out.add(finding("HIGH", dataCat, "Sensitive Data Stored in SharedPreferences", "M2: Insecure Data Storage",
                                    "shared_prefs/" + file + " contains a key resembling \"" + marker + "\" stored as plain XML — SharedPreferences are not encrypted by default and are readable on a rooted device or via backup.",
                                    "Use EncryptedSharedPreferences (Jetpack Security library) or the Android Keystore for any credential/token/session data — never store it in plain SharedPreferences.",
                                    List.of("Inspect /data/data/" + pkg + "/shared_prefs/" + file + " on a debuggable build via `adb run-as`"),
                                    excerpt(content), null));
                            break;
                        }
                    }
                }
            }
            String dbList = adb.shellStr(serial, "run-as " + pkg + " ls databases 2>/dev/null");
            if (dbList != null && !dbList.isBlank()) {
                for (String file : dbList.split("\\R")) {
                    file = file.trim();
                    if (file.isEmpty() || !file.endsWith(".db")) continue;
                    String header = adb.shellStr(serial, "run-as " + pkg + " head -c 16 databases/" + file + " | od -c 2>/dev/null");
                    boolean looksLikePlainSqlite = header != null && header.contains("S   Q   L   i   t   e");
                    if (looksLikePlainSqlite) {
                        out.add(finding("MEDIUM", dataCat, "SQLite Database Security", "M2: Insecure Data Storage",
                                "databases/" + file + " is a standard (unencrypted) SQLite database — readable in plain text on a rooted device or via backup if the file contains sensitive data.",
                                "If this database stores sensitive data (credentials, tokens, personal information), use SQLCipher or Jetpack Security's encrypted file APIs instead of a plain SQLite database.",
                                List.of("Inspect /data/data/" + pkg + "/databases/" + file + " on a debuggable build via `adb run-as`"),
                                null, null));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Device storage scan failed for {}: {}", pkg, e.toString());
        }
        return out;
    }

    private static String excerpt(String s) {
        if (s == null) return null;
        String redacted = s.replaceAll("(?i)(value=\")[^\"]{3,}(\")", "$1***redacted***$2");
        return redacted.length() > 1200 ? redacted.substring(0, 1200) + "…" : redacted;
    }

    // ── Overlay/tapjacking risk — from the already-parsed permission list ──────────────────────

    public static List<SmartFinding> scanOverlayRisk(ApkInfo apk) {
        List<SmartFinding> out = new ArrayList<>();
        if (apk.getPermissions() != null && apk.getPermissions().contains("android.permission.SYSTEM_ALERT_WINDOW")) {
            out.add(finding("MEDIUM", "Runtime Security", "Overlay/Tapjacking Protection (where applicable)", "M1: Improper Platform Usage",
                    "The app declares android.permission.SYSTEM_ALERT_WINDOW (draw-over-other-apps) — this permission is commonly abused for tapjacking/overlay attacks and is increasingly restricted by Play Store policy.",
                    "Remove this permission unless the app has a Play-policy-compliant use case (e.g. accessibility tooling); if kept, ensure sensitive screens set setFilterTouchesWhenObscured(true).",
                    null, null, null));
        }
        return out;
    }

    // ── Privacy & Compliance: third-party/analytics SDK summary (from ApkAnalyzer's own detection) ──

    private static final Set<String> ANALYTICS_MARKERS = Set.of(
            "Firebase / GA Analytics", "AppsFlyer", "OneSignal", "Crashlytics", "Facebook SDK", "Meta Audience Network", "Branch");

    public static List<SmartFinding> scanThirdPartySdks(ApkInfo apk) {
        List<SmartFinding> out = new ArrayList<>();
        List<String> sdks = apk.getDetectedSdks();
        if (sdks == null || sdks.isEmpty()) return out;
        List<String> analytics = new ArrayList<>();
        for (String s : sdks) if (ANALYTICS_MARKERS.contains(s)) analytics.add(s);
        out.add(finding("INFO", "Privacy & Compliance", "Third-Party SDK Detection", null,
                sdks.size() + " third-party SDK(s) detected: " + String.join(", ", sdks) + ".",
                "Confirm each third-party SDK's data collection is disclosed in the app's privacy policy and (where required) covered by user consent.",
                null, null, null));
        if (!analytics.isEmpty()) {
            out.add(finding("INFO", "Privacy & Compliance", "Analytics SDK Detection", null,
                    analytics.size() + " analytics/tracking SDK(s) detected: " + String.join(", ", analytics) + ".",
                    "Ensure analytics/tracking SDKs are disclosed to users and respect platform consent requirements (e.g. Google Play's Data Safety section, ATT on iOS if cross-platform).",
                    null, null, null));
        }
        return out;
    }
}
