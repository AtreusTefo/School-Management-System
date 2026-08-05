package com.example.tracker.controller;

import com.example.tracker.dto.AssessmentView;
import com.example.tracker.dto.PerformanceView;
import com.example.tracker.service.AssessmentReportService;
import com.example.tracker.service.AssessmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER for marks and performance.
 *
 * HTTP only. It receives a request, hands the work to AssessmentService, and
 * returns the result as JSON. Nothing here decides who may mark what, and
 * nothing here does arithmetic - both live in the service, so they hold for any
 * caller and can be tested without a web server.
 *
 * In particular the percentages and performance levels in the response are
 * computed server-side. The client could work them out from score and maxScore,
 * and deliberately does not: two implementations of the same formula are two
 * chances to disagree, and the one on the screen would be the one nobody
 * verified.
 */
@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {

    private final AssessmentService assessments;
    private final AssessmentReportService reports;

    public AssessmentController(AssessmentService assessments, AssessmentReportService reports) {
        this.assessments = assessments;
        this.reports = reports;
    }

    /**
     * GET /api/assessments
     *
     * A student's own marks, or a teacher's mark book across the courses they
     * take. The server decides which; the client sends nothing to choose.
     */
    @GetMapping
    public List<AssessmentView> list() {
        return assessments.listAssessments();
    }

    /**
     * GET /api/assessments/summary
     *
     * Performance per student per subject: how many marks, the totals, the
     * percentage and the level. Scoped exactly as the list above is.
     */
    @GetMapping("/summary")
    public List<PerformanceView> summary() {
        return assessments.summarise();
    }

    /** GET /api/assessments/course/{courseId} - one course's mark book. Teacher only. */
    @GetMapping("/course/{courseId}")
    public List<AssessmentView> forCourse(@PathVariable Long courseId) {
        return assessments.listForCourse(courseId);
    }

    /**
     * GET /api/assessments/report.xlsx - the same marks and performance data,
     * as one downloadable spreadsheet with two sheets.
     *
     * "Performance by student" and "Mark book" are two views of the same
     * underlying marks - one summarised, one detailed - so this is one file
     * rather than two: a teacher who wants to work with the numbers in Excel
     * should not have to download and stitch together two separate exports.
     *
     * Built by AssessmentReportService calling the exact same
     * AssessmentService methods list() and summary() above call, so the
     * workbook is bounded by the same authority those JSON endpoints already
     * enforce - there is no separate export-scoped query that could disagree
     * with what the caller sees on screen.
     */
    @GetMapping("/report.xlsx")
    public ResponseEntity<Resource> report() {
        byte[] workbook = reports.buildWorkbook();

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(reports.suggestedFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(workbook.length)
                .body(new ByteArrayResource(workbook));
    }

    /** POST /api/assessments - record a mark. Teacher only. */
    @PostMapping
    public AssessmentView record(@Valid @RequestBody RecordMarkRequest request) {
        return assessments.recordMark(
                request.getCourseId(), request.getStudentUsername(), request.getName(),
                request.getScore(), request.getMaxScore(), request.getSubmissionId());
    }

    /** PUT /api/assessments/{id} - correct a mark that was entered wrong. */
    @PutMapping("/{id}")
    public AssessmentView update(@PathVariable Long id,
                                 @Valid @RequestBody UpdateMarkRequest request) {
        return assessments.updateMark(
                id, request.getName(), request.getScore(), request.getMaxScore());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        assessments.deleteMark(id);
    }

    /**
     * The shape accepted when recording a mark.
     *
     * Separate from the entity so a client can never set the id, who recorded it
     * or when - the service takes all three from the session and the clock.
     *
     * Note what the annotations do NOT check: that the score is within the
     * maximum. That is a rule about the RELATIONSHIP between two fields, which
     * bean validation on individual fields cannot express. It is enforced in the
     * service, with a message naming both numbers, and in the schema by
     * ck_assessment_score_within_max.
     */
    static class RecordMarkRequest {

        @NotNull(message = "Choose a course")
        private Long courseId;

        @NotBlank(message = "Choose a student")
        private String studentUsername;

        @NotBlank(message = "Assessment name must not be blank")
        @Size(max = 100, message = "Assessment name must be at most 100 characters")
        private String name;

        @NotNull(message = "A score is required")
        @DecimalMin(value = "0.0", message = "A score cannot be negative")
        private BigDecimal score;

        @NotNull(message = "A maximum score is required")
        @DecimalMin(value = "0.01", message = "The maximum score must be greater than zero")
        private BigDecimal maxScore;

        /** Optional: the handed-in work this mark is for. */
        private Long submissionId;

        public Long getCourseId() {
            return courseId;
        }

        public void setCourseId(Long courseId) {
            this.courseId = courseId;
        }

        public String getStudentUsername() {
            return studentUsername;
        }

        public void setStudentUsername(String studentUsername) {
            this.studentUsername = studentUsername;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getScore() {
            return score;
        }

        public void setScore(BigDecimal score) {
            this.score = score;
        }

        public BigDecimal getMaxScore() {
            return maxScore;
        }

        public void setMaxScore(BigDecimal maxScore) {
            this.maxScore = maxScore;
        }

        public Long getSubmissionId() {
            return submissionId;
        }

        public void setSubmissionId(Long submissionId) {
            this.submissionId = submissionId;
        }
    }

    /** The shape accepted when correcting one. The student and course are fixed. */
    static class UpdateMarkRequest {

        @NotBlank(message = "Assessment name must not be blank")
        @Size(max = 100, message = "Assessment name must be at most 100 characters")
        private String name;

        @NotNull(message = "A score is required")
        @DecimalMin(value = "0.0", message = "A score cannot be negative")
        private BigDecimal score;

        @NotNull(message = "A maximum score is required")
        @DecimalMin(value = "0.01", message = "The maximum score must be greater than zero")
        private BigDecimal maxScore;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getScore() {
            return score;
        }

        public void setScore(BigDecimal score) {
            this.score = score;
        }

        public BigDecimal getMaxScore() {
            return maxScore;
        }

        public void setMaxScore(BigDecimal maxScore) {
            this.maxScore = maxScore;
        }
    }
}
