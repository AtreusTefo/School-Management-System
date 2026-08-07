import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminService, Student, Teacher } from './admin.service';
import { AssignmentService, Course, Subject } from './assignment.service';
import { NotificationService } from './notification.service';

/**
 * '/admin/students/:id' - one student's current teachers, and the form to
 * assign or unassign one.
 *
 * BACKED BY Course, NOT A DIRECT STUDENT-TEACHER TABLE - see
 * SchoolService.assignTeacherToStudent on the backend. "Assign a teacher"
 * here means "this teacher now teaches this student's class one subject";
 * the subject is a required choice in the form below for exactly that
 * reason - there is no such thing as assigning a teacher to a student
 * without saying what they teach them.
 *
 * SUBSCRIBES TO route.paramMap RATHER THAN READING IT ONCE
 * Angular reuses a routed component when only its route PARAMETER changes
 * (the route configuration itself is unchanged), so ngOnInit would not fire
 * again for a link from one student's detail page straight to another's.
 * Reading the id reactively is what makes that navigation actually reload
 * this page's data instead of silently continuing to show the previous
 * student under the new URL.
 */
@Component({
  selector: 'app-admin-student-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './admin-student-detail.component.html'
})
export class AdminStudentDetailComponent implements OnInit {

  studentId!: number;
  student: Student | null = null;
  currentTeachers: Course[] = [];
  teachers: Teacher[] = [];
  subjects: Subject[] = [];
  loading = false;

  showAssign = false;
  assignTeacherId: number | null = null;
  assignSubjectId: number | null = null;

  notice: string | null = null;

  constructor(
    private route: ActivatedRoute,
    private admin: AdminService,
    private service: AssignmentService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.load());
    this.route.paramMap.subscribe((params) => {
      const id = Number(params.get('id'));
      if (!Number.isNaN(id)) {
        this.studentId = id;
        this.load();
      }
    });
  }

  load(): void {
    this.loading = true;

    this.admin.listStudents().subscribe({
      next: (data) => {
        this.student = data.find((s) => s.id === this.studentId) ?? null;
      },
      error: (err) => this.notifications.showError(err, 'Could not load the student')
    });

    this.admin.listTeachersForStudent(this.studentId).subscribe({
      next: (data) => {
        this.loading = false;
        this.currentTeachers = data;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, "Could not load this student's teachers");
      }
    });

    this.admin.listTeachers().subscribe({
      next: (data) => this.teachers = data,
      error: (err) => this.notifications.showError(err, 'Could not load the teacher list')
    });

    this.service.getSubjects().subscribe({
      next: (data) => this.subjects = data,
      error: (err) => this.notifications.showError(err, 'Could not load the subject list')
    });
  }

  // ----- assign --------------------------------------------------------------

  openAssign(): void {
    this.showAssign = true;
    this.assignTeacherId = null;
    this.assignSubjectId = null;
  }

  closeAssign(): void {
    this.showAssign = false;
  }

  get canAssign(): boolean {
    return this.assignTeacherId !== null && this.assignSubjectId !== null;
  }

  onAssign(): void {
    if (!this.canAssign) {
      return;
    }
    this.admin.assignTeacher(this.studentId, this.assignTeacherId!, this.assignSubjectId!).subscribe({
      next: (course) => {
        this.notice = `'${course.teacherUsername}' now teaches '${course.subjectName}' to this student.`;
        this.showAssign = false;
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not assign that teacher')
    });
  }

  // ----- unassign --------------------------------------------------------------

  /**
   * Course carries the teacher's USERNAME, not their id - the id is needed
   * for the unassign endpoint, so it is resolved from the teacher list
   * already loaded for the assign form, rather than adding a second backend
   * shape just to carry one more number.
   */
  private teacherIdFor(username: string): number | undefined {
    return this.teachers.find((t) => t.username === username)?.id;
  }

  onUnassign(course: Course): void {
    const teacherId = this.teacherIdFor(course.teacherUsername);
    if (teacherId === undefined) {
      return;
    }
    if (!confirm(`Unassign '${course.teacherUsername}' from teaching `
        + `'${course.subjectName}' to this student?`)) {
      return;
    }
    this.admin.unassignTeacher(this.studentId, teacherId, course.subjectId).subscribe({
      next: () => this.load(),
      error: (err) => this.notifications.showError(err, 'Could not unassign that teacher')
    });
  }
}
