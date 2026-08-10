import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';
import { SessionService } from './session.service';

/**
 * Restricts the admin panel to signed-in ADMIN accounts. A courtesy, not the
 * security boundary - every admin endpoint enforces this on its own
 * authority regardless of what the browser's address bar says; see
 * AdminService.requireAdmin on the backend.
 *
 * Sends anyone who isn't a signed-in admin to '/' - there is one shared sign-in
 * form for every role (see AppComponent), not a separate admin login page, so
 * '/' is the correct landing spot whether the visitor is signed out, signed in
 * as a teacher or student, or has no account at all.
 *
 * WAITS FOR checkingSession() TO SETTLE, for the same reason auth.guard.ts
 * does: SessionService.init() is an async HTTP call that a hard navigation
 * (a typed URL, a bookmark) can race. Reading session.isAdmin synchronously
 * before that call resolves would always lose the race and bounce even a
 * genuinely signed-in admin - a real defect found and fixed once already in
 * auth.guard.ts/teacher.guard.ts; this guard is written to avoid repeating it.
 */
export const adminAuthGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);

  return toObservable(session.checkingSession).pipe(
    filter((checking) => !checking),
    take(1),
    map(() => {
      if (session.isAdmin) {
        return true;
      }
      return router.createUrlTree(['/']);
    })
  );
};
