package com.vasundhara.atf.engine;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model-based UI crawler. It launches the app once and performs a depth-first
 * exploration: at each screen it parses the live UI hierarchy, records unique
 * states with screenshots, then taps/types previously-unused interactive elements,
 * scrolling and backing out of dead ends. The resulting {@link ExplorationResult}
 * is shared by all interactive categories so the app is only driven once per run.
 */
@Component
public class ExplorationEngine {

    private final com.vasundhara.atf.ai.LlmAdClassifier llmAdClassifier;
    private final com.vasundhara.atf.ai.LlmNavigator llmNavigator;
    private final com.vasundhara.atf.ai.VisionNavigator visionNavigator;
    private final com.vasundhara.atf.ocr.OcrEngine ocrEngine;

    public ExplorationEngine(com.vasundhara.atf.ai.LlmAdClassifier llmAdClassifier,
                             com.vasundhara.atf.ai.LlmNavigator llmNavigator,
                             com.vasundhara.atf.ai.VisionNavigator visionNavigator,
                             com.vasundhara.atf.ocr.OcrEngine ocrEngine) {
        this.llmAdClassifier = llmAdClassifier;
        this.llmNavigator = llmNavigator;
        this.visionNavigator = visionNavigator;
        this.ocrEngine = ocrEngine;
    }

    /** Max LLM-guided dead-end escapes per run (bounds latency/cost) and per screen structure. */
    private static final int LLM_ESCAPE_MAX_PER_RUN = 12;
    private static final int LLM_ESCAPE_MAX_PER_SIG = 2;

    private static final Logger log = LoggerFactory.getLogger(ExplorationEngine.class);
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");
    /** Benign valid input default; context-aware alternatives chosen by sampleInputForField(). */
    private static final String SAMPLE_TEXT   = "Test123";
    private static final String SAMPLE_EMAIL  = "test@example.com";
    private static final String SAMPLE_PHONE  = "+15551234567";
    private static final String SAMPLE_PASS   = "Test@1234";
    private static final String SAMPLE_URL    = "https://example.com";
    private static final String SAMPLE_NAME   = "Test User";
    private static final String SAMPLE_SEARCH = "testing";
    private static final String SAMPLE_NUM    = "12345";
    /** Let splash screens / first-frame animations settle before the first capture. */
    private static final long SETTLE_AFTER_LAUNCH_MS = 5000;
    /** Allow the UI to react/transition after an action before re-reading the hierarchy. */
    private static final long SETTLE_AFTER_ACTION_MS = 700;
    /**
     * Stop once this many consecutive steps reveal no new screen — exploration has converged.
     * Raised from 25 so that complex apps with deep navigation (settings pages, nested menus,
     * bottom-sheet flows, multi-level tabs) have enough room to be fully discovered.
     */
    /**
     * Convergence guard: stop when this many consecutive steps produce neither a new screen
     * NOR a successful widget interaction. With the "in-screen-first" ranking, each button/input
     * action resets the counter to 0, so the engine only converges when it is genuinely stuck
     * (Back blocked at root, no scrollable content, all widgets exhausted).
     */
    private static final int MAX_NO_PROGRESS_STEPS = 60;
    // Frontier-directed exploration: when the crawl would otherwise converge but the navigation
    // graph still has screens with un-tried actions, replay a path back to the nearest such screen
    // and keep going. Bounded per run (and per replay depth) so it can't loop or run away.
    private static final int MAX_FRONTIER_DRIVES = 8;
    private static final int MAX_FRONTIER_PATH = 8;
    /** Hard ceiling on foreground exits before we give up (safety net; learning usually stops sooner). */
    private static final int MAX_APP_EXITS = 3;
    /** Hard ceiling on relaunch attempts regardless of cause. */
    private static final int MAX_RELAUNCHES = 3;
    /** Self-restart tolerance: after the foreground drops, poll this many times for the app to
     *  return on its own (a language/theme change or recreate() restarts the app briefly). */
    private static final int SELF_RESTART_POLLS = 4;
    private static final long SELF_RESTART_POLL_MS = 400;
    /** Max swipes during one scrollable-screen sweep (bounds infinite/lazy scroll). */
    private static final int MAX_SCROLL_STEPS = 12;
    /**
     * Max progressive scroll steps during dead-end recovery on one screen state.
     * Raised to 15 so long settings lists, privacy policies, and lazy-loading feeds
     * are fully traversed before the engine gives up and presses Back.
     */
    private static final int MAX_DEADEND_SCROLLS = 15;
    /**
     * Max horizontal swipes tried at a dead end before giving up and pressing Back.
     * Handles swipe-only onboarding/carousels that have no "Next" button.
     */
    private static final int MAX_DEADEND_HORIZ_SWIPES = 4;
    // Screenshot-diff tuning for the API-free visual exploration of opaque/canvas screens.
    // Bits differ out of a 64-bit perceptual (average) hash of an 8x8 grayscale of the screen.
    private static final int OPAQUE_SAME_SCREEN_BITS = 6;    // <=6 bits apart ⇒ treated as the same screen (absorbs animation)
    private static final int OPAQUE_MIN_CHANGE_BITS = 10;    // a real navigation must change at least this many bits
    private static final int OPAQUE_JITTER_MARGIN_BITS = 6;  // ...and exceed the screen's own animation jitter by this margin
    // Candidate tap points (fractions of screen w,h), probed in order per opaque screen: the
    // bottom-CTA band first (where onboarding Continue/Start/Get-Started buttons live), then a
    // coarse content grid so feature tiles and centred actions are reached too.
    private static final double[][] OPAQUE_CANDIDATES = {
            {0.50, 0.86}, {0.50, 0.90}, {0.50, 0.80}, {0.50, 0.74},   // bottom CTA band
            {0.50, 0.50},                                             // centre CTA
            {0.28, 0.45}, {0.72, 0.45}, {0.28, 0.65}, {0.72, 0.65},   // 4 content tiles
            {0.50, 0.35}, {0.28, 0.85}, {0.72, 0.85}                  // top-centre + bottom corners
    };
    // OCR-guided advance (recovery #2.7): a single on-screen word that matches a POSITIVE
    // call-to-action keyword and NOT a negative one is a safe tap target for advancing an opaque
    // screen (its box centre lands inside the painted button). Whole-word match, case-insensitive.
    private static final Set<String> OCR_CTA_WORDS = Set.of(
            "start", "started", "continue", "next", "begin", "skip", "allow", "agree", "accept",
            "ok", "okay", "create", "generate", "explore", "guest", "proceed", "confirm",
            "done", "finish", "register", "enter", "lets", "let's");
    // Never tap a word that looks like a purchase, ad, external link, or exit control — mirrors
    // the standing guards so OCR navigation can't break "never purchase / never leave the app".
    private static final Pattern OCR_CTA_NEGATIVE = Pattern.compile("(?i)"
            + "(buy|purchase|subscribe|subscription|upgrade|premium|\\bpro\\b|unlock|restore|trial|"
            + "rate|review|share|privacy|terms|policy|facebook|instagram|twitter|tiktok|youtube|"
            + "close|exit|cancel|logout|log ?out|sign ?out|[$₹€£])");
    // Vision-guided taps on opaque screens are one API call each, so bound them: per whole run and
    // per individual screen signature (a screen the model can't get past shouldn't burn the budget).
    private static final int VISION_MAX_PER_RUN = 30;
    private static final int VISION_MAX_PER_SIG = 3;
    /**
     * Cap on how many distinct-content visits to the same screen STRUCTURE are treated as new.
     * Raised to 25 to give dynamic feeds (badge counters, timestamps) more room while still
     * preventing runaway infinite-scroll loops.
     */
    private static final int MAX_VISITS_PER_ACTIVITY = 25;
    /**
     * Text patterns that are clearly dynamic (counters, timestamps, amounts) and must NOT be
     * included in the content component of the state signature.
     */
    private static final Pattern DYNAMIC_TEXT_PAT = Pattern.compile(
            "\\d+" +                                          // pure numbers / counters
            "|\\d{1,2}:\\d{2}(:\\d{2})?(\\s?[AaPp][Mm])?" + // times: 12:30, 3:45 PM
            "|\\d{1,4}[/\\-\\.:]\\d{1,2}[/\\-\\.:]\\d{2,4}" + // dates: 01/15/24
            "|[+\\-]?\\d+([,.]\\d+)*\\s?[%$€£₹¥]?" +        // amounts / percentages
            "|\\d+\\.\\d+");                                   // decimals

    public ExplorationResult explore(TestContext ctx, int maxSteps) {
        ExplorationResult result = new ExplorationResult();
        result.setAppiumAvailable(true);
        AndroidDriver driver = ctx.driver();
        String pkg = ctx.apkInfo().getPackageName();
        String serial = ctx.serial();

        long start = System.currentTimeMillis();
        sleep(SETTLE_AFTER_LAUNCH_MS);   // wait out splash before exploring
        Set<String> visitedStates = new HashSet<>();
        Set<String> executedActions = new HashSet<>();
        // Per-signature guard for ad wait: prevents repeated 8-second waits on the same ad screen.
        Set<String> adHandledSigs = new HashSet<>();
        // Navigation graph — screens, transitions and per-screen un-tried actions — used to drive
        // exploration toward the frontier instead of converging while coverage remains, and to
        // report honest coverage. graphLastSig/Key link the just-tapped action to the next screen.
        NavigationGraph graph = new NavigationGraph();
        String graphLastSig = null, graphLastKey = null;
        int frontierDrives = 0;
        // One full scroll sweep per activity; progressive dead-end scroll counts per state.
        Set<String> sweptActivities = new HashSet<>();
        Map<String, Integer> deadEndScrolls = new HashMap<>();
        // Dead-end horizontal swipe counter per state (handles swipe-only onboarding/carousels).
        Map<String, Integer> deadEndHorizSwipes = new HashMap<>();
        // Screenshot-based visual exploration of opaque/canvas screens (recovery #2.7, API-free).
        // The node tree can't distinguish these screens, so they are tracked by perceptual image
        // hash: opaqueHashes[k] is a visited canvas screen, opaqueCursors[k] the next candidate
        // tap point to probe on it. Fuzzy-matched (Hamming distance) so animation/frame jitter on
        // the same screen doesn't register as a new one.
        List<Long> opaqueHashes = new ArrayList<>();
        List<Integer> opaqueCursors = new ArrayList<>();
        // OCR-guided taps already made on each opaque visual screen, keyed "<screenIndex>|<label>",
        // so a CTA label that didn't advance isn't tapped again on the same screen.
        Set<String> ocrTriedLabels = new HashSet<>();
        // Vision-guided tap accounting for opaque screens (recovery #2.7): count per state + the
        // %-coordinates already tried on each state, so the vision model is asked for something new.
        Map<String, Integer> visionTapsPerSig = new HashMap<>();
        Map<String, List<int[]>> visionTriedPoints = new HashMap<>();
        int visionTapsThisRun = 0;
        // ── Visit counting keyed by screen STRUCTURE, not by activity name. ──────────────────
        // Modern Android apps use a single Activity for all screens via Jetpack Navigation.
        // If we key the visit cap by activity name, exploring 8+ fragments (splash, onboarding,
        // login) before reaching Home exhausts the cap for "MainActivity". The FIRST visit to
        // the Home fragment then triggers the over-cap path: ALL Home widgets are pre-marked as
        // executed, chooseAction() returns null, Back is pressed immediately, the Exit dialog
        // appears, and the Back→Cancel loop begins — Home was never actually explored at all.
        //
        // Keying by sigPrefix ("activity:structureHash") gives each SCREEN STRUCTURE its own
        // independent counter. Home has a unique layout → its counter starts at 1 regardless
        // of how many other fragments were explored first. The cap still prevents a dynamic feed
        // (same widget structure, infinite new content hashes) from absorbing the step budget.
        Map<String, Integer> structureVisitCounts = new HashMap<>();
        // Activities we have learned exit the app when Back is pressed — never Back out of these.
        Set<String> backExitsActivities = new HashSet<>();
        // Retroactive structure-level widget learning.
        // Problem: on a dynamic Home screen, each visit produces a new content-hash sig. The
        // sig-keyed executedActions entries from the previous visit don't match the new sig, so
        // nav buttons that already led to fully-explored features appear un-executed and get
        // re-fired, causing Home→FeatureA(visited)→Back→Home→FeatureA(visited)→... cycles.
        // Fix: when we observe that executing widget W led to an already-visited state, record
        // W in structureExecuted for that screen structure. Future visits skip W via chooseAction().
        // Crucially this is RETROACTIVE — we only learn a widget is "no benefit" after we
        // observe it led nowhere new. This avoids blocking wizard "Next" buttons prematurely
        // (they lead to new pages, so they're never added to structureExecuted).
        Map<String, Set<String>> structureExecuted = new HashMap<>();
        // Tracks the last widget executed and which screen structure it was executed from,
        // so the retroactive learning can fire at the start of the next iteration.
        String lastExecWidgetKey = null;
        String lastExecSigPrefix = null;
        String lastExecActivity = null;
        // State signatures where Back triggers an exit-confirmation dialog (same activity,
        // new Exit/Cancel buttons overlay). Stored per-sig (not per-activity) so that a
        // fragment-based app where a single MainActivity hosts both the Home fragment
        // (root, triggers exit dialog) and inner fragments (safe to Back out) is handled correctly.
        Set<String> sigsWhereBackTriggersExit = new HashSet<>();
        // Structure-prefix version of the above: "activity:structureHash" (sig without content component).
        // Handles the case where the Home screen has dynamic text (badge counters, timestamps,
        // "5 min ago" labels) that changes the content hash on every visit, causing a new sig
        // each time so sigsWhereBackTriggersExit never matches the current sig.
        Set<String> exitDialogStructurePrefixes = new HashSet<>();
        // ── Per-screen full widget inventory ──────────────────────────────────────────────────────
        // On the first structural visit to each screen, discoverBelowFold() silently scrolls the
        // entire screen and accumulates every actionable widget (including those below the fold) into
        // this map (keyed by sigPrefix = "activity:structureHash"). chooseAction() then draws from
        // this full set, and scrollToWidget() is used to bring an off-screen widget into the viewport
        // before tapping it. This ensures all controls on a long settings list, a scrollable form, or
        // a RecyclerView are tested regardless of initial viewport height.
        Map<String, LinkedHashMap<String, Widget>> screenWidgets = new HashMap<>();
        // Widgets confirmed to leave the app (external browser/ad link not caught by isAdWidget),
        // keyed by ACTIVITY alone rather than the full "activity:structureHash" sig prefix. Ad-
        // supported home screens often rotate a banner/promo slot that changes the widget set
        // between visits, making the structure hash unstable — a sigPrefix-keyed blacklist never
        // matches on the next visit, so the same external link gets re-tapped repeatedly until
        // MAX_APP_EXITS is exhausted and exploration aborts almost immediately. Keying by activity
        // alone survives that churn: an external-navigation widget's identity doesn't depend on
        // what else happens to be on screen at the same time.
        Map<String, Set<String>> activityExternalBlacklist = new HashMap<>();
        // Screen structures already sent through the AI ad classifier this run — caps it to once
        // per unique structure so it never adds latency to the normal per-tap interaction loop.
        Set<String> llmAdCheckedSigPrefixes = new HashSet<>();

        int steps = 0;
        int relaunches = 0;
        int appExits = 0;
        int noProgress = 0;
        int screenIndex = 0;
        String prevActivity = "<launch>";
        boolean lastStepWasBack = false;
        String lastBackActivity = null;
        // Sig that was active when the last Back press was issued — used to link an exit dialog
        // that appears on the NEXT loop iteration back to the sig that triggered it.
        String lastBackSig = null;
        long lastActionEnd = System.currentTimeMillis();
        // Simple name of the app's launcher/main activity, and the structure-prefix of the home
        // screen (the first screen we see on that activity). Back from the home screen exits the
        // app to the launcher by design; recording its structure lets us converge there instead of
        // pressing Back → exiting → relaunching (the reported close/open thrash). Scoped to the
        // home screen's own structure so inner fragments of a single-Activity app still allow Back.
        String launcherSimple = activitySimpleName(ctx.apkInfo().getMainActivity());
        String rootSigPrefix = null;
        // LLM-guided dead-end escape budget: when the heuristic crawl gets stuck on a screen with
        // no new action (common on gated single-Activity / Compose apps whose deeper content sits
        // behind a non-obvious control), the LLM picks a control likely to open unexplored area
        // instead of just backing out. Bounded so it never dominates runtime or loops.
        int llmEscapes = 0;
        Map<String, Integer> llmEscapesPerSig = new HashMap<>();

        while (steps < maxSteps) {
            if (Thread.currentThread().isInterrupted() || ctx.run().isCancelRequested()) {
                result.note("Exploration stopped by user cancellation.");
                break;
            }
            // Detect when the app has left the foreground (Back from its root screen, an external
            // link, or a self-close). If our previous Back press caused it, remember that activity
            // so we never Back out of it again — this is what breaks the open/close cycle at its root.
            String currentPkg = safe(driver::getCurrentPackage);
            if (currentPkg == null || !pkg.equals(currentPkg)) {
                // ── Self-restart / transient-foreground tolerance ────────────────────────────
                // An app that applies a language, theme, or locale change very commonly recreate()s
                // or restarts its own process. During that transition getCurrentPackage() briefly
                // reads null (no focused window) or a transient package, then the app returns to the
                // foreground on its own within ~1s. A genuine exit (ad click-through, Back-to-
                // launcher, an external link) does NOT come back by itself. Re-poll to tell them
                // apart: if the app returns, it was a self-restart and must NOT be counted as an app
                // exit. Miscounting these was the root cause of the language-screen thrash — each
                // language tap restarted the app, was misread as an exit, and after MAX_APP_EXITS
                // (2-3 taps) the whole crawl aborted before ever getting past language selection.
                if (waitForAppReturn(driver, pkg)) {
                    if (!lastStepWasBack) {
                        result.note("App restarted itself (likely applied a language/theme/locale "
                                + "change) and returned to the foreground on its own — treated as a "
                                + "self-restart, not an app exit.");
                    }
                    lastStepWasBack = false;
                    lastExecWidgetKey = null;
                    lastExecSigPrefix = null;
                    sleep(SETTLE_AFTER_LAUNCH_MS);
                    steps++;
                    lastActionEnd = System.currentTimeMillis();
                    continue;
                }
                // If the widget we just tapped is what caused this (not a Back press), it was an
                // ad (or other external link) that slipped past isAdWidget/isAdScreen — this
                // covers ad SDKs/formats the static classifiers don't recognize by resource-id or
                // class name. Never tap that exact widget again for the rest of this run, and log
                // it distinctly from a genuine app self-close so reports are honest about why
                // testing paused briefly. The tap itself already happened and can't be undone,
                // but this closes the loop so the SAME ad isn't clicked repeatedly.
                if (!lastStepWasBack && lastExecWidgetKey != null && lastExecSigPrefix != null) {
                    structureExecuted.computeIfAbsent(lastExecSigPrefix, k -> new HashSet<>()).add(lastExecWidgetKey);
                    if (lastExecActivity != null) {
                        activityExternalBlacklist.computeIfAbsent(lastExecActivity, k -> new HashSet<>())
                                .add(lastExecWidgetKey);
                    }
                    result.note("External app found — " + (currentPkg == null ? "unknown package" : currentPkg)
                            + " (likely an ad or external link not caught by ad detection). "
                            + "That widget is now permanently skipped and never tapped again this run.");
                }
                if (lastStepWasBack && lastBackActivity != null) {
                    backExitsActivities.add(lastBackActivity);
                    result.note("Learned that Back exits the app from '" + lastBackActivity
                            + "'; will push forward instead.");
                }
                lastStepWasBack = false;

                // Before counting this as a hard app exit, check whether a genuine runtime
                // PERMISSION dialog briefly took the foreground. That is the only foreign surface
                // we ever tap inside — tapping its "Allow"/"While using the app" button lets the
                // feature under test complete its flow. Every other foreground (Settings, the
                // launcher, the Play Store, a browser, any other installed app) is NEVER tapped:
                // isPermissionDialog() excludes them, so they fall straight through to the
                // force-stop + relaunch recovery below. This is what stops the framework from ever
                // being seen driving Settings or any other app.
                if (isPermissionDialog(currentPkg) && handleSystemDialog(driver)) {
                    sleep(600);
                    String nowPkg = safe(driver::getCurrentPackage);
                    if (pkg.equals(nowPkg)) {
                        steps++;
                        lastActionEnd = System.currentTimeMillis();
                        continue;
                    }
                }

                // Any foreign app that isn't a permission dialog or protected system UI (Settings,
                // Play Store, browser, another installed app) is force-stopped immediately so the
                // crawl can never wander into it, then the app under test is relaunched below.
                if (shouldForceStopForeign(currentPkg, pkg)) {
                    try { ctx.adb().forceStop(ctx.serial(), currentPkg); } catch (Exception ignored) {}
                }

                appExits++;
                if (appExits > MAX_APP_EXITS || relaunches >= MAX_RELAUNCHES) {
                    result.setLeftAppDuringRun(true);
                    result.note("Stopped after the app left the foreground " + appExits + " time(s). "
                            + "It exits on Back from its root or closes itself; explored "
                            + visitedStates.size() + " screen(s) first.");
                    break;
                }
                relaunches++;
                if (!ensureInApp(ctx, driver, pkg)) {
                    result.setLeftAppDuringRun(true);
                    result.note("Successfully returned to the app — No. App could not be brought back to the foreground.");
                    break;
                }
                result.note("Successfully returned to the app — Yes. Continuing execution from the app's current screen.");
                sleep(800);
                continue;
            }

            String source = readSourceWithRetry(driver);
            if (source == null) {
                // Distinguish a real app crash from a transient Appium/uiautomator2 hiccup.
                if (!ctx.adb().isAppRunning(serial, pkg)) {
                    result.setCrashSuspected(true);
                    result.note("App process died during exploration (likely crash).");
                    break;
                }
                result.note("Transient UI read failure (Appium proxy); app still alive — continuing.");
                if (!ensureInApp(ctx, driver, pkg)) break;
                steps++;
                continue;
            }
            String activity = safe(driver::currentActivity);
            if (activity == null) activity = "<unknown>";

            // Live progress instrumentation — pure side-effect, no logic change.
            ctx.setLiveProgress("step", steps);
            ctx.setLiveProgress("totalSteps", maxSteps);
            ctx.setLiveProgress("screensFound", result.getUniqueScreenCount());
            ctx.setLiveProgress("currentScreen", friendlyScreenName(activity));

            List<Widget> widgets = parse(source);
            // ── Semantics-tree-not-yet-attached retry ────────────────────────────────────
            // Jetpack Compose (and some other declarative UI toolkits) can render the visual
            // frame before its accessibility semantics tree finishes attaching, especially right
            // after a screen transition. getPageSource() returns a perfectly valid, non-blank XML
            // in that state (so readSourceWithRetry's null/blank check doesn't catch it) — but it's
            // just a stack of generic, non-actionable, full-viewport wrapper containers with none
            // of the real content. Verified live: a screen with 12+ clickable rows read as 0
            // actionable widgets a few seconds after transition, then attached fully ~5s later.
            // Reading it in that empty window makes a real, richly-interactive screen look like a
            // dead end, burning the whole step budget on Back-recovery instead of ever seeing its
            // actual content. One bounded re-read after a short wait is enough to catch the common
            // case without slowing down every normal (already-attached) screen read.
            if (isLikelyUnattachedSemanticsTree(widgets)) {
                sleep(1500);
                String retrySrc = readSourceWithRetry(driver);
                if (retrySrc != null) {
                    List<Widget> retryWidgets = parse(retrySrc);
                    if (!isLikelyUnattachedSemanticsTree(retryWidgets)) widgets = retryWidgets;
                }
            }
            String sig = stateSignature(activity, widgets);
            // activity:structureHash — the stable screen-structure key used for visit counting
            // and retroactive widget learning. Computed once per iteration and reused below.
            String sigPrefix = sig.contains(":") ? sig.substring(0, sig.lastIndexOf(':')) : sig;
            long loadTime = System.currentTimeMillis() - lastActionEnd;
            // Link the action tapped on the previous iteration to the screen it landed on.
            if (graphLastSig != null) {
                graph.recordEdge(graphLastSig, graphLastKey, sigPrefix);
                graphLastSig = null;
                graphLastKey = null;
            }
            // First screen seen on the launcher/main activity is the home screen — remember its
            // structure so Back is never pressed from it (it would exit the app to the launcher).
            if (rootSigPrefix == null && launcherSimple != null
                    && activitySimpleName(activity).equalsIgnoreCase(launcherSimple)) {
                rootSigPrefix = sigPrefix;
            }

            // ── Retroactive structureExecuted learning ──────────────────────────────────────
            // If the widget we just executed led to a state we've already visited (the current
            // sig is in visitedStates), that widget leads only to already-explored territory.
            // Record it so chooseAction() skips it on the next content-variant of the same screen.
            // Clearing lastExecWidgetKey unconditionally ensures stale tracking doesn't persist
            // across non-widget navigation steps (Back, scroll, swipe).
            if (lastExecWidgetKey != null && visitedStates.contains(sig)) {
                structureExecuted.computeIfAbsent(lastExecSigPrefix, k -> new HashSet<>())
                        .add(lastExecWidgetKey);
            }
            lastExecWidgetKey = null;
            lastExecSigPrefix = null;

            // ── AI-assisted ad review — second opinion, once per screen structure ──────────
            // The heuristic checks below (isAdScreen/isAdWidget) run first, always, and catch
            // the overwhelming majority of ad SDKs. This only ever ADDS widgets an unfamiliar/
            // custom-rendered ad network's CTA slipped past, merged into the same per-activity
            // blacklist chooseAction() already consults — never overrides or loosens heuristic
            // protection, and no-ops entirely when AI Review is disabled/unconfigured/fails.
            if (llmAdClassifier.isEnabled() && llmAdCheckedSigPrefixes.add(sigPrefix)) {
                Set<String> flaggedSigs = llmAdClassifier.flagAdWidgetSignatures(activity, widgets);
                if (!flaggedSigs.isEmpty()) {
                    Set<String> keys = new HashSet<>();
                    for (Widget w : widgets) if (flaggedSigs.contains(w.signature())) keys.add(activityWidgetKey(w, widgets));
                    activityExternalBlacklist.computeIfAbsent(activity, k -> new HashSet<>()).addAll(keys);
                    result.note("AI ad review flagged " + keys.size() + " additional widget(s) as ad content on '"
                            + activity + "' — excluded from interaction.");
                }
            }

            // ── Full-screen WebView interstitial — dismiss via Back ────────────────────────
            // AdMob (and most SDK) interstitials render as a single opaque WebView that fills the
            // screen, with the close "X" drawn INSIDE the web content — invisible to Appium's
            // native tree, so findAdCloseButton() can never see it. On these screens the only
            // native node is the WebView itself (which is never tapped), so the crawl would treat
            // it as a dead end and burn its step/convergence budget bouncing on it. Pressing Back
            // reliably dismisses an AdMob interstitial, so do that immediately and do NOT count it
            // against convergence — it's an ad interruption, not the app running out of screens.
            // Ad-heavy apps that throw an interstitial after nearly every tap depend on this to
            // keep making forward progress. (Consistent with the framework's existing stance that
            // a WebView is ad content — see isAdWidget.)
            if (isFullScreenWebViewOverlay(widgets)) {
                result.note("Full-screen WebView interstitial detected on '" + activity
                        + "' (close control is inside the ad WebView) — dismissing via Back.");
                safe(() -> { driver.navigate().back(); return null; });
                sleep(900);
                steps++;
                lastActionEnd = System.currentTimeMillis();
                lastStepWasBack = false;
                continue;
            }

            // ── Ad overlay handling — checked BEFORE recording or acting on the screen ──
            // Ad content is never tapped. When a full-screen ad is detected we wait up to 5 s
            // for its Close/Skip/Dismiss/X button, tap it if found, and continue testing the
            // app's own UI — the ad itself is never interacted with either way. Applies to all
            // ad formats: Banner (full-screen), Interstitial, Native, Rewarded, Rewarded
            // Interstitial, App Open, and third-party SDK overlays. (AdMob / Firebase Ads
            // Testing is the sole exception — it deliberately interacts with ads to validate
            // them, and does not run through this shared crawl loop.)
            if (isAdScreen(activity, widgets)) {
                String adFormat = adFormatGuess(activity, widgets);
                // Try the close button that may already be visible before waiting.
                Widget closeBtn = findAdCloseButton(widgets);
                result.note("Ad found — Type: " + adFormat + " — Screen: " + activity);
                if (closeBtn == null && adHandledSigs.add(sig)) {
                    // Button not yet visible — wait up to 5 s for it to appear (e.g. a
                    // skippable-after-5s countdown), without ever tapping the ad content.
                    log.debug("{} ad detected on {} — waiting up to 5 s for Close button…", adFormat, activity);
                    closeBtn = waitForAdCloseButton(driver, 5000);
                }
                if (closeBtn != null) {
                    log.debug("{} ad dismissed on {}.", adFormat, activity);
                    result.note("Close button found — Yes. Ad successfully closed without interacting with its content.");
                    tap(driver, closeBtn.x() + closeBtn.width() / 2, closeBtn.y() + closeBtn.height() / 2);
                } else {
                    // Close button not found within 5 s — never tap the ad; press Back as a
                    // last resort and continue testing the app's own UI.
                    log.debug("{} ad Close button not found on {} — pressing Back.", adFormat, activity);
                    result.note("Close button found — No (waited 5s). Ad skipped without interacting with its content; continuing app testing.");
                    safe(() -> { driver.navigate().back(); return null; });
                }
                sleep(900);
                steps++;
                lastActionEnd = System.currentTimeMillis();
                lastStepWasBack = false;
                continue;
            }

            // ── Subscription / paywall upsell handling ──────────────────────────────────
            // Never buys a plan: a genuine paywall (subscribe/upgrade CTA + a safe way out) is
            // dismissed via its "Not now"/"Maybe later"/"Continue free"/Close control, never by
            // tapping the purchase button itself. Mirrors the ad-overlay block above, minus the
            // countdown wait since paywalls don't have one.
            if (isPaywallScreen(activity, widgets)) {
                Widget dismiss = findPaywallDismissButton(widgets);
                if (dismiss != null) {
                    result.note("Subscription/paywall screen detected on '" + activity
                            + "' — dismissed via '" + dismiss.text() + "' without purchasing.");
                    tap(driver, dismiss.x() + dismiss.width() / 2, dismiss.y() + dismiss.height() / 2);
                } else {
                    result.note("Subscription/paywall screen detected on '" + activity
                            + "' — no safe dismiss control found, pressed Back without purchasing.");
                    safe(() -> { driver.navigate().back(); return null; });
                }
                sleep(700);
                steps++;
                lastActionEnd = System.currentTimeMillis();
                lastStepWasBack = false;
                continue;
            }

            // ── Exit-confirmation dialog detection ────────────────────────────────
            // Root screens often respond to Back with a "Do you want to exit?" dialog
            // instead of actually closing the app. Without special handling the engine
            // loops: exhausts Home actions → Back → dialog → Cancel → Home → Back → …
            //
            // Detection: we just pressed Back (lastStepWasBack) AND the resulting
            // screen matches exit-dialog patterns (Exit + Cancel buttons, same activity
            // or a dedicated dialog activity/overlay). When detected:
            //   1. Dismiss once (tap Cancel / No / Stay).
            //   2. Record the triggering sig so Back is never tried from it again.
            //   3. Continue without recording this dialog as a screen to explore.
            if (lastStepWasBack && lastBackSig != null && isExitDialog(widgets)) {
                Widget dismiss = findExitDialogDismissButton(widgets);
                sigsWhereBackTriggersExit.add(lastBackSig);
                // Also record the structure prefix (activity:structureHash) so that future visits
                // to the same screen with a different content hash (dynamic badge/timestamp) are
                // also blocked from pressing Back — this breaks the Back→dialog→dismiss→Back loop.
                String lastBackPrefix = lastBackSig.contains(":")
                        ? lastBackSig.substring(0, lastBackSig.lastIndexOf(':'))
                        : lastBackSig;
                exitDialogStructurePrefixes.add(lastBackPrefix);
                // Activity-level guard: if the screen structure changes between visits (dynamic
                // badge, loading indicator alters the widget tree → new structureHash), the prefix
                // check above may not match on the next visit. Blocking the entire activity from
                // Back is always safe for root screens — they never have a legitimate parent.
                if (lastBackActivity != null) backExitsActivities.add(lastBackActivity);
                result.note("Exit confirmation dialog detected after Back on '"
                        + lastBackActivity + "' — dismissing once and marking as root activity.");
                if (dismiss != null) {
                    // Pre-mark the dismiss button as executed so chooseAction never re-fires it
                    // if the dialog is accidentally reached again through another path.
                    executedActions.add(sig + "|" + dismiss.signature());
                    tap(driver, dismiss.x() + dismiss.width() / 2, dismiss.y() + dismiss.height() / 2);
                    log.debug("Exit dialog dismissed via '{}' on {}.", dismiss.text(), activity);
                } else {
                    // No dismiss button found — press Back to close the overlay.
                    safe(() -> { driver.navigate().back(); return null; });
                    log.debug("Exit dialog: no dismiss button found on {}; used Back to close.", activity);
                }
                sleep(700);
                lastStepWasBack = false;
                lastBackSig = null;
                lastExecWidgetKey = null;
                lastExecSigPrefix = null;
                steps++;
                lastActionEnd = System.currentTimeMillis();
                continue;
            }

            if (visitedStates.add(sig)) {
                // Count visits per screen STRUCTURE ("activity:structureHash"), not per activity.
                // This prevents the over-cap path from firing on the first visit to a Home screen
                // when many earlier fragments share the same activity (single-Activity / Jetpack Nav apps).
                int visits = structureVisitCounts.merge(sigPrefix, 1, Integer::sum);
                if (visits <= MAX_VISITS_PER_ACTIVITY) {
                    // Only the FIRST visit to a given structure resets noProgress — that is genuine
                    // new territory. Subsequent visits with the same structure but a new content hash
                    // (dynamic badges, timestamps) are content churn; they do NOT reset noProgress so
                    // the convergence counter keeps climbing instead of oscillating.
                    if (visits == 1) {
                        noProgress = 0;
                        String simple = activity.contains(".")
                                ? activity.substring(activity.lastIndexOf('.') + 1)
                                : activity;
                        ctx.logScreen(toReadableName(simple));

                        // ── Full-screen widget discovery ──────────────────────────────────
                        // Silently scroll to the bottom and back, collecting every actionable
                        // widget (including those below the initial viewport) into the per-
                        // structure inventory. chooseAction() draws from this full set, and
                        // scrollToWidget() is used to reach any off-screen widget before tapping.
                        // This runs BEFORE the formal scrollSweep so both mechanisms start with
                        // the same widget population.
                        if (!screenWidgets.containsKey(sigPrefix)) {
                            LinkedHashMap<String, Widget> inv = new LinkedHashMap<>();
                            for (Widget w : widgets) {
                                if (w.actionable() && !isAdWidget(w, widgets) && !isSubscriptionWidget(w)) inv.putIfAbsent(widgetKey(w), w);
                            }
                            try { discoverBelowFold(driver, ctx, inv, widgets); }
                            catch (Exception e) { log.debug("Below-fold discovery failed: {}", e.toString()); }
                            screenWidgets.put(sigPrefix, inv);
                            // Register this screen and its actionable widgets as the graph frontier.
                            graph.observeScreen(sigPrefix, activity, inv.keySet());
                            List<Widget> widgetsSnapshot = widgets;
                            log.debug("Screen {} inventory: {} widget(s) ({} from below fold)",
                                    simple, inv.size(),
                                    Math.max(0, inv.size() - (int) widgetsSnapshot.stream().filter(w -> w.actionable() && !isAdWidget(w, widgetsSnapshot) && !isSubscriptionWidget(w)).count()));
                        }
                    }
                    int thisIndex = screenIndex++;
                    String shot = captureShot(ctx, driver, "screen-" + thisIndex);
                    // Fully sweep scrollable screens once per activity: discover, validate, capture.
                    if (hasScrollable(widgets) && sweptActivities.add(activity)) {
                        try {
                            result.addScrollReport(scrollSweep(ctx, driver, thisIndex, activity, widgets, shot));
                        } catch (Exception e) {
                            log.debug("Scroll sweep failed on {}: {}", activity, e.toString());
                        }
                    }
                    result.addScreen(new ScreenCapture(thisIndex, sig, activity, widgets, shot, loadTime));
                    if (!activity.equals(prevActivity)) {
                        result.recordTransition(prevActivity, activity);
                    }
                } else {
                    // Over the per-structure cap — screen generates unbounded dynamic content sigs.
                    // Pre-mark all actions so the engine moves on.
                    if (visits == MAX_VISITS_PER_ACTIVITY + 1) {
                        result.note("Screen '" + sigPrefix + "' has produced " + visits
                                + " content variants; capping to prevent infinite dynamic-content looping.");
                    }
                    noProgress++;
                    for (Widget w : widgets) {
                        if (w.actionable() && !isAdWidget(w, widgets) && !isSubscriptionWidget(w)) {
                            executedActions.add(sig + "|" + w.signature());
                        }
                    }
                }
            } else {
                // Revisiting a known screen — increment the stall counter but do NOT break yet.
                // The convergence check fires only in the dead-end section so that in-screen
                // button/input actions (which reset noProgress to 0) keep the counter from
                // accumulating while there is still productive work to do on this screen.
                noProgress++;
            }
            prevActivity = activity;

            // Also accumulate the currently visible widgets into the per-screen inventory so that
            // repeated visits (with shifted content hashes) keep the set up to date.
            {
                LinkedHashMap<String, Widget> inv =
                        screenWidgets.computeIfAbsent(sigPrefix, k -> new LinkedHashMap<>());
                for (Widget w : widgets) {
                    if (w.actionable() && !isAdWidget(w, widgets) && !isSubscriptionWidget(w)) inv.putIfAbsent(widgetKey(w), w);
                }
            }

            // Build the full candidate list: currently visible widgets first (they have current
            // coordinates), followed by any below-fold widgets discovered during the initial sweep.
            List<Widget> candidates = new ArrayList<>(widgets);
            {
                LinkedHashMap<String, Widget> inv = screenWidgets.get(sigPrefix);
                if (inv != null) {
                    for (Map.Entry<String, Widget> e : inv.entrySet()) {
                        if (candidates.stream().noneMatch(w -> widgetKey(w).equals(e.getKey()))) {
                            candidates.add(e.getValue());
                        }
                    }
                }
            }

            Widget target = chooseAction(candidates, sig, executedActions, sigPrefix, structureExecuted,
                    activityExternalBlacklist.getOrDefault(activity, Set.of()));
            if (target == null) {
                // ── Dead-end recovery #1: progressive vertical scroll ────────────
                // Reveals controls below the visible viewport (long settings lists, etc.).
                int scrolled = deadEndScrolls.getOrDefault(sig, 0);
                if (hasScrollable(widgets) && scrolled < MAX_DEADEND_SCROLLS) {
                    deadEndScrolls.put(sig, scrolled + 1);
                    scrollDown(driver, ctx);
                    lastStepWasBack = false;
                    lastExecWidgetKey = null;
                    lastExecSigPrefix = null;
                    steps++;
                    lastActionEnd = System.currentTimeMillis();
                    continue;
                }
                // ── Dead-end recovery #2: horizontal swipe (ViewPager / carousel) ─
                // Advances through swipe-only onboarding or horizontal carousels that
                // have no "Next" button — the content-aware signature will treat each
                // revealed page as a new state so actions are re-executed on each page.
                int horizSwiped = deadEndHorizSwipes.getOrDefault(sig, 0);
                if (hasPagerOrCarousel(widgets) && horizSwiped < MAX_DEADEND_HORIZ_SWIPES) {
                    deadEndHorizSwipes.put(sig, horizSwiped + 1);
                    log.debug("Dead-end horizontal swipe #{} on {}", horizSwiped + 1, activity);
                    swipe(driver, ctx, "horizontal", true);
                    sleep(SETTLE_AFTER_ACTION_MS);
                    lastStepWasBack = false;
                    lastExecWidgetKey = null;
                    lastExecSigPrefix = null;
                    steps++;
                    lastActionEnd = System.currentTimeMillis();
                    continue;
                }
                // ── Dead-end recovery #2.7: drive an OPAQUE / canvas screen ──
                // Flutter, game-engine and fully-custom-canvas apps expose no usable accessibility
                // tree — the whole screen is one/few full-screen View nodes with no text and
                // clickable=false, so chooseAction finds nothing and the crawl stalls on the first
                // onboarding screen. On such a screen we pick a tap point by the best available
                // strategy, then use a perceptual screenshot hash to tell whether it navigated
                // (the node tree can neither distinguish canvas screens nor detect navigation).
                // Strategy order, most precise first:
                //   (A) OCR — read the on-screen text and tap a call-to-action label ("Continue",
                //       "Start", "Allow"…). API-free; the primary opaque-screen strategy.
                //   (B) Vision — a vision model returns the control's pixel coords (opt-in; a no-op
                //       unless AI Review / a local model is configured).
                //   (C) Blind grid — tap an ordered CTA/grid of points as a last resort.
                // A jitter baseline (a second frame with no action) makes navigation detection
                // robust to animated screens (e.g. a playing onboarding video). Fully generic — no
                // app-specific names — and the foreground guard still corrects any mis-tap that
                // leaves the app.
                {
                    int scrW = 0, scrH = 0;
                    for (Widget w : widgets) { scrW = Math.max(scrW, w.x() + w.width()); scrH = Math.max(scrH, w.y() + w.height()); }
                    if (looksOpaqueCanvas(widgets, scrW, scrH)) {
                        byte[] beforePng = safeScreenshotBytes(driver);
                        long hBefore = aHash(beforePng);
                        if (hBefore != 0L) {
                            sleep(400);
                            int jitter = hamming(hBefore, aHash(safeScreenshotBytes(driver)));
                            int changeThreshold = Math.max(OPAQUE_MIN_CHANGE_BITS, jitter + OPAQUE_JITTER_MARGIN_BITS);

                            int screenK = -1;
                            for (int k = 0; k < opaqueHashes.size(); k++)
                                if (hamming(opaqueHashes.get(k), hBefore) <= OPAQUE_SAME_SCREEN_BITS) { screenK = k; break; }
                            if (screenK < 0) { opaqueHashes.add(hBefore); opaqueCursors.add(0); screenK = opaqueHashes.size() - 1; }

                            int tx = -1, ty = -1;
                            String how = null;

                            // (A) OCR-guided CTA tap.
                            if (ocrEngine.isAvailable()) {
                                int[] pt = chooseOcrCtaPoint(beforePng, scrW, scrH, screenK, ocrTriedLabels);
                                if (pt != null && !isAdContentAt(widgets, pt[0], pt[1])) { tx = pt[0]; ty = pt[1]; how = "OCR-guided"; }
                            }
                            // (B) Vision-guided tap.
                            int visionUsedHere = visionTapsPerSig.getOrDefault(sig, 0);
                            if (tx < 0 && visionNavigator.isEnabled()
                                    && visionTapsThisRun < VISION_MAX_PER_RUN && visionUsedHere < VISION_MAX_PER_SIG) {
                                List<int[]> tried = visionTriedPoints.computeIfAbsent(sig, k -> new ArrayList<>());
                                String appCtx = ctx.apkInfo() != null ? ctx.apkInfo().getApplicationLabel() : null;
                                com.vasundhara.atf.ai.VisionNavigator.Tap t =
                                        visionNavigator.chooseTapPoint(beforePng, scrW, scrH, appCtx, tried);
                                if (t != null && !isAdContentAt(widgets, t.x(), t.y())) {
                                    visionTapsThisRun++;
                                    visionTapsPerSig.merge(sig, 1, Integer::sum);
                                    tried.add(new int[]{ t.x() * 100 / Math.max(1, scrW), t.y() * 100 / Math.max(1, scrH) });
                                    tx = t.x(); ty = t.y(); how = "Vision-guided";
                                }
                            }
                            // (C) Blind grid.
                            if (tx < 0) {
                                int cursor = opaqueCursors.get(screenK);
                                if (cursor < OPAQUE_CANDIDATES.length) {
                                    opaqueCursors.set(screenK, cursor + 1);
                                    int gx = (int) (scrW * OPAQUE_CANDIDATES[cursor][0]);
                                    int gy = (int) (scrH * OPAQUE_CANDIDATES[cursor][1]);
                                    if (!isAdContentAt(widgets, gx, gy)) { tx = gx; ty = gy; how = "Blind-grid"; }
                                }
                            }

                            if (tx >= 0) {
                                result.note(how + " exploration: tapping (" + tx + "," + ty + ") on an opaque/canvas screen.");
                                tap(driver, tx, ty);
                                result.incrementActions();
                                sleep(SETTLE_AFTER_ACTION_MS);
                                long hAfter = aHash(safeScreenshotBytes(driver));
                                boolean known = false;
                                for (Long hv : opaqueHashes) if (hamming(hv, hAfter) <= OPAQUE_SAME_SCREEN_BITS) { known = true; break; }
                                if (hAfter != 0L && !known && hamming(hAfter, hBefore) > changeThreshold) {
                                    opaqueHashes.add(hAfter);
                                    opaqueCursors.add(0);
                                    int idx2 = screenIndex++;
                                    String shot = captureShot(ctx, driver, "screen-" + idx2);
                                    result.addScreen(new ScreenCapture(idx2, sig + "|vh:" + Long.toHexString(hAfter),
                                            activity, widgets, shot, loadTime));
                                    noProgress = 0;
                                    result.note(how + " tap advanced to a new screen (image changed "
                                            + hamming(hAfter, hBefore) + " bits > threshold " + changeThreshold
                                            + "; animation jitter " + jitter + ").");
                                }
                                lastStepWasBack = false;
                                lastExecWidgetKey = null;
                                lastExecSigPrefix = null;
                                steps++;
                                lastActionEnd = System.currentTimeMillis();
                                continue;
                            }
                            // All strategies exhausted for this visual screen — genuine dead end;
                            // fall through to Back / convergence below.
                        }
                    }
                }
                // ── Dead-end recovery #2.5: LLM-guided escape ────────────────────
                // The heuristic found no new action here. On gated single-Activity / Compose apps
                // the way deeper is often a non-obvious control the ranking didn't prioritise. Ask
                // the LLM to pick a control that likely opens UNEXPLORED area, and tap it instead of
                // backing out. Fully bounded (per-run + per-structure caps), opt-in (AI Review), and
                // safety-filtered: only controls that are not ads / purchase CTAs / known external
                // links are offered, and the pick is re-verified before tapping — so it can never
                // be steered onto ad or payment content or out of the app.
                int sigEscapes = llmEscapesPerSig.getOrDefault(sigPrefix, 0);
                if (llmNavigator.isEnabled() && llmEscapes < LLM_ESCAPE_MAX_PER_RUN
                        && sigEscapes < LLM_ESCAPE_MAX_PER_SIG) {
                    List<Widget> safe = new ArrayList<>();
                    for (Widget w : candidates) {
                        if (!w.actionable()) continue;
                        // Only offer widgets currently on screen — this escape taps directly without
                        // a scroll-to-widget step, so an off-screen widget's stored coordinates
                        // would mis-tap.
                        if (!isCurrentlyVisible(w, widgets)) continue;
                        if (isAdWidget(w, widgets) || isSubscriptionWidget(w)) continue;
                        if (isLikelyExternalLinkWidget(w, widgets)) continue;
                        if (activityExternalBlacklist.getOrDefault(activity, Set.of())
                                .contains(activityWidgetKey(w, widgets))) continue;
                        safe.add(w);
                    }
                    if (!safe.isEmpty()) {
                        String goal = "explore an unexplored screen, feature, or section not yet visited";
                        int idx = llmNavigator.chooseNextTap(activity, goal, safe);
                        if (idx >= 0 && idx < safe.size()) {
                            Widget pick = safe.get(idx);
                            if (!isAdWidget(pick, widgets) && !isSubscriptionWidget(pick)
                                    && !isLikelyExternalLinkWidget(pick, widgets)) {
                                llmEscapes++;
                                llmEscapesPerSig.merge(sigPrefix, 1, Integer::sum);
                                result.note("LLM-guided exploration: tapping '" + labelFor(pick, widgets)
                                        + "' to reach unexplored area from '" + activity + "'.");
                                executedActions.add(sig + "|" + pick.signature());
                                lastExecWidgetKey = activityWidgetKey(pick, widgets);
                                lastExecSigPrefix = sigPrefix;
                                lastExecActivity = activity;
                                performAction(driver, pick);
                                result.incrementActions();
                                lastStepWasBack = false;
                                sleep(SETTLE_AFTER_ACTION_MS);
                                steps++;
                                lastActionEnd = System.currentTimeMillis();
                                continue;
                            }
                        }
                    }
                }
                // ── Dead-end recovery #3: Back navigation ────────────────────────
                // Skip Back if we already know it exits the app OR triggers an exit-
                // confirmation dialog from this exact sig OR this screen's structure prefix.
                // sigPrefix is already computed above for each iteration.
                boolean backBlockedByExitDialog = sigsWhereBackTriggersExit.contains(sig)
                        || exitDialogStructurePrefixes.contains(sigPrefix);
                boolean atHomeScreen = rootSigPrefix != null && rootSigPrefix.equals(sigPrefix);
                if (backExitsActivities.contains(activity) || backBlockedByExitDialog || atHomeScreen) {
                    // Root screen with no remaining actions — do not press Back (it exits the app
                    // or triggers the exit dialog again). This is a genuine dead end; increment the
                    // stall counter and break once it hits the ceiling. The convergence message uses
                    // the actual screen count so the report shows real coverage.
                    noProgress++;
                    if (noProgress >= MAX_NO_PROGRESS_STEPS) {
                        // Frontier-directed exploration: before giving up, if the graph still has a
                        // screen with un-tried actions, replay a tap path from here to reach it and
                        // keep exploring — this is what turns "converged at home" into real extra
                        // coverage on apps whose deeper screens weren't reached by plain DFS.
                        if (frontierDrives < MAX_FRONTIER_DRIVES && graph.hasFrontier()) {
                            List<String> path = graph.pathToNearestFrontier(sigPrefix, MAX_FRONTIER_PATH);
                            if (path != null && !path.isEmpty()) {
                                frontierDrives++;
                                result.note("Frontier-directed exploration: replaying " + path.size()
                                        + " step(s) to a screen with un-tried actions ("
                                        + graph.frontierScreens() + " such screen(s) remain).");
                                if (replayPath(driver, path)) {
                                    noProgress = 0;
                                    lastStepWasBack = false;
                                    lastExecWidgetKey = null;
                                    lastExecSigPrefix = null;
                                    steps++;
                                    lastActionEnd = System.currentTimeMillis();
                                    continue;
                                }
                            }
                        }
                        result.note("Exploration converged — root screen '" + activity
                                + "' blocked Back for " + noProgress + " consecutive steps. "
                                + "Visited " + visitedStates.size() + " unique screen(s), "
                                + result.getActionsPerformed() + " interactions performed. "
                                + graph.summary());
                        break;
                    }
                    result.note("Root screen '" + activity + "' has no remaining actions — Back blocked"
                            + (backBlockedByExitDialog ? " (exit dialog)" : " (exits app)")
                            + ". Continuing (" + visitedStates.size() + " screen(s) so far).");
                    lastExecWidgetKey = null;
                    lastExecSigPrefix = null;
                    continue;
                }
                // Record which sig we are backing out of so the next iteration can link any
                // exit dialog that appears back to this sig.
                lastBackSig = sig;
                safe(() -> { driver.navigate().back(); return null; });
                lastStepWasBack = true;
                lastBackActivity = activity;
                lastExecWidgetKey = null;
                lastExecSigPrefix = null;
                steps++;
                lastActionEnd = System.currentTimeMillis();
                continue;
            }

            // ── Scroll-to-widget ─────────────────────────────────────────────────────────
            // If the chosen widget came from the below-fold inventory and is not currently
            // in the visible viewport, scroll until it becomes visible. Re-read the hierarchy
            // so the widget's coordinates match the scrolled layout before tapping.
            if (!isCurrentlyVisible(target, widgets) && hasScrollable(widgets)) {
                try {
                    boolean found = scrollToWidget(driver, ctx, target);
                    if (found) {
                        sleep(SETTLE_AFTER_ACTION_MS);
                        String freshSrc = readSourceWithRetry(driver);
                        if (freshSrc != null) {
                            List<Widget> fresh = parse(freshSrc);
                            String tKey = widgetKey(target);
                            Widget located = fresh.stream()
                                    .filter(w -> widgetKey(w).equals(tKey))
                                    .findFirst().orElse(null);
                            if (located != null) {
                                target = located;
                                widgets = fresh;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Scroll-to-widget failed for {}: {}", widgetKey(target), e.toString());
                }
            }

            executedActions.add(sig + "|" + target.signature());
            // Track for retroactive learning: if the NEXT iteration lands on an already-visited
            // state, this widget will be added to structureExecuted to prevent re-execution.
            lastExecWidgetKey = activityWidgetKey(target, widgets);
            lastExecSigPrefix = sigPrefix;
            lastExecActivity = activity;
            // Navigation graph: this action is now tried on this screen; remember it as the edge
            // source so the next iteration can record where it led.
            String targetKey = widgetKey(target);
            graph.markTried(sigPrefix, targetKey);
            graphLastSig = sigPrefix;
            graphLastKey = targetKey;
            boolean acted = performAction(driver, target);
            if (acted) {
                result.incrementActions();
                // Reset the stall counter: we just performed a productive interaction.
                // This prevents premature convergence on screens with many in-screen elements
                // (long settings lists, forms, etc.) where each action keeps the screen the
                // same but is genuine forward progress in testing the current screen.
                noProgress = 0;
            }
            lastStepWasBack = false;
            sleep(SETTLE_AFTER_ACTION_MS);   // let the UI transition before next capture
            steps++;
            lastActionEnd = System.currentTimeMillis();
        }

        result.setDurationMillis(System.currentTimeMillis() - start);
        // Honest coverage summary from the navigation graph (screens, transitions, actions
        // exercised vs discovered, frontier remaining) — added once here so it's present whether
        // the crawl converged, exhausted its step budget, or was cancelled.
        result.note(graph.summary());
        log.info("Exploration finished: {} unique screens, {} actions, {} activities | {}",
                result.getUniqueScreenCount(), result.getActionsPerformed(),
                result.getActivitiesReached().size(), graph.summary());
        return result;
    }

    // ---- navigation helpers ----------------------------------------------

    /** Read the UI hierarchy, retrying through transient proxy/socket hiccups. */
    /**
     * True when a widget list has the fingerprint of a Compose/declarative-UI semantics tree that
     * hasn't finished attaching yet: a small handful of nodes, none actionable, none carrying any
     * text/content-desc — just generic wrapper containers with no real content or controls. A
     * genuinely blank/loading screen matches this too, in which case the one bounded re-read below
     * is harmless (costs one short wait, changes nothing if it's still blank).
     */
    public static boolean isLikelyUnattachedSemanticsTree(List<Widget> widgets) {
        if (widgets == null || widgets.isEmpty() || widgets.size() > 10) return false;
        for (Widget w : widgets) {
            if (w.actionable()) return false;
            if (w.text() != null && !w.text().isBlank()) return false;
            if (w.contentDesc() != null && !w.contentDesc().isBlank()) return false;
        }
        return true;
    }

    private String readSourceWithRetry(AndroidDriver driver) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String src = driver.getPageSource();
                if (src != null && !src.isBlank()) return src;
            } catch (Exception e) {
                log.debug("getPageSource attempt {} failed: {}", attempt, e.getMessage());
            }
            sleep(900);
        }
        return null;
    }

    private boolean ensureInApp(TestContext ctx, AndroidDriver driver, String pkg) {
        try {
            String current = driver.getCurrentPackage();
            if (pkg.equals(current)) return true;
            // Known external surface (Play Store / browser — where an ad click-through, "Install",
            // or a "Rate us"/social link lands): never press Back inside it (a browser Back just
            // goes to its previous page, keeping us in the foreign app). Force-stop it outright so
            // the framework is never seen operating inside it, then relaunch the app under test.
            if (isExternalSurface(current)) {
                try { ctx.adb().forceStop(ctx.serial(), current); } catch (Exception ignored) {}
                sleep(400);
                driver.activateApp(pkg);
                sleep(800);
                if (pkg.equals(driver.getCurrentPackage())) return true;
            }
            // Back out of any system dialogs / chrome custom tabs we wandered into.
            for (int i = 0; i < 2; i++) {
                driver.navigate().back();
                sleep(400);
                current = driver.getCurrentPackage();
                if (pkg.equals(current)) return true;
            }
            driver.activateApp(pkg);
            sleep(800);
            if (pkg.equals(driver.getCurrentPackage())) return true;

            // Last resort: a foreign app — Settings, another installed app reached via a bundled
            // SDK's account chooser or a deep link, etc. — can hold the foreground even after
            // activateApp(). Force-stop it (Settings included; only the launcher/SystemUI are
            // spared, since killing those would break the device) so it can never masquerade as,
            // or crash in place of, the app under test for the rest of the run.
            if (shouldForceStopForeign(current, pkg)) {
                try { ctx.adb().forceStop(ctx.serial(), current); } catch (Exception ignored) {}
                sleep(400);
                driver.activateApp(pkg);
                sleep(800);
            }
            return pkg.equals(driver.getCurrentPackage());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Polls for the app package returning to the foreground <em>on its own</em> within a short
     * window (no relaunch action taken here). Used to distinguish a self-restart — an app applying
     * a language/theme/locale change via recreate() or a process restart, which comes back by
     * itself — from a genuine exit (ad click-through, Back-to-launcher, external link), which does
     * not. Returns true if the app reappears, false if it stays gone.
     */
    private boolean waitForAppReturn(AndroidDriver driver, String pkg) {
        for (int i = 0; i < SELF_RESTART_POLLS; i++) {
            sleep(SELF_RESTART_POLL_MS);
            if (pkg.equals(safe(driver::getCurrentPackage))) return true;
        }
        return false;
    }

    /** Last dot/slash-separated segment of an activity name (e.g. "com.x/.MainActivity" → "MainActivity"). */
    private static String activitySimpleName(String activity) {
        if (activity == null || activity.isBlank()) return null;
        String a = activity;
        int slash = a.indexOf('/');
        if (slash >= 0) a = a.substring(slash + 1);
        int dot = a.lastIndexOf('.');
        return dot >= 0 ? a.substring(dot + 1) : a;
    }

    /**
     * A foreground package the framework must never be seen operating inside and must leave
     * instantly: the Play Store's own listing (where an ad's "Install"/"Open" lands) and any web
     * browser (where an ad click-through or a "Rate us"/social link lands). For these, pressing
     * Back would navigate <em>within</em> that app (a browser Back goes to its previous page), so
     * callers force-stop them outright the moment they appear rather than navigating them.
     */
    public static boolean isExternalSurface(String pkg) {
        return com.vasundhara.atf.device.ForeignAppPolicy.isExternalSurface(pkg);
    }

    /**
     * The ONLY foreign foreground surface the framework is allowed to tap inside: a genuine
     * runtime-permission grant dialog. Delegates to {@link com.vasundhara.atf.device.ForeignAppPolicy}
     * so the crawler, the ADB-fallback crawler, the step engine and the device-level foreground
     * guard all share one definition.
     */
    public static boolean isPermissionDialog(String pkg) {
        return com.vasundhara.atf.device.ForeignAppPolicy.isPermissionDialog(pkg);
    }

    /**
     * True when {@code foreground} is a package the framework must leave by force-stopping it —
     * anything that is NOT the app under test, NOT a permission dialog, and NOT protected system
     * UI. Delegates to the shared {@link com.vasundhara.atf.device.ForeignAppPolicy} so every
     * caller (including the continuous foreground guard) applies exactly the same generic rule.
     */
    public static boolean shouldForceStopForeign(String foreground, String appPkg) {
        return com.vasundhara.atf.device.ForeignAppPolicy.shouldForceStopForeign(foreground, appPkg);
    }

    /**
     * Handles system overlay dialogs that briefly bring a different package to the foreground —
     * Android permission grants (hotspot enable, location, notification), Google Play billing
     * sheets, and app-rating prompts. Reads the current UI, taps the most permissive positive
     * button to allow the feature under test to complete its flow, or dismisses a
     * subscription/billing sheet without purchasing.
     *
     * <p>Called BEFORE incrementing appExits so that successfully handled system dialogs do
     * not count against the exit cap.
     *
     * @return true if a button was tapped (caller should re-check the active package)
     */
    private boolean handleSystemDialog(AndroidDriver driver) {
        try {
            String src = readSourceWithRetry(driver);
            if (src == null) return false;
            List<Widget> widgets = parse(src);

            // Ordered priority: grant / enable labels first (maximise feature coverage),
            // then dismiss-without-action labels for rating/etc.
            String[] grantLabels = {
                "while using the app", "only this time", "allow", "always allow",
                "allow all the time", "allow in settings", "always", "grant",
                "enable", "turn on", "ok", "yes", "continue", "agree", "accept",
                "proceed", "confirm", "allow access", "allow permission"
            };
            String[] dismissLabels = {
                "not now", "no thanks", "maybe later", "cancel", "no", "close",
                "dismiss", "skip", "later", "remind me later"
            };

            // Purchase safety: if this surface is a payment/billing/subscription sheet, NEVER tap a
            // positive/grant button — several grant labels ("ok", "continue", "confirm", "proceed")
            // would confirm a real-money purchase on a billing/checkout confirmation. Take only a
            // safe dismiss control; if none is present, return false so the caller leaves via Back.
            boolean purchaseSurface = false;
            for (Widget w : widgets) {
                if (w.displayed() && isSubscriptionWidget(w)) { purchaseSurface = true; break; }
            }

            if (!purchaseSurface) {
                for (String label : grantLabels) {
                    for (Widget w : widgets) {
                        if (!w.displayed() || isSubscriptionWidget(w)) continue;
                        String txt = (w.text() != null ? w.text() : "").trim().toLowerCase();
                        String dsc = (w.contentDesc() != null ? w.contentDesc() : "").trim().toLowerCase();
                        if (txt.equals(label) || dsc.equals(label)) {
                            tap(driver, w.x() + w.width() / 2, w.y() + w.height() / 2);
                            sleep(700);
                            return true;
                        }
                    }
                }
            }
            for (String label : dismissLabels) {
                for (Widget w : widgets) {
                    if (!w.displayed() || isSubscriptionWidget(w)) continue;
                    String txt = (w.text() != null ? w.text() : "").trim().toLowerCase();
                    if (txt.equals(label)) {
                        tap(driver, w.x() + w.width() / 2, w.y() + w.height() / 2);
                        sleep(700);
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Widget chooseAction(List<Widget> widgets, String sig, Set<String> executed,
                                String sigPrefix, Map<String, Set<String>> structureExecuted,
                                Set<String> activityBlacklist) {
        // structureExecuted contains widgets retroactively learned to lead only to already-visited
        // states from this screen structure. Skip them to avoid redundant re-execution cycles.
        Set<String> structDone = structureExecuted.getOrDefault(sigPrefix, Set.of());
        List<Widget> actionable = new ArrayList<>(widgets.stream()
                .filter(Widget::actionable)
                .filter(w -> !isAdWidget(w, widgets) && !isSubscriptionWidget(w))
                // Never queue a control whose own label already signals it leaves the app (Play
                // Store rating/review, social share/follow icons, legal webview links, app-store
                // cross-promotion) — catches these BEFORE the first tap, complementing the
                // activityBlacklist below (which only ever learns AFTER a tap already left the
                // app once). Mirrors FunctionalTest's isLikelyExternalLinkWidget() guard.
                .filter(w -> !isLikelyExternalLinkWidget(w, widgets))
                .toList());
        // Screen geometry — used to recognise a bottom-navigation strip in Compose apps where the
        // tab items are bare android.view.View nodes with no resource-id or class hint. The decor
        // view's extent approximates the screen size.
        int screenW = 0, screenH = 0;
        for (Widget w : widgets) {
            screenW = Math.max(screenW, w.x() + w.width());
            screenH = Math.max(screenH, w.y() + w.height());
        }
        final int sw = screenW, sh = screenH;

        // Image-grid / Jetpack-Compose fallback: when the screen exposes almost no conventional
        // actionable controls, its real navigation targets are likely image/view tiles whose click
        // handler sits on a parent (so the tile node reports clickable=false and was filtered out
        // above). Promote sizable, non-ad tiles so the crawl can move through feature grids instead
        // of stalling on the launch screen. Requires >=2 similar tiles (a grid); a no-op on apps
        // that already expose proper buttons. Coordinate-tapping a tile triggers the parent onClick.
        if (actionable.size() < 3) {
            List<Widget> tiles = new ArrayList<>();
            for (Widget w : widgets) {
                if (!isTileLikeTapTarget(w, sw, sh)) continue;
                if (isAdWidget(w, widgets) || isSubscriptionWidget(w)) continue;
                if (isLikelyExternalLinkWidget(w, widgets)) continue;
                tiles.add(w);
            }
            if (tiles.size() >= 2) actionable.addAll(tiles);
        }

        actionable.sort((a, b) -> Integer.compare(rank(a, widgets, sw, sh), rank(b, widgets, sw, sh)));
        for (Widget w : actionable) {
            if (!executed.contains(sig + "|" + w.signature())
                    && !structDone.contains(activityWidgetKey(w, widgets))
                    && !activityBlacklist.contains(activityWidgetKey(w, widgets))) {
                return w;
            }
        }
        return null;
    }

    /**
     * High-confidence "this leads outside the app" labels — Play Store rating/review prompts,
     * social share/follow icons, legal-webview links, and app-store cross-promotion. Deliberately
     * conservative (no generic "help"/"support"/"contact us") since this runs on every APK
     * regardless of domain and a false match here silently skips real in-app coverage. Identical
     * to FunctionalTest's own EXTERNAL_LINK_LABELS — kept in sync so the two crawlers (this
     * shared engine, used by Analyze APK and other Appium categories, and FunctionalTest's own
     * DFS walk) treat the same kinds of controls consistently.
     */
    private static final Pattern EXTERNAL_LINK_LABELS = Pattern.compile(
            "\\b(rate (us|this app)|rate (it |on )?(play ?store|google play)|write a review|leave a review|"
            + "share this app|invite friends|tell (a )?friend|"
            + "follow us|follow @|facebook|instagram|twitter|"
            + "whatsapp|telegram|linkedin|tiktok|youtube channel|"
            + "privacy policy|terms (of service|and conditions|&amp;? conditions)|open source licen[cs]es|eula|"
            + "check out our other apps|more apps by|other apps by|visit our website)\\b");

    private static boolean isLikelyExternalLinkWidget(Widget w, List<Widget> all) {
        String label = (labelFor(w, all) + " " + (w.resourceId() == null ? "" : w.resourceId())).toLowerCase();
        return EXTERNAL_LINK_LABELS.matcher(label).find();
    }

    /**
     * Recognises a "tile" tap target for image-grid / Jetpack-Compose launcher UIs: a displayed,
     * enabled Image/View/CardView node of button-or-larger size (but not a full-screen container)
     * whose click handler lives on a parent — so the node itself reports {@code clickable=false}
     * and is missed by {@link Widget#actionable()}. Coordinate-tapping its centre still fires the
     * parent's onClick, letting the crawl navigate feature grids it would otherwise stall in front
     * of. Fully generic (class/size heuristics only). {@code sw}/{@code sh} are the screen extents,
     * used to reject full-screen containers.
     */
    /**
     * True when the current screen exposes essentially no usable accessibility tree — the
     * fingerprint of a Flutter / game-engine / fully-custom-canvas UI, where the entire screen
     * (text, buttons and all) is painted inside one or a few full-screen {@code View}/{@code
     * FrameLayout} nodes that carry no text/content-desc and are not individually clickable.
     * On such a screen the node-based crawler has nothing to reason about, so the caller falls
     * back to a blind coordinate tap. Requires: no node with a readable label, no conventional
     * (non-full-screen) actionable control, at least one full-screen container, and a small total
     * node count — so a normal, richly-populated native screen never matches.
     */
    private static boolean looksOpaqueCanvas(List<Widget> widgets, int sw, int sh) {
        if (widgets == null || widgets.isEmpty()) return false;
        long screenArea = (long) sw * sh;
        if (screenArea <= 0) return false;
        int withText = 0, realControls = 0, fullScreenish = 0;
        for (Widget w : widgets) {
            if (w.hasLabel()) withText++;
            if (w.actionable() && (long) w.area() < screenArea * 60 / 100) realControls++;
            if ((long) w.area() >= screenArea * 60 / 100) fullScreenish++;
        }
        return withText == 0 && realControls == 0 && fullScreenish >= 1 && widgets.size() <= 15;
    }

    public static boolean isTileLikeTapTarget(Widget w, int sw, int sh) {
        if (w == null || w.actionable() || w.editable() || w.scrollable()) return false;
        if (!w.displayed() || !w.enabled()) return false;
        String cls = w.simpleClass().toLowerCase();
        boolean tileClass = cls.contains("image")        // ImageView / ImageButton / AppCompatImageView
                || cls.equals("view")                    // bare android.view.View — a common Compose leaf
                || cls.contains("composeview")
                || cls.contains("cardview");
        if (!tileClass) return false;
        if (w.minSide() < 64) return false;              // too small to be a real tile / button
        if (sw > 0 && sh > 0 && (long) w.area() > (long) sw * sh * 55 / 100) return false; // full-screen container
        return true;
    }

    /**
     * Returns the effective text label for a widget. Compose controls are frequently bare
     * {@code android.view.View} nodes with no text/desc of their own; the visible label is a
     * separate, non-clickable Text node positioned inside the clickable's bounds. This resolves
     * that label so ranking and dedup can "see" what a Compose control actually is.
     */
    private static String labelFor(Widget w, List<Widget> all) {
        if (w.text() != null && !w.text().isBlank())        return w.text().trim();
        if (w.contentDesc() != null && !w.contentDesc().isBlank()) return w.contentDesc().trim();
        if (all == null) return "";
        int cx = w.x() + w.width() / 2;
        int cy = w.y() + w.height() / 2;
        String best = "";
        long bestArea = Long.MAX_VALUE;
        for (Widget t : all) {
            if (t == w) continue;
            String txt = t.text() != null && !t.text().isBlank() ? t.text().trim()
                       : (t.contentDesc() != null && !t.contentDesc().isBlank() ? t.contentDesc().trim() : "");
            if (txt.isEmpty() || txt.length() > 40) continue;     // labels are short phrases, not paragraphs
            if (cx >= t.x() && cx <= t.x() + t.width() && cy >= t.y() && cy <= t.y() + t.height()) {
                long area = (long) t.width() * t.height();
                if (area > 0 && area < bestArea) { bestArea = area; best = txt; }
            }
        }
        return best;
    }

    /**
     * Structure-level widget dedup key used by retroactive learning to prevent re-executing
     * a widget that has already been confirmed to lead only to visited territory.
     *
     * <p>When a resource-id is present it uniquely identifies the element across content variants
     * of the same screen (badge-count changes, etc.) — use class+id as a stable key.
     *
     * <p>When no resource-id is present (typical in Jetpack Compose apps), fall back to the
     * visible text label, then content-desc, then screen position. This prevents all resource-
     * ID-less elements from collapsing to the same key (e.g. "View|") which would cause the
     * first retroactive-learning hit to block every element of that class on the screen.
     */
    private static String activityWidgetKey(Widget w) {
        return activityWidgetKey(w, null);
    }

    private static String activityWidgetKey(Widget w, List<Widget> all) {
        String id = (w.resourceId() == null || w.resourceId().isBlank()) ? "" : w.resourceId();
        if (!id.isEmpty()) return w.simpleClass() + "|" + id;
        // No resource-id — distinguish by associated label first (stable across content variants),
        // then by position. labelFor() resolves a Compose control's visible text from an
        // overlapping Text node so the key is meaningful (e.g. "View|Settings") rather than a bare
        // position that shifts when the screen scrolls.
        String label = labelFor(w, all);
        if (label.isEmpty()) label = "@" + w.x() + "," + w.y();
        return w.simpleClass() + "|" + label;
    }

    /**
     * Third-party ad-network names recognized across class names, resource-ids, and activity
     * names. Kept as a single shared list so a network added/removed in one context (e.g. a class
     * name check) is automatically recognized in the others (resource-id, activity name) instead
     * of drifting into three independently-maintained copies.
     */
    private static final String[] AD_NETWORK_TOKENS = {
            "vungle", "applovin", "ironsource", "chartboost", "mopub", "adcolony", "inmobi",
            "startapp", "tapjoy", "smaato", "pubmatic", "criteo", "fyber", "unityads",
            "audiencenetwork", "mbridge", "flurry", "millennialmedia", "tremor", "verizonmedia"
    };

    /** Matches {@code raw} (a class name, resource-id, or activity name) against
     *  {@link #AD_NETWORK_TOKENS}, ignoring underscores/dots so "audience_network" and
     *  "AudienceNetworkAdView" both match the "audiencenetwork" token. */
    private static boolean matchesAdNetwork(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String norm = raw.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String token : AD_NETWORK_TOKENS) {
            if (norm.contains(token)) return true;
        }
        return false;
    }

    /**
     * Returns true when the widget is part of ad content that must never be tapped.
     * Covers all standard ad types: Banner, Interstitial, Rewarded, App Open, Native.
     * Close/dismiss/skip buttons are intentionally NOT matched here so they remain
     * tappable via {@link #findAdCloseButton}.
     */
    public static boolean isAdWidget(Widget w) {
        String cls  = w.className()   == null ? "" : w.className().toLowerCase();
        String id   = w.resourceId()  == null ? "" : w.resourceId().toLowerCase();
        String desc = w.contentDesc() == null ? "" : w.contentDesc().toLowerCase();
        String txt  = w.text()        == null ? "" : w.text().toLowerCase();
        // Ad SDK view classes (AdMob and common third-party SDKs)
        if (cls.contains("adview") || cls.contains("nativeadview")
                || cls.contains("adiconview") || cls.contains("mediaview")
                || cls.contains("adchoicesview") || cls.contains("unifiedadview")
                || cls.contains("bannerview") || cls.contains("interstitialview")
                || cls.contains("mraid") || cls.contains("nativebannerad")
                || matchesAdNetwork(cls)) return true;
        // Resource-id patterns: Banner, Interstitial, Rewarded, App Open, Native containers
        // AND the child elements of a native-ad template (headline / body / icon / media / CTA),
        // so the crawler never taps an embedded ad's "Install"/CTA button and leaves the app.
        if (id.contains("adview")     || id.contains("ad_view")
                || id.contains("banner_ad")  || id.contains("ad_banner")
                || id.contains("ad_container") || id.contains("admob")
                || id.contains("native_ad")  || id.contains("rewarded_ad")
                || id.contains("app_open_ad") || id.contains("ad_frame")
                || id.contains("ad_overlay") || id.contains("ad_placeholder")
                || id.contains("ad_slot") || id.contains("ads_container")
                || id.contains("adplaceholder") || id.contains("ad_layout")
                || id.contains("ad_root") || id.contains("ad_wrapper")
                || id.contains("gam_") || id.contains("dfp_")
                || id.contains("_cta") || id.contains("cta_")        // btn_cta, cta_button
                || id.contains("ad_headline") || id.contains("ad_body")
                || id.contains("ad_icon") || id.contains("ad_media")
                || id.contains("adchoices") || id.contains("sponsored")
                || id.contains("fan_ad") || matchesAdNetwork(id)) return true;
        // Google Mobile Services (GMS) prefix, or "gms" anywhere in the id — present in every
        // AdMob ad, never in genuine app content.
        if (id.startsWith("com.google.android.gms") || id.contains("gms.ads")) return true;
        // WebView: nearly every ad SDK (MRAID banners, interstitials, native web ads) renders
        // through a WebView, and it's the single most common vector for an ad tap opening the
        // Play Store or a browser. Legitimate in-app WebView content (ToS pages, hybrid screens)
        // exists too, but the risk of missing an ad far outweighs the cost of not tapping into a
        // WebView during automated exploration — every other category still explores the rest of
        // the screen normally, it just never taps INTO a WebView's own content.
        if (cls.contains("webview")) return true;
        // Content-desc / text ad markers. "ad"/"ads" as an EXACT match (not substring — that would
        // false-positive on words like "add"/"adjust"/"radio") catches the ubiquitous 2-3 letter
        // attribution badge nearly every native-ad template renders (AdMob, Facebook Audience
        // Network, and most third-party SDKs all use a bare "Ad"/"AD" chip) — this badge is what
        // {@link #nearAdMarker} relies on to protect a native ad's CTA button regardless of the
        // CTA's own wording, so missing it here was letting whole native ad cards slip through.
        // "AdChoices" is Google's mandatory attribution/transparency icon on every native ad
        // (opens an ad-preferences page when tapped) — its resource-id is already covered above,
        // but the icon is frequently a bare ImageView identified only by its accessibility
        // content-desc/text (e.g. "AdChoices icon", "Ad Choices"), which was missed here.
        if (desc.equals("advertisement") || desc.contains("sponsored")
                || desc.contains("close ad") || desc.contains("skip ad")
                || desc.equals("ad") || desc.equals("ads") || desc.contains("adchoices")
                || desc.contains("ad choices")) return true;
        if (txt.equals("advertisement") || txt.equals("sponsored")
                || txt.contains("test ad") || txt.startsWith("ad :")
                || txt.startsWith("ad:") || txt.equals("ad") || txt.equals("ads")
                || txt.contains("adchoices") || txt.contains("ad choices")) return true;
        return false;
    }

    /**
     * Stronger variant used wherever the full widget list is already available (so Compose's
     * merged-semantics label resolution via {@link #labelFor} can run). Last-resort safety net:
     * a clickable widget with no text, no content-desc, no resource-id, AND no associated label
     * from an overlapping Text node is exactly the fingerprint of a dynamically-rendered native
     * ad creative from an SDK whose naming convention isn't covered by {@link #isAdWidget(Widget)}
     * — there are dozens of ad networks, each with its own, and an exhaustive allowlist is a
     * losing battle. A genuinely anonymous, unlabeled clickable element in a real app is rare
     * (poor accessibility practice); the cost of skipping the occasional one is far lower than
     * tapping an unrecognized ad and leaving the app.
     */
    public static boolean isAdWidget(Widget w, List<Widget> all) {
        if (isAdWidget(w)) return true;
        if (!w.clickable()) return false;
        String txt  = w.text()        == null ? "" : w.text().trim();
        String desc = w.contentDesc() == null ? "" : w.contentDesc().trim();
        String id   = w.resourceId()  == null ? "" : w.resourceId().trim();
        if (txt.isEmpty() && desc.isEmpty() && id.isEmpty()) {
            return labelFor(w, all).isEmpty();
        }
        // Native-ad-card CTA button: any clickable widget sitting physically next to a CONFIRMED
        // ad marker (a "Sponsored"/"Ad" badge, or an ad-SDK container caught by
        // isAdWidget(Widget)) is part of that ad card, no matter what its own label says — a CTA
        // can read "Install", "Get Quote", "Sign Up", "Apply Now", or anything in any language;
        // an English keyword allowlist can never be exhaustive across every advertiser's wording
        // and every locale. Proximity to a real marker is direct, language-agnostic evidence, so
        // it alone is enough here — this is the fix for native ads whose CTA wording isn't one of
        // the common English verbs (e.g. an insurance/finance ad's "Get Quote" button).
        if (nearAdMarker(w, all)) return true;
        // Weaker signal, no explicit marker found anywhere on screen: the native-ad-card LAYOUT
        // SHAPE (icon + headline/body clustered around the button) still needs the CTA's own
        // wording to corroborate it, since shape alone is a much softer signal than a confirmed
        // marker and would otherwise flag ordinary in-app cards (e.g. a product tile with an
        // "Order Now" button) that just happen to look similar.
        String label = !txt.isEmpty() ? txt : desc;
        if (AD_CTA_LABELS.matcher(label).find() && hasNativeAdCardShapeNearby(w, all)) return true;
        return false;
    }

    /**
     * Fully local, zero-configuration equivalent of an "AI review" ad check — no API key, no
     * network call, works the same for every user out of the box. Recognizes the classic native-
     * ad card LAYOUT SHAPE (a small icon-sized image + a short title and/or a longer body text,
     * all mutually close to the candidate CTA button) even when the card carries NO "Ad"/
     * "Sponsored"/"Test Ad" marker text at all — the one case {@link #nearAdMarker} can't catch
     * because it requires an explicit marker somewhere on screen. Gated on the caller already
     * having matched {@link #AD_CTA_LABELS}, so this only ever fires for a button with genuine
     * ad-CTA wording that ALSO sits in an ad-card-shaped cluster — an ordinary in-app "Install"/
     * "Download" button with no icon+description cluster around it is left untouched.
     */
    private static boolean hasNativeAdCardShapeNearby(Widget cta, List<Widget> all) {
        String ctaLabel = !isBlank(cta.text()) ? cta.text().trim()
                         : (!isBlank(cta.contentDesc()) ? cta.contentDesc().trim() : "");
        boolean hasIcon = false, hasTitle = false, hasBody = false;
        for (Widget n : all) {
            if (n == cta || !boundsNear(cta, n)) continue;
            if (isIconLikeImage(n)) hasIcon = true;
            String t = n.text() == null ? "" : n.text().trim();
            if (t.isEmpty() || t.equalsIgnoreCase(ctaLabel)) continue;
            if (t.length() <= 50) hasTitle = true;
            else if (t.length() <= 200) hasBody = true;
        }
        return hasIcon && (hasTitle || hasBody);
    }

    /** A small, icon-sized image node — the app-icon slot of a native ad template, not a full
     *  banner/hero image or the app's own content photos (which run much larger). */
    private static boolean isIconLikeImage(Widget w) {
        String cls = w.simpleClass().toLowerCase();
        if (!cls.contains("image")) return false;
        int side = w.minSide();
        return side > 0 && side <= 200;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static final Pattern AD_CTA_LABELS = Pattern.compile(
            "\\b(install(\\s*now)?|download(\\s*now)?|download\\s*free|get\\s*it|play\\s*now|" +
            "open\\s*app|shop\\s*now|learn\\s*more|visit\\s*site|order\\s*now|book\\s*now|" +
            "try\\s*(it\\s*)?now|watch\\s*now|start\\s*now|claim\\s*(now|offer))\\b",
            Pattern.CASE_INSENSITIVE);

    /** True when a real ad marker (matched by the single-widget {@link #isAdWidget(Widget)} check)
     *  sits close enough to {@code w} on screen to be part of the same native-ad card. */
    private static boolean nearAdMarker(Widget w, List<Widget> all) {
        for (Widget m : all) {
            if (m == w || !isAdWidget(m)) continue;
            if (boundsNear(w, m)) return true;
        }
        return false;
    }

    /** Generous rectangle-proximity test — native ad card elements (icon, headline, body, CTA)
     *  are laid out as close siblings, but NOT always with any particular overlap axis: an
     *  icon-left/text-right row (common in native ad templates) has zero horizontal overlap
     *  between the icon and the headline/AD-badge next to it, and a stacked text-above-button
     *  layout has zero vertical overlap either. Requiring one specific overlap axis (as an
     *  earlier version of this check did) missed exactly the icon case — inflating both boxes by
     *  a shared margin and testing for intersection catches every common arrangement while still
     *  being bounded (won't match unrelated content elsewhere on a long screen). */
    private static boolean boundsNear(Widget a, Widget b) {
        int margin = Math.max(60, Math.max(
                Math.max(a.height(), b.height()), Math.max(a.width(), b.width())) / 2);
        boolean horizNear = a.x() - margin <= b.x() + b.width() && b.x() - margin <= a.x() + a.width();
        boolean vertNear  = a.y() - margin <= b.y() + b.height() && b.y() - margin <= a.y() + a.height();
        return horizNear && vertNear;
    }

    /**
     * True when the screen is dominated by a single opaque WebView with no other actionable native
     * content — the fingerprint of a full-screen SDK interstitial (AdMob et al.) whose close "X" is
     * rendered INSIDE the WebView and is therefore invisible to Appium's native tree (so
     * {@link #findAdCloseButton} can never find it). These are dismissed via Back. Deliberately
     * strict — the WebView must cover almost the whole viewport AND nothing else may be tappable —
     * so a hybrid screen with a native toolbar/nav around a WebView does NOT match and its native
     * chrome is still explored normally.
     */
    static boolean isFullScreenWebViewOverlay(List<Widget> widgets) {
        if (widgets == null || widgets.isEmpty()) return false;
        int screenW = 0, screenH = 0;
        for (Widget w : widgets) {
            screenW = Math.max(screenW, w.x() + w.width());
            screenH = Math.max(screenH, w.y() + w.height());
        }
        if (screenW <= 0 || screenH <= 0) return false;
        boolean bigWebView = false;
        for (Widget w : widgets) {
            if (w.simpleClass().toLowerCase().contains("webview")
                    && w.width() >= (int) (screenW * 0.90)
                    && w.height() >= (int) (screenH * 0.80)) {
                bigWebView = true;
                break;
            }
        }
        if (!bigWebView) return false;
        // If any non-ad native control is actionable, there's real content to explore — not a pure
        // interstitial overlay (the WebView itself is filtered out by isAdWidget).
        for (Widget w : widgets) {
            if (w.actionable() && !isAdWidget(w, widgets)) return false;
        }
        return true;
    }

    /**
     * Returns true ONLY when the current screen is a genuine FULL-SCREEN ad overlay
     * (Interstitial, Rewarded, Rewarded Interstitial, or App Open) that must be closed
     * before any app interaction is possible.
     *
     * <p><b>Why activity-aware:</b> the previous implementation returned true whenever ANY
     * ad marker appeared on screen — including a single "Test Ad" banner text or one
     * {@code native_ad_view}. Real apps embed banner ads on their Home screen and native ads
     * inside dialogs (e.g. an exit-confirmation bottom sheet). Treating those content screens
     * as full-screen ads sent the crawler into the ad branch (wait → press Back) on every
     * visit, which (a) prevented Home from ever being explored and (b) prevented the
     * exit-confirmation dialog from being recognised — the engine pressed Back, the sheet
     * reappeared, was again mis-classified as an ad, Back was pressed again… an infinite
     * Back → exit-sheet → Back loop. This was the true root cause of the reported loop.
     *
     * <p>A genuine full-screen ad overlay is launched by the ad SDK in its OWN dedicated
     * activity (AdMob {@code com.google.android.gms.ads.AdActivity}, AppLovin, Unity,
     * Facebook AudienceNetwork, ironSource, etc.). A banner/native ad embedded in app content
     * lives inside the app's own activity and must NOT be treated as a full-screen overlay —
     * the crawler simply avoids tapping its widgets via {@link #isAdWidget} and explores the
     * rest of the screen normally.
     *
     * @param activity the current foreground activity name (may be null)
     * @param widgets  the parsed UI hierarchy
     */
    public static boolean isAdScreen(String activity, List<Widget> widgets) {
        // 1) Dedicated full-screen ad activities — the only reliable signal of a true overlay.
        String act = activity == null ? "" : activity.toLowerCase();
        if (act.contains("adactivity")               // AdMob/GMS interstitial & app-open
                || act.contains(".ads.ad")            // com.google.android.gms.ads.Ad*
                || act.contains("interstitialactivity")
                || act.contains("rewardedactivity")
                || act.contains("appopenad")
                || act.contains("unity3d.ads")
                || act.contains("mraid")
                || matchesAdNetwork(act)) {
            return true;
        }
        // 2) Within the app's own activity, an in-app full-screen overlay is only assumed when a
        //    dedicated interstitial/rewarded/app-open CONTAINER is present together with a matching
        //    ad close/skip/countdown control. A bare banner ("Test Ad" text, native_ad_view, GMS
        //    banner) embedded in content does NOT qualify — those are handled by isAdWidget.
        boolean overlayContainer = false;
        boolean overlayCloseCtl  = false;
        for (Widget n : widgets) {
            String id = n.resourceId() == null ? "" : n.resourceId().toLowerCase();
            if (id.contains("interstitial") || id.contains("rewarded_ad")
                    || id.contains("app_open_ad") || id.contains("ad_overlay")) {
                overlayContainer = true;
            }
            if (id.contains("interstitial_close") || id.contains("rewarded_close")
                    || id.contains("ad_countdown") || id.contains("countdown_timer")) {
                overlayCloseCtl = true;
            }
        }
        return overlayContainer && overlayCloseCtl;
    }

    /**
     * Best-effort label for the ad format behind a detected {@link #isAdScreen} overlay, purely
     * for readable logging/reporting — never used to change avoidance behavior, which treats
     * every format identically (never tap the ad, only its Close/Skip/Dismiss control).
     */
    private static String adFormatGuess(String activity, List<Widget> widgets) {
        String act = activity == null ? "" : activity.toLowerCase();
        if (act.contains("rewarded")) return "Rewarded";
        if (act.contains("interstitial")) return "Interstitial";
        if (act.contains("appopenad")) return "App Open";
        if (act.contains("audiencenetwork") || act.contains("applovin") || act.contains("mbridge")
                || act.contains("unity3d.ads") || act.contains("ironsource")
                || act.contains("vungle") || act.contains("mraid")) return "Third-party";
        for (Widget w : widgets) {
            String id = w.resourceId() == null ? "" : w.resourceId().toLowerCase();
            if (id.contains("rewarded_ad")) return "Rewarded";
            if (id.contains("interstitial")) return "Interstitial";
            if (id.contains("app_open_ad")) return "App Open";
            if (id.contains("native_ad")) return "Native";
        }
        return "Full-screen";
    }

    /**
     * Returns the Close/X/Skip/Dismiss button from the widget list, or {@code null} if not yet
     * visible. Does NOT require {@code clickable=true} — AdMob close buttons are custom touch
     * targets whose XML dump has {@code clickable="false"} but respond to coordinate taps.
     */
    private static Widget findAdCloseButton(List<Widget> widgets) {
        for (Widget n : widgets) {
            String id  = n.resourceId()  == null ? "" : n.resourceId().toLowerCase();
            String dsc = n.contentDesc() == null ? "" : n.contentDesc().trim().toLowerCase();
            String txt = n.text()        == null ? "" : n.text().trim().toLowerCase();
            // Google Mobile Services close/skip/countdown button
            if (id.contains("com.google.android.gms")
                    && (id.contains("close") || id.contains("skip")
                        || id.contains("countdown") || id.contains("dismiss"))) return n;
            // Generic ad-SDK close button resource-id patterns
            if (id.contains("interstitial_close") || id.contains("rewarded_close")
                    || id.contains("close_button") || id.contains("ad_close")
                    || id.contains("dismiss_button") || id.contains("skip_button")
                    || id.contains("close_ad") || id.contains("btn_close")
                    || id.contains("iv_close") || id.contains("img_close")) return n;
            // Content-desc based (× icon, "Close Ad", "Skip")
            if (dsc.equals("close") || dsc.equals("close ad") || dsc.equals("close button")
                    || dsc.equals("dismiss ad") || dsc.equals("skip ad")
                    || dsc.equals("skip") || dsc.equals("dismiss")
                    || dsc.equals("×") || dsc.equals("x")) return n;
            // Text-based fallback
            if (txt.equals("close") || txt.equals("×") || txt.equals("x")
                    || txt.equals("dismiss") || txt.equals("skip")) return n;
        }
        return null;
    }

    // Currency tokens: symbols (incl. ₹ INR, ₩, ₦, ₱, ₨) and common text codes — so a rupee or
    // "USD 4.99/month" price on a paywall is recognised, not just $/€/£/¥.
    private static final String CURRENCY = "(?:[$€£¥₹₩₦₱₨]|rs\\.?|inr|usd|eur|gbp|aud|cad|aed)";

    // Matches a recurring subscription price string, e.g. "$4.99/mo", "₹299 / year", "USD 9.99 per month".
    private static final Pattern SUBSCRIPTION_PRICE_PAT = Pattern.compile(
            CURRENCY + "\\s?\\d+(?:[.,]\\d{1,2})?\\s*(?:/|per)\\s*(?:mo|month|wk|week|yr|year)\\b",
            Pattern.CASE_INSENSITIVE);

    // Matches a one-time-purchase amount paired with a completion keyword, e.g. "Pay $49.99",
    // "Total: ₹499", "Buy for $9.99", "Order Total $25.00" — a bare price tag on a product card
    // ("$29.99") is deliberately NOT enough on its own (too common on ordinary browsable content);
    // requiring the adjacent keyword keeps this specific to an actual completion control.
    private static final Pattern ONE_TIME_PRICE_CTA_PAT = Pattern.compile(
            "\\b(pay|total|buy for|amount due|order total|grand total)\\b.{0,12}"
            + CURRENCY + "\\s?\\d+(?:[.,]\\d{1,2})?", Pattern.CASE_INSENSITIVE);

    // Purchase-intent label match — verb-led so a plain "Premium" menu row, an empty-cart message,
    // or a "Payment Methods" settings label (no completion verb) is left alone and still explorable.
    // Deliberately does NOT include a bare "pay" (would misfire on a payments/wallet app's core
    // feature); real pay CTAs are still caught via "pay now"/"pay with"/the price patterns.
    private static final Pattern PURCHASE_LABEL_PAT = Pattern.compile(
            "\\b(subscribe( now)?|start( your)? free trial|start trial|free trial|"
            + "go premium|go pro|get premium|get pro|unlock (premium|pro|now|full|all|everything)|"
            + "upgrade( to)? (pro|premium|plan)|upgrade now|upgrade plan|"
            + "buy( now)?|buy (premium|pro|subscription|coins|credits|plan)|purchase( now)?|"
            + "continue to (payment|checkout)|choose (a )?plan|select (a )?plan|see plans|view plans|"
            + "place( your)? order|pay now|pay (with|via)\\b.*|"
            + "confirm (payment|order|purchase)|complete (purchase|order|payment)|"
            + "proceed to (pay|payment|checkout)|make payment|checkout( now)?|submit payment|"
            + "confirm ?& ?pay|confirm and pay|complete (your )?order|review (and )?pay|"
            + "add to cart|restore purchase|redeem)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Language-independent purchase-intent test on a control's visible label + resource-id. This is
     * the single source of truth for "would tapping this complete/advance a real-money in-app
     * purchase?", shared by {@link #isSubscriptionWidget} (Appium widgets) and the ADB-fallback
     * crawler (which only has text labels). Never tap anything this flags.
     */
    public static boolean isPurchaseText(String rawLabel, String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase();
        if (id.contains("paywall") || id.contains("subscribe") || id.contains("subscription")
                || id.contains("billing") || id.contains("upgrade_pro") || id.contains("go_premium")
                || id.contains("premium_cta") || id.contains("btn_upgrade") || id.contains("purchase")
                || id.contains("buy_subscription") || id.contains("checkout") || id.contains("place_order")
                || id.contains("buy_now") || id.contains("pay_now") || id.contains("btn_pay")
                || id.contains("confirm_payment") || id.contains("confirm_order")
                || id.contains("complete_purchase") || id.contains("proceed_to_pay")
                || id.contains("payment_button") || id.contains("cta_pay") || id.contains("add_to_cart")
                || id.contains("restore_purchase") || id.contains("iap_")) return true;
        String label = rawLabel == null ? "" : rawLabel.trim().toLowerCase();
        if (label.isEmpty()) return false;
        return PURCHASE_LABEL_PAT.matcher(label).find()
                || SUBSCRIPTION_PRICE_PAT.matcher(label).find()
                || ONE_TIME_PRICE_CTA_PAT.matcher(label).find();
    }

    /**
     * Returns true when the widget is itself a subscribe/upgrade/purchase/checkout/payment
     * call-to-action that must never be tapped — this is the in-app-paywall analogue of
     * {@link #isAdWidget}, and covers BOTH recurring subscriptions ("Subscribe Now", "$4.99/mo")
     * AND one-time purchases/checkout/payment confirmation ("Buy Now", "Place Order", "Pay Now",
     * "Confirm Payment") — any control that could complete a real-money transaction if tapped.
     * Unlike ad content (never legitimate to interact with), a "Premium"/"Checkout" label can
     * appear as ordinary, harmless copy (e.g. a settings row or an empty-cart message); to keep
     * false positives low this only matches strong purchase-intent phrasing (a verb + tier/price/
     * order, not a bare noun like "premium" or "cart") plus explicit resource-id conventions used
     * by real paywalls/billing/checkout SDKs.
     */
    public static boolean isSubscriptionWidget(Widget w) {
        String id   = w.resourceId()  == null ? "" : w.resourceId();
        String desc = w.contentDesc() == null ? "" : w.contentDesc().trim();
        String txt  = w.text()        == null ? "" : w.text().trim();
        String label = !txt.isEmpty() ? txt : desc;
        return isPurchaseText(label, id);
    }

    /**
     * Returns true only when the current screen is a genuine subscription/paywall upsell —
     * requires BOTH a subscription CTA widget AND a safe way out (a dismiss/skip/"maybe later"
     * control or a free/basic-tier option), mirroring {@link #isAdScreen}'s dual-signal guard so
     * an ordinary "Premium features" settings row isn't misclassified as a full paywall.
     */
    public static boolean isPaywallScreen(String activity, List<Widget> widgets) {
        boolean hasCta = false;
        boolean hasWayOut = false;
        for (Widget w : widgets) {
            if (isSubscriptionWidget(w)) hasCta = true;
            String id  = w.resourceId()  == null ? "" : w.resourceId().toLowerCase();
            String dsc = w.contentDesc() == null ? "" : w.contentDesc().trim().toLowerCase();
            String txt = w.text()        == null ? "" : w.text().trim().toLowerCase();
            String label = !txt.isEmpty() ? txt : dsc;
            if (id.contains("paywall_close") || id.contains("skip_paywall") || id.contains("dismiss_paywall")
                    || label.equals("not now") || label.equals("no thanks") || label.equals("maybe later")
                    || label.equals("skip") || label.equals("continue free") || label.equals("continue without")
                    || label.equals("×") || label.equals("x") || label.equals("close")) {
                hasWayOut = true;
            }
        }
        return hasCta && hasWayOut;
    }

    /**
     * Returns the safe way out of a detected paywall screen (never the purchase CTA itself):
     * a close/skip/"maybe later"/"continue free" control, in that preference order.
     */
    private static Widget findPaywallDismissButton(List<Widget> widgets) {
        for (Widget n : widgets) {
            String id  = n.resourceId()  == null ? "" : n.resourceId().toLowerCase();
            String dsc = n.contentDesc() == null ? "" : n.contentDesc().trim().toLowerCase();
            String txt = n.text()        == null ? "" : n.text().trim().toLowerCase();
            String label = !txt.isEmpty() ? txt : dsc;
            if (id.contains("paywall_close") || id.contains("skip_paywall") || id.contains("dismiss_paywall")
                    || id.contains("close_button") || id.contains("btn_close")) return n;
            if (label.equals("not now") || label.equals("no thanks") || label.equals("maybe later")
                    || label.equals("skip") || label.equals("continue free") || label.equals("continue without")
                    || label.equals("×") || label.equals("x") || label.equals("close")) return n;
        }
        return null;
    }

    /**
     * Returns {@code true} when the current screen looks like an exit-confirmation dialog.
     * This covers both full-screen alert dialogs and dialog fragments/AlertDialogs overlaid
     * on the triggering activity (where the activity name is unchanged).
     *
     * <p>Detection requires the presence of at least one "destructive" exit button (Exit, Quit,
     * Yes, Leave, Close App) AND at least one "safe" dismiss button (Cancel, No, Stay). This
     * two-button requirement prevents false positives on ordinary success/error dialogs.
     */
    private static boolean isExitDialog(List<Widget> widgets) {
        boolean hasExitButton    = false;
        boolean hasDismissButton = false;
        for (Widget w : widgets) {
            if (!w.displayed()) continue;
            String txt = (w.text()        != null ? w.text()        : "").trim().toLowerCase();
            String dsc = (w.contentDesc() != null ? w.contentDesc() : "").trim().toLowerCase();
            String id  = (w.resourceId()  != null ? w.resourceId()  : "").toLowerCase();
            // Destructive / exit-confirming button
            if (txt.equals("exit") || txt.equals("quit") || txt.equals("close app")
                    || txt.equals("leave") || txt.equals("yes, exit") || txt.equals("yes")
                    || txt.equals("exit app") || txt.equals("close") || txt.equals("exit now")
                    || txt.equals("leave app") || txt.equals("do exit") || txt.equals("terminate")
                    || txt.contains("exit the app") || txt.contains("quit the app")
                    || dsc.equals("exit") || dsc.equals("quit") || dsc.contains("exit app")
                    || id.contains("btn_exit") || id.contains("btn_quit")
                    || id.contains("confirm_exit") || id.contains("exit_confirm")
                    || id.contains("exit_btn") || id.contains("quit_btn")) {
                hasExitButton = true;
            }
            // Safe / stay-in-app button
            if (txt.equals("cancel") || txt.equals("no") || txt.equals("stay")
                    || txt.equals("not now") || txt.equals("no, stay") || txt.equals("back")
                    || txt.equals("dismiss") || txt.equals("keep") || txt.equals("continue")
                    || txt.equals("later") || txt.equals("no thanks") || txt.equals("don't exit")
                    || txt.equals("stay here") || txt.equals("remain") || txt.equals("no, keep")
                    || dsc.equals("cancel") || dsc.equals("no") || dsc.equals("stay")
                    || id.contains("btn_cancel") || id.contains("btn_no")
                    || id.contains("btn_stay") || id.contains("cancel_exit")
                    || id.contains("cancel_btn") || id.contains("no_btn")) {
                hasDismissButton = true;
            }
            if (hasExitButton && hasDismissButton) return true;
        }
        return false;
    }

    /**
     * Returns the "stay in app" / dismiss button from an exit-confirmation dialog, or
     * {@code null} if none found. Prefers explicit "Cancel" and "No" labels over
     * generic "Back" labels to avoid mis-tapping a navigation Back button.
     *
     * <p><b>Compose-aware:</b> in Jetpack Compose dialogs the visible "Cancel" label is often a
     * non-clickable Text node while the actual click target is a separate, text-less container
     * View at overlapping bounds. Because the crawler taps by coordinate, the matched label node
     * is returned directly when no clickable element carries the text — tapping its centre still
     * lands on the underlying handler. When a clickable element DOES carry the text it is
     * preferred (its bounds are the precise touch target).
     */
    private static Widget findExitDialogDismissButton(List<Widget> widgets) {
        // Priority order: explicit negative labels first, then generic fallbacks.
        String[] preferred = {
            "cancel", "no", "stay", "not now", "no, stay", "keep", "dismiss",
            "later", "no thanks", "don't exit", "stay here", "remain", "no, keep"
        };
        // Pass 1: a clickable element that itself carries the label (most precise target).
        for (String label : preferred) {
            for (Widget w : widgets) {
                if (!w.actionable() || !w.displayed()) continue;
                String txt = (w.text() != null ? w.text() : "").trim().toLowerCase();
                if (txt.equals(label)) return w;
            }
        }
        // Pass 2 (Compose): the label lives on a non-clickable Text node. Map it to the smallest
        // clickable container whose bounds contain the label's centre; if none, return the label
        // node itself (coordinate tap will still hit the underlying click handler).
        for (String label : preferred) {
            for (Widget w : widgets) {
                if (!w.displayed()) continue;
                String txt = (w.text() != null ? w.text() : "").trim().toLowerCase();
                if (!txt.equals(label)) continue;
                Widget container = enclosingClickable(widgets, w);
                return container != null ? container : w;
            }
        }
        // Pass 3: partial-match fallback on any displayed node carrying a negative label.
        for (Widget w : widgets) {
            if (!w.displayed()) continue;
            String txt = (w.text() != null ? w.text() : "").trim().toLowerCase();
            if (txt.equals("cancel") || txt.equals("no") || txt.startsWith("no,")
                    || txt.contains("stay") || txt.contains("later") || txt.contains("don't")) {
                Widget container = enclosingClickable(widgets, w);
                return container != null ? container : w;
            }
        }
        return null;
    }

    /**
     * Finds the smallest actionable widget whose bounds contain the centre of {@code label}.
     * Used to map a Compose Text label to the clickable container that actually handles taps.
     */
    private static Widget enclosingClickable(List<Widget> widgets, Widget label) {
        int cx = label.x() + label.width() / 2;
        int cy = label.y() + label.height() / 2;
        Widget best = null;
        long bestArea = Long.MAX_VALUE;
        for (Widget w : widgets) {
            if (!w.actionable() || !w.displayed()) continue;
            if (cx >= w.x() && cx <= w.x() + w.width()
                    && cy >= w.y() && cy <= w.y() + w.height()) {
                long area = (long) w.width() * w.height();
                if (area > 0 && area < bestArea) {
                    bestArea = area;
                    best = w;
                }
            }
        }
        return best;
    }

    /** Poll the UI every 800 ms until an ad close button appears or the timeout expires. */
    private Widget waitForAdCloseButton(AndroidDriver driver, long maxWaitMs) {
        long end = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) return null; // Stop requested — abort the wait immediately.
            List<Widget> current = parseCurrent(driver);
            Widget btn = findAdCloseButton(current);
            if (btn != null) return btn;
            sleep(800);
        }
        return null;
    }

    /**
     * Priority order for the action chooser — lower number = explored first.
     *
     * <p>The key design principle is <b>"complete one screen before navigating to the next"</b>:
     * all in-screen interactive elements (forms, toggles, buttons, dialogs) are tested before
     * the crawler follows a navigation bar or tab item to a different section.
     *
     * <pre>
     *  0 — Input / EditText fields       : form testing always first
     *  1 — Advance / gate buttons        : Continue/Next/Get Started/Skip — clears onboarding gates
     *  2 — Toggles / switches / checkboxes: state-changing same-screen controls
     *  3 — Navigation items               : bottom nav, tabs, drawer — explicitly navigates away
     *  4 — Regular buttons, FABs          : primary actions on the current screen
     *  5 — Images and text labels         : secondary clickable content
     *  6 — Everything else
     * </pre>
     *
     * <p>Navigation items were previously rank 0 (explored first), which caused the engine to
     * jump to Settings/Analytics immediately, leaving the current screen's forms and buttons
     * untested. With rank 3 they are still discovered — just after the current screen is done.
     *
     * <p>Rank 1 exists because onboarding/setup screens (language pickers, permission primers,
     * tutorial carousels) are often dominated by many structurally-similar selector rows (e.g.
     * one row per language) that rank the same as the actual "Continue" button. Without a boost,
     * the engine can exhaust its per-screen visit budget cycling through those rows and never
     * reach the button that actually advances the flow, effectively getting stuck on screen one
     * for the entire crawl. Boosting the advance button itself (not the whole screen) still lets
     * every other same-screen control be explored first when present (forms outrank it too).
     */
    private int rank(Widget w, List<Widget> all, int screenW, int screenH) {
        String c  = w.simpleClass().toLowerCase();
        String id = (w.resourceId()  != null ? w.resourceId()  : "").toLowerCase();
        String cd = (w.contentDesc() != null ? w.contentDesc() : "").toLowerCase();

        // ── Rank 0: Input fields — always test forms first ─────────────────
        if (w.editable()) return 0;

        // ── Rank 1: Advance/gate buttons — get past onboarding before it eats the budget ──
        if (w.clickable()) {
            String label = labelFor(w, all).toLowerCase();
            if (!label.isEmpty() && ADVANCE_LABELS.matcher(label).find()) return 1;
        }

        // ── Rank 2: Same-screen state-changing controls ─────────────────────
        if (c.contains("switch") || c.contains("toggle")
                || c.contains("checkbox") || c.contains("radiobutton")
                || c.contains("checkedtextview")
                || c.contains("seekbar") || c.contains("ratingbar")) return 2;

        // ── Rank 3: Navigation items — identified before generic buttons so they
        //    are never accidentally promoted to rank 4. ──────────────────────
        boolean isNav = c.contains("bottomnavigationitemview")
                || c.contains("navigationmenuitemview")
                || c.contains("tabview") || c.contains("tabitem")
                || id.contains("bottom_nav") || id.contains("nav_bar")
                || id.contains("navigation_bar") || id.contains("tab_")
                || id.contains("drawer") || id.contains("hamburger")
                || cd.contains("navigation drawer") || cd.contains("open drawer")
                || cd.contains("open navigation")
                || cd.equals("more options") || cd.contains("open menu");

        // Compose bottom-navigation tabs: bare View/clickable sitting in the bottom ~12% of the
        // screen, no wider than ~40% of the screen width, and/or carrying a known nav label.
        if (!isNav && screenH > 0 && screenW > 0) {
            int cy = w.y() + w.height() / 2;
            boolean bottomStrip = cy >= (int) (screenH * 0.86);
            boolean tabSized    = w.width() > 0 && w.width() <= (int) (screenW * 0.40);
            if (bottomStrip && tabSized) isNav = true;
            if (!isNav) {
                String label = labelFor(w, all).toLowerCase();
                if (!label.isEmpty() && NAV_LABELS.matcher(label).find()) isNav = true;
            }
            // Top app-bar action icon: a small, unlabeled, icon-sized clickable in the top ~10% of
            // the screen (back/confirm/save/search/overflow-menu — the standard toolbar affordance
            // slots). Genuinely decorative content images never live in the app-bar strip, so this
            // is safe to promote generically. Without this, a bare confirm/save icon with no text
            // or content-desc (very common — e.g. a checkmark that confirms a language/settings
            // picker) ties at Rank 5 with every other unlabeled image on the screen (flag icons,
            // thumbnails, decorative art) and can lose the draw within the per-screen test budget,
            // silently blocking the only way past that screen for the rest of the run.
            if (!isNav && isLikelyGateAdvanceControl(w, all, screenW, screenH)) isNav = true;
        }
        if (isNav) return 3;

        // ── Rank 4: Buttons and FABs ─────────────────────────────────────────
        // FABs may open a "create item" flow that lives entirely on the current screen, so they
        // are kept ahead of nav items. Regular buttons that happen to navigate are still explored
        // here — the engine will follow them, test the target screen, and return via Back.
        if (c.contains("floatingactionbutton")
                || id.contains("fab") || id.contains("action_button")) return 4;
        if (c.contains("button")) return 4;

        // ── Rank 5: Images and text labels ───────────────────────────────────
        if (c.contains("image") || c.contains("text")) return 5;

        // ── Rank 6: Everything else ───────────────────────────────────────────
        return 6;
    }

    /**
     * Common navigation-destination labels used as a SOFT hint to prioritise nav items when the
     * structural nav detection (BottomNavigationView / TabLayout / bottom-strip position) is
     * inconclusive. Deliberately spans ALL app categories — utility, content/creator, media,
     * social, commerce, productivity — so navigation is never tuned toward one kind of app. (An
     * earlier version leaned toward utility vocabulary like "speed test"/"devices"/"stats", which
     * quietly favoured hotspot/utility apps over content/creator apps whose nav uses words like
     * "create"/"gallery"/"templates".) This is only a tiebreaker; unmatched controls are still
     * explored — they just aren't promoted ahead of others.
     */
    private static final Pattern NAV_LABELS = Pattern.compile(
            "\\b(home|settings|profile|account|menu|explore|discover|dashboard|search|library|"
            + "notifications|messages|inbox|more|feed|categories|category|favou?rites|saved|"
            + "downloads|collection|collections|projects|history|analytics|stats|reports?|devices|"
            + "create|new|add|generate|make|gallery|templates?|styles?|effects?|filters?|tools?|"
            + "edit|editor|camera|scan|studio|cart|shop|store|orders?|wallet|"
            + "chat|reels|videos?|photos?|music|albums?|playlists?|help|support)\\b");

    /**
     * True when {@code w} is a gate/advance control — a "Next"/"Continue"/"Confirm"-labelled
     * button, OR an unlabeled icon sitting in the top app-bar strip (the standard slot for a
     * confirm/save checkmark on a language/settings picker). Exposed so callers OTHER than the
     * main crawl loop (which taps these to move forward and never reverts) can recognize and
     * SKIP such a control in a tap-and-revert sweep — see FunctionalTest's deep-inspect, which
     * already skips "Back"/"Close"/"Exit" for the mirror-image reason (a control that navigates
     * away). Tapping a gate/advance control and then reverting via Back to keep testing sibling
     * widgets defeats the one opportunity to get past the gate at all: verified live that exactly
     * this happened — deep-inspect tapped a language-picker's confirm checkmark, reached the
     * app's real home screen, then pressed Back to resume testing the language list, permanently
     * undoing the only successful advance for the rest of that run.
     */
    public static boolean isLikelyGateAdvanceControl(Widget w, List<Widget> all, int screenW, int screenH) {
        if (!w.clickable()) return false;
        String label = labelFor(w, all).toLowerCase();
        if (!label.isEmpty() && ADVANCE_LABELS.matcher(label).find()) return true;
        if (screenW <= 0 || screenH <= 0 || w.editable()) return false;
        int cy = w.y() + w.height() / 2;
        boolean topStrip = cy <= (int) (screenH * 0.10);
        boolean iconSized = w.width() > 0 && w.height() > 0
                && w.width() <= (int) (screenW * 0.15) && w.height() <= (int) (screenH * 0.08);
        return topStrip && iconSized && label.isEmpty();
    }

    /**
     * Onboarding/setup "advance the flow" labels — the way past language pickers, permission
     * primers, tutorial carousels, and consent screens. Kept narrow (whole-word match) to avoid
     * misfiring on unrelated buttons that merely contain a substring like "skip" or "done".
     */
    private static final Pattern ADVANCE_LABELS = Pattern.compile(
            "\\b(continue|next|get started|got it|skip|proceed|let'?s go|start|start now|begin|"
            + "i agree|agree|accept|allow|ok|okay|done|finish|confirm|"
            // Confirm/proceed affordances used on selection & onboarding gates (a language picker's
            // top-bar check, a "choose your plan" screen, etc.) — their control is often an icon
            // whose content-description is one of these words rather than "next"/"continue".
            + "apply|save|select|submit|choose|set|use|use this|confirm selection|"
            + "save changes|apply changes|tick|check)\\b");

    /**
     * Returns realistic sample text for a given input field, derived from resource-id,
     * hint text and content description. Typed input matching the field's expected format
     * lets validation logic, auto-complete, and keyboard types behave naturally.
     */
    private static String sampleInputForField(Widget w) {
        String ctx = ((w.resourceId()  != null ? w.resourceId().toLowerCase()  : "") + " "
                   + (w.text()        != null ? w.text().toLowerCase()        : "") + " "
                   + (w.contentDesc() != null ? w.contentDesc().toLowerCase() : ""));
        if (ctx.contains("email")    || ctx.contains("e-mail")  || ctx.contains("e_mail"))  return SAMPLE_EMAIL;
        if (ctx.contains("password") || ctx.contains("passwd")  || ctx.contains("pin")
                || ctx.contains("secret") || ctx.contains("passcode"))                       return SAMPLE_PASS;
        if (ctx.contains("phone")    || ctx.contains("mobile")  || ctx.contains("cell"))     return SAMPLE_PHONE;
        if (ctx.contains("url")      || ctx.contains("website") || ctx.contains("web_")
                || ctx.contains("link")   || ctx.contains("http"))                           return SAMPLE_URL;
        if (ctx.contains("search")   || ctx.contains("query")   || ctx.contains("find")
                || ctx.contains("keyword"))                                                   return SAMPLE_SEARCH;
        if (ctx.contains("first_name") || ctx.contains("last_name") || ctx.contains("full_name")
                || ctx.contains("username")
                || (ctx.contains("name") && !ctx.contains("package") && !ctx.contains("class")))
                                                                                             return SAMPLE_NAME;
        if (ctx.contains("age")      || ctx.contains("amount")  || ctx.contains("price")
                || ctx.contains("quantity") || ctx.contains("qty") || ctx.contains("zip")
                || ctx.contains("postal")   || ctx.contains("otp")
                || ctx.contains("_num")     || ctx.contains("number_"))                      return SAMPLE_NUM;
        return SAMPLE_TEXT;
    }

    private boolean performAction(AndroidDriver driver, Widget w) {
        int cx = w.x() + w.width() / 2;
        int cy = w.y() + w.height() / 2;
        try {
            if (w.editable()) {
                WebElement el = locate(driver, w);
                if (el != null) {
                    el.click();
                    el.clear();
                    el.sendKeys(sampleInputForField(w));
                } else {
                    tap(driver, cx, cy);
                }
                hideKeyboardQuietly(driver);
                return true;
            }
            // Long-press-only elements (list items, cards with context menus) respond only to a
            // long tap. Prefer a regular tap when the element supports both so that navigation
            // actions work correctly; fall back to long-press when it is the only gesture declared.
            if (w.longClickable() && !w.clickable()) {
                longPress(driver, cx, cy);
            } else {
                tap(driver, cx, cy);
            }
            return true;
        } catch (Exception e) {
            log.debug("Action on {} failed: {}", w.signature(), e.toString());
            return false;
        }
    }

    private WebElement locate(AndroidDriver driver, Widget w) {
        try {
            List<WebElement> candidates;
            if (w.resourceId() != null && !w.resourceId().isBlank()) {
                candidates = driver.findElements(AppiumBy.id(w.resourceId()));
            } else if (w.className() != null && !w.className().isBlank()) {
                candidates = driver.findElements(AppiumBy.className(w.className()));
            } else {
                return null;
            }
            // Pick the candidate whose location best matches the parsed bounds.
            for (WebElement el : candidates) {
                var p = el.getLocation();
                if (Math.abs(p.getX() - w.x()) <= 5 && Math.abs(p.getY() - w.y()) <= 5) {
                    return el;
                }
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private void tap(AndroidDriver driver, int x, int y) {
        driver.executeScript("mobile: clickGesture", Map.of("x", x, "y", y));
    }

    /**
     * Best-effort replay of a navigation-graph path: taps, in order, the widget matching each
     * action key on the current live screen. Aborts (returns false) the instant a step's widget
     * can't be found/tapped or would hit an ad/subscription control — the caller then converges
     * normally. Never throws. Used only by frontier-directed exploration to reach an unexplored
     * screen from a dead end.
     */
    private boolean replayPath(AndroidDriver driver, List<String> path) {
        for (String key : path) {
            String src = readSourceWithRetry(driver);
            if (src == null) return false;
            List<Widget> ws = parse(src);
            Widget target = null;
            for (Widget w : ws) {
                if (w.actionable() && w.displayed() && widgetKey(w).equals(key)
                        && !isAdWidget(w, ws) && !isSubscriptionWidget(w)) { target = w; break; }
            }
            if (target == null) return false;
            if (!performAction(driver, target)) return false;
            sleep(SETTLE_AFTER_ACTION_MS);
        }
        return true;
    }

    private void longPress(AndroidDriver driver, int x, int y) {
        driver.executeScript("mobile: longClickGesture",
                Map.of("x", x, "y", y, "duration", 1000));
    }

    private void scrollDown(AndroidDriver driver, TestContext ctx) {
        try {
            var dev = ctx.deviceInfo();
            int w = dev != null && dev.widthPx() > 0 ? dev.widthPx() : 1080;
            int h = dev != null && dev.heightPx() > 0 ? dev.heightPx() : 1920;
            driver.executeScript("mobile: swipeGesture", Map.of(
                    "left", w / 4, "top", h / 4, "width", w / 2, "height", h / 2,
                    "direction", "up", "percent", 0.75));
        } catch (Exception ignored) { }
    }

    private void scrollUp(AndroidDriver driver, TestContext ctx) {
        try {
            var dev = ctx.deviceInfo();
            int w = dev != null && dev.widthPx() > 0 ? dev.widthPx() : 1080;
            int h = dev != null && dev.heightPx() > 0 ? dev.heightPx() : 1920;
            driver.executeScript("mobile: swipeGesture", Map.of(
                    "left", w / 4, "top", h / 4, "width", w / 2, "height", h / 2,
                    "direction", "down", "percent", 0.75));
        } catch (Exception ignored) { }
    }

    /**
     * Silently sweeps a scrollable screen from top to bottom, collecting every actionable widget
     * into {@code accumulator} (keyed by {@link #widgetKey} so duplicates are collapsed), then
     * scrolls back to the top. Called once per screen structure on first discovery so the main
     * loop's {@code chooseAction} can "see" below-fold controls and use dead-end scroll recovery
     * to reach them rather than declaring a dead end prematurely and pressing Back.
     *
     * <p>This is intentionally lightweight — no screenshots, no ScrollReport, no side effects —
     * because the existing {@link #scrollSweep} already handles the detailed scroll analysis.
     */
    private void discoverBelowFold(AndroidDriver driver, TestContext ctx,
                                   LinkedHashMap<String, Widget> accumulator, List<Widget> initial) {
        if (!hasScrollable(initial)) return;
        String prevHash = contentHash(initial);
        int steps = 0;
        while (steps < MAX_SCROLL_STEPS) {
            if (Thread.currentThread().isInterrupted() || ctx.run().isCancelRequested()) break; // Stop requested.
            scrollDown(driver, ctx);
            sleep(400);
            steps++;
            List<Widget> cur = parseCurrent(driver);
            for (Widget w : cur) {
                if (w.actionable() && !isAdWidget(w, cur) && !isSubscriptionWidget(w)) accumulator.putIfAbsent(widgetKey(w), w);
            }
            String h = contentHash(cur);
            if (h.equals(prevHash)) break; // reached end of scrollable content
            prevHash = h;
        }
        // Return to the top so the main loop starts from a known position.
        for (int i = 0; i <= steps; i++) scrollUp(driver, ctx);
        sleep(400);
    }

    /**
     * Returns true if {@code target} is currently visible in the active viewport.
     * Matching is by content identity ({@link #widgetKey}) rather than coordinates, since the
     * widget's y-position shifts when the screen is scrolled.
     */
    private boolean isCurrentlyVisible(Widget target, List<Widget> current) {
        String k = widgetKey(target);
        return current.stream().anyMatch(w -> widgetKey(w).equals(k));
    }

    /**
     * Scrolls down until {@code target} becomes visible in the viewport or the scroll limit is
     * reached. Returns true if the widget was found. The caller should re-read the page source
     * after this call to get fresh coordinates for the (now on-screen) widget.
     */
    private boolean scrollToWidget(AndroidDriver driver, TestContext ctx, Widget target) {
        String k = widgetKey(target);
        for (int i = 0; i < MAX_DEADEND_SCROLLS; i++) {
            scrollDown(driver, ctx);
            sleep(400);
            List<Widget> cur = parseCurrent(driver);
            if (cur.stream().anyMatch(w -> widgetKey(w).equals(k))) return true;
            // Stop if the page is no longer moving.
            String h1 = contentHash(cur);
            sleep(150);
            if (h1.equals(contentHash(parseCurrent(driver)))) break;
        }
        return false;
    }

    private boolean hasScrollable(List<Widget> widgets) {
        return widgets.stream().anyMatch(Widget::scrollable);
    }

    /**
     * True when the current screen contains a horizontal pager or carousel widget.
     * Used to decide whether to attempt horizontal dead-end swipes when all button-based
     * actions are exhausted (e.g., a swipe-only onboarding flow with no "Next" button).
     */
    private boolean hasPagerOrCarousel(List<Widget> widgets) {
        for (Widget w : widgets) {
            String cls = w.className() != null ? w.className().toLowerCase() : "";
            if (cls.contains("viewpager") || cls.contains("horizontalscrollview")
                    || cls.contains("carousel") || cls.contains("pager")
                    || cls.contains("banner")) return true;
        }
        // Fallback: 3+ small displayed focusable/clickable widgets that look like page-indicator dots
        // (RadioButton or a custom indicator view clustered at a similar vertical position).
        long dotCount = widgets.stream()
                .filter(w -> w.displayed() && w.enabled() && (w.clickable() || w.focusable()))
                .filter(w -> {
                    String cls = w.className() != null ? w.className().toLowerCase() : "";
                    return cls.contains("radio") || cls.contains("indicator") || cls.contains("dot");
                })
                .count();
        return dotCount >= 3;
    }

    // ---- scrollable-screen sweep -----------------------------------------

    /** Generalised swipe; forward = reveal more content, backward = return toward start. */
    private void swipe(AndroidDriver driver, TestContext ctx, String direction, boolean forward) {
        try {
            var dev = ctx.deviceInfo();
            int w = dev != null && dev.widthPx() > 0 ? dev.widthPx() : 1080;
            int h = dev != null && dev.heightPx() > 0 ? dev.heightPx() : 1920;
            String g = "horizontal".equals(direction) ? (forward ? "left" : "right")
                                                       : (forward ? "up" : "down");
            driver.executeScript("mobile: swipeGesture", Map.of(
                    "left", w / 6, "top", h / 5, "width", w * 2 / 3, "height", h * 3 / 5,
                    "direction", g, "percent", 0.85));
            sleep(450);
        } catch (Exception ignored) { }
    }

    /**
     * Scroll a screen from top to bottom (or end), discovering and validating every element
     * that becomes visible, capturing before/during/after screenshots, detecting infinite/lazy
     * loading and recording scroll depth + coverage.
     */
    private ScrollReport scrollSweep(TestContext ctx, AndroidDriver driver, int idx,
                                     String activity, List<Widget> initial, String beforeShot) {
        ScrollReport r = new ScrollReport();
        r.activity = activity;
        r.beforeShot = beforeShot;

        LinkedHashMap<String, Widget> uniq = new LinkedHashMap<>();
        for (Widget wgt : initial) uniq.putIfAbsent(widgetKey(wgt), wgt);
        r.initialElements = uniq.size();

        // Detect direction: class hint first (ViewPager/HorizontalScrollView = horizontal).
        boolean horizHint = initial.stream().anyMatch(w -> w.scrollable() && w.className() != null
                && (w.className().contains("HorizontalScrollView") || w.className().contains("ViewPager")));
        String dir = horizHint ? "horizontal" : "vertical";

        String startHash = contentHash(initial);
        swipe(driver, ctx, dir, true);
        List<Widget> cur = parseCurrent(driver);
        if (contentHash(cur).equals(startHash)) {
            // No movement in the assumed direction — verify the other axis before giving up.
            swipe(driver, ctx, dir, false);
            String other = "vertical".equals(dir) ? "horizontal" : "vertical";
            swipe(driver, ctx, other, true);
            cur = parseCurrent(driver);
            if (contentHash(cur).equals(startHash)) {
                r.direction = "none";
                r.endReached = true;
                tally(uniq.values(), r, ctx);
                r.totalElements = uniq.size();
                return r;     // already at end / not actually scrollable
            }
            dir = other;
        }
        r.direction = dir;

        Set<String> offSeen = new HashSet<>();
        Set<String> ovSeen = new HashSet<>();
        for (Widget x : cur) uniq.putIfAbsent(widgetKey(x), x);
        accumulateIssues(cur, r, ctx, offSeen, ovSeen);
        r.duringShots.add(captureShot(ctx, driver, "scroll-" + idx + "-during-1"));

        String prevHash = contentHash(cur);
        int steps = 1, unchanged = 0, during = 1;
        while (steps < MAX_SCROLL_STEPS) {
            if (Thread.currentThread().isInterrupted() || ctx.run().isCancelRequested()) break; // Stop requested.
            swipe(driver, ctx, dir, true);
            steps++;
            List<Widget> w = parseCurrent(driver);
            for (Widget x : w) uniq.putIfAbsent(widgetKey(x), x);
            accumulateIssues(w, r, ctx, offSeen, ovSeen);
            String h = contentHash(w);
            if (h.equals(prevHash)) {
                if (++unchanged >= 2) break;          // content stable twice -> end of page
            } else {
                unchanged = 0;
                if (during < 3) {
                    r.duringShots.add(captureShot(ctx, driver, "scroll-" + idx + "-during-" + (during + 1)));
                    during++;
                }
            }
            prevHash = h;
        }

        r.scrollSteps = steps;
        r.endReached = unchanged >= 2;
        r.infiniteScrollSuspected = steps >= MAX_SCROLL_STEPS && unchanged < 2;
        r.coverage = r.infiniteScrollSuspected ? "Infinite-scroll (capped)"
                : (r.endReached ? "Complete" : "Partial");
        r.afterShot = captureShot(ctx, driver, "scroll-" + idx + "-after");

        tally(uniq.values(), r, ctx);
        r.totalElements = uniq.size();
        r.revealedElements = Math.max(0, r.totalElements - r.initialElements);

        // Return to the top so the crawler's action selection starts from a known position.
        for (int i = 0; i <= steps && i < MAX_SCROLL_STEPS + 1; i++) {
            swipe(driver, ctx, dir, false);
        }
        return r;
    }

    private List<Widget> parseCurrent(AndroidDriver driver) {
        String s = readSourceWithRetry(driver);
        return s == null ? List.of() : parse(s);
    }

    /** Classify the discovered components and count label/size issues (bounds-independent). */
    private void tally(Collection<Widget> ws, ScrollReport r, TestContext ctx) {
        int minTouch = ctx.dpToPx(48);
        for (Widget w : ws) {
            String c = w.simpleClass().toLowerCase();
            if (w.editable()) r.inputs++;
            else if (c.contains("button")) r.buttons++;
            else if (c.contains("image")) r.images++;
            else if (c.contains("card")) r.cards++;
            else if (w.clickable()) r.clickable++;

            if (w.actionable()) {
                if (!w.hasLabel() && (c.contains("image") || c.contains("button"))) r.missingLabels++;
                if (minTouch > 0 && w.minSide() > 0 && w.minSide() < minTouch) r.smallTargets++;
            }
        }
    }

    /** Count off-screen actionable elements and overlapping pairs per snapshot, de-duplicated. */
    private void accumulateIssues(List<Widget> ws, ScrollReport r, TestContext ctx,
                                  Set<String> offSeen, Set<String> ovSeen) {
        int sw = ctx.deviceInfo() != null ? ctx.deviceInfo().widthPx() : 0;
        int sh = ctx.deviceInfo() != null ? ctx.deviceInfo().heightPx() : 0;
        List<Widget> act = ws.stream().filter(Widget::actionable).toList();
        for (Widget w : act) {
            if (sw > 0 && sh > 0
                    && (w.x() < -2 || w.y() < -2 || w.x() + w.width() > sw + 2 || w.y() + w.height() > sh + 2)
                    && offSeen.add(widgetKey(w))) {
                r.offScreen++;
            }
        }
        for (int i = 0; i < act.size(); i++) {
            for (int j = i + 1; j < act.size(); j++) {
                Widget a = act.get(i), b = act.get(j);
                // Skip zero-area / tiny widgets — same false-positive fix as UiUxTest/CompatAnalyzer.
                if (a.area() < 900 || b.area() < 900) continue;
                int ix = Math.max(0, Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x()));
                int iy = Math.max(0, Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y()));
                int inter = ix * iy;
                if (inter <= 0) continue;
                int smaller = Math.min(a.area(), b.area());
                int larger  = Math.max(a.area(), b.area());
                double ratio = (double) inter / smaller;
                // Skip parent-child containment (clickable container + clickable child).
                if (ratio >= 0.80 && (double) larger / smaller >= 1.10) continue;
                if (ratio > 0.60) {
                    String pair = widgetKey(a).compareTo(widgetKey(b)) < 0
                            ? widgetKey(a) + "~" + widgetKey(b) : widgetKey(b) + "~" + widgetKey(a);
                    if (ovSeen.add(pair)) r.overlaps++;
                }
            }
        }
    }

    private String contentHash(List<Widget> ws) {
        TreeSet<String> set = new TreeSet<>();
        for (Widget w : ws) set.add(widgetKey(w));
        return Integer.toHexString(set.toString().hashCode());
    }

    private String widgetKey(Widget w) {
        return w.simpleClass() + "|" + (w.resourceId() == null ? "" : w.resourceId())
                + "|" + (w.text() == null ? "" : w.text().trim());
    }

    private String captureShot(TestContext ctx, AndroidDriver driver, String name) {
        try {
            return ctx.saveScreenshot(driver.getScreenshotAs(OutputType.BYTES), name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Current screen as raw PNG bytes for the vision navigator; null on any capture failure. */
    private byte[] safeScreenshotBytes(AndroidDriver driver) {
        try {
            return driver.getScreenshotAs(OutputType.BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 64-bit average-hash (aHash) of a screenshot, for perceptual same/different-screen comparison
     * during API-free visual exploration of opaque/canvas screens. Decodes the PNG, samples an 8x8
     * grid of block-average luminances, and sets each bit where a cell is brighter than the frame
     * mean. Two screenshots of the same screen differ by only a few bits (tolerates minor animation);
     * a genuine navigation flips many. Returns 0 on any failure (treated as "unknown", never matched).
     * Pure JDK — no network, so it works with no API key configured.
     */
    static long aHash(byte[] png) {
        if (png == null || png.length == 0) return 0L;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return 0L;
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0) return 0L;
            int N = 8;
            int[] cell = new int[N * N];
            long sum = 0;
            for (int cy = 0; cy < N; cy++) {
                for (int cx = 0; cx < N; cx++) {
                    int x0 = (int) ((long) cx * w / N), x1 = (int) ((long) (cx + 1) * w / N);
                    int y0 = (int) ((long) cy * h / N), y1 = (int) ((long) (cy + 1) * h / N);
                    if (x1 <= x0) x1 = x0 + 1;
                    if (y1 <= y0) y1 = y0 + 1;
                    long acc = 0; int n = 0;
                    // Sample up to a 4x4 lattice inside the cell — enough for a stable average,
                    // cheap enough to run each step.
                    int sxStep = Math.max(1, (x1 - x0) / 4), syStep = Math.max(1, (y1 - y0) / 4);
                    for (int y = y0; y < y1; y += syStep) {
                        for (int x = x0; x < x1; x += sxStep) {
                            int rgb = img.getRGB(Math.min(x, w - 1), Math.min(y, h - 1));
                            int lum = (int) (0.299 * ((rgb >> 16) & 0xff)
                                    + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff));
                            acc += lum; n++;
                        }
                    }
                    int avg = n > 0 ? (int) (acc / n) : 0;
                    cell[cy * N + cx] = avg;
                    sum += avg;
                }
            }
            long mean = sum / (N * N);
            long hash = 0L;
            for (int i = 0; i < N * N; i++) { hash <<= 1; if (cell[i] >= mean) hash |= 1L; }
            return hash;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * Picks a tap point on an opaque screen from OCR'd text: the most prominent on-screen word
     * that matches a positive call-to-action keyword — and has NO purchase/ad/exit keyword on the
     * same line, and hasn't already been tried on this visual screen. Returns the word box centre
     * (which lands inside the painted button), or null if there's no safe CTA. Fully generic —
     * keyword-based, no app-specific names.
     */
    private int[] chooseOcrCtaPoint(byte[] png, int sw, int sh, int screenK, Set<String> triedLabels) {
        List<com.vasundhara.atf.ocr.OcrEngine.Word> words = ocrEngine.recognize(png);
        if (words.isEmpty()) return null;
        com.vasundhara.atf.ocr.OcrEngine.Word best = null;
        for (com.vasundhara.atf.ocr.OcrEngine.Word w : words) {
            String norm = w.text().toLowerCase().replaceAll("[^a-z']", "");
            if (norm.isEmpty() || !OCR_CTA_WORDS.contains(norm)) continue;
            if (OCR_CTA_NEGATIVE.matcher(w.text().toLowerCase()).find()) continue;
            if (w.h() < 12 || w.cy() < sh * 0.06) continue;   // skip specks and the status-bar row
            if (triedLabels.contains(screenK + "|" + norm)) continue;
            // Spatial guard: reject if a purchase/ad/exit word sits on the same line (handles
            // "Try Premium", "Get Pro", "Start free trial" — word-level OCR can't see the phrase).
            boolean negNearby = false;
            for (com.vasundhara.atf.ocr.OcrEngine.Word o : words) {
                if (o == w) continue;
                if (Math.abs(o.cy() - w.cy()) <= Math.max(w.h(), o.h())
                        && OCR_CTA_NEGATIVE.matcher(o.text().toLowerCase()).find()) { negNearby = true; break; }
            }
            if (negNearby) continue;
            if (best == null || betterCta(w, best, sh)) best = w;
        }
        if (best == null) return null;
        triedLabels.add(screenK + "|" + best.text().toLowerCase().replaceAll("[^a-z']", ""));
        return new int[]{ best.cx(), best.cy() };
    }

    /** Prefer a CTA lower on screen (onboarding CTAs sit near the bottom), then larger text. */
    private static boolean betterCta(com.vasundhara.atf.ocr.OcrEngine.Word cand,
                                     com.vasundhara.atf.ocr.OcrEngine.Word cur, int sh) {
        boolean candBottom = cand.cy() >= sh * 0.5, curBottom = cur.cy() >= sh * 0.5;
        if (candBottom != curBottom) return candBottom;      // bottom-half CTA wins
        if (cand.h() != cur.h()) return cand.h() > cur.h();  // larger text wins
        return cand.cy() > cur.cy();                         // else the lower one wins
    }

    /**
     * Safety net for vision-guided taps: true when a known ad/subscription widget's bounds contain
     * the point the model chose. Even though the prompt forbids ads/purchases, this refuses the tap
     * if any recognisable ad/paywall node happens to sit under those coordinates — the standing
     * "never tap ads / never purchase" guarantee must not depend on the model behaving.
     */
    private static boolean isAdContentAt(List<Widget> widgets, int px, int py) {
        for (Widget w : widgets) {
            if (px < w.x() || px > w.x() + w.width() || py < w.y() || py > w.y() + w.height()) continue;
            if (isAdWidget(w, widgets) || isSubscriptionWidget(w)) return true;
        }
        return false;
    }

    private void hideKeyboardQuietly(AndroidDriver driver) {
        try {
            driver.hideKeyboard();
        } catch (Exception ignored) { }
    }

    // ---- page source parsing ---------------------------------------------

    public static List<Widget> parse(String xml) {
        List<Widget> widgets = new ArrayList<>();
        if (xml == null || xml.isBlank()) return widgets;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            var doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                if (!(all.item(i) instanceof Element el)) continue;
                String bounds = el.getAttribute("bounds");
                Matcher m = BOUNDS.matcher(bounds);
                if (!m.find()) continue;  // skip <hierarchy> root and non-visual nodes
                int x1 = Integer.parseInt(m.group(1));
                int y1 = Integer.parseInt(m.group(2));
                int x2 = Integer.parseInt(m.group(3));
                int y2 = Integer.parseInt(m.group(4));
                String cls = attr(el, "class", el.getTagName());
                widgets.add(new Widget(
                        cls,
                        attr(el, "resource-id", ""),
                        attr(el, "text", ""),
                        attr(el, "content-desc", ""),
                        attr(el, "hint", ""),         // UIAutomator2 exposes hint for EditText (API 26+)
                        bool(el, "clickable"),
                        bool(el, "long-clickable"),
                        bool(el, "scrollable"),
                        bool(el, "focusable"),
                        boolDefault(el, "enabled", true),
                        boolDefault(el, "displayed", true),
                        x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1),
                        attr(el, "package", "")));
            }
        } catch (Exception e) {
            log.debug("Page source parse failed: {}", e.toString());
        }
        return widgets;
    }

    /**
     * Convert an Android activity/fragment class name to a human-readable screen label.
     * Examples: "NoteDetailsActivity" → "Note Details", "SettingsFragment" → "Settings",
     * "MainActivity" → "Main", ".SplashActivity" → "Splash".
     */
    /**
     * Human-friendly label for the live "current screen" display on the Test Running page —
     * e.g. "SplashActivity" → "Splash Screen", "MainActivity" → "Main Screen",
     * "LanguageSelectionActivity" → "Language Selection Screen", "OnboardingActivity" →
     * "Onboarding Screen". Purely derived from the class name via {@link #toReadableName} — fully
     * generic, with no hardcoded app/activity names — so it works for any APK and updates as the
     * foreground activity changes during navigation.
     */
    static String friendlyScreenName(String activity) {
        String base = toReadableName(activity);
        if (base == null || base.isBlank() || base.toLowerCase().contains("unknown")) return "App Screen";
        String low = base.toLowerCase();
        if (low.endsWith("screen") || low.endsWith("page")) return base;
        return base + " Screen";
    }

    static String toReadableName(String className) {
        if (className == null || className.isBlank()) return "Unknown Screen";
        String s = className.startsWith(".") ? className.substring(1) : className;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        for (String sfx : new String[]{"Activity", "Fragment", "Screen", "Page", "View"}) {
            if (s.endsWith(sfx) && s.length() > sfx.length()) {
                s = s.substring(0, s.length() - sfx.length());
                break;
            }
        }
        // CamelCase → "Camel Case"
        s = s.replaceAll("([a-z])([A-Z])", "$1 $2")
             .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
             .trim();
        return s.isEmpty() ? className : s;
    }

    private static String attr(Element el, String name, String def) {
        String v = el.getAttribute(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static boolean bool(Element el, String name) {
        return "true".equals(el.getAttribute(name));
    }

    private static boolean boolDefault(Element el, String name, boolean def) {
        String v = el.getAttribute(name);
        if (v == null || v.isEmpty()) return def;
        return "true".equals(v);
    }

    /**
     * Produces a state signature that uniquely identifies a specific screen.
     *
     * <p>The signature has two components:
     * <ol>
     *   <li><b>Widget structure</b> — the set of (simpleClass#resourceId) pairs for every
     *       actionable widget. This determines which actions are available on this screen.</li>
     *   <li><b>Content fingerprint</b> — a hash of the first few short, non-dynamic text
     *       values visible on screen. This distinguishes pages that share the same widget
     *       structure but show different content: multi-page onboarding flows (the root cause
     *       of "stops on onboarding"), ViewPager tabs, slide carousels, and wizard steps.
     *       Dynamic texts (pure numbers, timestamps, amounts) are excluded so that counters
     *       and live data do not prevent convergence on genuinely stable screens.</li>
     * </ol>
     */
    public static String stateSignature(String activity, List<Widget> widgets) {
        // Component 1: actionable widget structure (what actions are available).
        // Uses a sorted List (not a Set) so that screens with different COUNTS of the same
        // element type produce different structure hashes. This matters for Jetpack Compose
        // apps where resource IDs are absent: a Language-list screen with 10 "View#" elements
        // and a Home screen with 3 "View#" elements would otherwise produce the same structure
        // hash and share the same visit-cap bucket in structureVisitCounts.
        List<String> structure = new ArrayList<>();
        for (Widget w : widgets) {
            if (w.actionable()) {
                structure.add(w.simpleClass() + "#" + (w.resourceId() == null ? "" : w.resourceId()));
            }
        }
        java.util.Collections.sort(structure);

        // Component 2: content fingerprint (what is currently displayed)
        // Collect up to 4 short, static text labels to distinguish pages of an onboarding
        // flow, wizard step, or carousel from one another even when the widget structure
        // (same buttons) is identical across pages.
        List<String> texts = new java.util.ArrayList<>();
        for (Widget w : widgets) {
            if (texts.size() >= 4) break;
            if (!w.displayed() || w.editable()) continue;
            String text = (w.text() != null ? w.text() : "").trim();
            // Only include label-length texts — long strings and pure-numeric / date / amount
            // tokens are excluded to avoid treating counter changes as new states.
            if (text.length() >= 4 && text.length() <= 60
                    && !DYNAMIC_TEXT_PAT.matcher(text).matches()) {
                texts.add(text);
            }
        }
        java.util.Collections.sort(texts);

        return activity + ":"
                + Integer.toHexString(structure.toString().hashCode())
                + ":" + Integer.toHexString(String.join("|", texts).hashCode());
    }

    // ---- small utilities --------------------------------------------------

    private interface Action<T> { T run() throws Exception; }

    private <T> T safe(Action<T> action) {
        try {
            return action.run();
        } catch (Exception e) {
            return null;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
