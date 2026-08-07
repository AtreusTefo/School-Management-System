package com.example.tracker.controller;

import com.example.tracker.dto.AuditLogView;
import com.example.tracker.dto.CourseView;
import com.example.tracker.dto.StudentView;
import com.example.tracker.dto.TeacherView;
import com.example.tracker.model.AuditAction;
import com.example.tracker.service.AdminService;
import com.example.tracker.service.AuditLogService;
import com.example.tracker.service.SchoolService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER for the admin panel: teacher accounts, the
 * teacher-to-student relationship, and the audit log.
 *
 * HTTP only, like every controller here - it does not decide who may be an
 * admin, what a teacher account may contain, or what counts as a duplicate
 * assignment; every one of those questions belongs to AdminService,
 * SchoolService or AuditLogService, and this class only translates a request
 * into a call and a result into a response.
 *
 * ONE CONTROLLER FOR THREE SERVICES, DELIBERATELY. These are three FEATures
 * of one EPIC - "Admin Management" - reached through the same panel by the
 * same kind of caller, the same way SchoolController already gathers
 * subjects, classes and courses under one roof because they are four views
 * of one timetable. Splitting this into AdminTeacherController,
 * AdminStudentController and AuditLogController would spread one concept
 * across files that would then have to agree with each other for no reader's
 * benefit.
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
     * POST /api/teachers - onboard a teacher.
     *
     * 201 Created, and the response deliberately carries no password - see
     * UserController.create for the identical reasoning: the caller supplied
     * the temporary one, so echoing it back would put a live credential into
     * logs and browser history for nothing.
     */
    @PostMapping("/teachers")
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherView createTeacher(@Valid @RequestBody CreateTeacherRequest request) {
        return admin.createTeacher(request.getUsername(), request.getTemporaryPassword());
    }

    @PutMapping("/teachers/{id}")
    public TeacherView renameTeacher(@PathVariable Long id,
                                     @Valid @RequestBody RenameTeacherRequest request) {
        return admin.renameTeacher(id, request.getUsername());
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

    // ----- students, and who teaches them ---------------------------------------

    @GetMapping("/students")
    public List<StudentView> listStudents() {
        return school.listStudents();
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

    // ----- request bodies --------------------------------------------------------

    static class CreateTeacherRequest {
        @NotBlank(message = "Username must not be blank")
        @Size(max = 50, message = "Username must be at most 50 characters")
        private String username;

        @NotBlank(message = "Temporary password must not be blank")
        @Size(min = 8, message = "Temporary password must be at least 8 characters")
        private String temporaryPassword;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }

    static class RenameTeacherRequest {
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
