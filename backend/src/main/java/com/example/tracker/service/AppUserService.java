package com.example.tracker.service;

import com.example.tracker.model.AppUser;
import com.example.tracker.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private final AppUserRepository users;

    public AppUserService(AppUserRepository users) {
        this.users = users;
    }

    /**
     * Look up an account, or fail loudly.
     *
     * A missing account at this point is not a user mistake - Spring Security
     * already authenticated the name - so it means the row vanished mid-session.
     * That is a genuine fault and should not be quietly swallowed.
     */
    public AppUser requireByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException(
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
    public AppUser findByUsernameOrReject(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No account named '" + username + "'."));
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
}
