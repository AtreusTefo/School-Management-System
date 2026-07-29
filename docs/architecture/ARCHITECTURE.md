# School Management System - System Architecture

**Applies to:** Assignment Tracker, as built
**Runtime:** Java 25 (LTS), Spring Boot 3.5.16, Hibernate 6.6.53, Tomcat 10.1.55
**Database:** SQL Server 2019 Developer Edition - instance `MSSQLSERVER01`, database
`School Management System`, TCP port 14333
**Last updated:** 29 July 2026

> This document describes the system that exists. Anything planned but not built is
> marked as such, or lives in `docs/project/PRD.md` section 7. A previous version of this file
> described an ASP.NET / Entity Framework / SQL Server application with teacher,
> student and admin portals; none of that was ever part of this repository.

---

## 1. High-Level Overview

The system is a strictly layered Spring Boot REST API with a decoupled Angular
single-page frontend. The two halves share nothing but an HTTP/JSON contract and can
be built, run and deployed independently.

```
+------------------------------------------------------------------+
|                        Angular 18 SPA                            |
|              (standalone components - no NgModule)               |
+------------------------------------------------------------------+
|  AppComponent      table of assignments, create form, error banner|
+------------------------------------------------------------------+
|  AssignmentService the only place that knows the API URLs         |
+------------------------------------------------------------------+
|  HttpClient        API base URL from environment.ts (build config) |
+------------------------------------------------------------------+
                          HTTP / JSON
+------------------------------------------------------------------+
|        Spring Boot 3.5.16 on embedded Tomcat, port 8080          |
+------------------------------------------------------------------+
|  controller/   AssignmentController      HTTP only, no rules      |
|                GlobalExceptionHandler    exception -> status code |
+------------------------------------------------------------------+
|  service/      AssignmentService         business rules,          |
|                                          transaction boundary     |
+------------------------------------------------------------------+
|  repository/   AssignmentRepository      Spring Data JPA          |
+------------------------------------------------------------------+
|  model/        Assignment, AssignmentStatus                       |
|                constraints, optimistic locking                    |
+------------------------------------------------------------------+
|  Hibernate 6.6 + Flyway migrations                                |
+------------------------------------------------------------------+
|  SQL Server 2019 - [School Management System] on port 14333       |
+------------------------------------------------------------------+
```

### A note on Entity Framework

An instruction during this project asked for **Entity Framework 6.4** with an
`ApplicationDbContext`. EF is a .NET technology and cannot run on a Java backend;
that wording came from the inherited documentation describing an unrelated ASP.NET
application. **JPA/Hibernate fills exactly the same role here** and is equally
code-first - the schema derives from the `@Entity` classes, and `AssignmentRepository`
is the direct counterpart of a `DbSet`. The SQL Server requirement was met without a
rewrite.

### The layering rule

Requests flow down, data flows up, and **each layer calls only the layer directly
beneath it**.

| Layer | Responsibility | Must never |
|-------|----------------|-----------|
| Controller | Receive HTTP, delegate, return JSON | Contain business rules or persistence calls |
| Service | Business rules, validation, transaction boundary | Reference HTTP types or write SQL |
| Repository | Data access | Contain business logic |
| Model | Entity shape and its constraints | Contain orchestration |

This is the point of the project as much as the feature set is, so a change that
blurs a boundary is a defect even if it works.

---

## 2. Backend

### Folder structure

```
backend/
├── mvnw, mvnw.cmd, .mvn/            # Maven Wrapper - no system Maven required
├── pom.xml
├── docs/                            # Project documentation
└── src/main/
    ├── java/com/example/tracker/
    │   ├── TrackerApplication.java   # Entry point, seeds sample data
    │   ├── model/
    │   │   ├── Assignment.java       # Entity + constraints + @Version
    │   │   └── AssignmentStatus.java # IN_PROGRESS | SUBMITTED
    │   ├── repository/
    │   │   └── AssignmentRepository.java
    │   ├── service/
    │   │   └── AssignmentService.java
    │   ├── controller/
    │   │   └── AssignmentController.java
    │   ├── config/
    │   │   └── CorsConfig.java        # CORS origins, read from configuration
    │   └── exception/
    │       ├── AssignmentNotFoundException.java
    │       └── GlobalExceptionHandler.java
    └── resources/application.properties
```

### Dependencies

| Dependency | Purpose |
|------------|---------|
| `spring-boot-starter-web` | REST controllers, embedded Tomcat, JSON |
| `spring-boot-starter-data-jpa` | Repositories, Hibernate, transactions |
| `spring-boot-starter-validation` | `@Valid`, `@NotBlank`, `@Size` |
| `com.h2database:h2` | In-memory database (runtime scope) |

There is no Swagger, AutoMapper, FluentValidation equivalent, mapping layer, DTO
assembler, or logging framework beyond Spring Boot's default.

---

### 2.1 Presentation Layer

#### AssignmentController (`/api/assignments`)

```
GET  /api/assignments               -> List<Assignment>   200
POST /api/assignments               -> Assignment         200  (400 on invalid)
PUT  /api/assignments/{id}/submit   -> Assignment         200  (400 / 404 / 409)
```

- Declares **no** CORS annotation. Permitted origins come from configuration via
  `CorsConfig` (see 2.7), so the frontend's address is not compiled into the backend.
- Accepts a nested `CreateAssignmentRequest` DTO carrying only `title`, so a client
  cannot set `id` or `status`. Assigning those is the service's job.
- `@Valid` on the request body activates the DTO's constraints. Without it the
  annotations are inert and invalid input reaches the service, where it used to
  surface as a 500.

#### GlobalExceptionHandler

A `@RestControllerAdvice` that maps exceptions to status codes. Without it, Spring
answers `500` for every service exception - which is what the system did before it
existed.

| Exception | Status | Meaning |
|-----------|--------|---------|
| `MethodArgumentNotValidException` | 400 | `@Valid` rejected the body |
| `IllegalArgumentException` | 400 | Service rejected the input, or a non-numeric id |
| `AssignmentNotFoundException` | 404 | No such id |
| `IllegalStateException` | 409 | Already submitted |
| `OptimisticLockingFailureException` | 409 | Someone else changed the row first |
| `DataIntegrityViolationException` | 400 | The database refused the row |

All handlers emit the same `ApiError` record, so the client parses one shape:

```json
{ "timestamp": "2026-07-28T09:52:13Z",
  "status": 404,
  "error": "Not Found",
  "message": "No assignment found with id = 999",
  "path": "/api/assignments/999/submit" }
```

Driver text is deliberately not echoed to the client for constraint violations, since
it exposes table and column names. That detail belongs in the server log.

---

### 2.2 Business Layer

#### AssignmentService

| Method | Transaction | Rules applied |
|--------|-------------|---------------|
| `getAllAssignments()` | `readOnly = true` | None - returns all rows |
| `createAssignment(title)` | read-write | Title must be non-blank; trimmed; status forced to `IN_PROGRESS` |
| `submitAssignment(id)` | read-write | Id must be non-null; assignment must exist; must not already be `SUBMITTED` |

The class is annotated `@Transactional(readOnly = true)` with write methods
overriding it. **The transaction boundary is the whole business operation, not the
individual repository calls.** `submitAssignment` reads, checks, then writes; without
a surrounding transaction those steps are separately committed and two callers can
both pass the check before either writes.

`submitAssignment` mutates the managed entity and lets Hibernate write at commit,
rather than calling `save()` explicitly.

---

### 2.3 Data Access Layer

`AssignmentRepository extends JpaRepository<Assignment, Long>` - an empty interface.
Spring Data generates the implementation at runtime, supplying `findAll`, `findById`,
`save`, `count` and `deleteById`. Custom queries would be declared here.

---

### 2.4 Model Layer and Database Schema

#### Entity

```java
@Entity
class Assignment {
    @Id @GeneratedValue(strategy = IDENTITY)      Long id;
    @NotBlank @Size(max = 200)
    @Column(nullable = false, length = 200)       String title;
    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)        AssignmentStatus status;
    @Version @JsonIgnore                          Long version;
}
```

#### The schema, as the database actually holds it

```
app_user.id             bigint         NOT NULL   PK, identity
app_user.username       nvarchar(50)   NOT NULL   UNIQUE
app_user.password_hash  nvarchar(100)  NOT NULL   BCrypt, never exposed
app_user.role           nvarchar(20)   NOT NULL   CHECK in ('STUDENT','TEACHER')
app_user.version        bigint         NULL       optimistic lock counter

assignment.id           bigint         NOT NULL   PK, identity
assignment.version      bigint         NULL       optimistic lock counter
assignment.title        nvarchar(200)  NOT NULL   CHECK len(trim(title)) > 0
assignment.status       nvarchar(20)   NOT NULL   CHECK in ('IN_PROGRESS','SUBMITTED')
assignment.owner_id     bigint         NOT NULL   FK -> app_user(id), ON DELETE NO_ACTION
assignment.due_date     date           NULL       null means "no deadline"
```

**The `status` CHECK constraint is not decoration - it replaced something that was
lost in the move.** On H2 the column was a native `enum ('IN_PROGRESS','SUBMITTED')`,
a type that refuses anything else. SQL Server has no ENUM type, so Hibernate maps
`@Enumerated(STRING)` to a plain `nvarchar` - and a plain `nvarchar` would accept
`'banana'` happily. Writing the constraint explicitly is what kept the guarantee true
across the migration rather than silently weakening it.

#### Referential integrity

`assignment.owner_id` is the system's first foreign key, and it is declared with
**`ON DELETE NO_ACTION`** rather than a cascade. Deleting an account that still owns
assignments is refused by the database. Destroying somebody's work as a side effect of
removing their account should be an explicit decision taken in the service, not
something the schema does quietly.

Verified by writing directly through `sqlcmd`, bypassing the application entirely:

| Attempt | Result |
|---|---|
| Assignment with `owner_id = 99999` | Rejected - `fk_assignment_owner` |
| Assignment with `owner_id = NULL` | Rejected - NOT NULL |
| Delete an account that owns work | Rejected - REFERENCE constraint |
| `status = 'banana'` | Rejected - `ck_assignment_status` |
| `role = 'PRINCIPAL'` | Rejected - `ck_app_user_role` |
| Duplicate username | Rejected - `uq_app_user_username` |
| Whitespace-only title | Rejected - `ck_assignment_title_not_blank` |
| 300-character title | Rejected - would be truncated |

That last row matters more than it looks: silent truncation would be the worst
outcome, because the row would be saved and the data quietly wrong.

**Why the constraints are duplicated in Java and in the schema.** Bean validation
(`@NotBlank`, `@Size`) refuses a bad object inside this application. The column
constraints refuse the row regardless of which application writes it. Only the second
makes the rule true of the *data*; the first is policy that holds while every writer
goes through this code.

**Why `EnumType.STRING`.** Ordinal storage records a position, so reordering or
inserting a constant silently reinterprets every existing row. Storing the name keeps
old rows meaningful and readable in the database console.

---

### 2.4a Security

| Concern | Choice | Why |
|---------|--------|-----|
| Passwords | BCrypt, strength 10 | Deliberately slow and individually salted. A fast hash like SHA-256 is the wrong tool - being fast is the problem |
| Session | Cookie, `IF_REQUIRED` | Established at sign-in; later requests carry no credentials |
| CSRF | Enabled, cookie-based token | Authentication rides on a cookie, and browsers attach cookies regardless of which site triggered the request |
| Unauthenticated | `401` and nothing else | The default is a redirect to a login PAGE, which would hand a `fetch()` a chunk of HTML and a `200` |
| Roles | `TEACHER`, `STUDENT` | Enforced in the SERVICE, so the rule holds for any caller, not just the UI |

**Where authority is decided.** In `AssignmentService`, not the controller. "May this
person do this?" is a business rule; putting it at the web layer would let a scheduled
job, a test, or a new endpoint bypass it entirely.

**Why some refusals are `404` rather than `403`.** A student asking for another
person's assignment is told "not found". Answering "forbidden" would confirm the id
exists and belongs to somebody, letting an outsider map the data by probing ids. Where
the caller can already legitimately see the row, `403` is used instead - hiding it
would be pointless and the honest answer is more useful.

---

### 2.5 Concurrency Control

The `@Version` column is the guard against two people changing the same row at once.
Hibernate increments it on every update and appends `AND version = ?` to the
statement; a transaction that arrives second matches zero rows and fails, rather than
overwriting the first silently.

This addressed a measured defect. Firing 15 simultaneous submissions at one
assignment:

| Build | Accepted (expected: 1) |
|-------|------------------------|
| Before `@Transactional` + `@Version` | 2 to 10, violated in 5 of 5 rounds |
| After | exactly 1, in 5 of 5 rounds |

The service's own `if (status == SUBMITTED)` check still handles the ordinary case and
produces the clearer message. The version column exists for the narrow window between
that check and the commit.

---

### 2.6 Configuration

| Setting | Value | Reason |
|---------|-------|--------|
| `spring.datasource.url` | `jdbc:sqlserver://localhost:14333;databaseName=School Management System` | Persistent. TCP had to be enabled on the instance first - only Shared Memory was on, and JDBC cannot use it |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate may CHECK the schema but never change it. Every structural change is a Flyway migration with a version and a history |
| `spring.flyway.enabled` | `true` | Migrations in `db/migration`, applied in order, recorded in `flyway_schema_history` |
| `spring.jpa.open-in-view` | `false` | Closes the persistence context when the service returns, instead of holding a connection through view rendering |
| `app.cors.allowed-origins` | `http://localhost:4200,http://127.0.0.1:4200` | Which origins may call the API. Read by `CorsConfig`; see 2.7 |

**Why the port is pinned to 14333.** `MSSQLSERVER01` is a *named* instance, and named
instances use dynamic ports that change on every restart. Without a static port the
SQL Browser service would have to be running to locate the instance, and the JDBC URL
would stop working after a reboot.

**Migrations**

| Version | Contents |
|---------|----------|
| `V1__baseline_assignment.sql` | The assignment table, with CHECK constraints for status and blank titles |
| `V2__accounts_and_ownership.sql` | `app_user`, the ownership foreign key, and `due_date` |

V2 is written as separate batches with `GO`. SQL Server compiles an entire batch
before executing any of it, so adding a column and using it in the same batch fails
with "Invalid column name" even though the ALTER would have created it first. That is
a SQL Server behaviour, not a Flyway one.

---

### 2.7 Cross-Origin Configuration

`CorsConfig` registers a `CorsFilter` for `/api/**`, with the permitted origins read
from `app.cors.allowed-origins`. Nothing about the frontend's address is compiled
into the backend, so one artefact serves every environment:

```bash
java -jar tracker.jar --app.cors.allowed-origins=https://tracker.example.com
APP_CORS_ALLOWED_ORIGINS=https://tracker.example.com java -jar tracker.jar
```

The origins in force are logged at startup, so there is no guessing which
configuration source won.

**Why a filter rather than `WebMvcConfigurer`.** The usual implementation overrides
`addCorsMappings`, whose parameter Spring declares `@NonNull`; an override that does
not repeat the annotation produces an unchecked-conversion warning on every build.
A filter overrides nothing, so the contract cannot be broken. It also runs earlier in
the chain, which matters if authentication is added in front of the controllers.

Both loopback spellings are listed by default because `localhost` and `127.0.0.1` are
the same machine but **not** the same origin - a page served from one was refused
while the identical page from the other worked, and the browser reports that refusal
to the client as `status 0`, indistinguishable from the server being switched off.

The list stays explicit. `*` would let any site on the internet call this API from a
visitor's browser.

---

## 3. Frontend

### Folder structure

```
frontend/tracker-ui/
├── angular.json, package.json, tsconfig*.json
└── src/
    ├── main.ts                     # bootstrapApplication + provideHttpClient
    ├── index.html
    ├── environments/
    │   ├── environment.ts          # production - apiBaseUrl '' (same origin)
    │   └── environment.development.ts  # dev - apiBaseUrl http://localhost:8080
    └── app/
        ├── assignment.service.ts   # Assignment, AssignmentStatus, HTTP calls
        ├── app.component.ts        # state, actions, error handling
        └── app.component.html      # error banner, create form, table
```

`angular.json` swaps `environment.ts` for `environment.development.ts` under the
`development` configuration, which `npm start` uses; `npm run build` keeps the
production file. The API address is therefore a build input, not source code - the
production bundle contains no `localhost` reference at all.

There is one component and one service. `app.config.ts` and `app.component.css` were
removed because nothing referenced them.

### AssignmentService

The only place that knows the backend's URLs; components never call `HttpClient`
directly. This mirrors the backend rule that only the repository touches the database.

The base address comes from `environment.apiBaseUrl` rather than a literal, so
pointing the app at another backend is a build setting rather than a code change.

```typescript
type AssignmentStatus = 'IN_PROGRESS' | 'SUBMITTED';   // mirrors the Java enum
interface Assignment { id: number; title: string; status: AssignmentStatus; }

private readonly baseUrl = `${environment.apiBaseUrl}/api/assignments`;

getAssignments()      : Observable<Assignment[]>   // GET
createAssignment(t)   : Observable<Assignment>     // POST { title }
submitAssignment(id)  : Observable<Assignment>     // PUT  /{id}/submit
```

The status union is deliberate: a typo such as `'SUBMITED'` becomes a compile error
rather than a comparison that never matches and leaves a button permanently enabled.

### AppComponent

| Member | Purpose |
|--------|---------|
| `assignments` | The rendered list |
| `newTitle` | Two-way bound to the create input |
| `errorMessage` | Drives the banner; `null` when healthy |
| `loadAssignments()` | Fetch and replace the list |
| `onCreate()` | Trim, guard against blank, POST, reload |
| `onSubmit(id)` | PUT, reload |
| `retry()` | Clear the banner and re-fetch the list |
| `clearError()` | Dismiss the banner |
| `showError(err, fallback)` | Turn a failure into one readable sentence |

`retry()` exists because the list is fetched once, at page load. If the backend was
down at that moment - most often mid-restart - the table stayed empty permanently
even after the backend returned, and the only recovery was a page refresh that
nothing on screen suggested.

Every `subscribe()` supplies an error callback. Before that, a failed request did
nothing visible and a click that failed looked identical to one that was ignored.

`showError` prefers the server's `message` field over a status number, and treats
`status === 0` as a distinct case - the request never reached the server - so the
banner can say the backend is unreachable instead of showing a meaningless code.

---

## 4. Data Flow

### Load the list
```
Page opens -> AppComponent.ngOnInit -> loadAssignments()
  -> AssignmentService.getAssignments() -> GET /api/assignments
  -> AssignmentController.getAllAssignments()
  -> AssignmentService.getAllAssignments()   [readOnly transaction]
  -> AssignmentRepository.findAll()          -> SELECT
  -> JSON array -> table renders
```

### Create an assignment
```
User types a title, presses Add (button disabled while blank)
  -> POST /api/assignments { "title": "Science Lab" }
  -> @Valid checks @NotBlank / @Size          -> 400 if it fails
  -> AssignmentService.createAssignment()     [transaction opens]
       trims the title, forces status IN_PROGRESS
  -> repository.save()                        -> INSERT, id generated
  -> 200 + created object -> frontend reloads the list
```

### Submit an assignment
```
User clicks Submit on a row (disabled when already SUBMITTED)
  -> PUT /api/assignments/3/submit
  -> AssignmentService.submitAssignment(3)    [transaction opens]
       id null?            -> IllegalArgumentException      -> 400
       not found?          -> AssignmentNotFoundException   -> 404
       already SUBMITTED?  -> IllegalStateException         -> 409
       otherwise           -> status = SUBMITTED
  -> commit: UPDATE ... WHERE id = ? AND version = ?
       zero rows matched   -> OptimisticLockingFailure      -> 409
  -> 200 + updated object -> frontend reloads the list
```

### Two tabs, one assignment
```
Tab A and Tab B both show it as IN_PROGRESS
Tab B submits    -> 200, version 0 -> 1
Tab A submits    -> its check passes or its UPDATE matches nothing
                 -> 409 "already been submitted" or "changed by someone else"
Tab A banner     -> shows the server's message, list reloads on next action
```

---

## 5. Security Posture

### As built - development only
```
No authentication and no user identity of any kind
Anyone who can reach the page has full control of the shared list
CORS restricted to an explicit allow-list (loopback by default, configurable)
No HTTPS
No rate limiting
H2 console exposed at /h2-console with default credentials
```

The absence of authentication is a deliberate scope decision recorded as `docs/project/PRD.md` L2,
not an oversight. It is also why the system must not be deployed anywhere public in
its current form.

### Required before any real deployment
```
Authentication and server-enforced roles      (PRD R3)
Password hashing - bcrypt or Argon2
HTTPS/TLS
Disable the H2 console
Rate limiting
A persistent database with migrations         (PRD R1)
```

CORS is no longer on this list: the allow-list is already configuration-driven, so a
deployment sets its own origins without a rebuild.

---

## 6. Deployment

### Development - the only supported mode today
```
Backend:  ./mvnw spring-boot:run          port 8080
Frontend: npm start                       port 4200
Database: H2 in-memory, created and dropped with the process
```
Start the backend first. If it is not up, the page reports it in a banner.

### Production
Not supported, but no longer for configuration reasons. Both halves now take their
environment-specific values as input rather than compiling them in:

```bash
# backend - same jar, any host and any permitted origin
java -jar tracker.jar --server.port=8081 \
     --app.cors.allowed-origins=https://tracker.example.com

# frontend - production build calls whichever host serves it
npm run build
```

What still blocks production is the security list above and the in-memory database,
which loses all data on restart (`docs/project/PRD.md` L1, R1).

---

## 7. Known Architectural Limitations

| | Limitation | Consequence |
|---|-----------|-------------|
| ~~A1~~ | ~~In-memory database~~ | **Resolved.** SQL Server 2019 with Flyway migrations; verified data survives a JVM restart |
| ~~A2~~ | ~~No authentication~~ | **Resolved.** Session auth, BCrypt, CSRF, two roles |
| ~~A3~~ | ~~Single table, no relationships~~ | **Resolved.** `assignment.owner_id` is a real foreign key. Classes, terms and subjects still do not exist |
| ~~A4~~ | ~~Hard-coded API base URL in the frontend~~ | **Resolved.** The base URL comes from `environment.apiBaseUrl`, and CORS origins from `app.cors.allowed-origins`. Neither half compiles the other's address in. |
| ~~A5~~ | ~~No automated tests~~ | **Resolved.** 36 tests run as part of the build |
| A6 | No mapping layer on responses | The entity is serialised directly; a new column is exposed unless explicitly hidden, as `version` and `owner` are |
| ~~A7~~ | ~~Submission is one-way~~ | **Resolved.** A teacher can reopen a submitted assignment |
| A8 | Development credentials are seeded | `teacher` and `student` share `password123`, reset at every startup |
| A9 | Tests run on H2, not SQL Server | The migrations themselves are not exercised by the suite; `ddl-auto=validate` catches drift instead |

---

## 8. Summary

The architecture delivers:

- **Separation of concerns** - four backend layers with enforced direction of
  dependency, mirrored on the frontend by a service that owns all API knowledge.
- **Integrity at the storage layer** - constraints in the schema, not only in Java, so
  they hold regardless of which application writes.
- **Correctness under concurrency** - one transaction per business operation plus
  optimistic locking, verified by measurement rather than assumed.
- **Honest failures** - each error path returns an accurate status and a message a
  person can act on, surfaced by the interface rather than swallowed.
- **Readability** - the codebase is small and commented to be taught from, which is a
  stated goal rather than an accident.

It now also delivers **persistence** on SQL Server with versioned migrations,
**authentication and roles** with hashed passwords and CSRF protection, **referential
integrity** through a real foreign key with deliberate delete semantics, and an
**automated test suite** of 36 tests that runs as part of the build.

What it still does not deliver: account self-service, any grouping above an
assignment (classes, terms, subjects), CI, or tests that exercise the SQL Server
migrations themselves. Those are recorded in `docs/project/PRD.md` and should not be
assumed present.
