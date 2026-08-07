import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { SessionService } from './session.service';

/**
 * '/admin' - the admin panel's landing page. Deliberately thin: it loads
 * nothing of its own, the same way the main DashboardComponent's quick-links
 * section needs no data before offering somewhere to go.
 */
@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './admin-dashboard.component.html'
})
export class AdminDashboardComponent {
  constructor(public session: SessionService) {}
}
