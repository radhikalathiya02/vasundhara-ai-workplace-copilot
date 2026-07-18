package com.vasundhara.atf.model;

/** Outcome of a single test category. */
public enum TestStatus {
    PENDING,
    RUNNING,
    PASSED,
    /** Completed with non-blocking issues (warnings) but no failures. */
    WARNING,
    FAILED,
    /** The category could not run (e.g. Appium unavailable, missing capability). */
    SKIPPED,
    /** The category threw an unexpected error while executing. */
    ERROR
}
