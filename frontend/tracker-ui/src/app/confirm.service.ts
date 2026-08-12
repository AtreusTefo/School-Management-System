import { Injectable, signal } from '@angular/core';

/**
 * AN IN-APP CONFIRMATION DIALOG, REPLACING THE BROWSER'S OWN confirm()
 * -----------------------------------------------------------------------
 * Every "Delete this?" prompt in the admin panel used to call the native
 * `window.confirm()`. That has a real failure mode this project hit: Chrome
 * (and other browsers) offers "Prevent this page from creating additional
 * dialogs" after a page raises several of them in a row, and once a user
 * checks that box, EVERY LATER confirm() on that page returns false
 * SILENTLY - no dialog, no error, nothing on screen. A Delete button that
 * worked five times and then appears to stop responding entirely is exactly
 * that box having been ticked, and there is no way for this application to
 * detect it happened or warn the user.
 *
 * A dialog this application draws itself cannot be suppressed that way, and
 * it also stops looking like a jarring native popup dropped into an
 * otherwise designed interface - the same reasoning that already moved this
 * app off native alert() for errors, onto NotificationService's banner.
 *
 * WHY A PROMISE, NOT A CALLBACK
 * window.confirm() is synchronous: the calling code blocks until the user
 * answers. A component-rendered dialog cannot block a click handler that way
 * - the answer only exists once the user has clicked Confirm or Cancel on a
 * LATER change-detection cycle. A Promise is the natural async replacement:
 * every call site becomes `if (!await confirm.ask(...)) return;`, one word
 * different from the synchronous version it replaces.
 *
 * ONE DIALOG, SHARED, THE SAME PATTERN NotificationService ALREADY
 * ESTABLISHED - a signal-backed service lets any component ask a question
 * and the SHELL render the answer UI, without either needing a reference to
 * the other.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {

  private readonly messageSignal = signal<string | null>(null);
  readonly message = this.messageSignal.asReadonly();

  /**
   * The Promise this service handed back from ask() is resolved from here,
   * once the user actually answers. Null between prompts - and briefly, in
   * the impossible case of a second ask() arriving before the first was
   * answered, the resolver of the first prompt would be silently replaced.
   * That never happens in practice: every call site awaits ask() before
   * doing anything else that could trigger a second confirmation.
   */
  private resolver: ((confirmed: boolean) => void) | null = null;

  /** Show the dialog and resolve once the user answers either way. */
  ask(message: string): Promise<boolean> {
    this.messageSignal.set(message);
    return new Promise<boolean>(resolve => {
      this.resolver = resolve;
    });
  }

  confirm(): void {
    this.messageSignal.set(null);
    this.resolver?.(true);
    this.resolver = null;
  }

  cancel(): void {
    this.messageSignal.set(null);
    this.resolver?.(false);
    this.resolver = null;
  }
}
