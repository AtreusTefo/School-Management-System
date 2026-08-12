import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AssignmentService, Course, SchoolClass, Subject } from './assignment.service';
import { AdminService, Student, Teacher } from './admin.service';
import { NotificationService } from './notification.service';
import { ConfirmService } from './confirm.service';
import { FieldErrorComponent } from './field-error.component';
import {
  SUBJECT_CODE_MAX_LENGTH, SUBJECT_NAME_MAX_LENGTH, CLASS_NAME_MAX_LENGTH
} from './validation';

/**
 * '/admin/timetable' - SUBJECTS, CLASSES, COURSES AND ENROLMENT.
 *
 * WHY THIS IS ITS OWN PAGE
 * It used to be a card on the shared dashboard, because a teacher built their
 * own timetable. Writing the timetable became admin-only - it decides who
 * receives every future assignment, so a teacher inventing a class, or naming a
 * colleague as the teacher of a course, put that decision in the wrong hands.
 *
 * Re-gating the card in place looked sufficient and was not. AppComponent sends
 * an admin to '/admin' the moment they sign in, so an admin never opens the
 * dashboard; a card there marked "admins only" is visible to nobody at all.
 * That regression survived a green 147-test backend suite and a clean frontend
 * build, and only appeared when a browser was driven as each role - the same
 * lesson the CSRF and route-guard defects taught.
 *
 * The guard on the route is what keeps other roles out of the page.
 * SchoolService is what keeps them out of the DATA, and that is the one that
 * matters: this page being hidden stops nobody from calling the API directly.
 */
@Component({
  selector: 'app-admin-timetable-page',
  standalone: true,
  imports: [CommonModule, FormsModule, FieldErrorComponent],
  templateUrl: './admin-timetable-page.component.html'
})
export class AdminTimetablePageComponent implements OnInit {

  readonly subjectCodeMaxLength = SUBJECT_CODE_MAX_LENGTH;
  readonly subjectNameMaxLength = SUBJECT_NAME_MAX_LENGTH;
  readonly classNameMaxLength = CLASS_NAME_MAX_LENGTH;

  subjects: Subject[] = [];
  classes: SchoolClass[] = [];
  courses: Course[] = [];
  /*
   * Loaded so the Teacher and Student fields below can be dropdowns rather
   * than free-typed usernames. A typo in a hand-typed username used to fail
   * only once submitted - `findByUsernameOrReject` on the backend has no way
   * to tell "misspelled" from "does not exist" - and a dropdown removes the
   * chance of the typo entirely, rather than catching it after the fact.
   */
  teachers: Teacher[] = [];
  students: Student[] = [];

  /**
   * Which row is being renamed, and the text in its box.
   *
   * One id per kind rather than one shared "editingId", because a subject id
   * and a class id are both plain numbers: a single field would open the class
   * row that happened to share a number with the subject being edited.
   */
  editingSubjectId: number | null = null;
  editSubjectCode = '';
  editSubjectName = '';

  editingClassId: number | null = null;
  editClassName = '';

  newSubjectCode = '';
  newSubjectName = '';
  newClassName = '';

  courseSubjectId: number | null = null;
  courseClassId: number | null = null;
  /**
   * Named explicitly, never defaulted.
   *
   * The server used to fall back to the caller - "I teach this" - which made
   * sense while a teacher built their own timetable. The caller is now always an
   * admin, and an admin cannot teach, so that default would have failed with a
   * message about the caller's role rather than about the missing field.
   */
  courseTeacherUsername = '';

  enrolClassId: number | null = null;
  enrolUsername = '';

  timetableNotice: string | null = null;

  constructor(
    private service: AssignmentService,
    private admin: AdminService,
    private notifications: NotificationService,
    private confirmDialog: ConfirmService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.reload());
    this.reload();
  }

  /** Subjects, classes, courses, and who could be picked for the two dropdowns. */
  reload(): void {
    this.service.getSubjects().subscribe({
      next: (data) => this.subjects = data,
      error: (err) => this.notifications.showError(err, 'Could not load subjects')
    });
    this.service.getClasses().subscribe({
      next: (data) => this.classes = data,
      error: (err) => this.notifications.showError(err, 'Could not load classes')
    });
    this.service.getAllCourses().subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.notifications.showError(err, 'Could not load courses')
    });
    this.admin.listTeachers().subscribe({
      next: (data) => this.teachers = data,
      error: (err) => this.notifications.showError(err, 'Could not load teachers')
    });
    this.admin.listStudents().subscribe({
      next: (data) => this.students = data,
      error: (err) => this.notifications.showError(err, 'Could not load students')
    });
  }

  // ----- renaming -------------------------------------------------------------

  startEditSubject(s: Subject): void {
    this.editingSubjectId = s.id;
    this.editSubjectCode = s.code;
    this.editSubjectName = s.name;
    this.editingClassId = null;
  }

  cancelEditSubject(): void {
    this.editingSubjectId = null;
  }

  saveSubject(): void {
    const id = this.editingSubjectId;
    const code = this.editSubjectCode.trim();
    const name = this.editSubjectName.trim();
    if (id === null || !code || !name) {
      return;
    }
    this.service.renameSubject(id, code, name).subscribe({
      next: (updated) => {
        this.editingSubjectId = null;
        this.timetableNotice = `Subject renamed to "${updated.name}".`;
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not rename the subject')
    });
  }

  startEditClass(c: SchoolClass): void {
    this.editingClassId = c.id;
    this.editClassName = c.name;
    this.editingSubjectId = null;
  }

  cancelEditClass(): void {
    this.editingClassId = null;
  }

  saveClass(): void {
    const id = this.editingClassId;
    const name = this.editClassName.trim();
    if (id === null || !name) {
      return;
    }
    this.service.renameClass(id, name).subscribe({
      next: (updated) => {
        this.editingClassId = null;
        this.timetableNotice = `Class renamed to "${updated.name}".`;
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not rename the class')
    });
  }

  // ----- removing -------------------------------------------------------------
  /*
   * Each of these can legitimately come back 409 - "still taught on a course",
   * "still has students enrolled", "work has already been set". That is not an
   * error to apologise for, it is the answer, and showError puts the server's
   * own sentence in front of the user rather than a generic failure.
   */

  async onDeleteSubject(s: Subject): Promise<void> {
    if (!await this.confirmDialog.ask(`Delete the subject "${s.name}"? This cannot be undone.`)) {
      return;
    }
    this.service.deleteSubject(s.id).subscribe({
      next: () => {
        this.timetableNotice = `Subject "${s.name}" deleted.`;
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete the subject')
    });
  }

  async onDeleteClass(c: SchoolClass): Promise<void> {
    if (!await this.confirmDialog.ask(`Delete the class "${c.name}"? This cannot be undone.`)) {
      return;
    }
    this.service.deleteClass(c.id).subscribe({
      next: () => {
        this.timetableNotice = `Class "${c.name}" deleted.`;
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete the class')
    });
  }

  async onDeleteCourse(c: Course): Promise<void> {
    if (!await this.confirmDialog.ask(`Delete the course "${c.label}"? This cannot be undone.`)) {
      return;
    }
    this.service.deleteCourse(c.id).subscribe({
      next: () => {
        this.timetableNotice = `Course "${c.label}" deleted.`;
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete the course')
    });
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
    const teacher = this.courseTeacherUsername.trim();
    if (this.courseSubjectId === null || this.courseClassId === null || !teacher) {
      return;
    }
    this.service.createCourse(this.courseSubjectId, this.courseClassId, teacher).subscribe({
      next: (created) => {
        this.timetableNotice = `${teacher} now teaches ${created.label}.`;
        this.courseTeacherUsername = '';
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
        // Reload so the per-class student counts in the dropdown stay honest.
        this.reload();
      },
      error: (err) => this.notifications.showError(err, 'Could not enrol that student')
    });
  }
}
