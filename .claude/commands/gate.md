---
description: Run the gate this change actually needs — stages by kind and area, then the review fan-out
argument-hint: "[--no-review] [--stages-only]"
---

The gate is not one fixed list. `tools/harness/policy.py` derives it from what the change **is**
(bug / feature / refactor / docs) and which **areas** it touches. Start by asking it:

```bash
tools/harness/gate.py status
```

That prints the kind, where the kind came from, the areas, and everything still owed. If the kind
is wrong — the branch name is uninformative, or a "chore" branch turned out to be a bug fix —
declare it before running anything: `tools/harness/gate.py task bug 133`.

A **docs** change owes nothing. Say so and stop; do not run Gradle to prove a README is fine.

## Stage 1 — the build stages

```bash
tools/harness/gate.py run
```

Runs exactly what is owed, in order, and records each stage against a digest of the tree it ran
against. Do **not** run Gradle by hand for this: a hand-run task is not recorded, because only the
runner can tie an exit code to the code it tested. Output goes to `.git/skerry-gate/<stage>.log`;
the runner prints the tail of a failing one.

Notes that cost time before:

- detekt fails on **new** findings only. Never run `detektBaseline` to bury your own finding — fix
  it, or tell the user why it stays.
- After a filtered test run (`--tests`), Gradle reports the aggregate task as up to date and the
  next full run passes in half a second having executed nothing. The runner adds `--rerun` itself
  when that has happened. `cleanAllTests` does not fix it.
- The runner stops the Gradle and Kotlin daemons when it finishes; together they hang this machine.
  `--keep-daemons` if you are about to run again.

A failure that is a build or config error rather than a design problem goes to
`ecc:kotlin-build-resolver` — minimal diffs, no architectural edits.

## Stage 2 — RED, for a bug fix only

A bug fix owes a test that failed **before** the fix existed. Record it while the bug is still live:

```bash
tools/harness/gate.py red --tests '*ReconcileDebt*' --file shared/src/commonTest/kotlin/.../ReconcileDebtStoreTest.kt
```

The runner refuses to record a test that passes — a green test before the fix proves nothing — and
refuses a pattern that matched no test at all. Afterwards the gate checks the sources really changed
since, so a stale record cannot stand in for a fix.

If the bug was already fixed before the test was written, revert the fix, record RED, restore it.
That is the whole point of step 1 in `CLAUDE.md`.

## Stage 3 — the review fan-out

Only when stage 1 is green, and skipped entirely on `--no-review` or `--stages-only`.

```bash
tools/harness/gate.py reviewers
```

prints the reviewers this change needs and which have not run against the current tree. The base
set is `skerry-reviewer`, `skerry-kotlin-reviewer`, `skerry-security-reviewer`,
`ecc:silent-failure-hunter`, `ecc:pr-test-analyzer`; a UI change adds `ecc:a11y-architect`, a server
change `ecc:java-reviewer`, a terminal change `ecc:performance-optimizer`.

An agent listed as `not installed` is not demanded — the ECC plugin is not declared in this repo and
a contributor without it must still be able to close the gate. When that happens, name the missing
angle in the hand-off instead of letting a thinner review pass silently.

Launch **all of them in a single message** so they run concurrently. Every reviewer prompt states:

- the exact range — `git diff main...HEAD` plus the uncommitted worktree;
- "read-only: report `file:line` and a concrete failure scenario, no edits";
- "never run `git checkout` / `switch` / `stash` / `reset` — the worktree is shared".

Editing code after a reviewer finishes invalidates that reviewer, exactly as it invalidates a build.

## Stage 4 — triage

Reviewers are fallible: **verify each finding against the actual code before acting on it**. Past
runs produced inflated severities and findings that were already implemented.

Report to the user as one table: finding → `file:line` → verdict (fix / reject + reason). Nothing is
dropped silently. A fix that changes behaviour goes back through step 1 — failing test first. Re-run
`tools/harness/gate.py run` afterwards; the digest changed, so the stages are owed again.

Finish by stating plainly what is still unverified — live device, live server, another OS.
