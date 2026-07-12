# Contributing to Skerry

Thanks for considering a contribution! This document covers the development environment,
the build/test workflow, and the conventions the project follows.

## Environment

- **JDK 21** — required for all builds; `foojay-resolver` fetches a toolchain automatically
  if none is installed.
- **Android SDK** — only for Android targets; set `ANDROID_HOME` (compileSdk 36).
- A server-only build needs no Android SDK: `./gradlew :server:run -PserverOnly`.

## Build, run, test

```bash
./gradlew :composeApp:run                                   # desktop app
./gradlew :composeApp:packageDistributionForCurrentOS       # .deb / .rpm / .msi / .dmg
./gradlew :composeApp:packageAppImage                       # portable Linux .AppImage
./gradlew :composeApp:packageFlatpak                        # Linux .flatpak (needs flatpak + flatpak-builder)
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
./gradlew test                                              # JUnit 5 — CI runs exactly this
docker compose up -d --build                                # sync server; set SKERRY_JWT_SECRET
```

Desktop packages are produced for the OS/architecture of the build machine.

## Module structure

```
shared/       # KMP core: ssh/, sftp/, vault/, sync/, team/, terminal/, ai/ (+ai/local),
              # telnet/, serial/, tunnel/, snippet/, host/, files/
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android app (MainActivity, manifest)
server/       # self-hosted sync server (Ktor, AGPL-3.0)
sync-wire/    # wire contract shared by client and server
docs/         # HTML prototypes (source of truth for UX) and design documents
```

## How the project is developed

- **TDD**: write the failing test first, then the implementation. Tests are JUnit 5.
- **Contracts live in `commonMain`**; platform libraries sit behind `expect`/`actual` or an
  interface.
- **Platform parity**: a feature isn't done until it works on both desktop and Android.
- **UI is built 1:1 from the HTML prototypes** in `docs/design/` (`Skerry Tablet.html`);
  don't invent chrome. Design tokens come from the prototype's `:root` block, mirrored in
  the Compose theme.
- Read `docs/coding-guidelines.md` before writing code — abstractions, decomposition,
  coroutine and security patterns, and the self-review checklist.

## Commit messages

English, imperative mood, capitalized, no type prefixes — a single subject line that says
what the change does:

```
Add SSH jump host (ProxyJump) support
Fix Flatpak headless crash on Wayland by granting X11+ipc
Wire the keep-alive setting end to end with dead-link detection
```

## Pull requests

1. Branch from `main`.
2. Make sure `./gradlew test` passes — CI runs it on every PR.
3. Keep desktop ⇆ Android parity for anything user-facing.
4. When touching `README.md`, mirror the change in `README.ru.md` (and vice versa) — the
   two must stay structurally identical.

## Packaging notes

- **ProGuard/minification is disabled on purpose** for the desktop release — it broke the
  crypto stack (JNA/libsodium, okio, BouncyCastle's signed jar). See the comment in
  `composeApp/build.gradle.kts` before trying to re-enable it.
- **Flatpak** is a hermetic source build: `flatpak-builder` compiles the app inside the
  sandbox from the committed offline sources (`composeApp/flatpak/flatpak-sources.json`).
  Regenerate that list with `composeApp/flatpak/regenerate-sources.sh` whenever desktop
  dependencies change.
- **Releases**: pushing a `v*` tag runs the release workflow, which builds
  `.deb`/`.rpm`/`.AppImage` (x64 + arm64), a Flatpak bundle (x64), `.msi` (x64), `.dmg`
  (arm64 + x64, unsigned), a signed `.apk`, and `SHA256SUMS.txt`, and publishes them as a
  **draft** GitHub Release — a maintainer reviews and publishes it manually.

## Licenses

Contributions to the clients (`shared/`, `composeApp/`, `androidApp/`) are accepted under
[GPL-3.0](LICENSE); contributions to `server/` under [AGPL-3.0](server/LICENSE).
