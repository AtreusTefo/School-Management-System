package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EPIC-09: account self-service, end to end through the real security filter.
 *
 * These cover the two rules that are easy to state and easy to get wrong:
 *   - changing a password requires the CURRENT one, even though the caller is
 *     already authenticated (US-21)
 *   - an account issued with a temporary password can do nothing but change it
 *     (US-22)
 *
 * Both are enforced in the service, so they hold for any caller. Testing them
 * through MockMvc rather than with mocks is deliberate: the point is that the
 * rule survives the whole stack, including the filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class AccountSelfServiceTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AuditLogRepository auditLog;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String name) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(name)
                .roles(name.equals("teacher") ? "TEACHER" : "STUDENT");
    }

    private static String changeBody(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("changing your own password (US-21)")
    class ChangingPassword {

        @Test
        @DisplayName("the current password is required, even though the session is valid")
        void currentPasswordIsRequired() throws Exception {
            // The caller IS authenticated. That is the whole point: a valid
            // session must not be enough on its own to seize the account.
            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("not-the-password", "A-good-new-password1"))
                            .with(as("student")).with(csrf()))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a new password shorter than 8 characters is refused with 400")
        void shortPasswordIsRefused() throws Exception {
            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "short"))
                            .with(as("student")).with(csrf()))
               .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a change without a CSRF token is refused")
        void csrfIsRequired() throws Exception {
            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "A-good-new-password1"))
                            .with(as("student")))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot change anybody's password")
        void anonymousIsRefused() throws Exception {
            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "A-good-new-password1"))
                            .with(csrf()))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a successful change stores a new hash and never echoes it")
        void successStoresHashAndLeaksNothing() throws Exception {
            /*
             * A THROWAWAY ACCOUNT, NOT THE SEEDED "teacher" - this used to
             * change the shared "teacher" account's password to
             * "Brand-new-password1" and never change it back, a real,
             * permanent write for the rest of that JVM's test run. Spring
             * caches the application context - and its embedded H2 database -
             * across every test class carrying the identical
             * @SpringBootTest/@AutoConfigureMockMvc/@ActiveProfiles("test")
             * trio, which every test class in this suite does, so the mutation
             * was never confined to this file. InputValidationApiTest logging
             * in as "teacher"/"password123" failed on GitHub's CI runner with
             * exactly that state left behind by whichever test class happened
             * to run first - the identical class of bug already fixed twice
             * the same day in AdminApiTest (see its own comments on
             * adminCanRename), just with a credential instead of a class name
             * as the shared mutable state.
             */
            AppUser account = users.save(new AppUser(
                    "self-service-probe", encoder.encode("password123"),
                    com.example.tracker.model.Role.TEACHER, false));
            String oldHash = account.getPasswordHash();

            String response = mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "Brand-new-password1"))
                            .with(as("self-service-probe")).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The regression that matters: no hash, and no password, in the body.
            assertThat(response).doesNotContain("passwordHash");
            assertThat(response).doesNotContain("Brand-new-password1");
            assertThat(response).doesNotContain("$2a$");

            AppUser after = users.findByUsername("self-service-probe").orElseThrow();
            assertThat(after.getPasswordHash()).isNotEqualTo(oldHash);
            // Stored hashed, never as typed - the whole reason BCrypt is here.
            assertThat(after.getPasswordHash()).isNotEqualTo("Brand-new-password1");
            assertThat(encoder.matches("Brand-new-password1", after.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("a successful change is written to the audit log")
        void successIsAudited() throws Exception {
            /*
             * Every admin-initiated password action (reset) is logged; a
             * self-service change - including the mandatory first-login one
             * every admin-created account must perform - was not. This is
             * the gap, not a duplicate of the test above: that one proves the
             * hash changed, this one proves there is a record of who changed
             * it and when.
             */
            users.save(new AppUser("audit-probe", encoder.encode("password123"),
                    com.example.tracker.model.Role.TEACHER, false));

            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "Another-new-password2"))
                            .with(as("audit-probe")).with(csrf()))
               .andExpect(status().isOk());

            boolean logged = auditLog.findAll().stream().anyMatch(entry ->
                    "AppUser".equals(entry.getEntityName())
                            && "audit-probe".equals(entry.getPerformedByUsername())
                            && entry.getSummary().contains("changed their own password"));
            assertThat(logged).isTrue();
        }
    }

    /*
     * US-23's "a teacher can create a student account" was superseded by the
     * Admin Management EPIC: creating a student is now ADMIN ONLY, with a
     * generated Student ID and a full profile, through
     * AdminService.createStudent - see AdminApiTest, which is where those
     * cases now live. POST /api/users no longer exists.
     */

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("an account pending a password change (US-22)")
    class PendingAccount {

        /** An account in the state a teacher-created one starts in. */
        private AppUser givenPendingAccount(String name) {
            return users.save(new AppUser(
                    name, encoder.encode("temp-password-1"),
                    com.example.tracker.model.Role.STUDENT, true));
        }

        @Test
        @DisplayName("cannot read the assignment list")
        void cannotListAssignments() throws Exception {
            givenPendingAccount("pending1");

            mvc.perform(get("/api/assignments").with(as("pending1")))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("cannot hand work in")
        void cannotSubmit() throws Exception {
            givenPendingAccount("pending2");

            /*
             * 403 rather than 404, even though submission 1 may not belong to
             * this account - and the ORDER of the two guards is why. The pending
             * check runs before anything is looked up, so a pending account is
             * told its own status rather than being sent to work out that the
             * row is missing. Getting this backwards would leak whether a
             * submission exists to an account that is not allowed to ask.
             */
            mvc.perform(put("/api/submissions/1/submit")
                            .with(as("pending2")).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("can still change its own password, which is the way out")
        void canChangeItsOwnPassword() throws Exception {
            givenPendingAccount("pending3");

            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("temp-password-1", "Chosen-by-the-owner1"))
                            .with(as("pending3")).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.mustChangePassword", is(false)));

            // And the account is usable afterwards - the flag really cleared,
            // rather than the response merely saying so.
            mvc.perform(get("/api/assignments").with(as("pending3")))
               .andExpect(status().isOk());
        }
    }
}
