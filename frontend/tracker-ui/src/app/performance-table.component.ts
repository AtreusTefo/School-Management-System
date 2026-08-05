import {
  AfterViewInit, Component, ElementRef, Input, NgZone,
  OnChanges, OnDestroy, Output, EventEmitter, ViewChild
} from '@angular/core';
import DataTable from 'datatables.net-dt';
import { Api } from 'datatables.net';
import 'datatables.net-buttons-dt';
import 'datatables.net-buttons/js/buttons.html5.mjs';
import 'datatables.net-buttons/js/buttons.print.mjs';
import { Performance } from './assignment.service';
import { exportPdfAction } from './report-export';

/**
 * THE PERFORMANCE SUMMARY, AS "BASICALLY THE SAME TABLE" AS THE MARK BOOK
 * -------------------------------------------------------------------------
 * This used to be a plain Angular `*ngFor` table with no sorting, no search
 * and no export of its own - a second-class citizen next to the Mark book's
 * full DataTables treatment, even though a teacher reads it first and just as
 * often. Rebuilding it on the SAME technology as MarksTableComponent - same
 * sort/search/paging, same Export PDF and Print, sharing the identical
 * exportPdfAction() from report-export.ts - is what "basically the same
 * table" means here: not literally one shared row shape (a summary and a
 * per-mark row are genuinely different granularities), but the same
 * capability wherever a teacher expects it.
 *
 * The two live on one page (ReportsPageComponent) so that "the report" is
 * naturally both of them together, and the page also offers ONE combined
 * download - the server-built .xlsx with both sheets - for exactly the case
 * where separate PDFs are not what somebody means by "the report".
 */
@Component({
  selector: 'app-performance-table',
  standalone: true,
  template: `<div class="dt-host"><table #table class="table"></table></div>`
})
export class PerformanceTableComponent implements AfterViewInit, OnChanges, OnDestroy {

  @Input() performance: Performance[] = [];

  /** Controls the Student column. The server enforces who may see whose row. */
  @Input() isTeacher = false;

  @Input() reportTitle = 'Performance summary';

  /** Raised when the PDF chunk cannot be fetched, so the page can say so. */
  @Output() exportFailed = new EventEmitter<string>();

  @ViewChild('table') private tableRef!: ElementRef<HTMLTableElement>;

  private dt: Api<Performance> | null = null;

  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.dt = new DataTable<Performance>(this.tableRef.nativeElement, {
      data: this.performance,
      order: [[this.isTeacher ? 4 : 3, 'desc']],   // Percentage, highest first
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
          text: 'Export PDF',
          className: 'btn btn--sm btn--primary',
          action: exportPdfAction(
            () => this.reportTitle,
            (message) => this.zone.run(() => this.exportFailed.emit(message))
          )
        },
        {
          extend: 'print',
          text: 'Print',
          className: 'btn btn--sm',
          title: () => this.reportTitle,
          exportOptions: { columns: ':not(.col-actions)' }
        }
      ],

      language: {
        search: '',
        searchPlaceholder: 'Search performance',
        lengthMenu: 'Show _MENU_',
        info: 'Showing _START_ to _END_ of _TOTAL_',
        infoEmpty: 'Nothing to show',
        infoFiltered: '(filtered from _MAX_)',
        emptyTable: 'No marks recorded yet.',
        zeroRecords: 'No results match your search.',
        paginate: { first: 'First', last: 'Last', next: 'Next', previous: 'Previous' }
      },

      columns: this.buildColumns()
    });
  }

  ngOnChanges(): void {
    if (this.dt) {
      this.redraw();
    }
  }

  ngOnDestroy(): void {
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
        title: 'Marks',
        data: 'assessmentCount',
        className: 'col-numeric'
      },
      {
        title: 'Total',
        data: 'totalScore',
        className: 'col-numeric',
        render: (_data: string, type: string, row: Performance) => {
          if (type === 'sort' || type === 'type') {
            return Number(row.percentage);
          }
          return `${this.trim(row.totalScore)} / ${this.trim(row.totalMaxScore)}`;
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
        render: (data: string, type: string, row: Performance) => {
          if (type === 'sort' || type === 'type') {
            return Number(row.percentage);
          }
          return `<span class="badge ${this.levelClass(data)}">`
               + `${this.escapeHtml(this.levelLabel(data))}</span>`;
        }
      }
    );

    return columns;
  }

  private redraw(): void {
    this.dt?.clear();
    this.dt?.rows.add(this.performance);
    this.dt?.draw(false);
  }

  // ----- rendering helpers, identical to MarksTableComponent's -----------------
  // Kept as separate copies rather than a shared mixin: two four-line functions
  // are cheaper to read than an inheritance hierarchy built to avoid them.

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

  private escapeHtml(value: string): string {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
}
