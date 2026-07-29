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
     */
    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50, unique = true)
    private String username;

    /** BCrypt hash. 60 characters for the standard $2a$ format; 100 leaves room. */
    @NotBlank
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Optimistic locking, for the same reason Assignment carries one. */
    @jakarta.persistence.Version
    @JsonIgnore
    private Long version;

    protected AppUser() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public AppUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
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

    public Long getVersion() {
        return version;
    }
}
