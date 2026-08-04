package com.example.tracker.dto;

import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Submission;
import com.example.tracker.model.SubmissionFile;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One student's state for one assignment - the row a student sees in their list,
 * and the row a teacher marks from.
 *
 * WHAT IS AND IS NOT HERE
 * The file's NAME, SIZE, CHECKSUM and UPLOAD TIME are published; its CONTENT is
 * not, and there is no field it could occupy. The bytes leave the server through
 * exactly one route - the download endpoint - which sets the right content type
 * and checks who is asking first. A ten-megabyte base64 blob inside a list
 * response would be both a performance problem and a disclosure one.
 *
 * The checksum is exposed deliberately. It lets a student confirm that what the
 * server stored is the file they chose, which is the difference between "I did
 * upload it" and being able to show it.
 */
public record SubmissionView(
        Long id,
        Long assignmentId,
        String assignmentTitle,
        String description,
        String subjectName,
        String className,
        String teacherUsername,
        String studentUsername,
        AssignmentStatus status,
        LocalDate dueDate,
        boolean overdue,
        Instant submittedAt,
        boolean hasFile,
        String fileName,
        Long fileSizeBytes,
        String fileSha256,
        Instant fileUploadedAt) {

    /**
     * Must be called INSIDE the transaction: reading {@code getFile()} walks a
     * lazy association, which is precisely what this record exists to do safely
     * and once.
     */
    public static SubmissionView of(Submission submission) {
        SubmissionFile file = submission.getFile();
        var assignment = submission.getAssignment();
        var course = assignment.getCourse();

        return new SubmissionView(
                submission.getId(),
                assignment.getId(),
                assignment.getTitle(),
                assignment.getDescription(),
                course.getSubject().getName(),
                course.getSchoolClass().getName(),
                course.getTeacher().getUsername(),
                submission.getStudent().getUsername(),
                submission.getStatus(),
                assignment.getDueDate(),
                submission.isOverdue(),
                submission.getSubmittedAt(),
                file != null,
                file == null ? null : file.getFilename(),
                file == null ? null : file.getSizeBytes(),
                file == null ? null : file.getSha256(),
                file == null ? null : file.getUploadedAt());
    }
}
