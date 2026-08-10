package com.example.tracker.dto;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.PersonProfile;

import java.time.LocalDate;

/**
 * What the admin panel's student list and detail view are shown.
 *
 * className is nullable rather than an empty string: a student can exist
 * with no enrolment yet (an account created but not yet placed in a class),
 * and that is a real, distinct state worth telling the admin apart from "in
 * a class named nothing".
 *
 * profile IS ALSO NULLABLE, for the same reason TeacherView's is - the
 * seeded development accounts (student, student2, student3) predate
 * PersonProfile.
 */
public record StudentView(
        Long id,
        String username,
        String externalId,
        String className,
        boolean mustChangePassword,
        String fullName,
        String nationalId,
        LocalDate dateOfBirth,
        String contactEmail,
        LocalDate dateEnrolled) {

    public static StudentView of(AppUser student, String className, PersonProfile profile) {
        return new StudentView(
                student.getId(),
                student.getUsername(),
                student.getExternalId(),
                className,
                student.isMustChangePassword(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getNationalId(),
                profile == null ? null : profile.getDateOfBirth(),
                profile == null ? null : profile.getContactEmail(),
                profile == null ? null : profile.getDateEnrolled());
    }
}
