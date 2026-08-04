package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
import com.example.tracker.model.Submission;
import com.example.tracker.model.SubmissionFile;
import com.example.tracker.repository.*;
import com.example.tracker.service.SubmissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guarantees that are easy to claim and hard to keep: CONSISTENCY under
 * concurrency, and INTEGRITY enforced by the database rather than by hope.
 *
 * These deserve their own class because they are the two properties most likely
 * to regress silently. A broken authority rule fails loudly the first time
 * somebody tries it; a broken transaction boundary passes every single-user test
 * and only misbehaves under load, which is exactly how the original defect
 * survived - 3 of 12 simultaneous submissions were accepted before
 * `@Transactional` and `@Version` existed.
 *
 * WHAT THIS SUITE CANNOT PROVE, STATED HONESTLY
 * ---------------------------------------------
 * It runs on H2, built from the entity annotations. Two things therefore differ
 * from the SQL Server schema Flyway builds:
 *
 *   The COMPOSITE foreign keys - (student_id, student_role) referencing
 *   app_user(id, role) - cannot be expressed in JPA, so H2 gets a single-column
 *   foreign key plus the CHECK. That still refuses a row claiming a role it is
 *   not allowed to hold, which is what the tests below assert; what it cannot
 *   refuse is a row claiming STUDENT for a teacher's id. Only SQL Server rejects
 *   that, and it is verified by hand with sqlcmd.
 *
 *   The migrations themselves are never executed here.
 *
 * Both gaps are recorded in docs/project/PRD.md as L10 and R10 rather than being
 * left for somebody to discover.
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("null")   // see AssignmentServiceTest for the reasoning
class ConcurrencyAndIntegrityTest {

    @Autowired private SubmissionService submissionService;
    @Autowired private AssignmentRepository assignments;
    @Autowired private SubmissionRepository submissions;
    @Autowired private AppUserRepository users;
    @Autowired private SubjectRepository subjects;
    @Autowired private SchoolClassRepository classes;
    @Autowired private CourseRepository courses;
    @Autowired private EnrolmentRepository enrolments;

    /** The smallest thing that passes the magic-number check. */
    private static final byte[] TINY_PDF =
            "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    /** Puts a real principal in the SecurityContext for the current thread. */
    private void actAs(String username, Role role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ----- CONSISTENCY ---------------------------------------------------------

    @Test
    @DisplayName("of 12 simultaneous hand-ins, exactly one succeeds")
    void onlyOneSimultaneousSubmissionSucceeds() throws Exception {
        AppUser student = users.findByUsername("student").orElseThrow();
        Submission target = freshSubmissionFor(student);

        actAs("student", Role.STUDENT);
        submissionService.uploadFile(target.getId(), "work.pdf", "application/pdf", TINY_PDF);

        final int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                // Each thread needs its own principal: the SecurityContext is
                // per-thread, and without this they would all act as nobody.
                actAs("student", Role.STUDENT);
                try {
                    startTogether.await();      // release them all at once
                    submissionService.submit(target.getId());
                    accepted.incrementAndGet();
                } catch (Exception e) {
                    // Either the business rule caught it (already submitted) or
                    // optimistic locking did (someone else committed first).
                    // Both are correct refusals.
                    rejected.incrementAndGet();
                }
                return null;
            });
        }

        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted.get())
                .as("exactly one of %d simultaneous hand-ins should be accepted", threads)
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        // And the stored state agrees with the count.
        Submission stored = submissions.findById(target.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AssignmentStatus.SUBMITTED);
        assertThat(stored.getSubmittedAt()).isNotNull();
    }

    @Test
    @DisplayName("status and submission time cannot contradict each other")
    void statusAndTimestampMustAgree() throws Exception {
        AppUser student = users.findByUsername("student").orElseThrow();
        Submission submission = freshSubmissionFor(student);

        /*
         * Set the status WITHOUT the timestamp, going around markSubmitted() -
         * which is exactly what a future refactor might do by accident. The
         * entity's paired methods are the convenience; the CHECK constraint is
         * the guarantee, and this proves the guarantee is real rather than a
         * comment about intentions.
         */
        var field = Submission.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(submission, AssignmentStatus.SUBMITTED);

        assertThatThrownBy(() -> submissions.saveAndFlush(submission))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a refused upload leaves no file row behind")
    void refusedUploadWritesNothing() {
        AppUser student = users.findByUsername("student").orElseThrow();
        Submission submission = freshSubmissionFor(student);
        actAs("student", Role.STUDENT);

        byte[] notAPdf = "MZ this is an executable".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> submissionService.uploadFile(
                submission.getId(), "essay.pdf", "application/pdf", notAPdf))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(submissions.findById(submission.getId()).orElseThrow().getFile())
                .as("a rejected upload must not leave a partial file row")
                .isNull();
    }

    // ----- REFERENTIAL INTEGRITY -----------------------------------------------

    @Test
    @DisplayName("a submission cannot exist without an assignment or a student")
    void submissionRequiresBothParents() {
        AppUser student = users.findByUsername("student").orElseThrow();
        Assignment assignment = assignments.findAllByOrderByIdAsc().get(0);

        assertThatThrownBy(() -> submissions.saveAndFlush(new Submission(null, student)))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);

        assertThatThrownBy(() -> submissions.saveAndFlush(new Submission(assignment, null)))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    @DisplayName("deleting a student who still has submissions is refused")
    void cannotDeleteAStudentWithWork() {
        AppUser student = users.findByUsername("student").orElseThrow();
        assertThat(submissions.existsByStudent(student)).isTrue();

        // There is deliberately no ON DELETE CASCADE: destroying somebody's work
        // as a side effect of removing their account should be an explicit
        // decision, not something the schema does quietly.
        assertThatThrownBy(() -> {
            users.delete(student);
            users.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a teacher who still teaches a course is refused")
    void cannotDeleteATeacherWithCourses() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        assertThat(courses.existsByTeacher(teacher)).isTrue();

        assertThatThrownBy(() -> {
            users.delete(teacher);
            users.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a class that still has a register is refused")
    void cannotDeleteAClassWithStudents() {
        SchoolClass tenA = classes.findByNameIgnoreCase("Grade 10A").orElseThrow();
        assertThat(enrolments.existsBySchoolClass(tenA)).isTrue();

        assertThatThrownBy(() -> {
            classes.delete(tenA);
            classes.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- ROLE GUARDS ---------------------------------------------------------

    @Test
    @DisplayName("only a student can be enrolled in a class")
    void onlyAStudentCanBeEnrolled() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        SchoolClass tenB = classes.findByNameIgnoreCase("Grade 10B").orElseThrow();

        // ck_enrolment_student_role refuses the row. On SQL Server the composite
        // foreign key refuses it a second way, by requiring the claimed role to
        // match the referenced account's real one.
        assertThatThrownBy(() -> enrolments.saveAndFlush(new Enrolment(teacher, tenB)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("only a teacher can teach a course")
    void onlyATeacherCanTeach() {
        AppUser student = users.findByUsername("student").orElseThrow();
        Subject maths = subjects.findByCodeIgnoreCase("MATH").orElseThrow();
        SchoolClass tenB = classes.findByNameIgnoreCase("Grade 10B").orElseThrow();

        assertThatThrownBy(() -> courses.saveAndFlush(new Course(maths, tenB, student)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- UNIQUENESS ----------------------------------------------------------

    @Test
    @DisplayName("a student cannot be enrolled in the same class twice")
    void enrolmentIsUnique() {
        AppUser student = users.findByUsername("student").orElseThrow();
        SchoolClass tenA = classes.findByNameIgnoreCase("Grade 10A").orElseThrow();

        assertThatThrownBy(() -> enrolments.saveAndFlush(new Enrolment(student, tenA)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a student cannot have two submissions for one assignment")
    void submissionIsUniquePerStudentAndAssignment() {
        AppUser student = users.findByUsername("student").orElseThrow();
        Submission existing = submissions.findByStudentOrderByIdAsc(student).get(0);

        // THE constraint the whole fan-out relies on. Even a fan-out run twice
        // cannot double a student's row.
        assertThatThrownBy(() -> submissions.saveAndFlush(
                new Submission(existing.getAssignment(), student)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same teacher cannot be given the same course twice")
    void courseIsUnique() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        Subject maths = subjects.findByCodeIgnoreCase("MATH").orElseThrow();
        SchoolClass tenA = classes.findByNameIgnoreCase("Grade 10A").orElseThrow();

        assertThatThrownBy(() -> courses.saveAndFlush(new Course(maths, tenA, teacher)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("subject codes, subject names and class names are all unique")
    void referenceDataIsUnique() {
        assertThatThrownBy(() -> subjects.saveAndFlush(new Subject("MATH", "Something else")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> subjects.saveAndFlush(new Subject("XX", "Mathematics")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> classes.saveAndFlush(new SchoolClass("Grade 10A")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a duplicate username is refused by the database, not just by code")
    void usernameIsUnique() {
        assertThatThrownBy(() -> users.saveAndFlush(
                new AppUser("teacher", "$2a$10$someotherhash", Role.STUDENT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- VALUE CONSTRAINTS ---------------------------------------------------

    @Test
    @DisplayName("an over-long title is refused at the column, not silently truncated")
    void titleLengthIsEnforced() {
        Assignment existing = assignments.findAllByOrderByIdAsc().get(0);
        Assignment tooLong = new Assignment(
                "x".repeat(500), null, existing.getCourse(), existing.getCreatedBy(), null);

        // Silent truncation would be the worst outcome: the row would be saved
        // and the data quietly wrong.
        assertThatThrownBy(() -> assignments.saveAndFlush(tooLong))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    @DisplayName("blank subject and class names are refused by the database")
    void blankReferenceNamesAreRefused() {
        assertThatThrownBy(() -> subjects.saveAndFlush(new Subject("  ", "  ")))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);

        assertThatThrownBy(() -> classes.saveAndFlush(new SchoolClass("   ")))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    @DisplayName("a non-PDF content type is refused by the column")
    void onlyPdfContentTypeIsStorable() {
        AppUser student = users.findByUsername("student").orElseThrow();
        Submission submission = freshSubmissionFor(student);

        // Going around the service entirely, which is the point: the service
        // checks the magic bytes, and the COLUMN refuses anything not declared
        // as a PDF even when nothing checked the bytes at all.
        SubmissionFile wrongType = new SubmissionFile(
                submission, "notes.txt", "text/plain",
                TINY_PDF, "a".repeat(64), Instant.now());

        assertThatThrownBy(() -> {
            submission.setFile(wrongType);
            submissions.saveAndFlush(submission);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an empty file is refused by the column")
    void emptyFileIsRefused() {
        AppUser student = users.findByUsername("student").orElseThrow();
        Submission submission = freshSubmissionFor(student);

        SubmissionFile empty = new SubmissionFile(
                submission, "empty.pdf", SubmissionFile.PDF_CONTENT_TYPE,
                new byte[0], "a".repeat(64), Instant.now());

        assertThatThrownBy(() -> {
            submission.setFile(empty);
            submissions.saveAndFlush(submission);
        }).isInstanceOfAny(DataIntegrityViolationException.class,
                           jakarta.validation.ConstraintViolationException.class);
    }

    // ----- helpers -------------------------------------------------------------

    /**
     * A brand new assignment on a course the student is in, with their own
     * submission row - so each test starts from a state it fully controls rather
     * than depending on what a previous test left behind.
     */
    private Submission freshSubmissionFor(AppUser student) {
        Enrolment enrolment = enrolments.findByStudentOrderByIdAsc(student).get(0);
        Course course = courses.findBySchoolClassOrderByIdAsc(enrolment.getSchoolClass()).get(0);

        Assignment assignment = assignments.save(new Assignment(
                "Probe " + System.nanoTime(), null, course, course.getTeacher(), null));

        return submissions.save(new Submission(assignment, student));
    }
}
