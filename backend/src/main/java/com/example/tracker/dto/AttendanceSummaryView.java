package com.example.tracker.dto;

import java.math.BigDecimal;

/**
 * How much of the timetable a student has actually attended.
 *
 * EVERY NUMBER HERE IS COMPUTED ON READ. There is no stored attendance rate
 * anywhere in this system, for exactly the reason there is no stored average
 * mark: a rate is wrong the moment one mark is corrected, and keeping it
 * honest would need a job somebody eventually forgets to run.
 *
 * LATE COUNTS AS ATTENDED, EXCUSED DOES NOT. That is a policy choice and it is
 * stated here rather than buried: a late student was in the lesson, so
 * counting them absent would misrepresent the room. An excused absence is
 * still an absence - it is accounted for, not attended - and reporting it as
 * attendance would make the figure useless for the pastoral question it exists
 * to answer. AttendanceStatus.isAttended is the single place that decides.
 */
public record AttendanceSummaryView(
        long present,
        long absent,
        long late,
        long excused,
        long total,
        /** Attended (present + late) over total, as a percentage, or null. */
        BigDecimal attendanceRate) {

    /**
     * Null rather than zero when nothing has been recorded.
     *
     * A student with no lessons on record has not attended 0% - the question
     * has no answer yet, and 0% is the answer to a different and much more
     * alarming question. The same distinction Gender.fromOmang draws by
     * returning null rather than guessing.
     */
    public static AttendanceSummaryView of(long present, long absent, long late, long excused) {
        long total = present + absent + late + excused;
        BigDecimal rate = total == 0 ? null
                : BigDecimal.valueOf(present + late)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
        return new AttendanceSummaryView(present, absent, late, excused, total, rate);
    }
}
