import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AssignmentService, Course } from './assignment.service';
import { SessionService } from './session.service';
import { NotificationService } from './notification.service';

/**
 * THE LANDING PAGE
 * -----------------
 * What a student or teacher lands on: their own timetable, read-only, and quick
 * links to the three working pages.
 *
 * It is now read-only by design. Creating accounts moved to the admin panel
 * (/admin/students, /admin/teachers), and WRITING the timetable moved to
 * /admin/timetable - subjects, classes, courses and enrolment decide who
 * receives every future assignment, so a teacher inventing a class, or naming a
 * colleague as the teacher of a course, put that decision in the wrong hands.
 *
 * An admin never reaches this page at all: AppComponent sends them to /admin on
 * sign-in. That is exactly why the timetable had to MOVE rather than simply be
 * re-gated here - see AdminTimetablePageComponent for the regression that
 * caused.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html'
})
export class DashboardComponent implements OnInit {


  courses: Course[] = [];


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

  get myClasses(): string[] {
    return [...new Set(this.courses.map(c => c.className))].sort();
  }

  /**
   * The course list grouped by subject, and again by class.
   *
   * All three views - subjects, classes, and the timetable itself - are the
   * SAME rows read differently. A teacher asking "what do I teach" and "which
   * classes am I assigned to" is asking about one relationship from two ends,
   * so grouping in the browser is correct: a second request would fetch the
   * same data and could disagree with the first if anything changed between
   * them.
   *
   * Sorted by name so the order is stable between visits. The server returns
   * courses in id order, which is the order they were created in - meaningful
   * to nobody reading a timetable.
   */
  get bySubject(): { name: string; code: string; entries: Course[] }[] {
    const groups = new Map<string, { name: string; code: string; entries: Course[] }>();
    for (const c of this.courses) {
      const group = groups.get(c.subjectName)
        ?? { name: c.subjectName, code: c.subjectCode, entries: [] };
      group.entries.push(c);
      groups.set(c.subjectName, group);
    }
    return [...groups.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  get byClass(): { name: string; entries: Course[] }[] {
    const groups = new Map<string, { name: string; entries: Course[] }>();
    for (const c of this.courses) {
      const group = groups.get(c.className) ?? { name: c.className, entries: [] };
      group.entries.push(c);
      groups.set(c.className, group);
    }
    return [...groups.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  /** The timetable rows themselves, ordered by subject then class. */
  get timetable(): Course[] {
    return [...this.courses].sort((a, b) =>
      a.subjectName.localeCompare(b.subjectName) || a.className.localeCompare(b.className));
  }

  loadCourses(): void {
    this.service.getCourses().subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.notifications.showError(err, 'Could not load your courses')
    });
  }
}
