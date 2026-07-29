# School Management System - System Architecture

**Applies to:** Assignment Tracker, as built
**Runtime:** Java 25 (LTS), Spring Boot 3.5.16, Hibernate 6.6.53, Tomcat 10.1.55
**Last updated:** 28 July 2026

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
|  Hibernate 6.6 -> H2 in-memory database (jdbc:h2:mem:trackerdb)   |
+------------------------------------------------------------------+
```

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

#### Generated DDL

```sql
create table assignment (
  id      bigint generated by default as identity,
  version bigint,
  title   varchar(200) not null,
  status  enum ('IN_PROGRESS','SUBMITTED') not null,
  primary key (id)
)
```

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | bigint | PK, identity | Server-assigned; a client cannot set it |
| `title` | varchar(200) | NOT NULL | Trimmed by the service before saving |
| `status` | enum | NOT NULL, restricted to two values | Stored as text, not ordinal |
| `version` | bigint | - | Optimistic lock counter; hidden from the API |

**Why the constraints are duplicated in Java and in the schema.** Bean validation
(`@NotBlank`, `@Size`) refuses a bad object inside this application. The column
constraints refuse the row regardless of which application writes it. Only the second
makes the rule true of the *data*; the first is policy that holds while every writer
goes through this code.

**Why `EnumType.STRING`.** Ordinal storage records a position, so reordering or
inserting a constant silently reinterprets every existing row. Storing the name keeps
old rows meaningful and readable in the database console.

#### Referential integrity

There is one table and no foreign keys, so there is presently nothing for referential
integrity to enforce. It becomes relevant when the entities in `docs/project/PRD.md` R3 (accounts)
and R4 (due dates) arrive; those relationships should be declared with explicit
foreign keys and deliberate `ON DELETE` semantics rather than application-side
cleanup.

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
| `spring.datasource.url` | `jdbc:h2:mem:trackerdb` | In-memory; wiped on shutdown |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` | Guarantees the schema matches the entities. `update` only ever adds and will not tighten an existing column, so constraints could silently drift |
| `spring.jpa.open-in-view` | `false` | Closes the persistence context when the service returns, instead of holding a connection through view rendering |
| `spring.h2.console.enabled` | `true` | Browse real rows at `/h2-console` |
| `app.cors.allowed-origins` | `http://localhost:4200,http://127.0.0.1:4200` | Which origins may call the API. Read by `CorsConfig`; see 2.7 |

On a persistent database, `ddl-auto` should be `validate` with schema changes managed
by migrations.

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
| A1 | In-memory database | All data lost on restart |
| A2 | No authentication | No per-user views, no authority rules |
| A3 | Single table, no relationships | No classes, terms, subjects or ownership |
| ~~A4~~ | ~~Hard-coded API base URL in the frontend~~ | **Resolved.** The base URL comes from `environment.apiBaseUrl`, and CORS origins from `app.cors.allowed-origins`. Neither half compiles the other's address in. |
| A5 | No automated tests | Every change is re-verified by hand |
| A6 | No mapping layer on responses | The entity is serialised directly; a new column is exposed unless explicitly hidden, as `version` is |
| A7 | Submission is one-way | No route from `SUBMITTED` back to `IN_PROGRESS` |

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

It does not deliver authentication, persistence, relationships, or automated tests.
Those are recorded in `docs/project/PRD.md` and should not be assumed present.
