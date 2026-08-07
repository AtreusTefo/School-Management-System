import { Routes } from '@angular/router';
import { authGuard } from './auth.guard';
import { teacherGuard } from './teacher.guard';
import { adminAuthGuard } from './admin-auth.guard';
import { adminGuestGuard } from './admin-guest.guard';

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
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'assignments',
    loadComponent: () => import('./assignments-page.component').then(m => m.AssignmentsPageComponent),
    canActivate: [authGuard, teacherGuard]
  },
  {
    path: 'marking-queue',
    loadComponent: () => import('./marking-queue-page.component').then(m => m.MarkingQueuePageComponent),
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
   * '/admin/login' carries adminGuestGuard, not authGuard: it must be
   * reachable by a visitor with NO session at all (see AppComponent's
   * second, signed-out-only <router-outlet> for how the shell makes that
   * possible), and its job is the opposite of authGuard's - turning an
   * ALREADY signed-in account away, not admitting one.
   *
   * Every other /admin/* route carries adminAuthGuard alone, not authGuard
   * as well. authGuard would technically also pass for a signed-in admin,
   * but stacking it adds nothing and its failure path (redirect to '/') is
   * the wrong answer here - an admin-only page should send a rejected
   * visitor to '/admin/login', which is exactly what adminAuthGuard does on
   * its own.
   */
  {
    path: 'admin/login',
    loadComponent: () => import('./admin-login.component').then(m => m.AdminLoginComponent),
    canActivate: [adminGuestGuard]
  },
  {
    path: 'admin',
    loadComponent: () => import('./admin-dashboard.component').then(m => m.AdminDashboardComponent),
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
    path: 'admin/students/:id',
    loadComponent: () => import('./admin-student-detail.component').then(m => m.AdminStudentDetailComponent),
    canActivate: [adminAuthGuard]
  },
  {
    path: 'admin/audit-log',
    loadComponent: () => import('./admin-audit-log-page.component').then(m => m.AdminAuditLogPageComponent),
    canActivate: [adminAuthGuard]
  },

  { path: '**', redirectTo: '' }
];
