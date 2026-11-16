#!/usr/bin/env bash
# Moves the Sodium/Iris family (and any jar named on the command line) from the Vulkan instance's mods/
# to disabledmods/ and records each move in disabledmods/.iroh-moved.txt so tools/restore-gl.sh can undo it.
# Never deletes. Refuses while the Vulkan instance game is running.
set -euo pipefail
INSTANCE="$HOME/.local/share/atlauncher/instances/Vulkan"
MODS="$INSTANCE/mods"; OFF="$INSTANCE/disabledmods"; LIST="$OFF/.iroh-moved.txt"
if ps -eo args | grep -E "KnotClient" | grep -E -- "--gameDir $INSTANCE/?( |$)" >/dev/null; then
  echo "Vulkan instance game is running; refusing." >&2; exit 1
fi
mkdir -p "$OFF"; touch "$LIST"
# The six jars P1-1 removes (sodium, iris, sodium-extra, iris-extension, iris_search, cull-fewer-leaves).
PATTERNS=("sodium-fabric-*.jar" "iris-fabric-*.jar" "sodium-extra-fabric-*.jar" "IrisExtension-fabric-*.jar"
          "IrisSearch-*.jar" "cull-fewer-leaves-*.jar" "$@")
shopt -s nullglob
for p in "${PATTERNS[@]}"; do
  for f in "$MODS"/$p; do
    b=$(basename "$f")
    mv -n "$f" "$OFF/$b"
    grep -qxF "$b" "$LIST" || echo "$b" >> "$LIST"
    echo "moved $b"
  done
done
echo "manifest: $LIST"; cat "$LIST"
