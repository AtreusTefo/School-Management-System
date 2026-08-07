import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdminService, AuditAction, AuditLogEntry } from './admin.service';
import { NotificationService } from './notification.service';

/**
 * '/admin/audit-log' - an immutable record of every create, update and
 * delete the system has performed, server-paginated rather than loaded
 * whole into the browser. Unlike the DataTables-based reports elsewhere in
 * this app, which happily hold a school's entire mark book in memory and
 * paginate client-side, an audit log grows without bound for as long as the
 * system runs - the server decides what a "page" is here, this component
 * only asks for the next or previous one.
 */
@Component({
  selector: 'app-admin-audit-log-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-audit-log-page.component.html'
})
export class AdminAuditLogPageComponent implements OnInit {

  entries: AuditLogEntry[] = [];
  loading = false;

  pageNumber = 0;
  pageSize = 20;
  totalPages = 0;
  totalElements = 0;

  filterEntityName = '';
  filterAction: AuditAction | '' = '';

  constructor(private admin: AdminService, private notifications: NotificationService) {}

  ngOnInit(): void {
    this.notifications.setRetryHandler(() => this.load());
    this.load();
  }

  load(): void {
    this.loading = true;
    this.admin.searchAuditLog({
      entityName: this.filterEntityName.trim() || null,
      action: this.filterAction || null,
      page: this.pageNumber,
      size: this.pageSize
    }).subscribe({
      next: (result) => {
        this.loading = false;
        this.entries = result.content;
        this.pageNumber = result.page.number;
        this.pageSize = result.page.size;
        this.totalPages = result.page.totalPages;
        this.totalElements = result.page.totalElements;
      },
      error: (err) => {
        this.loading = false;
        this.notifications.showError(err, 'Could not load the audit log');
      }
    });
  }

  applyFilters(): void {
    this.pageNumber = 0;
    this.load();
  }

  clearFilters(): void {
    this.filterEntityName = '';
    this.filterAction = '';
    this.applyFilters();
  }

  get hasPrevious(): boolean {
    return this.pageNumber > 0;
  }

  get hasNext(): boolean {
    return this.pageNumber + 1 < this.totalPages;
  }

  previousPage(): void {
    if (this.hasPrevious) {
      this.pageNumber -= 1;
      this.load();
    }
  }

  nextPage(): void {
    if (this.hasNext) {
      this.pageNumber += 1;
      this.load();
    }
  }
}
