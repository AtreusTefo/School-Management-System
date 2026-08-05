import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Assessment, AssignmentService, Course, Performance } from './assignment.service';
import { SessionService } from './session.service';
import { NotificationService } from './notification.service';
import { MarksTableComponent } from './marks-table.component';
import { PerformanceTableComponent } from './performance-table.component';
import { FieldErrorComponent } from './field-error.component';
import { DecimalValidatorDirective } from './decimal-validator.directive';
import {
  ASSESSMENT_NAME_MAX_LENGTH, checkDecimal, MARK_MAX_DECIMAL_DIGITS, MARK_MAX_INTEGER_DIGITS
} from './validation';

/**
 * "PERFORMANCE BY STUDENT AND MARK BOOK... BASICALLY THE SAME TABLE SO A
 * REPORT CAN BE EXPORTED"
 * -------------------------------------------------------------------------
 * Both tables live on this one page because they are two views of the same
 * marks, at two granularities - one row per student-and-subject, one row per
 * individual mark - and a teacher naturally wants the summary before the
 * detail behind it. Each keeps its OWN PDF/Print (they are still separate
 * DataTables, and "export what I am looking at right now" means one table at
 * a time for that).
 *
 * "the report needs to be exported to excel that pushes all the data to
 * work with" is answered differently, and on purpose: NOT by a client-side
 * library re-deriving a workbook from whatever happens to be loaded in this
 * component, but by asking the server for one - see
 * AssignmentService.downloadExcelReport() and, on the backend,
 * AssessmentReportService. The server builds the file by calling the exact
 * same AssessmentService methods this page's own JSON requests call, so the
 * download is bounded by the same authority as everything else on screen,
 * and it is never limited by pagination or a stale client-side cache the way
 * a browser-side export would be.
 */
@Component({
  selector: 'app-reports-page',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MarksTableComponent, PerformanceTableComponent,
    FieldErrorComponent, DecimalValidatorDirective
  ],
  templateUrl: './reports-page.component.html'
})
export class ReportsPageComponent implements OnInit {

  readonly assessmentNameMaxLength = ASSESSMENT_NAME_MAX_LENGTH;

  marks: Assessment[] = [];
  performance: Performance[] = [];
  courses: Course[] = [];
  loading = false;
  exportingExcel = false;

  showMarkEntry = false;
  markCourseId: number | null = null;
  markStudent = '';
  markName = '';
  markScore = '';
  markMaxScore = '';
  markCandidates: string[] = [];
  editingMarkId: number | null = null;
  notice: string | null = null;

  constructor(
    private service: AssignmentService,
    public session: SessionService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.load());
    this.load();
  }

  get isTeacher(): boolean {
    return this.session.isTeacher;
  }

  get markReportTitle(): string {
    return this.isTeacher
      ? `Mark book - ${this.session.user()?.username ?? ''}`
      : `My marks - ${this.session.user()?.username ?? ''}`;
  }

  get performanceReportTitle(): string {
    return this.isTeacher
      ? `Performance by student - ${this.session.user()?.username ?? ''}`
      : `My performance - ${this.session.user()?.username ?? ''}`;
  }

  load(): void {
    this.loading = true;
    this.service.getAssessments().subscribe({
      next: (data) => {
        this.loading = false;
        this.marks = data;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load marks');
      }
    });
    this.service.getPerformance().subscribe({
      next: (data) => this.performance = data,
      error: (err) => this.notifications.showError(err, 'Could not load the performance summary')
    });
    if (this.isTeacher) {
      this.service.getCourses().subscribe({
        next: (data) => this.courses = data,
        error: (err) => this.notifications.showError(err, 'Could not load your courses')
      });
    }
  }

  // ----- the combined Excel report --------------------------------------------

  /**
   * Ask the server for the two-sheet workbook and hand it to the browser as
   * a download.
   *
   * Same credentialed-blob-then-object-URL pattern as downloading a
   * submission's PDF - the endpoint needs the session cookie, and a plain
   * anchor navigation to a cross-origin URL will not carry it.
   */
  onExportExcel(): void {
    this.exportingExcel = true;
    this.service.downloadExcelReport().subscribe({
      next: (blob) => {
        this.exportingExcel = false;
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `assessment-report-${new Date().toISOString().slice(0, 10)}.xlsx`;
        anchor.click();
        URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.exportingExcel = false;
        this.notifications.showError(err, 'Could not export the report');
      }
    });
  }

  // ----- marks (teachers only) -------------------------------------------------

  openMarkEntry(): void {
    this.showMarkEntry = true;
    this.editingMarkId = null;
    this.markCourseId = null;
    this.markStudent = '';
    this.markName = '';
    this.markScore = '';
    this.markMaxScore = '';
    this.markCandidates = [];
  }

  closeMarkEntry(): void {
    this.showMarkEntry = false;
    this.editingMarkId = null;
  }

  onMarkCourseChange(): void {
    this.markStudent = '';
    this.markCandidates = [];
    if (this.markCourseId === null) {
      return;
    }
    const course = this.courses.find(c => c.id === this.markCourseId);
    if (!course) {
      return;
    }
    this.service.getClassStudents(course.classId).subscribe({
      next: (names) => this.markCandidates = names,
      error: (err) => this.notifications.showError(err, 'Could not load the class register')
    });
  }

  get canSaveMark(): boolean {
    const text = (value: unknown) => String(value ?? '').trim();

    if (!text(this.markName) || !text(this.markScore) || !text(this.markMaxScore)) {
      return false;
    }
    if (!this.scoreIsValidDecimal || !this.maxScoreIsValidDecimal || this.scoreExceedsMaxError) {
      return false;
    }
    if (this.editingMarkId === null) {
      return this.markCourseId !== null && this.markStudent.trim().length > 0;
    }
    return true;
  }

  get scoreIsValidDecimal(): boolean {
    return checkDecimal(this.markScore, {
      maxIntegerDigits: MARK_MAX_INTEGER_DIGITS,
      maxDecimalDigits: MARK_MAX_DECIMAL_DIGITS,
      requirePositive: false
    }).ok;
  }

  get maxScoreIsValidDecimal(): boolean {
    return checkDecimal(this.markMaxScore, {
      maxIntegerDigits: MARK_MAX_INTEGER_DIGITS,
      maxDecimalDigits: MARK_MAX_DECIMAL_DIGITS,
      requirePositive: true
    }).ok;
  }

  /**
   * "A score cannot exceed its maximum" - mirrors ck_assessment_score_within_max.
   * See AppComponent's earlier version of this getter for the fuller note;
   * behaviour is unchanged by the move to this page.
   */
  get scoreExceedsMaxError(): string | null {
    if (!this.scoreIsValidDecimal || !this.maxScoreIsValidDecimal) {
      return null;
    }
    if (this.markScore.trim() === '' || this.markMaxScore.trim() === '') {
      return null;
    }
    const score = Number(this.markScore);
    const max = Number(this.markMaxScore);
    return score > max
      ? `The score cannot be higher than the maximum of ${this.markMaxScore}.`
      : null;
  }

  onSaveMark(): void {
    if (!this.canSaveMark) {
      return;
    }

    const done = (verb: string) => () => {
      this.notice = `Mark ${verb}.`;
      this.showMarkEntry = false;
      this.editingMarkId = null;
      this.load();
    };

    const text = (value: unknown) => String(value ?? '').trim();

    if (this.editingMarkId !== null) {
      this.service.updateMark(this.editingMarkId, text(this.markName),
                              text(this.markScore), text(this.markMaxScore))
        .subscribe({
          next: done('corrected'),
          error: (err) => this.notifications.showError(err, 'Could not save the mark')
        });
      return;
    }

    this.service.recordMark(
      this.markCourseId!, text(this.markStudent), text(this.markName),
      text(this.markScore), text(this.markMaxScore), null
    ).subscribe({
      next: done('recorded'),
      error: (err) => this.notifications.showError(err, 'Could not record the mark')
    });
  }

  startEditMark(mark: Assessment): void {
    this.showMarkEntry = true;
    this.editingMarkId = mark.id;
    this.markCourseId = mark.courseId;
    this.markStudent = mark.studentUsername;
    this.markName = mark.name;
    this.markScore = mark.score;
    this.markMaxScore = mark.maxScore;
  }

  onDeleteMark(mark: Assessment): void {
    if (!confirm(`Delete "${mark.name}" for ${mark.studentUsername}? This cannot be undone.`)) {
      return;
    }
    this.service.deleteMark(mark.id).subscribe({
      next: () => {
        this.notice = 'Mark deleted.';
        this.load();
      },
      error: (err) => this.notifications.showError(err, 'Could not delete the mark')
    });
  }

  onExportFailed(message: string): void {
    this.notifications.show(message);
  }
}
