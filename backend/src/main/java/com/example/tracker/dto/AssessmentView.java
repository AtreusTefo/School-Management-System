package com.example.tracker.dto;

import com.example.tracker.model.Assessment;
import com.example.tracker.model.PerformanceLevel;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One mark as the API publishes it - the row a report is built from.
 *
 * The percentage and the level are computed here, on the way out, and exist
 * nowhere in the database. That is deliberate and is the reason this record has
 * more fields than the table has columns: the client should not be re-deriving
 * arithmetic that the server already knows how to do, and two implementations of
 * the same formula are two chances to disagree.
 */
public record AssessmentView(
        Long id,
        String studentUsername,
        Long courseId,
        String subjectCode,
        String subjectName,
        String className,
        String teacherUsername,
        String name,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal percentage,
        PerformanceLevel level,
        String levelDescription,
        String recordedByUsername,
        Instant recordedAt,
        Long submissionId) {

    public static AssessmentView of(Assessment assessment) {
        var course = assessment.getCourse();
        PerformanceLevel level = assessment.getLevel();

        return new AssessmentView(
                assessment.getId(),
                assessment.getStudent().getUsername(),
                course.getId(),
                course.getSubject().getCode(),
                course.getSubject().getName(),
                course.getSchoolClass().getName(),
                course.getTeacher().getUsername(),
                assessment.getName(),
                assessment.getScore(),
                assessment.getMaxScore(),
                assessment.getPercentage(),
                level,
                level == null ? null : level.getDescription(),
                assessment.getRecordedBy().getUsername(),
                assessment.getRecordedAt(),
                // Read without walking the lazy association: only the id is
                // wanted, and touching getSubmission() would fetch the whole row
                // and its file metadata to produce one number.
                assessment.getSubmission() == null ? null : assessment.getSubmission().getId());
    }
}
