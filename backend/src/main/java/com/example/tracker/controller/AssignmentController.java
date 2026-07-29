package com.example.tracker.controller;

import com.example.tracker.model.Assignment;
import com.example.tracker.service.AssignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER
 * -------------------------------
 * The "front door" of the backend. Its ONLY job is to:
 *   - receive HTTP requests from the outside world (our Angular app),
 *   - hand the work to the service,
 *   - return the result as JSON.
 *
 * It contains NO business rules and NO database code. In particular it does not
 * decide who may do what: every authority rule lives in AssignmentService, so it
 * still holds for any other caller.
 *
 * @RestController = handles web requests and returns data (JSON), not HTML.
 * @RequestMapping = every URL in this class starts with "/api/assignments".
 *
 * CROSS-ORIGIN permission is NOT declared here. It used to be, as
 * @CrossOrigin(origins = "http://localhost:4200"), which compiled the frontend's
 * address into the backend and meant a rebuild whenever the interface moved. It
 * now comes from configuration - see CorsConfig and `app.cors.allowed-origins`.
 */
@RestController
@RequestMapping("/api/assignments")
public class AssignmentController {

    private final AssignmentService service;

    public AssignmentController(AssignmentService service) {
        this.service = service;
    }

    /**
     * GET /api/assignments
     * The list the SIGNED-IN person is allowed to see - everything for a
     * teacher, only their own for a student. The scoping happens in the service.
     */
    @GetMapping
    public List<Assignment> getAllAssignments() {
        return service.getAllAssignments();
    }

    /**
     * POST /api/assignments
     *
     * @Valid switches ON the annotations declared inside the request DTO.
     * Without it those annotations are decoration and Spring ignores them
     * entirely - which is why a blank title once sailed past the web layer and
     * surfaced as a 500.
     */
    @PostMapping
    public Assignment createAssignment(@Valid @RequestBody CreateAssignmentRequest request) {
        return service.createAssignment(
                request.getTitle(), request.getDueDate(), request.getAssignTo());
    }

    /** PUT /api/assignments/{id} - correct the title or due date (US-17). */
    @PutMapping("/{id}")
    public Assignment updateAssignment(@PathVariable Long id,
                                       @Valid @RequestBody UpdateAssignmentRequest request) {
        return service.updateAssignment(id, request.getTitle(), request.getDueDate());
    }

    /**
     * DELETE /api/assignments/{id} - remove it (US-17).
     * 204 No Content: the deletion succeeded and there is nothing to return.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAssignment(@PathVariable Long id) {
        service.deleteAssignment(id);
    }

    /** PUT /api/assignments/{id}/submit - mark it handed in. */
    @PutMapping("/{id}/submit")
    public Assignment submitAssignment(@PathVariable Long id) {
        return service.submitAssignment(id);
    }

    /** PUT /api/assignments/{id}/unsubmit - reopen it, teacher only (US-19). */
    @PutMapping("/{id}/unsubmit")
    public Assignment unsubmitAssignment(@PathVariable Long id) {
        return service.unsubmitAssignment(id);
    }

    /**
     * The shape of the JSON accepted when creating an assignment.
     *
     * Keeping this separate from the Assignment entity means the client can
     * never set an id, a status or an owner - the service decides all three.
     */
    static class CreateAssignmentRequest {

        /**
         * @NotBlank rejects null, "" and "   " (whitespace only).
         *
         * The service ALSO checks the title. That duplication is deliberate:
         * this annotation guards the web edge, while the service guard protects
         * the business rule no matter who calls it.
         */
        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        private String title;

        /** Optional. Null means "no deadline", which is a legitimate state. */
        private LocalDate dueDate;

        /**
         * Optional: the account this work is set FOR. Omit it and the teacher
         * keeps the assignment themselves.
         *
         * Note this is a USERNAME, not an id. A client naming a row by its
         * primary key invites probing for ids that exist; a name is what the
         * teacher actually knows, and the service still refuses one that does
         * not resolve.
         */
        private String assignTo;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public void setDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
        }

        public String getAssignTo() {
            return assignTo;
        }

        public void setAssignTo(String assignTo) {
            this.assignTo = assignTo;
        }
    }

    /** The shape accepted when editing. Same fields; status is never editable. */
    static class UpdateAssignmentRequest {

        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        private String title;

        private LocalDate dueDate;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public void setDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
        }
    }
}
