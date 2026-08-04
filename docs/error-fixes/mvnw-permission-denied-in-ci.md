# CI fails with "./mvnw: Permission denied" (exit code 126)

**Date:** 4 August 2026
**Component:** GitHub Actions, `.github/workflows/build.yml`, backend job
**Severity:** Blocking in CI. No effect on local development on Windows.

## Symptom

The first run of the `build` workflow failed within five seconds. The frontend job
passed; the backend job failed at its first real step:

```
Run ./mvnw --batch-mode test
  shell: /usr/bin/bash -e {0}
  env:
    JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.3-9/x64
/home/runner/work/_temp/74293a2d-....sh: line 1: ./mvnw: Permission denied
Error: Process completed with exit code 126.
```

Note what had already succeeded: checkout, and `Set up JDK 25`. The JDK was present
and `JAVA_HOME` was correct. **Exit code 126 means "command found but not
executable"** - it is not a Java problem, a Maven problem, or a missing file.

## Root cause

`backend/mvnw` was committed with file mode **100644** (not executable) rather than
**100755**. Confirmed with:

```bash
$ git ls-files -s backend/mvnw backend/mvnw.cmd
100644 bd8896bf2217b46faa0291585e01ac1a3441a958 0  backend/mvnw
100644 92450f93273470af42eeee491874afb2039b700a 0  backend/mvnw.cmd
```

The reason is Windows. Git records a Unix executable bit in the index, but NTFS has
no such bit, so Git for Windows sets `core.filemode=false` and stores every new file
as 100644:

```bash
$ git config --get core.filemode
false
```

**This is a pre-existing defect, not a regression.** The wrapper has been
non-executable since it was first committed - the repository has simply never had
anything execute it on a Unix filesystem. Windows runs `mvnw.cmd`, which does not
consult the bit, so local development never noticed. Adding CI on an Ubuntu runner was
the first thing to try `./mvnw`, and it failed immediately.

A second, non-blocking finding in the same run:

```
Node.js 20 is deprecated. The following actions target Node.js 20 but are being
forced to run on Node.js 24: actions/checkout@v4, actions/setup-java@v4,
actions/setup-node@v4
```

That is about the Node runtime the *actions themselves* execute on, and is unrelated
to the `node-version: '20'` used to build the frontend.

## Fix applied

**1. The executable bit, corrected in the repository.**

```bash
git update-index --chmod=+x backend/mvnw
```

This sets mode 100755 in the index directly, independently of what the filesystem can
represent, so it works from Windows. Verified afterwards:

```bash
$ git ls-files -s backend/mvnw
100755 bd8896bf2217b46faa0291585e01ac1a3441a958 0  backend/mvnw
```

`backend/mvnw.cmd` was deliberately left at 100644. It is a Windows batch file that is
never executed on Linux, and marking it executable would be meaningless.

**2. Action versions bumped** in `.github/workflows/build.yml`, resolving the
deprecation warnings: `actions/checkout@v4` to `@v7`, `actions/setup-java@v4` to
`@v5`, `actions/setup-node@v4` to `@v7`.

**3. The misleading comment corrected.** The workflow previously carried:

> `# No chmod +x mvnw step: the wrapper is committed with its permission bit`

That claim was false, and stating it confidently is part of why the failure was a
surprise. It now records what actually happened and why the fix belongs in the
repository.

### Two fixes deliberately NOT chosen

| Rejected | Why |
|---|---|
| `chmod +x mvnw` as a CI step | Would make the job pass while leaving every Linux and macOS clone with a wrapper it still cannot run. It fixes the symptom in the one place the symptom is visible. |
| `sh mvnw --batch-mode test` | Same objection, and it additionally hides a genuinely broken wrapper - `sh` will run a script with a corrupt shebang that `./mvnw` would refuse. |

Both would leave the repository defective for anyone cloning it on a Unix system,
which is the actual problem.

## Testing steps

1. Confirm the mode is now executable in the index:

   ```bash
   git ls-files -s backend/mvnw     # expect 100755
   ```

2. Confirm Windows development is unaffected - `mvnw.cmd` does not use the bit:

   ```powershell
   cd backend ; .\mvnw.cmd test     # expect Tests run: 48, Failures: 0
   ```

3. Push and confirm the `Backend - 48 tests` job reaches and passes
   `Run the test suite`. **This is the only step that proves the fix**, because the
   failure cannot be reproduced on Windows at all.

## Troubleshooting

- **If it still says Permission denied**, check the mode actually reached the remote:
  `git ls-tree origin/main backend/mvnw` should show 100755. A local index change that
  was never committed will look correct locally and change nothing in CI.
- **If a future commit resets the mode to 100644**, a Windows client has re-added the
  file. A `.gitattributes` cannot fix this - the attribute system has no executable
  bit. Re-apply `git update-index --chmod=+x`.
- **If the run fails later, inside Maven**, the wrapper is now executing and the
  problem is a different one. Exit 126 specifically means "found, not executable".
- **If bumping the action majors broke a step**, drop to `@v5` across all three, which
  is the first major past the Node 20 line for each.

## Related files

| File | Relevance |
|---|---|
| `backend/mvnw` | The file whose mode was wrong |
| `.github/workflows/build.yml` | Where it surfaced; comment and action versions corrected |
| `docs/project/AGILE_HIERACHY.md` | US-29, the story that added the workflow |
| `docs/memory/PROJECT_STATUS.md` | Section 6 records this as an always-applicable trap |
