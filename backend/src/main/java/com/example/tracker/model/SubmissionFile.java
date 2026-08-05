package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.lang.NonNull;

import java.time.Instant;

/**
 * The PDF a student uploaded for one submission.
 *
 * WHY THE BYTES LIVE IN THE DATABASE
 * ----------------------------------
 * The usual arrangement is a file on disk and a row pointing at it. That is two
 * writes to two systems, and making them atomic takes real effort that is
 * usually not made. Every failure in between produces something wrong: a row
 * naming a file that was never written, a file nobody has a row for, or bytes
 * left behind by a transaction that rolled back. None of it is visible until
 * somebody clicks download and gets an error.
 *
 * Inside the database the upload is part of the same transaction as the rest of
 * the operation. It commits with it or disappears with it, a backup captures the
 * coursework rather than a set of dangling names, and there is no reconciliation
 * job to write and then forget to run.
 *
 * The cost is real and worth stating: binaries make the database larger and
 * backups slower. At ten megabytes per assignment that is a good trade. At video
 * scale it would not be, and the honest answer there is object storage with a
 * deliberate consistency job - not this, quietly scaled up until it hurts.
 *
 * A SEPARATE TABLE, NOT COLUMNS ON submission
 * Listing a class reads thirty submissions and none of their documents. Keeping
 * the bytes in their own table means the common query never touches them, and
 * the association is lazy so nothing loads a PDF by accident.
 */
@Entity
@Table(name = "submission_file")
@Checks({
        // PDF only, at the storage layer. The service ALSO inspects the leading
        // bytes of the upload, because a declared content type is supplied by
        // the client and trivially forged. This constraint is what stops a
        // direct INSERT registering an executable as coursework.
        @Check(name = "ck_submission_file_pdf", constraints = "content_type = 'application/pdf'"),

        // An empty file is not an upload; ten megabytes is the ceiling.
        @Check(name = "ck_submission_file_size", constraints = "size_bytes > 0 AND size_bytes <= 10485760"),

        @Check(name = "ck_submission_file_name", constraints = "LTRIM(RTRIM(filename)) <> ''")
})
public class SubmissionFile {

    /** The largest upload accepted, in bytes. Mirrored by ck_submission_file_size. */
    public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /** The only content type this system stores. Mirrored by ck_submission_file_pdf. */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The submission this belongs to. Unique, so a submission has at most one
     * current file.
     *
     * Re-uploading REPLACES rather than adding. That is what a student means by
     * "I picked the wrong file", and keeping every attempt would make "which one
     * is being marked?" ambiguous at exactly the moment it matters most.
     */
    @NotNull
    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "submission_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_submission_file_submission"))
    @JsonIgnore
    private Submission submission;

    /**
     * The name as the student's browser reported it, kept so the teacher
     * downloads something recognisable rather than "document.pdf".
     *
     * Sanitised by the service before it gets here: a browser-supplied filename
     * is untrusted input, and one containing path separators is how a download
     * ends up written somewhere it should not be.
     */
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String filename;

    @NotBlank
    @Size(max = 100)
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /**
     * Stored explicitly rather than derived from the array length on read.
     * The constraint needs a column to check, and a size that can be queried
     * without loading ten megabytes to answer "how big is it?".
     */
    @NotNull
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /**
     * SHA-256 of the content, as 64 lowercase hex characters.
     *
     * Stored so corruption is DETECTABLE. Without it, a truncated or altered
     * upload is indistinguishable from a good one, and the first person to find
     * out is the teacher who cannot open the file - by which time the student
     * has no way to prove what they sent. It also makes an accidental re-upload
     * of the identical document recognisable as such.
     */
    @NotBlank
    @Size(min = 64, max = 64)
    @Column(nullable = false, length = 64)
    private String sha256;

    /**
     * The document itself.
     *
     * LAZY so that reading a submission does not read its PDF, and @JsonIgnore
     * so it can never be serialised into a JSON response by accident. The only
     * route to these bytes is the download endpoint, which streams them with the
     * correct content type after checking who is asking.
     */
    @NotNull
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    @JsonIgnore
    private byte[] content;

    @NotNull
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Version
    @JsonIgnore
    private Long version;

    protected SubmissionFile() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public SubmissionFile(Submission submission, String filename, String contentType,
                          byte[] content, String sha256, Instant uploadedAt) {
        this.submission = submission;
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
        this.sizeBytes = content == null ? null : (long) content.length;
        this.sha256 = sha256;
        this.uploadedAt = uploadedAt;
    }

    public Long getId() {
        return id;
    }

    public Submission getSubmission() {
        return submission;
    }

    /**
     * WHY THESE THREE GETTERS ARE @NonNull
     * ------------------------------------
     * filename, contentType and content are each `@Column(nullable = false)`
     * and validated with `@NotBlank`/`@NotNull` at construction - there is no
     * constructor and no setter that can leave any of them empty on a persisted
     * row. That is a real guarantee the schema and the constructor both hold,
     * and stating it here is what lets SubmissionService.download hand these
     * straight to FileDownload's own @NonNull components without a cast, a
     * null check, or a suppression at the boundary between the two.
     */
    @NonNull
    public String getFilename() {
        return filename;
    }

    @NonNull
    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    @NonNull
    public byte[] getContent() {
        return content;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Long getVersion() {
        return version;
    }
}
