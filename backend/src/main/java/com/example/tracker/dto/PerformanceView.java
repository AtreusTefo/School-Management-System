package com.example.tracker.dto;

import com.example.tracker.model.PerformanceLevel;

import java.math.BigDecimal;

/**
 * One student's standing in one subject: how many marks, what they add up to,
 * and what that comes to as a percentage and a level.
 *
 * EVERY FIELD HERE IS DERIVED. Nothing in the database stores a total, an
 * average or a level - they are computed from the marks each time this is asked
 * for. A stored average is wrong the moment one mark is corrected, and keeping
 * it honest needs either a trigger or a scheduled job that somebody will
 * eventually forget to run.
 *
 * WHY THE PERCENTAGE IS TOTAL/TOTAL AND NOT THE MEAN OF THE PERCENTAGES
 * ---------------------------------------------------------------------
 * Those are different numbers and only one of them is right. A student scoring
 * 5/10 and 90/100 has 95 out of 110, which is 86.36%. Averaging the two
 * percentages instead gives (50 + 90) / 2 = 70%, which silently treats a
 * ten-mark quiz as equal in weight to a hundred-mark exam.
 *
 * Summing the scores and the maxima keeps each assessment's weight proportional
 * to what it was marked out of, which is what a teacher means by "their average".
 */
public record PerformanceView(
        String studentUsername,
        Long courseId,
        String subjectCode,
        String subjectName,
        String className,
        String teacherUsername,
        int assessmentCount,
        BigDecimal totalScore,
        BigDecimal totalMaxScore,
        BigDecimal percentage,
        PerformanceLevel level,
        String levelDescription) {

    public static PerformanceView of(String studentUsername, CourseView course,
                                     int count, BigDecimal totalScore,
                                     BigDecimal totalMax, BigDecimal percentage) {
        PerformanceLevel level = PerformanceLevel.of(percentage);

        return new PerformanceView(
                studentUsername,
                course.id(),
                course.subjectCode(),
                course.subjectName(),
                course.className(),
                course.teacherUsername(),
                count,
                totalScore,
                totalMax,
                percentage,
                level,
                level == null ? null : level.getDescription());
    }
}
