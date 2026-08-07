package com.example.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * One immutable record of a Create, Update or Delete somewhere in the system.
 *
 * NO SETTERS, ON PURPOSE. Every field is set once, in the constructor, and
 * never again - there is no `update()` method here because there is nothing
 * an audit log entry may legitimately be corrected to say. If a logged
 * summary is wrong, the fix is a new entry explaining the correction, never
 * an edit to the old one.
 *
 * NOT ROLE-PINNED BY A COMPOSITE FOREIGN KEY, UNLIKE Course/Enrolment/
 * Assessment. Those three all point at app_user (id, role) so the database
 * itself refuses a row claiming the wrong role for who it names. This entity
 * does the opposite deliberately: performedById carries no foreign key at
 * all. See V7__add_admin_role_and_audit_log.sql for why a live reference here
 * would make every account that had ever done anything permanently
 * undeletable.
 */
@Entity
@Table(name = "audit_log")
/*
 * LTRIM(RTRIM(x)) <> '' rather than the migration's LEN(LTRIM(RTRIM(x))) > 0 -
 * the two are equivalent, but H2 (which builds the test schema from this
 * annotation) has no LEN() function; that is SQL Server's name for it. Every
 * other blank-guard in this codebase (Assignment, Assessment) already makes
 * the same substitution for the same reason.
 */
@Check(name = "ck_audit_log_summary_not_blank", constraints = "LTRIM(RTRIM(summary)) <> ''")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 60)
    @Column(name = "entity_name", nullable = false, length = 60)
    private String entityName;

    @Column(name = "entity_id")
    private Long entityId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditAction action;

    /** A snapshot, not a live reference - see the class comment. */
    @Column(name = "performed_by_id")
    private Long performedById;

    @NotBlank
    @Size(max = 50)
    @Column(name = "performed_by_username", nullable = false, length = 50)
    private String performedByUsername;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "performed_by_role", nullable = false, length = 20)
    private Role performedByRole;

    @NotNull
    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    @NotBlank
    @Size(max = 500)
    @Column(nullable = false, length = 500)
    private String summary;

    protected AuditLog() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public AuditLog(String entityName, Long entityId, AuditAction action,
                    AppUser performedBy, String summary, Instant performedAt) {
        this.entityName = entityName;
        this.entityId = entityId;
        this.action = action;
        this.performedById = performedBy == null ? null : performedBy.getId();
        this.performedByUsername = performedBy == null ? "unknown" : performedBy.getUsername();
        this.performedByRole = performedBy == null ? null : performedBy.getRole();
        this.summary = summary;
        this.performedAt = performedAt;
    }

    public Long getId() {
        return id;
    }

    public String getEntityName() {
        return entityName;
    }

    public Long getEntityId() {
        return entityId;
    }

    public AuditAction getAction() {
        return action;
    }

    public Long getPerformedById() {
        return performedById;
    }

    public String getPerformedByUsername() {
        return performedByUsername;
    }

    public Role getPerformedByRole() {
        return performedByRole;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }

    public String getSummary() {
        return summary;
    }
}
