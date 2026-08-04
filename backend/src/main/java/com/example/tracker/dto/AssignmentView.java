package com.example.tracker.dto;

import com.example.tracker.model.Assignment;

import java.time.LocalDate;

/**
 * A piece of work as a TEACHER sees it: one row per assignment, with a count of
 * how far the class has got.
 *
 * The two counts are what a class-wide assignment needs and a single-owner one
 * never did. "17 of 30 handed in" is the question a teacher actually has, and it
 * cannot be answered from the assignment row alone - it is a fact about the
 * submissions, gathered in the service while the transaction is open.
 */
public record AssignmentView(
        Long id,
        String title,
        String description,
        LocalDate dueDate,
        boolean pastDue,
        Long courseId,
        String subjectName,
        String className,
        String teacherUsername,
        String createdByUsername,
        int studentCount,
        int submittedCount) {

    public static AssignmentView of(Assignment assignment, int studentCount, int submittedCount) {
        return new AssignmentView(
                assignment.getId(),
                assignment.getTitle(),
                assignment.getDescription(),
                assignment.getDueDate(),
                assignment.isPastDue(),
                assignment.getCourse().getId(),
                assignment.getCourse().getSubject().getName(),
                assignment.getCourse().getSchoolClass().getName(),
                assignment.getCourse().getTeacher().getUsername(),
                assignment.getCreatedBy().getUsername(),
                studentCount,
                submittedCount);
    }
}
