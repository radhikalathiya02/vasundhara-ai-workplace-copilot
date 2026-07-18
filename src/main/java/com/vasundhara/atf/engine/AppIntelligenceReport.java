package com.vasundhara.atf.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured understanding of an app under test, built from static APK analysis
 * and the live UI exploration. Consumed by {@link IntelligentE2ETest} (indirectly
 * via AppIntelligenceAnalyzer) to drive guided scenario-based testing.
 */
public class AppIntelligenceReport {

    public enum TestType { POSITIVE, NEGATIVE, BOUNDARY, EDGE_CASE }

    /** A named feature/module discovered in the app. */
    public static class DiscoveredFeature {
        private final String name;
        private final String description;
        private final List<String> relatedScreens;
        private final String screenKeyword;      // activity/screen name fragment used to navigate

        public DiscoveredFeature(String name, String description, List<String> relatedScreens, String screenKeyword) {
            this.name = name;
            this.description = description;
            this.relatedScreens = relatedScreens;
            this.screenKeyword = screenKeyword;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<String> getRelatedScreens() { return relatedScreens; }
        public String getScreenKeyword() { return screenKeyword; }
    }

    /** A named end-to-end user journey (sequence of screens). */
    public static class UserJourney {
        private final String name;
        private final List<String> steps;

        public UserJourney(String name, List<String> steps) {
            this.name = name;
            this.steps = steps;
        }

        public String getName() { return name; }
        public List<String> getSteps() { return steps; }
    }

    /** A specific test scenario to execute for a feature. */
    public static class TestScenario {
        private final String featureName;
        private final String scenarioName;
        private final TestType type;
        private final List<String> steps;
        private final String expectedResult;
        private final String screenKeyword;    // which screen to navigate to before executing

        public TestScenario(String featureName, String scenarioName, TestType type,
                            List<String> steps, String expectedResult, String screenKeyword) {
            this.featureName = featureName;
            this.scenarioName = scenarioName;
            this.type = type;
            this.steps = steps;
            this.expectedResult = expectedResult;
            this.screenKeyword = screenKeyword;
        }

        public String getFeatureName() { return featureName; }
        public String getScenarioName() { return scenarioName; }
        public TestType getType() { return type; }
        public List<String> getSteps() { return steps; }
        public String getExpectedResult() { return expectedResult; }
        public String getScreenKeyword() { return screenKeyword; }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private String appDomain = "General Android Application";
    private String appCategory = "General";
    private String analysisNotes;

    private final List<DiscoveredFeature> features = new ArrayList<>();
    private final List<UserJourney> userJourneys = new ArrayList<>();
    private final List<TestScenario> testScenarios = new ArrayList<>();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getAppDomain() { return appDomain; }
    public void setAppDomain(String appDomain) { this.appDomain = appDomain; }

    public String getAppCategory() { return appCategory; }
    public void setAppCategory(String appCategory) { this.appCategory = appCategory; }

    public String getAnalysisNotes() { return analysisNotes; }
    public void setAnalysisNotes(String analysisNotes) { this.analysisNotes = analysisNotes; }

    public List<DiscoveredFeature> getFeatures() { return features; }
    public List<UserJourney> getUserJourneys() { return userJourneys; }
    public List<TestScenario> getTestScenarios() { return testScenarios; }
}
