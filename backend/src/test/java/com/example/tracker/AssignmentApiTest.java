package com.example.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

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
 *
 * TESTS CREATE THEIR OWN DATA WHERE THE RESULT DEPENDS ON IT. Every
 * @SpringBootTest class here shares one application context, and therefore one
 * H2 database, so a test that asserted "there are 3 assignments" would pass or
 * fail according to what another class had already done. Asserting on rows this
 * class made itself is what keeps the suite order-independent.
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

    /** The smallest thing that passes the magic-number check. */
    private static final byte[] TINY_PDF =
            "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    // ----- authentication ------------------------------------------------------

    @Test
    @DisplayName("an anonymous request is refused with 401")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/assignments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/submissions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a write without a CSRF token is refused")
    void writeWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/api/assignments")
                        .contentType("application/json")
                        .content("{\"title\":\"No token\",\"courseIds\":[1]}")
                        .with(user("teacher")))
           .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String name) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(name)
                .roles(name.startsWith("teacher") ? "TEACHER" : "STUDENT");
    }

    // ----- the lists -----------------------------------------------------------

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a teacher sees the assignment list")
    void teacherSeesList() throws Exception {
        mvc.perform(get("/api/assignments"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("a student's submissions contain only their own work")
    void studentSubmissionsAreScoped() throws Exception {
        mvc.perform(get("/api/submissions"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[*].studentUsername", everyItem(is("student"))));
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a teacher's marking queue covers only the courses they teach")
    void teacherQueueIsScopedToTheirCourses() throws Exception {
        // 'teacher' takes Maths and History; Science belongs to teacher2.
        mvc.perform(get("/api/submissions"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[*].teacherUsername", everyItem(is("teacher"))));
    }

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("a student is taught several subjects by several teachers")
    void studentSeesEveryCourseTheyAreTaught() throws Exception {
        // The requirement, asserted rather than assumed: student is in Grade 10A,
        // which takes Maths and History from 'teacher' and Science from 'teacher2'.
        mvc.perform(get("/api/courses"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[*].subjectName",
                   hasItems("Mathematics", "History", "Science")))
           .andExpect(jsonPath("$[*].teacherUsername",
                   hasItems("teacher", "teacher2")));
    }

    // ----- validation ----------------------------------------------------------

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a blank title is rejected with 400")
    void blankTitleRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"   \",\"courseIds\":[" + mathsTenA() + "]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message", containsString("Title")));
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("a missing title is rejected with 400")
    void missingTitleRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json").content("{\"courseIds\":[1]}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("an over-long title is rejected with 400")
    void overLongTitleRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"" + "x".repeat(250)
                                + "\",\"courseIds\":[" + mathsTenA() + "]}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("work set for no course at all is rejected with 400")
    void noCourseRejected() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Homework\",\"courseIds\":[]}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("an unknown id is reported as 404, and a non-numeric one as 400")
    void badIdsReported() throws Exception {
        mvc.perform(delete("/api/assignments/999999").with(csrf()))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message", containsString("999999")));

        mvc.perform(delete("/api/assignments/abc").with(csrf()))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("every error body carries the same shape")
    void errorBodyShapeIsConsistent() throws Exception {
        mvc.perform(delete("/api/assignments/999999").with(csrf()))
           .andExpect(jsonPath("$.timestamp").exists())
           .andExpect(jsonPath("$.status").value(404))
           .andExpect(jsonPath("$.error").value("Not Found"))
           .andExpect(jsonPath("$.message").exists())
           .andExpect(jsonPath("$.path").value("/api/assignments/999999"));
    }

    // ----- role rules ----------------------------------------------------------

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("a student cannot set work")
    void studentCannotCreate() throws Exception {
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Not allowed\",\"courseIds\":[1]}"))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("a student cannot create a subject or a class")
    void studentCannotChangeTheTimetable() throws Exception {
        mvc.perform(post("/api/subjects").with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"X\",\"name\":\"Sneaky\"}"))
           .andExpect(status().isForbidden());

        mvc.perform(post("/api/classes").with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"Sneaky class\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "teacher2", roles = "TEACHER")
    @DisplayName("a teacher cannot set work for a course somebody else teaches")
    void teacherCannotUseAnotherTeachersCourse() throws Exception {
        // teacher2 takes Science only; Maths for 10A belongs to 'teacher'.
        mvc.perform(post("/api/assignments").with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Not mine\",\"courseIds\":[" + mathsTenA() + "]}"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.message", containsString("do not teach")));
    }

    // ----- the full lifecycle --------------------------------------------------

    @Test
    @DisplayName("set work, upload a PDF, hand in, refuse a second hand-in, reopen, delete")
    void fullLifecycle() throws Exception {
        // 1. A teacher sets work for their own course.
        MvcResult created = mvc.perform(post("/api/assignments").with(csrf())
                        .with(user("teacher"))
                        .contentType("application/json")
                        .content("{\"title\":\"Lifecycle\",\"description\":\"Do it\","
                                + "\"dueDate\":\"2027-12-31\",\"courseIds\":["
                                + mathsTenA() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Lifecycle"))
                // Grade 10A has two students, so the fan-out reached both.
                .andExpect(jsonPath("$[0].studentCount").value(2))
                .andExpect(jsonPath("$[0].submittedCount").value(0))
                .andReturn();

        int assignmentId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$[0].id");

        // 2. Editing it.
        mvc.perform(put("/api/assignments/" + assignmentId).with(csrf())
                        .with(user("teacher"))
                        .contentType("application/json")
                        .content("{\"title\":\"Renamed\",\"dueDate\":\"2027-01-15\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.title").value("Renamed"));

        // 3. The student finds their own submission for it.
        MvcResult marking = mvc.perform(get("/api/assignments/" + assignmentId + "/submissions")
                        .with(user("teacher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn();

        int submissionId = firstId(marking.getResponse().getContentAsString(),
                "$[?(@.studentUsername == 'student')].id");

        // 4. Handing in without a file is refused - a claim with no evidence.
        mvc.perform(put("/api/submissions/" + submissionId + "/submit").with(csrf())
                        .with(user("student")))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message", containsString("Upload")));

        // 5. Upload the PDF.
        mvc.perform(multipart("/api/submissions/" + submissionId + "/file")
                        .file(new MockMultipartFile("file", "my work.pdf",
                                "application/pdf", TINY_PDF))
                        .with(csrf()).with(user("student")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.hasFile").value(true))
           .andExpect(jsonPath("$.fileName").value("my work.pdf"))
           .andExpect(jsonPath("$.fileSha256", hasLength(64)))
           .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 6. Now it can be handed in.
        mvc.perform(put("/api/submissions/" + submissionId + "/submit").with(csrf())
                        .with(user("student")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUBMITTED"))
           .andExpect(jsonPath("$.submittedAt").exists());

        // 7. Handing in twice is a conflict, not a silent success.
        mvc.perform(put("/api/submissions/" + submissionId + "/submit").with(csrf())
                        .with(user("student")))
           .andExpect(status().isConflict());

        // 8. The teacher downloads it to mark.
        mvc.perform(get("/api/submissions/" + submissionId + "/file").with(user("teacher")))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", containsString("attachment")))
           .andExpect(content().contentType("application/pdf"))
           .andExpect(content().bytes(TINY_PDF));

        // 9. Work that has been handed in cannot be deleted.
        mvc.perform(delete("/api/assignments/" + assignmentId).with(csrf())
                        .with(user("teacher")))
           .andExpect(status().isConflict());

        // 10. Reopen, then it can be.
        mvc.perform(put("/api/submissions/" + submissionId + "/unsubmit").with(csrf())
                        .with(user("teacher")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
           .andExpect(jsonPath("$.submittedAt").doesNotExist());

        mvc.perform(delete("/api/assignments/" + assignmentId).with(csrf())
                        .with(user("teacher")))
           .andExpect(status().isNoContent());
    }

    // ----- uploads -------------------------------------------------------------

    @Test
    @DisplayName("a file that is not a PDF is refused however it is labelled")
    void nonPdfIsRefused() throws Exception {
        int submissionId = aFreshSubmissionForStudent();

        // Declared as a PDF, named as a PDF, and not a PDF. The magic-number
        // check is the only one of the three that can tell.
        mvc.perform(multipart("/api/submissions/" + submissionId + "/file")
                        .file(new MockMultipartFile("file", "essay.pdf", "application/pdf",
                                "MZ this is an executable".getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()).with(user("student")))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message", containsString("not a PDF")));
    }

    @Test
    @DisplayName("an empty upload is refused")
    void emptyUploadIsRefused() throws Exception {
        int submissionId = aFreshSubmissionForStudent();

        mvc.perform(multipart("/api/submissions/" + submissionId + "/file")
                        .file(new MockMultipartFile("file", "empty.pdf",
                                "application/pdf", new byte[0]))
                        .with(csrf()).with(user("student")))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a student cannot upload to somebody else's submission")
    void cannotUploadToAnotherStudentsWork() throws Exception {
        int submissionId = aFreshSubmissionForStudent();

        // student2 is in the same class, so the row exists - but it is not
        // theirs, and "not found" leaks less than "forbidden".
        mvc.perform(multipart("/api/submissions/" + submissionId + "/file")
                        .file(new MockMultipartFile("file", "sneaky.pdf",
                                "application/pdf", TINY_PDF))
                        .with(csrf()).with(user("student2")))
           .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a teacher who does not teach the course cannot download the work")
    void foreignTeacherCannotDownload() throws Exception {
        int submissionId = aFreshSubmissionForStudent();

        mvc.perform(multipart("/api/submissions/" + submissionId + "/file")
                        .file(new MockMultipartFile("file", "work.pdf",
                                "application/pdf", TINY_PDF))
                        .with(csrf()).with(user("student")))
           .andExpect(status().isOk());

        // teacher2 is a teacher, but not of Maths for Grade 10A. "Teacher" is
        // not a licence to read every child's work in the school.
        mvc.perform(get("/api/submissions/" + submissionId + "/file").with(user("teacher2")))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "student", roles = "STUDENT")
    @DisplayName("downloading when nothing has been uploaded is 404, not an empty file")
    void downloadWithNoFileIsNotFound() throws Exception {
        int submissionId = aFreshSubmissionForStudent();

        mvc.perform(get("/api/submissions/" + submissionId + "/file").with(user("student")))
           .andExpect(status().isNotFound());
    }

    // ----- disclosure ----------------------------------------------------------

    @Test
    @WithMockUser(username = "teacher", roles = "TEACHER")
    @DisplayName("no response ever contains a password hash or file bytes")
    void nothingPrivateLeaks() throws Exception {
        String assignmentsBody = mvc.perform(get("/api/assignments"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String submissionsBody = mvc.perform(get("/api/submissions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(assignmentsBody + submissionsBody)
                .doesNotContain("passwordHash")
                .doesNotContain("$2a$")      // the BCrypt prefix
                .doesNotContain("content");  // the PDF bytes have no field to occupy
    }

    // ----- helpers -------------------------------------------------------------

    /**
     * The id of the Maths course for Grade 10A, which 'teacher' takes.
     *
     * A JsonPath FILTER always evaluates to a list, even when exactly one thing
     * matches - so the result is read as a List and indexed in Java. Writing
     * "...id[0]" inside the expression looks equivalent and is not: it returns
     * the array itself and fails later with a ClassCastException, some distance
     * from the line that caused it.
     */
    private int mathsTenA() throws Exception {
        String body = mvc.perform(get("/api/courses").with(user("teacher")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        return firstId(body, "$[?(@.subjectCode == 'MATH' && @.className == 'Grade 10A')].id");
    }

    /** Read a filtered id list and return its first entry, failing clearly if empty. */
    private int firstId(String json, String path) {
        java.util.List<Integer> ids = com.jayway.jsonpath.JsonPath.read(json, path);
        org.assertj.core.api.Assertions.assertThat(ids)
                .as("expected at least one match for %s", path)
                .isNotEmpty();
        return ids.get(0);
    }

    /**
     * Set a new piece of work and return the student's own submission id.
     *
     * Each upload test needs a submission with no file yet, and reusing one
     * would make the tests depend on each other's order.
     */
    private int aFreshSubmissionForStudent() throws Exception {
        MvcResult created = mvc.perform(post("/api/assignments").with(csrf())
                        .with(user("teacher"))
                        .contentType("application/json")
                        .content("{\"title\":\"Upload probe " + System.nanoTime()
                                + "\",\"courseIds\":[" + mathsTenA() + "]}"))
                .andExpect(status().isOk()).andReturn();

        int assignmentId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$[0].id");

        String marking = mvc.perform(get("/api/assignments/" + assignmentId + "/submissions")
                        .with(user("teacher")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        return firstId(marking, "$[?(@.studentUsername == 'student')].id");
    }
}
