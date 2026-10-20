---
name: skerry-start
description: "Begin work in a Skerry checkout, establish current contracts and choose the relevant project skill. Use for onboarding or an unfamiliar task; domain skills handle implementation details."
---

# Skerry: начало работы и выбор маршрута

Этот комплект помогает работать с SkerrySSH после переноса на другую машину. Он описывает snapshot исходников от 2026-10-20; текущие пользовательские инструкции, AGENTS.md, код и результаты проверок важнее сохранённых фактов. Не считать названия моделей, версии, состояние ветки и CI постоянными.

## Первый проход

1. Найти checkout по `settings.gradle.kts`, `gradle/libs.versions.toml`, `tools/harness/gate.py`; не полагаться на старый абсолютный путь. Проверить `git status --short`, `git branch --show-current`, `git log -1 --oneline`. Существующая работа принадлежит пользователю.
2. Прочитать применимые AGENTS.md от родительских каталогов до области задачи, `docs/development-process.md`. Перед правкой кода — `docs/coding-guidelines.md`, начиная с каталога абстракций. Для знакомства — `.agents/ONBOARDING.md`. CLAUDE.md в исследованном snapshot отсутствует: не предполагать его наличие.
3. `python3 tools/harness/gate.py plan` и `doctor`. План отражает diff текущей ветки относительно базы, даже при чистом рабочем дереве. Не приписывать старые изменения новой задаче. `doctor` обнаруживает окружение, но не запускает продуктовые тесты.
4. Для неизвестного окружения применить `skerry-new-machine`. Для задачи найти контракт → реализацию → callers → тесты, используя `rg` / `rg --files` и `skerry-architecture`.
5. Работать в тематической ветке. Проект распознаёт `feat/`, `fix/`, `refactor/`, `docs/`; при обязательном префиксе хоста `codex/` использовать описательное имя и `gate.py task feature|bug|refactor|docs` при необходимости. Не редактировать main. Не менять существующую ветку с чужой работой ради формального имени.
6. Для планирования добавить поддерживаемые `--focus`, когда семантика не выводится из путей: `coroutines`, `architecture`, `build`, `errors`, `coverage`, `performance`. Это подсказки каждого вызова, не сохранённая настройка задачи.
7. Прочитать доступные скиллы, выбранные планом, только перед соответствующей работой. Недоступную рекомендацию назвать явно; присутствие рекомендации не означает её применение или завершённое review.

## Карта скиллов

| Задача | Скилл |
|---|---|
| Новый ПК, JDK, SDK, display, ограниченный запуск | `skerry-new-machine` |
| Модули, source sets, wiring, поиск нужной области | `skerry-architecture` |
| Реализовать фичу или исправить баг от начала до проверки | `skerry-change-workflow` |
| Выбрать тесты, RED, UI harness, integration | `skerry-testing` |
| Planner, receipts, hooks, reviews, Python harness | `skerry-harness` |
| Состояния, cancellation, mutex, lifetime | `skerry-kotlin-concurrency` |
| Compose, дизайн, пять языков, accessibility | `skerry-ui` |
| VT/ANSI, shell marks, input, rendering, recordings | `skerry-terminal` |
| SSH, transport routing, SFTP, tunnels, runbooks | `skerry-connections-files` |
| RDP/VNC, framebuffer, clipboard, audio, reconnect | `skerry-remote-desktop` |
| Vault, ключи, TOFU, biometrics, trust, untrusted input | `skerry-vault-security` |
| Sync, pairing, team scopes, session sharing | `skerry-sync-teams` |
| Ktor API, SQL, админка, контейнер сервера | `skerry-server` |
| AI policies, внешний provider, local llama.cpp | `skerry-ai` |
| Android lifecycle, service, IME, permissions | `skerry-android` |
| Измерения CPU/RAM/startup/latency, нагрузка | `skerry-performance` |
| GitHub Issue/PR, сборки пакетов, draft release | `skerry-release-github` |
| Делегирование, review profiles, явно запрошенный GLM | `skerry-agent-orchestration` |
| Ошибки окружения, сборки, сети и платформ | `skerry-troubleshooting` |

Не читать двадцать скиллов на каждый запрос. Обычно достаточно этого маршрутизатора, workflow и одной-двух областей. Подробные исходники читать выборочно; они важнее длинного onboarding.

## Неподвижные ограничения

- Общая логика и контракты — commonMain; desktop/Android parity обязательна. iOS отложен, его поддержки не обещать.
- Тесты: kotlin.test, JUnit 5, handwritten fakes. Не приносить Kotest/MockK или Android ViewModel/Room/NavController из общих примеров.
- Secrets — через проектные механизмы, без реальных ключей, vault, токенов и дампов окружения в заданиях, отчётах и скиллах.
- Сборки/JVM-нагрузку выполнять последовательно под ограничениями пользователя; перед запуском читать `skerry-new-machine`.
- Отчёты и бенчмарк-записки хранить вне checkout. Не переносить `.git/skerry-harness-v2` как доказательство нового запуска.
- Commit/push/merge/publication и сообщения другим людям требуют соответствующего прямого разрешения. Разрешение на реализацию не подменяет его. Уже данное разрешение не переспрашивать.

## Что считать результатом

Изменение + проверенные тесты/сборки + необходимые review receipts + конкретный пользовательский путь проверки. В финале назвать непроверенные платформы и сервисы. Два Compose shell-теста на desktop JVM проверяют общий UI, но не доказывают lifecycle Android или работу физического GPU.
