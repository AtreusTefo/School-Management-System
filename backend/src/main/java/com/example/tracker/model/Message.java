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
 * One message, written by a person, to a person.
 *
 * FLAT, NOT THREADED, DELIBERATELY. There is no conversation table and no
 * parent-message column. A conversation between two people IS every message
 * where they are the two participants, read in time order, and
 * MessagingService derives it that way. A stored thread id would be a second
 * copy of a fact the sender and recipient columns already carry, and the first
 * time the two disagreed the thread would be the one that was wrong.
 *
 * WHO MAY WRITE TO WHOM IS NOT IN THIS CLASS, and cannot be. It depends on
 * Course and Enrolment - a student may write to the teachers who actually
 * teach them - and no CHECK constraint can read another table. It lives in
 * MessagingService.requireMayWriteTo, which says so at the point it enforces
 * it. This is the same category as the three attendance rules and the
 * no-overlapping-terms rule: enforced in Java because it genuinely cannot be
 * enforced anywhere else, and called out rather than left to be discovered.
 *
 * What the database DOES enforce is everything that does not need a second
 * table: that both ends are real accounts whose role columns match their real
 * roles (composite foreign keys, the V4 technique), that nobody writes to
 * themselves, that the body is not blank, and that a read time cannot precede
 * the send.
 *
 * CORRESPONDENCE IS AN ARRANGEMENT, NOT AN ACADEMIC RECORD. That places it on
 * the Enrolment side of this system's oldest distinction rather than the
 * Assessment side, which is why AdminService clears a departing account's
 * messages instead of refusing to delete the account. The audit log is what
 * outlives an account; a private conversation with somebody who no longer
 * exists is not a school record.
 */
@Entity
@Table(name = "message")
@Checks({
        @Check(name = "ck_message_not_self", constraints = "sender_id <> recipient_id"),
        @Check(name = "ck_message_read_after_sent",
                constraints = "read_at IS NULL OR read_at >= sent_at")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sender_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_message_sender_only"))
    private AppUser sender;

    /**
     * Pinned to the sender's real role by a composite foreign key in V18.
     * Carries no CHECK because every role may write to somebody; the foreign
     * key alone is what makes the value agree with the account.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 20)
    @JsonIgnore
    private Role senderRole;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_message_recipient_only"))
    private AppUser recipient;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_role", nullable = false, length = 20)
    @JsonIgnore
    private Role recipientRole;

    @NotBlank
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String body;

    @NotNull
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /** Null until the recipient opens it - see Notification for the same choice. */
    @Column(name = "read_at")
    private Instant readAt;

    @Version
    @JsonIgnore
    private Long version;

    protected Message() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Message(AppUser sender, AppUser recipient, String body) {
        if (sender == null) {
            throw new IllegalArgumentException("A message needs a sender.");
        }
        if (recipient == null) {
            throw new IllegalArgumentException("A message needs somebody to send it to.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A message needs something in it.");
        }
        /*
         * Rejected here as well as by ck_message_not_self. The constraint is
         * the guarantee; this is what turns it into a sentence, and it also
         * catches the case where both ids are still null because neither
         * account has been persisted yet - which the constraint cannot see.
         */
        if (sender.getId() != null && sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("You cannot send a message to yourself.");
        }
        this.sender = sender;
        this.senderRole = sender.getRole();
        this.recipient = recipient;
        this.recipientRole = recipient.getRole();
        this.body = body.trim();
        this.sentAt = Instant.now();
    }

    /** Idempotent, for the same reason {@code Notification.markRead()} is. */
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    @NonNull
    public AppUser getSender() {
        AppUser value = sender;
        if (value == null) {
            throw new IllegalStateException("Message " + id + " has no sender.");
        }
        return value;
    }

    @NonNull
    public AppUser getRecipient() {
        AppUser value = recipient;
        if (value == null) {
            throw new IllegalStateException("Message " + id + " has no recipient.");
        }
        return value;
    }

    @NonNull
    public String getBody() {
        String value = body;
        if (value == null) {
            throw new IllegalStateException("Message " + id + " has no body.");
        }
        return value;
    }

    @NonNull
    public Instant getSentAt() {
        Instant value = sentAt;
        if (value == null) {
            throw new IllegalStateException("Message " + id + " has no send time.");
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
