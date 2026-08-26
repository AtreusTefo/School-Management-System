import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ATTENDANCE_STATUSES, AttendanceApiService, AttendanceMark, AttendanceRow,
  AttendanceStatus, AttendanceSummary
} from './attendance.service';
import { Term, TimetableApiService, TimetableSlot } from './timetable.service';
import { NotificationService } from './notification.service';
import { SessionService } from './session.service';

/**
 * '/attendance' - ONE ROUTE, TWO GENUINELY DIFFERENT JOBS.
 *
 * A teacher (or an admin) TAKES a register: pick a date, pick one of the
 * lessons that actually runs that day, mark everybody. A student READS their
 * own: what was recorded about them, and what proportion of it they attended.
 *
 * These are different enough that they could have been two routes, and one
 * thing decided otherwise: the server already branches on role for exactly
 * this data, and a student navigating to a teacher-shaped URL - or the reverse
 * - would be bounced by a guard rather than shown anything useful. One address
 * that always answers "your attendance, in whatever sense applies to you"
 * matches how /timetable already behaves.
 *
 * WHY THE LESSON LIST IS DERIVED, NOT FETCHED
 * There is no "lessons on 2026-08-31" endpoint and there should not be: a
 * lesson repeats weekly, so that list is the caller's own timetable filtered
 * to one weekday. Deriving it in the browser from a timetable already loaded
 * costs nothing, and inventing a server endpoint for it would have added a
 * second way to ask a question the timetable already answers - two answers
 * that could disagree.
 */
@Component({
  selector: 'app-attendance-page',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './attendance-page.component.html'
})
export class AttendancePageComponent implements OnInit {

  readonly statuses = ATTENDANCE_STATUSES;

  // ----- shared -----
  terms: Term[] = [];
  loading = true;
  notice: string | null = null;

  // ----- taking a register (teacher / admin) -----
  /** Defaults to today, which is when a register is almost always taken. */
  date = new Date().toISOString().substring(0, 10);
  slots: TimetableSlot[] = [];
  selectedSlotId: number | null = null;
  register: AttendanceRow[] = [];
  /** studentId -> the status picked on screen, before it is saved. */
  draft = new Map<number, AttendanceStatus>();
  notes = new Map<number, string>();
  saving = false;

  // ----- reading your own (student) -----
  myRecord: AttendanceRow[] = [];
  mySummary: AttendanceSummary | null = null;

  constructor(
    private attendance: AttendanceApiService,
    private timetable: TimetableApiService,
    private notifications: NotificationService,
    public session: SessionService
  ) {}

  get takesRegisters(): boolean {
    return this.session.isTeacher || this.session.isAdmin;
  }

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.reload());
    this.reload();
  }

  reload(): void {
    this.loading = true;
    if (!this.takesRegisters) {
      this.loadMyOwn();
      return;
    }
    this.timetable.getTerms().subscribe({
      next: (data) => {
        this.terms = data;
        this.loadLessonsForDate();
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load terms');
      }
    });
  }

  // ===== the student's own record =========================================

  private loadMyOwn(): void {
    const me = this.session.user();
    if (!me) {
      this.loading = false;
      return;
    }
    this.attendance.getStudentRecord(me.id).subscribe({
      next: (rows) => { this.myRecord = rows; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load your attendance');
      }
    });
    this.attendance.getStudentSummary(me.id).subscribe({
      next: (s) => this.mySummary = s,
      error: (err) => this.notifications.showError(err, 'Could not load your attendance summary')
    });
  }

  // ===== taking a register ================================================

  /**
   * The term covering the chosen date, or null.
   *
   * A lesson belongs to a term, so a date outside every term has no lessons -
   * which is a real answer (a school holiday), not an error, and the template
   * says so rather than showing an empty list that looks like a fault.
   */
  get termForDate(): Term | null {
    return this.terms.find(t => t.startDate <= this.date && this.date <= t.endDate) ?? null;
  }

  /** "MONDAY" for the chosen date - what the timetable is keyed by. */
  get weekdayForDate(): string {
    // Parsed as UTC deliberately: `new Date('2026-08-31')` is midnight UTC, and
    // using the local-time constructor would shift the weekday by one for
    // anybody west of Greenwich.
    const days = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
    return days[new Date(this.date + 'T00:00:00Z').getUTCDay()];
  }

  onDateChange(): void {
    this.selectedSlotId = null;
    this.register = [];
    this.draft.clear();
    this.notes.clear();
    this.loadLessonsForDate();
  }

  loadLessonsForDate(): void {
    const term = this.termForDate;
    if (!term) {
      this.slots = [];
      this.loading = false;
      return;
    }
    this.loading = true;
    this.timetable.getMyTimetable(term.id).subscribe({
      next: (all) => {
        this.slots = all.filter(s => s.dayOfWeek === this.weekdayForDate);
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load your timetable');
      }
    });
  }

  openLesson(slot: TimetableSlot): void {
    this.selectedSlotId = slot.id;
    this.notice = null;
    this.attendance.getRegister(slot.id, this.date).subscribe({
      next: (rows) => {
        this.register = rows;
        this.draft.clear();
        this.notes.clear();
        for (const row of rows) {
          // An already-marked student starts on their recorded status; an
          // unmarked one starts on PRESENT, because that is what most of a
          // class is and a register is faster to take by exception.
          this.draft.set(row.studentId, row.status ?? 'PRESENT');
          if (row.note) {
            this.notes.set(row.studentId, row.note);
          }
        }
      },
      error: (err) => this.notifications.showError(err, 'Could not load the register')
    });
  }

  closeLesson(): void {
    this.selectedSlotId = null;
    this.register = [];
    this.draft.clear();
    this.notes.clear();
  }

  statusOf(studentId: number): AttendanceStatus {
    return this.draft.get(studentId) ?? 'PRESENT';
  }

  setStatus(studentId: number, status: AttendanceStatus): void {
    this.draft.set(studentId, status);
  }

  noteOf(studentId: number): string {
    return this.notes.get(studentId) ?? '';
  }

  setNote(studentId: number, note: string): void {
    this.notes.set(studentId, note);
  }

  /** Mark everyone the same - the fast path for a full or an empty room. */
  markAll(status: AttendanceStatus): void {
    for (const row of this.register) {
      this.draft.set(row.studentId, status);
    }
  }

  get selectedSlot(): TimetableSlot | null {
    return this.slots.find(s => s.id === this.selectedSlotId) ?? null;
  }

  /** How many are not PRESENT - the number a teacher actually checks. */
  get notPresentCount(): number {
    return this.register.filter(r => this.statusOf(r.studentId) !== 'PRESENT').length;
  }

  save(): void {
    if (this.selectedSlotId === null || this.register.length === 0) {
      return;
    }
    this.saving = true;
    const marks: AttendanceMark[] = this.register.map(row => ({
      studentId: row.studentId,
      status: this.statusOf(row.studentId),
      note: this.noteOf(row.studentId) || null
    }));

    this.attendance.takeRegister(this.selectedSlotId, this.date, marks).subscribe({
      next: (rows) => {
        this.saving = false;
        this.register = rows;
        this.notice = `Register saved for ${rows.length} students.`;
      },
      /*
       * A date that is not a real occurrence of this lesson arrives here as a
       * 400 whose message says so - "2026-08-31 is a Monday, but this lesson is
       * taught on Thursday". Routing it through the shared banner shows the
       * server's own sentence, which is the whole reason those checks produce
       * one instead of letting a constraint fail.
       */
      error: (err) => {
        this.saving = false;
        this.notifications.showError(err, 'Could not save the register');
      }
    });
  }

  // ===== shared display helpers ===========================================

  shortTime(time: string): string {
    return time ? time.substring(0, 5) : '';
  }

  dayLabel(day: string): string {
    return day.charAt(0) + day.substring(1).toLowerCase();
  }

  statusLabel(status: string): string {
    return status.charAt(0) + status.substring(1).toLowerCase();
  }

  /** The badge modifier for a status - green for attended, red for not. */
  statusClass(status: string | null): string {
    switch (status) {
      case 'PRESENT': return 'badge--done';
      case 'LATE':    return 'badge--progress';
      case 'ABSENT':  return 'badge--overdue';
      case 'EXCUSED': return 'badge--neutral';
      default:        return 'badge--neutral';
    }
  }
}
