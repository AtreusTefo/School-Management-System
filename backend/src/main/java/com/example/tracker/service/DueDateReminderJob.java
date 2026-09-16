package com.example.tracker.service;

import com.example.tracker.model.Assignment;
import com.example.tracker.model.NotificationType;
import com.example.tracker.model.Submission;
import com.example.tracker.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * "YOUR WORK IS DUE SOON" - the one notification nothing a person does causes.
 *
 * A mark being posted and a register being taken are events: somebody acts,
 * and the notification is raised in that same transaction. A due date
 * approaching is not an event at all. Nothing happens; a date simply gets
 * closer. So this is the only notification in the system that needs something
 * to go looking, and that is what makes it the one with a duplication problem.
 *
 * WHY THIS JOB HAS NO MEMORY, AND DOES NOT NEED ONE
 * ------------------------------------------------
 * The obvious implementations both go wrong. Recording "last run at" and only
 * looking at work since then loses everything if a run fails, and double-sends
 * if the clock moves. Adding a "reminded" flag to Submission puts a
 * notification concern inside an academic record, and leaves that flag to be
 * maintained by every other path that touches a submission.
 *
 * Instead this re-reads the same window every time and asks
 * NotificationService to raise one per submission. Everything already
 * notified is refused by uq_notification_recipient_event, keyed on
 * DUE_SOON:{submissionId}. The job may run every ten minutes, twice at once
 * after a restart, or having missed a day entirely, and a student is still
 * told exactly once per piece of work. The guarantee is in the schema, not in
 * this class's control flow, which is the only place it can be trusted.
 *
 * ONE TRANSACTION PER SUBMISSION, deliberately. A single transaction over the
 * whole sweep would mean one unexpected failure - a student deleted mid-run,
 * a lock timeout - discarding every reminder raised before it, and the next
 * run would re-raise them all. Per-submission means a failure costs exactly
 * that one reminder, which the next run picks up because nothing was written
 * for it.
 *
 * THE WINDOW IS A LEAD TIME, NOT A SCHEDULE. Work due within the next
 * {@code noticeDays} days and not yet handed in gets one reminder. Work
 * already overdue is deliberately outside the window: the student can see it
 * is late on their own page, and a reminder about a date that has passed is
 * not notice, it is nagging.
 */
@Component
public class DueDateReminderJob {

    private static final Logger log = LoggerFactory.getLogger(DueDateReminderJob.class);

    private final SubmissionRepository submissions;
    private final NotificationService notifications;

    /**
     * How much notice a student gets. A property rather than a constant because
     * it is a school's policy decision, not a fact about the software.
     */
    @Value("${tracker.reminders.notice-days:3}")
    private int noticeDays = 3;

    public DueDateReminderJob(SubmissionRepository submissions,
                              NotificationService notifications) {
        this.submissions = submissions;
        this.notifications = notifications;
    }

    /**
     * Runs on a timer, and can be called directly by a test.
     *
     * Returns how many reminders were actually raised - not how many
     * submissions were examined - so a test can assert that a second run over
     * the same data raises nothing, which is the property that matters here.
     *
     * The fixed delay is measured from the END of the previous run, so a slow
     * sweep can never overlap itself on one instance. Two INSTANCES would
     * overlap, and that is fine: the unique constraint is what settles it, and
     * the loser of that race simply loses one reminder that the next run
     * raises.
     */
    @Scheduled(fixedDelayString = "${tracker.reminders.interval-ms:900000}",
               initialDelayString = "${tracker.reminders.initial-delay-ms:60000}")
    public int sendDueSoonReminders() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(Math.max(0, noticeDays));

        List<Submission> dueSoon = submissions.findDueBetweenAndNotHandedIn(today, horizon);
        int raised = 0;
        for (Submission submission : dueSoon) {
            try {
                if (remind(submission)) {
                    raised++;
                }
            } catch (RuntimeException ex) {
                /*
                 * One bad submission must not stop the sweep. Logged with the
                 * id so it can be found, and deliberately not rethrown - the
                 * next run will try it again, and nothing was written for it.
                 */
                log.warn("Could not raise a due-date reminder for submission {}: {}",
                        submission.getId(), ex.getMessage());
            }
        }
        if (raised > 0) {
            log.info("Raised {} due-date reminder(s) over {} submission(s) due by {}.",
                    raised, dueSoon.size(), horizon);
        }
        return raised;
    }

    /**
     * One reminder, in its own transaction - see the class comment.
     *
     * Package-private and non-final so Spring can proxy it; called through the
     * loop above rather than from outside.
     */
    @Transactional
    boolean remind(Submission submission) {
        Assignment assignment = submission.getAssignment();
        LocalDate due = assignment.getDueDate();
        long days = ChronoUnit.DAYS.between(LocalDate.now(), due);

        String when = switch ((int) Math.max(days, 0)) {
            case 0 -> "today";
            case 1 -> "tomorrow";
            default -> "in " + days + " days";
        };

        return notifications.raise(submission.getStudent(), NotificationType.DUE_SOON,
                "Due " + when + ": " + assignment.getTitle(),
                "'" + assignment.getTitle() + "' for "
                        + assignment.getCourse().getSubject().getName()
                        + " is due on " + due + " and you have not handed it in yet.",
                "SUBMISSION", submission.getId(),
                "DUE_SOON:" + submission.getId());
    }
}
