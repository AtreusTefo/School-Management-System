# Documentation Index

**Project:** School Management System - Assignment Tracker
**Last updated:** 28 July 2026

Every project document, what it is for, and when to read it. This file is the only
document permitted to sit loose in `docs/`; everything else belongs in a subfolder.

---

## Start here

| If you want to | Read |
|----------------|------|
| Run the application | [`README.md`](../README.md) (repository root) |
| Understand what the product is and is not | [`project/PRD.md`](project/PRD.md) |
| Understand how the code is structured | [`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) |
| See what was delivered and what is planned | [`project/AGILE_HIERACHY.md`](project/AGILE_HIERACHY.md) |
| Work on this codebase (human or AI) | [`CLAUDE.md`](../CLAUDE.md) (repository root) |

> **Scope warning.** The repository is named *School Management System*, but the
> delivered system is one slice of that idea: assignment tracking. One entity, three
> endpoints, no users. If a document ever describes teachers, students, admins,
> logins or grades, it is wrong - verify against the code.

---

## Current documents

### architecture/

| Document | Contents |
|----------|----------|
| [`ARCHITECTURE.md`](architecture/ARCHITECTURE.md) | The system as built. Layer responsibilities and the rule that each layer may call only the one beneath it; the endpoint contract; the entity and its generated DDL; how integrity and concurrency are enforced; data-flow walkthroughs; security posture; known architectural limits. |

### project/

| Document | Contents |
|----------|----------|
| [`PRD.md`](project/PRD.md) | Product requirements. Purpose, goals and non-goals, users, functional requirements (marked **Built**), the interface contract, non-functional requirements, proposed scope (marked **not agreed**), known limitations, acceptance criteria, and the assumptions behind all of it. |
| [`AGILE_HIERACHY.md`](project/AGILE_HIERACHY.md) | Scrum framework and backlog. Application to Epic to Feature to User Story to Task hierarchy, Definition of Done, story-point scale, the delivered backlog, the sprint record, and planned epics with their dependencies. |

### daily-reports/

| Document | Contents |
|----------|----------|
| [`2026-07-27.md`](daily-reports/2026-07-27.md) | Work of 27 July 2026: getting the stack running, fixing the HTTP error contract, making frontend failures visible, and the environment problems resolved along the way. |

### error-fixes/

Empty. The first documented fix goes here.

---

## Folder taxonomy

Where a new document belongs. Do not leave files loose in `docs/`.

| Folder | Holds | Status |
|--------|-------|--------|
| `architecture/` | System design, data flow, architectural patterns | In use |
| `project/` | Requirements, planning, deliverables, scope | In use |
| `daily-reports/` | Daily progress and status, named `YYYY-MM-DD.md` | In use |
| `error-fixes/` | Bug fixes and error resolutions, one file per issue | Empty, reserved |
| `implementation/` | Implementation guides, code summaries, completion reports | Not yet created |
| `guides/` | Quick start, testing, how-to | Not yet created |

`CLAUDE.md` and `README.md` stay at the repository root by design and must not be
moved into `docs/`. Documentation previously lived under `backend/docs/` and was
moved here on 28 July 2026, because it describes the whole project rather than the
backend alone. Do not recreate a `backend/docs/` tree.

---

## Conventions

These are drawn from `CLAUDE.md`; that file is authoritative if the two disagree.

- **No emojis** in any `.md` file. Professional, technical, objective tone throughout.
- **Accuracy over completeness.** A document describing features the code does not
  have is worse than no document. When code and documentation disagree, fix the
  document in the same change.
- **Mark speculation as speculation.** Keep what is built separate from what is
  proposed, and never present a plan as delivered work.
- **Do not rewrite dated records.** Files in `daily-reports/` describe what was true
  on their date. Correct them only if they were wrong when written, never to reflect
  later changes. This is why the 27 July report still names Spring Boot 3.3.2 and
  JDK 17, which were current that day.
- **Cross-references use repository-root paths**, for example
  `docs/project/PRD.md`, so they read the same from any folder.

### Adding an error-fix document

Use the template in `CLAUDE.md`: Issue Title, Root Cause, Fix Applied (file paths and
line numbers), Testing Steps (the command run and its output), Troubleshooting, and
Related Files. Reproduce the failure before fixing it, so the fix can be proven rather
than assumed.

### Keeping this index current

Add a row when a document is created, and remove one when a document is deleted, in
the same change. An index that lists documents which do not exist is the same defect
as a document describing features which do not exist.
