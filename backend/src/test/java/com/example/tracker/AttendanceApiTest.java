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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EPIC: the attendance register.
 *
 * Like TimetableApiTest, every test here allocates its own term year and its
 * own period numbers, because terms may not overlap and period positions are
 * unique - both GLOBAL rules, which makes a shared fixture a way for one test
 * to break another depending only on the order JUnit picked. This project has
 * paid for that twice already as CI failures that never reproduced locally.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class AttendanceApiTest {

    @Autowired private MockMvc mvc;

    private static final AtomicInteger NEXT = new AtomicInteger(0);

    private int uniqueYear()     { return 2200 + NEXT.getAndIncrement(); }
    private int uniqueSequence() { return 900 + NEXT.getAndIncrement(); }

    private static RequestPostProcessor as(String u, Role r) { return user(u).roles(r.name()); }
    private RequestPostProcessor asAdmin()   { return as("admin", Role.ADMIN); }
    private RequestPostProcessor asTeacher() { return as("teacher", Role.TEACHER); }
    private RequestPostProcessor asStudent() { return as("student", Role.STUDENT); }

    private final ObjectMapper json = new ObjectMapper();

    /**
     * A lesson taught by the seeded `teacher` to the class the seeded
     * `student` is in, on a known weekday, inside a term that covers a known
     * date. Returns [slotId, lessonDate].
     */
    private Object[] givenALesson() throws Exception {
        int year = uniqueYear();
        // A Monday inside the term below, chosen by walking forward from 10 Jan.
        LocalDate date = LocalDate.of(year, 1, 10);
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }

        String termBody = mvc.perform(post("/api/terms").contentType("application/json")
                        .content("{\"name\":\"Att Term " + year + "\",\"startDate\":\"" + year
                                + "-01-05\",\"endDate\":\"" + year + "-06-30\"}")
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long termId = json.readTree(termBody).get("id").asLong();

        int seq = uniqueSequence();
        String periodBody = mvc.perform(post("/api/periods").contentType("application/json")
                        .content("{\"name\":\"AttP" + seq + "\",\"sequenceNo\":" + seq
                                + ",\"startTime\":\"08:00\",\"endTime\":\"08:45\"}")
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long periodId = json.readTree(periodBody).get("id").asLong();

        // A course the seeded teacher teaches to the seeded student's class.
        String classNameOfStudent = studentClassName();
        JsonNode courses = json.readTree(mvc.perform(get("/api/courses/all").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        Long courseId = null;
        for (JsonNode c : courses) {
            if (c.get("className").asText().equals(classNameOfStudent)
                    && "teacher".equals(c.get("teacherUsername").asText())) {
                courseId = c.get("id").asLong();
                break;
            }
        }
        assertThat(courseId)
                .as("need a course taught by 'teacher' to %s", classNameOfStudent)
                .isNotNull();

        String slotBody = mvc.perform(post("/api/timetable").contentType("application/json")
                        .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                + ",\"courseId\":" + courseId + ",\"dayOfWeek\":\"MONDAY\"}")
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Object[]{ json.readTree(slotBody).get("id").asLong(), date };
    }

    private String studentClassName() throws Exception {
        JsonNode students = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode s : students) {
            if ("student".equals(s.get("username").asText())) {
                JsonNode cn = s.get("className");
                assertThat(cn == null || cn.isNull())
                        .as("the seeded 'student' must be enrolled in a class").isFalse();
                return cn.asText();
            }
        }
        throw new IllegalStateException("Seeded 'student' account not found.");
    }

    private long seededStudentId() throws Exception {
        JsonNode students = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode s : students) {
            if ("student".equals(s.get("username").asText())) {
                return s.get("id").asLong();
            }
        }
        throw new IllegalStateException("Seeded 'student' account not found.");
    }

    private String marks(long studentId, String status) {
        return "{\"marks\":[{\"studentId\":" + studentId + ",\"status\":\"" + status + "\"}]}";
    }

    // =========================================================================

    @Nested
    @DisplayName("taking a register")
    class Taking {

        @Test
        @DisplayName("the register lists every enrolled student, unmarked ones included")
        void registerListsEveryone() throws Exception {
            Object[] lesson = givenALesson();

            mvc.perform(get("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .with(asTeacher()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$", hasSize(greaterThan(0))))
               // Nobody marked yet - every status is null, which is what makes
               // "not taken" distinguishable from "all present".
               .andExpect(jsonPath("$[*].status", everyItem(nullValue())));
        }

        @Test
        @DisplayName("the teacher of the lesson can take it, and it reads back")
        void teacherCanTakeTheRegister() throws Exception {
            Object[] lesson = givenALesson();
            long studentId = seededStudentId();

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json")
                            .content(marks(studentId, "PRESENT"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].status", is("PRESENT")))
               .andExpect(jsonPath("$[0].recordedBy", is("teacher")));

            mvc.perform(get("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .with(asTeacher()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[?(@.studentId == " + studentId + ")].status",
                       contains("PRESENT")));
        }

        /**
         * Re-taking a register is a CORRECTION, not a second register.
         * uq_attendance_slot_student_date would refuse a duplicate row anyway;
         * this proves the service turns the second call into an amendment
         * rather than surfacing that refusal as an error.
         */
        @Test
        @DisplayName("taking the register twice corrects it rather than duplicating it")
        void retakingCorrects() throws Exception {
            Object[] lesson = givenALesson();
            long studentId = seededStudentId();

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json").content(marks(studentId, "ABSENT"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isOk());

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json").content(marks(studentId, "LATE"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].status", is("LATE")));

            // Exactly one row for that student, carrying the corrected value.
            mvc.perform(get("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .with(asTeacher()))
               .andExpect(jsonPath("$[?(@.studentId == " + studentId + ")].status",
                       contains("LATE")));
        }

        @Test
        @DisplayName("a student cannot take a register")
        void studentCannotTake() throws Exception {
            Object[] lesson = givenALesson();
            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json").content(marks(seededStudentId(), "PRESENT"))
                            .with(asStudent()).with(csrf()))
               .andExpect(status().isForbidden());
        }

        /**
         * A teacher who does NOT teach the lesson is refused. A register names
         * every student in a class, so a role check alone would be too weak -
         * see AttendanceService.requireMayTake.
         */
        @Test
        @DisplayName("a teacher who does not teach the lesson cannot take its register")
        void otherTeacherCannotTake() throws Exception {
            Object[] lesson = givenALesson();
            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json").content(marks(seededStudentId(), "PRESENT"))
                            .with(as("teacher2", Role.TEACHER)).with(csrf()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unknown status is refused with 400 naming the valid ones")
        void unknownStatusIsRefused() throws Exception {
            Object[] lesson = givenALesson();
            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json")
                            .content(marks(seededStudentId(), "MAYBE"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("PRESENT")));
        }
    }

    // =========================================================================
    // THE THREE RULES THE DATABASE CANNOT KEEP
    // =========================================================================

    @Nested
    @DisplayName("a date must be a real occurrence of the lesson")
    class DateRules {

        @Test
        @DisplayName("marking a Monday lesson on a Tuesday is refused")
        void wrongWeekdayIsRefused() throws Exception {
            Object[] lesson = givenALesson();
            LocalDate tuesday = ((LocalDate) lesson[1]).plusDays(1);

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", tuesday.toString())
                            .contentType("application/json").content(marks(seededStudentId(), "PRESENT"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("Monday")));
        }

        @Test
        @DisplayName("marking a date outside the lesson's term is refused, naming the term")
        void dateOutsideTermIsRefused() throws Exception {
            Object[] lesson = givenALesson();
            // A Monday well after the term ends (term runs to 30 June).
            LocalDate late = ((LocalDate) lesson[1]).plusWeeks(40);

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", late.toString())
                            .contentType("application/json").content(marks(seededStudentId(), "PRESENT"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("outside")));
        }

        @Test
        @DisplayName("a student not enrolled in the class cannot be marked")
        void unenrolledStudentIsRefused() throws Exception {
            Object[] lesson = givenALesson();
            // student3 is seeded into Grade 10B, not the seeded student's class.
            JsonNode students = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                    .andReturn().getResponse().getContentAsString());
            Long outsiderId = null;
            String mine = studentClassName();
            for (JsonNode s : students) {
                JsonNode cn = s.get("className");
                String name = cn == null || cn.isNull() ? null : cn.asText();
                if (name == null || !name.equals(mine)) {
                    outsiderId = s.get("id").asLong();
                    break;
                }
            }
            assertThat(outsiderId).as("need a student outside %s", mine).isNotNull();

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json").content(marks(outsiderId, "PRESENT"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("not enrolled")));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("reading a student's own record")
    class Reading {

        @Test
        @DisplayName("a student can read their own attendance and its summary")
        void studentReadsTheirOwn() throws Exception {
            Object[] lesson = givenALesson();
            long studentId = seededStudentId();

            mvc.perform(put("/api/attendance/lessons/" + lesson[0])
                            .param("date", lesson[1].toString())
                            .contentType("application/json").content(marks(studentId, "PRESENT"))
                            .with(asTeacher()).with(csrf()))
               .andExpect(status().isOk());

            mvc.perform(get("/api/attendance/students/" + studentId).with(asStudent()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$", hasSize(greaterThan(0))));

            mvc.perform(get("/api/attendance/students/" + studentId + "/summary")
                            .with(asStudent()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total", greaterThan(0)))
               .andExpect(jsonPath("$.attendanceRate", notNullValue()));
        }

        @Test
        @DisplayName("a student cannot read somebody else's attendance")
        void studentCannotReadAnother() throws Exception {
            JsonNode students = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                    .andReturn().getResponse().getContentAsString());
            Long other = null;
            long mine = seededStudentId();
            for (JsonNode s : students) {
                if (s.get("id").asLong() != mine) { other = s.get("id").asLong(); break; }
            }
            assertThat(other).as("need a second student").isNotNull();

            mvc.perform(get("/api/attendance/students/" + other).with(asStudent()))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot read any attendance")
        void anonymousIsRefused() throws Exception {
            mvc.perform(get("/api/attendance/students/1")).andExpect(status().isUnauthorized());
        }

        /**
         * A student with nothing on record has not attended 0% - the question
         * has no answer yet, and 0% answers a different, much more alarming
         * one. See AttendanceSummaryView.of.
         */
        @Test
        @DisplayName("a summary with nothing recorded reports no rate rather than zero")
        void emptySummaryHasNoRate() throws Exception {
            JsonNode students = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                    .andReturn().getResponse().getContentAsString());
            // student3 (Grade 10B) has no lessons timetabled by these tests.
            Long untouched = null;
            String mine = studentClassName();
            for (JsonNode s : students) {
                JsonNode cn = s.get("className");
                String name = cn == null || cn.isNull() ? null : cn.asText();
                if (name != null && !name.equals(mine)) { untouched = s.get("id").asLong(); break; }
            }
            if (untouched == null) {
                return;   // single-class seed data; nothing to assert
            }
            mvc.perform(get("/api/attendance/students/" + untouched + "/summary")
                            .with(asAdmin()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total", is(0)))
               .andExpect(jsonPath("$.attendanceRate", nullValue()));
        }
    }
}
