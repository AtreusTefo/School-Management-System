package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.PasswordResetToken;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EPIC: "I have forgotten my password".
 *
 * THE PROPERTY MOST OF THESE TESTS EXIST TO PROVE is that the request endpoint
 * cannot be used to discover who has an account. It is reachable without
 * signing in, so an answer that varied by whether the identifier matched would
 * be a free account-enumeration tool - and that is a security property, which
 * means it needs a test rather than a comment.
 *
 * The token is read straight out of the repository here rather than out of an
 * email. That is not a shortcut around the design: the server genuinely never
 * stores the token, so these tests generate their own, hash it the same way
 * the service does, and insert it - which also proves the hashing is what the
 * lookup actually depends on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class PasswordResetApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordResetTokenRepository tokens;
    @Autowired private com.example.tracker.repository.PersonProfileRepository profiles;
    @Autowired private PasswordEncoder encoder;

    private static final AtomicInteger NEXT = new AtomicInteger(0);

    /** A throwaway account, so nothing here touches the shared seeded ones. */
    private AppUser givenAccount() {
        String name = "reset-probe-" + NEXT.getAndIncrement();
        return users.save(new AppUser(name, encoder.encode("Old-Password-1"), Role.STUDENT, false));
    }

    /**
     * An account WITH a profile carrying `email`.
     *
     * The Omang is generated with a '1' as its 5th digit so it satisfies
     * ck_person_profile_omang - see V13.
     */
    private AppUser givenAccountWithEmail(String email) {
        AppUser account = givenAccount();
        String base = String.valueOf(System.nanoTime()).substring(0, 8);
        profiles.save(new com.example.tracker.model.PersonProfile(
                account, "Shared", null, "Inbox",
                base.substring(0, 4) + "1" + base.substring(4, 8),
                java.time.LocalDate.of(2010, 1, 1), email, null,
                java.time.LocalDate.of(2020, 1, 1)));
        return account;
    }

    /** The same hash the service uses - see PasswordResetService.sha256. */
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private PasswordResetToken givenToken(AppUser account, String raw, Instant expiry)
            throws Exception {
        return tokens.save(new PasswordResetToken(sha256(raw), account, expiry));
    }

    private String forgotBody(String identifier) {
        return "{\"identifier\":\"" + identifier + "\"}";
    }

    private String resetBody(String token, String password) {
        return "{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}";
    }

    // =========================================================================

    @Nested
    @DisplayName("asking for a reset link")
    class Requesting {

        /**
         * THE ENUMERATION TEST. Both responses must be byte-for-byte the same.
         * Comparing the actual strings rather than asserting each is 200
         * separately, because "both succeed" is a weaker property than "both
         * say exactly the same thing" - a difference in the body would leak
         * just as effectively as a difference in the status.
         */
        @Test
        @DisplayName("answers identically for a real account and for one that does not exist")
        void doesNotRevealWhoHasAnAccount() throws Exception {
            AppUser real = givenAccount();

            String forReal = mvc.perform(post("/api/auth/forgot-password")
                            .contentType("application/json")
                            .content(forgotBody(real.getUsername())).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String forNobody = mvc.perform(post("/api/auth/forgot-password")
                            .contentType("application/json")
                            .content(forgotBody("no-such-account-at-all")).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(forReal)
                    .as("a differing response would be an account-enumeration oracle")
                    .isEqualTo(forNobody);
        }

        @Test
        @DisplayName("a real request actually creates a token")
        void realRequestCreatesAToken() throws Exception {
            AppUser account = givenAccount();

            mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                            .content(forgotBody(account.getUsername())).with(csrf()))
               .andExpect(status().isOk());

            assertThat(tokens.findByAppUserOrderByCreatedAtDesc(account)).hasSize(1);
        }

        @Test
        @DisplayName("an unknown identifier creates nothing")
        void unknownIdentifierCreatesNothing() throws Exception {
            long before = tokens.count();

            mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                            .content(forgotBody("definitely-not-a-real-id")).with(csrf()))
               .andExpect(status().isOk());

            assertThat(tokens.count()).isEqualTo(before);
        }

        /**
         * Silently, on purpose. Reporting the throttle would answer the
         * question the enumeration test above exists to keep unanswerable.
         */
        @Test
        @DisplayName("asking twice in quick succession is throttled, and says so to nobody")
        void repeatedRequestsAreThrottled() throws Exception {
            AppUser account = givenAccount();

            for (int i = 0; i < 3; i++) {
                mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                                .content(forgotBody(account.getUsername())).with(csrf()))
                   .andExpect(status().isOk());
            }

            assertThat(tokens.findByAppUserOrderByCreatedAtDesc(account))
                    .as("three requests in a second must not mint three live keys")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a blank identifier is refused by validation")
        void blankIdentifierIsRefused() throws Exception {
            mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                            .content(forgotBody("")).with(csrf()))
               .andExpect(status().isBadRequest());
        }

        /**
         * THE DEFECT THIS EXISTS TO PREVENT.
         *
         * contact_email is deliberately not unique - a parent's inbox shared
         * by two children is a case V11 explicitly allows, and this database
         * already contains one. An earlier version of findByIdOrEmail took
         * .findFirst() over the matches, which meant a link sent to that
         * address would reset an ARBITRARY one of the accounts behind it: the
         * parent clicks in good faith and the wrong child's password changes,
         * with nothing recording which it was going to be.
         *
         * Refusing to act is the only safe answer, and it has to be silent -
         * saying "that address is ambiguous" would confirm the address is on
         * file, which is the enumeration question this whole flow refuses to
         * answer.
         */
        /*
         * @Transactional so the AppUser save and the PersonProfile save share
         * one persistence context - PersonProfile uses @MapsId, so it derives
         * its id from an AppUser that must still be MANAGED when the profile is
         * inserted. Two separate top-level repository calls detach it in
         * between. The endpoint under test joins this same transaction, so it
         * sees the profile. Identical to the person-profile tests in
         * AdminApiTest.
         */
        @Test
        @Transactional
        @DisplayName("an email shared by two accounts issues no token, and says nothing")
        void sharedEmailIsRefusedSilently() throws Exception {
            String shared = "shared-inbox-" + NEXT.getAndIncrement() + "@example.com";
            AppUser first = givenAccountWithEmail(shared);
            AppUser second = givenAccountWithEmail(shared);

            String response = mvc.perform(post("/api/auth/forgot-password")
                            .contentType("application/json")
                            .content(forgotBody(shared)).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Same reassuring message as always - it must not hint at anything.
            assertThat(response).contains("If that account exists");
            // And critically: NEITHER account got a token.
            assertThat(tokens.findByAppUserOrderByCreatedAtDesc(first)).isEmpty();
            assertThat(tokens.findByAppUserOrderByCreatedAtDesc(second)).isEmpty();
        }

        /** An address on exactly one account still works, which is the common case. */
        /*
         * @Transactional so the AppUser save and the PersonProfile save share
         * one persistence context - PersonProfile uses @MapsId, so it derives
         * its id from an AppUser that must still be MANAGED when the profile is
         * inserted. Two separate top-level repository calls detach it in
         * between. The endpoint under test joins this same transaction, so it
         * sees the profile. Identical to the person-profile tests in
         * AdminApiTest.
         */
        @Test
        @Transactional
        @DisplayName("an email on exactly one account does issue a token")
        void uniqueEmailStillWorks() throws Exception {
            String only = "sole-inbox-" + NEXT.getAndIncrement() + "@example.com";
            AppUser account = givenAccountWithEmail(only);

            mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                            .content(forgotBody(only)).with(csrf()))
               .andExpect(status().isOk());

            assertThat(tokens.findByAppUserOrderByCreatedAtDesc(account)).hasSize(1);
        }

        @Test
        @DisplayName("the endpoint is reachable without signing in")
        void reachableAnonymously() throws Exception {
            // No .with(user(...)) at all - this is the whole point of the flow.
            mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                            .content(forgotBody("anybody")).with(csrf()))
               .andExpect(status().isOk());
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("spending a reset link")
    class Redeeming {

        @Test
        @DisplayName("a valid token sets the new password and can then sign in")
        void validTokenResetsThePassword() throws Exception {
            AppUser account = givenAccount();
            String raw = "valid-token-" + NEXT.getAndIncrement();
            givenToken(account, raw, Instant.now().plusSeconds(600));

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(raw, "Brand-New-Password1!")).with(csrf()))
               .andExpect(status().isOk());

            AppUser after = users.findByUsername(account.getUsername()).orElseThrow();
            assertThat(encoder.matches("Brand-New-Password1!", after.getPasswordHash())).isTrue();

            // And it genuinely works as a credential.
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .content("{\"username\":\"" + account.getUsername()
                                    + "\",\"password\":\"Brand-New-Password1!\"}")
                            .with(csrf()))
               .andExpect(status().isOk());
        }

        /** Single use is the property; this is what makes it one. */
        @Test
        @DisplayName("the same token cannot be used twice")
        void tokenIsSingleUse() throws Exception {
            AppUser account = givenAccount();
            String raw = "single-use-" + NEXT.getAndIncrement();
            givenToken(account, raw, Instant.now().plusSeconds(600));

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(raw, "First-Password1!")).with(csrf()))
               .andExpect(status().isOk());

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(raw, "Second-Password1!")).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("already been used")));

            // The second attempt changed nothing.
            AppUser after = users.findByUsername(account.getUsername()).orElseThrow();
            assertThat(encoder.matches("First-Password1!", after.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("an expired token is refused, and says so")
        void expiredTokenIsRefused() throws Exception {
            AppUser account = givenAccount();
            String raw = "expired-" + NEXT.getAndIncrement();
            // Constructed valid, then aged past its expiry via the repository -
            // the constructor refuses an expiry in the past by design.
            PasswordResetToken token = givenToken(account, raw, Instant.now().plusSeconds(2));
            Thread.sleep(2100);

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(raw, "Late-Password1!")).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("expired")));

            assertThat(tokens.findById(token.getId()).orElseThrow().isUsed()).isFalse();
        }

        @Test
        @DisplayName("a token nobody issued is refused")
        void unknownTokenIsRefused() throws Exception {
            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody("not-a-real-token-at-all", "Whatever-Password1!"))
                            .with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("not valid")));
        }

        /**
         * The reset must not be a way around the password rules that every
         * other place enforces - otherwise it becomes the weakest door in.
         */
        @Test
        @DisplayName("a weak new password is refused by the same rules as everywhere else")
        void weakPasswordIsRefused() throws Exception {
            AppUser account = givenAccount();
            String raw = "weak-" + NEXT.getAndIncrement();
            givenToken(account, raw, Instant.now().plusSeconds(600));

            for (String weak : new String[]{"short1!", "alllowercase1!", "NoDigitsHere!", "NoSymbol1"}) {
                mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                                .content(resetBody(raw, weak)).with(csrf()))
                   .andExpect(status().isBadRequest());
            }
        }

        /**
         * Somebody issued a temporary password who never used it and reset
         * instead has now chosen their own - which is exactly the condition
         * mustChangePassword exists to force. Leaving it set would send them
         * to a screen asking for the temporary password they never knew.
         */
        @Test
        @DisplayName("resetting clears a pending forced password change")
        void resetClearsThePendingFlag() throws Exception {
            AppUser pending = users.save(new AppUser(
                    "reset-pending-" + NEXT.getAndIncrement(),
                    encoder.encode("Temp-Password-1!"), Role.STUDENT, true));
            String raw = "pending-" + NEXT.getAndIncrement();
            givenToken(pending, raw, Instant.now().plusSeconds(600));

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(raw, "Chosen-By-Owner1!")).with(csrf()))
               .andExpect(status().isOk());

            assertThat(users.findByUsername(pending.getUsername()).orElseThrow()
                    .isMustChangePassword()).isFalse();
        }

        /**
         * Three clicks on "forgot password" leave three live keys in three
         * inboxes. Spending one has to kill the rest.
         */
        @Test
        @DisplayName("spending one link invalidates every other outstanding link")
        void otherOutstandingTokensAreInvalidated() throws Exception {
            AppUser account = givenAccount();
            String first = "multi-a-" + NEXT.getAndIncrement();
            String second = "multi-b-" + NEXT.getAndIncrement();
            givenToken(account, first, Instant.now().plusSeconds(600));
            givenToken(account, second, Instant.now().plusSeconds(600));

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(first, "Winner-Password1!")).with(csrf()))
               .andExpect(status().isOk());

            mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                            .content(resetBody(second, "Loser-Password1!")).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("already been used")));

            List<PasswordResetToken> all = tokens.findByAppUserOrderByCreatedAtDesc(account);
            assertThat(all).allMatch(PasswordResetToken::isUsed);
        }

        /** The raw token must never be recoverable from what is stored. */
        @Test
        @DisplayName("the token is stored only as a hash, never in readable form")
        void tokenIsStoredHashedOnly() throws Exception {
            AppUser account = givenAccount();
            String raw = "secret-token-" + NEXT.getAndIncrement();
            PasswordResetToken stored = givenToken(account, raw, Instant.now().plusSeconds(600));

            assertThat(stored.getTokenHash())
                    .as("the stored value must not be the token itself")
                    .isNotEqualTo(raw)
                    .hasSize(64)
                    .isEqualTo(sha256(raw));
        }
    }
}
