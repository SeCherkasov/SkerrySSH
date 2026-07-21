# Skerry

[English](README.md) · **Русский**

[![CI](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml)
[![Релиз](https://img.shields.io/github/v/release/SeCherkasov/SkerrySSH)](../../releases/latest)
[![Клиенты: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-blue)](LICENSE)
[![Сервер: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-blue)](server/LICENSE)

Опенсорсный кроссплатформенный SSH-клиент с единым ядром: Kotlin Multiplatform под капотом,
Compose Multiplatform UI сверху. Один код ядра и один UI на **Desktop (Linux, Windows,
macOS)** и **Android**, паритет фич между платформами.

- **Local-first** — всё работает без сервера и без аккаунта.
- **Zero-knowledge** — мастер-пароль не покидает устройство.
- **AI under policy** — вывод модели считается недоверенным; действия только после
  подтверждения; есть полностью локальная модель.
- **Паритет платформ** — фича не готова, пока не работает везде.

## Сравнение с аналогами

| Возможность | Skerry | Termius | PuTTY | Tabby |
|---|---|---|---|---|
| **Open source** | ✅ GPL-3.0 · AGPL-3.0 | ❌ | ✅ MIT | ✅ MIT |
| **Платформы** | Linux · Windows · macOS · Android | Windows · macOS · Linux · iOS · Android | Windows · Unix | Windows · macOS · Linux |
| **Первый релиз** | 2026 (v0.1.x) | 2011 | 1999 | 2017 |
| **Цена** | бесплатно | free-тариф · платно от $10/мес | бесплатно | бесплатно |
| **Работает без аккаунта** | ✅ | ⚠️ только локально <sup>1</sup> | ✅ | ✅ |
| **Шифрованный vault** | ✅ всегда включён <sup>2</sup> | ✅ | ❌ | ⚠️ по желанию |
| **Синхронизация** | ✅ self-hosted, zero-knowledge | ✅ облако вендора, E2E (платно) | ❌ | ✅ self-hosted, E2E опционально <sup>3</sup> |
| **Шаринг в командах** | ✅ end-to-end | ⚠️ платный тариф | ❌ | ❌ |
| **SFTP** | ✅ двухпанельный UI | ✅ | ⚠️ только CLI (`psftp`) | ✅ встроенная панель |
| **Port forwarding** | ✅ local · remote · dynamic | ✅ | ✅ | ✅ |
| **Serial / Telnet** | ✅ / ✅ | ✅ / ✅ | ✅ / ✅ | ✅ / ✅ |
| **Mosh** | ✅ | ✅ | ❌ | ❌ |
| **AI-ассистент** | ✅ локальный или BYOK-облако <sup>4</sup> | ⚠️ облако, нужен аккаунт | ❌ | ❌ |

**Обозначения:** ✅ есть · ⚠️ частично / с оговорками · ❌ нет

<sup>1</sup> sync и AI требуют аккаунт &nbsp;·&nbsp;
<sup>2</sup> Argon2id + XChaCha20-Poly1305 &nbsp;·&nbsp;
<sup>3</sup> через self-hosted Tabby Web &nbsp;·&nbsp;
<sup>4</sup> опционально; вывод модели считается недоверенным, действия требуют подтверждения

*Данные о конкурентах собраны по официальным сайтам и репозиториям проектов 2026-07-12.
Нашли ошибку — присылайте PR.*

## Статус

В активной разработке под **Linux**, **Windows**, **macOS** и **Android**. **iOS/iPadOS**
— в планах.

## Установка

Скачайте пакет из **[последнего релиза](../../releases/latest)**:

| Платформа | Архитектура | Файлы |
|---|---|---|
| Linux | x86_64 | `.deb`, `.rpm`, `.AppImage`, `.flatpak` |
| Linux | arm64 | `.deb`, `.rpm`, `.AppImage` |
| Windows | x64 | `.msi` |
| macOS | Apple Silicon | `Skerry-*-arm64.dmg` |
| macOS | Intel | `Skerry-*-x64.dmg` |
| Android | arm64-v8a | `.apk` (подписан; sideload) |

- **Сборки под macOS не подписаны и не нотаризованы** (пока нет аккаунта Apple Developer),
  поэтому Gatekeeper блокирует первый запуск: правый клик по приложению → Open, либо
  разрешите в System Settings → Privacy & Security. В метаданных бандла (Get Info в
  Finder) стоит версия `1.x.y` — это тот же релиз `0.x` (упаковка под macOS требует
  мажорную версию ≥ 1); экран About показывает настоящую версию.
- Windows `.msi` тоже без подписи кода; SmartScreen может предупредить при первом запуске.
- Проверка контрольных сумм: `sha256sum -c --ignore-missing SHA256SUMS.txt`.

Собрать самостоятельно тоже несложно — см. [Сборка из исходников](#сборка-из-исходников).

## Скриншоты

![Терминал с менеджером хостов, вкладками сессий и панелью живых метрик](docs/screenshots/desktop-terminal.png)

<details>
<summary>Больше скриншотов</summary>

![Двухпанельный SFTP Commander](docs/screenshots/desktop-sftp.png)

![Менеджер port forwarding](docs/screenshots/desktop-tunnels.png)

![Vault: ключи, пароли, сертификаты](docs/screenshots/desktop-vault.png)

![AI-ассистент с per-host политиками](docs/screenshots/desktop-ai.png)

| Список хостов | Терминал |
|---|---|
| ![Список хостов с группами и тегами](docs/screenshots/mobile-hosts.png) | ![Мобильный терминал](docs/screenshots/mobile-terminal.png) |

</details>

## Возможности

- **Подключения** — SSH с jump-хостами (ProxyJump) и SSH-сертификатами; встроенный SSH-агент
  с пробросом по хостам (и `SSH_AUTH_SOCK` для локальных утилит на десктопе); SFTP (двухпанельный
  commander); port forwarding: local, remote, dynamic/SOCKS; Telnet; serial (desktop и
  Android USB-OTG).
- **Терминал** — своя grid-эмуляция, вкладки со split view, авто-реконнект SSH, поиск по
  scrollback, живые метрики хоста.
- **Vault** — всегда включённое шифрование (Argon2id + XChaCha20-Poly1305) для ключей,
  паролей, identities и сертификатов; биометрическая разблокировка на Android.
- **Sync** — опциональная и self-hosted, zero-knowledge, live push через WebSocket, паринг
  устройств по QR. См. [Sync-сервер](#sync-сервер).
- **Teams** — E2E-шифрованный шаринг хостов и сниппетов внутри команды.
- **Сниппеты и AI** — библиотека команд с type-ahead в терминале; AI-ассистент с per-host
  политиками — свой ключ OpenAI или локальная модель.
  См. [AI и приватность](#ai-и-приватность).
- **Локализация** — интерфейс на английском и русском; ассистент отвечает на языке UI.

## AI и приватность

Обещание vault'а («мастер-пароль не покидает устройство») и облачный AI-ассистент
сосуществуют только по явным правилам:

- **Ничего не отправляется автоматически.** Запрос содержит только текст, который вы
  ввели в AI-бар или чат, плюс фиксированный системный промпт. Вывод терминала, списки
  хостов и содержимое vault к запросам не прикладываются.
- **Облачный режим — BYOK**: ваш собственный API-ключ OpenAI; запросы идут напрямую из
  приложения на настроенный вами endpoint.
- **Per-host политики** решают, куда может уйти запрос:
  - **Strict** (по умолчанию для новых хостов) — только локальная модель; ничего не
    покидает устройство.
  - **Balanced** — облако разрешено; очевидные секреты (приватные ключи, токены,
    `password=…`) вырезаются из промпта перед отправкой. Редакция — это best-effort
    сопоставление паттернов, а не гарантия.
  - **Permissive** — облако без редакции, для нечувствительных систем.
  - **Off** — AI для этого хоста скрыт.
- Глобальный quick-chat всегда вырезает секреты, даже при работе с локальной моделью.
- **Локальный режим**: приложение само скачивает GGUF-модели (Qwen3, Phi-4 Mini) и
  запускает их на устройстве через llama.cpp — данные не покидают устройство вообще.
- **Вывод модели недоверенный**: предложенная команда никогда не выполняется сама — нужно
  явное подтверждение, а для команд, классифицированных как рискованные, — дополнительное.

## Технологии

- **Язык/UI**: Kotlin 2.x, Compose Multiplatform 1.11.1
- **Сборка**: Gradle 9.3.1, Android Gradle Plugin 9.0.1
- **JVM-таргет**: JDK 21 (`jvmToolchain(21)` во всех модулях, `JVM_21`)
- **Android**: minSdk 26 (Android 8.0), compileSdk/targetSdk 36
- **Ядро**: sshj 0.40.0, BouncyCastle 1.80.2, libsodium (ionspin KMP), okio, atomicfu
- **Serial**: jSerialComm 2.11.0 (desktop), usb-serial-for-android 3.9.0 (Android, jitpack)
- **Sync**: Ktor 3.4.3 (клиент+сервер), Exposed 0.58.0, SQLite/PostgreSQL, HikariCP,
  Nimbus SRP-6a

## Структура репозитория

```
shared/       # ядро KMP: ssh/, sftp/, vault/, sync/, team/, terminal/, ai/ (+ai/local),
              # telnet/, serial/, tunnel/, snippet/, host/, files/
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android-приложение (MainActivity, манифест); applicationId app.skerry
server/       # self-hosted sync-сервер (Ktor, AGPL-3.0)
sync-wire/    # wire-контракт, общий для клиента и сервера
docs/         # HTML-прототипы (источник правды по UX) и дизайн-документы
```

HTML-прототипы в `docs/design/` (`Skerry Tablet.html`, `Skerry Logo.html`) — источник правды
по UI, реализуется 1:1.

## Сборка из исходников

Этот раздел — для контрибьюторов; пользователям проще взять готовый пакет из раздела
[Установка](#установка). Процесс разработки, соглашения по коммитам и заметки по упаковке —
в **[CONTRIBUTING.md](CONTRIBUTING.md)**.

Нужен **JDK 21** (`foojay-resolver` при необходимости подтянет сам). Для Android дополнительно
нужен Android SDK (`ANDROID_HOME`).

Desktop (пакеты собираются под ОС и архитектуру машины, на которой идёт сборка — соберите
на macOS/ARM, чтобы получить `.dmg`/arm64-пакет):

```bash
./gradlew :composeApp:run                                # запуск
./gradlew :composeApp:packageDistributionForCurrentOS    # .deb / .rpm / .msi / .dmg
./gradlew :composeApp:packageAppImage                    # портабельный Linux .AppImage
./gradlew :composeApp:packageFlatpak                     # однофайловый Linux .flatpak (нужны flatpak + flatpak-builder)
```

Android:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

Тесты (JUnit 5):

```bash
./gradlew test
```

## Sync-сервер

Skerry — local-first: приложение полностью работает без сервера. Когда vault нужен на
нескольких устройствах, вы поднимаете **свой** sync-сервер; вендорского облака нет.

Сервер zero-knowledge по построению: он хранит только шифротекст (обёрнутый `dataKey`,
зашифрованные записи vault) и метаданные синхронизации. Аутентификация — SRP-6a: сам пароль
никогда не передаётся, и расшифровать ваши данные сервер не может.

Быстрый старт (готовый мультиархитектурный образ с
[Docker Hub](https://hub.docker.com/r/secherkasov/skerry-sync), SQLite в именованном томе —
нулевая настройка):

```bash
docker run -d --name skerry-sync -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

Сервер слушает `http://localhost:8080` и несёт встроенную, полностью офлайновую
админ-консоль на `/console`. Для сборки из исходников выполните из корня репозитория
`docker compose up -d --build`; для PostgreSQL раскомментируйте сервис `db` и
postgres-переменные в [docker-compose.yml](docker-compose.yml). Сборка только сервера не
требует Android SDK: `./gradlew :server:run -PserverOnly`.

Полный гид по развёртыванию — справочник конфигурации, API-эндпоинты, TLS-терминация
(Caddy/nginx), бэкапы и модель приватности — в
**[server/README.md](server/README.md)** ([RU](server/README.ru.md)).

## Безопасность

Политика безопасности — как приватно сообщить об уязвимости, поддерживаемые версии, модель
угроз и честная заметка о статусе аудита — в **[SECURITY.md](SECURITY.md)**.

## Участие в разработке

Контрибьюции приветствуются — в **[CONTRIBUTING.md](CONTRIBUTING.md)** описаны настройка
окружения, команды сборки и тестов, структура модулей, соглашения по коммитам и процесс PR.

## Лицензии

- Клиенты (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync-сервер (`server/`) — [AGPL-3.0](server/LICENSE). Сервер под AGPL, чтобы форки,
  которые хостят его как сервис, возвращали свои изменения в проект.
