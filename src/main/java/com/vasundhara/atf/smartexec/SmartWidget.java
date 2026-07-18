package com.vasundhara.atf.smartexec;

import java.util.List;

/**
 * A single UI element read from the device's accessibility tree, distilled to what the Smart
 * Execution crawler reasons about. Deliberately independent of {@code engine.Widget} — Smart
 * Execution parses its own accessibility dumps via {@link SmartAccessibilityReader} rather than
 * sharing the old crawler's parsing logic.
 */
public record SmartWidget(
        String className,
        String resourceId,
        String text,
        String contentDesc,
        String hint,
        boolean clickable,
        boolean longClickable,
        boolean scrollable,
        boolean checkable,
        boolean checked,
        boolean enabled,
        boolean displayed,
        int x, int y, int width, int height) {

    public boolean editable() {
        return className != null && className.contains("EditText");
    }

    public boolean actionable() {
        // `checkable` is included alongside clickable/longClickable/editable because some apps
        // (notably language/settings pickers using RadioButton, CheckBox or Switch rows) only set
        // checkable=true on the accessibility node without also setting clickable=true — without
        // this, such a row would never be seen as a candidate at all, and a screen where every
        // selectable item is checkable-only would have nothing to try before the confirm/advance
        // control becomes enabled, i.e. permanently stuck.
        return enabled && displayed && (clickable || longClickable || checkable || editable());
    }

    public boolean hasLabel() {
        return notBlank(text) || notBlank(contentDesc) || notBlank(hint);
    }

    /** Direct label, else the nearest overlapping non-actionable node's text (Compose merged semantics). */
    public String effectiveLabel(List<SmartWidget> all) {
        if (notBlank(text)) return text.trim();
        if (notBlank(contentDesc)) return contentDesc.trim();
        if (all == null) return "";
        int cx = x + width / 2, cy = y + height / 2;
        String best = ""; long bestArea = Long.MAX_VALUE;
        for (SmartWidget w : all) {
            if (w == this || w.actionable()) continue;
            String t = notBlank(w.text) ? w.text.trim() : (notBlank(w.contentDesc) ? w.contentDesc.trim() : "");
            if (t.isEmpty() || t.length() > 40) continue;
            if (cx >= w.x && cx <= w.x + w.width && cy >= w.y && cy <= w.y + w.height) {
                long area = (long) w.width * w.height;
                if (area > 0 && area < bestArea) { bestArea = area; best = t; }
            }
        }
        return best;
    }

    public int area() { return Math.max(0, width) * Math.max(0, height); }
    public int minSide() { return Math.min(Math.max(0, width), Math.max(0, height)); }
    public int cx() { return x + width / 2; }
    public int cy() { return y + height / 2; }

    public String simpleClass() {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    public String signature() {
        // Class + resourceId + position alone collide across widgets that share a reused shell
        // (e.g. a "Next" button at the same spot on every step of a wizard, or list rows that all
        // use the same generic resourceId) — including the widget's own label distinguishes them,
        // so a different control at the same position/class/id isn't mistaken for one already tried.
        String label = notBlank(text) ? text.trim() : (notBlank(contentDesc) ? contentDesc.trim() : "");
        String key = simpleClass() + "#" + (resourceId == null ? "" : resourceId) + "@" + x + "," + y;
        return label.isEmpty() ? key : key + ":" + (label.length() > 40 ? label.substring(0, 40) : label);
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
