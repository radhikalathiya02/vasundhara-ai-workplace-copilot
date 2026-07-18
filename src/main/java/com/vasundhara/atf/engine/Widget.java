package com.vasundhara.atf.engine;

import java.util.List;

/**
 * A single node from the device UI hierarchy, distilled to the attributes the
 * test categories reason about (labels, interactivity, geometry).
 *
 * <p>{@code hint} is the Android hint-text for EditText fields (API 26+, exposed by UIAutomator2
 * as the {@code hint} XML attribute). It counts as an accessible label for input fields.
 */
public record Widget(
        String className,
        String resourceId,
        String text,
        String contentDesc,
        String hint,
        boolean clickable,
        boolean longClickable,
        boolean scrollable,
        boolean focusable,
        boolean enabled,
        boolean displayed,
        int x, int y, int width, int height,
        String pkg) {

    public boolean editable() {
        return className != null && className.contains("EditText");
    }

    public boolean actionable() {
        return enabled && displayed && (clickable || longClickable || editable());
    }

    /**
     * Returns true when this widget carries a direct accessible label via its own attributes:
     * visible text, content description, or hint text (for input fields).
     */
    public boolean hasLabel() {
        return notBlank(text) || notBlank(contentDesc) || notBlank(hint);
    }

    /**
     * Context-aware label check. Returns true when this widget is labelled either directly
     * (same as {@link #hasLabel()}) OR indirectly via an overlapping non-actionable node in the
     * full widget tree that carries text or a content description.
     *
     * <p>This handles three real-world patterns that {@link #hasLabel()} misses:
     * <ol>
     *   <li><b>Compose merged semantics</b> — the clickable container has empty own attributes
     *       but a child {@code Text} node overlaps its centre and carries the label.</li>
     *   <li><b>Icon + sibling label</b> — a {@code Row}/{@code LinearLayout} puts an
     *       {@code ImageButton} and a nearby {@code TextView} inside a common parent; the text
     *       is not on the button itself but overlaps or contains the button's tap area.</li>
     *   <li><b>Compose child with own contentDesc</b> — when semantics merging did not happen,
     *       the inner {@code Icon} or {@code Image} node carries the description while the
     *       outer clickable container node appears unlabelled.</li>
     * </ol>
     *
     * @param allWidgets the complete widget list for the current screen (from
     *                   {@link com.vasundhara.atf.engine.ScreenCapture#widgets()})
     */
    public boolean hasEffectiveLabel(List<Widget> allWidgets) {
        if (hasLabel()) return true;
        if (allWidgets == null || allWidgets.isEmpty()) return false;
        int cx = x + width / 2;
        int cy = y + height / 2;
        for (Widget t : allWidgets) {
            if (t == this) continue;
            // Only look at non-actionable nodes — actionable siblings are separate controls,
            // not labels. A non-clickable Text or Image inside the same bounds is a label.
            if (t.actionable()) continue;
            String lbl = notBlank(t.text()) ? t.text().trim()
                       : (notBlank(t.contentDesc()) ? t.contentDesc().trim() : "");
            if (lbl.isEmpty() || lbl.length() > 60) continue;
            // The candidate node must contain this widget's centre point.
            if (cx >= t.x() && cx <= t.x() + t.width()
                    && cy >= t.y() && cy <= t.y() + t.height()) {
                return true;
            }
        }
        return false;
    }

    public int area() {
        return Math.max(0, width) * Math.max(0, height);
    }

    public int minSide() {
        return Math.min(Math.max(0, width), Math.max(0, height));
    }

    /** Last dot-separated segment of the class name, e.g. {@code Button}. */
    public String simpleClass() {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    /** Stable identity within a screen for de-duplicating actions. */
    public String signature() {
        return simpleClass() + "#" + (resourceId == null ? "" : resourceId)
                + "@" + x + "," + y;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
