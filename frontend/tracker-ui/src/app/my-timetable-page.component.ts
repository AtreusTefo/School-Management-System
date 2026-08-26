import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  DAYS_OF_WEEK, Term, TimetableApiService, TimetablePeriod, TimetableSlot
} from './timetable.service';
import { NotificationService } from './notification.service';
import { SessionService } from './session.service';

/**
 * '/timetable' - THE CALLER'S OWN WEEK.
 *
 * ONE PAGE FOR ALL THREE ROLES, because the difference between them is not in
 * the page, it is in the DATA - and the server, not this component, decides
 * what "own" means. A teacher gets the lessons they teach, a student gets
 * their class's lessons through enrolment, an admin gets the whole term.
 *
 * The request carries only a term id. It deliberately cannot name a class or a
 * teacher: a request that could would be a request to read somebody else's
 * timetable, and the endpoint would then have to be trusted to refuse it. Not
 * offering the parameter at all is a stronger guarantee than validating it -
 * the same reasoning UpdatePersonRequest follows by having no username field.
 */
@Component({
  selector: 'app-my-timetable-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './my-timetable-page.component.html'
})
export class MyTimetablePageComponent implements OnInit {

  readonly days = DAYS_OF_WEEK;

  terms: Term[] = [];
  periods: TimetablePeriod[] = [];
  slots: TimetableSlot[] = [];
  selectedTermId: number | null = null;
  loading = true;

  constructor(
    private timetable: TimetableApiService,
    private notifications: NotificationService,
    public session: SessionService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.reload());
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.timetable.getPeriods().subscribe({
      next: (data) => this.periods = data,
      error: (err) => this.notifications.showError(err, 'Could not load the school day')
    });
    this.timetable.getTerms().subscribe({
      next: (data) => {
        this.terms = data;
        if (data.length === 0) {
          this.loading = false;
          return;
        }
        if (this.selectedTermId === null) {
          this.selectedTermId = (data.find(t => t.current) ?? data[0]).id;
        }
        this.loadWeek();
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load terms');
      }
    });
  }

  loadWeek(): void {
    if (this.selectedTermId === null) {
      this.slots = [];
      this.loading = false;
      return;
    }
    this.loading = true;
    this.timetable.getMyTimetable(this.selectedTermId).subscribe({
      next: (data) => {
        this.slots = data;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load your timetable');
      }
    });
  }

  onTermChange(): void {
    this.loadWeek();
  }

  /** Only draw columns for days that actually have a lesson - see the template. */
  get activeDays(): string[] {
    return this.days.filter(day => this.slots.some(s => s.dayOfWeek === day));
  }

  /** Only draw rows for periods that actually have a lesson. */
  get activePeriods(): TimetablePeriod[] {
    return this.periods.filter(p => this.slots.some(s => s.periodId === p.id));
  }

  slotAt(day: string, periodId: number): TimetableSlot | null {
    return this.slots.find(s => s.dayOfWeek === day && s.periodId === periodId) ?? null;
  }

  shortTime(time: string): string {
    return time ? time.substring(0, 5) : '';
  }

  dayLabel(day: string): string {
    return day.charAt(0) + day.substring(1).toLowerCase();
  }

  /** A teacher sees which class; a student sees who teaches them. */
  get showsClass(): boolean {
    return this.session.isTeacher || this.session.isAdmin;
  }
}
