import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * PRIVACY POLICY.
 *
 * WRITTEN FROM THE SCHEMA, NOT FROM A TEMPLATE
 * --------------------------------------------
 * Every category listed on this page corresponds to a column that actually
 * exists: the name/national id/date of birth/contact email/phone on
 * PersonProfile, the uploaded PDF and its checksum on SubmissionFile, the
 * score and maximum on Assessment, the status/note/recorder on Attendance,
 * the hash and expiry on PasswordResetToken, the actor snapshot on AuditLog.
 * A privacy policy that lists data a system does not hold - or omits data it
 * does - is worse than none, because it is relied upon.
 *
 * THE OMISSION SIDE OF THAT IS NOT HYPOTHETICAL. This page went several
 * revisions without mentioning the attendance register or the reset-token
 * table, both of which hold personal data about identifiable children, purely
 * because nobody revisited it when they shipped. Adding a table that holds
 * personal data means editing this page in the same change.
 *
 * Two entries here describe a DERIVED value rather than a column - sex, read
 * out of the national identity number, and attendance rates, computed on
 * read. Both are stated as derived deliberately: a reader has a right to know
 * that correcting the source is what corrects them, and that there is no
 * separate field to ask about.
 *
 * THE REVIEW NOTICE AT THE TOP IS DELIBERATE AND SHOULD NOT BE REMOVED
 * CASUALLY. This is an accurate description of the system's behaviour, but
 * it is not legal advice, and a real school publishing it needs to confirm
 * the retention periods, the lawful basis, and the contact route against its
 * own obligations. Removing that notice is a decision for whoever owns those
 * obligations, not a tidy-up.
 */
@Component({
  selector: 'app-privacy',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './privacy.component.html'
})
export class PrivacyComponent {

  /** Shown in the page header and in the closing note, from one source. */
  readonly lastUpdated = '16 September 2026';
}
