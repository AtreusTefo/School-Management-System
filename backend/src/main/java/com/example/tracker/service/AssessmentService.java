package com.example.tracker.service;

import com.example.tracker.dto.AssessmentView;
import com.example.tracker.dto.CourseView;
import com.example.tracker.dto.PerformanceView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assessment;
import com.example.tracker.model.Course;
import com.example.tracker.model.Role;
import com.example.tracker.model.Submission;
import com.example.tracker.repository.AssessmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.SubmissionRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for marks and performance.
 *
 * Two jobs, and it is worth being clear which is which:
 *
 *   RECORDING is a write, and it is guarded - only a teacher, only for a course
 *   they take, only for a student actually enrolled in that course's class.
 *
 *   REPORTING is arithmetic. Totals, percentages and performance levels are
 *   computed here, every time they are asked for, from the marks. Not one of
 *   them is stored, so not one of them can drift out of agreement with the marks
 *   they came from. Correcting a single mark corrects the report, with nothing
 *   to recalculate and no job to remember to run.
 *
 * WHY THE PERCENTAGE IS A RATIO OF TOTALS
 * ---------------------------------------
 * A student scoring 5/10 and 90/100 has 95 out of 110 - 86.36%. Averaging the
 * two percentages instead gives 70%, which quietly treats a ten-mark quiz as
 * equal in weight to a hundred-mark exam. Summing the scores and the maxima
 * keeps each assessment's weight proportional to what it was marked out of.
 */
@Service
@Transactional(readOnly = true)
public class AssessmentService {

    private final AssessmentRepository assessments;
    private final CourseRepository courses;
    private final EnrolmentRepository enrolments;
    private final SubmissionRepository submissions;
    private final AppUserService users;

    public AssessmentService(AssessmentRepository assessments,
                             CourseRepository courses,
                             EnrolmentRepository enrolments,
                             SubmissionRepository submissions,
                             AppUserService users) {
        this.assessments = assessments;
        this.courses = courses;
        this.enrolments = enrolments;
        this.submissions = submissions;
        this.users = users;
    }

    // ----- reading -------------------------------------------------------------

    /**
     * The marks the caller may see.
     *
     * A STUDENT sees their own and nothing else - this is their report. A
     * TEACHER sees the marks for the courses they take, which is their mark
     * book. Neither sees the whole school, and the scoping is in the query.
     */
    public List<AssessmentView> listAssessments() {
        AppUser me = users.currentActiveUser();

        List<Assessment> visible = me.getRole() == Role.TEACHER
                ? assessments.findForTeacher(me)
                : assessments.findByStudentOrderByIdAsc(me);

        return visible.stream().map(AssessmentView::of).toList();
    }

    /** The mark book for one course. Teacher only, and only their own course. */
    public List<AssessmentView> listForCourse(Long courseId) {
        AppUser me = requireTeacher("view a mark book");
        Course course = requireCourse(courseId);
        requireTeaches(course, me);

        return assessments.findMarkBook(course).stream().map(AssessmentView::of).toList();
    }

    /**
     * Performance per student per subject, derived from the marks.
     *
     * Grouped in Java rather than by a GROUP BY query, deliberately. The rows are
     * already loaded and already scoped by the same authority rules as
     * listAssessments, so grouping here reuses that guarantee instead of writing
     * a second query that has to repeat it - and a scoping rule expressed twice
     * is a scoping rule that will eventually disagree with itself.
     *
     * LinkedHashMap so the report comes out in a stable order rather than
     * whatever order the hash happened to produce, which would make two
     * identical requests return differently-ordered reports.
     */
    public List<PerformanceView> summarise() {
        AppUser me = users.currentActiveUser();

        List<Assessment> visible = me.getRole() == Role.TEACHER
                ? assessments.findForTeacher(me)
                : assessments.findByStudentOrderByIdAsc(me);

        /*
         * The key is the PAIR (student, course), held as a List rather than a
         * concatenated string.
         *
         * A string key needs a separator, and every separator is a bet that it
         * cannot appear in the values - which for a username is a bet, not a
         * fact. List carries proper equals/hashCode over its elements, so two
         * different pairs cannot collide however they are spelled, and there is
         * no delimiter to choose wrongly.
         *
         * This replaced a concatenation whose separator was, through a slip, a
         * NUL character. It worked - and made the whole file binary to grep and
         * ripgrep, so it silently vanished from every code search.
         */
        Map<List<Object>, List<Assessment>> grouped = new LinkedHashMap<>();
        for (Assessment assessment : visible) {
            List<Object> key = List.of(
                    assessment.getStudent().getUsername(),
                    assessment.getCourse().getId());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(assessment);
        }

        List<PerformanceView> report = new ArrayList<>();
        for (List<Assessment> group : grouped.values()) {
            Assessment first = group.get(0);

            BigDecimal totalScore = BigDecimal.ZERO;
            BigDecimal totalMax = BigDecimal.ZERO;
            for (Assessment assessment : group) {
                totalScore = totalScore.add(assessment.getScore());
                totalMax = totalMax.add(assessment.getMaxScore());
            }

            // totalMax cannot be zero here - every row carries max_score > 0 and
            // the group is non-empty - but the guard stays, because a division
            // that is "obviously" safe is exactly the one that fails after
            // somebody relaxes a constraint two years from now.
            BigDecimal percentage = totalMax.signum() == 0
                    ? null
                    : totalScore.multiply(BigDecimal.valueOf(100))
                                .divide(totalMax, 2, RoundingMode.HALF_UP);

            report.add(PerformanceView.of(
                    first.getStudent().getUsername(),
                    CourseView.of(first.getCourse()),
                    group.size(), totalScore, totalMax, percentage));
        }

        return report;
    }

    // ----- writing -------------------------------------------------------------

    /**
     * Record a mark.
     *
     * THE THREE THINGS THIS CHECKS THAT THE DATABASE CANNOT
     * The schema enforces plenty - score within maximum, non-negative, the right
     * roles, no duplicate name per student per course, and that a linked
     * submission really belongs to the named student. What it cannot express
     * without a trigger is the relationship that runs across three tables:
     *
     *   the student must actually be enrolled in the class this course is for
     *
     * So that one is checked here, and its absence from the schema is stated
     * rather than glossed over. Everything else is defence in depth behind a
     * constraint.
     */
    @Transactional
    public AssessmentView recordMark(Long courseId, String studentUsername, String name,
                                     BigDecimal score, BigDecimal maxScore,
                                     Long submissionId) {
        AppUser me = requireTeacher("record a mark");

        Course course = requireCourse(courseId);
        requireTeaches(course, me);

        AppUser student = users.findByUsernameOrReject(require(studentUsername, "Student"));
        if (student.getRole() != Role.STUDENT) {
            throw new IllegalArgumentException(
                    "'" + student.getUsername() + "' is a " + student.getRole()
                            + ", and only a student can be marked.");
        }
        if (!enrolments.existsByStudentAndSchoolClass(student, course.getSchoolClass())) {
            throw new IllegalArgumentException(
                    "'" + student.getUsername() + "' is not enrolled in "
                            + course.getSchoolClass().getName() + ".");
        }

        String cleanName = require(name, "Assessment name");
        validateMark(score, maxScore);

        /*
         * A courtesy check, not the guarantee. Two teachers recording the same
         * assessment at the same instant could both pass it before either wrote;
         * uq_assessment_student_course_name is what refuses the second INSERT.
         * This only buys a clearer message in the ordinary case.
         */
        assessments.findByStudentAndCourseAndNameIgnoreCase(student, course, cleanName)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "'" + student.getUsername() + "' already has a mark for '"
                                    + cleanName + "' in " + course.getLabel()
                                    + ". Edit that mark instead of adding a second one.");
                });

        Submission submission = resolveSubmission(submissionId, student, course);

        return AssessmentView.of(assessments.save(new Assessment(
                student, course, submission, cleanName, score, maxScore, me, Instant.now())));
    }

    /**
     * Correct a mark.
     *
     * Marks get entered wrong, and a system that cannot fix one invites the
     * worse workaround of deleting and re-adding, which loses who recorded it
     * and when. The score and its maximum are changed together, through one
     * method on the entity, because the rule binding them is a rule about the
     * pair - see Assessment.reMark.
     */
    @Transactional
    public AssessmentView updateMark(Long id, String name, BigDecimal score, BigDecimal maxScore) {
        AppUser me = requireTeacher("correct a mark");
        Assessment assessment = requireAssessment(id);
        requireTeaches(assessment.getCourse(), me);

        String cleanName = require(name, "Assessment name");
        validateMark(score, maxScore);

        assessment.setName(cleanName);
        assessment.reMark(score, maxScore);
        return AssessmentView.of(assessment);
    }

    @Transactional
    public void deleteMark(Long id) {
        AppUser me = requireTeacher("delete a mark");
        Assessment assessment = requireAssessment(id);
        requireTeaches(assessment.getCourse(), me);

        assessments.delete(assessment);
    }

    // ----- validation ----------------------------------------------------------

    /**
     * The scoring rules, checked here as well as in the schema.
     *
     * The duplication is the point, and it is the same argument made everywhere
     * else in this codebase: this produces a sentence a teacher can act on
     * ("42 is more than the maximum of 30"), while ck_assessment_score_within_max
     * makes the rule true of the DATA, for every writer, including a script that
     * never runs this method.
     *
     * Remove this and marks stay correct but the error is opaque. Remove the
     * constraint and the rule becomes a promise about the current code.
     */
    private void validateMark(BigDecimal score, BigDecimal maxScore) {
        if (score == null || maxScore == null) {
            throw new IllegalArgumentException("A mark needs both a score and a maximum.");
        }
        if (maxScore.signum() <= 0) {
            throw new IllegalArgumentException("The maximum score must be greater than zero.");
        }
        if (score.signum() < 0) {
            throw new IllegalArgumentException("A score cannot be negative.");
        }
        if (score.compareTo(maxScore) > 0) {
            throw new IllegalArgumentException(
                    "A score of " + score.stripTrailingZeros().toPlainString()
                            + " is higher than the maximum of "
                            + maxScore.stripTrailingZeros().toPlainString() + ".");
        }
    }

    /**
     * Resolve the optional link to the handed-in work this mark is for.
     *
     * Checked in Java AND by a composite foreign key on
     * (submission_id, student_id). The check here explains the problem; the key
     * makes attaching a mark to another student's work impossible rather than
     * merely refused by code somebody might later move.
     */
    private Submission resolveSubmission(Long submissionId, AppUser student, Course course) {
        if (submissionId == null) {
            return null;
        }

        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("submission", submissionId));

        if (!submission.getStudent().getId().equals(student.getId())) {
            throw new IllegalArgumentException(
                    "That submission belongs to a different student.");
        }
        if (!submission.getAssignment().getCourse().getId().equals(course.getId())) {
            throw new IllegalArgumentException(
                    "That submission was handed in for a different course.");
        }
        assessments.findBySubmission(submission).ifPresent(existing -> {
            throw new IllegalStateException(
                    "That submission has already been marked as '" + existing.getName() + "'.");
        });

        return submission;
    }

    // ----- shared guards -------------------------------------------------------

    /**
     * WHY THESE GO THROUGH Require.orThrow RATHER THAN .orElseThrow(...) DIRECTLY
     * ----------------------------------------------------------------------------
     * Both either return a real object or throw - never null - but
     * `Optional<T>.orElseThrow()` is a JDK method with no null-safety
     * annotations, so a method declared @NonNull that ended with it would still
     * be flagged internally: the compiler cannot see past java.util.Optional's
     * own unannotated type. Require.orThrow makes the same guarantee provable
     * with an ordinary null check instead of an annotation. See that class.
     */
    @NonNull
    private Assessment requireAssessment(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Assessment id must not be null.");
        }
        return Require.orThrow(assessments.findById(id),
                () -> new ResourceNotFoundException("assessment", id));
    }

    @NonNull
    private Course requireCourse(Long courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("Course id must not be null.");
        }
        return Require.orThrow(courses.findById(courseId),
                () -> new ResourceNotFoundException("course", courseId));
    }

    /** The caller must actually teach this course. Explained in AssignmentService. */
    private void requireTeaches(Course course, AppUser me) {
        boolean teachesIt = courses.findBySubjectAndSchoolClassAndTeacher(
                course.getSubject(), course.getSchoolClass(), me).isPresent();

        if (!teachesIt) {
            throw new AccessDeniedException("You do not teach " + course.getLabel() + ".");
        }
    }

    @NonNull
    private AppUser requireTeacher(String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Only a teacher can " + action + ".");
        }
        return me;
    }

    private String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank.");
        }
        return value.trim();
    }
}
