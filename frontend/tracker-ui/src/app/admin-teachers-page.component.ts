import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdminService, Teacher } from './admin.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';
import { PASSWORD_MIN_LENGTH, USERNAME_MAX_LENGTH } from './validation';

/**
 * '/admin/teachers' - onboard, rename, reset the password of, or remove a
 * teacher account. One row per TEACHER account, not per course - which
 * classes a teacher runs is the student-assignment page's concern, not this
 * one's.
 *
 * RENAMING AND RESETTING A PASSWORD ARE TWO SEPARATE INLINE FORMS, not one
 * combined edit row. They are different admin actions with different
 * consequences (a username change is cosmetic; a password reset marks the
 * account pending again), and combining them into one "Edit" button with two
 * optional fields would make it unclear which one actually happened when
 * only one field was filled in.
 */
@Component({
  selector: 'app-admin-teachers-page',
  standalone: true,
  imports: [CommonModule, FormsModule, FieldErrorComponent],
  templateUrl: './admin-teachers-page.component.html'
})
export class AdminTeachersPageComponent implements OnInit {

  readonly usernameMaxLength = USERNAME_MAX_LENGTH;
  readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  teachers: Teacher[] = [];
  loading = false;
  notice: string | null = null;

  showCreate = false;
  newUsername = '';
  newTemporaryPassword = '';

  renamingId: number | null = null;
  renameUsername = '';

  resettingId: number | null = null;
  resetTemporaryPassword = '';

  constructor(private admin: AdminService, private notifications: NotificationService) {}

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
  }

  // ----- create ------------------------------------------------------------------

  openCreate(): void {
    this.showCreate = true;
    this.newUsername = '';
    this.newTemporaryPassword = '';
  }

  closeCreate(): void {
    this.showCreate = false;
  }

  get canCreate(): boolean {
    return this.newUsername.trim().length > 0
        && this.newTemporaryPassword.length >= PASSWORD_MIN_LENGTH;
  }

  onCreate(): void {
    if (!this.canCreate) {
      return;
    }
    this.admin.createTeacher(this.newUsername.trim(), this.newTemporaryPassword).subscribe({
      next: (created) => {
        this.notice = `Created '${created.username}'. It must change its password at first sign-in.`;
        this.showCreate = false;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not create that account')
    });
  }

  // ----- rename --------------------------------------------------------------------

  startRename(teacher: Teacher): void {
    this.resettingId = null;
    this.renamingId = teacher.id;
    this.renameUsername = teacher.username;
  }

  cancelRename(): void {
    this.renamingId = null;
  }

  saveRename(id: number): void {
    const username = this.renameUsername.trim();
    if (!username) {
      return;
    }
    this.admin.renameTeacher(id, username).subscribe({
      next: () => {
        this.renamingId = null;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not rename that account')
    });
  }

  // ----- reset password --------------------------------------------------------------

  startReset(teacher: Teacher): void {
    this.renamingId = null;
    this.resettingId = teacher.id;
    this.resetTemporaryPassword = '';
  }

  cancelReset(): void {
    this.resettingId = null;
  }

  get canSaveReset(): boolean {
    return this.resetTemporaryPassword.length >= PASSWORD_MIN_LENGTH;
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

  // ----- delete ------------------------------------------------------------------

  onDelete(teacher: Teacher): void {
    if (!confirm(`Delete the account '${teacher.username}'? This cannot be undone.`)) {
      return;
    }
    this.admin.deleteTeacher(teacher.id).subscribe({
      next: () => this.load(),
      error: (err) => this.notifications.showError(err, 'Could not delete that account')
    });
  }
}
