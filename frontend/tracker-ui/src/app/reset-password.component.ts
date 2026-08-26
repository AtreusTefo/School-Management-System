import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PasswordResetService } from './password-reset.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';
import { PasswordStrengthValidatorDirective } from './password-strength-validator.directive';
import { PASSWORD_MIN_LENGTH } from './validation';

/**
 * '/reset-password?token=...' - spend a link and choose a new password.
 *
 * THIS PAGE DOES REPORT FAILURE, unlike the one that asks for the link.
 * A token is 256 bits from a cryptographic RNG, so telling somebody theirs has
 * expired reveals nothing about who holds an account - and leaving them at a
 * form that silently refuses to work would be its own kind of broken. The
 * three refusals are deliberately distinguishable: not valid, already used,
 * expired. "Already used" in particular is worth being able to see, because it
 * is the one that might mean somebody else got there first.
 *
 * THE TOKEN COMES FROM THE QUERY STRING AND IS NEVER PUT ANYWHERE ELSE.
 * It is not stored, not logged, and not written into a link this page renders.
 * It is a bearer credential for as long as it lives, and the browser history
 * is already more exposure than anyone would choose.
 */
@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, FieldErrorComponent,
    PasswordStrengthValidatorDirective
  ],
  templateUrl: './reset-password.component.html'
})
export class ResetPasswordComponent implements OnInit {

  readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  token = '';
  newPassword = '';
  confirmPassword = '';
  saving = false;
  done = false;
  /** The server's own sentence when a link is refused - shown inline. */
  linkError: string | null = null;

  constructor(
    private reset: PasswordResetService,
    private notifications: NotificationService,
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
  }

  /** No token in the URL at all - somebody opened the page directly. */
  get hasToken(): boolean {
    return this.token.trim().length > 0;
  }

  get passwordsMatch(): boolean {
    return this.newPassword === this.confirmPassword;
  }

  get passwordsMatchError(): string | null {
    return this.confirmPassword.length > 0 && !this.passwordsMatch
      ? 'The two passwords do not match.'
      : null;
  }

  get canSubmit(): boolean {
    return !this.saving
        && this.hasToken
        && this.newPassword.length >= PASSWORD_MIN_LENGTH
        && this.passwordsMatch;
  }

  submit(): void {
    if (!this.canSubmit) {
      return;
    }
    this.saving = true;
    this.linkError = null;
    this.reset.resetPassword(this.token, this.newPassword).subscribe({
      next: () => {
        this.saving = false;
        this.done = true;
        // Cleared immediately - it is spent, and there is no reason for a live
        // credential to sit in a component field a moment longer than needed.
        this.token = '';
        this.newPassword = '';
        this.confirmPassword = '';
      },
      error: (err: unknown) => {
        this.saving = false;
        const status = (err as { status?: number })?.status;
        const message = (err as { error?: { message?: string } })?.error?.message;
        /*
         * A 400 here is the server explaining WHY the link cannot be used -
         * expired, already spent, not a real token. That belongs inline on
         * this form, not in the shared banner, because it is about the thing
         * the person is looking at. Anything else is a genuine fault.
         */
        if (status === 400 && message) {
          this.linkError = message;
        } else {
          this.notifications.showError(err, 'Could not reset your password');
        }
      }
    });
  }

  goToSignIn(): void {
    this.router.navigate(['/'], { fragment: 'signin' });
  }
}
