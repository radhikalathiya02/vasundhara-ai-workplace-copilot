package com.vasundhara.atf.analysis;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Writes a {@link TestCaseRow} list to a professionally formatted .xlsx workbook. */
public class TestCaseSheetWriter {

    private static final String[] HEADERS = {
            "Test Case ID", "Module", "Feature", "Scenario", "Steps to Execute", "Expected Result"
    };

    private static final String[] ISSUE_HEADERS = {
            "Screen", "Issue Type", "Severity", "Description", "Evidence"
    };

    public byte[] write(String appName, List<TestCaseRow> rows) throws IOException {
        return write(appName, rows, List.of());
    }

    public byte[] write(String appName, List<TestCaseRow> rows, List<IssueRow> issues) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Test Cases");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);

            CellStyle wrapStyle = wb.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            int r = 0;
            Row titleRow = sheet.createRow(r++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Test Case Sheet — " + (appName == null || appName.isBlank() ? "Application" : appName));
            titleCell.setCellStyle(titleStyle);
            Row subRow = sheet.createRow(r++);
            subRow.createCell(0).setCellValue(rows.size() + " test case(s) generated — auto-generated from APK analysis");
            r++; // blank spacer row

            Row header = sheet.createRow(r++);
            for (int c = 0; c < HEADERS.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(headerStyle);
            }
            header.setHeightInPoints(32);

            int seq = 1;
            for (TestCaseRow tc : rows) {
                Row row = sheet.createRow(r++);
                String testCaseId = tc.id != null && !tc.id.isBlank() ? tc.id : String.format("TC-%03d", seq++);
                String[] values = { testCaseId, tc.module, tc.feature, tc.scenario, tc.steps, tc.expectedResult };
                for (int c = 0; c < values.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(values[c] == null ? "" : values[c]);
                    cell.setCellStyle(wrapStyle);
                }
            }

            int[] widths = {12, 18, 20, 34, 46, 46};
            for (int c = 0; c < widths.length; c++) {
                sheet.setColumnWidth(c, widths[c] * 256);
            }
            sheet.createFreezePane(0, 4);

            if (!issues.isEmpty()) {
                writeIssuesSheet(wb, appName, issues, headerStyle, wrapStyle, titleStyle);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    /**
     * Second sheet: real defects observed live on the device during the crawl (crashes, ANRs,
     * native crashes, StrictMode violations, structural UI issues) — distinct from the "Test
     * Cases" sheet, which lists scenarios a human should go verify rather than things already
     * confirmed to be wrong.
     */
    private void writeIssuesSheet(XSSFWorkbook wb, String appName, List<IssueRow> issues,
                                   CellStyle headerStyle, CellStyle wrapStyle, CellStyle titleStyle) {
        Sheet sheet = wb.createSheet("Issues Found");

        CellStyle criticalStyle = wb.createCellStyle();
        criticalStyle.cloneStyleFrom(wrapStyle);
        criticalStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        criticalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int r = 0;
        Row titleRow = sheet.createRow(r++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Issues Found — " + (appName == null || appName.isBlank() ? "Application" : appName));
        titleCell.setCellStyle(titleStyle);
        Row subRow = sheet.createRow(r++);
        subRow.createCell(0).setCellValue(issues.size()
                + " real defect(s) observed live on the device during the crawl — not generated scenarios");
        r++; // blank spacer row

        Row header = sheet.createRow(r++);
        for (int c = 0; c < ISSUE_HEADERS.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(ISSUE_HEADERS[c]);
            cell.setCellStyle(headerStyle);
        }
        header.setHeightInPoints(32);

        for (IssueRow issue : issues) {
            Row row = sheet.createRow(r++);
            String[] values = { issue.screen, issue.type, issue.severity, issue.description, issue.evidence };
            CellStyle style = "Critical".equals(issue.severity) ? criticalStyle : wrapStyle;
            for (int c = 0; c < values.length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(values[c] == null ? "" : values[c]);
                cell.setCellStyle(style);
            }
        }

        int[] widths = {24, 20, 14, 46, 50};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }
        sheet.createFreezePane(0, 4);
    }
}
