package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Course;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for "who teaches what, to whom".
 *
 * The two queries at the bottom are the ones that answer the requirements about
 * a student having several teachers and several subjects. Note that neither
 * takes a student directly - a student reaches their courses through the class
 * they are enrolled in, which is what keeps the register and the timetable from
 * drifting apart.
 */
@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findAllByOrderByIdAsc();

    /** Every course this teacher runs - across as many subjects and classes as they like. */
    List<Course> findByTeacherOrderByIdAsc(AppUser teacher);

    List<Course> findBySchoolClassOrderByIdAsc(SchoolClass schoolClass);

    Optional<Course> findBySubjectAndSchoolClassAndTeacher(
            Subject subject, SchoolClass schoolClass, AppUser teacher);

    boolean existsBySubject(Subject subject);

    boolean existsBySchoolClass(SchoolClass schoolClass);

    boolean existsByTeacher(AppUser teacher);

    /**
     * Every course a student is taught, found through their enrolments.
     *
     * Written out as JPQL because it crosses from Course to Enrolment on the
     * shared class, which no derived method name can express. This one query is
     * the answer to both "which subjects is this student taught" and "which
     * teachers teach them" - they are the same set, read from different columns.
     */
    @Query("""
            SELECT c FROM Course c
            WHERE c.schoolClass IN (
                SELECT e.schoolClass FROM Enrolment e WHERE e.student = :student)
            ORDER BY c.id ASC
            """)
    List<Course> findCoursesForStudent(AppUser student);
}
