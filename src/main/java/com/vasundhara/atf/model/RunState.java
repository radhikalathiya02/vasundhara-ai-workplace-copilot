package com.vasundhara.atf.model;

/** Lifecycle state of a whole APK test run. */
public enum RunState {
    QUEUED,
    ANALYZING,
    INSTALLING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
