package com.example.tracker.repository;

import com.example.tracker.model.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * DATA ACCESS for failed sign-in attempts. How many is too many, and over what
 * window, is LoginAttemptService's - nothing here decides anything.
 *
 * Every method counts or deletes rather than loading. The throttle needs a
 * number, and the purge needs rows gone; neither has any use for a list of
 * LoginAttempt objects in the persistence context, and building one on a table
 * that grows fastest exactly when it is under attack would be the wrong shape.
 */
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Failures against one username since a moment - served by ix_login_attempt_username. */
    long countByUsernameAndAttemptedAtAfter(String username, Instant since);

    /** Failures from one address since a moment - served by ix_login_attempt_ip. */
    long countByIpAddressAndAttemptedAtAfter(String ipAddress, Instant since);

    /**
     * Forget this username's failures, called when it signs in successfully.
     *
     * Someone who mistyped four times and then got it right should not spend
     * the next quarter of an hour four failures away from being locked out.
     */
    @Modifying
    @Query("DELETE FROM LoginAttempt a WHERE a.username = :username")
    void deleteByUsername(@Param("username") String username);

    /** The hourly tidy-up - see LoginAttemptService.purgeOld. */
    @Modifying
    @Query("DELETE FROM LoginAttempt a WHERE a.attemptedAt < :cutoff")
    int deleteByAttemptedAtBefore(@Param("cutoff") Instant cutoff);
}
