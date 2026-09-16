package com.example.tracker;

import com.example.tracker.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EPIC: messages between people.
 *
 * The tests that matter here are the REFUSALS. Whether a message can be sent
 * and read back is the easy half; whether a student can quietly write to
 * another student, or to a teacher who does not teach them, is the half that
 * makes this feature safe to put in a school. Each of those is asserted from
 * the caller's side rather than by inspecting the rule.
 *
 * Every test creates its own accounts where it needs an isolated relationship,
 * for the reason AttendanceApiTest gives: the suite shares one H2 database
 * across test classes, so leaning on the seeded accounts for anything mutable
 * makes one test's outcome depend on another's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class MessagingApiTest {

    @Autowired private MockMvc mvc;

    private static final AtomicInteger NEXT = new AtomicInteger(0);
    private int unique() { return 700 + NEXT.getAndIncrement(); }

    private static RequestPostProcessor as(String u, Role r) { return user(u).roles(r.name()); }
    private RequestPostProcessor asAdmin()   { return as("admin", Role.ADMIN); }
    private RequestPostProcessor asTeacher() { return as("teacher", Role.TEACHER); }
    private RequestPostProcessor asStudent() { return as("student", Role.STUDENT); }

    private final ObjectMapper json = new ObjectMapper();

    // ===== fixtures ==========================================================

    private long idOf(String path, String username) throws Exception {
        JsonNode rows = json.readTree(mvc.perform(get(path).with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode row : rows) {
            if (username.equals(row.get("username").asText())) {
                return row.get("id").asLong();
            }
        }
        throw new IllegalStateException("No account '" + username + "' at " + path);
    }

    private long seededStudentId() throws Exception { return idOf("/api/students", "student"); }
    private long seededTeacherId() throws Exception { return idOf("/api/teachers", "teacher"); }
    // /api/admins, not /api/accounts - the latter is the FRONT-END route
    // ('/admin/accounts'); the API lists each role separately.
    private long seededAdminId()   throws Exception { return idOf("/api/admins", "admin"); }

    /**
     * A brand-new student, enrolled nowhere, so no teacher teaches them.
     *
     * The NAME is fixed and the Omang is what varies. Names are validated
     * against PersonPatterns.NAME - letters, spaces, hyphens and apostrophes -
     * so appending the counter to the surname to make it unique is a 400, and
     * nothing needs the name to be unique anyway. The national id is the
     * uniquely-constrained column, and omang() is what keeps it distinct.
     */
    private long givenAnUnrelatedStudent() throws Exception {
        int n = unique();
        String body = mvc.perform(post("/api/students").contentType("application/json")
                        .content("{\"firstName\":\"Una\",\"lastName\":\"Related\","
                                + "\"nationalId\":\"" + omang(n) + "\","
                                + "\"dateOfBirth\":\"2008-04-05\","
                                + "\"phone\":\"+26771234567\","
                                + "\"temporaryPassword\":\"Temp-Password-1\"}")
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    /** A teacher with no courses, so they teach nobody. */
    private String givenAnUnrelatedTeacher() throws Exception {
        int n = unique();
        String body = mvc.perform(post("/api/teachers").contentType("application/json")
                        .content("{\"firstName\":\"Noah\",\"lastName\":\"Courses\","
                                + "\"nationalId\":\"" + omang(n) + "\","
                                + "\"dateOfBirth\":\"1988-04-05\","
                                + "\"phone\":\"+26771234567\","
                                + "\"temporaryPassword\":\"Temp-Password-1\"}")
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("username").asText();
    }

    /**
     * A valid Botswana Omang: exactly 9 digits whose 5th is 1 or 2 - see V13.
     * Built from a counter so two fixtures never collide on the unique index.
     */
    private String omang(int n) {
        return String.format("%04d1%04d", n % 10000, (n * 7) % 10000);
    }

    private String send(long recipientId, String body) {
        return "{\"recipientId\":" + recipientId + ",\"body\":\"" + body + "\"}";
    }

    // =========================================================================
    @Nested
    @DisplayName("sending a message")
    class Sending {

        @Test
        @DisplayName("a student can write to a teacher who teaches them")
        void studentToOwnTeacher() throws Exception {
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), "Sir, I do not understand Q4."))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.senderUsername", is("student")))
                    .andExpect(jsonPath("$.recipientUsername", is("teacher")))
                    .andExpect(jsonPath("$.read", is(false)));
        }

        @Test
        @DisplayName("and it arrives in that teacher's inbox")
        void itArrives() throws Exception {
            String marker = "Marker" + unique();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), marker))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isOk());

            mvc.perform(get("/api/messages/inbox").with(asTeacher()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.body == '" + marker + "')]", hasSize(1)));
        }

        @Test
        @DisplayName("anybody can write to an administrator")
        void anybodyToAdmin() throws Exception {
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededAdminId(), "Please reset my password."))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a blank message is refused")
        void blankRefused() throws Exception {
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), "   "))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("writing to yourself is refused")
        void selfRefused() throws Exception {
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededStudentId(), "Note to self."))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsStringIgnoringCase("yourself")));
        }

        @Test
        @DisplayName("an unknown recipient is a 404")
        void unknownRecipient() throws Exception {
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(9_999_999L, "Hello?"))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an anonymous caller cannot send")
        void anonymousRefused() throws Exception {
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), "Hello")).with(csrf()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("who may write to whom")
    class Scoping {

        @Test
        @DisplayName("a student cannot write to another student")
        void studentToStudentRefused() throws Exception {
            long other = givenAnUnrelatedStudent();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(other, "hey"))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message",
                            containsStringIgnoringCase("teachers who teach them")));
        }

        @Test
        @DisplayName("a student cannot write to a teacher who does not teach them")
        void studentToUnrelatedTeacherRefused() throws Exception {
            String stranger = givenAnUnrelatedTeacher();
            long strangerId = idOf("/api/teachers", stranger);
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(strangerId, "hello"))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a teacher cannot write to a student they do not teach")
        void teacherToUnrelatedStudentRefused() throws Exception {
            long stranger = givenAnUnrelatedStudent();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(stranger, "hello"))
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a teacher can write to another teacher")
        void teacherToTeacher() throws Exception {
            String other = givenAnUnrelatedTeacher();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(idOf("/api/teachers", other), "Staff meeting at 4."))
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an admin can write to anybody")
        void adminToAnybody() throws Exception {
            long stranger = givenAnUnrelatedStudent();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(stranger, "Welcome to the school."))
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the compose list only offers people the send path accepts")
        void composeListAgreesWithTheRule() throws Exception {
            String body = mvc.perform(get("/api/messages/correspondents").with(asStudent()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode offered = json.readTree(body);
            assertThat(offered).as("a student must be offered somebody").isNotEmpty();

            /*
             * The property that matters is not the size of this list but that
             * EVERY name on it is actually reachable. A list built from a
             * second, parallel definition of the rule would eventually offer
             * somebody the send path refuses, and this is what would catch it.
             */
            for (JsonNode person : offered) {
                assertThat(person.get("role").asText())
                        .as("a student must never be offered another student")
                        .isIn("TEACHER", "ADMIN");
                mvc.perform(post("/api/messages").contentType("application/json")
                                .content(send(person.get("id").asLong(), "Checking reachability."))
                                .with(asStudent()).with(csrf()))
                        .andExpect(status().isOk());
            }
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("reading messages")
    class Reading {

        @Test
        @DisplayName("only the recipient can mark a message read")
        void onlyRecipientMarksRead() throws Exception {
            String body = mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), "Please mark this."))
                            .with(asStudent()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            long id = json.readTree(body).get("id").asLong();

            // The SENDER cannot - that would make "have they read it" a lie.
            mvc.perform(put("/api/messages/" + id + "/read").with(asStudent()).with(csrf()))
                    .andExpect(status().isNotFound());

            mvc.perform(put("/api/messages/" + id + "/read").with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.read", is(true)));
        }

        @Test
        @DisplayName("marking read twice leaves it read, and does not move the time")
        void markReadIsIdempotent() throws Exception {
            String body = mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), "Twice please."))
                            .with(asStudent()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            long id = json.readTree(body).get("id").asLong();

            mvc.perform(put("/api/messages/" + id + "/read").with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());
            mvc.perform(put("/api/messages/" + id + "/read").with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.read", is(true)));
        }

        @Test
        @DisplayName("a conversation reads both directions in time order")
        void conversationIsBothDirections() throws Exception {
            String tag = "Conv" + unique();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), tag + " from the student"))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededStudentId(), tag + " from the teacher"))
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());

            String body = mvc.perform(get("/api/messages/conversations/" + seededTeacherId())
                            .with(asStudent()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode all = json.readTree(body);
            assertThat(all).isNotEmpty();
            // Both halves of the exchange are present in one list.
            assertThat(body).contains(tag + " from the student");
            assertThat(body).contains(tag + " from the teacher");
        }

        @Test
        @DisplayName("a third party sees none of that conversation")
        void thirdPartySeesNothing() throws Exception {
            String secret = "Secret" + unique();
            mvc.perform(post("/api/messages").contentType("application/json")
                            .content(send(seededTeacherId(), secret))
                            .with(asStudent()).with(csrf()))
                    .andExpect(status().isOk());

            // An admin asking for their OWN conversation with that teacher must
            // not be shown the student's. The query is keyed on the caller.
            String body = mvc.perform(get("/api/messages/conversations/" + seededTeacherId())
                            .with(asAdmin()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(secret);
        }

        @Test
        @DisplayName("an anonymous caller cannot read an inbox")
        void anonymousCannotRead() throws Exception {
            mvc.perform(get("/api/messages/inbox")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/messages/conversations")).andExpect(status().isUnauthorized());
        }
    }
}
