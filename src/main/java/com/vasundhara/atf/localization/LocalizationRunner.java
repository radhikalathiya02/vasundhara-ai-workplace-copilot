package com.vasundhara.atf.localization;

import com.vasundhara.atf.apk.ApkAnalyzer;
import com.vasundhara.atf.compat.CompatAnalyzer;
import com.vasundhara.atf.compat.CompatVersionResult;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.device.DeviceManager;
import com.vasundhara.atf.device.DriverFactory;
import com.vasundhara.atf.device.EmulatorManager;
import com.vasundhara.atf.device.EmulatorManager.BootedEmulator;
import com.vasundhara.atf.engine.ExplorationEngine;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.TestContext;
import com.vasundhara.atf.model.ApkInfo;
import com.vasundhara.atf.model.TestRun;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drives automatic localization testing: detects the APK's supported languages,
 * runs a baseline crawl in the default locale, then for each language switches the
 * app's locale, re-crawls every reachable screen, and validates both translation
 * (untranslated strings per screen) and functionality (crashes, ANRs, UI/navigation
 * issues caused by the language change), capturing screenshots for evidence.
 *
 * <p>Language switching uses Android's per-app locales ({@code cmd locale
 * set-app-locales}, API 33+) for reliability instead of navigating each app's
 * bespoke in-app settings UI.
 */
@Component
public class LocalizationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalizationRunner.class);

    private final AtfProperties props;
    private final ApkAnalyzer apkAnalyzer;
    private final EmulatorManager emulators;
    private final AdbClient adb;
    private final DeviceManager deviceManager;
    private final DriverFactory driverFactory;
    private final ExplorationEngine explorationEngine;
    private final com.vasundhara.atf.engine.ExecutionLockService execLock;
    private final com.vasundhara.atf.report.RunBridgeService runBridge;
    private final com.vasundhara.atf.device.DeviceWatchdog watchdog;
    private final com.vasundhara.atf.ai.LlmTranslationReviewer translationReviewer;
    private final LocalizationSessionStore sessionStore;

    public LocalizationRunner(AtfProperties props, ApkAnalyzer apkAnalyzer, EmulatorManager emulators,
                              AdbClient adb, DeviceManager deviceManager, DriverFactory driverFactory,
                              ExplorationEngine explorationEngine,
                              com.vasundhara.atf.engine.ExecutionLockService execLock,
                              com.vasundhara.atf.report.RunBridgeService runBridge,
                              com.vasundhara.atf.device.DeviceWatchdog watchdog,
                              com.vasundhara.atf.ai.LlmTranslationReviewer translationReviewer,
                              LocalizationSessionStore sessionStore) {
        this.props = props;
        this.apkAnalyzer = apkAnalyzer;
        this.emulators = emulators;
        this.adb = adb;
        this.deviceManager = deviceManager;
        this.driverFactory = driverFactory;
        this.explorationEngine = explorationEngine;
        this.execLock = execLock;
        this.runBridge = runBridge;
        this.watchdog = watchdog;
        this.translationReviewer = translationReviewer;
        this.sessionStore = sessionStore;
    }

    @Async("testRunExecutor")
    public void run(LocalizationSession session, File apkFile) {
        runInternal(session, apkFile, null);
    }

    /**
     * Run targeting a specific device (multi-device execution). {@code preferredSerial} is honoured
     * by {@link DeviceManager#selectDevice(String)}; null/blank falls back to the default selection.
     */
    @Async("testRunExecutor")
    public void run(LocalizationSession session, File apkFile, String preferredSerial) {
        runBridge.start(session.getId(), "Localization", session.getApkFileName(), preferredSerial);
        try {
            runInternal(session, apkFile, preferredSerial);
        } finally {
            // Persist the FINAL session (with all languages/findings) — the only earlier save
            // (in LocalizationController, at creation) only ever wrote the initial empty SETUP
            // snapshot, so without this the Reports module would show stale/empty data for this
            // session after any server restart even though it completed successfully.
            sessionStore.save(session);
            runBridge.finish(session.getId(), session.getState().name(), session.getError());
            execLock.release(session.getId());
        }
    }

    private void runInternal(LocalizationSession session, File apkFile, String preferredSerial) {
        BootedEmulator emu = null;
        String serial = null;
        try {
            session.setState(LocalizationSession.State.RUNNING);
            session.addLog("Starting automatic localization testing…");

            ApkInfo info = apkAnalyzer.analyze(apkFile);
            String pkg = info.getPackageName();
            session.setPackageName(pkg);

            List<String> resourceLocales = info.getSupportedLocales();
            for (String src : info.getLocalizationSources())
                session.addLog("Localization source: " + src);

            if (!driverFactory.isAppiumReachable()) {
                fail(session, "Appium is not reachable. Localization testing needs the UI crawl to read the language list and on-screen text.");
                return;
            }

            // Pick a device: prefer the caller's chosen serial, then a connected one, else boot an emulator.
            Optional<String> connected = deviceManager.selectDevice(preferredSerial);
            if (connected.isPresent()) {
                serial = connected.get();
                session.addLog("Using connected device: " + serial);
            } else {
                int api = firstApi();
                session.addLog("No device connected — booting an emulator (API " + api + ")…");
                String avd = emulators.ensureAvd(api, session::addLog);
                emu = emulators.boot(avd, session::addLog);
                serial = emu.serial();
            }
            session.setDeviceSerial(serial);
            final String watchSerial = serial;
            watchdog.startWatch(session.getId(), watchSerial, () -> {
                if (session.isStopRequested()) return; // manual stop already in flight
                session.setError("Device disconnected during test execution.");
                session.requestStop();
                session.addLog("DEVICE DISCONNECTED — stopping localization test.");
            });

            int sdk = parseInt(adb.getProp(serial, "ro.build.version.sdk"));
            if (sdk > 0 && sdk < 33)
                session.addLog("Note: device is API " + sdk + " (<33); per-app locale switching may not apply — results best-effort.");

            // Install once.
            session.addLog("Installing APK…");
            var install = adb.install(serial, apkFile, pkg);
            if (!install.combined().contains("Success")) {
                fail(session, "APK install failed: " + install.combined());
                return;
            }
            int w = 0, h = 0;
            DeviceManager.DeviceInfo dev = deviceManager.profile(serial);
            if (dev != null) { w = dev.widthPx(); h = dev.heightPx(); }

            // ── Detect the in-app Language Selection screen — the ONLY source of truth ──
            // Localization testing only ever validates languages the app itself actually offers
            // through its own UI, never the APK's raw resource locales (those may include locales
            // with no in-app switcher, or omit ones added dynamically) and never OS-level locale
            // switching as a substitute for a real in-app language screen.
            Map<String, String> dict = LanguageScreenDetector.dictionary(new java.util.HashSet<>(resourceLocales));
            adb.clearAppData(serial, pkg);
            adb.launchApp(serial, pkg);
            sleep(2000);
            // Fast path: wait past the splash in case the Language Selection screen appears at
            // launch (the common case — a one-time startup chooser). 15 s covers slow-starting apps.
            InAppLanguageSelector.waitForPicker(adb, serial, dict, 15000);
            List<InAppLanguageSelector.Lang> picker = InAppLanguageSelector.scanAllLanguages(adb, serial, dict, w, h);

            if (picker.size() < 2) {
                // Not shown at launch — the language screen may live inside a Settings/menu screen
                // instead. Search the entire app for it before giving up.
                session.addLog("No Language Selection screen at launch — searching the app for one…");
                int searchBudget = Math.min(120, Math.max(props.getCrawlMaxSteps(), 80));
                LocalizationCrawler.LanguageScreenSearchResult found = LocalizationCrawler.findLanguageScreen(
                        adb, serial, pkg, dict, w, h, searchBudget, session::isStopRequested, session::addLog);
                if (found.found()) {
                    // Scan the FULL list from here (a single dump may miss rows further down the list).
                    picker = InAppLanguageSelector.scanAllLanguages(adb, serial, dict, w, h);
                }
            }

            if (picker.size() < 2) {
                fail(session, "No in-app Language Selection screen could be found anywhere in the app "
                        + "after searching it. Localization testing requires a real in-app language "
                        + "screen and does not run without one.");
                return;
            }

            session.setLanguageSource("In-app Language Selection screen");
            session.setLanguageScreen("in-app language chooser");
            session.addLog("Detected in-app Language Selection screen with " + picker.size()
                    + " language(s): " + picker.stream().map(InAppLanguageSelector.Lang::label)
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            for (InAppLanguageSelector.Lang l : picker)
                session.getLanguages().add(new LanguageResult(l.code(), l.label()));
            session.addLog("Stored " + picker.size() + " language(s). The list is detected only once; "
                    + "each language is now tested sequentially without re-detecting the list.");
            boolean useInApp = true;

            // Honour the configured cap (Settings → Localization). 0 = test every detected language.
            int maxLangs = props.getLocalizationMaxLanguages();
            if (maxLangs > 0 && session.getLanguages().size() > maxLangs) {
                int dropped = session.getLanguages().size() - maxLangs;
                while (session.getLanguages().size() > maxLangs) {
                    session.getLanguages().remove(session.getLanguages().size() - 1);
                }
                session.addLog("Limiting to the first " + maxLangs + " language(s) per Settings (skipped "
                        + dropped + "). Set 'max languages' to 0 to test all.");
            }

            // ── Sequential per-language validation ──
            // The first language is the reference: its on-screen text becomes the baseline that
            // subsequent languages are compared against to flag untranslated (unchanged) strings.
            Set<String> baseText = new java.util.LinkedHashSet<>();
            int total = session.getLanguages().size();

            // ── Prominent pre-execution summary ──
            session.addLog("═══════════════════════════════════════════════════════════════");
            session.addLog("  LOCALIZATION TESTING — " + total + " language(s) detected");
            String langList = session.getLanguages().stream()
                    .map(l -> l.getName() + " (" + l.getCode() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("");
            session.addLog("  Languages: " + langList);
            session.addLog("  Source   : " + session.getLanguageSource());
            session.addLog("  Coverage : 100% per language — every screen, module, feature, button, menu,");
            session.addLog("             dialog, popup, and input field tested. App data cleared between languages.");
            session.addLog("═══════════════════════════════════════════════════════════════");

            int pos = 0;
            boolean first = true;
            for (LanguageResult lr : session.getLanguages()) {
                pos++;
                if (session.isStopRequested()) {
                    session.addLog("Stopped by user — skipping the remaining language(s).");
                    break;
                }
                session.addLog("┌─── Language " + pos + " / " + total + " : "
                        + lr.getName() + " (" + lr.getCode() + ") ───");
                // For the first language, supply the stored picker entry so we can tap
                // by coordinates immediately without re-scanning the UI.
                InAppLanguageSelector.Lang pickerEntry = (first && pos <= picker.size()) ? picker.get(pos - 1) : null;
                runOneLanguage(session, lr, serial, apkFile, pkg, info, baseText, w, h, useInApp, first, dict, pickerEntry);
                first = false;
                // Per-language result summary
                String overallPass = deriveOverallStatus(lr);
                session.addLog("└─── " + lr.getName() + " result: " + overallPass
                        + " | screens=" + lr.getScreensExplored()
                        + " | strings=" + lr.getVisibleStrings()
                        + " | untranslated=" + lr.getUntranslated().size()
                        + " | issues=" + lr.getLocalizationIssues().size()
                        + " | functionality=" + lr.getFunctionalityStatus());
            }

            if (session.isStopRequested()) {
                long tested = session.getLanguages().stream().filter(l -> "DONE".equals(l.getState())).count();
                session.addLog("Localization testing stopped by user. Saved results for " + tested
                        + " language(s) tested so far.");
                generateConsolidatedReport(session);
                session.setState(LocalizationSession.State.STOPPED);
            } else {
                generateConsolidatedReport(session);
                session.addLog("Localization testing complete.");
                session.setState(LocalizationSession.State.COMPLETED);
            }
        } catch (Exception e) {
            log.error("Localization run error for {}: {}", session.getId(), e.getMessage(), e);
            fail(session, "Localization error: " + e.getMessage());
        } finally {
            watchdog.stopWatch(session.getId());
            if (serial != null) {
                try { setAppLocale(serial, session.getPackageName(), ""); } catch (Exception ignored) {}
                try { adb.uninstall(serial, session.getPackageName()); } catch (Exception ignored) {}
            }
            if (emu != null) emulators.shutdown(emu, session::addLog);
            session.setFinishedAt(System.currentTimeMillis());
        }
    }

    private void runOneLanguage(LocalizationSession session, LanguageResult lr, String serial, File apkFile, String pkg,
                                ApkInfo info, Set<String> baseText, int w, int h, boolean useInApp, boolean isReference,
                                Map<String, String> dict, InAppLanguageSelector.Lang pickerEntry) {
        String tag = lr.getName() + " (" + lr.getCode() + ")";
        try {
            lr.setState("TESTING");
            session.addLog("── " + tag + " ──");

            // Switch language. For startup-picker apps: clear app data so the chooser reappears,
            // launch, then tap the language row and verify the switch. Otherwise use the OS locale.
            boolean tapped = false;
            if (useInApp) {
                try {
                    if (isReference) {
                        // FIRST language: the app is ALREADY on the Language Selection screen from the
                        // one-time scan step (picker scrolled back to top, no relaunch needed).
                        // Tap immediately using the stored coordinates to avoid a slow re-scan that
                        // would risk the picker timing out or auto-advancing.
                        session.addLog(tag + ": tapping first language by stored coordinates (picker already on-screen).");
                        InAppLanguageSelector.SelectResult sel =
                                InAppLanguageSelector.selectByCoords(adb, serial, pickerEntry);
                        tapped = sel.tapped();
                        session.addLog(tag + ": " + sel.note());
                        if (tapped) lr.setSwitchMethod("Startup picker tap (coords)" + (sel.verified() ? " (switch verified)" : " (unverified)"));
                    } else {
                        // SUBSEQUENT languages: clear data so the one-time chooser reappears, relaunch,
                        // then wait (up to 15 s) until the Language screen is actually on screen.
                        adb.clearAppData(serial, pkg);
                        adb.launchApp(serial, pkg);
                        sleep(1500); // let the splash finish before polling
                        boolean shown = InAppLanguageSelector.waitForPicker(adb, serial, dict, 15000);
                        if (!shown) {
                            // Same rule as initial detection: never give up on the in-app screen
                            // without searching the whole app for it first (it may not reliably
                            // reappear right after a data-clear+relaunch on every app).
                            session.addLog(tag + ": Language screen not shown at launch — searching the app for it…");
                            int searchBudget = Math.min(120, Math.max(props.getCrawlMaxSteps(), 80));
                            shown = LocalizationCrawler.findLanguageScreen(adb, serial, pkg, dict, w, h,
                                    searchBudget, session::isStopRequested, session::addLog).found();
                        }
                        session.addLog(tag + ": Language screen "
                                + (shown ? "found." : "could not be found; will fall back to locale."));
                        if (shown) {
                            // Scroll to top so select() starts from a consistent position before
                            // searching downward for this language's row.
                            InAppLanguageSelector.scrollToTop(adb, serial, w, h);
                        }
                        InAppLanguageSelector.SelectResult sel = InAppLanguageSelector.select(adb, serial, lr.getName(), w, h);
                        tapped = sel.tapped();
                        session.addLog(tag + ": " + sel.note());
                        if (tapped) lr.setSwitchMethod("Startup picker tap" + (sel.verified() ? " (switch verified)" : " (unverified)"));
                    }
                } catch (Exception e) {
                    session.addLog(tag + ": startup selection error (" + e.getMessage() + ")");
                }
            }
            if (!tapped) {
                setAppLocale(serial, pkg, lr.getCode());
                lr.setSwitchMethod(useInApp ? "Android locale (picker row not found)" : "Android locale");
                session.addLog(tag + ": applied via Android locale" + (useInApp ? " (fallback)." : "."));
            }
            adb.clearLogcat(serial);

            String artifactBase = "/api/runs/" + session.getId() + "-" + lr.getCode() + "/artifacts/";
            ExplorationResult exp;
            if (tapped) {
                // Selected in-app: explore the live app from the post-selection state (no relaunch),
                // traversing all reachable screens and handling blockers, for full coverage.
                File runDir = new File(props.getWorkDir(), session.getId() + "-" + lr.getCode());
                runDir.mkdirs();
                int budget = Math.max(props.getCrawlMaxSteps(), 150);
                session.addLog(tag + ": completing the initial flow to Home, then exploring app content (up to "
                        + budget + " steps). Ad content will never be clicked; ads are closed via Close/X button only.");
                LocalizationCrawler.Result cr = LocalizationCrawler.explore(adb, serial, pkg, runDir, budget, w, h,
                        session::isStopRequested, 0L, session::addLog, true);
                exp = new ExplorationResult();
                exp.setCrashSuspected(cr.crashSuspected());
                exp.setLeftAppDuringRun(cr.leftApp());
                for (ScreenCapture s : cr.screens()) exp.addScreen(s);
                for (int i = 0; i < cr.actions(); i++) exp.incrementActions();
                session.addLog(tag + ": traversed " + cr.screens().size() + " screen(s), " + cr.actions() + " action(s).");
            } else {
                // Locale fallback (no in-app picker): the app opens straight to content, so the
                // shared Appium crawl is fine here.
                exp = crawl(session, serial, apkFile, info, pkg, lr.getCode());
            }
            boolean launched = exp != null && exp.getUniqueScreenCount() > 0;

            // Exclude the Splash screen and Language Selection screen — validate only real app content.
            ExplorationResult content = LocalizationAnalyzer.contentOnly(exp, dict);
            int visible = LocalizationAnalyzer.countVisibleStrings(content);
            lr.setVisibleStrings(visible);
            lr.setScreensExplored(content.getUniqueScreenCount());

            // Script-based checks (non-Latin languages): text in the WRONG script (untranslated /
            // hardcoded English left in) and MIXED-language text (target script + Latin together).
            LocalizationAnalyzer.ScriptFindings sf =
                    LocalizationAnalyzer.analyzeScript(content, lr.getCode(), artifactBase);
            lr.getMixedLanguage().addAll(sf.mixed);

            if (isReference) {
                // First language = reference: its content text defines the baseline.
                baseText.addAll(LocalizationAnalyzer.collectText(content));
                mergeUntranslated(lr, sf.untranslated); // foreign-script text even in the reference is worth listing
                lr.setTranslationStatus(visible == 0 ? "NO TEXT READ" : "REFERENCE");
                session.addLog(tag + ": reference language — captured " + baseText.size() + " baseline content string(s).");
            } else {
                mergeUntranslated(lr, LocalizationAnalyzer.findUntranslated(baseText, content, artifactBase));
                mergeUntranslated(lr, sf.untranslated);
                int unt = lr.getUntranslated().size();
                int translated = Math.max(0, visible - unt);
                double pct = visible == 0 ? 0 : (translated * 100.0 / visible);
                lr.setTranslationStatus(visible == 0 ? "NO TEXT READ"
                        : pct >= 95 ? "COMPLETE" : pct >= 60 ? "PARTIAL" : "MOSTLY UNTRANSLATED");
            }

            // Functionality validation (crashes, ANRs, UI/navigation) — content screens only.
            // Sanitize logcat so the framework's own clear-data/force-stop/relaunch cycle is NOT
            // mistaken for an application crash; real FATAL EXCEPTION crashes and ANRs are kept.
            String crashLog = safe(() -> adb.dumpCrashBuffer(serial)) + "\n" + safe(() -> adb.dumpLogcat(serial));
            CompatAnalyzer.Result a = CompatAnalyzer.analyze(content, sanitizeRestartNoise(crashLog), launched, lr.getName(), w, h, pkg);
            lr.setFunctionalityStatus(a.status());
            for (CompatAnalyzer.UiIssue i : a.issues()) {
                String shotUrl = (i.screenshot() != null && !i.screenshot().isBlank())
                        ? artifactBase + i.screenshot() : null;
                lr.getIssues().add(new CompatVersionResult.Issue(
                        i.screen(), i.type(), i.description(), i.severity().name(), i.logcat(), shotUrl, i.crash()));
            }
            // ── Senior-QA issue report ───────────────────────────────────────────
            // Consolidates ALL findings (untranslated, mixed, RTL, truncation, encoding,
            // functionality) into the LocalizationIssue QA format (screen + expected + actual).
            List<LanguageResult.LocalizationIssue> qaIssues = new java.util.ArrayList<>();
            // Layout / rendering defects — run for every language.
            qaIssues.addAll(LocalizationAnalyzer.detectTruncation(content, lr.getName(), artifactBase));
            qaIssues.addAll(LocalizationAnalyzer.detectEncodingIssues(content, lr.getName(), artifactBase));
            qaIssues.addAll(LocalizationAnalyzer.detectOverlappingText(content, lr.getName(), artifactBase));
            if (w > 0) qaIssues.addAll(LocalizationAnalyzer.detectRtlIssues(content, lr.getCode(), lr.getName(), artifactBase, w));
            // Translation / functionality findings — only meaningful for non-reference languages.
            if (!isReference) {
                qaIssues.addAll(LocalizationAnalyzer.toQaIssues(
                        lr.getUntranslated(), lr.getMixedLanguage(), lr.getIssues(), lr.getName()));
                // LLM translation-quality review — the one check that needs actual language
                // understanding (is a string that WAS translated actually a correct/sensible
                // translation, vs. garbled/wrong-language/placeholder text?). Fully opt-in and a
                // pure no-op when AI Review isn't configured; only ever adds findings on top of the
                // heuristic checks above.
                if (translationReviewer.isEnabled()) {
                    List<String> referenceSample = baseText.stream().limit(60).toList();
                    for (com.vasundhara.atf.engine.ScreenCapture s : content.getScreens()) {
                        List<String> screenTexts = LocalizationAnalyzer.translatableTexts(s);
                        if (screenTexts.isEmpty()) continue;
                        String screenName = com.vasundhara.atf.compat.CompatAnalyzer.screenName(s);
                        String shot = s.screenshotPath();
                        String shotUrl = (shot != null && !shot.isBlank()) ? artifactBase + shot : null;
                        for (com.vasundhara.atf.ai.LlmTranslationReviewer.Flag flag :
                                translationReviewer.reviewScreen(lr.getName(), lr.getCode(), referenceSample, screenTexts)) {
                            qaIssues.add(new LanguageResult.LocalizationIssue(screenName, "INCORRECT_TRANSLATION",
                                    flag.reason(), "A correct, natural " + lr.getName() + " translation.",
                                    "\"" + flag.text() + "\"", shotUrl, "MEDIUM"));
                        }
                    }
                }
            }
            lr.getLocalizationIssues().addAll(qaIssues);

            lr.setState("DONE");
            session.addLog(tag + ": translation " + lr.getTranslationStatus() + " ("
                    + lr.getUntranslated().size() + " untranslated, " + lr.getMixedLanguage().size()
                    + " mixed-language), functionality " + a.status()
                    + ", QA issues " + qaIssues.size() + ".");

            // Save this language's own developer-friendly report (screenshots + reproducible steps)
            // now, before the app is reset for the next language — "after completing one language,
            // save the report" — in addition to the consolidated cross-language summary at the end.
            File langRunDir = new File(props.getWorkDir(), session.getId() + "-" + lr.getCode());
            File report = LocalizationReportWriter.write(langRunDir, session.getApkFileName(), lr);
            if (report != null) {
                session.addLog(tag + ": report saved — /api/runs/" + session.getId() + "-" + lr.getCode()
                        + "/artifacts/report.html");
            }

            try { adb.forceStop(serial, pkg); } catch (Exception ignored) {}
        } catch (Exception e) {
            lr.setState("ERROR");
            lr.setError(e.getMessage());
            session.addLog(tag + ": error — " + e.getMessage());
        }
    }

    /** Install-once, locale-set; (re)launch and crawl, returning the exploration. */
    private ExplorationResult crawl(LocalizationSession session, String serial, File apkFile, ApkInfo info,
                                    String pkg, String runTag) {
        AndroidDriver driver = null;
        try {
            adb.forceStop(serial, pkg);
            adb.launchApp(serial, pkg);
            sleep(1500);
            driver = driverFactory.create(serial, info);
            DeviceManager.DeviceInfo dev = deviceManager.profile(serial);
            File runDir = new File(props.getWorkDir(), session.getId() + "-" + runTag);
            runDir.mkdirs();
            TestRun stub = new TestRun(session.getId(), session.getApkFileName(), List.of());
            TestContext ctx = new TestContext(props, adb, serial, apkFile, info, runDir, stub, dev);
            ctx.setDriver(driver);
            return explorationEngine.explore(ctx, Math.max(props.getCrawlMaxSteps(), 150));
        } catch (Exception e) {
            log.warn("Localization crawl failed ({}): {}", runTag, e.toString());
            return null;
        } finally {
            if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} }
        }
    }

    /** Add untranslated findings to a language result, de-duplicating by (screen, normalized text). */
    private void mergeUntranslated(LanguageResult lr, List<LanguageResult.Untranslated> add) {
        if (add == null || add.isEmpty()) return;
        Set<String> seen = new java.util.HashSet<>();
        for (LanguageResult.Untranslated u : lr.getUntranslated())
            seen.add(u.screen() + "||" + u.text().trim().toLowerCase());
        for (LanguageResult.Untranslated u : add) {
            if (seen.add(u.screen() + "||" + u.text().trim().toLowerCase()))
                lr.getUntranslated().add(u);
        }
    }

    /** Derive an overall PASS / WARNING / FAIL status for a finished language result. */
    private String deriveOverallStatus(LanguageResult lr) {
        if ("ERROR".equals(lr.getState())) return "ERROR";
        boolean hasCritical = lr.getLocalizationIssues().stream()
                .anyMatch(i -> "CRITICAL".equalsIgnoreCase(i.severity()));
        boolean hasHigh = lr.getLocalizationIssues().stream()
                .anyMatch(i -> "HIGH".equalsIgnoreCase(i.severity()));
        boolean funcFail = "FAIL".equalsIgnoreCase(lr.getFunctionalityStatus());
        boolean noText = "NO TEXT READ".equals(lr.getTranslationStatus());
        if (hasCritical || funcFail) return "FAIL";
        if (hasHigh || noText || "MOSTLY UNTRANSLATED".equals(lr.getTranslationStatus())) return "WARNING";
        if (lr.getLocalizationIssues().isEmpty() && lr.getUntranslated().isEmpty()) return "PASS";
        return "WARNING";
    }

    /** Generate a consolidated report across all tested languages and append it to session logs. */
    private void generateConsolidatedReport(LocalizationSession session) {
        List<LanguageResult> tested = session.getLanguages().stream()
                .filter(l -> "DONE".equals(l.getState()) || "ERROR".equals(l.getState())).toList();
        if (tested.isEmpty()) return;

        long passed   = tested.stream().filter(l -> "PASS".equals(deriveOverallStatus(l))).count();
        long warned   = tested.stream().filter(l -> "WARNING".equals(deriveOverallStatus(l))).count();
        long failed   = tested.stream().filter(l -> "FAIL".equals(deriveOverallStatus(l)) || "ERROR".equals(l.getState())).count();
        long totalIssues = tested.stream().mapToLong(l -> l.getLocalizationIssues().size()).sum();
        long totalUntranslated = tested.stream().mapToLong(l -> l.getUntranslated().size()).sum();

        // Count by issue type
        java.util.Map<String, Long> byType = new java.util.LinkedHashMap<>();
        for (LanguageResult lr : tested) {
            for (LanguageResult.LocalizationIssue i : lr.getLocalizationIssues()) {
                byType.merge(i.issueType(), 1L, Long::sum);
            }
        }

        session.addLog("╔═══════════════════════════════════════════════════════════════╗");
        session.addLog("║           CONSOLIDATED LOCALIZATION TEST REPORT               ║");
        session.addLog("╠═══════════════════════════════════════════════════════════════╣");
        session.addLog("║  Languages tested : " + tested.size() + " of " + session.getLanguages().size());
        session.addLog("║  PASS             : " + passed + " language(s)");
        session.addLog("║  WARNING          : " + warned + " language(s)");
        session.addLog("║  FAIL / ERROR     : " + failed + " language(s)");
        session.addLog("║  Total QA issues  : " + totalIssues);
        session.addLog("║  Untranslated str.: " + totalUntranslated);
        if (!byType.isEmpty()) {
            session.addLog("║  Issues by type   :");
            for (java.util.Map.Entry<String, Long> e : byType.entrySet()) {
                session.addLog("║    " + e.getKey() + " : " + e.getValue());
            }
        }
        session.addLog("╠═══════════════════════════════════════════════════════════════╣");
        session.addLog("║  Per-language summary:");
        for (LanguageResult lr : tested) {
            String st = deriveOverallStatus(lr);
            session.addLog("║  [" + padRight(st, 7) + "] " + padRight(lr.getName() + " (" + lr.getCode() + ")", 28)
                    + " | issues=" + lr.getLocalizationIssues().size()
                    + " untranslated=" + lr.getUntranslated().size()
                    + " screens=" + lr.getScreensExplored());
        }
        session.addLog("╚═══════════════════════════════════════════════════════════════╝");
    }

    private static String padRight(String s, int n) {
        if (s == null) s = "";
        return s.length() >= n ? s : s + " ".repeat(n - s.length());
    }

    private void setAppLocale(String serial, String pkg, String bcp47) {
        // API 33+: per-app locale. Empty string clears it (back to system default).
        adb.shell(serial, 30, "cmd", "locale", "set-app-locales", pkg, "--locales", bcp47);
    }

    private int firstApi() {
        for (String s : props.getCompatApiLevels().split(",")) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return 34;
    }

    private void fail(LocalizationSession s, String msg) {
        s.setError(msg);
        s.setState(LocalizationSession.State.FAILED);
        s.addLog("FAILED: " + msg);
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    /**
     * Remove logcat lines produced by the framework's own intentional app restarts
     * (force-stop / pm clear / relaunch) so they are not reported as crashes. Real
     * application crashes (FATAL EXCEPTION / AndroidRuntime) and ANRs are preserved.
     */
    private String sanitizeRestartNoise(String log) {
        if (log == null || log.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : log.split("\\R")) {
            String l = line.toLowerCase();
            boolean intentional = l.contains("force closing") || l.contains("force-stop") || l.contains("force stop")
                    || l.contains("has died") || l.contains("killing") || l.contains("am_kill")
                    || l.contains("lowmemorykiller") || (l.contains("process") && l.contains(" killed"))
                    || l.contains("scheduled stop") || l.contains("appdiedlocked");
            boolean realCrash = l.contains("fatal exception") || l.contains("androidruntime")
                    || l.contains("anr in") || l.contains("not responding");
            if (intentional && !realCrash) continue; // drop restart noise, keep genuine crash/ANR lines
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private interface ThrowingSupplier { String get() throws Exception; }
    private String safe(ThrowingSupplier s) {
        try { String v = s.get(); return v == null ? "" : v; } catch (Exception e) { return ""; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
