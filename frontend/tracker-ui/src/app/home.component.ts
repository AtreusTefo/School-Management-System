import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * THE PUBLIC LANDING PAGE - what the site is FOR, before anyone signs in.
 *
 * USED IN TWO PLACES, DELIBERATELY
 * --------------------------------
 * The shell embeds this component directly above the sign-in card for a
 * signed-out visitor at '/', and it is also routed at '/home' so a signed-in
 * user (or anyone following a footer link) can read it again without signing
 * out first. One component, so the two can never drift apart into two
 * descriptions of the same product.
 *
 * WHAT THIS PAGE MAY CLAIM
 * ------------------------
 * Only what the code actually does. CLAUDE.md's Scope Guard exists because
 * this repository is named "School Management System" while the delivered
 * system is one slice of that idea - assignment tracking with PDF hand-in,
 * marks, and the admin onboarding around them. There is no attendance, no
 * terms, no timetable PERIODS, and no notifications, so nothing on this page
 * mentions any of them. A landing page that oversells is the same defect as
 * a document describing features the code does not have, with a wider
 * audience.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './home.component.html'
})
export class HomeComponent {

  /**
   * The hero illustration is a real file on disk, not generated markup, so
   * it is the one thing on this page that can go missing - a bad deploy, a
   * file left out of the build. If it fails to load, the <figure> removes
   * itself rather than leaving a browser's broken-image icon in the middle
   * of the first thing a visitor sees. The text beside it carries the
   * message on its own; the picture supports it, and is not load-bearing.
   */
  heroImageAvailable = true;

  /** What the system does, in the order somebody meets it. */
  readonly capabilities = [
    {
      title: 'Set work once, for a whole class',
      body: 'A teacher publishes one assignment against a course. Every student '
          + 'enrolled in that class receives their own submission to hand in, so '
          + 'nobody is missed and no list has to be copied by hand.'
    },
    {
      title: 'Hand in a PDF, and know it arrived',
      body: 'Students upload their work as a PDF and can see its state change '
          + 'the moment it is accepted. No email thread, no question about '
          + 'whether it was received.'
    },
    {
      title: 'Marks that add up correctly',
      body: 'A score can never exceed its maximum, and no average is ever stored. '
          + 'Percentages and performance levels are worked out fresh on every '
          + 'read, so correcting one mark corrects every total built on it.'
    },
    {
      title: 'One shared view of who has handed in',
      body: 'Teachers see a marking queue, students see their own work, and both '
          + 'are reading the same records rather than two lists that disagree.'
    },
    {
      title: 'Accounts an administrator controls',
      body: 'Only an admin onboards a student, a teacher or another admin - one '
          + 'at a time, or in bulk from a spreadsheet. Each account is issued a '
          + 'sign-in ID rather than choosing a username.'
    },
    {
      title: 'A record of what changed',
      body: 'Every create, update and delete that passes through the system is '
          + 'written to an append-only audit log naming what changed and who '
          + 'changed it.'
    }
  ];

  /** The three roles the system actually has. */
  readonly roles = [
    {
      name: 'Teachers',
      body: 'Set work for the classes you teach, see who has handed in, mark what '
          + 'arrives, and export performance to Excel.'
    },
    {
      name: 'Students',
      body: 'See every piece of work set for you, hand it in as a PDF, and read '
          + 'your own marks and performance.'
    },
    {
      name: 'Administrators',
      body: 'Onboard accounts, maintain subjects, classes and courses, and review '
          + 'the audit log.'
    }
  ];

  /** How a piece of work actually travels through the system. */
  readonly steps = [
    {
      title: 'An admin sets up the school',
      body: 'Subjects, classes, and which teacher teaches which subject to which '
          + 'class. Students are enrolled into a class.'
    },
    {
      title: 'A teacher sets work',
      body: 'One assignment against one course, which fans out to every enrolled '
          + 'student as their own submission.'
    },
    {
      title: 'A student hands in',
      body: 'The work is uploaded as a PDF and the submission changes state, '
          + 'visible to both sides at once.'
    },
    {
      title: 'A teacher marks it',
      body: 'A score out of a maximum. Percentages, subject totals and '
          + 'performance levels follow from the marks themselves.'
    }
  ];
}
