package com.vasundhara.atf.testcase;

/** A single step within a test case. */
public record TcStep(int num, String description, String expectedResult, String testData) {
    public TcStep {
        description    = description    == null ? "" : description.trim();
        expectedResult = expectedResult == null ? "" : expectedResult.trim();
        testData       = testData       == null ? "" : testData.trim();
    }
}
