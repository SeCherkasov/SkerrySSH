# Dependency verification (Gradle artifact checksums)

Status: **not yet enabled.** This document is the turnkey procedure for generating
`gradle/verification-metadata.xml`, plus why it isn't a one-command job.

## What it is

Gradle [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
records a sha256 for every resolved artifact in `gradle/verification-metadata.xml`. With
`<verify-metadata>true</verify-metadata>`, Gradle then refuses to build if any downloaded
artifact's hash doesn't match — closing the supply-chain gap where a compromised repository
or MITM swaps a dependency. For an SSH client this is high-value hardening, and it mitigates
the JitPack and third-party-plugin exposure in the release build.

Dependabot (`.github/dependabot.yml`) handles the *ongoing* half — keeping actions and
dependencies patched. This file is the *artifact-checksum* half.

## Why it isn't a single command

`./gradlew --write-verification-metadata sha256` only records the artifacts it actually
resolves **on the machine it runs on**. Skerry resolves a different dependency set per
platform:

- **Linux desktop + server** — Linux-native skiko/JNA/sqlite-jdbc/lazysodium.
- **Windows / macOS desktop** — the win/mac (and arm64) classifier natives, absent on Linux.
- **Android** — the entire Android Gradle Plugin toolchain (aapt2, D8/R8, …), absent from the
  desktop/server graph.

Verification is global: once enabled, a Windows CI runner verifies `skiko-*-windows-x64`,
which a Linux-generated file won't contain → the build fails. So a correct file must be the
**union** of runs on every target OS. (`flatpak-sources.json` pins ~919 artifacts already,
but that too is only the Linux-desktop slice — same limitation.)

## Turnkey procedure (run once, then maintain via Dependabot bumps)

Run each on the matching OS, appending to the same file (Gradle merges into it):

```bash
# Linux runner
./gradlew --write-verification-metadata sha256 \
  :composeApp:dependencies :server:dependencies :androidApp:dependencies

# macOS runner (desktop + Android natives)
./gradlew --write-verification-metadata sha256 \
  :composeApp:dependencies :androidApp:dependencies

# Windows runner (desktop natives)
./gradlew --write-verification-metadata sha256 :composeApp:dependencies
```

Collect the three `gradle/verification-metadata.xml` outputs, merge the `<components>` (union,
dedup by group/name/version/artifact), commit the result, then flip
`<verify-metadata>true</verify-metadata>`. Verify a clean checkout builds green on all runners
before merging. Regenerate (or let Dependabot's bump PRs add entries) whenever dependencies
change.

The mechanical way to produce the merged file is a manual-dispatch CI workflow that runs the
above on an OS matrix and uploads the merged artifact for a maintainer to commit — deferred
until the verification is actually turned on.
