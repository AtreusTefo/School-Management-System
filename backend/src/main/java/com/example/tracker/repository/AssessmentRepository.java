package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assessment;
import com.example.tracker.model.Course;
import com.example.tracker.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for marks.
 *
 * The two scoping queries are the whole point of this interface. A student's
 * report is their own marks; a teacher's mark book is the marks for the courses
 * they take. Both are restricted in the QUERY rather than filtered afterwards -
 * loading every child's results and then declining to draw most of them would
 * send the whole school's marks to one browser, which is not access control.
 */
@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    /** One student's own marks - what a student sees, and nothing else. */
    List<Assessment> findByStudentOrderByIdAsc(AppUser student);

    List<Assessment> findByCourseOrderByIdAsc(Course course);

    List<Assessment> findByStudentAndCourseOrderByIdAsc(AppUser student, Course course);

    Optional<Assessment> findByStudentAndCourseAndNameIgnoreCase(
            AppUser student, Course course, String name);

    boolean existsByStudent(AppUser student);

    boolean existsByCourse(Course course);

    boolean existsBySubmission(Submission submission);

    Optional<Assessment> findBySubmission(Submission submission);

    /**
     * Every mark across the courses one teacher takes.
     *
     * Scoped by the timetable, not by role: a teacher seeing every mark in the
     * school would be a much larger claim than "a teacher sees their own
     * classes", and it is not the one this system makes.
     */
    @Query("""
            SELECT a FROM Assessment a
            WHERE a.course.teacher = :teacher
            ORDER BY a.student.username ASC, a.course.id ASC, a.id ASC
            """)
    List<Assessment> findForTeacher(AppUser teacher);

    /**
     * Every mark for every student in one class, for one course.
     *
     * The mark book a teacher fills in: one row per student, whether or not they
     * have been marked yet - the "not yet" case is answered by the service,
     * which knows the register.
     */
    @Query("""
            SELECT a FROM Assessment a
            WHERE a.course = :course
            ORDER BY a.student.username ASC, a.id ASC
            """)
    List<Assessment> findMarkBook(Course course);
}
