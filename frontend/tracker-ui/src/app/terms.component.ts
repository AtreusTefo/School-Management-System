import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * TERMS AND CONDITIONS.
 *
 * Like the privacy policy beside it, this describes rules the software
 * actually enforces rather than generic boilerplate - the PDF-only upload
 * limit, the ten megabyte ceiling, the fact that a submission cannot be
 * withdrawn once handed in without a teacher reopening it, that a register
 * can only be taken on a date the lesson genuinely falls on, that taking one
 * again corrects it rather than adding a second, and that an administrator
 * can reset a password but never read one. Terms that promise behaviour the
 * system does not have would be unenforceable and misleading in the same
 * breath.
 *
 * The obligation runs the other way too. Section 4 (attendance) and the reset
 * bullets in section 2 describe behaviour that shipped well before this page
 * acknowledged it, which left users agreeing to terms that did not cover what
 * the system was doing to their records. A new rule the software enforces
 * against a person belongs here in the same change that enforces it.
 *
 * The review notice at the top carries the same weight as the one on the
 * privacy policy: accurate is not the same as legally sufficient.
 */
@Component({
  selector: 'app-terms',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './terms.component.html'
})
export class TermsComponent {

  readonly lastUpdated = '16 September 2026';

  /** Mirrors SubmissionFile.MAX_SIZE_BYTES, stated in the unit people use. */
  readonly maxUploadMb = 10;

  /**
   * Mirrors PasswordResetService.TOKEN_LIFETIME.
   *
   * Both this and maxUploadMb above are numbers the SERVER owns, restated here
   * so a reader is told a specific figure rather than "a short while". That
   * makes them the kind of duplicate that goes stale silently: nothing fails
   * if the server's 30 minutes becomes 15 and this line does not follow. If
   * you change either constant in Java, change it here in the same commit.
   */
  readonly resetLinkMinutes = 30;
}
