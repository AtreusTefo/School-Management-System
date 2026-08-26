package com.example.tracker.dto;

import com.example.tracker.model.TimetablePeriod;

import java.time.LocalTime;

/** One slot of the school day, as the API publishes it. */
public record TimetablePeriodView(Long id, String name, Integer sequenceNo,
                                  LocalTime startTime, LocalTime endTime) {

    public static TimetablePeriodView of(TimetablePeriod period) {
        return new TimetablePeriodView(
                period.getId(),
                period.getName(),
                period.getSequenceNo(),
                period.getStartTime(),
                period.getEndTime());
    }
}
