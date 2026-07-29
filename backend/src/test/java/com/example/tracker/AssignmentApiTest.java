package com.example.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack tests: real controller, real service, real repository, real H2.
 *
 * These are the checks that used to be a PowerShell script somebody had to
 * remember to run. Turning them into tests is the whole point of US-20 - a
 * guarantee nobody re-verifies is a guarantee that quietly stops holding.
 *
 * @WithMockUser puts an authenticated principal in the SecurityContext, which is
 * what AppUserService.currentUser() reads. The usernames match the accounts the
 * application seeds at startup.
 *
 * .with(csrf()) supplies a valid CSRF token. Without it every write would be
 * rejected - which is itself asserted below, because a protection nobody tests
 * is a protection that can be switched off by accident.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
/*
 * Null analysis is suppressed for the same reason as in AssignmentServiceTest:
 * Hamcrest matchers and Spring's csrf()/user() post-processors are not
 * null-annotated, so the analysis cannot prove values it can plainly see are
 * non-null. The suppression is scoped to test code and explained rather than
 * applied silently - see that class for the fuller note.
 */
@SuppressWarnings("null")
class AssignmentApiTest {

    @Autowired private MockMvc mvc;

    // ----- authentication ------------------------------------------------------

    @Test
    @DisplayName("an anonymous request is refused with 401")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/assignments"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a write without a CSRF token is refused")
    void writeWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/api/assignments")
                        .contentType("application/json")
                        .content("{\"title\":\"No token\"}")
                        .with(user("teacher")))
           .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String name) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(name)
                .roles(name.equals("teacher") ? "TEACHER" : "STUDENT");
    }

    // ----- the list ------------------------------------------------------------

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a teacher sees the list")
    void teacherSeesList() throws Exception {
        mvc.perform(get("/api/assignments"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("a student's list contains only their own assignments")
    void studentListIsScoped() throws Exception {
        mvc.perform(get("/api/assignments"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[*].ownerUsername", everyItem(is("student"))));
    }

    // ----- validation ----------------------------------------------------------

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a blank title is rejected with 400")
    void blankTitleRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"   \"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message", containsString("Title")));
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a missing title is rejected with 400")
    void missingTitleRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json").content("{}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("an over-long title is rejected with 400")
    void overLongTitleRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"" + "x".repeat(250) + "\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("an unknown id is reported as 404, and a non-numeric one as 400")
    void badIdsReported() throws Exception {
        mvc.perform(put("/api/assignments/999999/submit").with(csrf()))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message", containsString("999999")));

        mvc.perform(put("/api/assignments/abc/submit").with(csrf()))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("every error body carries the same shape")
    void errorBodyShapeIsConsistent() throws Exception {
        mvc.perform(put("/api/assignments/999999/submit").with(csrf()))
           .andExpect(jsonPath("$.timestamp").exists())
           .andExpect(jsonPath("$.status").value(404))
           .andExpect(jsonPath("$.error").value("Not Found"))
           .andExpect(jsonPath("$.message").exists())
           .andExpect(jsonPath("$.path").value("/api/assignments/999999/submit"));
    }

    // ----- role rules ----------------------------------------------------------

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("a student cannot create an assignment")
    void studentCannotCreate() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Not allowed\"}"))
           .andExpect(status().isForbidden());
    }

    // ----- the full lifecycle --------------------------------------------------

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("create, edit, submit, refuse a second submit, reopen, then delete")
    void fullLifecycle() throws Exception {
        MvcResult created = mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Lifecycle\",\"dueDate\":\"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn();

        int id = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id");

        mvc.perform(put("/api/assignments/" + id).with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Renamed\",\"dueDate\":\"2027-01-15\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.title").value("Renamed"));

        mvc.perform(put("/api/assignments/" + id + "/submit").with(csrf()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Submitting twice is a conflict, not a silent success.
        mvc.perform(put("/api/assignments/" + id + "/submit").with(csrf()))
           .andExpect(status().isConflict());

        // A submitted assignment must be reopened before it can be removed.
        mvc.perform(delete("/api/assignments/" + id).with(csrf()))
           .andExpect(status().isConflict());

        mvc.perform(put("/api/assignments/" + id + "/unsubmit").with(csrf()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mvc.perform(delete("/api/assignments/" + id).with(csrf()))
           .andExpect(status().isNoContent());

        mvc.perform(put("/api/assignments/" + id + "/submit").with(csrf()))
           .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("the password hash never appears in a response")
    void passwordHashIsNeverExposed() throws Exception {
        String body = mvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("passwordHash")
                .doesNotContain("$2a$");   // the BCrypt prefix
    }
}
