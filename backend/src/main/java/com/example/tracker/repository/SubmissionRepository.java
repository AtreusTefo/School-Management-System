package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for each student's state on each assignment.
 *
 * The scoping queries live here rather than as a filter in the service, and
 * certainly not in the frontend. Restricting rows in the QUERY means another
 * student's work is never loaded, never serialised and never sent. Fetching
 * everything and declining to draw some of it is not access control.
 */
@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /**
     * Everything one student owes or has handed in - what a student sees.
     *
     * WHY THE LIST RETURNS ARE @NonNull
     * A Spring Data query method with a collection return type NEVER returns
     * null; when nothing matches it returns an empty list. That is a real
     * guarantee, and stating it lets these results be passed straight to
     * deleteAll() and the stream API, which require non-null.
     *
     * Left unstated, every caller got "needs unchecked conversion" for a value
     * that cannot be null - and a warning that is always wrong is a warning
     * people stop reading.
     */
    @NonNull
    List<Submission> findByStudentOrderByIdAsc(AppUser student);

    /** Every student's state for one assignment - the teacher's marking list. */
    @NonNull
    List<Submission> findByAssignmentOrderByIdAsc(Assignment assignment);

    Optional<Submission> findByAssignmentAndStudent(Assignment assignment, AppUser student);

    boolean existsByAssignmentAndStudent(Assignment assignment, AppUser student);

    boolean existsByAssignment(Assignment assignment);

    boolean existsByStudent(AppUser student);

    /** Used to refuse deleting an assignment somebody has already handed in. */
    boolean existsByAssignmentAndStatus(Assignment assignment, AssignmentStatus status);

    List<Submission> findByAssignmentAndStatus(Assignment assignment, AssignmentStatus status);

    /**
     * Every submission across all the courses one teacher runs.
     *
     * A teacher's marking queue. Scoped by the courses they actually teach, not
     * by role: a teacher seeing every submission in the school would be a
     * different and much larger claim than "a teacher sees their own classes",
     * and it is not the one this system makes.
     */
    @Query("""
            SELECT s FROM Submission s
            WHERE s.assignment.course.teacher = :teacher
            ORDER BY s.id ASC
            """)
    List<Submission> findForTeacher(AppUser teacher);
}
