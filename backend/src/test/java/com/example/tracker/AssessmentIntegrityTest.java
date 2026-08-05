package com.example.tracker;

import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.model.*;
import com.example.tracker.repository.*;
import com.example.tracker.service.AssessmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Marks, and the arithmetic built on them.
 *
 * A wrong mark is loud - somebody notices immediately. A wrong AVERAGE is quiet:
 * it looks like a number, it sorts, it exports to a report, and nobody can tell
 * by looking that it was computed the wrong way. So the arithmetic gets tests of
 * its own, not just the storage.
 *
 * WHAT THIS SUITE CANNOT PROVE
 * It runs on H2, built from the entity annotations. Two things in migration V6
 * therefore have no equivalent here and are checked by hand with sqlcmd:
 *
 *   the composite foreign key (submission_id, student_id) -> submission(id, student_id),
 *   which makes attaching a mark to another student's work impossible;
 *
 *   the FILTERED unique index on submission_id, which enforces "one mark per
 *   submission" only for rows that have one. H2 has no filtered indexes.
 *
 * Both gaps are recorded in docs/project/PRD.md L10.
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("null")   // see AssignmentServiceTest for the reasoning
class AssessmentIntegrityTest {

    @Autowired private AssessmentService service;
    @Autowired private AssessmentRepository assessments;
    @Autowired private AppUserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private SubjectRepository subjects;
    @Autowired private SchoolClassRepository classes;
    @Autowired private SubmissionRepository submissions;

    private void actAs(String username, Role role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Course mathsTenA() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        return courses.findBySubjectAndSchoolClassAndTeacher(
                subjects.findByCodeIgnoreCase("MATH").orElseThrow(),
                classes.findByNameIgnoreCase("Grade 10A").orElseThrow(),
                teacher).orElseThrow();
    }

    /** A name no other test uses, so these can run in any order. */
    private String uniqueName() {
        return "Probe " + System.nanoTime();
    }

    // ----- THE SCORING RULE ----------------------------------------------------

    @Test
    @DisplayName("a score higher than the maximum is refused, with both numbers named")
    void scoreCannotExceedMaximum() {
        actAs("teacher", Role.TEACHER);

        assertThatThrownBy(() -> service.recordMark(
                mathsTenA().getId(), "student", uniqueName(),
                new BigDecimal("42"), new BigDecimal("30"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("30");
    }

    @Test
    @DisplayName("the database refuses an over-maximum score even when the service is bypassed")
    void databaseRefusesOverMaximumScore() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        AppUser student = users.findByUsername("student").orElseThrow();

        // Going straight to the repository - no service, no validation. This is
        // the difference between a rule and a promise about the current code.
        Assessment corrupt = new Assessment(
                student, mathsTenA(), null, uniqueName(),
                new BigDecimal("42"), new BigDecimal("30"), teacher, Instant.now());

        assertThatThrownBy(() -> assessments.saveAndFlush(corrupt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a negative score, and a maximum of zero, are both refused")
    void scoreBoundsAreEnforced() {
        actAs("teacher", Role.TEACHER);
        Long courseId = mathsTenA().getId();

        assertThatThrownBy(() -> service.recordMark(courseId, "student", uniqueName(),
                new BigDecimal("-1"), new BigDecimal("30"), null))
                .isInstanceOf(IllegalArgumentException.class);

        // A maximum of zero would make every percentage a division by zero.
        assertThatThrownBy(() -> service.recordMark(courseId, "student", uniqueName(),
                new BigDecimal("0"), new BigDecimal("0"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a full mark is allowed - the boundary is inclusive")
    void fullMarksAreAllowed() {
        actAs("teacher", Role.TEACHER);

        var recorded = service.recordMark(mathsTenA().getId(), "student", uniqueName(),
                new BigDecimal("30"), new BigDecimal("30"), null);

        // Off-by-one on this boundary would refuse every perfect score, which is
        // the kind of bug that only shows up on somebody's best day.
        assertThat(recorded.percentage()).isEqualByComparingTo("100.00");
        assertThat(recorded.level()).isEqualTo(PerformanceLevel.OUTSTANDING);
    }

    // ----- THE ARITHMETIC ------------------------------------------------------

    @Test
    @DisplayName("the percentage is a ratio of totals, NOT the mean of the percentages")
    void averageIsWeightedByMaximum() {
        actAs("teacher", Role.TEACHER);

        AppUser student3 = users.findByUsername("student3").orElseThrow();
        SchoolClass tenB = classes.findByNameIgnoreCase("Grade 10B").orElseThrow();
        Course mathsTenB = courses.findBySubjectAndSchoolClassAndTeacher(
                subjects.findByCodeIgnoreCase("MATH").orElseThrow(), tenB,
                users.findByUsername("teacher").orElseThrow()).orElseThrow();

        service.recordMark(mathsTenB.getId(), "student3", "Weighting probe A",
                new BigDecimal("5"), new BigDecimal("10"), null);
        service.recordMark(mathsTenB.getId(), "student3", "Weighting probe B",
                new BigDecimal("90"), new BigDecimal("100"), null);

        var summary = service.summarise().stream()
                .filter(p -> p.studentUsername().equals(student3.getUsername()))
                .filter(p -> p.courseId().equals(mathsTenB.getId()))
                .findFirst().orElseThrow();

        /*
         * 95 out of 110 is 86.36%.
         *
         * Averaging the two percentages instead gives (50 + 90) / 2 = 70%, which
         * silently treats a ten-mark quiz as equal in weight to a hundred-mark
         * exam. Both numbers look plausible on a report; only one is right, and
         * this test is what stops the wrong one being introduced by someone
         * "simplifying" the calculation later.
         */
        assertThat(summary.totalScore()).isEqualByComparingTo("95");
        assertThat(summary.totalMaxScore()).isEqualByComparingTo("110");
        assertThat(summary.percentage()).isEqualByComparingTo("86.36");
        assertThat(summary.percentage()).isNotEqualByComparingTo("70.00");
        assertThat(summary.level()).isEqualTo(PerformanceLevel.OUTSTANDING);
    }

    @Test
    @DisplayName("performance bands are applied at their stated floors")
    void performanceBandsAreCorrect() {
        // Checked directly rather than through stored data, because a threshold
        // is a policy decision and this is the one place it is written down.
        assertThat(PerformanceLevel.of(new BigDecimal("80"))).isEqualTo(PerformanceLevel.OUTSTANDING);
        assertThat(PerformanceLevel.of(new BigDecimal("79.99"))).isEqualTo(PerformanceLevel.MERITORIOUS);
        assertThat(PerformanceLevel.of(new BigDecimal("50"))).isEqualTo(PerformanceLevel.ADEQUATE);
        assertThat(PerformanceLevel.of(new BigDecimal("49.99"))).isEqualTo(PerformanceLevel.MODERATE);
        assertThat(PerformanceLevel.of(new BigDecimal("0"))).isEqualTo(PerformanceLevel.NOT_ACHIEVED);

        // "No marks yet" is not "failed". Collapsing the two would label every
        // new student a failure on their first day.
        assertThat(PerformanceLevel.of(null)).isNull();
    }

    @Test
    @DisplayName("a percentage that does not divide evenly is rounded, not truncated")
    void roundingIsHalfUp() {
        actAs("teacher", Role.TEACHER);

        var recorded = service.recordMark(mathsTenA().getId(), "student2", uniqueName(),
                new BigDecimal("2"), new BigDecimal("3"), null);

        // 66.666... to two places is 66.67, not 66.66.
        assertThat(recorded.percentage()).isEqualByComparingTo("66.67");
    }

    // ----- AUTHORITY -----------------------------------------------------------

    @Test
    @DisplayName("a student cannot record a mark")
    void studentCannotMark() {
        actAs("student", Role.STUDENT);

        assertThatThrownBy(() -> service.recordMark(
                mathsTenA().getId(), "student", uniqueName(),
                new BigDecimal("100"), new BigDecimal("100"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a teacher cannot mark for a course somebody else teaches")
    void teacherCannotMarkAnotherCourse() {
        // teacher2 takes Science only; Maths for 10A belongs to 'teacher'.
        actAs("teacher2", Role.TEACHER);

        assertThatThrownBy(() -> service.recordMark(
                mathsTenA().getId(), "student", uniqueName(),
                new BigDecimal("10"), new BigDecimal("20"), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("do not teach");
    }

    @Test
    @DisplayName("a student's report contains only their own marks")
    void studentSeesOnlyTheirOwnMarks() {
        actAs("student", Role.STUDENT);

        assertThat(service.listAssessments())
                .isNotEmpty()
                .allSatisfy(a -> assertThat(a.studentUsername()).isEqualTo("student"));

        assertThat(service.summarise())
                .allSatisfy(p -> assertThat(p.studentUsername()).isEqualTo("student"));
    }

    // ----- REFERENTIAL INTEGRITY -----------------------------------------------

    @Test
    @DisplayName("a student not enrolled in the class cannot be marked for its course")
    void cannotMarkAStudentWhoIsNotEnrolled() {
        actAs("teacher", Role.TEACHER);

        // student3 is in Grade 10B, not 10A.
        assertThatThrownBy(() -> service.recordMark(
                mathsTenA().getId(), "student3", uniqueName(),
                new BigDecimal("10"), new BigDecimal("20"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not enrolled");
    }

    @Test
    @DisplayName("only a student can be marked, and only a teacher can mark")
    void roleGuardsHold() {
        actAs("teacher", Role.TEACHER);

        assertThatThrownBy(() -> service.recordMark(
                mathsTenA().getId(), "teacher2", uniqueName(),
                new BigDecimal("10"), new BigDecimal("20"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only a student can be marked");

        // And at the storage layer, bypassing the service entirely.
        AppUser teacher2 = users.findByUsername("teacher2").orElseThrow();
        Assessment wrongRole = new Assessment(
                teacher2, mathsTenA(), null, uniqueName(),
                new BigDecimal("10"), new BigDecimal("20"),
                users.findByUsername("teacher").orElseThrow(), Instant.now());

        assertThatThrownBy(() -> assessments.saveAndFlush(wrongRole))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same assessment cannot be recorded twice for one student")
    void assessmentNameIsUniquePerStudentAndCourse() {
        actAs("teacher", Role.TEACHER);
        String name = uniqueName();
        Long courseId = mathsTenA().getId();

        service.recordMark(courseId, "student", name, new BigDecimal("10"), new BigDecimal("20"), null);

        // Without this rule a double-clicked form records the mark twice and
        // every average computed afterwards is quietly wrong.
        assertThatThrownBy(() -> service.recordMark(
                courseId, "student", name, new BigDecimal("10"), new BigDecimal("20"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has a mark");
    }

    @Test
    @DisplayName("a mark cannot be attached to another student's submission")
    void cannotMarkAnotherStudentsWork() {
        actAs("teacher", Role.TEACHER);

        AppUser other = users.findByUsername("student2").orElseThrow();
        List<Submission> theirs = submissions.findByStudentOrderByIdAsc(other);
        org.junit.jupiter.api.Assumptions.assumeTrue(!theirs.isEmpty());

        Submission theirSubmission = theirs.get(0);

        // Naming 'student' while pointing at student2's work. Refused here with a
        // clear message; on SQL Server the composite foreign key on
        // (submission_id, student_id) refuses it a second time, which is what
        // makes it impossible rather than merely checked.
        assertThatThrownBy(() -> service.recordMark(
                theirSubmission.getAssignment().getCourse().getId(), "student", uniqueName(),
                new BigDecimal("10"), new BigDecimal("20"), theirSubmission.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different student");
    }

    @Test
    @DisplayName("deleting a student who has marks is refused")
    void cannotDeleteAMarkedStudent() {
        AppUser student = users.findByUsername("student").orElseThrow();
        assertThat(assessments.existsByStudent(student)).isTrue();

        assertThatThrownBy(() -> {
            users.delete(student);
            users.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- CORRECTING A MARK ---------------------------------------------------

    @Test
    @DisplayName("a mark can be corrected, and the correction obeys the same rules")
    void marksCanBeCorrected() {
        actAs("teacher", Role.TEACHER);
        var recorded = service.recordMark(mathsTenA().getId(), "student", uniqueName(),
                new BigDecimal("10"), new BigDecimal("20"), null);

        var corrected = service.updateMark(recorded.id(), recorded.name(),
                new BigDecimal("18"), new BigDecimal("20"));
        assertThat(corrected.percentage()).isEqualByComparingTo("90.00");

        // The scoring rule applies to a correction exactly as it does to a new
        // mark - otherwise the edit path would be a way around it.
        assertThatThrownBy(() -> service.updateMark(recorded.id(), recorded.name(),
                new BigDecimal("50"), new BigDecimal("20")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
