package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

    // JPA REQUIRES a no-argument constructor to build objects from DB rows.
    public Assignment() {
    }

    // A convenience constructor we use to create sample data at startup.
    public Assignment(String title, AssignmentStatus status) {
        this.title = title;
        this.status = status;
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
}
