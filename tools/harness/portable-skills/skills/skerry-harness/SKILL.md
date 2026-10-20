---
name: skerry-harness
description: "Operate or change the Skerry Python harness v2: plans, scoped stages, RED receipts, reviews, findings, hooks and delivery guards. Use for gate diagnosis or policy/CI work."
---

# Skerry: harness, receipts и review gate

Перед работой читать `tools/harness/README.md`, `docs/development-process.md`; при изменении кода harness — `tools/harness/tests` и соответствующие internals. Python 3.11+, standard library. Не восстанавливать исторический v1.

## Команды

```sh
python3 tools/harness/gate.py doctor
python3 tools/harness/gate.py plan --json
python3 tools/harness/gate.py skill-plan --focus coroutines --focus architecture --json
python3 tools/harness/gate.py task bug
python3 tools/harness/gate.py run --mode fast
python3 tools/harness/gate.py run
python3 tools/harness/gate.py status --json
python3 tools/harness/gate.py verify
python3 tools/harness/gate.py run checks selftest
python3 tools/harness/gate.py reviewers
python3 tools/harness/gate.py agent-plan --json
python3 tools/harness/gate.py install-hooks
python3 tools/harness/gate.py sync-agents
```

run/RED с JVM выполнять через resource wrapper. JSON CLI содержит envelope `status`, `summary`, `next_actions`, `artifacts`, `data`; не предполагать, что task лежит в корне. Использовать `--help` текущей версии. Не выдумывать `--focus i18n`: есть ui/accessibility/coverage; полный choices в help.

## Смысл плана

Planner учитывает изменённые файлы, base, task kind и граф consumers. shared → composeApp → Android, sync-wire → server/client, server → shared desktop integration tests. Root build/неизвестные executable inputs могут выбрать всё. Harness/policy/CI-only — checks+selftest; docs-only prose не требует Kotlin build/review. Не маскировать code change как docs командой task: это механизм ужесточения, не обхода.

План видит веточный diff даже при clean worktree. Для worktree путь `.git` может быть файлом: получать реальный git-dir командой Git. CI detached HEAD классифицируется с доверенным CI pull_request контекстом; не экспортировать CI=true локально, чтобы скрыть неправильную ветку.

## Evidence

V2 хранит приватное atomic snapshot/content-based состояние в git-dir `skerry-harness-v2`. Действительность определяется inputs каждой stage и окружением, не календарной свежестью и не «этот тест когда-то проходил». Затронутые dependencies инвалидируют consumer receipts; идентичное восстановленное содержимое может вернуть receipt. Не менять receipts вручную и не копировать со старой машины.

Stage runner пишет exact command/log и проверяет tree snapshot. JUnit: очищает только task leaf XML, запускает `--rerun`, отказывает при malformed/missing/all-skipped/failing output. Failed/interrupted stage не становится green. Manual Gradle не создаёт receipt. User-wide build lock касается managed builds; прямые Gradle/IDE не защищены этим lock.

`run` exit 1 может означать final RED/review debt при passed build stages. `run --mode fast` оценивает iteration debt. `run --build-only` — CI build verdict, не final local clearance. `status`/`verify`: 0 complete, 1 debt, 2 prerequisite/state error. Читать next_actions, не снижать требования ради exit 0.

## Review protocol

1. Завершить Gradle phase. `reviewers` определяет обязательных reviewers: project для кода, Kotlin для Kotlin, security для protocols/vault/server/trust.
2. `review-start NAME --json` **перед** запуском агента. Сохранить request вне checkout. Он даёт token, snapshot, delta, dispatch/model/effort и skills/capability instruction.
3. Read-only reviewer получает минимум исходников, policy, token, scope. Никаких builds/source edits/git mutation. Обеспечить пользователю живой интерфейс исполнителя; если нет — честно сообщить и дать доступный журнал.
4. Сохранить настоящий JSON report вне checkout и импортировать `review NAME --file /path/report.json`.
5. Проверить finding; `resolve TOKEN F1 fixed|rejected --reason '<конкретное доказательство>'`. Запись fixed сама по себе не заменяет тесты/новый review при drift.

Схема, которую необходимо сверить с текущим README:

```json
{
  "schema": 1,
  "token": "EXACT_LAUNCH_TOKEN",
  "reviewer": "skerry-reviewer",
  "summary": "Inspected changed contracts, callers and regression coverage.",
  "findings": [
    {"id":"F1","priority":1,"path":"shared/src/commonMain/kotlin/path/File.kt","line":42,
     "title":"Reject length before allocation","body":"Concrete input, failure and consequence."}
  ]
}
```

Clean report — findings: []; не создавать его вместо настоящего review. Priority 0..3, path repository-relative, line positive. Report с чужим token/reviewer/устаревшим scope отклоняется. Новый report не стирает открытые findings. Два completed rounds per reviewer/branch; третий только по прямому пользовательскому указанию, отражённому в `--extra-round-reason`. Не тратить round на организационный пробный запуск.

## Native hooks

install-hooks clone-local; сохранять чужие custom hooks. pre-commit требует соответствия tracked worktree/index; pre-push — clean worktree и verified HEAD, main защищён. PR creation тоже предваряет verify. Не использовать --no-verify, не переименовывать/удалять state для обхода. Если hook блокирует, читать причину и исправить её.

## Изменение harness

- policy.py — чистый граф/inputs/area/task planning; state.py — Git identity/path plumbing; evidence.py — versioned receipts/locks; runner.py — stage execution/JUnit/RED.
- reviews.py — launch snapshots/findings; agents.toml/agents.py — bounded dispatch; capabilities.py — ECC discovery; environment.py — diagnostics/hooks; checks.py — deterministic rules; resources.py — process cleanup только своего launch marker с PID-start-time guard.
- Регрессия сначала. Для Python bug RED через harness поддерживается. Обязательно `python3 tools/harness/selftest.py`, `git diff --check`, `run checks selftest`, final plan/verify с необходимым review.
- Не запускать Kotlin build для harness-only diff. Не разрушать старые receipts, CI isolation, atomic writes и process ownership.
- CI использует тот же plan с run --build-only; CodeQL принудительно компилирует без build cache. Не подменять build evidence старым artifact.
