#!/usr/bin/env bash
# Build a self-contained Skerry.AppImage from the jpackage app-image that Compose's
# createDistributable task produces (bundled JRE + app). Invoked by the Gradle
# :composeApp:packageAppImage task; also runnable standalone for local iteration.
#
# Environment:
#   APP_DIR         jpackage app-image dir (…/compose/binaries/main/app/Skerry)
#   APPIMAGE_DIR    where the assembled AppDir and the .AppImage land
#   ICON_PNG        512x512 app icon
#   ASSET_DIR       this directory (holds AppRun and skerry.desktop)
#   VERSION         version string embedded in the output filename
#   APPIMAGETOOL    optional path to appimagetool; downloaded to a cache if unset
set -euo pipefail

: "${APP_DIR:?APP_DIR is required}"
: "${APPIMAGE_DIR:?APPIMAGE_DIR is required}"
: "${ICON_PNG:?ICON_PNG is required}"
: "${ASSET_DIR:?ASSET_DIR is required}"
VERSION="${VERSION:-0.0.0}"

APPDIR="$APPIMAGE_DIR/Skerry.AppDir"

# The jpackage app-image matches the build host, so the AppImage arch follows uname -m
# (x86_64 or aarch64 — the names appimagetool and the AppImage spec use).
ARCH="$(uname -m)"
OUTPUT="$APPIMAGE_DIR/Skerry-${VERSION}-${ARCH}.AppImage"

# appimagetool releases pin a runtime built for the oldest still-supported glibc, so the
# resulting AppImage runs across distributions — the whole point of the format. Pin an immutable
# tagged release (not the mutable `continuous` tag) and verify its sha256 before running it.
APPIMAGETOOL_VERSION="1.9.1"
APPIMAGETOOL_URL="https://github.com/AppImage/appimagetool/releases/download/${APPIMAGETOOL_VERSION}/appimagetool-${ARCH}.AppImage"
case "$ARCH" in
  x86_64)  APPIMAGETOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0" ;;
  aarch64) APPIMAGETOOL_SHA256="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158" ;;
  *) echo "unsupported arch for appimagetool: $ARCH" >&2; exit 1 ;;
esac

echo "==> Assembling AppDir at $APPDIR"
rm -rf "$APPDIR"
mkdir -p "$APPDIR"
# The jpackage image (bin/ + lib/) becomes the AppDir root; AppRun execs bin/Skerry.
cp -a "$APP_DIR/." "$APPDIR/"

install -m 0755 "$ASSET_DIR/AppRun" "$APPDIR/AppRun"
install -m 0644 "$ASSET_DIR/skerry.desktop" "$APPDIR/skerry.desktop"

# appimagetool needs the icon at the AppDir root (it derives .DirIcon from it); the hicolor
# copy is what a desktop installs into its icon theme once the AppImage is integrated.
install -m 0644 "$ICON_PNG" "$APPDIR/skerry.png"
install -Dm 0644 "$ICON_PNG" "$APPDIR/usr/share/icons/hicolor/512x512/apps/skerry.png"

tool="${APPIMAGETOOL:-}"
if [ -n "$tool" ]; then
  # An externally supplied tool runs as-is. Say so, so a log never implies it was verified.
  echo "==> Using appimagetool from APPIMAGETOOL=$tool (sha256 not checked)" >&2
else
  cache="${XDG_CACHE_HOME:-$HOME/.cache}/skerry"
  mkdir -p "$cache"
  # Version and arch are part of the name: a version bump then downloads a new file instead of
  # running whatever the previous one left behind.
  cached="$cache/appimagetool-${APPIMAGETOOL_VERSION}-${ARCH}"
  # Everything happens in a private directory. The cache path is caller-controlled through
  # XDG_CACHE_HOME and shared between concurrent builds: a predictable name opened for writing
  # there can be a symlink, and a file verified there can be swapped between the check and the exec.
  work="$(mktemp -d)"
  trap 'rm -rf "$work" ${stage:+"$stage"}' EXIT
  tool="$work/appimagetool"
  if [ -e "$cached" ]; then
    from_cache=1
    cp "$cached" "$tool"
  else
    from_cache=
    echo "==> Downloading appimagetool → $cached"
    curl -fsSL -o "$tool" "$APPIMAGETOOL_URL"
  fi
  # Verified on every run, not only right after a download: otherwise a cached binary is executed
  # with no check at all. Hashed through stdin because `sha256sum "$path"` escapes the whole output
  # line, hash field included, when the path contains a backslash.
  actual="$(sha256sum < "$tool" | cut -d ' ' -f 1)"
  if [ "$actual" != "$APPIMAGETOOL_SHA256" ]; then
    if [ -n "$from_cache" ]; then
      echo "cached appimagetool failed sha256 verification ($actual) — dropping $cached" >&2
      rm -f "$cached"
    else
      echo "downloaded appimagetool failed sha256 verification ($actual)" >&2
    fi
    exit 1
  fi
  chmod +x "$tool"
  if [ -z "$from_cache" ]; then
    # The cache only ever gains verified bytes, staged under a name nobody could have pre-planted.
    stage="$(mktemp "$cache/.appimagetool.XXXXXX")"
    cp "$tool" "$stage"
    mv -f "$stage" "$cached"
    stage=
  fi
fi

echo "==> Building $OUTPUT"
mkdir -p "$APPIMAGE_DIR"
# ARCH is required by appimagetool; APPIMAGE_EXTRACT_AND_RUN lets both appimagetool and its
# bundled mksquashfs run in containers without FUSE (CI has no /dev/fuse).
ARCH="$ARCH" APPIMAGE_EXTRACT_AND_RUN=1 "$tool" "$APPDIR" "$OUTPUT"

echo "==> Done: $OUTPUT"
