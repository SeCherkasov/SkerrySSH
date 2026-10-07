# Skerry harness v2

A module-aware planner, stage runner and evidence store. Retains the old deterministic rules and
Git content identity; replaces its whole-tree gate, text-matching hooks and implicit review recorder.
Python 3.11+, standard library only. See `docs/development-process.md` for project policy.

```sh
python3 tools/harness/gate.py doctor
python3 tools/harness/gate.py plan --json
python3 tools/harness/gate.py run --mode fast
python3 tools/harness/gate.py run
python3 tools/harness/gate.py verify
python3 tools/harness/gate.py run checks selftest
python3 tools/harness/gate.py red --file shared/src/commonTest/kotlin/FooTest.kt --tests '*FooTest*'
python3 tools/harness/gate.py agent-plan --json
python3 tools/harness/gate.py skill-plan --focus coroutines --focus architecture --json
python3 tools/harness/gate.py install-hooks
python3 tools/harness/gate.py sync-agents
python3 tools/harness/selftest.py
```

`run` returns 1 while any required final evidence is owed, even if all build stages passed.
`run --mode fast` reports only iteration-stage debt. `run --build-only` is a CI stage verdict:
exit 0 proves the planned stages passed, while output still reports final review/RED debt.
`status`/`verify` return 0 for complete evidence, 1 for debt, 2 for harness/prerequisite errors.
JSON responses include status, summary, next_actions and artifacts; `run --json` includes its log.

## ECC guidance

`plan`, `agent-plan`, `reviewers`, `skill-plan` and `review-start` accept repeated `--focus` topics
and `--ecc-root /path/to/ecc/plugin`. The root also accepts `ECC_PLUGIN_ROOT`; a single local cache
version is discovered automatically. Ambiguous caches stay unresolved. No plugin files are copied
into the repository, and no cache/version path is pinned in project policy.

Skill entries contain `name`, relative `source`, absolute `path` (or null) and source status:
`available`, `missing` or `unresolved`. Availability proves location only. Read the selected
SKILL.md and required references before use. Resolve names against the current session catalogue
when necessary; report missing guidance. The printed `instruction` travels with a subagent task.

| Scope/topic | Skills | Optional specialist prompts |
|---|---|---|
| Kotlin source | kotlin-patterns, kotlin-testing | — |
| Compose/Android UI | compose-multiplatform-patterns, accessibility | a11y-architect |
| Server Kotlin | kotlin-ktor-patterns | — |
| Protocol/vault/server/trust | security-review | — |
| SQL/database/migration | database-migrations, kotlin-exposed-patterns | database-reviewer |
| Terminal/graphics | stack and security skills above | performance-optimizer |
| Harness/policy | agent-harness-construction | — |
| `--focus coroutines` | kotlin-coroutines-flows | — |
| `--focus architecture` | intent-driven-development | code-explorer, code-architect |
| `--focus build` | — | kotlin-build-resolver |
| `--focus errors` / `coverage` | kotlin-testing for coverage | silent-failure-hunter / pr-test-analyzer |

Specialists include a bounded `dispatch`, prompt source and `execution_role` fallback. They remain
read-only and run no builds. A suggestion neither launches an agent nor closes a required review.
Parent agents verify specialist findings and fix them or explain rejection in the handoff.
`review-start` also includes role-specific skills for required reviewers. `sync-agents` writes the
loading instruction into native configs; per-task selections still come from launch dispatches.

## Reviews

```sh
python3 tools/harness/gate.py review-start skerry-reviewer --json > /tmp/skerry-review-request.json
# Launch read-only agent with the printed model/effort, token, delta and relevant contracts.
python3 tools/harness/gate.py review skerry-reviewer --file /tmp/skerry-review-report.json
python3 tools/harness/gate.py resolve TOKEN F1 fixed --reason 'Regression test X passes; fixed bounds check in Y.'
```

A report is JSON, schema 1, with the exact launch token and reviewer. A clean pass has `findings: []`.
Every finding requires id, priority 0..3, repository-relative path, positive line, title and a concrete
failure scenario in body. Findings start open; the parent records resolutions separately.

```json
{
  "schema": 1,
  "token": "TOKEN_FROM_REVIEW_START",
  "reviewer": "skerry-reviewer",
  "summary": "Checked the changed contract, callers and regression coverage.",
  "findings": [
    {"id": "F1", "priority": 1, "path": "shared/src/commonMain/kotlin/Foo.kt", "line": 42,
     "title": "Reject the invalid length before allocation",
     "body": "A negative server length reaches allocation and terminates the session."}
  ]
}
```

Two completed rounds are allowed per reviewer/branch. Scope drift after that stays owed. An explicitly
user-requested extra round uses `review-start NAME --extra-round-reason '<user instruction>'`.
The harness cannot prove that a human authorized the text; the agent must follow the user contract.
Likewise, a receipt proves what was run or reported, not the honesty of an agent with filesystem access.

## Delivery and diagnostics

Native Git `pre-commit` and `pre-push` adapters work regardless of the agent's tool names. Installation
is clone-local and refuses to replace existing custom hooks. Commit requires the tracked worktree to
match the index; push requires a clean worktree and the verified HEAD, and protects `main`.
PR creation uses explicit `verify`; there is no shell-command parser or additional confirmation prompt.
Git hooks remain bypassable by Git's own options; CI provides independent build checks.

`doctor` reports JDK/SDK/display, hook installation and dispatch configuration. Environment warnings
irrelevant to the current plan do not prevent a harness-only check. There is no broad cache purge,
global daemon kill or implicit downgrade on state errors. A failed stage's output lives in
`.git/skerry-harness-v2/logs`; fix the cause and rerun the same plan.

## Internals

- `state.py`: Git identity and changed-path plumbing.
- `policy.py`: pure module/area plan and input selection.
- `evidence.py`: versioned receipts, atomic transactions, OS locks.
- `runner.py`: execution, targeted JUnit collection, RED evidence.
- `reviews.py`: launch snapshots, structured findings and resolutions.
- `agents.toml` / `agents.py`: bounded model/effort profiles and dispatch plans.
- `capabilities.py`: scoped ECC skill references and optional specialist prompt routing.
- `environment.py`: diagnostics and native delivery guard.
- `checks.py`: retained project rules over added lines.
- `tests/`: retained rule regressions and replacement-harness contracts.

Old evidence stays in `.git/skerry-gate` for inspection and does not count as v2 evidence. No product
code is changed by the harness migration. Linux is the live-tested host; Windows/macOS and CI runtime
must be reported as unverified until exercised there.
