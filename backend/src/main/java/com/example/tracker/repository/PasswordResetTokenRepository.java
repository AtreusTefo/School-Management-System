package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * DATA ACCESS for password reset tokens.
 *
 * LOOKUP IS BY HASH, never by token - the token is not stored, so there is
 * nothing else to look up by. See V15 for why that hash is SHA-256 rather than
 * BCrypt: a per-row salt would make this method impossible to write.
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Every token for an account, used or not - the rate check reads this. */
    List<PasswordResetToken> findByAppUserOrderByCreatedAtDesc(AppUser appUser);

    /**
     * Outstanding tokens for an account.
     *
     * Used to invalidate the rest when one is spent: a person who has clicked
     * "forgot password" three times should not leave three live keys to their
     * account lying in three emails.
     */
    List<PasswordResetToken> findByAppUserAndUsedAtIsNullAndExpiresAtAfter(
            AppUser appUser, Instant now);

    /** Removed with the account - see V15 on why this one DOES cascade in the service. */
    void deleteByAppUser(AppUser appUser);
}
