import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';
import { SessionService } from './session.service';

/**
 * Restricts a route to teachers - currently just "/assignments", the page
 * where work is set, edited and deleted. A student is sent to the marking
 * queue instead of a blank or broken page, which is what following a stale
 * link to a teacher-only page would otherwise produce.
 *
 * Same courtesy-not-boundary note as auth.guard.ts: AssignmentService's own
 * write endpoints refuse a student's request regardless of this guard. This
 * only decides what the student's BROWSER shows them, never what the API
 * accepts from them.
 *
 * WAITS FOR checkingSession() TO SETTLE, for the exact reason auth.guard.ts
 * does: session.isTeacher reads a signal that a hard navigation has not
 * necessarily populated yet, since SessionService.init() is an async HTTP
 * call racing the Router's initial navigation.
 */
export const teacherGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);

  return toObservable(session.checkingSession).pipe(
    filter((checking) => !checking),
    take(1),
    map(() => {
      if (session.isTeacher) {
        return true;
      }
      return router.createUrlTree(['/marking-queue']);
    })
  );
};
