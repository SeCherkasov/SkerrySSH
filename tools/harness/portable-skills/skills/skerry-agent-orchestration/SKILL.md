---
name: skerry-agent-orchestration
description: "Delegate Skerry work when authorized or perform required read-only reviews with harness-selected models, visible execution and bounded context. Includes GLM/OpenCode mode only when explicitly requested by the user."
---

# Skerry: исполнители и независимое review

## Выбрать режим по пользовательскому указанию

Обычные project reviews/запрошенные исполнители используют available runtime и актуальный `gate.py agent-plan` / `review-start`. Специальный GLM/OpenCode режим включается **только прямой просьбой пользователя**, включая «субагенты GLM». Наличие OpenCode, слова AI в задаче или этот скилл не включают режим автоматически.

При любом запрошенном делегировании открыть доступный живой UI исполнителя до/одновременно с dispatch, сохранить доступным во время работы. В нём должны быть задания/tools/правки/ответы. Commentary не заменяет интерфейс. Если live UI недоступен, сказать явно и дать доступный журнал, не утверждать, что просмотр обеспечен.

## Обычный bounded dispatch

1. `gate.py agent-plan` до delegation; соблюдать current model/effort/concurrency из agents.toml, не parent maximum. `review-start` отдельно для каждого required review до запуска.
2. В snapshot explorer gpt-6-luna low, worker/project/Kotlin gpt-6.1-sol medium, security gpt-6.1-sol high; **не закреплять эти ID как доступные на новом ПК**. Несовместимую модель назвать и явно согласовать remap, не silently inherit.
3. fork_turns="none", минимальный task/context, relevant AGENTS/process/coding contracts, selected skill names/source paths/capability_instruction. Specialist prompt прочитать перед launch; native unavailable role может использовать напечатанный execution_role fallback.
4. Explorer read-only, worker ownership конкретных файлов/ответственности и предупреждение «вы не одни в codebase; не откатывать чужие правки». Parallel workers только по независимым files или isolated checkout. Required reviewers только read-only, без builds/git/source mutations.
5. Memory-aware concurrency из planner; не запускать reviewers рядом с Gradle. Token/snapshot/delta передать reviewer, report вне repo и импорт через gate review.
6. Verifiable findings проверить, fixed/rejected evidence записать resolve. Suggested ECC role не является completed required review. Two completed rounds limit сохраняется.

## Явно запрошенный GLM/OpenCode

Codex — оркестратор/независимый проверяющий. GLM через **OpenCode CLI** — основной исполнитель: исследование проекта, product implementation, tests/build/bench и исправления замечаний. Codex читает исходники/diff, поддерживает orchestration/verification artifacts и запускает independent acceptance checks. Сам product code не пишет/не чинит и не подменяет GLM после первой трудности.

Не использовать ZCode CLI/app-server и встроенных GPT subagents вместо «GLM subagents». Provider name zcode внутри OpenCode допустим: это не запуск ZCode CLI. Ранее выбранная модель zcode/glm-5.3 — лишь кандидат для live verification; не менять provider/model/тариф молча.

### Перед dispatch

- Проверить repo status/instructions и ownership scope.
- `opencode --help`, `opencode serve --help`, `opencode run --help` текущей версии; проверить local model catalogue без вывода credential config.
- Найти или поднять постоянный server на loopback, выбрать свободный порт. Не использовать чужую/непроектную session и не предполагать старый 4096 свободным.
- Открыть **настоящий server web UI** в доступном браузере. Рабочая session должна быть видна до/при dispatch. Если server API есть, но UI нет, это не равнозначно live visibility.

CLI shape после проверки flags:

```sh
opencode run --attach http://127.0.0.1:PORT --dir /path/to/skerry --model zcode/glm-5.3 --title 'Bounded Skerry task' --file /path/to/brief.txt -- 'Execute the attached bounded task and return compact evidence.'
# Продолжение той же задачи:
opencode run --attach http://127.0.0.1:PORT --dir /path/to/skerry --session SESSION_ID --model zcode/glm-5.3 -- 'Address the concrete review findings in the same authorized scope.'
```

`--` важен: --file принимает array и может поглотить positional prompt как имя файла. `--auto` не ставить по умолчанию: это широкое auto-approval. При narrowly authorized permissions issue сначала сузить task/config и понять причину, сохраняя no-secrets/no-publication boundary.

### Формат задания

Передать цель/acceptance, relevant files/contracts, ownership, tests/resource constraints, existing changes, запрещённые внешние операции и expected compact report. Прямо указать nested agents **только GLM**; executor должен вернуть parent/child ids и actual model evidence. Секреты/токены/passwords не передавать, только имена доступных env/secure storage при необходимости.

Попросить отчёт: files changed; решения; exact checks и counts/paths/logs; failures; live gaps; child sessions. Не запрашивать весь журнал без причины.

### Контроль выполнения

OpenCode CLI может вернуть success с пустым stdout. Проверить `/session/status`, `/session/<id>/message` и фактические files/diff/results по API текущего server; не считать выход process доказательством завершения. Session/model identity подтверждать stored messages, а не только launch argument.

Вложенные OpenCode agents разрешены для полезной декомпозиции, но actual model каждого child нужно проверить отдельно: parent GLM не гарантирует child GLM. Отслеживать parent relation, открыть child session в server UI. При неправильной модели остановить соответствующий dispatch и исправить routing, не принять такую работу как соблюдение запроса.

Codex независимо смотрит существенный diff/contract/acceptance checks. Замечания вернуть в ту же session: file/line, repro, consequence и criterion. Сначала сузить/вернуть GLM, а не самостоятельно дописать fix. Повторять до acceptance или конкретного внешнего blocker. Если OpenCode недоступен, назвать точную причину; смена исполнителя/роли только по прямому пользовательскому указанию.

## Границы

Delegate/review не создаёт разрешение на commit/push/merge/messages/publication. Shared repo resource limits действуют и внутри OpenCode; поручить executor не запускать параллельный Gradle/снимать лимит. Live UI не должен раскрывать secrets; reports вне repo. Итог — фактический result + independent evidence + live gaps, без обещаний гарантированной экономии токенов/универсального качества модели.
