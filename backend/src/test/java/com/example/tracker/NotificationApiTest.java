package com.example.tracker;

import com.example.tracker.model.Role;
import com.example.tracker.service.DueDateReminderJob;
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
 * EPIC: notifications the system raises by itself.
 *
 * THE CENTRAL PROPERTY IS IDEMPOTENCE, not delivery. That a student is told
 * about a new mark is easy to get right and easy to see. That they are told
 * ONCE - however many times the register is corrected, however often the timer
 * fires, however many times the sweep is run - is the part the schema is
 * carrying, and it is what these tests are mostly about.
 *
 * The reminder job is invoked DIRECTLY rather than waited for. Scheduling is
 * switched off in this profile on purpose (see SchedulingConfig), and a test
 * that slept for a timer would be slow and flaky while proving less: whether
 * the sweep is repeatable is a property of the sweep, not of the clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("null")   // Hamcrest and Spring's post-processors are not null-annotated
class NotificationApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private DueDateReminderJob reminders;

    private static final AtomicInteger NEXT = new AtomicInteger(0);
    private int uniqueYear()     { return 2400 + NEXT.getAndIncrement(); }
    private int uniqueSequence() { return 400 + NEXT.getAndIncrement(); }

    private static RequestPostProcessor as(String u, Role r) { return user(u).roles(r.name()); }
    private RequestPostProcessor asAdmin()   { return as("admin", Role.ADMIN); }
    private RequestPostProcessor asTeacher() { return as("teacher", Role.TEACHER); }
    private RequestPostProcessor asStudent() { return as("student", Role.STUDENT); }

    private final ObjectMapper json = new ObjectMapper();

    // ===== fixtures ==========================================================

    private JsonNode myNotifications(RequestPostProcessor who) throws Exception {
        return json.readTree(mvc.perform(get("/api/notifications").with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** How many of the caller's notifications mention this marker. */
    private int countMentioning(RequestPostProcessor who, String marker) throws Exception {
        int found = 0;
        for (JsonNode n : myNotifications(who)) {
            if (n.get("title").asText().contains(marker)
                    || n.get("body").asText().contains(marker)) {
                found++;
            }
        }
        return found;
    }

    private long seededStudentId() throws Exception {
        JsonNode rows = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode row : rows) {
            if ("student".equals(row.get("username").asText())) {
                return row.get("id").asLong();
            }
        }
        throw new IllegalStateException("Seeded 'student' not found.");
    }

    private String studentClassName() throws Exception {
        JsonNode rows = json.readTree(mvc.perform(get("/api/students").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode row : rows) {
            if ("student".equals(row.get("username").asText())) {
                return row.get("className").asText();
            }
        }
        throw new IllegalStateException("Seeded 'student' not found.");
    }

    /** A course the seeded teacher teaches to the seeded student's class. */
    private long seededCourseId() throws Exception {
        String className = studentClassName();
        JsonNode courses = json.readTree(mvc.perform(get("/api/courses/all").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode c : courses) {
            if (c.get("className").asText().equals(className)
                    && "teacher".equals(c.get("teacherUsername").asText())) {
                return c.get("id").asLong();
            }
        }
        throw new IllegalStateException("No course taught by 'teacher' to " + className);
    }

    // =========================================================================
    @Nested
    @DisplayName("a mark being posted")
    class MarkPosted {

        @Test
        @DisplayName("tells the student, once")
        void tellsTheStudentOnce() throws Exception {
            String name = "Quiz" + uniqueSequence();
            mvc.perform(post("/api/assessments").contentType("application/json")
                            .content("{\"courseId\":" + seededCourseId()
                                    + ",\"studentUsername\":\"student\",\"name\":\"" + name
                                    + "\",\"score\":\"7\",\"maxScore\":\"10\"}")
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());

            assertThat(countMentioning(asStudent(), name))
                    .as("the student is told about '%s' exactly once", name)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("carries the score as it was at the time")
        void carriesTheScore() throws Exception {
            String name = "Snap" + uniqueSequence();
            mvc.perform(post("/api/assessments").contentType("application/json")
                            .content("{\"courseId\":" + seededCourseId()
                                    + ",\"studentUsername\":\"student\",\"name\":\"" + name
                                    + "\",\"score\":\"9\",\"maxScore\":\"10\"}")
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());

            boolean found = false;
            for (JsonNode n : myNotifications(asStudent())) {
                if (n.get("body").asText().contains(name)) {
                    assertThat(n.get("body").asText()).contains("9 out of 10");
                    assertThat(n.get("type").asText()).isEqualTo("MARK_POSTED");
                    assertThat(n.get("subjectType").asText()).isEqualTo("ASSESSMENT");
                    found = true;
                }
            }
            assertThat(found).as("a MARK_POSTED notification for '%s'", name).isTrue();
        }

        @Test
        @DisplayName("does not reach anybody else")
        void staysWithTheStudent() throws Exception {
            String name = "Private" + uniqueSequence();
            mvc.perform(post("/api/assessments").contentType("application/json")
                            .content("{\"courseId\":" + seededCourseId()
                                    + ",\"studentUsername\":\"student\",\"name\":\"" + name
                                    + "\",\"score\":\"3\",\"maxScore\":\"10\"}")
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());

            assertThat(countMentioning(asTeacher(), name))
                    .as("the teacher who recorded it is not notified about it").isZero();
            assertThat(countMentioning(asAdmin(), name))
                    .as("an admin is not notified about somebody's mark").isZero();
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("being marked absent")
    class Absence {

        /** A lesson taught by 'teacher' to 'student', and a date it falls on. */
        private Object[] givenALesson() throws Exception {
            int year = uniqueYear();
            LocalDate date = LocalDate.of(year, 1, 10);
            while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
                date = date.plusDays(1);
            }
            String termBody = mvc.perform(post("/api/terms").contentType("application/json")
                            .content("{\"name\":\"Notif Term " + year + "\",\"startDate\":\""
                                    + year + "-01-05\",\"endDate\":\"" + year + "-06-30\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long termId = json.readTree(termBody).get("id").asLong();

            int seq = uniqueSequence();
            String periodBody = mvc.perform(post("/api/periods").contentType("application/json")
                            .content("{\"name\":\"NotP" + seq + "\",\"sequenceNo\":" + seq
                                    + ",\"startTime\":\"09:00\",\"endTime\":\"09:45\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long periodId = json.readTree(periodBody).get("id").asLong();

            String slotBody = mvc.perform(post("/api/timetable").contentType("application/json")
                            .content("{\"termId\":" + termId + ",\"periodId\":" + periodId
                                    + ",\"courseId\":" + seededCourseId()
                                    + ",\"dayOfWeek\":\"MONDAY\"}")
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return new Object[]{ json.readTree(slotBody).get("id").asLong(), date };
        }

        private void takeRegister(long slotId, LocalDate date, String status) throws Exception {
            mvc.perform(put("/api/attendance/lessons/" + slotId + "?date=" + date)
                            .contentType("application/json")
                            .content("{\"marks\":[{\"studentId\":" + seededStudentId()
                                    + ",\"status\":\"" + status + "\"}]}")
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("tells the student")
        void tellsTheStudent() throws Exception {
            Object[] lesson = givenALesson();
            long slotId = (long) lesson[0];
            LocalDate date = (LocalDate) lesson[1];

            takeRegister(slotId, date, "ABSENT");

            assertThat(countMentioning(asStudent(), date.toString()))
                    .as("the student is told about the absence on %s", date)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("being marked PRESENT tells them nothing")
        void presentIsNotNews() throws Exception {
            Object[] lesson = givenALesson();
            long slotId = (long) lesson[0];
            LocalDate date = (LocalDate) lesson[1];

            takeRegister(slotId, date, "PRESENT");

            assertThat(countMentioning(asStudent(), date.toString()))
                    .as("turning up is not an event worth a notification").isZero();
        }

        @Test
        @DisplayName("correcting the register does not notify twice")
        void correctionDoesNotRenotify() throws Exception {
            Object[] lesson = givenALesson();
            long slotId = (long) lesson[0];
            LocalDate date = (LocalDate) lesson[1];

            takeRegister(slotId, date, "ABSENT");
            // Taking it again is a CORRECTION - one attendance row, one
            // notification, whatever the teacher does afterwards.
            takeRegister(slotId, date, "ABSENT");
            takeRegister(slotId, date, "PRESENT");
            takeRegister(slotId, date, "ABSENT");

            assertThat(countMentioning(asStudent(), date.toString()))
                    .as("four registers, one notification").isEqualTo(1);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("the due-date reminder sweep")
    class DueSoon {

        @Test
        @DisplayName("running it twice raises nothing the second time")
        void sweepIsIdempotent() throws Exception {
            // Whatever the seeded data contains, the SECOND run over identical
            // data must raise nothing. That is the property the unique
            // constraint exists for, and it holds regardless of how much work
            // happens to be due - which is what makes this assertion safe in a
            // suite that shares its database.
            reminders.sendDueSoonReminders();
            int second = reminders.sendDueSoonReminders();

            assertThat(second)
                    .as("a repeat sweep over unchanged data raises no new notifications")
                    .isZero();
        }

        @Test
        @DisplayName("a third and fourth run still raise nothing")
        void staysIdempotent() throws Exception {
            reminders.sendDueSoonReminders();
            assertThat(reminders.sendDueSoonReminders()).isZero();
            assertThat(reminders.sendDueSoonReminders()).isZero();
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("reading notifications")
    class Reading {

        @Test
        @DisplayName("the unread count matches the unread list")
        void countMatchesList() throws Exception {
            String body = mvc.perform(get("/api/notifications/unread-count").with(asStudent()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long counted = json.readTree(body).get("unread").asLong();

            JsonNode unread = json.readTree(
                    mvc.perform(get("/api/notifications?unreadOnly=true").with(asStudent()))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString());

            assertThat((long) unread.size())
                    .as("the badge and the list are two reads of one fact")
                    .isEqualTo(counted);
        }

        @Test
        @DisplayName("marking all read empties the badge")
        void markAllRead() throws Exception {
            mvc.perform(put("/api/notifications/read-all").with(asStudent()).with(csrf()))
                    .andExpect(status().isOk());

            mvc.perform(get("/api/notifications/unread-count").with(asStudent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unread", is(0)));
        }

        @Test
        @DisplayName("somebody else's notification is a 404, not a 403")
        void othersAreNotFound() throws Exception {
            String name = "Mine" + uniqueSequence();
            mvc.perform(post("/api/assessments").contentType("application/json")
                            .content("{\"courseId\":" + seededCourseId()
                                    + ",\"studentUsername\":\"student\",\"name\":\"" + name
                                    + "\",\"score\":\"5\",\"maxScore\":\"10\"}")
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isOk());

            long id = -1;
            for (JsonNode n : myNotifications(asStudent())) {
                if (n.get("body").asText().contains(name)) {
                    id = n.get("id").asLong();
                }
            }
            assertThat(id).as("the student's own notification").isNotEqualTo(-1);

            /*
             * 404 rather than 403 deliberately. A 403 would confirm that this
             * notification exists and belongs to somebody - on a table whose
             * rows say things like "your mark for Mathematics was posted",
             * that is itself worth not leaking.
             */
            final long theirs = id;
            mvc.perform(put("/api/notifications/" + theirs + "/read")
                            .with(asTeacher()).with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an anonymous caller gets nothing")
        void anonymousCannotRead() throws Exception {
            mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/notifications/unread-count"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
