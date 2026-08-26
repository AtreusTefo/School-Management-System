import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PasswordResetService } from './password-reset.service';
import { NotificationService } from './notification.service';
import { FieldErrorComponent } from './field-error.component';

/**
 * '/forgot-password' - ask for a reset link.
 *
 * THE CONFIRMATION IS DELIBERATELY VAGUE, AND MUST STAY THAT WAY.
 * "If that account exists, a link has been sent" reads like a hedge and is
 * load-bearing: the server answers identically whether the identifier matched
 * an account, matched one with no email on file, or matched nothing at all,
 * because this page is reachable without signing in and a more helpful message
 * would turn it into a tool for discovering which student IDs are real.
 *
 * So this component must not try to improve on it. No "no such account", no
 * different wording for the empty case - the vagueness IS the feature, and the
 * server is what guarantees it rather than this page.
 */
@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './forgot-password.component.html'
})
export class ForgotPasswordComponent {

  identifier = '';
  sending = false;
  /** Set once a request has been made - the same message, always. */
  sent = false;

  constructor(
    private reset: PasswordResetService,
    private notifications: NotificationService
  ) {}

  submit(): void {
    const id = this.identifier.trim();
    if (!id || this.sending) {
      return;
    }
    this.sending = true;
    this.reset.requestReset(id).subscribe({
      next: () => {
        this.sending = false;
        this.sent = true;
      },
      /*
       * A failure here is a genuine fault - a network problem or a server
       * error - never "that account does not exist", which the server reports
       * as a success by design. So this goes to the shared banner rather than
       * being turned into an inline message about the account.
       */
      error: (err) => {
        this.sending = false;
        this.notifications.showError(err, 'Could not send a reset link');
      }
    });
  }
}
