package com.vasundhara.atf.engine;

import com.vasundhara.atf.model.TestResult;

/**
 * One automated test category (e.g. security, monkey, accessibility). Implementations
 * are discovered as Spring beans and run by the {@code TestOrchestrator}.
 */
public interface TestCategory {

    /** Stable machine key, e.g. {@code "security"}. */
    String key();

    /** Human-friendly name shown on the dashboard. */
    String displayName();

    String description();

    /** Whether this category needs a live Appium driver (vs. pure adb/static). */
    boolean requiresAppium();

    /**
     * When {@code true} this category is excluded from the available-categories list
     * and from the default run-all selection. It can still be invoked explicitly by key
     * (preserving backward compatibility with saved DB records), but it will no longer
     * appear on the dashboard or be selected automatically.
     */
    default boolean hidden() { return false; }

    /** Execute against the prepared context, recording findings into {@code result}. */
    void execute(TestContext ctx, TestResult result) throws Exception;
}
