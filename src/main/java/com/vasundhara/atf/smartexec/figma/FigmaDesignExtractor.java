package com.vasundhara.atf.smartexec.figma;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a Figma document/node JSON tree (as returned by {@link FigmaClient}) and extracts one
 * {@link FigmaScreen} per top-level FRAME — the standard one-frame-per-screen convention nearly
 * every mobile-app Figma file follows. Generic: no hardcoded frame/layer names, works for any file.
 */
public final class FigmaDesignExtractor {
    private FigmaDesignExtractor() {}

    public static List<FigmaScreen> extractScreens(JsonNode root) {
        List<FigmaScreen> screens = new ArrayList<>();
        if (root == null || root.isMissingNode()) return screens;
        List<JsonNode> frames = new ArrayList<>();
        collectTopLevelFrames(root, frames);
        for (JsonNode frame : frames) {
            JsonNode bbox = frame.path("absoluteBoundingBox");
            double fx = bbox.path("x").asDouble(0), fy = bbox.path("y").asDouble(0);
            double fw = bbox.path("width").asDouble(0), fh = bbox.path("height").asDouble(0);
            List<FigmaNode> nodes = new ArrayList<>();
            for (JsonNode child : frame.path("children")) flatten(child, fx, fy, nodes);
            screens.add(new FigmaScreen(frame.path("id").asText(""), displayName(frame), fw, fh, nodes));
        }
        return screens;
    }

    /** A single node was requested directly (a frame/screen link) — treat it as one screen even
     *  though it wasn't reached via the usual document→canvas→frame walk. */
    public static FigmaScreen extractSingleScreen(JsonNode frame) {
        if (frame == null || frame.isMissingNode()) return null;
        JsonNode bbox = frame.path("absoluteBoundingBox");
        double fx = bbox.path("x").asDouble(0), fy = bbox.path("y").asDouble(0);
        double fw = bbox.path("width").asDouble(0), fh = bbox.path("height").asDouble(0);
        List<FigmaNode> nodes = new ArrayList<>();
        for (JsonNode child : frame.path("children")) flatten(child, fx, fy, nodes);
        return new FigmaScreen(frame.path("id").asText(""), displayName(frame), fw, fh, nodes);
    }

    private static void collectTopLevelFrames(JsonNode node, List<JsonNode> out) {
        String type = node.path("type").asText("");
        if ("FRAME".equals(type) || "COMPONENT".equals(type) || "COMPONENT_SET".equals(type)) {
            out.add(node);
            return; // don't descend into nested frames as separate "screens"
        }
        for (JsonNode child : node.path("children")) collectTopLevelFrames(child, out);
    }

    private static void flatten(JsonNode node, double originX, double originY, List<FigmaNode> out) {
        if (!node.path("visible").asBoolean(true)) return;
        String type = node.path("type").asText("");
        JsonNode bbox = node.path("absoluteBoundingBox");
        if (!bbox.isMissingNode()) {
            double x = bbox.path("x").asDouble(0) - originX, y = bbox.path("y").asDouble(0) - originY;
            double w = bbox.path("width").asDouble(0), h = bbox.path("height").asDouble(0);
            String text = "TEXT".equals(type) ? node.path("characters").asText("") : null;
            String fillHex = dominantFillHex(node.path("fills"));
            double corner = node.has("cornerRadius") ? node.path("cornerRadius").asDouble(0) : 0;
            out.add(new FigmaNode(node.path("id").asText(""), displayName(node), type, text, fillHex, corner, x, y, w, h));
        }
        for (JsonNode child : node.path("children")) flatten(child, originX, originY, out);
    }

    private static String displayName(JsonNode node) {
        String n = node.path("name").asText("");
        return n.isBlank() ? node.path("type").asText("Node") : n;
    }

    /** First visible SOLID fill as "#rrggbb", or null (gradient/image/no fill — not a flat color). */
    private static String dominantFillHex(JsonNode fills) {
        if (!fills.isArray()) return null;
        for (JsonNode f : fills) {
            if (!f.path("visible").asBoolean(true)) continue;
            if (!"SOLID".equals(f.path("type").asText(""))) continue;
            JsonNode c = f.path("color");
            int r = (int) Math.round(c.path("r").asDouble(0) * 255);
            int g = (int) Math.round(c.path("g").asDouble(0) * 255);
            int b = (int) Math.round(c.path("b").asDouble(0) * 255);
            return String.format("#%02x%02x%02x", r, g, b);
        }
        return null;
    }
}
