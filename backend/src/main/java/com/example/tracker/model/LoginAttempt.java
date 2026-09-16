package com.example.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.lang.NonNull;

import java.time.Instant;

/**
 * ONE FAILED SIGN-IN ATTEMPT.
 *
 * Written only when authentication FAILS. A successful sign-in clears the
 * username's history instead of adding to it, so this table holds exactly the
 * evidence a throttle needs and nothing about who signed in successfully -
 * that is the audit log's job, and duplicating it here would put a second,
 * weaker copy of the same fact in a table nobody treats as authoritative.
 *
 * NO FOREIGN KEY TO AppUser, ON PURPOSE. See the header of
 * V19__login_attempt_throttle.sql: most failed attempts name a username that
 * does not exist, and counting those is both the point of the table and what
 * stops the throttle turning into an account-enumeration oracle.
 *
 * NOTHING HERE IS DERIVED. There is no "locked" flag and no failure counter on
 * the account. Whether a sign-in is currently refused is COMPUTED by counting
 * rows inside a window, every time it is asked - the same rule percentages and
 * performance levels follow. A stored flag would need something to clear it,
 * and the something that clears it is the thing that eventually does not run.
 *
 * No @Version: rows are inserted and deleted, never updated, so there is no
 * lost-update to lose.
 */
@Entity
@Table(name = "login_attempt", indexes = {
        @Index(name = "ix_login_attempt_username", columnList = "username, attempted_at DESC"),
        @Index(name = "ix_login_attempt_ip", columnList = "ip_address, attempted_at DESC")
})
@Checks({
        @Check(name = "ck_login_attempt_username_not_blank", constraints = "LENGTH(username) > 0"),
        @Check(name = "ck_login_attempt_ip_not_blank", constraints = "LENGTH(ip_address) > 0")
})
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The username as typed, capped at app_user.username's own length.
     *
     * A longer string cannot match any account, so AuthController's
     * LoginRequest rejects it at the edge - but LoginAttemptService truncates
     * defensively as well, because this column is a place user-controlled text
     * gets stored and "the DTO already checked" is exactly the assumption that
     * stops being true the next time something else calls the service.
     */
    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String username;

    /** Evidence of where the attempt arrived from, not a reference to anything. */
    @NotBlank
    @Size(max = 45)
    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** For Hibernate. Leaves every field null until reflection fills them. */
    protected LoginAttempt() {
    }

    public LoginAttempt(String username, String ipAddress, Instant attemptedAt) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A login attempt needs the username that was tried.");
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            throw new IllegalArgumentException("A login attempt needs the address it came from.");
        }
        if (attemptedAt == null) {
            throw new IllegalArgumentException("A login attempt needs a time.");
        }
        this.username = username;
        this.ipAddress = ipAddress;
        this.attemptedAt = attemptedAt;
    }

    public Long getId() {
        return id;
    }

    /**
     * Non-null on anything a caller can observe: the constructor rejects a
     * blank, and a row Hibernate rebuilt came from a NOT NULL column. Stated
     * as an explicit check rather than an annotation on the field - see
     * CLAUDE.md's Nullability section for why the getter is the right place.
     */
    @NonNull
    public String getUsername() {
        if (username == null) {
            throw new IllegalStateException("A persisted login attempt has no username.");
        }
        return username;
    }

    @NonNull
    public String getIpAddress() {
        if (ipAddress == null) {
            throw new IllegalStateException("A persisted login attempt has no address.");
        }
        return ipAddress;
    }

    @NonNull
    public Instant getAttemptedAt() {
        if (attemptedAt == null) {
            throw new IllegalStateException("A persisted login attempt has no time.");
        }
        return attemptedAt;
    }
}
