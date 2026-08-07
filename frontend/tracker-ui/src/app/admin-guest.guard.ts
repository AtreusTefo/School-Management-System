import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';
import { SessionService } from './session.service';

/**
 * The inverse of adminAuthGuard: keeps an already signed-in account away
 * from the admin sign-in form. There is one session per browser, not one per
 * role, so "already signed in" always has an answer for where to send them
 * instead - the admin dashboard for an admin, the ordinary dashboard for
 * anyone else, since becoming a different account means signing out first.
 *
 * Same checkingSession() wait as every other guard in this app - see
 * auth.guard.ts for why reading session state synchronously on a hard
 * navigation is a real, previously-found defect rather than a theoretical one.
 */
export const adminGuestGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);

  return toObservable(session.checkingSession).pipe(
    filter((checking) => !checking),
    take(1),
    map(() => {
      if (session.isAdmin) {
        return router.createUrlTree(['/admin']);
      }
      if (session.user()) {
        return router.createUrlTree(['/']);
      }
      return true;
    })
  );
};
