# Skerry agent contract

Skerry is security-sensitive Kotlin Multiplatform software. Current user instructions, code,
Git history and test results outrank these defaults. Commit and push only when the user asks.

## Start

1. Inspect `git status --short`; preserve existing work.
2. Read `docs/development-process.md`. Before editing code, read `docs/coding-guidelines.md`.
3. Run `python3 tools/harness/gate.py plan` and `doctor`.
   Inspect the plan's ECC guidance; add semantic topics with repeated `--focus` flags when paths
   cannot infer them. Read selected available skills before relevant work; report unavailable ones.
4. Work on `feat/`, `fix/`, `refactor/` or `docs/`; protect `main`.
   Declare a stricter task with `gate.py task <kind> [ref]` when needed.

## Implement

- Search the existing abstraction catalogue before introducing a new abstraction.
- Features start with tests. Bugs start with a regression that fails for the intended reason:
  `gate.py red --file <test-file> --tests '<pattern>'`. Keep that regression unchanged through GREEN.
- Put contracts and domain logic in commonMain, preserve desktop/Android parity, project primitives,
  en/ru/zh/tr/de resources, structured concurrency and cancellation. Treat external data as untrusted.
- Keep fixes small and delete code made obsolete by them.

## Verify

- Iterate with `gate.py run --mode fast`; direct focused tests are also allowed.
- Finish with `gate.py run`. It checks the affected modules and their consumers, then reports
  outstanding reviews/findings. `gate.py verify` succeeds only when final evidence is complete.
- Never run Gradle beside another build. The runner locks managed builds across worktrees/clones;
  manual Gradle commands must obey the same rule. Check memory first.
- Harness/policy/CI changes require `python3 tools/harness/selftest.py`; no Kotlin build for a
  harness-only change. CI uses the same planner and runner with `run --build-only`.
- Delivery guards use native Git hooks: `gate.py install-hooks`. Do not bypass them silently.
  No shell-command matching hooks or inferred review completions are used.

## Delegate and review

- Run `gate.py agent-plan` before delegation. Use its explicit model and reasoning effort from
  `tools/harness/agents.toml`; do not inherit a maximum-effort parent profile.
- Launch with `fork_turns="none"` and pass the bounded task, relevant contracts and files.
  Include the dispatch's selected skill names, source paths and `capability_instruction`. Read
  specialist prompts before use; follow project contracts over generic ECC stack examples.
  Explorers are read-only. Workers need explicit file ownership and must accommodate others' edits.
- Obey the reported concurrency limit. Do not run reviewers beside Gradle. Increase reasoning only
  for a concrete unresolved problem; never retry every agent at maximum effort.
- `gate.py reviewers` selects the repository reviewers by scope, without depending on an ECC cache.
  The ECC specialist suggestions in `agent-plan` are optional; use the printed bounded dispatch and
  `execution_role` fallback when the plugin role is not exposed by the runtime. They never count
  as a completed review by being present in a plan. Verify and fix their findings or report rejection.
  `gate.py review-start <name> --json` supplies the launch token, snapshot, delta and dispatch profile.
- Reviewers are read-only: no builds, branch changes, stash or source edits. Return the structured
  report described in `tools/harness/README.md`; the parent saves it outside the worktree and runs
  `gate.py review <name> --file <report.json>`.
- Verify every finding. Record `gate.py resolve <token> <id> <fixed|rejected> --reason '<evidence>'`.
  Fixes must pass tests and invalidate affected receipts. Reports never erase unresolved findings.
- Two completed rounds per reviewer/branch. Further drift remains owed; a third round requires the
  user's instruction, recorded with `review-start --extra-round-reason`. No automatic exemption.

## Handoff

State the change, verified evidence and live-platform gaps. UI work needs a concrete visual path.
Keep reports short and technical. Code comments, commit messages and PR descriptions are in English.
