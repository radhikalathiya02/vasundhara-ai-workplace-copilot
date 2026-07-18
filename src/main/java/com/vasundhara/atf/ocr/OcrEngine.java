package com.vasundhara.atf.ocr;

import com.vasundhara.atf.util.ProcessRunner;
import com.vasundhara.atf.util.ProcessRunner.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline, API-free OCR over a device screenshot, used to crawl opaque / canvas-rendered screens
 * (Flutter, game engines, fully-custom UIs) whose controls are painted with no accessibility
 * nodes. It reads the on-screen text and its pixel bounding boxes locally so the crawler can find
 * and tap a labelled call-to-action ("Continue", "Start", "Allow", …) by coordinate — the API-free
 * counterpart to the vision navigator.
 *
 * <p>Shells out to the {@code tesseract} CLI (the same optional-external-tool pattern the framework
 * already uses for {@code adb}/Appium), rather than a JNI binding, so there is no native-linking
 * fragility and no heavy dependency. Fully optional: {@link #isAvailable()} is false when the
 * binary isn't installed, and every call then returns no words — so the crawler simply falls back
 * to its other strategies. Install once with e.g. {@code brew install tesseract} (free, offline).
 */
@Component
public class OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(OcrEngine.class);
    /** Tesseract word-level confidence (0–100) below which a word is discarded as noise. */
    private static final double MIN_CONF = 45.0;

    private volatile Boolean available;

    /** One word recognised on screen, with its pixel bounding box in the screenshot. */
    public record Word(String text, int x, int y, int w, int h, double conf) {
        public int cx() { return x + w / 2; }
        public int cy() { return y + h / 2; }
    }

    /** True when the {@code tesseract} binary is on PATH. Checked once and cached. */
    public boolean isAvailable() {
        Boolean a = available;
        if (a != null) return a;
        synchronized (this) {
            if (available == null) {
                boolean ok;
                try {
                    CommandResult r = ProcessRunner.run(List.of("tesseract", "--version"), 10);
                    ok = r.exitCode() == 0;
                } catch (Exception e) {
                    ok = false;
                }
                available = ok;
                if (ok) log.info("OCR fallback ENABLED — 'tesseract' found; opaque/canvas screens can be crawled via on-screen text.");
                else log.info("OCR fallback DISABLED — 'tesseract' not on PATH. Install it (e.g. `brew install tesseract`) "
                        + "to enable offline OCR-guided crawling of opaque/canvas apps. Falling back to heuristics.");
            }
            return available;
        }
    }

    /**
     * Recognise all words on the given screenshot. Returns an empty list if OCR is unavailable,
     * the input is empty, or tesseract fails for any reason (never throws).
     */
    public List<Word> recognize(byte[] png) {
        if (!isAvailable() || png == null || png.length == 0) return List.of();
        File tmp = null;
        try {
            tmp = File.createTempFile("atf-ocr-", ".png");
            Files.write(tmp.toPath(), png);
            // --psm 11 = "sparse text": find as much text as possible in no particular order, which
            // suits scattered UI labels far better than the default page-layout assumption. The
            // trailing 'tsv' config makes tesseract emit tab-separated rows (incl. bounding boxes)
            // to stdout ('stdout' as the output base).
            CommandResult r = ProcessRunner.run(
                    List.of("tesseract", tmp.getAbsolutePath(), "stdout", "--psm", "11", "tsv"), 30);
            if (r.timedOut() || r.exitCode() != 0) {
                log.debug("OCR: tesseract exit={} timedOut={}", r.exitCode(), r.timedOut());
                return List.of();
            }
            List<Word> out = new ArrayList<>();
            for (String line : r.stdout().split("\\R")) {
                String[] c = line.split("\t");
                if (c.length < 12 || !"5".equals(c[0])) continue; // level 5 = a single word
                double conf;
                try { conf = Double.parseDouble(c[10]); } catch (Exception e) { continue; }
                if (conf < MIN_CONF) continue;
                String text = c[11].trim();
                if (text.isEmpty()) continue;
                try {
                    out.add(new Word(text,
                            Integer.parseInt(c[6]), Integer.parseInt(c[7]),
                            Integer.parseInt(c[8]), Integer.parseInt(c[9]), conf));
                } catch (NumberFormatException ignore) { /* skip malformed row */ }
            }
            return out;
        } catch (Exception e) {
            log.debug("OCR failed: {}", e.toString());
            return List.of();
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ignore) {} }
        }
    }
}
