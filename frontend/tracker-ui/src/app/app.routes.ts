import { Routes } from '@angular/router';
import { authGuard } from './auth.guard';
import { teacherGuard } from './teacher.guard';
import { adminAuthGuard } from './admin-auth.guard';

/**
 * THE FOUR PAGES, WHERE THERE USED TO BE ONE
 * ---------------------------------------------
 * Every route below used to be a card on a single, very long page, switched
 * in and out with a boolean flag. Splitting them like this is what the pages
 * were actually asking for the whole time: "Work I have set" and "Marking
 * queue" are different JOBS a teacher does at different moments, not two
 * views of the same task, and a URL for each means either one can be
 * bookmarked, linked to a student directly, or opened in its own tab.
 *
 * Every route EXCEPT THE ROOT carries authGuard: a signed-out visitor, or one
 * whose password is still pending, is sent back to '/' rather than shown a
 * page that immediately fails every request it tries to make. '/assignments'
 * carries teacherGuard as well, because setting work is a teacher's job -
 * see that guard for why this is UX, not the actual security boundary.
 *
 * THE ROOT ROUTE DELIBERATELY HAS NO GUARD.
 * Every OTHER guard's failure path redirects here - so if '' carried
 * authGuard too, a signed-out visitor landing on any guarded page would
 * bounce to '/', re-trigger the SAME guard, and bounce again: an infinite
 * redirect loop that hung the browser rather than showing a login form. This
 * was a real defect, found by Playwright timing out on a direct navigation
 * to /reports rather than by any assumption about how it "should" behave.
 *
 * It is safe to leave unguarded because AppComponent's own template already
 * decides whether <router-outlet> exists at all: while signed out, the outlet
 * is inside an *ngIf="user" that is false, so Angular has nowhere to project
 * DashboardComponent into and never constructs it - no wasted requests, no
 * exposed data, just the login form the shell renders instead.
 */
/**
 * THE PUBLIC PAGES - readable signed in, signed out, or with no account at all.
 *
 * The shell consults this list to decide what to draw for a SIGNED-OUT
 * visitor: on a public path it renders <router-outlet> so the page below can
 * appear, and on anything else it renders the landing page and the sign-in
 * card instead. See AppComponent.isPublicPage.
 *
 * A PLAIN LIST OF PATHS, CHECKED SYNCHRONOUSLY, rather than route `data` read
 * back from an ActivatedRoute subscription. Both would work, and the second
 * is the more fashionable - but this app has already been bitten twice by
 * routing decisions that depended on an asynchronous value having arrived
 * (see the guards' own comments on racing SessionService.init()). The URL is
 * known synchronously from the first render, so deriving this from the URL
 * has no race to lose.
 */
export const PUBLIC_PATHS = [
  '/home', '/about', '/privacy', '/terms',
  // Forgetting a password is by definition done while signed out, so both
  // halves of that flow have to render for a visitor with no session.
  '/forgot-password', '/reset-password'
] as const;

/** True when `url` is one of PUBLIC_PATHS, ignoring any query or fragment. */
export function isPublicPath(url: string): boolean {
  const path = url.split('?')[0].split('#')[0];
  return (PUBLIC_PATHS as readonly string[]).includes(path);
}

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./dashboard.component').then(m => m.DashboardComponent)
  },

  /*
   * PUBLIC - no guard, by design.
   *
   * These carry no authGuard for the same reason the root route carries none:
   * every guard's failure path redirects to '/', so guarding a page that a
   * signed-out visitor is explicitly meant to read would either bounce them
   * or, if '/' were guarded too, loop. They are also the only routes whose
   * component renders while signed out, which is what makes a landing page,
   * an about page and the two legal pages reachable without an account.
   */
  {
    path: 'home',
    data: { public: true },
    loadComponent: () => import('./home.component').then(m => m.HomeComponent)
  },
  {
    path: 'about',
    data: { public: true },
    loadComponent: () => import('./about.component').then(m => m.AboutComponent)
  },
  {
    path: 'privacy',
    data: { public: true },
    loadComponent: () => import('./privacy.component').then(m => m.PrivacyComponent)
  },
  {
    path: 'terms',
    data: { public: true },
    loadComponent: () => import('./terms.component').then(m => m.TermsComponent)
  },
  {
    path: 'forgot-password',
    data: { public: true },
    loadComponent: () => import('./forgot-password.component')
      .then(m => m.ForgotPasswordComponent)
  },
  {
    /*
     * Carries the token as a query parameter, so it is public by necessity -
     * the whole point is that somebody with no session can use it. The token
     * itself is what authorises the change, and the server refuses an expired,
     * spent or unknown one.
     */
    path: 'reset-password',
    data: { public: true },
    loadComponent: () => import('./reset-password.component')
      .then(m => m.ResetPasswordComponent)
  },
  {
    path: 'assignments',
    loadComponent: () => import('./assignments-page.component').then(m => m.AssignmentsPageComponent),
    canActivate: [authGuard, teacherGuard]
  },
  {
    // Full detail for every student across the teacher's own courses only -
    // see SchoolService.listMyStudents for the server-side scoping this page
    // trusts completely rather than re-deriving.
    path: 'my-students',
    loadComponent: () => import('./my-students-page.component').then(m => m.MyStudentsPageComponent),
    canActivate: [authGuard, teacherGuard]
  },
  {
    path: 'marking-queue',
    loadComponent: () => import('./marking-queue-page.component').then(m => m.MarkingQueuePageComponent),
    canActivate: [authGuard]
  },
  {
    /*
     * Taking a register, or reading your own attendance - which of the two you
     * get is decided by AttendanceService from the session, not by this route.
     * authGuard alone for the same reason /timetable carries it alone.
     */
    path: 'attendance',
    loadComponent: () => import('./attendance-page.component').then(m => m.AttendancePageComponent),
    canActivate: [authGuard]
  },
  {
    /*
     * The caller's OWN week - every signed-in role, including students, which
     * is why this carries authGuard alone and not teacherGuard. What each role
     * sees differs entirely, and that difference is decided by
     * TimetableService from the session rather than by this route.
     */
    path: 'timetable',
    loadComponent: () => import('./my-timetable-page.component').then(m => m.MyTimetablePageComponent),
    canActivate: [authGuard]
  },
  {
    path: 'reports',
    loadComponent: () => import('./reports-page.component').then(m => m.ReportsPageComponent),
    canActivate: [authGuard]
  },

  /*
   * THE ADMIN PANEL - a separate section, not four more items bolted onto
   * the routes above.
   *
   * ONE SHARED SIGN-IN FORM FOR EVERY ROLE, deliberately - there is no
   * '/admin/login'. An earlier version of this app gave admin its own
   * sign-in page, and it produced a real defect: the page's own outlet was
   * gated on being SIGNED OUT, so the instant a login attempt succeeded
   * (even briefly, before the app decided the account wasn't an admin and
   * signed it back out) the outlet's condition flipped, tore the very
   * component performing the login out of the DOM, and its result never
   * reached the screen. A single form for every role sidesteps the whole
   * problem: AppComponent.onLogin() decides where to send the caller AFTER
   * a real, settled answer to "who signed in", not by gating a component's
   * existence on a state that can change mid-request.
   *
   * Every /admin/* route below carries adminAuthGuard alone, not authGuard
   * as well - stacking it would add nothing, and authGuard's failure path
   * (redirect to '/') is exactly what adminAuthGuard already does on its
   * own for a non-admin.
   */
  {
    path: 'admin',
    loadComponent: () => import('./admin-dashboard.component').then(m => m.AdminDashboardComponent),
    canActivate: [adminAuthGuard]
  },
  {
    // Subjects, classes, courses and enrolment. Guarded like every other admin
    // page - and, unlike a hidden card, actually unreachable without the role.
    path: 'admin/timetable',
    loadComponent: () => import('./admin-timetable-page.component')
      .then(m => m.AdminTimetablePageComponent),
    canActivate: [adminAuthGuard]
  },
  {
    // Terms, the shape of the school day, and WHEN each course is taught -
    // separate from /admin/timetable, which is who teaches what to whom.
    path: 'admin/schedule',
    loadComponent: () => import('./admin-schedule-page.component')
      .then(m => m.AdminSchedulePageComponent),
    canActivate: [adminAuthGuard]
  },
  {
    path: 'admin/teachers',
    loadComponent: () => import('./admin-teachers-page.component').then(m => m.AdminTeachersPageComponent),
    canActivate: [adminAuthGuard]
  },
  {
    path: 'admin/students',
    loadComponent: () => import('./admin-students-page.component').then(m => m.AdminStudentsPageComponent),
    canActivate: [adminAuthGuard]
  },
  {
    // Every student, teacher and admin account in one read-only table - see
    // AdminAccountsPageComponent for why this exists alongside the two pages
    // above rather than replacing either.
    path: 'admin/accounts',
    loadComponent: () => import('./admin-accounts-page.component').then(m => m.AdminAccountsPageComponent),
    canActivate: [adminAuthGuard]
  },
  {
    // The mirror of admin/students/:id - what one teacher teaches, and whom.
    path: 'admin/teachers/:id',
    loadComponent: () => import('./admin-teacher-detail.component')
      .then(m => m.AdminTeacherDetailComponent),
    canActivate: [adminAuthGuard]
  },
  {
    path: 'admin/students/:id',
    loadComponent: () => import('./admin-student-detail.component').then(m => m.AdminStudentDetailComponent),
    canActivate: [adminAuthGuard]
  },
  {
    path: 'admin/audit-log',
    loadComponent: () => import('./admin-audit-log-page.component').then(m => m.AdminAuditLogPageComponent),
    canActivate: [adminAuthGuard]
  },

  /*
   * 404 - A REAL PAGE, NOT A REDIRECT.
   *
   * This was `redirectTo: ''`, which sent every unknown URL to the dashboard
   * and rewrote the address bar on the way, so a mistyped or stale link was
   * indistinguishable from being signed out and sent home - with the evidence
   * (the URL itself) destroyed by the redirect. A component keeps the bad URL
   * on screen and says what happened.
   *
   * public: true because a signed-out visitor following a dead link must see
   * the 404 rather than the landing page - the shell decides what to render
   * from this flag. It stays LAST: '**' matches everything, so any route
   * declared after it would be unreachable.
   */
  {
    path: '**',
    data: { public: true },
    loadComponent: () => import('./not-found.component').then(m => m.NotFoundComponent)
  }
];
