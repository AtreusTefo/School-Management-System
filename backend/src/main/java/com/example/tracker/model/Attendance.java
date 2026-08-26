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
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One student, accounted for in one lesson, on one date.
 *
 * A RECORD OF SOMETHING THAT HAPPENED, not of a current arrangement - which
 * puts it on the same side of this system's oldest distinction as Assessment
 * and Submission, and the opposite side from Enrolment. It follows that
 * attendance is never tidied away as a side effect of an administrative
 * action: deleting a student is already refused while marks or submissions
 * exist, and AttendanceService adds this to that list rather than cascading.
 *
 * WHY THE DATE IS STORED AND THE WEEKDAY IS NOT
 * The slot says "Mondays, Period 3"; this says which Monday. That the date's
 * weekday matches the slot, and that it falls inside the slot's term, are both
 * checked in the service - a CHECK constraint cannot read another table, so
 * these are two of the few rules in this schema Java alone enforces, and
 * AttendanceService says so at the point it does it.
 *
 * schoolClass IS CARRIED FROM THE SLOT and pinned by a composite foreign key,
 * exactly as TimetableSlot carries it from Course. It is not a second opinion
 * about which class this is; it is the same fact, positioned so a register can
 * be read per class without a join.
 */
@Entity
@Table(
        name = "attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_attendance_slot_student_date",
                columnNames = {"timetable_slot_id", "student_id", "lesson_date"}))
@Checks({
        @Check(name = "ck_attendance_status",
                constraints = "status IN ('PRESENT','ABSENT','LATE','EXCUSED')"),
        @Check(name = "ck_attendance_student_role",
                constraints = "student_role = 'STUDENT'"),
        @Check(name = "ck_attendance_recorded_by_role",
                constraints = "recorded_by_role IN ('TEACHER','ADMIN')")
})
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "timetable_slot_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_attendance_slot"))
    private TimetableSlot slot;

    /** Pinned to the slot's own class by a composite FK. See the class comment. */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "class_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_attendance_class"))
    @JsonIgnore
    private SchoolClass schoolClass;

    @NotNull
    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "student_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_attendance_student_only"))
    private AppUser student;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "student_role", nullable = false, length = 20)
    @JsonIgnore
    private Role studentRole;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    @Size(max = 200)
    @Column(length = 200)
    private String note;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recorded_by_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_attendance_recorded_by_only"))
    private AppUser recordedBy;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "recorded_by_role", nullable = false, length = 20)
    @JsonIgnore
    private Role recordedByRole;

    @NotNull
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Version
    @JsonIgnore
    private Long version;

    protected Attendance() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Attendance(TimetableSlot slot, AppUser student, LocalDate lessonDate,
                      AttendanceStatus status, String note, AppUser recordedBy) {
        if (slot == null) {
            throw new IllegalArgumentException("An attendance mark needs a lesson.");
        }
        if (student == null) {
            throw new IllegalArgumentException("An attendance mark needs a student.");
        }
        if (lessonDate == null) {
            throw new IllegalArgumentException("An attendance mark needs a date.");
        }
        if (status == null) {
            throw new IllegalArgumentException("An attendance mark needs a status.");
        }
        if (recordedBy == null) {
            throw new IllegalArgumentException("An attendance mark needs whoever took it.");
        }
        this.slot = slot;
        this.schoolClass = slot.getSchoolClass();   // read off the slot, never passed in
        this.student = student;
        this.studentRole = student.getRole();
        this.lessonDate = lessonDate;
        this.status = status;
        this.note = (note == null || note.isBlank()) ? null : note.trim();
        this.recordedBy = recordedBy;
        this.recordedByRole = recordedBy.getRole();
        this.recordedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public TimetableSlot getSlot() {
        TimetableSlot value = slot;
        if (value == null) {
            throw new IllegalStateException("Attendance " + id + " has no lesson.");
        }
        return value;
    }

    @NonNull
    public AppUser getStudent() {
        AppUser value = student;
        if (value == null) {
            throw new IllegalStateException("Attendance " + id + " has no student.");
        }
        return value;
    }

    @NonNull
    public LocalDate getLessonDate() {
        LocalDate value = lessonDate;
        if (value == null) {
            throw new IllegalStateException("Attendance " + id + " has no date.");
        }
        return value;
    }

    @NonNull
    public AttendanceStatus getStatus() {
        AttendanceStatus value = status;
        if (value == null) {
            throw new IllegalStateException("Attendance " + id + " has no status.");
        }
        return value;
    }

    public SchoolClass getSchoolClass() {
        return schoolClass;
    }

    public Role getStudentRole() {
        return studentRole;
    }

    public String getNote() {
        return note;
    }

    public AppUser getRecordedBy() {
        return recordedBy;
    }

    public Role getRecordedByRole() {
        return recordedByRole;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Correct an existing mark.
     *
     * Re-stamps who and when, because a correction is itself something that
     * happened - the register should say who last touched it, not who first
     * took it. The audit log carries the full sequence; this carries the
     * current answer.
     */
    public void amend(AttendanceStatus status, String note, AppUser by) {
        if (status == null) {
            throw new IllegalArgumentException("An attendance mark needs a status.");
        }
        if (by == null) {
            throw new IllegalArgumentException("An attendance mark needs whoever took it.");
        }
        this.status = status;
        this.note = (note == null || note.isBlank()) ? null : note.trim();
        this.recordedBy = by;
        this.recordedByRole = by.getRole();
        this.recordedAt = Instant.now();
    }
}
