# School Management System — Assignment Tracker (JAVA SPRING BOOT)

A minimal full-stack prototype demonstrating a **layered Spring Boot backend**
and an **Angular frontend**. Beginner-friendly, heavily commented.

## What it does
- **Sign in** as a teacher or a student
- **Subjects, classes and courses** — a course is one subject taught to one class
  by one teacher. A teacher can take several subjects and several classes, so a
  student is taught several subjects by several teachers
- **Set work for a whole class at once**, or for several classes in one action —
  every enrolled student gets their own copy to hand in
- **Upload a PDF** against a piece of work, replace it freely, then **hand it in**
- **Download the PDF to mark it** — the teachers who take that course, nobody else
- **Reopen** handed-in work so a student can correct it (teachers only)
- **Edit and delete** work that was set — refused once anybody has handed in
- **Due dates**, with `OVERDUE` shown for work past its deadline
- **Record marks**, with the percentage and performance level worked out
  automatically. A teacher enters "34 out of 50"; the system does the rest
- **A marks report** with sorting, search and **Export PDF** — for a teacher
  across the courses they take, and for a student over their own results
- **Performance per subject**: totals, percentage and level, derived from the
  marks and stored nowhere
- **Sort, search and filter** the list, with a summary of what is outstanding
- **Change your own password**, and **create student accounts** (teachers only) that
  must have their temporary password replaced at first sign-in

Development accounts, all with password `password123`:

| Account | Role | Involved in |
|---------|------|-------------|
| `teacher` | TEACHER | Mathematics for 10A **and** 10B, History for 10A |
| `teacher2` | TEACHER | Science for 10A |
| `student` | STUDENT | Grade 10A |
| `student2` | STUDENT | Grade 10A |
| `student3` | STUDENT | Grade 10B |

That roster is the smallest one in which every relationship is visible: `student`
is taught three subjects by two teachers, and `teacher` runs two classes. Seed
data that cannot exercise the model is seed data that hides defects.

### A five-minute walkthrough

Start the backend, then the frontend, and open http://localhost:4200.

1. Sign in as **`student`**. *What I am taught* names the subjects and the
   teachers — several of each, from one course list rather than a special query.
2. Pick a row, click **Upload**, choose any PDF. The file name, size and a
   SHA-256 checksum appear. Try a `.txt` renamed to `.pdf`: it is refused, because
   the server reads the first five bytes rather than trusting the name.
3. **Hand in** is disabled until a file is there — "submitted" with nothing
   attached would be a claim with no evidence. Click it once a PDF is uploaded;
   the row becomes HANDED IN and **Upload** goes dead, so the marked document
   cannot change afterwards.
4. **Sign out** and sign in as **`teacher`**. The marking queue shows every
   student's work across their courses — and only theirs. **Download** the PDF.
5. Click **Set work**, tick *both* Mathematics classes, and set it. One action,
   two assignments, one row per enrolled student: "Set for 2 classes, reaching 3
   students."
6. Try to delete work somebody has handed in. Refused with `409` — reopen it
   first, so deletion is two deliberate acts rather than one careless click.
7. Click **Timetable** to add a subject, a class or a course, and to enrol a
   student. Enrolling a *teacher* is refused by the database itself, not just by
   the form — see [Data integrity](#data-integrity-is-enforced-by-the-database).
8. Visit http://localhost:8080/swagger-ui.html to see the same API described and
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
│       │   ├── TrackerApplication.java  # startup + seed: accounts, timetable, work
│       │   ├── model/          # MODEL layer - the ten entities
│       │   │   ├── AppUser.java, Role.java
│       │   │   ├── Subject.java, SchoolClass.java, Enrolment.java, Course.java
│       │   │   ├── Assignment.java, Submission.java, SubmissionFile.java
│       │   │   └── Assessment.java, PerformanceLevel.java
│       │   ├── repository/     # DATA ACCESS layer - one interface per entity
│       │   ├── service/        # BUSINESS layer
│       │   │   ├── AssignmentService.java   # sets work, fans out to the class
│       │   │   ├── SubmissionService.java   # PDF upload, hand-in, download
│       │   │   ├── SchoolService.java       # subjects, classes, enrolment, courses
│       │   │   ├── AssessmentService.java   # marks, and the derived arithmetic
│       │   │   └── AppUserService.java      # who is calling, passwords
│       │   ├── controller/     # PRESENTATION layer - HTTP only, no rules
│       │   ├── dto/            # the records the API publishes, never entities
│       │   └── exception/
│       │       ├── AssignmentNotFoundException.java  # signals "no such id"
│       │       ├── ResourceNotFoundException.java    # subjects, classes, courses
│       │       └── GlobalExceptionHandler.java       # exception -> HTTP status
│       └── resources/
│           ├── application.properties
│           └── db/migration/   # Flyway, SQL Server dialect, V1 to V6
└── frontend/
    ├── tracker-ui/                 # ← THE RUNNABLE ANGULAR PROJECT (ng serve here)
    │   └── src/                    #   working copy of the files below
    └── src/                        # reference copy of the hand-written sources
        ├── main.ts
        ├── index.html
        └── app/
            ├── assignment.service.ts        # the only place that knows API URLs
            ├── csrf.interceptor.ts          # attaches X-XSRF-TOKEN cross-origin
            ├── submission-table.component.ts # DataTables owns this table
            ├── marks-table.component.ts      # the report, with PDF export
            ├── app.component.ts             # UI logic
            └── app.component.html           # the tables, forms and banners
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

## Data integrity is enforced by the database

A rule written only in Java is a **promise** — it holds while every writer goes
through this application. The same rule written into the schema is a **fact**.
Everything below was proven by writing bad data with `sqlcmd`, going around the
application entirely; all 20 attempts were refused.

### The role guard, and how it works

"Only a student can be enrolled" and "only a teacher can teach" are not checks in
a service. They are enforced by **composite foreign keys**:

```sql
-- app_user carries a unique pair
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_id_role UNIQUE (id, role);

-- and every table that references a user pins the role it requires
CONSTRAINT ck_enrolment_student_role CHECK (student_role = 'STUDENT'),
CONSTRAINT fk_enrolment_student
    FOREIGN KEY (student_id, student_role) REFERENCES app_user (id, role)
```

Neither half is sufficient alone. The `CHECK` would let a row claim `STUDENT` for
a teacher's id; the foreign key would let it claim any role. Together, the only
value satisfying both is that user's **real** role — and it must be `STUDENT`.

One consequence is worth knowing before it surprises you: **a user's role cannot
be changed while any row depends on it.** Promoting an enrolled student to
teacher is refused. That is the guarantee working — the alternative is a
"teacher" still sitting on a class register as a pupil.

### What else the schema refuses

| Attempted, bypassing the application | Refused by |
|---|---|
| Enrolling a teacher, or claiming they are a student | `CHECK` + composite FK |
| Recording a student as teaching a course | composite FK |
| Enrolling the same student in a class twice | `uq_enrolment_student_class` |
| Two submissions for one student and one assignment | `uq_submission_assignment_student` |
| `SUBMITTED` with no timestamp, or `IN_PROGRESS` with one | `ck_submission_status_time` |
| A status outside the two valid values | `ck_submission_status` |
| Coursework that is not `application/pdf` | `ck_submission_file_pdf` |
| An empty file, or one over 10 MB | `ck_submission_file_size` |
| A checksum that is not 64 characters | `ck_submission_file_sha256` |
| An assignment pointing at a course that does not exist | `fk_assignment_course` |
| Deleting a class that still has a register | `fk_enrolment_class` |
| Deleting a student who still has work | `fk_enrolment_student` |
| A blank subject name, or a duplicate subject code | `CHECK` / `UNIQUE` |
| A 300-character title | column length — **not** silent truncation |
| **A score higher than its maximum** | `ck_assessment_score_within_max` |
| A negative score, or a maximum of zero | `ck_assessment_score_not_negative` / `ck_assessment_max_positive` |
| The same named assessment twice for one student | `uq_assessment_student_course_name` |
| Marking a teacher, or a mark recorded by a student | composite FKs |
| **A mark attached to another student's submission** | `fk_assessment_submission` (composite) |
| Two marks on one submission | `uq_assessment_submission` (filtered index) |

The score rule is the one worth dwelling on. "34 out of 20" is not a high mark —
it is a corrupt row, and every average computed from it afterwards is silently
wrong. That is why it is a `CHECK` constraint and not a validation message.

The mark-ownership rule reuses the composite-key technique: `submission` carries
`UNIQUE (id, student_id)`, and `assessment` points a two-column foreign key at
it. A mark naming submission 7 and student 3 is only storable if submission 7
really is student 3's work.

> **Writing to `assessment` by hand?** Put `SET QUOTED_IDENTIFIER ON;` at the top
> of your script. The table carries a filtered index, and SQL Server refuses any
> write to such a table without it — with error 1934, which reads exactly like
> the schema rejecting your data when it is really the session. The JDBC driver
> sets it on connect, so the application is unaffected; `sqlcmd` does not.

**Consistency** is separate again: `@Transactional` service methods plus a
`@Version` column on every mutable entity. Twelve simultaneous hand-ins on one
submission yield **exactly one** acceptance; the rest get `409`. Setting work for
three classes writes one assignment and one submission per enrolled student in a
single transaction, so it cannot half-succeed and leave half a class without work.

Uploaded PDFs are stored **in the database**, not on disk. That is a consistency
decision rather than a storage preference: a file on disk plus a row pointing at
it is two writes that must be made atomic by hand, and every failure in between
leaves either a row naming a missing file or bytes nobody has a row for.

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
| GET | `/api/subjects` | List subjects |
| POST | `/api/subjects` | Add a subject — teachers only |
| GET | `/api/classes` | List classes with their sizes |
| POST | `/api/classes` | Add a class — teachers only |
| GET | `/api/classes/{id}/students` | The register — teachers only |
| POST | `/api/classes/{id}/students` | Enrol a student — teachers only |
| DELETE | `/api/classes/{id}/students/{username}` | Withdraw a student — teachers only |
| GET | `/api/courses` | Courses you teach, or are taught (scoped by role) |
| GET | `/api/courses/all` | Every course — teachers only |
| POST | `/api/courses` | Record that a teacher takes a subject for a class |
| GET | `/api/assignments` | Work that was set (scoped by role) |
| POST | `/api/assignments` | Set work for one or more courses — teachers only |
| PUT | `/api/assignments/{id}` | Edit title / description / due date — teachers only |
| DELETE | `/api/assignments/{id}` | Delete — refused once anybody has handed in |
| GET | `/api/assignments/{id}/submissions` | The marking list for one assignment |
| GET | `/api/submissions` | Your own work, or your marking queue |
| POST | `/api/submissions/{id}/file` | Upload or replace the PDF (multipart) |
| GET | `/api/submissions/{id}/file` | Download the PDF |
| PUT | `/api/submissions/{id}/submit` | Hand it in — requires an uploaded PDF |
| PUT | `/api/submissions/{id}/unsubmit` | Reopen — teachers only |
| GET | `/api/assessments` | Your own marks, or your mark book (scoped by role) |
| GET | `/api/assessments/summary` | Performance per student per subject |
| GET | `/api/assessments/course/{id}` | One course's mark book — teachers only |
| POST | `/api/assessments` | Record a mark — teachers only |
| PUT | `/api/assessments/{id}` | Correct a mark |
| DELETE | `/api/assessments/{id}` | Remove a mark |

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

85 tests: 17 unit tests of the business rules, 22 full-stack tests through MockMvc
with real security (including multipart upload and download), 18 covering
concurrency and database integrity, 16 covering marks and the scoring arithmetic,
and 12 covering account self-service. They run against H2, so **no SQL Server
instance is needed** to run the suite.

`./mvnw package` runs them too, and fails the build if any test fails.

> **Two things this suite cannot prove, stated plainly.** It runs on H2, built
> from the entity annotations, so (a) the Flyway migrations are never executed,
> and (b) JPA cannot express the composite `(id, role)` foreign keys — H2 gets
> the `CHECK` and a single-column key instead. H2 will still refuse a row that
> claims a role it may not hold; what only SQL Server refuses is a row claiming
> `STUDENT` for a *teacher's* id. Both gaps are checked by hand with `sqlcmd` and
> recorded as `docs/project/PRD.md` L10 and R10. `ddl-auto=validate` is what
> catches drift between the migrations and the entities — it earned its keep
> here, refusing to start when a timestamp column was declared `DATETIME2` while
> Hibernate expected `DATETIMEOFFSET`.

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
