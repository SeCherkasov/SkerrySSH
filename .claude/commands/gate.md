---
description: Run the full pre-PR gate — tests, build, Android compile, then the review fan-out over the branch diff
argument-hint: "[diff range, default main...HEAD] [--no-review to stop after the build]"
---

Run the gate from `CLAUDE.md` → *How we work*, steps 3–4, on the current branch. Don't skip a stage
because the previous one "looked fine", and don't summarise a stage you didn't run.

**Range**: `$ARGUMENTS` if it names one, otherwise `main...HEAD`.

## Stage 1 — build gate

Refuse to start if `git status --short` is empty **and** the range is empty — there is nothing to
gate. Otherwise run, in order, writing output to files (a pipe would mask the exit code):

```bash
LOG_DIR=$(mktemp -d)
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ./gradlew test allTests > "$LOG_DIR/test.log" 2>&1; echo "tests: $?"
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ./gradlew build > "$LOG_DIR/build.log" 2>&1; echo "build: $?"
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ./gradlew detektAll > "$LOG_DIR/detekt.log" 2>&1; echo "detekt: $?"
```

detekt fails on new findings only. **Never** run `detektBaseline` to make your own finding go away —
fix it, or report to the user why it should stay.

If the diff touches `composeApp/` or `androidApp/`, also:

```bash
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ./gradlew :androidApp:compileDebugKotlin > "$LOG_DIR/android.log" 2>&1; echo "android: $?"
```

On a non-zero exit: read the failing section of the log, report it, and stop. Don't start the review
fan-out on a red branch. If the failure is a build/config error rather than a design problem, hand
it to `ecc:kotlin-build-resolver` (minimal diffs only).

## Stage 2 — review fan-out

Only when stage 1 is green. Launch **all of these in a single message so they run concurrently**,
each scoped to the range:

| Agent | Angle |
|---|---|
| `skerry-reviewer` | this project's own rules (parity, i18n, primitives, vault, abstractions) |
| `ecc:kotlin-reviewer` | idiomatic Kotlin, null safety, coroutines, Compose |
| `ecc:security-reviewer` | secrets, crypto, injection, untrusted input |
| `ecc:silent-failure-hunter` | swallowed exceptions, bad fallbacks, errors that vanish |
| `ecc:pr-test-analyzer` | whether the tests cover the behaviour, not just the lines |

Add by judgement: `ecc:performance-optimizer` (terminal/rendering hot paths),
`ecc:type-design-analyzer` (new domain types), `ecc:a11y-architect` (new UI surface),
`ecc:java-reviewer` or `ecc:database-reviewer` (server changes).

Every reviewer prompt must state: the exact range, "read-only, report `file:line` + concrete failure
scenario", and "never run `git checkout`/`switch`/`stash`/`reset` — the worktree is shared".

Skip this stage entirely if `$ARGUMENTS` contains `--no-review`.

## Stage 3 — triage

Reviewers are fallible — **verify each finding against the actual code before acting on it**. Past
runs produced inflated severities and findings that were already implemented.

Then report to the user as a single table: finding → `file:line` → verdict (fix / reject + reason).
Nothing gets silently dropped. Apply the fixes that survive triage; any fix that changes behaviour
goes back through the TDD loop (failing test first). Re-run stage 1 after applying fixes.

Finish by stating plainly what is still unverified — live device, live server, another OS.
