package com.example.tracker.service;

import com.example.tracker.dto.TeacherView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.CourseRepository;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for admin-only teacher account management.
 *
 * SEPARATE FROM AppUserService ON PURPOSE - the same reasoning UserController
 * already applies to itself: AppUserService answers "who is making this
 * request", a question every request needs answered regardless of role. This
 * answers a narrower one - "what may an ADMIN do to somebody ELSE's account" -
 * and giving it its own class keeps that authority rule out of the one class
 * every other service depends on.
 *
 * WHY THIS DOES NOT LIVE INSIDE AppUserService
 * AuditLogService depends on AppUserService (to gate its own search() to
 * admins). If admin account-management lived in AppUserService, AppUserService
 * would need to depend on AuditLogService to log it - and that is a cycle
 * Spring's constructor injection cannot resolve: AppUserService ->
 * AuditLogService -> AppUserService. Putting these operations in a class
 * ABOVE both breaks the cycle: AdminService depends on AppUserService (to
 * reuse currentActiveUser()) and on AuditLogService (to write entries), and
 * nothing depends on AdminService in return.
 */
@Service
@Transactional(readOnly = true)
public class AdminService {

    private final AppUserRepository users;
    private final CourseRepository courses;
    private final AssignmentRepository assignments;
    private final PasswordEncoder encoder;
    private final AppUserService currentUser;
    private final AuditLogService audit;

    public AdminService(AppUserRepository users, CourseRepository courses,
                        AssignmentRepository assignments, PasswordEncoder encoder,
                        AppUserService currentUser, AuditLogService audit) {
        this.users = users;
        this.courses = courses;
        this.assignments = assignments;
        this.encoder = encoder;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ----- reading ---------------------------------------------------------

    public List<TeacherView> listTeachers() {
        requireAdmin("view the teacher list");
        return users.findByRoleOrderByUsernameAsc(Role.TEACHER).stream()
                .map(TeacherView::of).toList();
    }

    // ----- writing -----------------------------------------------------------

    /**
     * Onboard a teacher. Mirrors AppUserService.createStudent's shape - role
     * hardcoded rather than a parameter, so no request shape can ask for
     * anything but a TEACHER, and the new account is marked pending so the
     * temporary password this admin necessarily knows has to be replaced
     * before the account can be used.
     */
    @Transactional
    public TeacherView createTeacher(String username, String temporaryPassword) {
        AppUser me = requireAdmin("create a teacher account");

        String name = requireUsername(username);
        requireTemporaryPasswordLength(temporaryPassword);

        /*
         * A courtesy check, not the guarantee - uq_app_user_username is what
         * actually refuses a duplicate written by two admins at once.
         */
        if (users.findByUsername(name).isPresent()) {
            throw new IllegalStateException("An account named '" + name + "' already exists.");
        }

        AppUser created = users.save(new AppUser(
                name, encoder.encode(temporaryPassword), Role.TEACHER, true));

        audit.record("AppUser", created.getId(), AuditAction.CREATE, me,
                "Created teacher account '" + name + "'.");

        return TeacherView.of(created);
    }

    /**
     * Rename a teacher's account.
     *
     * The only field left worth editing once an account exists: username,
     * password hash, role and the pending flag are the whole row (see
     * AppUser), and role is never editable through this endpoint for the same
     * reason it is never a parameter to createTeacher.
     */
    @Transactional
    public TeacherView renameTeacher(Long id, String newUsername) {
        AppUser me = requireAdmin("rename a teacher account");
        AppUser teacher = requireTeacher(id);

        String name = requireUsername(newUsername);
        users.findByUsername(name)
                .filter(existing -> !existing.getId().equals(teacher.getId()))
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "An account named '" + name + "' already exists.");
                });

        String oldName = teacher.getUsername();
        teacher.setUsername(name);

        audit.record("AppUser", teacher.getId(), AuditAction.UPDATE, me,
                "Renamed teacher account '" + oldName + "' to '" + name + "'.");

        return TeacherView.of(teacher);
    }

    /**
     * Issue a new temporary password for a teacher who has lost theirs.
     *
     * Mirrors account creation: the account is marked pending again, so the
     * temporary password an admin necessarily knows has to be replaced before
     * the account can be used for anything - exactly the guarantee that makes
     * a teacher-issued student password safe, applied one level up.
     */
    @Transactional
    public TeacherView resetTeacherPassword(Long id, String temporaryPassword) {
        AppUser me = requireAdmin("reset a teacher's password");
        AppUser teacher = requireTeacher(id);

        requireTemporaryPasswordLength(temporaryPassword);
        teacher.setPasswordHash(encoder.encode(temporaryPassword));
        teacher.setMustChangePassword(true);

        audit.record("AppUser", teacher.getId(), AuditAction.UPDATE, me,
                "Reset the password for teacher '" + teacher.getUsername() + "'.");

        return TeacherView.of(teacher);
    }

    /**
     * Remove a teacher account.
     *
     * REFUSED while the teacher still owns any course or has set any
     * assignment. Neither fk_course_teacher nor fk_assignment_created_by
     * carries ON DELETE CASCADE (see V2/V4) - destroying a teacher's courses
     * and assignments as a side effect of removing their account must be an
     * explicit decision, not a consequence nobody chose. The database would
     * refuse the delete outright either way; checking first turns an opaque
     * 400 (DataIntegrityViolationException) into a 409 naming exactly what is
     * still attached.
     */
    @Transactional
    public void deleteTeacher(Long id) {
        AppUser me = requireAdmin("delete a teacher account");
        AppUser teacher = requireTeacher(id);

        if (courses.existsByTeacher(teacher) || assignments.existsByCreatedBy(teacher)) {
            throw new IllegalStateException(
                    "'" + teacher.getUsername() + "' still teaches at least one course or has "
                            + "set at least one assignment, and cannot be deleted. Reassign or "
                            + "remove those first.");
        }

        String name = teacher.getUsername();
        Long teacherId = teacher.getId();
        users.delete(teacher);

        audit.record("AppUser", teacherId, AuditAction.DELETE, me,
                "Deleted teacher account '" + name + "'.");
    }

    // ----- shared guards -------------------------------------------------------

    private AppUser requireAdmin(String action) {
        AppUser me = currentUser.currentActiveUser();
        if (me.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin can " + action + ".");
        }
        return me;
    }

    @NonNull
    private AppUser requireTeacher(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Teacher id must not be null.");
        }
        AppUser teacher = Require.orThrow(users.findById(id),
                () -> new ResourceNotFoundException("teacher", id));
        if (teacher.getRole() != Role.TEACHER) {
            throw new IllegalArgumentException(
                    "'" + teacher.getUsername() + "' is a " + teacher.getRole()
                            + ", not a teacher.");
        }
        return teacher;
    }

    private String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank.");
        }
        return username.trim();
    }

    private void requireTemporaryPasswordLength(String password) {
        if (password == null || password.length() < AppUserService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "The temporary password must be at least "
                            + AppUserService.MIN_PASSWORD_LENGTH + " characters.");
        }
    }
}
