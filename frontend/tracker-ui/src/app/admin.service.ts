import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';
import { Course } from './assignment.service';

/** What the admin panel's teacher list and forms are shown. Never a password. */
export interface Teacher {
  id: number;
  username: string;
  mustChangePassword: boolean;
}

/**
 * What the admin panel's student list and detail view are shown.
 *
 * className is null, not '', when a student has not been enrolled in a class
 * yet - a real, distinct state the admin panel shows rather than papering
 * over with an empty string that would look like a class named nothing.
 */
export interface Student {
  id: number;
  username: string;
  className: string | null;
  mustChangePassword: boolean;
}

/** Mirrors the backend AuditAction enum. */
export type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE';

/** One immutable audit log entry, as the API publishes it. */
export interface AuditLogEntry {
  id: number;
  entityName: string;
  entityId: number | null;
  action: AuditAction;
  performedByUsername: string;
  performedByRole: string | null;
  performedAt: string;
  summary: string;
}

/**
 * A Spring Data PagedModel, the stable shape TrackerApplication opts into
 * (pageSerializationMode = VIA_DTO) instead of serializing a raw PageImpl.
 */
export interface Page<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface AuditLogFilter {
  entityName?: string | null;
  action?: AuditAction | null;
  from?: string | null;   // ISO Instant
  to?: string | null;     // ISO Instant
  page?: number;
  size?: number;
}

/**
 * THE ADMIN DATA SERVICE
 * ----------------------
 * The one place that knows the admin panel's URLs, the same rule
 * AssignmentService follows for every other page: components ask a service
 * for data, never HttpClient directly. Kept SEPARATE from AssignmentService
 * because these endpoints answer to a different authority (admin-only,
 * enforced server-side) and describe a different concern - who may manage
 * whom, not the assignment/submission/mark data every other page reads.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {

  private readonly api = environment.apiBaseUrl;
  private readonly opts = { withCredentials: true };

  constructor(private http: HttpClient) {}

  // ----- teacher accounts ------------------------------------------------------

  listTeachers(): Observable<Teacher[]> {
    return this.http.get<Teacher[]>(`${this.api}/api/teachers`, this.opts);
  }

  createTeacher(username: string, temporaryPassword: string): Observable<Teacher> {
    return this.http.post<Teacher>(
      `${this.api}/api/teachers`, { username, temporaryPassword }, this.opts);
  }

  renameTeacher(id: number, username: string): Observable<Teacher> {
    return this.http.put<Teacher>(
      `${this.api}/api/teachers/${id}`, { username }, this.opts);
  }

  resetTeacherPassword(id: number, temporaryPassword: string): Observable<Teacher> {
    return this.http.put<Teacher>(
      `${this.api}/api/teachers/${id}/password`, { temporaryPassword }, this.opts);
  }

  /** Refused with 409 while the teacher still owns a course or an assignment. */
  deleteTeacher(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/teachers/${id}`, this.opts);
  }

  // ----- students, and who teaches them -----------------------------------------

  listStudents(): Observable<Student[]> {
    return this.http.get<Student[]>(`${this.api}/api/students`, this.opts);
  }

  /** One student's current teachers, by subject. */
  listTeachersForStudent(studentId: number): Observable<Course[]> {
    return this.http.get<Course[]>(
      `${this.api}/api/students/${studentId}/teachers`, this.opts);
  }

  /** Grant a teacher access to a student, by teaching them one subject. */
  assignTeacher(studentId: number, teacherId: number, subjectId: number): Observable<Course> {
    return this.http.post<Course>(
      `${this.api}/api/students/${studentId}/teachers/${teacherId}`,
      { subjectId }, this.opts);
  }

  /** Refused with 409 while the teacher has already set work for that class. */
  unassignTeacher(studentId: number, teacherId: number, subjectId: number): Observable<void> {
    return this.http.delete<void>(
      `${this.api}/api/students/${studentId}/teachers/${teacherId}`,
      { ...this.opts, params: new HttpParams().set('subjectId', subjectId) });
  }

  // ----- audit log ---------------------------------------------------------------

  /** Paginated and filtered; every filter is optional. */
  searchAuditLog(filter: AuditLogFilter): Observable<Page<AuditLogEntry>> {
    let params = new HttpParams()
      .set('page', String(filter.page ?? 0))
      .set('size', String(filter.size ?? 20));
    if (filter.entityName) {
      params = params.set('entityName', filter.entityName);
    }
    if (filter.action) {
      params = params.set('action', filter.action);
    }
    if (filter.from) {
      params = params.set('from', filter.from);
    }
    if (filter.to) {
      params = params.set('to', filter.to);
    }
    return this.http.get<Page<AuditLogEntry>>(
      `${this.api}/api/audit-logs`, { ...this.opts, params });
  }
}
