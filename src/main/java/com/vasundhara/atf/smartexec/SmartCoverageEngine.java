package com.vasundhara.atf.smartexec;

import java.util.List;

/** Derives the coverage summary attached to a {@link SmartSession} from its navigation graph. */
public final class SmartCoverageEngine {

    private SmartCoverageEngine() {}

    public static void apply(SmartSession session, SmartNavigationGraph graph) {
        session.setTotalScreens(graph.totalScreens());
        session.setTestedScreens(graph.totalScreens() - graph.frontierScreens());
        session.setScreenCoveragePct(graph.totalScreens() == 0 ? 0
                : (int) Math.round(100.0 * (graph.totalScreens() - graph.frontierScreens()) / graph.totalScreens()));
        session.setFeatureCoveragePct(graph.coveragePercent());
        List<String> untested = graph.untestedScreenNames();
        session.setUntestedFlows(untested);
    }
}
