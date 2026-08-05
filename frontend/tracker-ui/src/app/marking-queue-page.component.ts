import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Submission, AssignmentService } from './assignment.service';
import { SessionService } from './session.service';
import { NotificationService } from './notification.service';
import { FileChosen, SubmissionTableComponent } from './submission-table.component';

/**
 * "MARKING QUEUE" for a teacher, "MY WORK" for a student - one page, because
 * it is genuinely one table with two readings of the same rows: a teacher
 * marks what a student hands in, and both are looking at the SAME submission
 * records, just with different actions available on them (see
 * SubmissionTableComponent for the buttons each role gets).
 */
@Component({
  selector: 'app-marking-queue-page',
  standalone: true,
  imports: [CommonModule, SubmissionTableComponent],
  templateUrl: './marking-queue-page.component.html'
})
export class MarkingQueuePageComponent implements OnInit {

  submissions: Submission[] = [];
  visibleSubmissions: Submission[] = [];
  statusFilter: 'ALL' | 'IN_PROGRESS' | 'SUBMITTED' | 'OVERDUE' = 'ALL';

  loading = false;
  uploading = false;

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

  get submittedCount(): number {
    return this.submissions.filter(s => s.status === 'SUBMITTED').length;
  }

  get inProgressCount(): number {
    return this.submissions.filter(s => s.status === 'IN_PROGRESS').length;
  }

  get overdueCount(): number {
    return this.submissions.filter(s => s.overdue).length;
  }

  setStatusFilter(filter: typeof this.statusFilter): void {
    this.statusFilter = filter;
    this.applyFilters();
  }

  /**
   * Narrow the loaded list down to the chosen status. Filters data already in
   * the browser rather than asking the server for a different set - see
   * AppComponent's earlier version of this note: the API decides what this
   * person is ALLOWED to see, the filter only decides what they are LOOKING
   * at, and the two must never be confused.
   */
  private applyFilters(): void {
    this.visibleSubmissions = this.submissions.filter(s =>
      this.statusFilter === 'ALL' ? true :
      this.statusFilter === 'OVERDUE' ? s.overdue :
      s.status === this.statusFilter
    );
  }

  load(): void {
    this.loading = true;
    this.service.getSubmissions().subscribe({
      next: (data) => {
        this.loading = false;
        this.submissions = data;
        this.applyFilters();
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the work');
      }
    });
  }

  onFileChosen(chosen: FileChosen): void {
    this.uploading = true;
    this.service.uploadFile(chosen.submissionId, chosen.file).subscribe({
      next: () => {
        this.uploading = false;
        this.load();
      },
      error: (err) => {
        this.uploading = false;
        this.notifications.showError(err, 'Could not upload that file');
      }
    });
  }

  onSubmitWork(id: number): void {
    this.service.submit(id).subscribe({
      next: () => this.load(),
      error: (err) => this.notifications.showError(err, 'Could not hand in the work')
    });
  }

  onUnsubmitWork(id: number): void {
    this.service.unsubmit(id).subscribe({
      next: () => this.load(),
      error: (err) => this.notifications.showError(err, 'Could not reopen the work')
    });
  }

  /**
   * Download a submitted PDF.
   *
   * The request cannot simply be a link: the endpoint needs the session
   * cookie AND, cross-origin, the browser will not attach it to a plain
   * navigation. So the bytes are fetched with the same credentialed
   * HttpClient as everything else, then handed to the user as a temporary
   * object URL.
   *
   * revokeObjectURL matters. Each blob URL pins its data in memory until it
   * is released, so a teacher working through a class of thirty would
   * otherwise accumulate every PDF they had opened.
   */
  onDownload(submission: Submission): void {
    this.service.downloadFile(submission.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = submission.fileName ?? 'submission.pdf';
        anchor.click();
        URL.revokeObjectURL(url);
      },
      error: (err) => this.notifications.showError(err, 'Could not download that file')
    });
  }
}
