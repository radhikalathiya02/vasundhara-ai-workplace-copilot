package com.vasundhara.atf.engine;

import java.util.List;

/**
 * Snapshot of one unique UI state encountered during exploration: its activity,
 * the widgets present, an optional screenshot artifact and how long it took to settle.
 */
public record ScreenCapture(
        int index,
        String signature,
        String activity,
        List<Widget> widgets,
        String screenshotPath,
        long loadTimeMillis) {

    public List<Widget> actionableWidgets() {
        return widgets.stream().filter(Widget::actionable).toList();
    }
}
