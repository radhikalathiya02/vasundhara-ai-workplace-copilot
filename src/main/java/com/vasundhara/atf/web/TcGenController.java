package com.vasundhara.atf.web;

import com.vasundhara.atf.analysis.AnalysisSession;
import com.vasundhara.atf.analysis.AnalysisSessionStore;
import com.vasundhara.atf.analysis.TestCaseMerger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additive endpoints for the New Test module's AI Test Case Generation workflow — merging the
 * Analyze-APK-generated test cases with a user-uploaded sheet into one consolidated,
 * execution-ready workbook. Purely additive: no existing API, execution flow, or database is
 * touched; execution of the merged sheet goes through the existing {@code POST /api/tc/execute}.
 */
@RestController
@RequestMapping("/api/tcgen")
public class TcGenController {

    /** In-memory merged-sheet registry, same lifecycle model as the analyze sessions themselves. */
    private final ConcurrentHashMap<String, byte[]> merged = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> mergedNames = new ConcurrentHashMap<>();

    private final AnalysisSessionStore analysisStore;

    public TcGenController(AnalysisSessionStore analysisStore) {
        this.analysisStore = analysisStore;
    }

    /**
     * Merge the AI-generated test cases of a completed Analyze-APK session with an uploaded
     * user test-case sheet. User-written cases are preserved verbatim; near-duplicate AI cases
     * are dropped. Returns counts plus the id to download/execute the consolidated sheet.
     */
    @PostMapping(value = "/merge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> merge(@RequestParam("analysisId") String analysisId,
                                     @RequestParam("sheet") MultipartFile sheet) throws Exception {
        AnalysisSession session = analysisStore.get(analysisId);
        if (session == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Analysis session not found — run Analyze APK again (sessions do not survive a server restart).");
        if (!session.isDone() || session.getSheetBytes() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Analysis is still running — wait for it to complete before merging.");
        if (sheet == null || sheet.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a test case sheet (.xlsx/.xls/.csv) to merge.");
        String name = sheet.getOriginalFilename() == null ? "sheet.xlsx" : sheet.getOriginalFilename();
        String lower = name.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls") && !lower.endsWith(".csv"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sheet must be .xlsx, .xls, or .csv.");

        List<TestCaseMerger.CaseRow> aiRows;
        List<TestCaseMerger.CaseRow> userRows;
        try {
            aiRows = TestCaseMerger.parse(session.getSheetBytes(), "generated.xlsx", false);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read the generated test cases: " + e.getMessage());
        }
        try {
            userRows = TestCaseMerger.parse(sheet.getBytes(), name, true);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read the uploaded sheet: " + e.getMessage());
        }
        if (userRows.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The uploaded sheet contains no test cases (check that it has Scenario/Steps columns).");

        TestCaseMerger.MergeResult result = TestCaseMerger.merge(aiRows, userRows);
        String id = UUID.randomUUID().toString();
        merged.put(id, TestCaseMerger.write(result.rows()));
        mergedNames.put(id, session.getApkFileName() == null ? "app" : session.getApkFileName().replaceAll("(?i)\\.apk$", ""));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("total", result.rows().size());
        out.put("userCount", result.userCount());
        out.put("aiCount", result.aiCount());
        out.put("duplicatesRemoved", result.duplicatesRemoved());
        out.put("downloadUrl", "/api/tcgen/merged/" + id + ".xlsx");
        return out;
    }

    /** Download a previously merged, consolidated test case sheet. */
    @GetMapping("/merged/{id}.xlsx")
    public ResponseEntity<byte[]> download(@PathVariable("id") String id) {
        byte[] bytes = merged.get(id);
        if (bytes == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Merged sheet not found — merge again (merged sheets do not survive a server restart).");
        String base = mergedNames.getOrDefault(id, "app");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"MergedTestCases-" + base + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
