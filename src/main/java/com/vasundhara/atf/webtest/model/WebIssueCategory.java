package com.vasundhara.atf.webtest.model;

/**
 * The analysis domains a website scan reports against. Mirrors the breadth of the Android
 * side's test categories, but for web: each analyzer emits {@link WebIssue}s tagged with one
 * of these so the report can group, score and filter by domain.
 */
public enum WebIssueCategory {

    PERFORMANCE("Performance"),
    ACCESSIBILITY("Accessibility"),
    SEO("SEO"),
    SECURITY("Security"),
    BEST_PRACTICE("Best Practices"),
    BROKEN_LINK("Broken Links"),
    JS_ERROR("JavaScript Errors"),
    NETWORK("Network"),
    RESPONSIVE("Responsive Design"),
    UI_UX("UI / UX"),
    FORM_VALIDATION("Forms & Input Validation"),
    HTML_VALIDATION("HTML Validation");

    private final String label;

    WebIssueCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
