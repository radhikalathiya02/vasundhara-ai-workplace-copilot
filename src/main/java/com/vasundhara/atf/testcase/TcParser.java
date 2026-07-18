package com.vasundhara.atf.testcase;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hpsf.SummaryInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Excel (.xlsx/.xls) and CSV test case sheets into {@link TcItem} lists.
 *
 * <p>Supports two sheet layouts:
 * <ul>
 *   <li><b>Multi-row (step-per-row)</b>: each row is one step; same TC_ID → same test case.</li>
 *   <li><b>Single-row (steps-in-cell)</b>: each row is one test case; steps column holds
 *       newline-separated numbered steps (e.g. "1. Launch app\n2. Enter email").</li>
 * </ul>
 *
 * <p>Column header matching is flexible (case-insensitive, partial):
 * TC ID, Module, Feature, Name/Title, Priority, Preconditions, Step No, Step/Steps,
 * Expected Result/Expected, Test Data/Data/Input.
 */
@Component
public class TcParser {

    private static final Logger log = LoggerFactory.getLogger(TcParser.class);
    private static final Pattern NUMBERED_STEP = Pattern.compile("^\\s*(\\d+)[.)\\s]+(.+)");
    private static final Set<String> VAGUE_EXPECTED = Set.of(
            "pass", "success", "successful", "correct", "ok", "works", "working",
            "appropriate", "proper", "relevant", "suitable", "expected", "as expected");

    // ── public API ────────────────────────────────────────────────────────────

    public List<TcItem> parse(File file) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return parseExcel(file);
        if (name.endsWith(".csv"))  return parseCsv(file);
        throw new IllegalArgumentException("Unsupported file type: " + file.getName()
                + ". Please upload .xlsx, .xls, or .csv.");
    }

    // ── Excel ─────────────────────────────────────────────────────────────────

    private List<TcItem> parseExcel(File file) throws Exception {
        try (InputStream in = Files.newInputStream(file.toPath());
             Workbook wb = file.getName().endsWith(".xlsx") ? new XSSFWorkbook(in) : new HSSFWorkbook(in)) {

            Sheet sheet = wb.getSheetAt(0);

            // Locate the header row by scanning the first few rows for the first one that maps
            // at least two known columns. Sheets exported by QA tools (including this framework's
            // own Analyze-APK sheet) often carry a title/subtitle/spacer block above the real
            // header row; sheets whose headers already sit on row 0 map immediately, so this is
            // fully backward-compatible with every sheet that parsed before.
            int headerIdx = -1;
            Map<String, Integer> cols = Map.of();
            for (int i = 0; i <= Math.min(5, sheet.getLastRowNum()); i++) {
                Row candidate = sheet.getRow(i);
                if (candidate == null || isBlankRow(candidate)) continue;
                Map<String, Integer> mapped = mapColumns(candidate);
                if (mapped.size() >= 2) { headerIdx = i; cols = mapped; break; }
            }
            if (headerIdx < 0) {
                Row header = sheet.getRow(0);
                if (header == null) throw new IllegalArgumentException("Sheet appears to be empty.");
                cols = mapColumns(header);
                headerIdx = 0;
            }

            List<Row> dataRows = new ArrayList<>();
            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r != null && !isBlankRow(r)) dataRows.add(r);
            }

            // Multi-row: explicit step-number column, OR STEP column whose cells do NOT
            // contain embedded newlines (each row is a single step).
            // Single-row: STEP column cells contain '\n' (all steps packed into one cell).
            boolean multiRow = cols.containsKey("STEP_NO")
                    || (cols.containsKey("STEP") && !stepCellsHaveNewlines(dataRows, cols));

            return multiRow ? parseMultiRow(dataRows, cols) : parseSingleRow(dataRows, cols);
        }
    }

    private Map<String, Integer> mapColumns(Row header) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Cell c : header) {
            String h = cellStr(c).toUpperCase().replaceAll("[^A-Z0-9]", "_");
            int idx = c.getColumnIndex();
            if (matches(h, "TC_ID","TEST_CASE_ID","ID","TESTCASEID","TEST_ID")) map.put("TC_ID", idx);
            else if (matches(h, "MODULE","COMPONENT","SUITE"))                   map.put("MODULE", idx);
            else if (matches(h, "FEATURE","FUNCTIONALITY","AREA"))               map.put("FEATURE", idx);
            else if (matches(h, "NAME","TITLE","TEST_CASE_NAME","TEST_NAME","DESCRIPTION","TEST_DESCRIPTION","SCENARIO")) map.put("NAME", idx);
            else if (matches(h, "PRIORITY","PRIO","SEVERITY"))                   map.put("PRIORITY", idx);
            else if (matches(h, "PRECONDITION","PRECONDITIONS","PREREQUISITE","PREREQUISITES")) map.put("PRECONDITIONS", idx);
            else if (matches(h, "STEP_NO","STEP_NUMBER","SEQ","SEQUENCE","STEP_SEQ","#")) map.put("STEP_NO", idx);
            else if (matches(h, "STEP","STEPS","STEP_DESCRIPTION","ACTION","ACTIONS","TEST_STEP","PROCEDURE")) map.put("STEP", idx);
            else if (matches(h, "EXPECTED","EXPECTED_RESULT","EXPECTED_RESULTS","EXPECTED_OUTCOME","RESULT")) map.put("EXPECTED", idx);
            else if (matches(h, "TEST_DATA","DATA","INPUT","INPUTS","VALUE","VALUES")) map.put("TEST_DATA", idx);
        }
        return map;
    }

    // Multi-row format: one step per row; TC_ID groups rows into test cases.
    private List<TcItem> parseMultiRow(List<Row> rows, Map<String, Integer> cols) {
        LinkedHashMap<String, TcItem> byId = new LinkedHashMap<>();
        int autoId = 1;

        for (Row row : rows) {
            String tcId = col(row, cols, "TC_ID");
            if (tcId.isBlank()) tcId = "TC" + String.format("%03d", autoId++);

            TcItem tc = byId.computeIfAbsent(tcId, id -> {
                TcItem t = new TcItem();
                t.setId(id);
                t.setModule(col(row, cols, "MODULE"));
                t.setFeature(col(row, cols, "FEATURE"));
                t.setName(col(row, cols, "NAME").isBlank() ? id : col(row, cols, "NAME"));
                t.setPriority(col(row, cols, "PRIORITY"));
                t.setPreconditions(col(row, cols, "PRECONDITIONS"));
                return t;
            });

            String desc  = col(row, cols, "STEP");
            String exp   = col(row, cols, "EXPECTED");
            String data  = col(row, cols, "TEST_DATA");
            if (!desc.isBlank()) addSteps(tc, desc, exp, data);
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Appends one sheet step cell to {@code tc}, splitting chained actions written in the very
     * common "action &gt; action" QA shorthand ("Select language &gt; Done", "Tap upload &gt;
     * Allow") into real sequential sub-steps. Without this, the whole chain reached the step
     * engine as ONE description, its keyword classifier matched only one verb in the blob, and
     * every action after the first silently never happened — verified live with a real sheet
     * where "Select language &gt; Done" tapped nothing and stalled the entire run.
     *
     * <p>Split only on separators with surrounding whitespace ("a &gt; b", "a -&gt; b", arrows)
     * so numeric comparisons like "&gt;30 sec" / "&lt;2 sec" inside a step survive untouched.
     * The expected result belongs to the OUTCOME of the chain, so it attaches to the last
     * sub-step; test data is carried on every sub-step (the engine only consumes it on typing).
     */
    private void addSteps(TcItem tc, String desc, String exp, String data) {
        String[] parts = desc.split("\\s+>\\s+|\\s+->\\s+|\\s*[→»]\\s*");
        List<String> subs = new ArrayList<>();
        for (String p : parts) if (!p.isBlank()) subs.add(p.trim());
        if (subs.isEmpty()) return;
        for (int i = 0; i < subs.size(); i++) {
            boolean last = i == subs.size() - 1;
            tc.getSteps().add(new TcStep(tc.getSteps().size() + 1, subs.get(i), last ? exp : "", data));
        }
    }

    private boolean stepCellsHaveNewlines(List<Row> rows, Map<String, Integer> cols) {
        Integer stepCol = cols.get("STEP");
        if (stepCol == null) return false;
        for (Row r : rows) {
            String v = cellStr(r.getCell(stepCol));
            if (v.contains("\n") || v.contains("\r")) return true;
        }
        return false;
    }

    // Single-row format: all steps in one cell, newline-separated.
    private List<TcItem> parseSingleRow(List<Row> rows, Map<String, Integer> cols) {
        List<TcItem> out = new ArrayList<>();
        int autoId = 1;

        for (Row row : rows) {
            TcItem tc = new TcItem();
            String id = col(row, cols, "TC_ID");
            tc.setId(id.isBlank() ? "TC" + String.format("%03d", autoId++) : id);
            tc.setModule(col(row, cols, "MODULE"));
            tc.setFeature(col(row, cols, "FEATURE"));
            String name = col(row, cols, "NAME");
            tc.setName(name.isBlank() ? tc.getId() : name);
            tc.setPriority(col(row, cols, "PRIORITY"));
            tc.setPreconditions(col(row, cols, "PRECONDITIONS"));

            String rawSteps    = col(row, cols, "STEP");
            String rawExpected = col(row, cols, "EXPECTED");
            String rawData     = col(row, cols, "TEST_DATA");

            List<String> stepLines = splitLines(rawSteps);
            List<String> expLines  = splitLines(rawExpected);
            List<String> dataLines = splitLines(rawData);

            for (int i = 0; i < stepLines.size(); i++) {
                String step = stripNumber(stepLines.get(i));
                String exp  = i < expLines.size()  ? stripNumber(expLines.get(i))  : "";
                String data = i < dataLines.size() ? stripNumber(dataLines.get(i)) : "";
                if (!step.isBlank()) addSteps(tc, step, exp, data);
            }
            if (!tc.getSteps().isEmpty()) out.add(tc);
        }
        return out;
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    private List<TcItem> parseCsv(File file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) rows.add(splitCsv(line));
            }
        }
        if (rows.size() < 2) throw new IllegalArgumentException("CSV file has no data rows.");

        // Wrap CSV rows into synthetic POI-like row proxies by treating String[] as columns.
        // Instead of reusing the POI path, we map manually.
        String[] headerArr = rows.get(0);
        Map<String, Integer> cols = new LinkedHashMap<>();
        for (int i = 0; i < headerArr.length; i++) {
            String h = headerArr[i].toUpperCase().replaceAll("[^A-Z0-9]", "_");
            if (matches(h, "TC_ID","TEST_CASE_ID","ID"))                          cols.put("TC_ID", i);
            else if (matches(h, "MODULE","COMPONENT"))                             cols.put("MODULE", i);
            else if (matches(h, "FEATURE","FUNCTIONALITY"))                        cols.put("FEATURE", i);
            else if (matches(h, "NAME","TITLE","TEST_CASE_NAME","DESCRIPTION","TEST_DESCRIPTION"))    cols.put("NAME", i);
            else if (matches(h, "PRIORITY","PRIO"))                                cols.put("PRIORITY", i);
            else if (matches(h, "PRECONDITION","PRECONDITIONS"))                   cols.put("PRECONDITIONS", i);
            else if (matches(h, "STEP_NO","STEP_NUMBER","SEQ"))                    cols.put("STEP_NO", i);
            else if (matches(h, "STEP","STEPS","STEP_DESCRIPTION","ACTION","ACTIONS","PROCEDURE")) cols.put("STEP", i);
            else if (matches(h, "EXPECTED","EXPECTED_RESULT","EXPECTED_RESULTS"))  cols.put("EXPECTED", i);
            else if (matches(h, "TEST_DATA","DATA","INPUT"))                       cols.put("TEST_DATA", i);
        }

        boolean multiRow = cols.containsKey("STEP_NO");
        LinkedHashMap<String, TcItem> byId = new LinkedHashMap<>();
        int autoId = 1;

        for (int rowIdx = 1; rowIdx < rows.size(); rowIdx++) {
            String[] row = rows.get(rowIdx);
            String tcId = get(row, cols, "TC_ID");
            if (tcId.isBlank()) tcId = "TC" + String.format("%03d", autoId++);
            String finalTcId = tcId;

            if (multiRow) {
                TcItem tc = byId.computeIfAbsent(finalTcId, k -> {
                    TcItem t = new TcItem();
                    t.setId(k);
                    t.setModule(get(row, cols, "MODULE"));
                    t.setFeature(get(row, cols, "FEATURE"));
                    String nm = get(row, cols, "NAME");
                    t.setName(nm.isBlank() ? k : nm);
                    t.setPriority(get(row, cols, "PRIORITY"));
                    t.setPreconditions(get(row, cols, "PRECONDITIONS"));
                    return t;
                });
                String desc = get(row, cols, "STEP");
                String exp  = get(row, cols, "EXPECTED");
                String data = get(row, cols, "TEST_DATA");
                if (!desc.isBlank()) addSteps(tc, desc, exp, data);
            } else {
                TcItem tc = new TcItem();
                tc.setId(tcId);
                tc.setModule(get(row, cols, "MODULE"));
                tc.setFeature(get(row, cols, "FEATURE"));
                String nm = get(row, cols, "NAME");
                tc.setName(nm.isBlank() ? tcId : nm);
                tc.setPriority(get(row, cols, "PRIORITY"));
                tc.setPreconditions(get(row, cols, "PRECONDITIONS"));
                List<String> stepLines = splitLines(get(row, cols, "STEP"));
                List<String> expLines  = splitLines(get(row, cols, "EXPECTED"));
                List<String> dataLines = splitLines(get(row, cols, "TEST_DATA"));
                for (int i = 0; i < stepLines.size(); i++) {
                    String s = stripNumber(stepLines.get(i));
                    String e = i < expLines.size()  ? stripNumber(expLines.get(i))  : "";
                    String d = i < dataLines.size() ? stripNumber(dataLines.get(i)) : "";
                    if (!s.isBlank()) addSteps(tc, s, e, d);
                }
                if (!tc.getSteps().isEmpty()) byId.put(tcId, tc);
            }
        }
        return new ArrayList<>(byId.values());
    }

    // ── Quality analysis ──────────────────────────────────────────────────────

    /** Detect duplicates and add quality warnings to each TcItem. */
    public void analyze(List<TcItem> items) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (TcItem tc : items) {
            String fp = tc.fingerprint();
            if (seen.containsKey(fp)) {
                tc.setDuplicate(true);
                tc.setDuplicateOf(seen.get(fp));
                tc.addWarning("Duplicate of " + seen.get(fp));
            } else {
                seen.put(fp, tc.getId());
            }

            if (tc.getSteps().isEmpty()) {
                tc.addWarning("No steps defined — test case cannot be executed.");
                continue;
            }
            TcStep lastStep = tc.getSteps().get(tc.getSteps().size() - 1);
            for (TcStep step : tc.getSteps()) {
                if (step.description().isBlank()) {
                    tc.addWarning("Step " + step.num() + " has an empty description.");
                }
                if (step.expectedResult().isBlank()) {
                    // Only the FINAL step's expected result decides the case's automated verdict;
                    // intermediate action steps (including sub-steps split from "a > b" chains,
                    // whose expected always sits on the chain's last action) legitimately have
                    // none — warning on each of those was pure noise.
                    if (step == lastStep) {
                        tc.addWarning("Step " + step.num() + " '" + truncate(step.description(), 40)
                                + "' has no expected result — automated pass/fail cannot be determined.");
                    }
                } else {
                    String expLow = step.expectedResult().toLowerCase().trim();
                    for (String v : VAGUE_EXPECTED) {
                        if (expLow.equals(v) || expLow.contains(v + " ") || expLow.endsWith(v)) {
                            tc.addWarning("Step " + step.num() + " expected result is vague: '"
                                    + step.expectedResult() + "'. Automated validation may be unreliable.");
                            break;
                        }
                    }
                }
            }
        }
    }

    // ── App hint extraction (for APK–sheet mismatch detection) ───────────────

    /**
     * Extracts candidate app-name strings from a test case sheet so the caller
     * can compare them against the APK's application label / package name.
     *
     * <p>Sources checked, in priority order:
     * <ol>
     *   <li>Original upload filename (stripped of extension and timestamp prefix)</li>
     *   <li>All Excel sheet tab names</li>
     *   <li>Title row — a leading row whose single non-empty cell looks like a heading</li>
     *   <li>Distinct values in any column whose header contains "application", "app",
     *       "product", or "project"</li>
     * </ol>
     */
    public List<String> extractAppHints(File file, String originalFilename) {
        List<String> hints = new ArrayList<>();

        // 1 — original filename without extension
        if (originalFilename != null && !originalFilename.isBlank()) {
            String fname = originalFilename
                    .replaceAll("(?i)\\.(xlsx|xls|csv)$", "")
                    .replaceAll("^tc_\\d+_", "")  // strip "tc_<timestamp>_" prefix added on save
                    .trim();
            if (!fname.isBlank()) hints.add(fname);
        }

        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            try (InputStream in = Files.newInputStream(file.toPath());
                 Workbook wb = lower.endsWith(".xlsx") ? new XSSFWorkbook(in) : new HSSFWorkbook(in)) {

                // 2a — document title from built-in workbook properties
                if (wb instanceof XSSFWorkbook xwb) {
                    String docTitle = xwb.getProperties().getCoreProperties().getTitle();
                    if (docTitle != null && !docTitle.isBlank()) hints.add(docTitle);
                } else if (wb instanceof HSSFWorkbook hwb) {
                    SummaryInformation si = hwb.getSummaryInformation();
                    if (si != null && si.getTitle() != null && !si.getTitle().isBlank())
                        hints.add(si.getTitle());
                }

                // 2b — sheet tab names
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    String tabName = wb.getSheetAt(i).getSheetName();
                    if (tabName != null && !tabName.isBlank()) hints.add(tabName);
                }

                Sheet sheet = wb.getSheetAt(0);

                // 3 — title row: row 0 is a title if it has exactly one non-empty cell
                //     and that cell is not a standard column header keyword
                Row row0 = sheet.getRow(0);
                if (row0 != null) {
                    List<String> vals = new ArrayList<>();
                    for (Cell c : row0) {
                        String v = cellStr(c);
                        if (!v.isBlank()) vals.add(v);
                    }
                    if (vals.size() == 1) {
                        String v = vals.get(0).trim();
                        boolean isHeader = v.toLowerCase().matches(
                            ".*(tc.id|test.case|step|expected|module|feature|priority).*");
                        if (!isHeader) hints.add(v);
                    }
                }

                // 4 — Application/App/Product/Project column values (first ~5 unique values)
                Row headerRow = findHeaderRowForHints(sheet);
                if (headerRow != null) {
                    Integer appCol = null;
                    for (Cell c : headerRow) {
                        String h = cellStr(c).toLowerCase().replaceAll("[^a-z]", "");
                        if (h.equals("application") || h.equals("applicationname")
                                || h.equals("appname") || h.equals("app")
                                || h.equals("product") || h.equals("productname")
                                || h.equals("project") || h.equals("projectname")) {
                            appCol = c.getColumnIndex();
                            break;
                        }
                    }
                    if (appCol != null) {
                        int hIdx = headerRow.getRowNum();
                        Set<String> seen = new LinkedHashSet<>();
                        for (int r = hIdx + 1; r <= Math.min(hIdx + 30, sheet.getLastRowNum()); r++) {
                            Row row = sheet.getRow(r);
                            if (row == null) continue;
                            String v = cellStr(row.getCell(appCol));
                            if (!v.isBlank()) seen.add(v);
                            if (seen.size() >= 5) break;
                        }
                        hints.addAll(seen);
                    }
                }

            } catch (Exception e) {
                log.debug("extractAppHints: could not read {}: {}", file.getName(), e.getMessage());
            }
        }
        // CSV: only the filename is usable (already added above)
        return hints;
    }

    private Row findHeaderRowForHints(Sheet sheet) {
        for (int r = 0; r <= Math.min(3, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                String v = cellStr(c).toLowerCase();
                if (v.contains("step") || v.contains("tc") || v.contains("test")
                        || v.contains("expected") || v.contains("id")) return row;
            }
        }
        return sheet.getRow(0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean matches(String h, String... candidates) {
        for (String c : candidates) if (h.equals(c) || h.contains(c)) return true;
        return false;
    }

    private String col(Row row, Map<String, Integer> cols, String key) {
        Integer idx = cols.get(key);
        if (idx == null) return "";
        Cell c = row.getCell(idx);
        return c == null ? "" : cellStr(c);
    }

    private String get(String[] row, Map<String, Integer> cols, String key) {
        Integer idx = cols.get(key);
        if (idx == null || idx >= row.length) return "";
        return row[idx].trim();
    }

    private String cellStr(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING  -> c.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getDateCellValue().toString()
                    : String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> {
                try { yield c.getStringCellValue().trim(); }
                catch (Exception e) {
                    try { yield String.valueOf((long) c.getNumericCellValue()); }
                    catch (Exception e2) { yield ""; }
                }
            }
            default -> "";
        };
    }

    private boolean isBlankRow(Row row) {
        for (Cell c : row) if (!cellStr(c).isBlank()) return false;
        return true;
    }

    private List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\\n|\\r\\n|\\r|;"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private String stripNumber(String s) {
        Matcher m = NUMBERED_STEP.matcher(s);
        return m.matches() ? m.group(2).trim() : s.trim();
    }

    private String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (char ch : line.toCharArray()) {
            if (ch == '"') { inQuotes = !inQuotes; }
            else if (ch == ',' && !inQuotes) { fields.add(sb.toString().trim()); sb.setLength(0); }
            else sb.append(ch);
        }
        fields.add(sb.toString().trim());
        return fields.toArray(new String[0]);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
