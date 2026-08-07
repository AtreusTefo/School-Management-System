package com.example.tracker.dto;

import com.example.tracker.model.AppUser;

/**
 * What the admin panel's student list and detail view are shown.
 *
 * className is nullable rather than an empty string: a student can exist
 * with no enrolment yet (an account created but not yet placed in a class),
 * and that is a real, distinct state worth telling the admin apart from "in
 * a class named nothing".
 */
public record StudentView(Long id, String username, String className,
                          boolean mustChangePassword) {

    public static StudentView of(AppUser student, String className) {
        return new StudentView(student.getId(), student.getUsername(), className,
                student.isMustChangePassword());
    }
}
