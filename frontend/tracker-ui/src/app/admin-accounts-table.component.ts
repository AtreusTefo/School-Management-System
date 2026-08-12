import { AfterViewInit, Component, ElementRef, Input, OnChanges, OnDestroy, ViewChild } from '@angular/core';
import DataTable from 'datatables.net-dt';
import { Api } from 'datatables.net';

/** One row of the merged account listing - a student, a teacher, or an admin. */
export interface AccountRow {
  role: 'STUDENT' | 'TEACHER' | 'ADMIN';
  id: number;
  username: string;
  fullName: string | null;
  nationalId: string | null;
  dateOfBirth: string | null;
  contactEmail: string | null;
  phone: string | null;
  className: string | null;
  dateEnrolled: string | null;
  mustChangePassword: boolean;
}

/**
 * THE ALL-ACCOUNTS TABLE
 * -----------------------
 * DataTables owns this table completely, the same division of labour as
 * SubmissionTableComponent: Angular hands over the merged row data, DataTables
 * draws every row and provides sorting, searching, paging and the result
 * count. See that component's own header for the full reasoning; it applies
 * here unchanged.
 *
 * READ-ONLY, DELIBERATELY. Editing a student or a teacher already has its own
 * page - '/admin/students' and '/admin/teachers' - with the reset/edit/delete
 * actions and the confirmation dialog those require. Duplicating that here
 * would give two places capable of writing the same account, which is exactly
 * the kind of drift this codebase avoids elsewhere (one URL knows the admin
 * panel's endpoints, one component draws an inline error). This table exists
 * to answer a different question - "show me everyone" - not to act on anyone.
 *
 * WHY EVERY STRING IS ESCAPED, EVEN THOUGH THE FORMS NOW VALIDATE THEM
 * The single-record admin forms constrain a name to letters/spaces/hyphens/
 * apostrophes and a national id to digits - see PersonPatterns on the
 * backend. Bulk CSV/Excel import does NOT go through those same Jakarta Bean
 * Validation annotations; AdminService.importOneStudent/importOneTeacher call
 * saveProfile directly with whatever text was in the spreadsheet cell, so a
 * name arriving through that path is not shape-checked at all. Escaping every
 * value here is what closes that gap regardless of which path a row came in
 * through, exactly the reasoning SubmissionTableComponent's own header gives
 * for treating a filename as untrusted.
 */
@Component({
  selector: 'app-admin-accounts-table',
  standalone: true,
  template: `<div class="dt-host"><table #table class="table"></table></div>`
})
export class AdminAccountsTableComponent implements AfterViewInit, OnChanges, OnDestroy {

  @Input() accounts: AccountRow[] = [];

  @ViewChild('table') private tableRef!: ElementRef<HTMLTableElement>;

  private dt: Api<AccountRow> | null = null;

  ngAfterViewInit(): void {
    this.dt = new DataTable<AccountRow>(this.tableRef.nativeElement, {
      data: this.accounts,
      order: [[1, 'asc']],
      pageLength: 25,
      lengthMenu: [10, 25, 50, 100],
      autoWidth: false,

      layout: {
        topStart: 'search',
        topEnd: 'pageLength',
        bottomStart: 'info',
        bottomEnd: 'paging'
      },

      language: {
        search: '',
        searchPlaceholder: 'Search every account',
        lengthMenu: 'Show _MENU_',
        info: 'Showing _START_ to _END_ of _TOTAL_',
        infoEmpty: 'Nothing to show',
        infoFiltered: '(filtered from _MAX_)',
        emptyTable: 'No accounts match this filter.',
        zeroRecords: 'No accounts match your search.',
        paginate: { first: 'First', last: 'Last', next: 'Next', previous: 'Previous' }
      },

      columns: [
        {
          title: 'Role',
          data: 'role',
          render: (data: AccountRow['role'], type: string) => {
            if (type !== 'display') {
              return data;
            }
            const cls = data === 'ADMIN' ? 'badge--role'
                      : data === 'TEACHER' ? 'badge--progress' : 'badge--neutral';
            return `<span class="badge ${cls}">${data}</span>`;
          }
        },
        {
          title: 'Name',
          data: 'fullName',
          render: (data: string | null, type: string, row: AccountRow) => {
            if (type !== 'display') {
              return data ?? row.username;
            }
            return data ? this.escapeHtml(data) : '<span class="muted">no profile</span>';
          }
        },
        {
          title: 'Id',
          data: 'username',
          render: (data: string, type: string) =>
            type === 'display' ? this.escapeHtml(data) : data
        },
        {
          title: 'National id',
          data: 'nationalId',
          render: (data: string | null, type: string) => {
            if (type !== 'display') {
              return data ?? '';
            }
            return data ? this.escapeHtml(data) : '<span class="muted">-</span>';
          }
        },
        {
          title: 'Date of birth',
          data: 'dateOfBirth',
          render: (data: string | null, type: string) => {
            if (type !== 'display') {
              return data ?? '9999-12-31';
            }
            return data ? this.formatDate(data) : '<span class="muted">-</span>';
          }
        },
        {
          title: 'Email',
          data: 'contactEmail',
          render: (data: string | null, type: string) => {
            if (type !== 'display') {
              return data ?? '';
            }
            return data ? this.escapeHtml(data) : '<span class="muted">-</span>';
          }
        },
        {
          title: 'Phone',
          data: 'phone',
          render: (data: string | null, type: string) => {
            if (type !== 'display') {
              return data ?? '';
            }
            return data ? this.escapeHtml(data) : '<span class="muted">-</span>';
          }
        },
        {
          title: 'Class',
          data: 'className',
          render: (data: string | null, type: string, row: AccountRow) => {
            if (type !== 'display') {
              return data ?? '';
            }
            if (row.role !== 'STUDENT') {
              return '<span class="muted">-</span>';
            }
            return data ? this.escapeHtml(data) : '<span class="muted">not enrolled</span>';
          }
        },
        {
          title: 'Status',
          data: 'mustChangePassword',
          render: (data: boolean, type: string) => {
            if (type !== 'display') {
              return data ? 1 : 0;
            }
            return data
              ? '<span class="badge badge--progress">Pending password change</span>'
              : '<span class="badge badge--done">Active</span>';
          }
        }
      ]
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

  private redraw(): void {
    this.dt?.clear();
    this.dt?.rows.add(this.accounts);
    this.dt?.draw(false);
  }

  /**
   * Render an ISO date as "12 Aug 2026" - see SubmissionTableComponent's own
   * copy of this method for why it is not `new Date(iso)`.
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

  /** Make a value safe to place in HTML text - see this class's own header comment. */
  private escapeHtml(value: string): string {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
}
