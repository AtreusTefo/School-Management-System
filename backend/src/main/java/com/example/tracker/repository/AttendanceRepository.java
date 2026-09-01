package com.example.tracker.repository;

import com.example.tracker.model.Attendance;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.AttendanceStatus;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.TimetableSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * DATA ACCESS for the attendance register. No rules here - who may take a
 * register, and whether a date belongs to a lesson, are AttendanceService's.
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /** The register for one lesson on one date - the screen itself. */
    List<Attendance> findBySlotAndLessonDateOrderByIdAsc(TimetableSlot slot, LocalDate lessonDate);

    /** One student's mark for one lesson, for the correct-an-existing-mark path. */
    Optional<Attendance> findBySlotAndStudentAndLessonDate(
            TimetableSlot slot, AppUser student, LocalDate lessonDate);

    /** One student's whole record, newest lesson first. */
    List<Attendance> findByStudentOrderByLessonDateDescIdAsc(AppUser student);

    /** One student's record inside a date range - the report a parent asks for. */
    List<Attendance> findByStudentAndLessonDateBetweenOrderByLessonDateDescIdAsc(
            AppUser student, LocalDate from, LocalDate to);

    /*
     * There is no findBySchoolClassAndLessonDate here any more.
     *
     * It was written alongside the others and never called by anything - dead
     * code that read like a live capability, which is how a column ends up
     * indexed for a query nobody makes. If a "whole class, one day" view is
     * ever built, add it back then, with the index it needs.
     */

    /**
     * How many of each status a student has, so a percentage can be computed
     * WITHOUT loading every row. Returns [status, count] pairs.
     *
     * The percentage itself is deliberately not calculated here and not stored
     * anywhere - see AttendanceService.summarise, and the same rule the marks
     * code follows: nothing derived is stored, because a stored rate is wrong
     * the moment one mark is corrected.
     */
    @Query("""
            SELECT a.status, COUNT(a) FROM Attendance a
            WHERE a.student = :student
            GROUP BY a.status
            """)
    List<Object[]> countByStatusForStudent(AppUser student);

    boolean existsByStudent(AppUser student);

    boolean existsBySlot(TimetableSlot slot);

    long countBySlotAndLessonDateAndStatus(
            TimetableSlot slot, LocalDate lessonDate, AttendanceStatus status);
}
