#!/usr/bin/env bash
# Builds the plugin and installs it into the local IntelliJ IDEA (macOS), then restarts the IDE.
# Usage: scripts/install-local.sh ["/Applications/IntelliJ IDEA.app"]
set -euo pipefail

app="${1:-/Applications/IntelliJ IDEA.app}"
root="$(cd "$(dirname "$0")/.." && pwd)"

data_dir="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dataDirectoryName"])' \
  "$app/Contents/Resources/product-info.json")"
plugins_dir="$HOME/Library/Application Support/JetBrains/$data_dir/plugins"

"$root/gradlew" -p "$root" buildPlugin --console=plain -q
zip="$(ls -t "$root"/build/distributions/*.zip | head -1)"

running=false
if pgrep -f "$app/Contents/MacOS/" >/dev/null; then
  running=true
  echo "Quitting $(basename "$app")..."
  osascript -e "tell application \"$app\" to quit"
  while pgrep -f "$app/Contents/MacOS/" >/dev/null; do sleep 1; done
fi

mkdir -p "$plugins_dir"
rm -rf "$plugins_dir/herdr-idea-plugin"
unzip -q "$zip" -d "$plugins_dir"
echo "Installed $(basename "$zip") into $plugins_dir"

if $running; then
  open -a "$app"
fi
