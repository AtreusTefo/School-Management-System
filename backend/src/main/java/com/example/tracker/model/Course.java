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
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Check;

/**
 * One subject, taught to one class, by one teacher.
 *
 * THIS TABLE IS WHERE FOUR REQUIREMENTS LIVE AT ONCE
 * --------------------------------------------------
 * They looked like four separate features and turned out to be one relationship
 * read from four directions:
 *
 *   a teacher teaches several subjects   many rows sharing a teacher
 *   a teacher teaches several classes    many rows sharing a teacher
 *   a student is taught several subjects the courses attached to their class
 *   a student has several teachers       the courses attached to their class
 *
 * Not one of them needed its own column, flag or special case. When a set of
 * requirements collapses like that, it is usually a sign the model has found the
 * right shape - and when they DON'T collapse, that is usually a sign the model
 * is fighting the domain.
 *
 * A student never points at a course directly. They are in a class, the class
 * has courses, and their teachers follow from that. Wiring students to courses
 * individually would mean re-wiring thirty rows every time a class gained a
 * subject, and the register and the timetable would drift apart the first time
 * somebody forgot.
 *
 * The teacher reference is role-pinned by the same composite foreign key
 * technique used in Enrolment, in the opposite direction - only a TEACHER can
 * teach. The full explanation of the mechanism is in that class.
 */
@Entity
@Table(
        name = "course",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_course_subject_class_teacher",
                columnNames = {"subject_id", "class_id", "teacher_id"}))
@Check(name = "ck_course_teacher_role", constraints = "teacher_role = 'TEACHER'")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "subject_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_course_subject"))
    private Subject subject;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "class_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_course_class"))
    private SchoolClass schoolClass;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "teacher_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_course_teacher"))
    private AppUser teacher;

    /** Pinned to the teacher's real role by the composite FK. See Enrolment. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "teacher_role", nullable = false, length = 20)
    @JsonIgnore
    private Role teacherRole;

    @Version
    @JsonIgnore
    private Long version;

    protected Course() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Course(Subject subject, SchoolClass schoolClass, AppUser teacher) {
        this.subject = subject;
        this.schoolClass = schoolClass;
        this.teacher = teacher;
        this.teacherRole = teacher == null ? null : teacher.getRole();
    }

    public Long getId() {
        return id;
    }

    public Subject getSubject() {
        return subject;
    }

    public SchoolClass getSchoolClass() {
        return schoolClass;
    }

    public AppUser getTeacher() {
        return teacher;
    }

    public Role getTeacherRole() {
        return teacherRole;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * How a course reads to a person: "Mathematics - Grade 10A".
     *
     * Derived, never stored. A stored label would be a second copy of the truth
     * that stops matching the moment a subject or class is renamed, and nothing
     * would ever tell you.
     */
    @Transient
    public String getLabel() {
        String subjectName = subject == null ? "?" : subject.getName();
        String className = schoolClass == null ? "?" : schoolClass.getName();
        return subjectName + " - " + className;
    }
}
