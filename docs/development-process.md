# Development process

Skerry shares a Kotlin Multiplatform core and Compose UI across desktop and Android. iOS is deferred.
The five modules are `shared`, `composeApp`, `androidApp`, `server` and `sync-wire`.
Architecture, coroutine, security and UI rules live in [coding-guidelines.md](coding-guidelines.md).
Agent-specific startup and delegation rules live in [AGENTS.md](../AGENTS.md).

## Environment

Use Python 3.11+ and JDK 21. Client builds require an Android SDK even for desktop work; set
`ANDROID_HOME` or `sdk.dir` in `local.properties`. Server compilation/tests use `-PserverOnly`;
the shared desktop integration suite boots the server and needs the client settings graph/SDK.
Compose JVM tests need a display; use `xvfb-run --auto-servernum` on headless Linux.

Tests use kotlin.test with JUnit 5 and hand-written fakes. No Kotest/MockK. Static analysis is detekt;
Android lint runs in build. Keep existing baselines; do not re-baseline your new findings.

```sh
python3 tools/harness/gate.py doctor
python3 tools/harness/gate.py plan
python3 tools/harness/gate.py install-hooks
./gradlew :composeApp:run
./gradlew :androidApp:installDebug
./gradlew :server:run -PserverOnly
```

## Change loop

1. Inspect the worktree, read the relevant contracts and abstraction catalogue, choose a typed branch.
   The branch infers docs/refactor/feature/bug; `gate.py task` can make the kind stricter.
2. Add tests before implementation. For bugs, run `gate.py red --file <test> --tests '<pattern>'`;
   inspect the assertion to confirm the failure is the intended bug. Compile errors, empty filters
   and passing tests are not RED. Keep the recorded test unchanged through GREEN.
3. Implement the smallest complete change, preserving commonMain contracts and desktop/Android parity.
4. Iterate with `gate.py run --mode fast`: deterministic rules, harness self-tests when relevant,
   and affected JVM suites. It does not satisfy the final build/review gate.
5. Run `gate.py run`: add affected module builds (including Android for shared/client changes)
   and detekt. The runner executes serially, with two Gradle workers and no persistent daemons.
6. Run the selected read-only reviewers using the profiles and launch snapshots from the harness.
   Resolve each finding with evidence. Recheck only the stages whose inputs changed.
7. `gate.py verify` must report complete final evidence before a requested commit, push or PR.
   Commit/push/PR creation still requires the user's authorization. No extra permission prompt is
   introduced by a green gate. State any platform/service behavior not tested live.

## Plans and evidence

The planner understands module dependencies: `sync-wire` affects server and clients, `shared`
feeds Compose and Android, Compose feeds Android. Root build inputs and unknown executable inputs
select all modules. A Python/policy/reviewer/CI-only change selects checks and selftest, not Gradle.
Product assets and native libraries count as inputs. docs-only prose needs no build or review.
Server edits also run the shared desktop integration suite, without demanding Android builds.

Each stage owns its input snapshot and exact command. Editing agent instructions does not invalidate
product test/build evidence. Editing a dependency invalidates its consumers. Restoring identical
inputs restores an earlier receipt; committing identical content preserves it. Checks also depend
on the diff base and change kind. Failed or interrupted checks never become green.

For tests the runner clears only that leaf task's XML directory, executes the explicit task with
`--rerun`, then checks that task's JUnit XML. Missing, malformed, failing or entirely skipped results
are refused. `sync-wire` currently has no test sources; its builds and consumer tests cover integration,
not a direct suite. Direct Gradle commands remain useful iterations but produce no receipts.

Evidence is private and worktree-local in `.git/skerry-harness-v2/`. State updates are atomic and
locked. A separate user-wide build lock prevents two managed runs in different worktrees/clones.
Manual Gradle calls are outside that lock. Process cleanup affects only the runner's own process
group and inherited launch marker; Linux cleanup also follows marked detached children. No global
Kotlin-daemon kill. Other hosts remain unverified live. Old `.git/skerry-gate` receipts are historical and never imported
as verification. Corrupt/unsupported v2 state is an explicit error, not an empty green state.

CI runs the same planner with `--build-only` (the full tree on main, the change on PRs); its successful exit means the planned build stages
passed. It does not claim local RED/review evidence. Fetch full Git history so the diff base exists.

## Reviews and agent profiles

`tools/harness/agents.toml` is the dispatch source of truth. Narrow exploration uses a lighter model
with low reasoning; bounded implementation and project/Kotlin review use a workhorse with medium
reasoning; security review uses high reasoning. No profile defaults to maximum reasoning.
`gate.py agent-plan` prints model IDs, effort, purpose and the memory-aware concurrency limit.
An unsupported model must be reported and explicitly remapped; never silently inherit the parent.

Always supply minimal task context (`fork_turns="none"`), relevant contracts, owned files and the
review token. Independent light agents may run within the reported limit; builds and reviews run
in separate phases. Read-only reviewers must not run Gradle or mutate Git/source state.

Every code change gets `skerry-reviewer` (project rules, coverage and correctness); Kotlin changes
add `skerry-kotlin-reviewer`; protocol/vault/server/trust changes add `skerry-security-reviewer`.
Additional specialist reviews are a judgment call, not a plugin-installation-dependent requirement.
Prompts ship in `.agents/reviewers`; local Codex configs are generated from them with `sync-agents`.

ECC guidance is selected by `gate.py skill-plan` and included in `plan`, `agent-plan`, `reviewers`
and `review-start` dispatches. Read each selected skill before relevant work and pass its source
reference and the capability instruction to subagents. Project contracts outrank generic examples:
use kotlin.test/JUnit 5 and hand-written fakes, commonMain contracts and this project's Compose
primitives; do not introduce Kotest/MockK, Android ViewModels, Room or NavController from a skill.

Paths suggest Kotlin/testing, Compose/accessibility, Ktor, security, SQL/migrations and harness
skills. Terminal/graphics changes suggest a performance specialist. Paths cannot reliably infer
coroutine behavior, architectural work, build failures or coverage gaps: add `--focus coroutines`,
`--focus architecture`, `--focus build`, `--focus errors` or `--focus coverage` as appropriate.
Repeat focus flags on each planning/review-start command; they are task hints, not persisted state.
For a non-trivial feature, consider code-explorer/code-architect before implementation; a build
failure can use kotlin-build-resolver for narrow read-only diagnosis, with builds kept in the parent.

These specialist prompts are recommendations, not mandatory fan-out. Use their printed model/effort
and execution-role fallback if the native ECC role is not callable. Read the source prompt first,
apply the narrow task and parent constraints, verify every finding, then fix or explain rejection.
Required project reviews and their receipts remain independent of plugin installation.

Source discovery uses `--ecc-root`, then `ECC_PLUGIN_ROOT`, then a single cached ECC version under
`CODEX_HOME` (default `~/.codex`). Multiple cached versions require explicit selection or the session's
available-skills catalogue. `available` means the source file can be located, not that a native role
is enabled or a skill has already been read. `missing`/`unresolved` is explicit; report the guidance
gap and follow project contracts. Never fabricate a skill name, install a plugin implicitly or count
unavailable guidance as applied.

Start a review before launching the agent; the token pins its scope and base. A report without a
launch token, with a mismatched reviewer, or after scope drift is refused. Findings remain open until
explicitly fixed/rejected with a concrete explanation. A second report never erases earlier findings.
Each subsequent pass reads the delta and necessary callers. After two completed rounds, further
changes remain unreviewed and block delivery; a third round is the user's decision.

Review schema and CLI examples are in [the harness README](../tools/harness/README.md).

## Product conventions

- UI follows `docs/design/Skerry Tablet.html`; `Skerry Logo.html` defines the brand mark.
- Keyboard shortcuts ship their Settings → Keyboard row in the same change.
- Desktop minification stays disabled: it breaks JNA/libsodium, okio and signed crypto jars.
- Client license: GPL-3.0; server: AGPL-3.0.
- User-facing text is short and technical; code comments and delivery descriptions are in English.
