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
# The six jars P1-1 removes (sodium, iris, sodium-extra, iris-extension, iris_search, cull-fewer-leaves), plus
# SmoothGL (a Sodium fork whose mod id is `sodium`; the owner's pack carries it since 2026-11-17, inert there because
# Sodium 0.8.13 outranks it, but it would BECOME the `sodium` mod once the real one is gone).
PATTERNS=("sodium-fabric-*.jar" "iris-fabric-*.jar" "sodium-extra-fabric-*.jar" "IrisExtension-fabric-*.jar"
          "IrisSearch-*.jar" "cull-fewer-leaves-*.jar" "SmoothGL-*.jar" "$@")
shopt -s nullglob
for p in "${PATTERNS[@]}"; do
  for f in "$MODS"/$p; do
    b=$(basename "$f")
    mv -n "$f" "$OFF/$b"
    grep -qxF "$b" "$LIST" || echo "$b" >> "$LIST"
    echo "moved $b"
  done
done
# The pack runs OpenGL through Zink via instance.json wrapperCommand; native Vulkan must not get it. Original kept in
# $INSTANCE/.iroh-wrapperCommand.orig (restore-gl.sh puts it back). Only the Vulkan instance is ever touched.
ORIG="$INSTANCE/.iroh-wrapperCommand.orig"
if grep -q '"wrapperCommand": "env MESA' "$INSTANCE/instance.json"; then
  grep -o '"wrapperCommand": "[^"]*"' "$INSTANCE/instance.json" | sed 's/^"wrapperCommand": "//; s/"$//' > "$ORIG"
  sed -i 's|"wrapperCommand": "env MESA[^"]*"|"wrapperCommand": ""|' "$INSTANCE/instance.json"
  echo "wrapperCommand cleared (original in $ORIG)"
fi
echo "manifest: $LIST"; cat "$LIST"
