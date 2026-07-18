package com.vasundhara.atf.localization;

import com.vasundhara.atf.device.AdbClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort in-app language selection: reads the current UI (uiautomator dump),
 * finds the row whose label matches the target language, taps it by coordinates,
 * then re-reads the UI to verify the screen actually changed (i.e. the language
 * switch took effect). Falls back to the caller's locale mechanism when the row
 * is not on a reachable screen.
 */
public final class InAppLanguageSelector {

    private InAppLanguageSelector() {}

    /** Outcome of a tap-selection attempt. */
    public record SelectResult(boolean tapped, boolean verified, String note) {}

    private static final Pattern NODE =
            Pattern.compile("<node\\b[^>]*?\\bbounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"", Pattern.DOTALL);
    private static final Pattern TEXT_ATTR = Pattern.compile("\\btext=\"([^\"]*)\"");
    private static final Pattern DESC_ATTR = Pattern.compile("\\bcontent-desc=\"([^\"]*)\"");

    /** One label with its tappable center. */
    private record Node(String label, int cx, int cy) {}

    /** A language option found on the startup picker: on-screen label + mapped code. */
    public record Lang(String label, String code, int cx, int cy) {}

    /**
     * Detect the languages offered on the current screen by matching node labels against the
     * language dictionary (English names + native endonyms). De-dupes by language code.
     */
    public static java.util.List<Lang> detectLanguages(String xml, java.util.Map<String, String> dict) {
        java.util.LinkedHashMap<String, Lang> byCode = new java.util.LinkedHashMap<>();
        if (xml == null || dict == null) return new ArrayList<>();
        for (Node n : parse(xml)) {
            String code = codeFor(n.label(), dict);
            if (code != null) byCode.putIfAbsent(code, new Lang(n.label(), code, n.cx(), n.cy()));
        }
        return new ArrayList<>(byCode.values());
    }

    /**
     * Scroll the Language Selection list from top to bottom, accumulating every language row.
     * Starts at the very top of the list so languages above the current scroll position are
     * never missed; keeps scrolling until three consecutive passes add nothing new (end of list).
     * Returns the list at the top position so the first {@link #select} call starts there.
     *
     * <p>Sleep times are kept short (350 ms per step) so the whole scan completes in under 15 s
     * for a 30-language list — important because many apps auto-navigate off the picker after ~30 s.
     */
    public static java.util.List<Lang> scanAllLanguages(AdbClient adb, String serial,
                                                        java.util.Map<String, String> dict, int w, int h) {
        java.util.LinkedHashMap<String, Lang> all = new java.util.LinkedHashMap<>();

        // Always reset to the top before scanning so no rows are skipped.
        scrollToTop(adb, serial, w, h);
        sleep(400); // let the scroll animation settle before the first capture

        int stale = 0;
        for (int i = 0; i < 40 && stale < 3; i++) {
            int before = all.size();
            for (Lang l : detectLanguages(adb.uiDump(serial), dict)) all.putIfAbsent(l.code(), l);
            stale = (all.size() == before) ? stale + 1 : 0;
            // Only scroll when we haven't yet exhausted the list; skip the last no-op scroll.
            if (stale < 3) {
                scrollDown(adb, serial, w, h);
                sleep(350); // short wait — just enough for RecyclerView to render new rows
            }
        }

        // Restore top position so the first language row is on-screen and tappable immediately.
        scrollToTop(adb, serial, w, h);
        sleep(400);

        return new ArrayList<>(all.values());
    }

    /** Map a label to a code, tolerating region annotations like "Português (BR)". */
    private static String codeFor(String label, java.util.Map<String, String> dict) {
        String n = norm(label);
        String c = dict.get(n);
        if (c == null) {
            String stripped = n.replaceAll("\\s*[\\(\\[].*?[\\)\\]]\\s*", "").trim();
            if (!stripped.equals(n)) c = dict.get(stripped);
        }
        return c;
    }

    private static void scrollDown(AdbClient adb, String serial, int w, int h) {
        if (w <= 0 || h <= 0) { w = 1080; h = 1920; }
        // Wider swipe range (80 % → 20 %) and slower duration (600 ms) so the gesture
        // registers reliably on custom RecyclerView/ListView language pickers.
        int x = w / 2, y1 = (int) (h * 0.80), y2 = (int) (h * 0.20);
        adb.shell(serial, 10, "input", "swipe", String.valueOf(x), String.valueOf(y1),
                String.valueOf(x), String.valueOf(y2), "600");
    }

    /** Swipe back up repeatedly so a scrolled picker returns to the top (first language visible). */
    public static void scrollToTop(AdbClient adb, String serial, int w, int h) {
        if (w <= 0 || h <= 0) { w = 1080; h = 1920; }
        int x = w / 2, y1 = (int) (h * 0.25), y2 = (int) (h * 0.80);
        // 5 upward swipes — sufficient for any reasonable list length; no-ops at the very top.
        for (int i = 0; i < 5; i++) {
            adb.shell(serial, 10, "input", "swipe", String.valueOf(x), String.valueOf(y1),
                    String.valueOf(x), String.valueOf(y2), "250");
            sleep(200);
        }
        // Brief settle so the list is fully at rest before the first uiDump.
        sleep(300);
    }

    /**
     * Wait until the Language Selection screen is actually showing (≥2 known language rows),
     * polling the live UI. Avoids selecting while the splash screen is still up.
     */
    public static boolean waitForPicker(AdbClient adb, String serial, java.util.Map<String, String> dict, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (detectLanguages(adb.uiDump(serial), dict).size() >= 2) return true;
            sleep(500);
        }
        return !detectLanguages(adb.uiDump(serial), dict).isEmpty();
    }

    /**
     * Select the first language using its stored screen coordinates from {@link #scanAllLanguages}.
     *
     * <p>Used for the reference (first) language only: the picker is still on-screen from the
     * scan step, scrolled back to the top, so we tap the exact coordinates that were recorded for
     * that row without any re-scan or label-matching. After the tap, handles any "Continue" button
     * that appears before the app transitions to the main content.
     *
     * @param lang  the first {@link Lang} returned by {@link #scanAllLanguages}
     */
    public static SelectResult selectByCoords(AdbClient adb, String serial, Lang lang) {
        if (lang == null)
            return new SelectResult(false, false, "No language coordinates available.");

        List<Node> beforeNodes = parse(adb.uiDump(serial));
        Set<String> beforeTexts = texts(beforeNodes);

        adb.tap(serial, lang.cx(), lang.cy());
        sleep(1000);

        // Check whether a "Continue" / "Get started" button appeared and tap it.
        List<Node> midNodes = parse(adb.uiDump(serial));
        Node continueBtn = findContinueButton(midNodes);
        if (continueBtn != null) {
            adb.tap(serial, continueBtn.cx(), continueBtn.cy());
            sleep(1500);
            midNodes = parse(adb.uiDump(serial));
        }

        Set<String> afterTexts = texts(midNodes);
        boolean changed = !afterTexts.isEmpty() && !afterTexts.equals(beforeTexts);
        return new SelectResult(true, changed,
                changed ? "Tapped \"" + lang.label() + "\" by coordinates; screen changed (switch confirmed)."
                        : "Tapped \"" + lang.label() + "\" by coordinates; awaiting screen transition.");
    }

    /**
     * Try to select {@code label} on whatever screen is currently shown.
     *
     * @param adb     device client
     * @param serial  device serial
     * @param label   the on-screen language label to tap (e.g. "Español")
     */
    public static SelectResult select(AdbClient adb, String serial, String label, int w, int h) {
        String target = norm(label);
        // The picker is always at the top when select() is called:
        //   first language  → scanAllLanguages() just finished and reset to top
        //   later languages → app was cleared and relaunched; picker opens at top
        // Search downward from the current position; no scrollToTop needed here.
        List<Node> nodes = List.of();
        Node match = null;
        for (int i = 0; i < 16 && match == null; i++) {
            String dump = adb.uiDump(serial);
            if (dump == null || dump.isBlank())
                return new SelectResult(false, false, "Could not read the screen (uiautomator dump empty).");
            nodes = parse(dump);
            for (Node n : nodes) {
                if (norm(n.label()).equals(target)) { match = n; break; }
            }
            if (match == null) { scrollDown(adb, serial, w, h); sleep(700); }
        }
        if (match == null)
            return new SelectResult(false, false, "Language row \"" + label + "\" not found after scrolling the list.");

        Set<String> beforeTexts = texts(nodes);
        adb.tap(serial, match.cx(), match.cy());
        sleep(1200);

        // Step 2 — if tapping the row only selected the radio button (still on the picker screen),
        // find and tap the "Continue in [Language] →" advance button to actually proceed.
        List<Node> midNodes = parse(adb.uiDump(serial));
        Node continueBtn = findContinueButton(midNodes);
        if (continueBtn != null) {
            adb.tap(serial, continueBtn.cx(), continueBtn.cy());
            sleep(1500);
            midNodes = parse(adb.uiDump(serial));
        }

        Set<String> afterTexts = texts(midNodes);
        boolean changed = !afterTexts.isEmpty() && !afterTexts.equals(beforeTexts);
        return new SelectResult(true, changed,
                changed ? "Tapped \"" + label + "\" and advance button; screen changed (switch confirmed)."
                        : "Tapped \"" + label + "\"; could not confirm a UI change.");
    }

    /**
     * Finds the advance button on a Language Selection screen — handles labels like
     * "Continue in English →", "Continuar en Español →", "Get started", etc.
     * All matching nodes are treated as tappable (the language picker has few non-button nodes).
     */
    private static Node findContinueButton(List<Node> nodes) {
        for (Node n : nodes) {
            String t = norm(n.label());
            if (t.startsWith("continue") || t.equals("get started") || t.equals("get started!")
                    || t.equals("let's go") || t.equals("lets go") || t.equals("proceed")
                    || t.equals("next") || t.equals("start")) return n;
        }
        return null;
    }

    private static List<Node> parse(String xml) {
        List<Node> out = new ArrayList<>();
        if (xml == null) return out;
        // Split into individual <node ...> chunks and pull bounds + text/desc from each.
        for (String chunk : xml.split("(?=<node\\b)")) {
            Matcher b = NODE.matcher(chunk);
            if (!b.find()) continue;
            int l = Integer.parseInt(b.group(1)), t = Integer.parseInt(b.group(2));
            int r = Integer.parseInt(b.group(3)), bo = Integer.parseInt(b.group(4));
            String label = "";
            Matcher tm = TEXT_ATTR.matcher(chunk);
            if (tm.find() && !tm.group(1).isBlank()) label = tm.group(1);
            if (label.isBlank()) {
                Matcher dm = DESC_ATTR.matcher(chunk);
                if (dm.find()) label = dm.group(1);
            }
            if (label.isBlank()) continue;
            out.add(new Node(unescape(label), (l + r) / 2, (t + bo) / 2));
        }
        return out;
    }

    private static Set<String> texts(List<Node> nodes) {
        Set<String> s = new LinkedHashSet<>();
        for (Node n : nodes) s.add(norm(n.label()));
        return s;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
