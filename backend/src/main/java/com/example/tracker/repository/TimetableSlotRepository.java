package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Course;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Term;
import com.example.tracker.model.TimetablePeriod;
import com.example.tracker.model.TimetableSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

/**
 * DATA ACCESS for the timetable.
 *
 * The two `findClash...` methods below mirror the two UNIQUE constraints on
 * the table exactly. They exist to turn an inevitable database refusal into a
 * sentence naming what is already in that slot - the same "the check is the
 * message, the constraint is the guarantee" split this codebase already
 * applies to every dependency-blocked delete. Deleting either method would
 * leave the system correct and its error messages useless.
 */
public interface TimetableSlotRepository extends JpaRepository<TimetableSlot, Long> {

    /** Everything scheduled in a term, in the order a timetable is read. */
    @Query("""
            SELECT s FROM TimetableSlot s
            ORDER BY s.dayOfWeek ASC, s.period.sequenceNo ASC
            """)
    List<TimetableSlot> findAllOrdered();

    @Query("""
            SELECT s FROM TimetableSlot s
            WHERE s.term = :term
            ORDER BY s.dayOfWeek ASC, s.period.sequenceNo ASC
            """)
    List<TimetableSlot> findByTermOrdered(Term term);

    /** One class's week - the student view. */
    @Query("""
            SELECT s FROM TimetableSlot s
            WHERE s.term = :term AND s.schoolClass = :schoolClass
            ORDER BY s.dayOfWeek ASC, s.period.sequenceNo ASC
            """)
    List<TimetableSlot> findByTermAndClassOrdered(Term term, SchoolClass schoolClass);

    /** One teacher's week - the teacher view. */
    @Query("""
            SELECT s FROM TimetableSlot s
            WHERE s.term = :term AND s.teacher = :teacher
            ORDER BY s.dayOfWeek ASC, s.period.sequenceNo ASC
            """)
    List<TimetableSlot> findByTermAndTeacherOrdered(Term term, AppUser teacher);

    /** Is this class already busy then? Mirrors uq_timetable_slot_class. */
    Optional<TimetableSlot> findByTermAndDayOfWeekAndPeriodAndSchoolClass(
            Term term, DayOfWeek dayOfWeek, TimetablePeriod period, SchoolClass schoolClass);

    /** Is this teacher already busy then? Mirrors uq_timetable_slot_teacher. */
    Optional<TimetableSlot> findByTermAndDayOfWeekAndPeriodAndTeacher(
            Term term, DayOfWeek dayOfWeek, TimetablePeriod period, AppUser teacher);

    /* Dependency checks, so a blocked delete can explain itself as a 409
       rather than surfacing the database's refusal as a 500. */
    boolean existsByTerm(Term term);

    boolean existsByPeriod(TimetablePeriod period);

    boolean existsByCourse(Course course);
}
