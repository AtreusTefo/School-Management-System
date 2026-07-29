package com.example.tracker;

import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.service.AssignmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

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
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("null")   // see AssignmentServiceTest for the reasoning
class ConcurrencyAndIntegrityTest {

    @Autowired private AssignmentService service;
    @Autowired private AssignmentRepository assignments;
    @Autowired private AppUserRepository users;

    /** Puts a real principal in the SecurityContext for the current thread. */
    private void actAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ----- CONSISTENCY ---------------------------------------------------------

    @Test
    @DisplayName("of 12 simultaneous submissions, exactly one succeeds")
    void onlyOneSimultaneousSubmissionSucceeds() throws Exception {
        actAs("teacher", "TEACHER");
        Long id = service.createAssignment("Concurrency probe", null, null).getId();

        final int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                // Each thread needs its own principal: the SecurityContext is
                // per-thread, and without this they would all act as nobody.
                actAs("teacher", "TEACHER");
                try {
                    startTogether.await();      // release them all at once
                    service.submitAssignment(id);
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
                .as("exactly one of %d simultaneous submissions should be accepted", threads)
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        // And the stored state agrees with the count.
        actAs("teacher", "TEACHER");
        assertThat(assignments.findById(id).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.SUBMITTED);
    }

    @Test
    @DisplayName("a rolled-back operation leaves nothing behind")
    void failedOperationWritesNothing() {
        actAs("teacher", "TEACHER");
        long before = assignments.count();

        // A blank title is refused by the service, so no row should be created.
        assertThatThrownBy(() -> service.createAssignment("   ", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(assignments.count())
                .as("a refused create must not leave a partial row")
                .isEqualTo(before);
    }

    // ----- REFERENTIAL INTEGRITY -----------------------------------------------

    @Test
    @DisplayName("an assignment cannot exist without an owner")
    void assignmentRequiresAnOwner() {
        Assignment orphan = new Assignment("No owner", AssignmentStatus.IN_PROGRESS, null);

        // owner is @NotNull and the column is NOT NULL with a foreign key, so
        // this is refused before it can become an orphaned row.
        assertThatThrownBy(() -> assignments.saveAndFlush(orphan))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    @DisplayName("deleting an account that still owns work is refused")
    void cannotDeleteAnOwnerWithAssignments() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        assertThat(assignments.existsByOwner(teacher)).isTrue();

        // There is deliberately no ON DELETE CASCADE: destroying somebody's work
        // as a side effect of removing their account should be an explicit
        // decision, not something the schema does quietly.
        assertThatThrownBy(() -> {
            users.delete(teacher);
            users.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- DATA INTEGRITY ------------------------------------------------------

    @Test
    @DisplayName("a duplicate username is refused by the database, not just by code")
    void usernameIsUnique() {
        AppUser duplicate = new AppUser("teacher", "$2a$10$someotherhash", com.example.tracker.model.Role.STUDENT);

        assertThatThrownBy(() -> users.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an over-long title is refused at the column, not silently truncated")
    void titleLengthIsEnforced() {
        AppUser teacher = users.findByUsername("teacher").orElseThrow();
        Assignment tooLong = new Assignment("x".repeat(500),
                AssignmentStatus.IN_PROGRESS, teacher);

        // Silent truncation would be the worst outcome: the row would be saved
        // and the data quietly wrong.
        assertThatThrownBy(() -> assignments.saveAndFlush(tooLong))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    @DisplayName("a deleted assignment is really gone")
    void deleteRemovesTheRow() {
        actAs("teacher", "TEACHER");
        Long id = service.createAssignment("To be removed", null, null).getId();

        service.deleteAssignment(id);

        assertThat(assignments.findById(id)).isEmpty();
        assertThatThrownBy(() -> service.submitAssignment(id))
                .isInstanceOf(AssignmentNotFoundException.class);
    }
}
