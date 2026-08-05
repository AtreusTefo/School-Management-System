package com.example.tracker.service;

import com.example.tracker.dto.AssessmentView;
import com.example.tracker.dto.PerformanceView;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * Builds the marks-and-performance spreadsheet a teacher or student can
 * download and work with in Excel.
 *
 * WHY THIS EXISTS ON THE SERVER RATHER THAN IN THE BROWSER
 * -----------------------------------------------------------------------
 * The alternative is a JavaScript library turning already-loaded JSON into a
 * workbook client-side. That would need the SAME two calls this class makes
 * anyway - list the marks, summarise the performance - and would then be a
 * SECOND place deciding what counts as "the caller's data".
 *
 * This class calls AssessmentService.listAssessments() and .summarise()
 * directly - the exact methods AssessmentController already calls for the
 * JSON endpoints. That means the workbook cannot contain a row the caller
 * could not already see through the API: there is no separate scoping logic
 * to write, and therefore none to accidentally get wrong or let drift out of
 * agreement with the JSON response. A teacher's downloaded report is bounded
 * by the same query that bounds their screen.
 *
 * It also means the export is never limited by what happens to be loaded in
 * the browser at the time - no pagination, no "only the current page's rows",
 * no stale client-side cache. It is built fresh, from the database, on every
 * download.
 *
 * WHY A SEPARATE CLASS RATHER THAN A METHOD ON AssessmentService
 * AssessmentService owns the business rules for marks - scoring, scoping,
 * validation. Turning a list of records into a spreadsheet is a rendering
 * concern, not a rule, and mixing the two would make AssessmentService harder
 * to read for either purpose. The same separation already exists between
 * SubmissionService (decides what a download may contain) and
 * SubmissionController (turns that into an HTTP response) - this class is the
 * equivalent split for a format that needs more building than a controller
 * method should do inline.
 */
@Service
public class AssessmentReportService {

    private static final String SHEET_PERFORMANCE = "Performance Summary";
    private static final String SHEET_MARKS = "Marks";

    private final AssessmentService assessments;

    public AssessmentReportService(AssessmentService assessments) {
        this.assessments = assessments;
    }

    /**
     * The filename offered to the browser. Dated so that downloading the
     * report twice in one day produces two recognisably-named files rather
     * than a silent overwrite the browser resolves however it likes.
     */
    public String suggestedFilename() {
        return "assessment-report-" + LocalDate.now() + ".xlsx";
    }

    /**
     * Build the workbook and return it as bytes, ready to stream back as an
     * HTTP response body.
     *
     * Two sheets, not one, and in this order: PERFORMANCE first, because a
     * teacher opening the file wants the summary before the detail behind it -
     * the same reason the web page shows the summary card above the mark
     * book. Together they are the answer to "Performance by student and Mark
     * book should basically be the same table so a report can be exported":
     * one file, one download, both views of the same underlying marks.
     */
    public byte[] buildWorkbook() {
        List<PerformanceView> performance = assessments.summarise();
        List<AssessmentView> marks = assessments.listAssessments();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle percentStyle = percentStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook);

            writePerformanceSheet(workbook, performance, headerStyle, percentStyle);
            writeMarksSheet(workbook, marks, headerStyle, percentStyle, dateStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // A failure to serialise an in-memory workbook to bytes is not a
            // client mistake and not a business rule - it is the server
            // genuinely breaking, so it is allowed to surface as the 500 that
            // GlobalExceptionHandler's fallback produces for anything
            // unclassified, rather than being dressed up as a 400.
            throw new IllegalStateException("Could not build the report workbook.", e);
        }
    }

    // ----- sheets ----------------------------------------------------------

    private void writePerformanceSheet(XSSFWorkbook workbook, List<PerformanceView> rows,
                                       CellStyle headerStyle, CellStyle percentStyle) {
        Sheet sheet = workbook.createSheet(SHEET_PERFORMANCE);

        String[] headers = {
                "Student", "Subject", "Class", "Assessments",
                "Total Score", "Total Out Of", "Percentage", "Level"
        };
        writeHeaderRow(sheet, headers, headerStyle);

        int rowIndex = 1;
        for (PerformanceView p : rows) {
            Row row = sheet.createRow(rowIndex++);
            int col = 0;
            row.createCell(col++).setCellValue(p.studentUsername());
            row.createCell(col++).setCellValue(p.subjectName());
            row.createCell(col++).setCellValue(p.className());
            row.createCell(col++).setCellValue(p.assessmentCount());
            row.createCell(col++).setCellValue(numeric(p.totalScore()));
            row.createCell(col++).setCellValue(numeric(p.totalMaxScore()));

            Cell percentCell = row.createCell(col++);
            if (p.percentage() != null) {
                // Excel percentages are stored as a fraction of 1, not of 100 -
                // 86.36% is the cell value 0.8636 with a percent format applied,
                // NOT the literal number 86.36. Writing 86.36 with a percent
                // style would display as "8636.00%".
                percentCell.setCellValue(numeric(p.percentage()) / 100.0);
                percentCell.setCellStyle(percentStyle);
            }

            row.createCell(col).setCellValue(p.level() == null ? "" : p.level().name());
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    private void writeMarksSheet(XSSFWorkbook workbook, List<AssessmentView> rows,
                                 CellStyle headerStyle, CellStyle percentStyle, CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet(SHEET_MARKS);

        String[] headers = {
                "Student", "Subject", "Class", "Assessment", "Score", "Out Of",
                "Percentage", "Level", "Recorded By", "Recorded At"
        };
        writeHeaderRow(sheet, headers, headerStyle);

        int rowIndex = 1;
        for (AssessmentView a : rows) {
            Row row = sheet.createRow(rowIndex++);
            int col = 0;
            row.createCell(col++).setCellValue(a.studentUsername());
            row.createCell(col++).setCellValue(a.subjectName());
            row.createCell(col++).setCellValue(a.className());
            row.createCell(col++).setCellValue(a.name());
            row.createCell(col++).setCellValue(numeric(a.score()));
            row.createCell(col++).setCellValue(numeric(a.maxScore()));

            Cell percentCell = row.createCell(col++);
            if (a.percentage() != null) {
                percentCell.setCellValue(numeric(a.percentage()) / 100.0);
                percentCell.setCellStyle(percentStyle);
            }

            row.createCell(col++).setCellValue(a.level() == null ? "" : a.level().name());
            row.createCell(col++).setCellValue(a.recordedByUsername());

            Cell recordedAtCell = row.createCell(col);
            if (a.recordedAt() != null) {
                recordedAtCell.setCellValue(toDate(a.recordedAt()));
                recordedAtCell.setCellStyle(dateStyle);
            }
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    // ----- formatting helpers -----------------------------------------------

    private void writeHeaderRow(Sheet sheet, String[] headers, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);

        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * Real numeric cells, not text - "pushes all the data to work with" means
     * a teacher can sum, sort and filter these columns in Excel the way they
     * would any other spreadsheet, rather than reading a string that merely
     * looks like a number.
     */
    private CellStyle percentStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
        return style;
    }

    private CellStyle dateStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
        return style;
    }

    /** BigDecimal to double for display. DECIMAL(6,2) never exceeds 9999.99, well within a double's precision. */
    private double numeric(java.math.BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    /**
     * An Instant has no time zone of its own, and this application does not
     * track one for its users - every other timestamp in this system (see
     * Submission.submittedAt) is shown the same way, as the UTC instant it
     * genuinely is, rather than silently guessing a zone to convert into.
     */
    private Date toDate(Instant instant) {
        return Date.from(instant);
    }
}
