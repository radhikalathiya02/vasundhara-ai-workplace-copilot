package com.vasundhara.atf.smartexec;

import java.util.List;

/** One captured screen state during a Smart Execution crawl. */
public record SmartScreen(int index, String signature, String screenName,
                          List<SmartWidget> widgets, String screenshotPath, long timestamp) {

    public long actionableCount() {
        return widgets == null ? 0 : widgets.stream().filter(SmartWidget::actionable).count();
    }
}
