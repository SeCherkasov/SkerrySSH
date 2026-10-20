---
name: skerry-testing
description: "Choose and run meaningful Skerry common, JVM integration, Compose shell, Android and server tests; preserve genuine RED and distinguish automated coverage from live platform evidence."
---

# Skerry: тестирование и доказательства

## Выбор уровня

| Что меняется | Тестовая граница |
|---|---|
| parser, codec, state transition, vault record rules | shared commonTest |
| sshj/Ktor/реальные JVM sockets, files, crypto adapters | shared desktopTest |
| shared controller, pure form state | composeApp commonTest |
| Compose layout/input/semantics, общий mobile shell | composeApp desktopTest |
| Activity/service/Android-specific coordination | androidApp src/test + device smoke |
| Route validation/auth, repository/SQL, websocket | server src/test |
| Harness/policy/CI/agent contract | tools/harness/selftest.py, без Kotlin build если только harness |
| sync-wire | builds + consumers; прямого test source suite в snapshot нет |

Перед запуском прочитать `tools/harness/policy.py` TEST_TASKS. В snapshot: shared/composeApp `desktopTest`, androidApp `testDebugUnitTest`, server `test`. commonTest входит в target suite. Не запускать вслепую `test allTests` как универсальную финальную команду.

## Тестовый стиль

kotlin.test assertions/annotations, JUnit 5 runner, kotlinx-coroutines-test, handwritten fakes. Никаких Kotest/MockK. Fixture должен выражать реальный контракт: bounded input, controlled clock/dispatcher, explicit signal для гонки. Не использовать задержки как единственный способ дождаться состояния, когда есть controllable scheduler/flow/latch.

В fake сохранять важную семантику реального dependency: cancellation, ошибки close, refused clipboard writes, потокобезопасность, identity scope. Fake, который всегда возвращает success, не доказывает recovery. Если баг связан с wire или store atomicity, проверить границу, а не только helper.

## RED и focused iterations

Записать failing assertion до product fix:

```sh
python3 tools/harness/gate.py red --file shared/src/commonTest/kotlin/app/skerry/shared/<area>/<Test>.kt --tests '*Test.method*'
```

Путь/имя заменить существующим тестом. JVM-вызов оборачивается bounded runner из `skerry-new-machine`. Прочитать `red.log` через реальный git-dir harness; test file не менять через GREEN. RED не засчитывает compilation errors, zero tests, only skipped или другой assertion. Для harness Python RED поддержан через `--file tools/harness/tests/...` и filter selftest.

Direct Gradle focused iteration: `:shared:desktopTest --tests '*ClassName*'`, `:composeApp:desktopTest --tests '*ClassName*'`, `:server:test -PserverOnly`, `:androidApp:testDebugUnitTest`. Исполнять под общим cgroup лимитом. Direct command не создаёт gate evidence; финально использовать planner/runner.

## Compose test harness

Authoritative fixture: `composeApp/src/desktopTest/kotlin/app/skerry/ui/desktop/ShellHarness.kt`.

- runDesktopShell / runMobileShell дают настоящий shell над demo graph.
- runForm — один composable; onField/onCatalog/onTab/press — стабильные доступные helpers.
- UiTags содержит устойчивые идентификаторы для navigation/screens/неименованных controls; текст ресурса и font ligature не использовать как единственный selector при локалях.
- FakeSystemClipboard/FakeDirectClipboard уже моделируют доступный/недоступный native clipboard и отказ записи/чтения.
- Проверять действие и наблюдаемый результат: dialog values после reopen, disabled state, focus возврат, scrollable viewport, error recovery. Не ограничиваться существованием ноды.
- `FormField`/fieldName задают доступное имя. Проверить semantics, keyboard navigation, live region lifecycle; physical TalkBack объявить отдельно.

Desktop JVM mobile shell — проверка общей mobile UI, не Android IME/service/USB/keystore/camera proof. Не называть её Android device test.

## Integration без реальных production данных

shared desktopTest уже умеет embedded Apache SSHD/SFTP и настоящий Ktor/Netty server. Route tests используют Ktor test host; `RoutesTestSupport` содержит общие guards/fixtures. Vault tests — okio FakeFileSystem и существующий crypto fixture; для криптографического roundtrip использовать реальную реализацию, где это смысл теста.

Сценарии, которые часто нужны: invalid lengths до allocation, cancelled connect не публикует session, close посреди read, simultaneous prompts, sync server switch, malformed remote record metadata, overwrite после смены каталога, EOF без финального update, view-only input, known-host mismatch. Выбирать относящиеся к задаче, не запускать все подряд.

## Финальная проверка и артефакты

`gate.py run --mode fast` — iteration; `run` — affected tests/build/detekt и связанные consumers, Android lint в build. Затем required reviews и `verify`. Python-only selftest не доказывает Kotlin compile; сборка не доказывает поведение теста; unit success не доказывает real-device runtime.

JUnit XML находится в `<module>/build/test-results/<task>/`, HTML tests — под `build/reports/tests`; detekt — `build/reports/detekt`. Harness очищает только leaf task XML и проверяет executed counts. Не использовать произвольные старые XML как proof. Для coverage `koverHtmlReport` через ограниченный запуск, выбирать нужную область; bench instrumentation отключать отдельно по performance skill.

Если suite упирается в лимит: свежая JVM и более узкие тесты/stages. Не повышать heap/cgroup, не обновлять baselines для сокрытия новых detekt findings и не выкидывать failing tests из плана. После успешных нужных проверок не повторять весь набор без изменений или нового основания.
