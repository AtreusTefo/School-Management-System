package com.example.tracker.controller;

import com.example.tracker.dto.TermView;
import com.example.tracker.dto.TimetablePeriodView;
import com.example.tracker.dto.TimetableSlotView;
import com.example.tracker.service.TimetableService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * WEB LAYER for terms, periods and the timetable.
 *
 * HTTP only: read the request, delegate, return the result. Every rule - who
 * may write, whether two lessons clash, whether two terms overlap - lives in
 * TimetableService, so it holds for any caller rather than for anyone who
 * happens to arrive through this class.
 *
 * VALIDATION AT BOTH EDGES, deliberately. The @NotBlank/@Size/@NotNull
 * annotations on the request records below reject a malformed request before
 * it reaches the service; the service checks the same things again because it
 * is also called from tests and could be called from anywhere else later. See
 * CLAUDE.md's Validation section on why neither layer is enough alone.
 */
@RestController
@RequestMapping("/api")
public class TimetableController {

    private final TimetableService timetable;

    public TimetableController(TimetableService timetable) {
        this.timetable = timetable;
    }

    // ----- terms -------------------------------------------------------------

    @GetMapping("/terms")
    public List<TermView> listTerms() {
        return timetable.listTerms();
    }

    @PostMapping("/terms")
    public ResponseEntity<TermView> createTerm(@Valid @RequestBody TermRequest request) {
        TermView created = timetable.createTerm(
                request.getName(), request.getStartDate(), request.getEndDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/terms/{id}")
    public TermView updateTerm(@PathVariable Long id, @Valid @RequestBody TermRequest request) {
        return timetable.updateTerm(
                id, request.getName(), request.getStartDate(), request.getEndDate());
    }

    @DeleteMapping("/terms/{id}")
    public ResponseEntity<Void> deleteTerm(@PathVariable Long id) {
        timetable.deleteTerm(id);
        return ResponseEntity.noContent().build();
    }

    // ----- periods -----------------------------------------------------------

    @GetMapping("/periods")
    public List<TimetablePeriodView> listPeriods() {
        return timetable.listPeriods();
    }

    @PostMapping("/periods")
    public ResponseEntity<TimetablePeriodView> createPeriod(
            @Valid @RequestBody PeriodRequest request) {
        TimetablePeriodView created = timetable.createPeriod(
                request.getName(), request.getSequenceNo(),
                request.getStartTime(), request.getEndTime());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/periods/{id}")
    public TimetablePeriodView updatePeriod(@PathVariable Long id,
                                            @Valid @RequestBody PeriodRequest request) {
        return timetable.updatePeriod(id, request.getName(), request.getSequenceNo(),
                request.getStartTime(), request.getEndTime());
    }

    @DeleteMapping("/periods/{id}")
    public ResponseEntity<Void> deletePeriod(@PathVariable Long id) {
        timetable.deletePeriod(id);
        return ResponseEntity.noContent().build();
    }

    // ----- the timetable -----------------------------------------------------

    /** The whole school's week for a term. Staff only - the service decides. */
    @GetMapping("/timetable")
    public List<TimetableSlotView> listSlots(@RequestParam Long termId) {
        return timetable.listSlots(termId);
    }

    /** The caller's own week - the only timetable read a student may make. */
    @GetMapping("/timetable/me")
    public List<TimetableSlotView> myTimetable(@RequestParam Long termId) {
        return timetable.listMyTimetable(termId);
    }

    @PostMapping("/timetable")
    public ResponseEntity<TimetableSlotView> schedule(@Valid @RequestBody SlotRequest request) {
        TimetableSlotView created = timetable.schedule(
                request.getTermId(), request.getPeriodId(),
                request.getCourseId(), request.getDayOfWeek());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/timetable/{id}")
    public ResponseEntity<Void> unschedule(@PathVariable Long id) {
        timetable.unschedule(id);
        return ResponseEntity.noContent().build();
    }

    // ----- request bodies ----------------------------------------------------

    /**
     * Classes with getters and setters rather than records, matching
     * AdminController's request types: Jackson binds a record's components
     * positionally through a canonical constructor, and a missing field then
     * arrives as a bare null with no field name attached to the failure. A
     * bean lets each field carry its own annotation and its own message.
     */
    static class TermRequest {

        @NotBlank(message = "A term name is required.")
        @Size(max = 60, message = "A term name may be at most 60 characters.")
        private String name;

        @NotNull(message = "A start date is required.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate startDate;

        @NotNull(message = "An end date is required.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate endDate;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalDate getStartDate() { return startDate; }
        public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

        public LocalDate getEndDate() { return endDate; }
        public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    }

    static class PeriodRequest {

        @NotBlank(message = "A period name is required.")
        @Size(max = 40, message = "A period name may be at most 40 characters.")
        private String name;

        @NotNull(message = "A position in the day is required.")
        @Positive(message = "A period's position must be 1 or more.")
        private Integer sequenceNo;

        @NotNull(message = "A start time is required.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        private LocalTime startTime;

        @NotNull(message = "An end time is required.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        private LocalTime endTime;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Integer getSequenceNo() { return sequenceNo; }
        public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }

        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    }

    static class SlotRequest {

        @NotNull(message = "A term is required.")
        private Long termId;

        @NotNull(message = "A period is required.")
        private Long periodId;

        @NotNull(message = "A course is required.")
        private Long courseId;

        @NotBlank(message = "A day of the week is required.")
        private String dayOfWeek;

        public Long getTermId() { return termId; }
        public void setTermId(Long termId) { this.termId = termId; }

        public Long getPeriodId() { return periodId; }
        public void setPeriodId(Long periodId) { this.periodId = periodId; }

        public Long getCourseId() { return courseId; }
        public void setCourseId(Long courseId) { this.courseId = courseId; }

        public String getDayOfWeek() { return dayOfWeek; }
        public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    }
}
