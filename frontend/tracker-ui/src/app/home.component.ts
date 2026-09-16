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
 * system is a defined part of that idea - assignment tracking with PDF
 * hand-in, marks, a timetable, an attendance register, and the admin
 * onboarding around them. A landing page that oversells is the same defect
 * as a document describing features the code does not have, with a wider
 * audience.
 *
 * THIS PAGE HAS BEEN WRONG BEFORE, WHICH IS THE POINT OF THIS NOTE.
 * It used to state that there was no attendance register and no term or
 * period timetable. That was true when written and became false when V11 and
 * V14 shipped, and the page went on saying it - a marketing page UNDER-selling
 * is a smaller problem than one overselling, but it is the same defect: a
 * claim about the system that nothing keeps in step with the system. If you
 * add or remove a capability, this file and about.component.ts are part of
 * that change, not follow-up work.
 *
 * Messaging and notifications were the last things this page denied, and they
 * shipped too. What remains genuinely absent, and is still said plainly below:
 * anything that leaves the building. There is no email, no SMS and no push -
 * a notification arrives on a page in this app and nowhere else, and the only
 * mail the system ever sends is a password reset link somebody asked for.
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
      title: 'A timetable that says when',
      body: 'Terms, the shape of the school day, and which lesson sits in which '
          + 'period. A class cannot be booked into two lessons at once and a '
          + 'teacher cannot be in two rooms at once - both are refused by the '
          + 'database, not merely discouraged by the screen.'
    },
    {
      title: 'A register taken per lesson',
      body: 'Attendance is recorded against a lesson on a date, not once a day, '
          + 'so a student present at first period and absent at fourth is '
          + 'recorded as exactly that. Taking a register again corrects the '
          + 'first one instead of quietly creating a second.'
    },
    {
      title: 'Accounts an administrator controls',
      body: 'Only an admin onboards a student, a teacher or another admin - one '
          + 'at a time, or in bulk from a spreadsheet. Each account is issued a '
          + 'sign-in ID rather than choosing a username, and anyone who forgets '
          + 'their password can reset it by email without waiting for staff.'
    },
    {
      title: 'Messages that stay inside the school',
      body: 'Students can write to the teachers who actually teach them and to '
          + 'the office, teachers to their own students and to each other. '
          + 'Student-to-student messaging is refused outright rather than '
          + 'merely hidden - a school is not a social network.'
    },
    {
      title: 'Told once, and only once',
      body: 'A new mark, an absence, a due date coming up. Each notice is tied '
          + 'to the event that caused it, so correcting a register or running '
          + 'the reminder again cannot produce a second copy of the same '
          + 'message - the database refuses it rather than the code remembering.'
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
      body: 'Read your own week, take the register for your lessons, set work for '
          + 'the classes you teach, see who has handed in, mark what arrives, '
          + 'message your students and colleagues, and export performance to Excel.'
    },
    {
      name: 'Students',
      body: 'See your timetable and every piece of work set for you, hand it in as '
          + 'a PDF, read your own marks, performance and attendance, and message '
          + 'the teachers who teach you.'
    },
    {
      name: 'Administrators',
      body: 'Onboard accounts, maintain subjects, classes and courses, set the '
          + 'terms and periods the timetable is built from, see what any teacher '
          + 'teaches and whom, and review the audit log.'
    }
  ];

  /** How a piece of work actually travels through the system. */
  readonly steps = [
    {
      title: 'An admin sets up the school',
      body: 'Subjects, classes, and which teacher teaches which subject to which '
          + 'class. Students are enrolled into a class, and the terms and periods '
          + 'that decide when each lesson happens are set once.'
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
