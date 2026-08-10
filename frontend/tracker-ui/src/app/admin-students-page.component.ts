import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AdminService, ImportSummary, Student } from './admin.service';
import { AssignmentService, SchoolClass } from './assignment.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';
import {
  CONTACT_EMAIL_MAX_LENGTH, NATIONAL_ID_MAX_LENGTH,
  PASSWORD_MIN_LENGTH, PERSON_NAME_MAX_LENGTH
} from './validation';

/**
 * '/admin/students' - onboard (one at a time or in bulk) and browse students.
 * Assigning or unassigning a teacher happens one student at a time on the
 * detail page this list links to, since that is where "which teacher, for
 * which subject" actually has to be decided.
 */
@Component({
  selector: 'app-admin-students-page',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './admin-students-page.component.html'
})
export class AdminStudentsPageComponent implements OnInit {

  readonly personNameMaxLength = PERSON_NAME_MAX_LENGTH;
  readonly nationalIdMaxLength = NATIONAL_ID_MAX_LENGTH;
  readonly contactEmailMaxLength = CONTACT_EMAIL_MAX_LENGTH;
  readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  students: Student[] = [];
  classes: SchoolClass[] = [];
  loading = false;
  notice: string | null = null;

  showCreate = false;
  firstName = '';
  middleName = '';
  lastName = '';
  nationalId = '';
  dateOfBirth = '';
  email = '';
  dateEnrolled = '';
  temporaryPassword = '';
  classId: number | null = null;

  showImport = false;
  importFile: File | null = null;
  importing = false;
  importResult: ImportSummary | null = null;

  constructor(
    private admin: AdminService,
    private service: AssignmentService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.load());
    this.load();
  }

  load(): void {
    this.loading = true;
    this.admin.listStudents().subscribe({
      next: (data) => {
        this.loading = false;
        this.students = data;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the student list');
      }
    });
    this.service.getClasses().subscribe({
      next: (data) => this.classes = data,
      error: (err) => this.notifications.showError(err, 'Could not load classes')
    });
  }

  // ----- create ------------------------------------------------------------------

  openCreate(): void {
    this.showCreate = true;
    this.showImport = false;
    this.firstName = '';
    this.middleName = '';
    this.lastName = '';
    this.nationalId = '';
    this.dateOfBirth = '';
    this.email = '';
    this.dateEnrolled = '';
    this.temporaryPassword = '';
    this.classId = null;
  }

  closeCreate(): void {
    this.showCreate = false;
  }

  get canCreate(): boolean {
    return this.firstName.trim().length > 0
        && this.lastName.trim().length > 0
        && this.nationalId.trim().length > 0
        && this.dateOfBirth.length > 0
        && this.temporaryPassword.length >= PASSWORD_MIN_LENGTH;
  }

  onCreate(): void {
    if (!this.canCreate) {
      return;
    }
    this.admin.createStudent({
      firstName: this.firstName.trim(),
      middleName: this.middleName.trim() || null,
      lastName: this.lastName.trim(),
      nationalId: this.nationalId.trim(),
      dateOfBirth: this.dateOfBirth,
      email: this.email.trim() || null,
      dateEnrolled: this.dateEnrolled || null,
      temporaryPassword: this.temporaryPassword
    }, this.classId ?? undefined).subscribe({
      next: (created) => {
        this.notice = `Created '${created.fullName}' - id ${created.externalId}. `
          + `They must change their password at first sign-in.`;
        this.showCreate = false;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not create that account')
    });
  }

  // ----- bulk import ---------------------------------------------------------------

  openImport(): void {
    this.showImport = true;
    this.showCreate = false;
    this.importFile = null;
    this.importResult = null;
  }

  closeImport(): void {
    this.showImport = false;
  }

  onImportFileChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.importFile = input.files && input.files.length > 0 ? input.files[0] : null;
  }

  onImport(): void {
    if (!this.importFile) {
      return;
    }
    this.importing = true;
    this.admin.importStudents(this.importFile).subscribe({
      next: (result) => {
        this.importing = false;
        this.importResult = result;
        this.load();
      },
      error: (err) => {
        this.importing = false;
        this.notifications.showError(err, 'Could not import that file');
      }
    });
  }
}
