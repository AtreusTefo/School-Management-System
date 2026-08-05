import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Assignment, AssignmentService, Course } from './assignment.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';
import { ASSIGNMENT_DESCRIPTION_MAX_LENGTH, ASSIGNMENT_TITLE_MAX_LENGTH } from './validation';

/**
 * "WORK I HAVE SET" - its own page, teacher only (see teacherGuard).
 *
 * One row per ASSIGNMENT, not per student - "what have I set, and to whom"
 * is a different question from the marking queue's "how is each student
 * getting on", which is why it was always drawn as a separate card and now
 * gets a URL of its own. Creating, correcting and deleting all belong here,
 * because they act on the assignment, not on any one student's copy of it.
 */
@Component({
  selector: 'app-assignments-page',
  standalone: true,
  imports: [CommonModule, FormsModule, FieldErrorComponent],
  templateUrl: './assignments-page.component.html'
})
export class AssignmentsPageComponent implements OnInit {

  readonly titleMaxLength = ASSIGNMENT_TITLE_MAX_LENGTH;
  readonly descriptionMaxLength = ASSIGNMENT_DESCRIPTION_MAX_LENGTH;

  courses: Course[] = [];
  assignments: Assignment[] = [];
  loading = false;

  newTitle = '';
  newDescription = '';
  newDueDate = '';
  selectedCourseIds = new Set<number>();
  courseTouched = false;
  showSetWork = false;

  editingAssignmentId: number | null = null;
  editTitle = '';
  editDescription = '';
  editDueDate = '';

  notice: string | null = null;

  constructor(
    private service: AssignmentService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.loadEverything());
    this.loadEverything();
  }

  loadEverything(): void {
    this.loading = true;
    this.service.getCourses().subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.notifications.showError(err, 'Could not load your courses')
    });
    this.service.getAssignments().subscribe({
      next: (data) => {
        this.loading = false;
        this.assignments = data;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the work you have set');
      }
    });
  }

  // ----- setting work ----------------------------------------------------------

  openSetWork(): void {
    this.showSetWork = true;
    this.newTitle = '';
    this.newDescription = '';
    this.newDueDate = '';
    this.selectedCourseIds.clear();
    this.courseTouched = false;
  }

  closeSetWork(): void {
    this.showSetWork = false;
  }

  toggleCourse(courseId: number): void {
    this.courseTouched = true;
    if (this.selectedCourseIds.has(courseId)) {
      this.selectedCourseIds.delete(courseId);
    } else {
      this.selectedCourseIds.add(courseId);
    }
  }

  isCourseSelected(courseId: number): boolean {
    return this.selectedCourseIds.has(courseId);
  }

  get canSetWork(): boolean {
    return this.newTitle.trim().length > 0 && this.selectedCourseIds.size > 0;
  }

  get noCourseSelectedError(): string | null {
    return this.courseTouched && this.selectedCourseIds.size === 0
      ? 'Choose at least one class.'
      : null;
  }

  onSetWork(): void {
    if (!this.canSetWork) {
      return;
    }
    this.service.createAssignment(
      this.newTitle.trim(),
      this.newDescription.trim() || null,
      this.newDueDate || null,
      [...this.selectedCourseIds]
    ).subscribe({
      next: (created) => {
        const students = created.reduce((total, a) => total + a.studentCount, 0);
        this.notice =
          `Set for ${created.length} class${created.length === 1 ? '' : 'es'}, `
          + `reaching ${students} student${students === 1 ? '' : 's'}.`;
        this.showSetWork = false;
        this.loadEverything();
      },
      error: (err) => this.notifications.showError(err, 'Could not set the work')
    });
  }

  // ----- editing and deleting ---------------------------------------------------

  startEditAssignment(a: Assignment): void {
    this.editingAssignmentId = a.id;
    this.editTitle = a.title;
    this.editDescription = a.description ?? '';
    this.editDueDate = a.dueDate ?? '';
  }

  cancelEditAssignment(): void {
    this.editingAssignmentId = null;
  }

  saveEditAssignment(id: number): void {
    const title = this.editTitle.trim();
    if (!title) {
      return;
    }
    this.service.updateAssignment(
      id, title, this.editDescription.trim() || null, this.editDueDate || null
    ).subscribe({
      next: () => {
        this.editingAssignmentId = null;
        this.loadEverything();
      },
      error: (err) => this.notifications.showError(err, 'Could not save the change')
    });
  }

  onDeleteAssignment(a: Assignment): void {
    if (!confirm(`Delete "${a.title}" for ${a.className}? This cannot be undone.`)) {
      return;
    }
    this.service.deleteAssignment(a.id).subscribe({
      next: () => this.loadEverything(),
      error: (err) => this.notifications.showError(err, 'Could not delete the work')
    });
  }
}
