package com.example.tracker.service;

import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AssignmentRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the BUSINESS RULES, with no database and no web layer.
 *
 * These run in milliseconds because nothing is started: the repository and the
 * user service are mocked, so each test states a situation and checks the rule.
 * That isolation is the point - a failure here means a rule is wrong, not that
 * a query or an endpoint is.
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
 * overriding an annotated method rather than silencing the complaint. That option
 * does not exist here, because the unannotated signatures belong to third-party
 * libraries. Suppressing a warning we cannot fix, in test code, with the reason
 * written down, is honest; suppressing one in production code to make a real
 * defect disappear would not be.
 */
@SuppressWarnings("null")
class AssignmentServiceTest {

    @Mock private AssignmentRepository repository;
    @Mock private AppUserService users;
    @InjectMocks private AssignmentService service;

    private AppUser teacher;
    private AppUser student;

    @BeforeEach
    void setUp() {
        teacher = userWithId(1L, "teacher", Role.TEACHER);
        student = userWithId(2L, "student", Role.STUDENT);
    }

    /** AppUser has no id setter (the database assigns it), so reflection is used. */
    private AppUser userWithId(Long id, String name, Role role) {
        AppUser u = new AppUser(name, "hash", role);
        try {
            var f = AppUser.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    private Assignment owned(Long id, AppUser owner, AssignmentStatus status) {
        Assignment a = new Assignment("Some work", status, owner, null);
        try {
            var f = Assignment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return a;
    }

    @Nested
    @DisplayName("creating (US-02, US-16)")
    class Creating {

        @Test
        @DisplayName("a teacher can create, and the status is forced to IN_PROGRESS")
        void teacherCanCreate() {
            when(users.currentUser()).thenReturn(teacher);
            when(repository.save(any(Assignment.class))).thenAnswer(i -> i.getArgument(0));

            Assignment created = service.createAssignment("Science Lab", null, null);

            assertThat(created.getStatus()).isEqualTo(AssignmentStatus.IN_PROGRESS);
            assertThat(created.getOwner()).isEqualTo(teacher);
        }

        @Test
        @DisplayName("a student cannot create")
        void studentCannotCreate() {
            when(users.currentUser()).thenReturn(student);

            assertThatThrownBy(() -> service.createAssignment("Nope", null, null))
                    .isInstanceOf(AccessDeniedException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("the title is trimmed, and a blank one is refused")
        void titleIsTrimmedAndBlankRefused() {
            when(users.currentUser()).thenReturn(teacher);
            when(repository.save(any(Assignment.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(service.createAssignment("  Padded  ", null, null).getTitle())
                    .isEqualTo("Padded");

            assertThatThrownBy(() -> service.createAssignment("   ", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("work can be set FOR another account")
        void canAssignToSomeoneElse() {
            when(users.currentUser()).thenReturn(teacher);
            when(users.findByUsernameOrReject("student")).thenReturn(student);
            when(repository.save(any(Assignment.class))).thenAnswer(i -> i.getArgument(0));

            Assignment created = service.createAssignment("Homework", null, "student");

            // The teacher created it, but the STUDENT owns it - this is what makes
            // the student role mean anything.
            assertThat(created.getOwner()).isEqualTo(student);
        }
    }

    @Nested
    @DisplayName("submitting (US-03)")
    class Submitting {

        @Test
        @DisplayName("an assignment cannot be submitted twice")
        void cannotSubmitTwice() {
            Assignment a = owned(5L, student, AssignmentStatus.SUBMITTED);
            when(users.currentUser()).thenReturn(student);
            when(repository.findById(5L)).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> service.submitAssignment(5L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been submitted");
        }

        @Test
        @DisplayName("a null id is refused as a bad request, not a crash")
        void nullIdRefused() {
            assertThatThrownBy(() -> service.submitAssignment(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an unknown id is reported as not found")
        void unknownIdNotFound() {
            when(users.currentUser()).thenReturn(teacher);
            when(repository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitAssignment(999L))
                    .isInstanceOf(AssignmentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("visibility (US-15)")
    class Visibility {

        @Test
        @DisplayName("a student asking for someone else's row is told NOT FOUND, not FORBIDDEN")
        void otherPeoplesRowsLookMissing() {
            Assignment someoneElses = owned(7L, teacher, AssignmentStatus.IN_PROGRESS);
            when(users.currentUser()).thenReturn(student);
            when(repository.findById(7L)).thenReturn(Optional.of(someoneElses));

            // 404 rather than 403 is deliberate: answering "forbidden" would
            // confirm the row exists, letting an outsider map the data by
            // probing ids.
            assertThatThrownBy(() -> service.submitAssignment(7L))
                    .isInstanceOf(AssignmentNotFoundException.class);
        }

        @Test
        @DisplayName("a teacher sees every assignment; a student sees only their own")
        void listIsScopedByRole() {
            when(users.currentUser()).thenReturn(student);
            service.getAllAssignments();
            verify(repository).findByOwnerOrderByIdAsc(student);
            verify(repository, never()).findAllByOrderByIdAsc();

            reset(repository);
            when(users.currentUser()).thenReturn(teacher);
            service.getAllAssignments();
            verify(repository).findAllByOrderByIdAsc();
        }
    }

    @Nested
    @DisplayName("lifecycle (US-17, US-19)")
    class Lifecycle {

        @Test
        @DisplayName("a student cannot edit even their OWN assignment")
        void studentCannotEditOwnAssignment() {
            Assignment mine = owned(9L, student, AssignmentStatus.IN_PROGRESS);
            when(users.currentUser()).thenReturn(student);
            when(repository.findById(9L)).thenReturn(Optional.of(mine));

            // Editing follows the role, not the row: otherwise a student could
            // rewrite the assignment they had been set.
            assertThatThrownBy(() -> service.updateAssignment(9L, "Rewritten", null))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a teacher can edit work owned by a student")
        void teacherCanEditStudentsAssignment() {
            Assignment theirs = owned(9L, student, AssignmentStatus.IN_PROGRESS);
            when(users.currentUser()).thenReturn(teacher);
            when(repository.findById(9L)).thenReturn(Optional.of(theirs));

            Assignment updated = service.updateAssignment(9L, "Corrected title",
                    LocalDate.of(2026, 12, 1));

            assertThat(updated.getTitle()).isEqualTo("Corrected title");
            assertThat(updated.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        }

        @Test
        @DisplayName("a submitted assignment cannot be deleted")
        void cannotDeleteSubmitted() {
            Assignment submitted = owned(11L, student, AssignmentStatus.SUBMITTED);
            when(users.currentUser()).thenReturn(teacher);
            when(repository.findById(11L)).thenReturn(Optional.of(submitted));

            assertThatThrownBy(() -> service.deleteAssignment(11L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be deleted");
            verify(repository, never()).delete(any());
        }

        @Test
        @DisplayName("only a teacher can reopen, and only something submitted")
        void reopenRules() {
            Assignment submitted = owned(12L, student, AssignmentStatus.SUBMITTED);
            when(repository.findById(12L)).thenReturn(Optional.of(submitted));

            when(users.currentUser()).thenReturn(student);
            assertThatThrownBy(() -> service.unsubmitAssignment(12L))
                    .isInstanceOf(AccessDeniedException.class);

            when(users.currentUser()).thenReturn(teacher);
            assertThat(service.unsubmitAssignment(12L).getStatus())
                    .isEqualTo(AssignmentStatus.IN_PROGRESS);

            // Now it is IN_PROGRESS, reopening again makes no sense.
            assertThatThrownBy(() -> service.unsubmitAssignment(12L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("overdue (US-18)")
    class Overdue {

        @Test
        @DisplayName("past due and not submitted is overdue")
        void pastDueIsOverdue() {
            Assignment a = new Assignment("Late", AssignmentStatus.IN_PROGRESS, student,
                    LocalDate.now().minusDays(1));
            assertThat(a.isOverdue()).isTrue();
        }

        @Test
        @DisplayName("submitted work is never overdue, however late it was")
        void submittedIsNeverOverdue() {
            Assignment a = new Assignment("Late but done", AssignmentStatus.SUBMITTED,
                    student, LocalDate.now().minusDays(30));
            assertThat(a.isOverdue()).isFalse();
        }

        @Test
        @DisplayName("no due date means never overdue")
        void noDueDateIsNeverOverdue() {
            Assignment a = new Assignment("No deadline", AssignmentStatus.IN_PROGRESS,
                    student, null);
            assertThat(a.isOverdue()).isFalse();
        }

        @Test
        @DisplayName("due today is not yet overdue")
        void dueTodayIsNotOverdue() {
            Assignment a = new Assignment("Due today", AssignmentStatus.IN_PROGRESS,
                    student, LocalDate.now());
            assertThat(a.isOverdue()).isFalse();
        }
    }
}
