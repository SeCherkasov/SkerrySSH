#!/usr/bin/env bash
# Generates the README screenshot set via the offscreen render harness (composeApp screenshotDesign).
# No network / master password: all screens are fed seeded in-memory data. Re-run to refresh.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=docs/screenshots
mkdir -p "$OUT"

# name|extra -D flags (view/overlay/device/etc), live is always on
shots=(
  "desktop-terminal|-Dskerry.screenshot.view=Terminal"
  "desktop-sftp|-Dskerry.screenshot.view=Sftp"
  "desktop-tunnels|-Dskerry.screenshot.view=Ports"
  "desktop-vault|-Dskerry.screenshot.view=Vault"
  "desktop-ai|-Dskerry.screenshot.view=Terminal -Dskerry.screenshot.overlay=settings -Dskerry.screenshot.settingsTab=AI"
  "mobile-hosts|-Dskerry.screenshot.device=mobile -Dskerry.screenshot.view=Hosts"
  "mobile-terminal|-Dskerry.screenshot.device=mobile -Dskerry.screenshot.view=Terminal"
)

for entry in "${shots[@]}"; do
  name="${entry%%|*}"
  flags="${entry#*|}"
  echo "### rendering $name"
  ./gradlew --console=plain -q :composeApp:screenshotDesign \
    -Dskerry.screenshot.out="$PWD/$OUT/$name.png" \
    -Dskerry.screenshot.live=true \
    $flags
done
echo "### done"
ls -la "$OUT"
