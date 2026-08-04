package com.example.tracker.dto;

import com.example.tracker.model.Course;

/**
 * One subject taught to one class by one teacher, as the API publishes it.
 *
 * This is the shape a teacher picks from when setting work, and the shape a
 * student's timetable is made of. The teacher's username is included because
 * "who teaches me this?" is the question a student most often has about a
 * subject - and answering it needs no extra request.
 */
public record CourseView(
        Long id,
        Long subjectId,
        String subjectCode,
        String subjectName,
        Long classId,
        String className,
        String teacherUsername,
        String label) {

    public static CourseView of(Course course) {
        return new CourseView(
                course.getId(),
                course.getSubject().getId(),
                course.getSubject().getCode(),
                course.getSubject().getName(),
                course.getSchoolClass().getId(),
                course.getSchoolClass().getName(),
                course.getTeacher().getUsername(),
                course.getLabel());
    }
}
