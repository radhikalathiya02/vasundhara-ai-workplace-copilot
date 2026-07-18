package com.vasundhara.atf.analysis;

/** One row of the generated Test Case Sheet. */
public class TestCaseRow {
    public String id;
    public String module;
    public String feature;
    public String screenName;
    public String scenario;
    public String preconditions;
    public String steps;
    public String expectedResult;
    public String priority;             // P1 / P2 / P3
    public String severity;             // Critical / High / Medium / Low
    public String testType;             // Positive / Negative / Boundary / Edge Case / Navigation / Security / ...
    public String category;             // Functional / UI / Security / Performance / Accessibility / Localization / Network
    public String automationFeasibility; // High / Medium / Low
    public String remarks;

    public TestCaseRow(String module, String feature, String screenName, String scenario,
                        String preconditions, String steps, String expectedResult,
                        String priority, String severity, String testType, String category,
                        String automationFeasibility, String remarks) {
        this.module = module;
        this.feature = feature;
        this.screenName = screenName;
        this.scenario = scenario;
        this.preconditions = preconditions;
        this.steps = steps;
        this.expectedResult = expectedResult;
        this.priority = priority;
        this.severity = severity;
        this.testType = testType;
        this.category = category;
        this.automationFeasibility = automationFeasibility;
        this.remarks = remarks;
    }
}
