# Skerry

Open-source, cross-platform SSH client with a single core. Kotlin Multiplatform, Compose
Multiplatform UI, one codebase across Desktop (Linux, Windows, macOS) and Android at feature parity.
**iOS/iPadOS is deferred** — don't re-add its targets or `iosMain`.

## Commands

Requires **JDK 21** (`foojay-resolver` fetches one if needed); Android needs `ANDROID_HOME`.

```bash
./gradlew :composeApp:run                                   # desktop
./gradlew :composeApp:packageDistributionForCurrentOS       # .deb / .rpm / .msi / .dmg
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
./gradlew test allTests                                     # JUnit 5; `test` alone skips shared/composeApp
./gradlew :androidApp:compileDebugKotlin                    # Android side of a UI change
./gradlew build                                             # full gate, lint included
./gradlew detektAll                                         # static analysis; detektBaseline to re-baseline
./gradlew koverHtmlReport                                   # coverage report
docker compose up -d --build                                # sync server; set SKERRY_JWT_SECRET
./gradlew :server:run -PserverOnly                          # server-only build, no Android SDK
```

Test stack is `kotlin("test")` on the **JUnit 5** backend. There is no Kotest, no MockK, and no
detekt/ktlint in this repo — don't introduce them because a generic Kotlin skill suggests them.
Fakes are hand-written; the lint gate is Android lint inside `./gradlew build`.

## Repository layout

```
shared/       # KMP core: ssh/, sftp/, vault/, sync/, team/, share/, terminal/, ai/ (+ai/local),
              # telnet/, serial/, mosh/, rdp/, vnc/, graphics/, audio/, tunnel/, container/,
              # snippet/, runbook/, host/, tag/, files/, guard/, update/
              # commonMain + jvmSharedMain (shared JVM for desktop+Android) + desktopMain + androidMain
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android app (MainActivity, manifest); applicationId app.skerry
server/       # self-hosted sync server (Ktor, AGPL-3.0)
sync-wire/    # wire contract shared by client and server (needed by server-only builds)
docs/         # HTML prototypes (source of truth for UX) and design documents
```

## How we work

Every change follows the same loop, but **what the loop demands depends on what the change is**.
Ask the harness rather than guessing:

```bash
tools/harness/gate.py status     # kind, areas, and everything still owed
tools/harness/gate.py task bug 133   # when the auto-detected kind is wrong
```

| Kind | How it is detected | What it owes |
|---|---|---|
| `docs` | no code in the diff | nothing — commit freely |
| `refactor` | `refactor/` `chore/` `perf/` branch | checks · tests · build · detekt · reviewers |
| `feature` | `feat/` branch, or an unnamed branch with code | the same, plus a test touched |
| `bug` | `fix/` `bug/` `hotfix/` branch, or declared | the same, plus a test recorded failing **before** the fix |

Areas add to that: UI or Android in the diff pulls in `:androidApp:compileDebugKotlin` and
`ecc:a11y-architect`; server pulls in `ecc:java-reviewer`; terminal pulls in
`ecc:performance-optimizer`. A declaration can make the gate stricter, never looser — a diff with
Kotlin in it is never treated as docs.

### 0. Orient before writing code

- **Read `docs/coding-guidelines.md`** — it encodes bugs we already paid for. Division of labour:
  this file owns the *process*, `coding-guidelines.md` owns *what the code must look like*
  (abstraction catalogue, decomposition, coroutine and security patterns, self-review checklist).
  A rule belongs in exactly one of the two.
- **Search for an existing abstraction before creating one** (guidelines §1). Second repetition is a
  signal, third makes extraction mandatory.
- For a non-trivial feature, map the ground first with `ecc:code-explorer` (how the existing
  subsystem works) and/or `ecc:code-architect` (where the new pieces belong). Skip for small fixes.
- Work happens on a feature branch. `main` is protected — every change lands through a PR.

### 1. RED — the failing test comes first

- Write the test before the implementation, in `commonMain` test sources unless the behaviour is
  genuinely platform-specific.
- Run it and **confirm it fails for the intended reason**, not on a compile error or a typo.
- For a bug fix the test must reproduce the bug, and the harness wants that on record:

  ```bash
  tools/harness/gate.py red --tests '*ReconcileDebt*' --file shared/src/commonTest/kotlin/.../ReconcileDebtStoreTest.kt
  ```

  It refuses to record a test that passes, and refuses a pattern that matched nothing. If the fix
  is already written, revert it, record RED, restore it — otherwise the bug fix cannot be committed.
- For controllers touching coroutines, cover cancellation and re-entry — that's the bug class
  guidelines §3 exists for.
- `ecc:tdd-guide` and the `ecc:kotlin-testing` skill are the reference for test shape; ignore their
  Kotest/MockK examples and use `kotlin.test` (see above).

### 2. GREEN — minimal implementation, then refactor

- Contracts and domain types in `commonMain`; platform libraries behind `expect`/`actual` or an
  interface. UI sees common contracts only.
- Desktop⇆Android parity: a feature isn't done until it works on both.
- Delete the code the change orphaned, in the same commit.

### 3. Build gate

```bash
tools/harness/gate.py run        # runs exactly the stages this change owes, in order
```

- **Only the runner marks a stage green.** It executes the stage, reads the exit code, and pins the
  result to a digest of the tree it ran against. A Gradle run made by hand is not recorded — not
  because hand runs are forbidden (iterate freely), but because nothing outside the runner can prove
  which code an exit code belonged to.
- Stages are `checks` (the deterministic project rules), `tests`, `build` (lint on), `detekt`, and
  `android` when the diff touches UI. Logs land in `.git/skerry-gate/<stage>.log`.
- detekt fails on **new** findings only; the existing ones sit in `gradle/detekt-baseline-*.xml`.
  Re-baselining (`./gradlew detektBaseline`) to silence your own finding is not allowed — fix it,
  or say out loud why it stays.
- After a filtered run (`--tests`), Gradle calls the aggregate task up to date and the next full run
  "passes" in half a second having run nothing. The runner adds `--rerun` when that has happened;
  `cleanAllTests` does not fix it.
- If the build breaks in a way that isn't obviously yours, hand it to `ecc:kotlin-build-resolver`
  (minimal diffs, no architectural edits) instead of reshaping the design around the error.
- New test added? Re-run it with the fix reverted to prove it actually catches the regression.
- Editing anything afterwards reopens the gate — the digest moved. That is not pedantry: it is the
  only way "green" can mean the code being committed.

### 4. Review gate — ECC fan-out before the PR

Once the branch is green, `tools/harness/gate.py reviewers` prints the set this change needs and
which of them have not run against the current tree. Launch them **in parallel, in a single
message**, scoped to `git diff main...HEAD` plus the uncommitted worktree:

| Agent | Looks for |
|---|---|
| `skerry-reviewer` | this project's own rules — parity, primitives, vault, the abstraction catalogue |
| `skerry-kotlin-reviewer` | structured concurrency, Compose recomposition, Kotlin idioms — for *this* stack |
| `skerry-security-reviewer` | the vault, untrusted protocol input, the sync/team boundary |
| `ecc:silent-failure-hunter` | swallowed exceptions, bad fallbacks, errors that never propagate |
| `ecc:pr-test-analyzer` | whether the tests actually cover the behaviour, not just the lines |

The first three live in `.claude/agents/` — they ship with the clone, so the fan-out does not
depend on a plugin installed on one machine. The generic Kotlin and security reviewers were
replaced because they review a stack this repo does not have (ViewModels, Room, NavController;
web vulnerabilities in an SSH client).

The harness adds `ecc:a11y-architect`, `ecc:java-reviewer` or `ecc:performance-optimizer` by area,
and reports them as *skipped* rather than owed when the plugin is not installed — say so in the
hand-off when that happens. Add by judgement: `ecc:type-design-analyzer` (new domain types),
`ecc:comment-analyzer` (comment rot), `ecc:database-reviewer` (SQL).

Rules for the fan-out:

- Reviewers are **read-only**. They report; the fixes are mine, in the working tree.
- Subagents must never run `git checkout`, switch branches, or stash — they share the worktree.
- Every finding gets one of two outcomes: fixed, or explicitly rejected to the user with the reason.
  Silent dismissal is not an option.
- A fix that changes behaviour goes back through step 1 (test first).
- Reviewers are fallible: verify each finding against the actual code before acting on it.

`/ecc:kotlin-review`, `/ecc:code-review` and `/ecc:review-pr` are the command shortcuts for the same
agents when a single-angle pass is enough.

**The whole loop is enforced, not advisory.** `git commit`, `git push` and `gh pr create` are
refused until this change has met the requirements for what it is — see the table at the top of this
section. The guard and the runner read the same policy module, so what is demanded and what is
reported can never disagree; `tools/harness/gate.py status` always says exactly what is left.

What "verified" is pinned to is **content**, not time: every stage records a digest of the files
that can affect a build. So an edit made by `sed`, by a patch, or by an editor outside the session
reopens the gate just as an `Edit` call does, `git commit` does not reopen it, and reverting a change
restores the green state it had. Documentation-only changes owe nothing at all.

The deliberate bypass is `SKERRY_GATE_OVERRIDE=1` on the command, and using it means saying out loud
why. It does not unprotect `main`.

The harness has its own tests — `python3 tools/harness/selftest.py`, ~70 cases, two seconds, no
Gradle. Changing a rule means changing them too; the previous version had no tests and both of its
holes were found in production.

### 5. Hand-off

- Commit messages in English. Commit and push **only when asked**.
- PR description in English: what the feature does, no development history, no "why we tried X".
- Tell the user how to verify the result with their own eyes — screen, scenario, keystrokes.
- State plainly what was *not* verified (live device, live server, other OS).

## Conventions

Code-level rules — reusable abstractions, file size, coroutines, security, design tokens, i18n —
live in `docs/coding-guidelines.md` and are not repeated here. What's left is project-wide:

- **UI 1:1 from the prototype** in `docs/design/Skerry Tablet.html` (`Skerry Logo.html` is the
  brand-mark source). Don't invent chrome; design tokens come from its `:root` block, mirrored in
  the Compose theme.
- A new keyboard shortcut ships with its row in Settings → Keyboard in the same commit.
- UI copy is technical and short; no reassuring second sentence.
- **Reporting to the user follows the same register as UI copy.** This is systems software, not a
  blog: fact, number, conclusion. A table or a short list beats paragraphs. Don't restate what was
  just done at length, don't enumerate options you won't take, don't ask about the obvious. Spell
  something out only when it hides a real gotcha or a decision that changes the work.
- Code comments in English, and only for the non-obvious *why*.

## Tooling

The ECC plugin (`ecc@ecc`) supplies the agents above plus skills worth loading in context:
`kotlin-testing`, `kotlin-coroutines-flows`, `compose-multiplatform-patterns`, `kotlin-patterns`,
`tdd-workflow`, `security-review`, `git-workflow`. Contributors without the plugin can read this
section as a checklist — the requirements (tests first, review before merge) are the point; the
agents are just how we execute them here.

## Warnings

- **ProGuard/minification is disabled on purpose** for the desktop release — it broke the crypto
  stack (JNA/libsodium, okio, BouncyCastle's signed jar). See the comment in
  `composeApp/build.gradle.kts` before re-enabling.
- CI runs `xvfb-run --auto-servernum ./gradlew test allTests`; UI tests need the virtual display.
- Licenses: GPL-3.0 for the clients, AGPL-3.0 for `server/`.
