package com.example.tracker.service;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.PasswordResetToken;
import com.example.tracker.model.PersonProfile;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.PasswordResetTokenRepository;
import com.example.tracker.repository.PersonProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * SERVICE LAYER for "I have forgotten my password".
 *
 * THE ONE RULE THAT SHAPES EVERYTHING ELSE: THIS MUST NOT REVEAL WHO HAS AN
 * ACCOUNT. requestReset returns the same thing whether the name matched
 * nobody, matched an account with no email on file, or sent a real message.
 * An endpoint that answered differently would be a free tool for working out
 * which student IDs are real, and it is reachable without signing in.
 *
 * That single requirement is why several things here look odd:
 *   nothing is thrown for an unknown account - it returns quietly
 *   ResetLinkMailer swallows its own failures rather than propagating them
 *   the rate limit is applied silently, not reported
 *
 * WHAT A TOKEN IS
 * 32 bytes from SecureRandom, base64url-encoded. Stored only as a SHA-256
 * hash - see V15 for why that hash is not BCrypt. Single use, and short-lived.
 *
 * ADMIN ACCOUNTS CANNOT USE THIS, AND THAT IS NOT A BUG. An admin has no
 * PersonProfile at all (a documented decision - see CLAUDE.md's Scope Guard),
 * so there is no address to send to. They are recovered by another admin
 * through the existing reset, which is the correct route for the role that can
 * onboard everybody else.
 */
@Service
@Transactional(readOnly = true)
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * How long a link lives.
     *
     * Short, because a reset link is a bearer credential sitting in an inbox:
     * anyone who reads that inbox later - a shared family machine, a mail
     * archive, a stolen laptop - can take the account. Thirty minutes is long
     * enough to walk to a computer and short enough that a forgotten email
     * stops being dangerous quickly.
     */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    /**
     * The shortest gap between two links for one account.
     *
     * Stops the endpoint being used to flood somebody's inbox, and stops a
     * script generating unlimited live tokens. Applied SILENTLY - saying "you
     * asked too recently" would answer the enumeration question this whole
     * class refuses to answer.
     */
    private static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    private final AppUserRepository users;
    private final PersonProfileRepository profiles;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final ResetLinkMailer mailer;
    private final AuditLogService audit;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(AppUserRepository users,
                                PersonProfileRepository profiles,
                                PasswordResetTokenRepository tokens,
                                PasswordEncoder encoder,
                                ResetLinkMailer mailer,
                                AuditLogService audit) {
        this.users = users;
        this.profiles = profiles;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mailer = mailer;
        this.audit = audit;
    }

    /**
     * Start a reset. Returns nothing, tells the caller nothing, always.
     *
     * `identifier` may be a sign-in id (STU20260001) or a contact email -
     * people remember one or the other, and refusing the one they remember
     * would make the feature useless to exactly the person who needs it.
     */
    @Transactional
    public void requestReset(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return;   // silently, like every other miss here
        }
        String clean = identifier.trim();

        AppUser account = findByIdOrEmail(clean).orElse(null);
        if (account == null) {
            // Deliberately quiet. See the class comment.
            log.info("Password reset requested for an identifier that matched no account.");
            return;
        }

        if (askedTooRecently(account)) {
            log.info("Password reset for '{}' throttled - asked again within {}.",
                    account.getUsername(), MIN_INTERVAL);
            return;
        }

        String rawToken = newToken();
        tokens.save(new PasswordResetToken(
                sha256(rawToken), account, Instant.now().plus(TOKEN_LIFETIME)));

        /*
         * The audit entry records that a reset was REQUESTED, never the token.
         * Writing the token here would defeat the whole point of storing only
         * its hash one line above.
         */
        audit.record("AppUser", account.getId(), AuditAction.UPDATE, account,
                "A password reset link was requested for '" + account.getUsername() + "'.");

        mailer.send(emailFor(account), account.getUsername(), rawToken);
    }

    /**
     * Finish a reset: spend the token, set the password.
     *
     * UNLIKE requestReset, THIS DOES REPORT FAILURE - and it can, because a
     * token is not a guessable identifier. Telling somebody their link has
     * expired reveals nothing about who has an account; leaving them staring
     * at a form that silently does nothing would be its own kind of broken.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("A reset link is required.");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("The new password must not be blank.");
        }
        if (newPassword.length() < AppUserService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "The new password must be at least "
                            + AppUserService.MIN_PASSWORD_LENGTH + " characters.");
        }

        PasswordResetToken token = tokens.findByTokenHash(sha256(rawToken.trim()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "This reset link is not valid. Ask for a new one."));

        Instant now = Instant.now();
        if (token.isUsed()) {
            // Distinguished from "not valid" on purpose - see the entity.
            throw new IllegalArgumentException(
                    "This reset link has already been used. Ask for a new one.");
        }
        if (token.isExpired(now)) {
            throw new IllegalArgumentException(
                    "This reset link has expired. Ask for a new one.");
        }

        AppUser account = token.getAppUser();
        account.setPasswordHash(encoder.encode(newPassword));
        /*
         * Clears the pending flag too. Somebody who was issued a temporary
         * password, never used it, and reset instead has now chosen their own -
         * which is exactly the condition mustChangePassword exists to force.
         * Leaving it set would send them straight back to a change-password
         * screen and ask for the temporary password they never knew.
         */
        account.setMustChangePassword(false);
        token.markUsed();

        /*
         * Every other outstanding link for this account dies with it. Somebody
         * who clicked "forgot password" three times has three live keys in
         * three emails; spending one must not leave the other two usable.
         */
        for (PasswordResetToken other
                : tokens.findByAppUserAndUsedAtIsNullAndExpiresAtAfter(account, now)) {
            if (!other.getId().equals(token.getId())) {
                other.markUsed();
            }
        }

        audit.record("AppUser", account.getId(), AuditAction.UPDATE, account,
                "'" + account.getUsername() + "' set a new password using a reset link.");
    }

    // ----- helpers -----------------------------------------------------------

    /**
     * A sign-in id, or a contact email.
     *
     * AN EMAIL SHARED BY MORE THAN ONE ACCOUNT MATCHES NOTHING, DELIBERATELY.
     * contact_email is not unique and was never meant to be - siblings sharing
     * a parent's inbox is the case V11 explicitly decided to allow, and this
     * database already contains one such address. The previous version of this
     * method took .findFirst() over the matches, which meant a link sent to
     * that address would reset an ARBITRARY one of the accounts behind it:
     * a parent could click the link in good faith and change the wrong child's
     * password, with nothing anywhere recording which one it was going to be.
     *
     * Refusing to act is the only safe answer. Sending a link for every match
     * would multiply the blast radius rather than resolve it, and asking the
     * caller which account they meant would answer the account-enumeration
     * question this whole class exists to keep unanswerable. Somebody in that
     * position uses their sign-in ID, which is unique by construction - and the
     * forgot-password page already tells them an administrator can help.
     *
     * Also no longer loads every profile in the school to find one address:
     * that filtering now happens in the database (and V16 indexes the column,
     * correcting V12's note that nothing queried it - something did, from the
     * moment this method was written).
     */
    private Optional<AppUser> findByIdOrEmail(String identifier) {
        Optional<AppUser> byName = users.findByUsername(identifier);
        if (byName.isPresent()) {
            return byName;
        }

        List<PersonProfile> byEmail = profiles.findByContactEmailIgnoreCase(identifier);
        if (byEmail.size() != 1) {
            if (byEmail.size() > 1) {
                log.info("Password reset identifier matched {} accounts by email - "
                        + "refusing to guess which was meant.", byEmail.size());
            }
            return Optional.empty();
        }
        return Optional.of(byEmail.get(0).getAppUser());
    }

    /**
     * The address to send to, or null.
     *
     * An explicit local rather than .map(PersonProfile::getContactEmail): the
     * method-reference form draws "Null type safety: parameter 'this' ... needs
     * unchecked conversion" from Eclipse, because Optional is an unannotated
     * JDK generic and the receiver carries no contract the analysis can see.
     * A local and an if is ordinary flow-sensitive analysis, verified natively
     * with no annotation and no suppression - see CLAUDE.md's Nullability
     * section, and Require.orThrow for the same technique applied to a throw.
     */
    private String emailFor(AppUser account) {
        PersonProfile profile = profiles.findByAppUser(account).orElse(null);
        return profile == null ? null : profile.getContactEmail();
    }

    private boolean askedTooRecently(AppUser account) {
        List<PasswordResetToken> existing = tokens.findByAppUserOrderByCreatedAtDesc(account);
        if (existing.isEmpty()) {
            return false;
        }
        Instant mostRecent = existing.get(0).getCreatedAt();
        return mostRecent != null
                && mostRecent.isAfter(Instant.now().minus(MIN_INTERVAL));
    }

    /** 32 bytes of entropy, URL-safe so it survives being pasted into a link. */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, hex.
     *
     * Deterministic by design: the whole point is that presenting the token
     * again produces the same hash, so the row can be FOUND. That is exactly
     * what a salted password hash cannot do, and why this is not BCrypt - see
     * V15's header for the full reasoning.
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
