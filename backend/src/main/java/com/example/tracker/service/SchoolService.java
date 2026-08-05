package com.example.tracker.service;

import com.example.tracker.dto.ClassView;
import com.example.tracker.dto.CourseView;
import com.example.tracker.dto.SubjectView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
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
    private final AppUserService users;

    public SchoolService(SubjectRepository subjects,
                         SchoolClassRepository classes,
                         EnrolmentRepository enrolments,
                         CourseRepository courses,
                         AppUserService users) {
        this.subjects = subjects;
        this.classes = classes;
        this.enrolments = enrolments;
        this.courses = courses;
        this.users = users;
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
        requireTeacher("create a subject");

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

        return SubjectView.of(subjects.save(new Subject(cleanCode, cleanName)));
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
        requireTeacher("create a class");
        String cleanName = require(name, "Class name");

        classes.findByNameIgnoreCase(cleanName).ifPresent(existing -> {
            throw new IllegalStateException("A class named '" + cleanName + "' already exists.");
        });

        return ClassView.of(classes.save(new SchoolClass(cleanName)), 0);
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
        requireTeacher("enrol a student");

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

        enrolments.save(new Enrolment(student, schoolClass));
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
        requireTeacher("withdraw a student");

        SchoolClass schoolClass = requireClass(classId);
        AppUser student = users.findByUsernameOrReject(require(username, "Username"));

        /*
         * ifPresentOrElse rather than orElseThrow-then-delete. Both are correct;
         * this one also satisfies null analysis, because the value reaches
         * delete() as Optional's own non-null lambda parameter instead of as a
         * local the analysis has to take on trust.
         */
        enrolments.findByStudentAndSchoolClass(student, schoolClass).ifPresentOrElse(
                enrolments::delete,
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

        return CourseView.of(courses.save(new Course(subject, schoolClass, teacher)));
    }

    // ----- shared guards -------------------------------------------------------

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
