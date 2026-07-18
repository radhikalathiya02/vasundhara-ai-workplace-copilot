package com.vasundhara.atf.smartexec.performance;

import com.vasundhara.atf.smartexec.SmartFinding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performance Testing category for Smart Execution — generic across any Android APK (native,
 * Flutter, Compose, React Native, Xamarin, WebView/hybrid): every check reasons over standard
 * Android platform instrumentation ({@code am start -W}, {@code dumpsys meminfo/gfxinfo}, logcat)
 * that exists for any app, never a hardcoded package/activity/screen name or app-specific rule.
 * Device orchestration (launching, backgrounding, sampling, the interaction/stress loop) lives in
 * {@code SmartOrchestrator.runPerformance}; this class is pure parsing + threshold judgment,
 * producing ordinary {@link SmartFinding}s tagged {@code category="performance"} — same finding
 * model, dedup and report plumbing every other category already uses (see
 * {@link PerformanceReportBuilder} for the dedicated score/summary report on top).
 *
 * <p>Findings' {@code screenName} field carries the "Performance Metric" bucket the product spec
 * asks for (App Launch / Runtime / Resource Usage / Stability / Network / Rendering / Background /
 * Stress); {@code feature} carries the specific metric name; expected/actual values and the
 * recommendation are woven into {@code expectedResult}/{@code actualResult} per the existing
 * finding model (no dedicated columns for them — see {@link PerformanceReportBuilder} for
 * full-fidelity rendering).
 */
public final class PerformanceScanner {
    private PerformanceScanner() {}

    private static SmartFinding finding(String severity, String bucket, String metric, String expected,
                                        String actual, String recommendation, String logsExcerpt) {
        return new SmartFinding(java.util.UUID.randomUUID().toString(), "performance", severity,
                SmartFinding.priorityFor(severity), bucket, metric, metric, List.of(),
                "Expected: " + expected + " — Recommendation: " + recommendation, actual,
                null, null, logsExcerpt, System.currentTimeMillis(), SmartFinding.keyOf(bucket, metric, metric));
    }

    // ── App Launch Performance ──────────────────────────────────────────────────────────────────
    // Thresholds follow Android's own published guidance (Play Console "vitals" bands): cold start
    // "excellent" < 5s / warm < 2s / hot < 1.5s are the platform's OWN documented ceilings before a
    // launch counts as "slow" for vitals purposes; we additionally flag a tighter MEDIUM band for
    // earlier visibility since most well-optimized apps launch well under those ceilings.

    public record LaunchTimes(long coldMs, long warmMs, long hotMs) {}

    public static List<SmartFinding> scanLaunch(LaunchTimes t) {
        List<SmartFinding> out = new ArrayList<>();
        String bucket = "App Launch Performance";
        out.addAll(judgeLaunch(bucket, "Cold Start Time", t.coldMs(), 2000, 5000));
        out.addAll(judgeLaunch(bucket, "Warm Start Time", t.warmMs(), 1000, 2000));
        out.addAll(judgeLaunch(bucket, "Hot Start Time", t.hotMs(), 500, 1500));
        // Splash Screen Duration / Time to Interactive: `am start -W`'s TotalTime is Android's own
        // "time until first frame drawn" instrumentation — the closest generic, no-instrumentation
        // proxy available for both. Reported explicitly as a proxy, not a true TTI measurement
        // (true TTI needs an app-side instrumentation hook this generic framework doesn't have).
        if (t.coldMs() > 0) {
            out.add(finding("INFO", bucket, "Splash Screen Duration / Time to Interactive (proxy)",
                    "N/A — informational",
                    "Using cold-start TotalTime (" + t.coldMs() + "ms) as the closest generic proxy for splash duration/TTI; "
                            + "a true measurement requires an app-side instrumentation hook this black-box framework doesn't have.",
                    "For an exact TTI, add a custom trace point (e.g. Jetpack Macrobenchmark / Firebase Performance Monitoring) in the app.", null));
        }
        return out;
    }

    private static List<SmartFinding> judgeLaunch(String bucket, String metric, long ms, long goodMs, long poorMs) {
        List<SmartFinding> out = new ArrayList<>();
        if (ms < 0) {
            out.add(finding("INFO", bucket, metric, "< " + goodMs + "ms", "Could not be measured (no TotalTime reported by `am start -W`).",
                    "Re-run once the app has a resolvable launcher activity.", null));
        } else if (ms > poorMs) {
            out.add(finding("HIGH", bucket, metric, "< " + goodMs + "ms (Android vitals ceiling: " + poorMs + "ms)", ms + "ms",
                    "Profile app startup (Android Studio App Startup Insights / Macrobenchmark); defer non-essential initialization (analytics SDKs, ad SDK init) off the launch path.", null));
        } else if (ms > goodMs) {
            out.add(finding("MEDIUM", bucket, metric, "< " + goodMs + "ms", ms + "ms",
                    "Within Android's acceptable range but above the excellent band — consider trimming Application.onCreate() work and lazy-initializing SDKs.", null));
        }
        return out;
    }

    // ── Background Behavior (resume timing reuses the same launch-time judgment bands) ────────

    public static List<SmartFinding> scanBackgroundResume(long backgroundToForegroundMs) {
        List<SmartFinding> out = new ArrayList<>();
        String bucket = "Background Behavior";
        if (backgroundToForegroundMs < 0) return out;
        if (backgroundToForegroundMs > 1500) {
            out.add(finding("MEDIUM", bucket, "Background to Foreground Time / App Resume Performance",
                    "< 1500ms", backgroundToForegroundMs + "ms",
                    "Avoid heavy work in onResume/onStart; if state was saved, restoring it should be near-instant.", null));
        }
        return out;
    }

    // ── Resource Usage: dumpsys meminfo parsing ─────────────────────────────────────────────────

    private static final Pattern TOTAL_PSS_MODERN = Pattern.compile("TOTAL\\s+PSS:\\s*(\\d+)");
    private static final Pattern TOTAL_PSS_LEGACY = Pattern.compile("(?m)^\\s*TOTAL\\s+(\\d+)");

    /** Parses the "TOTAL PSS" (KB) from a `dumpsys meminfo <pkg>` dump — Android's own per-process
     *  memory accounting, generic across every app regardless of runtime (ART/Flutter/RN/etc). */
    public static long parseTotalPssKb(String meminfoDump) {
        if (meminfoDump == null) return -1;
        Matcher m = TOTAL_PSS_MODERN.matcher(meminfoDump);
        if (m.find()) return Long.parseLong(m.group(1));
        m = TOTAL_PSS_LEGACY.matcher(meminfoDump);
        if (m.find()) return Long.parseLong(m.group(1));
        return -1;
    }

    public static List<SmartFinding> scanMemory(List<Long> pssSamplesKb) {
        List<SmartFinding> out = new ArrayList<>();
        String bucket = "Resource Usage";
        List<Long> valid = pssSamplesKb.stream().filter(v -> v != null && v > 0).toList();
        if (valid.isEmpty()) {
            out.add(finding("INFO", bucket, "Memory (RAM) Usage", "measurable", "Could not read TOTAL PSS from dumpsys meminfo — the app may have exited before sampling.",
                    "Re-run with the app kept in foreground longer.", null));
            return out;
        }
        long avg = valid.stream().mapToLong(Long::longValue).sum() / valid.size();
        long peak = valid.stream().mapToLong(Long::longValue).max().orElse(0);
        out.add(finding("INFO", bucket, "Memory (RAM) Usage", "< 250MB average for a typical app", (avg / 1024) + "MB average across " + valid.size() + " sample(s).",
                "No fixed threshold fits every app category (games/media apps legitimately use more) — compare against this app's own baseline over time.", null));
        String peakSev = peak > 500 * 1024 ? "MEDIUM" : "INFO";
        out.add(finding(peakSev, bucket, "Peak Memory Usage", "< 500MB", (peak / 1024) + "MB peak TOTAL PSS observed.",
                peak > 500 * 1024 ? "Investigate what screen/action drove the peak — large bitmaps, unbounded caches, or a leak are common causes." : "Peak usage looks reasonable.",
                null));

        // Memory Leak Detection: a monotonically increasing PSS trend across repeated navigation
        // cycles that doesn't come back down is the standard, generic leak signature — no need to
        // know what the app actually does to observe it.
        if (valid.size() >= 4) {
            long first = valid.get(0), last = valid.get(valid.size() - 1);
            double growthPct = first > 0 ? (last - first) * 100.0 / first : 0;
            boolean monotonic = isMonotonicNonDecreasing(valid, 0.85); // allow minor GC dips
            if (monotonic && growthPct > 25) {
                out.add(finding("HIGH", bucket, "Memory Leak Detection",
                        "Memory should stabilize or return to baseline after repeated navigation, not grow unbounded",
                        String.format(java.util.Locale.ROOT, "TOTAL PSS grew %.0f%% (%dMB → %dMB) across %d navigation cycles with no drop back toward baseline.",
                                growthPct, first / 1024, last / 1024, valid.size()),
                        "Profile with Android Studio Memory Profiler / LeakCanary to find retained Activity/Fragment/Bitmap references across navigation.",
                        null));
            }
        }
        return out;
    }

    private static boolean isMonotonicNonDecreasing(List<Long> values, double toleranceRatio) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) < values.get(i - 1) * toleranceRatio) return false;
        }
        return true;
    }

    // ── Rendering/Stability: dumpsys gfxinfo parsing (janky-frame %, percentiles) ───────────────
    // This IS Android's own platform-level metric for animation smoothness / scrolling / large
    // layout rendering / frame drops / UI jank — generic across every rendering pipeline (View
    // system, Compose, Flutter's own SurfaceView, RN's native views) since it's measured at the
    // SurfaceFlinger/Choreographer level, below any specific UI toolkit.

    private static final Pattern JANKY_FRAMES = Pattern.compile("Janky frames:\\s*(\\d+)\\s*\\(([\\d.]+)%\\)");
    private static final Pattern TOTAL_FRAMES = Pattern.compile("Total frames rendered:\\s*(\\d+)");
    private static final Pattern PCTILE_90 = Pattern.compile("90th percentile:\\s*(\\d+)ms");
    private static final Pattern PCTILE_99 = Pattern.compile("99th percentile:\\s*(\\d+)ms");

    public record GfxStats(int totalFrames, int jankyFrames, double jankyPct, int p90Ms, int p99Ms) {}

    public static GfxStats parseGfxinfo(String gfxDump) {
        if (gfxDump == null) return new GfxStats(0, 0, 0, -1, -1);
        int total = matchInt(TOTAL_FRAMES, gfxDump, 1);
        Matcher jm = JANKY_FRAMES.matcher(gfxDump);
        int janky = 0; double jankyPct = 0;
        if (jm.find()) { janky = Integer.parseInt(jm.group(1)); jankyPct = Double.parseDouble(jm.group(2)); }
        int p90 = matchInt(PCTILE_90, gfxDump, 1);
        int p99 = matchInt(PCTILE_99, gfxDump, 1);
        return new GfxStats(total, janky, jankyPct, p90, p99);
    }

    private static int matchInt(Pattern p, String s, int group) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.parseInt(m.group(group)) : -1;
    }

    public static List<SmartFinding> scanRendering(GfxStats g, boolean hasWebView, boolean isFlutter) {
        List<SmartFinding> out = new ArrayList<>();
        String bucket = "Rendering Performance";
        if (g.totalFrames() <= 0) {
            out.add(finding("INFO", bucket, "Frame Rendering Stats", "measurable",
                    "No gfxinfo frame stats were available — the app may not have rendered enough frames during the sampling window.",
                    "Re-run with more on-screen interaction so more frames are rendered.", null));
        } else {
            String sev = g.jankyPct() > 20 ? "HIGH" : g.jankyPct() > 10 ? "MEDIUM" : "INFO";
            out.add(finding(sev, "Stability", "Frame Drops / UI Jank Detection", "< 10% janky frames",
                    String.format(java.util.Locale.ROOT, "%d/%d frames (%.1f%%) were janky (missed the 16ms frame budget).", g.jankyFrames(), g.totalFrames(), g.jankyPct()),
                    g.jankyPct() > 10 ? "Profile with Android Studio's Layout Inspector/GPU rendering profile; look for overdraw, large RecyclerView item layouts, or work on the main thread during scroll/animation."
                            : "Frame timing looks healthy.", null));
            if (g.p90Ms() > 16) {
                out.add(finding(g.p90Ms() > 32 ? "MEDIUM" : "LOW", bucket, "Animation Smoothness / Scrolling Performance", "90th percentile frame time < 16ms (60fps budget)",
                        "90th percentile frame time is " + g.p90Ms() + "ms" + (g.p99Ms() > 0 ? ", 99th percentile " + g.p99Ms() + "ms" : "") + ".",
                        "Simplify view hierarchies on frequently-scrolled/animated screens; avoid allocations in onDraw/onBindViewHolder.", null));
            }
        }
        if (hasWebView) {
            out.add(finding("INFO", bucket, "WebView Rendering", "N/A — informational",
                    "This app uses WebView — its content rendering is a separate Chromium compositor pipeline not fully reflected in the app's own gfxinfo stats.",
                    "For WebView-heavy screens, additionally check Chrome DevTools remote debugging (chrome://inspect) for page-level rendering performance.", null));
        }
        if (isFlutter) {
            out.add(finding("INFO", bucket, "Flutter Frame Performance (if applicable)", "N/A — informational",
                    "This app is built with Flutter — the platform-level gfxinfo/jank stats above reflect the SurfaceView Flutter renders into, not Flutter's own internal Skia frame timeline.",
                    "For Flutter-specific frame timing, use `flutter run --profile` with the DevTools Performance view during manual testing.", null));
        }
        return out;
    }

    // ── Stability: pid-change (unexpected restart) + freeze detection ──────────────────────────

    public static List<SmartFinding> scanUnexpectedRestart(boolean pidChangedUnexpectedly, String detail) {
        List<SmartFinding> out = new ArrayList<>();
        if (pidChangedUnexpectedly) {
            out.add(finding("HIGH", "Stability", "Unexpected Restarts", "The app process should not restart during normal use",
                    "The app's process id changed during the test session" + (detail != null ? " (" + detail + ")" : "") + ", indicating it crashed and was relaunched, or the system killed it.",
                    "Check the crash buffer / logcat around the restart for the root cause (OOM kill, native crash, ANR-triggered kill).", null));
        }
        return out;
    }

    public static List<SmartFinding> scanFreeze(boolean screenFrozen, int consecutiveUnchangedFrames) {
        List<SmartFinding> out = new ArrayList<>();
        if (screenFrozen) {
            out.add(finding("HIGH", "Stability", "Application Freeze Detection", "The UI should keep responding to input",
                    "The screen did not change across " + consecutiveUnchangedFrames + " consecutive interactions during the stress phase — the app appears frozen/unresponsive.",
                    "Check for a deadlock or long-running main-thread operation (large synchronous I/O, unbounded loop) triggered by rapid interaction.", null));
        }
        return out;
    }

    // ── Stress Validation summary (the loop itself runs in SmartOrchestrator) ──────────────────

    public static List<SmartFinding> scanStressSummary(int actionsPerformed, int screensVisited, boolean survivedWithoutCrash) {
        List<SmartFinding> out = new ArrayList<>();
        String bucket = "Stress Validation";
        out.add(finding("INFO", bucket, "Rapid Navigation / Continuous Screen Switching / Repeated User Actions",
                "App should remain stable under sustained rapid interaction",
                actionsPerformed + " rapid action(s) across " + screensVisited + " screen(s) performed" + (survivedWithoutCrash ? " without crashing." : "; see Stability findings for issues encountered."),
                survivedWithoutCrash ? "No stress-induced instability observed this run." : "Investigate the crash/freeze/ANR findings above — they occurred during the stress phase.", null));
        out.add(finding("INFO", bucket, "Long Running Session / Large Data Handling", "N/A — informational",
                "Long-running-session and large-data-handling stress require a sustained, multi-minute/hour session and/or app-specific large datasets beyond this run's bounded stress window.",
                "For deeper stress coverage, run a longer session (extend the stress budget in Settings) or supply the app with a large dataset via its own seeding mechanism before testing.", null));
        return out;
    }

    // ── Network Performance (reuses the generic network-failure signal already in logcat rules) ─

    public static List<SmartFinding> scanNetworkNote() {
        return List.of(finding("INFO", "Network Performance", "API Response Time / Download / Upload Speed", "N/A — informational",
                "True request-level timing (API response time, download/upload throughput) requires a network proxy or app-side instrumentation this black-box framework doesn't attach. "
                        + "Timeout/retry/connection failures are still captured generically via the Functional/Regression categories' logcat analysis (network request failure rule) and attributed to this run if present.",
                "For request-level timing, integrate an APM SDK (Firebase Performance Monitoring, New Relic) or capture a network proxy trace (e.g. Charles/mitmproxy) during manual testing.", null));
    }

    // ── Storage / Battery / Background service impact — informational limitations ─────────────

    public static List<SmartFinding> scanStorageAndBattery(long appSizeMb) {
        List<SmartFinding> out = new ArrayList<>();
        String bucket = "Resource Usage";
        if (appSizeMb > 0) {
            out.add(finding(appSizeMb > 200 ? "LOW" : "INFO", bucket, "Storage Usage", "APK size proportionate to app functionality",
                    "Installed APK size is ~" + appSizeMb + "MB.",
                    appSizeMb > 200 ? "Consider Android App Bundles, WebP/vector assets, and removing unused resources/ABIs to reduce install size." : "APK size looks reasonable.",
                    null));
        }
        out.add(finding("INFO", bucket, "Battery Consumption", "N/A — informational",
                "Battery-drain measurement requires a long (multi-hour), charged-baseline session (`dumpsys batterystats --reset` / `--charged`) beyond this run's scope.",
                "For a battery audit, run Android Studio's Battery Historian or `adb shell dumpsys batterystats` across a full charge cycle with this app in typical use.", null));
        return out;
    }

    public static List<SmartFinding> scanBackgroundServiceImpact(long foregroundPssKb, long backgroundPssKb) {
        List<SmartFinding> out = new ArrayList<>();
        if (foregroundPssKb <= 0 || backgroundPssKb <= 0) return out;
        double ratio = (double) backgroundPssKb / foregroundPssKb;
        if (ratio > 0.85) {
            out.add(finding("MEDIUM", "Resource Usage", "Background Resource Usage / Background Service Impact",
                    "Memory usage should drop meaningfully when backgrounded",
                    String.format(java.util.Locale.ROOT, "Background TOTAL PSS (%dMB) stayed at %.0f%% of foreground usage (%dMB) — the app may be doing unnecessary work in the background.",
                            backgroundPssKb / 1024, ratio * 100, foregroundPssKb / 1024),
                    "Review background services/WorkManager jobs/foreground services for work that could be deferred, batched, or stopped when not needed.", null));
        }
        return out;
    }
}
