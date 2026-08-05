import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone,
  OnChanges, OnDestroy, Output, ViewChild
} from '@angular/core';
import DataTable from 'datatables.net-dt';
import { Api } from 'datatables.net';
import 'datatables.net-buttons-dt';
import 'datatables.net-buttons/js/buttons.html5.mjs';
import 'datatables.net-buttons/js/buttons.print.mjs';
import { Assessment } from './assignment.service';
import { exportPdfAction } from './report-export';

/**
 * THE MARKS REPORT
 * ----------------
 * DataTables owns this table completely - rows, sorting, searching, paging - and
 * supplies the export buttons along the top.
 *
 * WHAT THE PDF EXPORT ACTUALLY EXPORTS, AND WHY THAT MATTERS
 * It exports the CELLS AS DRAWN, which means the percentages and performance
 * levels in the document are the ones the server computed and sent. Nothing here
 * recalculates a mark. That distinction is the whole reason this is safe to do
 * in the browser: had the page been deriving its own percentages, the exported
 * report and the stored data would be two answers to the same question, and only
 * one of them would ever be checked.
 *
 * It also exports what the reader is LOOKING AT - the current search and sort -
 * which is usually what somebody means by "export this report".
 *
 * Rows are built as HTML strings by the render functions, so Angular's automatic
 * escaping does not apply and escapeHtml() below is not optional. An assessment
 * name is typed by a teacher and read by a student; that is exactly the shape of
 * a stored cross-site-scripting attack.
 */
@Component({
  selector: 'app-marks-table',
  standalone: true,
  template: `<div class="dt-host"><table #table class="table"></table></div>`
})
export class MarksTableComponent implements AfterViewInit, OnChanges, OnDestroy {

  @Input() marks: Assessment[] = [];

  /** Controls the student column and the edit buttons. The server enforces the rules. */
  @Input() isTeacher = false;

  /** Shown in the exported document, so a printed report says whose it is. */
  @Input() reportTitle = 'Marks report';

  @Output() editMark = new EventEmitter<Assessment>();
  @Output() deleteMark = new EventEmitter<Assessment>();

  /** Raised when the PDF chunk cannot be fetched, so the page can say so. */
  @Output() exportFailed = new EventEmitter<string>();

  @ViewChild('table') private tableRef!: ElementRef<HTMLTableElement>;

  private dt: Api<Assessment> | null = null;

  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.dt = new DataTable<Assessment>(this.tableRef.nativeElement, {
      data: this.marks,
      order: [[0, 'asc']],
      pageLength: 10,
      lengthMenu: [10, 25, 50, 100],
      autoWidth: false,

      layout: {
        topStart: 'buttons',
        topEnd: 'search',
        bottomStart: 'info',
        bottomEnd: 'paging'
      },

      buttons: [
        {
          // A custom button rather than extend: 'pdfHtml5', so the click can
          // AWAIT the pdfmake download before handing over to the built-in
          // export. The stock button's action is synchronous and would fire
          // before the library existed.
          text: 'Export PDF',
          className: 'btn btn--sm btn--primary',
          action: exportPdfAction(
            () => this.reportTitle,
            (message) => this.zone.run(() => this.exportFailed.emit(message))
          )
        },
        {
          // Print needs no extra library, so it stays a stock button.
          extend: 'print',
          text: 'Print',
          className: 'btn btn--sm',
          title: () => this.reportTitle,
          exportOptions: { columns: ':not(.col-actions)' }
        }
      ],

      language: {
        search: '',
        searchPlaceholder: 'Search marks',
        lengthMenu: 'Show _MENU_',
        info: 'Showing _START_ to _END_ of _TOTAL_',
        infoEmpty: 'Nothing to show',
        infoFiltered: '(filtered from _MAX_)',
        emptyTable: 'No marks recorded yet.',
        zeroRecords: 'No marks match your search.',
        paginate: { first: 'First', last: 'Last', next: 'Next', previous: 'Previous' }
      },

      columns: this.buildColumns()
    });

    this.tableRef.nativeElement.addEventListener('click', this.onTableClick);
  }

  ngOnChanges(): void {
    if (this.dt) {
      this.redraw();
    }
  }

  ngOnDestroy(): void {
    this.tableRef?.nativeElement.removeEventListener('click', this.onTableClick);
    this.dt?.destroy();
    this.dt = null;
  }

  // ----- columns --------------------------------------------------------------

  private buildColumns(): any[] {
    const columns: any[] = [];

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
        title: 'Subject',
        data: 'subjectName',
        render: (data: string, type: string) =>
          type === 'display' ? this.escapeHtml(data) : data
      },
      {
        title: 'Class',
        data: 'className',
        render: (data: string, type: string) =>
          type === 'display' ? this.escapeHtml(data) : data
      },
      {
        title: 'Assessment',
        data: 'name',
        render: (data: string, type: string) =>
          type === 'display' ? this.escapeHtml(data) : data
      },
      {
        // Score and maximum in one column, because "34" without "out of 50" is
        // not a mark - it is half of one, and the half that cannot be read.
        title: 'Mark',
        data: 'score',
        className: 'col-numeric',
        render: (_data: string, type: string, row: Assessment) => {
          if (type === 'sort' || type === 'type') {
            return Number(row.percentage);
          }
          return `${this.trim(row.score)} / ${this.trim(row.maxScore)}`;
        }
      },
      {
        title: '%',
        data: 'percentage',
        className: 'col-numeric',
        render: (data: string, type: string) =>
          type === 'sort' || type === 'type' ? Number(data) : this.trim(data)
      },
      {
        title: 'Level',
        data: 'level',
        render: (data: string, type: string, row: Assessment) => {
          if (type === 'sort' || type === 'type') {
            // Sort by the number behind the label, so the column orders by
            // achievement rather than alphabetically - "Adequate" before
            // "Outstanding" is not what anybody means by sorting a grade.
            return Number(row.percentage);
          }
          return `<span class="badge ${this.levelClass(data)}">`
               + `${this.escapeHtml(this.levelLabel(data))}</span>`;
        }
      }
    );

    if (this.isTeacher) {
      columns.push({
        title: 'Actions',
        data: null,
        orderable: false,
        searchable: false,
        className: 'col-actions',
        render: (_data: null, type: string, row: Assessment) => {
          if (type !== 'display') {
            return '';
          }
          const label = this.escapeAttr(row.name);
          return `<div class="row-actions">
            <button type="button" class="btn btn--sm" data-action="edit"
                    data-id="${row.id}" aria-label="Edit ${label}">Edit</button>
            <button type="button" class="btn btn--sm btn--danger" data-action="delete"
                    data-id="${row.id}" aria-label="Delete ${label}">Delete</button>
          </div>`;
        }
      });
    }

    return columns;
  }

  // ----- drawing and interaction ----------------------------------------------

  private redraw(): void {
    this.dt?.clear();
    this.dt?.rows.add(this.marks);
    this.dt?.draw(false);
  }

  private onTableClick = (event: MouseEvent): void => {
    const button = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-action]');
    if (!button) {
      return;
    }
    const id = Number(button.dataset['id']);
    const mark = this.marks.find(m => m.id === id);
    if (!mark) {
      return;
    }

    this.zone.run(() => {
      if (button.dataset['action'] === 'edit') {
        this.editMark.emit(mark);
      } else if (button.dataset['action'] === 'delete') {
        this.deleteMark.emit(mark);
      }
    });
  };

  // ----- rendering helpers ----------------------------------------------------

  /** "17.00" reads worse than "17" on a report. Trailing zeros go. */
  private trim(value: string | number | null): string {
    if (value === null || value === undefined) {
      return '';
    }
    const n = Number(value);
    return Number.isFinite(n) ? String(Number(n.toFixed(2))) : String(value);
  }

  private levelLabel(level: string | null): string {
    return level ? level.replace(/_/g, ' ') : '-';
  }

  private levelClass(level: string | null): string {
    switch (level) {
      case 'OUTSTANDING':
      case 'MERITORIOUS':
        return 'badge--done';
      case 'SUBSTANTIAL':
      case 'ADEQUATE':
        return '';
      default:
        return 'badge--overdue';
    }
  }

  /**
   * Make a value safe to place in HTML text.
   *
   * Angular does this automatically for {{ }}; building rows as strings opts out
   * of that protection. A teacher types an assessment name and a student loads
   * it, so an unescaped `<img src=x onerror=...>` would run in the student's
   * browser with the student's session.
   */
  private escapeHtml(value: string): string {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  private escapeAttr(value: string): string {
    return this.escapeHtml(value);
  }
}
