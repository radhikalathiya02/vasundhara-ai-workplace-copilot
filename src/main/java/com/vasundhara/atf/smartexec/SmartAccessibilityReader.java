package com.vasundhara.atf.smartexec;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a UI-hierarchy XML string into {@link SmartWidget}s. Smart Execution's own
 * accessibility-tree reader — independent of {@code engine.ExplorationEngine}'s parsing.
 *
 * <p>Handles BOTH XML shapes the framework encounters, since they carry the same attributes
 * (class, package, text, bounds, clickable, ...) but structure the tree differently:
 * <ul>
 *   <li>raw {@code adb shell uiautomator dump}: every element is a generic {@code <node>} tag.</li>
 *   <li>Appium/UiAutomator2's {@code driver.getPageSource()}: each element is named after its OWN
 *       widget class (e.g. {@code <android.widget.FrameLayout>}) under a {@code <hierarchy>} root —
 *       verified live: matching only {@code <node>} silently parsed zero widgets from this shape,
 *       even though the content itself was perfectly valid and non-empty.</li>
 * </ul>
 * Matching every element via the {@code "*"} wildcard (skipping only the {@code <hierarchy>} root
 * wrapper, which carries no widget attributes of its own) handles both shapes with one pass.
 */
public final class SmartAccessibilityReader {

    private SmartAccessibilityReader() {}

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    public static List<SmartWidget> parse(String uiDumpXml) {
        List<SmartWidget> out = new ArrayList<>();
        if (uiDumpXml == null || uiDumpXml.isBlank()) return out;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document doc = dbf.newDocumentBuilder().parse(
                    new ByteArrayInputStream(uiDumpXml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                if ("hierarchy".equals(el.getTagName())) continue;
                Matcher m = BOUNDS.matcher(attr(el, "bounds", ""));
                int x = 0, y = 0, w = 0, h = 0;
                if (m.find()) {
                    int x1 = Integer.parseInt(m.group(1)), y1 = Integer.parseInt(m.group(2));
                    int x2 = Integer.parseInt(m.group(3)), y2 = Integer.parseInt(m.group(4));
                    x = x1; y = y1; w = Math.max(0, x2 - x1); h = Math.max(0, y2 - y1);
                }
                out.add(new SmartWidget(
                        attr(el, "class", ""),
                        attr(el, "resource-id", ""),
                        attr(el, "text", ""),
                        attr(el, "content-desc", ""),
                        attr(el, "hint", ""),
                        bool(el, "clickable"),
                        bool(el, "long-clickable"),
                        bool(el, "scrollable"),
                        bool(el, "checkable"),
                        bool(el, "checked"),
                        boolDefault(el, "enabled", true),
                        boolDefault(el, "displayed", true),
                        x, y, w, h));
            }
        } catch (Exception ignored) {
            // Malformed/partial dump — return whatever was parsed before the failure (usually empty).
        }
        return out;
    }

    /**
     * The package that actually owns the dumped tree — the MAJORITY {@code package="..."} value
     * across all nodes (a small system overlay/dialog node shouldn't cause a false mismatch against
     * the app under test). Returns null if the dump has no nodes at all.
     */
    public static String rootPackage(String uiDumpXml) {
        if (uiDumpXml == null || uiDumpXml.isBlank()) return null;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document doc = dbf.newDocumentBuilder().parse(
                    new ByteArrayInputStream(uiDumpXml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                if ("hierarchy".equals(el.getTagName())) continue;
                String pkg = attr(el, "package", "");
                if (!pkg.isBlank()) counts.merge(pkg, 1, Integer::sum);
            }
        } catch (Exception ignored) {
            return null;
        }
        return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    /** Screen extents, derived from the largest node bounds in the dump. */
    public static int[] screenExtents(List<SmartWidget> widgets) {
        int w = 0, h = 0;
        for (SmartWidget wd : widgets) { w = Math.max(w, wd.x() + wd.width()); h = Math.max(h, wd.y() + wd.height()); }
        return new int[]{w, h};
    }

    private static String attr(Element el, String name, String def) {
        String v = el.getAttribute(name);
        return (v == null || v.isEmpty()) ? def : v;
    }
    private static boolean bool(Element el, String name) { return "true".equals(el.getAttribute(name)); }
    private static boolean boolDefault(Element el, String name, boolean def) {
        String v = el.getAttribute(name);
        return (v == null || v.isEmpty()) ? def : "true".equals(v);
    }
}
