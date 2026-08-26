package com.example.tracker.dto;

import com.example.tracker.model.Term;

import java.time.LocalDate;

/**
 * A term as the API publishes it. See SubjectView for why every endpoint
 * returns a record rather than the entity itself.
 *
 * `current` is COMPUTED HERE, not stored. A boolean column saying "this is the
 * current term" would be wrong from the morning the term ended until somebody
 * remembered to clear it, and two rows could claim it at once. Derived from the
 * dates on every read, it cannot disagree with them - the same rule this system
 * already applies to percentages and performance levels.
 */
public record TermView(Long id, String name, LocalDate startDate, LocalDate endDate,
                       boolean current) {

    public static TermView of(Term term) {
        return new TermView(
                term.getId(),
                term.getName(),
                term.getStartDate(),
                term.getEndDate(),
                term.covers(LocalDate.now()));
    }
}
