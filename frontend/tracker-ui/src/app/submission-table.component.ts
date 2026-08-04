import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone,
  OnChanges, OnDestroy, Output, ViewChild
} from '@angular/core';
import DataTable from 'datatables.net-dt';
import { Api } from 'datatables.net';
import { Submission } from './assignment.service';

/** A file the user chose, together with the submission it belongs to. */
export interface FileChosen {
  submissionId: number;
  file: File;
}

/**
 * THE SUBMISSION TABLE
 * --------------------
 * DataTables (https://datatables.net) owns this table completely: it draws every
 * row, and it provides the sorting, searching, paging and result counts.
 *
 * WHY THIS TABLE SHOWS SUBMISSIONS RATHER THAN ASSIGNMENTS
 * It used to list assignments, back when an assignment belonged to exactly one
 * person and the two were the same thing. They are not the same thing any more:
 * one assignment reaches a whole class, and what a person acts on - upload,
 * hand in, download, reopen - is their own submission. Listing assignments here
 * would mean a student seeing a row they cannot act on and a teacher seeing one
 * row for thirty different states.
 *
 * WHY ANGULAR DOES NOT DRAW THE ROWS
 * The obvious approach - let *ngFor render the rows and then point DataTables at
 * the finished table - puts two libraries in charge of the same DOM. DataTables
 * physically removes rows from the page to show a different page of results and
 * reorders them to sort. Angular, meanwhile, still holds references to those
 * nodes and expects to find them where it left them. The usual workaround is to
 * destroy and rebuild the whole table on every data change, which is fragile and
 * flickers.
 *
 * Instead, Angular hands DataTables the DATA and stops there. There is exactly
 * one owner of the table's DOM, so there is nothing to synchronise.
 *
 * WHAT THIS COSTS, AND HOW IT IS PAID
 * Rows are built as HTML strings by the render functions below, so Angular's
 * automatic escaping does not apply. An assignment titled
 * `<img src=x onerror=alert(1)>` would otherwise execute - and a teacher types
 * the title while a student loads it, which is exactly the shape of a stored
 * cross-site-scripting attack. Every value that came from a user is therefore
 * passed through escapeHtml() before it reaches the page. This is not optional.
 *
 * The component is deliberately "dumb": it receives submissions, it emits what
 * the user asked to do, and it decides nothing. The rules stay in the parent
 * and, more importantly, on the server.
 */
@Component({
  selector: 'app-submission-table',
  standalone: true,
  // No *ngFor and no rows here on purpose - DataTables fills the table in.
  //
  // ONE file input serves every row. Rendering one per row would create as many
  // hidden inputs as there are submissions, all but one of them idle; this
  // records which row asked and reuses the single element.
  template: `
    <div class="dt-host">
      <input #picker type="file" accept="application/pdf,.pdf" hidden
             (change)="onFileChosen($event)">
      <table #table class="table"></table>
    </div>`
})
export class SubmissionTableComponent implements AfterViewInit, OnChanges, OnDestroy {

  /** The rows to show. Replacing this array redraws the table. */
  @Input() submissions: Submission[] = [];

  /** Controls which columns and buttons are drawn. The server enforces the same rules. */
  @Input() isTeacher = false;

  @Output() submitWork = new EventEmitter<number>();
  @Output() unsubmitWork = new EventEmitter<number>();
  @Output() downloadFile = new EventEmitter<Submission>();
  @Output() fileChosen = new EventEmitter<FileChosen>();

  @ViewChild('table') private tableRef!: ElementRef<HTMLTableElement>;
  @ViewChild('picker') private pickerRef!: ElementRef<HTMLInputElement>;

  /** The live DataTables instance, or null before the view exists. */
  private dt: Api<Submission> | null = null;

  /** Which row is waiting for the file picker to come back. */
  private uploadingFor: number | null = null;

  /**
   * NgZone is injected so that clicks handled by our own listener are run inside
   * Angular's change detection. Without it, the parent's state could change (an
   * error banner appearing, say) with nothing on screen updating to match.
   */
  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.dt = new DataTable<Submission>(this.tableRef.nativeElement, {
      data: this.submissions,

      // Order by due date, soonest first. Rows with no due date sort last -
      // see the sort branch of the due-date renderer for how.
      order: [[this.isTeacher ? 3 : 2, 'asc']],

      pageLength: 10,
      lengthMenu: [10, 25, 50, 100],

      // DataTables otherwise measures the content and writes fixed pixel widths
      // onto every column, which leaves the table narrower than the card holding
      // it and re-measures on each redraw. The stylesheet sizes it instead.
      autoWidth: false,

      layout: {
        topStart: 'search',
        topEnd: 'pageLength',
        bottomStart: 'info',
        bottomEnd: 'paging'
      },

      language: {
        search: '',
        searchPlaceholder: this.isTeacher ? 'Search work' : 'Search my work',
        lengthMenu: 'Show _MENU_',
        info: 'Showing _START_ to _END_ of _TOTAL_',
        infoEmpty: 'Nothing to show',
        infoFiltered: '(filtered from _MAX_)',
        emptyTable: 'No work matches this filter.',
        zeroRecords: 'No work matches your search.',
        paginate: { first: 'First', last: 'Last', next: 'Next', previous: 'Previous' }
      },

      columns: this.buildColumns()
    });

    // ONE delegated listener for every button in the table. Attaching a listener
    // per button would leak them, because DataTables discards and rebuilds the
    // buttons on every sort, search and page change.
    this.tableRef.nativeElement.addEventListener('click', this.onTableClick);
  }

  ngOnChanges(): void {
    // Guarded because @Input() values arrive before the view exists.
    if (this.dt) {
      this.redraw();
    }
  }

  ngOnDestroy(): void {
    this.tableRef?.nativeElement.removeEventListener('click', this.onTableClick);
    // Without this the instance, its listeners and its cached rows survive the
    // component - a leak that also makes DataTables refuse to re-initialise on
    // the same element later.
    this.dt?.destroy();
    this.dt = null;
  }

  // ----- columns --------------------------------------------------------------

  /**
   * The teacher's table carries one extra column - whose work this is - because
   * that is the only question a marking queue has that a student's list does not.
   * The column indexes used for the default sort above account for it.
   */
  private buildColumns(): any[] {
    const columns: any[] = [
      {
        title: 'Subject',
        data: 'subjectName',
        render: (data: string, type: string) =>
          type === 'display' ? this.escapeHtml(data) : data
      },
      {
        title: 'Work',
        data: 'assignmentTitle',
        render: (data: string, type: string) =>
          type === 'display' ? this.escapeHtml(data) : data
      }
    ];

    if (this.isTeacher) {
      columns.push({
        title: 'Student',
        data: 'studentUsername',
        render: (data: string, type: string) =>
          type === 'display' ? this.escapeHtml(data) : data
      });
    }

    columns.push(
      {
        title: 'Due',
        data: 'dueDate',
        render: (data: string | null, type: string) => {
          if (type === 'sort' || type === 'type') {
            // No deadline sorts last rather than first: a row with no due date
            // is not the most urgent thing on the page.
            return data ?? '9999-12-31';
          }
          return data ? this.formatDate(data) : '<span class="muted">none</span>';
        }
      },
      {
        title: 'Status',
        data: 'status',
        render: (data: Submission['status'], type: string, row: Submission) => {
          if (type === 'sort' || type === 'type') {
            return this.statusRank(row);
          }
          if (row.overdue) {
            return '<span class="badge badge--overdue">OVERDUE</span>';
          }
          return data === 'SUBMITTED'
            ? '<span class="badge badge--done">HANDED IN</span>'
            : '<span class="badge">IN PROGRESS</span>';
        }
      },
      {
        title: 'File',
        data: 'fileName',
        render: (data: string | null, type: string, row: Submission) => {
          if (type === 'sort' || type === 'type') {
            return data ?? '';
          }
          if (!row.hasFile) {
            return '<span class="muted">none</span>';
          }
          return `<span title="SHA-256 ${this.escapeAttr(row.fileSha256 ?? '')}">`
               + `${this.escapeHtml(data ?? '')} `
               + `<span class="muted">(${this.formatSize(row.fileSizeBytes)})</span></span>`;
        }
      },
      {
        title: 'Actions',
        data: null,
        orderable: false,
        searchable: false,
        className: 'col-actions',
        render: (_data: null, type: string, row: Submission) =>
          type === 'display' ? this.renderActions(row) : ''
      }
    );

    return columns;
  }

  // ----- drawing --------------------------------------------------------------

  /**
   * Push the current data into the table.
   *
   * draw(false) keeps the reader on the page they were looking at. Plain draw()
   * jumps back to page one, which is infuriating when handing in work on page
   * three throws you to the top of the list.
   */
  private redraw(): void {
    this.dt?.clear();
    this.dt?.rows.add(this.submissions);
    this.dt?.draw(false);
  }

  // ----- interaction ----------------------------------------------------------

  /**
   * An arrow function so `this` is the component, and so the same reference can
   * be handed to removeEventListener in ngOnDestroy - a bound method would
   * produce a new function each time and never actually detach.
   */
  private onTableClick = (event: MouseEvent): void => {
    const target = event.target as HTMLElement | null;
    const button = target?.closest<HTMLElement>('[data-action]');
    if (!button) {
      return;
    }

    const action = button.dataset['action'];
    const id = Number(button.dataset['id']);
    const submission = this.submissions.find(s => s.id === id);
    if (!submission) {
      return;
    }

    // Back inside Angular, so whatever the parent changes in response actually
    // reaches the screen.
    this.zone.run(() => {
      switch (action) {
        case 'upload':   this.openPicker(id); break;
        case 'submit':   this.submitWork.emit(id); break;
        case 'unsubmit': this.unsubmitWork.emit(id); break;
        case 'download': this.downloadFile.emit(submission); break;
      }
    });
  };

  /**
   * Open the file dialog for one row.
   *
   * The value is cleared first so that choosing the SAME file twice still fires
   * a change event. Without it, a student who uploaded the wrong file, fixed it
   * on disk and picked it again would see nothing happen at all.
   */
  private openPicker(submissionId: number): void {
    this.uploadingFor = submissionId;
    this.pickerRef.nativeElement.value = '';
    this.pickerRef.nativeElement.click();
  }

  onFileChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (file && this.uploadingFor !== null) {
      this.fileChosen.emit({ submissionId: this.uploadingFor, file });
    }
    this.uploadingFor = null;
  }

  // ----- rendering helpers ----------------------------------------------------

  /**
   * Rank a row for status sorting: overdue first, then outstanding, then done.
   *
   * Overdue is not a stored status - it is derived by the server - so it cannot
   * be part of a text comparison. Ranking makes the column sort by urgency,
   * which is what the reader means when they sort by "Status".
   */
  private statusRank(row: Submission): number {
    if (row.overdue) {
      return 0;
    }
    return row.status === 'IN_PROGRESS' ? 1 : 2;
  }

  private renderActions(row: Submission): string {
    const id = row.id;
    const submitted = row.status === 'SUBMITTED';
    const label = this.escapeAttr(row.assignmentTitle);
    const buttons: string[] = [];

    if (!this.isTeacher) {
      // A student uploads and hands in. Both are disabled once the work is in:
      // changing the document after the deadline, with the timestamp still
      // claiming the original moment, is exactly what must not be possible.
      buttons.push(
        `<button type="button" class="btn btn--sm" data-action="upload"
                 data-id="${id}" aria-label="Upload a PDF for ${label}"
                 ${submitted ? 'disabled' : ''}>${row.hasFile ? 'Replace' : 'Upload'}</button>`);

      buttons.push(
        `<button type="button" class="btn btn--sm btn--primary" data-action="submit"
                 data-id="${id}" aria-label="Hand in ${label}"
                 title="${row.hasFile ? '' : 'Upload a PDF first'}"
                 ${submitted || !row.hasFile ? 'disabled' : ''}>Hand in</button>`);
    }

    if (row.hasFile) {
      buttons.push(
        `<button type="button" class="btn btn--sm" data-action="download"
                 data-id="${id}" aria-label="Download ${label}">Download</button>`);
    }

    if (this.isTeacher) {
      // Reopening is a teacher's decision only: otherwise "handed in" would mean
      // nothing, since work could be retracted the moment it was marked late.
      buttons.push(
        `<button type="button" class="btn btn--sm" data-action="unsubmit"
                 data-id="${id}" aria-label="Reopen ${label}"
                 ${submitted ? '' : 'disabled'}>Reopen</button>`);
    }

    return `<div class="row-actions">${buttons.join('')}</div>`;
  }

  /**
   * Render an ISO date as "12 Aug 2026".
   *
   * Deliberately not `new Date(iso)`. A bare yyyy-MM-dd is parsed as UTC
   * midnight, so anyone west of Greenwich sees the previous day - a due date of
   * the 12th displayed as the 11th. Splitting the string keeps the date exactly
   * as the server meant it, with no timezone involved at all.
   */
  private formatDate(iso: string): string {
    const parts = iso.split('-');
    if (parts.length !== 3) {
      return this.escapeHtml(iso);
    }
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const month = months[Number(parts[1]) - 1] ?? parts[1];
    return `${Number(parts[2])} ${month} ${parts[0]}`;
  }

  /** Bytes as something a person reads, e.g. "1.4 MB". */
  private formatSize(bytes: number | null): string {
    if (bytes === null) {
      return '';
    }
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
      return `${Math.round(bytes / 1024)} KB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  /**
   * Make a value safe to place in HTML text.
   *
   * Angular does this automatically for {{ }}; building rows as strings opts out
   * of that protection, so it has to be done by hand. A teacher sets the title
   * of an assignment and a student then loads it, and an uploaded FILENAME comes
   * straight from another user's machine - both are untrusted text arriving in
   * somebody else's browser. Escaping the five characters that can break out of
   * HTML text or an attribute closes that off.
   */
  private escapeHtml(value: string): string {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  /** Same escaping, named separately to make the intent at each call site clear. */
  private escapeAttr(value: string): string {
    return this.escapeHtml(value);
  }
}
