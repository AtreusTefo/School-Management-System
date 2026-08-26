package com.example.tracker.dto;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Gender;
import com.example.tracker.model.PersonProfile;

import java.time.LocalDate;

/**
 * What the admin panel's teacher list and detail view are shown. Deliberately
 * not the AppUser/PersonProfile entities directly - same reasoning as every
 * other view in this package - so the password hash cannot leak by accident
 * if a field is ever added to either entity later.
 *
 * profile IS NULLABLE. The seeded development accounts (teacher, teacher2)
 * predate this rule and were never given a PersonProfile row - see
 * AppUser.externalId's comment - so a teacher list has to render sensibly
 * for both a fully onboarded account and a legacy dev one.
 */
public record TeacherView(
        Long id,
        String username,
        String externalId,
        boolean mustChangePassword,
        String fullName,
        /*
         * The name in PARTS as well as joined. fullName is what a list wants;
         * an edit form needs the three fields back, and splitting a joined name
         * to recover them is guesswork - "van der Merwe" has no reliable seam.
         * Sending both costs three strings and removes the guess entirely.
         */
        String firstName,
        String middleName,
        String lastName,
        String nationalId,
        LocalDate dateOfBirth,
        String contactEmail,
        String phone,
        LocalDate dateEnrolled,
        /*
         * DERIVED FROM THE OMANG'S 5TH DIGIT, never stored - see Gender and
         * PersonProfile.getGender. Null where the number predates the 9-digit
         * rule and therefore cannot say, which is a real answer rather than a
         * gap: guessing a person's gender from a data-entry error would be
         * manufacturing a fact about them.
         */
        Gender gender) {

    public static TeacherView of(AppUser teacher, PersonProfile profile) {
        return new TeacherView(
                teacher.getId(),
                teacher.getUsername(),
                teacher.getExternalId(),
                teacher.isMustChangePassword(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getFirstName(),
                profile == null ? null : profile.getMiddleName(),
                profile == null ? null : profile.getLastName(),
                profile == null ? null : profile.getNationalId(),
                profile == null ? null : profile.getDateOfBirth(),
                profile == null ? null : profile.getContactEmail(),
                profile == null ? null : profile.getPhone(),
                profile == null ? null : profile.getDateEnrolled(),
                profile == null ? null : profile.getGender());
    }
}
