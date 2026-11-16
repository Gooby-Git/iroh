#!/usr/bin/env bash
# Builds the Proctor clone `iroh-vanilla`: Minecraft 1.21.1 + Fabric API + MCP Fabric + Iroh only
# (Proctor installs its own kernel jar at launch). It borrows instance.json (loader, libraries, Java)
# and options.txt from the Vulkan instance, and is marked `frozen` so Proctor never syncs the pack's
# 350 mods into it. Re-run after every Iroh build to refresh the Iroh jar (idempotent).
#   PROCTOR_CLONE=iroh-vanilla PROCTOR_INSTANCE=Vulkan PROCTOR_NO_WRAPPER=1 proctor test iroh/gate/boot
set -euo pipefail
SRC="$HOME/.local/share/atlauncher/instances/Vulkan"
DIR="$HOME/.cache/proctor/iroh-vanilla"
INST="$DIR/instance"
JAR=$(ls "$(dirname "$0")"/../build/libs/iroh-*.jar | grep -v -- '-sources' | head -n1)

if ps -eo args | grep -E "KnotClient" | grep -E -- "--gameDir $INST/?( |$)" >/dev/null; then
  echo "iroh-vanilla game is running; quit it first (PROCTOR_CLONE=iroh-vanilla proctor quit)" >&2; exit 1
fi
mkdir -p "$INST/mods" "$INST/config" "$INST/saves" "$INST/logs" "$INST/proctor"
echo "$SRC" > "$DIR/source"
echo "iroh-vanilla: minimal profile, never synced (tools/make-vanilla-clone.sh in ~/Projects/iroh)" > "$DIR/frozen"
cp "$SRC/instance.json" "$INST/instance.json"
[ -f "$INST/options.txt" ] || cp "$SRC/options.txt" "$INST/options.txt"
# Only these mods. Anything else in mods/ is removed.
find "$INST/mods" -maxdepth 1 -name '*.jar' ! -name 'proctor-*.jar' -delete
cp "$SRC"/mods/fabric-api-*.jar "$INST/mods/"
cp "$SRC"/mods/mcpfabric-*.jar "$INST/mods/" 2>/dev/null || cp "$SRC"/disabledmods/mcpfabric-*.jar "$INST/mods/"
cp "$JAR" "$INST/mods/"
ls "$INST/mods"
