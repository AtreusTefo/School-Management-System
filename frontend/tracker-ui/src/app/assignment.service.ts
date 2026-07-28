import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * The states an assignment can be in. This list mirrors the backend's
 * AssignmentStatus enum exactly.
 *
 * Declaring it as a union rather than `string` means a typo like 'SUBMITED'
 * is a compile error here, instead of a comparison that quietly never matches
 * and leaves a button enabled forever.
 */
export type AssignmentStatus = 'IN_PROGRESS' | 'SUBMITTED';

/**
 * A TypeScript "interface" describing the shape of one assignment.
 * It must match the JSON the backend sends (id, title, status).
 * This gives us autocomplete and type-safety in the editor.
 */
export interface Assignment {
  id: number;
  title: string;
  status: AssignmentStatus;
}

/**
 * THE DATA SERVICE
 * ----------------
 * This is the ONLY place that knows the backend's URLs. Components ask this
 * service for data; they never call HTTP directly. (Same idea as keeping DB
 * access inside the repository on the backend.)
 *
 * @Injectable({ providedIn: 'root' }) makes ONE shared instance available
 * everywhere in the app.
 */
@Injectable({ providedIn: 'root' })
export class AssignmentService {

  // The base address of our Spring Boot controller.
  private readonly baseUrl = 'http://localhost:8080/api/assignments';

  // Angular injects HttpClient for us (its version of dependency injection).
  constructor(private http: HttpClient) {}

  /**
   * GET the list. Returns an Observable — think of it as a "promise of data
   * that will arrive later". The component will subscribe() to receive it.
   * This maps to:  GET /api/assignments
   */
  getAssignments(): Observable<Assignment[]> {
    return this.http.get<Assignment[]>(this.baseUrl);
  }

  /**
   * POST a new assignment. We send a JSON body with just the title; the
   * backend decides the id and the starting status.
   * This maps to:  POST /api/assignments
   */
  createAssignment(title: string): Observable<Assignment> {
    return this.http.post<Assignment>(this.baseUrl, { title });
  }

  /**
   * PUT to submit one assignment by id.
   * This maps to:  PUT /api/assignments/{id}/submit
   * The second argument ({}) is the request body — empty here, because the
   * id in the URL is all the backend needs.
   */
  submitAssignment(id: number): Observable<Assignment> {
    return this.http.put<Assignment>(`${this.baseUrl}/${id}/submit`, {});
  }
}
