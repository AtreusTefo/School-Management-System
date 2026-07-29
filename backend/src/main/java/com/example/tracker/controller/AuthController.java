package com.example.tracker.controller;

import com.example.tracker.model.AppUser;
import com.example.tracker.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/**
 * SIGNING IN AND OUT (presentation layer)
 *
 * HTTP only, like every controller here: it takes credentials, asks Spring
 * Security to verify them, and reports who you are. It does not decide what a
 * role may do - that lives in SecurityConfig and in the service layer.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserService users;

    public AuthController(AuthenticationManager authenticationManager, AppUserService users) {
        this.authenticationManager = authenticationManager;
        this.users = users;
    }

    /**
     * POST /api/auth/login
     *
     * On success the session holds the authenticated context, and the browser
     * keeps the session cookie, so later calls need no credentials.
     *
     * Wrong username and wrong password produce the SAME 401 with the same
     * message. Distinguishing them would tell an attacker which usernames exist,
     * turning one guess into two separate, easier problems.
     */
    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest request,
                          HttpServletRequest httpRequest,
                          HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(), request.getPassword()));
        } catch (Exception ex) {
            throw new BadCredentialsException("Invalid username or password.");
        }

        // Bind the authenticated context to a session so it persists across calls.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository()
                .saveContext(context, httpRequest, httpResponse);

        AppUser user = users.requireByUsername(authentication.getName());
        return UserView.of(user);
    }

    /**
     * GET /api/auth/me - who am I?
     * The frontend calls this on startup to decide whether to show the login
     * screen or the assignment list. Returns 401 when nobody is signed in.
     */
    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        return UserView.of(users.requireByUsername(authentication.getName()));
    }

    /**
     * POST /api/auth/logout - end the session.
     *
     * Handled here rather than by Spring Security's default so the answer is
     * JSON-shaped like every other endpoint, instead of a redirect.
     */
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * GET /api/auth/csrf
     *
     * Issues the XSRF-TOKEN cookie so the client has a token before its first
     * state-changing request. Angular reads that cookie and echoes it back
     * automatically as X-XSRF-TOKEN; without this call the very first POST would
     * have no token to send and would be rejected.
     *
     * THE getToken() CALL IS NOT DECORATIVE - REMOVING IT BREAKS SIGN-IN.
     * Spring Security 6 loads the CSRF token lazily: CookieCsrfTokenRepository
     * only writes the cookie if something actually reads the token while
     * handling the request. An empty method body reads nothing, so the response
     * carried no cookie, the client had no token, and every write was refused.
     * Touching the value here is what forces it to exist.
     */
    @GetMapping("/csrf")
    public void csrf(CsrfToken token) {
        token.getToken();
    }

    /** What the client may send when signing in. */
    static class LoginRequest {
        @NotBlank(message = "Username must not be blank")
        private String username;

        @NotBlank(message = "Password must not be blank")
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * What the client is told back. Deliberately not the AppUser entity: this
     * carries a name and a role and nothing else, so the password hash cannot
     * leak by accident when a field is added to the entity later.
     */
    public record UserView(Long id, String username, String role) {
        static UserView of(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getRole().name());
        }
    }
}
