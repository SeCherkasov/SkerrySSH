# Skerry

Open-source, cross-platform SSH client with a single core. Kotlin Multiplatform, Compose
Multiplatform UI, one codebase across Desktop (Linux, Windows, macOS) and Android at feature parity.
**iOS/iPadOS is deferred** — don't re-add its targets or `iosMain`.

## Commands

Requires **JDK 21** (`foojay-resolver` fetches one if needed); Android needs `ANDROID_HOME`.

```bash
./gradlew :composeApp:run                                   # desktop
./gradlew :composeApp:packageDistributionForCurrentOS       # .deb / .rpm / .msi / .dmg
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
./gradlew test                                              # JUnit 5
docker compose up -d --build                                # sync server; set SKERRY_JWT_SECRET
./gradlew :server:run -PserverOnly                          # server-only build, no Android SDK
scripts/gen-screenshots.sh                                  # README screenshots (offscreen render)
```

## Repository layout

```
shared/       # KMP core: ssh/, sftp/, vault/, sync/, team/, terminal/, ai/ (+ai/local),
              # telnet/, serial/, tunnel/, snippet/, host/, files/
              # commonMain + jvmSharedMain (shared JVM for desktop+Android) + desktopMain + androidMain
composeApp/   # UI (Compose Multiplatform): commonMain + androidMain + desktopMain
androidApp/   # Android app (MainActivity, manifest); applicationId app.skerry
server/       # self-hosted sync server (Ktor, AGPL-3.0)
sync-wire/    # wire contract shared by client and server (needed by server-only builds)
docs/         # HTML prototypes (source of truth for UX) and design documents
```

## Conventions

- **TDD**: write the failing test first, then the implementation.
- **Contracts live in `commonMain`**; platform libraries sit behind `expect`/`actual` or an
  interface. Keep desktop⇆Android parity — a feature isn't done until it works on both.
- **Read `docs/coding-guidelines.md` before writing code** — abstractions, decomposition, coroutine
  and security patterns, self-review checklist.
- **Build UI 1:1 from the prototypes** in `docs/new/` (`Skerry.html`, `Skerry Mobile.html`,
  `Skerry Tablet.html`, `Skerry Sync Console.html`). Don't invent chrome. Design tokens come from
  their `:root` block, mirrored in the Compose theme.
- Commit messages in English; commit and push only when asked.

## Warnings

- **ProGuard/minification is disabled on purpose** for the desktop release — it broke the crypto
  stack (JNA/libsodium, okio, BouncyCastle's signed jar). See the comment in
  `composeApp/build.gradle.kts` before re-enabling.
- Licenses: GPL-3.0 for the clients, AGPL-3.0 for `server/`.

## Reference docs

- `docs/skerry-product-brief.md` — decisions, phases, principles, distribution channels.
- `docs/skerry-sync-design.md` — sync protocol, key hierarchy, `VaultRecord`, Teams sharing (§6),
  threat model.
- `docs/skerry-biometric-design.md` — biometric unlock design.
