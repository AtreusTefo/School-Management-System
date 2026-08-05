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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One mark: a named, scored piece of assessment for one student in one course.
 *
 * "Term 1 Test, 34 out of 50." That is the whole entity. Everything a report
 * shows on top of it - percentages, totals, averages, performance levels - is
 * derived from these rows and stored nowhere.
 *
 * WHY score AND max_score, RATHER THAN A PERCENTAGE
 * -------------------------------------------------
 * Storing "68%" throws away what actually happened. A teacher marks out of
 * whatever the task was worth, and 34/50 is a different piece of information
 * from 68/100 even though both are 68% - they carry different weight when
 * totalled with everything else. Recording the mark as it was given, and
 * computing the percentage on demand, keeps the original fact and makes the
 * derived one always correct.
 *
 * WHY BigDecimal AND NOT double
 * -----------------------------
 * Marks are exact quantities that get added up and compared. Binary floating
 * point cannot represent 0.1 exactly, so totals drift by fractions and two marks
 * that should be equal can compare unequal. It is the same requirement money
 * has, for the same reason, and the answer is the same type.
 */
@Entity
@Table(
        name = "assessment",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_assessment_student_course_name",
                columnNames = {"student_id", "course_id", "name"}))
@Checks({
        // THE RULE this entity exists to protect. "34 out of 20" is not a high
        // mark, it is a corrupt row, and every average computed from it
        // afterwards is silently wrong.
        @Check(name = "ck_assessment_score_within_max", constraints = "score <= max_score"),
        @Check(name = "ck_assessment_score_not_negative", constraints = "score >= 0"),
        @Check(name = "ck_assessment_max_positive", constraints = "max_score > 0"),
        @Check(name = "ck_assessment_name_not_blank", constraints = "LTRIM(RTRIM(name)) <> ''"),
        @Check(name = "ck_assessment_student_role", constraints = "student_role = 'STUDENT'"),
        @Check(name = "ck_assessment_recorded_by_role", constraints = "recorded_by_role = 'TEACHER'")
})
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "student_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_student"))
    @JsonIgnore
    private AppUser student;

    /** Role-pinned by composite foreign key. The mechanism is explained in Enrolment. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "student_role", nullable = false, length = 20)
    @JsonIgnore
    private Role studentRole;

    /**
     * Which subject, class and teacher this mark belongs to.
     *
     * Both scoping questions fall out of this one column: a student's report is
     * the marks for the courses attached to their class, and a teacher's mark
     * book is the marks for the courses they take.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "course_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_course"))
    @JsonIgnore
    private Course course;

    /**
     * The handed-in work this mark is for, or null.
     *
     * Null is an ordinary case rather than missing data: a test or an exam is
     * marked without anything ever being uploaded.
     *
     * WHEN IT IS NOT NULL, THE DATABASE CHECKS IT BELONGS TO THIS STUDENT.
     * The schema carries a two-column foreign key on (submission_id, student_id)
     * pointing at submission (id, student_id), so a mark naming submission 7 and
     * student 3 is only storable if submission 7 really is student 3's. Attaching
     * a mark to somebody else's work is impossible rather than merely refused by
     * a check somebody might later move or delete.
     *
     * JPA cannot express that second column, so this mapping is the plain
     * single-column half; the composite form lives in migration V6 and is
     * therefore only provable against SQL Server.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "submission_id",
            foreignKey = @ForeignKey(name = "fk_assessment_submission"))
    @JsonIgnore
    private Submission submission;

    /**
     * What was assessed - "Term 1 Test", "Practical 2".
     *
     * Free text on purpose. A school marks things this system has no name for,
     * and an enum here would need a migration every time somebody invented an
     * assessment type.
     */
    @NotBlank(message = "Assessment name must not be blank")
    @Size(max = 100, message = "Assessment name must be at most 100 characters")
    @Column(nullable = false, length = 100)
    private String name;

    @NotNull(message = "A score is required")
    @DecimalMin(value = "0.0", message = "A score cannot be negative")
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal score;

    @NotNull(message = "A maximum score is required")
    @DecimalMin(value = "0.01", message = "The maximum score must be greater than zero")
    @Column(name = "max_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxScore;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "recorded_by_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_recorded_by"))
    @JsonIgnore
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

    protected Assessment() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Assessment(AppUser student, Course course, Submission submission,
                      String name, BigDecimal score, BigDecimal maxScore,
                      AppUser recordedBy, Instant recordedAt) {
        this.student = student;
        this.studentRole = student == null ? null : student.getRole();
        this.course = course;
        this.submission = submission;
        this.name = name;
        this.score = score;
        this.maxScore = maxScore;
        this.recordedBy = recordedBy;
        this.recordedByRole = recordedBy == null ? null : recordedBy.getRole();
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public AppUser getStudent() {
        return student;
    }

    public Role getStudentRole() {
        return studentRole;
    }

    public Course getCourse() {
        return course;
    }

    public Submission getSubmission() {
        return submission;
    }

    public void setSubmission(Submission submission) {
        this.submission = submission;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getScore() {
        return score;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    /**
     * Change the mark.
     *
     * The two values are set TOGETHER, in one method, because the rule that
     * binds them - a score cannot exceed its maximum - is a rule about the PAIR.
     * Two separate setters would allow a moment where the object holds a score
     * of 40 and an old maximum of 20, and the failure would surface as a
     * constraint violation at flush time, far from the code that caused it.
     *
     * The same reasoning produced Submission.markSubmitted: a state change that
     * spans two fields gets one method.
     */
    public void reMark(BigDecimal newScore, BigDecimal newMaxScore) {
        this.score = newScore;
        this.maxScore = newMaxScore;
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
     * This mark as a percentage, to two decimal places.
     *
     * Derived, never stored. A stored percentage is a second copy of a truth
     * that already exists in score and max_score, and it stops agreeing with
     * them the first time a mark is corrected - with nothing anywhere to say so.
     *
     * HALF_UP rather than the platform default, and stated explicitly. Java's
     * default rounding for BigDecimal division is to throw rather than guess,
     * which is the right default and the wrong behaviour here: 1/3 of a mark has
     * to become a number somebody can read. HALF_UP is what a person does on
     * paper.
     */
    @Transient
    public BigDecimal getPercentage() {
        if (score == null || maxScore == null || maxScore.signum() == 0) {
            return null;
        }
        return score.multiply(BigDecimal.valueOf(100))
                    .divide(maxScore, 2, RoundingMode.HALF_UP);
    }

    /** The band this single mark falls into. See PerformanceLevel for the scale. */
    @Transient
    public PerformanceLevel getLevel() {
        return PerformanceLevel.of(getPercentage());
    }
}
