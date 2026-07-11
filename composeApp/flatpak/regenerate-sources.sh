#!/usr/bin/env bash
# Regenerate composeApp/flatpak/flatpak-sources.json — the offline Maven artifact list the Flatpak
# manifest feeds to flatpak-builder. Run this (online) whenever the desktop build's dependencies
# change, then commit the updated flatpak-sources.json. It is NOT part of the release build; the
# release only consumes the committed JSON (see package-flatpak.sh).
#
# It drives the flatpak-gradle-generator plugin via an init script so the production build files
# stay untouched. -PdesktopOnly matches exactly what the sandbox build resolves.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLATPAK_DIR="$REPO_ROOT/composeApp/flatpak"
cd "$REPO_ROOT"

echo "==> Resolving dependencies with flatpak-gradle-generator"
./gradlew --no-configuration-cache -PdesktopOnly \
  --init-script "$FLATPAK_DIR/tools/flatpak-gen.init.gradle.kts" \
  :sync-wire:flatpakGradleGenerator \
  :shared:flatpakGradleGenerator \
  :composeApp:flatpakGradleGenerator

echo "==> Merging per-module sources"
python3 "$FLATPAK_DIR/tools/merge-sources.py" \
  "$FLATPAK_DIR/flatpak-sources.json" \
  "$REPO_ROOT/sync-wire/build/flatpak-sources.json" \
  "$REPO_ROOT/shared/build/flatpak-sources.json" \
  "$REPO_ROOT/composeApp/build/flatpak-sources.json"

echo "==> Done: $FLATPAK_DIR/flatpak-sources.json"
