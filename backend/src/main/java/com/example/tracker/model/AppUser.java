package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

/**
 * A person who can sign in.
 *
 * Named AppUser rather than User because "user" is a reserved word in several
 * databases, including SQL Server. Fighting the keyword with quoted identifiers
 * on every query is a poor trade for a four-character saving.
 *
 * PASSWORDS ARE NEVER STORED AS TYPED.
 * The passwordHash column holds a BCrypt hash - a one-way function. Nobody,
 * including whoever runs the database, can read the original password back out.
 * Sign-in works by hashing the attempt and comparing hashes, never by comparing
 * plain text. @JsonIgnore keeps the hash out of API responses as well; there is
 * no reason for it ever to leave the server.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The name typed at sign-in. Unique, enforced by the database rather than by
     * a "check then insert" in Java, which two simultaneous registrations could
     * both pass before either wrote.
     *
     * For an account made through the admin panel, this IS externalId below -
     * the same generated value, in the one column sign-in actually reads. See
     * externalId's own comment for why there are not two columns doing
     * related but separate jobs.
     */
    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50, unique = true)
    private String username;

    /**
     * The id an admin-created account was issued at creation: STU20260001,
     * TCH20260001, ADM20260001. Null for the accounts seeded for development
     * (teacher, student, admin), which predate this rule and keep their
     * literal usernames as a documented exception - see CLAUDE.md.
     *
     * DUPLICATES username RATHER THAN BEING THE ONLY COPY, ON PURPOSE.
     * The alternative - deriving the login name from this field instead of
     * storing it twice - would still need username to exist as a real column
     * (Spring Security authenticates against it), so nothing is saved by
     * splitting them; what would be lost is the ability to tell, later,
     * whether a given username IS a system-issued id or a legacy dev
     * account's chosen name. Keeping both makes that a fact the row records
     * rather than a pattern something has to re-derive by parsing the string.
     *
     * NOT MARKED unique = true HERE, deliberately - see
     * uq_app_user_external_id in V8, a FILTERED index ("WHERE external_id IS
     * NOT NULL"). A plain unique constraint treats every NULL as equal on
     * SQL Server, which would permit exactly one legacy dev account with no
     * external_id in the whole table. The same reasoning already governs
     * Assessment.submission - see that class - and for the same reason, H2's
     * test schema does not enforce this particular rule; it is verified by
     * hand against SQL Server instead.
     */
    @Size(max = 20)
    @Column(name = "external_id", length = 20)
    private String externalId;

    /** BCrypt hash. 60 characters for the standard $2a$ format; 100 leaves room. */
    @NotBlank
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /**
     * True while this account still holds a password somebody else chose (US-22).
     *
     * An account created by a teacher is issued with a temporary password that
     * the teacher necessarily knows. Until the owner replaces it, "signed in as
     * this person" does not reliably mean this person, so the service refuses
     * every other operation for them.
     *
     * A primitive boolean rather than Boolean: there is no third state. Allowing
     * null would force every caller to decide what "unset" meant, and they would
     * not all decide the same way.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** Optimistic locking, for the same reason Assignment carries one. */
    @jakarta.persistence.Version
    @JsonIgnore
    private Long version;

    protected AppUser() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public AppUser(String username, String passwordHash, Role role) {
        this(username, passwordHash, role, false);
    }

    public AppUser(String username, String passwordHash, Role role,
                   boolean mustChangePassword) {
        this(username, passwordHash, role, mustChangePassword, null);
    }

    /**
     * The shape AdminService.createStudent/createTeacher/createAdmin use:
     * username and externalId are the SAME generated value (STU20260001 and
     * so on) for every account made through the admin panel. See
     * externalId's own field comment for why both columns still exist.
     */
    public AppUser(String username, String passwordHash, Role role,
                   boolean mustChangePassword, String externalId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
        this.externalId = externalId;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Long getVersion() {
        return version;
    }
}
