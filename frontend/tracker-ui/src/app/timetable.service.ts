import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

/** A named stretch of the school year. `current` is derived server-side. */
export interface Term {
  id: number;
  name: string;
  startDate: string;
  endDate: string;
  current: boolean;
}

/** One slot of the school day. Times arrive as "HH:mm:ss". */
export interface TimetablePeriod {
  id: number;
  name: string;
  sequenceNo: number;
  startTime: string;
  endTime: string;
}

/**
 * One lesson, flat rather than nested - see TimetableSlotView on the server
 * for why. Every field the weekly grid needs to draw a cell is on this object,
 * so drawing two hundred cells does not mean walking two hundred object graphs.
 */
export interface TimetableSlot {
  id: number;
  termId: number;
  termName: string;
  dayOfWeek: string;
  periodId: number;
  periodName: string;
  periodSequenceNo: number;
  startTime: string;
  endTime: string;
  courseId: number;
  subjectCode: string;
  subjectName: string;
  classId: number;
  className: string;
  teacherUsername: string;
}

/** The weekdays a lesson may fall on, in the order a week is read. */
export const DAYS_OF_WEEK = [
  'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'
] as const;

export type DayOfWeek = typeof DAYS_OF_WEEK[number];

/**
 * THE ONLY PLACE THAT KNOWS THE TIMETABLE'S URLs.
 *
 * A separate service from AssignmentService and AdminService for the same
 * reason TimetableService is separate on the server: this is a different set
 * of endpoints with a different owner, and AssignmentService is already 490
 * lines. Components never call HttpClient directly - see CLAUDE.md's frontend
 * rules.
 */
@Injectable({ providedIn: 'root' })
export class TimetableApiService {

  private readonly api = environment.apiBaseUrl;
  private readonly opts = { withCredentials: true };

  constructor(private http: HttpClient) {}

  // ----- terms ---------------------------------------------------------------

  getTerms(): Observable<Term[]> {
    return this.http.get<Term[]>(`${this.api}/api/terms`, this.opts);
  }

  createTerm(name: string, startDate: string, endDate: string): Observable<Term> {
    return this.http.post<Term>(
      `${this.api}/api/terms`, { name, startDate, endDate }, this.opts);
  }

  updateTerm(id: number, name: string, startDate: string, endDate: string): Observable<Term> {
    return this.http.put<Term>(
      `${this.api}/api/terms/${id}`, { name, startDate, endDate }, this.opts);
  }

  deleteTerm(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/terms/${id}`, this.opts);
  }

  // ----- periods -------------------------------------------------------------

  getPeriods(): Observable<TimetablePeriod[]> {
    return this.http.get<TimetablePeriod[]>(`${this.api}/api/periods`, this.opts);
  }

  createPeriod(name: string, sequenceNo: number,
               startTime: string, endTime: string): Observable<TimetablePeriod> {
    return this.http.post<TimetablePeriod>(
      `${this.api}/api/periods`, { name, sequenceNo, startTime, endTime }, this.opts);
  }

  updatePeriod(id: number, name: string, sequenceNo: number,
               startTime: string, endTime: string): Observable<TimetablePeriod> {
    return this.http.put<TimetablePeriod>(
      `${this.api}/api/periods/${id}`, { name, sequenceNo, startTime, endTime }, this.opts);
  }

  deletePeriod(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/periods/${id}`, this.opts);
  }

  // ----- the timetable -------------------------------------------------------

  /** The whole school's week for a term. Staff only - the server enforces it. */
  getTimetable(termId: number): Observable<TimetableSlot[]> {
    return this.http.get<TimetableSlot[]>(
      `${this.api}/api/timetable?termId=${termId}`, this.opts);
  }

  /**
   * The caller's own week. The only timetable read a student may make, and the
   * server decides what "own" means from the session rather than from anything
   * sent here - a request that named a class would be a request to read
   * somebody else's.
   */
  getMyTimetable(termId: number): Observable<TimetableSlot[]> {
    return this.http.get<TimetableSlot[]>(
      `${this.api}/api/timetable/me?termId=${termId}`, this.opts);
  }

  schedule(termId: number, periodId: number, courseId: number,
           dayOfWeek: string): Observable<TimetableSlot> {
    return this.http.post<TimetableSlot>(
      `${this.api}/api/timetable`, { termId, periodId, courseId, dayOfWeek }, this.opts);
  }

  unschedule(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/timetable/${id}`, this.opts);
  }
}
