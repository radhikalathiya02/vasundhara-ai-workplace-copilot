package com.vasundhara.atf.localization;

import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.ForeignAppPolicy;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A self-contained, adb-driven UI crawler used by the Localization module. Unlike the
 * shared exploration engine, it drives the app <b>in place</b> (no relaunch), so after a
 * language is selected it continues exploring the actual app content instead of bouncing
 * back to the launcher/Language screen. It systematically traverses screens, taps
 * unexplored controls, dismisses blockers (permission dialogs, onboarding, ads, popups),
 * and keeps going until no new screens are found or the step budget is reached.
 */
public final class LocalizationCrawler {

    private LocalizationCrawler() {}

    /** Crawl output: captured screens + signals for the analyzers. */
    public record Result(List<ScreenCapture> screens, boolean crashSuspected, boolean leftApp, int actions) {}

    /** Buttons that advance onboarding or dismiss non-ad blockers, highest priority first. */
    private static final List<String> BLOCKER_BUTTONS = List.of(
            "allow only while using the app", "while using the app", "allow all the time", "allow",
            "accept all", "accept", "agree", "i agree", "got it", "ok", "okay", "continue",
            "get started", "get started!", "let's go", "lets go", "let's start", "let's begin",
            "start", "start now", "proceed", "save", "apply", "confirm", "next", "done", "finish",
            "skip", "skip for now", "skip intro", "maybe later", "later", "not now", "no thanks",
            "dismiss", "close");
    /** Content-desc patterns used by {@link #findBlocker} to dismiss generic overlay buttons. */
    private static final List<String> CLOSE_DESCS = List.of("close", "dismiss", "close ad", "skip ad");
    /** Extended content-desc patterns for {@link #waitForAdCloseButton} — covers all ad types. */
    private static final List<String> AD_CLOSE_DESCS = List.of(
            "close", "close ad", "close button", "dismiss ad", "skip ad",
            "skip", "dismiss", "×", "x");

    private static final Pattern BOUNDS = Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");

    /** Parsed UI node with the attributes the crawler needs. {@code nodePackage} is the REAL owning
     *  package from the uiautomator dump's own {@code package} attribute — not assumed to be the
     *  app under test — so foreign content briefly on-screen (a system overlay, or another app
     *  fleetingly in the foreground before recovery) is never mistaken for the tested app's own UI. */
    private record Node(String className, String resourceId, String text, String desc,
                        boolean clickable, boolean enabled, boolean scrollable, boolean longClickable,
                        int l, int t, int r, int b, String nodePackage) {
        int cx() { return (l + r) / 2; }
        int cy() { return (t + b) / 2; }
        String key() { return className + "#" + resourceId + "@" + l + "," + t; }
        String label() { return notBlank(text) ? text : (notBlank(desc) ? desc : ""); }
        static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    }

    public static Result explore(AdbClient adb, String serial, String pkg, File runDir, int maxSteps, int w, int h,
                                 java.util.function.BooleanSupplier stop) {
        return explore(adb, serial, pkg, runDir, maxSteps, w, h, stop, 0L, null, false);
    }

    public static Result explore(AdbClient adb, String serial, String pkg, File runDir, int maxSteps, int w, int h,
                                 java.util.function.BooleanSupplier stop, long dwellMs, java.util.function.Consumer<String> log) {
        return explore(adb, serial, pkg, runDir, maxSteps, w, h, stop, dwellMs, log, false);
    }

    /**
     * @param dwellMs       extra settle time after reaching each new screen (0 = fast localization crawl).
     * @param log           optional progress logger; may be null.
     * @param skipAdClicks  when true, ad content nodes are NEVER tapped; ads are closed only via their
     *                      Close (X) button. Always {@code true} from the Localization module.
     */
    public static Result explore(AdbClient adb, String serial, String pkg, File runDir, int maxSteps, int w, int h,
                                 java.util.function.BooleanSupplier stop, long dwellMs, java.util.function.Consumer<String> log,
                                 boolean skipAdClicks) {
        List<ScreenCapture> screens = new ArrayList<>();
        Set<String> seenScreens = new LinkedHashSet<>();
        Set<String> tapped      = new HashSet<>();
        Set<String> backedFrom  = new HashSet<>();   // sigs from which Back was already pressed once
        boolean crash = false, leftApp = false;
        int idx = 0, noNew = 0, actions = 0, relaunches = 0;
        final int MAX_RELAUNCH = 4;
        Path shotDir = runDir.toPath().resolve("screenshots");
        try { Files.createDirectories(shotDir); } catch (Exception ignored) {}

        // ── Phase A — complete the INITIAL FLOW and reach the Home screen ──
        // Splash → language-confirm → onboarding/intro slides → permission prompts. This phase is
        // strictly forward-only: it taps advance/permission/skip buttons and swipes through intro
        // carousels, but NEVER presses BACK, so the app cannot be exited before testing begins.
        // If the app drops out (its own splash finishing, etc.) we relaunch and keep going.
        String lastSig = ""; int settle = 0;
        for (int i = 0; i < 40 && settle < 3; i++) {
            if (stop != null && stop.getAsBoolean()) return new Result(screens, crash, leftApp, actions);
            String fg = safe(() -> adb.currentForegroundPackage(serial));
            if (fg != null && !fg.isBlank() && !fg.contains(pkg)) {
                if (isPermissionPrompt(fg) && handleForeignBlocker(adb, serial)) { actions++; sleep(800); continue; } // system permission dialog only
                if (!adb.isAppRunning(serial, pkg)) {
                    if (relaunches++ >= MAX_RELAUNCH) { leftApp = true; break; }
                    adb.launchApp(serial, pkg); sleep(2500); continue;     // relaunch toward Home
                }
                // App alive but a foreign, unrecognized app has the foreground — actively recover
                // rather than passively waiting (a foreign app is not guaranteed to yield on its
                // own, and a passive wait here was observed to leave the crawl stuck indefinitely).
                bringAppToForeground(adb, serial, pkg, fg);
                actions++; continue;
            }
            List<Node> nodes = parse(adb.uiDump(serial));
            String sig = signature(nodes);
            // Ad overlay check comes FIRST — before findBlocker — so that ad content buttons
            // (e.g. "Continue to Install", "Next", "Start") are never mistaken for onboarding
            // advance buttons and tapped. Only the Close/X button is tapped to dismiss the ad.
            if (isAnyAdScreen(nodes)) {
                if (log != null) log.accept("Ad detected in initial flow — waiting for Close button…");
                Node adClose = waitForAdCloseButton(adb, serial, 8000);
                if (adClose != null) {
                    if (log != null) log.accept("Ad dismissed (initial flow).");
                    adb.tap(serial, adClose.cx(), adClose.cy()); actions++; sleep(1000); settle = 0; lastSig = sig; continue;
                }
                adb.pressBack(serial); actions++; sleep(800); continue;
            }
            Node adv = findBlocker(nodes);                                  // forward / permission / skip button
            if (adv != null) {
                // Never tap ad content even in Phase A — only dismiss via close/back above.
                if (skipAdClicks && isAdNode(adv)) { sleep(500); continue; }
                adb.tap(serial, adv.cx(), adv.cy()); actions++; sleep(1100); settle = 0; lastSig = sig; continue;
            }
            if (looksLikeCarousel(nodes) && w > 0 && h > 0) {               // intro pager with no button yet
                swipeLeft(adb, serial, w, h); actions++; sleep(900);
                settle = sig.equals(lastSig) ? settle + 1 : 0; lastSig = sig; continue;
            }
            // No advance control and the screen is steady → Home/content reached.
            settle = sig.equals(lastSig) ? settle + 1 : 0; lastSig = sig; sleep(500);
        }

        // ── Phase B — explore the whole app from Home ──
        // BACK is used to backtrack between branches; if it ever exits the app we relaunch and keep
        // going (capped) instead of ending the crawl, so coverage is not cut short.
        int foreignRecoveries = 0;                 // consecutive foreign-app takeovers recovered from
        final int MAX_FOREIGN_RECOVERIES = 8;      // some apps (e.g. an App-Distribution build) keep
                                                   // re-launching a companion app; give up rather than
                                                   // bounce forever, so we never spend the whole crawl
                                                   // fighting to stay inside the app under test.
        for (int step = 0; step < maxSteps && noNew < 8; step++) {
            if (stop != null && stop.getAsBoolean()) break;   // user stopped — return screens collected so far
            String fg = safe(() -> adb.currentForegroundPackage(serial));
            if (fg != null && !fg.isBlank() && !fg.contains(pkg)) {
                if (isPermissionPrompt(fg) && handleForeignBlocker(adb, serial)) { actions++; sleep(700); continue; }
                if (++foreignRecoveries > MAX_FOREIGN_RECOVERIES) {
                    leftApp = true;
                    if (log != null) log.accept("App under test keeps yielding the foreground to '" + fg
                            + "' after " + foreignRecoveries + " recoveries — stopping to avoid churning in other apps.");
                    break;
                }
                if (!adb.isAppRunning(serial, pkg)) {
                    leftApp = true;
                    if (relaunches++ >= MAX_RELAUNCH) break;
                    adb.launchApp(serial, pkg); sleep(2200); continue;
                }
                // Foreign app in foreground (Play Store / browser / system screen — possibly triggered
                // by an app-deep-link, NOT by an ad click since ad content is never tapped).
                // Return to the tested app immediately and continue.
                if (log != null) log.accept("Foreign app in foreground — returning to tested app.");
                bringAppToForeground(adb, serial, pkg, fg);
                actions++; continue;
            }
            foreignRecoveries = 0;   // back in-app — reset the consecutive-takeover counter

            List<Node> nodes = parse(adb.uiDump(serial));
            String sig = signature(nodes);
            if (seenScreens.add(sig)) {
                noNew = 0;
                String actName = currentActivity(adb, serial, pkg);
                if (log != null) log.accept("▶ Screen — " + toReadableName(actName.isBlank() ? "Screen " + (idx + 1) : actName));
                if (dwellMs > 0) sleep(dwellMs);   // let ads on this new screen load before capturing/acting
                String shot = capture(adb, serial, shotDir, idx);
                screens.add(new ScreenCapture(idx, sig, actName, toWidgets(nodes, pkg), shot, 0));
                idx++;
            } else {
                noNew++;
            }

            // 1) Ad handling ALWAYS takes priority — check BEFORE any blocker/content logic.
            //    When skipAdClicks=true (Localization/Compatibility), ad content is NEVER tapped
            //    on any visit — the close button or Back is used every time the ad screen appears.
            //    When skipAdClicks=false (Ads module), the first visit waits for the close button
            //    and taps it; subsequent visits to the same sig fall through so the full ad
            //    lifecycle (click, browser return, impression) can be exercised.
            if (isAnyAdScreen(nodes)) {
                boolean firstVisit = tapped.add("AD_WAIT:" + sig);
                if (firstVisit || skipAdClicks) {
                    // Wait up to 8 s for the Close/Skip button (covers the standard 5-second countdown),
                    // then press Back as a last resort. Ad content is NEVER tapped.
                    if (firstVisit) {
                        if (log != null) log.accept("Ad detected — waiting up to 8 s for Close button…");
                    }
                    Node adClose = waitForAdCloseButton(adb, serial, skipAdClicks ? 6000 : 8000);
                    if (adClose != null) {
                        if (log != null) log.accept("Ad dismissed — continuing test.");
                        adb.tap(serial, adClose.cx(), adClose.cy()); actions++; sleep(900);
                    } else {
                        if (log != null) log.accept("Ad close button not found — pressing Back.");
                        adb.pressBack(serial); actions++; sleep(800);
                    }
                }
                // Always skip to next iteration — never let ad-screen nodes reach findBlocker/nextUnexplored.
                continue;
            }
            // 1b) Exit dialog detection — must run BEFORE findBlocker and nextUnexplored so that
            //     exit-confirmation buttons ("Exit", "Yes") are never tapped as unexplored elements.
            //     Dismiss unconditionally via the cancel/stay button or Back; never confirm an exit.
            if (isExitDialog(nodes)) {
                if (log != null) log.accept("Exit dialog detected — dismissing.");
                Node dismiss = findExitDismissButton(nodes);
                if (dismiss != null) {
                    adb.tap(serial, dismiss.cx(), dismiss.cy()); actions++; sleep(600);
                } else {
                    adb.pressBack(serial); actions++; sleep(600);
                }
                continue;
            }
            // 2) Dismiss/advance any non-ad blocker (dialogs, permissions, onboarding).
            Node blocker = findBlocker(nodes);
            if (blocker != null && tapped.add("B:" + sig + ":" + blocker.key())) {
                if (skipAdClicks && isAdNode(blocker)) {
                    // Safety net: blocker matched an ad element not caught by isAnyAdScreen().
                    if (log != null) log.accept("Click blocked — ad element skipped [" + shortLabel(blocker) + "]");
                    continue;
                }
                adb.tap(serial, blocker.cx(), blocker.cy()); actions++; sleep(900);
                continue;
            }
            // 3) Otherwise tap the next unexplored interactive element to reach a new screen.
            Node next = nextUnexplored(nodes, tapped, sig, skipAdClicks, log);
            if (next != null) {
                tapped.add(sig + ":" + next.key());
                adb.tap(serial, next.cx(), next.cy()); actions++; sleep(900);
                // A text input only reveals itself as a real "feature" once something is typed
                // into it — tapping alone just focuses it. Type a generic sample so forms/search
                // bars are actually exercised (and any hint/placeholder text gets a chance to
                // surface a translation issue too), not just navigated past.
                if (isEditableNode(next)) {
                    typeSample(adb, serial);
                    sleep(400);
                }
                continue;
            }
            // 4) Nothing new to tap — backtrack.
            //    Guard: only press Back from a given sig ONCE. Re-backing from the same sig (e.g.
            //    Home) would retrigger the exit dialog on every iteration, creating an infinite loop.
            //    After the first Back we let noNew climb naturally until the crawl budget expires.
            if (backedFrom.add(sig)) {
                adb.pressBack(serial); actions++; sleep(700);
                String fg2 = safe(() -> adb.currentForegroundPackage(serial));
                if (fg2 != null && !fg2.isBlank() && !fg2.contains(pkg)) {
                    if (!adb.isAppRunning(serial, pkg)) {
                        leftApp = true;
                        if (relaunches++ >= MAX_RELAUNCH) break;
                        adb.launchApp(serial, pkg); sleep(2200);
                    }
                }
            } else {
                // Already backed from this sig — pressing Back again would re-trigger exit dialog.
                noNew++;
            }
        }
        return new Result(screens, crash, leftApp, actions);
    }

    /** Outcome of {@link #findLanguageScreen}. */
    public record LanguageScreenSearchResult(boolean found, List<InAppLanguageSelector.Lang> languages,
                                             int screensVisited) {}

    // Generic (not app-specific) hint that a control likely leads to a language screen — used only
    // to try promising controls FIRST so the search converges quickly on the common case (a
    // "Language" row in a menu/Settings screen); the search still falls through to plain
    // breadth-first exploration of every other control if these hints are absent or don't pan out,
    // so an app with no such labeled entry point is still fully searched.
    private static final Pattern LANGUAGE_HINT_PAT = Pattern.compile(
            "\\b(language|languages|idioma|langue|sprache|lingua|taal|idiomas)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SETTINGS_HINT_PAT = Pattern.compile(
            "\\b(settings|setting|preferences|options|more|menu|profile|account)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Searches the ENTIRE app for an in-app Language Selection screen — a screen offering ≥2
     * recognized language choices — when it isn't already showing at launch (e.g. it lives inside
     * a Settings/menu screen instead of appearing on first run). Drives the live app exactly like
     * {@link #explore}: dismisses ads (never taps ad content), dismisses exit-confirmation dialogs
     * without exiting, advances onboarding/permission blockers, and otherwise performs a breadth-
     * first walk of every reachable control, backtracking when a branch is exhausted. Purely
     * generic — no app-specific package, activity, or label names.
     *
     * <p>Every screen is checked for the language picker BEFORE anything else happens on it, so the
     * app is left sitting exactly on that screen (scroll position aside) the moment it's found —
     * the caller can immediately proceed with {@link InAppLanguageSelector#scanAllLanguages}.
     *
     * @param maxSteps search budget; a generous but bounded search of "the entire app" — most apps
     *                 that have a language screen at all reach it well within this budget.
     */
    public static LanguageScreenSearchResult findLanguageScreen(AdbClient adb, String serial, String pkg,
            Map<String, String> dict, int w, int h, int maxSteps,
            java.util.function.BooleanSupplier stop, java.util.function.Consumer<String> log) {
        Set<String> tapped = new HashSet<>();
        Set<String> seenScreens = new LinkedHashSet<>();
        Set<String> backedFrom = new HashSet<>();
        int relaunches = 0, noNew = 0, visited = 0;
        final int MAX_RELAUNCH = 4;

        for (int step = 0; step < maxSteps && noNew < 10; step++) {
            if (stop != null && stop.getAsBoolean()) break;
            String fg = safe(() -> adb.currentForegroundPackage(serial));
            if (fg != null && !fg.isBlank() && !fg.contains(pkg)) {
                if (isPermissionPrompt(fg) && handleForeignBlocker(adb, serial)) { sleep(700); continue; }
                if (!adb.isAppRunning(serial, pkg)) {
                    if (relaunches++ >= MAX_RELAUNCH) break;
                    adb.launchApp(serial, pkg); sleep(2200); continue;
                }
                bringAppToForeground(adb, serial, pkg, fg);
                continue;
            }

            String xml = adb.uiDump(serial);
            // Check for the language screen BEFORE any other handling on this screen.
            List<InAppLanguageSelector.Lang> langs = InAppLanguageSelector.detectLanguages(xml, dict);
            if (langs.size() >= 2) {
                if (log != null) log.accept("Language Selection screen found after searching "
                        + visited + " screen(s) — " + langs.size() + " language(s) detected.");
                return new LanguageScreenSearchResult(true, langs, visited);
            }

            List<Node> nodes = parse(xml);
            String sig = signature(nodes);
            if (seenScreens.add(sig)) { noNew = 0; visited++; } else { noNew++; }

            if (isAnyAdScreen(nodes)) {
                Node adClose = waitForAdCloseButton(adb, serial, 4000);
                if (adClose != null) { adb.tap(serial, adClose.cx(), adClose.cy()); sleep(800); }
                else { adb.pressBack(serial); sleep(700); }
                continue;
            }
            if (isExitDialog(nodes)) {
                Node dismiss = findExitDismissButton(nodes);
                if (dismiss != null) adb.tap(serial, dismiss.cx(), dismiss.cy());
                else adb.pressBack(serial);
                sleep(600);
                continue;
            }
            Node blocker = findBlocker(nodes);
            if (blocker != null && tapped.add("B:" + sig + ":" + blocker.key())) {
                if (isAdNode(blocker)) continue; // safety net; never tap ad content
                adb.tap(serial, blocker.cx(), blocker.cy()); sleep(900);
                continue;
            }

            // Try a "Language"/"Settings"-hinted control first so the common case (a Language row
            // inside a menu/Settings screen) is found fast, before falling back to plain BFS of
            // whatever else is on screen.
            Node next = findHintedUnexplored(nodes, tapped, sig, LANGUAGE_HINT_PAT);
            if (next == null) next = findHintedUnexplored(nodes, tapped, sig, SETTINGS_HINT_PAT);
            if (next == null) next = nextUnexplored(nodes, tapped, sig, true, log);
            if (next != null) {
                tapped.add(sig + ":" + next.key());
                adb.tap(serial, next.cx(), next.cy()); sleep(900);
                continue;
            }

            // Nothing new to tap — backtrack (once per sig, same anti-loop guard as explore()).
            if (backedFrom.add(sig)) {
                adb.pressBack(serial); sleep(700);
            } else {
                noNew++;
            }
        }
        if (log != null) log.accept("Searched " + visited + " screen(s) — no in-app Language "
                + "Selection screen found.");
        return new LanguageScreenSearchResult(false, List.of(), visited);
    }

    /** Like {@link #nextUnexplored} but restricted to controls whose label/resource-id match
     *  {@code hint} — used to try promising controls (e.g. "Language", "Settings") first. */
    private static Node findHintedUnexplored(List<Node> nodes, Set<String> tapped, String sig, Pattern hint) {
        for (Node n : nodes) {
            if (!n.enabled() || !(n.clickable() || n.longClickable())) continue;
            if (n.r() - n.l() <= 0 || n.b() - n.t() <= 0) continue;
            if (tapped.contains(sig + ":" + n.key())) continue;
            if (isExitIntentNode(n) || isPurchaseNode(n)) continue;
            String label = (n.label() == null ? "" : n.label()) + " " + (n.resourceId() == null ? "" : n.resourceId());
            if (hint.matcher(label).find()) return n;
        }
        return null;
    }

    /** Heuristic: the current screen is an intro/onboarding carousel that advances by swiping. */
    private static boolean looksLikeCarousel(List<Node> nodes) {
        for (Node n : nodes) {
            String c = n.className() == null ? "" : n.className().toLowerCase();
            String id = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
            if (c.contains("viewpager") || c.contains("pager")) return true;
            if (id.contains("pager") || id.contains("indicator") || id.contains("onboard")
                    || id.contains("intro") || id.contains("slide") || id.contains("walkthrough")
                    || id.contains("tutorial")) return true;
        }
        return false;
    }

    /** Swipe right-to-left to advance an intro carousel to the next page. */
    private static void swipeLeft(AdbClient adb, String serial, int w, int h) {
        int y = h / 2, x1 = (int) (w * 0.85), x2 = (int) (w * 0.15);
        adb.shell(serial, 10, "input", "swipe", String.valueOf(x1), String.valueOf(y),
                String.valueOf(x2), String.valueOf(y), "400");
    }

    /* ---- blocker handling ------------------------------------------------- */

    private static Node findBlocker(List<Node> nodes) {
        // Match by priority order so permission "Allow"/onboarding "Next" win over generic "Close".
        // A purchase control is never returned as a blocker/advance button — e.g. a paywall's
        // "Continue" that actually buys.
        for (String want : BLOCKER_BUTTONS) {
            for (Node n : nodes) {
                if (!n.clickable() && !looksLikeButton(n)) continue;
                if (isPurchaseNode(n)) continue;
                String txt = norm(n.label());
                if (txt.equals(want)) return n;
            }
        }
        for (Node n : nodes) {
            if (isPurchaseNode(n)) continue;
            String d = norm(n.desc());
            if (CLOSE_DESCS.contains(d) && (n.clickable() || looksLikeButton(n))) return n;
        }
        // Compound advance buttons: "Continue in English →", "Get started!", "Let's go →", etc.
        // These are not exact matches but start with a known advance verb.
        for (Node n : nodes) {
            if (!n.clickable() && !looksLikeButton(n)) continue;
            if (isPurchaseNode(n)) continue;
            String txt = norm(n.label());
            if ((txt.startsWith("continue") || txt.startsWith("get started")
                    || txt.startsWith("let's go") || txt.startsWith("lets go"))
                    && txt.length() < 80) return n;
        }
        return null;
    }

    /**
     * True when the current screen is an exit-confirmation dialog — the kind that appears when
     * Back is pressed from the Home screen ("Do you want to exit?", "Exit app?", etc.).
     * Detected by the presence of an explicit exit-intent label in any node's text or resource-id.
     */
    private static boolean isExitDialog(List<Node> nodes) {
        for (Node n : nodes) {
            String txt = norm(n.label());
            String id  = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
            if (id.contains("exit_dialog") || id.contains("quit_dialog")
                    || id.contains("exit_confirm") || id.contains("close_app_dialog")) return true;
            if (txt.equals("exit app") || txt.equals("quit app") || txt.equals("close app")
                    || txt.contains("do you want to exit") || txt.contains("exit the app")
                    || txt.contains("do you want to quit") || txt.contains("quit the app")
                    || txt.contains("are you sure you want to exit")
                    || txt.contains("are you sure you want to quit")) return true;
        }
        return false;
    }

    /**
     * Find the safest button to dismiss an exit dialog — prefer cancel/stay over exit/yes.
     * Returns null when no safe button is found (caller should press Back instead).
     */
    private static Node findExitDismissButton(List<Node> nodes) {
        for (String want : List.of("cancel", "no", "stay", "stay in app", "don't exit",
                                   "keep", "no thanks", "back to app")) {
            for (Node n : nodes) {
                if (!n.clickable() && !looksLikeButton(n)) continue;
                if (norm(n.label()).equals(want)) return n;
            }
        }
        return null;
    }

    /** True when a node represents an exit/quit action that must never be tapped during exploration. */
    private static boolean isExitIntentNode(Node n) {
        String txt = norm(n.label());
        String id  = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
        return txt.equals("exit") || txt.equals("exit app") || txt.equals("quit")
                || txt.equals("quit app") || txt.equals("close app")
                || id.contains("btn_exit") || id.contains("btn_quit")
                || id.contains("exit_btn") || id.contains("quit_btn");
    }

    /**
     * True only for the OS runtime-permission prompt (the grant/deny dialog), where tapping an
     * Allow/While-using button is the correct, expected action. Deliberately narrow: it must NOT
     * match Settings, Chrome, the Play Store, Gallery, Camera, or any other app — those are foreign
     * surfaces we return from, never tap into. Generic (matches the OS permission UI by name), no
     * app-specific package.
     */
    private static boolean isPermissionPrompt(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("permissioncontroller") || p.contains("packageinstaller") || p.contains("permission");
    }

    private static boolean handleForeignBlocker(AdbClient adb, String serial) {
        // System permission dialogs etc. live in another package — tap an allow/ok button.
        List<Node> nodes = parse(adb.uiDump(serial));
        Node b = findBlocker(nodes);
        if (b != null) { adb.tap(serial, b.cx(), b.cy()); return true; }
        return false;
    }

    /**
     * Brings the tested app back to the foreground after a foreign app took over — e.g. an
     * app-fired ad click-through, a "Rate us"/share deep link, or a bundled SDK handing off to
     * some other installed app. Any foreign app the shared {@link ForeignAppPolicy} flags (a
     * browser, the Play Store, Settings, Gallery, a file picker, any other installed app) is
     * force-stopped OUTRIGHT and the tested app relaunched on top — never a Back into it, since a
     * Back inside a browser/Store just navigates within that app and briefly uses it. Protected
     * system UI (launcher/SystemUI) is never killed — we simply relaunch the app over it. Genuine
     * permission dialogs never reach here (the caller handles them via {@link #handleForeignBlocker}).
     */
    private static void bringAppToForeground(AdbClient adb, String serial, String pkg, String foreignPkg) {
        if (ForeignAppPolicy.shouldForceStopForeign(foreignPkg, pkg)) {
            try { adb.forceStop(serial, foreignPkg); } catch (Exception ignored) {}
            sleep(400);
        }
        adb.launchApp(serial, pkg); sleep(2000);
    }

    private static boolean looksLikeButton(Node n) {
        String c = n.className() == null ? "" : n.className().toLowerCase();
        return c.contains("button") || n.clickable();
    }

    /**
     * True if the screen is dominated by an ad overlay of any type:
     * Interstitial, Rewarded, Rewarded Interstitial, App Open, Banner (full-screen), Native.
     * Detected via AdMob/ad-SDK resource-id patterns and known test-ad text markers.
     */
    private static boolean isAnyAdScreen(List<Node> nodes) {
        for (Node n : nodes) {
            String id  = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
            String cls = n.className()   == null ? "" : n.className().toLowerCase();
            String txt = n.text()        == null ? "" : n.text().toLowerCase();
            String dsc = n.desc()        == null ? "" : n.desc().toLowerCase();
            // Google Mobile Services (GMS) resource-id prefix — any GMS element means AdMob is active.
            // GMS elements never appear in regular app content, so this has zero false-positive risk.
            if (id.startsWith("com.google.android.gms") || id.startsWith("com.google.ads")) return true;
            // Interstitial / Rewarded / App Open ad resource-id patterns (AdMob SDK)
            if (id.contains("interstitial") || id.contains("rewarded_ad")
                    || id.contains("app_open_ad") || id.contains("admob_ad")
                    || id.contains("ad_frame") || id.contains("ad_overlay")) return true;
            // Presence of a dedicated ad close/skip/countdown button → a full-screen overlay is showing.
            // These resource-ids are specific to ad SDKs and do not appear in normal app dialogs.
            if (id.contains("interstitial_close") || id.contains("rewarded_close")
                    || id.contains("ad_close") || id.contains("close_ad")
                    || id.contains("skip_button") || id.contains("ad_countdown")
                    || id.contains("countdown_timer")) return true;
            // Ad SDK view classes (native / unified ad containers)
            if (cls.contains("nativeadview") || cls.contains("unifiedadview")
                    || cls.contains("adview") || cls.contains("adiconview")
                    || cls.contains("mediaview") || cls.contains("adchoicesview")) return true;
            // Test-ad text markers (development / test builds using AdMob test ad IDs)
            if (txt.contains("test ad") || txt.contains("admob test")
                    || txt.contains("this is a test ad") || txt.contains("sample ad")) return true;
            // Content-desc signals
            if (dsc.equals("advertisement") || dsc.contains("close ad") || dsc.contains("skip ad")) return true;
        }
        return false;
    }

    /**
     * Poll the UI waiting for an ad close/dismiss/skip button to become tappable.
     * Covers all ad types: Interstitial, Rewarded (skip after countdown), App Open, Banner, Native.
     * Polls every 800 ms so the button is found promptly after the typical 5-second countdown.
     *
     * @param maxWaitMs maximum total wait time (use 8 000 ms to cover the 5-second countdown + buffer)
     */
    private static Node waitForAdCloseButton(AdbClient adb, String serial, long maxWaitMs) {
        long end = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < end) {
            List<Node> n2 = parse(adb.uiDump(serial));
            for (Node n : n2) {
                // Do NOT filter on clickable=true. AdMob and most ad SDK close buttons are
                // custom touch targets whose XML dump has clickable="false", but tapping their
                // centre coordinates always works. Filtering on clickable would cause an 8-second
                // timeout on every real ad, after which Back is pressed instead of the Close button.
                String id  = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
                String d   = norm(n.desc());
                String txt = norm(n.label());
                // Google Mobile Services (AdMob SDK) close/skip/countdown patterns
                if (id.contains("com.google.android.gms") && (id.contains("close") || id.contains("skip")
                        || id.contains("countdown") || id.contains("dismiss"))) return n;
                // Generic ad-SDK close button resource-id patterns
                if (id.contains("interstitial_close") || id.contains("rewarded_close")
                        || id.contains("close_button") || id.contains("ad_close")
                        || id.contains("dismiss_button") || id.contains("skip_button")
                        || id.contains("close_ad") || id.contains("btn_close")
                        || id.contains("iv_close") || id.contains("img_close")) return n;
                // Content-desc based (× icons, "Close Ad", "Skip Ad", "Dismiss")
                if (AD_CLOSE_DESCS.contains(d)) return n;
                // Text-based fallback
                if (txt.equals("close") || txt.equals("×") || txt.equals("x")
                        || txt.equals("dismiss") || txt.equals("skip")) return n;
            }
            sleep(800); // poll every 800 ms — button typically appears after ~5 s countdown
        }
        return null;
    }

    /** True for a plain text-input control (EditText and common subclasses) — generic Android
     *  class-name check, no app-specific resource-ids. */
    private static boolean isEditableNode(Node n) {
        String c = n.className() == null ? "" : n.className().toLowerCase();
        return c.contains("edittext") || c.contains("autocompletetextview");
    }

    /** Types a short, generic, non-PII sample string into whatever field is currently focused. */
    private static void typeSample(AdbClient adb, String serial) {
        adb.shell(serial, 10, "input", "text", "Test123");
    }

    private static Node nextUnexplored(List<Node> nodes, Set<String> tapped, String sig,
                                       boolean skipAdClicks, java.util.function.Consumer<String> log) {
        for (Node n : nodes) {
            if (!n.enabled()) continue;
            if (!(n.clickable() || n.longClickable())) continue;
            if (n.r() - n.l() <= 0 || n.b() - n.t() <= 0) continue;
            if (tapped.contains(sig + ":" + n.key())) continue;
            // Never tap exit/quit buttons — they close the app and end the test run early.
            if (isExitIntentNode(n)) {
                tapped.add(sig + ":" + n.key()); // mark so it is not logged repeatedly
                continue;
            }
            if (skipAdClicks && isAdNode(n)) {
                if (log != null) log.accept("Click blocked — ad element skipped [" + shortLabel(n) + "]");
                tapped.add(sig + ":" + n.key());
                continue;
            }
            // Never tap a buy/subscribe/checkout/pay control — completing a real-money in-app
            // purchase must never happen during exploration (applies in every locale via the
            // shared, language-independent matcher).
            if (isPurchaseNode(n)) {
                if (log != null) log.accept("Click blocked — in-app purchase control skipped ["
                        + shortLabel(n) + "]");
                tapped.add(sig + ":" + n.key());
                continue;
            }
            return n;
        }
        return null;
    }

    /** True if this node would complete/advance a real-money in-app purchase. Delegates to the
     *  shared, language-independent matcher so this ADB-fallback crawler is as purchase-safe as the
     *  Appium path. */
    private static boolean isPurchaseNode(Node n) {
        String label = n.label();
        if ((label == null || label.isBlank()) && n.text() != null) label = n.text();
        if ((label == null || label.isBlank()) && n.desc() != null) label = n.desc();
        return com.vasundhara.atf.engine.ExplorationEngine.isPurchaseText(label, n.resourceId());
    }

    private static String shortLabel(Node n) {
        String cls = n.className() == null ? "" : n.className();
        int dot = cls.lastIndexOf('.');
        if (dot >= 0) cls = cls.substring(dot + 1);
        String id = n.resourceId() == null ? "" : n.resourceId();
        int slash = id.indexOf('/');
        if (slash >= 0) id = id.substring(slash + 1);
        String lbl = n.label();
        return cls + (id.isBlank() ? "" : "#" + id) + (lbl.isBlank() ? "" : " \"" + lbl + "\"");
    }

    /**
     * Returns true if the node is part of ad content that must NEVER be tapped.
     * Covers all standard AdMob ad types: Banner, Interstitial, Rewarded,
     * Rewarded Interstitial, App Open, and Native ads.
     * Close/dismiss/skip buttons are intentionally excluded so they can still be tapped.
     */
    private static boolean isAdNode(Node n) {
        String cls  = n.className()  == null ? "" : n.className().toLowerCase();
        String id   = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
        String desc = n.desc()       == null ? "" : n.desc().toLowerCase();
        String txt  = n.text()       == null ? "" : n.text().toLowerCase();
        // Google Mobile Services resource-id prefix — any GMS interactive element is ad-related.
        if (id.startsWith("com.google.android.gms")) return true;
        // Class-based: AdMob view classes
        if (cls.contains("adview") || cls.contains("nativeadview") || cls.contains("adiconview")
                || cls.contains("mediaview") || cls.contains("adchoicesview")
                || cls.contains("unifiedadview")) return true;
        // Resource-id: banner, interstitial, rewarded, app-open, native ad containers
        if (id.contains("adview") || id.contains("ad_view") || id.contains("banner_ad")
                || id.contains("ad_banner") || id.contains("ad_container")
                || id.contains("admob") || id.contains("native_ad")
                || id.contains("rewarded_ad") || id.contains("app_open_ad")
                || id.contains("ad_frame") || id.contains("ad_overlay")) return true;
        // Content-desc / text markers (partial match for "test ad" variants)
        if (desc.equals("advertisement") || desc.contains("sponsored")
                || desc.contains("close ad") || desc.contains("skip ad")
                || desc.contains("test ad") || desc.contains("admob")) return true;
        if (txt.equals("advertisement") || txt.equals("sponsored")
                || txt.contains("test ad") || txt.contains("admob test")) return true;
        // Common ad CTA labels that appear inside ad creatives — tapping these opens the
        // advertiser URL or Play Store, which is never appropriate during Localization Testing.
        if (!txt.isBlank() && (txt.equals("learn more") || txt.equals("install now")
                || txt.equals("install") || txt.equals("visit website")
                || txt.equals("shop now") || txt.equals("get offer")
                || txt.equals("download") || txt.equals("download now")
                || txt.equals("sign up") || txt.equals("subscribe")
                || txt.equals("open") || txt.equals("get it now")
                || txt.equals("play now") || txt.equals("try now"))) return true;
        return false;
    }

    /* ---- parsing / helpers ------------------------------------------------ */

    private static List<Node> parse(String xml) {
        List<Node> out = new ArrayList<>();
        if (xml == null) return out;
        for (String chunk : xml.split("(?=<node\\b)")) {
            if (!chunk.startsWith("<node")) continue;
            Matcher m = BOUNDS.matcher(chunk);
            if (!m.find()) continue;
            out.add(new Node(
                    attr(chunk, "class"), attr(chunk, "resource-id"),
                    unescape(attr(chunk, "text")), unescape(attr(chunk, "content-desc")),
                    "true".equals(attr(chunk, "clickable")), !"false".equals(attr(chunk, "enabled")),
                    "true".equals(attr(chunk, "scrollable")), "true".equals(attr(chunk, "long-clickable")),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    attr(chunk, "package")));
        }
        return out;
    }

    private static List<Widget> toWidgets(List<Node> nodes, String pkg) {
        List<Widget> ws = new ArrayList<>();
        for (Node n : nodes) {
            // Ad nodes are never included in the Widget list so LocalizationAnalyzer never
            // sees ad text — banner or native ad widgets on an otherwise-normal app screen
            // must not be validated for translations or layout issues.
            if (isAdNode(n)) continue;
            // A node's REAL owning package comes from the uiautomator dump's own "package"
            // attribute, never assumed. A screen captured while a foreign app briefly held the
            // foreground (a system overlay, or another app's window before recovery kicked in)
            // can mix in nodes from that other package — those must never be validated as this
            // app's own translation/UI content, or a completely unrelated app's screen gets
            // reported as a localization/UI defect of the app under test.
            String np = n.nodePackage();
            if (np != null && !np.isBlank() && !np.equals(pkg)) continue;
            ws.add(new Widget(n.className(), n.resourceId(), n.text(), n.desc(), "",
                    n.clickable(), n.longClickable(), n.scrollable(), false, n.enabled(), true,
                    n.l(), n.t(), n.r() - n.l(), n.b() - n.t(), pkg));
        }
        return ws;
    }

    /** Stable per-screen signature from the structure (classes + resource ids), order-independent. */
    private static String signature(List<Node> nodes) {
        Set<String> parts = new java.util.TreeSet<>();
        for (Node n : nodes) parts.add(n.className() + "|" + n.resourceId());
        return Integer.toHexString(parts.toString().hashCode()) + ":" + nodes.size();
    }

    /** Convert an activity/fragment class name to a human-readable screen label. */
    static String toReadableName(String className) {
        if (className == null || className.isBlank()) return className == null ? "Unknown Screen" : className;
        String s = className.startsWith(".") ? className.substring(1) : className;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        for (String sfx : new String[]{"Activity", "Fragment", "Screen", "Page", "View"}) {
            if (s.endsWith(sfx) && s.length() > sfx.length()) { s = s.substring(0, s.length() - sfx.length()); break; }
        }
        s = s.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2").trim();
        return s.isEmpty() ? className : s;
    }

    private static String currentActivity(AdbClient adb, String serial, String pkg) {
        String out = safe(() -> adb.shell(serial, 10, "dumpsys", "activity", "activities").stdout());
        if (out == null) return "";
        Matcher m = Pattern.compile(Pattern.quote(pkg) + "/([\\w.$]+)").matcher(out);
        if (m.find()) {
            String a = m.group(1);
            int dot = a.lastIndexOf('.');
            return dot >= 0 ? a.substring(dot + 1) : a;
        }
        return "";
    }

    private static String capture(AdbClient adb, String serial, Path shotDir, int idx) {
        try {
            byte[] png = adb.screencapPng(serial);
            if (png != null && png.length > 0) {
                String fn = "screen-" + idx + ".png";
                Files.write(shotDir.resolve(fn), png);
                return "screenshots/" + fn;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String attr(String chunk, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(chunk);
        return m.find() ? m.group(1) : "";
    }

    private static String unescape(String s) {
        return s == null ? "" : s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private interface Sup { String get() throws Exception; }
    private static String safe(Sup s) { try { return s.get(); } catch (Exception e) { return null; } }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
