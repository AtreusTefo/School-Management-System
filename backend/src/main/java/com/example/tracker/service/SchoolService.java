package com.example.tracker.service;

import com.example.tracker.dto.ClassView;
import com.example.tracker.dto.CourseView;
import com.example.tracker.dto.StudentView;
import com.example.tracker.dto.SubjectView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.SchoolClassRepository;
import com.example.tracker.repository.SubjectRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for the structure of the school itself:
 * subjects, classes, who is enrolled where, and who teaches what.
 *
 * This is the layer the assignment features stand on. Nothing here is about a
 * piece of work; it is all about the relationships that decide who a piece of
 * work reaches.
 *
 * WHERE THE ROLE RULES ARE, AND WHY THERE ARE TWO COPIES
 * -----------------------------------------------------
 * "Only a student can be enrolled" and "only a teacher can teach" are checked
 * here AND enforced by the schema through a composite foreign key (see
 * Enrolment for the mechanism). That is not belt-and-braces for its own sake:
 *
 *   The check HERE produces a clear 400 with a sentence a person can act on.
 *   The constraint THERE is what makes the rule true of the data, for every
 *   writer, including a script, an admin session, or a future service that
 *   forgets this one exists.
 *
 * Remove the Java check and the system still cannot be corrupted - it just
 * reports the refusal badly. Remove the constraint and the guarantee becomes a
 * promise about the current code.
 */
@Service
@Transactional(readOnly = true)
public class SchoolService {

    private final SubjectRepository subjects;
    private final SchoolClassRepository classes;
    private final EnrolmentRepository enrolments;
    private final CourseRepository courses;
    private final AssignmentRepository assignments;
    private final AppUserService users;
    private final AuditLogService audit;

    public SchoolService(SubjectRepository subjects,
                         SchoolClassRepository classes,
                         EnrolmentRepository enrolments,
                         CourseRepository courses,
                         AssignmentRepository assignments,
                         AppUserService users,
                         AuditLogService audit) {
        this.subjects = subjects;
        this.classes = classes;
        this.enrolments = enrolments;
        this.courses = courses;
        this.assignments = assignments;
        this.users = users;
        this.audit = audit;
    }

    // ----- subjects ------------------------------------------------------------

    /**
     * Everyone signed in may read the subject list. It is reference data with
     * nothing private in it, and a student needs it to make sense of their own
     * timetable.
     */
    public List<SubjectView> listSubjects() {
        users.currentActiveUser();
        return subjects.findAllByOrderByNameAsc().stream().map(SubjectView::of).toList();
    }

    @Transactional
    public SubjectView createSubject(String code, String name) {
        AppUser me = requireTeacher("create a subject");

        String cleanCode = require(code, "Subject code");
        String cleanName = require(name, "Subject name");

        /*
         * A courtesy check, not the guarantee - the same reasoning as account
         * creation. Two teachers adding "Mathematics" at the same instant could
         * both pass this before either wrote; uq_subject_name is what actually
         * refuses the second INSERT. Checking here only buys a clearer message
         * in the ordinary case.
         */
        subjects.findByCodeIgnoreCase(cleanCode).ifPresent(existing -> {
            throw new IllegalStateException("A subject with code '" + cleanCode + "' already exists.");
        });
        subjects.findByNameIgnoreCase(cleanName).ifPresent(existing -> {
            throw new IllegalStateException("A subject named '" + cleanName + "' already exists.");
        });

        Subject saved = subjects.save(new Subject(cleanCode, cleanName));
        audit.record("Subject", saved.getId(), AuditAction.CREATE, me,
                "Created subject '" + cleanName + "' (" + cleanCode + ").");
        return SubjectView.of(saved);
    }

    // ----- classes -------------------------------------------------------------

    public List<ClassView> listClasses() {
        users.currentActiveUser();
        return classes.findAllByOrderByNameAsc().stream()
                .map(schoolClass -> ClassView.of(
                        schoolClass,
                        enrolments.findBySchoolClassOrderByIdAsc(schoolClass).size()))
                .toList();
    }

    @Transactional
    public ClassView createClass(String name) {
        AppUser me = requireTeacher("create a class");
        String cleanName = require(name, "Class name");

        classes.findByNameIgnoreCase(cleanName).ifPresent(existing -> {
            throw new IllegalStateException("A class named '" + cleanName + "' already exists.");
        });

        SchoolClass saved = classes.save(new SchoolClass(cleanName));
        audit.record("SchoolClass", saved.getId(), AuditAction.CREATE, me,
                "Created class '" + cleanName + "'.");
        return ClassView.of(saved, 0);
    }

    /** The register: who is in this class. Teacher only. */
    public List<String> listStudentsInClass(Long classId) {
        requireTeacher("view a class register");
        SchoolClass schoolClass = requireClass(classId);

        return enrolments.findBySchoolClassOrderByIdAsc(schoolClass).stream()
                .map(enrolment -> enrolment.getStudent().getUsername())
                .toList();
    }

    // ----- enrolment -----------------------------------------------------------

    /**
     * Put a student in a class.
     *
     * This is the single most consequential write in this service: it is what
     * decides who receives every future assignment for that class. An enrolment
     * added by mistake means somebody gets work that is not theirs; one missed
     * means somebody silently gets nothing, which is the worse failure because
     * nothing appears wrong until marks are due.
     */
    @Transactional
    public void enrolStudent(Long classId, String username) {
        AppUser me = requireTeacher("enrol a student");

        SchoolClass schoolClass = requireClass(classId);
        AppUser student = users.findByUsernameOrReject(require(username, "Username"));

        /*
         * The role check the composite foreign key also makes. Catching it here
         * turns an opaque constraint violation into "X is a teacher, not a
         * student" - the difference between an error somebody can act on and one
         * they have to decode.
         */
        if (student.getRole() != Role.STUDENT) {
            throw new IllegalArgumentException(
                    "'" + student.getUsername() + "' is a " + student.getRole()
                            + ", and only a student can be enrolled in a class.");
        }
        if (enrolments.existsByStudentAndSchoolClass(student, schoolClass)) {
            throw new IllegalStateException(
                    "'" + student.getUsername() + "' is already in " + schoolClass.getName() + ".");
        }

        Enrolment saved = enrolments.save(new Enrolment(student, schoolClass));
        audit.record("Enrolment", saved.getId(), AuditAction.CREATE, me,
                "Enrolled '" + student.getUsername() + "' in " + schoolClass.getName() + ".");
    }

    /**
     * Take a student out of a class.
     *
     * Their existing submissions are deliberately left alone. Work that was set,
     * and possibly handed in, happened - removing somebody from a register is
     * not a claim that it did not. Deleting the history here would destroy
     * evidence of completed work as a side effect of an administrative change,
     * which is the same reasoning that keeps ON DELETE CASCADE out of the schema.
     */
    @Transactional
    public void withdrawStudent(Long classId, String username) {
        AppUser me = requireTeacher("withdraw a student");

        SchoolClass schoolClass = requireClass(classId);
        AppUser student = users.findByUsernameOrReject(require(username, "Username"));

        /*
         * ifPresentOrElse rather than orElseThrow-then-delete. Both are correct;
         * this one also satisfies null analysis, because the value reaches
         * delete() as Optional's own non-null lambda parameter instead of as a
         * local the analysis has to take on trust.
         */
        enrolments.findByStudentAndSchoolClass(student, schoolClass).ifPresentOrElse(
                enrolment -> {
                    Long enrolmentId = enrolment.getId();
                    enrolments.delete(enrolment);
                    audit.record("Enrolment", enrolmentId, AuditAction.DELETE, me,
                            "Withdrew '" + student.getUsername() + "' from "
                                    + schoolClass.getName() + ".");
                },
                () -> {
                    throw new ResourceNotFoundException(
                            "'" + student.getUsername() + "' is not in "
                                    + schoolClass.getName() + ".");
                });
    }

    // ----- courses -------------------------------------------------------------

    /**
     * What the caller is allowed to see.
     *
     * A teacher sees the courses they run; a student sees the courses attached
     * to the classes they are in. Neither sees the whole school, and the scoping
     * happens in the QUERY rather than being filtered afterwards.
     *
     * This method is the direct answer to two of the requirements: the list a
     * student gets back IS "the subjects I am taught", and its distinct teachers
     * are "the teachers who teach me".
     */
    public List<CourseView> listCourses() {
        AppUser me = users.currentActiveUser();

        List<Course> visible = me.getRole() == Role.TEACHER
                ? courses.findByTeacherOrderByIdAsc(me)
                : courses.findCoursesForStudent(me);

        return visible.stream().map(CourseView::of).toList();
    }

    /** Every course in the school. Teacher only - used when setting up teaching. */
    public List<CourseView> listAllCourses() {
        requireTeacher("view every course");
        return courses.findAllByOrderByIdAsc().stream().map(CourseView::of).toList();
    }

    /**
     * Record that a teacher teaches a subject to a class.
     *
     * The teacher defaults to the caller, so the common case - "I teach this" -
     * needs no extra field. Naming somebody else is allowed, because timetables
     * are usually entered by one person on behalf of many.
     */
    @Transactional
    public CourseView createCourse(Long subjectId, Long classId, String teacherUsername) {
        AppUser me = requireTeacher("set up a course");

        Subject subject = requireSubject(subjectId);
        SchoolClass schoolClass = requireClass(classId);

        AppUser teacher = (teacherUsername == null || teacherUsername.isBlank())
                ? me
                : users.findByUsernameOrReject(teacherUsername.trim());

        if (teacher.getRole() != Role.TEACHER) {
            throw new IllegalArgumentException(
                    "'" + teacher.getUsername() + "' is a " + teacher.getRole()
                            + ", and only a teacher can teach a course.");
        }

        courses.findBySubjectAndSchoolClassAndTeacher(subject, schoolClass, teacher)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "'" + teacher.getUsername() + "' already teaches "
                                    + subject.getName() + " to " + schoolClass.getName() + ".");
                });

        Course saved = courses.save(new Course(subject, schoolClass, teacher));
        audit.record("Course", saved.getId(), AuditAction.CREATE, me,
                "'" + teacher.getUsername() + "' now teaches " + subject.getName()
                        + " to " + schoolClass.getName() + ".");
        return CourseView.of(saved);
    }

    // ----- admin: browsing students -----------------------------------------

    /**
     * Every student, with their class if they have one - the admin panel's
     * student list, and what it navigates from into the detail view below.
     */
    public List<StudentView> listStudents() {
        requireAdmin("view the student list");
        return users.findByRole(Role.STUDENT).stream()
                .map(student -> {
                    List<Enrolment> own = enrolments.findByStudentOrderByIdAsc(student);
                    String className = own.isEmpty() ? null : own.get(0).getSchoolClass().getName();
                    return StudentView.of(student, className);
                })
                .toList();
    }

    /**
     * One student's current teachers, by subject - what the admin panel's
     * student detail view shows before offering to assign or unassign one.
     *
     * Uses findCoursesForStudent rather than requireSingleClass: a READ should
     * show whatever is true of the student, including the unusual case of more
     * than one class, rather than refusing to answer. It is only the WRITE
     * (assignTeacherToStudent/unassignTeacherFromStudent) that must be
     * unambiguous about which class gains or loses a course.
     */
    public List<CourseView> listCoursesForStudent(Long studentId) {
        requireAdmin("view a student's teachers");
        AppUser student = requireStudent(studentId);
        return courses.findCoursesForStudent(student).stream().map(CourseView::of).toList();
    }

    // ----- admin: the teacher-to-student relationship ---------------------------

    /**
     * Give a teacher access to one student, by teaching them a subject.
     *
     * "ASSIGN A TEACHER TO A STUDENT" IS "ADD A COURSE", NOT A NEW TABLE
     * -------------------------------------------------------------------
     * A teacher's relationship to a student is already fully expressed by
     * Course: a teacher teaches a subject to a CLASS, and a student's
     * teachers follow from the class they are in (see Course's class
     * comment). Inventing a second, direct student-teacher join alongside
     * that would let the two disagree - a teacher could be "assigned" to a
     * student by one mechanism while Course, which every assignment and
     * every mark is actually scoped by, said otherwise.
     *
     * So this method resolves the student's class and does exactly what
     * createCourse does, with two differences that make it an ADMIN
     * operation rather than a TEACHER one: the caller does not have to
     * already teach something to grant someone else access, and the class is
     * found FROM the student rather than supplied directly - "assign this
     * teacher to this student" is the whole request; which class that means
     * is this student's own business to resolve, not the caller's.
     *
     * A student with more than one active enrolment, or none, is refused
     * rather than guessed at - see requireSingleClass.
     */
    @Transactional
    public CourseView assignTeacherToStudent(Long studentId, Long teacherId, Long subjectId) {
        AppUser me = requireAdmin("assign a teacher to a student");

        AppUser student = requireStudent(studentId);
        AppUser teacher = requireTeacherAccount(teacherId);
        Subject subject = requireSubject(subjectId);
        SchoolClass schoolClass = requireSingleClass(student);

        courses.findBySubjectAndSchoolClassAndTeacher(subject, schoolClass, teacher)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "'" + teacher.getUsername() + "' already teaches "
                                    + subject.getName() + " to " + schoolClass.getName() + ".");
                });

        Course saved = courses.save(new Course(subject, schoolClass, teacher));
        audit.record("Course", saved.getId(), AuditAction.CREATE, me,
                "Assigned '" + teacher.getUsername() + "' to teach " + subject.getName()
                        + " to '" + student.getUsername() + "' (" + schoolClass.getName() + ").");
        return CourseView.of(saved);
    }

    /**
     * Withdraw a teacher's access to one student, by removing the course that
     * granted it.
     *
     * REFUSED once any assignment has been set for the course - the same
     * reasoning as deleting an assignment somebody has handed work in for:
     * removing the relationship must not silently orphan work that already
     * exists under it. fk_assignment_course would refuse the DELETE outright
     * either way; checking first names what is actually still attached.
     */
    @Transactional
    public void unassignTeacherFromStudent(Long studentId, Long teacherId, Long subjectId) {
        AppUser me = requireAdmin("unassign a teacher from a student");

        AppUser student = requireStudent(studentId);
        AppUser teacher = requireTeacherAccount(teacherId);
        Subject subject = requireSubject(subjectId);
        SchoolClass schoolClass = requireSingleClass(student);

        Course course = courses.findBySubjectAndSchoolClassAndTeacher(subject, schoolClass, teacher)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "'" + teacher.getUsername() + "' does not teach " + subject.getName()
                                + " to " + schoolClass.getName() + "."));

        if (assignments.existsByCourse(course)) {
            throw new IllegalStateException(
                    "'" + teacher.getUsername() + "' has already set work for "
                            + schoolClass.getName() + " in " + subject.getName()
                            + ", and cannot be unassigned. Remove that work first if this is intended.");
        }

        Long courseId = course.getId();
        courses.delete(course);
        audit.record("Course", courseId, AuditAction.DELETE, me,
                "Unassigned '" + teacher.getUsername() + "' from teaching " + subject.getName()
                        + " to '" + student.getUsername() + "' (" + schoolClass.getName() + ").");
    }

    // ----- shared guards -------------------------------------------------------

    /** Establish that the caller is an admin, and return them. */
    private AppUser requireAdmin(String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin can " + action + ".");
        }
        return me;
    }

    @NonNull
    private AppUser requireStudent(Long studentId) {
        if (studentId == null) {
            throw new IllegalArgumentException("Student id must not be null.");
        }
        AppUser student = Require.orThrow(users.findById(studentId),
                () -> new ResourceNotFoundException("student", studentId));
        if (student.getRole() != Role.STUDENT) {
            throw new IllegalArgumentException(
                    "'" + student.getUsername() + "' is a " + student.getRole()
                            + ", not a student.");
        }
        return student;
    }

    @NonNull
    private AppUser requireTeacherAccount(Long teacherId) {
        if (teacherId == null) {
            throw new IllegalArgumentException("Teacher id must not be null.");
        }
        AppUser teacher = Require.orThrow(users.findById(teacherId),
                () -> new ResourceNotFoundException("teacher", teacherId));
        if (teacher.getRole() != Role.TEACHER) {
            throw new IllegalArgumentException(
                    "'" + teacher.getUsername() + "' is a " + teacher.getRole()
                            + ", not a teacher.");
        }
        return teacher;
    }

    /**
     * The one class this student is enrolled in.
     *
     * A student with no enrolment yet, or with more than one, cannot have "a
     * teacher assigned" without an admin also saying which class is meant -
     * guessing either way would grant access to the wrong register.
     */
    @NonNull
    private SchoolClass requireSingleClass(AppUser student) {
        List<Enrolment> enrolments = this.enrolments.findByStudentOrderByIdAsc(student);
        if (enrolments.isEmpty()) {
            throw new IllegalStateException(
                    "'" + student.getUsername() + "' is not enrolled in a class yet.");
        }
        if (enrolments.size() > 1) {
            throw new IllegalStateException(
                    "'" + student.getUsername() + "' is enrolled in more than one class; "
                            + "assign the teacher to a class directly instead.");
        }
        return enrolments.get(0).getSchoolClass();
    }

    /**
     * Fetch a class or fail with 404 rather than a NullPointerException later.
     *
     * The null guard is not decoration. A null id reaching findById is an
     * ordinary bad request - a client omitted a field - and without this it
     * would surface as a 500, which says "the server broke" about a mistake the
     * client made.
     */
    @NonNull
    private SchoolClass requireClass(Long classId) {
        if (classId == null) {
            throw new IllegalArgumentException("Class id must not be null.");
        }
        // Require.orThrow, not .orElseThrow(...) directly: java.util.Optional
        // carries no null-safety annotations, so a method declared @NonNull that
        // ended with .orElseThrow(...) would still be flagged at its own return
        // statement. See Require for the full reasoning.
        return Require.orThrow(classes.findById(classId),
                () -> new ResourceNotFoundException("class", classId));
    }

    /** The same guard for subjects. */
    @NonNull
    private Subject requireSubject(Long subjectId) {
        if (subjectId == null) {
            throw new IllegalArgumentException("Subject id must not be null.");
        }
        return Require.orThrow(subjects.findById(subjectId),
                () -> new ResourceNotFoundException("subject", subjectId));
    }

    /**
     * Establish that the caller is a teacher, and return them.
     *
     * Returning the user rather than just checking saves every caller a second
     * lookup, and means there is exactly one place that decides what "the
     * current teacher" means.
     */
    private AppUser requireTeacher(String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Only a teacher can " + action + ".");
        }
        return me;
    }

    /** Trim, and refuse blank. Used for every free-text field this service takes. */
    private String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank.");
        }
        return value.trim();
    }
}
