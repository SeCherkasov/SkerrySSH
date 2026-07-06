# Skerry

[English](README.md) · **Русский**

Опенсорсный кроссплатформенный SSH-клиент с единым ядром: Kotlin Multiplatform под капотом
и Compose Multiplatform UI. Один код ядра и один UI на **Desktop (Linux, Windows)**
и **Android**, паритет фич между платформами.

Версия — `0.1.0` (до первого релиза).

## Статус

В активной разработке под **Linux**, **Windows** и **Android**. **macOS** и **iOS/iPadOS**
— в планах.

## Скриншоты

![Терминал с менеджером хостов, вкладками сессий и панелью живых метрик](docs/screenshots/desktop-terminal.png)

![Двухпанельный SFTP Commander](docs/screenshots/desktop-sftp.png)

![Менеджер port forwarding](docs/screenshots/desktop-tunnels.png)

![Vault: ключи, пароли, сертификаты](docs/screenshots/desktop-vault.png)

![AI-ассистент с per-host политиками](docs/screenshots/desktop-ai.png)

| Список хостов | Терминал |
|---|---|
| ![Список хостов с группами и тегами](docs/screenshots/mobile-hosts.png) | ![Мобильный терминал](docs/screenshots/mobile-terminal.png) |

Скриншоты рендерятся из живого UI офскрин-харнессом (`scripts/gen-screenshots.sh`),
без сети и мастер-пароля. Перезапустить для обновления: `scripts/gen-screenshots.sh`.

## Возможности

**SSH и подключения**
- SSH (sshj + BouncyCastle), SSH-сертификаты
- SFTP (двухпанельный commander)
- Port forwarding: local (`-L`), remote (`-R`), dynamic/SOCKS (`-D`)
- Telnet (свой кодек IAC-неготиации), serial (desktop jSerialComm; Android USB-OTG:
  CDC/FTDI/CP210x/CH34x)

**Терминал**
- Своя grid-эмуляция, конформность VT: line-drawing, Unicode/combining, SGR,
  OSC 8/4/52/104, bracketed-paste, мышь
- Вкладки, split-view (независимая вторая сессия), авто-реконнект для SSH, drag-reorder вкладок
- Живой статус-бар (cipher, версия сервера, throughput, RTT)
- Переключатель шрифта (JetBrains Mono / Hack), набор цветовых тем (в т.ч. Catppuccin Mocha,
  Dracula, Tokyo Day), autocomplete с историей команд, Ctrl-R reverse-search, циклирование
  альтернатив

**Хранилище и безопасность**
- Локальный зашифрованный vault: Argon2id → XChaCha20-Poly1305 (libsodium), zero-knowledge
- Мастер-пароль + биометрия (Android BiometricPrompt + Keystore), reset/recovery
- Точечный FLAG_SECURE на чувствительных экранах
- Менеджер хостов, групп и тегов; keychain + identities (username + credential)

**Sync (self-hosted, опционально)**
- Zero-knowledge E2E синхронизация: Argon2id → masterKey → authKey/dataKey, XChaCha20-Poly1305
- SRP-6a аутентификация (сервер хранит только verifier), JWT-сессии
- Live-sync push-on-change через WebSocket, tombstone-propagation, персист курсора,
  селективный синк по типам записей
- Паринг устройств по QR (ZXing + CameraX + ML Kit on-device), admin-консоль

**Teams (шеринг, опционально)**
- E2E zero-knowledge шеринг хостов и сниппетов внутри команды поверх sealed-envelope
  приглашений; роли owner/member, ACL-отзыв. Схема — [docs/skerry-sync-design.md](docs/skerry-sync-design.md) §6

**Сниппеты и AI**
- Библиотека команд со snippet type-ahead в терминале
- AI-ассистент (BYOK OpenAI, per-host политики Strict/Balanced/Permissive/Off) с SSE-стримингом
- Локальный AI на устройстве: приложение само качает GGUF-модели и запускает их через llama.cpp
  (каталог: Qwen3, Phi-4 Mini) — Strict-политика работает офлайн

**Локализация**
- Строки в compose-resources (`composeApp/src/commonMain/composeResources/values*`);
  переключатель языка (`LocalAppLocale`) для UI и языка ответов AI-ассистента (INFO/ASK)

Подробности решений и фаз — [docs/skerry-product-brief.md](docs/skerry-product-brief.md).

## Технологии

- **Язык/UI**: Kotlin 2.x, Compose Multiplatform 1.11.1
- **Сборка**: Gradle 9.3.1, Android Gradle Plugin 9.0.1
- **JVM-таргет**: JDK 21 (`jvmToolchain(21)` во всех модулях, `JVM_21`)
- **Android**: minSdk 26 (Android 8.0), compileSdk/targetSdk 36
- **Ядро**: sshj 0.40.0, BouncyCastle 1.80.2, libsodium (ionspin KMP), okio, atomicfu
- **Serial**: jSerialComm 2.11.0 (desktop), usb-serial-for-android 3.9.0 (Android, jitpack)
- **Sync**: Ktor 3.4.3 (client+server), Exposed 0.58.0, SQLite/PostgreSQL, HikariCP, Nimbus SRP-6a

## Структура репозитория

```
shared/       # ядро KMP: ssh/, sftp/, vault/, sync/, team/, terminal/, ai/ (+ai/local — on-device LLM),
              # telnet/, serial/, tunnel/, snippet/, host/, files/
              # commonMain + jvmSharedMain (общий JVM для desktop+Android) + desktopMain + androidMain
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android-приложение (MainActivity, манифест); applicationId app.skerry
server/       # self-hosted sync-сервер (Ktor, AGPL-3.0)
docs/         # HTML-прототипы (источник правды по UX) и проектные документы
```

Прототипы в `docs/new/` (`Skerry.html`, `Skerry Mobile.html`, `Skerry Tablet.html`,
`Skerry Sync Console.html`) открываются в браузере и являются источником правды по дизайну —
UI реализуется 1:1.

## Сборка и запуск

Нужен **JDK 21**. `foojay-resolver` при необходимости сам подтянет JDK.
Для Android нужен Android SDK (`ANDROID_HOME`).

Desktop:

```
./gradlew :composeApp:run
./gradlew :composeApp:packageDistributionForCurrentOS   # .deb / .rpm / .msi / .dmg
```

ProGuard/минификация для desktop-release настраивается в `composeApp/build.gradle.kts`.

Android (нужен `ANDROID_HOME`):

```
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

Тесты (JUnit 5):

```
./gradlew test
```

### Sync-сервер

Self-hosted, SQLite по умолчанию:

```
docker compose up -d --build
```

Сборка только сервера — `-PserverOnly` (без Android SDK), образ `eclipse-temurin:21`,
healthcheck `/healthz`. Задайте `SKERRY_JWT_SECRET`. PostgreSQL — сервис `db` и
postgres-переменные в [docker-compose.yml](docker-compose.yml).

## Принципы

- **Local-first** — всё работает без сервера.
- **Zero-knowledge** — мастер-пароль не покидает устройство.
- **AI under policy** — вывод модели считается недоверенным; действия только после подтверждения.
- **Паритет платформ** — фича не готова, пока не работает везде.

## Лицензии

- Клиенты (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync-сервер (`server/`) — [AGPL-3.0](server/LICENSE)
