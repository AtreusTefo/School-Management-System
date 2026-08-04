# Invalid runtime for JavaSE-nn: the path points to a missing or inaccessible folder

**Date:** 4 August 2026
**Updated:** 4 August 2026 - the original fix recurred on the other machine, in the
opposite direction. See "Why the previous solution failed" below before applying
anything here.
**Component:** VS Code, Language Support for Java by Red Hat
**Severity:** Editor only. The Maven build was never affected.

## Symptom

On opening the workspace, the Java language server raises one blocking notification
per configured JDK, and the status bar sticks at `Java: Searching...`:

```
Invalid runtime for JavaSE-25: The path points to a missing or inaccessible folder
(C:\Users\Developer.03\Java\jdk-25.0.4+7).

Invalid runtime for JavaSE-11: The path points to a missing or inaccessible folder
(C:\Program Files\Microsoft\jdk-11.0.12.7-hotspot).

Invalid runtime for JavaSE-1.8: The path points to a missing or inaccessible folder
(C:\Program Files\Eclipse Foundation\jdk-8.0.302.8-hotspot).

Source: Language Support for Java(TM) by Red Hat
```

A fourth entry, `JavaSE-17`, was configured with the same defect and produces the
same notification.

## Root cause

`.vscode/settings.json` declared four entries under `java.configuration.runtimes`,
every one of them a path from the machine the project was originally developed on:

| Name | Configured path | Present on this machine |
|---|---|---|
| JavaSE-25 | `C:\Users\Developer.03\Java\jdk-25.0.4+7` | No |
| JavaSE-17 | `C:\Users\Developer.03\Java\jdk-17.0.19+10` | No |
| JavaSE-11 | `C:\Program Files\Microsoft\jdk-11.0.12.7-hotspot` | No |
| JavaSE-1.8 | `C:\Program Files\Eclipse Foundation\jdk-8.0.302.8-hotspot` | No |

Two separate facts combine here.

1. **The paths belong to a different machine.** `C:\Users\Developer.03\` is another
   user profile entirely. The current machine's user is `hp`, and the only JDK
   installed on it is Temurin 25 at
   `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot`. The JDK 8 and JDK 11
   that `CLAUDE.md` describes as "also installed here" were never installed on this
   machine.

2. **The language server validates every entry, not just the one in use.** The list
   is a registry of available JDKs, so an entry pointing at a missing folder is
   reported even though this project targets only Java 25. Listing JDKs the project
   has no use for therefore costs an error each.

**Why the build was unaffected.** The language server keeps this registry entirely
separate from `JAVA_HOME`. The Maven Wrapper reads `JAVA_HOME` and never consults
`java.configuration.runtimes`, which is why `mvnw test` passed with all 36 tests while
the editor was still reporting three invalid runtimes. The reverse case is documented
in the same settings file: without a *correct* entry here, the server reports
"release 25 is not found in the system" even though the command-line build succeeds.

## Fix applied

**File:** `.vscode/settings.json`, `java.configuration.runtimes` (previously lines
27-45).

Reduced the list to the single JDK that exists on this machine, and pointed it at the
real location:

```json
"java.configuration.runtimes": [
    {
        "name": "JavaSE-25",
        "path": "C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.4.7-hotspot",
        "default": true
    }
]
```

The JavaSE-17, JavaSE-11 and JavaSE-1.8 entries were removed rather than repointed.
Nothing in this project targets them - `pom.xml` sets `<java.version>25</java.version>`
- so re-adding them would restore the errors without enabling anything.

The surrounding comment was extended to record why the list must contain only JDKs
that are actually present, and to warn that the path is machine-specific.

## Testing steps

1. Confirm the configured paths were genuinely absent:

   ```powershell
   Test-Path "C:\Users\Developer.03\Java\jdk-25.0.4+7"                    # False
   Test-Path "C:\Users\Developer.03\Java\jdk-17.0.19+10"                  # False
   Test-Path "C:\Program Files\Microsoft\jdk-11.0.12.7-hotspot"           # False
   Test-Path "C:\Program Files\Eclipse Foundation\jdk-8.0.302.8-hotspot"  # False
   Test-Path "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"     # True
   ```

2. Confirm the edited file is still valid JSON and its one runtime resolves. Output
   was `JSON valid` / `runtimes configured: 1` / `JavaSE-25 -> exists=True`:

   ```powershell
   $raw = Get-Content ".vscode\settings.json" -Raw -Encoding UTF8
   $stripped = ($raw -split "`n" | ForEach-Object { $_ -replace '^\s*//.*$','' }) -join "`n"
   $o = $stripped | ConvertFrom-Json
   $o.'java.configuration.runtimes' | ForEach-Object { $_.name + " -> " + (Test-Path $_.path) }
   ```

3. **Reload the VS Code window** (`Developer: Reload Window`) or run
   `Java: Clean Java Language Server Workspace`. The language server reads this
   setting at startup and will keep showing the old notifications until it restarts.

4. Confirm the build was never implicated:

   ```powershell
   cd backend ; .\mvnw.cmd test     # Tests run: 36, Failures: 0 / BUILD SUCCESS
   ```

**Not yet confirmed at the time of writing:** that the notifications stop after a
reload. Step 3 had not been performed - the fix is verified as correct configuration,
not yet as an observed absence of the error.

## Why the previous solution failed

The fix above was applied on the machine whose user profile is `hp`, and it hard-coded
that machine's JDK path into `.vscode/settings.json` - a file that is committed to git.
Pulling it onto the original `Developer.03` machine produced the same error again with
the paths exactly reversed:

```
Invalid runtime for JavaSE-25: The path points to a missing or inaccessible folder
(C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot).
```

| Path | On the `hp` machine | On the `Developer.03` machine |
|---|---|---|
| `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot` | Present | **Missing** |
| `C:\Users\Developer.03\Java\jdk-25.0.4+7` | Missing | **Present** (`JAVA_HOME`) |

The original diagnosis was therefore correct but incomplete. It identified that the
paths belonged to a different machine, and then fixed that by writing a different
machine-specific path into the same shared file. The defect is not which path is
written - it is that a machine-specific path is committed at all. Whoever fixes it
last hands the error to everybody else on their next pull.

Note also that `CLAUDE.md`'s "Environment Gotchas" is accurate for the `Developer.03`
machine: JDK 11 (`C:\Program Files\Microsoft\jdk-11.0.12.7-hotspot`) and JDK 17
(`C:\Users\Developer.03\Java\jdk-17.0.19+10`) are both installed there. The earlier
claim that they "were never installed on this machine" was true only of the `hp`
machine.

### Revised solution

Two parts. The first restores the editor now; the second is what stops the recurrence.

**1. Point the entry at this machine's JDK 25.** In `.vscode/settings.json`:

```json
"java.configuration.runtimes": [
    {
        "name": "JavaSE-25",
        "path": "C:\\Users\\Developer.03\\Java\\jdk-25.0.4+7",
        "default": true
    }
]
```

**`${env:JAVA_HOME}` does not work here, and was tried.** VS Code does not expand
variables in this setting, and the language server reported the placeholder back
verbatim: `Invalid runtime for JavaSE-25 ... (${env:JAVA_HOME})`. The path must be
literal.

**2. Move the block out of version control.** `java.configuration.runtimes` is
machine state, not project configuration, so it does not belong in a shared file. Cut
it from `.vscode/settings.json` into your VS Code **user** settings
(`%APPDATA%\Code\User\settings.json`), where each developer keeps their own. The
workspace file then retains only settings that are genuinely identical everywhere -
`typescript.tsdk`, `java.compile.nullAnalysis.mode` - and neither machine can break
the other again.

Until step 2 is done, expect this error to return on every pull that crosses machines.

### Revised testing steps

1. Confirm which paths exist on the machine reporting the error:

   ```powershell
   "JAVA_HOME = $env:JAVA_HOME"
   Test-Path "C:\Users\Developer.03\Java\jdk-25.0.4+7"                 # True here
   Test-Path "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"  # False here
   ```

2. Confirm the edited file is valid JSON and its runtime resolves. Output was
   `JSON valid` / `runtimes configured: 1` / `JavaSE-25 -> exists=True`:

   ```powershell
   $raw = Get-Content ".vscode\settings.json" -Raw -Encoding UTF8
   $stripped = ($raw -split "`n" | ForEach-Object { $_ -replace '^\s*//.*$','' }) -join "`n"
   $o = $stripped | ConvertFrom-Json
   $o.'java.configuration.runtimes' | ForEach-Object { $_.name + " -> " + (Test-Path $_.path) }
   ```

3. **Reload the VS Code window** (`Developer: Reload Window`). The language server
   reads this setting at startup only.

4. Confirm the build was never implicated - 48 tests as of Sprint 3:

   ```powershell
   cd backend ; .\mvnw.cmd test     # Tests run: 48, Failures: 0 / BUILD SUCCESS
   ```

**Verified:** steps 1, 2 and 4 were run and gave the output shown. Step 3 requires a
window reload and had not been observed at the time of writing, so the absence of the
notification is not yet confirmed - the same caveat the original fix carried.

## Troubleshooting

If the notifications persist after reloading:

- **Check user settings as well as workspace settings.** This fix changed
  `.vscode/settings.json` in the project. A `java.configuration.runtimes` block in
  `%APPDATA%\Code\User\settings.json` would also be validated. That file was
  inspected on 4 August 2026 and contains no Java settings, but it may acquire some.
- **Clear the server's cached workspace:** `Java: Clean Java Language Server
  Workspace`, then reload. The registry is cached between sessions.
- **If the error becomes "release 25 is not found in the system"**, the opposite
  problem now applies: the entry is missing or misspelled rather than wrong. The
  `name` values are Eclipse execution environments and must be exact - `JavaSE-25`,
  and `JavaSE-1.8` rather than `JavaSE-8`.
- **If the JDK path changes** (a Temurin update installs to a new versioned folder),
  this setting must be updated by hand. `echo $env:JAVA_HOME` gives the current one.

## Related files

| File | Relevance |
|---|---|
| `.vscode/settings.json` | The fix. Also pins `typescript.tsdk`, an analogous "editor disagrees with the build" setting. |
| `backend/pom.xml` | `<java.version>25</java.version>` - why only a JDK 25 entry is needed |
| `CLAUDE.md` | "Environment Gotchas" describes JDK 8 and 11 as installed; true of the original machine, not this one |
| `docs/memory/PROJECT_STATUS.md` | Section 5 records the JDK 25 installation; section 9 lists documents that describe the original machine |
