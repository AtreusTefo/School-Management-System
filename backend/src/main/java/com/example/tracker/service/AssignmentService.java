package com.example.tracker.service;

import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER
 * ------------------------------
 * This is the "brain" of the application. It holds the RULES: what is allowed,
 * what validation must pass, what should happen.
 *
 * The service does NOT know about HTTP (that's the controller's job), and does
 * NOT know how to talk to the database (that's the repository's job).
 *
 * WHY THIS CLASS IS THE TRANSACTION BOUNDARY
 * ------------------------------------------
 * A business operation either happens completely or not at all. Without
 * @Transactional every repository call commits on its own, so two people
 * submitting the same assignment could BOTH read IN_PROGRESS, BOTH pass the
 * "already submitted?" check, and BOTH write. Measured before this annotation
 * existed: 3 of 12 simultaneous submissions accepted when exactly 1 should have
 * been.
 *
 * WHY AUTHORITY LIVES HERE TOO
 * ----------------------------
 * "May this person do this?" is a business rule, not an HTTP concern. Putting it
 * in the controller would mean a second caller - a scheduled job, a test, a new
 * endpoint - could bypass it entirely. Every method below establishes the caller
 * from the session and decides for itself.
 */
@Service
@Transactional(readOnly = true)   // safe default; write methods override it below
public class AssignmentService {

    private final AssignmentRepository repository;
    private final AppUserService users;

    public AssignmentService(AssignmentRepository repository, AppUserService users) {
        this.repository = repository;
        this.users = users;
    }

    /**
     * What the signed-in person is allowed to see.
     *
     * A TEACHER sets work and needs the whole picture. A STUDENT sees only their
     * own - not as a UI convenience but as a rule enforced by the query itself.
     * Filtering in the frontend would send everyone's data to every browser and
     * merely decline to draw it.
     */
    public List<Assignment> getAllAssignments() {
        AppUser me = users.currentActiveUser();
        return me.getRole() == Role.TEACHER
                ? repository.findAllByOrderByIdAsc()
                : repository.findByOwnerOrderByIdAsc(me);
    }

    /**
     * Business rule: "set a piece of work".
     *
     * Only a TEACHER may create - checked here rather than only in the URL
     * rules, so the restriction holds for any caller.
     *
     * WHO THE ASSIGNMENT BELONGS TO
     * A teacher sets work FOR somebody. If assignTo names an account, the
     * assignment belongs to that person and appears in their list; if it is
     * omitted, the teacher keeps it themselves.
     *
     * This distinction is what makes the two roles mean anything. An earlier
     * version made the creator the owner unconditionally, which - since only
     * teachers can create - meant a student could never see a single assignment
     * and the role was decorative.
     *
     * The status is still forced to IN_PROGRESS: a client cannot create work
     * that is already submitted.
     */
    @Transactional
    public Assignment createAssignment(String title, LocalDate dueDate, String assignTo) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Only a teacher can create an assignment.");
        }
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }

        AppUser owner = (assignTo == null || assignTo.isBlank())
                ? me
                : users.findByUsernameOrReject(assignTo.trim());

        return repository.save(
                new Assignment(title.trim(), AssignmentStatus.IN_PROGRESS, owner, dueDate));
    }

    /**
     * Business rule: "submitting" an assignment.
     *
     * Runs in ONE transaction, and the entity carries a @Version column, so a
     * competing update is detected and rejected rather than silently overwriting.
     */
    @Transactional
    public Assignment submitAssignment(Long id) {
        Assignment assignment = requireVisible(id);

        if (AssignmentStatus.SUBMITTED == assignment.getStatus()) {
            throw new IllegalStateException(
                    "Assignment " + id + " has already been submitted.");
        }
        assignment.setStatus(AssignmentStatus.SUBMITTED);
        return assignment;
    }

    /**
     * US-19: undo an accidental submission.
     *
     * Deliberately NOT the mirror image of submit. Anyone who can see an
     * assignment may hand it in, but only a TEACHER may reopen one - otherwise
     * "submitted" would mean nothing, since a student could retract work the
     * moment it was marked late.
     */
    @Transactional
    public Assignment unsubmitAssignment(Long id) {
        AppUser me = users.currentActiveUser();
        Assignment assignment = requireVisible(id);

        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException(
                    "Only a teacher can reopen a submitted assignment.");
        }
        if (AssignmentStatus.IN_PROGRESS == assignment.getStatus()) {
            throw new IllegalStateException(
                    "Assignment " + id + " has not been submitted, so it cannot be reopened.");
        }
        assignment.setStatus(AssignmentStatus.IN_PROGRESS);
        return assignment;
    }

    /**
     * US-17: correct an assignment.
     *
     * TEACHER ONLY, and deliberately not "owner only".
     *
     * Ownership answers "whose work is this to hand in?", which is not the same
     * question as "who may change what the work IS". An owner-only rule looked
     * reasonable until a teacher set work FOR a student: the student became the
     * owner, and the teacher could no longer correct their own typo. The mirror
     * problem is worse - a student could rewrite the title of the assignment
     * they had been set.
     *
     * So editing follows the role, not the row. A blank title is refused for the
     * same reason it is refused on create: the rule belongs to the data, not to
     * one entry point.
     */
    @Transactional
    public Assignment updateAssignment(Long id, String title, LocalDate dueDate) {
        Assignment assignment = requireTeacher(id, "edit");

        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }
        assignment.setTitle(title.trim());
        assignment.setDueDate(dueDate);
        return assignment;
    }

    /**
     * US-17: remove an assignment.
     *
     * Teacher only, for the same reason as editing. A submitted assignment
     * cannot be deleted at all: destroying the record of work that was handed in
     * loses information nobody can recover. Reopen it first if that is genuinely
     * intended - which forces the deletion to be two deliberate acts rather than
     * one careless click.
     */
    @Transactional
    public void deleteAssignment(Long id) {
        Assignment assignment = requireTeacher(id, "delete");

        if (AssignmentStatus.SUBMITTED == assignment.getStatus()) {
            throw new IllegalStateException(
                    "Assignment " + id + " has been submitted and cannot be deleted. "
                            + "Reopen it first if this is intended.");
        }
        repository.delete(assignment);
    }

    // ----- shared guards -------------------------------------------------------

    /**
     * Fetch an assignment the caller is allowed to SEE, or fail.
     *
     * A student asking for somebody else's assignment gets 404, not 403.
     * Answering "forbidden" would confirm that the id exists and belongs to
     * someone - letting an outsider map the data by probing ids. "Not found" is
     * both true from their perspective and tells them nothing.
     */
    private Assignment requireVisible(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Assignment id must not be null.");
        }
        AppUser me = users.currentActiveUser();
        Assignment assignment = repository.findById(id)
                .orElseThrow(() -> new AssignmentNotFoundException(id));

        boolean mine = assignment.getOwner() != null
                && assignment.getOwner().getId().equals(me.getId());
        if (me.getRole() != Role.TEACHER && !mine) {
            throw new AssignmentNotFoundException(id);
        }
        return assignment;
    }

    /**
     * Fetch an assignment the caller is allowed to CHANGE, or fail.
     *
     * Here 403 is correct rather than 404. The caller is a student who can
     * already legitimately see their own row, so hiding its existence would be
     * pointless - and "you may not do that" is the more useful, honest answer.
     * Contrast requireVisible, where 404 is used precisely because confirming
     * existence would leak something.
     */
    private Assignment requireTeacher(Long id, String action) {
        Assignment assignment = requireVisible(id);
        AppUser me = users.currentActiveUser();

        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException(
                    "Only a teacher can " + action + " an assignment.");
        }
        return assignment;
    }
}
