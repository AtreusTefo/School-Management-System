package com.example.tracker.service;

import com.example.tracker.controller.AuthController;
import com.example.tracker.dto.ImportSummary;
import com.example.tracker.dto.StudentView;
import com.example.tracker.dto.TeacherView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.PersonProfile;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AttendanceRepository;
import com.example.tracker.repository.PasswordResetTokenRepository;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.AssessmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.PersonProfileRepository;
import com.example.tracker.repository.SchoolClassRepository;
import com.example.tracker.repository.SubjectRepository;
import com.example.tracker.repository.SubmissionRepository;
import com.example.tracker.validation.PersonPatterns;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for admin-only account management: onboarding
 * students and teachers (one at a time or in bulk), issuing the id each signs
 * in with, and removing a teacher account.
 *
 * SEPARATE FROM AppUserService ON PURPOSE - the same reasoning UserController
 * used to apply to itself: AppUserService answers "who is making this
 * request", a question every request needs answered regardless of role. This
 * answers a narrower one - "what may an ADMIN do to somebody ELSE's account" -
 * and giving it its own class keeps that authority rule out of the one class
 * every other service depends on.
 *
 * WHY THIS DOES NOT LIVE INSIDE AppUserService
 * AuditLogService depends on AppUserRepository (to gate its own search() to
 * admins) rather than on AppUserService, precisely so AppUserService can
 * depend on AuditLogService without a cycle. Admin account-management adds a
 * THIRD party to that graph; keeping it in a class above both is what lets it
 * depend on AppUserService (to reuse currentActiveUser()) and AuditLogService
 * (to write entries) with nothing depending back on it.
 *
 * EVERY LOGIN ID IS GENERATED, NEVER CHOSEN
 * A student's, teacher's or admin's account created here signs in with
 * STU20260001 / TCH20260001 / ADM20260001 - see IdGeneratorService. There is
 * no "rename" operation for these accounts for exactly that reason: a
 * system-issued id that could later be edited would not be one any more.
 * (The seeded development accounts - teacher, student, admin - predate this
 * rule and keep their literal usernames; see AppUser.externalId.)
 */
@Service
@Transactional(readOnly = true)
public class AdminService {

    /** Mirrors PersonProfile's own column bound, and the admin form's field. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /**
     * A Botswana mobile number in E.164 form - mirrors PersonPatterns.PHONE,
     * compiled once rather than re-parsed on every call.
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile(PersonPatterns.PHONE);

    /**
     * The Omang's shape, checked HERE as well as on the request DTO.
     *
     * Not redundant: bulk import never builds a PersonRequest, so the
     * @Pattern on that class does not run for a CSV row. Without this check a
     * spreadsheet could carry any string into saveProfile, and the first thing
     * to object would be ck_person_profile_omang at the database - which
     * arrives as a constraint violation rather than as "row 14: ...", losing
     * the one piece of information the importer needs.
     */
    private static final Pattern NATIONAL_ID_PATTERN =
            Pattern.compile(PersonPatterns.NATIONAL_ID);

    private final AppUserRepository users;
    private final PersonProfileRepository profiles;
    private final CourseRepository courses;
    private final AssignmentRepository assignments;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final EnrolmentRepository enrolments;
    private final SubmissionRepository submissions;
    private final AssessmentRepository assessments;
    private final AttendanceRepository attendance;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder encoder;
    private final AppUserService currentUser;
    private final AuditLogService audit;
    private final IdGeneratorService ids;
    private final ImportRowReader importRowReader;
    private final TransactionTemplate newTransaction;

    public AdminService(AppUserRepository users, PersonProfileRepository profiles,
                        CourseRepository courses, AssignmentRepository assignments,
                        SchoolClassRepository classes, SubjectRepository subjects,
                        EnrolmentRepository enrolments,
                        SubmissionRepository submissions,
                        AssessmentRepository assessments,
                        AttendanceRepository attendance,
                        PasswordResetTokenRepository resetTokens,
                        PasswordEncoder encoder,
                        AppUserService currentUser, AuditLogService audit,
                        IdGeneratorService ids, ImportRowReader importRowReader,
                        @NonNull PlatformTransactionManager transactionManager) {
        this.attendance = attendance;
        this.resetTokens = resetTokens;
        this.users = users;
        this.profiles = profiles;
        this.courses = courses;
        this.assignments = assignments;
        this.classes = classes;
        this.subjects = subjects;
        this.enrolments = enrolments;
        this.submissions = submissions;
        this.assessments = assessments;
        this.encoder = encoder;
        this.currentUser = currentUser;
        this.audit = audit;
        this.ids = ids;
        this.importRowReader = importRowReader;
        /*
         * Bulk import needs each ROW in its own transaction, not one big one
         * for the whole file - see importStudents. TransactionTemplate with
         * PROPAGATION_REQUIRES_NEW is how that is done explicitly; calling
         * this class's own @Transactional methods from a loop INSIDE this
         * class would not work, because self-invocation bypasses the Spring
         * proxy that @Transactional relies on entirely.
         *
         * transactionManager is @NonNull on the parameter above because
         * TransactionTemplate's own constructor requires it and Spring
         * genuinely guarantees it: a constructor-injected dependency is
         * either satisfied with a real bean or the context fails to start.
         * There is no third case in which a null arrives here, so the
         * annotation states a fact rather than silencing a warning.
         */
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ----- reading ---------------------------------------------------------

    public List<TeacherView> listTeachers() {
        requireAdmin("view the teacher list");
        return users.findByRoleOrderByUsernameAsc(Role.TEACHER).stream()
                .map(teacher -> TeacherView.of(teacher, profiles.findByAppUser(teacher).orElse(null)))
                .toList();
    }

    /**
     * Every admin account. ADMIN ONLY, same as the teacher and student lists.
     *
     * NO PersonProfile IS RETURNED, because none is collected - "Admin
     * accounts do not get one - only the student and teacher forms ask for
     * this detail" is a deliberate, documented decision (see the Scope Guard
     * in CLAUDE.md), not a gap this method works around. AuthController's own
     * UserView (id, username, role, mustChangePassword) is already the exact
     * shape an admin account has to show; reusing it here says that plainly
     * instead of inventing an AdminView that would carry null profile fields
     * nobody could ever fill in.
     */
    public List<AuthController.UserView> listAdmins() {
        requireAdmin("view the admin list");
        return users.findByRoleOrderByUsernameAsc(Role.ADMIN).stream()
                .map(AuthController.UserView::of)
                .toList();
    }

    // ----- single-record creation ------------------------------------------------

    /**
     * Onboard a student. ADMIN ONLY - the account's login id (STU...) is
     * generated here, never supplied, and classId is optional: a student can
     * exist before they are placed in a class, the same as one created
     * through the timetable's own enrol endpoint always could.
     */
    @Transactional
    public StudentView createStudent(String firstName, String middleName, String lastName,
                                     String nationalId, LocalDate dateOfBirth, String contactEmail,
                                     String phone, LocalDate dateEnrolled, String temporaryPassword,
                                     Long classId) {
        AppUser me = requireAdmin("create a student account");
        requireTemporaryPasswordLength(temporaryPassword);

        String externalId = ids.nextStudentId();
        AppUser created = users.save(new AppUser(
                externalId, encoder.encode(temporaryPassword), Role.STUDENT, true, externalId));

        PersonProfile profile = saveProfile(created, firstName, middleName, lastName,
                nationalId, dateOfBirth, contactEmail, phone, dateEnrolled);

        String className = null;
        if (classId != null) {
            SchoolClass schoolClass = requireClass(classId);
            Enrolment enrolment = enrolments.save(new Enrolment(created, schoolClass));
            className = schoolClass.getName();
            audit.record("Enrolment", enrolment.getId(), AuditAction.CREATE, me,
                    "Enrolled '" + externalId + "' in " + schoolClass.getName() + ".");
        }

        audit.record("AppUser", created.getId(), AuditAction.CREATE, me,
                "Created student '" + profile.getFullName() + "' (" + externalId + ").");

        return StudentView.of(created, className, profile);
    }

    /**
     * Onboard a teacher. ADMIN ONLY. initialCourses is optional - each entry
     * is one (subject, class) pair this teacher will teach from day one,
     * written through CourseRepository exactly as SchoolService.createCourse
     * would, so "which subjects they teach" stays the timetable's own
     * relationship rather than a second copy of it on this account.
     */
    @Transactional
    public TeacherView createTeacher(String firstName, String middleName, String lastName,
                                     String nationalId, LocalDate dateOfBirth, String contactEmail,
                                     String phone, LocalDate dateEnrolled, String temporaryPassword,
                                     List<CourseAssignment> initialCourses) {
        AppUser me = requireAdmin("create a teacher account");
        requireTemporaryPasswordLength(temporaryPassword);

        String externalId = ids.nextTeacherId();
        AppUser created = users.save(new AppUser(
                externalId, encoder.encode(temporaryPassword), Role.TEACHER, true, externalId));

        PersonProfile profile = saveProfile(created, firstName, middleName, lastName,
                nationalId, dateOfBirth, contactEmail, phone, dateEnrolled);

        if (initialCourses != null) {
            for (CourseAssignment assignment : initialCourses) {
                Subject subject = requireSubject(assignment.subjectId());
                SchoolClass schoolClass = requireClass(assignment.classId());
                Course course = courses.save(new Course(subject, schoolClass, created));
                audit.record("Course", course.getId(), AuditAction.CREATE, me,
                        "'" + externalId + "' now teaches " + subject.getName()
                                + " to " + schoolClass.getName() + ".");
            }
        }

        audit.record("AppUser", created.getId(), AuditAction.CREATE, me,
                "Created teacher '" + profile.getFullName() + "' (" + externalId + ").");

        return TeacherView.of(created, profile);
    }

    /**
     * Onboard another admin. No profile is collected - only the student and
     * teacher forms ask for one - but the account still receives a generated
     * id (ADM...) for the same reason a teacher's does: a chosen username
     * would be one more thing this system would have to trust an admin typed
     * correctly and uniquely, for no benefit over generating it.
     */
    @Transactional
    public AuthController.UserView createAdmin(String temporaryPassword) {
        AppUser me = requireAdmin("create an admin account");
        requireTemporaryPasswordLength(temporaryPassword);

        String externalId = ids.nextAdminId();
        AppUser created = users.save(new AppUser(
                externalId, encoder.encode(temporaryPassword), Role.ADMIN, true, externalId));

        audit.record("AppUser", created.getId(), AuditAction.CREATE, me,
                "Created admin account '" + externalId + "'.");

        return AuthController.UserView.of(created);
    }

    // ----- bulk import -------------------------------------------------------------

    /**
     * Import students from a CSV or Excel file. ADMIN ONLY.
     *
     * Columns (header row required, case-insensitive): firstName, middleName
     * (optional), lastName, nationalId, dateOfBirth (yyyy-MM-dd), email
     * (optional), className (optional - must already exist), dateEnrolled
     * (optional, defaults to today).
     *
     * EACH ROW IS ITS OWN TRANSACTION, so three bad rows out of two hundred
     * cost three rows, not the other 197 - see the constructor's note on
     * TransactionTemplate. A temporary password is generated per row (see
     * generateTemporaryPassword) since asking a spreadsheet to carry two
     * hundred hand-chosen passwords is not realistic, and it is returned in
     * the row's own result - the one time this system says a password in
     * plain text - so the admin can distribute it.
     */
    public ImportSummary importStudents(byte[] content) {
        AppUser me = requireAdmin("import students");
        List<Map<String, String>> rows = readRows(content);
        List<ImportSummary.ImportRowResult> results = new ArrayList<>();
        int succeeded = 0;

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + 2; // header is row 1
            results.add(importOneRowSafely(rowNumber, rows.get(i),
                    row -> importOneStudent(me, rowNumber, row)));
            if (results.get(results.size() - 1).success()) {
                succeeded++;
            }
        }

        return new ImportSummary(rows.size(), succeeded, rows.size() - succeeded, results);
    }

    /** Same shape as importStudents; see that method for the general approach. */
    public ImportSummary importTeachers(byte[] content) {
        AppUser me = requireAdmin("import teachers");
        List<Map<String, String>> rows = readRows(content);
        List<ImportSummary.ImportRowResult> results = new ArrayList<>();
        int succeeded = 0;

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + 2;
            results.add(importOneRowSafely(rowNumber, rows.get(i),
                    row -> importOneTeacher(me, rowNumber, row)));
            if (results.get(results.size() - 1).success()) {
                succeeded++;
            }
        }

        return new ImportSummary(rows.size(), succeeded, rows.size() - succeeded, results);
    }

    /**
     * Runs one row's import in its OWN transaction and turns a failure into a
     * result row rather than letting it propagate.
     *
     * THE try/catch IS OUT HERE, NOT INSIDE THE TRANSACTIONAL CALLBACK.
     * TransactionTemplate only rolls back what a callback did if a
     * RuntimeException actually escapes the callback - catching one INSIDE
     * it and returning a "failed" result instead would look identical from
     * out here, but the transaction would then COMMIT whatever had already
     * been written before the failure (an AppUser row with no profile, for
     * instance, if saveProfile is what threw). Letting the exception
     * propagate out of execute() first, so TransactionTemplate rolls back,
     * and only THEN catching it, is what keeps a failed row's partial writes
     * from ever reaching the database at all.
     */
    private ImportSummary.ImportRowResult importOneRowSafely(
            int rowNumber, Map<String, String> row,
            java.util.function.Function<Map<String, String>, ImportSummary.ImportRowResult> work) {
        try {
            return newTransaction.execute(status -> work.apply(row));
        } catch (RuntimeException e) {
            return new ImportSummary.ImportRowResult(
                    rowNumber, false, cellOrNull(row, "firstName"), null, null, e.getMessage());
        }
    }

    private List<Map<String, String>> readRows(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }
        return importRowReader.read(content);
    }

    /** Exceptions propagate deliberately - see importOneRowSafely for why. */
    private ImportSummary.ImportRowResult importOneStudent(
            AppUser me, int rowNumber, Map<String, String> row) {
        String firstName = cell(row, "firstName");
        String middleName = cellOrNull(row, "middleName");
        String lastName = cell(row, "lastName");
        String nationalId = cell(row, "nationalId");
        LocalDate dateOfBirth = parseDate(cell(row, "dateOfBirth"), "dateOfBirth");
        String email = cellOrNull(row, "email");
        // Optional, unlike on the single-record admin form - a spreadsheet a
        // school already has does not gain a phone column overnight. See
        // saveProfile's own comment on why presence is a caller decision.
        String phone = cellOrNull(row, "phone");
        String className = cellOrNull(row, "className");
        LocalDate dateEnrolled = parseOptionalDate(cellOrNull(row, "dateEnrolled"));
        String temporaryPassword = generateTemporaryPassword();

        String externalId = ids.nextStudentId();
        AppUser created = users.save(new AppUser(
                externalId, encoder.encode(temporaryPassword), Role.STUDENT, true, externalId));
        PersonProfile profile = saveProfile(created, firstName, middleName, lastName,
                nationalId, dateOfBirth, email, phone, dateEnrolled);

        if (className != null) {
            SchoolClass schoolClass = classes.findByNameIgnoreCase(className)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No class named '" + className + "'."));
            Enrolment enrolment = enrolments.save(new Enrolment(created, schoolClass));
            audit.record("Enrolment", enrolment.getId(), AuditAction.CREATE, me,
                    "Enrolled '" + externalId + "' in " + schoolClass.getName() + ".");
        }

        audit.record("AppUser", created.getId(), AuditAction.CREATE, me,
                "Imported student '" + profile.getFullName() + "' (" + externalId + ").");

        return new ImportSummary.ImportRowResult(
                rowNumber, true, profile.getFullName(), externalId, temporaryPassword, null);
    }

    /** Exceptions propagate deliberately - see importOneRowSafely for why. */
    private ImportSummary.ImportRowResult importOneTeacher(
            AppUser me, int rowNumber, Map<String, String> row) {
        String firstName = cell(row, "firstName");
        String middleName = cellOrNull(row, "middleName");
        String lastName = cell(row, "lastName");
        String nationalId = cell(row, "nationalId");
        LocalDate dateOfBirth = parseDate(cell(row, "dateOfBirth"), "dateOfBirth");
        String email = cellOrNull(row, "email");
        String phone = cellOrNull(row, "phone");
        LocalDate dateEnrolled = parseOptionalDate(cellOrNull(row, "dateEnrolled"));
        String temporaryPassword = generateTemporaryPassword();

        String externalId = ids.nextTeacherId();
        AppUser created = users.save(new AppUser(
                externalId, encoder.encode(temporaryPassword), Role.TEACHER, true, externalId));
        PersonProfile profile = saveProfile(created, firstName, middleName, lastName,
                nationalId, dateOfBirth, email, phone, dateEnrolled);

        audit.record("AppUser", created.getId(), AuditAction.CREATE, me,
                "Imported teacher '" + profile.getFullName() + "' (" + externalId + ").");

        return new ImportSummary.ImportRowResult(
                rowNumber, true, profile.getFullName(), externalId, temporaryPassword, null);
    }

    // ----- teacher account maintenance ----------------------------------------------

    /**
     * Issue a new temporary password for a teacher who has lost theirs.
     * Mirrors account creation: the account is marked pending again, so the
     * temporary password an admin necessarily knows has to be replaced
     * before the account can be used for anything.
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

        return TeacherView.of(teacher, profiles.findByAppUser(teacher).orElse(null));
    }

    /**
     * Remove a teacher account.
     *
     * REFUSED while the teacher still owns any course or has set any
     * assignment - see V2/V4 for why neither foreign key cascades. The
     * profile row (if any) is deleted explicitly in the same transaction,
     * the same "explicit, not cascaded" policy applied one table further.
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

        /*
         * Any outstanding reset link dies with the account. Unlike an audit
         * entry - which must outlive the account it names, and so stores a
         * snapshot rather than a reference - a reset token is meaningless
         * without its account, and fk_password_reset_token_user does not
         * cascade, so leaving one behind would simply make the delete fail.
         */
        resetTokens.deleteByAppUser(teacher);
        profiles.findByAppUser(teacher).ifPresent(profiles::delete);

        String name = teacher.getUsername();
        Long teacherId = teacher.getId();
        users.delete(teacher);

        audit.record("AppUser", teacherId, AuditAction.DELETE, me,
                "Deleted teacher account '" + name + "'.");
    }

    // =============================================================================
    // STUDENT ACCOUNT MANAGEMENT - THE SAME THREE OPERATIONS TEACHERS ALREADY HAD
    // =============================================================================
    /*
     * A student could be created and listed but never reset, edited or removed.
     * That was not a decision, it was a gap: a student whose temporary password
     * was lost had no recovery path at all, because the password is a one-way
     * hash and nothing could rewrite it. Each method below mirrors its teacher
     * equivalent deliberately, so the two roles cannot drift apart in what an
     * admin is able to do about them.
     */

    /**
     * Issue a student a new temporary password.
     *
     * Marks the account pending again, exactly as creation does: the admin
     * necessarily knows the password they just typed, so it must be replaced
     * before the account can do anything else.
     */
    @Transactional
    public StudentView resetStudentPassword(Long id, String temporaryPassword) {
        AppUser me = requireAdmin("reset a student's password");
        AppUser student = requireStudent(id);

        requireTemporaryPasswordLength(temporaryPassword);
        student.setPasswordHash(encoder.encode(temporaryPassword));
        student.setMustChangePassword(true);

        audit.record("AppUser", student.getId(), AuditAction.UPDATE, me,
                "Reset the password for student '" + student.getUsername() + "'.");

        return StudentView.of(student, classNameOf(student),
                profiles.findByAppUser(student).orElse(null));
    }

    /**
     * Correct a student's biographical detail.
     *
     * The USERNAME IS NOT EDITABLE, and that is the point of this method's
     * shape. It is the generated external id (STU20260001), it is what the
     * student signs in with, and every audit entry ever written names them by
     * it. Letting it be rewritten would silently detach a person from their own
     * history. Only PersonProfile changes here; AppUser keeps its identity.
     */
    @Transactional
    public StudentView updateStudent(Long id, String firstName, String middleName,
                                     String lastName, String nationalId, LocalDate dateOfBirth,
                                     String contactEmail, String phone, LocalDate dateEnrolled) {
        AppUser me = requireAdmin("edit a student");
        AppUser student = requireStudent(id);

        PersonProfile profile = updateProfile(student, firstName, middleName, lastName,
                nationalId, dateOfBirth, contactEmail, phone, dateEnrolled);

        audit.record("PersonProfile", student.getId(), AuditAction.UPDATE, me,
                "Updated the profile of student '" + student.getUsername() + "'.");

        return StudentView.of(student, classNameOf(student), profile);
    }

    /**
     * Remove a student account.
     *
     * REFUSED while anything of theirs still exists. The order of the checks is
     * deliberate - most consequential first, so the message names the thing
     * hardest to undo:
     *
     *   an assessment is a MARK. Deleting a marked student destroys the record
     *   that the work was judged, and every average computed from it changes.
     *   a submission is HANDED-IN WORK, possibly a PDF they cannot re-supply.
     *   an enrolment is only a register entry, and IS cleared automatically,
     *   because it carries no history of its own - it says where somebody is,
     *   not what they did.
     *
     * That last distinction is the whole policy in miniature: rows that RECORD
     * SOMETHING THAT HAPPENED block the delete; rows that merely describe the
     * current arrangement are tidied up. Neither is left to a cascade, because
     * the database is deliberately NO_ACTION throughout - see the foreign keys
     * in V4. Without these checks the same refusal still happens, but as a raw
     * constraint violation the caller cannot act on.
     */
    @Transactional
    public void deleteStudent(Long id) {
        AppUser me = requireAdmin("delete a student account");
        AppUser student = requireStudent(id);

        if (assessments.existsByStudent(student)) {
            throw new IllegalStateException(
                    "'" + student.getUsername() + "' has at least one recorded mark and cannot "
                            + "be deleted. Removing the account would destroy the record that "
                            + "the work was assessed.");
        }
        if (submissions.existsByStudent(student)) {
            throw new IllegalStateException(
                    "'" + student.getUsername() + "' has at least one submission and cannot be "
                            + "deleted. Handed-in work is evidence that it was handed in.");
        }
        /*
         * Attendance is the same kind of fact as a mark or a submission -
         * a record of SOMETHING THAT HAPPENED, not of a current arrangement -
         * so it blocks the delete for the identical reason, and unlike
         * enrolment it is not tidied away alongside the account.
         *
         * The database would refuse this anyway (fk_attendance_student does
         * not cascade); the check is what turns that refusal into a sentence
         * naming the reason instead of a 500.
         */
        if (attendance.existsByStudent(student)) {
            throw new IllegalStateException(
                    "'" + student.getUsername() + "' has attendance on record and cannot be "
                            + "deleted. A register is a record of who was actually there.");
        }

        /*
         * Enrolments go, explicitly, in this same transaction. They are the one
         * dependent row that records an arrangement rather than an event, so
         * removing them alongside the account loses nothing - and leaving them
         * would make the delete impossible, since fk_enrolment_student does not
         * cascade either.
         */
        enrolments.findByStudentOrderByIdAsc(student).forEach(enrolments::delete);
        /*
         * Any outstanding reset link dies with the account. Unlike an audit
         * entry - which must outlive the account it names, and so stores a
         * snapshot rather than a reference - a reset token is meaningless
         * without its account, and fk_password_reset_token_user does not
         * cascade, so leaving one behind would simply make the delete fail.
         */
        resetTokens.deleteByAppUser(student);
        profiles.findByAppUser(student).ifPresent(profiles::delete);

        String name = student.getUsername();
        Long studentId = student.getId();
        users.delete(student);

        audit.record("AppUser", studentId, AuditAction.DELETE, me,
                "Deleted student account '" + name + "'.");
    }

    /**
     * Correct a teacher's biographical detail. The username is not editable,
     * for the reason given on updateStudent.
     */
    @Transactional
    public TeacherView updateTeacher(Long id, String firstName, String middleName,
                                     String lastName, String nationalId, LocalDate dateOfBirth,
                                     String contactEmail, String phone, LocalDate dateEnrolled) {
        AppUser me = requireAdmin("edit a teacher");
        AppUser teacher = requireTeacher(id);

        PersonProfile profile = updateProfile(teacher, firstName, middleName, lastName,
                nationalId, dateOfBirth, contactEmail, phone, dateEnrolled);

        audit.record("PersonProfile", teacher.getId(), AuditAction.UPDATE, me,
                "Updated the profile of teacher '" + teacher.getUsername() + "'.");

        return TeacherView.of(teacher, profile);
    }

    // ----- shared helpers -------------------------------------------------------

    /**
     * The class this student is in, or null when they are in none yet.
     *
     * Read from the enrolment rows rather than stored on the student, because
     * the enrolment IS the fact - a copy on AppUser would be a second version
     * of it, wrong the moment somebody is moved between classes.
     */
    private String classNameOf(AppUser student) {
        return enrolments.findByStudentOrderByIdAsc(student).stream()
                .findFirst()
                .map(enrolment -> enrolment.getSchoolClass().getName())
                .orElse(null);
    }

    /**
     * Validate and write biographical detail onto an EXISTING account.
     *
     * Separate from saveProfile rather than sharing it, because of one line:
     * the duplicate-national-id check. Creating a profile asks "does this id
     * exist at all"; editing one must ask "does it exist on SOMEBODY ELSE",
     * or saving a record without touching its national id would collide with
     * itself and refuse every edit. The validation either side of it is
     * identical, and both end at the same unique index, which is what actually
     * enforces the rule.
     *
     * An account with no profile row yet gets one - the admin accounts created
     * before profiles existed are the real case.
     */
    private PersonProfile updateProfile(AppUser account, String firstName, String middleName,
                                        String lastName, String nationalId, LocalDate dateOfBirth,
                                        String contactEmail, String phone, LocalDate dateEnrolled) {
        String cleanFirst = require(firstName, "First name");
        String cleanLast = require(lastName, "Last name");
        String cleanNationalId = require(nationalId, "National id");
        String cleanEmail = (contactEmail == null || contactEmail.isBlank())
                ? null : contactEmail.trim();
        String cleanPhone = (phone == null || phone.isBlank()) ? null : phone.trim();

        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required.");
        }
        if (!dateOfBirth.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Date of birth must be in the past.");
        }
        if (cleanEmail != null && !EMAIL_PATTERN.matcher(cleanEmail).matches()) {
            throw new IllegalArgumentException("'" + cleanEmail + "' is not a valid email address.");
        }
        if (cleanPhone != null && !PHONE_PATTERN.matcher(cleanPhone).matches()) {
            throw new IllegalArgumentException(
                    "'" + cleanPhone + "' is not a valid phone number - use +267 followed by 8 digits.");
        }
        if (!NATIONAL_ID_PATTERN.matcher(cleanNationalId).matches()) {
            throw new IllegalArgumentException(
                    "'" + cleanNationalId + "' is not a valid Omang - it must be 9 digits, "
                            + "and the 5th digit must be 1 (male) or 2 (female).");
        }

        PersonProfile existing = profiles.findByAppUser(account).orElse(null);

        // "Taken by somebody else", not "taken" - see this method's own comment.
        profiles.findByNationalId(cleanNationalId).ifPresent(other -> {
            if (existing == null || !other.getId().equals(existing.getId())) {
                throw new IllegalStateException(
                        "A profile with national id '" + cleanNationalId + "' already exists.");
            }
        });

        LocalDate enrolled = dateEnrolled == null
                ? (existing == null ? LocalDate.now() : existing.getDateEnrolled())
                : dateEnrolled;
        if (enrolled.isBefore(dateOfBirth)) {
            throw new IllegalArgumentException("Date enrolled cannot be before date of birth.");
        }
        String cleanMiddle = (middleName == null || middleName.isBlank())
                ? null : middleName.trim();

        if (existing == null) {
            return profiles.save(new PersonProfile(account, cleanFirst, cleanMiddle, cleanLast,
                    cleanNationalId, dateOfBirth, cleanEmail, cleanPhone, enrolled));
        }

        existing.setFirstName(cleanFirst);
        existing.setMiddleName(cleanMiddle);
        existing.setLastName(cleanLast);
        existing.setNationalId(cleanNationalId);
        existing.setDateOfBirth(dateOfBirth);
        existing.setContactEmail(cleanEmail);
        existing.setPhone(cleanPhone);
        existing.setDateEnrolled(enrolled);
        return profiles.save(existing);
    }

    private PersonProfile saveProfile(AppUser account, String firstName, String middleName,
                                      String lastName, String nationalId, LocalDate dateOfBirth,
                                      String contactEmail, String phone, LocalDate dateEnrolled) {
        String cleanFirst = require(firstName, "First name");
        String cleanLast = require(lastName, "Last name");
        String cleanNationalId = require(nationalId, "National id");
        String cleanEmail = (contactEmail == null || contactEmail.isBlank())
                ? null : contactEmail.trim();
        /*
         * Format-checked when present, never REQUIRED at this layer - the same
         * treatment as cleanEmail just above. Presence is the CALLER's
         * decision: the single-record admin form requires it via NotBlank on
         * PersonRequest before this method is ever reached, while bulk import
         * has no phone column to require one from - see importOneStudent and
         * importOneTeacher below. Enforcing presence here would make every
         * already-working CSV import fail the moment this method changed
         * underneath it.
         */
        String cleanPhone = (phone == null || phone.isBlank()) ? null : phone.trim();

        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required.");
        }
        if (!dateOfBirth.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Date of birth must be in the past.");
        }
        if (cleanEmail != null && !EMAIL_PATTERN.matcher(cleanEmail).matches()) {
            throw new IllegalArgumentException("'" + cleanEmail + "' is not a valid email address.");
        }
        if (cleanPhone != null && !PHONE_PATTERN.matcher(cleanPhone).matches()) {
            throw new IllegalArgumentException(
                    "'" + cleanPhone + "' is not a valid phone number - use +267 followed by 8 digits.");
        }
        if (!NATIONAL_ID_PATTERN.matcher(cleanNationalId).matches()) {
            throw new IllegalArgumentException(
                    "'" + cleanNationalId + "' is not a valid Omang - it must be 9 digits, "
                            + "and the 5th digit must be 1 (male) or 2 (female).");
        }

        /*
         * A courtesy check, not the guarantee - uq_person_profile_national_id
         * is what actually refuses a duplicate written by two admins at once.
         */
        if (profiles.existsByNationalId(cleanNationalId)) {
            throw new IllegalStateException(
                    "A profile with national id '" + cleanNationalId + "' already exists.");
        }

        LocalDate enrolled = dateEnrolled == null ? LocalDate.now() : dateEnrolled;
        if (enrolled.isBefore(dateOfBirth)) {
            throw new IllegalArgumentException("Date enrolled cannot be before date of birth.");
        }

        return profiles.save(new PersonProfile(account, cleanFirst,
                (middleName == null || middleName.isBlank()) ? null : middleName.trim(),
                cleanLast, cleanNationalId, dateOfBirth, cleanEmail, cleanPhone, enrolled));
    }

    /**
     * A random temporary password for a bulk-imported account - long enough
     * to satisfy AppUserService.MIN_PASSWORD_LENGTH with room to spare, and
     * drawn from SecureRandom rather than Random, the same reasoning any
     * credential-shaped value in this codebase gets.
     */
    private String generateTemporaryPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return password.toString();
    }

    private String cell(Map<String, String> row, String column) {
        String value = cellOrNull(row, column);
        if (value == null) {
            throw new IllegalArgumentException("Column '" + column + "' is required.");
        }
        return value;
    }

    private String cellOrNull(Map<String, String> row, String column) {
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                String value = entry.getValue();
                return (value == null || value.isBlank()) ? null : value.trim();
            }
        }
        return null;
    }

    private LocalDate parseDate(String value, String column) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' in column '" + column + "' is not a date (expected yyyy-MM-dd).");
        }
    }

    private LocalDate parseOptionalDate(String value) {
        return value == null ? null : parseDate(value, "dateEnrolled");
    }

    private AppUser requireAdmin(String action) {
        AppUser me = currentUser.currentActiveUser();
        if (me.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin can " + action + ".");
        }
        return me;
    }

    @NonNull
    private AppUser requireStudent(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Student id must not be null.");
        }
        AppUser student = Require.orThrow(users.findById(id),
                () -> new ResourceNotFoundException("student", id));
        if (student.getRole() != Role.STUDENT) {
            throw new IllegalArgumentException(
                    "'" + student.getUsername() + "' is a " + student.getRole()
                            + ", not a student.");
        }
        return student;
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

    @NonNull
    private SchoolClass requireClass(Long classId) {
        if (classId == null) {
            throw new IllegalArgumentException("Class id must not be null.");
        }
        return Require.orThrow(classes.findById(classId),
                () -> new ResourceNotFoundException("class", classId));
    }

    @NonNull
    private Subject requireSubject(Long subjectId) {
        if (subjectId == null) {
            throw new IllegalArgumentException("Subject id must not be null.");
        }
        return Require.orThrow(subjects.findById(subjectId),
                () -> new ResourceNotFoundException("subject", subjectId));
    }

    private String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank.");
        }
        return value.trim();
    }

    private void requireTemporaryPasswordLength(String password) {
        if (password == null || password.length() < AppUserService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "The temporary password must be at least "
                            + AppUserService.MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    /** One (subject, class) pair a newly created teacher will teach from day one. */
    public record CourseAssignment(Long subjectId, Long classId) {
    }
}
