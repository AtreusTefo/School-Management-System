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
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;

import java.time.LocalDate;

/**
 * A piece of work a teacher has set.
 *
 * WHAT THIS CLASS USED TO BE, AND WHY IT CHANGED
 * ----------------------------------------------
 * It used to mean two things at once: the work itself, and one student's
 * progress on it. It carried both a `status` and an `owner`, which forced every
 * assignment to belong to exactly one person.
 *
 * That collapse is what made "set this for the whole class" impossible to
 * express. Thirty students meant thirty assignment rows with the same title, and
 * correcting a typo meant correcting it thirty times - with nothing in the
 * schema saying they were the same piece of work, so nothing could keep them
 * consistent.
 *
 * The two ideas are now two tables:
 *
 *   Assignment  what was set. One row, however many students receive it.
 *   Submission  one student's state for it, and their uploaded PDF.
 *
 * The signal that this was the right split: `status` never made sense here.
 * "Is this assignment submitted?" has no single answer for a class of thirty,
 * and the old model was quietly answering it for a class of one.
 */
@Entity
@Check(name = "ck_assignment_title_not_blank", constraints = "LTRIM(RTRIM(title)) <> ''")
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Title must not be blank")
    @Size(max = 200, message = "Title must be at most 200 characters")
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * Instructions for the work. Optional, because a title-only assignment is a
     * legitimate thing to set - and a mandatory description would produce a
     * column full of full stops, which is worse than an honest null.
     */
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    @Column(length = 2000)
    private String description;

    /**
     * Which subject, class and teacher this belongs to.
     *
     * The single most important field here: it is what turns an assignment from
     * a free-floating row into work set for a specific group studying a specific
     * subject. The class the work reaches follows from the course, so a change
     * of register is picked up automatically rather than needing every
     * assignment to be re-issued.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "course_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_assignment_course"))
    private Course course;

    /**
     * The teacher who actually set this.
     *
     * Not the same question as course.teacher, which is why it is stored
     * separately rather than derived. A course can be co-taught; "who teaches
     * Grade 10A maths" may have two answers, while "who set this particular
     * assignment" has exactly one and should stay answerable after the timetable
     * changes.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "created_by_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_assignment_created_by"))
    @JsonIgnore
    private AppUser createdBy;

    /** Role-pinned by composite foreign key, exactly as in Course. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "created_by_role", nullable = false, length = 20)
    @JsonIgnore
    private Role createdByRole;

    /**
     * A due date, or null when there is no deadline.
     *
     * LocalDate rather than a timestamp: "due on the 5th" is a calendar fact,
     * not an instant, and storing it as an instant drags time zones into a
     * question that has none.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Version
    @JsonIgnore
    private Long version;

    protected Assignment() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Assignment(String title, String description, Course course,
                      AppUser createdBy, LocalDate dueDate) {
        this.title = title;
        this.description = description;
        this.course = course;
        this.createdBy = createdBy;
        this.createdByRole = createdBy == null ? null : createdBy.getRole();
        this.dueDate = dueDate;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Course getCourse() {
        return course;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Role getCreatedByRole() {
        return createdByRole;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Whether the deadline has passed.
     *
     * Note what this deliberately does NOT say: whether anything is late. That
     * depends on each student's submission and is answered on Submission, where
     * the information actually is. An assignment being past due is a fact about
     * the calendar; being overdue is a fact about a person.
     *
     * Derived rather than stored, for the same reason as always: a stored flag
     * is wrong the moment midnight passes and would need a scheduled job to stay
     * honest. Computed on read, it is correct every time it is asked.
     */
    @Transient
    public boolean isPastDue() {
        return dueDate != null && dueDate.isBefore(LocalDate.now());
    }
}
