package com.vasundhara.atf.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-screen record of a scroll sweep: detected direction, depth reached, whether the
 * end was hit (vs. infinite/lazy loading), the elements/components revealed by scrolling,
 * any UI issues found in the scrolled region, and before/during/after screenshots.
 *
 * <p>Internal value holder populated by {@code ExplorationEngine} and consumed by the
 * {@code ScrollTest} category — not serialized directly, hence the plain fields.
 */
public class ScrollReport {

    public String activity;
    /** "vertical", "horizontal" or "none". */
    public String direction = "none";
    /** Number of swipe steps performed (scroll depth). */
    public int scrollSteps;
    public boolean endReached;
    public boolean infiniteScrollSuspected;
    /** "Complete", "Partial" or "Infinite-scroll (capped)". */
    public String coverage = "Complete";

    public int initialElements;     // elements visible before scrolling
    public int totalElements;       // unique elements discovered across the whole sweep
    public int revealedElements;    // totalElements - initialElements

    // Component inventory across the scrolled screen.
    public int buttons, inputs, images, clickable, cards;

    // Issues detected in the scrolled region.
    public int missingLabels, smallTargets, offScreen, overlaps;

    public String beforeShot;
    public final List<String> duringShots = new ArrayList<>();
    public String afterShot;

    public boolean hasIssues() {
        return missingLabels + smallTargets + offScreen + overlaps > 0;
    }
}
