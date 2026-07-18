package com.vasundhara.atf.localization;

import com.vasundhara.atf.compat.CompatVersionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Localization result for one language: translation validation (untranslated
 * strings with the screen they appear on) plus functionality validation
 * (crashes, ANRs, UI/navigation issues caused by switching to that language).
 */
public class LanguageResult {

    /** A string that did not change after switching language — likely untranslated. */
    public record Untranslated(String screen, String text, String screenshotUrl) {}

    /**
     * A single localization issue in Senior-QA format: screen, issue type, what was
     * expected, what was actually observed, severity, and screenshot evidence.
     */
    public record LocalizationIssue(
            String screen,
            String issueType,       // MISSING_TRANSLATION | MIXED_LANGUAGE | TEXT_TRUNCATION | ENCODING_ISSUE | RTL_LAYOUT_ISSUE | FUNCTIONALITY_ISSUE
            String description,
            String expectedResult,
            String actualResult,
            String screenshotUrl,
            String severity         // CRITICAL | HIGH | MEDIUM | LOW | INFO
    ) {}

    private final String code;       // e.g. "es"
    private final String name;       // e.g. "Spanish"
    private volatile String state = "PENDING"; // PENDING | TESTING | DONE | ERROR
    private String translationStatus = "—";    // COMPLETE | PARTIAL | MOSTLY UNTRANSLATED
    private String functionalityStatus = "—";  // PASS | WARNING | FAIL
    private String switchMethod = "—";          // how the language was applied (in-app tap / locale)
    private int screensExplored;
    private int visibleStrings;
    private final List<Untranslated> untranslated = new ArrayList<>();
    private final List<Untranslated> mixedLanguage = new ArrayList<>(); // mixed-script text (target + Latin)
    private final List<CompatVersionResult.Issue> issues = new ArrayList<>();
    private final List<LocalizationIssue> localizationIssues = new ArrayList<>();
    private String error;

    public LanguageResult(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getState() { return state; }
    public void setState(String s) { this.state = s; }
    public String getTranslationStatus() { return translationStatus; }
    public void setTranslationStatus(String s) { this.translationStatus = s; }
    public String getFunctionalityStatus() { return functionalityStatus; }
    public void setFunctionalityStatus(String s) { this.functionalityStatus = s; }
    public String getSwitchMethod() { return switchMethod; }
    public void setSwitchMethod(String s) { this.switchMethod = s; }
    public int getScreensExplored() { return screensExplored; }
    public void setScreensExplored(int v) { this.screensExplored = v; }
    public int getVisibleStrings() { return visibleStrings; }
    public void setVisibleStrings(int v) { this.visibleStrings = v; }
    public List<Untranslated> getUntranslated() { return untranslated; }
    public List<Untranslated> getMixedLanguage() { return mixedLanguage; }
    public List<CompatVersionResult.Issue> getIssues() { return issues; }
    public List<LocalizationIssue> getLocalizationIssues() { return localizationIssues; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
