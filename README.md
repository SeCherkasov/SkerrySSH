<div align="center">

<img src="docs/img/banner.png" alt="Skerry — the SSH client, the way it should be. Terminal · SFTP · tunnels · VNC/RDP · encrypted vault · no accounts, no cloud. Linux · Windows · macOS · Android" width="820">

**English** · [Русский](README.ru.md)

[![CI](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/SeCherkasov/SkerrySSH)](../../releases/latest)
[![Clients: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-blue)](LICENSE)
[![Server: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-blue)](server/LICENSE)

</div>

---

Open-source SSH client with a single core (Kotlin Multiplatform) for every platform:
**Linux · Windows · macOS · Android**.

- **Local-first** — fully functional without an account or external services; sync is optional
  and self-hosted.
- **Zero-knowledge** — vault sealed with Argon2id + XChaCha20-Poly1305; the master password and
  encryption keys never leave the device.
- **AI under policy** — model output is treated as untrusted input: command execution requires
  explicit confirmation; local inference (llama.cpp) rules out outbound traffic.

---

## How Skerry compares

| | Skerry | Termius | PuTTY | Tabby |
|---|---|---|---|---|
| **License** | GPL-3.0 / AGPL-3.0 | proprietary | MIT | MIT |
| **Platforms** | Linux · Windows · macOS · Android | Linux · Windows · macOS · Android · iOS | Windows · Unix | Linux · Windows · macOS |
| **Price** | free | from $10/mo | free | free |
| **Without an account** | ✅ | ⚠️ local only | ✅ | ✅ |
| **Encrypted vault** | ✅ | ✅ | ❌ | ⚠️ opt-in |
| **Sync** | ✅ self-hosted | ✅ vendor cloud | ❌ | ✅ self-hosted |
| **Team sharing** | ✅ | ⚠️ paid | ❌ | ❌ |
| **SFTP** | ✅ dual-pane | ✅ | ⚠️ CLI only | ✅ |
| **Mosh** | ✅ | ✅ | ❌ | ❌ |
| **VNC / RDP** | ✅ | ❌ | ❌ | ❌ |
| **Live session sharing** | ✅ | ⚠️ paid | ❌ | ❌ |
| **AI assistant** | ✅ local or BYOK | ⚠️ cloud only | ❌ | ❌ |

*Competitor data from the projects' official sites, 2026-07-23. If something is wrong, send a PR
with the correction or open an [issue](../../issues/new).*

---

## Status

Actively developed for **Linux**, **Windows**, **macOS** and **Android**.

**iOS/iPadOS** is deferred for the lack of hardware to build and debug on — the project has
no iOS targets.

---

## Install

Packages are in the **[latest release](../../releases/latest)**:

| Platform | Arch | Files |
|---|---|---|
| Linux | x86_64 | `.deb`, `.rpm`, `.AppImage` |
| Linux | arm64 | `.deb`, `.rpm`, `.AppImage` |
| Windows | x64 | `.msi`, `.zip` |
| macOS | Apple Silicon | `.dmg` |
| macOS | Intel | `.dmg` |
| Android | arm64-v8a | `.apk` |

- **Signing.** Builds are unsigned: there is no Apple Developer account. Gatekeeper blocks the
  first launch of a macOS build — right-click the app → Open, or allow it under System Settings
  → Privacy & Security. The Windows `.msi` is unsigned too, SmartScreen warns on first run.
- **macOS bundle version.** Get Info shows `1.x.y` instead of `0.x`: packaging requires a major
  version ≥ 1. The real version is on the About screen.
- **Checksums.** `sha256sum -c --ignore-missing SHA256SUMS.txt`

Building from source is covered [below](#building-from-source).

---

## Screenshots

![Terminal with host manager, session tabs, and live metrics panel](docs/screenshots/terminal.webp)

<details>
<summary>More screenshots</summary>

![Four split panes with synchronized input](docs/screenshots/panes.webp)

![Dual-pane SFTP commander](docs/screenshots/sftp.webp)

![Port forwarding manager](docs/screenshots/tunnels.webp)

![Vault: keys, passwords, certificates](docs/screenshots/vault.webp)

![Host monitoring: CPU, memory, disk, services, containers](docs/screenshots/monitor.webp)

![Runbooks: multi-step procedures with variables](docs/screenshots/runbooks.webp)

![Snippets with variables and shortcuts](docs/screenshots/snippets.webp)

![AI assistant with per-host policies](docs/screenshots/ai.webp)

![Team: members, roles, access scopes, shared vault](docs/screenshots/teams.webp)

| Host list | Terminal |
|---|---|
| ![Host list with groups and tags](docs/screenshots/mobile-hosts.webp) | ![Mobile terminal](docs/screenshots/mobile-terminal.webp) |

</details>

---

## Features

- **Protocols** — SSH, Mosh, Telnet, serial (desktop and Android USB-OTG), and a local shell in
  a tab with no connection at all.
- **SSH** — jump hosts (ProxyJump), certificates from the vault or from disk, CA-signed host-key
  certificates, keyboard-interactive 2FA, auto-reconnect, host import from `~/.ssh/config`.
- **SFTP** — dual-pane commander: file viewer and editor, sortable columns, name filter,
  transfer queue.
- **Port forwarding** — local, remote, dynamic/SOCKS, forwards raised automatically after the
  vault is unlocked, one-click forwarding of the ports discovered on the host.
- **Containers** — exec into a Docker container or a Kubernetes pod straight from the host.
- **Remote desktops** — VNC and RDP on a client stack written for this project: screenshots,
  Ctrl+Alt+Del, clipboard exchange, settings changed mid-session. H.264 in RDP when a decoder is
  available: Android — always, desktop — `ffmpeg` on PATH.
- **Terminal** — custom grid emulation, up to four tiled panes per tab with synchronized input,
  scrollback search, syntax highlighting, a command palette over the history, input broadcast to
  several sessions, file paths from the output opened in SFTP, session recording (asciinema v2)
  with in-app playback.
- **Host monitoring** — a screen of its own: CPU, memory and network with history, disk and swap
  as levels, top processes, systemd units, mounts, containers, threshold alerts on the device.
- **Session sharing** — a terminal streamed to a teammate over an end-to-end encrypted channel,
  read-only or with the keyboard handed over.
- **Production guard** — risk scoring for every command on hosts tagged `prod`, confirmation for
  the dangerous ones.
- **Runbooks** — step-by-step run of a procedure in a live session: a step is a command or an
  SFTP transfer, a pause for confirmation, a stop on a non-zero exit code. Run log: state,
  duration and output of every step.
- **Snippets** — command library with type-ahead, `${{…}}` variables (date/time, uuid, random,
  clipboard, vault secrets, prompted parameters) expanded at run time behind a confirmation
  preview.
- **AI** — a policy per host, an assistant panel beside the session on desktop, an input form on
  a key press on mobile, your own OpenAI key or a local model.
  See [AI and privacy](#ai-and-privacy).
- **Vault** — Argon2id + XChaCha20-Poly1305 for keys, passwords, identities and certificates,
  biometric unlock on Android, a secret card with algorithm, fingerprint, validity, dependants
  and last use, a 30-day trash restorable on every synced device.
- **Sync** — optional, self-hosted, zero-knowledge: live push over WebSocket, device pairing via
  QR, a browser account zone behind a separate password — metadata and device revocation,
  nothing else. See [Sync server](#sync-server).
- **Teams** — end-to-end encrypted sharing of hosts, snippets and runbooks, access scopes per
  member, an activity feed of who changed which host and who opened a session.
- **Interface** — dark and light themes, the terminal following the app theme, System mode
  tracking the OS, UI in English, Russian and Simplified Chinese.

---

## AI and privacy

The boundaries the assistant operates within:

- **Request contents** — the request text and a fixed system prompt. Terminal output, host lists
  and vault records are not sent.
- **Cloud mode** — your own OpenAI key only: traffic goes from the app to the endpoint you set,
  with no server in between.
- **Host policy** — decides the recipient of a request:
  - **Strict** (default for new hosts) — local model only.
  - **Balanced** — cloud, with obvious secrets stripped from the prompt: private keys, tokens,
    `password=…`. The mechanism is pattern matching and gives no guarantee.
  - **Permissive** — cloud without redaction, for non-sensitive systems.
  - **Off** — the assistant is hidden on the host.
- **Quick-chat** — redaction always on, the local model included.
- **Local models** — GGUF (Qwen3, Phi-4 Mini) via llama.cpp on the device, no outbound traffic.
- **Command execution** — model output is untrusted: a run takes explicit confirmation, a risky
  command a second one.

---

## Tech stack

- **Language and UI** — Kotlin 2.4, Compose Multiplatform 1.9
- **Build** — Gradle 9.6, Android Gradle Plugin 9.1, JDK 21 (`jvmToolchain(21)` in all modules)
- **Android** — minSdk 26 (Android 8.0), compileSdk 37, targetSdk 36
- **SSH and crypto** — sshj, BouncyCastle, libsodium (ionspin KMP): Argon2id +
  XChaCha20-Poly1305
- **Terminal** — custom grid emulation, pty4j for the local shell on desktop
- **Remote desktops** — VNC (RFB) and RDP stacks written for this project, no third-party client
- **Serial** — jSerialComm (desktop), usb-serial-for-android (Android)
- **AI** — llamatik (a llama.cpp binding) for local models, a Ktor client for the cloud
- **Sync** — Ktor (client and server), Exposed, SQLite/PostgreSQL, HikariCP, Nimbus SRP-6a
- **Quality** — JUnit 5, Kover coverage, detekt static analysis

Exact versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## Repository layout

```
shared/       # KMP core: ssh/, sftp/, vault/, sync/, team/, share/, terminal/, ai/ (+ai/local),
              # telnet/, serial/, mosh/, rdp/, vnc/, graphics/, audio/, tunnel/, container/,
              # snippet/, runbook/, host/, tag/, files/, guard/, update/
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android app (MainActivity, manifest), applicationId app.skerry
server/       # self-hosted sync server (Ktor, AGPL-3.0)
sync-wire/    # wire contract shared by client and server
docs/         # documentation and design material
```

---

## Building from source

Development workflow, commit conventions and packaging notes are in
**[CONTRIBUTING.md](CONTRIBUTING.md)**.

Requires **JDK 21** (`foojay-resolver` fetches one if needed) and the Android SDK — every client
build configures `:androidApp`, so set `ANDROID_HOME` or `sdk.dir` in `local.properties` even for
a desktop-only build.

A package is built for the OS and CPU architecture of the build machine: an arm64 `.dmg` comes
out of macOS/ARM only.

```bash
./gradlew :composeApp:run                                # run
./gradlew :composeApp:packageDistributionForCurrentOS    # .deb / .rpm / .msi / .dmg
./gradlew :composeApp:packageAppImage                    # portable Linux .AppImage
./gradlew :composeApp:packagePortableZip                 # portable .zip
```

Android:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

Tests (JUnit 5) and static analysis:

```bash
./gradlew test allTests    # `test` alone skips the multiplatform modules
./gradlew detektAll        # existing findings sit in gradle/detekt-baseline-*.xml
```

---

## Sync server

The server is only there to sync devices, and it is always your server: there is no vendor
cloud.

Zero-knowledge by design: what sits on the server is ciphertext (the wrapped `dataKey`,
encrypted vault records) and sync metadata. Authentication is SRP-6a, the password is never
transmitted, and the server cannot decrypt anything you store.

Quick start — a prebuilt multi-arch image from
[Docker Hub](https://hub.docker.com/r/secherkasov/skerry-sync), SQLite in a named volume, no
configuration:

```bash
docker run -d --name skerry-sync -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

The server listens on `http://localhost:8080` and ships a built-in offline web frontend: a
public page at `/`, an account cabinet at `/account`, an operator console at `/console`.
Building from source — `docker compose up -d --build` from the repository root; PostgreSQL is
enabled by the `db` service and the postgres variables in
[docker-compose.yml](docker-compose.yml). A server-only build does without the Android SDK:
`./gradlew :server:run -PserverOnly`.

Configuration, API endpoints, TLS termination (Caddy/nginx), backups and the privacy model are
in **[server/README.md](server/README.md)**.

---

## Security

Private vulnerability reporting, supported versions, the threat model and the audit status are
in **[SECURITY.md](SECURITY.md)**.

---

## Contributing

Issues and pull requests are welcome. Environment setup, module structure, how the project is
developed and what a PR has to satisfy are in **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## Licenses

- Clients (`shared/`, `composeApp/`, `androidApp/`) — [GPL-3.0](LICENSE)
- Sync server (`server/`) — [AGPL-3.0](server/LICENSE): a fork hosted as a service has to
  contribute its changes back to the project.
- Bundled fonts — OFL-1.1 and Apache-2.0, texts and versions in [licenses/](licenses/README.md)
