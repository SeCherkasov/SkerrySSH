---
name: skerry-architecture
description: "Navigate Skerry modules, source sets, dependency injection, domain packages and platform boundaries. Use to trace a behavior or decide where a change belongs."
---

# Skerry: архитектура и навигация по коду

## Общая схема

Skerry — local-first SSH/remote-work client. Клиент GPL-3.0, сервер AGPL-3.0. Linux/Windows/macOS — Compose Desktop JVM; Android — приложение с той же common UI и domain logic. iOS отложен. Основной UI не строится на Android ViewModel/NavController.

```text
androidApp ──> composeApp ──> shared ──> sync-wire
     └───────────────────────┘
server ─────────────────────────────> sync-wire
shared desktopTest ──> server (тестовая интеграция)
```

Это обзор; точные зависимости читать в build.gradle.kts и `tools/harness/policy.py`. Kotlin source sets и Gradle module dependencies — разные уровни графа.

| Модуль | Ответственность | Куда сначала смотреть |
|---|---|---|
| shared | Контракты, domain, протоколы, vault, stores, adapters | `src/commonMain/kotlin/app/skerry/shared` |
| composeApp | Общий Compose UI, controllers, shell/navigation, desktop entry | `src/commonMain/kotlin/app/skerry/ui` |
| androidApp | Activity, service, Android lifecycle/permissions и app wiring | `src/main/kotlin/app/skerry/android` |
| server | Ktor/Netty, auth, SQL repositories, admin, relay, web console | `src/main/kotlin/app/skerry/server` |
| sync-wire | Единственные сериализуемые client/server DTO | `src/main/kotlin/app/skerry/sync/wire` |

## Source sets

- commonMain — контракты и переносимая логика; здесь не должно появляться Java/Android API.
- jvmSharedMain в shared — общий JVM слой desktop+Android (sshj, Ktor/OkHttp, file adapters, AI runtime). Это не desktop-only код.
- desktopMain/androidMain — actual/platform behavior: PTY, audio/video codec, clipboard, biometrics, process/service.
- commonTest исполняется на поддерживаемом тестовом target; desktopTest добавляет JVM integration и Compose tests. Android app unit tests — отдельная задача, физический lifecycle ими не доказан.
- Новый Android KMP library plugin использует androidLibrary, не старый KotlinAndroidTarget. При изменении hierarchy читать комментарии текущего shared/build.gradle.kts.

## Application graph

Desktop: `composeApp/src/desktopMain/kotlin/app/skerry/ui/main.kt`. Ветка isolated LLM host обрабатывается до AWT/Skia; затем DisplayScaleReadiness, initializeVaultCrypto, config/preferences, render backend, buildDesktopGraph, Compose application. Не переносить инициализацию в composable с повторным выполнением на recomposition.

Общий контейнер зависимостей — `composeApp/src/commonMain/kotlin/app/skerry/ui/AppDependencies.kt`. На Android — `MainActivity.kt` с buildDependencies и KeepAliveRuntime. Меняя constructor/возможность, проследить оба wiring, demo/test graph и lifetime их scopes. UI/controller должен видеть общий контракт; platform adapter отвечает за платформенный механизм.

Сервер: Application.kt → ServerConfig → guardConfig → Db.connect → Services → Plugins/configureServer/routes → repositories. Background probes/cleanup принадлежат lifetime Application; DB/auth/metrics не создавать отдельно в каждом route.

## Области shared

| Пакеты | Что искать |
|---|---|
| ssh, trust, sftp, files, tunnel | Host identity, transport contracts/routing, connection pool, remote/local file browsers, forwarding |
| terminal | Emulator/parser/reflow, shell marks, safe input, recording/history/autocomplete |
| mosh, telnet, serial, jumpshell, container | Дополнительные transport semantics и специфичные codecs |
| graphics, rdp, vnc, audio | Общая remote session, framebuffer, протоколы/каналы/кодеки, output devices |
| vault, host, snippet, runbook | FileVault, authenticated records, credential/key management, vault-backed catalogues |
| sync, team, share | Delta sync, device-local records, team scopes/keys, live encrypted session sharing |
| ai, guard | Provider routing/policy/redaction, local isolated runtime, production command guard |
| text, tag, io, update | Нормализация, ограниченное чтение untrusted input, release client |

## Порядок исследования одной функции

1. Найти пользовательскую поверхность через ресурс/имя действия: `rg -n 'resource_name|ClassName' composeApp/src androidApp/src`.
2. Найти state/controller, его constructor dependencies, вызываемый contract. Затем shared implementation и platform actual.
3. Просмотреть тесты аналогичной границы; искать references по всем source sets, а не только рядом с исходником.
4. Для persistence найти codec/schema/record type и миграции; для wire — DTO и server validators; для lifecycle — owner, teardown и reconnect.
5. Перед новым helper прочитать **§1 docs/coding-guidelines.md**, authoritative abstraction catalogue. Он значительно подробнее этого обзора и меняется с проектом.
6. Составить короткую трассу «пользовательское действие → state → contract → implementation → side effect → observable result». Указать, где обрабатываются errors/cancellation/trust.

Для поиска по snapshot есть [references/source-map.json](references/source-map.json): tracked Kotlin/Gradle/harness paths, группы и SHA256 ключевых источников. Там нет содержимого приватных файлов. Карта помогает найти имя, но не заменяет текущий `rg --files`. `references/source-map.md` даёт сводку и правила обновления. Расхождение SHA/отсутствующий путь означает перечитать актуальный источник, а не восстановить старый код.

## Решение, куда помещать изменение

- Новый алгоритм/контракт — commonMain; библиотечный JVM/Android вызов — adapter; визуальная раскладка — Compose; lifecycle ОС — platform/application layer.
- Изменяемый UI form state общий, desktop/mobile layout тонкий. Domain parser не прятать в composable.
- Wire DTO не дублировать между shared/server. Sync cursor не хранить в UI preference без identity scope. Secret record не выносить в обычный preferences.
- Не создавать framework ради одного caller. Второе повторение — сигнал; третье требует извлечения общей абстракции. Файл около 400–500 строк разделять по ответственности.
- Изменение dependency catalog/root build затрагивает широкий граф; проверять план, а не только компиляцию своего модуля.
