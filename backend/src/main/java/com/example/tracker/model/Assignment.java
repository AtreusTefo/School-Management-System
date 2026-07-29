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

import java.time.LocalDate;

/**
 * MODEL LAYER
 * -----------
 * This class is an "Entity". It is a plain Java object that ALSO describes
 * a database table. Each field becomes a column; each object becomes a row.
 *
 * @Entity tells Spring/JPA: "Persist objects of this class in the database."
 *
 * WHERE INTEGRITY IS ENFORCED
 * ---------------------------
 * The rules below are declared in TWO places on purpose:
 *
 *   @NotBlank / @Size   are BEAN VALIDATION. Hibernate runs them before it
 *                       writes, so a bad object is refused in Java.
 *   @Column(...)        becomes real DDL — NOT NULL, a length limit, and (for
 *                       the enum) a CHECK constraint. The DATABASE refuses the
 *                       row even if something bypasses this application.
 *
 * Application-level checks alone are not data integrity; they are a policy that
 * holds only while every writer goes through this code. The column constraints
 * are what make the rule true of the data itself.
 */
@Entity
public class Assignment {

    /**
     * @Id marks the primary key (the unique identifier for each row).
     * @GeneratedValue means the database auto-assigns the number (1, 2, 3...),
     * so we never set the id ourselves.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The name of the assignment, e.g. "Math Homework 1".
     *
     * nullable = false and length = 200 are carried into the table definition,
     * so a NULL or over-long title is impossible at the storage layer, not just
     * discouraged at the web layer.
     */
    @NotBlank(message = "Title must not be blank")
    @Size(max = 200, message = "Title must be at most 200 characters")
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * The current state. Stored as text (EnumType.STRING) rather than as the
     * enum's position, because positions shift the moment somebody reorders or
     * inserts a constant — which would silently reinterpret every existing row.
     * Storing the name keeps old rows meaningful and readable in the database.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentStatus status;

    /**
     * OPTIMISTIC LOCKING — the guard against two people changing the same row
     * at the same time.
     *
     * Hibernate increments this on every update and adds "AND version = ?" to
     * the UPDATE. If another transaction got there first, the row no longer
     * matches, zero rows update, and Hibernate raises an optimistic-lock
     * failure that the web layer reports as 409 Conflict.
     *
     * Without it, two concurrent submissions both read IN_PROGRESS, both pass
     * the "already submitted?" check, and both write — the second silently
     * overwriting the first. That is a lost update, and it was reproducible
     * before this field existed.
     *
     * @JsonIgnore keeps it out of the API response, since the published
     * contract is {id, title, status} and no client needs this value today.
     */
    @Version
    @JsonIgnore
    private Long version;

    /**
     * Who this assignment belongs to. The system's FIRST foreign key.
     *
     * Until now the schema was a single table, so there was nothing for
     * referential integrity to enforce. This column changes that, and it is
     * declared as a real FK rather than "a number we promise points at a user":
     *
     *   - nullable = false      every assignment has an owner; there are no orphans
     *   - @ForeignKey(...)      a named constraint, so the database refuses an
     *                           owner_id that matches no row - even from sqlctl,
     *                           a script, or another application entirely
     *
     * Deletion semantics are deliberate and are handled in the service rather
     * than by ON DELETE CASCADE. Silently destroying somebody's assignments as a
     * side effect of removing an account is a decision, not a default.
     *
     * FetchType.EAGER is right here only because an assignment is never loaded
     * without needing its owner for an authority check. On a larger model this
     * would be LAZY.
     *
     * @JsonIgnore keeps the whole user object out of the response; the API
     * exposes just the owner's name through getOwnerUsername() below, so the
     * published contract does not leak account internals.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "owner_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_assignment_owner"))
    @JsonIgnore
    private AppUser owner;

    /**
     * A due date, or null when the assignment has no deadline.
     *
     * LocalDate rather than a timestamp: "due on the 5th" is a calendar fact,
     * not an instant, and storing it as an instant drags time zones into a
     * question that has none.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    // JPA REQUIRES a no-argument constructor to build objects from DB rows.
    public Assignment() {
    }

    /**
     * Every assignment is created owned. There is deliberately no constructor
     * that omits the owner, so an ownerless assignment cannot be built by
     * accident and then fail at the database.
     */
    public Assignment(String title, AssignmentStatus status, AppUser owner) {
        this.title = title;
        this.status = status;
        this.owner = owner;
    }

    public Assignment(String title, AssignmentStatus status, AppUser owner, LocalDate dueDate) {
        this(title, status, owner);
        this.dueDate = dueDate;
    }

    // ----- Getters and Setters -----
    // These let other layers (and JPA) read/write the private fields.
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public void setStatus(AssignmentStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    /**
     * The owner's name, for the API response.
     *
     * The owner object itself is @JsonIgnore'd, so this exposes exactly the one
     * field a client needs and nothing else - no id, no role, and certainly no
     * password hash. Widening what the API reveals then stays a deliberate act.
     */
    public String getOwnerUsername() {
        return owner == null ? null : owner.getUsername();
    }

    /**
     * Whether this assignment is past its due date and still not handed in.
     *
     * DERIVED, NOT STORED. @Transient means there is no such column, and that is
     * the point: a stored "overdue" flag is wrong the moment midnight passes,
     * and would need a scheduled job to keep it honest. Computing it on read is
     * always correct because it is answered against today's date, every time.
     *
     * A SUBMITTED assignment is never overdue, however late it was - handing work
     * in late is still handing it in.
     */
    @Transient
    public boolean isOverdue() {
        return dueDate != null
                && status != AssignmentStatus.SUBMITTED
                && dueDate.isBefore(LocalDate.now());
    }
}
