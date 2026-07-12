# Skerry

**English** · [Русский](README.ru.md)

[![CI](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/SeCherkasov/SkerrySSH)](../../releases/latest)
[![Clients: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-blue)](LICENSE)
[![Server: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-blue)](server/LICENSE)

An open-source, cross-platform SSH client with a single core: Kotlin Multiplatform under the
hood, Compose Multiplatform UI on top. One core codebase and one UI across
**Desktop (Linux, Windows, macOS)** and **Android**, with feature parity between platforms.

- **Local-first** — everything works without a server or an account.
- **Zero-knowledge** — the master password never leaves the device.
- **AI under policy** — model output is treated as untrusted; actions require confirmation;
  a fully local model is an option.
- **Platform parity** — a feature isn't done until it works everywhere.

## How Skerry compares

|  | Skerry | Termius | PuTTY | Tabby |
|---|---|---|---|---|
| Open source | GPL-3.0 (clients), AGPL-3.0 (server) | no | MIT | MIT |
| Platforms | Linux, Windows, macOS, Android | Windows, macOS, Linux, iOS, Android | Windows, Unix | Windows, macOS, Linux |
| First release | 2026 (v0.1.x) | 2011 | 1999 | 2017 |
| Price | free | free tier; paid from $10/mo | free | free |
| Works without an account | yes | partial — local use only; sync and AI need an account | yes | yes |
| Encrypted vault | yes, always on (Argon2id + XChaCha20-Poly1305) | yes | no | optional, opt-in |
| Sync | self-hosted server, zero-knowledge | vendor cloud, E2E (paid) | no | config sync via self-hostable Tabby Web (E2E opt-in) |
| Team sharing | yes, E2E | paid tier | no | no |
| SFTP | dual-pane UI | yes | command-line only (`psftp`) | built-in panel |
| Port forwarding | local, remote, dynamic | yes | yes | yes |
| Serial / Telnet | yes / yes | yes / yes | yes / yes | yes / yes |
| Mosh | no | yes | no | no |
| AI assistant | optional: BYOK cloud or a fully local model | yes, cloud-side; account required | no | no |

*Competitor data collected from the projects' official sites and repositories on 2026-07-12.
Spotted an error? Please open a PR.*

## Status

Actively developed for **Linux**, **Windows**, **macOS**, and **Android**. **iOS/iPadOS** is
planned.

## Install

Grab a package from the **[latest release](../../releases/latest)**:

| Platform | Arch | Files |
|---|---|---|
| Linux | x86_64 | `.deb`, `.rpm`, `.AppImage`, `.flatpak` |
| Linux | arm64 | `.deb`, `.rpm`, `.AppImage` |
| Windows | x64 | `.msi` |
| macOS | Apple Silicon | `Skerry-*-arm64.dmg` |
| macOS | Intel | `Skerry-*-x64.dmg` |
| Android | arm64-v8a | `.apk` (signed; sideload) |

- **macOS builds are unsigned and not notarized** (no Apple Developer account yet), so
  Gatekeeper blocks the first launch: right-click the app → Open, or allow it under
  System Settings → Privacy & Security. The `.dmg` file name carries a `1.x.y` version —
  it is the same `0.x` release (macOS packaging requires a major version ≥ 1).
- The Windows `.msi` is not code-signed either; SmartScreen may warn on first run.
- Verify downloads against the attached checksums: `sha256sum -c --ignore-missing SHA256SUMS.txt`.

Building it yourself is also easy — see [Building from source](#building-from-source).

## Screenshots

![Terminal with host manager, session tabs, and live metrics panel](docs/screenshots/desktop-terminal.png)

<details>
<summary>More screenshots</summary>

![Dual-pane SFTP commander](docs/screenshots/desktop-sftp.png)

![Port forwarding manager](docs/screenshots/desktop-tunnels.png)

![Vault: keys, passwords, certificates](docs/screenshots/desktop-vault.png)

![AI assistant with per-host policies](docs/screenshots/desktop-ai.png)

| Host list | Terminal |
|---|---|
| ![Host list with groups and tags](docs/screenshots/mobile-hosts.png) | ![Mobile terminal](docs/screenshots/mobile-terminal.png) |

</details>

## Features

- **Connections** — SSH with jump hosts (ProxyJump) and SSH certificates; SFTP (dual-pane
  commander); port forwarding: local, remote, dynamic/SOCKS; Telnet; serial (desktop and
  Android USB-OTG).
- **Terminal** — custom grid emulation, session tabs with split view, SSH auto-reconnect,
  scrollback search, live host metrics.
- **Vault** — always-on encryption (Argon2id + XChaCha20-Poly1305) for keys, passwords,
  identities, and certificates; biometric unlock on Android.
- **Sync** — optional and self-hosted, zero-knowledge, live push over WebSocket, device
  pairing via QR. See [Sync server](#sync-server).
- **Teams** — end-to-end encrypted sharing of hosts and snippets within a team.
- **Snippets & AI** — command library with type-ahead in the terminal; AI assistant with
  per-host policies — bring your own OpenAI key or run a local model.
  See [AI and privacy](#ai-and-privacy).
- **Localization** — English and Russian UI; the assistant replies in the UI language.

The full, detailed feature list lives in **[docs/FEATURES.md](docs/FEATURES.md)**.

## AI and privacy

The vault promise ("the master password never leaves the device") and a cloud AI assistant
only coexist under explicit rules:

- **Nothing is sent anywhere automatically.** A request contains only the text you type
  into the AI bar or chat, plus a fixed system prompt. Terminal output, host lists, and
  vault contents are never attached.
- **Cloud mode is BYOK**: your own OpenAI API key; requests go directly from the app to the
  endpoint you configured.
- **Per-host policies** decide where a request may go:
  - **Strict** (default for new hosts) — local model only; nothing leaves the device.
  - **Balanced** — cloud allowed; obvious secrets (private keys, tokens, `password=…`) are
    redacted from the prompt before sending. Redaction is best-effort pattern matching,
    not a guarantee.
  - **Permissive** — cloud allowed without redaction, for non-sensitive systems.
  - **Off** — AI is hidden for this host.
- The global quick-chat always redacts secrets, even when using the local model.
- **Local mode**: the app downloads GGUF models (Qwen3, Phi-4 Mini) and runs them on-device
  via llama.cpp — no data leaves the device at all.
- **Model output is untrusted**: a suggested command never runs by itself — it requires
  explicit confirmation, and commands classified as risky require an extra confirmation.

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

## Building from source

This section is for contributors — regular users should grab a package from
[Install](#install). See **[CONTRIBUTING.md](CONTRIBUTING.md)** for the development
workflow, commit conventions, and packaging notes.

Requires **JDK 21** (`foojay-resolver` fetches one if needed). Android additionally needs
the Android SDK (`ANDROID_HOME`).

Desktop (packages are produced for the OS and CPU architecture of the machine the build
runs on — build on macOS/ARM to get a `.dmg`/arm64 package):

```bash
./gradlew :composeApp:run                                # run
./gradlew :composeApp:packageDistributionForCurrentOS    # .deb / .rpm / .msi / .dmg
./gradlew :composeApp:packageAppImage                    # portable Linux .AppImage
./gradlew :composeApp:packageFlatpak                     # single-file Linux .flatpak (needs flatpak + flatpak-builder)
```

Android:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

Tests (JUnit 5):

```bash
./gradlew test
```

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

## Security

The security policy — how to report a vulnerability privately, supported versions, the
threat model, and an honest note on audit status — lives in **[SECURITY.md](SECURITY.md)**.

## Contributing

Contributions are welcome — see **[CONTRIBUTING.md](CONTRIBUTING.md)** for the environment
setup, build and test commands, module structure, commit conventions, and the PR process.

## Licenses

- Clients (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync server (`server/`) — [AGPL-3.0](server/LICENSE). The server is AGPL so that forks
  which host it as a service contribute their changes back to the project.
