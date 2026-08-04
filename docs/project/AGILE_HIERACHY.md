# School Management System - Agile Methodology
## Scrum Framework with Application > Epic > Feature > User Story > Task Hierarchy

**Last updated:** 4 August 2026

> **Document reset.** A previous version of this file recorded four completed sprints
> in March 2026 covering teacher registration, student CRUD, assessment scoring,
> DataTables, a student portal and an admin dashboard, built on ASP.NET, Entity
> Framework and SQL Server. None of that exists in this repository, and none of it was
> built here. That content was inherited from an unrelated project.
>
> This version records only work that can be verified against the code and the commit
> history. Delivered items are marked **Done**; everything else is marked **Proposed**
> and is a candidate, not a commitment.

> **Translation note, 4 August 2026.** The ASP.NET hierarchy described above was
> supplied again and has now been **translated** into this stack rather than discarded,
> so the planning value survives without importing a foreign design. Every one of its
> seven epics is accounted for in
> [Appendix A: Disposition of the ASP.NET hierarchy](#appendix-a-disposition-of-the-aspnet-hierarchy)
> - each item is marked as already delivered, translated into a proposed epic below,
> or excluded with a reason.
>
> Three constraints shaped the translation:
>
> - **Identifiers already in use were not reused.** `US-01`-`US-20` and
>   `TASK-01`-`TASK-65` denote delivered work, and several story IDs appear in the test
>   suite's display names (`visibility (US-15)`, `overdue (US-18)`). New work therefore
>   starts at `EPIC-09`, `FEAT-14`, `US-21`, `TASK-66`.
> - **.NET technology was replaced, not carried over.** EF Core migrations became
>   Flyway, `Program.cs` registration became Spring Boot configuration, XML doc comments
>   became springdoc annotations, and jQuery DataTables became Angular-native sorting
>   and filtering.
> - **One instruction was corrected rather than translated.** The source specified
>   `400 Bad Request` for invalid credentials. This system returns `401` deliberately,
>   with the same message for a wrong username as for a wrong password, so that the API
>   cannot be used to discover which usernames exist. That decision stands.

---

## Overview

This document applies the **Scrum framework** to the School Management System project.
Scrum delivers value in short, time-boxed iterations called **Sprints**, with defined
roles, artifacts and events.

Work is organised using a **five-level hierarchy** - **Application > Epics > Features
> User Stories > Tasks** - which populates the **Product Backlog**. User Stories are
estimated in **story points** on the Fibonacci scale and prioritised by value.

---

## Technology Stack

| Concern | Choice |
|---------|--------|
| **Backend** | Java 25 (LTS), Spring Boot 3.5.16, embedded Tomcat, port 8080 |
| **Persistence** | Spring Data JPA, Hibernate 6.6 |
| **Database** | SQL Server 2019 Developer Edition, instance `MSSQLSERVER01`, database `School Management System`, TCP port 14333 |
| **Schema management** | Flyway migrations; `ddl-auto=validate` so Hibernate may never alter the schema |
| **Security** | Spring Security - session authentication, BCrypt hashing, CSRF tokens, role-based rules |
| **Frontend** | Angular 18, standalone components, port 4200 |
| **Validation** | Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Size`) plus database constraints |
| **Build** | Maven Wrapper (`mvnw`) for the backend; npm / Angular CLI for the frontend |
| **Source control** | Git, GitHub - `AtreusTefo/School-Management-System` |
| **API documentation** | None. The contract is documented in `docs/project/PRD.md` section 5 |
| **Error logging** | Spring Boot default console logging |
| **Object mapping** | None. Small nested DTOs are used for request bodies |
| **Testing** | JUnit 5, Mockito, MockMvc, Spring Security Test - 48 tests against H2 |

**Roles:** `TEACHER` and `STUDENT`. A teacher sets work, may set it FOR a student,
and may edit, delete or reopen any assignment. A student sees only their own work
and may submit it.

> **A note on Entity Framework.** An earlier instruction asked for Entity Framework
> 6.4 with an `ApplicationDbContext`. EF is a .NET technology and cannot run on this
> Java backend; that wording came from the inherited documentation describing an
> unrelated ASP.NET application. JPA/Hibernate fills the same role and is equally
> code-first - the schema derives from the `@Entity` classes - so the database
> requirement was met without a rewrite.

---

## Agile Hierarchy: Definitions

### Application
The complete product delivered at the end of all Sprints.

> **This project**: *School Management System - Assignment Tracker*. A full-stack web
> application that lists assignments, allows one to be created, and allows one to be
> marked as submitted - built to demonstrate a strictly layered architecture.

### Epic
A large body of work representing a business domain or major capability. Spans
multiple Sprints and breaks down into Features.

> **Format**: `EPIC-XX: <Title>`

### Feature
A service or function delivering business value within an Epic. Breaks down into User
Stories.

> **Format**: `FEAT-XX: <Title>`

### User Story
A single piece of functionality from the end-user's perspective. Estimated in story
points and broken into Tasks.

> **Format**: `US-XX: As a [role], I want [goal], so that [benefit].`
>
> Each story carries a Description, Acceptance Criteria, Tasks, and a concrete example.

### Task
The smallest unit of work - a concrete implementation step. Completed within a Sprint
and not separately estimated.

> **Format**: `TASK-XX: <Action verb> + <implementation step>`

---

## Hierarchy Map

```
APPLICATION: School Management System - Assignment Tracker
│
├── EPIC-01: Assignment Tracking                                    [Done]
│   ├── FEAT-01: View Assignments
│   │   └── US-01: View all assignments and their status
│   ├── FEAT-02: Create Assignment
│   │   └── US-02: Add a new assignment by title
│   └── FEAT-03: Submit Assignment
│       └── US-03: Mark an assignment as submitted
│
├── EPIC-02: Reliability and Error Handling                         [Done]
│   ├── FEAT-04: Accurate HTTP Error Contract
│   │   ├── US-04: Receive the correct status code for every failure
│   │   └── US-05: Receive a readable message with every failure
│   └── FEAT-05: Failure Visibility in the Interface
│       ├── US-06: See an error message when an action fails
│       └── US-07: Be told when the backend cannot be reached
│
├── EPIC-03: Data Integrity and Consistency                         [Done]
│   ├── FEAT-06: Storage-Level Constraints
│   │   ├── US-08: Reject invalid data at the database, not only in code
│   │   └── US-09: Restrict status to a known set of values
│   └── FEAT-07: Concurrency Control
│       └── US-10: Accept only one of several simultaneous submissions
│
├── EPIC-04: Developer Experience and Documentation                 [Done]
│   ├── FEAT-08: Reproducible Build
│   │   ├── US-11: Build the backend without installing Maven
│   │   └── US-12: Start the frontend with a single command
│   └── FEAT-09: Accurate Project Documentation
│       └── US-13: Read documentation that matches the running system
│
├── EPIC-05: Persistence                                            [Done]
│   └── FEAT-10: Durable Storage
│       └── US-14: Keep assignments after a restart              (PRD R1)
│
├── EPIC-06: Accounts and Roles                                     [Done]
│   └── FEAT-11: Identity
│       ├── US-15: Sign in and see only my own assignments      (PRD R3)
│       └── US-16: Restrict who may create an assignment        (PRD R3)
│
├── EPIC-07: Assignment Lifecycle                                   [Done]
│   └── FEAT-12: Editing and Deadlines
│       ├── US-17: Correct or remove an assignment              (PRD R5)
│       ├── US-18: Set a due date and see overdue work          (PRD R4)
│       └── US-19: Undo an accidental submission                (PRD R6)
│
├── EPIC-08: Automated Testing                                      [Done]
│   └── FEAT-13: Regression Suite
│       └── US-20: Run the verification suite automatically   (PRD NFR-9)
│
├── EPIC-09: Account Self-Service                                   [Done]
│   ├── FEAT-14: Password Management
│   │   ├── US-21: Change my own password                        (PRD R7)
│   │   └── US-22: Be forced to replace a temporary password      (PRD L9)
│   └── FEAT-15: Account Administration
│       └── US-23: Create an account for a new student            (PRD R7)
│
├── EPIC-10: Finding Work in a Long List                            [Done]
│   └── FEAT-16: Sorting and Filtering
│       ├── US-24: Sort the assignment table by any column
│       ├── US-25: Filter assignments by text
│       └── US-26: Filter assignments by status
│
├── EPIC-11: API Documentation                                      [Done]
│   └── FEAT-17: OpenAPI Description
│       └── US-27: Browse and try the endpoints in a UI
│
├── EPIC-12: Student Overview                                       [Done]
│   └── FEAT-18: Personal Summary
│       └── US-28: See what is outstanding, submitted and overdue
│
└── EPIC-13: Continuous Verification                         [Part done]
    └── FEAT-19: Pipeline and Migration Coverage
        ├── US-29: Run the suite on every push          [Done]  (PRD R9)
        └── US-30: Exercise the SQL Server migrations   [Not started] (PRD R10)
```

**EPIC-01 to EPIC-08 are delivered** - 20 stories, 92 points. Sprint 2 below records
how, and what changed in the design along the way.

**EPIC-09 to EPIC-13 were delivered in Sprint 3, on 4 August 2026**, except US-30.
They are the translation of the ASP.NET hierarchy into work that makes sense for an
assignment tracker; Appendix A records what each was translated from and what was
excluded. Sprint 3 below records what was built, and the two corrections the work
forced.

> **US-25, US-26 and US-28 were already built when Sprint 3 began.** The search box,
> the status filter and the summary cards existed in `app.component.ts` and its
> template, having been added alongside the styling work, but no story had been written
> for them - so this document listed them as proposed while the code had them running.
> They are marked Done and pointed out here rather than quietly reclassified, because
> "the backlog said proposed and the code said shipped" is the exact failure this
> document was reset to stop.

---

## Scrum Framework

### Scrum Team

| Role | Person | Responsibilities |
|------|--------|-----------------|
| **Product Owner (Proxy)** | Teacher / student end-user | Represents needs, prioritises the backlog, accepts increments |
| **Scrum Master and Developer** | Atreus Tefo Ramokate | Solo developer: all Scrum events, backlog management, design, implementation, verification, deployment |

> **Note:** this is a solo project. The Product Owner role is represented by the
> end-user perspective rather than a separate person.

### Scrum Artifacts

**Product Backlog** - all work, tracked below and mirrored as GitHub Issues where
useful. Refined as understanding improves.

**Sprint Backlog** - the subset selected for the current Sprint.

**Increment** - a working, verified build merged to `main`. The current increment is
commit `80b3c9b` plus the data-integrity work of 28 July.

### Scrum Events

| Event | Timing | Timebox | How conducted |
|-------|--------|---------|---------------|
| **Sprint Planning** | Start of Sprint | ~30 min | Define the Sprint Goal and select backlog items |
| **Daily Scrum** | Each coding day | ~5 min | Solo check-in; note blockers |
| **Sprint Review** | End of Sprint | ~15 min | Demonstrate the running application and record what shipped |
| **Sprint Retrospective** | After Review | ~15 min | Start / Stop / Continue notes |

### Definition of Done

A User Story is **Done** only when all of the following hold:

- Code is committed and pushed to `main`.
- `.\mvnw.cmd clean package` succeeds, **including the test suite**; `npm run build`
  succeeds.
- The automated tests pass. They cover the API surface (`200` list and create; `400`
  blank, missing and over-long title; `404` unknown id; `400` non-numeric id; `409`
  resubmit), the role rules, the ownership scoping, and the full lifecycle.
- Authority holds: an anonymous request is `401`, a write without a CSRF token is
  `403`, and a student cannot create, edit or delete.
- Data survives a restart, and the database rejects invalid data written directly to
  it, bypassing the application.
- The application has been driven in a browser at `http://localhost:4200` as **both**
  roles, with no console errors.
- Failure is visible: with the backend stopped, the page says so and offers Retry.
- No error path returns a bare `500`.
- Documentation affected by the change was updated in the same commit.

> **The known gap is closed.** These checks used to be a script somebody had to
> remember to run; US-20 turned them into a suite that is now 48 tests that run as part of the build.
> Two limits remain honest: the suite runs against H2, so the SQL Server migrations
> are not themselves covered, and browser behaviour still needs a real browser -
> which is exactly where the CSRF defect was found.

### Story Point Scale (Fibonacci)

| Points | Effort | Example from this project |
|:------:|--------|---------------------------|
| 1 | Trivial | Fix a typo, adjust a config value |
| 2 | Small | Add a validation annotation, narrow a TypeScript type |
| 3 | Medium | Add an exception handler mapping, add a column constraint |
| 5 | Standard | Build one endpoint end to end, with its UI and error path |
| 8 | Large | Introduce transactions and optimistic locking across a layer |
| 13 | Very Large | Build a new Epic from scratch, such as accounts and roles |

---

## Product Backlog

Delivered work first, then candidates. **Total delivered: 47 points.**

| ID | User Story | Priority | Points | Status |
|----|-----------|----------|:------:|:------:|
| US-01 | View all assignments and their status | High | 3 | Done |
| US-02 | Add a new assignment by title | High | 5 | Done |
| US-03 | Mark an assignment as submitted | High | 5 | Done |
| US-04 | Correct status code for every failure | High | 5 | Done |
| US-05 | Readable message with every failure | High | 3 | Done |
| US-06 | See an error message when an action fails | High | 3 | Done |
| US-07 | Be told when the backend is unreachable | Medium | 2 | Done |
| US-08 | Reject invalid data at the database | High | 3 | Done |
| US-09 | Restrict status to a known set of values | High | 3 | Done |
| US-10 | Accept only one simultaneous submission | High | 8 | Done |
| US-11 | Build the backend without installing Maven | Medium | 2 | Done |
| US-12 | Start the frontend with a single command | Medium | 3 | Done |
| US-13 | Documentation matching the running system | Medium | 2 | Done |
| US-14 | Keep assignments after a restart | High | 5 | Done |
| US-15 | Sign in and see only my own assignments | High | 13 | Done |
| US-16 | Restrict who may create an assignment | Medium | 3 | Done |
| US-17 | Correct or remove an assignment | Medium | 5 | Done |
| US-18 | Set a due date and see overdue work | Medium | 8 | Done |
| US-19 | Undo an accidental submission | Low | 3 | Done |
| US-20 | Run the verification suite automatically | High | 8 | Done |
| **Delivered** | | | **92** | **all 20 stories** |

### Sprint 3 - delivered 4 August 2026

| ID | User Story | Epic | Priority | Points | Status |
|----|-----------|------|----------|:------:|:------:|
| US-29 | Run the suite on every push | EPIC-13 | High | 3 | Done |
| US-21 | Change my own password | EPIC-09 | High | 5 | Done |
| US-22 | Be forced to replace a temporary password | EPIC-09 | High | 3 | Done |
| US-23 | Create an account for a new student | EPIC-09 | Medium | 5 | Done |
| US-25 | Filter assignments by text | EPIC-10 | Medium | 2 | Done |
| US-24 | Sort the assignment table by any column | EPIC-10 | Medium | 3 | Done |
| US-26 | Filter assignments by status | EPIC-10 | Low | 2 | Done |
| US-28 | See what is outstanding, submitted and overdue | EPIC-12 | Low | 3 | Done |
| US-27 | Browse and try the endpoints in a UI | EPIC-11 | Low | 3 | Done |
| **Delivered** | | | | **29** | **9 stories** |

### Still open

| ID | User Story | Epic | Priority | Est. | Status |
|----|-----------|------|----------|:----:|:------:|
| US-30 | Exercise the SQL Server migrations | EPIC-13 | Medium | 8 | Not started |

> **Why US-30 was left.** It needs Testcontainers and a working Docker daemon, which
> makes it the one story here that cannot be verified on this machine today. Claiming
> it on the strength of code that has never run would be exactly the kind of unverified
> assertion this document exists to prevent. The gap it addresses is real and is still
> recorded as PRD R10 and L10.

---

## Sprint Record

### Sprint 1 - Working Application, Honest Failures, Sound Data
**Dates:** 27-28 July 2026
**Sprint Goal:** *Get the full stack running, make every failure report itself
accurately, and make the integrity rules true of the data rather than only of the
code.*
**Delivered:** 47 story points

| Story | Title | Points | Status |
|-------|-------|:------:|:------:|
| US-01 | View all assignments and their status | 3 | Done |
| US-02 | Add a new assignment by title | 5 | Done |
| US-03 | Mark an assignment as submitted | 5 | Done |
| US-04 | Correct status code for every failure | 5 | Done |
| US-05 | Readable message with every failure | 3 | Done |
| US-06 | See an error message when an action fails | 3 | Done |
| US-07 | Be told when the backend is unreachable | 2 | Done |
| US-08 | Reject invalid data at the database | 3 | Done |
| US-09 | Restrict status to a known set of values | 3 | Done |
| US-10 | Accept only one simultaneous submission | 8 | Done |
| US-11 | Build the backend without installing Maven | 2 | Done |
| US-12 | Start the frontend with a single command | 3 | Done |
| US-13 | Documentation matching the running system | 2 | Done |
| **Total** | | **47** | |

**Sprint Review.** The application runs end to end: backend on 8080, Angular on 4200.
All three endpoints work, and all nine API checks pass. Three defect classes were
found and fixed rather than merely reported:

1. Every business-rule failure returned `500` with an empty body, because nothing
   translated service exceptions into status codes. Now `400`, `404` and `409` as
   appropriate, each with a usable message.
2. The frontend discarded every failure - all three `subscribe()` calls had a success
   callback and nothing else - so a failed click looked identical to one that was
   ignored. Now surfaced in a dismissible banner.
3. The rule "an assignment cannot be submitted twice" did not hold under concurrency.
   Measured before the fix: 3 of 12 simultaneous submissions accepted. Cause was a
   read-check-write sequence with no transaction. Fixed with `@Transactional` plus a
   `@Version` column, then re-measured against both builds with the same probe -
   violated 5 of 5 rounds before, held 5 of 5 rounds after.

Supporting work: the Angular project was scaffolded and moved into the repository at
`frontend/tracker-ui` (previously the repo held source files with no build config, so
`ng serve` had nothing to serve); the Maven Wrapper was added so the build no longer
requires a system Maven; and `title` and `status` gained real column constraints.

**Sprint Retrospective**

| | Notes |
|-|-------|
| Start | Reproducing a defect before fixing it. The concurrency bug was only credible because it was measured first, and the fix only credible because the same probe was re-run against both builds. |
| Start | Recording environment fixes in `CLAUDE.md` as they are found. The antivirus TLS interception and the loopback proxy each cost significant time and would have cost it again. |
| Stop | Trusting inherited documentation. Three documents described a different application entirely, which would have misled anyone joining the project. |
| Continue | Checking error paths, not just happy paths. Every defect this Sprint lived in a path that ordinary use does not reach. |
| Continue | Keeping the layered structure strict. It made each fix land in exactly one place. |

---

### Sprint 2 - Durable Data, Real Accounts, a Full Lifecycle, and a Safety Net
**Dates:** 29 July 2026
**Sprint Goal:** *Move off the in-memory database, give the system a notion of who
is using it, complete the assignment lifecycle, and make every guarantee testable
without a human remembering to check.*
**Delivered:** 45 story points

| Story | Title | Points | Status |
|-------|-------|:------:|:------:|
| US-14 | Keep assignments after a restart | 5 | Done |
| US-20 | Run the verification suite automatically | 8 | Done |
| US-15 | Sign in and see only my own assignments | 13 | Done |
| US-16 | Restrict who may create an assignment | 3 | Done |
| US-17 | Correct or remove an assignment | 5 | Done |
| US-18 | Set a due date and see overdue work | 8 | Done |
| US-19 | Undo an accidental submission | 3 | Done |
| **Total** | | **45** | |

**Sprint Review.**

*Persistence (EPIC-05).* The database is now SQL Server 2019 Developer Edition on
instance `MSSQLSERVER01`, database `School Management System`. Schema changes go
through Flyway migrations and `ddl-auto=validate`, so Hibernate may check the schema
but never change it. Verified by creating an assignment, killing the JVM, restarting,
and finding the row still present - the acceptance criterion US-14 was written for.

*Accounts and roles (EPIC-06).* Two roles, session authentication, BCrypt hashing and
CSRF protection. This also gave the schema its first FOREIGN KEY: `assignment.owner_id`
references `app_user.id`, with no `ON DELETE CASCADE`, so the database refuses to
delete an account that still owns work rather than silently destroying it.

*Lifecycle (EPIC-07).* Edit, delete, due dates with a derived `OVERDUE` state, and
teacher-only reopening. Overdue is computed on every read rather than stored: a
stored flag is wrong the moment midnight passes.

*Testing (EPIC-08).* 36 automated tests - 17 unit tests of the business rules with
mocks, 12 full-stack tests through MockMvc with real security, and 7 covering
concurrency and database integrity. The nine checks that used to be a PowerShell
script somebody had to remember are now part of `mvnw test`.

**Three design corrections made during the Sprint,** each found by testing rather
than by review:

1. *A student could see nothing.* Ownership was first set to the creator, and only
   teachers can create - so a student's list was always empty and the role was
   decorative. Creating now accepts an optional `assignTo`, so a teacher sets work
   FOR somebody.
2. *A teacher could not fix their own typo.* With edit restricted to the owner, a
   teacher who assigned work to a student lost the ability to correct it, while the
   student gained the ability to rewrite the assignment they had been set. Editing
   now follows the ROLE, not the row.
3. *Sign-in failed in the browser but passed every API test.* Angular deliberately
   refuses to attach its CSRF token to cross-origin requests, and in development the
   API is on another port. A narrow interceptor now forwards the token to our own API
   only. No API-level test could have caught this; it needed a real browser.

**Sprint Retrospective**

| | Notes |
|-|-------|
| Start | Testing in a real browser as well as against the API. Two of the three defects above were invisible to HTTP-level probes. |
| Start | Writing migrations as separate batches on SQL Server. Adding a column and using it in one batch fails to compile, because SQL Server parses the whole batch first. |
| Stop | Trusting a probe that passes. The CSRF "no token" check passed for the wrong reason - PowerShell's `-WebSession` silently resends headers, so the test was sending the token it claimed to omit. |
| Continue | Making the database enforce its own rules. Every constraint was checked by writing bad data through `sqlcmd`, bypassing the application entirely. |
| Continue | Fixing the cause rather than the symptom. The null-analysis warnings were removed by not overriding an annotated method, not by adding a suppression. |

---

## EPIC-01: Assignment Tracking

> **Goal**: Provide one shared list that answers "what has been handed in?" without
> anyone having to ask around.

### FEAT-01: View Assignments

#### US-01: View All Assignments and Their Status
> **Story Points**: 3 | **Sprint**: 1 | **Status**: Done

**As a** teacher or student,
**I want** to see every assignment and its current status in one table,
**so that** I can tell at a glance what is outstanding.

**Acceptance Criteria**
- [x] The list loads automatically when the page opens.
- [x] Each row shows title, status, and an action control.
- [x] An empty list shows "No assignments yet. Add one above." rather than a blank table.
- [x] `GET /api/assignments` returns `200` and a JSON array.

**Tasks**
- TASK-01: Create the `Assignment` entity and `AssignmentRepository`
- TASK-02: Implement `getAllAssignments()` in the service
- TASK-03: Implement `GET /api/assignments`
- TASK-04: Build the Angular table and load it from `ngOnInit`

**Example**
> Opening `http://localhost:4200` shows two seeded rows - "Math Homework 1" and
> "History Essay" - both `IN_PROGRESS`.

### FEAT-02: Create Assignment

#### US-02: Add a New Assignment by Title
> **Story Points**: 5 | **Sprint**: 1 | **Status**: Done

**As a** teacher,
**I want** to add an assignment by typing its title,
**so that** it can be tracked without any setup.

**Acceptance Criteria**
- [x] The form accepts a title and nothing else; the client cannot set id or status.
- [x] The server assigns the id and forces the starting status to `IN_PROGRESS`.
- [x] Surrounding whitespace is trimmed.
- [x] The Add button is disabled until the field holds non-space text.
- [x] A blank title is rejected by the server independently, returning `400`.
- [x] The new row appears without a page reload.

**Tasks**
- TASK-05: Create the `CreateAssignmentRequest` DTO carrying only `title`
- TASK-06: Implement `createAssignment(title)` with the status rule in the service
- TASK-07: Implement `POST /api/assignments` with `@Valid`
- TASK-08: Build the Angular form and refresh the list on success

**Example**
> Typing "Science Lab" and pressing Add produces
> `{"id":3,"title":"Science Lab","status":"IN_PROGRESS"}` and a new table row.

### FEAT-03: Submit Assignment

#### US-03: Mark an Assignment as Submitted
> **Story Points**: 5 | **Sprint**: 1 | **Status**: Done

**As a** student,
**I want** to mark my work as handed in,
**so that** the list reflects what I have completed.

**Acceptance Criteria**
- [x] `PUT /api/assignments/{id}/submit` sets the status to `SUBMITTED`.
- [x] The row's button is disabled once the status is `SUBMITTED`.
- [x] Submitting twice is refused by the server independently, returning `409`.
- [x] Submitting an unknown id returns `404`.
- [x] Submission is one-way; there is no route back to `IN_PROGRESS`.

**Tasks**
- TASK-09: Implement `submitAssignment(id)` with the already-submitted rule
- TASK-10: Implement `PUT /api/assignments/{id}/submit`
- TASK-11: Wire the row button and disable it on `SUBMITTED`
- TASK-12: Reload the list after a successful submission

**Example**
> Clicking Submit on "Science Lab" flips its status to `SUBMITTED` and greys the
> button. Clicking again from a stale tab returns `409` with
> "Assignment 3 has already been submitted."

---

## EPIC-02: Reliability and Error Handling

> **Goal**: Make every failure state its cause accurately, so a user is never left
> guessing whether an action worked.

### FEAT-04: Accurate HTTP Error Contract

#### US-04: Correct Status Code for Every Failure
> **Story Points**: 5 | **Sprint**: 1 | **Status**: Done

**As a** client of the API,
**I want** each failure to carry the status code that matches the problem,
**so that** I can tell a mistake of mine from a fault of the server's.

**Acceptance Criteria**
- [x] Blank or missing title returns `400`.
- [x] A title over 200 characters returns `400`.
- [x] An unknown id returns `404`.
- [x] An already-submitted assignment returns `409`.
- [x] A concurrent modification returns `409`.
- [x] No rejected request returns `500`.

**Tasks**
- TASK-13: Create `GlobalExceptionHandler` as a `@RestControllerAdvice`
- TASK-14: Create `AssignmentNotFoundException` so 404 is distinguishable from 400
- TASK-15: Map validation, state, locking and integrity failures to their codes
- TASK-16: Verify all nine API checks

**Example**
> Before: every one of these returned `500` with an empty body.
> `PUT /api/assignments/999/submit` now returns `404` and
> "No assignment found with id = 999".

#### US-05: Readable Message with Every Failure
> **Story Points**: 3 | **Sprint**: 1 | **Status**: Done

**As a** client of the API,
**I want** a message describing what went wrong,
**so that** I can act on it rather than decode a number.

**Acceptance Criteria**
- [x] Every error body uses one shape: `timestamp`, `status`, `error`, `message`, `path`.
- [x] Validation failures name the field and the rule.
- [x] Database constraint failures return a safe message; driver text stays in the log.

**Tasks**
- TASK-17: Define the `ApiError` record
- TASK-18: Flatten bean-validation failures into one readable sentence
- TASK-19: Suppress driver detail on `DataIntegrityViolationException`

**Example**
> `POST` with `{"title":""}` returns
> `{"status":400,"message":"title: Title must not be blank", ...}`.

### FEAT-05: Failure Visibility in the Interface

#### US-06: See an Error Message When an Action Fails
> **Story Points**: 3 | **Sprint**: 1 | **Status**: Done

**As a** user,
**I want** a visible message when something fails,
**so that** a failed click does not look like a click that did nothing.

**Acceptance Criteria**
- [x] Every `subscribe()` supplies an error callback.
- [x] Failures appear in a banner that can be dismissed.
- [x] The banner prefers the server's message over a status number.

**Tasks**
- TASK-20: Add `errorMessage` state and `showError()` to `AppComponent`
- TASK-21: Add error callbacks to all three subscriptions
- TASK-22: Render the banner with a Dismiss control

**Example**
> A stale second tab clicking Submit sees: "Could not submit the assignment:
> Assignment 3 has already been submitted."

#### US-07: Be Told When the Backend Is Unreachable
> **Story Points**: 2 | **Sprint**: 1 | **Status**: Done

**As a** user,
**I want** to be told when the API cannot be reached,
**so that** I do not mistake a stopped server for an empty list.

**Acceptance Criteria**
- [x] `status === 0` is treated as a distinct case, not shown as a code.
- [x] The banner names the address and asks whether the backend is running.

**Tasks**
- TASK-23: Branch on `status === 0` inside `showError()`
- TASK-24: Verify with the backend stopped

**Example**
> With the backend down: "Could not load assignments: cannot reach the API at
> http://localhost:8080. Is the backend running?"

---

## EPIC-03: Data Integrity and Consistency

> **Goal**: Make the rules true of the data itself, not merely of the code path that
> happens to write it.

### FEAT-06: Storage-Level Constraints

#### US-08: Reject Invalid Data at the Database
> **Story Points**: 3 | **Sprint**: 1 | **Status**: Done

**As a** system,
**I want** constraints declared in the schema,
**so that** an invalid row is impossible regardless of which application writes it.

**Acceptance Criteria**
- [x] `title` is `NOT NULL` with `length = 200` in the table definition.
- [x] `@NotBlank` and `@Size` reject bad input before it reaches the database.
- [x] A 250-character title returns `400`.
- [x] `ddl-auto` is `create-drop`, so the schema always matches the entities.

**Tasks**
- TASK-25: Add `@Column(nullable = false, length = 200)` and `@Size` to the entity
- TASK-26: Add `@Size` to the create DTO
- TASK-27: Change `ddl-auto` from `update` to `create-drop`
- TASK-28: Inspect the generated DDL to confirm the constraints exist

**Example**
> The generated schema is `title varchar(200) not null`, so a null title cannot be
> stored even by a direct SQL insert.

#### US-09: Restrict Status to a Known Set of Values
> **Story Points**: 3 | **Sprint**: 1 | **Status**: Done

**As a** system,
**I want** status to be a typed value with a database constraint,
**so that** a meaningless status cannot be written or stored.

**Acceptance Criteria**
- [x] Status is an enum, not a free-text string.
- [x] The column is constrained to `IN_PROGRESS` and `SUBMITTED`.
- [x] Stored by name, not ordinal, so reordering constants cannot reinterpret rows.
- [x] The JSON contract is unchanged - clients still see the same strings.
- [x] The TypeScript type mirrors the Java enum.

**Tasks**
- TASK-29: Create the `AssignmentStatus` enum
- TASK-30: Annotate the field `@Enumerated(EnumType.STRING)` and constrain the column
- TASK-31: Update the seed data and service to use the enum
- TASK-32: Narrow the frontend type to a union and re-run `tsc --noEmit`

**Example**
> Before: `setStatus("banana")` compiled and persisted. Now it does not compile, and
> the column is declared `status enum ('IN_PROGRESS','SUBMITTED') not null`.

### FEAT-07: Concurrency Control

#### US-10: Accept Only One of Several Simultaneous Submissions
> **Story Points**: 8 | **Sprint**: 1 | **Status**: Done

**As a** system,
**I want** the "cannot submit twice" rule to hold when requests arrive together,
**so that** the rule is real rather than an appearance produced by single-user testing.

**Acceptance Criteria**
- [x] Each business operation runs in one transaction.
- [x] The entity carries a `@Version` column for optimistic locking.
- [x] A losing writer receives `409`, not a silent overwrite.
- [x] Of 12 simultaneous submissions, exactly one returns `200`.
- [x] Verified against both the pre-fix and post-fix builds with the same probe.

**Tasks**
- TASK-33: Add `@Transactional` to write methods, `readOnly = true` as the class default
- TASK-34: Add the `@Version` column, hidden from the API with `@JsonIgnore`
- TASK-35: Map `OptimisticLockingFailureException` to `409`
- TASK-36: Add a null guard on the id, resolving the null-safety warning
- TASK-37: Measure both builds with an identical concurrency probe

**Example**
> Pre-fix: violated in 5 of 5 rounds, once accepting 10 of 15 submissions.
> Post-fix: exactly 1 accepted in 5 of 5 rounds.

---

## EPIC-04: Developer Experience and Documentation

> **Goal**: Let someone clone the repository and run it without rediscovering the
> setup, and let them trust what they read.

### FEAT-08: Reproducible Build

#### US-11: Build the Backend Without Installing Maven
> **Story Points**: 2 | **Sprint**: 1 | **Status**: Done

**As a** developer,
**I want** the project to supply its own build tool,
**so that** a JDK is the only prerequisite.

**Acceptance Criteria**
- [x] `mvnw` and `mvnw.cmd` are committed.
- [x] A clean build succeeds with no Maven on the `PATH`.
- [x] The README states which JDK is required and that Maven is not.

**Tasks**
- TASK-38: Generate the Maven Wrapper
- TASK-39: Verify `clean package` with Maven removed from the `PATH`
- TASK-40: Document the TLS interception workaround for restricted networks

#### US-12: Start the Frontend with a Single Command
> **Story Points**: 3 | **Sprint**: 1 | **Status**: Done

**As a** developer,
**I want** a runnable Angular project in the repository,
**so that** starting the UI does not require scaffolding one first.

**Acceptance Criteria**
- [x] `frontend/tracker-ui` is a complete Angular project.
- [x] `npm start` serves it on port 4200.
- [x] `npm run build` produces a production bundle.
- [x] Build output and dependencies are excluded from version control.

**Tasks**
- TASK-41: Scaffold the project and move the source files into it
- TASK-42: Remove scaffold files that nothing references
- TASK-43: Add a root `.gitignore` covering `target/`, `node_modules/`, `dist/`
- TASK-44: Set `rootDir` explicitly in `tsconfig.json`

### FEAT-09: Accurate Project Documentation

#### US-13: Documentation Matching the Running System
> **Story Points**: 2 | **Sprint**: 1 | **Status**: Done

**As a** developer joining the project,
**I want** documentation that describes the system that exists,
**so that** I am not misled about what is built.

**Acceptance Criteria**
- [x] `docs/architecture/ARCHITECTURE.md` describes the Spring Boot and Angular system actually present.
- [x] `CLAUDE.md` describes the real stack, structure, commands and standards.
- [x] This document records only verifiable work.
- [x] Delivered work is separated from proposed work throughout.
- [x] `docs/project/PRD.md` marks each limitation as current or resolved.

**Tasks**
- TASK-45: Replace the inherited ASP.NET architecture document
- TASK-46: Replace the inherited JAMstack instructions file
- TASK-47: Reset this document against the commit history
- TASK-48: Record environment gotchas so they are not rediscovered

---

## EPIC-05: Persistence

> **Goal**: Stop losing everything on restart, so the rest of the system can mean
> something.

### FEAT-10: Durable Storage

#### US-14: Keep Assignments After a Restart
> **Story Points**: 5 | **Sprint**: 2 | **Status**: Done

**As a** user,
**I want** my assignments to still be there tomorrow,
**so that** the system is a record rather than a scratchpad.

**Acceptance Criteria**
- [x] The application database is SQL Server 2019 (`MSSQLSERVER01`), database
      `School Management System`.
- [x] Schema changes are Flyway migrations; `ddl-auto=validate` forbids Hibernate
      from altering the schema itself.
- [x] Data created before a restart is present after it.
- [x] Restarting does not re-run seed data or duplicate rows.
- [x] The `status` CHECK constraint survives the move - H2's native `ENUM` had no
      SQL Server equivalent and had to be written explicitly.

**Tasks**
- TASK-49: Enable TCP on the named instance and pin a static port (14333)
- TASK-50: Create the database and a scoped `tracker_app` login
- TASK-51: Add `mssql-jdbc` and Flyway; switch `ddl-auto` to `validate`
- TASK-52: Write V1 baseline with CHECK constraints for status and blank titles
- TASK-53: Verify data survives a JVM restart

**Example**
> Create an assignment, kill the JVM, start it again: the row is still there, the
> seed does not run a second time, and Flyway reports "up to date".

---

## EPIC-06: Accounts and Roles

> **Goal**: Give the system a notion of who is using it, so "my work" can exist.

### FEAT-11: Identity

#### US-15: Sign In and See Only My Own Assignments
> **Story Points**: 13 | **Sprint**: 2 | **Status**: Done

**As a** student,
**I want** to sign in and see the work set for me,
**so that** I am not looking at the whole school's list.

**Acceptance Criteria**
- [x] Passwords are stored as BCrypt hashes, never as typed text.
- [x] An anonymous request returns `401`, not a redirect to an HTML page.
- [x] A student's list contains only assignments they own - scoped by the QUERY,
      so other people's rows are never loaded or sent.
- [x] Asking for somebody else's assignment returns `404`, not `403`.
- [x] Writes require a CSRF token; the session rides in a cookie.
- [x] The password hash never appears in any API response.

**Tasks**
- TASK-54: Create the `AppUser` entity, `Role` enum and repository
- TASK-55: Add Spring Security with BCrypt and session authentication
- TASK-56: Add `assignment.owner_id` as a real FOREIGN KEY (migration V2)
- TASK-57: Scope the list query by owner for students
- TASK-58: Return 404 rather than 403 where existence itself is sensitive

**Example**
> Signing in as `student` shows three assignments, all owned by `student`.
> Signing in as `teacher` shows every assignment in the system.

#### US-16: Restrict Who May Create an Assignment
> **Story Points**: 3 | **Sprint**: 2 | **Status**: Done

**As a** school,
**I want** only teachers to set work,
**so that** students cannot invent their own assignments.

**Acceptance Criteria**
- [x] A student's create attempt returns `403`.
- [x] The rule lives in the service, so it holds for any caller, not just the UI.
- [x] A teacher may set work FOR a named account via `assignTo`.
- [x] Naming an account that does not exist returns `400`.

**Tasks**
- TASK-59: Enforce the TEACHER role in `createAssignment`
- TASK-60: Add `assignTo` so work can be set for a student
- TASK-61: Hide the create form from students in the UI as a courtesy

---

## EPIC-07: Assignment Lifecycle

> **Goal**: Let a mistake be corrected and a deadline be expressed.

### FEAT-12: Editing and Deadlines

#### US-17: Correct or Remove an Assignment
> **Story Points**: 5 | **Sprint**: 2 | **Status**: Done

**Acceptance Criteria**
- [x] `PUT /api/assignments/{id}` edits the title and due date.
- [x] `DELETE /api/assignments/{id}` removes it, returning `204`.
- [x] Editing and deleting are TEACHER-only, following the role rather than the row.
- [x] A SUBMITTED assignment cannot be deleted until it is reopened.

> **Design note.** This was first written as owner-only, which failed in two
> directions at once: a teacher who set work for a student could no longer correct
> it, and the student could rewrite the assignment they had been set.

#### US-18: Set a Due Date and See Overdue Work
> **Story Points**: 8 | **Sprint**: 2 | **Status**: Done

**Acceptance Criteria**
- [x] An assignment may carry a due date, or none - null is a legitimate state.
- [x] Past due and not submitted is shown as `OVERDUE`.
- [x] Submitted work is never overdue, however late it was.
- [x] Due today is not yet overdue.
- [x] The flag is DERIVED on read, not stored.

> **Why derived.** A stored flag is wrong the moment midnight passes and would need
> a scheduled job to stay honest. Computing it per read is always correct.

#### US-19: Undo an Accidental Submission
> **Story Points**: 3 | **Sprint**: 2 | **Status**: Done

**Acceptance Criteria**
- [x] `PUT /api/assignments/{id}/unsubmit` returns it to `IN_PROGRESS`.
- [x] TEACHER only - a student cannot retract their own submission.
- [x] Reopening something already `IN_PROGRESS` returns `409`.

> **Not the mirror of submit.** Anyone who can see an assignment may hand it in, but
> only a teacher may reopen one; otherwise "submitted" would mean nothing, since work
> could be retracted the moment it was marked late.

---

## EPIC-08: Automated Testing

> **Goal**: Make the guarantees hold without depending on somebody's memory.

### FEAT-13: Regression Suite

#### US-20: Run the Verification Suite Automatically
> **Story Points**: 8 | **Sprint**: 2 | **Status**: Done

**Acceptance Criteria**
- [x] `mvnw test` runs the suite; `mvnw package` fails if any test fails.
- [x] 36 tests: 17 unit tests of business rules, 12 full-stack tests through MockMvc,
      and 7 covering concurrency and database integrity.
- [x] Coverage includes every status code in the error contract, the role rules, the
      ownership scoping, the full lifecycle, and the overdue derivation.
- [x] A regression test asserts the password hash never appears in a response.
- [x] Tests run against H2 and need no SQL Server instance.

**Tasks**
- TASK-62: Add `spring-boot-starter-test` and `spring-security-test`
- TASK-63: Add a `test` profile using H2 with Flyway disabled
- TASK-64: Write unit tests for the rules, with the repository mocked
- TASK-65: Write MockMvc tests covering security, validation and the lifecycle

> **Honest limits.** The suite runs on H2, so the SQL Server migrations are not
> themselves exercised - `ddl-auto=validate` is what catches drift between the
> migrations and the entities. And browser behaviour still needs a browser: the CSRF
> defect passed every API test and only appeared in Chrome.

---

## Epics Delivered in Sprint 2

| Epic | Stories | Points | Depended on | PRD | Status |
|------|---------|:------:|-------------|-----|--------|
| EPIC-05: Persistence | US-14 | 5 | - | R1 | Done |
| EPIC-06: Accounts and Roles | US-15, US-16 | 16 | EPIC-05 | R3 | Done |
| EPIC-07: Assignment Lifecycle | US-17, US-18, US-19 | 16 | EPIC-05, EPIC-06 | R4, R5, R6 | Done |
| EPIC-08: Automated Testing | US-20 | 8 | - | NFR-9 | Done |

**The sequencing note held.** EPIC-05 was built first because it genuinely blocked the
rest: accounts, due dates and edit history are all meaningless while every restart
wipes the database. EPIC-08 ran alongside rather than last, and earned its place -
the test suite caught the seed-ordering fault that only appeared once Flyway was
disabled in the test profile.

## Sprint 3 - Translating the Inherited Hierarchy

**Dates:** 4 August 2026
**Sprint Goal:** *Turn the translated ASP.NET hierarchy into working software, without
importing the design decisions that made it a different application.*
**Delivered:** 29 story points, 9 stories. US-30 not started.

**Sprint Review.**

*Account self-service (EPIC-09).* The system had one honest, serious limitation: every
account used a shared password published in the README, and no one could change it.
A user may now replace their own password, and a teacher may issue an account whose
temporary password must be replaced before it can be used for anything at all.

*Presentation (EPIC-10, EPIC-12).* Sorting was added; the text filter, status filter
and summary counts were found already built and were given the stories they had been
missing. Sorting composes with the filters rather than replacing them - filter decides
which rows, sort decides their order.

*Documentation (EPIC-11).* springdoc-openapi serves an interactive description of all
ten paths at `/swagger-ui.html`, generated from the controllers rather than maintained
by hand.

*Verification (EPIC-13).* A GitHub Actions workflow runs the backend suite and the
frontend type-check and build on every push. The suite grew from 36 tests to 48.

**Two corrections the work forced.**

1. *A forced password change would have locked out the demo.* The story as written
   marked seeded accounts pending, which on the next start would have blocked
   `teacher` and `student` - and every existing test - from doing anything, in order
   to protect credentials already printed in the README. Migration V3 backfills
   existing rows to "not pending" and the flag is set only on accounts a teacher
   creates, where the risk is real: somebody else chose that password and knows it.
2. *The unit tests were stubbing a method the service no longer called.* Making the
   pending-account rule real meant `AssignmentService` moved from
   `AppUserService.currentUser()` to `currentActiveUser()`. Fourteen mock stubs in
   `AssignmentServiceTest` still named the old method, so Mockito returned null and
   eleven tests failed with no obvious connection to the change. Worth noting because
   the failures pointed at assignment logic that was entirely correct.

**Sprint Retrospective**

| | Notes |
|-|-------|
| Start | Checking the code before writing the story. Three stories in this sprint were already implemented, and the backlog did not know. |
| Stop | Assuming a security rule is free of consequences for the demo path. The forced-change flag was correct in principle and would have made the application unusable on first start. |
| Continue | Adding tests in the same change as the rule. The twelve new tests in `AccountSelfServiceTest` are what make "a pending account can do nothing else" a guarantee rather than a claim. |
| Continue | Running migrations against real SQL Server before calling them done. V3 was written with `GO` batches from the start because V2 had already taught that lesson. |

---

## Epic Detail: EPIC-09 to EPIC-13

Each story states what it was translated from, so the origin stays traceable.

---

### EPIC-09: Account Self-Service

> **Goal**: Let an account's password belong to the person using it, rather than to
> whoever wrote the seed data.
>
> **Translated from**: FEAT-01 Teacher Registration, FEAT-12 Student Account Setup.
> Open self-registration was **not** carried over: in a school, accounts are issued,
> not claimed, and anyone who could register themselves as a `TEACHER` would be able to
> set work for any student. Creation therefore stays an administrative act (US-23) and
> only the password becomes self-service.

#### FEAT-14: Password Management

##### US-21: Change My Own Password
> **Points**: 5 | **Status**: Done | **PRD**: R7

**As a** signed-in user,
**I want** to change my own password,
**so that** my account is not permanently held by a value someone else chose.

**Acceptance Criteria**
- [x] `PUT /api/auth/password` accepts the current password and a new one.
- [x] The current password must be supplied and must verify, even though the caller is
      already authenticated - a hijacked session must not be enough to seize the account.
- [x] A wrong current password returns `401`; a new password failing policy returns `400`.
- [x] The new password is stored as a BCrypt hash by the application's own encoder.
- [x] The hash never appears in any response, and the existing regression test that
      asserts this continues to pass.
- [x] `seedAccounts` must not silently reset a changed password on the next restart.

**Tasks**
- TASK-66: Add `ChangePasswordRequest` DTO with `@NotBlank` and `@Size` constraints
- TASK-67: Implement `changePassword` in `AppUserService`, re-verifying the current password
- TASK-68: Implement `PUT /api/auth/password` in `AuthController`
- TASK-69: Build the Angular change-password form, routed behind the signed-in guard

> **Watch the seed runner.** `TrackerApplication.seedAccounts` currently rewrites the
> password whenever the stored hash does not verify against `password123`. Once a user
> can choose their own, that logic would undo their change at the next restart. The
> guard has to become "only if the account is still using the seeded value", or the
> reset has to be limited to a development profile.

##### US-22: Be Forced to Replace a Temporary Password
> **Points**: 3 | **Status**: Done | **PRD**: L9

**As a** school,
**I want** a password somebody else chose to be unusable beyond first sign-in,
**so that** an account is only ever operated by the person it belongs to.

**Acceptance Criteria**
- [x] `AppUser` carries a `must_change_password` flag, set true on any account a
      teacher creates.
- [x] While the flag is set, every endpoint except sign-in, sign-out, `/api/auth/me`
      and the password change returns `403` with a message naming the reason.
- [x] Changing the password clears the flag, and the account works immediately after.
- [x] The flag is a real column with a `NOT NULL` constraint and a default, added by
      Flyway migration `V3`.

**Tasks**
- TASK-70: Add the column in migration `V3__must_change_password.sql` as its own `GO` batch
- TASK-71: Enforce the restriction in the service layer, so it holds for any caller
- TASK-72: Redirect the Angular app to the change-password form while the flag is set

> **The story originally said "seeded password", and building it showed why that was
> wrong.** Marking the two seeded development accounts pending would have locked
> `teacher`, `student` and the whole test suite out of the system on the very next
> start - to protect credentials that are printed in the README and are a known,
> accepted limitation (L9). V3 therefore backfills existing rows to *not* pending, and
> the flag is set only where the risk is real: an account whose password was chosen by
> somebody else, who still knows it. L9 remains open and is not what this story closed.

> **Note the exemption for `/api/auth/me`.** A pending account has to be able to ask
> who it is, or the frontend cannot discover that it is pending and would have nothing
> to show but an error. The rule lives in `AppUserService.currentActiveUser()`, which
> the assignment operations call; `currentUser()` stays unguarded precisely so identity
> and the password change itself remain reachable.

##### FEAT-15: Account Administration

##### US-23: Create an Account for a New Student
> **Points**: 5 | **Status**: Done | **PRD**: R7

**As a** teacher,
**I want** to create a student account,
**so that** work can be set for someone who has not been onboarded yet.

**Acceptance Criteria**
- [x] `POST /api/users` is `TEACHER`-only, enforced in the service.
- [x] A duplicate username returns `409`, and the database's `uq_app_user_username`
      constraint is what makes that true rather than a check-then-insert race.
- [x] The initial password is issued with `must_change_password` set.
- [x] A teacher cannot create another `TEACHER` - role escalation stays an explicit,
      out-of-band act.

**Tasks**
- TASK-73: Add `CreateUserRequest` DTO and `AppUserService.createStudent`
- TASK-74: Implement `POST /api/users` with the role guard
- TASK-75: Map the unique-constraint violation to `409` without leaking driver text
- TASK-76: Build the Angular create-account form, visible only to teachers

---

### EPIC-10: Finding Work in a Long List

> **Goal**: Keep the list usable once it holds more rows than fit on a screen.
>
> **Translated from**: EPIC-04 DataTables Integration. The capability was kept and the
> library was not. jQuery DataTables would add a second DOM-manipulation model beside
> Angular's, and the source's own task - "sort Performance by a hidden percentage
> column" - exists only to work around the plugin sorting rendered text. Angular sorts
> the typed array directly, so the workaround is unnecessary here.

#### FEAT-16: Sorting and Filtering

##### US-24: Sort the Assignment Table by Any Column
> **Points**: 3 | **Status**: Done

**As a** user,
**I want** to sort the table by any column,
**so that** I can bring the most urgent work to the top.

**Acceptance Criteria**
- [x] Title, owner, due date and status all sort, ascending and descending.
- [x] Sorting is client-side over the already-loaded array; it issues no new request.
- [x] Assignments with no due date sort predictably rather than landing arbitrarily.
- [x] `OVERDUE` sorts as a distinct value even though it is derived, not stored.
- [x] The active sort column and direction are visible, not merely applied.

**Tasks**
- TASK-77: Add sort state and a typed comparator per column to `AppComponent`
- TASK-78: Make headers clickable, with an accessible `aria-sort` attribute
- TASK-79: Decide and document the null-due-date ordering rule

##### US-25: Filter Assignments by Text
> **Points**: 2 | **Status**: Done

**As a** user,
**I want** to filter the list by typing,
**so that** I can find one assignment without reading the whole table.

**Acceptance Criteria**
- [x] One input filters on title and owner, case-insensitively, as the user types.
- [x] Filtering is client-side and does not re-query the API.
- [x] A filter matching nothing shows an explicit "No assignments match" message,
      distinct from the existing "No assignments yet."
- [x] Clearing the input restores the full list.

**Tasks**
- TASK-80: Add the filter input and a derived filtered list
- TASK-81: Distinguish "empty because filtered" from "empty because none exist"

##### US-26: Filter Assignments by Status
> **Points**: 2 | **Status**: Done

**As a** teacher,
**I want** to show only outstanding or only overdue work,
**so that** I can see what still needs chasing.

**Acceptance Criteria**
- [x] Filters for All, `IN_PROGRESS`, `SUBMITTED` and `OVERDUE`.
- [x] `OVERDUE` filters on the derived flag, not on the stored status.
- [x] The status filter and the text filter compose rather than overriding each other.

**Tasks**
- TASK-82: Add the status filter control and fold it into the derived list
- TASK-83: Verify the two filters compose correctly in both orders

---

### EPIC-11: API Documentation

> **Goal**: Let the API be explored without reading the controllers.
>
> **Translated from**: EPIC-05 API and Documentation. Swagger registration in
> `Program.cs` becomes the `springdoc-openapi` starter; XML doc comments become
> annotations. The current position - "None. The contract is documented in PRD.md
> section 5" - is a deliberate choice, and this epic revisits it rather than
> contradicting it.

#### FEAT-17: OpenAPI Description

##### US-27: Browse and Try the Endpoints in a UI
> **Points**: 3 | **Status**: Done

**As a** developer or tester,
**I want** an interactive description of the API,
**so that** I can see the contract and exercise it without writing a client.

**Acceptance Criteria**
- [x] All ten endpoints appear with their request and response shapes.
- [x] The documented error bodies match the real `ApiError` shape.
- [x] The UI is reachable only in development; it is not exposed by the production profile.
- [x] Its routes do not weaken the security rules - it must not become an unauthenticated
      hole in a filter chain where every other path requires a session.
- [x] CSRF still applies to any write issued from the UI, or the page states why it cannot.

**Tasks**
- TASK-84: Add the `springdoc-openapi-starter-webmvc-ui` dependency
- TASK-85: Annotate the controllers and the `ApiError` record
- TASK-86: Restrict the UI to a development profile and re-run the security tests

> **The security tests are the acceptance gate here.** Adding a documentation UI means
> adding permitted paths to `SecurityConfig`, which is exactly the kind of change that
> can quietly widen access. `AssignmentApiTest` already asserts that an anonymous
> request receives `401`; that must still pass.

---

### EPIC-12: Student Overview

> **Goal**: Answer "how am I doing?" without counting rows by eye.
>
> **Translated from**: FEAT-14 Student Dashboard. The source's dashboard reported
> total, average and percentage **scores**. This system holds no marks, and the scope
> guard excludes them, so the dashboard was re-aimed at the data that does exist:
> counts of outstanding, submitted and overdue work.

#### FEAT-18: Personal Summary

##### US-28: See What Is Outstanding, Submitted and Overdue
> **Points**: 3 | **Status**: Done

**As a** student,
**I want** a summary of my work at the top of the page,
**so that** I can see what is left without scanning the table.

**Acceptance Criteria**
- [x] Shows counts of outstanding, submitted and overdue assignments.
- [x] Counts are derived from the list already fetched - no second endpoint, no second
      source of truth that could disagree with the table beneath it.
- [x] A teacher sees the same summary scoped to everything they can see.
- [x] Overdue is counted from the same derived flag the table uses.

**Tasks**
- TASK-87: Derive the counts in `AppComponent` from the existing array
- TASK-88: Render the summary above the table
- TASK-89: Verify the counts against the table for both roles

> **Deliberately not a new endpoint.** A `GET /api/assignments/summary` would compute
> the same numbers a second time, and any divergence between it and the list would be a
> defect that only appears with certain data. Deriving from the array already on the
> page cannot drift.

---

### EPIC-13: Continuous Verification

> **Goal**: Close the two gaps the current suite honestly admits to.
>
> **Translated from**: nothing in the ASP.NET hierarchy - it had no CI story. These are
> PRD R9 and R10, promoted here because the source's Definition of Done assumed a
> pipeline that does not exist.

#### FEAT-19: Pipeline and Migration Coverage

##### US-29: Run the Suite on Every Push
> **Points**: 3 | **Status**: Done | **PRD**: R9

**Acceptance Criteria**
- [x] A GitHub Actions workflow runs `mvnw test` on push and pull request.
- [x] The workflow provisions JDK 25 itself rather than assuming a machine.
- [x] A failing test fails the check visibly on the pull request.
- [x] `npm run build` and `tsc --noEmit` run for the frontend.

**Tasks**
- TASK-90: Add `.github/workflows/build.yml` pinning JDK 25
- TASK-91: Cache the Maven and npm dependencies
- TASK-92: Verify the workflow fails when a test is deliberately broken

##### US-30: Exercise the SQL Server Migrations
> **Estimate**: 8 | **Status**: Proposed | **PRD**: R10

**Acceptance Criteria**
- [ ] The migrations run against real SQL Server, not H2, in at least one test.
- [ ] The `GO`-batch structure of `V2` is exercised as written.
- [ ] The constraints are proved by attempting bad writes and expecting refusal -
      orphan `owner_id`, duplicate username, invalid status, over-long title.
- [ ] The suite still runs with no SQL Server present, skipping these rather than failing.

**Tasks**
- TASK-93: Add Testcontainers with the SQL Server image
- TASK-94: Add a migration test profile with Flyway enabled and `ddl-auto=validate`
- TASK-95: Port the `sqlcmd` constraint probes into assertions that expect rejection

> **This is the larger of the two and the more valuable.** `ddl-auto=validate` currently
> catches drift between migrations and entities, but nothing at all exercises the
> migration SQL itself - a `GO`-batch mistake would reach a real database undetected.

---

## Appendix A: Disposition of the ASP.NET Hierarchy

Every epic from the source map, and where it went. Nothing was dropped silently.

| Source | Disposition | Where |
|---|---|---|
| **EPIC-01** Security - Teacher Login | **Already delivered.** Session cookie plus CSRF, BCrypt, `401` on failure. | US-15 |
| **EPIC-01** Security - Input Validation | **Already delivered.** Jakarta Bean Validation at the edge plus a service guard plus database constraints - three layers, not one. | US-08, US-09 |
| **EPIC-01** Security - Teacher Registration | **Translated, narrowed.** Self-registration excluded; password self-service and teacher-issued accounts kept. | EPIC-09 |
| **EPIC-02** Student Management (CRUD on a `Student` entity) | **Excluded.** A student here is an `AppUser` with role `STUDENT`, not a separate record with grade, student number and contact details. Account creation covers the real need. | US-23 |
| **EPIC-03** Assessment and Scoring | **Excluded.** The scope guard states there are no grades, marks or assessments. This is the single largest exclusion: US-11, US-12, US-13, US-21 and US-22 of the source all rest on stored marks. | - |
| **EPIC-04** DataTables Integration | **Translated.** Capability kept, jQuery plugin replaced with Angular-native sorting and filtering. | EPIC-10 |
| **EPIC-05** API and Documentation | **Translated.** Swagger in `Program.cs` becomes springdoc-openapi. | EPIC-11 |
| **EPIC-06** Student Portal - login | **Already delivered.** A student signs in and sees only their own work, scoped by the query. | US-15 |
| **EPIC-06** Student Portal - dashboard | **Translated, re-aimed.** Score summary becomes a work-status summary, since no marks exist. | EPIC-12 |
| **EPIC-07** Admin Portal and audit logs | **Excluded.** There are two roles and no audit log; the scope guard names both. An `ADMIN` role remains listed as candidate work, but the tabbed dashboard and `AuditLogService` are not proposed. | - |

### Specific instructions not carried over

| Source instruction | Why not |
|---|---|
| `400 Bad Request` for invalid credentials (TASK-12, TASK-82) | This system returns `401`, with an identical message for a wrong username and a wrong password, so the API cannot be used to enumerate accounts. A deliberate decision, documented in PRD.md section 4. |
| Store the session token in `localStorage` (TASK-04, TASK-11, TASK-81) | Authentication is a `HttpOnly` session cookie with a CSRF token. A token in `localStorage` is readable by any script on the page. |
| EF Core migrations (TASK-33, TASK-48) | Entity Framework is .NET. Flyway fills the role here. |
| Register services in `Program.cs` (TASK-17, TASK-68) | ASP.NET Core's entry point. The equivalent is `TrackerApplication` plus `@Configuration` classes. |
| XML doc comments on controllers (TASK-69) | A .NET documentation convention; springdoc uses annotations. |
| `DELETE /api/students/{id}` with cascade delete (TASK-40) | `assignment.owner_id` is deliberately `ON DELETE NO_ACTION`. Destroying somebody's work as a side effect of removing their account must be an explicit decision in the service, never a schema behaviour. |
| Separate `Grade` entity seeded 7-12 (TASK-33) | There is no grouping above an assignment. Listed as candidate work, not proposed. |

### Still candidate work, not proposed above

| Item | Why it is not an epic yet |
|------|-----|
| An `ADMIN` role | Nothing currently needs oversight across accounts; two roles cover the delivered behaviour |
| Frontend component tests | Karma and Jasmine are installed but no specs exist |
| Classes, terms and subjects | The schema has no grouping above an assignment, and adding one is a data-model change, not a feature |

---

## Summary Table

| ID | Level | Title | Parent | Points | Status |
|----|-------|-------|--------|:------:|:------:|
| EPIC-01 | Epic | Assignment Tracking | Application | 13 | Done |
| FEAT-01 | Feature | View Assignments | EPIC-01 | 3 | Done |
| US-01 | User Story | View all assignments and their status | FEAT-01 | 3 | Done |
| FEAT-02 | Feature | Create Assignment | EPIC-01 | 5 | Done |
| US-02 | User Story | Add a new assignment by title | FEAT-02 | 5 | Done |
| FEAT-03 | Feature | Submit Assignment | EPIC-01 | 5 | Done |
| US-03 | User Story | Mark an assignment as submitted | FEAT-03 | 5 | Done |
| EPIC-02 | Epic | Reliability and Error Handling | Application | 13 | Done |
| FEAT-04 | Feature | Accurate HTTP Error Contract | EPIC-02 | 8 | Done |
| US-04 | User Story | Correct status code for every failure | FEAT-04 | 5 | Done |
| US-05 | User Story | Readable message with every failure | FEAT-04 | 3 | Done |
| FEAT-05 | Feature | Failure Visibility in the Interface | EPIC-02 | 5 | Done |
| US-06 | User Story | See an error message when an action fails | FEAT-05 | 3 | Done |
| US-07 | User Story | Be told when the backend is unreachable | FEAT-05 | 2 | Done |
| EPIC-03 | Epic | Data Integrity and Consistency | Application | 14 | Done |
| FEAT-06 | Feature | Storage-Level Constraints | EPIC-03 | 6 | Done |
| US-08 | User Story | Reject invalid data at the database | FEAT-06 | 3 | Done |
| US-09 | User Story | Restrict status to a known set of values | FEAT-06 | 3 | Done |
| FEAT-07 | Feature | Concurrency Control | EPIC-03 | 8 | Done |
| US-10 | User Story | Accept only one simultaneous submission | FEAT-07 | 8 | Done |
| EPIC-04 | Epic | Developer Experience and Documentation | Application | 7 | Done |
| FEAT-08 | Feature | Reproducible Build | EPIC-04 | 5 | Done |
| US-11 | User Story | Build the backend without installing Maven | FEAT-08 | 2 | Done |
| US-12 | User Story | Start the frontend with a single command | FEAT-08 | 3 | Done |
| FEAT-09 | Feature | Accurate Project Documentation | EPIC-04 | 2 | Done |
| US-13 | User Story | Documentation matching the running system | FEAT-09 | 2 | Done |
| EPIC-05 | Epic | Persistence | Application | 5 | Done |
| FEAT-10 | Feature | Durable Storage | EPIC-05 | 5 | Done |
| US-14 | User Story | Keep assignments after a restart | FEAT-10 | 5 | Done |
| EPIC-06 | Epic | Accounts and Roles | Application | 16 | Done |
| FEAT-11 | Feature | Identity | EPIC-06 | 16 | Done |
| US-15 | User Story | Sign in and see only my own assignments | FEAT-11 | 13 | Done |
| US-16 | User Story | Restrict who may create an assignment | FEAT-11 | 3 | Done |
| EPIC-07 | Epic | Assignment Lifecycle | Application | 16 | Done |
| FEAT-12 | Feature | Editing and Deadlines | EPIC-07 | 16 | Done |
| US-17 | User Story | Correct or remove an assignment | FEAT-12 | 5 | Done |
| US-18 | User Story | Set a due date and see overdue work | FEAT-12 | 8 | Done |
| US-19 | User Story | Undo an accidental submission | FEAT-12 | 3 | Done |
| EPIC-08 | Epic | Automated Testing | Application | 8 | Done |
| FEAT-13 | Feature | Regression Suite | EPIC-08 | 8 | Done |
| US-20 | User Story | Run the verification suite automatically | FEAT-13 | 8 | Done |
| **Subtotal** | | **8 Epics, 13 Features, 20 User Stories, 65 Tasks** | | **92 pts** | **92 delivered** |
| EPIC-09 | Epic | Account Self-Service | Application | 13 | Done |
| FEAT-14 | Feature | Password Management | EPIC-09 | 8 | Done |
| US-21 | User Story | Change my own password | FEAT-14 | 5 | Done |
| US-22 | User Story | Be forced to replace a seeded password | FEAT-14 | 3 | Done |
| FEAT-15 | Feature | Account Administration | EPIC-09 | 5 | Done |
| US-23 | User Story | Create an account for a new student | FEAT-15 | 5 | Done |
| EPIC-10 | Epic | Finding Work in a Long List | Application | 7 | Done |
| FEAT-16 | Feature | Sorting and Filtering | EPIC-10 | 7 | Done |
| US-24 | User Story | Sort the assignment table by any column | FEAT-16 | 3 | Done |
| US-25 | User Story | Filter assignments by text | FEAT-16 | 2 | Done |
| US-26 | User Story | Filter assignments by status | FEAT-16 | 2 | Done |
| EPIC-11 | Epic | API Documentation | Application | 3 | Done |
| FEAT-17 | Feature | OpenAPI Description | EPIC-11 | 3 | Done |
| US-27 | User Story | Browse and try the endpoints in a UI | FEAT-17 | 3 | Done |
| EPIC-12 | Epic | Student Overview | Application | 3 | Done |
| FEAT-18 | Feature | Personal Summary | EPIC-12 | 3 | Done |
| US-28 | User Story | See what is outstanding, submitted and overdue | FEAT-18 | 3 | Done |
| EPIC-13 | Epic | Continuous Verification | Application | 11 | Part done |
| FEAT-19 | Feature | Pipeline and Migration Coverage | EPIC-13 | 11 | Part done |
| US-29 | User Story | Run the suite on every push | FEAT-19 | 3 | Done |
| US-30 | User Story | Exercise the SQL Server migrations | FEAT-19 | 8 | Not started |
| **Subtotal** | | **5 Epics, 6 Features, 10 User Stories, 30 Tasks** | | **37 pts** | **29 delivered** |
| **Totals** | | **13 Epics, 19 Features, 30 User Stories, 95 Tasks** | | **129 pts** | **121 delivered, US-30 outstanding** |
