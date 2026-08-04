import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Assignment, AssignmentService, CurrentUser
} from './assignment.service';
import {
  AssignmentEdit, AssignmentTableComponent
} from './assignment-table.component';
import { environment } from '../environments/environment';

/**
 * THE COMPONENT
 * -------------
 * The controller of the UI. It asks the service for data, stores it, and gives
 * the template something to display. It reacts to user actions (clicks).
 *
 * It shows or hides controls by role, but it does NOT enforce anything. Every
 * rule it reflects is also enforced by the server - hiding a button is a
 * courtesy to the user, not a security measure, because anyone can call the API
 * directly.
 *
 * The table itself is not drawn here. AssignmentTableComponent hands the rows to
 * DataTables and lets it own that piece of the page; this component supplies the
 * data and reacts to what the user asked to do with a row. Every call to the API
 * still goes through AssignmentService, exactly as before.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, AssignmentTableComponent],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {

  /** Who is signed in, or null when nobody is. Drives login screen vs list. */
  user: CurrentUser | null = null;

  /** True until the first "who am I?" answer arrives, so we do not flash the login form. */
  checkingSession = true;

  // Sign-in form
  loginUsername = '';
  loginPassword = '';

  /**
   * True while a request is in flight, so the interface can say so.
   *
   * Kept as separate flags rather than one shared "busy", because each blocks a
   * different part of the screen: loadingList replaces the table, signingIn
   * disables the sign-in button, and checkingSession above holds back the whole
   * page. A single flag would make signing in blank out the list, and a slow
   * refresh disable a form the user was not waiting on.
   */
  loadingList = false;
  signingIn = false;

  /** Everything the server returned. */
  assignments: Assignment[] = [];

  /**
   * The subset handed to the table, after the status filter.
   *
   * Free-text search is NOT applied here - DataTables owns that, and doing it in
   * both places would mean two search boxes fighting over the same rows.
   *
   * Held as a field and reassigned by applyFilters() rather than exposed as a
   * getter. A getter returns a new array on every change-detection pass, which
   * would look like new data to the table component and trigger a needless
   * redraw several times a second.
   */
  visibleAssignments: Assignment[] = [];

  /** Status filter. A client-side view of data already loaded. */
  statusFilter: 'ALL' | 'IN_PROGRESS' | 'SUBMITTED' | 'OVERDUE' = 'ALL';

  // Create form
  newTitle = '';
  newDueDate = '';
  newAssignTo = '';

  // ----- account self-service (EPIC-09) --------------------------------------

  /** Change-password form. */
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  changingPassword = false;
  passwordNotice: string | null = null;

  /**
   * True when the user opened the change-password form themselves, as opposed to
   * being sent there because their account is pending.
   *
   * Kept separate from user.mustChangePassword so the form can be dismissed in
   * the voluntary case and cannot be in the forced one.
   */
  showPasswordForm = false;

  /** Create-account form (teachers only). */
  newUsername = '';
  newUserPassword = '';
  creatingUser = false;
  showCreateUser = false;
  createUserNotice: string | null = null;

  // Inline edit state now lives in AssignmentTableComponent, because the row
  // being edited is part of the table DataTables draws. This component only sees
  // the finished result, through the (saveAssignment) event.

  /** The last error to show the user, or null when all is well. */
  errorMessage: string | null = null;

  constructor(private service: AssignmentService) {}

  /**
   * On startup: get a CSRF token, then ask whether we are already signed in.
   *
   * A 401 from /me is the normal "not signed in" answer, not a failure worth
   * showing - so it is swallowed deliberately and only unexpected errors reach
   * the banner.
   */
  ngOnInit(): void {
    this.service.primeCsrf().subscribe({
      next: () => this.checkSession(),
      error: () => this.checkSession()
    });
  }

  private checkSession(): void {
    this.service.me().subscribe({
      next: (user) => {
        this.user = user;
        this.checkingSession = false;
        // An account pending a password change may not read the list - the
        // server answers 403. Asking anyway would put a red banner on top of a
        // screen that is already explaining what to do.
        if (!user.mustChangePassword) {
          this.loadAssignments();
        }
      },
      error: () => {
        this.user = null;
        this.checkingSession = false;
      }
    });
  }

  get isTeacher(): boolean {
    return this.user?.role === 'TEACHER';
  }

  // ----- presentation helpers -------------------------------------------------
  // These exist only to keep the template readable. Nothing here decides
  // anything; they turn values the server sent into something displayable.

  /** The letter shown in the avatar circle. */
  get userInitial(): string {
    return this.user ? this.user.username.charAt(0).toUpperCase() : '?';
  }

  get submittedCount(): number {
    return this.assignments.filter(a => a.status === 'SUBMITTED').length;
  }

  get inProgressCount(): number {
    return this.assignments.filter(a => a.status === 'IN_PROGRESS').length;
  }

  get overdueCount(): number {
    return this.assignments.filter(a => a.overdue).length;
  }

  // ----- filtering ------------------------------------------------------------

  setStatusFilter(filter: typeof this.statusFilter): void {
    this.statusFilter = filter;
    this.applyFilters();
  }

  /**
   * Narrow the loaded list down to the chosen status.
   *
   * This filters data already in the browser; it does not ask the server for a
   * different set. That keeps the endpoint's meaning intact - the API still
   * decides what this person is ALLOWED to see, and the filter only decides what
   * they are currently LOOKING at. A filter must never be mistaken for access
   * control.
   *
   * A NEW array is assigned rather than the existing one being mutated, because
   * the table component compares its @Input() by reference: mutating in place
   * would change the data with nothing to tell Angular it had happened.
   */
  private applyFilters(): void {
    this.visibleAssignments = this.assignments.filter(a =>
      this.statusFilter === 'ALL' ? true :
      this.statusFilter === 'OVERDUE' ? a.overdue :
      a.status === this.statusFilter
    );
  }

  // ----- authentication ------------------------------------------------------

  onLogin(): void {
    const username = this.loginUsername.trim();
    if (!username || !this.loginPassword) {
      return;
    }
    this.signingIn = true;
    this.service.login(username, this.loginPassword).subscribe({
      next: (user) => {
        this.signingIn = false;
        this.user = user;
        this.loginPassword = '';
        this.errorMessage = null;
        this.passwordNotice = null;
        // Same reason as checkSession: a pending account gets the change-password
        // screen, not a list request that is going to be refused.
        if (!user.mustChangePassword) {
          this.loadAssignments();
        }
      },
      // Handled separately rather than through showError: a 401 here means the
      // credentials were wrong, NOT that a session expired. Routing it through
      // the generic handler produced "Your session has ended, please sign in
      // again" on the login screen, which is both confusing and untrue.
      error: (err: unknown) => {
        this.signingIn = false;
        const status = (err as { status?: number })?.status;
        this.errorMessage = status === 401
          ? 'Invalid username or password.'
          : null;
        if (this.errorMessage === null) {
          this.showError(err, 'Could not sign in');
        }
      }
    });
  }

  onLogout(): void {
    this.service.logout().subscribe({
      next: () => {
        this.user = null;
        this.assignments = [];
        this.visibleAssignments = [];
        this.statusFilter = 'ALL';
        this.errorMessage = null;
        // Clear the account forms too. Leaving a half-typed password in a field
        // for the next person to sign in at this browser would be careless.
        this.showPasswordForm = false;
        this.showCreateUser = false;
        this.passwordNotice = null;
        this.createUserNotice = null;
        this.resetPasswordFields();
      },
      error: (err) => this.showError(err, 'Could not sign out')
    });
  }

  // ----- account self-service (EPIC-09) --------------------------------------

  /**
   * True when the account cannot do anything until its password is replaced.
   *
   * The list is not even requested in this state - not to enforce anything, but
   * because the server would refuse it with a 403, and a red banner on a screen
   * that is already telling the user what to do would only be noise.
   */
  get mustChangePassword(): boolean {
    return this.user?.mustChangePassword === true;
  }

  /** Whether to render the change-password form at all. */
  get passwordFormVisible(): boolean {
    return this.mustChangePassword || this.showPasswordForm;
  }

  openPasswordForm(): void {
    this.showPasswordForm = true;
    this.passwordNotice = null;
    this.resetPasswordFields();
  }

  /** Only reachable when the change is voluntary; a forced one has no way out. */
  closePasswordForm(): void {
    this.showPasswordForm = false;
    this.resetPasswordFields();
  }

  private resetPasswordFields(): void {
    this.currentPassword = '';
    this.newPassword = '';
    this.confirmPassword = '';
  }

  /**
   * Client-side check that the two new-password fields agree.
   *
   * The server never sees `confirmPassword` and has no opinion on it - it is a
   * typing check, not a rule. Everything the server does enforce (length, that
   * the current password matches, that the new one differs) is checked there and
   * surfaces as a normal error.
   */
  get passwordsMatch(): boolean {
    return this.newPassword === this.confirmPassword;
  }

  get canSubmitPassword(): boolean {
    return !this.changingPassword
        && this.currentPassword.length > 0
        && this.newPassword.length >= 8
        && this.passwordsMatch;
  }

  onChangePassword(): void {
    if (!this.canSubmitPassword) {
      return;
    }
    this.changingPassword = true;
    this.service.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: (user) => {
        this.changingPassword = false;
        const wasForced = this.mustChangePassword;
        // Take the server's word for the new state rather than assuming the flag
        // cleared - it is the server that decides when the account is usable.
        this.user = user;
        this.showPasswordForm = false;
        this.resetPasswordFields();
        this.errorMessage = null;
        this.passwordNotice = 'Your password has been changed.';
        // Coming out of the forced state, the list has never been loaded.
        if (wasForced) {
          this.loadAssignments();
        }
      },
      error: (err) => {
        this.changingPassword = false;
        this.showError(err, 'Could not change your password');
      }
    });
  }

  // ----- creating an account (teachers only) ---------------------------------

  openCreateUser(): void {
    this.showCreateUser = true;
    this.createUserNotice = null;
    this.newUsername = '';
    this.newUserPassword = '';
  }

  closeCreateUser(): void {
    this.showCreateUser = false;
  }

  get canCreateUser(): boolean {
    return !this.creatingUser
        && this.newUsername.trim().length > 0
        && this.newUserPassword.length >= 8;
  }

  onCreateUser(): void {
    if (!this.canCreateUser) {
      return;
    }
    this.creatingUser = true;
    const username = this.newUsername.trim();
    this.service.createStudent(username, this.newUserPassword).subscribe({
      next: () => {
        this.creatingUser = false;
        this.createUserNotice =
          `Account "${username}" created. They must change this password when they first sign in.`;
        this.newUsername = '';
        this.newUserPassword = '';
        this.errorMessage = null;
      },
      error: (err) => {
        this.creatingUser = false;
        this.showError(err, 'Could not create the account');
      }
    });
  }

  // ----- assignments ---------------------------------------------------------

  loadAssignments(): void {
    this.loadingList = true;
    this.service.getAssignments().subscribe({
      next: (data) => {
        this.loadingList = false;
        this.assignments = data;
        // Re-apply the current status filter to the new data, so a refresh does
        // not silently reset the view the user had set up.
        this.applyFilters();
        this.errorMessage = null;
      },
      error: (err) => {
        this.loadingList = false;
        this.showError(err, 'Could not load assignments');
      }
    });
  }

  onCreate(): void {
    const title = this.newTitle.trim();
    if (!title) {
      return;
    }
    this.service.createAssignment(
      title,
      this.newDueDate || null,
      this.newAssignTo.trim() || null
    ).subscribe({
      next: () => {
        this.newTitle = '';
        this.newDueDate = '';
        this.newAssignTo = '';
        this.loadAssignments();
      },
      error: (err) => this.showError(err, 'Could not create the assignment')
    });
  }

  onSubmit(id: number): void {
    this.service.submitAssignment(id).subscribe({
      next: () => this.loadAssignments(),
      error: (err) => this.showError(err, 'Could not submit the assignment')
    });
  }

  onUnsubmit(id: number): void {
    this.service.unsubmitAssignment(id).subscribe({
      next: () => this.loadAssignments(),
      error: (err) => this.showError(err, 'Could not reopen the assignment')
    });
  }

  onDelete(a: Assignment): void {
    if (!confirm(`Delete "${a.title}"? This cannot be undone.`)) {
      return;
    }
    this.service.deleteAssignment(a.id).subscribe({
      next: () => this.loadAssignments(),
      error: (err) => this.showError(err, 'Could not delete the assignment')
    });
  }

  // ----- inline editing ------------------------------------------------------

  /**
   * The table component collected the new title and due date; this saves them.
   *
   * Only a teacher may edit at all - the table only draws the Edit button for
   * one, and the server refuses anyone else regardless. That rule follows the
   * role rather than the row: a teacher who sets work FOR a student would
   * otherwise be unable to correct it, and a student would be able to rewrite
   * the assignment they had been set.
   */
  onSaveEdit(edit: AssignmentEdit): void {
    this.service.updateAssignment(edit.id, edit.title, edit.dueDate).subscribe({
      next: () => this.loadAssignments(),
      error: (err) => this.showError(err, 'Could not save the change')
    });
  }

  // ----- error handling ------------------------------------------------------

  clearError(): void {
    this.errorMessage = null;
  }

  retry(): void {
    this.errorMessage = null;
    this.loadAssignments();
  }

  /**
   * Turn a failed HTTP call into one sentence a human can read.
   *
   * The backend sends { status, error, message, path }, so when there IS a
   * message we show it - far more useful than a status number.
   */
  private showError(err: unknown, fallback: string): void {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 0) {
        // status 0 means the browser never got a response. It does NOT prove the
        // backend is down - a CORS rejection or an intercepted request looks
        // identical from here.
        const api = environment.apiBaseUrl || 'this site';
        this.errorMessage =
          `${fallback}: no response from the API at ${api}. ` +
          `The backend may be stopped, or the browser may have blocked the request ` +
          `(CORS, or a proxy intercepting localhost). Check the browser console — ` +
          `it names the actual cause.`;
        return;
      }
      if (err.status === 401) {
        // The session expired underneath us. Say so plainly and show the login
        // form again, rather than leaving a stale list on screen.
        this.user = null;
        this.assignments = [];
        this.visibleAssignments = [];
        this.errorMessage = 'Your session has ended. Please sign in again.';
        return;
      }
      const serverMessage = (err.error as { message?: string } | null)?.message;
      this.errorMessage = serverMessage
        ? `${fallback}: ${serverMessage}`
        : `${fallback} (HTTP ${err.status}).`;
      return;
    }
    this.errorMessage = fallback + '.';
  }
}
