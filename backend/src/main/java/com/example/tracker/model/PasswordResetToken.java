package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.springframework.lang.NonNull;

import java.time.Instant;

/**
 * A single-use, time-limited permission to choose a new password.
 *
 * THIS ENTITY NEVER HOLDS THE TOKEN. It holds a SHA-256 of it. The token
 * itself is generated once, put in an email, and forgotten by the server -
 * see V15's header for why a fast deterministic hash is correct here where
 * BCrypt is correct for a password.
 *
 * IT IS ALSO NEVER SERIALISED. There is no DTO that exposes one and no
 * endpoint that returns one; the only thing the API ever says about a reset is
 * "if that account exists, a link has been sent". @JsonIgnore on the hash is
 * belt-and-braces on top of that, because the cost of being wrong here is an
 * account takeover rather than an information leak.
 */
@Entity
@Table(
        name = "password_reset_token",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_password_reset_token_hash", columnNames = "token_hash"))
@Check(name = "ck_password_reset_token_expiry", constraints = "expires_at > created_at")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(min = 64, max = 64)
    @Column(name = "token_hash", nullable = false, length = 64)
    @JsonIgnore
    private String tokenHash;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_password_reset_token_user"))
    @JsonIgnore
    private AppUser appUser;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null until spent. The row is kept, marked - see V15. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Version
    @JsonIgnore
    private Long version;

    protected PasswordResetToken() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public PasswordResetToken(String tokenHash, AppUser appUser, Instant expiresAt) {
        if (tokenHash == null) {
            throw new IllegalArgumentException("A reset token needs its hash.");
        }
        if (appUser == null) {
            throw new IllegalArgumentException("A reset token needs an account.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("A reset token needs an expiry.");
        }
        this.tokenHash = tokenHash;
        this.appUser = appUser;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        if (!expiresAt.isAfter(this.createdAt)) {
            throw new IllegalArgumentException("A reset token must expire in the future.");
        }
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public AppUser getAppUser() {
        AppUser value = appUser;
        if (value == null) {
            throw new IllegalStateException("Reset token " + id + " has no account.");
        }
        return value;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    /**
     * Usable exactly once, and only before it expires.
     *
     * Both halves matter and they fail for different reasons: an expired token
     * was never used and the person should ask for another; a used one may
     * mean somebody else already used it, which is worth being able to tell
     * apart. See PasswordResetService for how the two are reported.
     */
    public boolean isUsable(Instant now) {
        return !isUsed() && !isExpired(now);
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public Long getVersion() {
        return version;
    }
}
