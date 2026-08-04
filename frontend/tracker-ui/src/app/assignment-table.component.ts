import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone,
  OnChanges, OnDestroy, Output, ViewChild
} from '@angular/core';
import DataTable from 'datatables.net-dt';
import { Api } from 'datatables.net';
import { Assignment } from './assignment.service';

/** What the parent needs in order to save an inline edit. */
export interface AssignmentEdit {
  id: number;
  title: string;
  dueDate: string | null;
}

/**
 * THE ASSIGNMENT TABLE
 * --------------------
 * DataTables (https://datatables.net) owns this table completely: it draws every
 * row, and it provides the sorting, searching, paging and result counts.
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
 * one owner of the table's DOM, so there is nothing to synchronise and no
 * rebuild-on-change dance. Angular still owns everything outside the table.
 *
 * WHAT THIS COSTS, AND HOW IT IS PAID
 * Rows are built as HTML strings by the render functions below, so Angular's
 * automatic escaping does not apply. An assignment titled
 * `<img src=x onerror=alert(1)>` would otherwise execute. Every value that came
 * from a user is therefore passed through escapeHtml() before it reaches the
 * page - see the note on that method. This is not optional.
 *
 * The component is deliberately "dumb": it receives assignments, it emits what
 * the user asked to do, and it decides nothing. The rules stay in the parent and,
 * more importantly, on the server.
 */
@Component({
  selector: 'app-assignment-table',
  standalone: true,
  // No *ngFor and no rows here on purpose - DataTables fills the table in.
  // The header is generated from the column titles configured below.
  template: `<div class="dt-host"><table #table class="table"></table></div>`
})
export class AssignmentTableComponent implements AfterViewInit, OnChanges, OnDestroy {

  /** The rows to show. Replacing this array redraws the table. */
  @Input() assignments: Assignment[] = [];

  /** Controls which action buttons are drawn. The server enforces the same rules. */
  @Input() isTeacher = false;

  @Output() submitAssignment = new EventEmitter<number>();
  @Output() unsubmitAssignment = new EventEmitter<number>();
  @Output() deleteAssignment = new EventEmitter<Assignment>();
  @Output() saveAssignment = new EventEmitter<AssignmentEdit>();

  @ViewChild('table') private tableRef!: ElementRef<HTMLTableElement>;

  /** The live DataTables instance, or null before the view exists. */
  private dt: Api<Assignment> | null = null;

  /** The id being edited inline, or null. Drives which row renders inputs. */
  private editingId: number | null = null;

  /**
   * NgZone is injected so that clicks handled by our own listener are run inside
   * Angular's change detection. Without it, the parent's state could change (an
   * error banner appearing, say) with nothing on screen updating to match.
   */
  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.dt = new DataTable<Assignment>(this.tableRef.nativeElement, {
      data: this.assignments,

      // Order by due date, soonest first. Rows with no due date sort last -
      // see the sort branch of the due-date renderer for how.
      order: [[2, 'asc']],

      pageLength: 10,
      lengthMenu: [10, 25, 50, 100],

      // DataTables otherwise measures the content and writes fixed pixel widths
      // onto every column, which leaves the table narrower than the card holding
      // it and re-measures on each redraw. The stylesheet sizes it instead.
      autoWidth: false,

      // Where DataTables puts its own controls. Stated explicitly rather than
      // left to the default, so the layout is obvious when reading this file.
      layout: {
        topStart: 'search',
        topEnd: 'pageLength',
        bottomStart: 'info',
        bottomEnd: 'paging'
      },

      language: {
        search: '',
        searchPlaceholder: 'Search assignments',
        lengthMenu: 'Show _MENU_',
        info: 'Showing _START_ to _END_ of _TOTAL_',
        infoEmpty: 'Nothing to show',
        infoFiltered: '(filtered from _MAX_)',
        emptyTable: 'No assignments match this filter.',
        zeroRecords: 'No assignments match your search.',
        paginate: { first: 'First', last: 'Last', next: 'Next', previous: 'Previous' }
      },

      columns: [
        {
          title: 'Title',
          data: 'title',
          className: 'cell-title',
          render: (data: string, type: string, row: Assignment) => {
            // 'display' is the HTML that reaches the page; every other type
            // ('sort', 'filter', 'type') wants the plain value. Keeping them
            // separate is what lets a row sort and search on its real content
            // while showing markup - DataTables calls this orthogonal data.
            if (type !== 'display') {
              return data;
            }
            if (this.editingId === row.id) {
              return `<label class="sr-only" for="dt-edit-title">Title</label>
                      <input id="dt-edit-title" class="input js-edit-title" type="text"
                             value="${this.escapeAttr(data)}" />`;
            }
            return this.escapeHtml(data);
          }
        },
        {
          title: 'Owner',
          data: 'ownerUsername',
          className: 'cell-muted',
          render: (data: string, type: string) =>
            type === 'display' ? this.escapeHtml(data) : data
        },
        {
          title: 'Due',
          data: 'dueDate',
          render: (data: string | null, type: string, row: Assignment) => {
            if (type !== 'display') {
              // A missing due date sorts to the very end rather than to the top,
              // where an empty string would put it. "No deadline" is not more
              // urgent than a deadline.
              return data ?? '9999-12-31';
            }
            if (this.editingId === row.id) {
              return `<label class="sr-only" for="dt-edit-due">Due date</label>
                      <input id="dt-edit-due" class="input js-edit-due" type="date"
                             value="${this.escapeAttr(data ?? '')}" />`;
            }
            const label = data
              ? `<span>${this.formatDate(data)}</span>`
              : `<span class="cell-muted">No due date</span>`;
            // Overdue is decided by the server on every read, so it is correct
            // for today rather than for whenever a flag was last written.
            const flag = row.overdue
              ? `<span class="badge badge--overdue">Overdue</span>`
              : '';
            return `<span class="cell-due">${label}${flag}</span>`;
          }
        },
        {
          title: 'Status',
          data: 'status',
          render: (data: Assignment['status'], type: string, row: Assignment) => {
            // Sorting status as text would order it by accident: IN_PROGRESS
            // precedes SUBMITTED alphabetically rather than by meaning, and
            // "overdue" is derived rather than stored, so it has no string to
            // compare at all. Ranking by urgency is the order somebody actually
            // wants when they click the column. (Carried over from the
            // hand-written sort this table replaced.)
            if (type === 'sort' || type === 'type') {
              return this.statusRank(row);
            }
            if (type !== 'display') {
              return data;
            }
            // The badge pairs a coloured dot with a written word. Colour on its
            // own carries no meaning for a colour-blind reader, or in print.
            const done = data === 'SUBMITTED';
            const cls = done ? 'badge--done' : 'badge--progress';
            const text = done ? 'Submitted' : 'In progress';
            return `<span class="badge ${cls}"><span class="badge__dot"></span>${text}</span>`;
          }
        },
        {
          title: 'Actions',
          data: null,
          orderable: false,
          searchable: false,
          className: 'col-actions',
          render: (_data: null, type: string, row: Assignment) => {
            if (type !== 'display') {
              return '';
            }
            return this.renderActions(row);
          }
        }
      ]
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

  // ----- drawing --------------------------------------------------------------

  /**
   * Push the current data into the table.
   *
   * draw(false) keeps the reader on the page they were looking at. Plain draw()
   * jumps back to page one, which is infuriating when submitting an assignment
   * on page three throws you to the top of the list.
   */
  private redraw(): void {
    this.dt?.clear();
    this.dt?.rows.add(this.assignments);
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
    const assignment = this.assignments.find(a => a.id === id);
    if (!assignment) {
      return;
    }

    // Back inside Angular, so whatever the parent changes in response actually
    // reaches the screen.
    this.zone.run(() => {
      switch (action) {
        case 'submit':   this.submitAssignment.emit(id); break;
        case 'unsubmit': this.unsubmitAssignment.emit(id); break;
        case 'delete':   this.deleteAssignment.emit(assignment); break;
        case 'edit':     this.startEdit(id); break;
        case 'cancel':   this.cancelEdit(); break;
        case 'save':     this.saveEdit(button, id); break;
      }
    });
  };

  private startEdit(id: number): void {
    this.editingId = id;
    this.redraw();
  }

  private cancelEdit(): void {
    this.editingId = null;
    this.redraw();
  }

  /**
   * Read the two inputs back out of the row and hand them to the parent.
   *
   * The values are read from the DOM rather than from a bound model, because the
   * inputs were written as HTML by the renderer above and Angular knows nothing
   * about them. That is the honest cost of letting DataTables own the rows.
   */
  private saveEdit(button: HTMLElement, id: number): void {
    const row = button.closest('tr');
    const titleInput = row?.querySelector<HTMLInputElement>('.js-edit-title');
    const dueInput = row?.querySelector<HTMLInputElement>('.js-edit-due');

    const title = titleInput?.value.trim() ?? '';
    if (!title) {
      return;   // The Save button is also disabled, but never trust only that.
    }

    this.editingId = null;
    this.saveAssignment.emit({ id, title, dueDate: dueInput?.value || null });
  }

  // ----- rendering helpers ----------------------------------------------------

  /**
   * Rank a row for status sorting: overdue first, then outstanding, then done.
   *
   * Overdue is not a stored status - it is derived by the server - so it cannot
   * be part of a text comparison. Ranking makes the column sort by urgency,
   * which is what the reader means when they sort by "Status".
   */
  private statusRank(row: Assignment): number {
    if (row.overdue) {
      return 0;
    }
    return row.status === 'IN_PROGRESS' ? 1 : 2;
  }

  private renderActions(row: Assignment): string {
    const id = row.id;
    const submitted = row.status === 'SUBMITTED';

    if (this.editingId === id) {
      return `<div class="row-actions">
        <button type="button" class="btn btn--sm btn--primary" data-action="save" data-id="${id}">Save</button>
        <button type="button" class="btn btn--sm btn--ghost" data-action="cancel" data-id="${id}">Cancel</button>
      </div>`;
    }

    const label = this.escapeAttr(row.title);
    const buttons: string[] = [];

    buttons.push(
      `<button type="button" class="btn btn--sm btn--primary" data-action="submit"
               data-id="${id}" aria-label="Submit ${label}"
               ${submitted ? 'disabled' : ''}>Submit</button>`);

    if (this.isTeacher) {
      // Reopening is a teacher's decision only: otherwise "submitted" would mean
      // nothing, since work could be retracted once it was late.
      buttons.push(
        `<button type="button" class="btn btn--sm" data-action="unsubmit"
                 data-id="${id}" aria-label="Reopen ${label}"
                 ${submitted ? '' : 'disabled'}>Reopen</button>`);

      buttons.push(
        `<button type="button" class="btn btn--sm" data-action="edit"
                 data-id="${id}" aria-label="Edit ${label}">Edit</button>`);

      buttons.push(
        `<button type="button" class="btn btn--sm btn--danger" data-action="delete"
                 data-id="${id}" aria-label="Delete ${label}"
                 title="A submitted assignment must be reopened before it can be deleted"
                 ${submitted ? 'disabled' : ''}>Delete</button>`);
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

  /**
   * Make a value safe to place in HTML text.
   *
   * Angular does this automatically for {{ }}; building rows as strings opts out
   * of that protection, so it has to be done by hand. A teacher can set the
   * title of an assignment and a student then loads it, which is precisely the
   * shape of a stored cross-site-scripting attack: the title
   * `<img src=x onerror=...>` would run in the student's browser with the
   * student's session. Escaping the five characters that can break out of HTML
   * text or an attribute closes that off.
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
