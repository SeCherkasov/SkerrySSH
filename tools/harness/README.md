# The development harness

Skerry's contribution loop is written down in `CLAUDE.md`: failing test first, minimal
implementation, build gate, review fan-out, then the PR. This directory is the part of that loop a
machine can check.

It exists because the distance between "written in CLAUDE.md" and "actually done" was covered only
by discipline, and discipline lost often enough to be worth automating.

## What it does

```bash
tools/harness/gate.py status     # what this change is, and what it still owes
tools/harness/gate.py run        # run the stages it owes
tools/harness/gate.py red --tests '*Foo*' --file path/to/FooTest.kt
tools/harness/gate.py reviewers  # which reviewers this change needs
                                 # and, for each, what moved since its last pass
tools/harness/gate.py checks     # the deterministic rules on their own
tools/harness/gate.py task bug 133
python3 tools/harness/selftest.py
```

A Claude Code hook (`.claude/hooks/guard-git.py`) refuses `git commit`, `git push` and
`gh pr create` while anything is owed. `SKERRY_GATE_OVERRIDE=1` on the command bypasses that
deliberately; it does not unprotect `main`.

## The requirements are a function of the change

Not every change deserves the same gate. A README fix owes nothing; a bug fix owes a test that was
recorded failing before the fix existed.

| Kind | Detected from | Owes |
|---|---|---|
| `docs` | no code in the diff | nothing |
| `refactor` | `refactor/` `chore/` `perf/` `test/` branch | checks, tests, build, detekt, reviewers |
| `feature` | `feat/` branch, or an unnamed branch with code | the above, and at least one test touched |
| `bug` | `fix/` `bug/` `hotfix/` `issue/` branch, or declared | the above, and a recorded RED run |

Areas touched by the diff add to it: `ui`/`android` pull in `:androidApp:compileDebugKotlin` and
`ecc:a11y-architect`, `server` pulls in `ecc:java-reviewer`, `terminal` pulls in
`ecc:performance-optimizer`.

## Reviewers

Three of them ship with the clone, in `.claude/agents/`: `skerry-reviewer` (the project's own
rules), `skerry-kotlin-reviewer` (structured concurrency and Compose for a KMP app with no
ViewModels, Room or Navigation) and `skerry-security-reviewer` (the vault, untrusted protocol
input, the sync and team boundary). They exist because the generic equivalents review a stack this
repository does not have.

The rest come from the ECC plugin, which is *not* declared anywhere in this repository. An agent
that is not installed is reported as skipped and not demanded — otherwise a contributor without the
plugin would face a gate no action of theirs could close. A skipped reviewer is an unreviewed
angle: say so in the hand-off.

`gate.py task <kind>` overrides the detection for the current branch. It can only make the gate
stricter: declaring a diff that contains Kotlin as "docs" does nothing.

## What "verified" is pinned to

Content, not time. Each stage records a digest of every file that can affect a build — Kotlin,
Gradle scripts, resources, the version catalog — computed from git blob ids so that the identity of
a file survives being committed.

Consequences worth knowing:

- an edit by `sed`, `git apply`, a subagent or an outside editor reopens the gate, because the
  content moved and nothing needs to have observed the edit;
- `git commit` does **not** reopen it — the content is unchanged, only HEAD moved;
- reverting an edit restores the green state it had before;
- switching branches invalidates it, because the content differs;
- prose, images, CI files and the harness itself are outside the digest: they cannot change what
  Gradle produces.

## Why the runner, and not a recorder

The previous version watched Bash commands go past and inferred a green build from their text. It
cost about ten unrecorded green runs: `/gate` redirects Gradle's output into a log, the log path
often lived in a shell variable, and a pattern reading the command text could not follow it. It also
did not recognise `./gradlew build` at all.

Here the runner executes the stage itself and reads the exit code. It also discards a run whose tree
changed while it was running, rather than recording it against code that no longer exists.

## The deterministic rules

`checks.py` holds the rules from `docs/coding-guidelines.md` that need no judgement — a missing
translation, a raw `Text(`, a hex colour, a hard dependency coordinate, `writeText` on a
secret-bearing path, a key binding with no row in Settings → Keyboard. They apply to the lines the
branch **adds**, not to the repository as a whole; inherited legacy is not this branch's debt.

Blocking rules were replayed over the last 25 merged PRs and tuned until what remained was real.
Escape hatch, for the cases where the rule is right in general and wrong here:

```kotlin
Txt("Skerry")  // harness-allow: i18n-hardcoded
```

## Layout

| File | Holds |
|---|---|
| `state.py` | digests, the state file, git plumbing |
| `policy.py` | kind, areas, and the requirements that follow |
| `checks.py` | the deterministic rules |
| `gate.py` | the CLI and the runner |
| `selftest.py` | ~70 tests over throwaway git repositories, two seconds, no Gradle |

State lives in `.git/skerry-gate/` — per clone, never committed.

Changing a rule means changing `selftest.py` with it. The two holes found in the previous version
were both found in production, on a branch that could not be committed because of them.
