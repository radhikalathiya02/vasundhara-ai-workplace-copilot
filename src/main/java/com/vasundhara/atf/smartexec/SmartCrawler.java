package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.device.AdbClient;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.OutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Smart Execution's own accessibility-tree crawler — completely independent of
 * {@code engine.ExplorationEngine}. Generic across native/Compose/Flutter/React-Native/Xamarin/
 * WebView apps: no hardcoded package/activity/screen names or locators.
 *
 * <p>Guarantees enforced on every step: stays inside the app's own package (foreign apps are
 * force-stopped instantly via {@link SmartForeignAppPolicy}), never taps ad content itself (only
 * its close/skip control, when found — never a live/production ad click), never taps a
 * purchase/subscription control, never confirms an exit dialog. When {@code allowAdInteraction}
 * is set (the AdMob/Firebase category), ad format/placement/close-control/app-stability are
 * additionally validated and reported as findings.
 */
public class SmartCrawler {

    private static final Logger log = LoggerFactory.getLogger(SmartCrawler.class);

    private static final int MAX_APP_EXITS = 4;
    private static final int MAX_NO_PROGRESS_STEPS = 40;
    private static final int MAX_VISITS_PER_SCREEN = 20;
    private static final int OPAQUE_SAME_SCREEN_BITS = 6;
    private static final int OPAQUE_MIN_CHANGE_BITS = 10;
    private static final double[][] OPAQUE_CANDIDATES = {
            {0.50, 0.86}, {0.50, 0.90}, {0.50, 0.80}, {0.50, 0.74}, {0.50, 0.50},
            {0.28, 0.45}, {0.72, 0.45}, {0.28, 0.65}, {0.72, 0.65}
    };

    public record Params(AdbClient adb, AndroidDriver driver, String serial, String pkg, String appLabel,
                         File runDir, int maxSteps, boolean allowAdInteraction, boolean uiChecks,
                         SmartOcrEngine ocr, SmartVisionClient vision, SmartSession session,
                         SmartNavigationGraph graph, BooleanSupplier stopRequested) {}

    public record Result(int screensFound, int actionsPerformed, boolean leftAppRepeatedly) {}

    private final Set<String> seenScreens = new HashSet<>();
    private final Map<String, Integer> visitCounts = new HashMap<>();
    private final Set<String> backedFrom = new HashSet<>();
    private final Set<String> gateAttempted = new HashSet<>();
    private final List<Long> opaqueHashes = new ArrayList<>();
    private final List<Integer> opaqueCursors = new ArrayList<>();
    private final List<SmartFinding> findings = new ArrayList<>();
    private int screenIndex = 0;
    // Set whenever an ad widget was seen (AdMob/Firebase category only) — lets the generic
    // app-exit/crash detection a few lines below flag it as an ad-related stability finding when
    // the app leaves the foreground shortly after an ad was shown/closed, not only in the single
    // step immediately after tapping a close control.
    private int lastAdSeenAtStep = Integer.MIN_VALUE;
    private static final int AD_STABILITY_WINDOW_STEPS = 3;
    // screenName -> evidence filename, one screenshot per newly-discovered screen — feeds the
    // optional Figma design comparison (com.vasundhara.atf.smartexec.figma), which otherwise has no
    // way to see what the app under test actually looked like on each screen.
    private final Map<String, String> screenshotFiles = new LinkedHashMap<>();

    public List<SmartFinding> findings() { return findings; }
    public Map<String, String> screenshotFiles() { return screenshotFiles; }

    public Result explore(Params p) {
        int steps = 0, actions = 0, appExits = 0, noProgress = 0;
        String rootSig = null;
        while (steps < p.maxSteps() && !p.stopRequested().getAsBoolean()) {
            // Ground-truth foreground check via ADB (dumpsys), NOT driver.getCurrentPackage() —
            // verified live that the Appium/UiAutomator2 call can report the session's configured
            // target package even when the REAL foreground is something else entirely (observed:
            // the device sat on the home launcher the whole run while getCurrentPackage() kept
            // reporting the app under test, so the crawler "tested" the launcher's own icons and
            // called it 100% coverage). This mirrors the proven, hardened pattern used everywhere
            // else in this framework for exactly this reason.
            String fg = safe(() -> p.adb().currentForegroundPackage(p.serial()));
            if (fg == null || fg.isBlank() || !fg.equals(p.pkg())) {
                // Debounce before treating this as a real app-exit — a screen/activity transition
                // animation, or a transient system surface (IME, a momentary system dialog) briefly
                // taking focus, can make one single dumpsys round-trip report something other than
                // our package even though the app is still genuinely in the foreground. Re-checking
                // once after a short pause avoids counting that blip toward MAX_APP_EXITS and
                // relaunching an app that never actually left.
                sleep(400);
                String fgRecheck = safe(() -> p.adb().currentForegroundPackage(p.serial()));
                if (fgRecheck != null && fgRecheck.equals(p.pkg())) { continue; }
                fg = fgRecheck;
                if (SmartForeignAppPolicy.isPermissionDialog(fg) && tapPermissionAllow(p)) { steps++; sleep(500); continue; }
                if (SmartForeignAppPolicy.shouldForceStopForeign(fg, p.pkg())) {
                    try { p.adb().forceStop(p.serial(), fg); } catch (Exception ignored) {}
                    // HOME before relaunching — verified live: force-stopping the foreign app alone
                    // can leave Android free to resume whatever task sits behind it in the recents
                    // stack (a previously-tested app, or one an OEM background service/notification
                    // brought forward) instead of the home screen, so the relaunch below can
                    // silently land back in a stale, unrelated app rather than ours.
                    try { p.adb().pressHome(p.serial()); } catch (Exception ignored) {}
                    p.session().addStep("Foreign app '" + fg + "' appeared — force-stopped it and returned to the app.");
                }
                flagIfAdRelatedExit(p, steps, "A foreign surface ('" + fg + "') appeared");
                appExits++;
                if (appExits > MAX_APP_EXITS) return new Result(seenScreens.size(), actions, true);
                try { p.adb().launchApp(p.serial(), p.pkg()); } catch (Exception ignored) {}
                sleep(1500);
                steps++;
                continue;
            }

            // Read the tree through the ALREADY-CONNECTED Appium/UiAutomator2 session, not a
            // separate `adb shell uiautomator dump` process — verified live: Android's
            // UiAutomation service only accepts ONE connected client at a time, so a standalone
            // dump command racing against an active Appium session silently fails (empty output)
            // on every single call. That was completely invisible on a static single-screen app
            // (a stale cached dump of its one unchanging screen looked identical to a fresh one)
            // but meant real multi-screen apps were being crawled off a permanently empty tree —
            // 0 widgets, 0 actions, every iteration, forever mistaken for "nothing left to explore".
            String dump = safe(() -> p.driver().getPageSource());
            if (dump == null) dump = "";
            // Re-verify package identity against the dump itself, not just the foreground check
            // above — verified live: an app can crash and fall back to the home launcher in the
            // gap between the foreground check and this uiDump call (adb round-trips are not
            // instantaneous), and without this the crawler would silently treat the LAUNCHER's own
            // screen as if it were the app under test's, tapping its icons/widgets and reporting
            // fabricated "coverage" of an app that was never actually on screen.
            String dumpPkg = SmartAccessibilityReader.rootPackage(dump);
            if (dumpPkg != null && !dumpPkg.isBlank() && !dumpPkg.equals(p.pkg())) {
                appExits++;
                if (appExits > MAX_APP_EXITS) return new Result(seenScreens.size(), actions, true);
                p.session().addStep("Foreign surface '" + dumpPkg + "' detected mid-crawl (app likely crashed) — relaunching.");
                flagIfAdRelatedExit(p, steps, "The app left the foreground (surface: '" + dumpPkg + "')");
                if (SmartForeignAppPolicy.shouldForceStopForeign(dumpPkg, p.pkg())) {
                    try { p.adb().forceStop(p.serial(), dumpPkg); } catch (Exception ignored) {}
                }
                try { p.adb().pressHome(p.serial()); } catch (Exception ignored) {}
                try { p.adb().launchApp(p.serial(), p.pkg()); } catch (Exception ignored) {}
                sleep(1800);
                steps++;
                continue;
            }
            List<SmartWidget> widgets = SmartAccessibilityReader.parse(dump);
            int[] extents = SmartAccessibilityReader.screenExtents(widgets);
            int sw = extents[0], sh = extents[1];
            String sig = structureSignature(widgets);
            // Computed once per screen: does this screen disclose ad content at all ("Ad",
            // "Sponsored", "Test Ad", ...)? Only then are generic CTA-labeled buttons ("Install",
            // "Download", "Play Now", ...) with no SDK-branded resource-id also treated as ad
            // widgets — see SmartForeignAppPolicy.isAdWidget(w, boolean).
            boolean adDisclosure = SmartForeignAppPolicy.screenHasAdDisclosure(widgets);

            if (rootSig == null) rootSig = sig;
            String screenName = inferScreenName(widgets);
            p.session().setLiveScreenName(screenName);

            if (seenScreens.add(sig)) {
                p.session().addStep("Screen — " + screenName);
                int idx = screenIndex++;
                p.graph().observeScreen(sig, screenName, candidateKeys(widgets, adDisclosure));
                if (p.uiChecks()) runUiChecks(widgets, sw, sh, screenName, p.session().getId());
                captureScreenSnapshot(p, screenName, idx);
            }
            int visits = visitCounts.merge(sig, 1, Integer::sum);
            if (visits > MAX_VISITS_PER_SCREEN) { noProgress++; }

            // Exit-confirmation dialog — never confirm; dismiss via the "stay" side or Back.
            if (SmartForeignAppPolicy.looksLikeExitDialog(widgets)) {
                tapStayButton(p, widgets);
                steps++; sleep(500); continue;
            }

            // Ad content — validate (AdMob/Firebase category) and close, never tap the ad creative
            // itself (that would risk an accidental/live ad click, which the product spec explicitly
            // forbids — only load/display/lifecycle/stability are validated).
            SmartWidget adClose = findAdCloseControl(widgets);
            boolean screenIsAdDominated = isAdDominated(widgets, adDisclosure);
            // Classification/validation runs whenever ANY ad widget is present — not only when the
            // screen is ad-dominated or a close control was found — otherwise a banner ad sitting
            // alongside normal app content (the single most common placement) would never be
            // classified or size/position-checked at all. This does NOT force a `continue`: a
            // banner coexisting with real content shouldn't block exploring the rest of the screen.
            boolean anyAdWidget = widgets.stream().anyMatch(w -> SmartForeignAppPolicy.isAdWidget(w, adDisclosure));
            if (p.allowAdInteraction() && anyAdWidget) lastAdSeenAtStep = steps;
            if (p.allowAdInteraction() && anyAdWidget && visits == 1) {
                SmartForeignAppPolicy.AdFormat fmt = SmartForeignAppPolicy.classifyAdFormat(
                        widgets, sw, sh, screenIsAdDominated, seenScreens.size() <= 1);
                p.session().addStep("AdMob/Firebase — " + fmt + " ad detected on '" + screenName + "'.");
                if (screenIsAdDominated && adClose == null) {
                    findings.add(evidencedFinding(p, "ads", "HIGH", screenName, fmt + " ad",
                            List.of("Reach the '" + screenName + "' screen where a " + fmt + " ad is shown",
                                    "Look for a close/skip control"),
                            "A full-screen ad should always offer a visible, tappable close/skip control.",
                            "No close, skip, or dismiss control could be found on this ad.",
                            safeScreenshot(p)));
                } else if (fmt == SmartForeignAppPolicy.AdFormat.BANNER) {
                    SmartWidget bw = widgets.stream().filter(w -> SmartForeignAppPolicy.isAdWidget(w, adDisclosure)).findFirst().orElse(null);
                    if (bw != null && sh > 0 && bw.height() > sh * 0.28) {
                        findings.add(evidencedFinding(p, "ads", "MEDIUM", screenName, "Banner ad",
                                List.of("Reach the '" + screenName + "' screen where a banner ad is shown"),
                                "A banner ad should occupy a thin strip, typically anchored to the top or bottom edge.",
                                "The banner ad measures ~" + Math.round(bw.height() * 100.0 / sh) + "% of the screen height, which may obscure app content.",
                                safeScreenshot(p)));
                    }
                }
            }
            if (screenIsAdDominated || adClose != null) {
                if (adClose != null) {
                    tap(p, adClose.cx(), adClose.cy());
                    actions++;
                    sleep(900);
                    if (p.allowAdInteraction()) {
                        String after = safe(() -> p.adb().currentForegroundPackage(p.serial()));
                        if (after == null || after.isBlank() || !after.equals(p.pkg())) {
                            findings.add(evidencedFinding(p, "ads", "HIGH", screenName, "App stability",
                                    List.of("Close the ad shown on '" + screenName + "'"),
                                    "The app should return to the foreground and remain stable after an ad is closed.",
                                    "The app did not return to the foreground after the ad's close control was tapped (possible crash, or the ad handed off to an external surface).",
                                    null));
                        }
                    }
                } else {
                    // No close control detected — wait briefly in case it's still loading, then back
                    // out, WITHOUT ever tapping the ad content itself.
                    sleep(600);
                    safe(() -> { p.driver().navigate().back(); return null; });
                }
                steps++; continue;
            }

            // Opaque/canvas screen — no readable, actionable content.
            if (looksOpaqueCanvas(widgets, sw, sh)) {
                if (driveOpaqueScreen(p, widgets, sw, sh)) { actions++; noProgress = 0; }
                steps++; continue;
            }

            SmartWidget next = pickNextAction(widgets, sig, adDisclosure);
            if (next != null) {
                markTried(p, sig, next);
                if (next.editable()) { typeSample(p, next); } else { tap(p, next.cx(), next.cy()); }
                actions++; noProgress = 0;
                sleep(900);
                steps++; continue;
            }

            // Nothing NEW left to try — before giving up, check for a generic gate/advance control
            // (Continue/Next/Done/Confirm/Allow/Agree/Start/...). This is what gets past language
            // pickers, onboarding carousels and permission-style screens: every selectable item on
            // the screen is tried FIRST (above), so a language row still gets tapped like any other
            // candidate; only once nothing else is left do we tap the gate — which matters because
            // a "Confirm" button is often disabled until a selection is made, so tapping it too
            // early would be a no-op that (if permanently marked tried) could never be retried once
            // it becomes live. Bounded to one attempt per exact screen structure so a genuinely
            // inert button can't loop forever — verified via the same generic keyword set already
            // proven for this purpose elsewhere in the framework. No app-specific names.
            SmartWidget gate = findGateControl(widgets);
            if (gate != null && gateAttempted.add(sig)) {
                tap(p, gate.cx(), gate.cy());
                actions++; noProgress = 0;
                sleep(1000);
                steps++; continue;
            }

            // Nothing new here — frontier-directed replay, else Back, else stop.
            List<String> path = p.graph().pathToNearestFrontier(sig, 6);
            if (path != null && !path.isEmpty() && replayPath(p, path)) { noProgress = 0; steps++; continue; }

            boolean atRoot = rootSig.equals(sig);
            if (atRoot || !backedFrom.add(sig)) {
                noProgress++;
                if (noProgress >= MAX_NO_PROGRESS_STEPS) break;
                steps++; continue;
            }
            safe(() -> { p.driver().navigate().back(); return null; });
            steps++;
        }
        return new Result(seenScreens.size(), actions, false);
    }

    // ── Monkey testing: intelligent randomized interaction, not deterministic exploration ──────
    //
    // Unlike explore() (which systematically tries every widget exactly once, in order, to reach
    // full deterministic coverage), Monkey testing intentionally injects RANDOM taps, long-presses,
    // swipes, scrolls, back presses and text input — including repeated interactions with the same
    // control — to stress the app the way a real chaotic user would, while still (a) staying inside
    // the app's own package, (b) never touching purchase/subscription/payment/logout/delete-account
    // controls or ad content, (c) spreading interactions across every reachable screen instead of
    // hammering just the first one or two, and (d) reporting only real, evidenced, deduped findings.
    private static final int MONKEY_MAX_APP_EXITS = 4;
    private static final int MONKEY_FREEZE_STREAK = 6;      // consecutive near-identical screenshots despite action = frozen UI
    private static final int MONKEY_FREEZE_HAMMING_BITS = 4; // aHash distance below this counts as "unchanged"

    private enum MonkeyAction { TAP, LONG_PRESS, SWIPE, SCROLL, BACK, TEXT_INPUT }

    public Result exploreMonkey(Params p) {
        java.util.Random rnd = new java.util.Random();
        int actions = 0, appExits = 0, freezeStreak = 0;
        long lastScreenshotHash = 0L;
        int steps = 0;
        while (steps++ < p.maxSteps() && !p.stopRequested().getAsBoolean()) {
            String fg = safe(() -> p.adb().currentForegroundPackage(p.serial()));
            if (fg == null || fg.isBlank() || !fg.equals(p.pkg())) {
                // Debounce — see explore()'s identical reasoning: a transition animation or a
                // momentary system surface can make a single dumpsys read look like an app-exit.
                sleep(400);
                String recheck = safe(() -> p.adb().currentForegroundPackage(p.serial()));
                if (recheck != null && recheck.equals(p.pkg())) continue;
                fg = recheck;
                if (SmartForeignAppPolicy.isPermissionDialog(fg) && tapPermissionAllow(p)) { sleep(500); continue; }
                if (SmartForeignAppPolicy.shouldForceStopForeign(fg, p.pkg())) {
                    try { p.adb().forceStop(p.serial(), fg); } catch (Exception ignored) {}
                    try { p.adb().pressHome(p.serial()); } catch (Exception ignored) {}
                    p.session().addStep("Monkey testing — foreign app '" + fg + "' appeared, force-stopped and returned.");
                }
                appExits++;
                if (appExits > MONKEY_MAX_APP_EXITS) return new Result(seenScreens.size(), actions, true);
                try { p.adb().launchApp(p.serial(), p.pkg()); } catch (Exception ignored) {}
                sleep(1500);
                continue;
            }

            String dump = safe(() -> p.driver().getPageSource());
            if (dump == null) dump = "";
            String dumpPkg = SmartAccessibilityReader.rootPackage(dump);
            if (dumpPkg != null && !dumpPkg.isBlank() && !dumpPkg.equals(p.pkg())) {
                appExits++;
                if (appExits > MONKEY_MAX_APP_EXITS) return new Result(seenScreens.size(), actions, true);
                p.session().addStep("Monkey testing — foreign surface '" + dumpPkg + "' detected (app likely crashed), relaunching.");
                if (SmartForeignAppPolicy.shouldForceStopForeign(dumpPkg, p.pkg())) {
                    try { p.adb().forceStop(p.serial(), dumpPkg); } catch (Exception ignored) {}
                }
                try { p.adb().pressHome(p.serial()); } catch (Exception ignored) {}
                try { p.adb().launchApp(p.serial(), p.pkg()); } catch (Exception ignored) {}
                sleep(1800);
                continue;
            }

            List<SmartWidget> widgets = SmartAccessibilityReader.parse(dump);
            int[] extents = SmartAccessibilityReader.screenExtents(widgets);
            int sw = extents[0], sh = extents[1];
            String sig = structureSignature(widgets);
            String screenName = inferScreenName(widgets);
            p.session().setLiveScreenName(screenName);
            if (seenScreens.add(sig)) {
                p.session().addStep("Monkey testing — Screen: " + screenName);
                p.graph().observeScreen(sig, screenName, candidateKeys(widgets, false));
            }

            if (SmartForeignAppPolicy.looksLikeExitDialog(widgets)) { tapStayButton(p, widgets); sleep(500); continue; }

            boolean adDisclosure = SmartForeignAppPolicy.screenHasAdDisclosure(widgets);
            SmartWidget adClose = findAdCloseControl(widgets);
            if (isAdDominated(widgets, adDisclosure) || adClose != null) {
                if (adClose != null) { tap(p, adClose.cx(), adClose.cy()); actions++; sleep(700); }
                else safe(() -> { p.driver().navigate().back(); return null; });
                continue;
            }

            // Freeze detection: a screenshot that keeps coming back near-identical despite this loop
            // performing real actions means the UI genuinely stopped responding — a real, reportable
            // defect distinct from "nothing new to try" (which explore() would instead interpret as
            // exploration being complete; Monkey testing has no such notion since it never runs out
            // of random actions to attempt).
            byte[] shot = safeScreenshot(p);
            long hash = aHash(shot);
            if (hash != 0L && lastScreenshotHash != 0L && hamming(hash, lastScreenshotHash) <= MONKEY_FREEZE_HAMMING_BITS) {
                freezeStreak++;
                if (freezeStreak >= MONKEY_FREEZE_STREAK) {
                    findings.add(evidencedFinding(p, "crash", "HIGH", screenName, "UI Freeze",
                            List.of("Run Monkey testing (randomized taps/swipes/long-presses/scrolls/back/text input)"),
                            "The UI should keep responding to input.",
                            "The screen did not change across " + MONKEY_FREEZE_STREAK + " consecutive randomized interactions — the app appears frozen/unresponsive.",
                            shot));
                    freezeStreak = 0;
                    // Recovery attempt: back, then relaunch if that alone doesn't help next iteration.
                    safe(() -> { p.driver().navigate().back(); return null; });
                    sleep(800);
                    continue;
                }
            } else {
                freezeStreak = 0;
            }
            lastScreenshotHash = hash;

            List<SmartWidget> candidates = new ArrayList<>();
            for (SmartWidget w : widgets) {
                if (!w.actionable() || !w.displayed()) continue;
                if (SmartForeignAppPolicy.isAdWidget(w, adDisclosure)) continue;
                if (SmartForeignAppPolicy.isSubscriptionWidget(w)) continue;
                if (SmartForeignAppPolicy.isSensitiveActionWidget(w)) continue;
                String label = w.text() == null ? "" : w.text().trim().toLowerCase();
                if (label.equals("exit") || label.equals("exit app") || label.equals("quit") || label.equals("quit app") || label.equals("close app")) continue;
                candidates.add(w);
            }

            MonkeyAction action = pickMonkeyAction(rnd, candidates);
            switch (action) {
                case TAP -> { SmartWidget w = candidates.get(rnd.nextInt(candidates.size())); tap(p, w.cx(), w.cy()); actions++; sleep(500 + rnd.nextInt(500)); }
                case LONG_PRESS -> { SmartWidget w = candidates.get(rnd.nextInt(candidates.size())); longPress(p, w.cx(), w.cy()); actions++; sleep(600 + rnd.nextInt(500)); }
                case TEXT_INPUT -> {
                    List<SmartWidget> editable = candidates.stream().filter(SmartWidget::editable).toList();
                    SmartWidget w = editable.get(rnd.nextInt(editable.size()));
                    typeSample(p, w);
                    actions++;
                }
                case SCROLL, SWIPE -> { doRandomSwipe(p, sw, sh, rnd); actions++; sleep(500 + rnd.nextInt(400)); }
                case BACK -> { safe(() -> { p.driver().navigate().back(); return null; }); actions++; sleep(500 + rnd.nextInt(400)); }
            }
        }
        return new Result(seenScreens.size(), actions, false);
    }

    /** Weighted-random action choice — mostly taps (the most information-dense action), with the
     *  other gesture types mixed in per the product spec, degrading gracefully when a type has no
     *  eligible target on the current screen (e.g. no editable field → TEXT_INPUT is never picked). */
    private MonkeyAction pickMonkeyAction(java.util.Random rnd, List<SmartWidget> candidates) {
        boolean hasEditable = candidates.stream().anyMatch(SmartWidget::editable);
        List<MonkeyAction> weighted = new ArrayList<>();
        if (!candidates.isEmpty()) {
            for (int i = 0; i < 5; i++) weighted.add(MonkeyAction.TAP);
            weighted.add(MonkeyAction.LONG_PRESS);
        }
        weighted.add(MonkeyAction.SWIPE);
        weighted.add(MonkeyAction.SCROLL);
        weighted.add(MonkeyAction.BACK);
        if (hasEditable) weighted.add(MonkeyAction.TEXT_INPUT);
        return weighted.get(rnd.nextInt(weighted.size()));
    }

    /** A generic directional swipe/scroll (up, down, left or right) within the screen bounds. */
    private void doRandomSwipe(Params p, int sw, int sh, java.util.Random rnd) {
        if (sw <= 0 || sh <= 0) { sw = 1080; sh = 1920; }
        int cx = sw / 2, cy = sh / 2;
        int dir = rnd.nextInt(4);
        int x1, y1, x2, y2;
        switch (dir) {
            case 0 -> { x1 = cx; y1 = (int) (sh * 0.75); x2 = cx; y2 = (int) (sh * 0.25); } // swipe up
            case 1 -> { x1 = cx; y1 = (int) (sh * 0.25); x2 = cx; y2 = (int) (sh * 0.75); } // swipe down
            case 2 -> { x1 = (int) (sw * 0.80); y1 = cy; x2 = (int) (sw * 0.20); y2 = cy; } // swipe left
            default -> { x1 = (int) (sw * 0.20); y1 = cy; x2 = (int) (sw * 0.80); y2 = cy; } // swipe right
        }
        swipe(p, x1, y1, x2, y2, 300);
    }

    private void swipe(Params p, int x1, int y1, int x2, int y2, int durationMs) {
        try {
            p.driver().executeScript("mobile: swipeGesture", Map.of(
                    "left", Math.min(x1, x2), "top", Math.min(y1, y2),
                    "width", Math.max(1, Math.abs(x2 - x1)), "height", Math.max(1, Math.abs(y2 - y1)),
                    "direction", y1 != y2 ? (y1 > y2 ? "up" : "down") : (x1 > x2 ? "left" : "right"),
                    "percent", 1.0));
        } catch (Exception e) {
            try { p.adb().swipe(p.serial(), x1, y1, x2, y2, durationMs); } catch (Exception ignored) {}
        }
    }

    private void longPress(Params p, int x, int y) {
        try { p.driver().executeScript("mobile: longClickGesture", Map.of("x", x, "y", y, "duration", 700)); }
        catch (Exception e) { try { p.adb().longPress(p.serial(), x, y, 700); } catch (Exception ignored) {} }
    }

    /** AdMob/Firebase category only: the app just left the foreground / a foreign surface showed
     *  up shortly (within {@link #AD_STABILITY_WINDOW_STEPS} steps) after an ad was shown or
     *  closed — report it as an ad-attributable stability finding, in addition to (not instead of)
     *  the generic crash/foreign-app recovery the caller already performs. Best-effort/heuristic:
     *  the window can't prove causation, only correlation, so this is worded accordingly. */
    private void flagIfAdRelatedExit(Params p, int steps, String what) {
        if (!p.allowAdInteraction()) return;
        if (steps - lastAdSeenAtStep > AD_STABILITY_WINDOW_STEPS) return;
        findings.add(evidencedFinding(p, "ads", "HIGH", "App", "App stability around ads",
                List.of("Encounter and dismiss/close an ad shown by the app"),
                "The app should remain stable and stay in the foreground before and after an ad is shown.",
                what + " shortly after an ad was shown — possible crash, ANR, or the ad taking over navigation.",
                safeScreenshot(p)));
    }

    /** Screenshot-evidenced finding — every detected issue gets a screenshot, timestamp and
     *  reproduction steps, per the product spec (used by Monkey testing and AdMob/Firebase ad
     *  validation; explore()'s uiChecks findings don't need this — they're structural/static,
     *  not crash-level). */
    /** One screenshot per newly-discovered screen, saved into the run's evidence directory
     *  alongside finding screenshots (served by the same existing artifacts endpoint). Best-effort —
     *  a failure here never affects exploration. */
    private void captureScreenSnapshot(Params p, String screenName, int idx) {
        if (p.runDir() == null) return;
        try {
            byte[] png = safeScreenshot(p);
            if (png == null || png.length == 0) return;
            File dir = new File(p.runDir(), "evidence");
            dir.mkdirs();
            String name = "screen-" + slug(screenName) + "-" + idx + ".png";
            Files.write(new File(dir, name).toPath(), png);
            screenshotFiles.put(screenName, name);
        } catch (Exception ignored) {}
    }

    private static String slug(String s) {
        if (s == null) return "screen";
        String t = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return t.isBlank() ? "screen" : (t.length() > 40 ? t.substring(0, 40) : t);
    }

    private SmartFinding evidencedFinding(Params p, String category, String severity, String screenName,
                                          String feature, List<String> steps, String expected, String actual, byte[] screenshot) {
        String screenshotPath = null;
        if (p.runDir() != null && screenshot != null && screenshot.length > 0) {
            try {
                File dir = new File(p.runDir(), "evidence"); dir.mkdirs();
                String name = category + "-" + System.currentTimeMillis() + ".png";
                Files.write(new File(dir, name).toPath(), screenshot);
                screenshotPath = name;
            } catch (Exception ignored) {}
        }
        return new SmartFinding(java.util.UUID.randomUUID().toString(), category, severity,
                SmartFinding.priorityFor(severity), screenName, feature, feature, steps, expected, actual,
                screenshotPath, null, null, System.currentTimeMillis(), SmartFinding.keyOf(screenName, feature, feature));
    }

    // ── candidate selection ──────────────────────────────────────────────────

    private SmartWidget pickNextAction(List<SmartWidget> widgets, String sig, boolean adDisclosure) {
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            // Ad widgets are never tapped as ordinary exploration candidates, in any category —
            // ad interaction (close-control only) is handled exclusively by the dedicated ad
            // block above, never as part of general exploration.
            if (SmartForeignAppPolicy.isAdWidget(w, adDisclosure)) continue;
            if (SmartForeignAppPolicy.isSubscriptionWidget(w)) continue;
            String label = w.text() == null ? "" : w.text().trim().toLowerCase();
            if (label.equals("back") || label.equals("close") || label.equals("exit") || label.equals("cancel")) continue;
            if (!tried.contains(sig + "|" + w.signature())) return w;
        }
        return null;
    }

    private final Set<String> tried = new HashSet<>();
    private void markTried(Params p, String sig, SmartWidget w) {
        tried.add(sig + "|" + w.signature());
        p.graph().markTried(sig, w.signature());
    }

    private List<String> candidateKeys(List<SmartWidget> widgets, boolean adDisclosure) {
        List<String> out = new ArrayList<>();
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            // Ad widgets are never tapped as ordinary exploration candidates, in any category —
            // ad interaction (close-control only) is handled exclusively by the dedicated ad
            // block above, never as part of general exploration.
            if (SmartForeignAppPolicy.isAdWidget(w, adDisclosure)) continue;
            if (SmartForeignAppPolicy.isSubscriptionWidget(w)) continue;
            out.add(w.signature());
        }
        return out;
    }

    private boolean replayPath(Params p, List<String> path) {
        for (String key : path) {
            String dump = safe(() -> p.driver().getPageSource());
            List<SmartWidget> ws = SmartAccessibilityReader.parse(dump == null ? "" : dump);
            SmartWidget target = null;
            for (SmartWidget w : ws) if (w.actionable() && w.displayed() && w.signature().equals(key)) { target = w; break; }
            if (target == null) return false;
            tap(p, target.cx(), target.cy());
            sleep(900);
        }
        return true;
    }

    // ── ad / exit / permission handling ─────────────────────────────────────

    // Generic "advance past this screen" labels — language pickers, onboarding, ToS/permission
    // gates. Deliberately conservative and keyword-based (no app names); English-only is an
    // accepted, existing limitation shared with the rest of the framework's heuristics.
    private static final Set<String> GATE_LABELS = Set.of(
            "done", "continue", "next", "confirm", "ok", "okay", "agree", "accept", "allow",
            "save", "got it", "understood", "proceed", "apply", "start", "begin", "finish",
            "let's go", "lets go", "get started", "i agree", "i accept", "yes", "submit",
            "skip", "skip for now", "not now", "later");

    private SmartWidget findGateControl(List<SmartWidget> widgets) {
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            String label = w.text() == null ? "" : w.text().trim().toLowerCase();
            if (GATE_LABELS.contains(label)) return w;
        }
        return null;
    }

    private SmartWidget findAdCloseControl(List<SmartWidget> widgets) {
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            String probe = ((w.contentDesc() == null ? "" : w.contentDesc()) + " " + (w.resourceId() == null ? "" : w.resourceId())).toLowerCase();
            if (probe.contains("close") || probe.contains("skip") || probe.contains("dismiss")
                    || (probe.contains("ad_") && (probe.endsWith("x") || probe.contains("close")))) return w;
        }
        return null;
    }
    private boolean isAdDominated(List<SmartWidget> widgets, boolean adDisclosure) {
        long adish = widgets.stream().filter(w -> SmartForeignAppPolicy.isAdWidget(w, adDisclosure)).count();
        return adish >= 3;
    }
    private void tapStayButton(Params p, List<SmartWidget> widgets) {
        for (SmartWidget w : widgets) {
            String t = w.text() == null ? "" : w.text().trim().toLowerCase();
            if (t.equals("cancel") || t.equals("no") || t.equals("stay") || t.equals("not now")) { tap(p, w.cx(), w.cy()); return; }
        }
        safe(() -> { p.driver().navigate().back(); return null; });
    }
    private boolean tapPermissionAllow(Params p) {
        String dump = p.adb().uiDump(p.serial());
        List<SmartWidget> ws = SmartAccessibilityReader.parse(dump);
        for (SmartWidget w : ws) {
            String t = w.text() == null ? "" : w.text().trim().toLowerCase();
            if (t.contains("while using") || t.equals("allow") || t.contains("only this time")) { tap(p, w.cx(), w.cy()); return true; }
        }
        return false;
    }

    // ── UI/UX structural checks (uiux category) ─────────────────────────────

    private void runUiChecks(List<SmartWidget> widgets, int sw, int sh, String screenName, String sessionId) {
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            if (sw > 0 && w.minSide() > 0 && w.minSide() < 32) {
                findings.add(finding("uiux", "MEDIUM", screenName, "Touch target size",
                        "Small touch target", List.of("Open '" + screenName + "'", "Inspect the control at (" + w.cx() + "," + w.cy() + ")"),
                        "Touch target should be at least ~48dp.", "Control measures ~" + w.minSide() + "px on its shortest side.", null));
            }
            if (sw > 0 && (w.x() + w.width() > sw || w.y() + w.height() > sh)) {
                findings.add(finding("uiux", "LOW", screenName, "Layout bounds",
                        "Control partially off-screen", List.of("Open '" + screenName + "'"),
                        "All controls should be fully within the visible viewport.", "A control extends beyond the screen bounds.", null));
            }
        }
    }

    private SmartFinding finding(String category, String severity, String screenName, String feature, String title,
                                 List<String> steps, String expected, String actual, String logs) {
        return new SmartFinding(java.util.UUID.randomUUID().toString(), category, severity,
                SmartFinding.priorityFor(severity), screenName, feature, title, steps, expected, actual,
                null, null, logs, System.currentTimeMillis(), SmartFinding.keyOf(screenName, feature, title));
    }

    // ── opaque/canvas screens: OCR -> vision -> blind grid, with perceptual-hash progress check ──

    private boolean looksOpaqueCanvas(List<SmartWidget> widgets, int sw, int sh) {
        if (widgets.isEmpty() || sw <= 0 || sh <= 0) return false;
        long screenArea = (long) sw * sh;
        int withText = 0, realControls = 0, fullScreenish = 0;
        for (SmartWidget w : widgets) {
            if (w.hasLabel()) withText++;
            if (w.actionable() && (long) w.area() < screenArea * 60 / 100) realControls++;
            if ((long) w.area() >= screenArea * 60 / 100) fullScreenish++;
        }
        return withText == 0 && realControls == 0 && fullScreenish >= 1 && widgets.size() <= 15;
    }

    private boolean driveOpaqueScreen(Params p, List<SmartWidget> widgets, int sw, int sh) {
        byte[] before = safeScreenshot(p);
        long hBefore = aHash(before);
        if (hBefore == 0L) return false;
        int screenK = -1;
        for (int k = 0; k < opaqueHashes.size(); k++) if (hamming(opaqueHashes.get(k), hBefore) <= OPAQUE_SAME_SCREEN_BITS) { screenK = k; break; }
        if (screenK < 0) { opaqueHashes.add(hBefore); opaqueCursors.add(0); screenK = opaqueHashes.size() - 1; }

        int tx = -1, ty = -1;
        if (p.ocr().isAvailable()) {
            int[] pt = ocrCta(p, before, sw, sh);
            if (pt != null) { tx = pt[0]; ty = pt[1]; }
        }
        if (tx < 0 && p.vision().isEnabled()) {
            SmartVisionClient.Tap t = p.vision().chooseTapPoint(before, sw, sh, p.appLabel(), List.of());
            if (t != null) { tx = t.x(); ty = t.y(); }
        }
        if (tx < 0) {
            int cursor = opaqueCursors.get(screenK);
            if (cursor >= OPAQUE_CANDIDATES.length) return false;
            opaqueCursors.set(screenK, cursor + 1);
            tx = (int) (sw * OPAQUE_CANDIDATES[cursor][0]);
            ty = (int) (sh * OPAQUE_CANDIDATES[cursor][1]);
        }
        tap(p, tx, ty);
        sleep(900);
        long hAfter = aHash(safeScreenshot(p));
        if (hAfter != 0L && hamming(hAfter, hBefore) > OPAQUE_MIN_CHANGE_BITS) {
            boolean known = false;
            for (Long hv : opaqueHashes) if (hamming(hv, hAfter) <= OPAQUE_SAME_SCREEN_BITS) { known = true; break; }
            if (!known) { opaqueHashes.add(hAfter); opaqueCursors.add(0); }
        }
        return true;
    }

    private int[] ocrCta(Params p, byte[] png, int sw, int sh) {
        List<SmartOcrEngine.Word> words = p.ocr().recognize(png);
        Set<String> positive = Set.of("start", "continue", "next", "begin", "skip", "allow", "agree",
                "accept", "ok", "okay", "create", "explore", "guest", "proceed", "confirm", "done", "finish");
        for (SmartOcrEngine.Word w : words) {
            String norm = w.text().toLowerCase().replaceAll("[^a-z]", "");
            if (positive.contains(norm) && w.cy() > sh * 0.06) return new int[]{w.cx(), w.cy()};
        }
        return null;
    }

    private static long aHash(byte[] png) {
        if (png == null || png.length == 0) return 0L;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return 0L;
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0) return 0L;
            int N = 8; int[] cell = new int[N * N]; long sum = 0;
            for (int cy = 0; cy < N; cy++) for (int cx = 0; cx < N; cx++) {
                int x0 = cx * w / N, x1 = Math.max(x0 + 1, (cx + 1) * w / N);
                int y0 = cy * h / N, y1 = Math.max(y0 + 1, (cy + 1) * h / N);
                long acc = 0; int n = 0;
                for (int y = y0; y < y1; y += Math.max(1, (y1 - y0) / 4))
                    for (int x = x0; x < x1; x += Math.max(1, (x1 - x0) / 4)) {
                        int rgb = img.getRGB(Math.min(x, w - 1), Math.min(y, h - 1));
                        acc += (int) (0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff));
                        n++;
                    }
                int avg = n > 0 ? (int) (acc / n) : 0;
                cell[cy * N + cx] = avg; sum += avg;
            }
            long mean = sum / (N * N); long hash = 0L;
            for (int i = 0; i < N * N; i++) { hash <<= 1; if (cell[i] >= mean) hash |= 1L; }
            return hash;
        } catch (Exception e) { return 0L; }
    }
    private static int hamming(long a, long b) { return Long.bitCount(a ^ b); }

    // ── misc helpers ─────────────────────────────────────────────────────────

    private void typeSample(Params p, SmartWidget w) {
        tap(p, w.cx(), w.cy());
        sleep(300);
        String hint = (w.hint() == null ? "" : w.hint()).toLowerCase();
        String sample = hint.contains("email") ? "tester@example.com"
                : hint.contains("phone") ? "9998887777"
                : hint.contains("password") ? "Test@1234"
                : hint.contains("search") ? "test" : "Test input";
        try { p.driver().executeScript("mobile: type", Map.of("text", sample)); } catch (Exception ignored) {}
        sleep(300);
    }

    private void tap(Params p, int x, int y) {
        try { p.driver().executeScript("mobile: clickGesture", Map.of("x", x, "y", y)); }
        catch (Exception e) { try { p.adb().tap(p.serial(), x, y); } catch (Exception ignored) {} }
    }

    private byte[] safeScreenshot(Params p) {
        try { return p.driver().getScreenshotAs(OutputType.BYTES); } catch (Exception e) { return null; }
    }

    private String structureSignature(List<SmartWidget> widgets) {
        // Class+resourceId alone is too coarse: apps that reuse a stable shell across screens
        // (single-Activity + Fragments, Compose screens all wrapping one ComposeView, WebViews,
        // list/RecyclerView rows sharing a generic resourceId) produce the SAME signature for
        // genuinely different screens, which made the crawler think a new screen was already
        // "seen" — it never recorded it, its visit counter kept climbing on someone else's tally,
        // and every widget on it looked already-tried, so exploration stalled after 1-2 real
        // screens. Folding in each actionable widget's own label, a digest of the screen's static
        // (non-actionable) text, and the widget counts distinguishes same-shell-different-content
        // screens while still collapsing genuine re-visits of the same screen.
        StringBuilder actionable = new StringBuilder();
        StringBuilder staticText = new StringBuilder();
        int actionableCount = 0;
        for (SmartWidget w : widgets) {
            if (w.actionable()) {
                actionableCount++;
                String label = w.effectiveLabel(widgets);
                actionable.append(w.simpleClass()).append('#').append(w.resourceId()).append(':').append(clip(label)).append(';');
            } else if (w.hasLabel()) {
                String t = w.text() != null && !w.text().isBlank() ? w.text() : w.contentDesc();
                if (t != null) staticText.append(clip(t)).append('|');
            }
        }
        String key = actionable + "#n=" + widgets.size() + ",a=" + actionableCount
                + "#t=" + Integer.toHexString(staticText.toString().hashCode());
        return Integer.toHexString(key.hashCode());
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private String inferScreenName(List<SmartWidget> widgets) {
        for (SmartWidget w : widgets) {
            if (w.hasLabel() && !w.actionable()) {
                String t = w.text() != null && !w.text().isBlank() ? w.text() : w.contentDesc();
                if (t != null && t.length() <= 30) return t.trim();
            }
        }
        return "Screen";
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private interface SafeOp<T> { T run() throws Exception; }
    private static <T> T safe(SafeOp<T> op) { try { return op.run(); } catch (Exception e) { return null; } }
}
