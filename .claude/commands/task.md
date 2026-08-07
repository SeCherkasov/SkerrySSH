---
description: Declare what kind of change this is, so the gate demands the right things
argument-hint: "bug | feature | refactor | docs [issue or PR ref]"
---

The harness infers the kind from the branch prefix (`fix/`, `feat/`, `refactor/`, `docs/`) and from
whether the diff contains code at all. When that inference is wrong — an unnamed branch, or a
"chore" that turned out to be a bug fix — declare it:

```bash
tools/harness/gate.py task $ARGUMENTS
```

What the declaration changes:

| Kind | What the gate then demands |
|---|---|
| `bug` | a test recorded failing **before** the fix (`gate.py red`), plus everything below |
| `feature` | checks, tests, build, detekt, Android compile if UI is touched, full reviewer fan-out, and at least one test touched |
| `refactor` | the same build stages and reviewers, but no new test is required |
| `docs` | nothing — commit freely |

The declaration is scoped to the current branch: it does not follow the worktree onto the next
piece of work. Declaring `docs` on a branch that touches Kotlin does not disarm the gate — a change
with code in it is never treated as docs.

After declaring, report the resulting requirements to the user in one line, then continue the work.
