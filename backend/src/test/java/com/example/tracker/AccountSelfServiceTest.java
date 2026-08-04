package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.repository.AppUserRepository;
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
                            .content(changeBody("not-the-password", "a-good-new-password"))
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
                            .content(changeBody("password123", "a-good-new-password"))
                            .with(as("student")))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot change anybody's password")
        void anonymousIsRefused() throws Exception {
            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "a-good-new-password"))
                            .with(csrf()))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a successful change stores a new hash and never echoes it")
        void successStoresHashAndLeaksNothing() throws Exception {
            AppUser before = users.findByUsername("teacher").orElseThrow();
            String oldHash = before.getPasswordHash();

            String response = mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("password123", "brand-new-password"))
                            .with(as("teacher")).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The regression that matters: no hash, and no password, in the body.
            assertThat(response).doesNotContain("passwordHash");
            assertThat(response).doesNotContain("brand-new-password");
            assertThat(response).doesNotContain("$2a$");

            AppUser after = users.findByUsername("teacher").orElseThrow();
            assertThat(after.getPasswordHash()).isNotEqualTo(oldHash);
            // Stored hashed, never as typed - the whole reason BCrypt is here.
            assertThat(after.getPasswordHash()).isNotEqualTo("brand-new-password");
            assertThat(encoder.matches("brand-new-password", after.getPasswordHash())).isTrue();
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("creating a student account (US-23)")
    class CreatingAccounts {

        @Test
        @DisplayName("a teacher can create an account, and it starts pending a change")
        void teacherCanCreate() throws Exception {
            mvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content("{\"username\":\"newpupil\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(as("teacher")).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.username", is("newpupil")))
               .andExpect(jsonPath("$.role", is("STUDENT")))
               .andExpect(jsonPath("$.mustChangePassword", is(true)));

            assertThat(users.findByUsername("newpupil")).isPresent();
        }

        @Test
        @DisplayName("a student cannot create an account")
        void studentCannotCreate() throws Exception {
            mvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content("{\"username\":\"sneaky\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(as("student")).with(csrf()))
               .andExpect(status().isForbidden());

            assertThat(users.findByUsername("sneaky")).isEmpty();
        }

        @Test
        @DisplayName("a duplicate username is refused with 409")
        void duplicateIsRefused() throws Exception {
            mvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content("{\"username\":\"student\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(as("teacher")).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("the request has no role field, so no caller can ask for a TEACHER")
        void roleCannotBeRequested() throws Exception {
            // A role IS sent. It must be ignored rather than honoured - the DTO
            // has no such property, so there is nothing for it to bind to.
            mvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content("{\"username\":\"wannabe\",\"temporaryPassword\":"
                                    + "\"temp-password-1\",\"role\":\"TEACHER\"}")
                            .with(as("teacher")).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.role", is("STUDENT")));
        }
    }

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
        @DisplayName("cannot submit an assignment")
        void cannotSubmit() throws Exception {
            givenPendingAccount("pending2");

            mvc.perform(put("/api/assignments/1/submit")
                            .with(as("pending2")).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("can still change its own password, which is the way out")
        void canChangeItsOwnPassword() throws Exception {
            givenPendingAccount("pending3");

            mvc.perform(put("/api/auth/password")
                            .contentType("application/json")
                            .content(changeBody("temp-password-1", "chosen-by-the-owner"))
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
