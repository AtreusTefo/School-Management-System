import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { SessionService } from './session.service';
import { FieldErrorComponent } from './field-error.component';
import { USERNAME_MAX_LENGTH } from './validation';

/**
 * '/admin/login' - a SEPARATE page from the ordinary sign-in form at '/',
 * even though both ultimately call the same SessionService.login() and the
 * same POST /api/auth/login. There is no "/api/admins/login" and no admin
 * JWT: this reuses the session-based auth every role already shares (see
 * CLAUDE.md's Admin Management section for why), and the only thing this
 * component adds on top is checking that the account which just signed in
 * is actually an admin.
 *
 * A TEACHER OR STUDENT SIGNING IN HERE IS SIGNED STRAIGHT BACK OUT.
 * The login call itself cannot know in advance which role owns the
 * credentials, so success only means "these credentials are valid", not
 * "this is an admin". Leaving a non-admin signed in on the admin login page
 * would be a confusing half-state - authenticated, but immediately bounced
 * by adminAuthGuard if they tried to go anywhere in the panel - so this signs
 * them out again and says plainly that the account is not an admin account,
 * rather than leaving them wondering why nothing happened.
 */
@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './admin-login.component.html'
})
export class AdminLoginComponent {

  readonly usernameMaxLength = USERNAME_MAX_LENGTH;

  username = '';
  password = '';
  signingIn = false;
  loginError: string | null = null;

  constructor(private session: SessionService, private router: Router) {}

  onLogin(): void {
    const username = this.username.trim();
    if (!username || !this.password) {
      return;
    }
    this.signingIn = true;
    this.loginError = null;

    this.session.login(username, this.password).subscribe({
      next: () => {
        if (this.session.isAdmin) {
          this.signingIn = false;
          this.router.navigateByUrl('/admin');
          return;
        }
        // Valid credentials, wrong kind of account - see the class comment.
        this.session.logout().subscribe({
          next: () => {
            this.signingIn = false;
            this.loginError = 'That account is not an admin account.';
          },
          error: () => {
            this.signingIn = false;
            this.loginError = 'That account is not an admin account.';
          }
        });
      },
      error: (err: unknown) => {
        this.signingIn = false;
        const status = (err as { status?: number })?.status;
        this.loginError = status === 401
          ? 'Invalid username or password.'
          : 'Could not sign in. Check your connection and try again.';
      }
    });
  }
}
