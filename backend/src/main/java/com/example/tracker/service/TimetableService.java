package com.example.tracker.service;

import com.example.tracker.dto.TimetableSlotView;
import com.example.tracker.dto.TermView;
import com.example.tracker.dto.TimetablePeriodView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.Term;
import com.example.tracker.model.TimetablePeriod;
import com.example.tracker.model.TimetableSlot;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.AttendanceRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.TermRepository;
import com.example.tracker.repository.TimetablePeriodRepository;
import com.example.tracker.repository.TimetableSlotRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for terms, periods and the timetable.
 *
 * A CLASS OF ITS OWN, NOT MORE METHODS ON SchoolService
 * -----------------------------------------------------
 * SchoolService already answers "who is taught what by whom" - subjects,
 * classes, courses, enrolment. This answers "and WHEN", which is a different
 * question over different tables with a different owner, and SchoolService is
 * already 800 lines. The split follows the same reasoning AdminService's own
 * header gives for not being part of AppUserService.
 *
 * WHO MAY WRITE: ADMIN ONLY, the same rule the rest of the timetable already
 * follows (see SchoolService.createCourse and the 11 August change that moved
 * those writes off teachers). Scheduling decides who receives every future
 * lesson, so it belongs to the same role that decides who teaches what.
 *
 * WHO MAY READ: everyone signed in, but scoped. A teacher and a student each
 * get their own week rather than the whole school's - readMyTimetable below -
 * because a full timetable names every class and every colleague, which is
 * information neither role needs in order to find their own next lesson.
 *
 * THE CLASH RULES ARE ENFORCED TWICE, ON PURPOSE
 * uq_timetable_slot_class and uq_timetable_slot_teacher make a double-booking
 * impossible to store. The checks in this class run first so the caller gets
 * "Grade 10A is already taught Mathematics in Period 3 on Monday" instead of a
 * driver-level constraint violation that GlobalExceptionHandler could only
 * honestly report as a 500. The check is the message; the constraint is the
 * guarantee. Removing either leaves the system correct and worse.
 */
@Service
@Transactional(readOnly = true)
public class TimetableService {

    private final TermRepository terms;
    private final TimetablePeriodRepository periods;
    private final TimetableSlotRepository slots;
    private final CourseRepository courses;
    private final EnrolmentRepository enrolments;
    private final AttendanceRepository attendance;
    private final AppUserService users;
    private final AuditLogService audit;

    public TimetableService(TermRepository terms,
                            TimetablePeriodRepository periods,
                            TimetableSlotRepository slots,
                            CourseRepository courses,
                            EnrolmentRepository enrolments,
                            AttendanceRepository attendance,
                            AppUserService users,
                            AuditLogService audit) {
        this.terms = terms;
        this.periods = periods;
        this.slots = slots;
        this.courses = courses;
        this.enrolments = enrolments;
        this.attendance = attendance;
        this.users = users;
        this.audit = audit;
    }

    // ===== TERMS =============================================================

    public List<TermView> listTerms() {
        users.currentActiveUser();   // signed in is enough to read the calendar
        return terms.findAllByOrderByStartDateAsc().stream().map(TermView::of).toList();
    }

    @Transactional
    public TermView createTerm(String name, LocalDate startDate, LocalDate endDate) {
        AppUser me = requireAdmin("create a term");
        String cleanName = require(name, "Term name");
        requireDates(startDate, endDate);
        requireNoOverlap(startDate, endDate, null);

        if (terms.existsByNameIgnoreCase(cleanName)) {
            throw new IllegalStateException("A term named '" + cleanName + "' already exists.");
        }

        Term saved = terms.save(new Term(cleanName, startDate, endDate));
        audit.record("Term", saved.getId(), AuditAction.CREATE, me,
                "Created term '" + cleanName + "' (" + startDate + " to " + endDate + ").");
        return TermView.of(saved);
    }

    @Transactional
    public TermView updateTerm(Long id, String name, LocalDate startDate, LocalDate endDate) {
        AppUser me = requireAdmin("edit a term");
        Term term = requireTerm(id);
        String cleanName = require(name, "Term name");
        requireDates(startDate, endDate);
        requireNoOverlap(startDate, endDate, term.getId());

        terms.findByNameIgnoreCase(cleanName).ifPresent(other -> {
            if (!other.getId().equals(term.getId())) {
                throw new IllegalStateException("A term named '" + cleanName + "' already exists.");
            }
        });

        term.setName(cleanName);
        term.setDates(startDate, endDate);

        audit.record("Term", term.getId(), AuditAction.UPDATE, me,
                "Edited term '" + cleanName + "' (" + startDate + " to " + endDate + ").");
        return TermView.of(term);
    }

    @Transactional
    public void deleteTerm(Long id) {
        AppUser me = requireAdmin("delete a term");
        Term term = requireTerm(id);

        if (slots.existsByTerm(term)) {
            throw new IllegalStateException(
                    "'" + term.getName() + "' still has lessons timetabled and cannot be deleted. "
                            + "Remove them first if this is intended.");
        }

        String name = term.getName();
        terms.delete(term);
        audit.record("Term", id, AuditAction.DELETE, me, "Deleted term '" + name + "'.");
    }

    // ===== PERIODS ===========================================================

    public List<TimetablePeriodView> listPeriods() {
        users.currentActiveUser();
        return periods.findAllByOrderBySequenceNoAsc().stream()
                .map(TimetablePeriodView::of).toList();
    }

    @Transactional
    public TimetablePeriodView createPeriod(String name, Integer sequenceNo,
                                            LocalTime startTime, LocalTime endTime) {
        AppUser me = requireAdmin("create a period");
        String cleanName = require(name, "Period name");
        requireTimes(startTime, endTime);
        if (sequenceNo == null) {
            throw new IllegalArgumentException("A period needs a position in the day.");
        }

        if (periods.existsByNameIgnoreCase(cleanName)) {
            throw new IllegalStateException("A period named '" + cleanName + "' already exists.");
        }
        if (periods.existsBySequenceNo(sequenceNo)) {
            throw new IllegalStateException(
                    "Another period is already number " + sequenceNo + " in the day.");
        }

        TimetablePeriod saved = periods.save(
                new TimetablePeriod(cleanName, sequenceNo, startTime, endTime));
        audit.record("TimetablePeriod", saved.getId(), AuditAction.CREATE, me,
                "Created period '" + cleanName + "' (" + startTime + " to " + endTime + ").");
        return TimetablePeriodView.of(saved);
    }

    @Transactional
    public TimetablePeriodView updatePeriod(Long id, String name, Integer sequenceNo,
                                            LocalTime startTime, LocalTime endTime) {
        AppUser me = requireAdmin("edit a period");
        TimetablePeriod period = requirePeriod(id);
        String cleanName = require(name, "Period name");
        requireTimes(startTime, endTime);
        if (sequenceNo == null) {
            throw new IllegalArgumentException("A period needs a position in the day.");
        }

        periods.findByNameIgnoreCase(cleanName).ifPresent(other -> {
            if (!other.getId().equals(period.getId())) {
                throw new IllegalStateException(
                        "A period named '" + cleanName + "' already exists.");
            }
        });
        periods.findBySequenceNo(sequenceNo).ifPresent(other -> {
            if (!other.getId().equals(period.getId())) {
                throw new IllegalStateException(
                        "Another period is already number " + sequenceNo + " in the day.");
            }
        });

        period.setName(cleanName);
        period.setSequenceNo(sequenceNo);
        period.setTimes(startTime, endTime);

        audit.record("TimetablePeriod", period.getId(), AuditAction.UPDATE, me,
                "Edited period '" + cleanName + "'.");
        return TimetablePeriodView.of(period);
    }

    @Transactional
    public void deletePeriod(Long id) {
        AppUser me = requireAdmin("delete a period");
        TimetablePeriod period = requirePeriod(id);

        if (slots.existsByPeriod(period)) {
            throw new IllegalStateException(
                    "'" + period.getName() + "' still has lessons timetabled in it and cannot be "
                            + "deleted. Remove them first if this is intended.");
        }

        String name = period.getName();
        periods.delete(period);
        audit.record("TimetablePeriod", id, AuditAction.DELETE, me, "Deleted period '" + name + "'.");
    }

    // ===== THE TIMETABLE ITSELF ==============================================

    /** The whole school's week for one term. Staff only - see the class comment. */
    public List<TimetableSlotView> listSlots(Long termId) {
        requireTeacherOrAdmin("view the whole timetable");
        Term term = requireTerm(termId);
        return slots.findByTermOrdered(term).stream().map(TimetableSlotView::of).toList();
    }

    /**
     * The caller's OWN week.
     *
     * The one endpoint a student may call. A teacher gets the lessons they
     * teach; a student gets the lessons of the class they are enrolled in. An
     * admin teaches nothing and attends nothing, so they get the whole term -
     * which is the view they maintain anyway.
     */
    public List<TimetableSlotView> listMyTimetable(Long termId) {
        AppUser me = users.currentActiveUser();
        Term term = requireTerm(termId);

        if (me.getRole() == Role.ADMIN) {
            return slots.findByTermOrdered(term).stream().map(TimetableSlotView::of).toList();
        }
        if (me.getRole() == Role.TEACHER) {
            return slots.findByTermAndTeacherOrdered(term, me).stream()
                    .map(TimetableSlotView::of).toList();
        }

        /*
         * A student's timetable is their CLASS's timetable, found through
         * enrolment - they never point at a course or a slot directly, the
         * same way SchoolService derives their subjects and teachers. A
         * student enrolled in nothing gets an empty week rather than an error:
         * that is a true answer, and an admin who has not enrolled them yet is
         * an ordinary state, not a fault.
         */
        /*
         * An explicit loop rather than .map(Enrolment::getSchoolClass).distinct():
         * the method reference draws "Null type safety: parameter 'this' ...
         * needs unchecked conversion to conform to '@NonNull Enrolment'",
         * because the stream element carries no contract Eclipse's analysis can
         * see. A LinkedHashSet does the same de-duplication while preserving
         * enrolment order, and every value is a local the analysis can follow.
         */
        List<TimetableSlotView> week = new ArrayList<>();
        java.util.Set<Long> seenClasses = new java.util.LinkedHashSet<>();
        for (Enrolment enrolment : enrolments.findByStudentOrderByIdAsc(me)) {
            var schoolClass = enrolment.getSchoolClass();
            if (schoolClass == null || !seenClasses.add(schoolClass.getId())) {
                continue;   // a student enrolled twice in one class is still one week
            }
            for (TimetableSlot slot : slots.findByTermAndClassOrdered(term, schoolClass)) {
                week.add(TimetableSlotView.of(slot));
            }
        }
        return week;
    }

    @Transactional
    public TimetableSlotView schedule(Long termId, Long periodId, Long courseId, String day) {
        AppUser me = requireAdmin("timetable a lesson");
        Term term = requireTerm(termId);
        TimetablePeriod period = requirePeriod(periodId);
        Course course = requireCourse(courseId);
        DayOfWeek dayOfWeek = parseDay(day);

        /*
         * Both checks mirror a UNIQUE constraint exactly - see the class
         * comment on why they exist anyway. Reading the CLASS clash first is
         * deliberate: when a course is being added to a class that is already
         * busy, that is the more useful of the two messages, and a teacher
         * clash discovered afterwards is rarer.
         */
        slots.findByTermAndDayOfWeekAndPeriodAndSchoolClass(
                term, dayOfWeek, period, course.getSchoolClass()).ifPresent(clash -> {
            throw new IllegalStateException(
                    course.getSchoolClass().getName() + " is already taught "
                            + clash.getCourse().getSubject().getName() + " in "
                            + period.getName() + " on " + label(dayOfWeek) + ".");
        });

        slots.findByTermAndDayOfWeekAndPeriodAndTeacher(
                term, dayOfWeek, period, course.getTeacher()).ifPresent(clash -> {
            throw new IllegalStateException(
                    course.getTeacher().getUsername() + " already teaches "
                            + clash.getSchoolClass().getName() + " in "
                            + period.getName() + " on " + label(dayOfWeek) + ".");
        });

        TimetableSlot saved = slots.save(new TimetableSlot(term, period, course, dayOfWeek));
        audit.record("TimetableSlot", saved.getId(), AuditAction.CREATE, me,
                "Timetabled " + course.getSubject().getName() + " for "
                        + course.getSchoolClass().getName() + " in " + period.getName()
                        + " on " + label(dayOfWeek) + " (" + term.getName() + ").");
        return TimetableSlotView.of(saved);
    }

    @Transactional
    public void unschedule(Long id) {
        AppUser me = requireAdmin("remove a lesson from the timetable");
        if (id == null) {
            throw new IllegalArgumentException("Timetable slot id must not be null.");
        }
        TimetableSlot slot = Require.orThrow(slots.findById(id),
                () -> new ResourceNotFoundException("No timetable slot found with id = " + id));

        /*
         * A LESSON WITH A REGISTER AGAINST IT STAYS.
         *
         * Attendance is a record of something that happened - who was actually
         * in the room - which puts it on the same side of this system's oldest
         * distinction as a mark or a submission, and it must not disappear as a
         * side effect of tidying a timetable. fk_attendance_slot already
         * refused this; without the check it surfaced as a bare 400 that
         * mentioned neither the register nor what to do about it.
         *
         * There is deliberately no force-delete. Removing a register has to be
         * a separate, deliberate act, the same way reopening submissions is
         * before an assignment can go.
         */
        if (attendance.existsBySlot(slot)) {
            throw new IllegalStateException(
                    "Attendance has been recorded for this lesson, so it cannot be removed "
                            + "from the timetable. A register is a record of who was actually "
                            + "there.");
        }

        String summary = slot.getCourse().getSubject().getName() + " for "
                + slot.getSchoolClass().getName() + " in " + slot.getPeriod().getName()
                + " on " + label(slot.getDayOfWeek());

        slots.delete(slot);
        audit.record("TimetableSlot", id, AuditAction.DELETE, me, "Removed " + summary + ".");
    }

    // ===== guards and helpers ================================================

    @NonNull
    private AppUser requireAdmin(String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin can " + action + ".");
        }
        return me;
    }

    @NonNull
    private AppUser requireTeacherOrAdmin(String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER && me.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only a teacher or an admin can " + action + ".");
        }
        return me;
    }

    @NonNull
    private Term requireTerm(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Term id must not be null.");
        }
        return Require.orThrow(terms.findById(id),
                () -> new ResourceNotFoundException("No term found with id = " + id));
    }

    @NonNull
    private TimetablePeriod requirePeriod(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Period id must not be null.");
        }
        return Require.orThrow(periods.findById(id),
                () -> new ResourceNotFoundException("No period found with id = " + id));
    }

    @NonNull
    private Course requireCourse(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Course id must not be null.");
        }
        return Require.orThrow(courses.findById(id),
                () -> new ResourceNotFoundException("No course found with id = " + id));
    }

    /**
     * Refuse a term that shares a day with one already on record.
     *
     * THE ONE RULE HERE THE DATABASE CANNOT KEEP. SQL Server has no exclusion
     * constraint and a CHECK cannot see other rows, so unlike every clash rule
     * on timetable_slot this really is enforced in Java alone. It runs inside
     * the caller's transaction, which closes the ordinary read-then-write gap
     * for anything going through this service - but two admins writing through
     * different paths at the same instant could still slip past it, and that
     * is worth knowing rather than assuming otherwise.
     */
    private void requireNoOverlap(LocalDate startDate, LocalDate endDate, Long excludeId) {
        for (Term existing : terms.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(
                endDate, startDate)) {
            if (excludeId != null && excludeId.equals(existing.getId())) {
                continue;   // editing a term must not collide with itself
            }
            throw new IllegalStateException(
                    "These dates overlap '" + existing.getName() + "' ("
                            + existing.getStartDate() + " to " + existing.getEndDate() + ").");
        }
    }

    private void requireDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("A term needs both a start and an end date.");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "A term cannot end (" + endDate + ") before it starts (" + startDate + ").");
        }
    }

    private void requireTimes(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("A period needs both a start and an end time.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                    "A period must end after it starts - got " + startTime + " to " + endTime + ".");
        }
    }

    /**
     * A day name from the client, turned into a DayOfWeek or refused as 400.
     *
     * valueOf() alone throws IllegalArgumentException with the message
     * "No enum constant java.time.DayOfWeek.mondayy", which names an internal
     * type at the client. Catching it to say something a person can act on is
     * the same courtesy every other validation message in this codebase pays.
     */
    private DayOfWeek parseDay(String day) {
        if (day == null || day.isBlank()) {
            throw new IllegalArgumentException("A day of the week is required.");
        }
        try {
            return DayOfWeek.valueOf(day.trim().toUpperCase());
        } catch (IllegalArgumentException notADay) {
            throw new IllegalArgumentException(
                    "'" + day + "' is not a day of the week - use MONDAY through SUNDAY.");
        }
    }

    /** "Monday", not "MONDAY" - these strings end up in messages people read. */
    private String label(DayOfWeek day) {
        String name = day.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }

    private String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank.");
        }
        return value.trim();
    }
}
