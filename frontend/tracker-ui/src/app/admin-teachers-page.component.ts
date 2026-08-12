import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdminService, ImportSummary, PersonFields, Teacher } from './admin.service';
import { AssignmentService, Course, SchoolClass, Subject } from './assignment.service';
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
 * '/admin/teachers' - onboard (one at a time or in bulk), reset the password
 * of, or remove a teacher account. One row per TEACHER account, not per
 * course - which classes a teacher runs is the student-assignment page's
 * concern, not this one's.
 *
 * NO "RENAME" - a teacher's sign-in id (TCH20260001) is generated once and
 * never edited; see AdminService on the backend for why a system-issued id
 * that could later be changed would not be one any more. Resetting the
 * password is the only per-account action left besides delete.
 */
@Component({
  selector: 'app-admin-teachers-page',
  standalone: true,
  imports: [CommonModule, FormsModule, FieldErrorComponent, PasswordStrengthValidatorDirective],
  templateUrl: './admin-teachers-page.component.html'
})
export class AdminTeachersPageComponent implements OnInit {

  readonly personNameMaxLength = PERSON_NAME_MAX_LENGTH;
  readonly nationalIdMaxLength = NATIONAL_ID_MAX_LENGTH;
  readonly contactEmailMaxLength = CONTACT_EMAIL_MAX_LENGTH;
  readonly passwordMinLength = PASSWORD_MIN_LENGTH;
  readonly phoneLocalPatternSource = PHONE_LOCAL_PATTERN.source;
  readonly phoneCountryCode = PHONE_COUNTRY_CODE;

  teachers: Teacher[] = [];
  subjects: Subject[] = [];
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
  phoneLocal = '';
  dateEnrolled = '';
  temporaryPassword = '';
  confirmTemporaryPassword = '';
  initialSubjectId: number | null = null;
  initialClassId: number | null = null;

  resettingId: number | null = null;

  /* Editing a teacher, mirroring the student page exactly. Held separately from
     resettingId so opening one box cannot leave the other open on a different
     row. */
  editingId: number | null = null;
  editFirstName = '';
  editMiddleName = '';
  editLastName = '';
  editNationalId = '';
  editDateOfBirth = '';
  editEmail = '';
  editPhoneLocal = '';
  resetTemporaryPassword = '';
  confirmResetTemporaryPassword = '';

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
    this.admin.listTeachers().subscribe({
      next: (data) => {
        this.loading = false;
        this.teachers = data;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the teacher list');
      }
    });
    this.service.getSubjects().subscribe({
      next: (data) => this.subjects = data,
      error: (err) => this.notifications.showError(err, 'Could not load subjects')
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
    this.initialSubjectId = null;
    this.initialClassId = null;
  }

  closeCreate(): void {
    this.showCreate = false;
  }

  get passwordsMatch(): boolean {
    return this.temporaryPassword === this.confirmTemporaryPassword;
  }

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
    const initialCourses = (this.initialSubjectId !== null && this.initialClassId !== null)
      ? [{ subjectId: this.initialSubjectId, classId: this.initialClassId }]
      : undefined;

    this.admin.createTeacher({
      firstName: this.firstName.trim(),
      middleName: this.middleName.trim() || null,
      lastName: this.lastName.trim(),
      nationalId: this.nationalId.trim(),
      dateOfBirth: this.dateOfBirth,
      email: this.email.trim() || null,
      phone: PHONE_COUNTRY_CODE + this.phoneLocal,
      dateEnrolled: this.dateEnrolled || null,
      temporaryPassword: this.temporaryPassword
    }, initialCourses).subscribe({
      next: (created) => {
        this.notice = `Created '${created.fullName}' - id ${created.externalId}. `
          + `It must change its password at first sign-in.`;
        this.showCreate = false;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not create that account')
    });
  }

  // ----- reset password --------------------------------------------------------------

  startReset(teacher: Teacher): void {
    this.resettingId = teacher.id;
    this.resetTemporaryPassword = '';
    this.confirmResetTemporaryPassword = '';
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

  saveReset(teacher: Teacher): void {
    if (!this.canSaveReset) {
      return;
    }
    this.admin.resetTeacherPassword(teacher.id, this.resetTemporaryPassword).subscribe({
      next: () => {
        this.resettingId = null;
        this.notice = `Issued a new temporary password for '${teacher.username}'.`;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not reset that password')
    });
  }

  // ----- edit ------------------------------------------------------------------

  startEdit(teacher: Teacher): void {
    this.editingId = teacher.id;
    this.resettingId = null;
    this.editFirstName = teacher.firstName ?? '';
    this.editMiddleName = teacher.middleName ?? '';
    this.editLastName = teacher.lastName ?? '';
    this.editNationalId = teacher.nationalId ?? '';
    this.editDateOfBirth = teacher.dateOfBirth ?? '';
    this.editEmail = teacher.contactEmail ?? '';
    this.editPhoneLocal = teacher.phone?.startsWith(PHONE_COUNTRY_CODE)
      ? teacher.phone.slice(PHONE_COUNTRY_CODE.length) : '';
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

  /** The username is absent by design - see the student page for why. */
  saveEdit(teacher: Teacher): void {
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

    this.admin.updateTeacher(teacher.id, fields).subscribe({
      next: () => {
        this.editingId = null;
        this.notice = `Updated '${teacher.username}'.`;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not save those changes')
    });
  }

  // ----- delete ------------------------------------------------------------------

  async onDelete(teacher: Teacher): Promise<void> {
    if (!await this.confirmDialog.ask(`Delete the account '${teacher.username}'? This cannot be undone.`)) {
      return;
    }
    this.admin.deleteTeacher(teacher.id).subscribe({
      next: () => this.load(),
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
    this.admin.importTeachers(this.importFile).subscribe({
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
