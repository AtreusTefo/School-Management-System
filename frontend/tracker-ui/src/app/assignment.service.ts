import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

/**
 * The states an assignment can be in. This list mirrors the backend's
 * AssignmentStatus enum exactly.
 *
 * Declaring it as a union rather than `string` means a typo like 'SUBMITED'
 * is a compile error here, instead of a comparison that quietly never matches
 * and leaves a button enabled forever.
 */
export type AssignmentStatus = 'IN_PROGRESS' | 'SUBMITTED';

/** Mirrors the backend Role enum. */
export type Role = 'STUDENT' | 'TEACHER';

/**
 * One assignment, as the API sends it.
 *
 * `overdue` is computed by the server on every read rather than stored. A flag
 * held in the database would be wrong the moment midnight passed; asking the
 * server means the answer is always relative to today.
 */
export interface Assignment {
  id: number;
  title: string;
  status: AssignmentStatus;
  ownerUsername: string;
  dueDate: string | null;   // ISO yyyy-MM-dd, or null when there is no deadline
  overdue: boolean;
}

/** Who is signed in. */
export interface CurrentUser {
  id: number;
  username: string;
  role: Role;
}

/**
 * THE DATA SERVICE
 * ----------------
 * This is the ONLY place that knows the backend's URLs. Components ask this
 * service for data; they never call HTTP directly. (Same idea as keeping DB
 * access inside the repository on the backend.)
 *
 * WHY EVERY CALL SETS withCredentials
 * Sign-in produces a session cookie. A browser will NOT attach a cookie to a
 * cross-origin request unless the caller explicitly asks it to - and in
 * development the page is on :4200 while the API is on :8080, which is
 * cross-origin. Without this the app would sign in successfully and then be
 * anonymous again on the very next request.
 *
 * The CSRF token needs no code at all: Angular's HttpClient reads the
 * XSRF-TOKEN cookie and echoes it back as X-XSRF-TOKEN automatically, which is
 * exactly the pair the backend expects.
 */
@Injectable({ providedIn: 'root' })
export class AssignmentService {

  /**
   * The address of our Spring Boot controller, built from configuration.
   *
   * environment.apiBaseUrl supplies the host, and Angular swaps the environment
   * file per build configuration (see angular.json). In development it is
   * 'http://localhost:8080'; in production it is empty, which makes the path
   * relative so the site calls whichever host served it.
   */
  private readonly api = environment.apiBaseUrl;
  private readonly baseUrl = `${environment.apiBaseUrl}/api/assignments`;
  private readonly opts = { withCredentials: true };

  constructor(private http: HttpClient) {}

  // ----- authentication ------------------------------------------------------

  /**
   * Ask the server to issue the XSRF-TOKEN cookie.
   *
   * Must be called before the first write. Angular can only echo a token back
   * once one exists, and the very first sign-in POST is itself a write.
   */
  primeCsrf(): Observable<void> {
    return this.http.get<void>(`${this.api}/api/auth/csrf`, this.opts);
  }

  login(username: string, password: string): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(
      `${this.api}/api/auth/login`, { username, password }, this.opts);
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.api}/api/auth/logout`, {}, this.opts);
  }

  /** Who am I? Returns 401 when nobody is signed in. */
  me(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>(`${this.api}/api/auth/me`, this.opts);
  }

  // ----- assignments ---------------------------------------------------------

  /** The list the signed-in person may see. The server decides the scope. */
  getAssignments(): Observable<Assignment[]> {
    return this.http.get<Assignment[]>(this.baseUrl, this.opts);
  }

  /** Teacher only. `assignTo` names the student it is set for; omit to keep it. */
  createAssignment(title: string, dueDate: string | null, assignTo: string | null)
      : Observable<Assignment> {
    return this.http.post<Assignment>(
      this.baseUrl, { title, dueDate, assignTo }, this.opts);
  }

  /** Owner only. */
  updateAssignment(id: number, title: string, dueDate: string | null)
      : Observable<Assignment> {
    return this.http.put<Assignment>(
      `${this.baseUrl}/${id}`, { title, dueDate }, this.opts);
  }

  /** Owner only, and refused once the assignment has been submitted. */
  deleteAssignment(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`, this.opts);
  }

  submitAssignment(id: number): Observable<Assignment> {
    return this.http.put<Assignment>(`${this.baseUrl}/${id}/submit`, {}, this.opts);
  }

  /** Teacher only - reopens a submitted assignment. */
  unsubmitAssignment(id: number): Observable<Assignment> {
    return this.http.put<Assignment>(`${this.baseUrl}/${id}/unsubmit`, {}, this.opts);
  }
}
