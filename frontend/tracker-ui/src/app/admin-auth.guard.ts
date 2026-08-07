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
 * A signed-out visitor, or a signed-in TEACHER/STUDENT, is sent to
 * /admin/login rather than the main '/' - following a link into the admin
 * panel should offer the admin sign-in screen, not the ordinary one.
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
      return router.createUrlTree(['/admin/login']);
    })
  );
};
