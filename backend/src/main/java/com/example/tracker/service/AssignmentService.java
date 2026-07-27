package com.example.tracker.service;

import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.Assignment;
import com.example.tracker.repository.AssignmentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER
 * ------------------------------
 * This is the "brain" of the application. It holds the RULES:
 * what is allowed, what validation must pass, what should happen.
 *
 * The service does NOT know about HTTP (that's the controller's job),
 * and does NOT know how to talk to the database (that's the repository's job).
 * It just orchestrates: "given this request, apply the rules, then use the
 * repository to read/write data."
 *
 * @Service tells Spring to create and manage one shared instance (a "bean").
 */
@Service
public class AssignmentService {

    // The service DEPENDS ON the repository below it.
    // We store it in a field and receive it via the constructor ("dependency
    // injection"): Spring hands us a ready-to-use repository automatically.
    private final AssignmentRepository repository;

    public AssignmentService(AssignmentRepository repository) {
        this.repository = repository;
    }

    /**
     * Business rule: "fetching assignments" simply means return them all.
     * The controller will call this. It, in turn, asks the repository.
     */
    public List<Assignment> getAllAssignments() {
        return repository.findAll();
    }

    /**
     * Business rule: "create a new assignment".
     * This is where we VALIDATE the input before saving.
     *
     * Steps:
     *   1. Check the title is actually present (not empty/blank).
     *   2. Build a fresh Assignment. New assignments always start "IN_PROGRESS"
     *      — the client is NOT allowed to invent a status. That decision lives
     *      here in the business layer, not in the controller or the frontend.
     *   3. Save it (this INSERTs a new row and fills in the generated id).
     */
    public Assignment createAssignment(String title) {

        // 1. VALIDATION: a blank title is not allowed.
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }

        // 2. Build the new object with the enforced default status.
        Assignment assignment = new Assignment(title.trim(), "IN_PROGRESS");

        // 3. Persist it. save() INSERTs because there is no id yet.
        return repository.save(assignment);
    }

    /**
     * Business rule: "submitting" an assignment.
     * This is where validation and logic live — NOT in the controller.
     *
     * Steps:
     *   1. Find the assignment by id (or fail clearly if it doesn't exist).
     *   2. Check it isn't already submitted (a business rule).
     *   3. Change the status to "SUBMITTED".
     *   4. Save it back to the database.
     */
    public Assignment submitAssignment(Long id) {

        // 1. Fetch it. findById returns an Optional (a box that may be empty).
        //    If empty, we throw a clear error instead of returning null.
        //    We use a DEDICATED exception type here (not IllegalArgumentException)
        //    so the web layer can answer 404 Not Found rather than 400.
        Assignment assignment = repository.findById(id)
                .orElseThrow(() -> new AssignmentNotFoundException(id));

        // 2. VALIDATION / business rule: don't submit something twice.
        if ("SUBMITTED".equals(assignment.getStatus())) {
            throw new IllegalStateException(
                    "Assignment " + id + " has already been submitted.");
        }

        // 3. Apply the change.
        assignment.setStatus("SUBMITTED");

        // 4. Persist it. save() UPDATES because the object already has an id.
        return repository.save(assignment);
    }
}
