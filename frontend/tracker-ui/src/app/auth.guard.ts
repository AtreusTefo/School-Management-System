import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';
import { SessionService } from './session.service';

/**
 * A COURTESY, NOT A SECURITY BOUNDARY - like every other role check in this
 * frontend. The server refuses every one of these endpoints on its own
 * authority regardless of what the browser's address bar says; this guard
 * exists so following a stale bookmark or a shared link redirects a signed-out
 * visitor to the login screen instead of dropping them onto a blank routed
 * page that fails to load data one HTTP call at a time.
 *
 * WAITS FOR checkingSession() TO SETTLE - this used to read session.user()
 * synchronously, on the theory that "the whole app already waits for
 * checkingSession before it renders anything, so session state has already
 * settled by the time a route can activate". That is true for a link clicked
 * INSIDE the app, and false for a hard navigation: typing /reports, following
 * a bookmark, or Playwright's page.goto() all start the Router's initial
 * navigation at the same moment AppComponent.ngOnInit() fires
 * session.init() - a real HTTP call - so the guard was reading user() before
 * that call had answered and always losing the race, bouncing even a
 * genuinely signed-in user back to '/'. Found by driving the browser, not by
 * reasoning about it: a student signed in, then a direct navigation to a
 * teacher-only page landed on '/' instead of the expected '/marking-queue'.
 *
 * A pending-password account is treated the same as signed-out: it may not
 * use any routed page until the password is replaced, and the shell renders
 * the change-password screen in that state regardless of the URL.
 */
export const authGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);

  return toObservable(session.checkingSession).pipe(
    filter((checking) => !checking),
    take(1),
    map(() => {
      if (session.user() && !session.mustChangePassword) {
        return true;
      }
      return router.createUrlTree(['/']);
    })
  );
};
