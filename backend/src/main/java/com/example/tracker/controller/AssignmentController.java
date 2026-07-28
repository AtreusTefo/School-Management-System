package com.example.tracker.controller;

import com.example.tracker.model.Assignment;
import com.example.tracker.service.AssignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER
 * -------------------------------
 * This is the "front door" of the backend. Its ONLY job is to:
 *   - receive HTTP requests from the outside world (our Angular app),
 *   - hand the work to the service,
 *   - return the result as JSON.
 *
 * It contains NO business rules and NO database code.
 *
 * @RestController = this class handles web requests and returns data (JSON),
 *                   not HTML pages.
 * @RequestMapping  = every URL in this class starts with "/api/assignments".
 *
 * @CrossOrigin is a SECURITY setting. Browsers block a page served from one
 * origin (http://localhost:4200, the Angular dev server) from calling an API
 * on a different origin (http://localhost:8080, Spring Boot) unless the API
 * explicitly allows it. This line grants that permission (CORS).
 *
 * BOTH spellings of loopback are listed deliberately. "localhost" and
 * "127.0.0.1" are the same machine but NOT the same origin, so a page opened at
 * http://127.0.0.1:4200 was refused with 403 while the identical page at
 * http://localhost:4200 worked. Worse, the browser reports that refusal to
 * Angular as status 0 — indistinguishable from the backend being switched off —
 * which makes it a genuinely confusing failure to diagnose.
 *
 * The list stays explicit rather than becoming a wildcard: "*" would let any
 * site on the internet call this API from a visitor's browser. For a real
 * deployment these values belong in configuration, not compiled in.
 */
@RestController
@RequestMapping("/api/assignments")
@CrossOrigin(origins = { "http://localhost:4200", "http://127.0.0.1:4200" })
public class AssignmentController {

    // The controller DEPENDS ON the service below it (dependency injection).
    private final AssignmentService service;

    public AssignmentController(AssignmentService service) {
        this.service = service;
    }

    /**
     * GET /api/assignments
     * Fetch the full list. Spring converts the returned List into JSON.
     */
    @GetMapping
    public List<Assignment> getAllAssignments() {
        return service.getAllAssignments();
    }

    /**
     * POST /api/assignments
     * Create a new assignment.
     *
     * @RequestBody turns the incoming JSON (e.g. {"title":"Science Lab"}) into
     * a Java object. We use a small "CreateAssignmentRequest" class below so the
     * client can ONLY send a title — it cannot set an id or a status. Enforcing
     * the status is the service's job.
     *
     * @Valid switches ON the annotations declared inside CreateAssignmentRequest
     * (@NotBlank). Without @Valid those annotations are decoration and Spring
     * ignores them entirely — which is why a blank title used to sail past the
     * web layer and blow up as a 500 further down. With it, Spring rejects the
     * request up front and GlobalExceptionHandler turns that into a clean 400.
     */
    @PostMapping
    public Assignment createAssignment(@Valid @RequestBody CreateAssignmentRequest request) {
        return service.createAssignment(request.getTitle());
    }

    /**
     * PUT /api/assignments/{id}/submit
     * Trigger the status update for one assignment.
     *
     * @PathVariable pulls the number out of the URL. For example, a request to
     * /api/assignments/3/submit gives us id = 3.
     *
     * We simply delegate to the service — all the real logic lives there.
     */
    @PutMapping("/{id}/submit")
    public Assignment submitAssignment(@PathVariable Long id) {
        return service.submitAssignment(id);
    }

    /**
     * A tiny "DTO" (Data Transfer Object): the shape of the JSON we accept when
     * creating an assignment. Keeping this separate from the Assignment entity
     * means the client can never sneak in an id or status it shouldn't control.
     */
    static class CreateAssignmentRequest {

        /**
         * @NotBlank rejects null, "" and "   " (whitespace only). The message is
         * what the client receives in the 400 response body.
         *
         * The service ALSO checks the title. That duplication is deliberate: this
         * annotation guards the web edge, while the service guard protects the
         * business rule no matter who calls it. Never rely on one layer alone.
         */
        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        private String title;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}
