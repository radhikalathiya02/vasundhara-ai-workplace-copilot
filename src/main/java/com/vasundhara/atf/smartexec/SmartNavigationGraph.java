package com.vasundhara.atf.smartexec;

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
 * Smart Execution's own screen/transition graph, used to drive frontier-directed exploration
 * (replay a path to the nearest screen with un-tried actions instead of converging early) and to
 * report honest coverage. Independent implementation from {@code engine.NavigationGraph}.
 */
public class SmartNavigationGraph {

    private static final class Node {
        final Set<String> untried = new LinkedHashSet<>();
        final Set<String> tried = new HashSet<>();
        String screenName;
        int visits;
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> edges = new HashMap<>();
    private int transitions;

    public void observeScreen(String sig, String screenName, Collection<String> actionKeys) {
        if (sig == null) return;
        Node n = nodes.computeIfAbsent(sig, k -> new Node());
        n.screenName = screenName;
        n.visits++;
        if (actionKeys != null) for (String k : actionKeys) if (k != null && !n.tried.contains(k)) n.untried.add(k);
    }

    public void markTried(String sig, String actionKey) {
        if (sig == null || actionKey == null) return;
        Node n = nodes.computeIfAbsent(sig, k -> new Node());
        n.untried.remove(actionKey);
        n.tried.add(actionKey);
    }

    public void recordEdge(String fromSig, String actionKey, String toSig) {
        if (fromSig == null || actionKey == null || toSig == null) return;
        edges.computeIfAbsent(fromSig, k -> new HashMap<>()).put(actionKey, toSig);
        if (!fromSig.equals(toSig)) transitions++;
    }

    public boolean hasFrontier() {
        for (Node n : nodes.values()) if (!n.untried.isEmpty()) return true;
        return false;
    }

    public List<String> pathToNearestFrontier(String fromSig, int maxDepth) {
        if (fromSig == null || !nodes.containsKey(fromSig)) return null;
        if (!nodes.get(fromSig).untried.isEmpty()) return new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String[]> parent = new HashMap<>();
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
    public int frontierScreens() { int c = 0; for (Node n : nodes.values()) if (!n.untried.isEmpty()) c++; return c; }
    public int triedActions() { int c = 0; for (Node n : nodes.values()) c += n.tried.size(); return c; }
    public int totalActions() { int c = 0; for (Node n : nodes.values()) c += n.tried.size() + n.untried.size(); return c; }
    public int coveragePercent() { int t = totalActions(); return t == 0 ? 100 : (int) Math.round(100.0 * triedActions() / t); }
    public int transitions() { return transitions; }

    /** Screen names that were observed but never had any of their actions exercised. */
    public List<String> untestedScreenNames() {
        List<String> out = new ArrayList<>();
        for (Node n : nodes.values()) if (n.tried.isEmpty() && !n.untried.isEmpty() && n.screenName != null) out.add(n.screenName);
        return out;
    }

    public String summary() {
        return "Navigation graph: " + totalScreens() + " screen(s), " + transitions + " transition(s), actions "
                + triedActions() + "/" + totalActions() + " (" + coveragePercent() + "%), "
                + frontierScreens() + " screen(s) with un-tried actions remaining.";
    }
}
