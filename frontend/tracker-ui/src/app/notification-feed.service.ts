import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../environments/environment';

/**
 * NOTE THE NAME. This is NOT NotificationService.
 *
 * `notification.service.ts` in this same folder is THE ERROR BANNER - the
 * thing that shows a failed request to the user - and it has been called that
 * since long before this feature existed. Two services called
 * NotificationService, one raising alerts about marks and one reporting HTTP
 * failures, would be confused by every future reader and silently mis-imported
 * by at least one. This is the feed of notifications the SERVER raised; that
 * one is the banner. Neither name is changed, because renaming the banner
 * would touch every page in the app to fix an ambiguity a clear name here
 * removes for free.
 */

/** Mirrors NotificationType on the server. */
export type NotificationKind =
  'DUE_SOON' | 'MARK_POSTED' | 'ABSENCE_RECORDED' | 'MESSAGE_RECEIVED';

/**
 * One notification.
 *
 * `subjectType`/`subjectId` are a link to whatever this is about, and are
 * deliberately NOT a foreign key on the server - see Notification's class
 * comment. So this client must treat a subject that no longer exists as a
 * link to nothing rather than an error: `title` and `body` are a snapshot and
 * stay readable whether the subject survives or not.
 */
export interface AppNotification {
  id: number;
  type: NotificationKind;
  title: string;
  body: string;
  subjectType: string | null;
  subjectId: number | null;
  createdAt: string;
  read: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationFeedService {

  private readonly base = `${environment.apiBaseUrl}/api/notifications`;

  /**
   * The number on the bell, as a signal.
   *
   * Held here rather than in the shell component because the bell is drawn by
   * the shell but CHANGED by the notifications page - marking everything read
   * has to empty the badge without either component reaching into the other.
   * A signal in the service is the one place both can read.
   */
  readonly unread = signal(0);

  constructor(private http: HttpClient) {}

  list(unreadOnly = false): Observable<AppNotification[]> {
    return this.http.get<AppNotification[]>(
      `${this.base}?unreadOnly=${unreadOnly}`, { withCredentials: true });
  }

  /** Refreshes the badge. Callers do not need the value; the signal carries it. */
  refreshUnread(): Observable<{ unread: number }> {
    return this.http.get<{ unread: number }>(
      `${this.base}/unread-count`, { withCredentials: true })
      .pipe(tap(r => this.unread.set(r.unread)));
  }

  markRead(id: number): Observable<AppNotification> {
    return this.http.put<AppNotification>(`${this.base}/${id}/read`, {},
      { withCredentials: true })
      // Decrement rather than refetch: the server has already decided, and a
      // second round trip to learn a number we can compute is a slower way to
      // show the same answer.
      .pipe(tap(() => this.unread.update(n => Math.max(0, n - 1))));
  }

  markAllRead(): Observable<{ marked: number }> {
    return this.http.put<{ marked: number }>(`${this.base}/read-all`, {},
      { withCredentials: true })
      .pipe(tap(() => this.unread.set(0)));
  }

  /** Clears the badge on sign-out, so the next account never inherits it. */
  reset(): void {
    this.unread.set(0);
  }
}
