import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AppNotification, NotificationFeedService } from './notification-feed.service';
import { NotificationService } from './notification.service';

/**
 * '/notifications' - everything the system has told the caller.
 *
 * READ-ONLY EXCEPT FOR "READ". There is no delete. A notification is a record
 * of what somebody was told, and letting a student clear one would remove the
 * only evidence they were informed - see Notification's class comment on why
 * the body is a snapshot for the same reason. Marking it read is the only
 * state this page changes.
 *
 * THE LINK MAY POINT AT NOTHING, BY DESIGN. subjectType/subjectId are not a
 * foreign key on the server, so the record a notification names can be
 * corrected or removed after the fact. This page therefore offers a link only
 * for subjects it knows a route for, and the title and body carry the meaning
 * on their own - the link is a convenience, never the content.
 */
@Component({
  selector: 'app-notifications-page',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './notifications-page.component.html'
})
export class NotificationsPageComponent implements OnInit {

  notifications: AppNotification[] = [];
  loading = true;
  showUnreadOnly = false;

  constructor(
    private feed: NotificationFeedService,
    private notifications_: NotificationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.notifications_.setRetryHandler(() => this.reload());
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.feed.list(this.showUnreadOnly).subscribe({
      next: (data) => {
        this.notifications = data;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.notifications_.showError(err, 'Could not load your notifications');
      }
    });
    this.feed.refreshUnread().subscribe({
      error: (err) => this.notifications_.showError(err, 'Could not count unread notifications')
    });
  }

  toggleFilter(): void {
    this.showUnreadOnly = !this.showUnreadOnly;
    this.reload();
  }

  markRead(n: AppNotification): void {
    if (n.read) {
      return;
    }
    this.feed.markRead(n.id).subscribe({
      next: (updated) => {
        n.read = updated.read;
        // Marking the last unread one read while filtered would leave it on
        // screen contradicting the filter, so the list is re-read instead.
        if (this.showUnreadOnly) {
          this.reload();
        }
      },
      error: (err) => this.notifications_.showError(err, 'Could not mark that as read')
    });
  }

  markAllRead(): void {
    this.feed.markAllRead().subscribe({
      next: () => this.reload(),
      error: (err) => this.notifications_.showError(err, 'Could not mark everything as read')
    });
  }

  get unreadCount(): number {
    return this.notifications.filter(n => !n.read).length;
  }

  /** A short label for the kind of thing this is. */
  kindLabel(n: AppNotification): string {
    switch (n.type) {
      case 'DUE_SOON':         return 'Due soon';
      case 'MARK_POSTED':      return 'New mark';
      case 'ABSENCE_RECORDED': return 'Attendance';
      case 'MESSAGE_RECEIVED': return 'Message';
      default:                 return 'Notice';
    }
  }

  /**
   * Where this notification points, or null when there is nowhere useful.
   *
   * Deliberately a route this app HAS. An unrecognised subject type returns
   * null and the row simply carries no link, which is the correct behaviour
   * for a subject that has since been deleted as well.
   */
  linkFor(n: AppNotification): string | null {
    switch (n.subjectType) {
      case 'ASSESSMENT': return '/reports';
      case 'ATTENDANCE': return '/attendance';
      case 'SUBMISSION': return '/marking-queue';
      case 'MESSAGE':    return '/messages';
      default:           return null;
    }
  }

  open(n: AppNotification): void {
    this.markRead(n);
    const target = this.linkFor(n);
    if (target) {
      this.router.navigateByUrl(target);
    }
  }
}
