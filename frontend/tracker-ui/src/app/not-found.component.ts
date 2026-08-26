import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { SessionService } from './session.service';

/**
 * 404 - a URL that matches no route.
 *
 * WHY THIS REPLACED A SILENT REDIRECT
 * -----------------------------------
 * The catch-all route used to be `{ path: '**', redirectTo: '' }`, which sent
 * every unknown URL to the dashboard with no explanation. A typo in a
 * bookmark, a renamed route, or a stale link therefore looked exactly like
 * "you have been signed out and sent home", and there was nothing anywhere -
 * on screen or in the URL bar, since the redirect rewrote it - to say the
 * address had simply not existed. Showing a page that says so, and KEEPING
 * the bad URL visible, is the difference between a mistake somebody can
 * correct and one they cannot even see.
 *
 * WHERE "BACK" GOES DEPENDS ON WHO IS ASKING, because the three roles have
 * genuinely different home pages - an admin sent to '/' would land on the
 * teacher/student dashboard, which queries services that have no ADMIN case
 * and would render an empty student-shaped view. That is the same reasoning
 * the app bar's two separate nav lists already follow.
 */
@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './not-found.component.html'
})
export class NotFoundComponent {

  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  /** The address that did not resolve, shown so the reader can spot the typo. */
  readonly attemptedUrl = this.router.url;

  get signedIn(): boolean {
    return this.session.user() !== null;
  }

  /** Admins have a different home from everybody else - see the class comment. */
  get homeLink(): string {
    return this.session.isAdmin ? '/admin' : '/';
  }

  get homeLabel(): string {
    return this.signedIn ? 'Back to your dashboard' : 'Back to the home page';
  }
}
