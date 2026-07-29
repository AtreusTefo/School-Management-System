# Product Requirements Document — School Assignment Tracker

| | |
|---|---|
| **Product** | School Management System — Assignment Tracker |
| **Document version** | 0.1 |
| **Status** | Draft |
| **Last updated** | 27 July 2026 |
| **Owner** | Atreus Ramokate |

> **Read this first.** The repository is named *School Management System*, but what
> exists today is one slice of that idea: assignment tracking. This document
> describes that slice as it is actually built and verified, then sets out what a
> fuller system would need. Sections marked **Built** reflect working, tested
> behaviour. Sections marked **Proposed** are candidates, not commitments — they
> need review before anyone treats them as agreed scope.

---

## 1. Purpose

Teachers and students currently track assignment progress informally — verbally, on
paper, or in a spreadsheet. There is no single place that answers *"what has been
handed in?"* without someone asking around.

The Assignment Tracker provides one shared list of assignments and their submission
state, so that question has a single, current answer.

A second purpose is educational. The codebase is deliberately small and heavily
commented, and is intended to demonstrate a **layered architecture** — where each
layer talks only to the one beneath it — clearly enough to be read and learned from:

```
Angular  ──HTTP──►  Controller ──► Service ──► Repository ──► Database
(browser)          (web layer)   (rules)     (data access)   (storage)
```

Both purposes are real, and they constrain each other. Where a production-grade
choice would obscure the teaching goal, this release favours clarity — and Section 8
records the cost of doing so.

## 2. Goals and non-goals

### Goals

| | |
|---|---|
| **G1** | Show every assignment and its current status in one place |
| **G2** | Let a new assignment be added in a few seconds, without training |
| **G3** | Let an assignment be marked as submitted in a single action |
| **G4** | Make failures legible — the user should always know what went wrong and why |
| **G5** | Remain small and readable enough to serve as a teaching example |

### Non-goals for this release

- Grading, marks, or feedback on submitted work
- File upload or attachment of the actual assignment
- Notifications, reminders, or deadline tracking
- Multiple schools, classes, or terms
- Mobile applications

## 3. Users

The system now has real accounts. Signing in is required; an anonymous request
receives `401`.

| Role | May do | Supported |
|---|---|---|
| **Teacher** | Create work and set it for a student; see every assignment; edit, delete and reopen any of them | Yes |
| **Student** | See only the work set for them; submit it | Yes |
| **Administrator** | Oversight across classes and terms | No — see R8 |

> **The open question is resolved.** Students cannot create assignments; a teacher
> sets work, optionally *for* a named student. Editing and deleting follow the role
> rather than ownership, so a teacher can correct work they set for someone else and
> a student cannot rewrite the assignment they were given.

Development accounts are `teacher` and `student`, both with password `password123`,
reset at every startup. That is acceptable locally and nowhere else (L9).

## 4. Functional requirements — **Built**

Each requirement below is implemented and verified. See Section 9 for how.

### FR-0 · Sign in

A person identifies themselves before doing anything else.

- Passwords are stored as **BCrypt hashes**, never as typed text, and the hash never
  appears in any response.
- A wrong username and a wrong password produce the *same* `401` and the same message.
  Distinguishing them would let an attacker enumerate valid usernames.
- The session rides in a cookie; state-changing requests additionally require a CSRF
  token.

### FR-1 · View my assignments

The system displays a table of **title**, **owner**, **due date**, **status** and
action controls. The list loads automatically once signed in.

- A **teacher** sees every assignment; a **student** sees only their own.
- The scoping happens in the **query**, so another person's rows are never loaded,
  never serialised and never sent. Filtering in the interface would not be access
  control.
- An empty list shows *"No assignments yet."* rather than a blank table.

### FR-2 · Create an assignment

A teacher submits a title, an optional due date, and optionally the account the work
is **for**. The assignment appears without a page reload.

- **Teachers only.** A student's attempt returns `403`, enforced in the service so the
  rule holds for any caller and not merely for the UI.
- The client supplies only title, due date and target account. Id, status and version
  are assigned by the server.
- Every new assignment starts as `IN_PROGRESS`.
- Surrounding whitespace is trimmed from the title.
- A blank or whitespace-only title is rejected. The **Add** button stays disabled
  until the field holds non-space text, and the server rejects it independently —
  neither guard is relied on alone.
- Naming an account that does not exist returns `400`.

### FR-3 · Submit an assignment

A user marks an assignment as handed in. Its status becomes `SUBMITTED`.

- An assignment that is already `SUBMITTED` cannot be submitted again. Its button is
  disabled in the interface, and the server rejects the attempt independently.
- Of *N* simultaneous submissions of one assignment, exactly one succeeds; the rest
  receive `409`.

### FR-5 · Edit, delete and reopen

- **Edit** changes the title and due date. Teachers only.
- **Delete** removes the assignment, returning `204`. Teachers only, and refused with
  `409` while the assignment is `SUBMITTED` — it must be reopened first, so destroying
  a record of handed-in work takes two deliberate acts.
- **Reopen** returns a submitted assignment to `IN_PROGRESS`. Teachers only:
  if students could retract their own work, "submitted" would mean nothing.

### FR-6 · Due dates and overdue work

- An assignment may carry a due date, or none — no deadline is a legitimate state.
- Work that is past its due date and not yet submitted is shown as **OVERDUE**.
- Submitted work is never overdue, however late it was. Work due today is not yet
  overdue.
- The flag is **derived on every read**, not stored. A stored flag would be wrong the
  moment midnight passed.

### FR-4 · Report failures honestly

Every failure returns the HTTP status that matches the problem, plus a message the
interface can show the user directly.

| Situation | Status | Message |
|---|---|---|
| Not signed in | `401` | — |
| Wrong username or password | `401` | `Invalid username or password.` |
| Missing or invalid CSRF token | `403` | — |
| Blank or missing title | `400` | `title: Title must not be blank` |
| Naming an account that does not exist | `400` | `No account named 'nobody'.` |
| Signed in but not permitted | `403` | `Only a teacher can create an assignment.` |
| Unknown assignment id | `404` | `No assignment found with id = 999` |
| Someone else's assignment | `404` | *deliberately indistinguishable from "unknown"* |
| Already submitted | `409` | `Assignment 3 has already been submitted.` |
| Deleting submitted work | `409` | `Assignment 3 has been submitted and cannot be deleted.` |
| A concurrent modification | `409` | `This assignment was changed by someone else.` |

> **Why someone else's assignment is `404` and not `403`.** Answering "forbidden"
> would confirm that the id exists and belongs to somebody, letting an outsider map
> the data by probing ids. "Not found" is true from that caller's perspective and
> reveals nothing. Where the caller can already legitimately see the row, `403` is
> used instead — hiding it would be pointless, and the honest answer is more useful.

- Every error body uses one consistent shape, so the client parses a single format:

  ```json
  { "timestamp": "...", "status": 404, "error": "Not Found",
    "message": "No assignment found with id = 999",
    "path": "/api/assignments/999/submit" }
  ```

- `500` is reserved for genuine server faults. A rejected request is never a `500`.
- The interface shows the failure in a dismissible banner, preferring the server's
  message over a status number.
- If the API cannot be reached at all, the banner says so explicitly rather than
  failing silently.

## 5. Interface contract — **Built**

Base URL `http://localhost:8080`. Requests and responses are JSON.

| Method | Path | Body | Success | Failure |
|---|---|---|---|---|
| `GET` | `/api/auth/csrf` | — | `200`, sets `XSRF-TOKEN` cookie | — |
| `POST` | `/api/auth/login` | `{"username":"...","password":"..."}` | `200` + user | `400`, `401` |
| `GET` | `/api/auth/me` | — | `200` + user | `401` |
| `POST` | `/api/auth/logout` | — | `200` | — |
| `GET` | `/api/assignments` | — | `200` + array | `401` |
| `POST` | `/api/assignments` | `{"title":"...","dueDate":"2026-12-31","assignTo":"student"}` | `200` + created | `400`, `401`, `403` |
| `PUT` | `/api/assignments/{id}` | `{"title":"...","dueDate":"..."}` | `200` + updated | `400`, `401`, `403`, `404` |
| `DELETE` | `/api/assignments/{id}` | — | `204` | `401`, `403`, `404`, `409` |
| `PUT` | `/api/assignments/{id}/submit` | — | `200` + updated | `400`, `401`, `404`, `409` |
| `PUT` | `/api/assignments/{id}/unsubmit` | — | `200` + updated | `401`, `403`, `404`, `409` |

Every request except `/api/auth/login` and `/api/auth/csrf` requires a session.
Every state-changing request additionally requires the `X-XSRF-TOKEN` header.

An assignment is represented as:

```json
{ "id": 1, "title": "Math Homework 1", "status": "IN_PROGRESS",
  "ownerUsername": "student", "dueDate": "2026-12-31", "overdue": false }
```

`version` exists on the row for optimistic locking but is deliberately not exposed.

An assignment is represented as:

```json
{ "id": 1, "title": "Math Homework 1", "status": "IN_PROGRESS" }
```

Cross-origin requests are permitted from an explicit allow-list, set by
`app.cors.allowed-origins` and defaulting to `http://localhost:4200` and
`http://127.0.0.1:4200`. The base URL above is the development default; the frontend
reads its own value from `environment.apiBaseUrl`, so neither half has the other's
address compiled in.

## 6. Non-functional requirements

### **Built**

| | |
|---|---|
| **NFR-1** | Each layer depends only on the layer directly beneath it. The controller holds no business rules; the service holds no HTTP or SQL. |
| **NFR-2** | Validation is enforced server-side regardless of what the client does. Client-side guards are a convenience, never the only defence. |
| **NFR-2a** | Integrity rules hold at the storage layer, not only in application code. `title` is `NOT NULL` with a length bound; `status` is constrained to the two valid values. A writer that bypasses this application still cannot create an invalid row. |
| **NFR-2b** | Each business operation is one transaction, and concurrent edits to the same assignment are detected rather than silently merged. Exactly one of N simultaneous submissions succeeds; the rest receive `409`. |
| **NFR-3** | Startup is repeatable. Sample data is seeded only when the table is empty, so restarting cannot duplicate rows. |
| **NFR-4** | Building requires only a JDK 25 (current LTS). The Maven Wrapper supplies its own Maven. |
| **NFR-5** | The code is written to be read: layer responsibilities are commented, and comments explain *why* a choice was made, not just what the line does. |
| **NFR-5a** | Neither half compiles the other's address in. The backend takes its permitted origins from configuration; the frontend takes its API address from a build-time environment file. The same backend jar runs in any environment, and a production bundle contains no `localhost` reference. |

### **Proposed**

| | |
|---|---|
| **NFR-6** | List and write operations respond within 300 ms for up to 1,000 assignments |
| ~~**NFR-7**~~ | ~~Data survives a restart~~ — **Delivered.** SQL Server 2019 with Flyway migrations. |
| **NFR-8** | The interface meets WCAG 2.1 AA — keyboard operable, sufficient contrast, status conveyed by more than colour |
| ~~**NFR-9**~~ | ~~Automated tests cover every requirement in Section 4~~ — **Delivered**, 29 tests in the build. Running them in CI on each push is still outstanding. |

## 7. Formerly proposed scope — **now delivered**

Every item in this section has been built. They are kept here, struck through, so the
record shows what was proposed and when it became real rather than quietly vanishing.

**~~R1 · Persistent storage.~~ Delivered.** The database is SQL Server 2019
(`MSSQLSERVER01`, database `School Management System`). Schema changes go through
Flyway migrations with `ddl-auto=validate`, so Hibernate may check the schema but
never alter it.

**~~R2 · Typed status.~~ Delivered** in Sprint 1. `AssignmentStatus` is an enum, and
the column carries a `CHECK` constraint restricting it to the two valid values.

**~~R3 · Accounts and roles.~~ Delivered.** `TEACHER` and `STUDENT`, session
authentication, BCrypt hashing and CSRF protection. The open question about who may
create assignments is resolved: teachers only, and a teacher may set work *for* a
student.

**~~R4 · Due dates.~~ Delivered.** An optional due date per assignment, with `OVERDUE`
derived on read rather than stored — a stored flag would be wrong the moment midnight
passed.

**~~R5 · Edit and delete.~~ Delivered.** Teacher-only. Deletion is hard, not soft, but
a submitted assignment must be reopened first, so removing a record of handed-in work
takes two deliberate acts.

**~~R6 · Un-submit.~~ Delivered.** Teacher-only, deliberately not the mirror of
submit: if students could retract their own work, "submitted" would mean nothing.

### Still open

| | Item |
|---|---|
| **R7** | Password change and account self-service — credentials are fixed at seed time |
| **R8** | Classes, terms and subjects — there is still no grouping above an assignment |
| **R9** | Run the test suite in CI on every push (the remaining half of NFR-9) |
| **R10** | Tests that exercise the SQL Server migrations, not just the entities |

## 8. Known limitations

Accepted for this release, recorded so they are chosen rather than discovered.

| | Limitation | Consequence |
|---|---|---|
| ~~**L1**~~ | ~~Data is in-memory~~ | **Resolved.** The database is SQL Server 2019 (`MSSQLSERVER01`, database `School Management System`), with Flyway migrations and `ddl-auto=validate`. Verified by restarting the JVM and finding the data intact. |
| ~~**L2**~~ | ~~No authentication~~ | **Resolved.** Session authentication with BCrypt hashing, CSRF protection, and two roles. An anonymous request receives `401`. |
| ~~**L3**~~ | ~~Status is an untyped string~~ | **Resolved.** Status is now a typed enum with a database-level constraint on the column. |
| ~~**L4**~~ | ~~No automated tests~~ | **Resolved.** 29 tests run as part of the build - 17 unit tests of the business rules, 12 full-stack tests through MockMvc with real security. |
| ~~**L5**~~ | ~~Localhost-only CORS~~ | **Resolved.** Permitted origins come from `app.cors.allowed-origins`, and the frontend's API address from its environment file, so either half can be pointed elsewhere without a code change. |
| **L6** | The list does not refresh on its own | Two people working at once can act on stale data. Handled safely — the server returns `409` and the interface explains it — but it is a recovery, not a prevention. |
| ~~**L7**~~ | ~~Single shared list~~ | **Partly resolved.** The list is now scoped per account: a student sees only their own work. Classes, terms and subjects still do not exist. |
| ~~**L8**~~ | ~~No relationships between tables~~ | **Resolved.** `assignment.owner_id` is a real foreign key to `app_user.id`, with no `ON DELETE CASCADE`, so the database refuses to delete an account that still owns assignments. |
| **L9** | Development credentials are seeded | `teacher` and `student` both use `password123`, reset at every startup. Fine locally, unacceptable anywhere else. |
| **L10** | Tests run against H2, not SQL Server | The Flyway migrations themselves are not covered by the suite. `ddl-auto=validate` is what catches drift between migrations and entities. |

## 9. Acceptance criteria

This release is accepted when all of the following hold. Every item below was
confirmed against a running instance on 27 July 2026.

**Functional**
- [x] The list loads on open and shows title and status per assignment
- [x] A valid title creates an assignment that appears without a reload
- [x] Submitting changes the status to `SUBMITTED` and the list reflects it
- [x] A blank title is rejected by both the interface and the server
- [x] An already-submitted assignment cannot be submitted again

**Error handling** — each returns the correct status *and* a usable message
- [x] `400` blank or missing title
- [x] `404` unknown id
- [x] `409` already submitted
- [x] No rejected request returns `500`

**Interface**
- [x] Failures appear in a visible, dismissible banner
- [x] An unreachable backend is named as such, not shown as a silent no-op
- [x] No console errors during normal use

**Integrity and consistency**
- [x] `title` is `NOT NULL` and length-bounded in the table definition
- [x] `status` is constrained at the column to `IN_PROGRESS` or `SUBMITTED`
- [x] A title over 200 characters is rejected with `400`
- [x] Of 15 simultaneous submissions of one assignment, exactly one succeeds

**Verification method.** 29 automated tests run as part of `mvnw package` — 17 unit
tests of the business rules and 12 full-stack tests through MockMvc with real
security. Alongside them, 34 scripted API checks against the running SQL Server
build, direct writes through `sqlcmd` to confirm the database rejects invalid data
without the application's help, and browser scenarios driven as both roles.

> **This section is now a standing guarantee, not a record of a past run.** The build
> fails if any test fails, so these claims cannot quietly stop being true. Two honest
> limits remain: the suite runs against H2, so the SQL Server migrations are not
> themselves covered (`ddl-auto=validate` catches drift instead), and browser
> behaviour still needs a browser — the CSRF defect passed every API test.

## 10. Assumptions

These shaped the requirements above. Several have since been settled by building the
work; the rest remain **unconfirmed**, and if any is wrong the affected section needs
revisiting.

| | Assumption | Affects | Status |
|---|---|---|---|
| ~~**A1**~~ | ~~A shared list without per-user views is acceptable for now~~ | FR-1, R3 | Superseded — the list is scoped per account |
| **A2** | Submission is a self-declaration; no proof of work is uploaded | FR-3, non-goals | Still holds |
| ~~**A3**~~ | ~~In-memory storage is tolerable during development~~ | L1, R1 | Superseded — the database is persistent |
| ~~**A4**~~ | ~~Two statuses are sufficient until due dates arrive~~ | FR-3, R4 | Still two statuses; `OVERDUE` is derived, not a third state |
| **A5** | Deployment beyond a local machine is out of scope for this release | L5 | Still holds |
| **A6** | A teacher setting work *for* a student is the intended model, rather than students creating their own | FR-2, R3 | **Unconfirmed** — this was a design decision made during Sprint 2 and is worth checking |

---

## Appendix · Glossary

| Term | Meaning |
|---|---|
| **Assignment** | A unit of work with a title and a status. The only entity in the system. |
| **`IN_PROGRESS`** | The starting status. Not yet handed in. |
| **`SUBMITTED`** | Handed in. Terminal — nothing moves out of this state today. |
| **Layer** | A tier with one responsibility, which may call only the tier directly beneath it. |
| **Seed data** | Two sample assignments inserted at startup when the table is empty. |
