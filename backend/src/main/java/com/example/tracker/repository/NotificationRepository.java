package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;

import java.util.List;

/**
 * DATA ACCESS for notifications. No rules here - who may read one, and what is
 * worth raising, are NotificationService's.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Somebody's notifications, newest first - served by
     * ix_notification_recipient_created.
     *
     * WHY THE COLLECTION RETURNS HERE ARE @NonNull: see SubmissionRepository,
     * which states the reasoning once. A Spring Data query method with a
     * collection return type never returns null, and saying so is what lets
     * these results be handed straight to saveAll() and the stream API, both
     * of which require non-null. Left unstated, markAllRead() reported "needs
     * unchecked conversion" for a value that cannot be null.
     */
    @NonNull
    Page<Notification> findByRecipientOrderByCreatedAtDescIdDesc(AppUser recipient, Pageable page);

    /** The unread ones only, for the "mark all read" path and for tests. */
    @NonNull
    List<Notification> findByRecipientAndReadAtIsNullOrderByCreatedAtDescIdDesc(AppUser recipient);

    /**
     * The badge in the app bar. Served by ix_notification_unread, which is
     * filtered to unread rows, so this counts a handful of rows rather than
     * every notification the account has ever received.
     */
    long countByRecipientAndReadAtIsNull(AppUser recipient);

    /**
     * The idempotency check - see NotificationService.raise for why this exists
     * alongside uq_notification_recipient_event rather than instead of it.
     */
    boolean existsByRecipientAndDedupeKey(AppUser recipient, String dedupeKey);

    /**
     * Everything belonging to an account that is being removed.
     *
     * A bulk delete rather than loading and deleting one at a time: this runs
     * inside AdminService's account-deletion transaction, where the rows are
     * about to cease existing and nothing needs them in the persistence
     * context on the way out.
     */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.recipient = :account")
    void deleteByRecipient(@Param("account") AppUser account);
}
