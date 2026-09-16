import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * ABOUT - why this system exists and what it was built to prove.
 *
 * A public page, reachable signed in or out, so the reasoning behind the
 * product is not something only the people who wrote it can read.
 *
 * The principles listed here are the ones the codebase is actually held to,
 * not aspirations: each one has a corresponding, enforced rule (see
 * CLAUDE.md's Coding Standards and Data Integrity sections). Listing a
 * principle the code does not follow would be the same defect as a feature
 * claim it cannot back up.
 */
@Component({
  selector: 'app-about',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './about.component.html'
})
export class AboutComponent {

  /** See the hero image note in HomeComponent - same reasoning. */
  illustrationAvailable = true;

  readonly principles = [
    {
      title: 'Integrity belongs in the database',
      body: 'A rule enforced only in application code holds right up until '
          + 'something else writes to the same table. Constraints, foreign keys '
          + 'and checks make a rule true of the data itself - so a score cannot '
          + 'exceed its maximum, a class cannot be booked into two lessons at '
          + 'once, and an account that still owns work cannot simply be deleted '
          + 'out from under it. Where a rule genuinely cannot be expressed that '
          + 'way it is written down as an exception rather than quietly left to '
          + 'the application.'
    },
    {
      title: 'Nothing derived is stored',
      body: 'Percentages, totals, averages and performance levels are computed '
          + 'on every read. A stored average is wrong the moment one mark is '
          + 'corrected, and keeping it honest would need a job somebody '
          + 'eventually forgets to run.'
    },
    {
      title: 'Each layer does one job',
      body: 'The interface talks to a controller, the controller delegates to a '
          + 'service, and only the service decides anything. Business rules '
          + 'never leak into a screen or a query, which is what keeps the same '
          + 'rule true no matter who calls it.'
    },
    {
      title: 'Failures are visible',
      body: 'Every error path returns an accurate status and a message a person '
          + 'can act on, and the interface shows it. A refusal that explains '
          + 'itself - "this subject is still taught on a course" - is worth more '
          + 'than a silent failure or a generic server error.'
    },
    {
      title: 'A record of what changed',
      body: 'Every create, update and delete is written to an append-only audit '
          + 'log capturing what changed and who changed it, kept as a snapshot '
          + 'so it stays readable long after an account is gone.'
    },
    {
      title: 'The code is meant to be read',
      body: 'Comments here explain why a decision was made, not what a line does. '
          + 'The system is deliberately small enough to read end to end, and is '
          + 'built to be learned from as much as used.'
    }
  ];
}
