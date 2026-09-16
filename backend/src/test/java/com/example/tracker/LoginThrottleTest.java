package com.example.tracker;

import com.example.tracker.repository.LoginAttemptRepository;
import com.example.tracker.service.LoginThrottleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BRUTE-FORCE PROTECTION ON SIGN-IN.
 *
 * SMALL LIMITS, SET HERE RATHER THAN INHERITED. @TestPropertySource gives this
 * class its own three-and-six instead of the suite-wide numbers in
 * application-test.properties, which are deliberately enormous so that the
 * other test classes' failed sign-ins cannot throttle each other. Two
 * consequences worth knowing: this class gets its own application context (a
 * different property set is a different context), and therefore its own H2
 * database, and these tests do not depend on the production defaults - they
 * test the RULE, not the numbers.
 *
 * The login_attempt table is emptied before each test. Without that, every
 * failure in this class would accumulate in one shared per-IP bucket, because
 * MockMvc gives every request the same remote address - the very property the
 * per-IP test below relies on.
 *
 * THE TEST THAT MATTERS MOST is enumerationIsNotPossible. The throttle is the
 * one place in this system that answers differently depending on what has been
 * tried, and it is safe only because a failure is counted for ANY username. If
 * that ever changes to count only real accounts, the difference between 429
 * and 401 becomes a reliable way to discover which sign-in ids exist, undoing
 * from a new direction exactly what PasswordResetApiTest protects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "tracker.login.window-minutes=15",
        "tracker.login.max-per-username=3",
        "tracker.login.max-per-ip=6",
        "tracker.login.retain-hours=24"
})
@SuppressWarnings("null")   // Spring's post-processors are not null-annotated
class LoginThrottleTest {

    @Autowired private MockMvc mvc;
    @Autowired private LoginAttemptRepository attempts;
    @Autowired private LoginThrottleService throttle;

    private static final String GOOD_PASSWORD = "password123";
    private static final String BAD_PASSWORD = "not-the-password";

    @BeforeEach
    void forgetEveryEarlierAttempt() {
        attempts.deleteAll();
    }

    private ResultActions signIn(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    @Nested
    @DisplayName("the per-username limit")
    class PerUsername {

        @Test
        @DisplayName("attempts below the limit are still answered 401, not 429")
        void belowTheLimitIsStillUnauthorised() throws Exception {
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the attempt after the limit is refused with 429")
        void overTheLimitIsRefused() throws Exception {
            for (int i = 0; i < 3; i++) {
                signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            }
            signIn("student", BAD_PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.message").value(
                            "Too many sign-in attempts. Please wait a few minutes and try again."));
        }

        /**
         * The whole point of checking before authenticating. If the correct
         * password still worked once the limit was reached, an attacker would
         * simply keep going and the limit would be decoration.
         */
        @Test
        @DisplayName("once throttled, even the CORRECT password is refused")
        void onceThrottledTheRightPasswordIsRefusedToo() throws Exception {
            for (int i = 0; i < 3; i++) {
                signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            }
            signIn("student", GOOD_PASSWORD).andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("a successful sign-in forgets that username's failures")
        void successClearsTheCount() throws Exception {
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());

            signIn("student", GOOD_PASSWORD).andExpect(status().isOk());
            assertThat(attempts.count())
                    .as("a correct sign-in should have cleared the two failures before it")
                    .isZero();

            // Two more failures must therefore still be answered normally.
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("one username being throttled does not throttle another")
        void throttlingIsPerUsername() throws Exception {
            for (int i = 0; i < 3; i++) {
                signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            }
            signIn("student", BAD_PASSWORD).andExpect(status().isTooManyRequests());

            // A different account is unaffected, and signs in normally.
            signIn("teacher", GOOD_PASSWORD).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("enumeration")
    class Enumeration {

        /**
         * A username that does not exist must reach 429 exactly as readily as
         * one that does, or the status code becomes an oracle.
         */
        @Test
        @DisplayName("an invented username is throttled the same as a real one")
        void enumerationIsNotPossible() throws Exception {
            for (int i = 0; i < 3; i++) {
                signIn("nobody-here-at-all", BAD_PASSWORD).andExpect(status().isUnauthorized());
            }
            signIn("nobody-here-at-all", BAD_PASSWORD).andExpect(status().isTooManyRequests());
        }

        /**
         * The refusal a real account gets and the refusal an invented one gets
         * must be the same bytes.
         *
         * Asserted by comparing the two messages to each other rather than by
         * hunting for forbidden substrings in the envelope. The first version
         * of this test did the latter and failed on the word it was not
         * looking for: the ISO timestamp happens to contain the digit the
         * assertion had banned as a possible attempt count. Comparing the two
         * answers directly tests the property that actually matters and cannot
         * trip over the parts of the body that are the same either way.
         */
        @Test
        @DisplayName("a real and an invented username are refused in identical words")
        void bothRefusalsReadTheSame() throws Exception {
            String real = throttleAndReadMessage("student");
            attempts.deleteAll();
            String invented = throttleAndReadMessage("nobody-here-at-all");

            assertThat(real).isEqualTo(invented);
        }

        @Test
        @DisplayName("the refusal names no account, no limit and no count")
        void theMessageLeaksNothing() throws Exception {
            String message = throttleAndReadMessage("student");

            assertThat(message).isEqualTo(
                    "Too many sign-in attempts. Please wait a few minutes and try again.");
            assertThat(message).doesNotContain("student");
            assertThat(message.toLowerCase()).doesNotContain("username");
            // No digits at all: an attempt count, a remaining count or the
            // length of the window would each have to be written as one.
            assertThat(message).doesNotMatch(".*[0-9].*");
        }

        /** Exhaust the per-username limit and return just the message field. */
        private String throttleAndReadMessage(String username) throws Exception {
            for (int i = 0; i < 3; i++) {
                signIn(username, BAD_PASSWORD).andExpect(status().isUnauthorized());
            }
            return com.jayway.jsonpath.JsonPath.read(
                    signIn(username, BAD_PASSWORD)
                            .andExpect(status().isTooManyRequests())
                            .andReturn().getResponse().getContentAsString(),
                    "$.message");
        }
    }

    @Nested
    @DisplayName("the per-IP limit")
    class PerAddress {

        /**
         * Spraying: one password against many accounts, where no single
         * username ever reaches its own limit of three. Six distinct
         * usernames, one failure each, is under the per-username limit
         * everywhere and over the per-IP limit of six.
         */
        @Test
        @DisplayName("many accounts tried from one address is refused even though no username is over")
        void sprayingIsCaughtByTheAddressLimit() throws Exception {
            for (int i = 0; i < 6; i++) {
                signIn("sprayed" + i, BAD_PASSWORD).andExpect(status().isUnauthorized());
            }

            signIn("sprayed-one-more", BAD_PASSWORD)
                    .andExpect(status().isTooManyRequests());
        }

        /**
         * The address limit is deliberately NOT cleared by a success - see
         * LoginThrottleService.clearFailures. Otherwise a sprayer keeps one
         * account they already own and resets the counter at will.
         */
        @Test
        @DisplayName("a successful sign-in does not clear the address history")
        void successDoesNotClearTheAddressCount() throws Exception {
            for (int i = 0; i < 5; i++) {
                signIn("sprayed" + i, BAD_PASSWORD).andExpect(status().isUnauthorized());
            }
            signIn("student", GOOD_PASSWORD).andExpect(status().isOk());

            assertThat(attempts.count())
                    .as("clearing succeeded for 'student' only - the five others must remain")
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("the purge drops attempts past the retention window and keeps recent ones")
        void purgeRemovesOnlyOldAttempts() {
            attempts.save(new com.example.tracker.model.LoginAttempt(
                    "old", "10.0.0.1", Instant.now().minus(48, ChronoUnit.HOURS)));
            attempts.save(new com.example.tracker.model.LoginAttempt(
                    "recent", "10.0.0.1", Instant.now().minus(1, ChronoUnit.HOURS)));

            int removed = throttle.purgeOld();

            assertThat(removed).isEqualTo(1);
            assertThat(attempts.count()).isEqualTo(1);
        }

        /**
         * A failure has to survive the 401 it belongs to. It is written in its
         * own REQUIRES_NEW transaction for exactly this reason, and if that
         * ever regressed the counter would sit at zero for ever - a throttle
         * that silently does nothing.
         */
        @Test
        @DisplayName("a failed attempt is actually persisted, not rolled back with the request")
        void theFailureIsCommitted() throws Exception {
            signIn("student", BAD_PASSWORD).andExpect(status().isUnauthorized());
            assertThat(attempts.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("an over-long username is rejected at the edge, before anything is stored")
        void overLongUsernameIsRejected() throws Exception {
            signIn("x".repeat(51), BAD_PASSWORD).andExpect(status().isBadRequest());
            assertThat(attempts.count())
                    .as("a 400 from bean validation happens before the controller body runs")
                    .isZero();
        }
    }
}
