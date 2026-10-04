# Project memory

This is the portable entry point for knowledge accumulated while Skerry was developed with Claude
Code. The raw project memory was imported verbatim on 2026-10-04 to
`.agent-memory/claude/` (231 Markdown files plus the previous index backup, about 1.5 MiB). That
directory is intentionally git-ignored and stored with owner-only permissions because it contains
historical, machine-specific and potentially private operational notes. It remains available
locally for exact lookup.

## How to use it

- Never treat memory as proof of the current tree, issue state, release state, or remote state.
- Verify status in code and git; use GitHub only when the task requires current remote information.
- Start with the raw `MEMORY.md` index, then open only notes relevant to the task.
- When a historical note conflicts with tracked instructions, `AGENTS.md`, `CLAUDE.md` and
  `docs/coding-guidelines.md` win.
- Do not copy secrets, personal data, local paths or unpublished product ideas from raw memory into
  tracked files, commits, issues or PRs.

Raw index: `../.agent-memory/claude/MEMORY.md`

## Durable project facts

- Skerry is a Kotlin Multiplatform SSH client for desktop and Android. iOS/iPadOS is deferred.
- `shared/` owns common domain and protocol logic; `composeApp/` owns Compose UI; `androidApp/` is
  the Android shell; `server/` is the Ktor sync server; `sync-wire/` is the shared wire contract.
- The repository uses JDK 21, Kotlin/JUnit 5 tests, detekt baselines and Android lint. It does not
  use Kotest or MockK.
- Security boundaries include the encrypted vault, untrusted protocol/server/AI input, sync/team
  authorization, filesystem writes and cancellation-safe concurrency.
- UI strings ship together in English, Russian, Simplified Chinese, Turkish and German.
- Work lands through pull requests; `main` is protected. Commits and pushes happen only on request.

## High-value historical topics

Use the raw index rather than guessing filenames. The most reusable notes cover:

- harness behavior, RED recording, review scope and the two-round cap;
- coroutine cancellation, test races, Compose UI-test first-frame behavior and Gradle/X11 caches;
- platform packaging and live-verification gaps on Android, Windows and macOS;
- vault/sync/team threat-model decisions and previously rejected security findings;
- RDP/VNC/terminal protocol edge cases and performance regressions;
- release signing, versioning and artifact verification.

## Known staleness

The imported index was last updated around the 0.5.1 development window and contains merged work
under headings that once meant “open”. It also preserves decisions tied to older code. Always check
the current branch and implementation before relying on a note.
