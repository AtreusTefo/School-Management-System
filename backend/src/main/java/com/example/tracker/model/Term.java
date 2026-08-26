package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.lang.NonNull;

import java.time.LocalDate;

/**
 * A named stretch of the school year - "Term 1", 12 January to 27 March.
 *
 * WHY A TERM EXISTS AT ALL
 * ------------------------
 * A timetable is not a fact about a school, it is a fact about a school
 * DURING SOME PERIOD. The same class is taught different subjects, by
 * different teachers, in different rooms, from one term to the next. Without
 * a term to hang it on, changing next term's timetable would mean destroying
 * this term's - and with it any record of what was actually taught when.
 *
 * That is the same distinction Enrolment and Assessment already draw: an
 * enrolment records the CURRENT arrangement, a mark records SOMETHING THAT
 * HAPPENED. A timetable slot is the first kind, so it needs to say which
 * arrangement it belongs to.
 *
 * OVERLAPPING TERMS ARE REFUSED IN THE SERVICE, NOT HERE
 * "no two terms may overlap" is a genuine rule, and it is one of the few in
 * this schema the database cannot state: SQL Server has no exclusion
 * constraint, and a CHECK cannot see other rows. TermService performs the
 * check inside the same transaction as the write. This is called out rather
 * than left implicit precisely because every other rule in this system IS
 * enforced at the database, and a reader is entitled to assume that until
 * told otherwise.
 */
@Entity
@Table(
        name = "term",
        uniqueConstraints = @UniqueConstraint(name = "uq_term_name", columnNames = "name"))
@Checks({
        @Check(name = "ck_term_name_not_blank",
                constraints = "LTRIM(RTRIM(name)) <> ''"),
        @Check(name = "ck_term_dates",
                constraints = "end_date >= start_date")
})
public class Term {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 60)
    @Column(nullable = false, length = 60)
    private String name;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @NotNull
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Version
    @JsonIgnore
    private Long version;

    protected Term() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    /**
     * Rejects what the getters below promise. The parameters are deliberately
     * NOT annotated @NonNull - see SubmissionFile's constructor for the full
     * reasoning, in short: a parameter annotation is a promise from the caller
     * that is erased at runtime, and it would make these checks look like dead
     * code to the null analysis while enforcing nothing.
     */
    public Term(String name, LocalDate startDate, LocalDate endDate) {
        if (name == null) {
            throw new IllegalArgumentException("A term needs a name.");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("A term needs a start date.");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("A term needs an end date.");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "A term cannot end (" + endDate + ") before it starts (" + startDate + ").");
        }
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public String getName() {
        String value = name;
        if (value == null) {
            throw new IllegalStateException("Term " + id + " has no name.");
        }
        return value;
    }

    public void setName(String name) {
        this.name = name;
    }

    @NonNull
    public LocalDate getStartDate() {
        LocalDate value = startDate;
        if (value == null) {
            throw new IllegalStateException("Term " + id + " has no start date.");
        }
        return value;
    }

    @NonNull
    public LocalDate getEndDate() {
        LocalDate value = endDate;
        if (value == null) {
            throw new IllegalStateException("Term " + id + " has no end date.");
        }
        return value;
    }

    /**
     * Set both ends together, so a term can never pass through a state where
     * the new start is after the old end. Two separate setters would allow
     * exactly that between the two calls, and the entity would be momentarily
     * invalid in a way ck_term_dates would only notice at flush time.
     */
    public void setDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("A term needs both a start and an end date.");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "A term cannot end (" + endDate + ") before it starts (" + startDate + ").");
        }
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /** True when `date` falls inside this term, both ends included. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(getStartDate()) && !date.isAfter(getEndDate());
    }

    /**
     * True when this term shares at least one day with `other`.
     *
     * Lives on the entity rather than in the service because it is a fact
     * about two terms, not about the act of saving one - which means the
     * overlap test and the dates it tests cannot drift apart.
     */
    public boolean overlaps(Term other) {
        return other != null
                && !getStartDate().isAfter(other.getEndDate())
                && !other.getStartDate().isAfter(getEndDate());
    }

    public Long getVersion() {
        return version;
    }
}
