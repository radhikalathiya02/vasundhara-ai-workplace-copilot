package com.vasundhara.atf.webtest.analyze;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.model.WebIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses the raw per-page/per-analyzer findings into a clean, deduplicated, severity-ranked
 * issue set. The same defect reported on many pages (a header/footer problem, a site-wide
 * missing header) or by multiple analyzers (Lighthouse + axe both flag contrast) is merged into
 * one canonical {@link WebIssue} carrying every affected page and source — which is what keeps a
 * large-site report readable. Ordering is severity-desc, then most-affected-pages first.
 */
@Component
public class IssueAggregator {

    /** Deduplicate + merge by canonical signature, then sort. */
    public List<WebIssue> aggregate(List<WebIssue> raw) {
        Map<String, WebIssue> canonical = new LinkedHashMap<>();
        for (WebIssue issue : raw) {
            if (issue == null || issue.getSeverity() == null) continue;
            String sig = issue.signature();
            WebIssue existing = canonical.get(sig);
            if (existing == null) {
                canonical.put(sig, issue);
            } else {
                existing.mergeOccurrence(issue);
            }
        }
        List<WebIssue> merged = new ArrayList<>(canonical.values());
        merged.sort(Comparator
                .comparingInt((WebIssue i) -> i.getSeverity() == null ? -1 : i.getSeverity().weight()).reversed()
                .thenComparing(Comparator.comparingInt(WebIssue::getAffectedPageCount).reversed())
                .thenComparing(i -> i.getCategory() == null ? "" : i.getCategory().name()));
        return merged;
    }

    /** Convenience: total issues at or above a severity, for headline banners. */
    public static long countAtLeast(List<WebIssue> issues, Severity min) {
        return issues.stream().filter(i -> i.getSeverity() != null
                && i.getSeverity().weight() >= min.weight()).count();
    }
}
