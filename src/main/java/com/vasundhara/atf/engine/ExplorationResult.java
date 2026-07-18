package com.vasundhara.atf.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregated output of one automated UI crawl. This single shared artifact feeds the
 * functional, UI/UX, accessibility, E2E and exploratory categories so the app is
 * driven once rather than re-crawled per category.
 */
public class ExplorationResult {

    private final List<ScreenCapture> screens = new ArrayList<>();
    private final Set<String> activitiesReached = new LinkedHashSet<>();
    private final List<String> transitions = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<ScrollReport> scrollReports = new ArrayList<>();

    private int actionsPerformed;
    private boolean appiumAvailable;
    private boolean leftAppDuringRun;
    private boolean crashSuspected;
    private long durationMillis;

    public void addScreen(ScreenCapture screen) {
        screens.add(screen);
        if (screen.activity() != null && !screen.activity().isBlank()) {
            activitiesReached.add(screen.activity());
        }
    }

    public void recordTransition(String from, String to) {
        transitions.add(from + " -> " + to);
    }

    public void note(String note) {
        notes.add(note);
    }

    public void addScrollReport(ScrollReport report) {
        scrollReports.add(report);
    }

    public List<ScrollReport> getScrollReports() {
        return scrollReports;
    }

    public void incrementActions() {
        actionsPerformed++;
    }

    public List<ScreenCapture> getScreens() { return screens; }
    public Set<String> getActivitiesReached() { return activitiesReached; }
    public List<String> getTransitions() { return transitions; }
    public List<String> getNotes() { return notes; }

    public int getActionsPerformed() { return actionsPerformed; }

    public boolean isAppiumAvailable() { return appiumAvailable; }
    public void setAppiumAvailable(boolean appiumAvailable) { this.appiumAvailable = appiumAvailable; }

    public boolean isLeftAppDuringRun() { return leftAppDuringRun; }
    public void setLeftAppDuringRun(boolean leftAppDuringRun) { this.leftAppDuringRun = leftAppDuringRun; }

    public boolean isCrashSuspected() { return crashSuspected; }
    public void setCrashSuspected(boolean crashSuspected) { this.crashSuspected = crashSuspected; }

    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = durationMillis; }

    public int getUniqueScreenCount() { return screens.size(); }
}
