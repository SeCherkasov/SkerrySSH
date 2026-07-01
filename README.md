# Skerry

Опенсорсный кроссплатформенный SSH-клиент с единым ядром: Kotlin Multiplatform под капотом
и Compose Multiplatform UI. Один код ядра и один UI на **Desktop (Linux, Windows, macOS)**
и **Android**, паритет фич между платформами.

Версия — `0.1.0` (до первого релиза).

## Статус

- **Phase 1 (MVP)** — закрыт: SSH, SFTP, port forwarding, менеджер хостов/ключей, терминал,
  зашифрованный vault (мастер-пароль + биометрия).
- **Phase 2** — закрыт: self-hosted zero-knowledge sync, паринг устройств (QR), сниппеты,
  AI-ассистент (BYOK OpenAI) с per-host политиками.
- **Phase 3** — реализованы Telnet, serial (desktop через jSerialComm, Android через USB-OTG),
  autocomplete терминала и desktop-хоткеи. Отложены: **Mosh** (до после релиза) и
  **локальная AI-модель на desktop**.
- **iOS/iPadOS** — отложён: таргеты и `iosMain` удалены из исходников; крипта и хранилище
  остаются мультиплатформенно-готовыми, чтобы возврат был дешёвым.

## Возможности

**Подключения**
- SSH (sshj + BouncyCastle) — пароль, ключи, SSH-сертификаты
- SFTP — двухпанельный файловый менеджер (навигация в стиле mc/Total Commander)
- Port forwarding — локальные и удалённые туннели, глобальный менеджер туннелей
- Telnet (собственный кодек IAC-неготиации) и Serial (desktop + Android USB-OTG:
  CDC/FTDI/CP210x/CH34x)

**Терминал**
- Собственная grid-реализация с конформностью VT: line-drawing, Unicode/combining, SGR,
  OSC 8/4/52/104, мышь, bracketed-paste
- Табы, split-view (независимая вторая сессия), авто-реконнект для SSH, drag-reorder вкладок
- Живой статус-бар (cipher, версия сервера, throughput, RTT)
- Переключатель шрифта (JetBrains Mono / Hack), autocomplete с историей команд,
  Ctrl-R reverse-search, циклирование альтернатив

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

**Сниппеты и AI**
- Сниппеты с тегами, type-ahead и детекцией конфликтов хоткеев
- AI-ассистент (BYOK OpenAI, ключ в vault) с per-host политиками Strict/Balanced/Permissive/Off,
  SSE-стримингом, редактированием секретов и гейтом опасных команд перед выполнением

**Интерфейс**
- Локализация (i18n): английский и русский. Автоопределение по языку системы при старте +
  ручное переключение в настройках (Appearance → Language) на лету, без перезапуска. Строки —
  в Compose-ресурсах (`composeApp/src/commonMain/composeResources/values*`); смена локали в рантайме
  через `LocalAppLocale` (переопределяет системную локаль, читаемую окружением ресурсов). Ответы
  AI-ассистента (INFO/ASK) тоже следуют языку интерфейса.

Полный базис по фазам и решениям — [docs/skerry-product-brief.md](docs/skerry-product-brief.md).

## Технологии

- **Язык/UI**: Kotlin 2.3.21 (Multiplatform), Compose Multiplatform 1.11.1
- **Сборка**: Gradle 9.3.1, Android Gradle Plugin 9.0.1
- **JVM-таргет**: JDK 21 (`jvmToolchain(21)` во всех модулях, `JVM_21`)
- **Android**: minSdk 26 (Android 8.0), compileSdk/targetSdk 36
- **Ядро**: sshj 0.40.0, BouncyCastle 1.80.2, libsodium (ionspin KMP), okio, atomicfu
- **Serial**: jSerialComm 2.11.0 (desktop), usb-serial-for-android 3.9.0 (Android, jitpack)
- **Sync**: Ktor 3.4.3 (client+server), Exposed 0.58.0, SQLite/PostgreSQL, HikariCP, Nimbus SRP-6a

## Структура репозитория

```
shared/        # ядро KMP: ssh/, sftp/, vault/, sync/, terminal/, ai/, telnet/, tunnel/, snippet/, host/, files/
               #   commonMain + jvmSharedMain (общий JVM для desktop+Android) + desktopMain + androidMain
composeApp/    # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/    # Android-приложение (MainActivity, манифест); applicationId app.skerry
server/        # self-hosted sync-сервер (Ktor, AGPL-3.0)
docs/          # HTML-прототипы (источник правды по UX) и проектные документы
```

Прототипы в `docs/new/` (`Skerry.html`, `Skerry Mobile.html`, `Skerry Tablet.html`,
`Skerry Sync Console.html`) открываются в браузере и являются источником правды по дизайну —
UI реализуется по ним 1:1.

## Сборка и запуск

Нужен **JDK 21**. Отдельно ставить его не обязательно — Gradle через `foojay-resolver` сам
подтянет нужный тулчейн, даже если запущен на более новом JDK. Для Android-таргета нужен
Android SDK (переменная `ANDROID_HOME`).

**Desktop:**
```bash
./gradlew :composeApp:run                              # запустить приложение
./gradlew :composeApp:packageDistributionForCurrentOS  # .deb / .rpm / .msi / .dmg под текущую ОС
```
ProGuard/минификация для desktop-release осознанно отключены (ломали крипто-стек);
подробности — в комментарии `composeApp/build.gradle.kts`.

**Android** (нужен `ANDROID_HOME`):
```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

**Тесты** (JUnit 5):
```bash
./gradlew :shared:desktopTest :composeApp:desktopTest :server:test
```

## Sync-сервер

Self-hosted, разворачивается через Docker (по умолчанию SQLite в томе, нулевая настройка):

```bash
docker compose up -d --build
```

Образ собирается флагом `-PserverOnly` (исключает Android-модули, Android SDK не нужен) на
`eclipse-temurin:21`, работает от непривилегированного пользователя, отдаёт `/healthz`.
В проде обязательно задайте устойчивый `SKERRY_JWT_SECRET` (иначе токены инвалидируются при
рестарте). Для PostgreSQL раскомментируйте сервис `db` и postgres-переменные в
[docker-compose.yml](docker-compose.yml).

## Принципы

- **Local-first** — всё работает без сервера.
- **Zero-knowledge** — мастер-пароль не покидает устройство.
- **AI under policy** — вывод модели считается недоверенным; действия только после подтверждения.
- **Паритет платформ** — фича не готова, пока не работает везде.

## Лицензии

- Клиенты (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync-сервер (`server/`) — [AGPL-3.0](server/LICENSE)
</content>
