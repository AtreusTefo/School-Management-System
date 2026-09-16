import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  Conversation, Correspondent, Message, MessagingService
} from './messaging.service';
import { NotificationService } from './notification.service';
import { SessionService } from './session.service';
import { FieldErrorComponent } from './field-error.component';
import { MESSAGE_BODY_MAX_LENGTH } from './validation';

/**
 * '/messages' - conversations, and composing a new one.
 *
 * TWO PANES, ONE SELECTION. The left is who you have spoken to; the right is
 * that conversation. Picking somebody from the compose list simply selects
 * them, whether or not a conversation already exists - which means "reply" and
 * "start a new conversation" are the same action and there is no separate
 * compose screen to keep in step.
 *
 * WHO YOU MAY WRITE TO IS THE SERVER'S ANSWER, NOT THIS PAGE'S. The
 * correspondents list comes from the same predicate that refuses a send (see
 * MessagingService.listCorrespondents on the backend). This component does not
 * filter it further and must not start to: a second copy of that rule here
 * would drift, and the visible symptom would be a form offering somebody the
 * send path then refuses - or worse, hiding somebody a student is entitled to
 * reach.
 *
 * MARKING READ IS DRIVEN BY OPENING A CONVERSATION, not by a button. Opening
 * is what reading is. Only messages the caller RECEIVED are marked - the
 * server refuses to mark a sent message read, which is what keeps "have they
 * seen it" honest.
 */
@Component({
  selector: 'app-messages-page',
  standalone: true,
  imports: [CommonModule, FormsModule, FieldErrorComponent],
  templateUrl: './messages-page.component.html'
})
export class MessagesPageComponent implements OnInit {

  conversations: Conversation[] = [];
  correspondents: Correspondent[] = [];
  thread: Message[] = [];

  selectedId: number | null = null;
  selectedName = '';

  draft = '';
  composing = false;
  sending = false;

  loading = true;
  loadingThread = false;

  readonly bodyMaxLength = MESSAGE_BODY_MAX_LENGTH;

  constructor(
    private messaging: MessagingService,
    private notifications: NotificationService,
    public session: SessionService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.reload());
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.messaging.conversations().subscribe({
      next: (data) => {
        this.conversations = data;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load your conversations');
      }
    });

    this.messaging.correspondents().subscribe({
      next: (data) => this.correspondents = data,
      error: (err) => this.notifications.showError(err, 'Could not load who you can message')
    });

    this.messaging.refreshUnread().subscribe({
      error: (err) => this.notifications.showError(err, 'Could not count unread messages')
    });
  }

  select(personId: number, name: string): void {
    this.selectedId = personId;
    this.selectedName = name;
    this.composing = false;
    this.openThread();
  }

  private openThread(): void {
    if (this.selectedId === null) {
      return;
    }
    this.loadingThread = true;
    this.messaging.conversationWith(this.selectedId).subscribe({
      next: (data) => {
        this.thread = data;
        this.loadingThread = false;
        this.markIncomingRead();
      },
      error: (err) => {
        this.loadingThread = false;
        this.notifications.showError(err, 'Could not load that conversation');
      }
    });
  }

  /**
   * Mark everything the caller RECEIVED in this thread as read.
   *
   * One request per unread message rather than a bulk endpoint, because there
   * is no bulk endpoint and inventing one to save a handful of requests on a
   * conversation somebody is actively reading would be optimising the wrong
   * thing. Each is idempotent, so a repeat costs nothing.
   */
  private markIncomingRead(): void {
    const me = this.session.user()?.username;
    for (const message of this.thread) {
      if (!message.read && message.recipientUsername === me) {
        this.messaging.markRead(message.id).subscribe({
          next: () => {
            message.read = true;
            this.refreshConversationCounts();
          },
          error: (err) =>
            this.notifications.showError(err, 'Could not mark that message as read')
        });
      }
    }
  }

  private refreshConversationCounts(): void {
    const current = this.conversations.find(c => c.personId === this.selectedId);
    if (current) {
      current.unread = 0;
    }
  }

  startCompose(): void {
    this.composing = true;
    this.selectedId = null;
    this.thread = [];
    this.draft = '';
  }

  chooseRecipient(person: Correspondent): void {
    this.selectedId = person.id;
    this.selectedName = person.fullName || person.username;
    this.composing = false;
    this.openThread();
  }

  send(): void {
    if (this.selectedId === null || !this.draft.trim() || this.sending) {
      return;
    }
    this.sending = true;
    const body = this.draft.trim();

    this.messaging.send(this.selectedId, body).subscribe({
      next: (sent) => {
        this.sending = false;
        this.draft = '';
        // Appended rather than refetched: the server has returned the row it
        // stored, so re-reading the thread would only confirm what we have.
        this.thread = [...this.thread, sent];
        this.refreshConversationList();
      },
      error: (err) => {
        this.sending = false;
        this.notifications.showError(err, 'Could not send that message');
      }
    });
  }

  /** The conversation list carries a preview and an order, so it is re-read. */
  private refreshConversationList(): void {
    this.messaging.conversations().subscribe({
      next: (data) => this.conversations = data,
      error: (err) => this.notifications.showError(err, 'Could not refresh your conversations')
    });
  }

  isMine(message: Message): boolean {
    return message.senderUsername === this.session.user()?.username;
  }

  get totalUnread(): number {
    return this.conversations.reduce((sum, c) => sum + c.unread, 0);
  }

  /** People with no conversation yet, so the compose list is not all repeats. */
  get newCorrespondents(): Correspondent[] {
    const known = new Set(this.conversations.map(c => c.personId));
    return this.correspondents.filter(p => !known.has(p.id));
  }
}
