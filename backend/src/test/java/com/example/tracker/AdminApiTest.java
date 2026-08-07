package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Role;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EPIC: Admin Management, end to end through the real security filter.
 *
 * ADMIN REUSES THE EXISTING SESSION-BASED LOGIN - there is no separate
 * "/api/admins/login" and no JWT. An admin signs in through the same
 * POST /api/auth/login every other role uses; the response's "role" field
 * says ADMIN, and every admin-only endpoint below checks that field itself,
 * the same way every other role check in this codebase is enforced in the
 * service layer rather than declaratively in SecurityConfig.
 *
 * "ASSIGN A TEACHER TO A STUDENT" IS BACKED BY Course, NOT A NEW TABLE - see
 * SchoolService.assignTeacherToStudent. The seeded timetable already gives
 * every existing course (Maths/10A, History/10A, Science/10A) a real
 * assignment (TrackerApplication.seedAssignments), which is exactly the
 * fixture needed to test the 409-on-unassign-with-dependents path for free,
 * without any extra setup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class AdminApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    private static RequestPostProcessor as(String username, Role role) {
        return user(username).roles(role.name());
    }

    private static RequestPostProcessor asAdmin() {
        return as("admin", Role.ADMIN);
    }

    private static RequestPostProcessor asTeacher() {
        return as("teacher", Role.TEACHER);
    }

    private static RequestPostProcessor asStudent() {
        return as("student", Role.STUDENT);
    }

    private Long idOf(String username) {
        return users.findByUsername(username).orElseThrow().getId();
    }

    // =============================================================================
    // ADMIN LOGIN REUSES /api/auth/login
    // =============================================================================

    @Nested
    @DisplayName("admin sign-in")
    class SignIn {

        @Test
        @DisplayName("the admin account signs in through the ordinary login endpoint, with role ADMIN")
        void adminSignsInThroughTheOrdinaryEndpoint() throws Exception {
            mvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"admin\",\"password\":\"password123\"}")
                            .with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username", is("admin")))
               .andExpect(jsonPath("$.role", is("ADMIN")));
        }
    }

    // =============================================================================
    // TEACHER ACCOUNTS - ADMIN ONLY
    // =============================================================================

    @Nested
    @DisplayName("managing teacher accounts")
    class TeacherAccounts {

        @Test
        @DisplayName("an admin can create a teacher account, pending its own password change")
        void adminCanCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"newteacher\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.username", is("newteacher")))
               .andExpect(jsonPath("$.mustChangePassword", is(true)));

            AppUser created = users.findByUsername("newteacher").orElseThrow();
            assertThat(created.getRole()).isEqualTo(Role.TEACHER);
        }

        @Test
        @DisplayName("a teacher cannot create another teacher account")
        void teacherCannotCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"sneaky\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());

            assertThat(users.findByUsername("sneaky")).isEmpty();
        }

        @Test
        @DisplayName("a student cannot create a teacher account")
        void studentCannotCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"sneaky2\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asStudent()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot create a teacher account")
        void anonymousCannotCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"sneaky3\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(csrf()))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a duplicate username is refused with 409")
        void duplicateTeacherUsernameIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"teacher\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("the admin panel's teacher list includes every seeded teacher")
        void listIncludesSeededTeachers() throws Exception {
            mvc.perform(get("/api/teachers").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[*].username", hasItems("teacher", "teacher2")));
        }

        @Test
        @DisplayName("a teacher cannot list the teacher accounts")
        void teacherCannotListTeachers() throws Exception {
            mvc.perform(get("/api/teachers").with(asTeacher()))
               .andExpect(status().isForbidden());
        }

        /**
         * Creates a throwaway teacher with no courses or assignments, so
         * rename/reset-password/delete tests never mutate a SEEDED account
         * that other tests in this shared context still depend on existing
         * under its original name.
         */
        private String givenAFreshTeacher(String username) throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"" + username
                                    + "\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());
            return username;
        }

        @Test
        @DisplayName("an admin can rename a teacher account")
        void adminCanRenameTeacher() throws Exception {
            Long id = idOf(givenAFreshTeacher("rename-me"));

            mvc.perform(put("/api/teachers/" + id)
                            .contentType("application/json")
                            .content("{\"username\":\"renamed-teacher\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username", is("renamed-teacher")));

            assertThat(users.findByUsername("renamed-teacher")).isPresent();
        }

        @Test
        @DisplayName("renaming to an existing username is refused with 409")
        void renamingToADuplicateIsRefused() throws Exception {
            Long id = idOf(givenAFreshTeacher("rename-collision"));

            mvc.perform(put("/api/teachers/" + id)
                            .contentType("application/json")
                            .content("{\"username\":\"teacher\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("an admin can reset a teacher's password, marking the account pending again")
        void adminCanResetTeacherPassword() throws Exception {
            String username = givenAFreshTeacher("reset-me");
            Long id = idOf(username);

            mvc.perform(put("/api/teachers/" + id + "/password")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"fresh-temp-password\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.mustChangePassword", is(true)));

            AppUser after = users.findByUsername(username).orElseThrow();
            assertThat(encoder.matches("fresh-temp-password", after.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("deleting a teacher who still teaches a course is refused with 409")
        void deletingATeacherWithCoursesIsRefused() throws Exception {
            mvc.perform(delete("/api/teachers/" + idOf("teacher2"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());

            assertThat(users.findByUsername("teacher2")).isPresent();
        }

        @Test
        @DisplayName("deleting a teacher with no courses or assignments succeeds")
        void deletingATeacherWithNothingAttachedSucceeds() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"throwaway\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(delete("/api/teachers/" + idOf("throwaway"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isNoContent());

            assertThat(users.findByUsername("throwaway")).isEmpty();
        }
    }

    // =============================================================================
    // THE TEACHER-TO-STUDENT RELATIONSHIP (Course, admin-managed)
    // =============================================================================

    @Nested
    @DisplayName("assigning and unassigning teachers to a student")
    class TeacherAssignment {

        @Test
        @DisplayName("an admin can list students, with their class")
        void listStudentsShowsClass() throws Exception {
            mvc.perform(get("/api/students").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[?(@.username == 'student')].className", contains("Grade 10A")));
        }

        @Test
        @DisplayName("a student's current teachers reflect the seeded timetable")
        void listTeachersForStudentReflectsSeededCourses() throws Exception {
            mvc.perform(get("/api/students/" + idOf("student") + "/teachers").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[*].subjectName", hasItems("Mathematics", "History")));
        }

        @Test
        @DisplayName("an admin can assign a teacher to a student for a subject that teacher does not already teach them")
        void adminCanAssignANewTeacher() throws Exception {
            Long studentId = idOf("student");
            Long teacherId = idOf("teacher2");   // teaches Science to 10A, not History
            Long historyId = requireSubjectId("HIST");

            mvc.perform(post("/api/students/" + studentId + "/teachers/" + teacherId)
                            .contentType("application/json")
                            .content("{\"subjectId\":" + historyId + "}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.teacherUsername", is("teacher2")))
               .andExpect(jsonPath("$.subjectName", is("History")));
        }

        @Test
        @DisplayName("assigning a teacher who already teaches that subject to that class is refused with 409")
        void assigningAnExistingRelationshipIsRefused() throws Exception {
            Long studentId = idOf("student");
            Long teacherId = idOf("teacher");   // already teaches Maths to 10A
            Long mathsId = requireSubjectId("MATH");

            mvc.perform(post("/api/students/" + studentId + "/teachers/" + teacherId)
                            .contentType("application/json")
                            .content("{\"subjectId\":" + mathsId + "}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a teacher cannot assign teachers to students - that is admin-only")
        void teacherCannotAssign() throws Exception {
            Long studentId = idOf("student");
            Long teacherId = idOf("teacher2");
            Long historyId = requireSubjectId("HIST");

            mvc.perform(post("/api/students/" + studentId + "/teachers/" + teacherId)
                            .contentType("application/json")
                            .content("{\"subjectId\":" + historyId + "}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("assigning a teacher to a student with no class yet is refused")
        void assigningToAnUnenrolledStudentIsRefused() throws Exception {
            AppUser loose = users.save(new AppUser(
                    "unenrolled", encoder.encode("temp-password-1"), Role.STUDENT, true));
            Long teacherId = idOf("teacher2");
            Long historyId = requireSubjectId("HIST");

            mvc.perform(post("/api/students/" + loose.getId() + "/teachers/" + teacherId)
                            .contentType("application/json")
                            .content("{\"subjectId\":" + historyId + "}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("unassigning a teacher who has already set work for the class is refused with 409")
        void unassigningWithDependentWorkIsRefused() throws Exception {
            Long studentId = idOf("student");
            Long teacherId = idOf("teacher");   // Maths/10A already has a seeded assignment
            Long mathsId = requireSubjectId("MATH");

            mvc.perform(delete("/api/students/" + studentId + "/teachers/" + teacherId)
                            .queryParam("subjectId", mathsId.toString())
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        /**
         * Uses (teacher, Science) rather than adminCanAssignANewTeacher's
         * (teacher2, History) - the seeded timetable leaves exactly those two
         * subject/teacher combinations free for Grade 10A, and this suite's
         * tests share one Spring context and one database, so two tests
         * claiming the SAME free slot would make whichever runs second fail
         * with "already teaches" instead of testing what it says it tests.
         */
        @Test
        @DisplayName("a freshly assigned teacher with no work yet can be unassigned")
        void unassigningAFreshRelationshipSucceeds() throws Exception {
            Long studentId = idOf("student");
            Long teacherId = idOf("teacher");
            Long scienceId = requireSubjectId("SCI");

            mvc.perform(post("/api/students/" + studentId + "/teachers/" + teacherId)
                            .contentType("application/json")
                            .content("{\"subjectId\":" + scienceId + "}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk());

            mvc.perform(delete("/api/students/" + studentId + "/teachers/" + teacherId)
                            .queryParam("subjectId", scienceId.toString())
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isNoContent());
        }

        private Long requireSubjectId(String code) throws Exception {
            String body = mvc.perform(get("/api/subjects").with(asStudent()))
                    .andReturn().getResponse().getContentAsString();
            // A tiny hand-rolled extraction rather than pulling in a JSON path
            // library here: the subjects list is small and stable, and this
            // keeps the test self-contained.
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            for (com.fasterxml.jackson.databind.JsonNode node : root) {
                if (node.get("code").asText().equals(code)) {
                    return node.get("id").asLong();
                }
            }
            throw new IllegalStateException("Seeded subject '" + code + "' not found.");
        }
    }

    // =============================================================================
    // THE AUDIT LOG - ADMIN ONLY, IMMUTABLE
    // =============================================================================

    @Nested
    @DisplayName("the audit log")
    class AuditLog {

        @Test
        @DisplayName("creating a teacher writes an audit entry an admin can find")
        void creatingATeacherIsAudited() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"audited-teacher\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/audit-logs")
                            .queryParam("entityName", "AppUser")
                            .queryParam("action", "CREATE")
                            .with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content[*].summary",
                       hasItem(containsString("audited-teacher"))));
        }

        @Test
        @DisplayName("a teacher cannot view the audit log")
        void teacherCannotViewAuditLog() throws Exception {
            mvc.perform(get("/api/audit-logs").with(asTeacher()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a student cannot view the audit log")
        void studentCannotViewAuditLog() throws Exception {
            mvc.perform(get("/api/audit-logs").with(asStudent()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot view the audit log")
        void anonymousCannotViewAuditLog() throws Exception {
            mvc.perform(get("/api/audit-logs"))
               .andExpect(status().isUnauthorized());
        }

        /**
         * Creates its own audit entry first rather than assuming another test
         * already left one behind - @Nested classes and their methods have no
         * guaranteed run order, and seeding (TrackerApplication's
         * CommandLineRunners) writes directly through the repositories,
         * bypassing AuditLogService entirely, so a fresh test run can
         * legitimately have an empty log until something using the service
         * layer writes to it.
         */
        @Test
        @DisplayName("the log is paginated")
        void theLogIsPaginated() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"username\":\"pagination-check\",\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/audit-logs").queryParam("size", "1").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content", hasSize(1)))
               .andExpect(jsonPath("$.page.size", is(1)));
        }
    }
}
