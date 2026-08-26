import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * TERMS AND CONDITIONS.
 *
 * Like the privacy policy beside it, this describes rules the software
 * actually enforces rather than generic boilerplate - the PDF-only upload
 * limit, the ten megabyte ceiling, the fact that a submission cannot be
 * withdrawn once handed in without a teacher reopening it, and that an
 * administrator can reset a password but never read one. Terms that promise
 * behaviour the system does not have would be unenforceable and misleading
 * in the same breath.
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

  readonly lastUpdated = '26 August 2026';

  /** Mirrors SubmissionFile.MAX_SIZE_BYTES, stated in the unit people use. */
  readonly maxUploadMb = 10;
}
