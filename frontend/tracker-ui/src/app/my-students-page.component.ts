import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AssignmentService } from './assignment.service';
// Student is exported by admin.service.ts, not re-exported through
// assignment.service.ts - see the type-only import note on getMyStudents().
import { Student } from './admin.service';
import { NotificationService } from './notification.service';
import { AccountRow, AdminAccountsTableComponent } from './admin-accounts-table.component';

/**
 * '/my-students' - TEACHER ONLY. Full biographical detail for every distinct
 * student across the signed-in teacher's own courses.
 *
 * Reuses AdminAccountsTableComponent rather than a second copy of the same
 * DataTables wiring - that component only ever draws whatever rows it is
 * handed and has no admin-specific behaviour baked in, the same way
 * SubmissionTableComponent already serves both a teacher's marking queue and
 * a student's own work list. The Role column it draws will always read
 * STUDENT here, which is a little redundant but harmless; the Class column is
 * the one that actually matters on this page.
 *
 * SCOPED BY THE SERVER, NOT BY THIS COMPONENT
 * GET /api/teachers/me/students already returns only the students in classes
 * the calling teacher has a course in - see SchoolService.listMyStudents.
 * This page trusts that scope completely and applies none of its own; a
 * teacher's dashboard link into a school-wide list would be the same class of
 * mistake AdminService's own admin-only guards exist to prevent, just
 * reached from the other side of the person/course relationship.
 */
@Component({
  selector: 'app-my-students-page',
  standalone: true,
  imports: [CommonModule, AdminAccountsTableComponent],
  templateUrl: './my-students-page.component.html'
})
export class MyStudentsPageComponent implements OnInit {

  students: AccountRow[] = [];
  loading = false;

  constructor(
    private service: AssignmentService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.load());
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.getMyStudents().subscribe({
      next: (data) => {
        this.loading = false;
        this.students = data.map(s => this.fromStudent(s));
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load your students');
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
}
