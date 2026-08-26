package com.example.tracker.service;

import com.example.tracker.dto.AttendanceSummaryView;
import com.example.tracker.dto.AttendanceView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Attendance;
import com.example.tracker.model.AttendanceStatus;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.PersonProfile;
import com.example.tracker.model.Role;
import com.example.tracker.model.TimetableSlot;
import com.example.tracker.repository.AttendanceRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.PersonProfileRepository;
import com.example.tracker.repository.TimetableSlotRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for the attendance register.
 *
 * THE THREE RULES THE DATABASE CANNOT KEEP
 * ----------------------------------------
 * Most of this feature's integrity is in the schema - one mark per student per
 * lesson per date, only a STUDENT may be marked, only a TEACHER or an ADMIN
 * may mark. Three rules are left over, and each needs to read a second table,
 * which is precisely what a CHECK constraint cannot do:
 *
 *   the date's WEEKDAY must match the lesson's day (no marking a Monday
 *   lesson on a Thursday)
 *   the date must fall INSIDE the lesson's term
 *   the student must be ENROLLED in the class that lesson is taught to
 *
 * They are enforced here, inside the same transaction as the write. This is
 * called out rather than left implicit because every other rule in this
 * feature IS at the database, and a reader is entitled to assume that until
 * told otherwise.
 *
 * WHO MAY TAKE A REGISTER
 * The teacher who teaches that course, or an admin. Not any teacher: a
 * register names every student in a class, and a teacher who does not teach
 * them has no reason to see it, let alone write it. Checked against the slot's
 * own teacher rather than against a role alone.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceService {

    private final AttendanceRepository attendance;
    private final TimetableSlotRepository slots;
    private final EnrolmentRepository enrolments;
    private final PersonProfileRepository profiles;
    private final AppUserService users;
    private final AuditLogService audit;

    public AttendanceService(AttendanceRepository attendance,
                             TimetableSlotRepository slots,
                             EnrolmentRepository enrolments,
                             PersonProfileRepository profiles,
                             AppUserService users,
                             AuditLogService audit) {
        this.attendance = attendance;
        this.slots = slots;
        this.enrolments = enrolments;
        this.profiles = profiles;
        this.users = users;
        this.audit = audit;
    }

    // ===== reading ===========================================================

    /**
     * The register for one lesson on one date.
     *
     * Returns a row for EVERY enrolled student, not only those already marked -
     * an unmarked student comes back with a null status. A register that only
     * listed the marked ones would make "nobody has taken this yet" and
     * "everybody was present" look identical on screen, and taking a register
     * is precisely the act of turning the first into the second.
     */
    public List<AttendanceView> register(Long slotId, LocalDate date) {
        TimetableSlot slot = requireSlot(slotId);
        requireMayTake(slot, "view this register");
        requireDateFitsLesson(slot, date);

        Map<Long, Attendance> marked = new HashMap<>();
        for (Attendance a : attendance.findBySlotAndLessonDateOrderByIdAsc(slot, date)) {
            marked.put(a.getStudent().getId(), a);
        }

        List<AttendanceView> rows = new ArrayList<>();
        for (Enrolment enrolment : enrolments.findBySchoolClassOrderByIdAsc(slot.getSchoolClass())) {
            AppUser student = enrolment.getStudent();
            String name = nameOf(student);
            Attendance mark = marked.get(student.getId());
            if (mark != null) {
                rows.add(AttendanceView.withName(mark, name));
            } else {
                // Not yet marked - same shape, null status. See the method comment.
                rows.add(new AttendanceView(
                        null, slot.getId(), date, slot.getDayOfWeek().name(),
                        slot.getPeriod().getName(),
                        slot.getCourse().getSubject().getName(),
                        slot.getSchoolClass().getName(),
                        student.getId(), student.getUsername(), name,
                        null, null, null));
            }
        }
        return rows;
    }

    /** One student's own record. A student may only ever ask about themselves. */
    public List<AttendanceView> studentRecord(Long studentId) {
        AppUser student = requireVisibleStudent(studentId);
        return attendance.findByStudentOrderByLessonDateDescIdAsc(student).stream()
                .map(a -> AttendanceView.withName(a, nameOf(student)))
                .toList();
    }

    /** The counts and the rate, computed on read - see AttendanceSummaryView. */
    public AttendanceSummaryView summarise(Long studentId) {
        AppUser student = requireVisibleStudent(studentId);
        long present = 0, absent = 0, late = 0, excused = 0;
        for (Object[] row : attendance.countByStatusForStudent(student)) {
            AttendanceStatus status = (AttendanceStatus) row[0];
            long count = ((Number) row[1]).longValue();
            switch (status) {
                case PRESENT -> present = count;
                case ABSENT -> absent = count;
                case LATE -> late = count;
                case EXCUSED -> excused = count;
            }
        }
        return AttendanceSummaryView.of(present, absent, late, excused);
    }

    // ===== writing ===========================================================

    /**
     * Take (or correct) the register for one lesson, in one transaction.
     *
     * ONE CALL FOR THE WHOLE CLASS, not one per student. Taking a register is a
     * single act: thirty separate requests would leave a half-marked lesson
     * behind the first time one of them failed, and there would be no moment at
     * which the register was either taken or not taken. Same reasoning as
     * AssignmentService's fan-out.
     *
     * A student already marked is AMENDED rather than duplicated -
     * uq_attendance_slot_student_date would refuse a second row anyway, and
     * this is what makes re-taking a register mean "correct it".
     */
    @Transactional
    public List<AttendanceView> takeRegister(Long slotId, LocalDate date, List<Mark> marks) {
        AppUser me = users.currentActiveUser();
        TimetableSlot slot = requireSlot(slotId);
        requireMayTake(slot, "take this register");
        requireDateFitsLesson(slot, date);

        if (marks == null || marks.isEmpty()) {
            throw new IllegalArgumentException("A register needs at least one student.");
        }

        // Everyone enrolled in the class this lesson is taught to. A mark for
        // anyone else is refused rather than silently ignored: quietly dropping
        // it would leave the caller believing it was recorded.
        Map<Long, AppUser> enrolled = new HashMap<>();
        for (Enrolment e : enrolments.findBySchoolClassOrderByIdAsc(slot.getSchoolClass())) {
            enrolled.put(e.getStudent().getId(), e.getStudent());
        }

        List<AttendanceView> saved = new ArrayList<>();
        for (Mark mark : marks) {
            if (mark == null || mark.studentId() == null) {
                throw new IllegalArgumentException("Every mark needs a student.");
            }
            AppUser student = enrolled.get(mark.studentId());
            if (student == null) {
                throw new IllegalArgumentException(
                        "Student " + mark.studentId() + " is not enrolled in "
                                + slot.getSchoolClass().getName() + ".");
            }
            AttendanceStatus status = parseStatus(mark.status());

            Attendance existing = attendance
                    .findBySlotAndStudentAndLessonDate(slot, student, date).orElse(null);
            if (existing == null) {
                saved.add(AttendanceView.withName(
                        attendance.save(new Attendance(
                                slot, student, date, status, mark.note(), me)),
                        nameOf(student)));
            } else {
                existing.amend(status, mark.note(), me);
                saved.add(AttendanceView.withName(existing, nameOf(student)));
            }
        }

        audit.record("Attendance", slot.getId(), AuditAction.UPDATE, me,
                "Took the register for " + slot.getCourse().getSubject().getName()
                        + " (" + slot.getSchoolClass().getName() + ") on " + date
                        + " - " + saved.size() + " students.");
        return saved;
    }

    /** One student's mark for one lesson, as submitted by the register screen. */
    public record Mark(Long studentId, String status, String note) { }

    // ===== guards and helpers ================================================

    @NonNull
    private TimetableSlot requireSlot(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Lesson id must not be null.");
        }
        return Require.orThrow(slots.findById(id),
                () -> new ResourceNotFoundException("No lesson found with id = " + id));
    }

    /**
     * The teacher who teaches this lesson, or an admin. See the class comment
     * on why a role check alone is not enough.
     */
    private void requireMayTake(TimetableSlot slot, String action) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() == Role.ADMIN) {
            return;
        }
        if (me.getRole() == Role.TEACHER && slot.getTeacher().getId().equals(me.getId())) {
            return;
        }
        throw new AccessDeniedException(
                "Only the teacher of this lesson, or an admin, can " + action + ".");
    }

    /**
     * A student may read only their own record; staff may read any.
     *
     * The id is still REQUIRED from a student rather than inferred, so one
     * endpoint serves both - but a student asking about anyone else is refused
     * rather than quietly redirected to their own, which would make the API
     * lie about what it returned.
     */
    @NonNull
    private AppUser requireVisibleStudent(Long studentId) {
        AppUser me = users.currentActiveUser();
        if (studentId == null) {
            throw new IllegalArgumentException("Student id must not be null.");
        }
        if (me.getRole() == Role.STUDENT && !me.getId().equals(studentId)) {
            throw new AccessDeniedException("You can only see your own attendance.");
        }
        AppUser student = Require.orThrow(users.findById(studentId),
                () -> new ResourceNotFoundException("No account found with id = " + studentId));
        if (student.getRole() != Role.STUDENT) {
            throw new IllegalArgumentException(
                    "'" + student.getUsername() + "' is not a student.");
        }
        return student;
    }

    /**
     * The date must be a real occurrence of this lesson.
     *
     * TWO CHECKS THE DATABASE CANNOT MAKE, both needing another table - see the
     * class comment. Without the first, a Monday lesson could be marked on a
     * Thursday and the register would look perfectly valid; without the second,
     * a lesson could be marked in a term it was never timetabled for.
     */
    private void requireDateFitsLesson(TimetableSlot slot, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("A date is required.");
        }
        if (date.getDayOfWeek() != slot.getDayOfWeek()) {
            throw new IllegalArgumentException(
                    date + " is a " + label(date.getDayOfWeek().name())
                            + ", but this lesson is taught on "
                            + label(slot.getDayOfWeek().name()) + ".");
        }
        if (!slot.getTerm().covers(date)) {
            throw new IllegalArgumentException(
                    date + " is outside " + slot.getTerm().getName()
                            + " (" + slot.getTerm().getStartDate()
                            + " to " + slot.getTerm().getEndDate() + ").");
        }
    }

    private AttendanceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("An attendance status is required.");
        }
        try {
            return AttendanceStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException notAStatus) {
            throw new IllegalArgumentException(
                    "'" + status + "' is not an attendance status - use PRESENT, "
                            + "ABSENT, LATE or EXCUSED.");
        }
    }

    /**
     * The student's real name, or their username when they have no profile.
     *
     * WRITTEN AS AN EXPLICIT LOCAL AND AN if, NOT .map(PersonProfile::getFullName).
     * The method-reference form draws "Null type safety: parameter 'this' ...
     * needs unchecked conversion to conform to '@NonNull PersonProfile'" from
     * Eclipse's null analysis: Optional is an unannotated JDK generic, so the
     * receiver the method reference would be invoked on carries no contract
     * the analysis can see. Assigning to a local and testing it is ordinary
     * flow-sensitive analysis, which Eclipse verifies natively with no
     * annotation and no suppression - the same technique, and the same
     * reasoning, as Require.orThrow. See CLAUDE.md's Nullability section.
     */
    private String nameOf(AppUser student) {
        PersonProfile profile = profiles.findByAppUser(student).orElse(null);
        return profile == null ? student.getUsername() : profile.getFullName();
    }

    private String label(String name) {
        return name.charAt(0) + name.substring(1).toLowerCase();
    }
}
