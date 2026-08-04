# Invalid runtime for JavaSE-nn: the path points to a missing or inaccessible folder

**Date:** 4 August 2026
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
