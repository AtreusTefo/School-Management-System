package com.example.tracker.dto;

import com.example.tracker.model.Attendance;

import java.time.LocalDate;

/**
 * One attendance mark, as the API publishes it. See SubjectView for why every
 * endpoint returns a record rather than the entity.
 *
 * Flat, and carrying the lesson's labels alongside its ids, for the same
 * reason TimetableSlotView is: a register screen draws thirty of these at once
 * and should not have to walk four levels of object per row.
 */
public record AttendanceView(
        Long id,
        Long slotId,
        LocalDate lessonDate,
        String dayOfWeek,
        String periodName,
        String subjectName,
        String className,
        Long studentId,
        String studentUsername,
        String studentName,
        String status,
        String note,
        String recordedBy) {

    public static AttendanceView of(Attendance a) {
        return new AttendanceView(
                a.getId(),
                a.getSlot().getId(),
                a.getLessonDate(),
                a.getSlot().getDayOfWeek().name(),
                a.getSlot().getPeriod().getName(),
                a.getSlot().getCourse().getSubject().getName(),
                a.getSchoolClass().getName(),
                a.getStudent().getId(),
                a.getStudent().getUsername(),
                null,
                a.getStatus().name(),
                a.getNote(),
                a.getRecordedBy().getUsername());
    }

    /**
     * The same mark with the student's real name attached.
     *
     * A SEPARATE FACTORY rather than a lookup inside the one above, because
     * the name lives on PersonProfile - a different table, which the register
     * screen wants and the student's own history does not (they know their own
     * name). Making the caller pass it keeps this record from quietly issuing a
     * query per row.
     */
    public static AttendanceView withName(Attendance a, String studentName) {
        AttendanceView base = of(a);
        return new AttendanceView(
                base.id(), base.slotId(), base.lessonDate(), base.dayOfWeek(),
                base.periodName(), base.subjectName(), base.className(),
                base.studentId(), base.studentUsername(), studentName,
                base.status(), base.note(), base.recordedBy());
    }
}
