package com.example.tracker.dto;

import com.example.tracker.model.TimetableSlot;

import java.time.LocalTime;

/**
 * One lesson, flattened into everything a timetable grid needs to draw it.
 *
 * WHY THIS IS FLAT RATHER THAN NESTED
 * -----------------------------------
 * A timetable is drawn as a grid of days against periods, and every cell needs
 * the subject, the class, the teacher and the clock time at once. Publishing
 * nested TermView/CourseView/TimetablePeriodView objects would make the client
 * walk four levels for each of perhaps two hundred cells, and would ship the
 * same term and period objects over and over.
 *
 * The ids are published ALONGSIDE the labels, not instead of them, because the
 * grid needs the ids to group by and the labels to display - and deriving one
 * from the other on the client would mean the client holding a second copy of
 * the period list just to render a name.
 */
public record TimetableSlotView(
        Long id,
        Long termId,
        String termName,
        String dayOfWeek,
        Long periodId,
        String periodName,
        Integer periodSequenceNo,
        LocalTime startTime,
        LocalTime endTime,
        Long courseId,
        String subjectCode,
        String subjectName,
        Long classId,
        String className,
        String teacherUsername) {

    public static TimetableSlotView of(TimetableSlot slot) {
        return new TimetableSlotView(
                slot.getId(),
                slot.getTerm().getId(),
                slot.getTerm().getName(),
                slot.getDayOfWeek().name(),
                slot.getPeriod().getId(),
                slot.getPeriod().getName(),
                slot.getPeriod().getSequenceNo(),
                slot.getPeriod().getStartTime(),
                slot.getPeriod().getEndTime(),
                slot.getCourse().getId(),
                slot.getCourse().getSubject().getCode(),
                slot.getCourse().getSubject().getName(),
                slot.getSchoolClass().getId(),
                slot.getSchoolClass().getName(),
                slot.getTeacher().getUsername());
    }
}
