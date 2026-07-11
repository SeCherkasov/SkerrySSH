#!/usr/bin/env bash
# Build a single-file Skerry.flatpak bundle from app.skerry.Skerry.yml. flatpak-builder downloads
# the pinned sources (JDK-nothing: the Gradle distribution + the offline Maven repo from
# flatpak-sources.json) in a network phase, then runs `gradle --offline :composeApp:createDistributable`
# hermetically in the sandbox and installs the resulting app-image into /app.
#
# Invoked by the Gradle :composeApp:packageFlatpak task and by the release workflow; also runnable
# standalone. Regenerate flatpak-sources.json with regenerate-sources.sh when dependencies change.
#
# Environment:
#   VERSION       version embedded in the output filename (default 0.0.0)
#   BUILDER       override the flatpak-builder command (default: native, else `flatpak run org.flatpak.Builder`)
set -euo pipefail

FLATPAK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$FLATPAK_DIR/app.skerry.Skerry.yml"
VERSION="${VERSION:-0.0.0}"

WORK="$FLATPAK_DIR/.build"
REPO="$WORK/repo"
BUILDDIR="$WORK/builddir"
STATEDIR="$WORK/state"
OUTPUT="$FLATPAK_DIR/Skerry-${VERSION}-x86_64.flatpak"

if [ ! -f "$FLATPAK_DIR/flatpak-sources.json" ]; then
  echo "error: flatpak-sources.json is missing — run regenerate-sources.sh first" >&2
  exit 1
fi

# Prefer a native flatpak-builder; fall back to the Flatpak-packaged one (org.flatpak.Builder),
# which is how the tool ships on distros without a system package (and how CI installs it).
if [ -n "${BUILDER:-}" ]; then
  read -r -a builder <<< "$BUILDER"
elif command -v flatpak-builder >/dev/null 2>&1; then
  builder=(flatpak-builder)
else
  builder=(flatpak run org.flatpak.Builder)
fi

echo "==> Building with: ${builder[*]}"
rm -rf "$BUILDDIR" "$REPO"
mkdir -p "$WORK"

# --user: resolve/export against the user installation (runtimes installed there).
# --disable-rofiles-fuse: CI runners have no /dev/fuse.
"${builder[@]}" \
  --user --force-clean --disable-rofiles-fuse \
  --state-dir="$STATEDIR" \
  --repo="$REPO" \
  "$BUILDDIR" "$MANIFEST"

echo "==> Exporting single-file bundle → $OUTPUT"
flatpak build-bundle --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo \
  "$REPO" "$OUTPUT" app.skerry.Skerry

echo "==> Done: $OUTPUT"
