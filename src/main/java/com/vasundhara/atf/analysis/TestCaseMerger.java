package com.vasundhara.atf.analysis;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merges AI-generated test cases (the Analyze-APK sheet) with a user-uploaded test case sheet
 * into one consolidated, execution-ready workbook:
 *
 * <ul>
 *   <li><b>User rows are preserved verbatim</b> — their wording, order, and IDs are never
 *       rewritten; when a user case and an AI case describe the same scenario, the user's wins.</li>
 *   <li><b>Duplicates are removed intelligently</b> — two cases are considered the same when the
 *       normalized text of their scenario + steps is near-identical (exact normalized match, or
 *       token-set Jaccard similarity ≥ {@link #DUP_SIMILARITY}). Pure text heuristics — works for
 *       any APK, no app-specific logic.</li>
 *   <li><b>Output round-trips into the executor</b> — headers on row 0 using column names the
 *       Test Case Execution parser maps directly, so the merged sheet can be fed straight to
 *       {@code POST /api/tc/execute}.</li>
 * </ul>
 */
public final class TestCaseMerger {

    private TestCaseMerger() {}

    /** Jaccard token-set similarity at/above which two cases count as duplicates. */
    private static final double DUP_SIMILARITY = 0.85;

    /** One parsed test-case row, origin-tagged. */
    public record CaseRow(String id, String module, String feature, String scenario,
                          String steps, String expected, boolean fromUser) {}

    /** Merge outcome: consolidated rows plus bookkeeping counts for the UI. */
    public record MergeResult(List<CaseRow> rows, int userCount, int aiCount, int duplicatesRemoved) {}

    // ── parsing ────────────────────────────────────────────────────────────────

    /**
     * Parses a test-case workbook (.xlsx/.xls bytes) or CSV bytes into rows. Scans the first few
     * rows for the header (tolerates the title/subtitle block the Analyze-APK sheet carries) and
     * maps columns by generous, case-insensitive synonyms.
     */
    public static List<CaseRow> parse(byte[] bytes, String fileName, boolean fromUser) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return parseCsv(bytes, fromUser);
        try (Workbook wb = lower.endsWith(".xls") && !lower.endsWith(".xlsx")
                ? new HSSFWorkbook(new ByteArrayInputStream(bytes))
                : new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            int headerIdx = -1;
            Map<String, Integer> cols = Map.of();
            for (int i = 0; i <= Math.min(6, sheet.getLastRowNum()); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String, Integer> mapped = mapColumns(r);
                if (mapped.size() >= 2) { headerIdx = i; cols = mapped; break; }
            }
            if (headerIdx < 0) throw new IOException("No recognizable header row found in "
                    + (fileName == null ? "sheet" : fileName)
                    + " — expected columns like Test Case ID / Module / Scenario / Steps / Expected Result.");
            List<CaseRow> out = new ArrayList<>();
            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                String scenario = cell(r, cols.get("SCENARIO"));
                String steps    = cell(r, cols.get("STEPS"));
                String expected = cell(r, cols.get("EXPECTED"));
                if (scenario.isBlank() && steps.isBlank()) continue;   // blank/dross row
                out.add(new CaseRow(cell(r, cols.get("ID")), cell(r, cols.get("MODULE")),
                        cell(r, cols.get("FEATURE")), scenario, steps, expected, fromUser));
            }
            return out;
        }
    }

    private static List<CaseRow> parseCsv(byte[] bytes, boolean fromUser) throws IOException {
        List<CaseRow> out = new ArrayList<>();
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
        Map<String, Integer> cols = null;
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] fields = splitCsvLine(line);
            if (cols == null) {
                Map<String, Integer> mapped = mapHeaders(fields);
                if (mapped.size() >= 2) cols = mapped;
                continue;   // keep scanning until a header-looking line appears
            }
            String scenario = at(fields, cols.get("SCENARIO"));
            String steps    = at(fields, cols.get("STEPS"));
            if (scenario.isBlank() && steps.isBlank()) continue;
            out.add(new CaseRow(at(fields, cols.get("ID")), at(fields, cols.get("MODULE")),
                    at(fields, cols.get("FEATURE")), scenario, steps,
                    at(fields, cols.get("EXPECTED")), fromUser));
        }
        if (cols == null) throw new IOException("No recognizable header row found in the CSV.");
        return out;
    }

    private static Map<String, Integer> mapColumns(Row header) {
        List<String> hs = new ArrayList<>();
        int last = header.getLastCellNum();
        for (int i = 0; i < Math.max(0, last); i++) hs.add(cellStr(header.getCell(i)));
        return mapHeaders(hs.toArray(new String[0]));
    }

    private static Map<String, Integer> mapHeaders(String[] headers) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i] == null ? "" : headers[i].toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            if (h.isBlank()) continue;
            if (has(h, "TEST_CASE_ID", "TC_ID", "TESTCASEID", "TEST_ID") || h.equals("ID")) put(map, "ID", i);
            else if (has(h, "MODULE", "COMPONENT", "SUITE"))                         put(map, "MODULE", i);
            else if (has(h, "FEATURE", "FUNCTIONALITY", "AREA"))                     put(map, "FEATURE", i);
            else if (has(h, "SCENARIO", "TEST_CASE_NAME", "TEST_NAME", "TITLE", "NAME", "DESCRIPTION")) put(map, "SCENARIO", i);
            else if (has(h, "STEP", "ACTION", "PROCEDURE"))                          put(map, "STEPS", i);
            else if (has(h, "EXPECTED", "RESULT"))                                   put(map, "EXPECTED", i);
        }
        return map;
    }

    /** First mapping wins — a second "…name…"-like column must not steal an already-mapped slot. */
    private static void put(Map<String, Integer> map, String key, int idx) { map.putIfAbsent(key, idx); }

    private static boolean has(String h, String... syns) {
        for (String s : syns) if (h.equals(s) || h.contains(s)) return true;
        return false;
    }

    // ── merging ────────────────────────────────────────────────────────────────

    /**
     * Merges user rows (preserved verbatim, first) with AI rows (appended when no user row —
     * or earlier AI row — already covers the same scenario). IDs: user IDs kept as-is; blank or
     * colliding IDs get the next free TC-### so every row in the consolidated sheet is unique.
     */
    public static MergeResult merge(List<CaseRow> aiRows, List<CaseRow> userRows) {
        List<CaseRow> out = new ArrayList<>(userRows);
        List<Set<String>> seenTokens = new ArrayList<>();
        List<String> seenNorms = new ArrayList<>();
        for (CaseRow u : userRows) { seenTokens.add(tokens(u)); seenNorms.add(norm(u)); }

        int duplicates = 0;
        for (CaseRow ai : aiRows) {
            Set<String> t = tokens(ai);
            String n = norm(ai);
            boolean dup = false;
            for (int i = 0; i < seenTokens.size(); i++) {
                if (n.equals(seenNorms.get(i)) || jaccard(t, seenTokens.get(i)) >= DUP_SIMILARITY) { dup = true; break; }
            }
            if (dup) { duplicates++; continue; }
            out.add(ai);
            seenTokens.add(t);
            seenNorms.add(n);
        }

        // Unique IDs: keep existing non-blank ones on first use; assign TC-### to the rest.
        Set<String> usedIds = new LinkedHashSet<>();
        int seq = 1;
        List<CaseRow> withIds = new ArrayList<>(out.size());
        for (CaseRow r : out) {
            String id = r.id() == null ? "" : r.id().trim();
            if (id.isBlank() || !usedIds.add(id)) {
                String candidate;
                do { candidate = String.format("TC-%03d", seq++); } while (!usedIds.add(candidate));
                id = candidate;
            }
            withIds.add(new CaseRow(id, r.module(), r.feature(), r.scenario(), r.steps(), r.expected(), r.fromUser()));
        }
        return new MergeResult(withIds, userRows.size(), aiRows.size() - duplicates, duplicates);
    }

    private static String norm(CaseRow r) {
        return (r.scenario() + " " + r.steps()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Set<String> tokens(CaseRow r) {
        Set<String> t = new LinkedHashSet<>(Arrays.asList(norm(r).split(" ")));
        t.remove("");
        return t;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    // ── writing ────────────────────────────────────────────────────────────────

    /**
     * Writes the consolidated sheet with the header on ROW 0 (execution-ready — the executor's
     * parser maps these names directly) and a Source column so QA can tell user cases from
     * AI-generated ones at a glance.
     */
    public static byte[] write(List<CaseRow> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Test Cases");
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);

            String[] headers = {"Test Case ID", "Module", "Feature", "Scenario", "Steps to Execute", "Expected Result", "Source"};
            Row h = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(headerStyle);
            }
            int r = 1;
            for (CaseRow row : rows) {
                Row x = sheet.createRow(r++);
                String[] vals = {row.id(), row.module(), row.feature(), row.scenario(),
                        row.steps(), row.expected(), row.fromUser() ? "User" : "AI"};
                for (int c = 0; c < vals.length; c++) {
                    Cell cell = x.createCell(c);
                    cell.setCellValue(vals[c] == null ? "" : vals[c]);
                    cell.setCellStyle(wrap);
                }
            }
            int[] widths = {12, 18, 20, 34, 46, 46, 8};
            for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
            sheet.createFreezePane(0, 1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private static String cell(Row r, Integer idx) {
        if (idx == null) return "";
        return cellStr(r.getCell(idx));
    }

    private static String cellStr(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING  -> c.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> {
                try { yield c.getStringCellValue().trim(); } catch (Exception e) { yield ""; }
            }
            default -> "";
        };
    }

    private static String at(String[] arr, Integer idx) {
        return idx == null || idx >= arr.length ? "" : arr[idx].trim();
    }

    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean q = false;
        StringBuilder sb = new StringBuilder();
        for (char ch : line.toCharArray()) {
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) { fields.add(sb.toString()); sb.setLength(0); }
            else sb.append(ch);
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
