import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AdminService, ImportSummary, PersonFields, Student } from './admin.service';
import { AssignmentService, SchoolClass } from './assignment.service';
import { NotificationService } from './notification.service';
import { ConfirmService } from './confirm.service';
import { FieldErrorComponent } from './field-error.component';
import { PasswordStrengthValidatorDirective } from './password-strength-validator.directive';
import {
  CONTACT_EMAIL_MAX_LENGTH, NATIONAL_ID_MAX_LENGTH,
  PASSWORD_MIN_LENGTH, PERSON_NAME_MAX_LENGTH,
  PHONE_COUNTRY_CODE, PHONE_LOCAL_PATTERN
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
  imports: [
    CommonModule, FormsModule, RouterLink, FieldErrorComponent, PasswordStrengthValidatorDirective
  ],
  templateUrl: './admin-students-page.component.html'
})
export class AdminStudentsPageComponent implements OnInit {

  readonly personNameMaxLength = PERSON_NAME_MAX_LENGTH;
  readonly nationalIdMaxLength = NATIONAL_ID_MAX_LENGTH;
  readonly contactEmailMaxLength = CONTACT_EMAIL_MAX_LENGTH;
  readonly passwordMinLength = PASSWORD_MIN_LENGTH;
  /** The HTML `pattern` attribute takes a string source, not a RegExp. */
  readonly phoneLocalPatternSource = PHONE_LOCAL_PATTERN.source;
  readonly phoneCountryCode = PHONE_COUNTRY_CODE;

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
  /** Just the 8 digits typed - +267 is fixed UI, joined on submit. */
  phoneLocal = '';
  dateEnrolled = '';
  temporaryPassword = '';
  confirmTemporaryPassword = '';
  classId: number | null = null;

  /*
   * Reset, edit and delete: the three things a student account could not
   * previously have done to it. A student whose temporary password was lost had
   * no recovery path at all, because the password is a one-way hash.
   *
   * Each holds the id of the row it is open on, rather than a shared "editingId",
   * so opening the reset box on one row cannot leave the edit form open on
   * another.
   */
  resettingId: number | null = null;
  resetTemporaryPassword = '';
  confirmResetTemporaryPassword = '';

  editingId: number | null = null;
  editFirstName = '';
  editMiddleName = '';
  editLastName = '';
  editNationalId = '';
  editDateOfBirth = '';
  editEmail = '';
  editPhoneLocal = '';

  showImport = false;
  importFile: File | null = null;
  importing = false;
  importResult: ImportSummary | null = null;

  constructor(
    private admin: AdminService,
    private service: AssignmentService,
    private notifications: NotificationService,
    private confirmDialog: ConfirmService
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
    this.phoneLocal = '';
    this.dateEnrolled = '';
    this.temporaryPassword = '';
    this.confirmTemporaryPassword = '';
    this.classId = null;
  }

  closeCreate(): void {
    this.showCreate = false;
  }

  get passwordsMatch(): boolean {
    return this.temporaryPassword === this.confirmTemporaryPassword;
  }

  /** Fed to app-field-error's externalError - see FieldErrorComponent's own comment on why. */
  get confirmPasswordError(): string | null {
    return this.confirmTemporaryPassword.length > 0 && !this.passwordsMatch
      ? 'The two passwords do not match.'
      : null;
  }

  get canCreate(): boolean {
    return this.firstName.trim().length > 0
        && this.lastName.trim().length > 0
        && this.nationalId.trim().length > 0
        && this.dateOfBirth.length > 0
        && PHONE_LOCAL_PATTERN.test(this.phoneLocal)
        && this.temporaryPassword.length >= PASSWORD_MIN_LENGTH
        && this.passwordsMatch;
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
      phone: PHONE_COUNTRY_CODE + this.phoneLocal,
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

  // ----- reset password ------------------------------------------------------

  startReset(student: Student): void {
    this.resettingId = student.id;
    this.resetTemporaryPassword = '';
    this.confirmResetTemporaryPassword = '';
    this.editingId = null;
  }

  cancelReset(): void {
    this.resettingId = null;
  }

  get resetPasswordsMatch(): boolean {
    return this.resetTemporaryPassword === this.confirmResetTemporaryPassword;
  }

  get confirmResetPasswordError(): string | null {
    return this.confirmResetTemporaryPassword.length > 0 && !this.resetPasswordsMatch
      ? 'The two passwords do not match.'
      : null;
  }

  get canSaveReset(): boolean {
    return this.resetTemporaryPassword.length >= PASSWORD_MIN_LENGTH && this.resetPasswordsMatch;
  }

  saveReset(student: Student): void {
    if (!this.canSaveReset) {
      return;
    }
    this.admin.resetStudentPassword(student.id, this.resetTemporaryPassword).subscribe({
      next: () => {
        this.resettingId = null;
        this.notice = `Issued a new temporary password for '${student.username}'. `
                    + `They must change it before they can use the account.`;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not reset that password')
    });
  }

  // ----- edit ------------------------------------------------------------------

  startEdit(student: Student): void {
    this.editingId = student.id;
    this.resettingId = null;
    this.editFirstName = student.firstName ?? '';
    this.editMiddleName = student.middleName ?? '';
    this.editLastName = student.lastName ?? '';
    this.editNationalId = student.nationalId ?? '';
    this.editDateOfBirth = student.dateOfBirth ?? '';
    this.editEmail = student.contactEmail ?? '';
    // A profile saved before this column existed has no phone at all - the
    // local field starts blank rather than showing a bare "+267".
    this.editPhoneLocal = student.phone?.startsWith(PHONE_COUNTRY_CODE)
      ? student.phone.slice(PHONE_COUNTRY_CODE.length) : '';
  }

  cancelEdit(): void {
    this.editingId = null;
  }

  get canSaveEdit(): boolean {
    return this.editFirstName.trim() !== ''
        && this.editLastName.trim() !== ''
        && this.editNationalId.trim() !== ''
        && this.editDateOfBirth !== ''
        && PHONE_LOCAL_PATTERN.test(this.editPhoneLocal);
  }

  /**
   * The username is deliberately absent from this form.
   *
   * It is the generated login id (STU20260001) and every audit entry names the
   * student by it; editing it would detach somebody from their own history. The
   * server has no username field on its update DTO either, so the rule holds
   * whoever calls it.
   */
  saveEdit(student: Student): void {
    if (!this.canSaveEdit) {
      return;
    }
    const fields = {
      firstName: this.editFirstName.trim(),
      middleName: this.editMiddleName.trim() || null,
      lastName: this.editLastName.trim(),
      nationalId: this.editNationalId.trim(),
      dateOfBirth: this.editDateOfBirth,
      email: this.editEmail.trim() || null,
      phone: PHONE_COUNTRY_CODE + this.editPhoneLocal,
      temporaryPassword: ''   // stripped by AdminService before the request
    } as PersonFields;

    this.admin.updateStudent(student.id, fields).subscribe({
      next: () => {
        this.editingId = null;
        this.notice = `Updated '${student.username}'.`;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not save those changes')
    });
  }

  // ----- delete ----------------------------------------------------------------

  /**
   * Refused with 409 while the student has any submission or recorded mark, and
   * that refusal is the point: removing the account would destroy the record
   * that work was handed in and judged. The server's own sentence reaches the
   * user through showError.
   */
  async onDelete(student: Student): Promise<void> {
    if (!await this.confirmDialog.ask(`Delete the account '${student.username}'? This cannot be undone.`)) {
      return;
    }
    this.admin.deleteStudent(student.id).subscribe({
      next: () => {
        this.notice = `Deleted '${student.username}'.`;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete that account')
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
