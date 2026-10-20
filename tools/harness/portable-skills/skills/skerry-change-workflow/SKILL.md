---
name: skerry-change-workflow
description: "Implement a Skerry feature, regression fix or refactor using current project contracts, tests-first development, scoped gates and authorized delivery boundaries."
---

# Skerry: полный цикл изменения

## Уточнить фактическую задачу

Начать с `skerry-start`. Отделить наблюдаемый баг, гипотезу причины и желаемый результат. Если пользователь просит только анализ issue, работать read-only до прямой команды реализации. Для обычного «сделай» самостоятельно довести разрешённую работу до проверяемого результата; не заканчивать обещанием плана.

Зафиксировать scope: desktop/Android/server, persisted/wire compatibility, UI путь, recovery, acceptance. Не расширять задачу до переписывания подсистемы без причины. Сначала найти существующие абстракции, callers и тесты. Для существенного решения использовать `--focus architecture`, для async — `--focus coroutines`.

## Tests first

- Feature: сначала выразить новое поведение тестом на нужной границе. Bug: неизменяемая регрессия, которая падает именно из-за бага.
- Для bug записать RED: `python3 tools/harness/gate.py red --file <test-file> --tests '<glob>'` через bounded runner, если это JVM. Проверить assertion, не только exit code. Compilation error, missing SDK, пустой filter и все skipped — не RED.
- Оставить записанный тест неизменным через GREEN. Если неверен сам тест, исправить и заново записать честный RED; не редактировать receipts.
- Для refactor сохранить поведение, покрыть реально рискованные границы, не писать тест на каждую строку ради количества.

## Реализация

1. Изменять минимальную полную цепочку. New field в модели часто требует persistence/defaults/import/wire/UI/translations/оба wiring/tests.
2. Учитывать cancellation/re-entry, ownership, UI thread, validation-before-side-effect. Secrets — только project primitives, external bytes — untrusted.
3. Удалить ставшие ненужными реализации, зависимости и тесты вместе с заменой. Не заводить второй state store или PromptQueue без необходимости.
4. Для UI обеспечить общий form state, дизайн tokens/primitives, en/ru/zh/tr/de, keyboard settings row и конкретный visual path.
5. Во время итерации `gate.py run --mode fast` под лимитом. Direct focused tests допустимы для исследования, но не создают receipts.

## Перед handoff

- Просмотреть `git diff --stat`, существенный diff, `git diff --check`, untracked файлы. Проверить scope и отсутствие secrets/локальных отчётов.
- `gate.py run` под ресурсным лимитом. Это build/test phase; не запускать reviewers одновременно.
- `gate.py reviewers`, затем `review-start` и read-only reviewer по требуемым профилям. Подробно в `skerry-harness`/`skerry-agent-orchestration`. Если среда не позволяет нужный role/model, сообщить конкретный пробел, не ставить фиктивное completion.
- Проверить findings; исправить или отклонить с наблюдаемым доказательством. После исправления rerun затронутые stages, проверить свежесть reviews. Не больше двух завершённых раундов без пользовательского разрешения на дополнительный.
- `gate.py verify` должен подтверждать final evidence. Выход `run` 1 после зелёных build stages может означать review/RED debt; нельзя объявить весь gate сломанным или готовым без чтения summary.
- Для UI — открыть реальный экран, пройти сценарий desktop и Android в заявленной области. Common mobile shell test на desktop полезен, но отдельно обозначить device lifecycle gap.

## Разрешённая доставка

Commit/push/PR только при соответствующем прямом разрешении; merge/publication — в явно разрешённых границах. Если разрешение уже дано, не вставлять повторный вопрос после готовой проверки. Перед commit проверить точный набор staged файлов и соответствие дерева index; перед push — verified HEAD и clean tree по hooks. Не bypass guards.

Для описания Issue/PR применить `skerry-release-github`, английский обязательный формат. Не включать workflow агентов и токены reviews в PR body. После создания PR прикрепить его к chat, если инструмент среды доступен.

## Краткий отчёт пользователю

Что изменилось и какой сценарий теперь работает; выполненные проверки с результатом; ограничения live проверки; путь к артефакту или UI. Не представлять unit/fake/software tests как proof реального сервера/устройства. Если остановились на внешней зависимости, оставить конкретный воспроизводимый next step и готовую проверяемую работу.
