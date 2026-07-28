# Product Requirements Document — Assignment Tracker

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

The current release has **no concept of user identity** — anyone who opens the page
sees and controls the same shared list. The roles below describe who the product is
*for*, and motivate the access rules proposed in Section 7.

| Role | Needs | Supported today |
|---|---|---|
| **Teacher** | Create assignments; see at a glance what has been handed in | Yes, fully |
| **Student** | See what is outstanding; mark work as submitted | Yes, but cannot be distinguished from a teacher |
| **Administrator** | Oversight across classes and terms | No |

> **Open question.** Whether students should be able to create assignments is
> currently unresolved. Today they can. Section 7 proposes restricting it.

## 4. Functional requirements — **Built**

Each requirement below is implemented and verified. See Section 9 for how.

### FR-1 · View all assignments

The system displays every assignment as a table of **title**, **status**, and an
action control. The list loads automatically when the page opens.

- An empty list shows *"No assignments yet. Add one above."* rather than a blank table.

### FR-2 · Create an assignment

A user submits a title through a form. The system creates the assignment and shows it
in the list without a page reload.

- The client supplies **only** the title. The id and starting status are assigned by
  the server and cannot be set by the caller.
- Every new assignment starts as `IN_PROGRESS`.
- Surrounding whitespace is trimmed from the title.
- A blank or whitespace-only title is rejected. The **Add** button stays disabled
  until the field holds non-space text, and the server rejects it independently —
  neither guard is relied on alone.

### FR-3 · Submit an assignment

A user marks a specific assignment as handed in. Its status becomes `SUBMITTED` and
the list reflects the change.

- An assignment that is already `SUBMITTED` cannot be submitted again. Its button is
  disabled in the interface, and the server rejects the attempt independently.
- Submission is one-way. There is no route back to `IN_PROGRESS`.

### FR-4 · Report failures honestly

Every failure returns the HTTP status that matches the problem, plus a message the
interface can show the user directly.

| Situation | Status | Message |
|---|---|---|
| Blank or missing title | `400` | `title: Title must not be blank` |
| Unknown assignment id | `404` | `No assignment found with id = 999` |
| Already submitted | `409` | `Assignment 3 has already been submitted.` |

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
| `GET` | `/api/assignments` | — | `200` + array | — |
| `POST` | `/api/assignments` | `{"title":"..."}` | `200` + created object | `400` |
| `PUT` | `/api/assignments/{id}/submit` | — | `200` + updated object | `400`, `404`, `409` |

An assignment is represented as:

```json
{ "id": 1, "title": "Math Homework 1", "status": "IN_PROGRESS" }
```

Cross-origin requests are permitted from `http://localhost:4200` only.

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

### **Proposed**

| | |
|---|---|
| **NFR-6** | List and write operations respond within 300 ms for up to 1,000 assignments |
| **NFR-7** | Data survives a restart (see Section 7, R1) |
| **NFR-8** | The interface meets WCAG 2.1 AA — keyboard operable, sufficient contrast, status conveyed by more than colour |
| **NFR-9** | Automated tests cover every requirement in Section 4 and run in CI on each push |

## 7. Proposed scope — **not yet agreed**

Ordered by how much each unblocks the others. Nothing here is built.

**R1 · Persistent storage.** The database is in-memory today: every restart wipes all
data back to two sample rows. Nothing else in this list is worth building until data
survives a restart. *Blocks: everything.*

**R2 · Typed status.** Status is stored as free text (`"IN_PROGRESS"`, `"SUBMITTED"`).
A typed value would make an invalid status impossible to represent rather than merely
unlikely, and would let the workflow in R4 be enforced by the compiler.

**R3 · Accounts and roles.** Introduces identity, so the list can be scoped to a
person and the teacher/student distinction in Section 3 can be enforced. Resolves the
open question about who may create assignments. *Depends on R1.*

**R4 · Due dates.** Adds a deadline per assignment and an `OVERDUE` state derived from
it. This is the most-requested capability that the current model cannot express at
all. *Depends on R2.*

**R5 · Edit and delete.** An assignment created with a typo is currently permanent.
Needs a decision on whether deletion is soft or hard.

**R6 · Un-submit.** Submission is one-way. If work is submitted by mistake there is no
recovery short of database surgery. *Depends on R3, since this needs authority rules.*

## 8. Known limitations

Accepted for this release, recorded so they are chosen rather than discovered.

| | Limitation | Consequence |
|---|---|---|
| **L1** | Data is in-memory | All assignments are lost on restart |
| **L2** | No authentication | Anyone reaching the page has full control |
| ~~**L3**~~ | ~~Status is an untyped string~~ | **Resolved.** Status is now a typed enum with a database-level constraint on the column. |
| **L4** | No automated tests | Every change must be re-verified by hand |
| **L5** | Localhost-only CORS | Cannot be deployed without configuration changes |
| **L6** | The list does not refresh on its own | Two people working at once can act on stale data. Handled safely — the server returns `409` and the interface explains it — but it is a recovery, not a prevention. |
| **L7** | Single shared list | No classes, terms, or subjects |
| **L8** | No relationships between tables | The schema is a single table, so there are no foreign keys and nothing for referential integrity to enforce. It becomes relevant at R3 and R4. |

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

**Verification method.** Eight API checks covering three success paths and five
failure paths, plus three browser scenarios driven end to end: the normal
create-and-submit flow; the backend stopped, to confirm the unreachable-API message;
and a genuine `409` produced by two tabs with one held stale, to confirm the server's
message reaches the user intact.

> These checks are currently scripted and run by hand. NFR-9 exists to make them
> permanent — until then, this section is evidence of a past run, not a standing
> guarantee.

## 10. Assumptions

These shaped the requirements above and are **unconfirmed**. If any is wrong, the
affected section needs revisiting.

| | Assumption | Affects |
|---|---|---|
| **A1** | A shared list without per-user views is acceptable for now | FR-1, R3 |
| **A2** | Submission is a self-declaration; no proof of work is uploaded | FR-3, non-goals |
| **A3** | Assignments are short-lived enough that in-memory storage is tolerable during development | L1, R1 |
| **A4** | Two statuses are sufficient until due dates arrive | FR-3, R4 |
| **A5** | Deployment beyond a local machine is out of scope for this release | L5 |

---

## Appendix · Glossary

| Term | Meaning |
|---|---|
| **Assignment** | A unit of work with a title and a status. The only entity in the system. |
| **`IN_PROGRESS`** | The starting status. Not yet handed in. |
| **`SUBMITTED`** | Handed in. Terminal — nothing moves out of this state today. |
| **Layer** | A tier with one responsibility, which may call only the tier directly beneath it. |
| **Seed data** | Two sample assignments inserted at startup when the table is empty. |
