# Skerry

**English** · [Русский](README.ru.md)

[![CI](https://github.com/SeCherkasov/Skerry/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/Skerry/actions/workflows/ci.yml)
[![Clients: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-blue)](LICENSE)
[![Server: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-blue)](server/LICENSE)

An open-source, cross-platform SSH client with a single core: Kotlin Multiplatform under the
hood, Compose Multiplatform UI on top. One core codebase and one UI across
**Desktop (Linux, Windows)** and **Android**, with feature parity between platforms.

Current version — `0.1.0` (pre first release).

## Status

Actively developed for **Linux**, **Windows**, and **Android**. **macOS** and **iOS/iPadOS**
are planned.

## Screenshots

![Terminal with host manager, session tabs, and live metrics panel](docs/screenshots/desktop-terminal.png)

![Dual-pane SFTP commander](docs/screenshots/desktop-sftp.png)

![Port forwarding manager](docs/screenshots/desktop-tunnels.png)

![Vault: keys, passwords, certificates](docs/screenshots/desktop-vault.png)

![AI assistant with per-host policies](docs/screenshots/desktop-ai.png)

| Host list | Terminal |
|---|---|
| ![Host list with groups and tags](docs/screenshots/mobile-hosts.png) | ![Mobile terminal](docs/screenshots/mobile-terminal.png) |

## Features

**Connections**
- SSH (sshj + BouncyCastle), SSH certificates
- SFTP (dual-pane commander)
- Port forwarding: local (`-L`), remote (`-R`), dynamic/SOCKS (`-D`)
- Telnet (custom IAC-negotiation codec), serial (jSerialComm on desktop; USB-OTG on Android:
  CDC/FTDI/CP210x/CH34x)

**Terminal**
- Custom grid emulation: VT line-drawing, Unicode/combining characters, SGR,
  OSC 8/4/52/104, bracketed paste
- Session tabs with split view, SSH auto-reconnect, drag-to-reorder, live host metrics (RTT)
- JetBrains Mono rendering, scrollback reverse-search

**Vault**
- XChaCha20-Poly1305, zero-knowledge: the master password never leaves the device
- Biometric unlock (BiometricPrompt) with reset/recovery flow, `FLAG_SECURE` on Android
- Keys, passwords, identities, certificates

**Sync (self-hosted, optional)**
- Zero-knowledge sync: authKey/dataKey split, XChaCha20-Poly1305 payloads,
  SRP-6a authentication (server stores a verifier, never the password), JWT sessions
- Live sync: push-on-change over WebSocket, tombstone propagation, cursor persistence,
  selective sync by record type
- Device pairing via QR (ZXing + CameraX + ML Kit, on-device), admin console
- See [Sync server](#sync-server) below

**Teams (sharing, optional)**
- E2E zero-knowledge sharing of hosts and snippets within a team, on top of sealed-envelope
  invitations; owner/member roles, ACL revocation

**Snippets & AI**
- Command library with snippet type-ahead in the terminal
- AI assistant (BYOK OpenAI, per-host policies Strict/Balanced/Permissive/Off) with SSE
  streaming
- On-device local AI: the app downloads GGUF models itself and runs them via llama.cpp
  (catalog: Qwen3, Phi-4 Mini) — the Strict policy works fully offline

**Localization**
- Strings live in compose-resources (`composeApp/src/commonMain/composeResources/values*`);
  a language switcher (`LocalAppLocale`) drives both the UI and the AI assistant's reply
  language (INFO/ASK)

## Tech stack

- **Language/UI**: Kotlin 2.x, Compose Multiplatform 1.11.1
- **Build**: Gradle 9.3.1, Android Gradle Plugin 9.0.1
- **JVM target**: JDK 21 (`jvmToolchain(21)` in all modules, `JVM_21`)
- **Android**: minSdk 26 (Android 8.0), compileSdk/targetSdk 36
- **Core**: sshj 0.40.0, BouncyCastle 1.80.2, libsodium (ionspin KMP), okio, atomicfu
- **Serial**: jSerialComm 2.11.0 (desktop), usb-serial-for-android 3.9.0 (Android, jitpack)
- **Sync**: Ktor 3.4.3 (client+server), Exposed 0.58.0, SQLite/PostgreSQL, HikariCP,
  Nimbus SRP-6a

## Repository layout

```
shared/       # KMP core: ssh/, sftp/, vault/, sync/, team/, terminal/, ai/ (+ai/local),
              # telnet/, serial/, tunnel/, snippet/, host/, files/
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android app (MainActivity, manifest); applicationId app.skerry
server/       # self-hosted sync server (Ktor, AGPL-3.0)
sync-wire/    # wire contract shared by client and server
docs/         # HTML prototypes (source of truth for UX) and design documents
```

HTML prototypes in `docs/design/` (`Skerry Tablet.html`, `Skerry Logo.html`) are the source
of truth for the UI, built 1:1.

## Building

Requires **JDK 21** (`foojay-resolver` fetches one if needed). Android additionally needs
the Android SDK (`ANDROID_HOME`).

Desktop:

```bash
./gradlew :composeApp:run                                # run
./gradlew :composeApp:packageDistributionForCurrentOS    # .deb / .rpm / .msi / .dmg
./gradlew :composeApp:packageAppImage                    # portable Linux .AppImage
./gradlew :composeApp:packageFlatpak                     # single-file Linux .flatpak (needs flatpak + flatpak-builder)
```

The Flatpak is a hermetic source build: `flatpak-builder` compiles the app inside the sandbox
from the committed offline sources (`composeApp/flatpak/flatpak-sources.json`). Regenerate that
list with `composeApp/flatpak/regenerate-sources.sh` whenever desktop dependencies change.

ProGuard/minification is intentionally disabled for the desktop release — it broke the crypto
stack; see the comment in `composeApp/build.gradle.kts`.

Android:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

Tests (JUnit 5):

```bash
./gradlew test
```

Releases: pushing a `v*` tag runs the release workflow, which builds `.deb`/`.rpm`/`.msi`,
a signed `.apk`, and `SHA256SUMS`, and publishes them as a draft GitHub Release.

## Sync server

Skerry is local-first — the app is fully functional without any server. When you want your
vault on more than one device, you run your **own** sync server; there is no vendor cloud.

The server is zero-knowledge by design: it stores only ciphertext (the wrapped `dataKey`,
encrypted vault records) and sync metadata. It authenticates clients with SRP-6a — the
password itself is never transmitted — and cannot decrypt anything you store.

Quick start (single container, SQLite in a named volume — zero configuration):

```bash
export SKERRY_JWT_SECRET="$(openssl rand -base64 48)"    # required: server refuses the default
export SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)"      # optional: enables the /console dashboard
docker compose up -d --build
```

The server listens on `http://localhost:8080` and ships a built-in, fully offline admin
console at `/console`. For PostgreSQL, uncomment the `db` service and the postgres variables
in [docker-compose.yml](docker-compose.yml). A server-only Gradle build needs no Android SDK:
`./gradlew :server:run -PserverOnly`.

The full deployment guide — configuration reference, API endpoints, TLS termination
(Caddy/nginx), backups, and the privacy model — lives in
**[server/README.md](server/README.md)**.

## Principles

- **Local-first** — everything works without a server.
- **Zero-knowledge** — the master password never leaves the device.
- **AI under policy** — model output is treated as untrusted; actions require confirmation.
- **Platform parity** — a feature isn't done until it works everywhere.

## Licenses

- Clients (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync server (`server/`) — [AGPL-3.0](server/LICENSE)
