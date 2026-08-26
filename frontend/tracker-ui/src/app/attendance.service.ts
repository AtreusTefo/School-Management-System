import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

/** The four ways a student can be accounted for. Mirrors AttendanceStatus. */
export const ATTENDANCE_STATUSES = ['PRESENT', 'ABSENT', 'LATE', 'EXCUSED'] as const;
export type AttendanceStatus = typeof ATTENDANCE_STATUSES[number];

/**
 * One row of a register.
 *
 * `id` and `status` are NULL for a student who has not been marked yet - the
 * server returns a row for every enrolled student precisely so that "not taken
 * yet" and "everybody present" cannot look the same on screen. See
 * AttendanceService.register on the backend.
 */
export interface AttendanceRow {
  id: number | null;
  slotId: number;
  lessonDate: string;
  dayOfWeek: string;
  periodName: string;
  subjectName: string;
  className: string;
  studentId: number;
  studentUsername: string;
  studentName: string | null;
  status: AttendanceStatus | null;
  note: string | null;
  recordedBy: string | null;
}

/** Counts and the rate, all computed on read - nothing here is stored. */
export interface AttendanceSummary {
  present: number;
  absent: number;
  late: number;
  excused: number;
  total: number;
  /** Null, not zero, when nothing has been recorded yet. */
  attendanceRate: number | null;
}

/** One student's mark, as the register screen submits it. */
export interface AttendanceMark {
  studentId: number;
  status: AttendanceStatus;
  note?: string | null;
}

/**
 * THE ONLY PLACE THAT KNOWS THE ATTENDANCE URLs.
 *
 * Separate from TimetableApiService for the same reason AttendanceService is
 * separate on the server: a register is a different set of endpoints with a
 * different owner, even though it is read through a timetable.
 */
@Injectable({ providedIn: 'root' })
export class AttendanceApiService {

  private readonly api = environment.apiBaseUrl;
  private readonly opts = { withCredentials: true };

  constructor(private http: HttpClient) {}

  /** Every enrolled student for one lesson on one date, marked or not. */
  getRegister(slotId: number, date: string): Observable<AttendanceRow[]> {
    return this.http.get<AttendanceRow[]>(
      `${this.api}/api/attendance/lessons/${slotId}?date=${date}`, this.opts);
  }

  /**
   * PUT, not POST - taking the same register twice must leave the same result
   * rather than two registers, which is what idempotent means. The server
   * turns a second call into a correction of the first.
   */
  takeRegister(slotId: number, date: string, marks: AttendanceMark[]): Observable<AttendanceRow[]> {
    return this.http.put<AttendanceRow[]>(
      `${this.api}/api/attendance/lessons/${slotId}?date=${date}`, { marks }, this.opts);
  }

  /** One student's own record. The server refuses a student asking about anyone else. */
  getStudentRecord(studentId: number): Observable<AttendanceRow[]> {
    return this.http.get<AttendanceRow[]>(
      `${this.api}/api/attendance/students/${studentId}`, this.opts);
  }

  getStudentSummary(studentId: number): Observable<AttendanceSummary> {
    return this.http.get<AttendanceSummary>(
      `${this.api}/api/attendance/students/${studentId}/summary`, this.opts);
  }
}
