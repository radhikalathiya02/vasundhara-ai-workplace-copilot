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
 * force-stopped instantly via {@link SmartForeignAppPolicy}), never taps ad content except the
 * single sanctioned close-and-return check when {@code allowAdInteraction} is set, never taps a
 * purchase/subscription control, never confirms an exit dialog.
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

    public List<SmartFinding> findings() { return findings; }

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
                p.graph().observeScreen(sig, screenName, candidateKeys(widgets, p.allowAdInteraction(), adDisclosure));
                if (p.uiChecks()) runUiChecks(widgets, sw, sh, screenName, p.session().getId());
            }
            int visits = visitCounts.merge(sig, 1, Integer::sum);
            if (visits > MAX_VISITS_PER_SCREEN) { noProgress++; }

            // Exit-confirmation dialog — never confirm; dismiss via the "stay" side or Back.
            if (SmartForeignAppPolicy.looksLikeExitDialog(widgets)) {
                tapStayButton(p, widgets);
                steps++; sleep(500); continue;
            }

            // Ad content — close-and-move-on by default; the sanctioned single click+return only
            // when explicitly permitted (AdMob category), and only on first visit to this screen.
            SmartWidget adClose = findAdCloseControl(widgets);
            boolean screenIsAdDominated = isAdDominated(widgets, adDisclosure);
            if (screenIsAdDominated || adClose != null) {
                if (p.allowAdInteraction() && screenIsAdDominated && visits == 1) {
                    SmartWidget target = adClose != null ? adClose : pickAnyAdWidget(widgets);
                    if (target != null) {
                        tap(p, target.cx(), target.cy());
                        actions++;
                        sleep(1200);
                        String after = safe(() -> p.adb().currentForegroundPackage(p.serial()));
                        if (after == null || after.isBlank() || !after.equals(p.pkg())) {
                            // Ad click opened an external surface (Play Store/browser) — return immediately.
                            if (after != null) { try { p.adb().forceStop(p.serial(), after); } catch (Exception ignored) {} }
                            try { p.adb().pressHome(p.serial()); } catch (Exception ignored) {}
                            try { p.adb().launchApp(p.serial(), p.pkg()); } catch (Exception ignored) {}
                            sleep(1200);
                        }
                        steps++; continue;
                    }
                }
                if (adClose != null) { tap(p, adClose.cx(), adClose.cy()); actions++; sleep(700); }
                else safe(() -> { p.driver().navigate().back(); return null; });
                steps++; continue;
            }

            // Opaque/canvas screen — no readable, actionable content.
            if (looksOpaqueCanvas(widgets, sw, sh)) {
                if (driveOpaqueScreen(p, widgets, sw, sh)) { actions++; noProgress = 0; }
                steps++; continue;
            }

            SmartWidget next = pickNextAction(widgets, sig, p.allowAdInteraction(), adDisclosure);
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

    // ── candidate selection ──────────────────────────────────────────────────

    private SmartWidget pickNextAction(List<SmartWidget> widgets, String sig, boolean allowAds, boolean adDisclosure) {
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            if (!allowAds && SmartForeignAppPolicy.isAdWidget(w, adDisclosure)) continue;
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

    private List<String> candidateKeys(List<SmartWidget> widgets, boolean allowAds, boolean adDisclosure) {
        List<String> out = new ArrayList<>();
        for (SmartWidget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            if (!allowAds && SmartForeignAppPolicy.isAdWidget(w, adDisclosure)) continue;
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
    private SmartWidget pickAnyAdWidget(List<SmartWidget> widgets) {
        return widgets.stream().filter(w -> w.actionable() && w.displayed()).findFirst().orElse(null);
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
        StringBuilder sb = new StringBuilder();
        for (SmartWidget w : widgets) if (w.actionable()) sb.append(w.simpleClass()).append('#').append(w.resourceId()).append(';');
        return Integer.toHexString(sb.toString().hashCode());
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
