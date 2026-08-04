package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * REPOSITORY (DATA ACCESS) LAYER
 * ------------------------------
 * This is the ONLY layer that talks to the database.
 *
 * Notice there is almost no code: by extending JpaRepository, Spring Data
 * generates a full implementation at runtime with findAll, findById, save,
 * count and deleteById.
 *
 * WHAT CHANGED WHEN ASSIGNMENT SPLIT IN TWO
 * -----------------------------------------
 * The old findByOwnerOrderByIdAsc is gone, and its absence is the point. An
 * assignment no longer HAS an owner - it has a course, and the students who
 * receive it are whoever is enrolled in that course's class. "Which assignments
 * are mine?" is now a question about submissions, and is answered by
 * SubmissionRepository.
 */
@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /** Everything, oldest first. */
    List<Assignment> findAllByOrderByIdAsc();

    List<Assignment> findByCourseOrderByIdAsc(Course course);

    /** Everything set for any of a list of courses - a teacher's whole workload. */
    List<Assignment> findByCourseInOrderByIdAsc(List<Course> courses);

    boolean existsByCourse(Course course);

    boolean existsByCreatedBy(AppUser createdBy);

    /**
     * The assignments visible to one student, through their class enrolments.
     *
     * A student sees an assignment because they are in the class it was set for,
     * not because a row names them. Written as JPQL because the path crosses
     * Assignment to Course to Enrolment, which no derived method name can say.
     */
    @Query("""
            SELECT a FROM Assignment a
            WHERE a.course.schoolClass IN (
                SELECT e.schoolClass FROM Enrolment e WHERE e.student = :student)
            ORDER BY a.id ASC
            """)
    List<Assignment> findForStudent(AppUser student);
}
