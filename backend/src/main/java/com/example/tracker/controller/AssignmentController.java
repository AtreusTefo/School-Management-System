package com.example.tracker.controller;

import com.example.tracker.dto.AssignmentView;
import com.example.tracker.dto.SubmissionView;
import com.example.tracker.service.AssignmentService;
import com.example.tracker.service.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER
 * -------------------------------
 * The "front door" for setting work. Its ONLY job is to receive HTTP requests,
 * hand the work to a service, and return the result as JSON.
 *
 * It contains NO business rules and NO database code. In particular it does not
 * decide who may do what: every authority rule lives in the service, so it holds
 * for any other caller - a test, a scheduled job, a future endpoint.
 *
 * CROSS-ORIGIN permission is not declared here. It used to be, as
 * @CrossOrigin(origins = "http://localhost:4200"), which compiled the frontend's
 * address into the backend. It now comes from configuration - see CorsConfig.
 */
@RestController
@RequestMapping("/api/assignments")
public class AssignmentController {

    private final AssignmentService assignments;
    private final SubmissionService submissions;

    public AssignmentController(AssignmentService assignments, SubmissionService submissions) {
        this.assignments = assignments;
        this.submissions = submissions;
    }

    /** GET /api/assignments - what the signed-in person can see, with progress counts. */
    @GetMapping
    public List<AssignmentView> list() {
        return assignments.listAssignments();
    }

    /**
     * POST /api/assignments
     *
     * Returns a LIST, because one request can set the same work for several
     * courses at once and each produces its own assignment.
     *
     * @Valid switches ON the annotations inside the request body class. Without
     * it those annotations are decoration and Spring ignores them entirely -
     * which is why a blank title once sailed past the web layer and surfaced as
     * a 500.
     */
    @PostMapping
    public List<AssignmentView> create(@Valid @RequestBody CreateAssignmentRequest request) {
        return assignments.createAssignment(
                request.getTitle(), request.getDescription(),
                request.getDueDate(), request.getCourseIds());
    }

    /** PUT /api/assignments/{id} - correct the title, description or due date. */
    @PutMapping("/{id}")
    public AssignmentView update(@PathVariable Long id,
                                 @Valid @RequestBody UpdateAssignmentRequest request) {
        return assignments.updateAssignment(
                id, request.getTitle(), request.getDescription(), request.getDueDate());
    }

    /**
     * DELETE /api/assignments/{id}
     * 204 No Content: the deletion succeeded and there is nothing to return.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        assignments.deleteAssignment(id);
    }

    /**
     * GET /api/assignments/{id}/submissions - the marking list.
     *
     * Every student's state for this assignment, so a teacher can see who has
     * handed in and reach each PDF. Teacher-only, and only for a course they
     * teach; the service enforces both.
     */
    @GetMapping("/{id}/submissions")
    public List<SubmissionView> submissionsFor(@PathVariable Long id) {
        return submissions.listForAssignment(id);
    }

    /**
     * The shape of the JSON accepted when setting work.
     *
     * Keeping this separate from the Assignment entity means a client can never
     * set an id, a creator or a course's teacher - the service decides all
     * three from the session.
     */
    static class CreateAssignmentRequest {

        /** @NotBlank rejects null, "" and "   " (whitespace only). */
        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        private String title;

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        private String description;

        /** Optional. Null means "no deadline", which is a legitimate state. */
        private LocalDate dueDate;

        /**
         * Which courses to set this work for. A list, because setting the same
         * task for three classes is one action for the teacher even though it is
         * three assignments in the data.
         */
        @NotEmpty(message = "Choose at least one course")
        private List<Long> courseIds;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public void setDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
        }

        public List<Long> getCourseIds() {
            return courseIds;
        }

        public void setCourseIds(List<Long> courseIds) {
            this.courseIds = courseIds;
        }
    }

    /** The shape accepted when editing. The course is deliberately not editable. */
    static class UpdateAssignmentRequest {

        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        private String title;

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        private String description;

        private LocalDate dueDate;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public void setDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
        }
    }
}
