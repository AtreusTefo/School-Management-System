package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * DATA ACCESS for accounts.
 *
 * Like AssignmentRepository, this is an interface with no body: Spring Data
 * writes the implementation at runtime. The one method declared here is derived
 * from its own name - Spring reads "findByUsername" and generates
 * "SELECT ... WHERE username = ?". No SQL is written by hand, and none belongs
 * in the service above it.
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Look an account up by the name typed at sign-in.
     *
     * Returns Optional rather than null so the caller is forced to handle
     * "no such user" rather than discovering it as a NullPointerException.
     */
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
