package com.vasundhara.atf.smartexec.figma;

/**
 * One visible node inside a Figma screen (frame), distilled to what the comparison engine needs.
 * Coordinates are relative to the containing screen's frame (Figma's {@code absoluteBoundingBox}
 * minus the frame's own origin), so they're directly comparable to a device screenshot region once
 * scaled to the screenshot's resolution.
 */
public record FigmaNode(
        String id,
        String name,
        String type,       // Figma node type: TEXT, RECTANGLE, FRAME, GROUP, VECTOR, INSTANCE, IMAGE, ...
        String text,        // TEXT node's characters, else null
        String fillHex,     // dominant solid fill as "#rrggbb", else null (images/gradients/no fill)
        double cornerRadius, // px, 0 if not applicable/rectangular
        double x, double y, double width, double height) {

    public boolean isText() { return "TEXT".equals(type); }
    public boolean isImageLike() { return "IMAGE".equals(type) || "VECTOR".equals(type) || "INSTANCE".equals(type) || "ELLIPSE".equals(type); }
    public double cx() { return x + width / 2; }
    public double cy() { return y + height / 2; }
}
