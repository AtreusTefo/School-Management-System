package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.lang.NonNull;

import java.time.LocalTime;

/**
 * One slot in the school day - "Period 3", 10:15 to 11:00.
 *
 * A SHAPE OF THE DAY, NOT A LESSON. This says the day has a Period 3 running
 * from 10:15; it says nothing about who is taught what in it. That is
 * TimetableSlot's job, and keeping the two apart is what lets one period
 * definition be reused by every class in the school instead of being repeated
 * on hundreds of rows that could disagree about when Period 3 starts.
 *
 * WHY sequenceNo IS SEPARATE FROM startTime
 * Sorting by clock time looks like it would give the right order and mostly
 * does - until two periods share a start (a split year group, a break that
 * runs alongside a lesson), at which point the order becomes whatever the
 * database happened to return. An explicit sequence is the school saying what
 * order its own day runs in, rather than the application inferring it.
 *
 * The column is `sequence_no`, not `sequence`: SEQUENCE is a reserved word in
 * SQL Server and awkward in H2, and a column that has to be quoted in every
 * ad-hoc query is a trap for whoever writes the next one.
 */
@Entity
@Table(
        name = "timetable_period",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_timetable_period_name", columnNames = "name"),
                @UniqueConstraint(name = "uq_timetable_period_sequence", columnNames = "sequence_no")
        })
@Checks({
        @Check(name = "ck_timetable_period_name_not_blank",
                constraints = "LTRIM(RTRIM(name)) <> ''"),
        @Check(name = "ck_timetable_period_sequence",
                constraints = "sequence_no > 0"),
        @Check(name = "ck_timetable_period_times",
                constraints = "end_time > start_time")
})
public class TimetablePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String name;

    @NotNull
    @Positive
    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @NotNull
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @NotNull
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Version
    @JsonIgnore
    private Long version;

    protected TimetablePeriod() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public TimetablePeriod(String name, Integer sequenceNo, LocalTime startTime, LocalTime endTime) {
        if (name == null) {
            throw new IllegalArgumentException("A period needs a name.");
        }
        if (sequenceNo == null) {
            throw new IllegalArgumentException("A period needs a position in the day.");
        }
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("A period's position must be 1 or more.");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("A period needs both a start and an end time.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                    "A period must end after it starts - got " + startTime + " to " + endTime + ".");
        }
        this.name = name;
        this.sequenceNo = sequenceNo;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public String getName() {
        String value = name;
        if (value == null) {
            throw new IllegalStateException("Period " + id + " has no name.");
        }
        return value;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    @NonNull
    public LocalTime getStartTime() {
        LocalTime value = startTime;
        if (value == null) {
            throw new IllegalStateException("Period " + id + " has no start time.");
        }
        return value;
    }

    @NonNull
    public LocalTime getEndTime() {
        LocalTime value = endTime;
        if (value == null) {
            throw new IllegalStateException("Period " + id + " has no end time.");
        }
        return value;
    }

    /** Both ends at once - see Term.setDates for why this is not two setters. */
    public void setTimes(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("A period needs both a start and an end time.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                    "A period must end after it starts - got " + startTime + " to " + endTime + ".");
        }
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getVersion() {
        return version;
    }
}
