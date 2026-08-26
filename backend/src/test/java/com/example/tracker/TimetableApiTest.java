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
 * EPIC: terms, periods and the WHEN of the timetable.
 *
 * WHY EVERY TEST HERE ALLOCATES ITS OWN YEAR AND ITS OWN PERIOD NUMBER
 * --------------------------------------------------------------------
 * Two rules in this feature are GLOBAL rather than scoped to one row: no two
 * terms may overlap, and no two periods may share a position in the day. That
 * makes shared fixtures actively dangerous here in a way they are not
 * elsewhere - a term created by one test can make an unrelated test's term
 * illegal, and which of them fails would depend on the order JUnit happened to
 * pick.
 *
 * This project has already been bitten twice by exactly that (see AdminApiTest
 * on the seeded class it used to rename, and AccountSelfServiceTest on the
 * password it used to change), both times as a CI failure that never
 * reproduced locally. So every test below takes its dates from a distinct
 * year and its period numbers from a shared counter - nothing here can collide
 * with anything else no matter what order it runs in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class TimetableApiTest {

    @Autowired private MockMvc mvc;

    /**
     * Hands out a year, and a block of period numbers, that no other test in
     * this class is using. Static and atomic because @Nested classes get their
     * own instance - a plain field would restart at zero for each group.
     */
    private static final AtomicInteger NEXT = new AtomicInteger(0);

    /** A year no other test is using, well clear of any seeded data. */
    private int uniqueYear() {
        return 2100 + NEXT.getAndIncrement();
    }

    /** A position in the day no other test is using. */
    private int uniqueSequence() {
        return 100 + NEXT.getAndIncrement();
    }

    private static RequestPostProcessor as(String username, Role role) {
        return user(username).roles(role.name());
    }

    private RequestPostProcessor asAdmin()   { return as("admin", Role.ADMIN); }
    private RequestPostProcessor asTeacher() { return as("teacher", Role.TEACHER); }
    private RequestPostProcessor asStudent() { return as("student", Role.STUDENT); }

    private String termJson(String name, int year) {
        return "{\"name\":\"" + name + "\",\"startDate\":\"" + year + "-01-10\","
                + "\"endDate\":\"" + year + "-03-27\"}";
    }

    private String periodJson(String name, int sequenceNo, String from, String to) {
        return "{\"name\":\"" + name + "\",\"sequenceNo\":" + sequenceNo + ","
                + "\"startTime\":\"" + from + "\",\"endTime\":\"" + to + "\"}";
    }

    /** Create a term and return its id. */
    private long givenTerm(String name) throws Exception {
        String body = mvc.perform(post("/api/terms").contentType("application/json")
                        .content(termJson(name + NEXT.get(), uniqueYear()))
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asLong();
    }

    /** Create a period and return its id. */
    private long givenPeriod(String name) throws Exception {
        String body = mvc.perform(post("/api/periods").contentType("application/json")
                        .content(periodJson(name + NEXT.get(), uniqueSequence(), "08:00", "08:45"))
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asLong();
    }

    /** A seeded course id, read rather than created - these are never mutated. */
    private long anyCourseId() throws Exception {
        String body = mvc.perform(get("/api/courses/all").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = new ObjectMapper().readTree(body);
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("No seeded courses found.");
        }
        return root.get(0).get("id").asLong();
    }

    /** A seeded course taught to a DIFFERENT class than anyCourseId(). */
    private JsonNode allCourses() throws Exception {
        String body = mvc.perform(get("/api/courses/all").with(asAdmin()))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    // =========================================================================
    // TERMS
    // =========================================================================

    @Nested
    @DisplayName("terms")
    class Terms {

        @Test
        @DisplayName("an admin can create a term, and it reports whether it is current")
        void adminCanCreateTerm() throws Exception {
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content(termJson("Probe Term A", uniqueYear()))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.name", is("Probe Term A")))
               .andExpect(jsonPath("$.current", is(false)));   // year 21xx is not now
        }

        @Test
        @DisplayName("a teacher cannot create a term")
        void teacherCannotCreateTerm() throws Exception {
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content(termJson("Teacher Term", uniqueYear()))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a student cannot create a term")
        void studentCannotCreateTerm() throws Exception {
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content(termJson("Student Term", uniqueYear()))
                            .with(asStudent()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a term ending before it starts is refused with 400")
        void backwardsTermIsRefused() throws Exception {
            int year = uniqueYear();
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content("{\"name\":\"Backwards\",\"startDate\":\"" + year
                                    + "-06-01\",\"endDate\":\"" + year + "-01-01\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("cannot end")));
        }

        @Test
        @DisplayName("a blank term name is refused with 400")
        void blankNameIsRefused() throws Exception {
            int year = uniqueYear();
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content("{\"name\":\"  \",\"startDate\":\"" + year
                                    + "-01-01\",\"endDate\":\"" + year + "-03-01\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest());
        }

        /**
         * The one rule in this feature the DATABASE cannot keep - SQL Server has
         * no exclusion constraint - so this test is the only thing standing
         * behind it. See TimetableService.requireNoOverlap.
         */
        @Test
        @DisplayName("a term overlapping an existing one is refused, naming the clash")
        void overlappingTermIsRefused() throws Exception {
            int year = uniqueYear();
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content("{\"name\":\"Overlap Base\",\"startDate\":\"" + year
                                    + "-01-01\",\"endDate\":\"" + year + "-06-30\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            // Starts inside the term above.
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content("{\"name\":\"Overlap Clash\",\"startDate\":\"" + year
                                    + "-06-01\",\"endDate\":\"" + year + "-12-31\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsString("Overlap Base")));
        }

        @Test
        @DisplayName("editing a term does not collide with itself")
        void editingATermDoesNotCollideWithItself() throws Exception {
            int year = uniqueYear();
            String body = mvc.perform(post("/api/terms").contentType("application/json")
                            .content("{\"name\":\"Self Edit\",\"startDate\":\"" + year
                                    + "-01-01\",\"endDate\":\"" + year + "-03-01\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long id = new ObjectMapper().readTree(body).get("id").asLong();

            // Same dates, new name - the overlap check must skip this row.
            mvc.perform(put("/api/terms/" + id).contentType("application/json")
                            .content("{\"name\":\"Self Edit Renamed\",\"startDate\":\"" + year
                                    + "-01-01\",\"endDate\":\"" + year + "-03-01\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name", is("Self Edit Renamed")));
        }

        @Test
        @DisplayName("a duplicate term name is refused")
        void duplicateTermNameIsRefused() throws Exception {
            String name = "Duplicate Term " + NEXT.getAndIncrement();
            mvc.perform(post("/api/terms").contentType("application/json")
                            .content(termJson(name, uniqueYear()))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/terms").contentType("application/json")
                            .content(termJson(name, uniqueYear()))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("any signed-in role can read the term list")
        void anyoneSignedInCanReadTerms() throws Exception {
            mvc.perform(get("/api/terms").with(asStudent())).andExpect(status().isOk());
            mvc.perform(get("/api/terms").with(asTeacher())).andExpect(status().isOk());
            mvc.perform(get("/api/terms").with(asAdmin())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("an anonymous caller cannot read the term list")
        void anonymousCannotReadTerms() throws Exception {
            mvc.perform(get("/api/terms")).andExpect(status().isUnauthorized());
        }
    }

    // =========================================================================
    // PERIODS
    // =========================================================================

    @Nested
    @DisplayName("periods")
    class Periods {

        @Test
        @DisplayName("an admin can create a period")
        void adminCanCreatePeriod() throws Exception {
            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Probe Period", uniqueSequence(), "08:00", "08:45"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.name", is("Probe Period")))
               .andExpect(jsonPath("$.startTime", is("08:00:00")));
        }

        @Test
        @DisplayName("a teacher cannot create a period")
        void teacherCannotCreatePeriod() throws Exception {
            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Teacher Period", uniqueSequence(), "09:00", "09:45"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a period ending at or before it starts is refused with 400")
        void backwardsPeriodIsRefused() throws Exception {
            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Backwards", uniqueSequence(), "10:00", "09:00"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("must end after")));

            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Zero Length", uniqueSequence(), "10:00", "10:00"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a position already taken in the day is refused")
        void duplicateSequenceIsRefused() throws Exception {
            int sequence = uniqueSequence();
            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Seq Base " + sequence, sequence, "08:00", "08:45"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Seq Clash " + sequence, sequence, "09:00", "09:45"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsString("already number")));
        }

        @Test
        @DisplayName("a position of zero or less is refused with 400")
        void nonPositiveSequenceIsRefused() throws Exception {
            mvc.perform(post("/api/periods").contentType("application/json")
                            .content(periodJson("Zeroth", 0, "08:00", "08:45"))
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest());
        }
    }

    // =========================================================================
    // THE TIMETABLE
    // =========================================================================

    @Nested
    @DisplayName("scheduling lessons")
    class Scheduling {

        @Test
        @DisplayName("an admin can timetable a course into a period")
        void adminCanSchedule() throws Exception {
            long termId = givenTerm("Sched");
            long periodId = givenPeriod("Sched");
            long courseId = anyCourseId();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.dayOfWeek", is("MONDAY")))
               .andExpect(jsonPath("$.subjectCode", notNullValue()))
               .andExpect(jsonPath("$.className", notNullValue()))
               .andExpect(jsonPath("$.teacherUsername", notNullValue()));
        }

        @Test
        @DisplayName("a teacher cannot timetable a lesson")
        void teacherCannotSchedule() throws Exception {
            long termId = givenTerm("NoTeach");
            long periodId = givenPeriod("NoTeach");
            long courseId = anyCourseId();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        /**
         * THE RULE THAT MAKES THIS A TIMETABLE. The same class cannot be in two
         * lessons at once. Enforced by uq_timetable_slot_class at the database
         * and checked here first so the message names what is in the way.
         */
        @Test
        @DisplayName("a class already busy in that period is refused, naming the clash")
        void classClashIsRefused() throws Exception {
            long termId = givenTerm("ClassClash");
            long periodId = givenPeriod("ClassClash");
            JsonNode courses = allCourses();

            long first = courses.get(0).get("id").asLong();
            String firstClass = courses.get(0).get("className").asText();

            // A different course taught to the SAME class.
            Long second = null;
            for (JsonNode course : courses) {
                if (course.get("className").asText().equals(firstClass)
                        && course.get("id").asLong() != first) {
                    second = course.get("id").asLong();
                    break;
                }
            }
            /*
             * A FAILURE, NOT A SILENT SKIP. This used to `return` when the
             * fixture was missing, which meant the test would keep passing
             * while proving nothing at all if the seed data ever changed
             * shape. The seeded timetable gives Grade 10A both Mathematics
             * and History, so this is genuinely available - and if that
             * stops being true, this test must say so rather than quietly
             * becoming decorative.
             */
            assertThat(second)
                    .as("need a second course for class %s to test a clash", firstClass)
                    .isNotNull();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + first + ",\"dayOfWeek\":\"TUESDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + second + ",\"dayOfWeek\":\"TUESDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsString(firstClass)));
        }

        /**
         * THE OTHER RULE. A teacher cannot be in two places at once, which is a
         * genuinely different constraint from the class clash above - the seeded
         * teacher teaches Mathematics to BOTH Grade 10A and Grade 10B, so these
         * two lessons involve different classes and would not trip
         * uq_timetable_slot_class at all. Only uq_timetable_slot_teacher catches
         * it. Testing one clash and assuming the other would leave half the
         * table's purpose unproven.
         */
        @Test
        @DisplayName("a teacher already teaching elsewhere in that period is refused")
        void teacherClashIsRefused() throws Exception {
            long termId = givenTerm("TeacherClash");
            long periodId = givenPeriod("TeacherClash");
            JsonNode courses = allCourses();

            Long first = null;
            Long second = null;
            String teacherName = null;
            for (JsonNode course : courses) {
                String who = course.get("teacherUsername").asText();
                String className = course.get("className").asText();
                if (first == null) {
                    first = course.get("id").asLong();
                    teacherName = who;
                    continue;
                }
                // Same teacher, DIFFERENT class - so only the teacher rule applies.
                if (who.equals(teacherName)
                        && !className.equals(courses.get(0).get("className").asText())) {
                    second = course.get("id").asLong();
                    break;
                }
            }
            assertThat(second)
                    .as("need two courses taught by %s to different classes", teacherName)
                    .isNotNull();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + first + ",\"dayOfWeek\":\"THURSDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + second + ",\"dayOfWeek\":\"THURSDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsString(teacherName)));
        }

        /** The same lesson twice is the simplest possible clash. */
        @Test
        @DisplayName("the identical lesson twice is refused")
        void identicalSlotTwiceIsRefused() throws Exception {
            long termId = givenTerm("Twice");
            long periodId = givenPeriod("Twice");
            long courseId = anyCourseId();
            String body = "{\"termId\":" + termId + ",\"periodId\":" + periodId
                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"WEDNESDAY\"}";

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content(body).with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content(body).with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("the same course on a different day is allowed")
        void sameCourseDifferentDayIsAllowed() throws Exception {
            long termId = givenTerm("TwoDays");
            long periodId = givenPeriod("TwoDays");
            long courseId = anyCourseId();

            for (String day : new String[]{"MONDAY", "THURSDAY"}) {
                mvc.perform(post("/api/timetable").contentType("application/json")
                                .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                        + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"" + day + "\"}")
                                .with(asAdmin()).with(csrf()))
                   .andExpect(status().isCreated());
            }
        }

        @Test
        @DisplayName("an unknown day name is refused with 400 that names the valid range")
        void unknownDayIsRefused() throws Exception {
            long termId = givenTerm("BadDay");
            long periodId = givenPeriod("BadDay");
            long courseId = anyCourseId();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"Fundaay\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("MONDAY")));
        }

        @Test
        @DisplayName("an unknown term is 404, not 400 or 500")
        void unknownTermIs404() throws Exception {
            long periodId = givenPeriod("Missing");
            long courseId = anyCourseId();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":999999,\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"FRIDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a lesson can be removed from the timetable")
        void slotCanBeRemoved() throws Exception {
            long termId = givenTerm("Remove");
            long periodId = givenPeriod("Remove");
            long courseId = anyCourseId();

            String body = mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"FRIDAY\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long slotId = new ObjectMapper().readTree(body).get("id").asLong();

            mvc.perform(delete("/api/timetable/" + slotId).with(asAdmin()).with(csrf()))
               .andExpect(status().isNoContent());

            mvc.perform(get("/api/timetable").param("termId", String.valueOf(termId))
                            .with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("a term with lessons timetabled cannot be deleted")
        void termInUseCannotBeDeleted() throws Exception {
            long termId = givenTerm("InUse");
            long periodId = givenPeriod("InUse");
            long courseId = anyCourseId();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(delete("/api/terms/" + termId).with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message", containsString("still has lessons")));
        }

        @Test
        @DisplayName("a period with lessons timetabled in it cannot be deleted")
        void periodInUseCannotBeDeleted() throws Exception {
            long termId = givenTerm("PeriodInUse");
            long periodId = givenPeriod("PeriodInUse");
            long courseId = anyCourseId();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(delete("/api/periods/" + periodId).with(asAdmin()).with(csrf()))
               .andExpect(status().isConflict());
        }
    }

    // =========================================================================
    // READING A TIMETABLE - EACH ROLE SEES ITS OWN
    // =========================================================================

    @Nested
    @DisplayName("reading a timetable")
    class Reading {

        @Test
        @DisplayName("a student cannot read the whole school's timetable")
        void studentCannotReadWholeTimetable() throws Exception {
            long termId = givenTerm("StudentRead");

            mvc.perform(get("/api/timetable").param("termId", String.valueOf(termId))
                            .with(asStudent()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a teacher and an admin can read the whole timetable")
        void staffCanReadWholeTimetable() throws Exception {
            long termId = givenTerm("StaffRead");

            mvc.perform(get("/api/timetable").param("termId", String.valueOf(termId))
                            .with(asTeacher()))
               .andExpect(status().isOk());
            mvc.perform(get("/api/timetable").param("termId", String.valueOf(termId))
                            .with(asAdmin()))
               .andExpect(status().isOk());
        }

        /**
         * A student's own week comes from their CLASS, through enrolment - they
         * never reference a slot directly. Asserted in BOTH directions: the
         * lesson for their class is present, and one for a class they are not in
         * is absent. A one-sided assertion here would pass just as happily if
         * scoping were removed entirely.
         */
        @Test
        @DisplayName("a student's own timetable is their class's lessons, and no others")
        void studentSeesOnlyTheirOwnClass() throws Exception {
            long termId = givenTerm("Scope");
            long periodOne = givenPeriod("ScopeOne");
            long periodTwo = givenPeriod("ScopeTwo");
            JsonNode courses = allCourses();

            // Which class is the seeded student in?
            String myClass = mvc.perform(get("/api/students").with(asAdmin()))
                    .andReturn().getResponse().getContentAsString();
            JsonNode students = new ObjectMapper().readTree(myClass);
            String studentClass = null;
            for (JsonNode student : students) {
                if ("student".equals(student.get("username").asText())) {
                    JsonNode className = student.get("className");
                    studentClass = className == null || className.isNull()
                            ? null : className.asText();
                }
            }
            assertThat(studentClass)
                    .as("the seeded 'student' account must be enrolled in a class")
                    .isNotNull();

            Long mine = null;
            Long theirs = null;
            for (JsonNode course : courses) {
                String className = course.get("className").asText();
                if (className.equals(studentClass) && mine == null) {
                    mine = course.get("id").asLong();
                } else if (!className.equals(studentClass) && theirs == null) {
                    theirs = course.get("id").asLong();
                }
            }
            assertThat(mine).as("need a course for the student's own class").isNotNull();
            assertThat(theirs).as("need a course for a class they are NOT in").isNotNull();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodOne
                                    + ",\"courseId\":" + mine + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodTwo
                                    + ",\"courseId\":" + theirs + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/timetable/me").param("termId", String.valueOf(termId))
                            .with(asStudent()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[*].className", everyItem(is(studentClass))))
               .andExpect(jsonPath("$", hasSize(greaterThan(0))));
        }

        @Test
        @DisplayName("a teacher's own timetable contains only lessons they teach")
        void teacherSeesOnlyTheirOwnLessons() throws Exception {
            long termId = givenTerm("TeacherScope");
            long periodId = givenPeriod("TeacherScope");
            JsonNode courses = allCourses();

            Long taughtByTeacher = null;
            for (JsonNode course : courses) {
                if ("teacher".equals(course.get("teacherUsername").asText())) {
                    taughtByTeacher = course.get("id").asLong();
                    break;
                }
            }
            assertThat(taughtByTeacher)
                    .as("the seeded 'teacher' account must teach at least one course")
                    .isNotNull();

            mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + taughtByTeacher + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/timetable/me").param("termId", String.valueOf(termId))
                            .with(asTeacher()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[*].teacherUsername", everyItem(is("teacher"))));
        }

        @Test
        @DisplayName("an anonymous caller cannot read any timetable")
        void anonymousCannotRead() throws Exception {
            mvc.perform(get("/api/timetable").param("termId", "1"))
               .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/timetable/me").param("termId", "1"))
               .andExpect(status().isUnauthorized());
        }
    }
}
