package com.vasundhara.atf.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A live model of the app's screens and how the crawler moved between them, used to make
 * exploration <em>frontier-directed</em> rather than purely depth-first: instead of giving up
 * when the current screen has nothing new to tap, the engine asks this graph for the nearest
 * screen that still has un-tried actions and a path of taps to get there, then replays it.
 *
 * <p>Nodes are keyed by a screen's structure signature (the stable "activity:structureHash"
 * prefix, so dynamic-content variants of one screen collapse to a single node). Each node holds
 * the set of actionable-widget keys still to try ({@code untried}) and those already tried.
 * Edges record {@code (fromScreen, actionKey) → toScreen} so a path can be reconstructed.
 *
 * <p>Also produces an honest coverage summary: distinct screens seen, actions exercised vs
 * discovered, and how many screens still carry un-tried actions.
 */
public class NavigationGraph {

    private static final class Node {
        final String sig;
        String activity;
        final Set<String> untried = new LinkedHashSet<>();
        final Set<String> tried = new HashSet<>();
        int visits;
        Node(String sig) { this.sig = sig; }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    // from-sig → (actionKey → to-sig)
    private final Map<String, Map<String, String>> edges = new HashMap<>();
    private int transitions;

    /** Register a screen the first time (or refresh it) with its actionable-widget keys. */
    public void observeScreen(String sig, String activity, Collection<String> actionKeys) {
        if (sig == null) return;
        Node n = nodes.computeIfAbsent(sig, Node::new);
        n.activity = activity;
        n.visits++;
        if (actionKeys != null) {
            for (String k : actionKeys) {
                if (k != null && !n.tried.contains(k)) n.untried.add(k);
            }
        }
    }

    /** Mark one action on a screen as tried (moves it out of that screen's frontier). */
    public void markTried(String sig, String actionKey) {
        if (sig == null || actionKey == null) return;
        Node n = nodes.get(sig);
        if (n == null) { n = nodes.computeIfAbsent(sig, Node::new); }
        n.untried.remove(actionKey);
        n.tried.add(actionKey);
    }

    /** Record that {@code actionKey} on {@code fromSig} led to {@code toSig}. */
    public void recordEdge(String fromSig, String actionKey, String toSig) {
        if (fromSig == null || actionKey == null || toSig == null) return;
        edges.computeIfAbsent(fromSig, k -> new HashMap<>()).put(actionKey, toSig);
        if (!fromSig.equals(toSig)) transitions++;
    }

    /** True when any known screen still has an un-tried action. */
    public boolean hasFrontier() {
        for (Node n : nodes.values()) if (!n.untried.isEmpty()) return true;
        return false;
    }

    /**
     * Breadth-first search from {@code fromSig} over recorded edges for the nearest screen that
     * still has un-tried actions, returning the ordered list of action keys to tap to reach it
     * (empty if {@code fromSig} itself is a frontier; null if no frontier is reachable via known
     * edges). Bounded by {@code maxDepth} so replay paths stay short and reliable.
     */
    public List<String> pathToNearestFrontier(String fromSig, int maxDepth) {
        if (fromSig == null || !nodes.containsKey(fromSig)) return null;
        if (!nodes.get(fromSig).untried.isEmpty()) return new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String[]> parent = new HashMap<>(); // sig → {parentSig, actionKey}
        Map<String, Integer> depth = new HashMap<>();
        queue.add(fromSig);
        depth.put(fromSig, 0);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = depth.get(cur);
            if (d >= maxDepth) continue;
            Map<String, String> out = edges.get(cur);
            if (out == null) continue;
            for (Map.Entry<String, String> e : out.entrySet()) {
                String next = e.getValue();
                if (depth.containsKey(next)) continue;
                parent.put(next, new String[]{cur, e.getKey()});
                depth.put(next, d + 1);
                Node nn = nodes.get(next);
                if (nn != null && !nn.untried.isEmpty()) {
                    // Reconstruct the action-key path from fromSig to this frontier node.
                    List<String> path = new ArrayList<>();
                    String s = next;
                    while (parent.containsKey(s)) { String[] p = parent.get(s); path.add(0, p[1]); s = p[0]; }
                    return path;
                }
                queue.add(next);
            }
        }
        return null;
    }

    public int totalScreens() { return nodes.size(); }

    public int frontierScreens() {
        int c = 0;
        for (Node n : nodes.values()) if (!n.untried.isEmpty()) c++;
        return c;
    }

    public int triedActions() {
        int c = 0;
        for (Node n : nodes.values()) c += n.tried.size();
        return c;
    }

    public int totalActions() {
        int c = 0;
        for (Node n : nodes.values()) c += n.tried.size() + n.untried.size();
        return c;
    }

    /** Honest action-coverage percentage: actions exercised / actions discovered. */
    public int coveragePercent() {
        int total = totalActions();
        return total == 0 ? 100 : (int) Math.round(100.0 * triedActions() / total);
    }

    public int transitions() { return transitions; }

    public String summary() {
        return "Navigation graph: " + totalScreens() + " screen(s), " + transitions
                + " transition(s), actions exercised " + triedActions() + "/" + totalActions()
                + " (" + coveragePercent() + "%), " + frontierScreens()
                + " screen(s) still had un-tried actions at finish.";
    }
}
