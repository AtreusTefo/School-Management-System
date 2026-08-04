package com.example.tracker.service;

import com.example.tracker.dto.AssignmentView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.Submission;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for setting work.
 *
 * This is the "brain" for assignments: what is allowed, what must be validated,
 * what should happen. It does NOT know about HTTP (the controller's job) and
 * does NOT write SQL (the repository's job).
 *
 * THE FAN-OUT, AND WHY IT IS ONE TRANSACTION
 * ------------------------------------------
 * Setting work for a class writes one assignment row and one submission row per
 * enrolled student - thirty-one writes for a class of thirty. Every one of them
 * has to happen, or none of them can be allowed to.
 *
 * Without a single transaction, a failure at student 17 leaves an assignment
 * that half the class can see. Nothing would report it: the teacher's screen
 * would show the assignment created successfully, and sixteen students would
 * have work while fourteen had none, until somebody noticed the marks were
 * missing. @Transactional is what makes "set this for the class" one event that
 * either happened or did not.
 *
 * The database backs this up rather than taking it on trust:
 * uq_submission_assignment_student refuses a duplicate outright, so even a
 * fan-out run twice cannot double a student's row.
 */
@Service
@Transactional(readOnly = true)   // safe default; write methods override it below
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final CourseRepository courses;
    private final EnrolmentRepository enrolments;
    private final AppUserService users;

    public AssignmentService(AssignmentRepository assignments,
                             SubmissionRepository submissions,
                             CourseRepository courses,
                             EnrolmentRepository enrolments,
                             AppUserService users) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.courses = courses;
        this.enrolments = enrolments;
        this.users = users;
    }

    /**
     * The assignments the caller can see, with progress counts.
     *
     * A teacher sees what they set, across every course they run. A student sees
     * what was set for the classes they are in - though a student's real view is
     * their submission list, which carries their own state; this exists so both
     * roles can answer "what work exists for me?" from one place.
     */
    public List<AssignmentView> listAssignments() {
        AppUser me = users.currentActiveUser();

        List<Assignment> visible = me.getRole() == Role.TEACHER
                ? assignments.findByCourseInOrderByIdAsc(courses.findByTeacherOrderByIdAsc(me))
                : assignments.findForStudent(me);

        return visible.stream().map(this::withCounts).toList();
    }

    /**
     * Set a piece of work for one or more courses.
     *
     * WHY courseIds IS A LIST
     * "A teacher can assign to more than one class at a time" is the
     * requirement, and this is where it is met. Each course produces its own
     * assignment row, because an assignment belongs to one subject taught to one
     * class - two classes doing the same task are doing two instances of it,
     * with their own registers and their own progress.
     *
     * Making it one row shared between classes was the alternative and is worse:
     * "17 of 30 handed in" stops meaning anything when the 30 is really two
     * groups, and withdrawing a class would have to unpick a shared row.
     *
     * All of it - every assignment and every student's submission - happens in
     * ONE transaction. Setting work for three classes cannot half-succeed.
     */
    @Transactional
    public List<AssignmentView> createAssignment(String title, String description,
                                                 LocalDate dueDate, List<Long> courseIds) {
        AppUser me = requireTeacher("create an assignment");

        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }
        if (courseIds == null || courseIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Choose at least one course to set this work for.");
        }

        String cleanTitle = title.trim();
        String cleanDescription = (description == null || description.isBlank())
                ? null : description.trim();

        List<AssignmentView> created = new ArrayList<>();

        for (Long courseId : courseIds) {
            Course course = requireCourse(courseId);
            requireTeaches(course, me);

            Assignment assignment = assignments.save(
                    new Assignment(cleanTitle, cleanDescription, course, me, dueDate));

            created.add(withCounts(assignment, fanOutToClass(assignment)));
        }

        return created;
    }

    /**
     * Give every student in the course's class their own submission row.
     *
     * A class with nobody enrolled yet produces an assignment with no
     * submissions, and that is allowed on purpose: setting work before the
     * register is filled is ordinary, and refusing it would force teachers to
     * remember to come back. The consequence is stated honestly in the API - the
     * assignment reports a student count of zero rather than pretending.
     *
     * Note what is NOT done here: students enrolled LATER do not retroactively
     * receive this assignment. That is a deliberate limit, not an oversight -
     * silently handing a new arrival three weeks of missed deadlines would be
     * worse than leaving it to a teacher to decide. It is recorded as an open
     * item rather than hidden.
     */
    private int fanOutToClass(Assignment assignment) {
        List<Enrolment> register =
                enrolments.findBySchoolClassOrderByIdAsc(assignment.getCourse().getSchoolClass());

        for (Enrolment enrolment : register) {
            submissions.save(new Submission(assignment, enrolment.getStudent()));
        }
        return register.size();
    }

    /**
     * Correct an assignment.
     *
     * TEACHER ONLY, and only a teacher who teaches this course.
     *
     * This is a narrowing of the older rule, which let any teacher edit anything.
     * That rule existed to fix a real defect - ownership used to pass to the
     * STUDENT, leaving the teacher unable to correct their own typo - and the
     * fix was right for the model at the time. The model has changed: authority
     * now follows the timetable, which is a teacher-to-teacher relationship and
     * cannot trap anyone the way student ownership did. Co-teaching still works,
     * because a co-teacher has their own course row for the same class.
     */
    @Transactional
    public AssignmentView updateAssignment(Long id, String title, String description,
                                           LocalDate dueDate) {
        Assignment assignment = requireOwnCourse(id, "edit");

        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }

        assignment.setTitle(title.trim());
        assignment.setDescription(
                (description == null || description.isBlank()) ? null : description.trim());
        assignment.setDueDate(dueDate);
        return withCounts(assignment);
    }

    /**
     * Remove an assignment.
     *
     * REFUSED once anybody has handed work in. Deleting then would destroy a
     * record of completed work - including the student's uploaded PDF - and
     * nobody could recover it. Reopening those submissions first makes the
     * deletion two deliberate acts rather than one careless click.
     *
     * The child submissions are deleted EXPLICITLY here rather than by an
     * ON DELETE CASCADE in the schema. That is the same decision taken for
     * accounts in V2 and for the same reason: a cascade would make this method's
     * refusal above bypassable by anyone who deleted the parent another way.
     */
    @Transactional
    public void deleteAssignment(Long id) {
        Assignment assignment = requireOwnCourse(id, "delete");

        if (submissions.existsByAssignmentAndStatus(assignment, AssignmentStatus.SUBMITTED)) {
            throw new IllegalStateException(
                    "Assignment " + id + " has work handed in and cannot be deleted. "
                            + "Reopen those submissions first if this is intended.");
        }

        submissions.deleteAll(submissions.findByAssignmentOrderByIdAsc(assignment));
        assignments.delete(assignment);
    }

    // ----- shared guards and helpers -------------------------------------------

    /** Progress counts for one assignment: how many students, how many handed in. */
    private AssignmentView withCounts(Assignment assignment) {
        List<Submission> all = submissions.findByAssignmentOrderByIdAsc(assignment);
        int submitted = (int) all.stream()
                .filter(s -> s.getStatus() == AssignmentStatus.SUBMITTED)
                .count();
        return AssignmentView.of(assignment, all.size(), submitted);
    }

    /** The same, when the caller already knows the student count. */
    private AssignmentView withCounts(Assignment assignment, int studentCount) {
        return AssignmentView.of(assignment, studentCount, 0);
    }

    private Assignment requireAssignment(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Assignment id must not be null.");
        }
        return assignments.findById(id)
                .orElseThrow(() -> new AssignmentNotFoundException(id));
    }

    private Course requireCourse(Long courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("Course id must not be null.");
        }
        return courses.findById(courseId)
                .orElseThrow(() -> new com.example.tracker.exception.ResourceNotFoundException(
                        "course", courseId));
    }

    /**
     * The caller must actually teach this course's subject to this class.
     *
     * Checked by looking for a course row rather than by comparing
     * course.teacher directly, so co-teaching works: two teachers sharing a
     * class each have their own row, and each satisfies this.
     */
    private void requireTeaches(Course course, AppUser me) {
        boolean teachesIt = courses.findBySubjectAndSchoolClassAndTeacher(
                course.getSubject(), course.getSchoolClass(), me).isPresent();

        if (!teachesIt) {
            throw new AccessDeniedException(
                    "You do not teach " + course.getLabel() + ".");
        }
    }

    private Assignment requireOwnCourse(Long id, String action) {
        AppUser me = requireTeacher(action + " an assignment");
        Assignment assignment = requireAssignment(id);
        requireTeaches(assignment.getCourse(), me);
        return assignment;
    }

    private AppUser requireTeacher(String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Only a teacher can " + action + ".");
        }
        return me;
    }
}
