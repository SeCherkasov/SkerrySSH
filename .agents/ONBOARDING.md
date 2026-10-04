# Skerry codebase map

## Overview

Skerry is a local-first SSH client sharing one Kotlin Multiplatform core between Linux, Windows,
macOS and Android. It includes terminal, SFTP, tunnels, Mosh/Telnet/serial/local shell, VNC/RDP,
an encrypted vault, optional self-hosted sync and teams, runbooks, snippets and policy-controlled AI.

## Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.4, JDK 21 |
| UI | Compose Multiplatform 1.9 / Material 3 |
| Android | AGP 9.1, minSdk 26, targetSdk 36 |
| Networking | Ktor 3.5, sshj, custom protocol clients |
| Server | Ktor/Netty, Exposed, SQLite or PostgreSQL |
| Security | libsodium, Argon2id, XChaCha20-Poly1305, BouncyCastle |
| Quality | kotlin.test on JUnit 5, detekt, Kover, Android lint, CodeQL |

Exact versions are in `gradle/libs.versions.toml`.

## Modules and entry points

| Area | Purpose / entry point |
|---|---|
| `shared/` | KMP domain, stores, protocols and platform adapters |
| `composeApp/` | Shared UI plus desktop/Android implementations |
| `composeApp/src/desktopMain/.../main.kt` | Desktop application entry point |
| `androidApp/.../MainActivity.kt` | Android application entry point |
| `server/.../Application.kt` | Ktor sync-server entry point and application module |
| `sync-wire/` | Serializable client/server wire DTOs |
| `docs/coding-guidelines.md` | Architecture, concurrency, security and UI rules |
| `tools/harness/` | Content-pinned development gate and its self-tests |

The common pattern is UI/controller → injected common contract/store → protocol or persistence
implementation in `shared` → platform adapter where required. Server requests enter Ktor routes,
pass validation/auth/rate limits, call repositories/services, then serialize wire DTOs.

## Source sets

- `commonMain`: portable contracts, models and logic.
- `jvmSharedMain`: JVM code shared by desktop and Android.
- `desktopMain` / `androidMain`: platform implementations.
- `commonTest`, `desktopTest`, and Android/server JVM tests mirror behavior boundaries.

## Common commands

```bash
./gradlew :composeApp:run
ANDROID_HOME=/path/to/sdk ./gradlew :androidApp:installDebug
./gradlew :server:run -PserverOnly
./gradlew test allTests
./gradlew detektAll
./gradlew koverHtmlReport
tools/harness/gate.py status
tools/harness/gate.py run
python3 tools/harness/selftest.py
```

Use `tools/harness/gate.py run` for final verification; a direct Gradle command is only an
iteration and does not create a gate record.

## Where to look

| Goal | Start at |
|---|---|
| Change a protocol or domain behavior | matching package in `shared/src/commonMain` |
| Add a platform implementation | matching `desktopMain` / `androidMain` package |
| Change desktop or mobile UI | `composeApp/src/commonMain/kotlin/app/skerry/ui` |
| Add or change server API | `server/src/main/.../routes` plus repository/service |
| Change the sync contract | `sync-wire/` and both client/server consumers |
| Add UI copy | all locale resource sets in the same change |
| Add a test | matching test source set and package |
| Change the development process | `CLAUDE.md`, `tools/harness/`, and self-tests |

## Non-obvious constraints

- The normal Gradle settings graph includes Android even for desktop builds; an Android SDK is
  required unless using `-PserverOnly` for the server.
- Never run concurrent Gradle builds on this workstation.
- ProGuard/minification is disabled deliberately because it breaks the desktop crypto stack.
- A new keyboard shortcut also needs a row in Settings → Keyboard.
- New UI copy must preserve all five locales and use project design primitives/tokens.
- Live platform verification gaps must be stated explicitly; a green JVM suite is not a device test.
