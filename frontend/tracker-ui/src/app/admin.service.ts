import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';
import { Course } from './assignment.service';

/**
 * The profile fields the student and teacher onboarding forms share -
 * mirrors AdminController.PersonRequest on the backend. dateOfBirth and
 * dateEnrolled are ISO yyyy-MM-dd strings, the same convention every other
 * LocalDate in this app's API already uses (see Assignment.dueDate).
 */
export interface PersonFields {
  firstName: string;
  middleName?: string | null;
  lastName: string;
  nationalId: string;
  dateOfBirth: string;
  email?: string | null;
  /**
   * Required, unlike email - +267 followed by 8 digits (E.164). The server
   * rejects a missing or malformed one with 400; see PersonPatterns.PHONE on
   * the backend and PHONE_PATTERN here.
   */
  phone: string;
  dateEnrolled?: string | null;
  temporaryPassword: string;
}

/** The same fields an edit sends: everything except the credential. */
export type PersonEditFields = Omit<PersonFields, 'temporaryPassword'>;

/**
 * Drop the temporary password before sending an edit.
 *
 * Editing and creating share a form, so the same object reaches both. Deleting
 * the key here rather than trusting each call site means an edit cannot carry a
 * credential even by accident - and the server, which has no password field on
 * its update DTO at all, would ignore it regardless.
 */
function withoutPassword(fields: PersonFields): PersonEditFields {
  const { temporaryPassword, ...rest } = fields;
  return rest;
}

/** One (subject, class) pair a newly created teacher starts out teaching. */
export interface CourseAssignment {
  subjectId: number;
  classId: number;
}

/**
 * What the admin panel's teacher list and detail view are shown. Never a
 * password. Profile fields are nullable - the seeded development accounts
 * (teacher, teacher2) predate PersonProfile and have none.
 */
export interface Teacher {
  id: number;
  username: string;
  externalId: string | null;
  mustChangePassword: boolean;
  fullName: string | null;
  /* The name in parts as well as joined - an edit form needs the three fields
     back, and splitting a joined name to recover them is guesswork. */
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  nationalId: string | null;
  dateOfBirth: string | null;
  contactEmail: string | null;
  phone: string | null;
  dateEnrolled: string | null;
}

/**
 * What the admin panel's student list and detail view are shown.
 *
 * className is null, not '', when a student has not been enrolled in a class
 * yet - a real, distinct state the admin panel shows rather than papering
 * over with an empty string that would look like a class named nothing.
 * Profile fields are nullable for the same reason Teacher's are.
 */
export interface Student {
  id: number;
  username: string;
  externalId: string | null;
  className: string | null;
  mustChangePassword: boolean;
  fullName: string | null;
  /* The name in parts as well as joined - an edit form needs the three fields
     back, and splitting a joined name to recover them is guesswork. */
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  nationalId: string | null;
  dateOfBirth: string | null;
  contactEmail: string | null;
  phone: string | null;
  dateEnrolled: string | null;
}

/**
 * An admin account, as GET /api/admins publishes it - mirrors
 * AuthController.UserView. No profile fields: admin accounts do not get one,
 * a deliberate decision recorded in CLAUDE.md's Scope Guard, not a gap.
 */
export interface AdminAccount {
  id: number;
  username: string;
  role: string;
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
 * The report a bulk import hands back - mirrors ImportSummary on the
 * backend. temporaryPassword is set only on rows that succeeded, since it is
 * the one time this system ever says a password in plain text.
 */
export interface ImportSummary {
  totalRows: number;
  succeeded: number;
  failed: number;
  rows: ImportRowResult[];
}

export interface ImportRowResult {
  rowNumber: number;
  success: boolean;
  identifier: string | null;
  externalId: string | null;
  temporaryPassword: string | null;
  message: string | null;
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

  /** Every admin account. No profile fields - see AdminAccount's own comment. */
  listAdmins(): Observable<AdminAccount[]> {
    return this.http.get<AdminAccount[]>(`${this.api}/api/admins`, this.opts);
  }

  /**
   * Onboard a teacher. The account's login id (TCH...) is generated by the
   * server, never chosen here - see AdminService on the backend.
   * initialCourses is optional: which subjects, for which classes, this
   * teacher starts out teaching.
   */
  createTeacher(fields: PersonFields, initialCourses?: CourseAssignment[]): Observable<Teacher> {
    return this.http.post<Teacher>(
      `${this.api}/api/teachers`, { ...fields, initialCourses }, this.opts);
  }

  resetTeacherPassword(id: number, temporaryPassword: string): Observable<Teacher> {
    return this.http.put<Teacher>(
      `${this.api}/api/teachers/${id}/password`, { temporaryPassword }, this.opts);
  }

  /** Refused with 409 while the teacher still owns a course or an assignment. */
  deleteTeacher(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/teachers/${id}`, this.opts);
  }

  /** Bulk-onboard teachers from a CSV or Excel file - see ImportSummary. */
  importTeachers(file: File): Observable<ImportSummary> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<ImportSummary>(`${this.api}/api/teachers/import`, form, this.opts);
  }

  // ----- students, and who teaches them -----------------------------------------

  listStudents(): Observable<Student[]> {
    return this.http.get<Student[]>(`${this.api}/api/students`, this.opts);
  }

  /**
   * Onboard a student. classId is optional - a student can exist before
   * being placed in a class, the same as one enrolled through the
   * timetable's own endpoint always could.
   */
  createStudent(fields: PersonFields, classId?: number | null): Observable<Student> {
    return this.http.post<Student>(
      `${this.api}/api/students`, { ...fields, classId }, this.opts);
  }

  /** Bulk-onboard students from a CSV or Excel file - see ImportSummary. */
  importStudents(file: File): Observable<ImportSummary> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<ImportSummary>(`${this.api}/api/students/import`, form, this.opts);
  }

  /**
   * Issue a student a new temporary password.
   *
   * The account is marked pending by the server, so the password the admin
   * just typed must be replaced before the student can do anything with it.
   */
  resetStudentPassword(id: number, temporaryPassword: string): Observable<Student> {
    return this.http.put<Student>(
      `${this.api}/api/students/${id}/password`, { temporaryPassword }, this.opts);
  }

  /**
   * Correct a person's biographical detail.
   *
   * PersonFields carries a temporaryPassword because CREATING an account needs
   * one; an edit must not send it, or correcting a spelling would rotate the
   * credential. The field is stripped here rather than left to each caller to
   * remember - the server rejects it too, but one place is better than four.
   */
  updateStudent(id: number, fields: PersonFields): Observable<Student> {
    return this.http.put<Student>(
      `${this.api}/api/students/${id}`, withoutPassword(fields), this.opts);
  }

  updateTeacher(id: number, fields: PersonFields): Observable<Teacher> {
    return this.http.put<Teacher>(
      `${this.api}/api/teachers/${id}`, withoutPassword(fields), this.opts);
  }

  /** Refused with 409 while the student has any submission or recorded mark. */
  deleteStudent(id: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/api/students/${id}`, this.opts);
  }

  /** One student's current teachers, by subject. */
  /**
   * What a named teacher teaches, and whom.
   *
   * TWO CALLS RATHER THAN ONE, mirroring the two endpoints - see
   * SchoolController for why they are separate. Both are admin-only; the
   * server enforces that, and a teacher calling either about a colleague gets
   * 403 rather than a filtered list.
   */
  coursesForTeacher(teacherId: number): Observable<Course[]> {
    return this.http.get<Course[]>(
      `${this.api}/api/teachers/${teacherId}/courses`, this.opts);
  }

  studentsOfTeacher(teacherId: number): Observable<Student[]> {
    return this.http.get<Student[]>(
      `${this.api}/api/teachers/${teacherId}/students`, this.opts);
  }

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

  // ----- admin accounts ------------------------------------------------------------

  /** Onboard another admin. No profile form - see AdminService on the backend. */
  createAdmin(temporaryPassword: string): Observable<{ username: string }> {
    return this.http.post<{ username: string }>(
      `${this.api}/api/admins`, { temporaryPassword }, this.opts);
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
