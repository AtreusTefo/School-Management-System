package com.example.tracker.service;

import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BUSINESS LAYER for accounts.
 *
 * Small on purpose. Its real job is to answer one question that the rest of the
 * system asks constantly: "who is making this request?" Answering it in one
 * place means the authority rules elsewhere cannot disagree about the answer.
 */
@Service
@Transactional(readOnly = true)
public class AppUserService {

    /**
     * The shortest password this system will accept.
     *
     * Length is the only rule enforced. Composition rules - "one capital, one
     * digit, one symbol" - reliably produce Password1! and a sticky note, and
     * are worth less than the extra characters length buys.
     */
    static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository users;

    /**
     * The same encoder the rest of the application uses, injected rather than
     * constructed here. Hashing a password with a different cost factor than the
     * one sign-in verifies against would lock the account out silently.
     */
    private final PasswordEncoder encoder;

    public AppUserService(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * Look up an account, or fail loudly.
     *
     * A missing account at this point is not a user mistake - Spring Security
     * already authenticated the name - so it means the row vanished mid-session.
     * That is a genuine fault and should not be quietly swallowed.
     */
    /**
     * Require.orThrow, not .orElseThrow(...) directly: java.util.Optional
     * carries no null-safety annotations, so a method declared @NonNull that
     * ended with .orElseThrow(...) would still be flagged at its own return
     * statement. See Require for the full reasoning; every "look this up or
     * fail" guard in the service layer now goes through it.
     */
    @NonNull
    public AppUser requireByUsername(String username) {
        return Require.orThrow(users.findByUsername(username),
                () -> new IllegalStateException(
                        "Authenticated user '" + username + "' no longer exists."));
    }

    /**
     * Look up an account named by a CLIENT, not by the session.
     *
     * Separate from requireByUsername on purpose. That method asks about the
     * already-authenticated caller, so a miss is a server fault. This one asks
     * about a name somebody typed, so a miss is an ordinary bad request and must
     * surface as 400 rather than 500.
     */
    @NonNull
    public AppUser findByUsernameOrReject(String username) {
        return Require.orThrow(users.findByUsername(username),
                () -> new IllegalArgumentException(
                        "No account named '" + username + "'."));
    }

    /**
     * Look up an account by id, for callers (like admin operations choosing a
     * student or teacher by id) that already know which HTTP status a miss
     * should produce and want to choose the exception themselves - unlike
     * findByUsernameOrReject, which fixes that choice at 400.
     *
     * A null id is treated the same as "not found" rather than passed
     * through: every current caller already rejects null before reaching
     * here, but this method's own signature does not say so, and
     * AppUserRepository.findById declares a @NonNull parameter - passing an
     * unproven Long through is exactly the "our own declaration carries no
     * contract" gap described in the Nullability section of CLAUDE.md. The
     * guard here makes the gap disappear rather than papering over it with
     * an annotation this method cannot actually back up.
     */
    public java.util.Optional<AppUser> findById(Long id) {
        if (id == null) {
            return java.util.Optional.empty();
        }
        return users.findById(id);
    }

    /** Every account holding one role, alphabetical - the admin panel's lists. */
    public java.util.List<AppUser> findByRole(Role role) {
        return users.findByRoleOrderByUsernameAsc(role);
    }

    /**
     * The account behind the current request.
     *
     * Reads from the SecurityContext, which Spring Security populates from the
     * session on every request. Note what this does NOT do: it never trusts a
     * user id sent by the client. A request that says "I am user 7" proves
     * nothing; the session cookie is what proves identity.
     */
    public AppUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user on this request.");
        }
        return requireByUsername(authentication.getName());
    }

    /**
     * The caller, but only if their account is fully theirs (US-22).
     *
     * Everything that does real work goes through this instead of currentUser().
     * While an account is pending a password change, the person signed in as it
     * is not provably its owner - a teacher issued the temporary password and
     * knows it - so the account may sign in, look at itself, and change its
     * password, and nothing else.
     *
     * Deliberately a SEPARATE method rather than a check inside currentUser().
     * The password-change endpoint has to identify the caller in order to serve
     * them, so a blanket check in the one place everybody calls would lock the
     * only route out of the pending state.
     */
    public AppUser currentActiveUser() {
        AppUser user = currentUser();
        if (user.isMustChangePassword()) {
            throw new AccessDeniedException(
                    "Your password must be changed before you can use the system.");
        }
        return user;
    }

    /**
     * Replace the caller's own password (US-21).
     *
     * THE CURRENT PASSWORD IS REQUIRED EVEN THOUGH THE CALLER IS AUTHENTICATED.
     * A session cookie proves that somebody signed in as this account at some
     * point; it does not prove that the person at the keyboard now is its owner.
     * Without this check, an unattended browser or a stolen session would be
     * enough to seize the account permanently by changing what it needs to sign
     * in. Asking for the existing password is what keeps a session hijack
     * temporary.
     *
     * A wrong current password is an authentication failure, not a bad request,
     * so it surfaces as 401 - and says only that it did not match, never whether
     * some other value would have.
     */
    @Transactional
    public AppUser changeOwnPassword(String currentPassword, String newPassword) {
        AppUser user = currentUser();

        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Your current password is not correct.");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("The new password must not be blank.");
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "The new password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException(
                    "The new password must be different from the current one.");
        }

        user.setPasswordHash(encoder.encode(newPassword));
        // Whatever the account was pending, it is not pending now: the password
        // is one only its owner has typed.
        user.setMustChangePassword(false);
        return user;
    }
}
