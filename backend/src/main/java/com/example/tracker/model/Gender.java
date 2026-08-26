package com.example.tracker.model;

/**
 * Male or female, as recorded by the Omang's gender marker.
 *
 * NOT A COLUMN, AND DELIBERATELY SO
 * ---------------------------------
 * There is no gender field on person_profile and there should not be. The
 * Omang already carries it in its fifth digit, so a column would be a second
 * copy of a fact the national identity number already states - and the two
 * would eventually disagree, the first time somebody corrected one and not the
 * other. This is the same rule the rest of the system follows for percentages,
 * subject totals and performance levels: nothing derived is stored, it is
 * computed on read, every time.
 *
 * See PersonProfile.getGender for the derivation, including why it can
 * legitimately answer "I do not know".
 */
public enum Gender {

    MALE,
    FEMALE;

    /**
     * The gender a valid Omang encodes, or null if this number cannot say.
     *
     * NULL IS A REAL ANSWER HERE, not a failure to handle one. Six profiles in
     * this database predate the 9-digit rule (see V13 - they are grandfathered,
     * not corrected, because inventing an identity number for a named person is
     * worse than holding a visibly wrong one). Their fifth digit means nothing,
     * so reporting "unknown" is the only truthful thing to return. Guessing
     * from it, or defaulting to one of the two, would manufacture a fact about
     * a person out of a data-entry error.
     */
    public static Gender fromOmang(String nationalId) {
        if (nationalId == null || nationalId.length() != 9) {
            return null;
        }
        for (int i = 0; i < 9; i++) {
            if (!Character.isDigit(nationalId.charAt(i))) {
                return null;
            }
        }
        return switch (nationalId.charAt(4)) {
            case '1' -> MALE;
            case '2' -> FEMALE;
            default -> null;
        };
    }
}
