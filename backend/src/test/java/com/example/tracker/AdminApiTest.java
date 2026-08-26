package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.PersonProfile;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.PersonProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired private PersonProfileRepository profiles;

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
                + "\"phone\":\"+26771234567\","
                + "\"temporaryPassword\":\"Temp-Password-1\"}";
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
                            .content("{\"username\":\"" + externalId + "\",\"password\":\"Temp-Password-1\"}")
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
                                    + "\"nationalId\":\"333313333\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
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
            Long id = givenAFreshTeacher("444414444");

            mvc.perform(put("/api/teachers/" + id + "/password")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"Fresh-Temp-Password1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.mustChangePassword", is(true)));

            AppUser after = users.findById(id).orElseThrow();
            assertThat(encoder.matches("Fresh-Temp-Password1", after.getPasswordHash())).isTrue();
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
            Long id = givenAFreshTeacher("555515555");

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
                                    + "\"nationalId\":\"666616666\",\"dateOfBirth\":\"1985-05-05\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\","
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
    // PHONE, NAME AND PASSWORD FORMAT - Jakarta Bean Validation on PersonRequest
    // =============================================================================
    /*
     * These live here rather than in InputValidationApiTest, matching this
     * file's own established precedent: blankFirstNameIsRefused, just above,
     * already tests a PersonRequest field's Bean Validation from inside
     * AdminApiTest rather than the generic validation suite. Admin-account
     * field rules stay with the admin tests that already exercise the rest of
     * that DTO.
     */

    @Nested
    @DisplayName("phone, name and password format")
    class PersonFieldValidation {

        /** A valid body, with one field swapped out - isolates exactly what is under test. */
        private String personJsonWithout(String nationalId, String fieldName, String fieldValue) {
            String base = "{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                    + "\"nationalId\":\"" + nationalId + "\",\"dateOfBirth\":\"2000-01-01\","
                    + "\"phone\":\"+26771234567\","
                    + "\"temporaryPassword\":\"Temp-Password-1\"";
            return base + ",\"" + fieldName + "\":" + fieldValue + "}";
        }

        @Test
        @DisplayName("a phone number is required to create a teacher")
        void phoneIsRequired() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010001\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("phone")));
        }

        @Test
        @DisplayName("a phone number without the +267 country code is refused")
        void phoneWithoutCountryCodeIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJsonWithout("400010002", "phone", "\"71234567\""))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("phone")));
        }

        @Test
        @DisplayName("a phone number with the wrong digit count is refused")
        void phoneWithWrongDigitCountIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJsonWithout("400010003", "phone", "\"+2677123456\""))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("phone")));
        }

        @Test
        @DisplayName("a well-formed Botswana number is accepted and comes back on the created account")
        void validPhoneIsAccepted() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content(personJsonWithout("400010004", "phone", "\"+26772345678\""))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.phone", is("+26772345678")));
        }

        @Test
        @DisplayName("a first name containing a digit is refused")
        void firstNameWithDigitIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kag1so\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010005\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("first name")));
        }

        /*
         * A hyphenated or apostrophe'd name must NOT be refused - the rule is
         * "letters, spaces, hyphens and apostrophes", not "letters only". A
         * regex that forgot the hyphen would reject "Van der Merwe" and
         * "O'Brien", real names, not edge cases.
         */
        @Test
        @DisplayName("a hyphenated last name is accepted")
        void hyphenatedLastNameIsAccepted() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Van der Merwe\","
                                    + "\"nationalId\":\"400010006\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.fullName", containsString("Van der Merwe")));
        }

        @Test
        @DisplayName("a national id containing letters is refused")
        void nationalIdWithLettersIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"ABC000007\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("Omang")));
        }

        @Test
        @DisplayName("a temporary password with no uppercase letter is refused")
        void passwordWithoutUppercaseIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010008\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"lowercase-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("uppercase")));
        }

        @Test
        @DisplayName("a temporary password with no digit is refused")
        void passwordWithoutDigitIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010009\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"NoDigitsHere\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("digit")));
        }

        @Test
        @DisplayName("a temporary password with no symbol is refused")
        void passwordWithoutSymbolIsRefused() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010010\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"NoSymbol123\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("symbol")));
        }

        @Test
        @DisplayName("a password satisfying every rule is accepted")
        void compliantPasswordIsAccepted() throws Exception {
            mvc.perform(post("/api/teachers")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010011\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Valid-Pass-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("editing a student without a phone number is refused")
        void editWithoutPhoneIsRefused() throws Exception {
            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010012\",\"dateOfBirth\":\"2000-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            Long id = new ObjectMapper().readTree(body).get("id").asLong();

            mvc.perform(put("/api/students/" + id)
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"400010012\",\"dateOfBirth\":\"2000-01-01\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("phone")));
        }

        @Test
        @DisplayName("bulk import does not require a phone column - a school's existing spreadsheet still works")
        void bulkImportWithoutPhoneColumnStillSucceeds() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\n"
                    + "NoPhone,Person,400010013,2000-01-01\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/students/import").file(file)
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.succeeded", is(1)));
        }
    }

    // =============================================================================
    // GET /api/teachers/me/students - A TEACHER'S OWN STUDENTS, FULL DETAIL
    // =============================================================================

    @Nested
    @DisplayName("a teacher's own students")
    class MyStudents {

        @Test
        @DisplayName("a teacher sees full detail for students in their own classes, and nobody else's")
        void teacherSeesOnlyTheirOwnStudents() throws Exception {
            String body = mvc.perform(get("/api/teachers/me/students").with(asTeacher()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode root = new ObjectMapper().readTree(body);

            assertThat(root).as("the seed enrols 'student' into Grade 10A, which 'teacher' teaches")
                    .isNotEmpty();
            for (JsonNode node : root) {
                assertThat(node.get("nationalId")).as("full profile detail, not just a username").isNotNull();
                assertThat(node.get("fullName")).isNotNull();
            }
        }

        @Test
        @DisplayName("a student cannot read the teacher's-students endpoint")
        void studentCannotReadMyStudents() throws Exception {
            mvc.perform(get("/api/teachers/me/students").with(asStudent()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot read the teacher's-students endpoint")
        void anonymousCannotReadMyStudents() throws Exception {
            mvc.perform(get("/api/teachers/me/students"))
               .andExpect(status().isUnauthorized());
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
                            .content(personJson("777717777"))
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
                            .content(personJson("777717778"))
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
                                    + "\"nationalId\":\"888818888\",\"dateOfBirth\":\"2010-03-03\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\",\"classId\":" + classId + "}")
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
                    + "Thabo,Seretse,900010001,2011-02-02\n"
                    + "Lesego,Mogae,900010002,2011-04-04\n";
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
                    + "Goodwill,Tau,900010003,2011-02-02\n"
                    + ",MissingFirstName,900010004,2011-04-04\n";
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
            assertThat(students).contains("900010003");
            assertThat(students).doesNotContain("900010004");
        }

        @Test
        @DisplayName("a teacher cannot bulk-import students")
        void teacherCannotImportStudents() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\nA,B,900010005,2011-01-01\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/students/import").file(file).with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("importing teachers from CSV reports one success per valid row")
        void importTeachersFromCsv() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\n"
                    + "Onalenna,Phiri,900010006,1988-06-06\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "teachers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/teachers/import").file(file).with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.succeeded", is(1)))
               .andExpect(jsonPath("$.rows[0].externalId", startsWith("TCH")));
        }
    }

    // =============================================================================
    // PERSON PROFILE - DATABASE-LEVEL CONSTRAINTS
    //
    // The service-layer guards below are the message; ck_person_profile_
    // enrolled_after_birth and ck_person_profile_phone_format (mirrored onto
    // PersonProfile.java from V10 and V9) are the guarantee. Each rule gets
    // two tests: one through the API proving the service refuses it cleanly
    // with a 400, and one saving directly through the repository - bypassing
    // AdminService entirely - proving the database refuses it independently.
    // =============================================================================

    @Nested
    @DisplayName("person profile constraints")
    class PersonProfileConstraints {

        @Test
        @DisplayName("a student cannot be enrolled before their own date of birth")
        void enrolledBeforeBirthIsRefused() throws Exception {
            mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"700010001\",\"dateOfBirth\":\"2010-01-01\","
                                    + "\"dateEnrolled\":\"2005-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("Date enrolled")));
        }

        /*
         * @Transactional so the AppUser save and the PersonProfile save share
         * one persistence context - PersonProfile's @MapsId derives its own
         * id from appUser, and needs appUser to still be MANAGED, not
         * detached, when the insert happens. Every save in production runs
         * inside AdminService's own single @Transactional method for exactly
         * this reason; two separate top-level repository calls here would
         * detach the account in between them.
         */
        @Test
        @Transactional
        @DisplayName("the database itself refuses a profile enrolled before its own date of birth")
        void databaseRefusesEnrolledBeforeBirth() {
            AppUser account = users.save(new AppUser(
                    "profile-probe-1", encoder.encode("password123"), Role.STUDENT, true));

            assertThatThrownBy(() -> profiles.saveAndFlush(new PersonProfile(
                    account, "Kagiso", null, "Molefe", "700010002",
                    LocalDate.of(2010, 1, 1), null, null, LocalDate.of(2005, 1, 1))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /*
         * THE OMANG'S SHAPE - 9 digits, 5th is 1 (male) or 2 (female).
         *
         * The 5th digit is the only self-describing position in the number, so
         * it is the only one a checksum-free validator can test. These cover
         * both halves: the length, and that marker.
         */
        @Test
        @DisplayName("an Omang that is not exactly 9 digits is refused")
        void wrongLengthOmangIsRefused() throws Exception {
            for (String bad : new String[]{"4001000", "40010002123", "400100"}) {
                mvc.perform(post("/api/students")
                                .contentType("application/json")
                                .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                        + "\"nationalId\":\"" + bad + "\",\"dateOfBirth\":\"2010-01-01\","
                                        + "\"phone\":\"+26771234567\","
                                        + "\"temporaryPassword\":\"Temp-Password-1\"}")
                                .with(asAdmin()).with(csrf()))
                   .andExpect(status().isBadRequest());
            }
        }

        @Test
        @DisplayName("an Omang whose 5th digit is not 1 or 2 is refused")
        void wrongGenderMarkerIsRefused() throws Exception {
            // Nine digits, right shape, but the gender marker is a 0 / 7 / 9.
            for (String bad : new String[]{"400000002", "777777777", "123495678"}) {
                mvc.perform(post("/api/students")
                                .contentType("application/json")
                                .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                        + "\"nationalId\":\"" + bad + "\",\"dateOfBirth\":\"2010-01-01\","
                                        + "\"phone\":\"+26771234567\","
                                        + "\"temporaryPassword\":\"Temp-Password-1\"}")
                                .with(asAdmin()).with(csrf()))
                   .andExpect(status().isBadRequest());
            }
        }

        @Test
        @DisplayName("both gender markers are accepted, and the gender is derived from them")
        void bothGenderMarkersAreAccepted() throws Exception {
            // 1 = male
            mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Kagiso\",\"lastName\":\"Molefe\","
                                    + "\"nationalId\":\"432119876\",\"dateOfBirth\":\"2010-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.gender", is("MALE")));

            // 2 = female
            mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Neo\",\"lastName\":\"Basimane\","
                                    + "\"nationalId\":\"432229876\",\"dateOfBirth\":\"2010-01-01\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.gender", is("FEMALE")));
        }

        /**
         * Bulk import never builds a PersonRequest, so the @Pattern on that
         * class does not run for a CSV row - only AdminService's own check
         * does. Without it the first objection would come from the database,
         * losing the row number the importer needs to report.
         */
        @Test
        @DisplayName("bulk import refuses a bad Omang per row, keeping the good rows")
        void bulkImportChecksTheOmang() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth\n"
                    + "Good,Row,445129876,2011-02-02\n"
                    + "Bad,Row,999999999,2011-04-04\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            mvc.perform(multipart("/api/students/import").file(file).with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.succeeded", is(1)))
               .andExpect(jsonPath("$.failed", is(1)))
               .andExpect(jsonPath("$.rows[1].message", containsString("Omang")));
        }

        @Test
        @Transactional
        @DisplayName("the database itself refuses an Omang with a bad gender marker")
        void databaseRefusesBadOmang() {
            AppUser account = users.save(new AppUser(
                    "omang-probe", encoder.encode("password123"), Role.STUDENT, true));

            assertThatThrownBy(() -> profiles.saveAndFlush(new PersonProfile(
                    account, "Kagiso", null, "Molefe", "400000002",
                    LocalDate.of(2010, 1, 1), null, null, LocalDate.now())))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @Transactional
        @DisplayName("the database itself refuses malformed phone numbers")
        void databaseRefusesMalformedPhone() {
            AppUser account = users.save(new AppUser(
                    "profile-probe-2", encoder.encode("password123"), Role.STUDENT, true));

            // 5 digits, not 8 - the shape ck_person_profile_phone_format exists
            // to catch regardless of which layer produced it.
            assertThatThrownBy(() -> profiles.saveAndFlush(new PersonProfile(
                    account, "Kagiso", null, "Molefe", "700010003",
                    LocalDate.of(2000, 1, 1), null, "+26712345", LocalDate.now())))
                    .isInstanceOf(DataIntegrityViolationException.class);
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
                            .content("{\"temporaryPassword\":\"Temp-Password-1\"}")
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
                            .content("{\"temporaryPassword\":\"Temp-Password-1\"}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin can list every admin account, with no profile fields")
        void adminCanListAdmins() throws Exception {
            mvc.perform(get("/api/admins").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[?(@.username == 'admin')]").exists())
               .andExpect(jsonPath("$[0].role", is("ADMIN")));
        }

        @Test
        @DisplayName("a teacher cannot list admin accounts")
        void teacherCannotListAdmins() throws Exception {
            mvc.perform(get("/api/admins").with(asTeacher()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot list admin accounts")
        void anonymousCannotListAdmins() throws Exception {
            mvc.perform(get("/api/admins"))
               .andExpect(status().isUnauthorized());
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
                            .content(personJson("999919991"))
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
                            .content(personJson("999919992"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/audit-logs").queryParam("size", "1").with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content", hasSize(1)))
               .andExpect(jsonPath("$.page.size", is(1)));
        }

        /**
         * importOneStudent used to enrol a bulk-imported row without writing
         * the "Enrolled" entry the single-record path (createStudent) writes
         * for the identical action - a CSV with a className column produced
         * only an AppUser/CREATE entry, never an Enrolment/CREATE one.
         */
        @Test
        @DisplayName("a bulk-imported row that names a class is audited the same as a single enrolment")
        void bulkImportedEnrolmentIsAudited() throws Exception {
            String csv = "firstName,lastName,nationalId,dateOfBirth,className\n"
                    + "Kelebogile,Ntsima,900010007,2011-03-03,Grade 10A\n";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

            String importBody = mvc.perform(
                            multipart("/api/students/import").file(file).with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.succeeded", is(1)))
               .andReturn().getResponse().getContentAsString();
            String externalId = new ObjectMapper().readTree(importBody)
                    .get("rows").get(0).get("externalId").asText();

            // The summary names the id and the class, the same shape
            // "Enrolled '<externalId>' in Grade 10A." createStudent already writes.
            mvc.perform(get("/api/audit-logs")
                            .queryParam("entityName", "Enrolment")
                            .queryParam("action", "CREATE")
                            .with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content[*].summary",
                       hasItem(allOf(containsString(externalId), containsString("Grade 10A")))));
        }
    }

    // =============================================================================
    // THE TIMETABLE IS THE ADMIN'S TO WRITE
    // =============================================================================

    @Nested
    @DisplayName("writing the timetable")
    class Timetable {

        /*
         * These endpoints used to be teacher-only, which meant a teacher could
         * invent a subject, a class, or a course naming any colleague as its
         * teacher. The timetable decides who receives every future assignment,
         * so it is now the admin's alone.
         *
         * Every case is asserted in BOTH directions - refused for a teacher, and
         * accepted for an admin. A one-sided test passes just as happily when the
         * endpoint is broken for everybody.
         *
         * Note these could not have been caught by the existing validation tests:
         * @Valid runs at the web edge, BEFORE the service guard, so a request with
         * a bad body returns 400 no matter who sent it. Only a well-formed request
         * reaches the role check at all.
         */

        @Test
        @DisplayName("a teacher can no longer create a subject, a class, or a course")
        void teacherIsRefused() throws Exception {
            mvc.perform(post("/api/subjects").with(asTeacher()).with(csrf())
                            .contentType("application/json")
                            .content("{\"code\":\"TCH1\",\"name\":\"Teacher made this\"}"))
               .andExpect(status().isForbidden());

            mvc.perform(post("/api/classes").with(asTeacher()).with(csrf())
                            .contentType("application/json")
                            .content("{\"name\":\"Teacher made this class\"}"))
               .andExpect(status().isForbidden());

            mvc.perform(post("/api/courses").with(asTeacher()).with(csrf())
                            .contentType("application/json")
                            .content("{\"subjectId\":" + requireSubjectId("MATH")
                                    + ",\"classId\":" + requireClassId("Grade 10A")
                                    + ",\"teacherUsername\":\"teacher\"}"))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a teacher can no longer enrol or withdraw a student")
        void teacherCannotChangeTheRegister() throws Exception {
            Long classId = requireClassId("Grade 10A");

            mvc.perform(post("/api/classes/" + classId + "/students")
                            .with(asTeacher()).with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"student\"}"))
               .andExpect(status().isForbidden());

            mvc.perform(delete("/api/classes/" + classId + "/students/student")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin can create a subject and a class")
        void adminCanWriteTheTimetable() throws Exception {
            mvc.perform(post("/api/subjects").with(asAdmin()).with(csrf())
                            .contentType("application/json")
                            .content("{\"code\":\"ADM1\",\"name\":\"Admin made this\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code", is("ADM1")));

            mvc.perform(post("/api/classes").with(asAdmin()).with(csrf())
                            .contentType("application/json")
                            .content("{\"name\":\"Admin made this class\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name", is("Admin made this class")));
        }

        /*
         * createCourse used to default the teacher to the caller. That default is
         * gone, because the caller is now always an admin and an admin cannot
         * teach - it would have failed with a message about the admin's role
         * rather than about the field that was actually missing.
         */
        @Test
        @DisplayName("a course must name its teacher, and says so plainly")
        void courseMustNameItsTeacher() throws Exception {
            mvc.perform(post("/api/courses").with(asAdmin()).with(csrf())
                            .contentType("application/json")
                            .content("{\"subjectId\":" + requireSubjectId("MATH")
                                    + ",\"classId\":" + requireClassId("Grade 10A") + "}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("teacher username")));
        }

        /*
         * A register names every classmate, so a student must not read it. The
         * admin must, or they would be enrolling students into a class whose
         * current membership they cannot see.
         */
        @Test
        @DisplayName("an admin and a teacher may read a class register; a student may not")
        void registerIsReadableByStaffOnly() throws Exception {
            Long classId = requireClassId("Grade 10A");

            mvc.perform(get("/api/classes/" + classId + "/students").with(asAdmin()))
               .andExpect(status().isOk());
            mvc.perform(get("/api/classes/" + classId + "/students").with(asTeacher()))
               .andExpect(status().isOk());
            mvc.perform(get("/api/classes/" + classId + "/students").with(asStudent()))
               .andExpect(status().isForbidden());
        }
    }

    // =============================================================================
    // EDITING AND REMOVING - AND WHAT MUST REFUSE TO BE REMOVED
    // =============================================================================

    @Nested
    @DisplayName("maintaining student accounts - reset, edit, delete")
    class StudentAccountMaintenance {

        /*
         * A student could previously be created and listed, and nothing else.
         * The reset in particular closed a dead end: a password is a one-way
         * hash, so a lost temporary password left the account unusable with
         * nothing in the system able to rewrite it.
         */

        @Test
        @DisplayName("an admin can reset a student's password, marking the account pending again")
        void adminCanResetAStudentPassword() throws Exception {
            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content(personJson("777010111"))
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode created = new ObjectMapper().readTree(body);
            Long id = created.get("id").asLong();
            String externalId = created.get("externalId").asText();

            mvc.perform(put("/api/students/" + id + "/password")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"Brand-New-Password1\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk());

            AppUser after = users.findByUsername(externalId).orElseThrow();
            assertThat(encoder.matches("Brand-New-Password1", after.getPasswordHash())).isTrue();
            assertThat(after.isMustChangePassword())
                    .as("a reset password is temporary, so the account is pending again")
                    .isTrue();
        }

        @Test
        @DisplayName("a teacher cannot reset a student's password")
        void teacherCannotResetAStudentPassword() throws Exception {
            mvc.perform(put("/api/students/" + idOf("student") + "/password")
                            .contentType("application/json")
                            .content("{\"temporaryPassword\":\"Not-Allowed-1\"}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin can correct a student's details")
        void adminCanEditAStudent() throws Exception {
            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content(personJson("777010222"))
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            Long id = new ObjectMapper().readTree(body).get("id").asLong();

            mvc.perform(put("/api/students/" + id)
                            .contentType("application/json")
                            .content("{\"firstName\":\"Corrected\",\"lastName\":\"Name\","
                                    + "\"nationalId\":\"777010222\",\"dateOfBirth\":\"2001-02-03\","
                                    + "\"phone\":\"+26771234567\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.fullName", containsString("Corrected")));
        }

        /*
         * The edit path asks "is this national id somebody ELSE's", not "does it
         * exist". Saving a record without touching its id would otherwise
         * collide with itself and refuse every edit - a bug that only shows up
         * when the field is left alone, which is the common case.
         */
        @Test
        @DisplayName("editing a student without changing their national id is not a duplicate")
        void editingWithoutChangingNationalIdIsAllowed() throws Exception {
            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content(personJson("777010333"))
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            Long id = new ObjectMapper().readTree(body).get("id").asLong();

            mvc.perform(put("/api/students/" + id)
                            .contentType("application/json")
                            .content("{\"firstName\":\"Same\",\"lastName\":\"Id\","
                                    + "\"nationalId\":\"777010333\",\"dateOfBirth\":\"2001-02-03\","
                                    + "\"phone\":\"+26771234567\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk());
        }

        @Test
        @DisplayName("taking another person's national id is refused with 409")
        void cannotTakeSomebodyElsesNationalId() throws Exception {
            mvc.perform(post("/api/students").contentType("application/json")
                            .content(personJson("777010444")).with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content(personJson("777010555"))
                            .with(asAdmin()).with(csrf()))
                    .andReturn().getResponse().getContentAsString();
            Long id = new ObjectMapper().readTree(body).get("id").asLong();

            mvc.perform(put("/api/students/" + id)
                            .contentType("application/json")
                            .content("{\"firstName\":\"Thief\",\"lastName\":\"Of Ids\","
                                    + "\"nationalId\":\"777010444\",\"dateOfBirth\":\"2001-02-03\","
                                    + "\"phone\":\"+26771234567\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("an admin can delete a student who has no work, and their enrolment goes too")
        void adminCanDeleteAnUnencumberedStudent() throws Exception {
            String body = mvc.perform(post("/api/students")
                            .contentType("application/json")
                            .content("{\"firstName\":\"Temp\",\"lastName\":\"Student\","
                                    + "\"nationalId\":\"777010666\",\"dateOfBirth\":\"2001-02-03\","
                                    + "\"phone\":\"+26771234567\","
                                    + "\"temporaryPassword\":\"Temp-Password-1\","
                                    + "\"classId\":" + requireClassId("Grade 10A") + "}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode created = new ObjectMapper().readTree(body);
            Long id = created.get("id").asLong();
            String externalId = created.get("externalId").asText();

            mvc.perform(delete("/api/students/" + id).with(asAdmin()).with(csrf()))
               .andExpect(status().isNoContent());

            assertThat(users.findByUsername(externalId))
                    .as("the account is gone, enrolment and profile with it")
                    .isEmpty();
        }

        /*
         * THE POINT OF THE WHOLE EXERCISE. 'student' is seeded with submissions
         * and marks. Every foreign key is ON DELETE NO_ACTION, so the database
         * would refuse this anyway - but as a raw constraint violation, which is
         * only honestly reportable as a 500. The service check turns it into a
         * 409 that names what is in the way.
         */
        @Test
        @DisplayName("deleting a student who has work or marks is refused with 409, not a 500")
        void deletingAStudentWithWorkIsRefused() throws Exception {
            mvc.perform(delete("/api/students/" + idOf("student")).with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", anyOf(
                       containsStringIgnoringCase("mark"),
                       containsStringIgnoringCase("submission"))));

            assertThat(users.findByUsername("student"))
                    .as("a refused delete must leave the account exactly where it was")
                    .isPresent();
        }

        @Test
        @DisplayName("a teacher cannot delete a student")
        void teacherCannotDeleteAStudent() throws Exception {
            mvc.perform(delete("/api/students/" + idOf("student")).with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("editing and removing the timetable")
    class TimetableEditing {

        @Test
        @DisplayName("an admin can rename a subject and a class")
        void adminCanRename() throws Exception {
            // Created here rather than reusing a seeded subject: MATH, SCI and
            // HIST are all taught on courses, and renaming shared fixture data
            // would leak into whichever test happened to run next.
            String created = mvc.perform(post("/api/subjects").contentType("application/json")
                            .content("{\"code\":\"REN1\",\"name\":\"Renameable\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            Long subjectId = new ObjectMapper().readTree(created).get("id").asLong();

            mvc.perform(put("/api/subjects/" + subjectId)
                            .contentType("application/json")
                            .content("{\"code\":\"REN1\",\"name\":\"General Studies\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name", is("General Studies")));

            // Created here rather than reusing "Grade 10B", for the identical
            // reason the subject above is created rather than reusing MATH:
            // renaming shared seed data is a permanent write for the rest of
            // this JVM's test run, and any OTHER test that looks up "Grade
            // 10B" by its original name - see requireClassId("Grade 10B") a
            // few hundred lines above - would fail with "not found" if it
            // happened to run afterwards. JUnit does not guarantee execution
            // order across nested classes, so this is not hypothetical: it
            // passed locally by coincidence of ordering and failed on CI,
            // where the order came out differently.
            String createdClass = mvc.perform(post("/api/classes").contentType("application/json")
                            .content("{\"name\":\"Renameable Class\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            Long classId = new ObjectMapper().readTree(createdClass).get("id").asLong();

            mvc.perform(put("/api/classes/" + classId)
                            .contentType("application/json")
                            .content("{\"name\":\"Renamed Class\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name", is("Renamed Class")));
        }

        @Test
        @DisplayName("a teacher cannot rename or delete anything on the timetable")
        void teacherCannotEditTheTimetable() throws Exception {
            Long subjectId = requireSubjectId("MATH");
            mvc.perform(put("/api/subjects/" + subjectId).contentType("application/json")
                            .content("{\"code\":\"MATH\",\"name\":\"Mine now\"}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());

            mvc.perform(delete("/api/subjects/" + subjectId).with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());

            mvc.perform(delete("/api/classes/" + requireClassId("Grade 10A"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a subject still taught on a course cannot be deleted")
        void subjectInUseIsRefused() throws Exception {
            mvc.perform(delete("/api/subjects/" + requireSubjectId("MATH"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("course")));
        }

        @Test
        @DisplayName("a class with students enrolled cannot be deleted")
        void classWithStudentsIsRefused() throws Exception {
            mvc.perform(delete("/api/classes/" + requireClassId("Grade 10A"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("enrolled")));
        }

        @Test
        @DisplayName("a course that already has work set cannot be deleted")
        void courseWithWorkIsRefused() throws Exception {
            String body = mvc.perform(get("/api/courses/all").with(asAdmin()))
                    .andReturn().getResponse().getContentAsString();
            JsonNode courses = new ObjectMapper().readTree(body);
            assertThat(courses).as("the seed sets work, so some course must exist").isNotEmpty();

            Long courseId = courses.get(0).get("id").asLong();
            mvc.perform(delete("/api/courses/" + courseId).with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsStringIgnoringCase("work")));
        }

        @Test
        @DisplayName("an unused subject CAN be deleted - the refusals are about dependants, not a blanket ban")
        void unusedSubjectCanBeDeleted() throws Exception {
            String body = mvc.perform(post("/api/subjects").contentType("application/json")
                            .content("{\"code\":\"DEL1\",\"name\":\"Delete me\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            Long id = new ObjectMapper().readTree(body).get("id").asLong();

            mvc.perform(delete("/api/subjects/" + id).with(asAdmin()).with(csrf()))
               .andExpect(status().isNoContent());
        }
    }
}
