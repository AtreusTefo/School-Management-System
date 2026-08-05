# CLAUDE.md - School Management System Master Instructions

## Project Context
- **Name:** School Management System - Assignment Tracker
- **Repository:** https://github.com/AtreusTefo/School-Management-System
- **Stack:** Java 25 (LTS), Spring Boot 3.5.16 (backend REST API) + Angular 18 standalone (frontend SPA) + SQL Server 2019, database `School Management System`. The instance name, port and auth mode are per-machine; the values currently in force are in `docs/memory/PROJECT_STATUS.md`.
- **Primary IDEs:** VS Code, Claude Code
- **Main Goal:** A small, heavily commented full-stack application that tracks assignment submission state, built to demonstrate a strictly layered architecture. Both goals are real: the software must work, and the code must stay readable enough to teach from.

### Scope Guard - Read Before Adding Features
The repository is named *School Management System*, but the delivered system is one slice of that idea: **assignment tracking with PDF hand-in**. Ten entities (`AppUser`, `Subject`, `SchoolClass`, `Enrolment`, `Course`, `Assignment`, `Submission`, `SubmissionFile`, `Assessment`, plus the `Role`/`AssignmentStatus`/`PerformanceLevel` enums), two roles (`TEACHER`, `STUDENT`).

There are **no** audit logs, notifications, terms or timetable periods. Documentation describing such features was inherited from an unrelated ASP.NET project; do not reintroduce it, and do not assume a feature exists because a document once mentioned it. Verify against the code.

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
│       │   ├── TrackerApplication.java  # Entry point + seed runners: accounts, then
│       │   │                            #   timetable, then assignments (@Order matters)
│       │   ├── controller/     # AssignmentController, SubmissionController,
│       │   │                   #   SchoolController, AssessmentController,
│       │   │                   #   AuthController, UserController
│       │   ├── service/        # AssignmentService (fan-out), SubmissionService (PDF),
│       │   │                   #   SchoolService (timetable), AssessmentService (marks),
│       │   │                   #   AppUserService
│       │   ├── repository/     # One per entity (Spring Data JPA)
│       │   ├── model/          # AppUser, Role, Subject, SchoolClass, Enrolment, Course,
│       │   │                   #   Assignment, Submission, SubmissionFile, AssignmentStatus,
│       │   │                   #   Assessment, PerformanceLevel
│       │   ├── dto/            # Records the API publishes - see SubjectView for why
│       │   ├── config/         # SecurityConfig (auth, CSRF), CorsConfig (allowed origins)
│       │   ├── security/       # AppUserDetailsService - loads accounts for Spring Security
│       │   └── exception/      # AssignmentNotFoundException, ResourceNotFoundException,
│       │                       #   AccessDeniedException, GlobalExceptionHandler
│       ├── main/resources/
│       │   ├── application.properties
│       │   └── db/migration/   # Flyway, SQL Server dialect - V1 baseline, V2 accounts and
│       │                       #   ownership, V3 must-change-password, V4 subjects/classes/
│       │                       #   courses/submissions, V5 timestamp column types,
│       │                       #   V6 assessment and marks
│       └── test/
│           ├── java/com/example/tracker/
│           │   ├── AssignmentApiTest.java            # MockMvc, real security
│           │   ├── ConcurrencyAndIntegrityTest.java  # 12 racing submissions, DB constraints
│           │   └── service/AssignmentServiceTest.java # business rules, repository mocked
│           └── resources/application-test.properties # H2, Flyway off, ddl-auto=create-drop
└── frontend/
    └── tracker-ui/             # The runnable Angular project - ng serve here
        └── src/
            ├── main.ts
            ├── index.html
            ├── styles.css
            ├── environments/   # environment.ts (build), environment.development.ts (serve)
            └── app/
                ├── assignment.service.ts    # The only place that knows API URLs
                ├── csrf.interceptor.ts      # Attaches X-XSRF-TOKEN cross-origin
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
- **Run tests:** `.\mvnw.cmd test` - 103 tests, run against H2, no SQL Server needed.
- **Database:** SQL Server, database `School Management System`, login `tracker_app`. The connection string in `application.properties` uses port 14333; confirm your instance actually listens there before assuming a failure is the application's fault. Query it with:
  `sqlcmd -S "tcp:localhost,14333" -U tracker_app -P 'Tracker!2026Dev' -d "School Management System"`
- **Development accounts:** `teacher` and `student`, both password `password123`, reset at every startup.

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
- **A filtered index makes `SET QUOTED_IDENTIFIER ON` mandatory for writes.** Any INSERT/UPDATE/DELETE on a table carrying one fails with error 1934 unless the session sets it. The JDBC driver sets it on connect, so the application is unaffected - but `sqlcmd` defaults it OFF, and the resulting failure looks exactly like the schema rejecting your data when it is really the session being misconfigured. Put `SET QUOTED_IDENTIFIER ON;` at the top of any ad-hoc script that writes to `assessment`.

### Testing Requirements
There is an automated suite: `.\mvnw.cmd test` runs 103 tests, and `package` fails the build if any fail. Add to it rather than reverting to manual checks.

- **Unit tests** (`AssignmentServiceTest`) - business rules with the repository mocked.
- **Full-stack tests** (`AssignmentApiTest`) - MockMvc with real security; every status code in the error contract.
- **Concurrency and integrity** (`ConcurrencyAndIntegrityTest`) - 12 simultaneous submissions must yield exactly one `200`; the database must refuse orphans, duplicates, invalid enum values and over-long titles.

Two things the suite cannot cover, so check them by hand when relevant:
- **The SQL Server migrations.** Tests run on H2, so migration SQL is unexercised; `ddl-auto=validate` is what catches drift between migrations and entities.
- **Browser behaviour.** Drive `http://localhost:4200` as **both** roles. The CSRF defect passed every API test and only appeared in Chrome, because Angular refuses to attach its token cross-origin.

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
