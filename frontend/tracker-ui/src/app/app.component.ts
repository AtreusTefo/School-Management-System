import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Assignment, AssignmentService, CurrentUser
} from './assignment.service';
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
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
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

  // The list we display
  assignments: Assignment[] = [];

  // Create form
  newTitle = '';
  newDueDate = '';
  newAssignTo = '';

  // Inline edit state: the id being edited, or null
  editingId: number | null = null;
  editTitle = '';
  editDueDate = '';

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
        this.loadAssignments();
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

  // ----- authentication ------------------------------------------------------

  onLogin(): void {
    const username = this.loginUsername.trim();
    if (!username || !this.loginPassword) {
      return;
    }
    this.service.login(username, this.loginPassword).subscribe({
      next: (user) => {
        this.user = user;
        this.loginPassword = '';
        this.errorMessage = null;
        this.loadAssignments();
      },
      // Handled separately rather than through showError: a 401 here means the
      // credentials were wrong, NOT that a session expired. Routing it through
      // the generic handler produced "Your session has ended, please sign in
      // again" on the login screen, which is both confusing and untrue.
      error: (err: unknown) => {
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
        this.errorMessage = null;
      },
      error: (err) => this.showError(err, 'Could not sign out')
    });
  }

  // ----- assignments ---------------------------------------------------------

  loadAssignments(): void {
    this.service.getAssignments().subscribe({
      next: (data) => {
        this.assignments = data;
        this.errorMessage = null;
      },
      error: (err) => this.showError(err, 'Could not load assignments')
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

  startEdit(a: Assignment): void {
    this.editingId = a.id;
    this.editTitle = a.title;
    this.editDueDate = a.dueDate ?? '';
  }

  cancelEdit(): void {
    this.editingId = null;
  }

  saveEdit(): void {
    if (this.editingId === null) {
      return;
    }
    const title = this.editTitle.trim();
    if (!title) {
      return;
    }
    this.service.updateAssignment(this.editingId, title, this.editDueDate || null)
      .subscribe({
        next: () => {
          this.editingId = null;
          this.loadAssignments();
        },
        error: (err) => this.showError(err, 'Could not save the change')
      });
  }

  /**
   * Only a teacher may edit or delete - the server enforces this too.
   *
   * Not owner-based: a teacher who sets work FOR a student would otherwise be
   * unable to correct it, and a student would be able to rewrite the assignment
   * they had been set. Editing follows the role, not the row.
   */
  canModify(_a: Assignment): boolean {
    return this.isTeacher;
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
