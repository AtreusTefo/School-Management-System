package com.example.tracker.controller;

import com.example.tracker.dto.AuditLogView;
import com.example.tracker.dto.CourseView;
import com.example.tracker.dto.ImportSummary;
import com.example.tracker.dto.StudentView;
import com.example.tracker.dto.TeacherView;
import com.example.tracker.model.AuditAction;
import com.example.tracker.service.AdminService;
import com.example.tracker.service.AuditLogService;
import com.example.tracker.service.SchoolService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER for the admin panel: onboarding students and
 * teachers (one at a time or in bulk), the teacher-to-student relationship,
 * and the audit log.
 *
 * HTTP only, like every controller here - it does not decide who may be an
 * admin, what a student's profile must contain, or what counts as a
 * duplicate national id; every one of those questions belongs to
 * AdminService, SchoolService or AuditLogService, and this class only
 * translates a request into a call and a result into a response.
 *
 * ONE CONTROLLER FOR THREE SERVICES, DELIBERATELY - see SchoolController for
 * the identical reasoning applied to subjects/classes/courses: these are
 * views of one admin panel, reached by the same kind of caller, and
 * splitting them would spread one concept across files that would then have
 * to agree with each other for no reader's benefit.
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private final AdminService admin;
    private final SchoolService school;
    private final AuditLogService auditLog;

    public AdminController(AdminService admin, SchoolService school, AuditLogService auditLog) {
        this.admin = admin;
        this.school = school;
        this.auditLog = auditLog;
    }

    // ----- teachers --------------------------------------------------------

    @GetMapping("/teachers")
    public List<TeacherView> listTeachers() {
        return admin.listTeachers();
    }

    /**
     * POST /api/teachers - onboard a teacher. 201 Created, and the response
     * deliberately carries no password - the caller supplied the temporary
     * one, so echoing it back would put a live credential into logs and
     * browser history for nothing. The account's actual sign-in id (TCH...)
     * IS in the response, since that is what the admin needs to hand the new
     * teacher along with the password.
     */
    @PostMapping("/teachers")
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherView createTeacher(@Valid @RequestBody CreateTeacherRequest request) {
        List<AdminService.CourseAssignment> initialCourses = request.getInitialCourses() == null
                ? null
                : request.getInitialCourses().stream()
                        .map(c -> new AdminService.CourseAssignment(c.getSubjectId(), c.getClassId()))
                        .toList();
        return admin.createTeacher(
                request.getFirstName(), request.getMiddleName(), request.getLastName(),
                request.getNationalId(), request.getDateOfBirth(), request.getEmail(),
                request.getDateEnrolled(), request.getTemporaryPassword(), initialCourses);
    }

    /** PUT /api/teachers/{id}/password - issue a new temporary password. */
    @PutMapping("/teachers/{id}/password")
    public TeacherView resetTeacherPassword(@PathVariable Long id,
                                            @Valid @RequestBody ResetPasswordRequest request) {
        return admin.resetTeacherPassword(id, request.getTemporaryPassword());
    }

    @DeleteMapping("/teachers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTeacher(@PathVariable Long id) {
        admin.deleteTeacher(id);
    }

    /**
     * POST /api/teachers/import - bulk onboard from a CSV or Excel file.
     * Never fails the whole request over a bad row - see AdminService for
     * why - so this always answers 200 with a per-row report, even when
     * every row failed.
     */
    @PostMapping("/teachers/import")
    public ImportSummary importTeachers(@RequestParam("file") MultipartFile file) {
        return admin.importTeachers(readBytes(file));
    }

    // ----- students, and who teaches them ---------------------------------------

    @GetMapping("/students")
    public List<StudentView> listStudents() {
        return school.listStudents();
    }

    /**
     * POST /api/students - onboard a student. classId is optional: a
     * student can exist before they are placed in a class.
     */
    @PostMapping("/students")
    @ResponseStatus(HttpStatus.CREATED)
    public StudentView createStudent(@Valid @RequestBody CreateStudentRequest request) {
        return admin.createStudent(
                request.getFirstName(), request.getMiddleName(), request.getLastName(),
                request.getNationalId(), request.getDateOfBirth(), request.getEmail(),
                request.getDateEnrolled(), request.getTemporaryPassword(), request.getClassId());
    }

    /** POST /api/students/import - bulk onboard from a CSV or Excel file. */
    @PostMapping("/students/import")
    public ImportSummary importStudents(@RequestParam("file") MultipartFile file) {
        return admin.importStudents(readBytes(file));
    }

    /** GET /api/students/{id}/teachers - who currently teaches this student, by subject. */
    @GetMapping("/students/{id}/teachers")
    public List<CourseView> listTeachersForStudent(@PathVariable Long id) {
        return school.listCoursesForStudent(id);
    }

    /**
     * POST /api/students/{studentId}/teachers/{teacherId} - grant a teacher
     * access to this student, for one subject.
     *
     * The subject is a required body field rather than being guessed: a
     * teacher-to-student relationship in this system is really "teaches them
     * a subject" (see Course), and there is no reasonable default for which
     * subject an admin means.
     */
    @PostMapping("/students/{studentId}/teachers/{teacherId}")
    public CourseView assignTeacher(@PathVariable Long studentId, @PathVariable Long teacherId,
                                    @Valid @RequestBody AssignTeacherRequest request) {
        return school.assignTeacherToStudent(studentId, teacherId, request.getSubjectId());
    }

    /**
     * DELETE /api/students/{studentId}/teachers/{teacherId} - withdraw that
     * access. The subject is a query parameter rather than a body: DELETE
     * with a body is poorly supported by intermediaries, the same reasoning
     * SchoolController's withdraw endpoint already follows.
     */
    @DeleteMapping("/students/{studentId}/teachers/{teacherId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassignTeacher(@PathVariable Long studentId, @PathVariable Long teacherId,
                                @RequestParam Long subjectId) {
        school.unassignTeacherFromStudent(studentId, teacherId, subjectId);
    }

    // ----- admins ------------------------------------------------------------------

    /** POST /api/admins - onboard another admin. No profile form; see AdminService. */
    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthController.UserView createAdmin(@Valid @RequestBody CreateAdminRequest request) {
        return admin.createAdmin(request.getTemporaryPassword());
    }

    // ----- audit log -------------------------------------------------------------

    /**
     * GET /api/audit-logs - paginated, filterable. Every filter is optional;
     * `page`/`size`/`sort` are handled automatically by Spring Data's
     * Pageable resolver, the same mechanism every Spring Data repository in
     * this application already relies on.
     */
    @GetMapping("/audit-logs")
    public Page<AuditLogView> auditLogs(
            @RequestParam(required = false) String entityName,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pageable) {
        return auditLog.search(entityName, action, from, to, pageable);
    }

    // ----- shared -------------------------------------------------------------------

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
    }

    // ----- request bodies --------------------------------------------------------

    /**
     * The profile fields shared by the student and teacher onboarding forms.
     * A plain superclass rather than a shared interface, so @Valid walks
     * every field through one set of annotations regardless of which
     * subclass carries them.
     */
    abstract static class PersonRequest {
        @NotBlank(message = "First name must not be blank")
        @Size(max = 50, message = "First name must be at most 50 characters")
        private String firstName;

        @Size(max = 50, message = "Middle name must be at most 50 characters")
        private String middleName;

        @NotBlank(message = "Last name must not be blank")
        @Size(max = 50, message = "Last name must be at most 50 characters")
        private String lastName;

        @NotBlank(message = "National id must not be blank")
        @Size(max = 20, message = "National id must be at most 20 characters")
        private String nationalId;

        @NotNull(message = "Date of birth is required")
        private LocalDate dateOfBirth;

        @Email(message = "Enter a valid email address")
        @Size(max = 100, message = "Email must be at most 100 characters")
        private String email;

        /** Optional - AdminService defaults to today when this is null. */
        private LocalDate dateEnrolled;

        @NotBlank(message = "Temporary password must not be blank")
        @Size(min = 8, message = "Temporary password must be at least 8 characters")
        private String temporaryPassword;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getMiddleName() {
            return middleName;
        }

        public void setMiddleName(String middleName) {
            this.middleName = middleName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getNationalId() {
            return nationalId;
        }

        public void setNationalId(String nationalId) {
            this.nationalId = nationalId;
        }

        public LocalDate getDateOfBirth() {
            return dateOfBirth;
        }

        public void setDateOfBirth(LocalDate dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public LocalDate getDateEnrolled() {
            return dateEnrolled;
        }

        public void setDateEnrolled(LocalDate dateEnrolled) {
            this.dateEnrolled = dateEnrolled;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }

    static class CreateStudentRequest extends PersonRequest {
        /** Optional - a student may exist before being placed in a class. */
        private Long classId;

        public Long getClassId() {
            return classId;
        }

        public void setClassId(Long classId) {
            this.classId = classId;
        }
    }

    static class CreateTeacherRequest extends PersonRequest {
        /** Optional - which subjects, for which classes, this teacher starts out teaching. */
        private List<CourseAssignmentRequest> initialCourses;

        public List<CourseAssignmentRequest> getInitialCourses() {
            return initialCourses;
        }

        public void setInitialCourses(List<CourseAssignmentRequest> initialCourses) {
            this.initialCourses = initialCourses;
        }
    }

    static class CourseAssignmentRequest {
        @NotNull(message = "Choose a subject")
        private Long subjectId;

        @NotNull(message = "Choose a class")
        private Long classId;

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
    }

    static class CreateAdminRequest {
        @NotBlank(message = "Temporary password must not be blank")
        @Size(min = 8, message = "Temporary password must be at least 8 characters")
        private String temporaryPassword;

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }

    static class ResetPasswordRequest {
        @NotBlank(message = "Temporary password must not be blank")
        @Size(min = 8, message = "Temporary password must be at least 8 characters")
        private String temporaryPassword;

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }

    static class AssignTeacherRequest {
        @NotNull(message = "Choose a subject")
        private Long subjectId;

        public Long getSubjectId() {
            return subjectId;
        }

        public void setSubjectId(Long subjectId) {
            this.subjectId = subjectId;
        }
    }
}
