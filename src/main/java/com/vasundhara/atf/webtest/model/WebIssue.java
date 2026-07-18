package com.vasundhara.atf.webtest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vasundhara.atf.model.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A single canonical finding produced by any website analyzer — a bug, risk, warning or
 * best-practice recommendation. Every analyzer (SEO, accessibility, security, performance,
 * broken-link, JS-error, responsive, form-validation …) normalises its raw output into this one
 * shape so the aggregator can deduplicate, merge cross-page occurrences, severity-rank and score
 * them uniformly (see {@code IssueAggregator}).
 *
 * <p>Enterprise-grade detail: beyond the headline {@link #title}/{@link #detail}, a finding
 * carries the full developer-facing story — {@link #impact} (who/what it hurts), {@link #rootCause}
 * (why it happens), {@link #standard} (the spec/guideline being violated), a concrete
 * {@link #codeFix} snippet, and step-by-step {@link #reproSteps} — so a developer can understand
 * and fix it without re-investigating from scratch.
 *
 * <p>Mutable by design: the aggregator merges duplicates in place (folding additional affected
 * pages and sources into an existing issue) rather than allocating a new instance per merge.
 * Instances are only mutated during aggregation, before the report is exposed. The {@code with*}
 * methods return {@code this} so analyzers can build a rich finding fluently.
 */
public class WebIssue {

    private final String id = UUID.randomUUID().toString();
    private WebIssueCategory category;
    private Severity severity;
    private String title;
    private String detail;
    private String recommendation;

    // ---- Rich, developer-facing context (all optional) ----
    /** The concrete impact — on users, performance, SEO, accessibility, security or functionality. */
    private String impact;
    /** The underlying reason the issue occurs, when it can be identified. */
    private String rootCause;
    /** The standard/guideline being violated, e.g. "WCAG 2.1 SC 1.4.3 (AA)", "OWASP Secure Headers". */
    private String standard;
    /** A concrete code snippet / example showing the fix. */
    private String codeFix;
    /** Step-by-step reproduction instructions. */
    private final List<String> reproSteps = new ArrayList<>();

    /** The page URL this was first observed on (or the site origin for site-level issues). */
    private String page;
    /** Optional element locator / HTML snippet the issue points at. */
    private String element;
    /** Optional WCAG success criterion (accessibility issues only), e.g. "1.4.3". */
    private String wcag;
    /** Which analyzer(s) surfaced this — e.g. AXE, LIGHTHOUSE, SEO, SECURITY, NETWORK, FORM. */
    private final Set<String> sources = new LinkedHashSet<>();
    /** Path (relative to the run dir) of an evidence screenshot, if any. */
    private String evidence;
    /** Whether {@link #evidence} is an annotated (highlighted-element) capture. */
    private boolean evidenceAnnotated;
    /** All distinct pages this same issue was found on. */
    private final Set<String> affectedPages = new LinkedHashSet<>();
    /** How many times this canonical issue was observed across the whole scan. */
    private int occurrenceCount = 1;
    /** 0..1 — heuristic/AI findings are < 1; deterministic tool findings are 1. */
    private double confidence = 1.0;
    /** Wall-clock time the finding was first produced (epoch millis). */
    private final long detectedAtMillis = System.currentTimeMillis();

    public WebIssue() { }

    public static WebIssue of(WebIssueCategory category, Severity severity, String title,
                              String detail, String recommendation, String page, String source) {
        WebIssue i = new WebIssue();
        i.category = category;
        i.severity = severity;
        i.title = title;
        i.detail = detail;
        i.recommendation = recommendation;
        i.page = page;
        if (source != null) i.sources.add(source);
        if (page != null && !page.isBlank()) i.affectedPages.add(page);
        return i;
    }

    // ---- Fluent enrichment (analyzer-friendly) ----
    public WebIssue impact(String s) { this.impact = s; return this; }
    public WebIssue rootCause(String s) { this.rootCause = s; return this; }
    public WebIssue standard(String s) { this.standard = s; return this; }
    public WebIssue codeFix(String s) { this.codeFix = s; return this; }
    public WebIssue repro(String... steps) {
        for (String s : steps) if (s != null && !s.isBlank()) this.reproSteps.add(s);
        return this;
    }
    public WebIssue element(String s) { this.element = s; return this; }
    public WebIssue wcag(String s) { this.wcag = s; return this; }
    public WebIssue evidence(String rel, boolean annotated) {
        this.evidence = rel; this.evidenceAnnotated = annotated; return this;
    }
    public WebIssue confidence(double c) { this.confidence = c; return this; }

    /**
     * Canonical signature used for deduplication. Two issues with the same category, title and
     * element are considered the same defect (regardless of which page/analyzer found them) and
     * get merged into one cross-page issue.
     */
    @JsonIgnore
    public String signature() {
        String el = element == null ? "" : element.trim().toLowerCase();
        String t = title == null ? "" : title.trim().toLowerCase();
        return category + "|" + t + "|" + el;
    }

    /** Fold another occurrence of the same canonical issue into this one. */
    public void mergeOccurrence(WebIssue other) {
        this.occurrenceCount++;
        this.affectedPages.addAll(other.affectedPages);
        this.sources.addAll(other.sources);
        // Keep the most severe classification when two analyzers disagree.
        if (other.severity != null && (this.severity == null
                || other.severity.weight() > this.severity.weight())) {
            this.severity = other.severity;
        }
        // Prefer to keep the first non-null rich context; back-fill anything still missing.
        if (this.evidence == null && other.evidence != null) {
            this.evidence = other.evidence; this.evidenceAnnotated = other.evidenceAnnotated;
        }
        if (this.wcag == null && other.wcag != null) this.wcag = other.wcag;
        if (this.impact == null && other.impact != null) this.impact = other.impact;
        if (this.rootCause == null && other.rootCause != null) this.rootCause = other.rootCause;
        if (this.standard == null && other.standard != null) this.standard = other.standard;
        if (this.codeFix == null && other.codeFix != null) this.codeFix = other.codeFix;
        if (this.reproSteps.isEmpty() && !other.reproSteps.isEmpty()) this.reproSteps.addAll(other.reproSteps);
        // Agreement between independent sources raises confidence toward certainty.
        this.confidence = Math.max(this.confidence, other.confidence);
    }

    public String getId() { return id; }
    public WebIssueCategory getCategory() { return category; }
    public void setCategory(WebIssueCategory category) { this.category = category; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }
    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }
    public String getStandard() { return standard; }
    public void setStandard(String standard) { this.standard = standard; }
    public String getCodeFix() { return codeFix; }
    public void setCodeFix(String codeFix) { this.codeFix = codeFix; }
    public List<String> getReproSteps() { return reproSteps; }
    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; if (page != null && !page.isBlank()) affectedPages.add(page); }
    public String getElement() { return element; }
    public void setElement(String element) { this.element = element; }
    public String getWcag() { return wcag; }
    public void setWcag(String wcag) { this.wcag = wcag; }
    public Set<String> getSources() { return sources; }
    public void addSource(String source) { if (source != null) sources.add(source); }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public boolean isEvidenceAnnotated() { return evidenceAnnotated; }
    public void setEvidenceAnnotated(boolean evidenceAnnotated) { this.evidenceAnnotated = evidenceAnnotated; }
    public List<String> getAffectedPages() { return new ArrayList<>(affectedPages); }
    public int getAffectedPageCount() { return affectedPages.size(); }
    public int getOccurrenceCount() { return occurrenceCount; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public long getDetectedAtMillis() { return detectedAtMillis; }
}
