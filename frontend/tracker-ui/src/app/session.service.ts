import { Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AssignmentService, CurrentUser } from './assignment.service';

/**
 * WHO IS SIGNED IN, SHARED ACROSS EVERY ROUTED PAGE
 * ---------------------------------------------------
 * Before routing existed, AppComponent was the only component in the app, so
 * `user` living on it as a plain field was enough - every template that needed
 * it was the same template. Splitting the app into pages breaks that: a route
 * guard has to know who is signed in before ANY page component exists to ask,
 * and the shell (still AppComponent) needs the same answer the guards and
 * every page need, kept in exact agreement.
 *
 * A signal, not a plain field, because Angular's router and its guards run
 * outside any one component's change-detection cycle - a guard reads
 * `session.user()` synchronously when a navigation is attempted, and a signal
 * is what makes "the current value, right now" a well-defined question to ask
 * from outside a component at all.
 *
 * WHAT STAYS OUT OF THIS SERVICE
 * Only identity lives here - who is signed in, and the four account actions
 * that change or ask about identity. Course lists, submissions, marks and
 * every other kind of data stay owned by whichever PAGE loads them, each
 * through AssignmentService directly. A "load everything into one shared
 * store" service would mean every page paid for data it does not use, and
 * would put page-specific loading logic somewhere no single page owns it.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {

  private readonly userSignal = signal<CurrentUser | null>(null);
  private readonly checkingSignal = signal(true);

  /** Read-only outside this service - nothing may set the user except signing in or out. */
  readonly user = this.userSignal.asReadonly();

  /** True until the first "who am I?" answer arrives, so the shell does not flash a login form. */
  readonly checkingSession = this.checkingSignal.asReadonly();

  constructor(private service: AssignmentService) {}

  get isTeacher(): boolean {
    return this.userSignal()?.role === 'TEACHER';
  }

  /**
   * True when the account cannot do anything until its password is replaced.
   * The guards treat this the same as "not signed in" for every routed page -
   * see auth.guard.ts.
   */
  get mustChangePassword(): boolean {
    return this.userSignal()?.mustChangePassword === true;
  }

  /**
   * Prime the CSRF cookie, then ask whether a session already exists.
   *
   * A 401 from /me is the ordinary "not signed in" answer, not a failure - so
   * it resolves normally with the user left null rather than erroring, and
   * the shell decides what to show from `user()` alone.
   */
  init(): Observable<CurrentUser | null> {
    return new Observable<CurrentUser | null>((subscriber) => {
      this.service.primeCsrf().subscribe({
        next: () => this.checkSession(subscriber),
        error: () => this.checkSession(subscriber)
      });
    });
  }

  private checkSession(subscriber: { next: (u: CurrentUser | null) => void; complete: () => void }): void {
    this.service.me().subscribe({
      next: (user) => {
        this.userSignal.set(user);
        this.checkingSignal.set(false);
        subscriber.next(user);
        subscriber.complete();
      },
      error: () => {
        this.userSignal.set(null);
        this.checkingSignal.set(false);
        subscriber.next(null);
        subscriber.complete();
      }
    });
  }

  login(username: string, password: string): Observable<CurrentUser> {
    return this.service.login(username, password).pipe(
      tap((user) => this.userSignal.set(user))
    );
  }

  logout(): Observable<void> {
    return this.service.logout().pipe(
      tap(() => this.userSignal.set(null))
    );
  }

  changePassword(currentPassword: string, newPassword: string): Observable<CurrentUser> {
    return this.service.changePassword(currentPassword, newPassword).pipe(
      // Take the server's word for the new state rather than assuming the
      // pending flag cleared - it is the server that decides when an account
      // stops being pending.
      tap((user) => this.userSignal.set(user))
    );
  }

  createStudent(username: string, temporaryPassword: string): Observable<CurrentUser> {
    return this.service.createStudent(username, temporaryPassword);
  }
}
