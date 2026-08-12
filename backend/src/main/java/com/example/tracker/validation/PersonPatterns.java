package com.example.tracker.validation;

/**
 * REGEX PATTERNS SHARED ACROSS DTOs, AND WHY THIS CLASS EXISTS AT ALL
 * ---------------------------------------------------------------------
 * Every other pattern in this codebase (AdminService.EMAIL_PATTERN,
 * SubmissionService's filename pattern) is a private constant local to the one
 * class that uses it, deliberately - a pattern used once belongs next to its
 * one use.
 *
 * A password rule is different: it has to read identically on
 * AuthController.ChangePasswordRequest (a user changing their own password),
 * AdminController.PersonRequest (an admin setting one at onboarding) and
 * AdminController.ResetPasswordRequest (an admin issuing a new one). Three
 * private copies could say three different things about what "strong enough"
 * means without anything catching the drift. The name and phone patterns are
 * shared for the same reason - PersonRequest and UpdatePersonRequest both need
 * them, and one is not a subclass of the other.
 *
 * Jakarta's {@code @Pattern(regexp = ...)} requires a compile-time constant
 * expression, so these are {@code String}s, not compiled {@code Pattern}
 * objects - a class named the same as {@code java.util.regex.Pattern} would
 * also invite exactly the wrong import at every call site.
 */
public final class PersonPatterns {

    /**
     * Unicode letters, spaces, hyphens and apostrophes - "O'Brien",
     * "Van der Merwe", "Kagiso". Deliberately NOT the ASCII-only
     * {@code [a-zA-Z]} a purely English-language form would use: this project
     * already treats names as carrying accented and non-Latin characters
     * elsewhere (see the localeCompare sort in AssignmentTableComponent on the
     * frontend), and a Motswana school's own enrolment is not going to be
     * exclusively ASCII names.
     */
    public static final String NAME = "^[\\p{L} '\\-]+$";

    /**
     * Digits only, 4 to 20 of them. Real profiles in this database carry a
     * 12-13 digit Omang number; the test suite's fixtures run as short as 6.
     * Not "exactly 9" - a length that fits neither.
     */
    public static final String NATIONAL_ID = "^[0-9]{4,20}$";

    /**
     * A Botswana mobile number in E.164 form: +267 followed by exactly eight
     * digits. Mirrors ck_person_profile_phone_format at the database - see
     * V9's own header for why the shape is enforced in both places.
     */
    public static final String PHONE = "^\\+267[0-9]{8}$";

    // ----- password strength -----------------------------------------------
    // Four separate patterns rather than one combined regex, so each missing
    // requirement produces its OWN message. A single regex that fails to match
    // can only say "invalid" - it cannot say WHICH of "needs an uppercase
    // letter", "needs a digit" or "needs a symbol" is the one still missing,
    // and a person correcting a password one attempt at a time needs to know
    // which. Stacking multiple @Pattern annotations on one field is standard
    // Jakarta Bean Validation - every constraint is evaluated and every
    // failing one contributes its own message to the response.

    public static final String PASSWORD_HAS_UPPERCASE = ".*[A-Z].*";
    public static final String PASSWORD_HAS_DIGIT = ".*[0-9].*";
    /** Anything that is not a letter or a digit - deliberately broad. */
    public static final String PASSWORD_HAS_SYMBOL = ".*[^a-zA-Z0-9].*";

    private PersonPatterns() {
        // Constants only.
    }
}
