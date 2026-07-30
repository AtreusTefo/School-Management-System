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
   * The subset currently on screen, after the search box and the status filter.
   *
   * Held as a field and recomputed by applyFilters() rather than exposed as a
   * getter. A getter returning a new array runs on every change-detection pass
   * and hands *ngFor a fresh object each time, which makes Angular tear down and
   * rebuild rows that did not change.
   */
  visibleAssignments: Assignment[] = [];

  /** Search text and status filter. Both are client-side views of loaded data. */
  searchTerm = '';
  statusFilter: 'ALL' | 'IN_PROGRESS' | 'SUBMITTED' | 'OVERDUE' = 'ALL';

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

  /** True when a filter is hiding rows, so the empty state can say which. */
  get isFiltered(): boolean {
    return this.statusFilter !== 'ALL' || this.searchTerm.trim() !== '';
  }

  /** SUBMITTED reads as shouting in a table cell; the badge says "Submitted". */
  statusLabel(status: Assignment['status']): string {
    return status === 'SUBMITTED' ? 'Submitted' : 'In progress';
  }

  /**
   * Render an ISO date as "12 Aug 2026".
   *
   * Deliberately not `new Date(iso)`. A bare yyyy-MM-dd is parsed by the browser
   * as UTC midnight, so anyone west of Greenwich sees the previous day - a due
   * date of the 12th displayed as the 11th. Splitting the string keeps the date
   * exactly as the server meant it, with no timezone involved at all.
   */
  formatDate(iso: string | null): string {
    if (!iso) {
      return 'No due date';
    }
    const parts = iso.split('-');
    if (parts.length !== 3) {
      return iso;
    }
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const monthIndex = Number(parts[1]) - 1;
    const month = months[monthIndex] ?? parts[1];
    return `${Number(parts[2])} ${month} ${parts[0]}`;
  }

  /**
   * Identity for *ngFor. Without it Angular compares the objects themselves, and
   * because every refresh fetches new objects it would discard and rebuild every
   * row - losing focus and scroll position on each poll.
   */
  trackById(_index: number, a: Assignment): number {
    return a.id;
  }

  // ----- filtering ------------------------------------------------------------

  /** Called by the search box and the status buttons. */
  onFilterChange(): void {
    this.applyFilters();
  }

  setStatusFilter(filter: typeof this.statusFilter): void {
    this.statusFilter = filter;
    this.applyFilters();
  }

  clearFilters(): void {
    this.searchTerm = '';
    this.statusFilter = 'ALL';
    this.applyFilters();
  }

  /**
   * Narrow the loaded list down to what the user asked to see.
   *
   * This filters data already in the browser; it does not ask the server for a
   * different set. That keeps the endpoint's meaning intact - the API still
   * decides what this person is ALLOWED to see, and the filter only decides what
   * they are currently LOOKING at. A filter must never be mistaken for access
   * control.
   */
  private applyFilters(): void {
    const term = this.searchTerm.trim().toLowerCase();

    this.visibleAssignments = this.assignments.filter(a => {
      const matchesStatus =
        this.statusFilter === 'ALL' ? true :
        this.statusFilter === 'OVERDUE' ? a.overdue :
        a.status === this.statusFilter;

      const matchesTerm = term === '' ||
        a.title.toLowerCase().includes(term) ||
        a.ownerUsername.toLowerCase().includes(term);

      return matchesStatus && matchesTerm;
    });
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
        this.loadAssignments();
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
        this.clearFilters();
        this.errorMessage = null;
      },
      error: (err) => this.showError(err, 'Could not sign out')
    });
  }

  // ----- assignments ---------------------------------------------------------

  loadAssignments(): void {
    this.loadingList = true;
    this.service.getAssignments().subscribe({
      next: (data) => {
        this.loadingList = false;
        this.assignments = data;
        // Re-apply the current search and status filter to the new data, so a
        // refresh does not silently reset the view the user had set up.
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
