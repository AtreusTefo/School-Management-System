package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

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
 * "/api/admins/login" and no JWT, and there is no separate sign-in FORM
 * either: every role, including admin, signs in through the same
 * POST /api/auth/login. Every admin-only endpoint below checks the caller's
 * role itself, the same way every other role check in this codebase is
 * enforced in the service layer rather than declaratively in SecurityConfig.
 *
 * EVERY LOGIN ID IS GENERATED - a student, teacher or admin account made
 * through this API signs in with STU20260001 / TCH20260001 / ADM20260001,
 * never a chosen username. The seeded development accounts (teacher,
 * student, admin) predate that rule and keep their literal names - see
 * AppUser.externalId.
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

    /**
     * A minimal, valid profile JSON body - every field the admin forms
     * require, filled with fixed values. A test that needs a specific field
     * wrong (a blank first name, say) builds its own literal instead of
     * trying to edit this one apart. nationalId is the one argument, since
     * every test that creates more than one person needs a DISTINCT one -
     * uq_person_profile_national_id refuses a duplicate.
     */
    private String personJson(String nationalId) {
        return "{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                + "\"nationalId\":\"" + nationalId + "\",\"dateOfBirth\":\"2000-01-01\","
                + "\"temporaryPassword\":\"temp-password-1\"}";
    }

    private Long requireSubjectId(String code) throws Exception {
        String body = mvc.perform(get("/api/subjects").with(asStudent()))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = new ObjectMapper().readTree(body);
        for (JsonNode node : root) {
            if (node.get("code").asText().equals(code)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("Seeded subject '" + code + "' not found.");
    }

    private Long requireClassId(String name) throws Exception {
        String body = mvc.perform(get("/api/classes").with(asStudent()))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = new ObjectMapper().readTree(body);
        for (JsonNode node : root) {
            if (node.get("name").asText().equals(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("Seeded class '" + name + "' not found.");
    }

    // =============================================================================
    // ADMIN LOGIN REUSES /api/auth/login - THE SAME FORM AS EVERY OTHER ROLE
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
        @DisplayName("an admin can create a teacher account, with a generated id, pending its own password change")
        void adminCanCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("111111111"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.externalId", startsWith("TCH")))
               .andExpect(jsonPath("$.fullName", is("Kagiso Molefe")))
               .andExpect(jsonPath("$.mustChangePassword", is(true)));
        }

        @Test
        @DisplayName("the account signs in with the generated id, not a chosen name")
        void createdTeacherSignsInWithGeneratedId() throws Exception {
            String body = mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("111111112"))
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            String externalId = new ObjectMapper().readTree(body).get("externalId").asText();

            mvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"" + externalId + "\",\"password\":\"temp-password-1\"}")
                            .with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.role", is("TEACHER")))
               .andExpect(jsonPath("$.mustChangePassword", is(true)));
        }

        @Test
        @DisplayName("a teacher cannot create another teacher account")
        void teacherCannotCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("111111113"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a student cannot create a teacher account")
        void studentCannotCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("111111114"))
                            .with(asStudent()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot create a teacher account")
        void anonymousCannotCreateTeacher() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("111111115"))
                            .with(csrf()))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a duplicate national id is refused with 409")
        void duplicateNationalIdIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("222222222"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson("222222222"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a blank first name is refused with 400")
        void blankFirstNameIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"333333333\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest());
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

        private Long givenAFreshTeacher(String nationalId) throws Exception {
            String body = mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJson(nationalId))
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            return new ObjectMapper().readTree(body).get("id").asLong();
        }

        @Test
        @DisplayName("an admin can reset a teacher's password, marking the account pending again")
        void adminCanResetTeacherPassword() throws Exception {
            Long id = givenAFreshTeacher("444444444");

            mvc.perform(put("/api/teachers/" + id + "/password")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"fresh-temp-password\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.mustChangePassword", is(true)));

            AppUser after = users.findById(id).orElseThrow();
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
            Long id = givenAFreshTeacher("555555555");

            mvc.perform(delete("/api/teachers/" + id)
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isNoContent());

            assertThat(users.findById(id)).isEmpty();
        }

        @Test
        @DisplayName("an admin can create a teacher already teaching a subject to a class")
        void adminCanCreateTeacherWithInitialCourse() throws Exception {
            Long subjectId = requireSubjectId("HIST");
            Long classId = requireClassId("Grade 10B");

            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Boitumelo\",\"lastName\":\"Kgosi\","
                                    + "\"nationalId\":\"666666666\",\"dateOfBirth\":\"1985-05-05\","
                                    + "\"temporaryPassword\":\"temp-password-1\","
                                    + "\"initialCourses\":[{\"subjectId\":" + subjectId
                                    + ",\"classId\":" + classId + "}]}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/courses/all").with(asTeacher()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[?(@.teacherUsername == 'Boitumelo Kgosi' "
                       + "|| @.subjectCode == 'HIST' && @.className == 'Grade 10B')]").exists());
        }
    }

    // =============================================================================
    // STUDENT ACCOUNTS - ADMIN ONLY
    // =============================================================================

    @Nested
    @DisplayName("managing student accounts")
    class StudentAccounts {

        @Test
        @DisplayName("an admin can create a student account, with a generated id")
        void adminCanCreateStudent() throws Exception {
            mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content(personJson("777777777"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.externalId", startsWith("STU")))
               .andExpect(jsonPath("$.mustChangePassword", is(true)));
        }

        @Test
        @DisplayName("a teacher cannot create a student account - onboarding is admin-only")
        void teacherCannotCreateStudent() throws Exception {
            mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content(personJson("777777778"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin can create a student already enrolled in a class")
        void adminCanCreateStudentWithClass() throws Exception {
            Long classId = requireClassId("Grade 10A");

            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Naledi\",\"lastName\":\"Pule\","
                                    + "\"nationalId\":\"888888888\",\"dateOfBirth\":\"2010-03-03\","
                                    + "\"temporaryPassword\":\"temp-password-1\",\"classId\":" + classId + "}")
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(new ObjectMapper().readTree(body).get("className").asText())
                    .isEqualTo("Grade 10A");
        }
    }

    // =============================================================================
    // BULK IMPORT
    // =============================================================================

    @Nested
    @DisplayName("bulk import")
    class BulkImport {

        @Test
        @DisplayName("importing students from CSV reports one success per valid row")
        void importStudentsFromCsv() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\n"
                    + "Thabo,Seretse,900000001,2011-02-02\n"
                    + "Lesego,Mogae,900000002,2011-04-04\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/students/import").file(file).with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalRows", is(2)))
               .andExpect(jsonPath("$.succeeded", is(2)))
               .andExpect(jsonPath("$.failed", is(0)))
               .andExpect(jsonPath("$.rows[0].externalId", startsWith("STU")))
               .andExpect(jsonPath("$.rows[0].temporaryPassword", notNullValue()));
        }

        @Test
        @DisplayName("a bad row fails on its own without losing the good rows in the same file")
        void oneBadRowDoesNotSinkTheWholeImport() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\n"
                    + "Goodwill,Tau,900000003,2011-02-02\n"
                    + ",MissingFirstName,900000004,2011-04-04\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/students/import").file(file).with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalRows", is(2)))
               .andExpect(jsonPath("$.succeeded", is(1)))
               .andExpect(jsonPath("$.failed", is(1)))
               .andExpect(jsonPath("$.rows[1].success", is(false)))
               .andExpect(jsonPath("$.rows[1].message", notNullValue()));

            // The good row was actually persisted, not merely reported as a
            // success - and the bad row's partial writes (an AppUser with no
            // profile, had one been left behind) did not slip through either.
            String students = mvc.perform(get("/api/students").with(asAdmin()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(students).contains("900000003");
            assertThat(students).doesNotContain("900000004");
        }

        @Test
        @DisplayName("a teacher cannot bulk-import students")
        void teacherCannotImportStudents() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\nA,B,900000005,2011-01-01\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/students/import").file(file).with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("importing teachers from CSV reports one success per valid row")
        void importTeachersFromCsv() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\n"
                    + "Onalenna,Phiri,900000006,1988-06-06\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "teachers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/teachers/import").file(file).with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.succeeded", is(1)))
               .andExpect(jsonPath("$.rows[0].externalId", startsWith("TCH")));
        }
    }

    // =============================================================================
    // ADMIN ACCOUNTS - ADMIN ONLY
    // =============================================================================

    @Nested
    @DisplayName("managing admin accounts")
    class AdminAccounts {

        @Test
        @DisplayName("an admin can create another admin account, with a generated id and no profile")
        void adminCanCreateAdmin() throws Exception {
            mvc.perform(post("/api/admins")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.username", startsWith("ADM")))
               .andExpect(jsonPath("$.role", is("ADMIN")))
               .andExpect(jsonPath("$.mustChangePassword", is(true)));
        }

        @Test
        @DisplayName("a teacher cannot create an admin account")
        void teacherCannotCreateAdmin() throws Exception {
            mvc.perform(post("/api/admins")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"temp-password-1\"}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
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
                            .content(personJson("999999991"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/audit-logs")
                            .queryParam("entityName", "AppUser")
                            .queryParam("action", "CREATE")
                            .with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content[*].summary",
                       hasItem(containsString("Kagiso Molefe"))));
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
                            .content(personJson("999999992"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/audit-logs").queryParam("size", "1").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content", hasSize(1)))
               .andExpect(jsonPath("$.page.size", is(1)));
        }
    }
}
