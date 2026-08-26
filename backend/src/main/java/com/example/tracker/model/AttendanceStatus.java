package com.example.tracker.model;

/**
 * How a student was accounted for in one lesson.
 *
 * FOUR VALUES, NOT A BOOLEAN. "Present or not" loses the two distinctions a
 * school actually acts on: LATE is a different conversation from ABSENT, and
 * EXCUSED is an absence somebody has already accounted for. Collapsing them
 * would mean the register could not tell an attendance problem from a bus
 * timetable.
 *
 * Stored with EnumType.STRING and mirrored by ck_attendance_status - never
 * ordinal, for the reason AssignmentStatus states: an ordinal stores a
 * position, and reordering these constants would silently reinterpret every
 * row already written.
 */
public enum AttendanceStatus {

    PRESENT,
    ABSENT,
    LATE,
    EXCUSED;

    /** True when this counts as the student having attended the lesson. */
    public boolean isAttended() {
        return this == PRESENT || this == LATE;
    }
}
