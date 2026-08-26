package com.example.tracker.controller;

import com.example.tracker.dto.AttendanceSummaryView;
import com.example.tracker.dto.AttendanceView;
import com.example.tracker.service.AttendanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * WEB LAYER for the attendance register.
 *
 * HTTP only. Who may take a register, whether a date is a real occurrence of a
 * lesson, and whether a student is enrolled in the class all live in
 * AttendanceService, so they hold for any caller rather than for whoever
 * happens to arrive through this class.
 *
 * PUT RATHER THAN POST for taking the register, deliberately. Taking it twice
 * for the same lesson and date must leave the same result rather than two
 * registers - which is exactly what idempotent means, and PUT is the method
 * that says so. The service turns the second call into a correction of the
 * first; the URL identifies the register, not the act of taking it.
 */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    /**
     * The register for one lesson on one date - every enrolled student, marked
     * or not. See AttendanceService.register for why the unmarked are included.
     */
    @GetMapping("/lessons/{slotId}")
    public List<AttendanceView> register(
            @PathVariable Long slotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendance.register(slotId, date);
    }

    @PutMapping("/lessons/{slotId}")
    public List<AttendanceView> takeRegister(
            @PathVariable Long slotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody RegisterRequest request) {
        return attendance.takeRegister(slotId, date, request.toMarks());
    }

    /** One student's record. A student may only ask about themselves. */
    @GetMapping("/students/{studentId}")
    public List<AttendanceView> studentRecord(@PathVariable Long studentId) {
        return attendance.studentRecord(studentId);
    }

    /** The counts and the rate, computed on read - never stored. */
    @GetMapping("/students/{studentId}/summary")
    public AttendanceSummaryView summary(@PathVariable Long studentId) {
        return attendance.summarise(studentId);
    }

    // ----- request bodies ----------------------------------------------------

    /**
     * Beans rather than records, matching every other request type in this
     * codebase: Jackson binds a record positionally through its canonical
     * constructor, so a missing field arrives as a bare null with no field name
     * attached to the failure.
     */
    static class RegisterRequest {

        @NotEmpty(message = "A register needs at least one student.")
        @Valid
        private List<MarkRequest> marks;

        public List<MarkRequest> getMarks() { return marks; }
        public void setMarks(List<MarkRequest> marks) { this.marks = marks; }

        List<AttendanceService.Mark> toMarks() {
            return marks == null ? List.of() : marks.stream()
                    .map(m -> new AttendanceService.Mark(
                            m.getStudentId(), m.getStatus(), m.getNote()))
                    .toList();
        }
    }

    static class MarkRequest {

        @NotNull(message = "Every mark needs a student.")
        private Long studentId;

        @NotNull(message = "Every mark needs a status.")
        private String status;

        @Size(max = 200, message = "A note may be at most 200 characters.")
        private String note;

        public Long getStudentId() { return studentId; }
        public void setStudentId(Long studentId) { this.studentId = studentId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
