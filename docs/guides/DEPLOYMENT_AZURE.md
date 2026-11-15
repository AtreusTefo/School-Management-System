# Deploying to Azure App Service (free tier)

This guide puts the whole application online as one Azure App Service running
the jar under the `prod` profile. The Angular build is packaged into the jar and
served by the backend itself (`SpaWebConfig`), so the browser calls `/api` on
the same address and no CORS or second host is involved.

| Part | Azure service | Plan |
|------|---------------|------|
| Backend and Angular site | App Service, Linux, Java 25, Java SE (embedded web server) | Free (F1) |
| Database | Azure SQL Database | Free offer (serverless) |
| Build and deploy | GitHub Actions, `.github/workflows/azure-app-service.yml` | Free for public repositories |

## 1. Create the database

1. In the Azure portal, create a **SQL database** and choose **Apply offer** on
   the free offer banner. An existing logical server can be reused.
2. Under **Behavior when free limit reached**, choose **Auto-pause the database
   until next month**. This is what keeps it free.
3. Make sure the server allows **Azure services and resources to access this
   server**.

Flyway creates the whole schema on the first start. There is nothing to run by
hand.

## 2. Create the Web App

**Publish**: Code. **Runtime stack**: Java 25. **Java web server stack**: Java SE
(Embedded Web Server). **Operating system**: Linux. **Pricing plan**: Free F1.

Then, in **Settings > Environment variables > App settings**, add:

| Name | Value |
|------|-------|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | `jdbc:sqlserver://<server>.database.windows.net:1433;databaseName=<database>;encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=60` |
| `DB_USERNAME` | the SQL login |
| `DB_PASSWORD` | its password |
| `APP_CORS_ALLOWED_ORIGINS` | the site's own origin, `https://<app-host>` |
| `RESET_LINK_BASE` | `https://<app-host>/reset-password` |
| `MAIL_HOST` | an SMTP server, plus `MAIL_USERNAME` / `MAIL_PASSWORD` if it needs them |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | a temporary password of at least 12 characters - see step 4 |
| `SPRING_FLYWAY_CONNECT_RETRIES` | `10` |
| `SERVER_PORT` and `WEBSITES_PORT` | `8080` |

Notes on the less obvious ones:

- `SPRING_FLYWAY_CONNECT_RETRIES`: the free database pauses when idle, and the
  first connection to a paused database is refused while it resumes. Without
  retries, Flyway gives up on that first refusal and the application does not
  start.
- `SERVER_PORT` and `WEBSITES_PORT` are set to the same value so the port the
  jar listens on and the port App Service forwards to cannot disagree.
- `MAIL_HOST` is required by the `prod` profile (see the comment in
  `application-prod.properties`). With no working mail server, a reset email
  fails and is logged as an error; the link itself is never written to the log.

## 3. Connect GitHub Actions

1. In the Web App, open **Settings > Configuration** and switch on **SCM Basic
   Auth Publishing Credentials**.
2. On the **Overview** page, choose **Download publish profile**.
3. In the repository, **Settings > Secrets and variables > Actions**:
   - secret `AZURE_WEBAPP_PUBLISH_PROFILE`: the whole downloaded file
   - variable `AZURE_WEBAPP_NAME`: the Web App name
4. Push to `main`, or run the **deploy** workflow by hand.

The workflow runs the full test suite before packaging, so a commit that fails
its tests is never deployed.

## 4. Sign in for the first time

Under `prod` no seed account exists. `FirstAdminBootstrap` creates one account,
`admin`, from `APP_BOOTSTRAP_ADMIN_PASSWORD` - only while no administrator
exists - and marks it as having to change its password.

1. Sign in as `admin` with that password. The application forces a password
   change immediately.
2. **Delete `APP_BOOTSTRAP_ADMIN_PASSWORD`** from the App settings. Once an
   admin exists it has no effect, and the application logs a warning on every
   start while it is still set.
3. Create teachers and students from the admin panel.

## 5. Known limits of the free tier

- **Cold starts.** F1 has no Always On and the free database pauses when idle.
  The first request after a quiet period can take a minute or more.
- **CPU quota.** F1 allows 60 CPU minutes a day. A demo stays well inside it,
  but every cold start of the JVM spends some of it.
- **Uploaded PDFs** are stored in the database, so they survive redeploys.
- **Public data.** Anyone with the link can reach the sign-in page. Use made-up
  students and teachers only. The privacy policy and terms still carry the
  bracketed placeholders described in `CLAUDE.md`.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| Application refuses to start, naming missing variables | `ProductionConfigCheck` - add the variables it lists |
| `Login failed` or a network error at startup | wrong `DB_PASSWORD`, or the SQL server does not allow Azure services |
| Start fails once, then succeeds | the database was paused; `SPRING_FLYWAY_CONNECT_RETRIES` should absorb this |
| Deploy step fails with 401 | SCM Basic Auth is off, or the publish profile predates switching it on |
| Signed in, but every later request is 401 | the site is being reached over plain `http`; `prod` cookies are `Secure` |
