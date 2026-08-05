import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
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

  constructor(
    public session: SessionService,
    public notifications: NotificationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.session.init().subscribe();
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
    this.session.logout().subscribe({
      next: () => {
        this.notifications.clear();
        this.showPasswordForm = false;
        this.passwordNotice = null;
        this.resetPasswordFields();
        this.router.navigateByUrl('/');
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
