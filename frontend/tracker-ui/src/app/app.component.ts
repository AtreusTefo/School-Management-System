import { Component, OnInit, signal, WritableSignal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { SessionService } from './session.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';
import { PASSWORD_MIN_LENGTH, USERNAME_MAX_LENGTH } from './validation';

/**
 * THE SHELL
 * ---------
 * What used to be the whole application is now just the frame around it: the
 * app bar, the sign-in and forced-password-change screens that gate
 * everything else, the one shared error banner, and the navigation that picks
 * which of the four routed pages sits inside <router-outlet>.
 *
 * Nothing here loads courses, submissions or marks any more - that is what
 * splitting the app into pages MEANS. Each page component fetches exactly
 * what its own page needs, through the same AssignmentService as always, and
 * this shell only ever asks SessionService "who is signed in" and
 * NotificationService "is there an error to show".
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, RouterLinkActive, RouterOutlet, FieldErrorComponent
  ],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {

  readonly usernameMaxLength = USERNAME_MAX_LENGTH;
  readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  // Sign-in form
  loginUsername = '';
  loginPassword = '';
  signingIn = false;
  loginError: string | null = null;

  // Change-password form
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  changingPassword = false;
  passwordNotice: string | null = null;
  showPasswordForm = false;

  /**
   * Tracks the current URL so the template can tell "signed out, on an
   * admin route" apart from "signed out, everywhere else" - see
   * isAdminRoute. A signal updated on NavigationEnd, rather than reading
   * router.url directly in the template: router.url is a plain property, and
   * nothing here would otherwise trigger change detection on the SPA
   * navigation between /admin/login and its own guard redirects.
   *
   * Initialized in the constructor body, not as a field initializer - field
   * initializers run before constructor parameter properties are assigned,
   * so `this.router` would not exist yet at the point this field's own
   * initializer ran.
   */
  private readonly urlSignal: WritableSignal<string>;

  constructor(
    public session: SessionService,
    public notifications: NotificationService,
    private router: Router
  ) {
    this.urlSignal = signal(this.router.url);
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.urlSignal.set(this.router.url));
  }

  ngOnInit(): void {
    this.session.init().subscribe();
  }

  /**
   * Whether a route under the admin panel is what the URL currently names.
   *
   * WHY THE SHELL NEEDS TO KNOW THIS AT ALL
   * <router-outlet> normally sits behind *ngIf="user" here, because every
   * other routed page requires a signed-in account and the shell shows its
   * own inline login form instead when nobody is signed in. /admin/login
   * breaks that assumption on purpose - it must be reachable by a completely
   * signed-out visitor - so the template gives admin routes their OWN outlet,
   * rendered when signed out AND on an admin route, alongside the ordinary
   * one that still requires `user`. The two conditions are mutually
   * exclusive, so exactly one <router-outlet> is ever active at a time.
   */
  get isAdminRoute(): boolean {
    return this.urlSignal().startsWith('/admin');
  }

  get user() {
    return this.session.user();
  }

  get checkingSession(): boolean {
    return this.session.checkingSession();
  }

  get isTeacher(): boolean {
    return this.session.isTeacher;
  }

  get mustChangePassword(): boolean {
    return this.session.mustChangePassword;
  }

  get passwordFormVisible(): boolean {
    return this.mustChangePassword || this.showPasswordForm;
  }

  get userInitial(): string {
    const user = this.user;
    return user ? user.username.charAt(0).toUpperCase() : '?';
  }

  // ----- authentication --------------------------------------------------------

  onLogin(): void {
    const username = this.loginUsername.trim();
    if (!username || !this.loginPassword) {
      return;
    }
    this.signingIn = true;
    this.session.login(username, this.loginPassword).subscribe({
      next: () => {
        this.signingIn = false;
        this.loginPassword = '';
        this.loginError = null;
        this.passwordNotice = null;
        this.router.navigateByUrl('/');
      },
      // Handled separately from NotificationService: a 401 here means the
      // credentials were wrong, NOT that a session expired. Routing it
      // through the generic handler produced "Your session has ended, please
      // sign in again" on the login screen, which is both confusing and
      // untrue - and showError's 401 branch also calls session.logout() and
      // redirects, neither of which makes sense for a login attempt that
      // never succeeded in the first place.
      error: (err: unknown) => {
        this.signingIn = false;
        const status = (err as { status?: number })?.status;
        if (status === 401) {
          this.loginError = 'Invalid username or password.';
        } else {
          this.notifications.showError(err, 'Could not sign in');
        }
      }
    });
  }

  onLogout(): void {
    const wasAdmin = this.session.isAdmin;
    this.session.logout().subscribe({
      next: () => {
        this.notifications.clear();
        this.showPasswordForm = false;
        this.passwordNotice = null;
        this.resetPasswordFields();
        // An admin signing out belongs back at the admin sign-in screen, not
        // the ordinary one - the same reasoning admin-login.component.ts
        // uses when a non-admin account is turned away from it.
        this.router.navigateByUrl(wasAdmin ? '/admin/login' : '/');
      },
      error: (err) => this.notifications.showError(err, 'Could not sign out')
    });
  }

  // ----- account self-service (EPIC-09) ----------------------------------------

  openPasswordForm(): void {
    this.showPasswordForm = true;
    this.passwordNotice = null;
    this.resetPasswordFields();
  }

  /** Only reachable when the change is voluntary; a forced one has no way out. */
  closePasswordForm(): void {
    this.showPasswordForm = false;
    this.resetPasswordFields();
  }

  private resetPasswordFields(): void {
    this.currentPassword = '';
    this.newPassword = '';
    this.confirmPassword = '';
  }

  get passwordsMatch(): boolean {
    return this.newPassword === this.confirmPassword;
  }

  get passwordsMatchError(): string | null {
    return this.confirmPassword.length > 0 && !this.passwordsMatch
      ? 'The two passwords do not match.'
      : null;
  }

  get canSubmitPassword(): boolean {
    return !this.changingPassword
        && this.currentPassword.length > 0
        && this.newPassword.length >= PASSWORD_MIN_LENGTH
        && this.passwordsMatch;
  }

  onChangePassword(): void {
    if (!this.canSubmitPassword) {
      return;
    }
    this.changingPassword = true;
    const wasForced = this.mustChangePassword;
    this.session.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.changingPassword = false;
        this.showPasswordForm = false;
        this.resetPasswordFields();
        this.passwordNotice = 'Your password has been changed.';
        // Coming out of the forced state, nothing has ever loaded - send the
        // account to the dashboard now that it can actually use the app.
        if (wasForced) {
          this.router.navigateByUrl('/');
        }
      },
      error: (err) => {
        this.changingPassword = false;
        this.notifications.showError(err, 'Could not change your password');
      }
    });
  }
}
