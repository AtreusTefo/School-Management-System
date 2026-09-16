package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;

import java.util.List;

/**
 * DATA ACCESS for messages. Who may write to whom is MessagingService's, and
 * cannot be expressed here - see Message's own class comment.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * The inbox, newest first - served by ix_message_recipient_sent.
     *
     * Annotated non-null for the reason SubmissionRepository states once: a
     * collection-returning query method never returns null, and saying so is
     * what lets these feed the stream API without every caller reporting an
     * unchecked conversion. (Written out rather than starting the line with
     * the annotation's name, which Javadoc would read as a block tag.)
     */
    @NonNull
    List<Message> findByRecipientOrderBySentAtDescIdDesc(AppUser recipient);

    /** What this account has sent - served by ix_message_sender_sent. */
    @NonNull
    List<Message> findBySenderOrderBySentAtDescIdDesc(AppUser sender);

    /** The badge, counted the same way notifications are. */
    long countByRecipientAndReadAtIsNull(AppUser recipient);

    /**
     * One conversation: every message between two people, oldest first, which
     * is the order a conversation is read in.
     *
     * BOTH DIRECTIONS IN ONE QUERY, deliberately. Reading each direction
     * separately and merging in Java would work, but it would mean two result
     * sets ordered independently and re-sorted by a comparator that has to
     * agree with the database's own ordering - a second place for "which
     * message came first" to be decided, and eventually to be decided
     * differently. There is no thread id to key this on by design; see
     * Message's class comment.
     */
    @Query("""
            SELECT m FROM Message m
            WHERE (m.sender = :me      AND m.recipient = :them)
               OR (m.sender = :them    AND m.recipient = :me)
            ORDER BY m.sentAt ASC, m.id ASC
            """)
    @NonNull
    List<Message> conversation(@Param("me") AppUser me, @Param("them") AppUser them);

    /** Whether these two have ever corresponded - used to list conversations. */
    boolean existsBySenderAndRecipient(AppUser sender, AppUser recipient);

    /**
     * Everything an account is a party to, for account deletion.
     *
     * Both directions in one statement, because a message has two ends and
     * leaving the other half behind would strand a conversation pointing at an
     * account that no longer exists - and fk_message_sender does not cascade,
     * so it would simply make the delete fail.
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.sender = :account OR m.recipient = :account")
    void deleteAllInvolving(@Param("account") AppUser account);
}
