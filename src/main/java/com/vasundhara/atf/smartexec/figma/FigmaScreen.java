package com.vasundhara.atf.smartexec.figma;

import java.util.List;

/** One top-level frame extracted from a Figma file — treated as one app "screen" (the standard
 *  one-frame-per-screen convention nearly every mobile-app Figma file follows). */
public record FigmaScreen(String nodeId, String name, double width, double height, List<FigmaNode> nodes) {}
