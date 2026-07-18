package com.vasundhara.atf.smartexec.figma;

import com.fasterxml.jackson.databind.JsonNode;
import com.vasundhara.atf.smartexec.SmartOcrEngine;
import com.vasundhara.atf.smartexec.SmartSession;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ties {@link FigmaClient} → {@link FigmaDesignExtractor} → {@link FigmaScreenMatcher} →
 * {@link FigmaDiffEngine} together into one pass over every app screen Smart Execution discovered
 * during its normal crawl (whatever screenshots {@code SmartCrawler} captured, across whichever
 * categories ran) that a Figma screen could be matched to. Runs once per Smart Execution session,
 * after the category loop — this is a whole-app design audit, not a per-category behavior.
 */
@Component
public class FigmaComparisonRunner {

    private final FigmaClient client;

    public FigmaComparisonRunner(FigmaClient client) {
        this.client = client;
    }

    /** No-op (with an explanatory session step) unless the session has a Figma URL. */
    public void run(SmartSession session, File runDir, Map<String, String> appScreenshots, SmartOcrEngine ocr) {
        String url = session.getFigmaUrl();
        if (url == null || url.isBlank()) return;

        session.addStep("── Figma design comparison — starting");
        if (!client.isEnabled()) {
            session.setFigmaStatus("SKIPPED_NO_TOKEN");
            session.setFigmaNote("Skipped — no Figma API Token configured. Add one in Settings → Figma to enable this comparison.");
            session.addStep("Figma comparison skipped — no Figma API Token configured (Settings → Figma).");
            return;
        }

        FigmaUrlParser.Parsed parsed = FigmaUrlParser.parse(url);
        if (parsed == null) {
            fail(session, "The pasted link doesn't look like a Figma file/frame URL.");
            return;
        }

        JsonNode doc = client.fetchDocument(parsed.fileKey(), parsed.nodeId());
        if (doc == null) {
            fail(session, "Could not fetch the Figma file — check the token has access to it and the link is correct.");
            return;
        }

        List<FigmaScreen> screens;
        String docType = doc.path("type").asText("");
        if (parsed.nodeId() != null && ("FRAME".equals(docType) || "COMPONENT".equals(docType) || "COMPONENT_SET".equals(docType))) {
            FigmaScreen single = FigmaDesignExtractor.extractSingleScreen(doc);
            screens = single == null ? List.of() : List.of(single);
        } else {
            screens = FigmaDesignExtractor.extractScreens(doc);
        }
        if (screens.isEmpty()) {
            fail(session, "No frames/screens were found in the Figma file/link.");
            return;
        }
        session.addStep("Figma comparison — extracted " + screens.size() + " screen(s) from the design.");

        if (appScreenshots == null || appScreenshots.isEmpty()) {
            fail(session, "No app screens were captured during this run to compare against.");
            return;
        }
        List<FigmaScreenMatcher.Match> matches = FigmaScreenMatcher.match(new ArrayList<>(appScreenshots.keySet()), screens);
        session.addStep("Figma comparison — matched " + matches.size() + " / " + appScreenshots.size()
                + " app screen(s) to a Figma screen by name.");
        if (matches.isEmpty()) {
            session.setFigmaStatus("COMPLETED");
            session.setFigmaNote("No app screen could be confidently matched to a Figma screen by name — "
                    + "rename Figma frames to read similarly to the app's own screen titles for automatic matching.");
            return;
        }

        List<String> nodeIds = matches.stream().map(m -> m.figmaScreen().nodeId()).distinct().toList();
        Map<String, String> imageUrls = client.renderImageUrls(parsed.fileKey(), nodeIds);

        File evidenceDir = new File(runDir, "evidence");
        evidenceDir.mkdirs();
        List<FigmaFinding> findings = new ArrayList<>();
        for (FigmaScreenMatcher.Match m : matches) {
            String imgUrl = imageUrls.get(m.figmaScreen().nodeId());
            byte[] figmaPng = imgUrl == null ? null : client.download(imgUrl);
            byte[] appPng = readAppScreenshot(evidenceDir, appScreenshots.get(m.appScreenName()));
            if (figmaPng == null || appPng == null) {
                session.addStep("Figma comparison — could not render/read a screenshot for '" + m.appScreenName() + "', skipping.");
                continue;
            }
            String figmaFile = save(evidenceDir, "figma-" + slug(m.appScreenName()), figmaPng);
            FigmaDiffEngine.ComparisonResult result = FigmaDiffEngine.compare(m.figmaScreen(), figmaPng, appPng, ocr);
            String diffFile = result.diffOverlayPng() != null ? save(evidenceDir, "figma-diff-" + slug(m.appScreenName()), result.diffOverlayPng()) : null;
            for (FigmaDiffEngine.Outcome o : result.outcomes()) {
                findings.add(new FigmaFinding(UUID.randomUUID().toString(), m.appScreenName(), o.componentName(),
                        o.expected(), o.actual(), o.difference(), o.severity(),
                        appScreenshots.get(m.appScreenName()), figmaFile, diffFile,
                        System.currentTimeMillis(),
                        norm(m.appScreenName()) + "|" + norm(o.componentName()) + "|" + norm(o.difference())));
            }
        }
        session.setFigmaFindings(findings);
        session.setFigmaStatus("COMPLETED");
        session.setFigmaNote(findings.isEmpty()
                ? "No mismatches found across " + matches.size() + " matched screen(s)."
                : findings.size() + " mismatch(es) found across " + matches.size() + " matched screen(s).");
        session.addStep("Figma comparison — completed, " + findings.size() + " finding(s) across " + matches.size() + " screen(s).");
    }

    private void fail(SmartSession session, String note) {
        session.setFigmaStatus("FAILED");
        session.setFigmaNote(note);
        session.addStep("Figma comparison — " + note);
    }

    private byte[] readAppScreenshot(File evidenceDir, String filename) {
        if (filename == null) return null;
        try { return Files.readAllBytes(new File(evidenceDir, filename).toPath()); } catch (Exception e) { return null; }
    }

    private String save(File dir, String prefix, byte[] png) {
        try {
            String name = prefix + "-" + System.currentTimeMillis() + ".png";
            Files.write(new File(dir, name).toPath(), png);
            return name;
        } catch (Exception e) { return null; }
    }

    private static String slug(String s) {
        if (s == null) return "screen";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return t.isBlank() ? "screen" : (t.length() > 40 ? t.substring(0, 40) : t);
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
}
