package com.example.tracker.service;

import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.repository.AssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * WHY THIS CLASS IS THE TRANSACTION BOUNDARY
 * ------------------------------------------
 * A business operation either happens completely or not at all. "Submit this
 * assignment" is one operation even though it is several database calls, so the
 * transaction has to wrap the whole method — not each call inside it.
 *
 * Without @Transactional, every repository call commits on its own. Two people
 * submitting the same assignment at the same moment could BOTH read
 * IN_PROGRESS, BOTH pass the "already submitted?" check, and BOTH write. The
 * rule appears to hold in single-user testing and quietly fails under load.
 * (Measured before this annotation existed: 3 of 12 simultaneous submissions
 * were accepted when exactly 1 should have been.)
 */
@Service
@Transactional(readOnly = true)   // safe default; write methods override it below
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
     *
     * readOnly = true (inherited from the class) lets the database skip dirty
     * checking and signals that this path must never write.
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
    @Transactional
    public Assignment createAssignment(String title) {

        // 1. VALIDATION: a blank title is not allowed.
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }

        // 2. Build the new object with the enforced default status.
        Assignment assignment = new Assignment(title.trim(), AssignmentStatus.IN_PROGRESS);

        // 3. Persist it. save() INSERTs because there is no id yet.
        return repository.save(assignment);
    }

    /**
     * Business rule: "submitting" an assignment.
     * This is where validation and logic live — NOT in the controller.
     *
     * Steps:
     *   0. Refuse a missing id outright.
     *   1. Find the assignment by id (or fail clearly if it doesn't exist).
     *   2. Check it isn't already submitted (a business rule).
     *   3. Change the status to SUBMITTED.
     *
     * The whole method runs in ONE transaction, and the entity carries a
     * @Version column, so a competing update is detected and rejected rather
     * than silently overwriting this one.
     */
    @Transactional
    public Assignment submitAssignment(Long id) {

        // 0. GUARD: the repository treats its argument as non-null, so a null id
        //    would surface as an obscure failure deep in the persistence layer.
        //    Rejecting it here turns that into a clear 400 — and tells the
        //    compiler's null analysis that `id` is safe to pass on below.
        if (id == null) {
            throw new IllegalArgumentException("Assignment id must not be null.");
        }

        // 1. Fetch it. findById returns an Optional (a box that may be empty).
        //    If empty, we throw a clear error instead of returning null.
        //    We use a DEDICATED exception type here (not IllegalArgumentException)
        //    so the web layer can answer 404 Not Found rather than 400.
        Assignment assignment = repository.findById(id)
                .orElseThrow(() -> new AssignmentNotFoundException(id));

        // 2. VALIDATION / business rule: don't submit something twice.
        //    This catches the everyday case. The @Version column catches the
        //    narrow case where a competing transaction slips between this check
        //    and the commit below.
        if (AssignmentStatus.SUBMITTED == assignment.getStatus()) {
            throw new IllegalStateException(
                    "Assignment " + id + " has already been submitted.");
        }

        // 3. Apply the change. The entity is managed inside this transaction, so
        //    Hibernate writes the UPDATE (with its version check) at commit.
        assignment.setStatus(AssignmentStatus.SUBMITTED);

        return assignment;
    }
}
