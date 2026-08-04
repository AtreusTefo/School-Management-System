package com.example.tracker.dto;

import com.example.tracker.model.SchoolClass;

/**
 * A teaching group as the API publishes it.
 *
 * studentCount is passed in rather than derived from a collection on the entity.
 * SchoolClass deliberately has no {@code List<Enrolment>} field: mapping one
 * would mean any code touching a class could load its whole register, and the
 * count is the only part anybody actually wants here.
 */
public record ClassView(Long id, String name, int studentCount) {

    public static ClassView of(SchoolClass schoolClass, int studentCount) {
        return new ClassView(schoolClass.getId(), schoolClass.getName(), studentCount);
    }
}
