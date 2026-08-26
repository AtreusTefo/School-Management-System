import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AssignmentService, Course } from './assignment.service';
import {
  DAYS_OF_WEEK, Term, TimetableApiService, TimetablePeriod, TimetableSlot
} from './timetable.service';
import { NotificationService } from './notification.service';
import { ConfirmService } from './confirm.service';
import { FieldErrorComponent } from './field-error.component';

/**
 * '/admin/schedule' - TERMS, PERIODS, AND WHEN EACH COURSE IS TAUGHT.
 *
 * A SEPARATE PAGE FROM '/admin/timetable', DELIBERATELY
 * -----------------------------------------------------
 * That page answers "who teaches what to whom" - subjects, classes, courses,
 * enrolment. This one answers "and when". They are different jobs done at
 * different moments: a school defines its subjects and classes once and
 * reshuffles its timetable every term. Bolting these three tables onto a page
 * that is already 332 lines of template would also have produced a page nobody
 * could scan.
 *
 * WHY THE GRID IS BUILT HERE RATHER THAN FETCHED PER CELL
 * The week is one request for the whole term, turned into a lookup keyed by
 * day and period in the browser. Fetching per cell would be forty requests for
 * one screen, and - worse - forty answers that could disagree with each other
 * if somebody edited the timetable while they were in flight.
 */
@Component({
  selector: 'app-admin-schedule-page',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './admin-schedule-page.component.html'
})
export class AdminSchedulePageComponent implements OnInit {

  readonly days = DAYS_OF_WEEK;

  terms: Term[] = [];
  periods: TimetablePeriod[] = [];
  courses: Course[] = [];
  slots: TimetableSlot[] = [];

  /** Which term the grid below is showing. */
  selectedTermId: number | null = null;

  notice: string | null = null;

  // ----- new term -----
  newTermName = '';
  newTermStart = '';
  newTermEnd = '';

  // ----- new period -----
  newPeriodName = '';
  newPeriodSequence: number | null = null;
  newPeriodStart = '';
  newPeriodEnd = '';

  // ----- schedule a lesson -----
  slotCourseId: number | null = null;
  slotPeriodId: number | null = null;
  slotDay = '';

  /**
   * Which row is being edited, one field per kind.
   *
   * A term id and a period id are both plain numbers, so a single shared
   * "editingId" would open whichever period happened to share a number with
   * the term being edited - the same trap admin-timetable-page documents.
   */
  editingTermId: number | null = null;
  editTermName = '';
  editTermStart = '';
  editTermEnd = '';

  editingPeriodId: number | null = null;
  editPeriodName = '';
  editPeriodSequence: number | null = null;
  editPeriodStart = '';
  editPeriodEnd = '';

  constructor(
    private timetable: TimetableApiService,
    private school: AssignmentService,
    private notifications: NotificationService,
    private confirmDialog: ConfirmService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.reload());
    this.reload();
  }

  reload(): void {
    this.timetable.getTerms().subscribe({
      next: (data) => {
        this.terms = data;
        // Open on the term that is actually running, which is the one an admin
        // almost always means. Falling back to the first keeps the grid useful
        // out of season rather than showing nothing at all.
        if (this.selectedTermId === null && data.length > 0) {
          this.selectedTermId = (data.find(t => t.current) ?? data[0]).id;
          this.loadGrid();
        }
      },
      error: (err) => this.notifications.showError(err, 'Could not load terms')
    });
    this.timetable.getPeriods().subscribe({
      next: (data) => this.periods = data,
      error: (err) => this.notifications.showError(err, 'Could not load periods')
    });
    this.school.getAllCourses().subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.notifications.showError(err, 'Could not load courses')
    });
  }

  loadGrid(): void {
    if (this.selectedTermId === null) {
      this.slots = [];
      return;
    }
    this.timetable.getTimetable(this.selectedTermId).subscribe({
      next: (data) => this.slots = data,
      error: (err) => this.notifications.showError(err, 'Could not load the timetable')
    });
  }

  onTermChange(): void {
    this.loadGrid();
  }

  // ===== the grid ==========================================================

  /**
   * The lesson in one cell, or null.
   *
   * Linear over the term's slots rather than a precomputed map. A term holds a
   * few hundred rows at most and this runs per cell during change detection,
   * which sounds like the wrong trade until you consider the alternative: a map
   * rebuilt on every mutation, with one more thing to forget to rebuild. If a
   * timetable ever grows large enough for this to matter, the fix is to key a
   * Map once in loadGrid - not to guess now.
   */
  slotAt(day: string, periodId: number): TimetableSlot | null {
    return this.slots.find(s => s.dayOfWeek === day && s.periodId === periodId) ?? null;
  }

  /** "08:00:00" as "08:00" - seconds are noise on a timetable. */
  shortTime(time: string): string {
    return time ? time.substring(0, 5) : '';
  }

  /** "MONDAY" as "Monday". */
  dayLabel(day: string): string {
    return day.charAt(0) + day.substring(1).toLowerCase();
  }

  // ===== terms =============================================================

  createTerm(): void {
    if (!this.newTermName.trim() || !this.newTermStart || !this.newTermEnd) {
      return;
    }
    this.timetable.createTerm(this.newTermName.trim(), this.newTermStart, this.newTermEnd)
      .subscribe({
        next: (term) => {
          this.notice = `Term "${term.name}" added.`;
          this.newTermName = '';
          this.newTermStart = '';
          this.newTermEnd = '';
          this.reload();
        },
        error: (err) => this.notifications.showError(err, 'Could not add the term')
      });
  }

  startEditTerm(term: Term): void {
    this.editingTermId = term.id;
    this.editTermName = term.name;
    this.editTermStart = term.startDate;
    this.editTermEnd = term.endDate;
  }

  cancelEditTerm(): void {
    this.editingTermId = null;
  }

  saveTerm(term: Term): void {
    if (!this.editTermName.trim() || !this.editTermStart || !this.editTermEnd) {
      return;
    }
    this.timetable.updateTerm(
      term.id, this.editTermName.trim(), this.editTermStart, this.editTermEnd).subscribe({
      next: () => {
        this.notice = 'Term updated.';
        this.editingTermId = null;
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not update the term')
    });
  }

  async deleteTerm(term: Term): Promise<void> {
    // ConfirmService, never window.confirm() - see its own header for the
    // silent-failure mode that replaced.
    if (!await this.confirmDialog.ask(
      `Delete "${term.name}"? This cannot be undone.`)) {
      return;
    }
    this.timetable.deleteTerm(term.id).subscribe({
      next: () => {
        this.notice = `Term "${term.name}" deleted.`;
        if (this.selectedTermId === term.id) {
          this.selectedTermId = null;
          this.slots = [];
        }
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete the term')
    });
  }

  // ===== periods ===========================================================

  createPeriod(): void {
    if (!this.newPeriodName.trim() || this.newPeriodSequence === null
        || !this.newPeriodStart || !this.newPeriodEnd) {
      return;
    }
    this.timetable.createPeriod(
      this.newPeriodName.trim(), this.newPeriodSequence,
      this.newPeriodStart, this.newPeriodEnd).subscribe({
      next: (period) => {
        this.notice = `Period "${period.name}" added.`;
        this.newPeriodName = '';
        this.newPeriodSequence = null;
        this.newPeriodStart = '';
        this.newPeriodEnd = '';
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not add the period')
    });
  }

  startEditPeriod(period: TimetablePeriod): void {
    this.editingPeriodId = period.id;
    this.editPeriodName = period.name;
    this.editPeriodSequence = period.sequenceNo;
    this.editPeriodStart = this.shortTime(period.startTime);
    this.editPeriodEnd = this.shortTime(period.endTime);
  }

  cancelEditPeriod(): void {
    this.editingPeriodId = null;
  }

  savePeriod(period: TimetablePeriod): void {
    if (!this.editPeriodName.trim() || this.editPeriodSequence === null
        || !this.editPeriodStart || !this.editPeriodEnd) {
      return;
    }
    this.timetable.updatePeriod(
      period.id, this.editPeriodName.trim(), this.editPeriodSequence,
      this.editPeriodStart, this.editPeriodEnd).subscribe({
      next: () => {
        this.notice = 'Period updated.';
        this.editingPeriodId = null;
        this.reload();
        this.loadGrid();
      },
      error: (err) => this.notifications.showError(err, 'Could not update the period')
    });
  }

  async deletePeriod(period: TimetablePeriod): Promise<void> {
    if (!await this.confirmDialog.ask(
      `Delete "${period.name}"? This cannot be undone.`)) {
      return;
    }
    this.timetable.deletePeriod(period.id).subscribe({
      next: () => {
        this.notice = `Period "${period.name}" deleted.`;
        this.reload();
        this.loadGrid();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete the period')
    });
  }

  // ===== scheduling ========================================================

  get canSchedule(): boolean {
    return this.selectedTermId !== null
        && this.slotCourseId !== null
        && this.slotPeriodId !== null
        && this.slotDay !== '';
  }

  schedule(): void {
    if (!this.canSchedule) {
      return;
    }
    this.timetable.schedule(
      this.selectedTermId!, this.slotPeriodId!, this.slotCourseId!, this.slotDay).subscribe({
      next: (slot) => {
        this.notice = `${slot.subjectName} timetabled for ${slot.className} `
                    + `in ${slot.periodName} on ${this.dayLabel(slot.dayOfWeek)}.`;
        this.slotDay = '';
        this.loadGrid();
      },
      /*
       * A clash arrives here as a 409 whose message names what is already in
       * that slot ("Grade 10A is already taught Mathematics in Period 3 on
       * Monday"). Routing it through the shared banner shows the server's own
       * sentence rather than a generic failure - which is the entire reason
       * TimetableService checks before inserting instead of letting the UNIQUE
       * constraint refuse it as a 500.
       */
      error: (err) => this.notifications.showError(err, 'Could not timetable that lesson')
    });
  }

  async unschedule(slot: TimetableSlot): Promise<void> {
    if (!await this.confirmDialog.ask(
      `Remove ${slot.subjectName} for ${slot.className} from `
      + `${slot.periodName} on ${this.dayLabel(slot.dayOfWeek)}?`)) {
      return;
    }
    this.timetable.unschedule(slot.id).subscribe({
      next: () => {
        this.notice = 'Lesson removed from the timetable.';
        this.loadGrid();
      },
      error: (err) => this.notifications.showError(err, 'Could not remove that lesson')
    });
  }
}
