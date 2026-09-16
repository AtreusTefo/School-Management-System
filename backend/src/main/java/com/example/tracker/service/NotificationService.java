package com.example.tracker.service;

import com.example.tracker.dto.NotificationView;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Notification;
import com.example.tracker.model.NotificationType;
import com.example.tracker.repository.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RAISING AND READING NOTIFICATIONS.
 *
 * This service has two audiences and they barely overlap. {@link #raise} is
 * called by OTHER SERVICES as part of their own transactions - AssessmentService
 * when a mark is recorded, AttendanceService when somebody is marked absent,
 * DueDateReminderJob on a timer. The read methods are called by the person the
 * notifications belong to, and are scoped to them absolutely.
 *
 * WHY raise() TAKES A RECIPIENT AND NOT "THE CURRENT USER"
 * -------------------------------------------------------
 * Every other read in this system is about the caller. This is the one that is
 * deliberately not: a teacher recording a mark causes a notification for the
 * STUDENT. Passing the recipient explicitly is what keeps that obvious at
 * every call site, and it is why raise() performs no permission check of its
 * own - it is not reachable from a controller, and the caller has already
 * decided both that the event happened and who it concerns.
 *
 * WHY THERE IS BOTH AN existsBy CHECK AND A UNIQUE CONSTRAINT
 * ----------------------------------------------------------
 * uq_notification_recipient_event is the guarantee: one notification per
 * recipient per event, true of the data whatever wrote it. The check in
 * raise() is not a second implementation of that rule - it is what keeps the
 * ordinary case from ever reaching it.
 *
 * That distinction is not stylistic here, it is required. Letting the
 * constraint fire inside a transaction throws DataIntegrityViolationException,
 * and JPA marks the whole transaction rollback-only when it does - so a
 * duplicate notification would roll back the MARK that caused it. A student
 * would lose a recorded score because the system tried to tell them about it
 * twice. Checking first means the common case never touches the constraint,
 * and the constraint is left to catch the genuine race between two callers,
 * where losing that one transaction and retrying is correct.
 *
 * The scheduled job depends on exactly this: it re-reads the same work every
 * run and calls raise() for all of it, and everything already notified is
 * skipped here rather than remembered anywhere.
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {

    /**
     * A page big enough that the notifications screen is never truncated in
     * practice, and bounded so a long-lived account cannot ask the server to
     * materialise years of rows in one response.
     */
    private static final int MAX_PAGE = 200;

    private final NotificationRepository notifications;
    private final AppUserService users;

    public NotificationService(NotificationRepository notifications, AppUserService users) {
        this.notifications = notifications;
        this.users = users;
    }

    // =====================================================================
    // RAISING - called by other services, never by a controller
    // =====================================================================

    /**
     * Tell {@code recipient} about {@code dedupeKey}, unless they have already
     * been told about it.
     *
     * Returns true when a notification was actually raised, so a caller can
     * count what it did - the scheduled job reports this. Silently doing
     * nothing on a repeat is the point, not a failure.
     *
     * Runs in the CALLER'S transaction, deliberately. A notification about a
     * mark that was rolled back should not survive, and REQUIRES_NEW here
     * would leave exactly that: a student told about a score that no longer
     * exists. The two facts belong to one business operation, so they belong
     * to one transaction.
     */
    @Transactional
    public boolean raise(AppUser recipient, NotificationType type, String title, String body,
                         String subjectType, Long subjectId, String dedupeKey) {
        if (recipient == null) {
            throw new IllegalArgumentException("A notification needs somebody to notify.");
        }
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("A notification needs the event it is about.");
        }
        if (notifications.existsByRecipientAndDedupeKey(recipient, dedupeKey)) {
            return false;
        }
        notifications.save(new Notification(
                recipient, type, title, body, subjectType, subjectId, dedupeKey));
        return true;
    }

    // =====================================================================
    // READING - always and only the caller's own
    // =====================================================================

    /**
     * The caller's notifications, newest first.
     *
     * There is no "notifications of user X" method here, and that is
     * deliberate: unlike attendance - where staff genuinely need to look at a
     * student's record - a notification is nobody's business but its
     * recipient's. Not writing the method is a stronger guarantee than writing
     * one and guarding it.
     */
    public List<NotificationView> listMine(boolean unreadOnly) {
        AppUser me = users.currentActiveUser();
        List<Notification> rows = unreadOnly
                ? notifications.findByRecipientAndReadAtIsNullOrderByCreatedAtDescIdDesc(me)
                : notifications.findByRecipientOrderByCreatedAtDescIdDesc(
                        me, PageRequest.of(0, MAX_PAGE)).getContent();
        return rows.stream().map(NotificationView::of).toList();
    }

    /** The number on the bell. */
    public long unreadCount() {
        return notifications.countByRecipientAndReadAtIsNull(users.currentActiveUser());
    }

    /** Mark one as read. Idempotent - see Notification.markRead. */
    @Transactional
    public NotificationView markRead(Long id) {
        Notification notification = requireOwn(id);
        notification.markRead();
        return NotificationView.of(notifications.save(notification));
    }

    /**
     * Mark everything unread as read.
     *
     * Loads and marks rather than issuing a bulk UPDATE, because each row's
     * read time is its own fact and @Version is what keeps this from silently
     * overwriting a concurrent change. The set is bounded by what is unread,
     * which is the same handful of rows the badge counts.
     */
    @Transactional
    public int markAllRead() {
        AppUser me = users.currentActiveUser();
        List<Notification> unread =
                notifications.findByRecipientAndReadAtIsNullOrderByCreatedAtDescIdDesc(me);
        // A plain loop, not unread.forEach(Notification::markRead) - the same
        // unannotated-JDK-generic problem as MessagingService.fullNameOf, here
        // through List.forEach's Consumer. The loop needs no annotation.
        for (Notification notification : unread) {
            notification.markRead();
        }
        notifications.saveAll(unread);
        return unread.size();
    }

    /**
     * The caller's own notification, or a 404.
     *
     * DELIBERATELY 404 AND NOT 403 for somebody else's. A 403 would confirm
     * that notification 87 exists and belongs to someone - which, on a table
     * whose rows say things like "your mark for Mathematics was posted", is
     * itself worth not leaking. To a caller it is not there, because as far as
     * they are concerned it is not.
     */
    @NonNull
    private Notification requireOwn(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Notification id must not be null.");
        }
        AppUser me = users.currentActiveUser();
        Notification notification = Require.orThrow(notifications.findById(id),
                () -> new ResourceNotFoundException("No notification found with id = " + id));
        if (!notification.getRecipient().getId().equals(me.getId())) {
            throw new ResourceNotFoundException("No notification found with id = " + id);
        }
        return notification;
    }
}
