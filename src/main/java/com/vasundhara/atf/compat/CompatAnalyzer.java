package com.vasundhara.atf.compat;

import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;
import com.vasundhara.atf.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure runtime functionality &amp; UI analysis shared by the in-suite
 * {@code CompatibilityTest} (single device) and the multi-emulator
 * {@code CompatMatrixRunner}. It reasons only about what the app did at runtime —
 * the exploration crawl and device logcat — never the manifest, SDK levels,
 * permissions or other static attributes.
 *
 * <p>Every issue carries an issue type, the affected screen, a relevant logcat
 * excerpt (the full exception/stack trace for crashes/ANRs) and, where available,
 * the screenshot of the affected screen.
 */
public final class CompatAnalyzer {

    private CompatAnalyzer() {}

    /** Issue type buckets surfaced in the report. */
    public static final String CRASH = "Crash";
    public static final String ANR_T = "ANR";
    public static final String UI = "UI Issue";
    public static final String FUNC = "Functionality Issue";

    /** One runtime issue tied to a screen, with evidence. {@code crash} is set for crashes only. */
    public record UiIssue(String screen, String type, String description, Severity severity,
                          String logcat, String screenshot, CrashInfo crash) {
        public UiIssue(String screen, String type, String description, Severity severity) {
            this(screen, type, description, severity, "", null, null);
        }
        public UiIssue(String screen, String type, String description, Severity severity,
                       String logcat, String screenshot) {
            this(screen, type, description, severity, logcat, screenshot, null);
        }
    }

    /** Aggregated analysis for one Android version / device. */
    public record Result(List<UiIssue> issues, int score, String status,
                         int screensExplored, int actions, int interactiveElements) {}

    private static final Pattern CRASH_P = Pattern.compile("(?i)FATAL EXCEPTION|AndroidRuntime.*?:");
    private static final Pattern ANR_P = Pattern.compile("(?i)ANR in |Application Not Responding|Reason: Input dispatching timed out");
    private static final Pattern FORCE_P = Pattern.compile("(?i)Force Closing|has died|Process .* killed|lowmemorykiller");

    public static Result analyze(ExplorationResult exp, String crashLog, boolean appLaunched,
                                 String release, int screenW, int screenH, String pkg) {
        List<UiIssue> issues = new ArrayList<>();
        int screenCount = exp == null ? 0 : exp.getUniqueScreenCount();
        String log = crashLog == null ? "" : crashLog;

        ScreenCapture lastScreen = exp != null && !exp.getScreens().isEmpty()
                ? exp.getScreens().get(exp.getScreens().size() - 1) : null;
        String last = lastScreen != null ? screenName(lastScreen) : "App";
        String lastShot = lastScreen != null ? lastScreen.screenshotPath() : null;

        // 1. Launch & startup — functionality blockers (screenshot only if the screen is itself the evidence)
        if (!appLaunched) {
            // No screen to show; the failure is explained by the description + logcat.
            issues.add(new UiIssue("App launch", FUNC,
                    "The app failed to launch on Android " + release + " (no foreground activity after install).",
                    Severity.CRITICAL, relatedLog(log, pkg, null, 12), null));
        } else if (screenCount == 0) {
            // A blank/non-rendering first screen — a screenshot demonstrates it.
            issues.add(new UiIssue("App launch", FUNC,
                    "The app launched but rendered no inspectable UI on Android " + release
                            + " — possible blank screen or immediate crash.", Severity.HIGH,
                    relatedLog(log, pkg, null, 12), lastShot));
        } else {
            long startupMs = exp.getScreens().get(0).loadTimeMillis();
            if (startupMs > 6000) // only flag genuinely poor startup; explained by the number, no screenshot
                issues.add(new UiIssue(screenName(exp.getScreens().get(0)), UI,
                        "Slow startup: first screen took " + startupMs + "ms to become interactive (>6s).",
                        Severity.MEDIUM, "", null));
        }

        // 2. Crashes / ANRs / force-close — the logcat/stack trace is the evidence, no screenshot needed.
        // The device logcat is shared by every process, not just this app, so each match is only
        // trusted if the extracted block actually names this app's package — otherwise a crash in
        // some unrelated app already on the test device would be misattributed to this run.
        if (!log.isBlank()) {
            if (CRASH_P.matcher(log).find()) {
                String crashBlock = block(log, "FATAL EXCEPTION", 60, pkg);
                if (blockMentionsPkg(crashBlock, pkg)) {
                    CrashInfo crash = CrashParser.parse(crashBlock, pkg);
                    String desc = crash != null && !crash.exceptionType().isBlank()
                            ? "Application crash on Android " + release + ": " + crash.exceptionType()
                              + (crash.message() != null && !crash.message().isBlank() ? " — " + crash.message() : "")
                            : "Application crash (fatal exception) on Android " + release + ".";
                    issues.add(new UiIssue(last, CRASH, desc, Severity.CRITICAL, crashBlock, null, crash));
                }
            }
            String anrBlock = block(log, "ANR in", 20, pkg);
            if (ANR_P.matcher(log).find() && blockMentionsPkg(anrBlock, pkg))
                issues.add(new UiIssue(last, ANR_T, "ANR (Application Not Responding) on Android "
                        + release + ".", Severity.CRITICAL, anrBlock, null));
            String forceBlock = block(log, "died", 12, pkg);
            if (FORCE_P.matcher(log).find() && blockMentionsPkg(forceBlock, pkg))
                issues.add(new UiIssue(last, CRASH, "App process was force-closed / killed during the flow on Android "
                        + release + ".", Severity.HIGH, forceBlock, null));
        }
        if (exp != null && exp.isCrashSuspected()
                && issues.stream().noneMatch(i -> i.type().equals(CRASH)))
            issues.add(new UiIssue(last, CRASH, "A crash was suspected during exploration (app stopped responding).",
                    Severity.HIGH, relatedLog(log, pkg, last, 12), null));
        if (exp != null && exp.isLeftAppDuringRun())
            issues.add(new UiIssue(last, FUNC, "The app unexpectedly left the foreground during the user flow.",
                    Severity.MEDIUM, relatedLog(log, pkg, last, 8), null));

        // 3. Visual UI defects — these DO need a screenshot to demonstrate the problem.
        int blank = 0, overlaps = 0, clipped = 0, truncated = 0, actionable = 0;
        if (exp != null) {
            for (ScreenCapture s : exp.getScreens()) {
                String name = screenName(s);
                String shot = s.screenshotPath();
                String rl = relatedLog(log, pkg, name, 6);
                List<Widget> widgets = s.widgets();
                List<Widget> shown = widgets.stream().filter(Widget::displayed).toList();

                if (shown.isEmpty() && ++blank <= 4)
                    issues.add(new UiIssue(name, UI, "Screen rendered without any visible UI elements (blank/empty render).",
                            Severity.MEDIUM, rl, shot));
                if (screenW > 0 && screenH > 0)
                    for (Widget w : shown) {
                        if (w.area() <= 0 || !w.actionable()) continue;
                        boolean off = w.x() < -2 || w.y() < -2
                                || w.x() + w.width() > screenW + 2 || w.y() + w.height() > screenH + 2;
                        if (off && ++clipped <= 5)
                            issues.add(new UiIssue(name, UI, "Interactive element clipped or off-screen (not fully usable): "
                                    + widgetLabel(w) + " at [" + w.x() + "," + w.y() + " " + w.width() + "x" + w.height()
                                    + "] vs screen " + screenW + "x" + screenH + ".", Severity.MEDIUM, rl, shot));
                    }
                // Text truncation: text longer than the widget can plausibly display (~6 px/char minimum).
                for (Widget w : shown) {
                    String wt = w.text();
                    if (wt != null && wt.length() > 14 && w.width() > 0 && w.width() < wt.length() * 6
                            && w.height() > 0 && ++truncated <= 4)
                        issues.add(new UiIssue(name, UI,
                                "Possible text truncation in " + w.simpleClass() + ": \""
                                + (wt.length() > 32 ? wt.substring(0, 29) + "…" : wt)
                                + "\" may not fit in " + w.width() + "×" + w.height() + "px widget.",
                                Severity.LOW, rl, shot));
                }
                List<Widget> act = shown.stream().filter(Widget::actionable).toList();
                actionable += act.size();
                for (int a = 0; a < act.size(); a++)
                    for (int b = a + 1; b < act.size(); b++)
                        if (significantOverlap(act.get(a), act.get(b)) && ++overlaps <= 5)
                            issues.add(new UiIssue(name, UI, "Overlapping interactive elements (tap targets collide): "
                                    + widgetLabel(act.get(a)) + " overlaps " + widgetLabel(act.get(b)) + ".",
                                    Severity.MEDIUM, rl, shot));
            }
        }

        // 4. Navigation / functionality — explained by counts, no screenshot.
        // Uses screenCount (distinct screen STATE signatures), not raw Activity name count: modern
        // single-Activity apps (Jetpack Compose / Jetpack Navigation) render every screen inside one
        // Activity, so exp.getActivitiesReached().size() stays at 1 no matter how much real
        // navigation happened — that used to flag "Broken navigation" on every such app regardless
        // of how many genuinely distinct screens it visited. screenCount already tracks distinct
        // navigated states correctly for both single- and multi-Activity apps.
        int actions = exp == null ? 0 : exp.getActionsPerformed();
        if (actionable > 0 && actions >= 5 && screenCount <= 1)
            issues.add(new UiIssue("Navigation", FUNC, "Broken navigation: " + actions
                    + " UI action(s) were performed but the app never left the first screen — buttons/navigation may not be functioning.",
                    Severity.HIGH, relatedLog(log, pkg, null, 6), null));

        return new Result(issues, scoreFor(issues), statusFor(issues), screenCount, actions, actionable);
    }

    public static String statusFor(List<UiIssue> issues) {
        boolean fail = issues.stream().anyMatch(i -> i.severity() == Severity.CRITICAL || i.severity() == Severity.HIGH);
        return fail ? "FAIL" : (issues.isEmpty() ? "PASS" : "WARNING");
    }

    public static int scoreFor(List<UiIssue> issues) {
        int score = 100;
        for (UiIssue i : issues)
            score -= switch (i.severity()) {
                case CRITICAL -> 40; case HIGH -> 25; case MEDIUM -> 10; case LOW -> 4; default -> 0;
            };
        return Math.max(0, score);
    }

    public static String verdict(int score) {
        return score >= 85 ? "Excellent" : score >= 70 ? "Good" : score >= 50 ? "Fair" : "Poor";
    }

    /* ---- per-screen result builder --------------------------------------- */

    /**
     * Groups the flat issue list by explored screen and computes a Pass/Fail status for
     * each screen. Each {@link CompatVersionResult.ScreenResult} gets a screenshot URL
     * built from {@code artifactBase} + the relative path stored in the ScreenCapture.
     */
    public static List<CompatVersionResult.ScreenResult> buildScreenResults(
            ExplorationResult exp, List<UiIssue> allIssues, String artifactBase) {
        if (exp == null || exp.getScreens().isEmpty()) return List.of();
        List<CompatVersionResult.ScreenResult> out = new ArrayList<>();
        for (ScreenCapture s : exp.getScreens()) {
            String name = screenName(s);
            String shotUrl = (s.screenshotPath() != null && !s.screenshotPath().isBlank() && artifactBase != null)
                    ? artifactBase + s.screenshotPath() : null;
            CompatVersionResult.ScreenResult sr = new CompatVersionResult.ScreenResult(name, shotUrl);
            for (UiIssue i : allIssues) {
                if (name.equals(i.screen())) {
                    String issShot = (i.screenshot() != null && !i.screenshot().isBlank() && artifactBase != null)
                            ? artifactBase + i.screenshot() : i.screenshot();
                    sr.getIssues().add(new CompatVersionResult.Issue(
                            i.screen(), i.type(), i.description(), i.severity().name(),
                            i.logcat(), issShot, i.crash()));
                }
            }
            String st = "PASS";
            for (CompatVersionResult.Issue iss : sr.getIssues()) {
                if ("CRITICAL".equals(iss.severity()) || "HIGH".equals(iss.severity())) { st = "FAIL"; break; }
                else if ("MEDIUM".equals(iss.severity()) || "LOW".equals(iss.severity())) st = "WARNING";
            }
            sr.setStatus(st);
            out.add(sr);
        }
        return out;
    }

    /**
     * Checks for landscape orientation regressions by comparing a landscape XML widget
     * count against the portrait baseline. Returns an empty list when no issues found.
     *
     * @param landXml           uiautomator XML dump taken in landscape orientation
     * @param firstActivity     the activity name to attach the issue to (best-effort)
     * @param portraitWidgetCount total widget count across all portrait screens
     */
    public static List<UiIssue> orientationIssues(String landXml, String firstActivity, int portraitWidgetCount) {
        List<UiIssue> out = new ArrayList<>();
        String act = firstActivity != null && !firstActivity.isBlank() ? firstActivity : "App";
        if (landXml == null || landXml.isBlank()) {
            out.add(new UiIssue(act, UI,
                    "Landscape orientation: UI snapshot could not be captured — possible layout freeze or crash.",
                    Severity.MEDIUM));
            return out;
        }
        int landCount = countXmlBounds(landXml);
        if (portraitWidgetCount > 0 && landCount < portraitWidgetCount / 4) {
            out.add(new UiIssue(act, UI,
                    "Landscape layout regression: only " + landCount + " UI element(s) visible in landscape "
                    + "vs " + portraitWidgetCount + " in portrait — significant layout collapse or missing views.",
                    Severity.MEDIUM));
        }
        return out;
    }

    private static int countXmlBounds(String xml) {
        int count = 0, pos = 0;
        while ((pos = xml.indexOf("bounds=", pos)) >= 0) { count++; pos++; }
        return count;
    }

    /* ---- logcat extraction ----------------------------------------------- */

    /**
     * Extract a block starting at a line matching {@code marker}, up to {@code maxLines}. The
     * device logcat is shared by every process, so a marker can legitimately occur more than
     * once for unrelated apps — prefer the first occurrence whose window mentions {@code pkg}
     * over blindly taking the very first occurrence in the log.
     */
    private static String block(String log, String marker, int maxLines, String pkg) {
        String[] lines = log.split("\\R");
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(marker.toLowerCase())) starts.add(i);
        }
        if (starts.isEmpty()) return "(no matching logcat line found)";
        int start = starts.get(0);
        if (pkg != null && !pkg.isBlank()) {
            for (int s : starts) {
                if (sliceBlock(lines, s, maxLines).contains(pkg)) { start = s; break; }
            }
        }
        return sliceBlock(lines, start, maxLines);
    }

    /** True if the extracted block can be attributed to this app (or no package was supplied). */
    private static boolean blockMentionsPkg(String block, String pkg) {
        return pkg == null || pkg.isBlank() || block.contains(pkg);
    }

    private static String sliceBlock(String[] lines, int start, int maxLines) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < Math.min(lines.length, start + maxLines); i++) {
            sb.append(lines[i]).append('\n');
            if (sb.length() > 2400) break;
        }
        return sb.toString().trim();
    }

    /** Most-recent logcat lines mentioning the activity or package — "simple output related to the issue". */
    private static String relatedLog(String log, String pkg, String activity, int maxLines) {
        if (log == null || log.isBlank()) return "";
        String[] lines = log.split("\\R");
        List<String> matched = new ArrayList<>();
        for (String l : lines) {
            boolean hit = (activity != null && !activity.isBlank() && l.contains(activity))
                    || (pkg != null && !pkg.isBlank() && l.contains(pkg));
            if (hit) matched.add(l);
        }
        if (matched.isEmpty()) return "";
        int from = Math.max(0, matched.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < matched.size(); i++) {
            sb.append(matched.get(i)).append('\n');
            if (sb.length() > 1600) break;
        }
        return sb.toString().trim();
    }

    /* ---- helpers --------------------------------------------------------- */

    private static boolean significantOverlap(Widget a, Widget b) {
        // Ignore zero-area and tiny widgets (collapsed, hidden, invisible views).
        // Math.max(1,...) was the previous floor — it turned any zero-area widget into a
        // guaranteed false positive because any intersection would satisfy inter/1 ≥ 60.
        if (a.area() < 900 || b.area() < 900) return false;

        int ix = Math.max(a.x(), b.x()), iy = Math.max(a.y(), b.y());
        int ix2 = Math.min(a.x() + a.width(), b.x() + b.width());
        int iy2 = Math.min(a.y() + a.height(), b.y() + b.height());
        int iw = ix2 - ix, ih = iy2 - iy;
        if (iw <= 0 || ih <= 0) return false;

        int smaller = Math.min(a.area(), b.area());
        int larger  = Math.max(a.area(), b.area());
        int inter   = iw * ih;
        double ratio = (double) inter / smaller;

        // Parent-child containment: a clickable container holding a clickable child is normal
        // Android (ripple wrapper, CardView, list-item FrameLayout). Exclude when ≥80 % of the
        // smaller element is covered AND the outer element is at least 10 % bigger.
        if (ratio >= 0.80 && (double) larger / smaller >= 1.10) return false;

        return ratio >= 0.60;
    }

    private static String widgetLabel(Widget w) {
        if (w.text() != null && !w.text().isBlank()) return "\"" + w.text().trim() + "\"";
        if (w.contentDesc() != null && !w.contentDesc().isBlank()) return "\"" + w.contentDesc().trim() + "\"";
        if (w.resourceId() != null && !w.resourceId().isBlank()) return w.resourceId();
        return w.simpleClass();
    }

    public static String screenName(ScreenCapture s) {
        if (s.activity() != null && !s.activity().isBlank()) {
            String a = s.activity();
            int dot = a.lastIndexOf('.');
            return dot >= 0 ? a.substring(dot + 1) : a;
        }
        return "Screen #" + s.index();
    }

    public static String shortTitle(String description) {
        int nl = description.indexOf('\n');
        String first = nl >= 0 ? description.substring(0, nl) : description;
        return first.length() > 80 ? first.substring(0, 77) + "…" : first;
    }
}
