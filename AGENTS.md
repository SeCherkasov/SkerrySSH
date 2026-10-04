# Skerry agent instructions

These instructions apply to the entire repository. Skerry is security-sensitive systems software;
prefer a verified small change over a broad speculative one.

## Read order

1. Read this file.
2. Read `CLAUDE.md` completely. It is the canonical development process and harness contract.
3. Before changing code, read `docs/coding-guidelines.md` completely.
4. Read `.agents/MEMORY.md`, then only the linked historical notes relevant to the task.

Authority, from highest to lowest: the current user request; current code, git history and test
results; `AGENTS.md`, `CLAUDE.md` and `docs/coding-guidelines.md`; curated memory; raw historical
memory. Memory records decisions and failure modes, but never proves current status.

## Start every task safely

- Inspect `git status --short`; existing changes belong to the user.
- Run `tools/harness/gate.py status` to identify the change kind, affected areas and existing debt.
- Search for the existing abstraction before adding one. The catalogue is in
  `docs/coding-guidelines.md`.
- Work on a typed branch (`feat/`, `fix/`, `refactor/`, `docs/`). Never commit or push from `main`.
- If the inferred kind is wrong, declare the stricter truth with
  `tools/harness/gate.py task <bug|feature|refactor|docs> [ref]`.

## Implementation contract

- Bug fixes start with a regression test that fails for the intended reason. Record RED with
  `tools/harness/gate.py red --tests '<pattern>' --file <test-file>` before implementing the fix.
- Features start with tests. Prefer `commonTest`; use platform tests only for platform behavior.
- Keep contracts and domain logic in `shared/commonMain`. Hide platform APIs behind interfaces or
  `expect`/`actual`. Desktop and Android must stay at feature parity unless explicitly scoped out.
- Follow existing project primitives, resource strings, design tokens and dependency catalog.
- Preserve structured concurrency, rethrow `CancellationException`, and keep blocking work off the
  UI thread. Treat server, protocol and AI data as untrusted.
- Delete code made obsolete by the change. Do not combine unrelated cleanup with the task.

## Verification and harness

The portable gate is `tools/harness/gate.py`; the Claude Code hooks are only adapters around it.
Other agents do not receive those hooks automatically, so enforce the same contract explicitly.

- Iterate with focused tests as needed.
- Finish build stages with `tools/harness/gate.py run`; manual Gradle runs do not close the gate.
- Never run two Gradle builds concurrently. Check memory before heavy work and stop daemons after a
  build series when needed.
- Run `python3 tools/harness/selftest.py` after changing the harness, its hooks, commands, reviewer
  definitions, or policy-facing agent instructions.
- Before any requested commit, push, or PR, run `tools/harness/gate.py status` and resolve every
  owed item. `SKERRY_GATE_OVERRIDE=1` is exceptional and must be disclosed with the exact reason.
- Commit and push only when the user asks.

## Review compatibility

For code changes, use the reviewer set printed by `tools/harness/gate.py reviewers`. Repository
reviewer prompts live in `.claude/agents/`; plugin reviewers use the matching installed ECC
capability. Reviewers are read-only and must not run Gradle or mutate git state.

When the current environment has no Claude hook, save each completed review to a temporary file and
record it explicitly:

```bash
tools/harness/gate.py review <reviewer-name> --file <report-file>
```

Verify every finding against the code. Fix it or report a reasoned rejection. Run no more than two
review rounds per branch unless the user explicitly requests a third. Avoid parallel heavy agents;
review fan-out is allowed only when available memory is safe, and reviewers still must not build.

## Handoff

State what changed, what was verified, and what was not verified on live platforms or services.
For UI work, include a concrete visual verification path. Keep user-facing prose technical and
short. Code comments and commit messages are in English.
