import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AssignmentService, Course, SchoolClass, Subject } from './assignment.service';
import { SessionService } from './session.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';
import {
  SUBJECT_CODE_MAX_LENGTH, SUBJECT_NAME_MAX_LENGTH,
  CLASS_NAME_MAX_LENGTH, USERNAME_MAX_LENGTH
} from './validation';

/**
 * THE LANDING PAGE
 * -----------------
 * What is left once "Work I have set", "Marking queue" and "Reports" each
 * became their own page: a student or teacher's own timetable, quick links to
 * the three working pages, and - teachers only - the timetable itself
 * (subjects, classes, courses, enrolment). Creating accounts moved to the
 * admin panel entirely - see /admin/students and /admin/teachers - since
 * onboarding is now admin-only.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './dashboard.component.html'
})
export class DashboardComponent implements OnInit {

  readonly usernameMaxLength = USERNAME_MAX_LENGTH;
  readonly subjectCodeMaxLength = SUBJECT_CODE_MAX_LENGTH;
  readonly subjectNameMaxLength = SUBJECT_NAME_MAX_LENGTH;
  readonly classNameMaxLength = CLASS_NAME_MAX_LENGTH;

  courses: Course[] = [];
  subjects: Subject[] = [];
  classes: SchoolClass[] = [];

  showTimetable = false;
  newSubjectCode = '';
  newSubjectName = '';
  newClassName = '';
  courseSubjectId: number | null = null;
  courseClassId: number | null = null;
  enrolClassId: number | null = null;
  enrolUsername = '';
  timetableNotice: string | null = null;

  constructor(
    private service: AssignmentService,
    public session: SessionService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.loadCourses());
    this.loadCourses();
  }

  get isTeacher(): boolean {
    return this.session.isTeacher;
  }

  get mySubjects(): string[] {
    return [...new Set(this.courses.map(c => c.subjectName))].sort();
  }

  get myTeachers(): string[] {
    return [...new Set(this.courses.map(c => c.teacherUsername))].sort();
  }

  loadCourses(): void {
    this.service.getCourses().subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.notifications.showError(err, 'Could not load your courses')
    });
  }

  // ----- the timetable (teachers only) ----------------------------------------

  openTimetable(): void {
    this.showTimetable = true;
    this.timetableNotice = null;
    this.service.getSubjects().subscribe({
      next: (data) => this.subjects = data,
      error: (err) => this.notifications.showError(err, 'Could not load subjects')
    });
    this.service.getClasses().subscribe({
      next: (data) => this.classes = data,
      error: (err) => this.notifications.showError(err, 'Could not load classes')
    });
  }

  closeTimetable(): void {
    this.showTimetable = false;
  }

  onCreateSubject(): void {
    const code = this.newSubjectCode.trim();
    const name = this.newSubjectName.trim();
    if (!code || !name) {
      return;
    }
    this.service.createSubject(code, name).subscribe({
      next: (created) => {
        this.subjects = [...this.subjects, created].sort((a, b) => a.name.localeCompare(b.name));
        this.newSubjectCode = '';
        this.newSubjectName = '';
        this.timetableNotice = `Subject "${created.name}" added.`;
      },
      error: (err) => this.notifications.showError(err, 'Could not add the subject')
    });
  }

  onCreateClass(): void {
    const name = this.newClassName.trim();
    if (!name) {
      return;
    }
    this.service.createClass(name).subscribe({
      next: (created) => {
        this.classes = [...this.classes, created].sort((a, b) => a.name.localeCompare(b.name));
        this.newClassName = '';
        this.timetableNotice = `Class "${created.name}" added.`;
      },
      error: (err) => this.notifications.showError(err, 'Could not add the class')
    });
  }

  onCreateCourse(): void {
    if (this.courseSubjectId === null || this.courseClassId === null) {
      return;
    }
    this.service.createCourse(this.courseSubjectId, this.courseClassId, null).subscribe({
      next: (created) => {
        this.timetableNotice = `You now teach ${created.label}.`;
        this.loadCourses();
      },
      error: (err) => this.notifications.showError(err, 'Could not set up that course')
    });
  }

  onEnrol(): void {
    const username = this.enrolUsername.trim();
    if (this.enrolClassId === null || !username) {
      return;
    }
    this.service.enrolStudent(this.enrolClassId, username).subscribe({
      next: () => {
        this.timetableNotice = `"${username}" enrolled.`;
        this.enrolUsername = '';
        this.openTimetable();   // refresh the counts
      },
      error: (err) => this.notifications.showError(err, 'Could not enrol that student')
    });
  }
}
