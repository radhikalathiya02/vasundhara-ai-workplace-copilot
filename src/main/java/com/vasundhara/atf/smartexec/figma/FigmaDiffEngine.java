package com.vasundhara.atf.smartexec.figma;

import com.vasundhara.atf.smartexec.SmartOcrEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares one matched (app screen, Figma screen) pair. Deliberately scoped to what's reliably
 * automatable from a screenshot + the Figma node tree alone (the "focused core slice" — see the
 * accompanying findings list for what's intentionally NOT attempted yet: font family/weight, corner
 * radius/border/button-style classification, dark/light-mode parity, true responsive-layout
 * testing, and navigation-consistency all need either a second design variant to diff against or
 * signal this pipeline doesn't have — those are reported as a single "not yet implemented" note,
 * never faked). Every check is generic — no hardcoded screen/component names, works for any file.
 */
public final class FigmaDiffEngine {
    private FigmaDiffEngine() {}

    /** One raw comparison outcome, before the caller wraps it into a {@link FigmaFinding} with
     *  ids/paths/dedupe keys (which needs I/O — writing evidence images — that doesn't belong here). */
    public record Outcome(String componentName, String expected, String actual, String difference, String severity) {}

    private static final double PIXEL_DIFF_THRESHOLD = 32;   // per-channel delta counted as "different"
    private static final double SCREEN_DIFF_WARN_PCT = 6.0;  // overall diff % → MEDIUM
    private static final double SCREEN_DIFF_HIGH_PCT = 15.0; // overall diff % → HIGH
    private static final double COLOR_DIST_THRESHOLD = 60;   // Euclidean RGB distance
    private static final double MIN_NODE_AREA_PX = 200;      // skip decorative slivers when checking "missing"

    public record ComparisonResult(List<Outcome> outcomes, byte[] diffOverlayPng, double overallDiffPct) {}

    public static ComparisonResult compare(FigmaScreen figma, byte[] figmaPng, byte[] appPng, SmartOcrEngine ocr) {
        List<Outcome> out = new ArrayList<>();
        BufferedImage appImg = decode(appPng), figmaImg = decode(figmaPng);
        if (appImg == null || figmaImg == null || figma.width() <= 0 || figma.height() <= 0) {
            return new ComparisonResult(out, null, 0);
        }
        int aw = appImg.getWidth(), ah = appImg.getHeight();
        // Scale factor from Figma frame coordinates to the app screenshot's pixel space.
        double sx = aw / figma.width(), sy = ah / figma.height();

        BufferedImage figmaScaled = scale(figmaImg, aw, ah);
        double[] overallDiff = new double[1];
        byte[] overlay = diffOverlay(appImg, figmaScaled, overallDiff);
        double diffPct = overallDiff[0];
        if (diffPct >= SCREEN_DIFF_WARN_PCT) {
            out.add(new Outcome("Overall screen", "Matches the Figma design",
                    String.format(Locale.ROOT, "%.1f%% of pixels differ from the Figma render", diffPct),
                    String.format(Locale.ROOT, "%.1f%% pixel difference between the app screen and its Figma design.", diffPct),
                    diffPct >= SCREEN_DIFF_HIGH_PCT ? "HIGH" : "MEDIUM"));
        }

        List<SmartOcrEngine.Word> ocrWords = ocr != null && ocr.isAvailable() ? ocr.recognize(appPng) : List.of();
        boolean[] wordMatched = new boolean[ocrWords.size()];

        for (com.vasundhara.atf.smartexec.figma.FigmaNode node : figma.nodes()) {
            if (node.width() <= 0 || node.height() <= 0) continue;
            int rx = (int) Math.round(node.x() * sx), ry = (int) Math.round(node.y() * sy);
            int rw = Math.max(1, (int) Math.round(node.width() * sx)), rh = Math.max(1, (int) Math.round(node.height() * sy));
            if (rx >= aw || ry >= ah || rx + rw <= 0 || ry + rh <= 0) continue; // off-frame, skip

            if (node.isText() && node.text() != null && !node.text().isBlank()) {
                String found = collectOverlappingText(ocrWords, wordMatched, rx, ry, rw, rh);
                if (found.isBlank()) {
                    if (node.width() * node.height() >= MIN_NODE_AREA_PX) {
                        out.add(new Outcome(node.name(), node.text(), "(not visible)",
                                "Expected text \"" + node.text() + "\" was not found at its expected position.", "HIGH"));
                    }
                } else if (!normalize(found).equals(normalize(node.text()))) {
                    out.add(new Outcome(node.name(), node.text(), found,
                            "Text differs from the Figma design.", "MEDIUM"));
                }
                continue;
            }

            if (node.width() * node.height() < MIN_NODE_AREA_PX) continue; // decorative slivers — too noisy to judge

            if (node.isImageLike()) {
                if (looksBlank(appImg, rx, ry, rw, rh)) {
                    out.add(new Outcome(node.name(), "Image/icon visible", "(not visible)",
                            "Expected an image/icon here — the region looks like empty background.", "HIGH"));
                }
                continue;
            }

            if (node.fillHex() != null) {
                String actualHex = averageHex(appImg, rx, ry, rw, rh);
                if (actualHex != null && colorDistance(node.fillHex(), actualHex) > COLOR_DIST_THRESHOLD) {
                    out.add(new Outcome(node.name(), node.fillHex(), actualHex,
                            "Rendered color differs from the Figma fill color.", "MEDIUM"));
                }
            }
        }

        // Unmatched OCR text = on-screen content with no corresponding Figma text node — flagged as
        // low-confidence "possible extra content" (OCR + rough region matching can't be fully certain).
        for (int i = 0; i < ocrWords.size(); i++) {
            if (wordMatched[i]) continue;
            SmartOcrEngine.Word w = ocrWords.get(i);
            out.add(new Outcome("Unmapped text", "(not present in Figma design)", w.text(),
                    "Text visible in the app has no corresponding element in the Figma design.", "LOW"));
        }

        return new ComparisonResult(out, overlay, diffPct);
    }

    private static String collectOverlappingText(List<SmartOcrEngine.Word> words, boolean[] matched, int rx, int ry, int rw, int rh) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            SmartOcrEngine.Word w = words.get(i);
            if (w.cx() >= rx && w.cx() <= rx + rw && w.cy() >= ry && w.cy() <= ry + rh) {
                matched[i] = true;
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(w.text());
            }
        }
        return sb.toString();
    }

    private static String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    private static BufferedImage decode(byte[] png) {
        if (png == null || png.length == 0) return null;
        try { return ImageIO.read(new ByteArrayInputStream(png)); } catch (Exception e) { return null; }
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Renders a red-highlight diff overlay on top of the app screenshot and returns overall diff %. */
    private static byte[] diffOverlay(BufferedImage app, BufferedImage figma, double[] diffPctOut) {
        int w = app.getWidth(), h = app.getHeight();
        BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        long different = 0, total = (long) w * h;
        int step = Math.max(1, (int) Math.sqrt(total / 250_000.0)); // sample-stride for very large screenshots
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int a = app.getRGB(x, y), f = figma.getRGB(x, y);
                double d = channelDelta(a, f);
                boolean diff = d > PIXEL_DIFF_THRESHOLD;
                if (diff) different++;
                overlay.setRGB(x, y, diff ? 0x66FF3B30 : (a & 0x00FFFFFF));
            }
        }
        diffPctOut[0] = total == 0 ? 0 : 100.0 * different / ((total + step - 1) / step);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(overlay, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) { return null; }
    }

    private static double channelDelta(int rgbA, int rgbB) {
        int ra = (rgbA >> 16) & 0xFF, ga = (rgbA >> 8) & 0xFF, ba = rgbA & 0xFF;
        int rb = (rgbB >> 16) & 0xFF, gb = (rgbB >> 8) & 0xFF, bb = rgbB & 0xFF;
        return (Math.abs(ra - rb) + Math.abs(ga - gb) + Math.abs(ba - bb)) / 3.0;
    }

    /** True when a region has almost no variance/edges — reads as untouched background, not an
     *  image/icon that Figma expects to be there. */
    private static boolean looksBlank(BufferedImage img, int rx, int ry, int rw, int rh) {
        int x0 = Math.max(0, rx), y0 = Math.max(0, ry);
        int x1 = Math.min(img.getWidth(), rx + rw), y1 = Math.min(img.getHeight(), ry + rh);
        if (x1 <= x0 || y1 <= y0) return true;
        long sum = 0, sumSq = 0, n = 0;
        int step = Math.max(1, Math.min(x1 - x0, y1 - y0) / 20);
        for (int y = y0; y < y1; y += step) for (int x = x0; x < x1; x += step) {
            int rgb = img.getRGB(x, y);
            int gray = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
            sum += gray; sumSq += (long) gray * gray; n++;
        }
        if (n == 0) return true;
        double mean = (double) sum / n;
        double variance = (double) sumSq / n - mean * mean;
        return variance < 12; // near-uniform region
    }

    private static String averageHex(BufferedImage img, int rx, int ry, int rw, int rh) {
        int x0 = Math.max(0, rx), y0 = Math.max(0, ry);
        int x1 = Math.min(img.getWidth(), rx + rw), y1 = Math.min(img.getHeight(), ry + rh);
        if (x1 <= x0 || y1 <= y0) return null;
        long r = 0, g = 0, b = 0, n = 0;
        int step = Math.max(1, Math.min(x1 - x0, y1 - y0) / 12);
        for (int y = y0; y < y1; y += step) for (int x = x0; x < x1; x += step) {
            int rgb = img.getRGB(x, y);
            r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF; n++;
        }
        if (n == 0) return null;
        return String.format("#%02x%02x%02x", (int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static double colorDistance(String hexA, String hexB) {
        int[] a = hexToRgb(hexA), b = hexToRgb(hexB);
        if (a == null || b == null) return 0;
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static int[] hexToRgb(String hex) {
        if (hex == null || !hex.matches("#?[0-9a-fA-F]{6}")) return null;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[]{Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16)};
    }
}
