package com.example.tracker.config;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * THE FIRST ADMINISTRATOR OF A NEW PRODUCTION DATABASE
 * ----------------------------------------------------
 * Under the "prod" profile no seed runner runs (see TrackerApplication and
 * ProductionProfileTest), so a fresh database has no account anybody can sign
 * in with - and only an admin can create accounts. Without something like this
 * class, the first deployment would need a hand-written INSERT carrying a
 * BCrypt hash, which is the "hash literal is a hostage to the cost factor"
 * problem migration V2 already describes.
 *
 * WHAT IT DOES
 * ------------
 * If APP_BOOTSTRAP_ADMIN_PASSWORD is set AND no ADMIN account exists, it
 * creates one account, username "admin", with that password and
 * must_change_password set. The first sign-in is therefore forced through the
 * password change, so the value that sat in the hosting configuration stops
 * being a working password at the first use.
 *
 * WHY IT IS NOT A SEED RUNNER
 * ---------------------------
 * The seed runners RESET a changed password back to a literal on every start.
 * This does the opposite: it acts at most once, and never again once any admin
 * exists - not even to repair a forgotten password. A variable left behind in
 * the configuration is therefore inert, and this logs a warning saying so,
 * rather than being a standing way back in.
 *
 * It lives outside TrackerApplication deliberately. ProductionProfileTest
 * requires every runner declared there to be excluded from "prod"; this is the
 * one runner that exists ONLY for "prod", and putting it beside the others
 * would mean weakening that test to make room for it.
 */
@Component
@Profile("prod")
public class FirstAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    /** The username of the one account this class can create. */
    static final String USERNAME = "admin";

    /**
     * The shortest password accepted here. Short of the strength rules the
     * password-change form enforces, but it is temporary by construction and
     * is replaced at the first sign-in; this only stops an obviously trivial
     * value from protecting an admin account in the meantime.
     */
    static final int MIN_LENGTH = 12;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String password;

    public FirstAdminBootstrap(AppUserRepository users,
                               PasswordEncoder encoder,
                               @Value("${app.bootstrap-admin-password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            return;
        }
        if (!users.findByRoleOrderByUsernameAsc(Role.ADMIN).isEmpty()) {
            log.warn("APP_BOOTSTRAP_ADMIN_PASSWORD is set, but an administrator already exists, "
                    + "so it has no effect. Remove it from the configuration.");
            return;
        }
        if (password.length() < MIN_LENGTH) {
            // Refusing to start is louder than skipping: a skipped bootstrap
            // leaves a deployment nobody can sign in to, with nothing obvious
            // to explain why.
            throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD must be at least "
                    + MIN_LENGTH + " characters long.");
        }
        if (users.findByUsername(USERNAME).isPresent()) {
            throw new IllegalStateException("Cannot create the first administrator: an account named '"
                    + USERNAME + "' already exists with a different role.");
        }

        users.save(new AppUser(USERNAME, encoder.encode(password), Role.ADMIN, true));
        log.warn("Created the first administrator account '{}'. It must change its password at "
                + "first sign-in. Remove APP_BOOTSTRAP_ADMIN_PASSWORD from the configuration.", USERNAME);
    }
}
