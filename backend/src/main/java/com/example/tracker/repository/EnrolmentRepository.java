package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for class membership.
 *
 * findBySchoolClassOrderByIdAsc is the query the whole fan-out depends on: when
 * a teacher sets work for a course, this is what decides which students receive
 * it. Getting it wrong means either a student silently missing an assignment, or
 * one appearing for somebody who left the class.
 */
@Repository
public interface EnrolmentRepository extends JpaRepository<Enrolment, Long> {

    List<Enrolment> findBySchoolClassOrderByIdAsc(SchoolClass schoolClass);

    List<Enrolment> findByStudentOrderByIdAsc(AppUser student);

    Optional<Enrolment> findByStudentAndSchoolClass(AppUser student, SchoolClass schoolClass);

    boolean existsByStudentAndSchoolClass(AppUser student, SchoolClass schoolClass);

    /** Used before deleting a class, so the refusal carries a useful message. */
    boolean existsBySchoolClass(SchoolClass schoolClass);

    boolean existsByStudent(AppUser student);
}
