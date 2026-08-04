package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One student's state for one assignment, and the PDF they uploaded for it.
 *
 * This is the half of the old Assignment that was about a PERSON rather than
 * about the WORK. Separating them is what allows one assignment to reach a whole
 * class: the teacher writes one row here for each student, and each student's
 * progress moves independently.
 *
 * A submission is created by the system, never by a student. It appears when a
 * teacher sets work for a course, for every student then enrolled in that
 * course's class. A student cannot invent one for an assignment they were not
 * set - the row simply does not exist for them, which is a stronger guarantee
 * than an authority check because there is nothing to check.
 *
 * THE TWO-FIELD CONSISTENCY RULE
 * ------------------------------
 * `status` and `submitted_at` describe the same event, so they can contradict
 * each other, so the database refuses to let them. A row claiming SUBMITTED with
 * no timestamp, or IN_PROGRESS carrying one, is not a state of the world that
 * exists - and if such a row were storable, every reader would have to decide
 * for itself which of the two fields to believe. They would not all decide the
 * same way.
 */
@Entity
@Table(
        name = "submission",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_submission_assignment_student",
                columnNames = {"assignment_id", "student_id"}))
@Checks({
        @Check(name = "ck_submission_student_role", constraints = "student_role = 'STUDENT'"),
        @Check(name = "ck_submission_status_time", constraints =
                "(status = 'SUBMITTED' AND submitted_at IS NOT NULL)"
              + " OR (status = 'IN_PROGRESS' AND submitted_at IS NULL)")
})
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "assignment_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_submission_assignment"))
    @JsonIgnore
    private Assignment assignment;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "student_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_submission_student"))
    @JsonIgnore
    private AppUser student;

    /** Role-pinned by composite foreign key. The mechanism is explained in Enrolment. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "student_role", nullable = false, length = 20)
    @JsonIgnore
    private Role studentRole;

    /**
     * Stored as text rather than as the enum's position. Positions shift the
     * moment somebody reorders or inserts a constant, which would silently
     * reinterpret every existing row; the name stays meaningful and is readable
     * straight out of the database.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentStatus status;

    /**
     * When the work was handed in, or null while it has not been.
     *
     * An Instant, not a LocalDateTime. This one IS a moment rather than a
     * calendar entry - unlike the due date, which is deliberately a LocalDate -
     * and "handed in at 23:59" needs to survive a server moving time zone
     * without becoming a different answer about whether it was late.
     */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * The uploaded PDF, or null if nothing has been uploaded yet.
     *
     * LAZY and @JsonIgnore, both load-bearing. The file row carries the document
     * bytes, and this association is walked while listing a whole class - eager
     * loading would drag every student's PDF into memory to render a table that
     * shows none of them.
     *
     * With open-in-view disabled, that means this field can only be touched
     * inside a transaction. The service reads it there and copies what the API
     * needs into a DTO; nothing outside the service layer ever sees it.
     */
    @OneToOne(mappedBy = "submission", fetch = FetchType.LAZY,
              cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private SubmissionFile file;

    @Version
    @JsonIgnore
    private Long version;

    protected Submission() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    /**
     * A new submission always starts IN_PROGRESS with no timestamp, and there is
     * no constructor that allows anything else. A caller cannot manufacture a
     * row that claims to have been handed in already.
     */
    public Submission(Assignment assignment, AppUser student) {
        this.assignment = assignment;
        this.student = student;
        this.studentRole = student == null ? null : student.getRole();
        this.status = AssignmentStatus.IN_PROGRESS;
        this.submittedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public AppUser getStudent() {
        return student;
    }

    public Role getStudentRole() {
        return studentRole;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public SubmissionFile getFile() {
        return file;
    }

    public void setFile(SubmissionFile file) {
        this.file = file;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Hand the work in.
     *
     * The status and the timestamp are set TOGETHER, in one method, because the
     * database constraint requires them to agree. Exposing two setters would
     * make it possible to write one without the other and discover the problem
     * as a constraint violation at flush time, far from the code that caused it.
     * A state change is one operation, so it gets one method.
     */
    public void markSubmitted(Instant when) {
        this.status = AssignmentStatus.SUBMITTED;
        this.submittedAt = when;
    }

    /** Reopen it. Clears the timestamp, for the same paired reason as above. */
    public void markInProgress() {
        this.status = AssignmentStatus.IN_PROGRESS;
        this.submittedAt = null;
    }

    /**
     * Late and still not handed in.
     *
     * Work that WAS handed in is never overdue, however late it arrived - a late
     * submission is still a submission, and reporting it as outstanding would be
     * wrong in the direction that matters to the student.
     */
    @Transient
    public boolean isOverdue() {
        LocalDate due = assignment == null ? null : assignment.getDueDate();
        return due != null
                && status != AssignmentStatus.SUBMITTED
                && due.isBefore(LocalDate.now());
    }
}
