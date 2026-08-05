import { Injectable, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { SessionService } from './session.service';
import { environment } from '../environments/environment';

/**
 * THE ONE ERROR BANNER, NOW SHARED ACROSS EVERY PAGE
 * -----------------------------------------------------
 * Before routing, `errorMessage` was a field on the single AppComponent and
 * every failed request in the app set it directly. Splitting the app into
 * pages means a failure can now originate in any one of five components, but
 * there is still exactly one banner - it lives in the shell, above
 * `<router-outlet>`, because an error should stay visible across a navigation
 * rather than vanishing the moment the page that raised it is torn down.
 *
 * A signal-backed service is what lets a PAGE component report a failure and
 * the SHELL display it, without either needing a reference to the other -
 * both just talk to this service.
 *
 * WHY Retry NEEDS A REGISTERED HANDLER RATHER THAN A FIXED ACTION
 * The old, single-page Retry button always meant the same thing: reload
 * everything. On a routed page it has to mean "reload THIS page's data",
 * and only the page currently mounted knows what that is. Each page
 * registers its own reload function on init and clears it on destroy, so
 * the shell's Retry button always calls whichever page is actually on
 * screen - never a stale action left behind by a page the user has since
 * navigated away from.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {

  private readonly messageSignal = signal<string | null>(null);
  readonly message = this.messageSignal.asReadonly();

  private retryHandler: (() => void) | null = null;

  constructor(private session: SessionService, private router: Router) {}

  /** Called by whichever page can usefully reload itself when Retry is pressed. */
  setRetryHandler(handler: (() => void) | null): void {
    this.retryHandler = handler;
  }

  retry(): void {
    this.clear();
    this.retryHandler?.();
  }

  clear(): void {
    this.messageSignal.set(null);
  }

  /**
   * Show a message that did not come from a failed HTTP call - the PDF-export
   * chunk failing to load is the current example. showError() below expects
   * an HttpErrorResponse to pick a message out of; forcing a plain failure
   * through that shape would either lose the real message or require
   * fabricating a fake HTTP error just to satisfy the type. This is the
   * honest alternative for "something failed, and here is what to say".
   */
  show(message: string): void {
    this.messageSignal.set(message);
  }

  /**
   * Turn a failed HTTP call into one sentence a human can read.
   *
   * The backend sends { status, error, message, path }, so when there IS a
   * message we show it - far more useful than a status number.
   */
  showError(err: unknown, fallback: string): void {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 0) {
        // status 0 means the browser never got a response. It does NOT prove
        // the backend is down - a CORS rejection or an intercepted request
        // looks identical from here.
        const api = environment.apiBaseUrl || 'this site';
        this.messageSignal.set(
          `${fallback}: no response from the API at ${api}. ` +
          `The backend may be stopped, or the browser may have blocked the request ` +
          `(CORS, or a proxy intercepting localhost). Check the browser console — ` +
          `it names the actual cause.`);
        return;
      }
      if (err.status === 401) {
        // The session expired underneath us. Say so plainly, clear it, and
        // send the user back to the root route - the shell there shows the
        // login form once SessionService.user() is null, and a routed page
        // is never left rendered with data for an account nobody is signed
        // in as any more.
        this.session.logout().subscribe({ error: () => {} });
        this.router.navigateByUrl('/');
        this.messageSignal.set('Your session has ended. Please sign in again.');
        return;
      }
      if (err.status === 413) {
        this.messageSignal.set(`${fallback}: that file is larger than the 10 MB limit.`);
        return;
      }
      const serverMessage = (err.error as { message?: string } | null)?.message;
      this.messageSignal.set(serverMessage ? `${fallback}: ${serverMessage}` : `${fallback} (HTTP ${err.status}).`);
      return;
    }
    this.messageSignal.set(fallback + '.');
  }
}
