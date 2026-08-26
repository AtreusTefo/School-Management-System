package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Check;
import org.springframework.lang.NonNull;

import java.time.DayOfWeek;

/**
 * One lesson in the timetable: this course, on this weekday, in this period,
 * during this term.
 *
 * THE TWO RULES THIS TABLE EXISTS TO ENFORCE
 * ------------------------------------------
 * A class cannot be in two lessons at once, and a teacher cannot be in two
 * places at once. Both are expressed as ordinary UNIQUE constraints, and both
 * depend on schoolClass and teacher being COLUMNS on this row rather than
 * facts reachable only by following courseId.
 *
 * That duplication is safe because it is pinned: in SQL Server each is half of
 * a COMPOSITE foreign key back to (course.id, course.class_id) and
 * (course.id, course.teacher_id), so the only value either column can legally
 * hold is the one the referenced course already has. Exactly the technique V4
 * uses to pin a role, aimed at a different fact - see V11's own header.
 *
 * WHAT H2 CANNOT REPRODUCE, STATED HONESTLY
 * JPA cannot express a composite foreign key, so the test schema gets
 * single-column references plus both UNIQUE constraints. That still refuses
 * every double-booking, which is what the tests assert. What only SQL Server
 * refuses is a row whose class_id or teacher_id disagrees with its own
 * course - the same shape of gap already documented for Enrolment, and
 * verified by hand with sqlcmd rather than assumed.
 *
 * THE CONSTRUCTOR TAKES A Course AND READS THE OTHER TWO OFF IT, rather than
 * accepting three arguments a caller could pass inconsistently. There is no
 * setter for either. Given the whole point is that they agree with the course,
 * letting anyone supply them separately would hand out the one mistake this
 * design exists to prevent.
 */
@Entity
@Table(
        name = "timetable_slot",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_timetable_slot_class",
                        columnNames = {"term_id", "day_of_week", "period_id", "class_id"}),
                @UniqueConstraint(
                        name = "uq_timetable_slot_teacher",
                        columnNames = {"term_id", "day_of_week", "period_id", "teacher_id"})
        })
@Check(name = "ck_timetable_slot_day", constraints =
        "day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')")
public class TimetableSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "term_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_timetable_slot_term"))
    private Term term;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "period_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_timetable_slot_period"))
    private TimetablePeriod period;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "course_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_timetable_slot_course"))
    private Course course;

    /**
     * EnumType.STRING, never ORDINAL - the same rule AssignmentStatus follows.
     * An ordinal stores a position, and reordering java.time.DayOfWeek's
     * constants would silently reinterpret every stored row as a different day.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 9)
    private DayOfWeek dayOfWeek;

    /** Pinned to the course's own class by a composite FK. See the class comment. */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "class_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_timetable_slot_class"))
    @JsonIgnore
    private SchoolClass schoolClass;

    /** Pinned to the course's own teacher by a composite FK. See the class comment. */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_timetable_slot_teacher"))
    @JsonIgnore
    private AppUser teacher;

    @Version
    @JsonIgnore
    private Long version;

    protected TimetableSlot() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public TimetableSlot(Term term, TimetablePeriod period, Course course, DayOfWeek dayOfWeek) {
        if (term == null) {
            throw new IllegalArgumentException("A timetable slot needs a term.");
        }
        if (period == null) {
            throw new IllegalArgumentException("A timetable slot needs a period.");
        }
        if (course == null) {
            throw new IllegalArgumentException("A timetable slot needs a course.");
        }
        if (dayOfWeek == null) {
            throw new IllegalArgumentException("A timetable slot needs a day of the week.");
        }
        this.term = term;
        this.period = period;
        this.course = course;
        this.dayOfWeek = dayOfWeek;
        // Read off the course, never passed in - see the class comment.
        this.schoolClass = course.getSchoolClass();
        this.teacher = course.getTeacher();
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public Term getTerm() {
        Term value = term;
        if (value == null) {
            throw new IllegalStateException("Timetable slot " + id + " has no term.");
        }
        return value;
    }

    @NonNull
    public TimetablePeriod getPeriod() {
        TimetablePeriod value = period;
        if (value == null) {
            throw new IllegalStateException("Timetable slot " + id + " has no period.");
        }
        return value;
    }

    @NonNull
    public Course getCourse() {
        Course value = course;
        if (value == null) {
            throw new IllegalStateException("Timetable slot " + id + " has no course.");
        }
        return value;
    }

    @NonNull
    public DayOfWeek getDayOfWeek() {
        DayOfWeek value = dayOfWeek;
        if (value == null) {
            throw new IllegalStateException("Timetable slot " + id + " has no day.");
        }
        return value;
    }

    public SchoolClass getSchoolClass() {
        return schoolClass;
    }

    public AppUser getTeacher() {
        return teacher;
    }

    public Long getVersion() {
        return version;
    }
}
