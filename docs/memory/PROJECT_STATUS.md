# School Management System - Project Status and Session Handoff

**Last verified:** 4 August 2026
**Verified against:** a running instance on this machine (backend on :8080, frontend on :4200, SQL Server 2019)

---

## How to use this document

This is a snapshot of what is **built, deployed, and learned**. Paste this file or its
path into a new session to get instant context without re-deriving decisions already
made.

**Division of labour between documents.** Do not duplicate their content here.

| Document | Answers | Path |
|---|---|---|
| `PRD.md` | What the product must do; requirements and spec | `docs/project/PRD.md` |
| `ARCHITECTURE.md` | How the code is structured; layers, DDL, data flow | `docs/architecture/ARCHITECTURE.md` |
| `AGILE_HIERACHY.md` | Backlog, sprints, user-story IDs | `docs/project/AGILE_HIERACHY.md` |
| `CLAUDE.md` | Rules for working on this codebase | repository root |
| **This file** | **Implementation state, operational knowledge, settled decisions, known traps** | `docs/memory/PROJECT_STATUS.md` |

> `PRD.md` is at `docs/project/PRD.md`, **not** the repository root. Only `CLAUDE.md`
> and `README.md` live at the root.

**Precedence when documents disagree:** the code wins, then `CLAUDE.md`, then this
file, then everything else. Section 9 lists document claims currently known to be
false - check it before trusting a figure you read elsewhere.

---

## 1. What the system is

An assignment tracker: a shared list of assignments and their submission state,
scoped by role. It is deliberately small and heavily commented, because it serves two
real goals at once - working software, and a readable demonstration of strict
layering.

```
Angular  --HTTP-->  Controller  -->  Service  -->  Repository  -->  SQL Server
(browser)          (web layer)     (rules)       (data access)    (storage)
```

**Scope guard.** The repository is named *School Management System*, but the delivered
system is one slice: assignment tracking. **Two** entities (`Assignment`, `AppUser`),
**two** roles (`TEACHER`, `STUDENT`), **ten** endpoints.

There are no grades, marks, assessments, audit logs, file uploads, notifications,
classes, terms or subjects. Documentation describing such features was inherited from
an unrelated ASP.NET project and has been removed. Do not reintroduce it.

**Entity Framework cannot be used here.** EF6 and `ApplicationDbContext` are .NET
technologies; this backend is Java. JPA/Hibernate fills the same role and is equally
code-first. Any document mentioning EF is inherited from that unrelated project.

---

## 2. Verified state, 4 August 2026

| Component | Version / state | How confirmed |
|---|---|---|
| Java | Temurin **25.0.4+7** (LTS) | `java -version` |
| Spring Boot | **3.5.16** | `pom.xml` |
| Angular | **18.2** standalone, TypeScript 5.5 | `package.json` |
| Node / npm | **24.18.0** / **11.16.0** | `node --version` |
| Database | SQL Server **2019** (15.0.2000.5) | `SELECT @@VERSION` |
| Schema | Flyway **V1 + V2 applied**, `ddl-auto=validate` | startup log |
| Test suite | **36 tests, 0 failures**, BUILD SUCCESS | `mvnw test` |
| Backend | Running, :8080 | live HTTP |
| Frontend | Running, :4200, watch mode | live HTTP |

**Test breakdown (36 total).** The figure 29 appears in older documents and is wrong.

| Class | Count | Covers |
|---|---|---|
| `AssignmentServiceTest` | 17 | Business rules, repository mocked. Nested: creating 4, submitting 3, visibility 2, lifecycle 4, overdue 4 |
| `AssignmentApiTest` | 12 | MockMvc with real security; every status in the error contract |
| `ConcurrencyAndIntegrityTest` | 7 | 12 simultaneous submissions yield exactly one 200; DB refuses orphans, duplicates, invalid enums, over-long titles |

Tests run against **H2**, so no SQL Server instance is needed for the suite.

---

## 3. Where everything lives

Verified file map. `frontend/tracker-ui/` is the only runnable Angular project.

```
School Management System/
├── CLAUDE.md, README.md, .gitignore        # root by design
├── docs/
│   ├── DOCUMENTATION_INDEX.md
│   ├── architecture/ARCHITECTURE.md
│   ├── project/       PRD.md, AGILE_HIERACHY.md
│   ├── memory/        PROJECT_STATUS.md     # this file
│   ├── daily-reports/ 2026-07-27/28/29.md
│   └── error-fixes/   invalid-runtime-for-javase.md
├── backend/
│   ├── mvnw, mvnw.cmd, .mvn/                # wrapper; no system Maven needed
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/tracker/
│       │   ├── TrackerApplication.java       # entry point + seed runners
│       │   ├── config/      CorsConfig, SecurityConfig
│       │   ├── controller/  AssignmentController, AuthController
│       │   ├── service/     AssignmentService, AppUserService
│       │   ├── repository/  AssignmentRepository, AppUserRepository
│       │   ├── model/       Assignment, AssignmentStatus, AppUser, Role
│       │   ├── security/    AppUserDetailsService
│       │   └── exception/   AssignmentNotFoundException, AccessDeniedException,
│       │                    GlobalExceptionHandler
│       ├── main/resources/  application.properties
│       │                    db/migration/V1__baseline_assignment.sql
│       │                    db/migration/V2__accounts_and_ownership.sql
│       └── test/            AssignmentApiTest, ConcurrencyAndIntegrityTest,
│                            service/AssignmentServiceTest,
│                            resources/application-test.properties
└── frontend/tracker-ui/src/
    ├── main.ts, index.html, styles.css
    ├── environments/  environment.ts, environment.development.ts
    └── app/           app.component.ts, app.component.html,
                       assignment.service.ts, csrf.interceptor.ts
```

`CLAUDE.md`'s structure diagram previously omitted the `config/` and `security/`
packages, the `AppUser` and `Role` model classes, the migrations and the whole test
tree. It was corrected on 4 August 2026 and every path in it verified to exist, so the
two agree.

---

## 4. Decisions already settled - do not re-litigate

Each of these was reasoned through and, in several cases, paid for with a real defect.
Reopening one needs a stated reason, not a preference.

### Architecture

| Decision | Why |
|---|---|
| Authority lives in the **service**, not the controller | "May this person do this?" is a business rule. In the controller, any second caller - a job, a test, a new endpoint - bypasses it. |
| Role scoping happens in the **query** | `findByOwnerOrderByIdAsc` means another person's rows are never loaded, serialised or sent. Filtering in the UI is not access control. |
| Validation is enforced at **both** the web edge and the service | `@Valid`/`@NotBlank` guards the edge; the service guard holds for any caller. Neither is relied on alone. |
| Service is the **transaction boundary**; class default `@Transactional(readOnly = true)` | A read-check-write without a transaction is a race, not a rule. |
| Errors are signalled by **throwing**; `GlobalExceptionHandler` maps to status | Keeps HTTP out of the service. A rejected request is never a bare 500. |

### Domain rules

| Decision | Why |
|---|---|
| **Teachers only** create; they may set work *for* a student | An earlier version made the creator the owner unconditionally. Since only teachers create, a student could never see any assignment and the role was decorative. |
| Edit and delete follow the **role, not the row** | Owner-only broke the moment a teacher set work for a student: the student became owner and the teacher could not fix their own typo. The mirror is worse - a student rewriting the assignment they were set. |
| **Unsubmit is teacher-only**, deliberately not the mirror of submit | If students could retract their own work, "submitted" would mean nothing. |
| Submitted work **cannot be deleted**; reopen first | Destroying a record of handed-in work takes two deliberate acts, not one careless click. |
| `OVERDUE` is **derived on read**, never stored | A stored flag is wrong the moment midnight passes. It is not a third status - there are still exactly two. |
| Another person's assignment returns **404, not 403** | 403 confirms the id exists and belongs to somebody, letting an outsider map data by probing ids. Where the caller can already legitimately see the row, 403 is used instead - hiding it would be pointless. |

### Data integrity

| Decision | Why |
|---|---|
| Constrain at the **database**, not only in Java | Application checks are policy that holds only while every writer goes through this code. |
| `@Version` optimistic locking | Measured defect: before it existed, **3 of 12** simultaneous submissions were accepted when exactly 1 should have been. Now a lost update becomes a 409. |
| `EnumType.STRING`, never ordinal | Reordering constants silently reinterprets existing rows. |
| `status` carries an explicit **`CHECK` constraint** | H2's native `ENUM` had no SQL Server equivalent; the guarantee had to be rewritten by hand. Re-verify every constraint when changing database - do not assume it ported. |
| `owner_id` FK with **`ON DELETE NO_ACTION`** | The database refuses to delete an account that still owns work. Never add `ON DELETE CASCADE`: destroying somebody's work as a side effect of removing their account must be an explicit service decision. |
| `ddl-auto=validate` + Flyway, never `update` | `update` only ever adds and will not tighten an existing column. |
| Seeding is guarded by `count() > 0` | The database is persistent now; unguarded seeding would pile up duplicates on every restart. This was harmless on H2 and is not harmless now. |
| Password hash is **not** a SQL literal | V2 inserts a placeholder; `TrackerApplication.seedAccounts` writes a real BCrypt hash with the application's own encoder. A hash literal is a hostage to whatever cost factor was current when it was typed. |

### Security

| Decision | Why |
|---|---|
| **CSRF stays on** | Auth rides on a session cookie, and browsers attach cookies regardless of which site triggered the request. "It's an API" only justifies disabling CSRF when the API does not use cookies. This one does. |
| BCrypt cost 10, per-password salt | Being fast is precisely the wrong property for a password hash. |
| Wrong username and wrong password give the **same** 401 | Distinguishing them enables username enumeration. |
| `HttpStatusEntryPoint(401)`, not a login redirect | The default redirect would hand a `fetch()` a 200 and a chunk of HTML to parse as JSON. |
| CORS from an explicit allow-list, never `*` | `*` would let any site on the internet call the API from a visitor's browser. |

### Frontend

| Decision | Why |
|---|---|
| `assignment.service.ts` is the **only** place that knows URLs | Components never call `HttpClient` directly. |
| A **hand-written `csrf.interceptor.ts`** exists | Angular's built-in XSRF support skips absolute cross-origin URLs on purpose. In development the page is :4200 and the API :8080, so it stayed silent and sign-in failed with a misleading 401. The interceptor forwards the token narrowly - only to `environment.apiBaseUrl`, only for unsafe methods. |
| `withCredentials: true` on every call | A browser will not attach the session cookie cross-origin unless asked. Without it the app signs in and is anonymous on the next request. |
| A dev-server proxy was **considered and rejected** | `proxy.conf.json` would remove CORS from development entirely, but it would hide the cross-origin behaviour this project exists to demonstrate. |
| Every `subscribe()` needs an error callback | Silent failure is a defect. Use the existing banner. |

---

## 5. Environment setup - reproducing this from scratch

**This procedure was executed and verified on 4 August 2026.** The machine had *none*
of the prerequisites, despite `README.md` implying otherwise.

State found on a clean machine:

| Item | Found | Action taken |
|---|---|---|
| JDK | **None installed**; `java.exe` absent, `JAVA_HOME` unset | Installed Temurin 25 |
| `node_modules` | **Missing** (README claims pre-installed) | `npm install` |
| SQL Server instance | Default **`MSSQLSERVER`**, not the named `MSSQLSERVER01` the docs describe | Used as-is |
| SQL Server TCP | **Disabled** (`Enabled = 0`) | Enabled |
| SQL Server auth | **Windows-only** (`LoginMode = 1`) | Set to Mixed Mode (2) |
| Database and login | **Neither existed** | Created |

### Step 1 - JDK 25

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK --exact --silent `
  --accept-package-agreements --accept-source-agreements
[Environment]::SetEnvironmentVariable("JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot", "User")
```

`JAVA_HOME` is what the Maven Wrapper reads, in preference to whatever `java` is on
`PATH`. A terminal opened before it was set will not see it; in VS Code, restart the
**editor**, not just the terminal.

### Step 2 - database, login, user

Runs unelevated if you are a Windows sysadmin on the instance (shared memory works
even with TCP off).

```sql
CREATE DATABASE [School Management System];
CREATE LOGIN [tracker_app] WITH PASSWORD = 'Tracker!2026Dev', CHECK_POLICY = OFF;
-- then, in that database:
CREATE USER [tracker_app] FOR LOGIN [tracker_app];
ALTER ROLE db_owner ADD MEMBER [tracker_app];
```

### Step 3 - enable TCP and mixed-mode auth

JDBC **cannot use shared memory**. This is the trap that makes `sqlcmd` connect while
Java cannot, which looks baffling. Mixed mode is required because the app signs in
with the SQL login `tracker_app`, not a Windows identity.

`xp_instance_regwrite` performs these writes as the service account, so **no UAC
prompt is needed** for the registry portion:

```sql
EXEC xp_instance_regwrite N'HKEY_LOCAL_MACHINE',
  N'Software\Microsoft\MSSQLServer\MSSQLServer\SuperSocketNetLib\Tcp',
  N'Enabled', REG_DWORD, 1;
EXEC xp_instance_regwrite N'HKEY_LOCAL_MACHINE',
  N'Software\Microsoft\MSSQLServer\MSSQLServer\SuperSocketNetLib\Tcp\IPAll',
  N'TcpPort', REG_SZ, N'1433,14333';
EXEC xp_instance_regwrite N'HKEY_LOCAL_MACHINE',
  N'Software\Microsoft\MSSQLServer\MSSQLServer\SuperSocketNetLib\Tcp\IPAll',
  N'TcpDynamicPorts', REG_SZ, N'';
EXEC xp_instance_regwrite N'HKEY_LOCAL_MACHINE',
  N'Software\Microsoft\MSSQLServer\MSSQLServer',
  N'LoginMode', REG_DWORD, 2;
```

Then restart the service - **this step does need elevation**:

```powershell
Restart-Service MSSQLSERVER -Force
```

> **Why the port list is `1433,14333` and not just one.** `application.properties`
> hardcodes port 14333. On the original machine that was a *named* instance, which
> would otherwise take a dynamic port that changes on every restart. This machine's
> **default** instance already served 1433. Listening on both means the project's
> connection string works unmodified while anything already using 1433 keeps working.
> SQL Server accepts a comma-separated port list in `IPAll\TcpPort`.
>
> The alternative - editing `application.properties` or passing
> `--spring.datasource.url=...` - was rejected so that a plain `mvnw spring-boot:run`
> works with zero overrides.

### Step 4 - frontend dependencies

```powershell
cd frontend/tracker-ui
npm install
```

### Step 5 - run

Backend first; the frontend shows a red banner with a **Retry** button if the API is
not up.

```powershell
cd backend           ; .\mvnw.cmd spring-boot:run      # :8080
cd frontend/tracker-ui ; npm start                     # :4200
```

Sign in as `teacher` or `student`, both `password123`.

---

## 6. Operational gotchas

These cost real time. Check them before diagnosing further.

> **This is where machine-specific values belong.** `CLAUDE.md`'s "Environment
> Gotchas" was rewritten on 4 August 2026 to be portable - it now describes symptoms
> and defers here for the literal paths, ports and instance names in force. Keep that
> split: a gotcha written as "the path is X" becomes wrong the moment the project is
> cloned; written as "if you see Y, check Z" it stays useful everywhere.

### Confirmed on the current machine, 4 August 2026

- **npm 11 blocks install scripts.** `npm install` reported four packages with
  unapproved scripts (`esbuild` x2, `lmdb`, `msgpackr-extract`). The Angular build
  succeeded anyway because the platform binaries resolve through optional
  dependencies - but if an esbuild platform error appears, run
  `npm approve-scripts --allow-scripts-pending`.
- **This directory is not a git repository.** `CLAUDE.md` names a GitHub remote
  (`AtreusTefo/School-Management-System`), but there is no `.git` here. Do not assume
  history, branches, or `git diff` are available.
- **`.vscode/settings.json` carried JDK paths from the original machine.** All four
  entries in `java.configuration.runtimes` pointed at folders that do not exist here,
  so the Red Hat Java extension raised one "Invalid runtime for JavaSE-nn" error per
  entry. Fixed on 4 August 2026 by reducing the list to the single installed JDK 25 -
  see `docs/error-fixes/invalid-runtime-for-javase.md`. The setting is validated
  entry by entry, so listing a JDK the project does not target costs an error for
  nothing. **The build never used it:** the Maven Wrapper reads `JAVA_HOME` and
  ignores this registry entirely, which is why the suite passed while the editor
  complained. That path is machine-specific and will need correcting on any other
  machine.
- **No PKIX/TLS interception was observed here.** Maven downloaded from Central
  cleanly. The Avast truststore workaround below applies to the original machine, not
  necessarily this one.

### Inherited from the original machine - verify before applying

- **Antivirus intercepts HTTPS.** Avast re-signs TLS, and a fresh JDK does not trust
  its root, so Maven fails with `PKIX path building failed`. Import the root with
  `keytool -importcert -cacerts -storepass changeit`. For npm, set
  `NODE_EXTRA_CA_CERTS` to a PEM copy. **The root rotates** - when a build that
  worked yesterday starts failing with `PKIX path validation failed`, re-export and
  re-import; delete the old alias first.
- **Antivirus quarantines build files.** Avast has removed `backend/mvnw.cmd` and a
  freshly installed `java.exe` as false positives. Symptoms: a vanished wrapper
  script, and `Access denied` when restoring it while other files write fine.
- **JDK 8 and 11 are also installed there**, and `java` on `PATH` resolves to JDK 8
  by design. `JAVA_HOME` is the setting that matters.
- **The system proxy captures loopback.** Chrome cannot reach `localhost:4200`
  through it. Launch browser automation with
  `--no-proxy-server --proxy-bypass-list=<-loopback>`.

### Always applicable

- **Stop the backend before rebuilding.** Windows holds a lock on the running jar and
  `mvnw clean` fails to delete it.
- **Never wait on network idle** against the Angular dev server. Its live-reload
  websocket stays open, so that condition never arrives. Wait for the document.
- **Write SQL Server migrations as separate `GO` batches.** SQL Server compiles a
  whole batch before running any of it, so adding a column and using it in the same
  batch fails with "Invalid column name" even though the ALTER would have run first.
  This is why `V2` is not one continuous script.
- **PowerShell `-WebSession` persists headers between requests.** A probe that sets
  `X-XSRF-TOKEN` once resends it forever, so a "no token" test passes for the wrong
  reason. Call `$session.Headers.Clear()` before asserting a header is absent - **but
  remember that clearing it also strips the token from subsequent writes**, which
  will return 403 (CSRF) and can be misread as an authorisation result. This
  misfired during verification on 4 August 2026: a 404 check reported 403 until the
  token was re-attached.
- **Register Playwright dialog handlers before navigating.** Playwright dismisses
  dialogs by default, so a late `page.on('dialog', ...)` means `confirm()` returns
  false and the action under test never runs.
- **Do not round-trip UTF-8 files through PowerShell `Get-Content`/`Set-Content`.** It
  mangles box-drawing characters in the directory trees in these documents.
- **The editor may use a different TypeScript than the build.** `.vscode/settings.json`
  pins `typescript.tsdk`; choose "Use Workspace Version" once when prompted.

---

## 7. Verification playbook

Commands with their expected output, so a claim can be checked rather than assumed.

```powershell
# Full suite - expect: Tests run: 36, Failures: 0, Errors: 0 / BUILD SUCCESS
cd backend ; .\mvnw.cmd test

# Database reachable over the exact path JDBC uses
sqlcmd -S "tcp:localhost,14333" -U tracker_app -P 'Tracker!2026Dev' -d "School Management System"
```

Live API round-trip. Every write needs `X-XSRF-TOKEN`; re-read the cookie after login,
because the token is rotated on authentication.

```powershell
Invoke-WebRequest "http://localhost:8080/api/auth/csrf" -SessionVariable s -UseBasicParsing
$tok = ($s.Cookies.GetCookies("http://localhost:8080") | ? Name -eq "XSRF-TOKEN").Value
Invoke-WebRequest "http://localhost:8080/api/auth/login" -Method Post `
  -Body '{"username":"teacher","password":"password123"}' -ContentType "application/json" `
  -Headers @{"X-XSRF-TOKEN"=$tok} -WebSession $s -UseBasicParsing
$tok = ($s.Cookies.GetCookies("http://localhost:8080") | ? Name -eq "XSRF-TOKEN").Value
```

Error contract, confirmed live on 4 August 2026 against SQL Server:

| Probe | Expected |
|---|---|
| `GET /api/assignments` anonymous | `401` |
| `GET /api/auth/me` as teacher | `{"id":1,"username":"teacher","role":"TEACHER"}` |
| `GET /api/assignments` teacher / student | 4 rows / 3 rows |
| `POST /api/assignments` as student | `403` `Only a teacher can create an assignment.` |
| `POST /api/assignments` blank title | `400` `title: Title must not be blank` |
| `PUT /api/assignments/999/submit` | `404` `No assignment found with id = 999` |
| `PUT /api/assignments/1/submit` twice | `200`, then `409` `Assignment 1 has already been submitted.` |
| Any write without the token | `403` |

Startup log should show Flyway applying `V1 baseline assignment` and
`V2 accounts and ownership`, then `Started TrackerApplication`.

### Two things the suite cannot cover

- **The SQL Server migrations.** Tests run on H2, so migration SQL is unexercised.
  `ddl-auto=validate` is what catches drift between migrations and entities.
- **Browser behaviour.** Drive `http://localhost:4200` as **both** roles. The CSRF
  defect passed every API test and appeared only in Chrome. **This was not done on
  4 August 2026** - the page was confirmed to serve (`200`, `app-root` present) but
  not confirmed to work when clicked.

**Prove constraints by bypassing the application.** Write bad data directly with
`sqlcmd` and confirm the database refuses it. A rule enforced only in Java is policy,
not integrity.

---

## 8. Seed data

Recreated only when the `assignment` table is empty. Accounts come from migration V2;
their passwords are reset at every startup by `seedAccounts`.

| id | Title | Owner | Due | Purpose |
|---|---|---|---|---|
| 1 | Math Homework 1 | student | none | "no deadline" is a legitimate state |
| 2 | History Essay | student | +7 days | ordinary future deadline |
| 3 | Science Lab Report | student | **-3 days** | makes `OVERDUE` visible immediately |
| 4 | Prepare end-of-term report | teacher | +14 days | so the two roles show different lists |

`seedAccounts` is `@Order(1)` and `seedAssignments` `@Order(2)`. The ordering is not
decoration: under the test profile Flyway is off, so `seedAccounts` is the only thing
that creates the accounts, and the undeclared dependency surfaced as an unexplained
`NoSuchElementException`.

Development credentials are `teacher` / `student`, both `password123`. Acceptable
locally and nowhere else.

---

## 9. Known documentation inaccuracies

Verified false on 4 August 2026. **Trust the code, not these claims.** They are
recorded rather than silently fixed so a future session does not "rediscover" them.

| Where | Claim | Reality |
|---|---|---|
| `PRD.md` NFR-9, L4 | "29 tests" | **36**. The figure omits the 7 concurrency and integrity tests. |
| `PRD.md` s.9 | "Of **15** simultaneous submissions" | The test uses **12** threads (`ConcurrencyAndIntegrityTest:63`). |
| `PRD.md` s.9 | "29 automated tests run as part of `mvnw package`" | 36. |
| `PRD.md` s.5 | The assignment JSON block appears **twice**; the second omits `ownerUsername`, `dueDate`, `overdue` | The first block is correct; the second is a stale leftover. |
| `DOCUMENTATION_INDEX.md` | Scope warning: "One entity, three endpoints, no users" | **Two** entities, **ten** endpoints, and accounts with two roles. Badly stale - it contradicts the same file's own PRD summary. |
| `README.md` | "the two `src` folders"; `frontend/src/` is a reference copy | **`frontend/src/` does not exist.** Only `frontend/tracker-ui/` is present. |
| `README.md` | "its dependencies are installed, so this is now a one-liner" | `node_modules/` was absent; `npm install` was required. |
| ~~`CLAUDE.md`~~ | ~~Repository structure diagram omits `config/`, `security/`, `AppUser`, `Role`~~ | **Corrected 4 August 2026.** The diagram now also covers the migrations and the test tree; all 39 named paths were verified to exist. |
| `application.properties` | Instance `MSSQLSERVER01` on 14333 | This machine has the **default** instance `MSSQLSERVER`, now listening on 1433 **and** 14333. The connection string still works unmodified, which is why it was left alone. `CLAUDE.md` made the same claim and was corrected on 4 August 2026. |
| `assignment.service.ts:56-58` | "The CSRF token needs no code at all - Angular echoes it back automatically" | False cross-origin, which is precisely why `csrf.interceptor.ts` was written. The comment contradicts the neighbouring file. |
| `assignment.service.ts:117,124` | `updateAssignment` / `deleteAssignment` marked "Owner only" | Both are **teacher-only** (`AssignmentService.requireTeacher`). |

None of these have been corrected in place; correcting them is unclaimed work.

---

## 10. Open items - explicitly not agreed scope

From `PRD.md` section 7, "Still open". Listed so they are not mistaken for either
delivered work or an agreed backlog.

| | Item |
|---|---|
| **R7** | Password change and account self-service; credentials are fixed at seed time |
| **R8** | Classes, terms and subjects; there is no grouping above an assignment |
| **R9** | Run the test suite in CI on every push |
| **R10** | Tests that exercise the SQL Server migrations, not just the entities |

Accepted limitations, chosen rather than discovered:

| | Limitation | Consequence |
|---|---|---|
| **L6** | The list does not refresh on its own | Two people working at once can act on stale data. Handled safely - the server returns 409 and the UI explains it - but that is recovery, not prevention. |
| **L9** | Development credentials are seeded | Fine locally, unacceptable anywhere else. |
| **L10** | Tests run against H2, not SQL Server | The migrations are not covered by the suite. |

Also outstanding, from the inaccuracy list above: correcting the stale figures and
comments in section 9, and driving the browser as both roles.

---

## 11. Working agreements

Condensed from `CLAUDE.md`, which is authoritative if they disagree.

- **No emojis** anywhere - documentation, comments, commit messages.
- **Verify before asserting.** The project is small enough to read. The docs have been
  wrong before; section 9 lists where.
- **Preserve the teaching voice.** Comments explain *why*, not just *what*. Do not
  strip them to make code look tidy.
- **Layering is not negotiable.** Business rules in the service. A controller that
  decides anything, or a service that knows HTTP or SQL, is a defect regardless of
  whether it works.
- **Integrity is enforced at the database.** Any new field or state carries its
  constraint into the schema, not only into Java.
- **Failures must be visible.** Accurate status, actionable message, surfaced in the UI.
- **Do not claim unverified work.** State what was run and what it returned. If
  something was skipped or failed, say so.
- **Documentation belongs in its folder** under `docs/`. Never leave files loose in
  `docs/` except `DOCUMENTATION_INDEX.md`. Add an index row in the same change.
- **Do not rewrite dated records.** `docs/daily-reports/` describes what was true on
  its date; correct only what was wrong when written.

---

## 12. Document history

| Date | Change |
|---|---|
| 4 August 2026 | Expanded from a four-line stub into a full handoff record. All figures re-verified against the running system; test count corrected 29 to 36; section 9 added after cross-checking documents against the code; section 5 records the from-scratch setup performed that day. |
| 4 August 2026 | Recorded the `.vscode/settings.json` invalid-runtime fix in section 6 and the first `error-fixes/` document in the file map. |
| 4 August 2026 | `CLAUDE.md`'s "Environment Gotchas" rewritten to be machine-portable; this file is now the agreed home for per-machine values. Section 9 updated accordingly. |
| 4 August 2026 | `CLAUDE.md`'s repository structure diagram corrected and `memory/` added to its folder taxonomy. The corresponding section 9 row struck through rather than deleted, so the record shows what was wrong and when it was fixed. |
