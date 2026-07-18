package com.vasundhara.atf.smartexec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes duplicate bugs and low-confidence false positives before findings are attached to a
 * session, so the final report contains only reproducible, developer-friendly issues.
 */
public final class SmartBugValidator {

    private SmartBugValidator() {}

    /**
     * @param incoming        findings just produced by one category
     * @param seenDedupeKeys  keys already accepted earlier in this session (mutated: accepted keys are added)
     * @return the subset of {@code incoming} that are new and pass the minimum-evidence bar
     */
    public static List<SmartFinding> validate(List<SmartFinding> incoming, Set<String> seenDedupeKeys) {
        List<SmartFinding> out = new ArrayList<>();
        for (SmartFinding f : incoming) {
            if (f == null) continue;
            if (!seenDedupeKeys.add(f.dedupeKey())) continue;          // duplicate of an earlier finding
            if (!hasMinimumEvidence(f)) continue;                     // false-positive guard
            out.add(f);
        }
        return out;
    }

    /** A finding must name a screen/feature and state what was expected vs. what actually happened. */
    private static boolean hasMinimumEvidence(SmartFinding f) {
        if (f.title() == null || f.title().isBlank()) return false;
        if (f.severity() == null) return false;
        boolean hasScreen = f.screenName() != null && !f.screenName().isBlank();
        boolean hasOutcome = (f.actualResult() != null && !f.actualResult().isBlank())
                || (f.logsExcerpt() != null && !f.logsExcerpt().isBlank());
        return hasScreen && hasOutcome;
    }
}
