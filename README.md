# School Management System — Assignment Tracker (JAVA SPRING BOOT)

A minimal full-stack prototype demonstrating a **layered Spring Boot backend**
and an **Angular frontend**. Beginner-friendly, heavily commented.

## What it does
- **Sign in** as a teacher or a student
- **Lists** assignments — a teacher sees all, a student sees only their own
- **Create** work, optionally setting it *for* a named student (teachers only)
- **Submit** an assignment, flipping its status to `SUBMITTED`
- **Edit, delete and reopen** (teachers only; submitted work must be reopened first)
- **Due dates**, with `OVERDUE` shown for work past its deadline
- **Sort, search and filter** the list, with a summary of what is outstanding
- **Change your own password**, and **create student accounts** (teachers only) that
  must have their temporary password replaced at first sign-in

Development accounts: `teacher` and `student`, both with password `password123`.

### A five-minute walkthrough

Start the backend, then the frontend, and open http://localhost:4200.

1. Sign in as **`teacher`**. Four assignments; the summary counts outstanding,
   submitted and overdue. "Science Lab Report" is already overdue on purpose.
2. Click the **Title**, **Due** or **Status** headings to sort. Click again to
   reverse, a third time to return to the original order. Assignments with no due
   date always sort last — "no deadline" is a real state, not a missing value.
3. Type in the search box and use the **All / Open / Submitted / Overdue** buttons.
   They compose, and the empty state distinguishes "nothing matches your filter"
   from "nothing here yet".
4. Click **Add a student account**, create one with a temporary password, and note
   the message: they must change it at first sign-in.
5. **Sign out**, then sign in as the account you just made. The application is
   replaced by *Choose your password* — there is no Cancel, and no other screen is
   reachable. The server returns `403` for anything else, so this holds even if the
   browser is bypassed.
6. Set a new password. The list appears immediately.
7. Visit http://localhost:8080/swagger-ui.html to see the same API described and
   callable.

## Requirements
- **Java 25** (the current LTS) — Maven is not needed, the project ships `mvnw`
- **Node.js 18+** for the frontend
- **SQL Server** with a database named `School Management System` — see
  [Database setup](#database-setup)

## Project layout
```
School Management System/
├── backend/                        # Java Spring Boot (the API)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/tracker/
│       │   ├── TrackerApplication.java     # startup + sample data
│       │   ├── model/       Assignment.java        # MODEL layer
│       │   ├── repository/  AssignmentRepository.java  # DATA ACCESS layer
│       │   ├── service/     AssignmentService.java     # BUSINESS layer
│       │   └── controller/  AssignmentController.java  # PRESENTATION layer
│       └── resources/application.properties
│       │   └── exception/
│       │       ├── AssignmentNotFoundException.java  # signals "no such id"
│       │       └── GlobalExceptionHandler.java       # exception -> HTTP status
│       └── resources/application.properties
└── frontend/
    ├── tracker-ui/                 # ← THE RUNNABLE ANGULAR PROJECT (ng serve here)
    │   └── src/                    #   working copy of the files below
    └── src/                        # reference copy of the hand-written sources
        ├── main.ts
        ├── index.html
        └── app/
            ├── assignment.service.ts   # talks to the API
            ├── app.component.ts        # UI logic
            └── app.component.html      # the table + create form
```

> **Heads-up on the two `src` folders.** `frontend/tracker-ui/src/` is what
> actually runs; `frontend/src/` is the original hand-written copy the project
> started from. They are identical today, but nothing keeps them in sync — if you
> edit one, copy the change across, or delete `frontend/src/` and treat
> `frontend/tracker-ui/` as the single source of truth.

## How the layers connect (request flows DOWN, data flows UP)
```
Angular  ──HTTP──►  Controller ──► Service ──► Repository ──► SQL Server
(browser)          (web layer)   (rules)     (data access)   (storage)
```
Each layer only talks to the one directly beneath it.

## Database setup

The application uses **SQL Server**. On this machine that is instance
`MSSQLSERVER01` (SQL Server 2019 Developer Edition), database
`School Management System`, on TCP port **14333**.

Two things had to be done once, as an administrator, before Java could connect:

```powershell
# 1. Enable TCP and pin a static port. Only Shared Memory was enabled by
#    default, and the JDBC driver cannot use shared memory at all.
$k = "HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL15.MSSQLSERVER01\MSSQLServer\SuperSocketNetLib"
Set-ItemProperty "$k\Tcp" -Name Enabled -Value 1
Set-ItemProperty "$k\Tcp\IPAll" -Name TcpPort -Value "14333"
Set-ItemProperty "$k\Tcp\IPAll" -Name TcpDynamicPorts -Value ""
Restart-Service "MSSQL`$MSSQLSERVER01" -Force
```

The port is **pinned** because named instances otherwise use dynamic ports that
change on every restart, which would break the connection string.

```sql
-- 2. Create the database and a login scoped to it.
CREATE DATABASE [School Management System];
GO
CREATE LOGIN [tracker_app] WITH PASSWORD = 'Tracker!2026Dev', CHECK_POLICY = OFF;
GO
USE [School Management System];
CREATE USER [tracker_app] FOR LOGIN [tracker_app];
ALTER ROLE db_owner ADD MEMBER [tracker_app];
```

Tables are created by **Flyway migrations** on first start —
`backend/src/main/resources/db/migration`. Hibernate runs with
`ddl-auto=validate`, so it may check the schema but never alter it.

Credentials are overridable without a rebuild:

```bash
DB_USERNAME=... DB_PASSWORD=... java -jar tracker.jar
```

> **A note on Entity Framework.** EF6 and `ApplicationDbContext` are .NET
> technologies and cannot run on this Java backend. JPA/Hibernate fills the same
> role and is equally code-first — the schema derives from the `@Entity` classes.

## API endpoints

Every endpoint except `login` and `csrf` requires a session; every write also
requires the `X-XSRF-TOKEN` header.

| Method | URL | Purpose |
|--------|-----|---------|
| GET | `/api/auth/csrf` | Issue the CSRF cookie |
| POST | `/api/auth/login` | Sign in (`{"username":"...","password":"..."}`) |
| GET | `/api/auth/me` | Who am I? |
| POST | `/api/auth/logout` | End the session |
| PUT | `/api/auth/password` | Change your own password |
| POST | `/api/users` | Create a student account — teachers only |
| GET | `/api/assignments` | List (scoped by role) |
| POST | `/api/assignments` | Create — teachers only |
| PUT | `/api/assignments/{id}` | Edit title / due date — teachers only |
| DELETE | `/api/assignments/{id}` | Delete — teachers only, not while submitted |
| PUT | `/api/assignments/{id}/submit` | Mark as SUBMITTED |
| PUT | `/api/assignments/{id}/unsubmit` | Reopen — teachers only |

Browse and try all of these at **http://localhost:8080/swagger-ui.html** while the
backend is running. The description is generated from the controllers, so it cannot
drift from the code. It is off unless `app.openapi.enabled` is true — development
turns it on; a deployment should not.

### Error responses
Failures return the status code that matches the problem, plus a readable message:

| Situation | Status | Example message |
|-----------|--------|-----------------|
| Not signed in | 401 | — |
| Wrong username or password | 401 | `Invalid username or password.` |
| Missing or invalid CSRF token | 403 | — |
| Blank or missing `title` | 400 | `title: Title must not be blank` |
| Signed in but not permitted | 403 | `Only a teacher can create an assignment.` |
| Unknown assignment id | 404 | `No assignment found with id = 999` |
| Someone else's assignment | 404 | *deliberately the same as "unknown"* |
| Submitting something already sent | 409 | `Assignment 3 has already been submitted.` |
| Deleting submitted work | 409 | `Assignment 3 has been submitted and cannot be deleted.` |

> Asking for another person's assignment returns **404, not 403**. Answering
> "forbidden" would confirm the id exists and belongs to somebody, letting an
> outsider map the data by probing ids.

Every error body has the same shape, so the frontend only parses one thing:
```json
{ "timestamp": "...", "status": 404, "error": "Not Found",
  "message": "No assignment found with id = 999", "path": "/api/assignments/999/submit" }
```

## Run the backend
Requires **Java 25** (the current LTS). Maven does *not* need to be installed —
the project ships the Maven Wrapper (`mvnw`), which fetches the right Maven itself.

```bash
cd "School Management System/backend"
./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```
- API runs at http://localhost:8080
- Check it: `curl http://localhost:8080/api/assignments` returns `401` until you sign in

## Run the tests

```bash
cd "School Management System/backend"
./mvnw test
```

48 tests: 17 unit tests of the business rules, 12 full-stack tests through MockMvc
with real security, 7 covering concurrency and database integrity, and 12 covering
account self-service. They run against H2, so **no SQL Server instance is needed**
to run the suite.

`./mvnw package` runs them too, and fails the build if any test fails.

**`JAVA_HOME` must point at a JDK 25.** The wrapper reads `JAVA_HOME` in
preference to whatever `java` is on your `PATH`, so it is the setting that
matters. If it is unset or points somewhere older, the build fails with
`Unsupported class file major version` or `class file version 61.0`. Check with:

```bash
echo $JAVA_HOME          # Windows PowerShell: echo $env:JAVA_HOME
```

A terminal opened before `JAVA_HOME` was set will not see it. In VS Code, restart
the editor itself — a new terminal tab still inherits the old environment.

<details>
<summary>If the build fails with <code>PKIX path building failed</code></summary>

Some antivirus and corporate proxies (Avast, Kaspersky, Zscaler…) intercept
HTTPS by re-signing traffic with their own root certificate. Windows trusts it,
but a freshly installed JDK does not — so Maven can't download from Maven
Central. Export that root certificate and add it to your JDK's truststore:

```bash
keytool -importcert -trustcacerts -alias corp-proxy \
        -file the-root-cert.cer \
        -cacerts -storepass changeit
```

Node hits the same wall; for npm, set `NODE_EXTRA_CA_CERTS` to a PEM copy of the
same certificate.
</details>

## Run the frontend
The Angular project is already scaffolded and its dependencies are installed, so
this is now a one-liner (requires Node.js 18+):

```bash
cd "School Management System/frontend/tracker-ui"
npm start
```
- UI runs at http://localhost:4200

Start the **backend first**, then the frontend, so the API is ready. If the API
isn't up, the page says so in a red banner and offers a **Retry** button — no need
to refresh once the backend comes back.

If `node_modules/` is ever missing or broken, restore it with `npm install` in
`frontend/tracker-ui`.

## Pointing the two halves elsewhere

Neither half has the other's address compiled in, so moving either one is
configuration rather than a code change.

**Backend** — the same jar runs anywhere. Port and permitted origins are supplied
at launch:

```bash
java -jar target/tracker-0.0.1-SNAPSHOT.jar \
     --server.port=8081 \
     --app.cors.allowed-origins=https://tracker.example.com
```

Environment variables work too (`APP_CORS_ALLOWED_ORIGINS=...`). The origins in
force are logged at startup. Never set this to `*` — that would let any site on
the internet call the API from a visitor's browser.

**Frontend** — the API address lives in `src/environments/`:

| File | Used by | `apiBaseUrl` |
|------|---------|--------------|
| `environment.development.ts` | `npm start` | `http://localhost:8080` |
| `environment.ts` | `npm run build` | `''` (same origin) |

The production build's empty base makes every request relative, so the site calls
whichever host serves it — the usual shape behind a reverse proxy that forwards
`/api` to the backend. That also means production needs no CORS permission at all;
CORS is a development concern here, created by serving the page and the API on
different ports.
