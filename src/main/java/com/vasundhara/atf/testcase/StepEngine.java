package com.vasundhara.atf.testcase;

import com.vasundhara.atf.device.AdbClient;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword-driven step interpreter + ADB executor.
 *
 * <p>Classifies a plain-English step description, extracts any target
 * element / value, then carries out the action via {@link AdbClient}.
 *
 * <p>Element resolution uses a multi-strategy chain:
 * <ol>
 *   <li>Exact / contains match on text, content-desc, resource-id (case-insensitive).</li>
 *   <li>Multi-word partial match (majority of words must appear in any attribute).</li>
 *   <li>Scroll down one page and retry both strategies.</li>
 *   <li>Scroll back to top and retry.</li>
 *   <li>Try the original action verb as the element label (e.g. "Allow", "Deny").</li>
 * </ol>
 */
@Component
public class StepEngine {

    // ── action enum ───────────────────────────────────────────────────────────

    public enum Action {
        LAUNCH_APP, RELAUNCH, BG_RESUME, NETWORK_ON, NETWORK_OFF,
        TAP, LONG_PRESS, TYPE, CLEAR, BACK, HOME,
        VERIFY_VISIBLE, VERIFY_NOT_VISIBLE, VERIFY_TEXT,
        SCROLL_DOWN, SCROLL_UP, SCROLL_LEFT, SCROLL_RIGHT,
        SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT,
        WAIT, NAVIGATE, SCREENSHOT, UNKNOWN
    }

    // ── keyword → action map (first match wins, so order matters) ─────────────

    private static final Map<Pattern, Action> KEYWORD_MAP = new LinkedHashMap<>();
    static {
        // BG_RESUME — before RELAUNCH: "background and reopen app" contains "reopen … app"
        // which would otherwise force-stop the app; backgrounding means Home + resume, keeping
        // the app's live state so "state restored without crash" cases are genuinely exercised.
        kw(Action.BG_RESUME,
                "background and (?:re-?)?open|send (?:the\\s+)?app to (?:the\\s+)?background|" +
                "minimi[sz]e and (?:re-?)?open|background the app|resume from background");

        // RELAUNCH — before TAP/LAUNCH_APP: "close and reopen" would otherwise match TAP's
        // \bclose\b (tries to tap an element called "and reopen"), and "reopen the app" would
        // match LAUNCH_APP, which does NOT force-stop first — the app would just resume in the
        // same state, so persistence test cases (e.g. "previously selected language persists")
        // would trivially "pass" without ever exercising a real cold restart.
        kw(Action.RELAUNCH,
                "re-?launch|re-?start(?:\\s+the)?\\s+app|close and (?:re-?)?open|" +
                "kill and (?:re-?)?open|re-?open(?:\\s+the)?\\s+app|" +
                "force.?stop and (?:re-?)?open|exit and (?:re-?)?open");

        // NETWORK — before TAP: "disable"/"enable"/"turn off" are TAP verbs, so "Disable
        // internet" used to be classified TAP and hunted for an element called "internet".
        kw(Action.NETWORK_OFF,
                "(?:disable|turn off|switch off|disconnect|deactivate)\\s+(?:the\\s+)?" +
                "(?:internet|wi-?fi|network|mobile data|data)|airplane mode on|go offline|" +
                "offline mode|internet lost|no internet|without internet");
        kw(Action.NETWORK_ON,
                "(?:enable|turn on|switch on|connect|reconnect|activate)\\s+(?:the\\s+)?" +
                "(?:internet|wi-?fi|network|mobile data|data)|airplane mode off|go online|" +
                "with internet|restore (?:the\\s+)?(?:internet|network|connection)");

        // LAUNCH_APP — requires "app" to avoid matching "open Home module"
        kw(Action.LAUNCH_APP,
                "launch(?:\\s+the)?\\s+app|open(?:\\s+the)?\\s+app|" +
                "start(?:\\s+the)?\\s+app|run(?:\\s+the)?\\s+app|" +
                "install(?:\\s+the)?\\s+app");

        // LONG_PRESS — before TAP to avoid "press" stealing it
        kw(Action.LONG_PRESS,
                "long.?press|long.?tap|press and hold|hold(?:\\s+down)?\\b");

        // BACK / HOME — before TAP: "press back"/"tap back" contain TAP verbs (\bpress\b, \btap\b)
        // and used to be classified TAP, which then hunted for an on-screen element literally
        // named "back" instead of pressing the hardware Back key. The leading negative lookahead
        // keeps verification sentences ("Verify home screen is visible", "Check the back button
        // appears") flowing through to the VERIFY_* patterns below instead of being stolen here.
        kw(Action.BACK,
                "^(?!.*\\b(?:verify|should|visible|displayed|shown|appears?|check)\\b).*?" +
                "(?:press back|go back|navigate back|back button|tap back|\\bback\\s+key\\b)");
        kw(Action.HOME,
                "^(?!.*\\b(?:verify|should|visible|displayed|shown|appears?|check)\\b).*?" +
                "(?:press home|go to home|home button|home screen|navigate home)");

        // TEXT INPUT
        kw(Action.TYPE,
                "\\btype\\b|\\benter\\b(?!.*permission)|\\binput\\b|\\bfill\\b|\\bwrite\\b|set text|\\binsert\\b");

        // CLEAR
        kw(Action.CLEAR,
                "\\bclear\\b(?!\\s+all\\s+permission)|\\berase\\b|delete the text|remove text");

        // TAP / CLICK — broad catch including natural language verbs
        kw(Action.TAP,
                "\\btap\\b|\\bclick\\b|\\bpress\\b|\\bselect\\b|\\btouch\\b|" +
                "turn on|turn off|\\benable\\b|\\bdisable\\b|\\btoggle\\b|" +
                "switch on|switch off|\\bactivate\\b|\\bdeactivate\\b|" +
                "\\ballow\\b|\\bgrant\\b|\\bdeny\\b|\\breject\\b|\\baccept\\b|\\bpermit\\b|" +
                "\\bopen\\b|\\bclose\\b|\\bdismiss\\b|\\bcancel\\b|\\bskip\\b|" +
                "\\bdone\\b|\\bsubmit\\b|\\bapply\\b|\\bsave\\b|\\bfinish\\b|" +
                "\\bexpand\\b|\\bcollapse\\b|\\baccess\\b|\\buse\\b");

        // VERIFY — not-visible before visible so "not" doesn't fall through
        kw(Action.VERIFY_NOT_VISIBLE,
                "not visible|not present|disappear|\\bhidden\\b|" +
                "should not (?:be )?(?:visible|shown|displayed|appear)|" +
                "must not (?:be )?visible");

        kw(Action.VERIFY_TEXT,
                "verify text|check text|text should|contains? text|assert text|" +
                "should (?:contain|equal|read|say)");

        kw(Action.VERIFY_VISIBLE,
                "verify(?:\\s+(?:that|if))?\\s+visible|\\bis visible\\b|" +
                "should (?:be )?(?:visible|shown|displayed|enabled|active|appear)|" +
                "must (?:be )?visible|status should|should show|should display|should appear|" +
                "\\bobserve\\b|\\bnotice\\b|\\bcheck\\b(?!\\s+text)|" +
                "\\bsee\\b|\\bappear\\b|\\bshown\\b|\\bpresent\\b|\\bdisplay\\b");

        // SCROLL
        kw(Action.SCROLL_DOWN,  "scroll down|scroll(?:\\s+to)?\\s+bottom");
        kw(Action.SCROLL_UP,    "scroll up|scroll(?:\\s+to)?\\s+top");
        kw(Action.SCROLL_LEFT,  "scroll left");
        kw(Action.SCROLL_RIGHT, "scroll right");

        // SWIPE
        kw(Action.SWIPE_UP,    "swipe up");
        kw(Action.SWIPE_DOWN,  "swipe down");
        kw(Action.SWIPE_LEFT,  "swipe left");
        kw(Action.SWIPE_RIGHT, "swipe right");

        // NAVIGATION (BACK/HOME are registered earlier, before TAP — see above)
        kw(Action.NAVIGATE,
                "navigate to|go to\\b|open (?:screen|page|module|section|activity)|open to");

        // WAIT
        kw(Action.WAIT, "\\bwait\\b|\\bsleep\\b|\\bpause\\b|\\bdelay\\b");

        // SCREENSHOT
        kw(Action.SCREENSHOT, "take screenshot|capture screen|screenshot");
    }

    private static void kw(Action a, String pattern) {
        KEYWORD_MAP.put(Pattern.compile("(?i)" + pattern), a);
    }

    // Verbs that are ALSO common Android button labels — if target extraction
    // removes them as a verb prefix, we fall back to trying the verb itself.
    private static final Set<String> VERB_BUTTON_LABELS = Set.of(
            "allow", "deny", "grant", "reject", "accept", "cancel",
            "ok", "skip", "dismiss", "close", "done", "confirm", "continue",
            "enable", "disable", "start", "stop", "save", "apply");

    private static final Pattern QUOTED    = Pattern.compile("[\"'`](.*?)[\"'`]");
    private static final Pattern BRACKETED = Pattern.compile("\\[(.*?)]");
    private static final Pattern WAIT_MS   = Pattern.compile(
            "(?i)(?:wait|sleep|pause|delay)\\s+(?:for\\s+)?(\\d+)\\s*(?:ms|milliseconds?|s|sec(?:ond)?s?)?");

    // ── result record ─────────────────────────────────────────────────────────

    public record StepOutcome(boolean passed, String actualResult, String notes,
                              String screenshotBefore, String screenshotAfter) {
        public static StepOutcome pass(String actual) {
            return new StepOutcome(true, actual, null, null, null);
        }
        public static StepOutcome fail(String actual, String notes) {
            return new StepOutcome(false, actual, notes, null, null);
        }
        public StepOutcome withScreenshots(String before, String after) {
            return new StepOutcome(passed, actualResult, notes, before, after);
        }
    }

    // ── main entry point ──────────────────────────────────────────────────────

    /**
     * Execute one test step and return the outcome, including before/after screenshots.
     *
     * @param runDir directory to write screenshots to; {@code null} disables screenshots
     */
    public StepOutcome execute(TcStep step, String serial, String pkg,
                               int w, int h, AdbClient adb, File runDir) {
        String desc = step.description().trim();
        long   ts   = System.currentTimeMillis();

        String shotBefore = captureScreenshot(serial, adb, runDir,
                "step" + step.num() + "_before_" + ts);

        Action action = classify(desc);

        // Purchase safety — guards EVERY path that can tap (TAP, LONG_PRESS, NAVIGATE, and
        // UNKNOWN's inferred-tap fallback), not just doTap: a bare "Purchase" step classifies
        // UNKNOWN and would otherwise be tap-inferred straight onto a real-money billing control.
        // Verification-only steps may still mention purchases — they never tap anything.
        if ((action == Action.TAP || action == Action.LONG_PRESS
                || action == Action.NAVIGATE || action == Action.UNKNOWN)
                && PURCHASE_GUARD.matcher(desc).find()) {
            String shot = captureScreenshot(serial, adb, runDir, "step" + step.num() + "_guard_" + ts);
            return StepOutcome.fail("Not executed — purchase safety",
                    "This step would tap a real-money purchase/billing control ('" + desc + "'). "
                    + "The framework never completes transactions; verify purchase flows manually "
                    + "with a Play Billing test/sandbox account.").withScreenshots(shotBefore, shot);
        }

        StepOutcome outcome = switch (action) {
            case LAUNCH_APP          -> doLaunch(serial, pkg, adb);
            case RELAUNCH            -> doRelaunch(serial, pkg, adb);
            case BG_RESUME           -> doBackgroundResume(serial, pkg, adb);
            case NETWORK_ON          -> doNetwork(serial, adb, true);
            case NETWORK_OFF         -> doNetwork(serial, adb, false);
            case TAP                 -> doTap(desc, serial, w, h, adb);
            case LONG_PRESS          -> doLongPress(desc, serial, w, h, adb);
            case TYPE                -> doType(desc, step.testData(), serial, adb);
            case CLEAR               -> doClear(serial, adb);
            case BACK                -> doBack(serial, adb);
            case HOME                -> doHome(serial, adb);
            case VERIFY_VISIBLE      -> doVerify(desc, serial, adb, true,  w, h);
            case VERIFY_NOT_VISIBLE  -> doVerify(desc, serial, adb, false, w, h);
            case VERIFY_TEXT         -> doVerifyText(desc, step.expectedResult(), serial, adb);
            case SCROLL_DOWN, SWIPE_UP    -> doScroll(serial, "down",  w, h, adb);
            case SCROLL_UP,   SWIPE_DOWN  -> doScroll(serial, "up",    w, h, adb);
            case SCROLL_LEFT, SWIPE_LEFT  -> doScroll(serial, "left",  w, h, adb);
            case SCROLL_RIGHT, SWIPE_RIGHT -> doScroll(serial, "right", w, h, adb);
            case WAIT                -> doWait(desc);
            case NAVIGATE            -> doNavigate(desc, serial, pkg, w, h, adb);
            case SCREENSHOT          -> StepOutcome.pass("Screenshot captured.");
            default                  -> handleUnknown(desc, serial, pkg, w, h, adb);
        };

        // Guard shared by every module: a tap can land on an ad click-through, a share/rate-us
        // intent, or any other deep link that hands the foreground to Chrome, the Play Store,
        // Settings, Gallery, or any other installed app. StepEngine has no open-ended crawl loop
        // to catch this on the next iteration (unlike ExplorationEngine/LocalizationCrawler), so
        // every step checks the foreground once right after acting and recovers immediately —
        // using the same generic, app-agnostic policy as the rest of the framework.
        String fg = safeForeground(serial, adb);
        if (com.vasundhara.atf.engine.ExplorationEngine.shouldForceStopForeign(fg, pkg)) {
            try { adb.forceStop(serial, fg); } catch (Exception ignored) {}
            sleep(500);
            try { adb.launchApp(serial, pkg); } catch (Exception ignored) {}
            sleep(2000);
            String leftNote = "This step left the app to '" + fg + "' — force-stopped it and returned to " + pkg + ".";
            outcome = new StepOutcome(outcome.passed(), outcome.actualResult(),
                    outcome.notes() == null ? leftNote : outcome.notes() + " " + leftNote,
                    null, null);
        }

        // Wait for UI animations to settle before capturing the after-screenshot
        sleep(800);

        String shotAfter = captureScreenshot(serial, adb, runDir,
                "step" + step.num() + "_after_" + ts);

        return outcome.withScreenshots(shotBefore, shotAfter);
    }

    // ── action implementations ────────────────────────────────────────────────

    private StepOutcome doLaunch(String serial, String pkg, AdbClient adb) {
        try {
            adb.launchApp(serial, pkg);
            sleep(2500);
            return StepOutcome.pass("App launched: " + pkg);
        } catch (Exception e) {
            return StepOutcome.fail("Launch failed",
                    "Could not launch '" + pkg + "': " + e.getMessage());
        }
    }

    private StepOutcome doRelaunch(String serial, String pkg, AdbClient adb) {
        try {
            adb.forceStop(serial, pkg);
            sleep(900);
            adb.launchApp(serial, pkg);
            sleep(2500);
            return StepOutcome.pass("App closed (force-stopped) and reopened: " + pkg);
        } catch (Exception e) {
            return StepOutcome.fail("Relaunch failed",
                    "Could not close and reopen '" + pkg + "': " + e.getMessage());
        }
    }

    /** Home key then re-open — resumes the live task WITHOUT force-stopping, unlike RELAUNCH. */
    private StepOutcome doBackgroundResume(String serial, String pkg, AdbClient adb) {
        try {
            adb.pressHome(serial);
            sleep(1500);
            adb.launchApp(serial, pkg);   // launcher-intent on a live task = resume, not restart
            sleep(2000);
            return StepOutcome.pass("App sent to background (Home) and resumed: " + pkg);
        } catch (Exception e) {
            return StepOutcome.fail("Background/resume failed", e.getMessage());
        }
    }

    private StepOutcome doNetwork(String serial, AdbClient adb, boolean on) {
        try {
            adb.setWifi(serial, on);
            try { adb.setData(serial, on); } catch (Exception ignored) {} // wifi-only devices
            sleep(2000);   // give connectivity a moment to actually change state
            return StepOutcome.pass("Internet " + (on ? "enabled" : "disabled") + " (WiFi + mobile data).");
        } catch (Exception e) {
            return StepOutcome.fail("Network toggle failed",
                    "Could not turn " + (on ? "on" : "off") + " connectivity: " + e.getMessage());
        }
    }

    /**
     * "Select a random/any option" steps ("Select random language and tap Done",
     * "Choose any theme"). No element on screen is literally called "random language", so the
     * name-matching chain can't handle these — worse, its longest-word fallback used to match
     * the screen TITLE (e.g. "Language"), tap a non-clickable header, report success, and leave
     * the run stuck on the selection screen for every remaining test case.
     */
    private static final Pattern RANDOM_PICK = Pattern.compile("(?i)\\b(?:random|any)\\b");
    /** Follow-up confirm button optionally named in the same step ("… and tap Done"). */
    private static final Pattern PICK_CONFIRM = Pattern.compile(
            "(?i)\\b(done|save|apply|confirm|submit|continue|next|ok)\\b");
    /** "Select/Choose X" steps — eligible for pick-an-option handling when X isn't a real control. */
    private static final Pattern SELECT_VERB = Pattern.compile("(?i)^\\s*(?:select|choose|pick)\\b");
    /**
     * Real-money transaction controls the framework must never tap (standing policy shared with
     * every other module): a sheet step like "Purchase" on a coins/subscription screen would
     * otherwise complete an actual Google Play purchase on a signed-in device.
     */
    private static final Pattern PURCHASE_GUARD = Pattern.compile(
            "(?i)\\b(purchase|buy now|\\bbuy\\b|subscribe|checkout|pay now|payment|place order|add money)\\b");

    private StepOutcome doTap(String desc, String serial, int w, int h, AdbClient adb) {
        // Purchase safety is enforced upstream in execute() so it also covers LONG_PRESS,
        // NAVIGATE and UNKNOWN's inferred taps — not re-checked here.
        if (RANDOM_PICK.matcher(desc).find()) {
            StepOutcome picked = doTapRandomOption(desc, serial, adb);
            if (picked != null) return picked;
            // No selectable options found — fall through to the normal name-matching chain.
        }

        String target = extractTarget(desc);

        // "Select <category>" where <category> isn't a tappable control on this screen (e.g.
        // "Select language" on the language-list screen — the only match is the screen TITLE):
        // a manual QA picks an actual option from the list, so do the same. Only fires when the
        // named element is absent or the best match is a non-clickable label; a real "Language"
        // settings row (clickable) still wins the normal path below.
        if (SELECT_VERB.matcher(desc).find()) {
            ElemHit hit = findElementHit(target, serial, adb);
            if (hit == null || !hit.tappable()) {
                StepOutcome picked = doTapRandomOption(desc, serial, adb);
                if (picked != null) return picked;
            }
        }

        // Primary search
        int[] xy = findElementMultiStrategy(target, serial, w, h, adb);

        // Fallback: try the first word of the description as the button label
        // (handles "Allow notification permission" → tap "Allow")
        if (xy == null) {
            String firstWord = desc.split("[\\s/,]+")[0].replaceAll("[^a-zA-Z]", "");
            if (VERB_BUTTON_LABELS.contains(firstWord.toLowerCase())) {
                int[] vxy = findElement(firstWord, serial, adb);
                if (vxy != null) {
                    xy = vxy;
                    target = firstWord;
                }
            }
        }

        // Fallback 2: for multi-word targets try each significant word
        if (xy == null) {
            for (String word : target.split("[\\s/,]+")) {
                if (word.length() < 4) continue;
                int[] wxy = findElement(word, serial, adb);
                if (wxy != null) { xy = wxy; target = word; break; }
            }
        }

        if (xy == null) {
            return StepOutcome.fail("Element not found",
                    buildNotFoundMsg(target, desc, serial, adb));
        }

        try {
            adb.tap(serial, xy[0], xy[1]);
            sleep(1200);
            return StepOutcome.pass("Tapped '" + target + "' at (" + xy[0] + "," + xy[1] + ").");
        } catch (Exception e) {
            return StepOutcome.fail("Tap failed",
                    "Found '" + target + "' at (" + xy[0] + "," + xy[1] + ") but tap failed: " + e.getMessage());
        }
    }

    /**
     * Picks one arbitrary selectable option from the current screen — exactly what a manual QA
     * does for "select any/random X" — then taps the confirm button ("Done"/"Save"/…) when the
     * same step names one. Fully generic: candidates are just visible, tappable, text-bearing
     * rows; nothing is language- or app-specific. Returns {@code null} when the screen offers no
     * selectable options so the caller can fall back to normal element matching.
     */
    private StepOutcome doTapRandomOption(String desc, String serial, AdbClient adb) {
        // Words that mark control/heading nodes rather than options. "select"/"choose" excludes
        // the screen title ("Select Language"); purchase words guard against ever picking a
        // paywall CTA that happens to share the screen.
        Set<String> notOptions = Set.of("done", "save", "cancel", "ok", "back", "continue",
                "apply", "skip", "next", "submit", "confirm", "close");
        Pattern excluded = Pattern.compile("(?i)select|choose|premium|subscribe|buy|upgrade|unlock|trial|[$₹€£]");

        record Opt(int x, int y, String label, boolean checkable, boolean checked) {}
        List<Opt> options = new ArrayList<>();
        try {
            String dump = uiDumpCurrent(serial, adb);
            if (dump.isBlank()) return null;
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(dump.getBytes("UTF-8")));
            NodeList nodes = doc.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String text = el.getAttribute("text").trim();
                if (text.isBlank() || text.length() > 40) continue;
                if (notOptions.contains(text.toLowerCase()) || excluded.matcher(text).find()) continue;

                // Tappable = the node itself, or any ancestor (list rows put clickable on the
                // container, text on an inner TextView), is clickable or checkable.
                boolean tappable = false, checkable = false, checked = false;
                for (org.w3c.dom.Node n = el; n instanceof Element cur; n = n.getParentNode()) {
                    if ("true".equals(cur.getAttribute("clickable"))
                            || "true".equals(cur.getAttribute("checkable"))) tappable = true;
                    if ("true".equals(cur.getAttribute("checkable"))) {
                        checkable = true;
                        checked = "true".equals(cur.getAttribute("checked"));
                    }
                }
                if (!tappable) continue;

                int[] xy = boundsCenter(el);
                if (xy == null) continue;
                // Dedupe rows that surface twice (icon node + label node share a center region).
                boolean dup = options.stream().anyMatch(o -> Math.abs(o.y() - xy[1]) < 12);
                if (!dup) options.add(new Opt(xy[0], xy[1], text, checkable, checked));
            }
        } catch (Exception e) {
            return null;
        }
        if (options.isEmpty()) return null;

        // Prefer an option that is NOT currently selected (radio-row screens), so the choice
        // actually changes state; otherwise skip index 0 (usually the already-active default).
        Opt pick = options.stream().filter(o -> o.checkable() && !o.checked()).findFirst()
                .orElse(options.get(Math.min(1, options.size() - 1)));
        try {
            adb.tap(serial, pick.x(), pick.y());
            sleep(1200);
        } catch (Exception e) {
            return StepOutcome.fail("Tap failed",
                    "Found option '" + pick.label() + "' but tapping it failed: " + e.getMessage());
        }

        // Same-step confirm button ("Random language - Done" is one step in the sheet).
        Matcher cm = PICK_CONFIRM.matcher(desc);
        if (cm.find()) {
            String confirm = cm.group(1);
            int[] cxy = findElement(confirm, serial, adb);
            if (cxy != null) {
                try { adb.tap(serial, cxy[0], cxy[1]); sleep(1500); } catch (Exception ignored) {}
                return StepOutcome.pass("Selected option '" + pick.label() + "' then tapped '" + confirm + "'.");
            }
            return StepOutcome.pass("Selected option '" + pick.label() + "' — confirm button '"
                    + confirm + "' not found on screen (may auto-apply).");
        }
        return StepOutcome.pass("Selected option '" + pick.label() + "'.");
    }

    private StepOutcome doLongPress(String desc, String serial, int w, int h, AdbClient adb) {
        String target = extractTarget(desc);
        int[] xy = findElementMultiStrategy(target, serial, w, h, adb);
        if (xy == null) return StepOutcome.fail("Element not found",
                buildNotFoundMsg(target, desc, serial, adb));
        try {
            adb.shellStr(serial, "input swipe " + xy[0] + " " + xy[1]
                    + " " + xy[0] + " " + xy[1] + " 1500");
            sleep(800);
            return StepOutcome.pass("Long-pressed '" + target + "' at (" + xy[0] + "," + xy[1] + ").");
        } catch (Exception e) {
            return StepOutcome.fail("Long press failed", e.getMessage());
        }
    }

    private StepOutcome doType(String desc, String testData, String serial, AdbClient adb) {
        String text = testData != null && !testData.isBlank() ? testData : extractQuoted(desc);
        if (text.isBlank()) {
            // Last resort: take everything after the action verb
            text = extractTarget(desc);
        }
        if (text.isBlank()) return StepOutcome.fail("No text to type",
                "No text found in test data or quoted in step: '" + desc + "'.");
        try {
            String safe = text.replaceAll("([\"'\\\\$&;<>|`!#%^*?{}\\[\\]()])", "\\\\$1")
                              .replace(" ", "%s");
            adb.shellStr(serial, "input text " + safe);
            sleep(600);
            return StepOutcome.pass("Typed: '" + text + "'.");
        } catch (Exception e) {
            return StepOutcome.fail("Type failed",
                    "Could not type '" + text + "': " + e.getMessage());
        }
    }

    private StepOutcome doClear(String serial, AdbClient adb) {
        try {
            adb.shell(serial, 10, "input", "keyevent", "KEYCODE_CTRL_A");
            adb.shell(serial, 10, "input", "keyevent", "KEYCODE_DEL");
            sleep(300);
            return StepOutcome.pass("Field cleared.");
        } catch (Exception e) {
            return StepOutcome.fail("Clear failed", e.getMessage());
        }
    }

    private StepOutcome doBack(String serial, AdbClient adb) {
        try {
            adb.pressBack(serial);
            sleep(700);
            return StepOutcome.pass("Back pressed.");
        } catch (Exception e) {
            return StepOutcome.fail("Back failed", e.getMessage());
        }
    }

    private StepOutcome doHome(String serial, AdbClient adb) {
        try {
            adb.pressHome(serial);
            sleep(700);
            return StepOutcome.pass("Home key pressed.");
        } catch (Exception e) {
            return StepOutcome.fail("Home failed", e.getMessage());
        }
    }

    private StepOutcome doVerify(String desc, String serial, AdbClient adb,
                                 boolean shouldBeVisible, int w, int h) {
        String target = extractVerifyTarget(desc);
        String dump   = uiDumpCurrent(serial, adb);
        boolean found = containsCI(dump, target);

        // If not found and we expect it, try scrolling once
        if (!found && shouldBeVisible) {
            doScroll(serial, "down", w, h, adb);
            dump  = uiDumpCurrent(serial, adb);
            found = containsCI(dump, target);
            if (!found) doScroll(serial, "up", w, h, adb); // restore
        }

        // Also check expected result text if target not specific enough
        if (!found && shouldBeVisible) {
            String expText = extractFirstMeaningfulToken(desc);
            if (!expText.isBlank() && !expText.equalsIgnoreCase(target)) {
                found = containsCI(dump, expText);
                if (found) target = expText;
            }
        }

        if (found == shouldBeVisible) {
            return StepOutcome.pass("'" + target + "' is "
                    + (shouldBeVisible ? "" : "not ") + "visible — as expected.");
        }
        return StepOutcome.fail("Assertion failed",
                "Expected '" + target + "' to be " + (shouldBeVisible ? "visible" : "not visible")
                + " but it was " + (found ? "found" : "not found") + " on screen.");
    }

    private StepOutcome doVerifyText(String desc, String expected, String serial, AdbClient adb) {
        String target = (expected != null && !expected.isBlank()) ? expected : extractQuoted(desc);
        if (target.isBlank()) target = extractVerifyTarget(desc);
        if (target.isBlank()) return StepOutcome.fail("No expected text",
                "No expected result or quoted text found in step.");
        String dump = uiDumpCurrent(serial, adb);
        if (containsCI(dump, target))
            return StepOutcome.pass("Text '" + target + "' found on screen.");
        return StepOutcome.fail("Text not found",
                "Expected text '" + target + "' was not present on screen.");
    }

    private StepOutcome doScroll(String serial, String dir, int w, int h, AdbClient adb) {
        try {
            int cx = w / 2, cy = h / 2;
            String cmd = switch (dir) {
                case "down"  -> "input swipe " + cx + " " + (cy + 350) + " " + cx + " " + (cy - 350) + " 450";
                case "up"    -> "input swipe " + cx + " " + (cy - 350) + " " + cx + " " + (cy + 350) + " 450";
                case "left"  -> "input swipe " + (cx + 350) + " " + cy + " " + (cx - 350) + " " + cy + " 450";
                default      -> "input swipe " + (cx - 350) + " " + cy + " " + (cx + 350) + " " + cy + " 450";
            };
            adb.shellStr(serial, cmd);
            sleep(700);
            return StepOutcome.pass("Scrolled " + dir + ".");
        } catch (Exception e) {
            return StepOutcome.fail("Scroll failed", e.getMessage());
        }
    }

    private StepOutcome doWait(String desc) {
        long ms = 2000;
        Matcher m = WAIT_MS.matcher(desc);
        if (m.find()) {
            long v = Long.parseLong(m.group(1));
            ms = v < 100 ? v * 1000 : v;
        }
        sleep(ms);
        return StepOutcome.pass("Waited " + ms + " ms.");
    }

    private StepOutcome doNavigate(String desc, String serial, String pkg,
                                   int w, int h, AdbClient adb) {
        String target = extractTarget(desc);
        int[] xy = findElementMultiStrategy(target, serial, w, h, adb);
        if (xy != null) {
            try {
                adb.tap(serial, xy[0], xy[1]);
                sleep(1200);
                return StepOutcome.pass("Navigated to '" + target + "' (tapped at " + xy[0] + "," + xy[1] + ").");
            } catch (Exception ignored) {}
        }
        // Fallback: relaunch to get to the home/main screen
        return doLaunch(serial, pkg, adb);
    }

    private StepOutcome handleUnknown(String desc, String serial, String pkg,
                                      int w, int h, AdbClient adb) {
        // Best-effort: extract target and attempt a tap
        String target = extractTarget(desc);

        if (!target.isBlank() && !target.equalsIgnoreCase(desc)) {
            int[] xy = findElementMultiStrategy(target, serial, w, h, adb);
            if (xy != null) {
                try {
                    adb.tap(serial, xy[0], xy[1]);
                    sleep(1200);
                    return StepOutcome.pass(
                            "[Inferred] Tapped '" + target + "' at (" + xy[0] + "," + xy[1] + ").");
                } catch (Exception e) {
                    return StepOutcome.fail("Inferred tap failed", e.getMessage());
                }
            }
        }

        // Second attempt: scan for any word from description that's clickable on screen
        for (String word : desc.split("[\\s,./;:!?–—]+")) {
            if (word.length() < 4) continue;
            int[] xy = findElement(word, serial, adb);
            if (xy != null) {
                try {
                    adb.tap(serial, xy[0], xy[1]);
                    sleep(1200);
                    return StepOutcome.pass(
                            "[Inferred] Tapped element matching keyword '" + word + "' at (" + xy[0] + "," + xy[1] + ").");
                } catch (Exception ignored) {}
            }
        }

        String visibleElements = getVisibleElementSummary(serial, adb);
        return StepOutcome.fail("Step not automatable",
                "No keyword matched in: '" + desc + "'. " +
                "Extracted target: '" + target + "' — not found on screen. " +
                "Visible elements: [" + visibleElements + "]. " +
                "Use explicit keywords such as 'tap', 'click', 'verify visible', 'scroll down', " +
                "'type', 'wait', or quote the target element name.");
    }

    // ── element finding ───────────────────────────────────────────────────────

    /**
     * Multi-strategy element search: exact/partial match, scroll down, scroll back, word-by-word.
     */
    int[] findElementMultiStrategy(String target, String serial, int w, int h, AdbClient adb) {
        if (target == null || target.isBlank()) return null;

        // 1. Search current screen
        int[] xy = findElement(target, serial, adb);
        if (xy != null) return xy;

        // 2. Scroll down one page and search
        adb.shellStr(serial, "input swipe " + w/2 + " " + (h*2/3) + " " + w/2 + " " + (h/3) + " 450");
        sleep(700);
        xy = findElement(target, serial, adb);
        if (xy != null) return xy;

        // 3. Scroll back up and search (restores original position)
        adb.shellStr(serial, "input swipe " + w/2 + " " + (h/3) + " " + w/2 + " " + (h*2/3) + " 450");
        sleep(500);
        xy = findElement(target, serial, adb);
        if (xy != null) return xy;

        // 4. Individual significant words (longest first for specificity)
        List<String> words = Arrays.stream(target.split("[\\s/,]+"))
                .filter(w2 -> w2.length() >= 4)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String word : words) {
            xy = findElement(word, serial, adb);
            if (xy != null) return xy;
        }

        return null;
    }

    /** One element match: center coordinates + whether it (or an ancestor) is actually tappable. */
    record ElemHit(int x, int y, boolean tappable) {}

    /**
     * Search the current UI dump for {@code target} using:
     * <ol>
     *   <li>Case-insensitive exact or substring match on text / content-desc / resource-id.</li>
     *   <li>Majority-word match for multi-word targets.</li>
     * </ol>
     */
    int[] findElement(String target, String serial, AdbClient adb) {
        ElemHit hit = findElementHit(target, serial, adb);
        return hit == null ? null : new int[]{hit.x(), hit.y()};
    }

    /**
     * Same search as {@link #findElement} but reports whether the match is genuinely tappable
     * (clickable/checkable itself or via an ancestor — list rows put clickable on the container).
     * A tappable match anywhere in the pass beats a non-tappable one: previously the first text
     * match won even when it was a plain heading, so "Language" resolved to the screen TITLE
     * instead of the clickable Settings row bearing the same word.
     */
    ElemHit findElementHit(String target, String serial, AdbClient adb) {
        if (target == null || target.isBlank()) return null;
        String dump = uiDumpCurrent(serial, adb);
        if (dump.isBlank()) return null;
        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(dump.getBytes("UTF-8")));
            NodeList nodes = doc.getElementsByTagName("node");
            String tLow = target.toLowerCase().trim();
            ElemHit fallback = null;

            // Pass 1 — exact or substring match (first TAPPABLE match wins; else first match)
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                if (matchesNode(el, tLow)) {
                    int[] xy = boundsCenter(el);
                    if (xy == null) continue;
                    if (isTappable(el)) return new ElemHit(xy[0], xy[1], true);
                    if (fallback == null) fallback = new ElemHit(xy[0], xy[1], false);
                }
            }
            if (fallback != null) return fallback;

            // Pass 2 — majority of significant words appear in the node
            String[] words = tLow.split("\\s+");
            if (words.length > 1) {
                int threshold = Math.max(1, (words.length + 1) / 2);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element el   = (Element) nodes.item(i);
                    String attrs = (el.getAttribute("text") + " " +
                                    el.getAttribute("content-desc") + " " +
                                    el.getAttribute("resource-id")).toLowerCase();
                    int hits = 0;
                    for (String w : words) {
                        if (w.length() >= 3 && attrs.contains(w)) hits++;
                    }
                    if (hits >= threshold) {
                        int[] xy = boundsCenter(el);
                        if (xy == null) continue;
                        if (isTappable(el)) return new ElemHit(xy[0], xy[1], true);
                        if (fallback == null) fallback = new ElemHit(xy[0], xy[1], false);
                    }
                }
            }
            return fallback;
        } catch (Exception ignored) {}
        return null;
    }

    /** True when the node itself or any ancestor is clickable/checkable (real tap target). */
    private static boolean isTappable(Element el) {
        for (org.w3c.dom.Node n = el; n instanceof Element cur; n = n.getParentNode()) {
            if ("true".equals(cur.getAttribute("clickable"))
                    || "true".equals(cur.getAttribute("checkable"))) return true;
        }
        return false;
    }

    private boolean matchesNode(Element el, String tLow) {
        String text = el.getAttribute("text").toLowerCase();
        String cd   = el.getAttribute("content-desc").toLowerCase();
        String rid  = el.getAttribute("resource-id").toLowerCase();
        // Exact match
        if (text.equals(tLow) || cd.equals(tLow) || rid.equals(tLow)) return true;
        // Suffix match on resource-id (e.g. "hotspot" matches "com.app/.hotspot_button")
        if (rid.endsWith("/" + tLow) || rid.endsWith("_" + tLow) || rid.endsWith("." + tLow)) return true;
        // Substring match
        return text.contains(tLow) || cd.contains(tLow) || rid.contains(tLow);
    }

    private int[] boundsCenter(Element el) {
        Matcher m = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
                           .matcher(el.getAttribute("bounds"));
        if (!m.find()) return null;
        int x = (Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(3))) / 2;
        int y = (Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(4))) / 2;
        return (x > 0 || y > 0) ? new int[]{x, y} : null;
    }

    // ── target extraction ─────────────────────────────────────────────────────

    public Action classify(String desc) {
        String d = desc.toLowerCase();
        for (Map.Entry<Pattern, Action> e : KEYWORD_MAP.entrySet()) {
            if (e.getKey().matcher(d).find()) return e.getValue();
        }
        return Action.UNKNOWN;
    }

    /** Remove leading action verb + filler words to extract the element name. */
    String extractTarget(String desc) {
        String q = extractQuoted(desc);
        if (!q.isBlank()) return q;
        Matcher m = BRACKETED.matcher(desc);
        if (m.find()) return m.group(1).trim();

        String r = desc.replaceFirst(
            "(?i)^(?:tap|click|press|select|touch|" +
            "long.?press|long.?tap|press and hold|hold(?:\\s+down)?|" +
            "verify visible|verify not visible|verify(?:\\s+(?:that|if))?|check(?:\\s+(?:that|if|the))?|" +
            "assert(?:\\s+that)?|observe|notice|confirm|ensure|see|watch|" +
            "scroll down|scroll up|scroll left|scroll right|" +
            "navigate to|go to|open (?:the|screen|page|module|section)?|" +
            "enter|type|input|fill(?:\\s+in)?|write|" +
            "turn on|turn off|switch on|switch off|" +
            "enable|disable|toggle|activate|deactivate|" +
            "allow|grant|deny|reject|accept|permit|block|" +
            "close|dismiss|cancel|skip|done|finish|submit|apply|save|" +
            "launch|start|run|install|use|access|expand|collapse)" +
            "\\s+(?:on\\s+)?(?:the\\s+)?(?:a\\s+)?(?:an\\s+)?", "").trim();

        // Strip trailing punctuation
        r = r.replaceAll("[.!?,;:–—]+$", "").trim();

        return r.isEmpty() ? desc.replaceAll("[.!?,;:–—]+$", "").trim() : r;
    }

    private String extractVerifyTarget(String desc) {
        String stripped = desc.replaceFirst(
            "(?i)^(?:verify(?:\\s+(?:that|if))?|check(?:\\s+(?:that|if))?|" +
            "assert(?:\\s+that)?|observe|confirm|ensure|see|notice|watch|" +
            "status should|should show|should display|should appear|" +
            "should (?:be )?(?:shown|visible|displayed|enabled|active))" +
            "\\s+", "").trim();
        stripped = stripped.replaceAll("[.!?,;:–—]+$", "").trim();
        return stripped.isEmpty() ? desc : stripped;
    }

    private String extractQuoted(String desc) {
        Matcher m = QUOTED.matcher(desc);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractFirstMeaningfulToken(String desc) {
        for (String t : desc.split("\\s+")) {
            String clean = t.replaceAll("[^a-zA-Z0-9]", "");
            if (clean.length() >= 4) return clean;
        }
        return "";
    }

    // ── screenshot ────────────────────────────────────────────────────────────

    private String captureScreenshot(String serial, AdbClient adb, File runDir, String name) {
        if (runDir == null) return null;
        try {
            byte[] png = adb.screencapPng(serial);
            if (png == null || png.length == 0) return null;
            File dest = new File(runDir, name + ".png");
            Files.write(dest.toPath(), png);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private String uiDumpCurrent(String serial, AdbClient adb) {
        String dump = adb.uiDump(serial);
        return dump == null ? "" : dump;
    }

    private String getVisibleElementSummary(String serial, AdbClient adb) {
        try {
            String dump = uiDumpCurrent(serial, adb);
            if (dump.isBlank()) return "no UI data";
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(dump.getBytes("UTF-8")));
            NodeList nodes = doc.getElementsByTagName("node");
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < nodes.getLength() && labels.size() < 10; i++) {
                Element el  = (Element) nodes.item(i);
                String text = el.getAttribute("text").trim();
                String cd   = el.getAttribute("content-desc").trim();
                String label = !text.isBlank() ? text : (!cd.isBlank() ? cd : "");
                if (!label.isBlank() && !labels.contains(label)) labels.add(label);
            }
            return labels.isEmpty() ? "no visible text elements" : String.join(", ", labels);
        } catch (Exception e) {
            return "UI dump unavailable";
        }
    }

    private String buildNotFoundMsg(String target, String fullDesc, String serial, AdbClient adb) {
        return "Could not find element '" + target + "' on screen (from step: '" + fullDesc + "'). " +
               "Searched: text, content-desc, resource-id — exact and partial match — " +
               "on current screen and after one-page scroll. " +
               "Visible elements: [" + getVisibleElementSummary(serial, adb) + "].";
    }

    private boolean containsCI(String text, String search) {
        if (text == null || search == null || search.isBlank()) return false;
        return text.toLowerCase().contains(search.toLowerCase());
    }

    // ── AI notes ──────────────────────────────────────────────────────────────

    public String generateNotes(TcStep step, StepOutcome outcome) {
        if (outcome.passed()) return null;
        if (outcome.notes() != null) return outcome.notes();
        Action action = classify(step.description());
        String target = extractTarget(step.description());
        return switch (action) {
            case TAP, LONG_PRESS ->
                "Element '" + target + "' not found after searching all visible elements and scrolling. " +
                "Possible causes: element is behind a dialog, has a different label in this build, " +
                "or requires a prior navigation step to be accessible.";
            case VERIFY_VISIBLE  ->
                "Expected '" + target + "' to be visible but it was not found. " +
                "The app may be in an unexpected state from a prior step.";
            case VERIFY_NOT_VISIBLE ->
                "Expected '" + target + "' to be absent but it was present.";
            case VERIFY_TEXT     ->
                "Expected text not found. Check for localisation differences or dynamic content.";
            case TYPE            ->
                "Could not type text. The target field may not be focused or editable.";
            case LAUNCH_APP      ->
                "App failed to launch. Verify the package name and that the APK is installed.";
            default              ->
                outcome.actualResult() != null ? outcome.actualResult() : "Step execution failed.";
        };
    }

    // ── utility ───────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String safeForeground(String serial, AdbClient adb) {
        try { return adb.currentForegroundPackage(serial); } catch (Exception e) { return null; }
    }
}
