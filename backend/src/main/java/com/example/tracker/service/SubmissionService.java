package com.example.tracker.service;

import com.example.tracker.dto.FileDownload;
import com.example.tracker.dto.SubmissionView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.AssignmentNotFoundException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.Course;
import com.example.tracker.model.Role;
import com.example.tracker.model.Submission;
import com.example.tracker.model.SubmissionFile;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.SubmissionRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for handing work in.
 *
 * A student uploads a PDF and then submits it; a teacher downloads it to mark.
 * Those are two different acts and this class keeps them that way - uploading is
 * not submitting, so a student can replace a file they chose by mistake right up
 * until they commit to it.
 *
 * WHY THIS SERVICE TAKES BYTES, NOT A MultipartFile
 * -------------------------------------------------
 * MultipartFile is a Spring WEB type. Accepting one here would drag HTTP into
 * the business layer and, more practically, would mean none of the rules below
 * could be tested without standing up a web request. The controller unwraps the
 * upload and passes a filename, a declared type and a byte array; everything
 * that decides whether the upload is ACCEPTABLE happens here, in plain Java.
 */
@Service
@Transactional(readOnly = true)
public class SubmissionService {

    /** The first five bytes of every well-formed PDF: "%PDF-". */
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final SubmissionRepository submissions;
    private final AssignmentRepository assignments;
    private final CourseRepository courses;
    private final AppUserService users;
    private final AuditLogService audit;

    public SubmissionService(SubmissionRepository submissions,
                             AssignmentRepository assignments,
                             CourseRepository courses,
                             AppUserService users,
                             AuditLogService audit) {
        this.submissions = submissions;
        this.assignments = assignments;
        this.courses = courses;
        this.users = users;
        this.audit = audit;
    }

    /**
     * What the caller is allowed to see.
     *
     * A student gets their own work and nothing else. A teacher gets every
     * submission across the courses they teach - not every submission in the
     * school, which would be a much larger claim than this system makes.
     *
     * Both are scoped in the QUERY. Loading everything and filtering afterwards
     * would send other people's coursework to a browser that merely declines to
     * draw it, which is not access control.
     */
    public List<SubmissionView> listSubmissions() {
        AppUser me = users.currentActiveUser();

        List<Submission> visible = me.getRole() == Role.TEACHER
                ? submissions.findForTeacher(me)
                : submissions.findByStudentOrderByIdAsc(me);

        return visible.stream().map(SubmissionView::of).toList();
    }

    /** Every student's state for one assignment - the teacher's marking list. */
    public List<SubmissionView> listForAssignment(Long assignmentId) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException(
                    "Only a teacher can see the whole class's work for an assignment.");
        }

        Assignment assignment = requireAssignment(assignmentId);
        requireTeaches(assignment.getCourse(), me);

        return submissions.findByAssignmentOrderByIdAsc(assignment).stream()
                .map(SubmissionView::of).toList();
    }

    /**
     * Attach a PDF to a submission, replacing any previous one.
     *
     * VALIDATION HAPPENS IN THIS ORDER FOR A REASON. The cheap structural checks
     * run before the expensive hash, and the content check runs before anything
     * is written, so a rejected upload never reaches the database at all.
     *
     * THE DECLARED CONTENT TYPE IS NOT TRUSTED. A browser sends whatever it
     * likes, and a client written by hand sends whatever it wants; renaming
     * malware.exe to essay.pdf sets the declared type to application/pdf without
     * changing a byte of the file. So the first five bytes are inspected as well
     * - a real PDF begins "%PDF-". That is not a virus scanner and this comment
     * does not pretend otherwise; it is the difference between checking a label
     * and checking the contents.
     */
    @Transactional
    public SubmissionView uploadFile(Long submissionId, String filename,
                                     String declaredContentType, byte[] content) {
        AppUser me = users.currentActiveUser();
        Submission submission = requireOwnSubmission(submissionId, "upload work for");

        /*
         * Uploading to already-submitted work is refused. Allowing it would mean
         * the document being marked could change after the deadline, silently,
         * with the submission timestamp still claiming the original moment.
         * Reopening is a teacher's decision and leaves a visible state change.
         */
        if (submission.getStatus() == AssignmentStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "This work has already been handed in. Ask your teacher to reopen it "
                            + "before uploading a different file.");
        }

        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }
        if (content.length > SubmissionFile.MAX_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "That file is " + (content.length / (1024 * 1024))
                            + " MB. The limit is 10 MB.");
        }
        if (!looksLikePdf(content)) {
            throw new IllegalArgumentException(
                    "That file is not a PDF. Only PDF documents can be handed in.");
        }

        String cleanName = sanitiseFilename(filename);
        String checksum = sha256Hex(content);

        /*
         * REPLACE rather than add. The association is orphanRemoval = true, so
         * dropping the old file here deletes its row - no second table full of
         * abandoned documents, and no ambiguity about which one is being marked.
         */
        submission.setFile(null);
        submissions.flush();

        submission.setFile(new SubmissionFile(
                submission, cleanName, SubmissionFile.PDF_CONTENT_TYPE,
                content, checksum, Instant.now()));

        audit.record("Submission", submission.getId(), AuditAction.UPDATE, me,
                "Uploaded '" + cleanName + "' for submission " + submission.getId() + ".");

        return SubmissionView.of(submission);
    }

    /**
     * Hand the work in.
     *
     * A FILE IS REQUIRED. "Submitted" with nothing attached would be a claim
     * with no evidence behind it, and the teacher would only discover the gap
     * when they came to mark it.
     *
     * Runs in ONE transaction against a row carrying @Version, so two clicks
     * arriving together cannot both succeed - the second finds the row changed
     * and is refused with 409 rather than silently overwriting the first.
     */
    @Transactional
    public SubmissionView submit(Long submissionId) {
        AppUser me = users.currentActiveUser();
        Submission submission = requireOwnSubmission(submissionId, "hand in");

        if (submission.getStatus() == AssignmentStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Submission " + submissionId + " has already been handed in.");
        }
        if (submission.getFile() == null) {
            throw new IllegalStateException(
                    "Upload your PDF before handing this in.");
        }

        submission.markSubmitted(Instant.now());
        audit.record("Submission", submission.getId(), AuditAction.UPDATE, me,
                "Handed in submission " + submission.getId() + ".");
        return SubmissionView.of(submission);
    }

    /**
     * Reopen submitted work.
     *
     * TEACHER ONLY, and deliberately not the mirror image of submit. If a
     * student could retract their own submission, "submitted" would mean nothing
     * - work could be pulled back the moment it was marked late. Anyone may hand
     * their own work in; only a teacher may undo it.
     */
    @Transactional
    public SubmissionView unsubmit(Long submissionId) {
        AppUser me = users.currentActiveUser();
        if (me.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Only a teacher can reopen handed-in work.");
        }

        Submission submission = requireSubmission(submissionId);
        requireTeaches(submission.getAssignment().getCourse(), me);

        if (submission.getStatus() == AssignmentStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Submission " + submissionId + " has not been handed in, "
                            + "so it cannot be reopened.");
        }

        submission.markInProgress();
        audit.record("Submission", submission.getId(), AuditAction.UPDATE, me,
                "Reopened submission " + submission.getId() + ".");
        return SubmissionView.of(submission);
    }

    /**
     * Fetch the PDF for marking or for the student's own reference.
     *
     * TWO ROLES MAY DOWNLOAD, FOR DIFFERENT REASONS: the student who uploaded
     * it, and a teacher of the course it was set for. Any other teacher is
     * refused - "teacher" is not a licence to read every child's work in the
     * school, only the work in the classes they teach.
     */
    public FileDownload download(Long submissionId) {
        AppUser me = users.currentActiveUser();
        Submission submission = requireSubmission(submissionId);

        boolean mine = submission.getStudent().getId().equals(me.getId());
        if (!mine) {
            if (me.getRole() != Role.TEACHER) {
                // Same reasoning as elsewhere: for a student asking about
                // somebody else's row, "not found" leaks less than "forbidden"
                // and is true from where they are standing.
                throw new ResourceNotFoundException("submission", submissionId);
            }
            requireTeaches(submission.getAssignment().getCourse(), me);
        }

        SubmissionFile file = submission.getFile();
        if (file == null) {
            throw new ResourceNotFoundException(
                    "No file has been uploaded for submission " + submissionId + ".");
        }

        return new FileDownload(file.getFilename(), file.getContentType(), file.getContent());
    }

    // ----- validation helpers --------------------------------------------------

    /**
     * Does this actually begin like a PDF?
     *
     * Checks the magic number rather than the extension or the declared type,
     * both of which are supplied by whoever is uploading. Not a guarantee the
     * document is well-formed throughout - that would need a parser - but it
     * does mean the bytes claiming to be a PDF start the way one does.
     */
    private boolean looksLikePdf(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reduce a browser-supplied filename to something safe to store and serve.
     *
     * A filename from a client is UNTRUSTED INPUT. Two things go wrong with it:
     * path separators, where "../../etc/passwd" turns a download into a write
     * somewhere it should not be; and control characters, which can forge extra
     * HTTP headers when the name is echoed into Content-Disposition.
     *
     * Everything after the last separator is kept, the rest discarded.
     */
    private String sanitiseFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "submission.pdf";
        }

        String name = filename.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}\"]", "");

        if (name.isBlank()) {
            return "submission.pdf";
        }
        if (name.length() > 255) {
            name = name.substring(0, 255);
        }
        return name;
    }

    /** SHA-256 as 64 lowercase hex characters, matching ck_submission_file_sha256. */
    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform. If it is genuinely
            // absent the environment is broken in a way this code cannot repair,
            // and pretending otherwise would store a file with no checksum.
            throw new IllegalStateException("SHA-256 is not available on this JVM.", e);
        }
    }

    // ----- shared guards -------------------------------------------------------

    /**
     * Require.orThrow, not .orElseThrow(...) directly: java.util.Optional
     * carries no null-safety annotations, so a method declared @NonNull that
     * ended with .orElseThrow(...) would still be flagged at its own return
     * statement, unable to see past the JDK type. See Require for the full
     * reasoning; every "look this up or 404" guard in this codebase now goes
     * through it.
     */
    @NonNull
    private Submission requireSubmission(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Submission id must not be null.");
        }
        return Require.orThrow(submissions.findById(id),
                () -> new ResourceNotFoundException("submission", id));
    }

    /**
     * The caller's own submission, or 404.
     *
     * A student asking about somebody else's row gets "not found" rather than
     * "forbidden", because answering "forbidden" would confirm the row exists
     * and belongs to someone - letting an outsider map the data by probing ids.
     */
    private Submission requireOwnSubmission(Long id, String action) {
        AppUser me = users.currentActiveUser();
        Submission submission = requireSubmission(id);

        if (!submission.getStudent().getId().equals(me.getId())) {
            if (me.getRole() == Role.TEACHER) {
                throw new AccessDeniedException(
                        "A teacher cannot " + action + " a student's submission.");
            }
            throw new ResourceNotFoundException("submission", id);
        }
        return submission;
    }

    @NonNull
    private Assignment requireAssignment(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Assignment id must not be null.");
        }
        return Require.orThrow(assignments.findById(id),
                () -> new AssignmentNotFoundException(id));
    }

    /** The caller must teach this course. Explained in AssignmentService. */
    private void requireTeaches(Course course, AppUser me) {
        boolean teachesIt = courses.findBySubjectAndSchoolClassAndTeacher(
                course.getSubject(), course.getSchoolClass(), me).isPresent();

        if (!teachesIt) {
            throw new AccessDeniedException("You do not teach " + course.getLabel() + ".");
        }
    }
}
