import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../environments/environment';

/** One message, as the API publishes it. Both ends are named - see MessageView. */
export interface Message {
  id: number;
  senderId: number;
  senderUsername: string;
  senderRole: string;
  recipientId: number;
  recipientUsername: string;
  recipientRole: string;
  body: string;
  sentAt: string;
  read: boolean;
}

/** One person the caller has corresponded with. Derived on read, never stored. */
export interface Conversation {
  personId: number;
  username: string;
  role: string;
  fullName: string;
  lastMessage: string;
  lastMessageAt: string;
  unread: number;
}

/**
 * Somebody the caller is ALLOWED to write to.
 *
 * This list comes from the server and must not be second-guessed here: it is
 * produced by the same predicate that refuses a send, so a client-side filter
 * on top of it would be a second definition of the rule and would eventually
 * disagree. See MessagingService.listCorrespondents on the backend.
 */
export interface Correspondent {
  id: number;
  username: string;
  role: string;
  fullName: string;
  context: string;
}

@Injectable({ providedIn: 'root' })
export class MessagingService {

  private readonly base = `${environment.apiBaseUrl}/api/messages`;

  /** Unread messages, for the badge - see NotificationFeedService.unread. */
  readonly unread = signal(0);

  constructor(private http: HttpClient) {}

  conversations(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>(`${this.base}/conversations`,
      { withCredentials: true });
  }

  conversationWith(personId: number): Observable<Message[]> {
    return this.http.get<Message[]>(`${this.base}/conversations/${personId}`,
      { withCredentials: true });
  }

  correspondents(): Observable<Correspondent[]> {
    return this.http.get<Correspondent[]>(`${this.base}/correspondents`,
      { withCredentials: true });
  }

  inbox(): Observable<Message[]> {
    return this.http.get<Message[]>(`${this.base}/inbox`, { withCredentials: true });
  }

  sent(): Observable<Message[]> {
    return this.http.get<Message[]>(`${this.base}/sent`, { withCredentials: true });
  }

  send(recipientId: number, body: string): Observable<Message> {
    return this.http.post<Message>(this.base, { recipientId, body },
      { withCredentials: true });
  }

  markRead(id: number): Observable<Message> {
    return this.http.put<Message>(`${this.base}/${id}/read`, {},
      { withCredentials: true })
      .pipe(tap(() => this.unread.update(n => Math.max(0, n - 1))));
  }

  refreshUnread(): Observable<{ unread: number }> {
    return this.http.get<{ unread: number }>(`${this.base}/unread-count`,
      { withCredentials: true })
      .pipe(tap(r => this.unread.set(r.unread)));
  }

  reset(): void {
    this.unread.set(0);
  }
}
