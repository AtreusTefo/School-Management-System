import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';
import { AdminService, Student, Teacher } from './admin.service';
import { NotificationService } from './notification.service';
import { AccountRow, AdminAccountsTableComponent } from './admin-accounts-table.component';

/**
 * '/admin/accounts' - every student, teacher and admin account, merged into
 * one searchable, sortable table.
 *
 * WHY THIS EXISTS ALONGSIDE '/admin/students' AND '/admin/teachers'
 * Those two pages already list their own role, with the reset/edit/delete
 * actions each needs. Neither shows the other role, and NEITHER shows admin
 * accounts at all - there was previously no view of the whole staff and
 * student body in one place, and no view of admin accounts anywhere in the
 * UI. This page answers "show me everyone", read-only; it does not duplicate
 * the write actions those pages already own.
 *
 * THREE REQUESTS, JOINED, NOT ONE NEW ENDPOINT
 * The three underlying lists are each already admin-only and independently
 * useful (GET /api/students, /api/teachers, /api/admins). A fourth endpoint
 * that unions them server-side would duplicate that authority check and give
 * the same data two different shapes to keep in sync. forkJoin combines the
 * three client-side once they all answer, which costs three round trips
 * instead of one but keeps every list's meaning exactly what it already is.
 */
@Component({
  selector: 'app-admin-accounts-page',
  standalone: true,
  imports: [CommonModule, AdminAccountsTableComponent],
  templateUrl: './admin-accounts-page.component.html'
})
export class AdminAccountsPageComponent implements OnInit {

  accounts: AccountRow[] = [];
  loading = false;

  constructor(
    private admin: AdminService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.load());
    this.load();
  }

  get studentCount(): number {
    return this.accounts.filter(a => a.role === 'STUDENT').length;
  }

  get teacherCount(): number {
    return this.accounts.filter(a => a.role === 'TEACHER').length;
  }

  get adminCount(): number {
    return this.accounts.filter(a => a.role === 'ADMIN').length;
  }

  load(): void {
    this.loading = true;
    forkJoin({
      students: this.admin.listStudents(),
      teachers: this.admin.listTeachers(),
      admins: this.admin.listAdmins()
    }).subscribe({
      next: ({ students, teachers, admins }) => {
        this.loading = false;
        this.accounts = [
          ...students.map(s => this.fromStudent(s)),
          ...teachers.map(t => this.fromTeacher(t)),
          ...admins.map(a => ({
            role: 'ADMIN' as const,
            id: a.id,
            username: a.username,
            fullName: null,
            nationalId: null,
            dateOfBirth: null,
            contactEmail: null,
            phone: null,
            className: null,
            dateEnrolled: null,
            mustChangePassword: a.mustChangePassword
          }))
        ];
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the account list');
      }
    });
  }

  private fromStudent(s: Student): AccountRow {
    return {
      role: 'STUDENT',
      id: s.id,
      username: s.username,
      fullName: s.fullName,
      nationalId: s.nationalId,
      dateOfBirth: s.dateOfBirth,
      contactEmail: s.contactEmail,
      phone: s.phone,
      className: s.className,
      dateEnrolled: s.dateEnrolled,
      mustChangePassword: s.mustChangePassword
    };
  }

  private fromTeacher(t: Teacher): AccountRow {
    return {
      role: 'TEACHER',
      id: t.id,
      username: t.username,
      fullName: t.fullName,
      nationalId: t.nationalId,
      dateOfBirth: t.dateOfBirth,
      contactEmail: t.contactEmail,
      phone: t.phone,
      // A teacher is not enrolled in a class - see Course for why "which
      // classes a teacher teaches" is a different relationship entirely,
      // expressed the other way round from a student's enrolment.
      className: null,
      dateEnrolled: t.dateEnrolled,
      mustChangePassword: t.mustChangePassword
    };
  }
}
