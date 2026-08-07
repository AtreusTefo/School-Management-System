import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AdminService, Student } from './admin.service';
import { NotificationService } from './notification.service';

/**
 * '/admin/students' - every student, with their class if they have one. This
 * page only lists; assigning or unassigning a teacher happens one student at
 * a time on the detail page it links to, since that is where "which teacher,
 * for which subject" actually has to be decided.
 */
@Component({
  selector: 'app-admin-students-page',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './admin-students-page.component.html'
})
export class AdminStudentsPageComponent implements OnInit {

  students: Student[] = [];
  loading = false;

  constructor(private admin: AdminService, private notifications: NotificationService) {}

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
  }
}
