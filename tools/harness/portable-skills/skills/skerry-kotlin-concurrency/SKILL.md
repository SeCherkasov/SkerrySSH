---
name: skerry-kotlin-concurrency
description: "Design or review Skerry Kotlin controllers, suspend operations, Flow state, shared stores and resource lifetimes. Use for cancellation, race, re-entry or ownership changes."
---

# Skerry: Kotlin и конкурентность

## Контракт

Прочитать concurrency/architecture sections `docs/coding-guidelines.md` и актуальные callers. Добавить `--focus coroutines` при планировании async behavior, `--focus errors` для анализа ошибочного восстановления. Общие ECC примеры адаптировать к проектным contracts, не вводить Android architecture framework автоматически.

## Ownership и lifetime

Для каждого scope/job/connection выяснить: кто создаёт, кто отменяет, кто закрывает, кто может использовать после cancel. Входящий поток должен иметь одного определённого owner; callbacks старой попытки не должны публиковать состояние новой сессии. buildDesktopGraph/MainActivity/KeepAliveRuntime имеют разные lifetime — не переносить объект между ними без teardown contract.

В UI state держать наблюдаемую модель, а не несогласованные flags. Constructor dependencies/dispatchers инжектировать. Heavy IO/crypto/parsing выносить с UI dispatcher; пример — kdfDispatcher у VaultGateController. Одна фоновая coroutine не делает код thread-safe автоматически.

## Правила suspend-кода

- Catch вокруг suspend обязан пробросить CancellationException. В runCatching тоже проверить cancellation path; generic failure/result mapping не должен проглатывать cancel.
- Guard/busy flags сбрасывать в finally. После cancelled connect новый connect должен работать; cancelled biometric flow не должен навсегда оставить operation busy.
- Проверку и изменение общего состояния проводить под одним mutex. Read-modify-write vault — только внутри vault.transaction; отдельно взятые чтение и запись теряют concurrent merge.
- Не держать mutex во время пользовательского prompt/длинного network call без контрактного основания. Сначала snapshot/id, затем side effect, затем fenced publication по актуальному generation/owner.
- Flow/Channel queue должны быть bounded и иметь явную backpressure/drop policy. Не заменять terminal bytes conflation: можно coalesce derived snapshots, но нельзя терять входной byte stream.
- WebSocket handler читает входящие frames/Close даже если основная его задача — publish. Только write-loop пропускает disconnect.
- Blocking bridge для handshake/trust выполняется на подходящем worker; prompt timeout начинается при показе через PromptQueue, ответ привязан к конкретному вопросу.

## Confirmations и TOCTOU

Когда открывается confirmation, зафиксировать host/path/command/destination/identity. При OK не брать заново текущий выделенный item/tab/panel directory. Изменившийся UI не должен перенаправить уже подтверждённый overwrite/execute. Если identity устарела, запрос отклонить/переспросить по реальному контракту, не выполнить на новом объекте.

## Минимальные сценарии проверки

1. Cancel до allocation/connect и после получения ресурса: утечек нет, state возвращается в корректное состояние.
2. Два concurrent вызова: один owner или корректно определённая очередь, без двойного client/socket.
3. Закрытие/lock/reconnect во время read/write/prompt: позднее completion не воскрешает старую session.
4. Exception в середине transaction/guard: atomicity сохранена, finally отработал, повторный вызов возможен.
5. UI сменил selection между confirmation и submit: действие остаётся на подтверждённом snapshot.

Использовать controllable dispatchers/signals, fakes с observable close/cancel, а не случайный sleep. Подходящий уровень — pure/common controller test + integration на actual adapter, если баг в blocking boundary. Tests, Gradle и reviewers запускать разными фазами по harness.

## Review questions

Можно ли объяснить порядок событий без предположения о скорости CPU? В каком месте identity связывает данные с session/link? Как cancellation проходит через каждый catch и finally? Кто освобождает ресурсы при частично успешной инициализации? Какая очередь ограничивает untrusted sender? Если ответа нет, сначала прояснить контракт, потом писать код.
