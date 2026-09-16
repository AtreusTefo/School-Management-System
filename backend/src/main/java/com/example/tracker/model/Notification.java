package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * One thing the system decided somebody should be told.
 *
 * RAISED, NOT WRITTEN. Nobody is the author. A Message has a sender who can be
 * replied to and held to what they said; this has a cause - a mark was
 * recorded, a register marked somebody absent, a due date came close - and the
 * only sensible response to it is to read it. That difference is why the two
 * are separate tables and not one table with a nullable sender.
 *
 * THE BODY IS A SNAPSHOT, AND THAT IS WHAT MAKES subject_id SAFE
 * -------------------------------------------------------------
 * {@link #subjectType}/{@link #subjectId} say what this is about so the
 * interface can offer a link. They are deliberately NOT a foreign key - the
 * second and last such deviation in this schema, after
 * {@code audit_log.performed_by_id}, and for the same reason. A live reference
 * would mean that telling a student their mark was posted made that mark
 * undeletable; a notification must never be the reason a real record cannot be
 * corrected.
 *
 * What makes that safe is that this row does not need its subject to remain
 * meaningful. {@link #title} and {@link #body} were written when it was raised
 * and are never recomputed, exactly as {@code AuditLog.summary} is. The
 * notification stays readable and true after whatever it names is gone.
 *
 * This is the one place in this system where text is deliberately DUPLICATED
 * rather than derived, so it is worth being explicit about why that does not
 * contradict "nothing derived is stored". A percentage is derived because the
 * marks it comes from are still there and still authoritative; recomputing it
 * is how a correction reaches every total. A notification is a record of what
 * somebody was TOLD, at a moment. Recomputing it later would not correct it -
 * it would rewrite history, and produce a notification claiming to have said
 * something it never said.
 *
 * ONE PER RECIPIENT PER EVENT
 * ---------------------------
 * {@link #dedupeKey} names the event - {@code MARK:41}, {@code DUE_SOON:512} -
 * and {@code uq_notification_recipient_event} makes it unique per recipient.
 * The job that raises DUE_SOON runs on a timer with no memory of its own; this
 * constraint, not that job's cleverness, is what stops a student being told the
 * same thing every time it wakes up. See NotificationService for the
 * check-then-insert that keeps the common case out of the constraint's way.
 */
@Entity
@Table(
        name = "notification",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_recipient_event",
                columnNames = {"recipient_id", "dedupe_key"}))
@Checks({
        @Check(name = "ck_notification_type",
                constraints = "type IN ('DUE_SOON','MARK_POSTED','ABSENCE_RECORDED',"
                        + "'MESSAGE_RECEIVED')"),
        @Check(name = "ck_notification_subject_paired",
                constraints = "(subject_type IS NULL AND subject_id IS NULL) "
                        + "OR (subject_type IS NOT NULL AND subject_id IS NOT NULL)")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_notification_recipient_only"))
    private AppUser recipient;

    /**
     * Pinned to the recipient's real role by a composite foreign key in V18.
     * Unlike V4 and V14 this carries no CHECK, because every role may be
     * notified - there is no role to restrict to. The foreign key is still
     * what makes the value true.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_role", nullable = false, length = 20)
    @JsonIgnore
    private Role recipientRole;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @NotBlank
    @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String title;

    @NotBlank
    @Size(max = 500)
    @Column(nullable = false, length = 500)
    private String body;

    /** Never a foreign key - see the class comment. Null together with the id. */
    @Size(max = 30)
    @Column(name = "subject_type", length = 30)
    private String subjectType;

    @Column(name = "subject_id")
    private Long subjectId;

    @NotBlank
    @Size(max = 120)
    @Column(name = "dedupe_key", nullable = false, length = 120)
    @JsonIgnore
    private String dedupeKey;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Null until opened. The presence of a time IS the read state - there is no
     * separate boolean that could disagree with it, and "when" is strictly more
     * information than "whether" for the same storage.
     */
    @Column(name = "read_at")
    private Instant readAt;

    @Version
    @JsonIgnore
    private Long version;

    protected Notification() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Notification(AppUser recipient, NotificationType type, String title, String body,
                        String subjectType, Long subjectId, String dedupeKey) {
        if (recipient == null) {
            throw new IllegalArgumentException("A notification needs somebody to notify.");
        }
        if (type == null) {
            throw new IllegalArgumentException("A notification needs a type.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A notification needs a title.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A notification needs a body.");
        }
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("A notification needs the event it is about.");
        }
        /*
         * Rejected here as well as by ck_notification_subject_paired, because
         * half a reference is a programming mistake rather than bad input and
         * the caller should meet it at construction rather than as a constraint
         * violation three layers later.
         */
        if ((subjectType == null) != (subjectId == null)) {
            throw new IllegalArgumentException(
                    "A notification's subject is a type and an id together, or neither.");
        }
        this.recipient = recipient;
        this.recipientRole = recipient.getRole();
        this.type = type;
        this.title = title.trim();
        this.body = body.trim();
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.dedupeKey = dedupeKey.trim();
        this.createdAt = Instant.now();
    }

    /**
     * Mark this as read, once.
     *
     * Deliberately not a setter: reading something twice does not un-read it,
     * and the second call must not move the time - which would make "when did
     * they first see this" unanswerable. Idempotent by construction rather
     * than by every caller remembering to check first.
     */
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public AppUser getRecipient() {
        AppUser value = recipient;
        if (value == null) {
            throw new IllegalStateException("Notification " + id + " has no recipient.");
        }
        return value;
    }

    @NonNull
    public NotificationType getType() {
        NotificationType value = type;
        if (value == null) {
            throw new IllegalStateException("Notification " + id + " has no type.");
        }
        return value;
    }

    @NonNull
    public String getTitle() {
        String value = title;
        if (value == null) {
            throw new IllegalStateException("Notification " + id + " has no title.");
        }
        return value;
    }

    @NonNull
    public String getBody() {
        String value = body;
        if (value == null) {
            throw new IllegalStateException("Notification " + id + " has no body.");
        }
        return value;
    }

    @Nullable
    public String getSubjectType() {
        return subjectType;
    }

    @Nullable
    public Long getSubjectId() {
        return subjectId;
    }

    @NonNull
    public String getDedupeKey() {
        String value = dedupeKey;
        if (value == null) {
            throw new IllegalStateException("Notification " + id + " has no event key.");
        }
        return value;
    }

    @NonNull
    public Instant getCreatedAt() {
        Instant value = createdAt;
        if (value == null) {
            throw new IllegalStateException("Notification " + id + " has no creation time.");
        }
        return value;
    }

    @Nullable
    public Instant getReadAt() {
        return readAt;
    }

    public boolean isRead() {
        return readAt != null;
    }
}
