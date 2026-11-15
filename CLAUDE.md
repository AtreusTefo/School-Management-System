# CLAUDE.md - School Management System Master Instructions

## Project Context
- **Name:** School Management System - Assignment Tracker
- **Repository:** https://github.com/AtreusTefo/School-Management-System
- **Stack:** Java 25 (LTS), Spring Boot 3.5.16 (backend REST API) + Angular 18 standalone (frontend SPA) + SQL Server 2019, database `School Management System`. The instance name, port and auth mode are per-machine; the values currently in force are in `docs/memory/PROJECT_STATUS.md`.
- **Primary IDEs:** VS Code, Claude Code
- **Main Goal:** A small, heavily commented full-stack application that tracks assignment submission state, built to demonstrate a strictly layered architecture. Both goals are real: the software must work, and the code must stay readable enough to teach from.

### Scope Guard - Read Before Adding Features
The repository is named *School Management System*, but the delivered system is one slice of that idea: **assignment tracking with PDF hand-in**. Twelve entities (`AppUser`, `PersonProfile`, `Subject`, `SchoolClass`, `Enrolment`, `Course`, `Assignment`, `Submission`, `SubmissionFile`, `Assessment`, `AuditLog`, plus the `Role`/`AssignmentStatus`/`PerformanceLevel`/`AuditAction` enums), three roles (`TEACHER`, `STUDENT`, `ADMIN`).

**Password reset by email DOES exist now** (added 26 August 2026, `PasswordResetToken`, migration V15). Two things about it are load-bearing rather than incidental: the request endpoint answers IDENTICALLY whether the identifier matched an account, matched one with no email, or matched nothing - it is reachable while signed out, so any variation would be an account-enumeration oracle - and the token is stored only as a SHA-256 hash, never in readable form. That hash is deliberately NOT BCrypt: a per-row salt would make lookup by token impossible, and a 256-bit random value has nothing to guess. The application runs with no mail server - `ResetLinkMailer` logs the link at WARN instead of sending, which is a development convenience that announces itself on every send.

**Sign-in is throttled now** (added 16 September 2026, `LoginAttempt`/`LoginThrottleService`, migration V19). Failed attempts are counted per username and per source address over a sliding window; past either limit, `POST /api/auth/login` answers 429 without looking at the password. Three things about it are load-bearing:

- **Nothing is locked.** There is no flag on `AppUser` and nothing an administrator has to undo. Whether sign-in is currently refused is COMPUTED by counting rows in the last few minutes, the same rule percentages and performance levels follow. That is not tidiness: a username-keyed limit is unavoidably a way to make somebody else fail to sign in, and a persistent lock would turn that into a denial of service with a human in the loop to reverse it, once per victim.
- **A failure is counted for ANY attempted username, real or invented.** This is what stops the throttle becoming the account-enumeration oracle that `PasswordResetService` is carefully built to avoid - 429 must be as easy to reach with a made-up id as with a real one. It is also why `login_attempt` has no foreign key to `app_user`: the third and last such omission in this schema, and the only one where the missing key is a security property rather than a deletion convenience.
- **The per-IP limit is the loose one, deliberately.** A school is the NAT case - a whole computer lab arrives from one public address - so a limit tight enough to stop a determined sprayer would lock out a classroom. The per-username limit is the one that actually holds; per-IP catches the careless.

The window and both limits are properties. `application-test.properties` raises them enormously rather than switching the feature off, because every MockMvc request in the suite shares one loopback address and would otherwise throttle the suite against itself; `LoginThrottleTest` sets its own small limits with `@TestPropertySource` and so does not depend on either number.

**Messaging and notifications DO exist now** (added 16 September 2026, `Message`/`Notification`/`NotificationType`, migration V18). Earlier revisions of this file said there were none, and that was true when written. Four things about them are load-bearing:

- **Nothing leaves the building.** Every message and every notification is delivered to a page inside this app. There is no email, no SMS and no push - a parent is not texted when a child is marked absent. The only mail the system ever sends is the password reset link described above.
- **One notification per recipient per event, enforced by `uq_notification_recipient_event`, not by the code remembering.** `notification.dedupe_key` names the EVENT (`MARK:41`, `DUE_SOON:512`, `ABSENCE:903`), so `DueDateReminderJob` can re-read the same window on every sweep with no memory of its own and never notify twice. `NotificationService.raise` checks `existsByRecipientAndDedupeKey` FIRST - not as a second copy of the rule, but because letting the constraint fire inside a transaction throws `DataIntegrityViolationException` and JPA then marks the whole transaction rollback-only, which would roll back the mark that caused the notification.
- **`notification.subject_type`/`subject_id` deliberately carry NO foreign key.** This is the second and last such deviation in the schema, after `audit_log.performed_by_id`, and for the same reason: a live reference would make a mark undeletable because somebody was told about it. `title` and `body` are a snapshot written once and never recomputed, which is what makes that safe - and is the one place in this system where text is duplicated rather than derived, because a notification records what somebody was TOLD, not what is currently true.
- **Who may message whom is enforced in Java alone, and cannot be anywhere else.** Every clause needs to read `Course` or `Enrolment`. Students may write only to the teachers who actually teach them and to any admin - **never to another student**, which is a safeguarding rule, not a UI preference. `MessagingService.listCorrespondents` is written in terms of the same predicate that refuses a send, so the compose form can never offer somebody the send path rejects.

Messages and notifications are the "current arrangement" side of this system's oldest distinction, so `AdminService.deleteStudent`/`deleteTeacher` clear them with the account (like enrolments and reset tokens) rather than refusing the delete the way a mark, a submission or a register does.

Scheduling is enabled by `SchedulingConfig`, which is `@Profile("!test")` - a timer firing mid-suite against the shared H2 database would make any "this student has one notification" assertion depend on when it happened to run.

Documentation describing features beyond these was inherited from an unrelated ASP.NET project; do not assume a feature exists because a document once mentioned it. Verify against the code.

**An attendance register DOES exist now** (added 26 August 2026, `Attendance`/`AttendanceStatus`, migration V14). It is recorded PER LESSON, not per day - a row is (timetable_slot, student, date), which is only expressible because V11 gave lessons an identity. Three of its rules are enforced in Java alone, in `AttendanceService`, because each needs to read a second table and a `CHECK` cannot: the date's weekday must match the lesson's day, the date must fall inside the lesson's term, and the student must be enrolled in the class. Taking a register twice is a CORRECTION, not a second register - `uq_attendance_slot_student_date` makes that structural rather than conventional.

**The national id is a Botswana Omang, and its shape is enforced** (added 26 August 2026, migration V13): exactly 9 digits, whose 5th is 1 (male) or 2 (female). That middle digit is the only self-describing position in the number, which is what makes it worth checking - nine arbitrary digits have no structure a transposition would break. `Gender` is DERIVED from it on read and never stored, the same rule percentages and performance levels follow.

**V13 is the one constraint in this schema added `WITH NOCHECK`, and that is deliberate.** Six real profiles predate the rule (12-13 digits). Applying it normally would have failed the migration and stopped the application; rewriting those six would have meant inventing a government identity number for a named person. Instead they are grandfathered and still editable, while every write from here on is checked - verified empirically, not assumed. The constraint reports `is_not_trusted = 1`, which is accurate and should stay so until somebody who knows the real numbers corrects them.

**Terms and timetable periods DO exist now** (added 26 August 2026, `Term`/`TimetablePeriod`/`TimetableSlot`, migration V11). Earlier revisions of this file said they did not and that reintroducing them was forbidden, and that was true when written - the limit was lifted deliberately, on request, rather than by drift. "Timetable" now means both things: `Course` still says which teacher teaches which subject to which class, and `TimetableSlot` says *when*, as a (term, weekday, period, course) row.

Two rules about the timetable matter more than the rest:

- **A class cannot be in two lessons at once, and a teacher cannot be in two places at once.** Both are `UNIQUE` constraints, not Java checks - `uq_timetable_slot_class` and `uq_timetable_slot_teacher`. They work because `class_id` and `teacher_id` are stored on the slot AND pinned to the referenced course by composite foreign keys, exactly the technique V4 already used to pin a role. Expressed against `course_id` alone neither rule would be checkable by the database at all.
- **"No two terms may overlap" is the one rule in this schema enforced in Java alone.** SQL Server has no exclusion constraint and a `CHECK` cannot see other rows, so `TimetableService.requireNoOverlap` is genuinely the only thing holding it. That is called out in the code and here because every *other* rule in this system is enforced at the database, and a reader is entitled to assume that until told otherwise.

**Admin management, a third role, and audit logging DO exist now** (added 6-10 August 2026). Earlier revisions of this file said there were two roles and no audit log, and that was true when written. What changed, and why, in one place:

- **Only an admin may onboard a student, a teacher, or another admin.** The earlier "a teacher can create a student account" flow (`POST /api/users`) is gone entirely - not merely re-gated - because it collected a bare username and nothing else, and the replacement collects a full profile. See `AdminService.createStudent`/`createTeacher`/`createAdmin`.
- **A student or teacher signs in with a generated id, never a chosen username.** `STU20260001`, `TCH20260001`, `ADM20260001` - see `IdGeneratorService` and `AppUser.externalId`'s own comment for why the id is duplicated into `username` rather than sign-in being taught to look somewhere else. There is **one shared login form** for every role, including admin; a role-specific login page was tried, produced a real defect (a component torn out of the DOM mid-request by its own visibility guard), and was removed - see `app.routes.ts`.
- **A person's biographical detail (name, national id, date of birth, contact email, date enrolled) lives in `PersonProfile`, not `AppUser`.** `AppUser` stays an identity and a credential; `PersonProfile` is a separate 1:1 table, deliberately, so a query that only needs "who is this and what can they do" never carries a date of birth past it. Admin accounts do not get one - only the student and teacher forms ask for this detail.
- **A student's grade and subjects, and a teacher's subjects and classes, are NOT columns on the profile.** They are the *existing* `Enrolment`/`Course` relationship, exactly as before - see the requirement table below. Admin's create forms can enrol a student in a class or set a teacher up with an initial course as part of the same request, but always by writing through those tables, never a second copy of the fact.
- **Bulk import (CSV or Excel) reuses the exact same per-record validation as the single-record form**, one row at a time, each in its own database transaction - a bad row fails on its own without losing the good rows already committed in the same file. See `AdminService.importStudents`/`importTeachers` and the comment on why the transaction boundary is drawn explicitly with `TransactionTemplate` rather than relying on `@Transactional` self-invocation, which would not have worked.
- **The audit log is append-only and does not foreign-key to `AppUser`.** Every Create/Update/Delete that goes through the service layer writes an entry naming what changed and who changed it, but the "who" is a snapshot (username + role at the time), not a live reference - a real foreign key here would make any account that had ever done anything permanently undeletable. See the file header of `V7__add_admin_role_and_audit_log.sql` and `AuditLog`'s own class comment.

**Marks, percentages and performance levels DO exist now** (added 5 August 2026, `Assessment` + `PerformanceLevel`). Earlier revisions of this file said they did not, and that was true when written.

Two rules about them matter more than the rest:

- **A score can never exceed its maximum.** `ck_assessment_score_within_max`. "34 out of 20" is not a high mark, it is a corrupt row, and every average computed from it afterwards is silently wrong.
- **Nothing derived is stored.** Percentages, totals, averages and performance levels are computed on read, every time. A stored average is wrong the moment one mark is corrected, and keeping it honest would need a trigger or a job somebody forgets to run. The bands live in one place: `PerformanceLevel`.

A subject percentage is **total score over total maximum**, never the mean of the individual percentages. Those are different numbers: 5/10 and 90/100 is 95/110 = 86.36%, while averaging the percentages gives 70% and silently treats a ten-mark quiz as equal to a hundred-mark exam.

**Subjects, classes and file uploads DO exist now** (added 4 August 2026). Earlier revisions of this file said they did not, and that was true when written - the model has since grown to carry them, because four requirements were unrepresentable without them:

| Requirement | What carries it |
|---|---|
| A student is taught by several teachers | `Course` rows for their class |
| A student is taught several subjects | the same rows |
| A teacher teaches several classes and subjects | the same rows, grouped by teacher |
| One piece of work reaches a whole class | `Assignment` fans out to one `Submission` per enrolled student |

The central design point: **`Assignment` and `Submission` are separate tables.** An assignment is what a teacher set; a submission is one student's state for it, and holds their PDF. They used to be one table with an `owner_id`, which is what made a class-wide assignment impossible to express.

**Entity Framework is not usable here.** EF6 and `ApplicationDbContext` are .NET technologies; this backend is Java. JPA/Hibernate fills the same role and is equally code-first. If a document or instruction mentions EF, it is inherited from the unrelated ASP.NET project.

Remaining candidate work is listed in `docs/project/PRD.md` section 7 under "Still open" and is explicitly **not agreed scope**.

## AI Behavior Guidelines
- **No Emojis:** Do NOT use emojis in any documentation, comments, or commit messages. Keep text professional and plain-text based.
- **Verify before asserting:** This project is small enough to read. Check the source rather than inferring behaviour from a document; the docs have been wrong before.
- **Preserve the teaching voice:** Existing comments explain *why* a decision was made, not just what a line does. Match that register when editing. Do not strip comments to make code look tidier.
- **Respect the layering:** See "Coding Standards" below. A change that puts business rules in the controller or SQL in the service is a defect regardless of whether it works.
- **General:** If logic is ambiguous, state the ambiguity and request clarification concisely. Reference `docs/architecture/ARCHITECTURE.md` before suggesting structural changes.

## Repository Structure
```
School Management System/
├── CLAUDE.md                   # This file - stays at the repository root
├── README.md                   # Run instructions - stays at the repository root
├── .gitignore                  # Excludes target/, node_modules/, dist/, .angular/
├── .vscode/settings.json       # TypeScript pin + java.configuration.runtimes
├── docs/                       # ALL project documentation (see below)
│   ├── DOCUMENTATION_INDEX.md
│   ├── architecture/           # ARCHITECTURE.md
│   ├── project/                # PRD.md, AGILE_HIERACHY.md
│   ├── memory/                 # PROJECT_STATUS.md - session handoff, per-machine values
│   ├── daily-reports/          # YYYY-MM-DD.md, dated records - do not rewrite
│   └── error-fixes/            # One file per issue
├── backend/                    # Spring Boot REST API
│   ├── mvnw / mvnw.cmd / .mvn/ # Maven Wrapper - no system Maven needed
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/tracker/
│       │   ├── TrackerApplication.java  # Entry point + seed runners: accounts (admin,
│       │   │                            #   teacher, student - dev usernames, not
│       │   │                            #   generated ids), then timetable, then
│       │   │                            #   assignments, then marks (@Order matters)
│       │   ├── controller/     # AssignmentController, SubmissionController,
│       │   │                   #   SchoolController, AssessmentController,
│       │   │                   #   AuthController, AdminController,
│       │   │                   #   TimetableController, AttendanceController,
│       │   │                   #   MessageController, NotificationController
│       │   ├── service/        # AssignmentService (fan-out), SubmissionService (PDF),
│       │   │                   #   SchoolService (timetable + admin course/enrolment
│       │   │                   #   endpoints), AssessmentService (marks),
│       │   │                   #   AssessmentReportService (the Excel workbook, built
│       │   │                   #   from AssessmentService's own methods), AppUserService
│       │   │                   #   (who is signed in), AdminService (onboarding +
│       │   │                   #   bulk import - see its own header for why it is NOT
│       │   │                   #   part of AppUserService), AuditLogService,
│       │   │                   #   IdGeneratorService (STU/TCH/ADM ids), ImportRowReader
│       │   │                   #   (CSV/Excel parsing, shared by both import paths),
│       │   │                   #   TimetableService (terms, periods, slots - and the
│       │   │                   #   one rule enforced in Java alone, see below),
│       │   │                   #   AttendanceService (the per-lesson register),
│       │   │                   #   PasswordResetService (+ ResetLinkMailer),
│       │   │                   #   PersonPatterns (Omang + phone, shared by the
│       │   │                   #   entity @Check and the service guard),
│       │   │                   #   NotificationService (raising + reading),
│       │   │                   #   LoginThrottleService (how often signing in may
│       │   │                   #     fail - the whole rule, including why it
│       │   │                   #     does not lock an account),
│       │   │                   #   MessagingService (and the who-may-write-to-whom
│       │   │                   #   rule), DueDateReminderJob (the only @Scheduled
│       │   │                   #   thing in the app - see SchedulingConfig)
│       │   ├── repository/     # One per entity (Spring Data JPA)
│       │   ├── model/          # AppUser, PersonProfile, Role, Subject, SchoolClass,
│       │   │                   #   Enrolment, Course, Assignment, Submission,
│       │   │                   #   SubmissionFile, AssignmentStatus, Assessment,
│       │   │                   #   PerformanceLevel, AuditLog, AuditAction,
│       │   │                   #   Term, TimetablePeriod, TimetableSlot, Attendance,
│       │   │                   #   AttendanceStatus, PasswordResetToken, Message,
│       │   │                   #   LoginAttempt (failed sign-ins only),
│       │   │                   #   Notification, NotificationType, Gender
│       │   │                   #   (DERIVED from the Omang, never stored)
│       │   ├── dto/            # Records the API publishes - see SubjectView for why;
│       │   │                   #   ImportSummary is the bulk-import per-row report
│       │   ├── config/         # SecurityConfig (auth, CSRF, cookie flags), CorsConfig
│       │                   #   (allowed origins), SchedulingConfig (@Profile("!test")),
│       │                   #   ProductionConfigCheck (refuses to start the "prod"
│       │                   #   profile with unset environment variables - an
│       │                   #   EnvironmentPostProcessor registered in
│       │                   #   META-INF/spring.factories, NOT a bean: it has to run
│       │                   #   before any @ConditionalOnProperty is evaluated, and a
│       │                   #   BeanFactoryPostProcessor was measurably too late),
│       │                   #   SpaRoutes + SpaWebConfig (serve the packaged Angular
│       │                   #   site and answer deep links with index.html - only
│       │                   #   GET/HEAD outside /api, /actuator and the docs paths),
│       │                   #   FirstAdminBootstrap (prod only: creates "admin" from
│       │                   #   APP_BOOTSTRAP_ADMIN_PASSWORD while no admin exists)
│       │   ├── security/       # AppUserDetailsService - loads accounts for Spring Security
│       │   └── exception/      # AssignmentNotFoundException, ResourceNotFoundException,
│       │                       #   AccessDeniedException, GlobalExceptionHandler
│       ├── main/resources/
│       │   ├── application.properties       # the DEVELOPMENT configuration
│       │   ├── application-prod.properties  # --spring.profiles.active=prod - tightens
│       │                   #   the above and REMOVES every default, so an unconfigured
│       │                   #   deployment fails instead of inheriting a dev value
│       │   └── db/migration/   # Flyway, SQL Server dialect - V1 baseline, V2 accounts and
│       │                       #   ownership, V3 must-change-password, V4 subjects/classes/
│       │                       #   courses/submissions, V5 timestamp column types,
│       │                       #   V6 assessment and marks, V7 admin role + audit log,
│       │                       #   V8 person profiles + external ids (STU/TCH/ADM sequences),
│       │                       #   V9 phone number, V10 enrolled-after-birth,
│       │                       #   V11 terms + periods + timetable slots,
│       │                       #   V12 performance indexes, V13 Omang format,
│       │                       #   V14 attendance register,
│       │                       #   V15 password reset tokens,
│       │                       #   V16 contact_email index (corrects a V12 claim),
│       │                       #   V17 attendance.recorded_by_id index,
│       │                       #   V18 messages + notifications,
│       │                       #   V19 login_attempt (the sign-in throttle)
│       └── test/
│           ├── java/com/example/tracker/
│           │   ├── AssignmentApiTest.java            # MockMvc, real security
│           │   ├── ConcurrencyAndIntegrityTest.java  # 12 racing submissions, DB constraints
│           │   ├── AccountSelfServiceTest.java       # password change (account CREATION
│           │   │                                     #   moved to AdminApiTest - admin only)
│           │   ├── AssessmentIntegrityTest.java      # marks, scoring arithmetic
│           │   ├── AssessmentReportTest.java         # the Excel endpoint - headers, sheets,
│           │   │                                     #   numeric cell types, per-role scoping
│           │   ├── AdminApiTest.java                 # onboarding, generated ids, bulk
│           │   │                                     #   import, teacher-to-student
│           │   │                                     #   assignment, the audit log
│           │   ├── InputValidationApiTest.java       # every DTO's Jakarta Bean Validation
│           │   ├── TimetableApiTest.java             # terms, periods, slots, the no-overlap
│           │   │                                     #   rule, and the delete refusals
│           │   ├── AttendanceApiTest.java            # the register: weekday/term/enrolment
│           │   │                                     #   rules, correction not duplication
│           │   ├── PasswordResetApiTest.java         # the identical answer whatever the
│           │   │                                     #   identifier matched; hashed tokens
│           │   ├── LoginThrottleTest.java           # the sign-in throttle: both limits,
│           │   │                                     #   that a success clears the count,
│           │   │                                     #   that an invented username is
│           │   │                                     #   refused in identical words
│           │   ├── config/ProductionProfileTest.java # that no seed runner is ungated,
│           │   │                                     #   and that every bare placeholder in
│           │   │                                     #   application-prod.properties is in
│           │   │                                     #   ProductionConfigCheck.REQUIRED
│           │   └── service/AssignmentServiceTest.java # business rules, repository mocked
│           └── resources/
│               ├── application-test.properties # H2, Flyway off, ddl-auto=create-drop
│               └── schema.sql                  # the STU/TCH/ADM sequences V8 creates for
│                                                #   production - Hibernate's ddl-auto has
│                                                #   no annotation that creates a free-
│                                                #   standing SEQUENCE, so this fills the gap
│                                                #   for the H2 test schema; see its own header
└── frontend/
    └── tracker-ui/             # The runnable Angular project - ng serve here
        └── src/
            ├── main.ts         # bootstrapApplication(AppComponent, appConfig)
            ├── index.html      # <base href="/"> - required once any route is more than
            │                   #   one path segment deep; see its own comment for the
            │                   #   defect this fixed
            ├── styles.css
            ├── environments/   # environment.ts (build), environment.development.ts (serve)
            └── app/
                ├── assignment.service.ts    # The only place that knows the non-admin API URLs
                ├── admin.service.ts         # The only place that knows the admin panel's
                │                            #   URLs - onboarding, bulk import, audit log
                ├── csrf.interceptor.ts      # Attaches X-XSRF-TOKEN cross-origin
                ├── session.service.ts       # WHO is signed in - a signal, read by guards
                │                            #   and every page outside any one component
                ├── notification.service.ts  # THE error banner - shared across navigations
                ├── timetable.service.ts     # terms, periods, slots, and "my week"
                ├── attendance.service.ts    # taking a register / reading your own
                ├── password-reset.service.ts # the two signed-out endpoints
                ├── theme.service.ts         # dark/light, persisted; toggle lives in the shell
                ├── confirm.service.ts       # THE confirm dialog - replaces window.confirm
                ├── notification-feed.service.ts # the notifications the SERVER raised -
                │                            #   NOT notification.service.ts, which is the
                │                            #   error banner; see its own header
                ├── messaging.service.ts     # conversations, correspondents, sending
                ├── auth.guard.ts            # signed-in + not mustChangePassword, else -> '/'
                ├── teacher.guard.ts         # teacher-only routes, else -> /marking-queue
                ├── admin-auth.guard.ts      # admin-only routes, else -> '/' - there is no
                │                            #   separate admin login page; see app.routes.ts
                ├── app.routes.ts            # every page, admin panel included - see its
                │                            #   own note on the root route carrying no guard
                ├── app.config.ts            # provideRouter + provideHttpClient
                ├── app.component.ts         # THE SHELL - ONE login form for every role,
                │                            #   forced password change, error banner,
                │                            #   <router-outlet>
                ├── app.component.html
                ├── home.component.ts/.html               # '/home' - the landing page,
                │                                         #   readable with no account
                ├── about.component.ts/.html              # '/about'   - public
                ├── privacy.component.ts/.html            # '/privacy' - public
                ├── terms.component.ts/.html              # '/terms'   - public
                ├── forgot-password.component.ts/.html    # '/forgot-password' - public
                ├── reset-password.component.ts/.html     # '/reset-password?token=' - public
                ├── not-found.component.ts/.html          # '**' - a real 404 page, NOT a
                │                                         #   redirect; see app.routes.ts
                ├── dashboard.component.ts/.html          # '/' - timetable, quick links
                │                                         #   (account creation moved to
                │                                         #   the admin panel entirely)
                ├── assignments-page.component.ts/.html   # '/assignments' - "Work I have set"
                ├── marking-queue-page.component.ts/.html # '/marking-queue' - submissions
                ├── my-students-page.component.ts/.html   # '/my-students' - a teacher's own
                │                                         #   roll, scoped server-side
                ├── my-timetable-page.component.ts/.html  # '/timetable' - the caller's week,
                │                                         #   every role, shaped by session
                ├── attendance-page.component.ts/.html    # '/attendance' - take a register,
                │                                         #   or read your own attendance
                ├── notifications-page.component.ts/.html # '/notifications' - read-only
                │                                         #   except for "read"; no delete
                ├── messages-page.component.ts/.html      # '/messages' - conversations and
                │                                         #   composing, two panes
                ├── reports-page.component.ts/.html       # '/reports' - performance summary
                │                                         #   + mark book + Export Excel
                ├── admin-dashboard.component.ts/.html    # '/admin' - quick links
                ├── admin-teachers-page.component.ts/.html   # '/admin/teachers' - onboard
                │                                            #   (one at a time or by CSV/
                │                                            #   Excel), reset password, delete
                ├── admin-students-page.component.ts/.html   # '/admin/students' - onboard,
                │                                            #   browse, link to detail
                ├── admin-teacher-detail.component.ts/.html  # '/admin/teachers/:id' - what
                │                                            #   one teacher teaches and whom;
                │                                            #   DERIVED from Course +
                │                                            #   Enrolment, nothing stored
                ├── admin-student-detail.component.ts/.html  # '/admin/students/:id' - the
                │                                            #   mirror: class, subjects and
                │                                            #   which teachers reach them;
                │                                            #   assign/unassign by subject
                ├── admin-accounts-page.component.ts/.html   # '/admin/accounts' - every
                │                                            #   account, read-only
                ├── admin-accounts-table.component.ts        # DataTables, that table
                ├── admin-timetable-page.component.ts/.html  # '/admin/timetable' - subjects,
                │                                            #   classes, courses, enrolment
                │                                            #   (WHO teaches what to whom)
                ├── admin-schedule-page.component.ts/.html   # '/admin/schedule' - terms,
                │                                            #   periods, slots (WHEN)
                ├── admin-audit-log-page.component.ts/.html  # '/admin/audit-log' - server-
                │                                            #   paginated, filterable
                ├── performance-table.component.ts   # DataTables, performance-by-student
                ├── marks-table.component.ts         # DataTables, the mark book
                ├── submission-table.component.ts    # DataTables, the marking queue
                ├── report-export.ts         # shared lazy pdfMake loader + exportPdfAction,
                │                            #   used by both DataTables components above
                ├── validation.ts            # every length/format constant + checkDecimal()
                │                            #   + fieldErrorMessage()
                ├── decimal-validator.directive.ts # appDecimal / appDecimalPositive
                ├── password-strength-validator.directive.ts # appPasswordStrength
                └── field-error.component.ts # the ONE place an inline error is drawn
```

**Note on documentation location:** all documentation lives in `docs/` at the repository root, because it describes the whole project rather than the backend alone. It previously sat under `backend/docs/` and was moved on 28 July 2026. `CLAUDE.md` and `README.md` remain at the root by design. Do not recreate a `backend/docs/` tree, and do not move these two files into `docs/`.

## Environment Commands
- **Backend:** `cd backend` then `.\mvnw.cmd spring-boot:run` (Windows) or `./mvnw spring-boot:run`. Serves on `http://localhost:8080`.
- **Frontend:** `cd frontend/tracker-ui` then `npm start`. Serves on `http://localhost:4200`.
- **Build backend:** `.\mvnw.cmd clean package -DskipTests` produces `backend/target/tracker-0.0.1-SNAPSHOT.jar`.
- **Build frontend:** `npm run build` in `frontend/tracker-ui`.
- **Type-check frontend:** `npx tsc --noEmit -p tsconfig.app.json`.
- **Run tests:** `.\mvnw.cmd test` - 310 tests, run against H2, no SQL Server needed.
- **Database:** SQL Server, database `School Management System`, login `tracker_app`. The connection string in `application.properties` uses port 14333; confirm your instance actually listens there before assuming a failure is the application's fault. Query it with:
  `sqlcmd -S "tcp:localhost,14333" -U tracker_app -P 'Tracker!2026Dev' -d "School Management System"`
- **Development accounts:** `admin`, `teacher` and `student`, all password `password123`, reset at every startup. **They do not exist under the `prod` profile** - all four seed runners in `TrackerApplication` carry `@Profile("!prod")`, and `ProductionProfileTest` fails the build if a new one is added without it. These keep their literal usernames as a documented exception - every account created through the admin panel from here on signs in with a generated id instead (`STU20260001`, `TCH20260001`, `ADM20260001`); see the Scope Guard section above.
- **Production profile:** `java -jar tracker.jar --spring.profiles.active=prod`. It reads `application-prod.properties`, which turns Swagger off, marks cookies `Secure`, honours `X-Forwarded-*`, keeps SQL and bind parameters out of the log, and - most importantly - **removes every development default**, so four environment variables become mandatory:

  | Variable | Example |
  |---|---|
  | `DB_URL` | `jdbc:sqlserver://db.example.com:1433;databaseName=School Management System;encrypt=true` |
  | `DB_USERNAME` | `tracker_app` |
  | `DB_PASSWORD` | (the real one) |
  | `APP_CORS_ALLOWED_ORIGINS` | `https://tracker.example.com` |
  | `MAIL_HOST` | `smtp.example.com` |
  | `RESET_LINK_BASE` | `https://tracker.example.com/reset-password` |

  `APP_BOOTSTRAP_ADMIN_PASSWORD` is optional and deliberately not in that list: it is read only by `FirstAdminBootstrap`, which creates the first `admin` account (forced to change its password) while no administrator exists and does nothing afterwards. Remove it once the first sign-in has happened. The whole deployment, including the jar that serves the Angular build itself, is described in `docs/guides/DEPLOYMENT_AZURE.md`.

  With any of them unset the application refuses to start and names all the missing ones at once - `ProductionConfigCheck` does that, and exists because Boot's `Binder` does NOT throw on an unresolved `${...}` in a `spring.datasource.*` value the way `@Value` does; it passes the literal text through, and the deployment then fails with a driver or login error pointing at the wrong thing. Verified by starting the profile both ways.

  `MAIL_HOST` and `RESET_LINK_BASE` are required for the same reason as the rest, and it is the sharpest one in the list: with no mail server `ResetLinkMailer` WRITES THE RESET LINK TO THE LOG instead of sending it, and never throws while doing so. With no `RESET_LINK_BASE` the emails that do go out contain `http://localhost:4200/...`. If a deployment genuinely has no mail server, take the forgot-password route out of `SecurityConfig` rather than letting the links go to the log.

  Behind a single origin (the production frontend build uses relative URLs, so that is the normal shape) set `APP_CORS_ALLOWED_ORIGINS` to the site's own origin. It is still required rather than defaulted, because the alternative is inheriting `http://localhost:4200` from the shared file and granting it credentialed access to a production API.

- **Health check:** `GET /actuator/health`, the one actuator endpoint that is exposed and the one path that does not need a session - a load balancer cannot sign in. It answers `{"status":"UP"}` and nothing more (`show-details=never`), so it does not describe the infrastructure behind it to an anonymous prober. Every other actuator path, `/actuator/health/db` included, requires a session.

  **What the prod profile does NOT fix, and a deployment still must:** `ck_person_profile_omang_format` is still `is_not_trusted = 1` from V13; there is no backup or restore procedure written down; the privacy policy and terms still carry bracketed placeholders for the operating school to complete, and say so in their own text.

Start the backend first. The frontend calls it directly at `http://localhost:8080` and shows a red banner with a Retry button if it cannot connect.

### Environment Gotchas
These cost real time. Check them before diagnosing further.

**Read this section as symptoms, not as settings.** It is split in two deliberately.
Group A holds facts about the project and the stack, true on any machine. Group B
holds problems that depend on the machine: they are written as *symptom, cause, how to
check, how to fix*, because the specific paths, ports and instance names differ
between installations.

> **Never copy a literal path, port or instance name out of this file and assume it
> matches your machine.** Doing exactly that is what produced a workspace full of
> "Invalid runtime for JavaSE-nn" errors on 4 August 2026: `.vscode/settings.json`
> carried four JDK paths from the original development machine, none of which existed
> on the new one. Verify a value before relying on it. The concrete values in force on
> the current machine are recorded in `docs/memory/PROJECT_STATUS.md` sections 2, 5
> and 6, which is the file that is meant to change per machine - this one is not.

#### Group A - always true, on any machine

- **`JAVA_HOME` must point at a JDK 25, and it is `JAVA_HOME` that decides.** The Maven Wrapper reads it in preference to whatever `java` is on the `PATH`, so the `PATH` can point anywhere without breaking the build. If `mvnw` fails with `Unsupported class file major version` or `class file version 61.0`, `JAVA_HOME` is unset, stale, or aimed at an older JDK. A terminal opened before it was set will not see it, and in VS Code the **editor** must be restarted, not just the terminal. Check with `echo $env:JAVA_HOME` (PowerShell) or `echo $JAVA_HOME`.
- **Stop the backend before rebuilding.** Windows holds a lock on the running jar and `mvnw clean` fails to delete it.
- **The JDBC driver cannot use shared memory.** SQL Server must have TCP enabled before Java can connect at all. This is the reason `sqlcmd` can connect while the application cannot, which looks baffling until you know it - `sqlcmd` will happily use shared memory.
- **Write SQL Server migrations as separate `GO` batches.** SQL Server compiles a whole batch before running any of it, so adding a column and using it in the same batch fails with "Invalid column name" even though the ALTER would have created it first.
- **Never wait on network idle** against the Angular dev server. Its live-reload websocket stays open, so that condition never arrives. Wait for the document instead.
- **The editor may use a different TypeScript than the build.** `.vscode/settings.json` pins `typescript.tsdk` to the project copy; select "Use Workspace Version" once when prompted, or the editor will report errors the build does not.
- **Do not round-trip UTF-8 files through PowerShell `Get-Content`/`Set-Content`.** It mangles the box-drawing characters in this file's directory tree. Edit Markdown with an editor or a tool that preserves encoding.
- **A single NUL byte makes a source file invisible to `grep` and `ripgrep`.** They classify it as binary and skip it, so the file silently drops out of every code search - while `javac` compiles it without complaint. This happened for real in `AssessmentService.java`, where a separator in a map key came out as `"\0"` instead of `" "`. Symptom: `grep` prints `Binary file X matches` instead of the matching lines. Check with `python -c "print(open('F','rb').read().count(b'\x00'))"`, and prefer a structured map key - `List.of(a, b)` - over concatenating with a delimiter, since then there is no separator to get wrong.
- **PowerShell's `-WebSession` persists headers between requests.** A probe that sets `X-XSRF-TOKEN` once will silently resend it forever, so a "no token" test passes for the wrong reason. Call `$session.Headers.Clear()` before asserting that a header is absent - but note that clearing it also strips the token from later writes, which then return `403` for CSRF reasons and are easily misread as an authorisation result.
- **Register Playwright dialog handlers before navigating.** Playwright dismisses dialogs by default, so a late `page.on('dialog', ...)` means `confirm()` returns false and the action under test never runs.
- **VS Code's Java Problems panel can report an entire class as "cannot be resolved" - a whole class name, a whole import - while `mvnw compile` and `mvnw test` both pass clean.** The Problems panel is Eclipse JDT's own project model, a completely separate process from Maven, and it can be resolving against a source tree that no longer exists. **Maven, not the Problems panel, is the authority on whether the code compiles** - if `mvnw compile` succeeds, treat red squiggles as stale until proven otherwise by actually reading the flagged lines. Do not "fix" code that already compiles and passes its tests to satisfy a stale diagnostic; that risks a real regression to silence a phantom one.

  **The cause is a setting, not bad luck, and it is now fixed in `.vscode/settings.json`.** `java.configuration.updateBuildConfiguration` was `interactive`, which means the language server does not refresh its classpath when the build configuration or source layout changes - it raises a notification and waits to be clicked. Dismiss or miss that prompt after a session that adds or deletes source files, and the model keeps resolving against the tree as it was beforehand, permanently: the errors survive restarts, which is exactly what makes them look real. It is now `automatic`. If you meet this on a machine that predates the change, or after an already-drifted index, run Command Palette -> "Java: Clean Java Language Server Workspace" once (failing that, "Java: Reload Projects").

  Before blaming the index, rule out the causes that produce identical symptoms: a JDK path in `java.configuration.runtimes` that does not exist on this machine (the entry is validated at startup and a bad one breaks the whole project model while Maven, reading `JAVA_HOME`, carries on fine); a NUL byte in the file; a package declaration that disagrees with the directory; or two source roots claiming the same class. All are quick to check and each has bitten this project at least once.

#### Group B - machine-dependent, verify before applying

- **More than one JDK installed, and `java` resolves to the wrong one.** Common where older JDKs are kept so other work keeps running. Harmless by itself: set `JAVA_HOME` to the JDK 25 and leave the `PATH` alone. Only a problem if something reads the `PATH` instead.
- **The Java language server reports `Invalid runtime for JavaSE-nn: the path points to a missing or inaccessible folder`.** `java.configuration.runtimes` in `.vscode/settings.json` lists JDK locations, every entry is validated at startup, and the list is machine-specific. Point the JavaSE-25 entry at your own JDK and delete entries for JDKs you do not have - listing one the project does not target costs an error for nothing. This never affects the Maven build, which ignores the setting entirely. Fully documented in `docs/error-fixes/invalid-runtime-for-javase.md`.
- **Maven fails with `PKIX path building failed` or `PKIX path validation failed`.** Some antivirus and corporate proxies (Avast, Kaspersky, Zscaler and others) intercept HTTPS by re-signing it with their own root certificate. Windows trusts that root; a freshly installed JDK does not. Import it into the JDK truststore with `keytool -importcert -trustcacerts -alias corp-proxy -file the-root.cer -cacerts -storepass changeit`. For npm, set `NODE_EXTRA_CA_CERTS` to a PEM copy. **These roots rotate**, so a build that worked yesterday can fail today - re-export and re-import, deleting the old alias first. If your machine has no such interception, none of this applies; Maven simply downloads.
- **The Maven Wrapper script or `java.exe` vanishes, or restoring it gives `Access denied` while other files write fine.** Antivirus false-positive quarantine. Avast has removed both `backend/mvnw.cmd` and a freshly installed `java.exe`. Restore from Quarantine and add exclusions for the project folder and your JDK install directory.
- **A browser cannot reach `localhost:4200`.** A system proxy may capture loopback traffic. Launch browser automation with `--no-proxy-server --proxy-bypass-list=<-loopback>`.
- **SQL Server will not accept a JDBC connection.** Work through these in order, since the defaults differ by installation: TCP enabled at all; the port the instance actually listens on; whether the instance is *named* (dynamic ports that change on every restart, so pin one) or the *default* instance (usually 1433 already); and whether authentication is Mixed Mode, which is required because the application signs in with the SQL login `tracker_app` rather than a Windows identity. Enabling TCP and changing the auth mode can be done through `xp_instance_regwrite` without an elevated shell if you are a sysadmin on the instance, but **restarting the service needs elevation**. A worked example is in `docs/memory/PROJECT_STATUS.md` section 5.
- **`node_modules/` is missing.** Run `npm install` in `frontend/tracker-ui`. Do not assume it is present because a document says the project ships with dependencies installed. Note also that npm 11 blocks package install scripts by default; if an esbuild platform error appears, run `npm approve-scripts --allow-scripts-pending`.

#### When you hit a new machine-specific problem

Add it to **Group B as a symptom**, not to Group A as a fact, and keep your own machine's literal values in `docs/memory/PROJECT_STATUS.md` rather than here. A gotcha written as "the path is X" becomes wrong the moment somebody clones the project; the same gotcha written as "if you see error Y, check Z" stays useful everywhere.

## Coding Standards & Patterns

### Layering - the core rule
Each layer may call only the layer directly beneath it:
```
Angular  --HTTP-->  Controller  -->  Service  -->  Repository  -->  Database
(browser)          (web layer)     (rules)       (data access)    (storage)
```
- **Controller:** HTTP only. Reads the request, delegates, returns the result. No business rules, no persistence calls.
- **Service:** All business rules and validation. Must not reference HTTP types or write SQL. This is also the transaction boundary.
- **Repository:** Data access only. A Spring Data interface; add query methods here, never business logic.
- **Model:** Entities and their constraints.

### Validation
Validate at both the web edge and in the service, deliberately. `@Valid` plus `@NotBlank` on the request DTO guards the edge; the service guard protects the rule no matter who calls it. Never rely on the client alone, and never rely on a single layer.

### Nullability - state the guarantee, do not suppress the warning
The editor runs Eclipse's null analysis (`java.compile.nullAnalysis.mode`), and it has caught real gaps. When it reports **"needs unchecked conversion to conform to `@NonNull`"** in production code, the cause is almost always that *our own* declaration carries no contract, so the analysis cannot see a guarantee that is genuinely true.

Annotate the declaration with `org.springframework.lang.NonNull` rather than silencing the call site:

| Declaration | Why it is genuinely non-null |
|---|---|
| `FileDownload` components | every backing column is `NOT NULL`; the service throws rather than returning a hole |
| Repository methods returning `List<T>` | Spring Data returns an empty list, never null |
| Private `require*` guards | they return a real object or throw - that is what "require" means |

**Annotating a `require*` guard `@NonNull` is not the end of it.** If the method body ends in `.orElseThrow(...)`, the warning does not disappear - it moves inside, to that return statement, because `java.util.Optional` is itself an unannotated JDK type and the compiler now has to verify the guarantee it was just told to trust. Route it through `service.Require.orThrow(optional, exceptionSupplier)` instead: it replaces `Optional`'s opaque generic return with a plain `if (value == null) throw ...;` check, which Eclipse's null-flow analysis verifies natively with no annotation involved at all. Every "look this up or fail" guard in the service layer (`AppUserService`, `AssessmentService`, `AssignmentService`, `SchoolService`, `SubmissionService`) goes through it - if you add a new one, use it from the start rather than rediscovering this.

**On an entity, `@NonNull` goes on the GETTER - not on the constructor parameter, and not on the field.** All three look like the same statement and none of them are. Working outwards from the mistakes:

- **Not the parameter.** A parameter annotation is a compile-time promise *from the caller* that Eclipse then trusts; it is erased at runtime and enforces nothing. Worse, any `if (x == null) throw ...` guard in that same constructor is then reported as **dead code**, because the analysis has been told the value can never be null. That is exactly backwards - the runtime check is what makes the guarantee true, and deleting it to silence the warning would leave an annotation asserting something nothing enforces.
- **Not the field.** A `@NonNull` field draws *"the @NonNull field x may not have been initialized"*, and Eclipse is right: the `protected` no-arg constructor every entity carries exists precisely to hand Hibernate an instance whose fields are still null, to be populated by reflection immediately afterwards. During that window the claim is false, so making it is the exact "annotate around a real null" mistake this section forbids.
- **The getter, with an explicit check.** What is genuinely true is that no entity a caller can ever observe holds a null there - the public constructor rejects it, and any row Hibernate rebuilt came from a `NOT NULL` column. `if (value == null) throw new IllegalStateException(...)` states that in a form ordinary flow-sensitive analysis verifies natively, with no trust involved. This is the same technique as `Require.orThrow`, applied inline because `Require` is package-private in the service layer and a model class reaching up into it would invert the layering.

So: unannotated parameter, an explicit reject in the public constructor, an explicit check in the getter, `@NonNull` on the getter alone. `SubmissionFile` and `Enrolment` are the worked examples. A related tell that a value is not really guaranteed yet: a hedge like `sizeBytes = content == null ? null : content.length` in the constructor is the code admitting a null can get in, and must be resolved by rejecting it rather than by annotating around it.

An injected dependency is the opposite case and *is* annotated on the parameter (`AdminService`'s `PlatformTransactionManager`): Spring either supplies a real bean or the context fails to start, so there is no null case for a check to catch and nothing to verify at runtime.

Two rules about this:
- **Never annotate something `@NonNull` to quiet a warning when it can actually be null.** That converts a visible warning into a runtime NPE. If it can be null, say `@Nullable` and handle it - `MultipartFile.getOriginalFilename()` is a real example, and `SubmissionService.sanitiseFilename` handles it.
- **`@SuppressWarnings("null")` is for TEST code only**, where the unannotated signatures belong to third-party libraries (Mockito, Hamcrest, Spring Test) and cannot be changed. Every use carries a written justification. In production code the answer is to fix the declaration - see `CorsConfig`, `SchoolService` and `FileDownload` for the precedent.

### Error handling contract
The service signals problems by throwing. `GlobalExceptionHandler` translates exceptions into status codes. Never return a bare `500` for a client mistake.

| Cause | Status |
|-------|--------|
| Invalid input, blank or over-long title, null id | 400 |
| Unknown assignment id | 404 |
| Already submitted, or a concurrent modification | 409 |
| Too many failed sign-in attempts, too quickly | 429 |
| Genuine server fault | 500 |

Every error body uses one shape so the client parses one format:
```json
{ "timestamp": "...", "status": 404, "error": "Not Found",
  "message": "No assignment found with id = 999",
  "path": "/api/assignments/999/submit" }
```

Do not echo driver or stack-trace text to the client. Constraint-violation details belong in the server log.

### Frontend rules
- **The service owns the URLs.** Components never call `HttpClient` directly.
- **Every `subscribe()` needs an error callback.** A failed request must reach the user; silent failure is a defect. Use the existing banner.
- **Keep types aligned with the backend.** `AssignmentStatus` in TypeScript mirrors the Java enum. If one changes, change both.

### Frontend input validation (added 6 August 2026)
This app uses **template-driven forms** throughout (`[(ngModel)]`), not `ReactiveFormsModule` - a standing choice, not something a new form should quietly deviate from. Centralized validation is built on top of that, in three files:

| File | Role |
|---|---|
| `validation.ts` | Every length/format constant, mirroring a real backend `@Size`/column bound (`USERNAME_MAX_LENGTH`, `ASSIGNMENT_TITLE_MAX_LENGTH`, ...); `checkDecimal()`; `fieldErrorMessage()` - the ONE function that turns a validator's error object into a sentence |
| `decimal-validator.directive.ts` | `appDecimal` / `appDecimalPositive` - a `NG_VALIDATORS` directive for the mark score/maxScore text fields, checked against DECIMAL(6,2)'s actual shape rather than what a JS number allows |
| `field-error.component.ts` | `<app-field-error [control]="xModel" label="X">` - the ONE place an inline error is drawn. Every validated field in every form uses this and nothing else. |

**A field earns validation by three things, together:** a validator attribute (`required`, `[minlength]`, `[maxlength]`, `appDecimal`), a template reference (`#xModel="ngModel"`), and `<app-field-error [control]="xModel" label="...">` underneath it. Cross-field rules (passwords must match, a score cannot exceed its maximum, at least one class must be selected) have no single `ngModel` to attach to, so they stay plain component getters returning `string | null`, fed to `<app-field-error>` via `[externalError]`.

**`[minlength]` / `[maxlength]`, never `[attr.minlength]` / `[attr.maxlength]`.** The two look interchangeable and are not. `[attr.X]` writes the raw DOM attribute directly, which native `maxlength` truncation happens to read - so a `maxlength` bound that way looks like it works - but it never reaches Angular's own `MinLengthValidator`/`MaxLengthValidator` directives, which read their value through a normal `@Input()` that only `[X]` (property binding) populates. Real defect, found by typing into the form in a browser: under `[attr.minlength]`, `errors.minlength` was never set, so a too-short password reported itself valid at any length with nothing to show for it. No unit test on either side would have caught this - it is a live DOM/directive-wiring behaviour, provable only by rendering the form.

**A boolean directive input needs `transform: booleanAttribute`.** `<input appDecimalPositive>` (bare, like `<input required>`) binds the string `""` to a plain `@Input() appDecimalPositive = false`, and `Boolean('')` is `false` - the opposite of what the bare attribute means. `@Input({ transform: booleanAttribute })` is what makes presence mean `true`.

### Frontend routing and page structure (added 6 August 2026)
What used to be one long page behind boolean flags is now four routed pages - Dashboard (`/`), "Work I have set" (`/assignments`, teachers only), the marking queue (`/marking-queue`), and Reports (`/reports`, performance-by-student and the mark book together, since a report over both is what makes them "basically the same table"). `SessionService` and `NotificationService` were extracted so identity and the error banner survive a navigation instead of dying with the one component that used to hold them - see `app.component.ts` for why the shell now only knows about login, forced-password-change, and delegating to whichever page `<router-outlet>` is showing.

**The root route (`''`) deliberately carries no guard.** Every OTHER guard's failure path redirects to `/`. Guarding `/` too created a real infinite redirect loop: a signed-out visitor hitting any guarded page bounced to `/`, re-triggered the same guard, and bounced again - a browser hang, not an error message. It is safe to leave unguarded because `AppComponent`'s own template hides `<router-outlet>` behind `*ngIf="user"`; while signed out there is nowhere for the router to project a component into, guard or not.

**A route guard must wait for `SessionService.checkingSession()` to settle, not read `user()`/`isTeacher` synchronously.** `SessionService.init()` is an async HTTP call fired from `AppComponent.ngOnInit()`. On a link clicked inside the running app that call has long since resolved, so reading the signal directly looks safe - and is, for that one case. It fails on a *hard* navigation (a typed URL, a bookmark, `page.goto()` in a test), where the Router's initial navigation and `init()`'s HTTP call start at the same moment: the guard reads a signal that has not been set yet and always loses the race, bouncing even a genuinely signed-in user back to `/`. Both guards resolve this by piping `toObservable(session.checkingSession)` through `filter(c => !c), take(1)` before deciding - found by Playwright driving a direct navigation to a teacher-only URL as a signed-in student and landing on `/` instead of `/marking-queue`, not by reasoning about the code.

**The Excel export is built entirely on the server**, `GET /api/assessments/report.xlsx` (`AssessmentReportService`, Apache POI). It calls the exact same `AssessmentService` methods (`listAssessments()`, `summarise()`) that the JSON endpoints call, so the download can never contain a row its caller could not already see through the API, and it is never limited by whatever happens to be paginated or loaded in the browser at the time. Two sheets, in a fixed order - Performance Summary, then Marks - matching the order the two tables appear in on the Reports page. Percentages are written as a fraction of 1 with a `"0.00%"` cell format, not as the literal number; getting that backwards is an easy, silent way to make an Excel export wrong without any test noticing unless it actually opens the file.

## Data Integrity, Referential Integrity and Consistency Standards
- **Constrain at the database, not just in Java.** Application checks are policy that holds only while every writer goes through this code. Column constraints make the rule true of the data itself. `title` is `NOT NULL` with `length = 200`; `status` is an enum column restricted to its valid values.
- **Use typed values for closed sets.** Status is an enum, stored with `EnumType.STRING` (never ordinal - positions shift when constants are reordered and silently reinterpret existing rows).
- **One business operation, one transaction.** Annotate service write methods with `@Transactional`; the class default is `@Transactional(readOnly = true)`. A read-check-write sequence without a transaction is a race, not a rule.
- **Guard concurrent edits with `@Version`.** Optimistic locking turns a lost update into a `409`. This was a real defect: before it existed, 3 of 12 simultaneous submissions were accepted when exactly 1 should have been.
- **Keep the schema honest.** The application database uses `ddl-auto=validate` plus Flyway migrations - Hibernate may check the schema but never change it. Never use `update`, which only ever adds and will not tighten an existing column. The test profile uses `create-drop` against H2 because Flyway's migrations are SQL Server dialect.
- **Referential integrity is real now.** `assignment.owner_id` is a foreign key to `app_user.id` with **`ON DELETE NO_ACTION`**, so the database refuses to delete an account that still owns work. Do not add `ON DELETE CASCADE`: destroying somebody's work as a side effect of removing their account must be an explicit decision in the service.
- **A constraint that H2 gave for free may not survive a port.** H2's native `ENUM` column type had no SQL Server equivalent, so the "status must be one of two values" guarantee had to be rewritten as an explicit `CHECK` constraint. When changing database, re-verify each constraint rather than assuming it moved.
- **Prove constraints by bypassing the application.** Write bad data directly with `sqlcmd` and confirm the database refuses it. A rule only enforced in Java is policy, not integrity.
- **Role rules are enforced by COMPOSITE foreign keys, not by Java alone.** `app_user` carries `UNIQUE (id, role)`; `enrolment`, `course`, `submission` and `assignment` each store the referenced user's role alongside their id, `CHECK` it against the literal the row requires, and point a two-column foreign key at `app_user (id, role)`. The `CHECK` alone would let a row claim STUDENT for a teacher; the foreign key alone would let it claim any role. Together the only value satisfying both is that user's real role. A consequence worth knowing before it surprises you: **a user's role cannot be changed while any row depends on it** - that is the guarantee working, not a bug.
- **JPA cannot express those composite keys, so the H2 test schema is weaker than production.** Tests get the `CHECK` plus a single-column foreign key, which still refuses a row claiming a role it may not hold. What only SQL Server refuses is a row claiming STUDENT for a teacher's id. Verify that half with `sqlcmd`.
- **Hibernate maps `java.time.Instant` to `datetimeoffset`, not `datetime2`.** Declaring a timestamp column as `DATETIME2` in a migration and mapping it from `Instant` fails at startup with `Schema-validation: wrong column type`. This is `ddl-auto=validate` doing its job - the alternative is times that silently lose their offset. Fixed in `V5__timestamp_columns_with_offset.sql`.
- **Never edit an applied migration.** Flyway stores a checksum of every one precisely so an applied migration cannot be changed underneath a database that has run it. A correction gets its own version, even when the mistake is minutes old.
- **Money-like values are `DECIMAL` and `BigDecimal`, never `FLOAT` or `double`.** Marks get added up and compared; binary floating point cannot represent 0.1 exactly, so totals drift and two equal marks can compare unequal. This extends to the browser: an `<input type="number">` bound with `[(ngModel)]` routes the value through a JavaScript double, which is why the mark fields are `type="text"` with `inputmode="decimal"` and are carried as strings all the way to the server.
- **A `UNIQUE` constraint on a nullable column is not what you want in SQL Server.** It treats NULLs as equal, so it permits exactly ONE null row in the whole table. "At most one mark per submission, but many marks with no submission" needs a **filtered** unique index (`WHERE submission_id IS NOT NULL`).
- **A filtered index makes `SET QUOTED_IDENTIFIER ON` mandatory for writes.** Any INSERT/UPDATE/DELETE on a table carrying one fails with error 1934 unless the session sets it. The JDBC driver sets it on connect, so the application is unaffected - but `sqlcmd` defaults it OFF, and the resulting failure looks exactly like the schema rejecting your data when it is really the session being misconfigured. Put `SET QUOTED_IDENTIFIER ON;` at the top of any ad-hoc script that writes to `assessment` or `app_user`.
- **Sometimes the correct design is NOT a foreign key.** `audit_log.performed_by_id` deliberately carries no foreign key to `app_user`, the one deviation from "constrain at the database" in this whole schema. A live reference here would make any account that had ever performed a single logged action permanently undeletable - since virtually every write is logged, that is nearly every account, immediately defeating `AdminService.deleteTeacher`. The log instead stores a snapshot (`performed_by_username`, `performed_by_role`) captured at the moment of the action, which stays true and readable after the account is long gone. `uq_app_user_external_id` is the more familiar case in the same migration - a filtered unique index, for the same "the legacy dev accounts have no `external_id`, and SQL Server treats every NULL as equal" reason `assessment.submission_id` already established.
- **A database SEQUENCE, not `SELECT MAX(id) + 1`, generates every admin-issued login id.** `IdGeneratorService` calls `NEXT VALUE FOR seq_student_id` (and `_teacher_id`, `_admin_id`) rather than reading the highest number issued so far - the latter is a genuine race under two admins onboarding at the same moment, caught only as a 409 the second one has to retry past. A sequence is atomic by construction, so there is nothing to retry. H2 has no annotation that creates a free-standing sequence as a side effect of `ddl-auto`, so the test schema gets its own copy in `src/test/resources/schema.sql`, which Spring Boot runs automatically against any embedded database.

### Testing Requirements
There is an automated suite: `.\mvnw.cmd test` runs 310 tests, and `package` fails the build if any fail. Add to it rather than reverting to manual checks.

- **Unit tests** (`AssignmentServiceTest`) - business rules with the repository mocked.
- **Full-stack tests** (`AssignmentApiTest`, `InputValidationApiTest`) - MockMvc with real security; every status code in the error contract, and every DTO's Jakarta Bean Validation.
- **Concurrency and integrity** (`ConcurrencyAndIntegrityTest`, `AssessmentIntegrityTest`) - 12 simultaneous submissions must yield exactly one `200`; the database must refuse orphans, duplicates, invalid enum values, over-long titles, and out-of-range marks.
- **The Excel report** (`AssessmentReportTest`) - attachment headers, sheet order and headers, numeric cell types, and that a teacher's download is scoped to their own courses (proven with an assertion strong enough to fail if scoping regressed, not merely one that happens not to trip).
- **Admin management** (`AdminApiTest`) - onboarding a student/teacher/admin with a generated id, that id actually signing in, bulk CSV/Excel import (including a bad row inside a good file not being persisted - see the transaction-boundary note on `AdminService.importOneRowSafely`), admin-only enforcement on every one of those endpoints, assigning/unassigning a teacher to a student through `Course`, and the audit log (pagination, filtering, and that only ADMIN may read it).

Two things the suite cannot cover, so check them by hand when relevant:
- **The SQL Server migrations.** Tests run on H2, so migration SQL is unexercised; `ddl-auto=validate` is what catches drift between migrations and entities.
- **Browser behaviour.** Drive `http://localhost:4200` as **both** roles. The CSRF defect passed every API test and only appeared in Chrome, because Angular refuses to attach its token cross-origin. The two route-guard defects described above are the same lesson repeated: both passed every unit and API test, because both were about how the *browser* resolves a route, not about anything the backend does. Include at least one **hard navigation** (`page.goto()` to a deep URL, not a link click inside the app) in any routing check - it is the only way to exercise the race between a guard and `SessionService.init()`.

Report results honestly, including failures. Do not describe a check as passing unless it was run.

## Documentation Standards
- **Style:** Professional, technical, objective.
- **Format:** Standard Markdown - headings, tables, lists.
- **Prohibition:** Strictly zero emojis in `.md` files.
- **Accuracy over completeness:** a document that describes features the code does not have is worse than no document. When code and documentation disagree, fix the document in the same change.
- **Mark speculation as speculation.** Separate what is built from what is proposed, and never present a plan as delivered work.
- **Do not rewrite dated records.** Files in `docs/daily-reports/` describe what was true on their date. Correct them only if they were wrong when written, never to reflect later changes.
- **Organization:** documentation belongs in its rightful folder under `docs/`:
  - `architecture/` - system design, data flow, architectural patterns
  - `implementation/` - implementation guides, code summaries, completion reports
  - `project/` - requirements, planning, deliverables, scope
  - `guides/` - quick start, testing, how-to
  - `error-fixes/` - bug fixes, error resolutions, issue tracking
  - `daily-reports/` - daily progress and status updates
  - `memory/` - session handoff. Implementation state, settled decisions, and the per-machine values the rest of the documentation deliberately avoids hardcoding. Kept current rather than dated, which is what separates it from `daily-reports/`.
- **Never** leave documentation files loose in the `docs/` root, apart from `DOCUMENTATION_INDEX.md`.

## Error Resolution Procedure

### When an Error Occurs or Needs Fixing
1. **Check existing documentation first**
   - Search `docs/error-fixes/` for the message, code, or keywords
   - Check `docs/daily-reports/` for recent issues and resolutions
   - Check `docs/implementation/` for known issues and completed fixes

2. **Identify if already documented**
   - If documentation exists, review the root cause and solution
   - If a fix was applied, verify it was implemented correctly
   - If multiple solutions exist, choose the most recent or recommended one

3. **Apply the documented solution**
   - Follow the documented steps, reference the file in your response

4. **If not documented**
   - Reproduce the failure first, so the fix can be proven rather than assumed
   - Proceed with analysis and implementation
   - Create documentation in `docs/error-fixes/` covering root cause, fix, testing steps, and troubleshooting

5. **If the documented solution does not work**
   - Test it thoroughly to confirm it genuinely fails
   - Analyse why (environment differences, code changes)
   - Implement a new solution from root-cause analysis
   - Update the original file with: "Why Previous Solution Failed", the revised solution, updated testing steps, an "Updated: [DATE]" note at the top, and links to related issues
   - Alert the user that documentation has been revised

### Error Documentation Template
- **Issue Title:** clear, searchable description
- **Root Cause:** technical explanation of why it occurred
- **Fix Applied:** exact changes - file paths, line numbers, code
- **Testing Steps:** how to verify, including the command run and its output
- **Troubleshooting:** further diagnostics if it persists
- **Related Files:** everything the fix touched

## Critical Rules
- **Rule 1 - Layering is not negotiable.** Business rules live in the service. A controller that decides anything, or a service that knows about HTTP, must be corrected rather than extended.
- **Rule 2 - Integrity is enforced at the database.** Any new field or state must carry its constraint into the schema, not only into Java.
- **Rule 3 - Failures must be visible.** Every error path returns an accurate status and a message a person can act on, and the interface must surface it.
- **Rule 4 - Do not claim unverified work.** State what was run and what it returned. If something was skipped or failed, say so.
