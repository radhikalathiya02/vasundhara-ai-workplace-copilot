package com.vasundhara.atf.smartexec;

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
 * Smart Execution's own offline OCR fallback for opaque/canvas screens (Flutter, game engines) —
 * independent implementation from {@code ocr.OcrEngine}, shelling out to the {@code tesseract} CLI.
 * Fully optional: a no-op when the binary isn't installed.
 */
@Component
public class SmartOcrEngine {

    private static final Logger log = LoggerFactory.getLogger(SmartOcrEngine.class);
    private static final double MIN_CONF = 45.0;

    private volatile Boolean available;

    public record Word(String text, int x, int y, int w, int h, double conf) {
        public int cx() { return x + w / 2; }
        public int cy() { return y + h / 2; }
    }

    public boolean isAvailable() {
        Boolean a = available;
        if (a != null) return a;
        synchronized (this) {
            if (available == null) {
                boolean ok;
                try { ok = ProcessRunner.run(List.of("tesseract", "--version"), 10).exitCode() == 0; }
                catch (Exception e) { ok = false; }
                available = ok;
            }
            return available;
        }
    }

    public List<Word> recognize(byte[] png) {
        if (!isAvailable() || png == null || png.length == 0) return List.of();
        File tmp = null;
        try {
            tmp = File.createTempFile("smartexec-ocr-", ".png");
            Files.write(tmp.toPath(), png);
            CommandResult r = ProcessRunner.run(
                    List.of("tesseract", tmp.getAbsolutePath(), "stdout", "--psm", "11", "tsv"), 30);
            if (r.timedOut() || r.exitCode() != 0) return List.of();
            List<Word> out = new ArrayList<>();
            for (String line : r.stdout().split("\\R")) {
                String[] c = line.split("\t");
                if (c.length < 12 || !"5".equals(c[0])) continue;
                double conf;
                try { conf = Double.parseDouble(c[10]); } catch (Exception e) { continue; }
                if (conf < MIN_CONF) continue;
                String text = c[11].trim();
                if (text.isEmpty()) continue;
                try {
                    out.add(new Word(text, Integer.parseInt(c[6]), Integer.parseInt(c[7]),
                            Integer.parseInt(c[8]), Integer.parseInt(c[9]), conf));
                } catch (NumberFormatException ignore) {}
            }
            return out;
        } catch (Exception e) {
            log.debug("Smart OCR failed: {}", e.toString());
            return List.of();
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ignore) {} }
        }
    }
}
