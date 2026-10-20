---
name: skerry-new-machine
description: "Set up or diagnose a fresh Skerry development machine, JDK 21, Android SDK, display and resource-bounded build execution. Use before first builds or after toolchain migration."
---

# Skerry: настройка нового ПК

Цель — получить воспроизводимое рабочее окружение, не переносить случайные настройки старого домашнего каталога. Все пути задавать для текущей машины. На Linux автоматизирована диагностика и ограниченный запуск; Windows/macOS описаны ниже, но эта обёртка там не является эквивалентом ресурсных ограничений.

## Источники версий

Сначала читать в checkout: `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `settings.gradle.kts`, `gradle.properties`, `docs/development-process.md`, `.github/workflows/ci.yml`.

Snapshot 2026-10-20: Python 3.11+, **полный JDK 21 с javac/jpackage/jlink**, Gradle wrapper 9.7.1, Kotlin 2.4.10, AGP 9.1.1, Compose 1.9.3. Android compile SDK 37, build-tools 37.0.0, target 36, min 26. Это не предложение обновить зависимости: перечитывать каталог, не выбирать «последнее» вместо него. Собственный Gradle/Kotlin глобально устанавливать не требуется.

## Подготовка

1. Установить Git, Python 3.11+, JDK 21, Android SDK command-line tools; Android Studio/IDE — по предпочтению пользователя. Linux desktop: графическая сессия или Xvfb. `rg` и `gh` полезны, `gh` нужен для GitHub lifecycle. Docker нужен только для соответствующей серверной/контейнерной проверки.
2. Доступ к Git проверить без вывода приватных данных: `git remote -v` только если URL не содержит токена; `ssh-add -l` при SSH. При ошибке посмотреть текущий SSH_AUTH_SOCK и применимую конфигурацию, не объявлять ключ отсутствующим по одной ошибке DNS. У нового компьютера свой agent socket.
3. Клонировать репозиторий по одобренному remote; сохранить полную историю для diff base harness. Проверить `git status --short`, `git rev-parse --is-shallow-repository`, наличие wrapper jar и executable bit `gradlew` на Unix. Для shallow clone получать недостающую историю, если сеть разрешена.
4. Выбрать JDK в терминале **и IDE Gradle JVM**. `JAVA_HOME` указывает на корень JDK, не bin. PATH должен начинаться с его bin. Сверить `java -version` и `javac -version`: обе команды 21. Старый путь SDKMAN не переносить буквально. JRE без javac недостаточен.
5. SDK: `ANDROID_HOME` указывает на SDK или в игнорируемом `local.properties` задан `sdk.dir`. Если задан ANDROID_SDK_ROOT, не оставлять конфликтующие пути. Использовать sdkmanager текущей установки, принять лицензии, установить `platform-tools`, `platforms;android-37`, `build-tools;37.0.0` для данного snapshot. Эмулятор и system image — отдельно, под нужный ABI; release APK сейчас arm64-v8a.
6. Полный settings graph включает Android даже при desktop-разработке. Только server-only graph (`-PserverOnly` или `SKERRY_SERVER_ONLY=1`) исключает клиентские модули. Он не подходит для shared desktop integration, которые запускают server.
7. Linux Compose: проверить действующий DISPLAY; один WAYLAND_DISPLAY не заменяет X display для JVM Compose-тестов. Headless — `xvfb-run --auto-servernum`. Не отключать тесты, чтобы скрыть отсутствие дисплея. Wayland clipboard может требовать `wl-copy`/`wl-paste`; H.264 desktop — найденный проектом ffmpeg. Это возможности конкретных функций, не обязательные зависимости любого сервера.
8. Не копировать `.gradle`, `.kotlin`, IDE caches, build outputs и harness receipts ради «готовой сборки». Артефакты инициализируются текущим wrapper. Кэш можно отдельно переносить при ограниченной сети, но не считать его проверкой и не переносить секреты из global gradle.properties.

## Read-only диагностика

Из каталога этого скилла:

```sh
python3 scripts/inspect_environment.py --repo /path/to/skerry
# Для отдельной серверной разработки:
python3 scripts/inspect_environment.py --repo /path/to/skerry --server-only
```

Скрипт читает только каталог версий, SDK path, версии инструментов и память. Он не вызывает Gradle, не читает vault/SSH keys/credential stores, не выводит env. Exit 1 означает найденный пробел; это не продуктовый тест. Дополнительно из checkout:

```sh
python3 tools/harness/gate.py doctor
python3 tools/harness/gate.py plan
python3 tools/harness/gate.py install-hooks
```

Если install-hooks обнаружил пользовательские hooks, сохранить и согласовать интеграцию, не затирать их. Для agent roles `sync-agents` генерирует локальные конфиги из `.agents/reviewers`, но наличие конфигурации не гарантирует наличие модели у провайдера.

## Ресурсный контракт Skerry на Linux

Пользователь ранее потребовал: каждая локальная Gradle/JVM test/compiler/performance/stress нагрузка — в user systemd scope с **MemoryMax=3G, MemorySwapMax=0, CPUQuota=200%**. Перед запуском минимум 6 GiB MemAvailable и swap не заполнен. Отсутствие swap само по себе не ошибка. Сборки последовательные; review не запускать рядом с Gradle. При OOM разделять задачи на свежие JVM, не повышать лимит. Эти числа относятся к Skerry, не ко всем проектам машины.

Обёртка [scripts/bounded_run.py](scripts/bounded_run.py) проверяет память/конкурирующие build JVM, использует отдельный lock для собственных запусков и оставляет внутренний lock harness в силе. Прямой Gradle вне обёртки всё ещё может создать гонку после preflight: не запускать его параллельно из IDE/терминала. Idle Gradle daemon также вызывает отказ до выяснения его принадлежности.

```sh
python3 /path/to/skill/scripts/bounded_run.py --repo /path/to/skerry --dry-run -- python3 tools/harness/gate.py run
python3 /path/to/skill/scripts/bounded_run.py --repo /path/to/skerry -- xvfb-run --auto-servernum python3 tools/harness/gate.py run --mode fast
python3 /path/to/skill/scripts/bounded_run.py --repo /path/to/skerry -- xvfb-run --auto-servernum python3 tools/harness/gate.py run
```

Обёртка задаёт launch-local GRADLE_OPTS: heap 1536 MiB, parallel=false, workers=1, in-process Kotlin compiler. Внутренний harness может явно задавать workers=2; outer cgroup остаётся общей верхней границей всех дочерних процессов. Она не меняет gradle.properties и глобальную swap. Команда передаётся argv без shell; не передавать секреты в аргументах. При недоступном systemd/user bus запуск прекращается, unbounded fallback отсутствует.

Для обычного desktop run использовать ту же обёртку с `./gradlew :composeApp:run`; для Android — `./gradlew :androidApp:installDebug`; для server — `./gradlew :server:run -PserverOnly` с локальными переменными окружения по серверному скиллу. Длительный app/server run занимает лимит до остановки.

## Windows и macOS

- JDK 21 + Python 3.11+ + SDK остаются обязательными для клиента. Windows wrapper — `gradlew.bat`; некоторые команды Python harness формируют `./gradlew`, поэтому весь локальный lifecycle Windows сначала проверить на текущем коде. Не объявлять его проверенным по Linux-результатам.
- Выбирать нативный JDK под архитектуру хоста. Упаковку MSI/DMG/DEB/RPM проверять на соответствующих ОС; Linux не доказывает все native bundles.
- Linux systemd wrapper не работает на Windows/macOS. До тяжёлого запуска настроить и проверить эквивалент группового лимита в согласованном окружении или использовать подготовленную Linux VM/host. Не обходить пользовательский лимит молча; 3 GiB heap одной JVM не равны 3 GiB всего дерева процессов.
- Не переносить XDG_RUNTIME_DIR, DBUS_SESSION_BUS_ADDRESS и DISPLAY со старого ПК. При проблеме systemd брать реальные значения активной пользовательской сессии, не жёстко UID 1000.

## Критерии готовности

Диагностика JDK/SDK/display понятна; Python harness selftest проходит; выбранный план выполнен под лимитом; desktop открывается с тестовым vault; Android устанавливается на нужный device, если он в scope; server-only проверен отдельно, если нужен. Полный green на чистом checkout может не выполнять Gradle при отсутствии изменённых входов — для первого smoke явно выбрать нужную тестовую/сборочную задачу из актуального `plan`/`policy.py`. Итог записать с ОС, архитектурой, версиями, командами и непроверенными платформами.

## Что переносить отдельно и безопасно

Код, этот комплект, разрешённые личные настройки; signing keystore и пароли — только через защищённый канал/secret manager при необходимости релиза. Production vault/identity/device_id, биометрические и trusted-device артефакты не копировать как dev-данные: они завязаны на устройство и его OS key store. Не добавлять их в архив скиллов. Для проверки разработки создавать отдельные fixtures и dev account.
