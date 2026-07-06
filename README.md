# Skerry

**English** · [Русский](README.ru.md)

An open-source, cross-platform SSH client with a single core: Kotlin Multiplatform under the
hood and a Compose Multiplatform UI. One core codebase and one UI across
**Desktop (Linux, Windows)** and **Android**, with feature parity between platforms.

Version — `0.1.0` (pre first release).

## Status

Actively developed for **Linux**, **Windows**, and **Android**. **macOS** and **iOS/iPadOS**
are planned.

## Screenshots

![Terminal with host manager, session tabs, and a live metrics panel](docs/screenshots/desktop-terminal.png)

![Dual-pane SFTP commander](docs/screenshots/desktop-sftp.png)

![Port forwarding manager](docs/screenshots/desktop-tunnels.png)

![Vault: keys, passwords, certificates](docs/screenshots/desktop-vault.png)

![AI assistant with per-host policies](docs/screenshots/desktop-ai.png)

| Host list | Terminal |
|---|---|
| ![Host list with groups and tags](docs/screenshots/mobile-hosts.png) | ![Mobile terminal](docs/screenshots/mobile-terminal.png) |

Screenshots are rendered from the live UI by an offscreen harness (`scripts/gen-screenshots.sh`),
with no network and no master password. Re-run to refresh: `scripts/gen-screenshots.sh`.

## Features

**SSH and connections**
- SSH (sshj + BouncyCastle), SSH certificates
- SFTP (dual-pane commander)
- Port forwarding: local (`-L`), remote (`-R`), dynamic/SOCKS (`-D`)
- Telnet (custom IAC-negotiation codec), serial (desktop jSerialComm; Android USB-OTG:
  CDC/FTDI/CP210x/CH34x)

**Terminal**
- Custom grid emulation, VT conformance: line-drawing, Unicode/combining, SGR,
  OSC 8/4/52/104, bracketed-paste, mouse
- Tabs, split view (independent second session), auto-reconnect for SSH, drag-reorder tabs
- Live status bar (cipher, server version, throughput, RTT)
- Font switcher (JetBrains Mono / Hack), a set of color themes (incl. Catppuccin Mocha,
  Dracula, Tokyo Day), autocomplete with command history, Ctrl-R reverse-search, cycling
  through alternatives

**Storage and security**
- Local encrypted vault: Argon2id → XChaCha20-Poly1305 (libsodium), zero-knowledge
- Master password + biometrics (Android BiometricPrompt + Keystore), reset/recovery
- Targeted FLAG_SECURE on sensitive screens
- Host, group, and tag manager; keychain + identities (username + credential)

**Sync (self-hosted, optional)**
- Zero-knowledge E2E synchronization: Argon2id → masterKey → authKey/dataKey, XChaCha20-Poly1305
- SRP-6a authentication (the server stores only the verifier), JWT sessions
- Live-sync push-on-change over WebSocket, tombstone propagation, cursor persistence,
  selective sync by record type
- Device pairing via QR (ZXing + CameraX + ML Kit on-device), admin console

**Teams (sharing, optional)**
- E2E zero-knowledge sharing of hosts and snippets within a team, on top of sealed-envelope
  invitations; owner/member roles, ACL revocation. Schema — [docs/skerry-sync-design.md](docs/skerry-sync-design.md) §6

**Snippets and AI**
- Command library with snippet type-ahead in the terminal
- AI assistant (BYOK OpenAI, per-host policies Strict/Balanced/Permissive/Off) with SSE streaming
- On-device local AI: the app downloads GGUF models itself and runs them via llama.cpp
  (catalog: Qwen3, Phi-4 Mini) — the Strict policy works offline

**Localization**
- Strings in compose-resources (`composeApp/src/commonMain/composeResources/values*`);
  a language switcher (`LocalAppLocale`) for the UI and for the AI assistant's reply language (INFO/ASK)

For the full rationale behind decisions and phases, see [docs/skerry-product-brief.md](docs/skerry-product-brief.md).

## Tech stack

- **Language/UI**: Kotlin 2.x, Compose Multiplatform 1.11.1
- **Build**: Gradle 9.3.1, Android Gradle Plugin 9.0.1
- **JVM target**: JDK 21 (`jvmToolchain(21)` in all modules, `JVM_21`)
- **Android**: minSdk 26 (Android 8.0), compileSdk/targetSdk 36
- **Core**: sshj 0.40.0, BouncyCastle 1.80.2, libsodium (ionspin KMP), okio, atomicfu
- **Serial**: jSerialComm 2.11.0 (desktop), usb-serial-for-android 3.9.0 (Android, jitpack)
- **Sync**: Ktor 3.4.3 (client+server), Exposed 0.58.0, SQLite/PostgreSQL, HikariCP, Nimbus SRP-6a

## Repository layout

```
shared/       # KMP core: ssh/, sftp/, vault/, sync/, team/, terminal/, ai/ (+ai/local — on-device LLM),
              # telnet/, serial/, tunnel/, snippet/, host/, files/
              # commonMain + jvmSharedMain (shared JVM for desktop+Android) + desktopMain + androidMain
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android app (MainActivity, manifest); applicationId app.skerry
server/       # self-hosted sync server (Ktor, AGPL-3.0)
docs/         # HTML prototypes (source of truth for UX) and design documents
```

The prototypes in `docs/new/` (`Skerry.html`, `Skerry Mobile.html`, `Skerry Tablet.html`,
`Skerry Sync Console.html`) open in a browser and are the source of truth for the design —
the UI is implemented 1:1.

## Build and run

Requires **JDK 21**. `foojay-resolver` will fetch a JDK if needed.
Android requires the Android SDK (`ANDROID_HOME`).

Desktop:

```
./gradlew :composeApp:run
./gradlew :composeApp:packageDistributionForCurrentOS   # .deb / .rpm / .msi / .dmg
```

ProGuard/minification for the desktop release is configured in `composeApp/build.gradle.kts`.

Android (requires `ANDROID_HOME`):

```
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

Tests (JUnit 5):

```
./gradlew test
```

### Sync server

Self-hosted, SQLite by default:

```
docker compose up -d --build
```

A server-only build uses `-PserverOnly` (no Android SDK), the `eclipse-temurin:21` image, and a
`/healthz` healthcheck. Set `SKERRY_JWT_SECRET`. For PostgreSQL, see the `db` service and the
postgres variables in [docker-compose.yml](docker-compose.yml).

## Principles

- **Local-first** — everything works without a server.
- **Zero-knowledge** — the master password never leaves the device.
- **AI under policy** — model output is treated as untrusted; actions only after confirmation.
- **Platform parity** — a feature isn't done until it works everywhere.

## Licenses

- Clients (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync server (`server/`) — [AGPL-3.0](server/LICENSE)
