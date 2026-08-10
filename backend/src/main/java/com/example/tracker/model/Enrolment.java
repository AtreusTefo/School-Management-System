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

/**
 * One student's membership of one class.
 *
 * A join table with an identity of its own, rather than a bare @ManyToMany. That
 * is deliberate: @ManyToMany would hide the join table from the model, and this
 * one carries a constraint the application depends on. A relationship you cannot
 * see is a relationship you cannot constrain.
 *
 * HOW "ONLY A STUDENT CAN BE ENROLLED" IS ENFORCED BY THE DATABASE
 * ---------------------------------------------------------------
 * The obvious approach is a check in Java - reject the row if the user's role is
 * not STUDENT. That is policy, and it holds only while every writer goes through
 * this code. The schema does better, using a standard relational technique:
 *
 *   1. app_user carries UNIQUE (id, role)          - see migration V4 step 1
 *   2. this table stores student_id AND student_role
 *   3. CHECK (student_role = 'STUDENT')            - pins the value
 *   4. FOREIGN KEY (student_id, student_role)
 *          REFERENCES app_user (id, role)          - pins it to the REAL role
 *
 * Steps 3 and 4 together are the guarantee. The CHECK alone would let a row
 * claim STUDENT for a teacher's id; the foreign key alone would let it claim any
 * role. Together, the only value that satisfies both is the genuine role of that
 * user, and it must be STUDENT.
 *
 * It has a consequence worth stating plainly: while somebody is enrolled, their
 * role cannot be edited. That is the constraint working, not an obstacle. A
 * "teacher" still sitting on a class register as a pupil is precisely the
 * inconsistency this prevents.
 *
 * WHAT H2 GETS, AND WHAT IT DOES NOT
 * The test database is built from these annotations, and JPA cannot express a
 * composite foreign key whose second column is pinned to a literal. So H2 gets
 * the CHECK and a single-column foreign key on student_id - the rule verified in
 * two pieces rather than one. The composite form exists only in the migration,
 * and is therefore only provable against SQL Server. That gap is real and is
 * checked by hand; see docs/project/PRD.md L10.
 */
@Entity
@Table(
        name = "enrolment",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_enrolment_student_class",
                columnNames = {"student_id", "class_id"}))
@Check(name = "ck_enrolment_student_role", constraints = "student_role = 'STUDENT'")
public class Enrolment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "student_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_enrolment_student"))
    private AppUser student;

    /**
     * The student's role, duplicated from app_user so the composite foreign key
     * above has something to point at.
     *
     * Duplication that is CONSTRAINED is not the same thing as duplication that
     * is hoped for. Nothing can set this to a value that disagrees with the
     * referenced account, because the foreign key would fail. There is
     * deliberately no setter: it is derived once, at construction, from the
     * student it describes.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "student_role", nullable = false, length = 20)
    @JsonIgnore
    private Role studentRole;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "class_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_enrolment_class"))
    private SchoolClass schoolClass;

    @Version
    @JsonIgnore
    private Long version;

    protected Enrolment() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    /**
     * There is no constructor that omits either side, and none that lets the
     * caller choose the role. An Enrolment that does not yet know who or where
     * cannot be built and then quietly saved half-formed.
     *
     * THE NULL CHECKS BELOW ARE WHAT MAKE THAT SENTENCE TRUE, NOT JUST STATED.
     * Before this, the constructor accepted a null student or class silently
     * (`student == null ? null : student.getRole()` was written defensively
     * around exactly that gap) and only failed later, opaquely, at the
     * database's NOT NULL columns.
     *
     * THE PARAMETERS BELOW ARE DELIBERATELY *NOT* @NonNull. Java has no
     * enforced non-null types - @NonNull on a parameter is Eclipse's null
     * analysis trusting the caller's promise at compile time, nothing more,
     * and it does not survive to the bytecode. If these parameters carried
     * @NonNull, the checks immediately below would be provably unreachable BY
     * THAT SAME ANALYSIS ("the type already guarantees this is never null, so
     * why check?") and be flagged as dead code - which is exactly backwards:
     * the check is what makes the guarantee real, not the annotation.
     */
    public Enrolment(AppUser student, SchoolClass schoolClass) {
        if (student == null) {
            throw new IllegalArgumentException("An enrolment needs a student.");
        }
        if (schoolClass == null) {
            throw new IllegalArgumentException("An enrolment needs a class.");
        }
        this.student = student;
        this.schoolClass = schoolClass;
        this.studentRole = student.getRole();
    }

    public Long getId() {
        return id;
    }

    /**
     * WHY THESE TWO GETTERS CHECK A FIELD THE CONSTRUCTOR ALREADY REJECTED
     * -------------------------------------------------------------------
     * The @NonNull sits on the GETTER, not on the field, and the check is
     * what makes it verifiable rather than merely asserted.
     *
     * It cannot sit on the field, because the JPA constructor above exists
     * precisely to hand Hibernate an instance whose fields are still null,
     * to be populated by reflection immediately afterwards. A @NonNull field
     * would be a statement this class genuinely cannot make during that
     * window - and Eclipse says so, correctly: "the @NonNull field student
     * may not have been initialized". Annotating anyway would be the exact
     * mistake the Nullability section of CLAUDE.md warns against, trading a
     * visible warning for a runtime NPE somewhere further away.
     *
     * What IS true is that no Enrolment a caller can ever observe has a null
     * student or class: the public constructor rejects both, and any row
     * Hibernate rebuilds came from NOT NULL columns behind a foreign key.
     * The check below states that, and is ordinary flow-sensitive null
     * analysis rather than a trust exercise - the same technique, and the
     * same reasoning, as service.Require.orThrow (which this cannot simply
     * call: Require is package-private in the service layer, and a model
     * class reaching upwards into it would invert the layering).
     *
     * If either ever fires, the entity is genuinely corrupt and a loud
     * message here beats a bare NullPointerException three frames later.
     */
    @NonNull
    public AppUser getStudent() {
        AppUser value = student;
        if (value == null) {
            throw new IllegalStateException("Enrolment " + id + " has no student.");
        }
        return value;
    }

    public Role getStudentRole() {
        return studentRole;
    }

    @NonNull
    public SchoolClass getSchoolClass() {
        SchoolClass value = schoolClass;
        if (value == null) {
            throw new IllegalStateException("Enrolment " + id + " has no class.");
        }
        return value;
    }

    public Long getVersion() {
        return version;
    }
}
