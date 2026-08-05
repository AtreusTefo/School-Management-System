package com.example.tracker.controller;

import com.example.tracker.dto.ClassView;
import com.example.tracker.dto.CourseView;
import com.example.tracker.dto.SubjectView;
import com.example.tracker.service.SchoolService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER for the structure of the school: subjects,
 * classes, who is in them, and who teaches what.
 *
 * One controller rather than four, because these are four views of one thing -
 * the timetable - and splitting them would spread a single concept across files
 * that would then have to agree with each other. All of it delegates to
 * SchoolService; nothing is decided here.
 */
@RestController
@RequestMapping("/api")
public class SchoolController {

    private final SchoolService school;

    public SchoolController(SchoolService school) {
        this.school = school;
    }

    // ----- subjects ------------------------------------------------------------

    @GetMapping("/subjects")
    public List<SubjectView> listSubjects() {
        return school.listSubjects();
    }

    @PostMapping("/subjects")
    public SubjectView createSubject(@Valid @RequestBody CreateSubjectRequest request) {
        return school.createSubject(request.getCode(), request.getName());
    }

    // ----- classes -------------------------------------------------------------

    @GetMapping("/classes")
    public List<ClassView> listClasses() {
        return school.listClasses();
    }

    @PostMapping("/classes")
    public ClassView createClass(@Valid @RequestBody CreateClassRequest request) {
        return school.createClass(request.getName());
    }

    /** GET /api/classes/{id}/students - the register. */
    @GetMapping("/classes/{id}/students")
    public List<String> listStudents(@PathVariable Long id) {
        return school.listStudentsInClass(id);
    }

    /** POST /api/classes/{id}/students - put a student in this class. */
    @PostMapping("/classes/{id}/students")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enrol(@PathVariable Long id, @Valid @RequestBody EnrolRequest request) {
        school.enrolStudent(id, request.getUsername());
    }

    /**
     * DELETE /api/classes/{id}/students/{username} - take them out again.
     *
     * The username is in the PATH rather than a body, because DELETE with a body
     * is poorly supported by intermediaries and some clients drop it silently.
     */
    @DeleteMapping("/classes/{id}/students/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable Long id, @PathVariable String username) {
        school.withdrawStudent(id, username);
    }

    // ----- courses -------------------------------------------------------------

    /**
     * GET /api/courses - what the caller is involved in.
     *
     * For a student this response IS the answer to "which subjects am I taught
     * and by whom" - the requirement about several teachers and several subjects
     * needs no separate endpoint, because it falls out of the course list.
     */
    @GetMapping("/courses")
    public List<CourseView> listCourses() {
        return school.listCourses();
    }

    /** GET /api/courses/all - every course in the school. Teacher only. */
    @GetMapping("/courses/all")
    public List<CourseView> listAllCourses() {
        return school.listAllCourses();
    }

    @PostMapping("/courses")
    public CourseView createCourse(@Valid @RequestBody CreateCourseRequest request) {
        return school.createCourse(
                request.getSubjectId(), request.getClassId(), request.getTeacherUsername());
    }

    // ----- request bodies ------------------------------------------------------

    static class CreateSubjectRequest {

        @NotBlank(message = "Subject code must not be blank")
        @Size(max = 20, message = "Subject code must be at most 20 characters")
        private String code;

        @NotBlank(message = "Subject name must not be blank")
        @Size(max = 100, message = "Subject name must be at most 100 characters")
        private String name;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class CreateClassRequest {

        @NotBlank(message = "Class name must not be blank")
        @Size(max = 50, message = "Class name must be at most 50 characters")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class EnrolRequest {

        /**
         * @Size mirrors app_user.username NVARCHAR(50) - the same bound every
         * other username field in this application carries (UserController,
         * AuthController). Without it, this was the one place a client could
         * send an arbitrarily long username past bean validation; it would
         * still be refused, but only by the database's column length, several
         * layers further in and with a generic "violates a data constraint"
         * message rather than one naming the actual limit.
         */
        @NotBlank(message = "Username must not be blank")
        @Size(max = 50, message = "Username must be at most 50 characters")
        private String username;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    static class CreateCourseRequest {

        @NotNull(message = "Choose a subject")
        private Long subjectId;

        @NotNull(message = "Choose a class")
        private Long classId;

        /** Optional: defaults to the teacher making the request. */
        private String teacherUsername;

        public Long getSubjectId() {
            return subjectId;
        }

        public void setSubjectId(Long subjectId) {
            this.subjectId = subjectId;
        }

        public Long getClassId() {
            return classId;
        }

        public void setClassId(Long classId) {
            this.classId = classId;
        }

        public String getTeacherUsername() {
            return teacherUsername;
        }

        public void setTeacherUsername(String teacherUsername) {
            this.teacherUsername = teacherUsername;
        }
    }
}
