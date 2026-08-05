package com.example.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SECURITY EPIC + INPUT VALIDATION FEATURE, verified through the real HTTP
 * stack rather than by hand in Postman.
 *
 * "Verify 400 Bad Request with structured error body in Postman" is a manual
 * step someone has to remember to repeat after every change. Turning it into a
 * test is the same argument US-20 already made for the rest of the API: a
 * guarantee nobody re-checks is a guarantee that quietly stops holding.
 *
 * TWO THINGS THIS CLASS DELIBERATELY DOES NOT DO
 * ------------------------------------------------
 * It does not assert wrong-credentials returns 400. The API returns 401, on
 * purpose: 401 means "who you say you are was not accepted", which is what
 * happened, while 400 means the REQUEST was malformed, which it was not - a
 * login attempt with a real-shaped body and a wrong password is a perfectly
 * well-formed request. Switching to 400 would also be a real regression: the
 * whole point of the identical message below is that a wrong username and a
 * wrong password are indistinguishable to the caller, which is what stops an
 * attacker using the response to enumerate valid accounts. 400 does not carry
 * that guarantee any more or less than 401 does, so there is no upside to
 * changing it, and a real downside to being wrong about the status code's
 * meaning in the API's own documentation.
 *
 * It does not test every DTO's every field - AssignmentApiTest already covers
 * assignment creation (blank/over-long title, no course chosen) in detail, and
 * AssessmentIntegrityTest covers the scoring rules at the service layer. This
 * class fills the gap those left: the endpoints that had no HTTP-level 400
 * coverage at all (subjects, classes, enrolment, courses, marks), plus the one
 * login test the whole suite was missing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // see AssignmentServiceTest for the reasoning
class InputValidationApiTest {

    @Autowired private MockMvc mvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String name) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(name)
                .roles(name.startsWith("teacher") ? "TEACHER" : "STUDENT");
    }

    /** The id of a course 'teacher' actually teaches, for tests that need a valid one. */
    private int aRealCourseId() throws Exception {
        String body = mvc.perform(get("/api/courses").with(user("teacher")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<Integer> ids = com.jayway.jsonpath.JsonPath.read(body, "$[*].id");
        org.assertj.core.api.Assertions.assertThat(ids).isNotEmpty();
        return ids.get(0);
    }

    // ===== EPIC: Security - a friendly, honest answer to wrong credentials =====

    @Nested
    @DisplayName("logging in with the wrong credentials")
    class Login {

        @Test
        @DisplayName("a wrong password is refused with 401 and a structured body")
        void wrongPasswordIsRefused() throws Exception {
            mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"teacher\",\"password\":\"not-the-password\"}"))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.timestamp").exists())
               .andExpect(jsonPath("$.status").value(401))
               .andExpect(jsonPath("$.error").value("Unauthorized"))
               .andExpect(jsonPath("$.message").value("Invalid username or password."))
               .andExpect(jsonPath("$.path").value("/api/auth/login"));
        }

        @Test
        @DisplayName("an unknown username gets the SAME 401 and the SAME message as a wrong password")
        void unknownUsernameIsIndistinguishableFromWrongPassword() throws Exception {
            // This is the actual security property the user story is protecting:
            // if these two responses ever differed, an attacker could use the
            // difference to find out which usernames exist without knowing any
            // password at all - turning one hard guess into two easy ones.
            MvcResult wrongPassword = mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"teacher\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized()).andReturn();

            MvcResult unknownUser = mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"nobody-by-this-name\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized()).andReturn();

            String wrongPasswordMessage = com.jayway.jsonpath.JsonPath.read(
                    wrongPassword.getResponse().getContentAsString(), "$.message");
            String unknownUserMessage = com.jayway.jsonpath.JsonPath.read(
                    unknownUser.getResponse().getContentAsString(), "$.message");

            org.assertj.core.api.Assertions.assertThat(unknownUserMessage)
                    .isEqualTo(wrongPasswordMessage);
        }

        @Test
        @DisplayName("a blank username or password is rejected at the edge, before authentication even runs")
        void blankCredentialsAreRejectedByValidation() throws Exception {
            mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"\",\"password\":\"\"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("Username")));
        }

        @Test
        @DisplayName("the right credentials still work - this suite is not just testing refusals")
        void correctCredentialsSucceed() throws Exception {
            mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"teacher\",\"password\":\"password123\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").value("teacher"))
               .andExpect(jsonPath("$.role").value("TEACHER"));
        }
    }

    // ===== FEAT: Input Validation - every DTO refuses bad data at the edge =====

    @Nested
    @DisplayName("subjects")
    class Subjects {

        @Test
        @DisplayName("a blank code or name is rejected with 400")
        void blankFieldsRejected() throws Exception {
            mvc.perform(post("/api/subjects").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"code\":\"   \",\"name\":\"Geography\"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("code")));

            mvc.perform(post("/api/subjects").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"code\":\"GEOG\",\"name\":\"   \"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("name")));
        }

        @Test
        @DisplayName("a code or name over the schema's length is rejected with 400, not truncated")
        void overLongFieldsRejected() throws Exception {
            // Mirrors subject.code NVARCHAR(20) and subject.name NVARCHAR(100).
            mvc.perform(post("/api/subjects").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"code\":\"" + "X".repeat(21) + "\",\"name\":\"Geography\"}"))
               .andExpect(status().isBadRequest());

            mvc.perform(post("/api/subjects").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"code\":\"GEOG\",\"name\":\"" + "X".repeat(101) + "\"}"))
               .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a student cannot add a subject")
        void studentCannotCreate() throws Exception {
            mvc.perform(post("/api/subjects").with(csrf()).with(user("student"))
                            .contentType("application/json")
                            .content("{\"code\":\"GEOG\",\"name\":\"Geography\"}"))
               .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("classes")
    class Classes {

        @Test
        @DisplayName("a blank class name is rejected with 400")
        void blankNameRejected() throws Exception {
            mvc.perform(post("/api/classes").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"name\":\"   \"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("name")));
        }

        @Test
        @DisplayName("a class name over 50 characters is rejected with 400")
        void overLongNameRejected() throws Exception {
            // Mirrors school_class.name NVARCHAR(50).
            mvc.perform(post("/api/classes").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"name\":\"" + "X".repeat(51) + "\"}"))
               .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("a blank username is rejected with 400")
        void blankUsernameRejected() throws Exception {
            // The path id does not need to name a real class: bean validation
            // on the body runs before the controller method - and therefore the
            // service and the id - is ever reached.
            mvc.perform(post("/api/classes/1/students").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"username\":\"   \"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("Username")));
        }

        @Test
        @DisplayName("a username over 50 characters is rejected with 400")
        void overLongUsernameRejected() throws Exception {
            // The fix this class exists to prove: EnrolRequest.username used to
            // carry @NotBlank with no @Size, the one username field in the
            // application without a length bound - inconsistent with
            // UserController's CreateUserRequest and with app_user.username
            // NVARCHAR(50) itself.
            mvc.perform(post("/api/classes/1/students").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"username\":\"" + "x".repeat(51) + "\"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("50 characters")));
        }
    }

    @Nested
    @DisplayName("courses")
    class Courses {

        @Test
        @DisplayName("creating a course without a subject or class is rejected with 400")
        void missingReferencesRejected() throws Exception {
            mvc.perform(post("/api/courses").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("subject")))
               .andExpect(jsonPath("$.message", containsString("class")));
        }
    }

    @Nested
    @DisplayName("marks")
    class Marks {

        @Test
        @DisplayName("recording a mark with missing fields is rejected with 400")
        void missingFieldsRejected() throws Exception {
            mvc.perform(post("/api/assessments").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("course")))
               .andExpect(jsonPath("$.message", containsString("student")))
               .andExpect(jsonPath("$.message", containsString("score")));
        }

        @Test
        @DisplayName("a negative score is rejected with 400 before it reaches the service")
        void negativeScoreRejected() throws Exception {
            int courseId = aRealCourseId();
            mvc.perform(post("/api/assessments").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"courseId\":" + courseId + ",\"studentUsername\":\"student\","
                                    + "\"name\":\"Validation probe\",\"score\":-5,\"maxScore\":20}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("negative")));
        }

        @Test
        @DisplayName("a maximum of zero is rejected with 400 - it would make every percentage a division by zero")
        void zeroMaximumRejected() throws Exception {
            int courseId = aRealCourseId();
            mvc.perform(post("/api/assessments").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"courseId\":" + courseId + ",\"studentUsername\":\"student\","
                                    + "\"name\":\"Validation probe\",\"score\":0,\"maxScore\":0}"))
               .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a score above the maximum is rejected with 400, naming both numbers")
        void scoreAboveMaximumRejected() throws Exception {
            // Unlike the checks above, THIS rule spans two fields, so bean
            // validation on either field alone cannot express it - it is
            // enforced in AssessmentService.validateMark, and by
            // ck_assessment_score_within_max in the schema underneath that.
            // Still a 400: the request is well-formed, the DATA is not.
            int courseId = aRealCourseId();
            mvc.perform(post("/api/assessments").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"courseId\":" + courseId + ",\"studentUsername\":\"student\","
                                    + "\"name\":\"Validation probe over-max\",\"score\":42,\"maxScore\":30}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("42")))
               .andExpect(jsonPath("$.message", containsString("30")));
        }

        @Test
        @DisplayName("an assessment name over 100 characters is rejected with 400")
        void overLongNameRejected() throws Exception {
            int courseId = aRealCourseId();
            mvc.perform(post("/api/assessments").with(csrf()).with(user("teacher"))
                            .contentType("application/json")
                            .content("{\"courseId\":" + courseId + ",\"studentUsername\":\"student\","
                                    + "\"name\":\"" + "x".repeat(101) + "\",\"score\":5,\"maxScore\":10}"))
               .andExpect(status().isBadRequest());
        }
    }

    // ===== Every 400 in this class shares the one error shape the frontend relies on =====

    @Test
    @DisplayName("a validation failure always carries the same structured body")
    void everyValidationFailureHasTheSameShape() throws Exception {
        mvc.perform(post("/api/classes").with(csrf()).with(user("teacher"))
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.timestamp").exists())
           .andExpect(jsonPath("$.status").value(400))
           .andExpect(jsonPath("$.error").value("Bad Request"))
           .andExpect(jsonPath("$.message").exists())
           .andExpect(jsonPath("$.path").value("/api/classes"));
    }
}
