import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Assignment, AssignmentService } from './assignment.service';

/**
 * THE COMPONENT
 * -------------
 * The controller of the UI. It asks the service for data, stores it, and
 * gives the template something to display. It reacts to user actions (clicks).
 */
@Component({
  selector: 'app-root',
  standalone: true,                 // modern Angular: no NgModule needed
  imports: [CommonModule, FormsModule], // FormsModule enables [(ngModel)] on the form
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {

  // The list we display. Starts empty and fills once data arrives.
  assignments: Assignment[] = [];

  // Bound to the text box in the "create" form via [(ngModel)].
  newTitle = '';

  /**
   * The last error message to show the user, or null when all is well.
   *
   * Previously every subscribe() passed only a success callback, so a failed
   * request did NOTHING visible — the button click just appeared to be ignored.
   * Holding the message here lets the template surface it.
   */
  errorMessage: string | null = null;

  // Angular injects our data service here.
  constructor(private service: AssignmentService) {}

  /**
   * ngOnInit runs once when the component first loads.
   * We use it to fetch the initial list — a good place for "load data on open".
   */
  ngOnInit(): void {
    this.loadAssignments();
  }

  /** Ask the service for the list and store the response. */
  loadAssignments(): void {
    // subscribe() takes TWO callbacks: one for data, one for failure.
    // Supplying both is what stops errors disappearing silently.
    this.service.getAssignments().subscribe({
      next: (data) => {
        this.assignments = data;
        this.errorMessage = null;
      },
      error: (err) => this.showError(err, 'Could not load assignments')
    });
  }

  /** Called when the user submits the "Add assignment" form. */
  onCreate(): void {
    // A small front-end guard so we don't send an obviously empty title.
    // (The backend validates too — never trust the client alone.)
    const title = this.newTitle.trim();
    if (!title) {
      return;
    }

    this.service.createAssignment(title).subscribe({
      next: () => {
        this.newTitle = '';       // clear the input box
        this.loadAssignments();   // refresh the table so the new row appears
      },
      error: (err) => this.showError(err, 'Could not create the assignment')
    });
  }

  /** Called when the user clicks a "Submit" button in the template. */
  onSubmit(id: number): void {
    this.service.submitAssignment(id).subscribe({
      next: () => {
        // After submitting, reload the list so the UI shows the new status.
        this.loadAssignments();
      },
      error: (err) => this.showError(err, 'Could not submit the assignment')
    });
  }

  /** Dismiss the error banner. */
  clearError(): void {
    this.errorMessage = null;
  }

  /**
   * Retry after a failure: clear the banner and fetch the list again.
   *
   * WHY THIS EXISTS
   * ---------------
   * The list is fetched exactly once, when the page opens. If the backend
   * happens to be down at that moment - most commonly because it is mid-restart -
   * the table stays empty FOREVER, even after the backend comes back. The only
   * recovery was a full page refresh, which nothing on the page suggested.
   *
   * A Retry button turns that dead end into a one-click recovery. loadAssignments()
   * already clears the error on success and re-raises it on failure, so this stays
   * honest if the backend is still down.
   */
  retry(): void {
    this.errorMessage = null;
    this.loadAssignments();
  }

  /**
   * Turn a failed HTTP call into one sentence a human can read.
   *
   * The backend's GlobalExceptionHandler sends a JSON body shaped like
   * { status, error, message, path }, so when there IS a message we show it —
   * that's far more useful than a status number. Two cases need care:
   *   - status 0 means the request never reached the server at all,
   *     which has SEVERAL possible causes (see below).
   *   - otherwise fall back to the caller's generic description.
   */
  private showError(err: unknown, fallback: string): void {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 0) {
        // status 0 means the browser never got a response. It does NOT prove the
        // backend is down — the request may also have been blocked before it left
        // the browser (a CORS rejection looks identical from here, and so does a
        // proxy or security tool intercepting loopback traffic).
        //
        // The previous wording asserted "Is the backend running?" as though that
        // were the only explanation, which sent a reader hunting the wrong problem
        // when the server was demonstrably up. List the real candidates instead,
        // and point at the console, which is the only place the true reason shows.
        this.errorMessage =
          `${fallback}: no response from the API at http://localhost:8080. ` +
          `The backend may be stopped, or the browser may have blocked the request ` +
          `(CORS, or a proxy intercepting localhost). Check the browser console — ` +
          `it names the actual cause.`;
        return;
      }
      const serverMessage = err.error?.message;
      this.errorMessage = serverMessage
        ? `${fallback}: ${serverMessage}`
        : `${fallback} (HTTP ${err.status}).`;
      return;
    }
    this.errorMessage = fallback + '.';
  }
}
