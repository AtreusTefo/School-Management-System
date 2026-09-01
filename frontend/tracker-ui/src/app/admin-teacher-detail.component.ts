import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminService, Student, Teacher } from './admin.service';
import { Course } from './assignment.service';
import { NotificationService } from './notification.service';

/**
 * '/admin/teachers/:id' - WHAT ONE TEACHER TEACHES, AND WHOM.
 *
 * The mirror of '/admin/students/:id', which already answered the same
 * question from the other end: that page shows a student's class and the
 * teachers who reach them, and there was no equivalent for a teacher. An
 * admin could see a list of teacher ACCOUNTS and edit them, but nothing
 * anywhere said which subjects a given teacher covers or which children are
 * in front of them.
 *
 * EVERYTHING HERE IS DERIVED, NOTHING IS STORED
 * ---------------------------------------------
 * A teacher's subjects, classes and students are not columns anywhere. They
 * are the Course and Enrolment rows, read three ways:
 *
 *   subjects  the distinct subjects of their courses
 *   classes   the distinct classes of their courses
 *   students  everybody enrolled in those classes
 *
 * That is the whole point of Course's design - see its class comment on the
 * four requirements that collapse into one table. Storing "this teacher's
 * subjects" as a list would be a second copy of a fact the timetable already
 * owns, and the two would disagree the first time a course changed.
 *
 * The grouping is done HERE rather than in a third endpoint, from courses the
 * page has already loaded. A separate "subjects of teacher" call would return
 * the same rows read differently and could disagree with this list if anything
 * changed between the two requests.
 */
@Component({
  selector: 'app-admin-teacher-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './admin-teacher-detail.component.html'
})
export class AdminTeacherDetailComponent implements OnInit {

  teacherId: number | null = null;
  teacher: Teacher | null = null;
  courses: Course[] = [];
  students: Student[] = [];
  loading = true;

  constructor(
    private admin: AdminService,
    private route: ActivatedRoute,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    const raw = this.route.snapshot.paramMap.get('id');
    this.teacherId = raw ? Number(raw) : null;
    this.notifications.setRetryHandler(() => this.reload());
    this.reload();
  }

  reload(): void {
    if (this.teacherId === null || Number.isNaN(this.teacherId)) {
      this.loading = false;
      return;
    }
    this.loading = true;

    /*
     * The teacher's own account row comes from the existing list rather than a
     * new "one teacher" endpoint. It is a handful of rows, the page needs the
     * name and the generated id, and adding an endpoint to save one filter
     * would be a second way to ask a question that is already answered.
     */
    this.admin.listTeachers().subscribe({
      next: (all) => {
        this.teacher = all.find(t => t.id === this.teacherId) ?? null;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the teacher');
      }
    });

    this.admin.coursesForTeacher(this.teacherId).subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.notifications.showError(err, 'Could not load what this teacher teaches')
    });

    this.admin.studentsOfTeacher(this.teacherId).subscribe({
      next: (data) => this.students = data,
      error: (err) => this.notifications.showError(err, 'Could not load this teacher\'s students')
    });
  }

  /** The distinct subjects, in the order their courses were created. */
  get subjects(): string[] {
    const seen = new Map<number, string>();
    for (const course of this.courses) {
      seen.set(course.subjectId, course.subjectName);
    }
    return [...seen.values()];
  }

  /** The distinct classes, same reasoning. */
  get classes(): string[] {
    const seen = new Map<number, string>();
    for (const course of this.courses) {
      seen.set(course.classId, course.className);
    }
    return [...seen.values()];
  }

  /**
   * Courses grouped by class, which is how a timetable is read aloud:
   * "Grade 10A - Mathematics, History".
   */
  get byClass(): { className: string; subjects: string[] }[] {
    const grouped = new Map<string, string[]>();
    for (const course of this.courses) {
      const list = grouped.get(course.className) ?? [];
      if (!list.includes(course.subjectName)) {
        list.push(course.subjectName);
      }
      grouped.set(course.className, list);
    }
    return [...grouped.entries()].map(([className, subjects]) => ({ className, subjects }));
  }

  displayName(t: Teacher | null): string {
    if (!t) {
      return 'Teacher';
    }
    return t.fullName || t.externalId || t.username;
  }
}
