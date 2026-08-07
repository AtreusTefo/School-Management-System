import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

/**
 * The states a student's work can be in. Mirrors the backend AssignmentStatus
 * enum exactly.
 *
 * Declaring it as a union rather than `string` means a typo like 'SUBMITED' is a
 * compile error here, instead of a comparison that quietly never matches and
 * leaves a button enabled forever.
 */
export type AssignmentStatus = 'IN_PROGRESS' | 'SUBMITTED';

/** Mirrors the backend Role enum. */
export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN';

/**
 * One subject taught to one class by one teacher.
 *
 * A student's list of these IS the answer to "which subjects am I taught, and by
 * whom" - the two are the same data read from different columns, which is why
 * neither needs its own request.
 */
export interface Course {
  id: number;
  subjectId: number;
  subjectCode: string;
  subjectName: string;
  classId: number;
  className: string;
  teacherUsername: string;
  label: string;
}

export interface Subject {
  id: number;
  code: string;
  name: string;
}

export interface SchoolClass {
  id: number;
  name: string;
  studentCount: number;
}

/**
 * A piece of work a teacher set, with progress counts.
 *
 * studentCount and submittedCount are what a class-wide assignment needs and a
 * single-owner one never did: "17 of 30 handed in" is the question a teacher
 * actually has, and it cannot be answered from the assignment alone.
 */
export interface Assignment {
  id: number;
  title: string;
  description: string | null;
  dueDate: string | null;   // ISO yyyy-MM-dd, or null when there is no deadline
  pastDue: boolean;
  courseId: number;
  subjectName: string;
  className: string;
  teacherUsername: string;
  createdByUsername: string;
  studentCount: number;
  submittedCount: number;
}

/**
 * One student's state for one assignment - the row both roles act on.
 *
 * `overdue` is computed by the server on every read rather than stored. A flag
 * held in the database would be wrong the moment midnight passed; asking the
 * server means the answer is always relative to today.
 *
 * The file's name, size and checksum are here; its CONTENT is not, and has no
 * field it could occupy. The bytes leave the server only through the download
 * endpoint, which checks who is asking first.
 */
export interface Submission {
  id: number;
  assignmentId: number;
  assignmentTitle: string;
  description: string | null;
  subjectName: string;
  className: string;
  teacherUsername: string;
  studentUsername: string;
  status: AssignmentStatus;
  dueDate: string | null;
  overdue: boolean;
  submittedAt: string | null;
  hasFile: boolean;
  fileName: string | null;
  fileSizeBytes: number | null;
  fileSha256: string | null;
  fileUploadedAt: string | null;
}

/**
 * The performance bands. Mirrors the backend PerformanceLevel enum.
 *
 * The thresholds live on the SERVER, not here. This type only names the bands
 * so a typo is a compile error; deciding which band a percentage falls into is
 * the server's job, and doing it in both places would be two implementations of
 * one policy that could quietly disagree.
 */
export type PerformanceLevel =
  | 'OUTSTANDING' | 'MERITORIOUS' | 'SUBSTANTIAL'
  | 'ADEQUATE' | 'MODERATE' | 'ELEMENTARY' | 'NOT_ACHIEVED';

/**
 * One mark, as the API sends it.
 *
 * `percentage` and `level` arrive already computed. The browser never derives
 * them, which is why the PDF export can simply dump what is on screen and still
 * be the authoritative report.
 *
 * The numbers are strings, not numbers - Java sends BigDecimal as a JSON string
 * to avoid the precision loss that turns 0.1 into 0.09999999999999999. Parse
 * them for arithmetic if you must; for display, use them as they came.
 */
export interface Assessment {
  id: number;
  studentUsername: string;
  courseId: number;
  subjectCode: string;
  subjectName: string;
  className: string;
  teacherUsername: string;
  name: string;
  score: string;
  maxScore: string;
  percentage: string | null;
  level: PerformanceLevel | null;
  levelDescription: string | null;
  recordedByUsername: string;
  recordedAt: string;
  submissionId: number | null;
}

/**
 * One student's standing in one subject.
 *
 * Every field is derived by the server from the marks; nothing is stored. A
 * corrected mark corrects this the next time it is asked for, with nothing to
 * recalculate.
 */
export interface Performance {
  studentUsername: string;
  courseId: number;
  subjectCode: string;
  subjectName: string;
  className: string;
  teacherUsername: string;
  assessmentCount: number;
  totalScore: string;
  totalMaxScore: string;
  percentage: string | null;
  level: PerformanceLevel | null;
  levelDescription: string | null;
}

/**
 * Who is signed in.
 *
 * `mustChangePassword` is true while the account still holds a password someone
 * else chose - a teacher created it and issued a temporary one. The UI uses it
 * to show the change-password form instead of the list, but that is a courtesy:
 * the server refuses every other operation for such an account regardless of
 * what the browser chooses to draw.
 */
export interface CurrentUser {
  id: number;
  username: string;
  role: Role;
  mustChangePassword: boolean;
}

/**
 * THE DATA SERVICE
 * ----------------
 * This is the ONLY place that knows the backend's URLs. Components ask this
 * service for data; they never call HttpClient directly. (Same idea as keeping
 * database access inside the repository on the backend.)
 *
 * WHY EVERY CALL SETS withCredentials
 * Sign-in produces a session cookie. A browser will NOT attach a cookie to a
 * cross-origin request unless the caller explicitly asks it to - and in
 * development the page is on :4200 while the API is on :8080, which is
 * cross-origin. Without this the app would sign in successfully and then be
 * anonymous again on the very next request.
 *
 * The CSRF token is handled by csrf.interceptor.ts, because Angular's built-in
 * XSRF support deliberately refuses to attach the token cross-origin.
 */
@Injectable({ providedIn: 'root' })
export class AssignmentService {

  /**
   * The address of our Spring Boot API, built from configuration.
   *
   * environment.apiBaseUrl supplies the host, and Angular swaps the environment
   * file per build configuration (see angular.json). In development it is
   * 'http://localhost:8080'; in production it is empty, which makes the path
   * relative so the site calls whichever host served it.
   */
  private readonly api = environment.apiBaseUrl;
  private readonly opts = { withCredentials: true };

  constructor(private http: HttpClient) {}

  // ----- authentication ------------------------------------------------------

  /**
   * Ask the server to issue the XSRF-TOKEN cookie.
   *
   * Must be called before the first write. The interceptor can only echo a token
   * back once one exists, and the very first sign-in POST is itself a write.
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

  /**
   * Replace my own password (US-21).
   *
   * The current password goes with it. The server requires it even though the
   * session already identifies us - a session proves somebody signed in, not
   * that the person here now is the account's owner.
   */
  changePassword(currentPassword: string, newPassword: string): Observable<CurrentUser> {
    return this.http.put<CurrentUser>(
      `${this.api}/api/auth/password`, { currentPassword, newPassword }, this.opts);
  }

  /** Create a student account (US-23). Teacher only; the server enforces it. */
  createStudent(username: string, temporaryPassword: string): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(
      `${this.api}/api/users`, { username, temporaryPassword }, this.opts);
  }

  // ----- the timetable -------------------------------------------------------

  /** The courses the caller is involved in. Scope is decided by the server. */
  getCourses(): Observable<Course[]> {
    return this.http.get<Course[]>(`${this.api}/api/courses`, this.opts);
  }

  getSubjects(): Observable<Subject[]> {
    return this.http.get<Subject[]>(`${this.api}/api/subjects`, this.opts);
  }

  getClasses(): Observable<SchoolClass[]> {
    return this.http.get<SchoolClass[]>(`${this.api}/api/classes`, this.opts);
  }

  createSubject(code: string, name: string): Observable<Subject> {
    return this.http.post<Subject>(`${this.api}/api/subjects`, { code, name }, this.opts);
  }

  createClass(name: string): Observable<SchoolClass> {
    return this.http.post<SchoolClass>(`${this.api}/api/classes`, { name }, this.opts);
  }

  createCourse(subjectId: number, classId: number, teacherUsername: string | null)
      : Observable<Course> {
    return this.http.post<Course>(
      `${this.api}/api/courses`, { subjectId, classId, teacherUsername }, this.opts);
  }

  /** The register for a class. Teacher only. */
  getClassStudents(classId: number): Observable<string[]> {
    return this.http.get<string[]>(
      `${this.api}/api/classes/${classId}/students`, this.opts);
  }

  enrolStudent(classId: number, username: string): Observable<void> {
    return this.http.post<void>(
      `${this.api}/api/classes/${classId}/students`, { username }, this.opts);
  }

  withdrawStudent(classId: number, username: string): Observable<void> {
    return this.http.delete<void>(
      `${this.api}/api/classes/${classId}/students/${encodeURIComponent(username)}`,
      this.opts);
  }

  // ----- assignments ---------------------------------------------------------

  getAssignments(): Observable<Assignment[]> {
    return this.http.get<Assignment[]>(`${this.api}/api/assignments`, this.opts);
  }

  /**
   * Set work for one or more courses. Teacher only.
   *
   * Returns a LIST: setting the same task for three classes is one action for
   * the teacher and three assignments in the data, because each class has its
   * own register and its own progress.
   */
  createAssignment(title: string, description: string | null,
                   dueDate: string | null, courseIds: number[]): Observable<Assignment[]> {
    return this.http.post<Assignment[]>(
      `${this.api}/api/assignments`, { title, description, dueDate, courseIds }, this.opts);
  }

  updateAssignment(id: number, title: string, description: string | null,
                   dueDate: string | null): Observable<Assignment> {
    return this.http.put<Assignment>(
      `${this.api}/api/assignments/${id}`, { title, description, dueDate }, this.opts);
  }

  /** Refused once anybody has handed work in. */
  deleteAssignment(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/assignments/${id}`, this.opts);
  }

  /** Every student's state for one assignment - the teacher's marking list. */
  getSubmissionsForAssignment(assignmentId: number): Observable<Submission[]> {
    return this.http.get<Submission[]>(
      `${this.api}/api/assignments/${assignmentId}/submissions`, this.opts);
  }

  // ----- submissions ---------------------------------------------------------

  /** A student's own work, or a teacher's marking queue. */
  getSubmissions(): Observable<Submission[]> {
    return this.http.get<Submission[]>(`${this.api}/api/submissions`, this.opts);
  }

  /**
   * Attach or replace the PDF.
   *
   * FormData rather than JSON, and deliberately WITHOUT a Content-Type header:
   * the browser has to set it itself so it can append the multipart boundary.
   * Setting it by hand produces a request the server cannot parse, and the
   * failure looks like a server fault rather than a client mistake.
   */
  uploadFile(submissionId: number, file: File): Observable<Submission> {
    const form = new FormData();
    form.append('file', file, file.name);

    return this.http.post<Submission>(
      `${this.api}/api/submissions/${submissionId}/file`, form, this.opts);
  }

  submit(submissionId: number): Observable<Submission> {
    return this.http.put<Submission>(
      `${this.api}/api/submissions/${submissionId}/submit`, {}, this.opts);
  }

  /** Teacher only - reopens handed-in work. */
  unsubmit(submissionId: number): Observable<Submission> {
    return this.http.put<Submission>(
      `${this.api}/api/submissions/${submissionId}/unsubmit`, {}, this.opts);
  }

  // ----- marks and performance -----------------------------------------------

  /** A student's own marks, or a teacher's mark book. The server decides. */
  getAssessments(): Observable<Assessment[]> {
    return this.http.get<Assessment[]>(`${this.api}/api/assessments`, this.opts);
  }

  /** Performance per student per subject, derived server-side from the marks. */
  getPerformance(): Observable<Performance[]> {
    return this.http.get<Performance[]>(`${this.api}/api/assessments/summary`, this.opts);
  }

  /**
   * Record a mark. Teacher only.
   *
   * score and maxScore are sent as STRINGS. A JavaScript number is a double, and
   * routing a mark through one is how 34.5 becomes 34.499999999999996 on its way
   * to a BigDecimal column. Keeping it as text means the server parses exactly
   * what the teacher typed.
   */
  recordMark(courseId: number, studentUsername: string, name: string,
             score: string, maxScore: string,
             submissionId: number | null): Observable<Assessment> {
    return this.http.post<Assessment>(`${this.api}/api/assessments`,
      { courseId, studentUsername, name, score, maxScore, submissionId }, this.opts);
  }

  updateMark(id: number, name: string, score: string, maxScore: string)
      : Observable<Assessment> {
    return this.http.put<Assessment>(
      `${this.api}/api/assessments/${id}`, { name, score, maxScore }, this.opts);
  }

  deleteMark(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/assessments/${id}`, this.opts);
  }

  /**
   * Fetch the PDF as a Blob so the page can offer it as a download.
   *
   * responseType 'blob' matters: the default would try to parse the bytes as
   * JSON and fail on the first byte of the PDF header. The caller turns this
   * into a download; see AppComponent.download.
   */
  downloadFile(submissionId: number): Observable<Blob> {
    return this.http.get(`${this.api}/api/submissions/${submissionId}/file`, {
      withCredentials: true,
      responseType: 'blob'
    });
  }

  /**
   * Fetch the combined marks-and-performance workbook as a Blob.
   *
   * BUILT ON THE SERVER, DELIBERATELY. The alternative - a JavaScript library
   * turning already-loaded JSON into a workbook here in the browser - would
   * have to re-derive the same scoping AssessmentService already applies to
   * every mark and every summary row, as a SECOND implementation of that rule
   * that could drift from the first. Asking the server for the finished file
   * means the download can never contain a row the JSON endpoints would not
   * already have shown this caller.
   *
   * Same 'blob' responseType as downloadFile, for the same reason: the
   * default would try to parse .xlsx bytes as JSON and fail on the first one.
   */
  downloadExcelReport(): Observable<Blob> {
    return this.http.get(`${this.api}/api/assessments/report.xlsx`, {
      withCredentials: true,
      responseType: 'blob'
    });
  }
}
