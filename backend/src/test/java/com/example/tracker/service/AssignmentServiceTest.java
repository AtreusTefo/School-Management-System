package com.example.tracker.service;

import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
import com.example.tracker.model.Submission;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the BUSINESS RULES, with no database and no web layer.
 *
 * These run in milliseconds because nothing is started: the repositories and the
 * user service are mocked, so each test states a situation and checks the rule.
 * That isolation is the point - a failure here means a rule is wrong, not that a
 * query or an endpoint is.
 *
 * The rules that need real relationships - role guards enforced by the schema,
 * the fan-out reaching an actual register, concurrency - are NOT here. Mocking
 * them would only prove the mocks agree with themselves. They live in
 * ConcurrencyAndIntegrityTest and SchoolIntegrityTest, against a real database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
/*
 * WHY NULL ANALYSIS IS SUPPRESSED HERE, AND WHY THAT IS NOT A COP-OUT.
 *
 * The editor runs Eclipse's null analysis (java.compile.nullAnalysis.mode), which
 * earned its place by catching a genuine gap in AssignmentService. It reports
 * "needs unchecked conversion to conform to @NonNull" all over this file, but for
 * a different reason: Mockito's when()/thenReturn() and Optional.of() are not
 * null-annotated, so the analysis cannot prove what it can plainly see - every
 * value passed below was constructed two lines earlier and is provably non-null.
 *
 * Elsewhere this warning was removed by CHANGING THE CODE - CorsConfig stopped
 * overriding an annotated method rather than silencing the complaint, and
 * SchoolService gained a real null guard it was missing. That option does not
 * exist here, because the unannotated signatures belong to third-party libraries.
 * Suppressing a warning we cannot fix, in test code, with the reason written
 * down, is honest; suppressing one in production code to make a real defect
 * disappear would not be.
 */
@SuppressWarnings("null")
class AssignmentServiceTest {

    @Mock private AssignmentRepository assignments;
    @Mock private SubmissionRepository submissions;
    @Mock private CourseRepository courses;
    @Mock private EnrolmentRepository enrolments;
    @Mock private AppUserService users;
    @Mock private AuditLogService audit;
    @InjectMocks private AssignmentService service;

    private AppUser teacher;
    private AppUser otherTeacher;
    private AppUser student;
    private Subject maths;
    private SchoolClass tenA;
    private Course mathsTenA;

    @BeforeEach
    void setUp() {
        teacher = userWithId(1L, "teacher", Role.TEACHER);
        otherTeacher = userWithId(2L, "teacher2", Role.TEACHER);
        student = userWithId(3L, "student", Role.STUDENT);

        maths = withId(new Subject("MATH", "Mathematics"), 10L);
        tenA = withId(new SchoolClass("Grade 10A"), 20L);
        mathsTenA = withId(new Course(maths, tenA, teacher), 30L);

        when(courses.findById(30L)).thenReturn(Optional.of(mathsTenA));
        // "the caller teaches this course" resolves for teacher, not for anyone else
        when(courses.findBySubjectAndSchoolClassAndTeacher(maths, tenA, teacher))
                .thenReturn(Optional.of(mathsTenA));
        when(courses.findBySubjectAndSchoolClassAndTeacher(maths, tenA, otherTeacher))
                .thenReturn(Optional.empty());
        when(assignments.save(any(Assignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ----- creating ------------------------------------------------------------

    @Nested
    @DisplayName("setting work")
    class Creating {

        @Test
        @DisplayName("a student cannot set work")
        void studentCannotCreate() {
            when(users.currentActiveUser()).thenReturn(student);

            assertThatThrownBy(() -> service.createAssignment("Homework", null, null, List.of(30L)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Only a teacher");

            verify(assignments, never()).save(any());
        }

        @Test
        @DisplayName("a blank title is refused")
        void blankTitleRefused() {
            when(users.currentActiveUser()).thenReturn(teacher);

            assertThatThrownBy(() -> service.createAssignment("   ", null, null, List.of(30L)))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(assignments, never()).save(any());
        }

        @Test
        @DisplayName("work must be set for at least one course")
        void noCourseRefused() {
            when(users.currentActiveUser()).thenReturn(teacher);

            assertThatThrownBy(() -> service.createAssignment("Homework", null, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one course");

            assertThatThrownBy(() -> service.createAssignment("Homework", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a teacher cannot set work for a course they do not teach")
        void cannotSetWorkForSomebodyElsesCourse() {
            when(users.currentActiveUser()).thenReturn(otherTeacher);

            assertThatThrownBy(() -> service.createAssignment("Homework", null, null, List.of(30L)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("do not teach");

            verify(assignments, never()).save(any());
        }

        @Test
        @DisplayName("the title and description are trimmed, and a blank description becomes null")
        void inputIsNormalised() {
            when(users.currentActiveUser()).thenReturn(teacher);
            when(enrolments.findBySchoolClassOrderByIdAsc(tenA)).thenReturn(List.of());

            var created = service.createAssignment("  Homework  ", "   ", null, List.of(30L));

            assertThat(created).hasSize(1);
            assertThat(created.get(0).title()).isEqualTo("Homework");
            assertThat(created.get(0).description()).isNull();
        }

        @Test
        @DisplayName("one request for two courses produces two assignments")
        void oneRequestCanCoverSeveralClasses() {
            SchoolClass tenB = withId(new SchoolClass("Grade 10B"), 21L);
            Course mathsTenB = withId(new Course(maths, tenB, teacher), 31L);
            when(courses.findById(31L)).thenReturn(Optional.of(mathsTenB));
            when(courses.findBySubjectAndSchoolClassAndTeacher(maths, tenB, teacher))
                    .thenReturn(Optional.of(mathsTenB));
            when(users.currentActiveUser()).thenReturn(teacher);
            when(enrolments.findBySchoolClassOrderByIdAsc(any())).thenReturn(List.of());

            var created = service.createAssignment("Homework", null, null, List.of(30L, 31L));

            assertThat(created).hasSize(2);
            assertThat(created).extracting("className")
                    .containsExactly("Grade 10A", "Grade 10B");
            verify(assignments, times(2)).save(any(Assignment.class));
        }

        @Test
        @DisplayName("every enrolled student gets their own submission")
        void fansOutToTheWholeClass() {
            AppUser second = userWithId(4L, "student2", Role.STUDENT);
            when(users.currentActiveUser()).thenReturn(teacher);
            when(enrolments.findBySchoolClassOrderByIdAsc(tenA)).thenReturn(List.of(
                    new Enrolment(student, tenA),
                    new Enrolment(second, tenA)));

            var created = service.createAssignment("Homework", null, null, List.of(30L));

            // One row per student in the register - the whole point of the split.
            verify(submissions, times(2)).save(any(Submission.class));
            assertThat(created.get(0).studentCount()).isEqualTo(2);
            assertThat(created.get(0).submittedCount()).isZero();
        }

        @Test
        @DisplayName("a class with nobody in it yet is allowed, and reports zero")
        void emptyClassIsAllowed() {
            when(users.currentActiveUser()).thenReturn(teacher);
            when(enrolments.findBySchoolClassOrderByIdAsc(tenA)).thenReturn(List.of());

            var created = service.createAssignment("Homework", null, null, List.of(30L));

            assertThat(created.get(0).studentCount()).isZero();
            verify(submissions, never()).save(any());
        }
    }

    // ----- editing and deleting ------------------------------------------------

    @Nested
    @DisplayName("changing work that was set")
    class Changing {

        private Assignment existing;

        @BeforeEach
        void given() {
            existing = withId(
                    new Assignment("Homework", null, mathsTenA, teacher, null), 100L);
            when(assignments.findById(100L)).thenReturn(Optional.of(existing));
            when(submissions.findByAssignmentOrderByIdAsc(existing)).thenReturn(List.of());
        }

        @Test
        @DisplayName("an unknown id is reported as not found")
        void unknownIdIsNotFound() {
            when(users.currentActiveUser()).thenReturn(teacher);
            when(assignments.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateAssignment(999L, "New", null, null))
                    .isInstanceOf(AssignmentNotFoundException.class)
                    .hasMessageContaining("999");
        }

        @Test
        @DisplayName("a null id is a bad request, not a server fault")
        void nullIdIsBadRequest() {
            when(users.currentActiveUser()).thenReturn(teacher);

            assertThatThrownBy(() -> service.updateAssignment(null, "New", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a student cannot edit")
        void studentCannotEdit() {
            when(users.currentActiveUser()).thenReturn(student);

            assertThatThrownBy(() -> service.updateAssignment(100L, "New", null, null))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a teacher who does not teach the course cannot edit")
        void foreignTeacherCannotEdit() {
            when(users.currentActiveUser()).thenReturn(otherTeacher);

            assertThatThrownBy(() -> service.updateAssignment(100L, "New", null, null))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("do not teach");
        }

        @Test
        @DisplayName("the title, description and due date are all editable")
        void editApplies() {
            when(users.currentActiveUser()).thenReturn(teacher);
            LocalDate due = LocalDate.of(2027, 1, 15);

            var updated = service.updateAssignment(100L, "  Renamed  ", "  Do it well  ", due);

            assertThat(updated.title()).isEqualTo("Renamed");
            assertThat(updated.description()).isEqualTo("Do it well");
            assertThat(updated.dueDate()).isEqualTo(due);
        }

        @Test
        @DisplayName("a blank title is refused on edit, as it is on create")
        void blankTitleRefusedOnEdit() {
            when(users.currentActiveUser()).thenReturn(teacher);

            assertThatThrownBy(() -> service.updateAssignment(100L, "  ", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("work that has been handed in cannot be deleted")
        void cannotDeleteOnceHandedIn() {
            when(users.currentActiveUser()).thenReturn(teacher);
            when(submissions.existsByAssignmentAndStatus(existing, AssignmentStatus.SUBMITTED))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.deleteAssignment(100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Reopen");

            verify(assignments, never()).delete(any());
        }

        @Test
        @DisplayName("deleting removes the submissions first, then the assignment")
        void deleteRemovesChildrenExplicitly() {
            when(users.currentActiveUser()).thenReturn(teacher);
            when(submissions.existsByAssignmentAndStatus(existing, AssignmentStatus.SUBMITTED))
                    .thenReturn(false);

            service.deleteAssignment(100L);

            // Explicitly, because the schema has no ON DELETE CASCADE - a cascade
            // would make the refusal above bypassable from anywhere else.
            verify(submissions).deleteAll(any());
            verify(assignments).delete(existing);
        }
    }

    // ----- listing -------------------------------------------------------------

    @Test
    @DisplayName("a teacher sees their own courses' work; a student sees their classes'")
    void listIsScopedByRole() {
        when(users.currentActiveUser()).thenReturn(teacher);
        when(courses.findByTeacherOrderByIdAsc(teacher)).thenReturn(List.of(mathsTenA));
        when(assignments.findByCourseInOrderByIdAsc(List.of(mathsTenA))).thenReturn(List.of());

        service.listAssignments();
        verify(assignments).findByCourseInOrderByIdAsc(List.of(mathsTenA));
        verify(assignments, never()).findForStudent(any());

        when(users.currentActiveUser()).thenReturn(student);
        when(assignments.findForStudent(student)).thenReturn(List.of());

        service.listAssignments();
        // The scoping is in the QUERY. Fetching everything and filtering in Java
        // would load other classes' rows before deciding not to show them.
        verify(assignments).findForStudent(student);
    }

    // ----- helpers -------------------------------------------------------------

    /** Entities have no id setter (the database assigns it), so reflection is used. */
    private AppUser userWithId(Long id, String name, Role role) {
        return withId(new AppUser(name, "hash", role), id);
    }

    private <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }
}
