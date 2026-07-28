# School Management System — Assignment Tracker (JAVA SPRING BOOT)

A minimal full-stack prototype demonstrating a **layered Spring Boot backend**
and an **Angular frontend**. Beginner-friendly, heavily commented.

## What it does
- Lists assignments in a table (title + status)
- **Create** a new assignment via a form (POST)
- **Submit** an assignment, flipping its status to `SUBMITTED` (PUT)

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
Angular  ──HTTP──►  Controller ──► Service ──► Repository ──► H2 Database
(browser)          (web layer)   (rules)     (data access)   (storage)
```
Each layer only talks to the one directly beneath it.

## API endpoints
| Method | URL                              | Purpose                         |
|--------|----------------------------------|---------------------------------|
| GET    | `/api/assignments`               | List all assignments            |
| POST   | `/api/assignments`               | Create one (body: `{"title":"..."}`) |
| PUT    | `/api/assignments/{id}/submit`   | Mark one as SUBMITTED           |

### Error responses
Failures return the status code that matches the problem, plus a readable message:

| Situation                          | Status | Example message                          |
|------------------------------------|--------|------------------------------------------|
| Blank or missing `title`            | 400    | `title: Title must not be blank`         |
| Unknown assignment id               | 404    | `No assignment found with id = 999`      |
| Submitting something already sent   | 409    | `Assignment 3 has already been submitted.` |

Every error body has the same shape, so the frontend only parses one thing:
```json
{ "timestamp": "...", "status": 404, "error": "Not Found",
  "message": "No assignment found with id = 999", "path": "/api/assignments/999/submit" }
```

## Run the backend
Requires **Java 17 or newer**. Maven does *not* need to be installed — the
project ships the Maven Wrapper (`mvnw`), which fetches the right Maven itself.

```bash
cd "School Management System/backend"
./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```
- API runs at http://localhost:8080
- Check it: open http://localhost:8080/api/assignments (should show JSON)
- DB console: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:trackerdb`)

**Java 17 is a hard requirement** — Spring Boot 3.x will not start on Java 8 or
11. Check with `java -version`; if it's older, install a JDK 17+ and point
`JAVA_HOME` at it before running `mvnw`.

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
isn't up, the page now says so in a red banner instead of sitting there blank.

If `node_modules/` is ever missing or broken, restore it with `npm install` in
`frontend/tracker-ui`.
