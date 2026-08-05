package com.example.tracker;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Excel export, verified as what it actually is: bytes that Apache POI
 * itself can read back as a real, two-sheet workbook - not merely bytes with
 * the right Content-Type header. A test asserting only the header would pass
 * for a corrupt file; asserting the content parses is what proves the file
 * genuinely opens in Excel.
 *
 * WHAT THIS CLASS DOES NOT RE-TEST
 * The numbers themselves - scoring, the weighted percentage, performance
 * bands - are AssessmentIntegrityTest's job. This class exists to prove the
 * SHAPE of the export (two sheets, real headers, real numeric cells) and the
 * ONE property unique to it: that the file is bounded by the same authority
 * as the JSON endpoints, because it is built by calling them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // see AssignmentServiceTest for the reasoning
class AssessmentReportTest {

    @Autowired private MockMvc mvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String name) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(name)
                .roles(name.startsWith("teacher") ? "TEACHER" : "STUDENT");
    }

    @Test
    @DisplayName("an anonymous request for the report is refused with 401")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/assessments/report.xlsx"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the report downloads as a real spreadsheet, as an attachment, not inline")
    void downloadsAsAnAttachment() throws Exception {
        MvcResult result = mvc.perform(get("/api/assessments/report.xlsx").with(user("teacher")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).as("the response body must not be empty").isNotEmpty();

        // The real proof: POI can open it. A workbook that merely LOOKS like
        // one - wrong bytes, a truncated stream - would fail here even though
        // every header assertion above already passed.
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the workbook has exactly the two sheets, in the summary-then-detail order")
    void hasBothSheetsInOrder() throws Exception {
        byte[] bytes = downloadReportAs("teacher");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Performance Summary");
            assertThat(workbook.getSheetName(1)).isEqualTo("Marks");
        }
    }

    @Test
    @DisplayName("each sheet has a header row naming its columns")
    void sheetsHaveHeaderRows() throws Exception {
        byte[] bytes = downloadReportAs("teacher");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row performanceHeader = workbook.getSheet("Performance Summary").getRow(0);
            assertThat(cellStrings(performanceHeader))
                    .contains("Student", "Subject", "Class", "Percentage", "Level");

            Row marksHeader = workbook.getSheet("Marks").getRow(0);
            assertThat(cellStrings(marksHeader))
                    .contains("Student", "Assessment", "Score", "Out Of", "Recorded By", "Recorded At");
        }
    }

    @Test
    @DisplayName("percentage and score cells are real numbers, not text")
    void numericCellsAreActuallyNumeric() throws Exception {
        byte[] bytes = downloadReportAs("teacher");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet marks = workbook.getSheet("Marks");
            // There is seeded data (see TrackerApplication.seedMarks), so row 1
            // beyond the header is guaranteed to exist.
            Row firstMark = marks.getRow(1);
            assertThat(firstMark).as("seeded marks should produce at least one data row").isNotNull();

            // "Score" is column index 4 - see AssessmentReportService.writeMarksSheet.
            assertThat(firstMark.getCell(4).getCellType().name()).isEqualTo("NUMERIC");
            // "Percentage" is column index 6.
            assertThat(firstMark.getCell(6).getCellType().name()).isEqualTo("NUMERIC");
        }
    }

    @Test
    @DisplayName("a student's report never names another student")
    void studentReportIsScopedToThemselves() throws Exception {
        byte[] bytes = downloadReportAs("student");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Set<String> studentsNamed = new HashSet<>();

            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) {
                        continue;   // header row
                    }
                    var cell = row.getCell(0);   // "Student" is always column 0
                    if (cell != null && cell.getCellType().name().equals("STRING")) {
                        studentsNamed.add(cell.getStringCellValue());
                    }
                }
            }

            // This is the property that matters most about a SERVER-BUILT
            // export: it is bounded by AssessmentService's own scoping, the
            // same query the JSON endpoint uses - there is no separate,
            // export-only path that could leak a row the API would refuse.
            assertThat(studentsNamed)
                    .as("a student's downloaded report must contain only their own name")
                    .containsExactly("student");
        }
    }

    @Test
    @DisplayName("a teacher's report never contains a mark for a course they do not teach")
    void teacherReportIsScopedToTheirCourses() throws Exception {
        // teacher2 takes Science only. Every seeded mark (see
        // TrackerApplication.seedMarks) is Maths or History, both taught by
        // 'teacher' - so a correctly-scoped report for teacher2 has NO data
        // rows at all. That is a strong, falsifiable assertion: if scoping
        // ever regressed to "every mark in the school", this row count would
        // stop being zero and the test would fail loudly, rather than a loop
        // that happens to find nothing to complain about either way.
        byte[] bytes = downloadReportAs("teacher2");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet marks = workbook.getSheet("Marks");
            assertThat(marks.getLastRowNum())
                    .as("teacher2 teaches no course any seeded mark belongs to, "
                            + "so their Marks sheet should be header-only")
                    .isEqualTo(0);

            Sheet performance = workbook.getSheet("Performance Summary");
            assertThat(performance.getLastRowNum())
                    .as("with no marks, there is nothing to summarise either")
                    .isEqualTo(0);
        }
    }

    // ----- helpers -----------------------------------------------------------

    private byte[] downloadReportAs(String username) throws Exception {
        return mvc.perform(get("/api/assessments/report.xlsx").with(user(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private java.util.List<String> cellStrings(Row row) {
        java.util.List<String> values = new java.util.ArrayList<>();
        row.forEach(cell -> values.add(cell.getStringCellValue()));
        return values;
    }
}
