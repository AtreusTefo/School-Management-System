# CLAUDE.md - School Management System Master Instructions

## Project Context
- **Name:** School Management System - Assignment Tracker
- **Repository:** https://github.com/AtreusTefo/School-Management-System
- **Stack:** Java 25 (LTS), Spring Boot 3.5.16 (backend REST API) + Angular 18 standalone (frontend SPA) + H2 in-memory database
- **Primary IDEs:** VS Code, Claude Code
- **Main Goal:** A small, heavily commented full-stack application that tracks assignment submission state, built to demonstrate a strictly layered architecture. Both goals are real: the software must work, and the code must stay readable enough to teach from.

### Scope Guard - Read Before Adding Features
The repository is named *School Management System*, but the delivered system is one slice of that idea: **assignment tracking**. There is exactly one entity (`Assignment`), three endpoints, and no concept of a user.

There are **no** teachers, students, admins, logins, grades, assessments, marks, audit logs, file uploads, or notifications. Documentation describing such features was inherited from an unrelated project and has been removed. Do not reintroduce it, and do not assume a feature exists because a document once mentioned it. Verify against the code.

Planned work is listed in `docs/project/PRD.md` section 7 and is explicitly **not agreed scope**.

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
├── docs/                       # ALL project documentation (see below)
│   ├── DOCUMENTATION_INDEX.md
│   ├── architecture/           # ARCHITECTURE.md
│   ├── project/                # PRD.md, AGILE_HIERACHY.md
│   ├── daily-reports/
│   └── error-fixes/
├── backend/                    # Spring Boot REST API
│   ├── mvnw / mvnw.cmd / .mvn/ # Maven Wrapper - no system Maven needed
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/tracker/
│       │   ├── TrackerApplication.java      # Entry point + seed data
│       │   ├── model/          # Assignment, AssignmentStatus
│       │   ├── repository/     # AssignmentRepository (Spring Data JPA)
│       │   ├── service/        # AssignmentService - business rules
│       │   ├── controller/     # AssignmentController - HTTP only
│       │   └── exception/      # AssignmentNotFoundException, GlobalExceptionHandler
│       └── resources/application.properties
└── frontend/
    └── tracker-ui/             # The runnable Angular project - ng serve here
        └── src/
            ├── main.ts
            ├── index.html
            └── app/
                ├── assignment.service.ts    # The only place that knows API URLs
                ├── app.component.ts         # UI logic
                └── app.component.html       # Table, create form, error banner
```

**Note on documentation location:** all documentation lives in `docs/` at the repository root, because it describes the whole project rather than the backend alone. It previously sat under `backend/docs/` and was moved on 28 July 2026. `CLAUDE.md` and `README.md` remain at the root by design. Do not recreate a `backend/docs/` tree, and do not move these two files into `docs/`.

## Environment Commands
- **Backend:** `cd backend` then `.\mvnw.cmd spring-boot:run` (Windows) or `./mvnw spring-boot:run`. Serves on `http://localhost:8080`.
- **Frontend:** `cd frontend/tracker-ui` then `npm start`. Serves on `http://localhost:4200`.
- **Build backend:** `.\mvnw.cmd clean package -DskipTests` produces `backend/target/tracker-0.0.1-SNAPSHOT.jar`.
- **Build frontend:** `npm run build` in `frontend/tracker-ui`.
- **Type-check frontend:** `npx tsc --noEmit -p tsconfig.app.json`.
- **Database console:** `http://localhost:8080/h2-console`, JDBC URL `jdbc:h2:mem:trackerdb`, user `sa`, no password.

Start the backend first. The frontend calls it directly at `http://localhost:8080` and shows a red banner if it cannot connect.

### Environment Gotchas on This Machine
These cost real time. Check them before diagnosing further.

- **Java 25 is required, and `JAVA_HOME` must point at it.** JDK 8 and 11 are also installed here, and `java` on the PATH still resolves to JDK 8 by design so other work keeps running. `JAVA_HOME` is set to `C:\Users\Developer.03\Java\jdk-25.0.4+7`; the Maven Wrapper reads it in preference to the PATH. If `mvnw` fails with `Unsupported class file major version` or `class file version 61.0`, `JAVA_HOME` is unset or stale - a terminal opened before it was set will not see it, and inside VS Code the editor itself must be restarted, not just the terminal.
- **Antivirus intercepts HTTPS.** Avast re-signs TLS traffic. A fresh JDK does not trust its root, so Maven fails with `PKIX path building failed`. Import the Avast root into the JDK truststore (`keytool -importcert -cacerts -storepass changeit`). For npm, set `NODE_EXTRA_CA_CERTS` to a PEM copy of the same certificate.
- **Antivirus also quarantines build files.** Avast has removed both `backend/mvnw.cmd` and a freshly installed `java.exe` as false positives. Symptoms are a vanished wrapper script and `Access denied` when restoring it, even though other files write fine. Fix in Avast: restore from Quarantine and add exclusions for the project folder and `C:\Users\Developer.03\Java\`.
- **The system proxy captures loopback.** Chrome cannot reach `localhost:4200` through it. Launch browser automation with `--no-proxy-server --proxy-bypass-list=<-loopback>`.
- **Never wait on network idle** against the Angular dev server. Its live-reload websocket stays open, so that condition never arrives. Wait for the document instead.
- **Stop the backend before rebuilding.** Windows holds a lock on the running jar and `mvnw clean` fails to delete it.
- **The editor may use a different TypeScript than the build.** `.vscode/settings.json` pins `typescript.tsdk` to the project copy; select "Use Workspace Version" once when prompted, or the editor will report errors the build does not.
- **Do not round-trip UTF-8 files through PowerShell `Get-Content`/`Set-Content`.** It mangles the box-drawing characters in this file's directory tree. Edit Markdown with an editor or a tool that preserves encoding.

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

### Error handling contract
The service signals problems by throwing. `GlobalExceptionHandler` translates exceptions into status codes. Never return a bare `500` for a client mistake.

| Cause | Status |
|-------|--------|
| Invalid input, blank or over-long title, null id | 400 |
| Unknown assignment id | 404 |
| Already submitted, or a concurrent modification | 409 |
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

## Data Integrity, Referential Integrity and Consistency Standards
- **Constrain at the database, not just in Java.** Application checks are policy that holds only while every writer goes through this code. Column constraints make the rule true of the data itself. `title` is `NOT NULL` with `length = 200`; `status` is an enum column restricted to its valid values.
- **Use typed values for closed sets.** Status is an enum, stored with `EnumType.STRING` (never ordinal - positions shift when constants are reordered and silently reinterpret existing rows).
- **One business operation, one transaction.** Annotate service write methods with `@Transactional`; the class default is `@Transactional(readOnly = true)`. A read-check-write sequence without a transaction is a race, not a rule.
- **Guard concurrent edits with `@Version`.** Optimistic locking turns a lost update into a `409`. This was a real defect: before it existed, 3 of 12 simultaneous submissions were accepted when exactly 1 should have been.
- **Keep the schema honest.** `ddl-auto=create-drop` against the in-memory database guarantees the schema matches the entities. On any persistent database use `validate` plus migrations (Flyway or Liquibase); never `update`, which only ever adds and will not tighten an existing column.
- **Referential integrity:** the schema is a single table with no foreign keys, so there is currently nothing to enforce. When relationships arrive (see `docs/project/PRD.md` R3, R4), declare them with explicit foreign keys and deliberate `ON DELETE` semantics rather than relying on application cleanup.

### Testing Requirements
There is no automated test suite yet - this is tracked as `docs/project/PRD.md` L4 and NFR-9. Until it exists, a change is verified by:
- **API surface:** exercise all three endpoints plus every error path. Expected codes: `200` list, `200` create, `400` blank title, `400` missing title, `400` over-long title, `200` submit, `409` resubmit, `404` unknown id, `400` non-numeric id.
- **Concurrency:** fire at least 12 simultaneous submissions at one assignment. Exactly one must return `200`; the rest must return `409`.
- **Browser:** load `http://localhost:4200`, create an assignment, submit it, confirm the status changes and no console errors appear.
- **Failure visibility:** stop the backend and confirm the page reports that the API is unreachable rather than doing nothing.

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
