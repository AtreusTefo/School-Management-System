# Documentation Index

**Project:** School Management System - Assignment Tracker
**Last updated:** 4 August 2026

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
| Pick up where the last session left off | [`memory/PROJECT_STATUS.md`](memory/PROJECT_STATUS.md) |

> **Scope warning.** The repository is named *School Management System*, but the
> delivered system is one slice of that idea: assignment tracking. Two entities
> (`Assignment`, `AppUser`), two roles (`TEACHER`, `STUDENT`), ten endpoints. If a
> document describes grades, marks, assessments, audit logs, file uploads,
> notifications, classes, terms or subjects, it is wrong - verify against the code.

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

### memory/

| Document | Contents |
|----------|----------|
| [`PROJECT_STATUS.md`](memory/PROJECT_STATUS.md) | Session handoff. Verified implementation state and versions; the file map as built; settled decisions with the reasoning behind them, so they are not re-litigated; the from-scratch environment setup actually performed; operational gotchas; a verification playbook with expected output; known documentation inaccuracies; open items. Written to be pasted into a new session for instant context. |

### daily-reports/

| Document | Contents |
|----------|----------|
| [`2026-07-29.md`](daily-reports/2026-07-29.md) | Work of 29 July 2026: separating the two halves for independent deployment, then completing the four remaining epics — SQL Server persistence, accounts and roles, the full assignment lifecycle, and a 36-test automated suite. |
| [`2026-07-28.md`](daily-reports/2026-07-28.md) | Work of 28 July 2026: enforcing data integrity at the database, fixing a concurrency defect measured before and after, upgrading to Java 25 and Spring Boot 3.5.16, and replacing documentation that described an unrelated application. |
| [`2026-07-27.md`](daily-reports/2026-07-27.md) | Work of 27 July 2026: getting the stack running, fixing the HTTP error contract, making frontend failures visible, and the environment problems resolved along the way. |

### error-fixes/

| Document | Contents |
|----------|----------|
| [`invalid-runtime-for-javase.md`](error-fixes/invalid-runtime-for-javase.md) | The Red Hat Java extension reporting "Invalid runtime for JavaSE-nn: the path points to a missing or inaccessible folder". Root cause: `java.configuration.runtimes` in `.vscode/settings.json` listed four JDK paths belonging to the original development machine. Editor-only; the Maven build reads `JAVA_HOME` and was unaffected. |

---

## Folder taxonomy

Where a new document belongs. Do not leave files loose in `docs/`.

| Folder | Holds | Status |
|--------|-------|--------|
| `architecture/` | System design, data flow, architectural patterns | In use |
| `project/` | Requirements, planning, deliverables, scope | In use |
| `daily-reports/` | Daily progress and status, named `YYYY-MM-DD.md` | In use |
| `memory/` | Session-handoff snapshots: implementation state and operational knowledge, kept current rather than dated | In use |
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
