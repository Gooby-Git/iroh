#!/usr/bin/env bash
# Undoes tools/disable-gl.sh: moves every jar listed in disabledmods/.iroh-moved.txt back to mods/, and moves
# the Iroh jar out of mods/ into disabledmods/ (never deletes). The instance is then the stock OpenGL pack
# (Sodium + Iris; its wrapperCommand still runs it on Zink). Refuses while the Vulkan instance game is running.
set -euo pipefail
INSTANCE="$HOME/.local/share/atlauncher/instances/Vulkan"
MODS="$INSTANCE/mods"; OFF="$INSTANCE/disabledmods"; LIST="$OFF/.iroh-moved.txt"
if ps -eo args | grep -E "KnotClient" | grep -E -- "--gameDir $INSTANCE/?( |$)" >/dev/null; then
  echo "Vulkan instance game is running; refusing." >&2; exit 1
fi
[ -f "$LIST" ] || { echo "no $LIST: nothing to restore"; exit 0; }
while IFS= read -r b; do
  [ -n "$b" ] || continue
  if [ -f "$OFF/$b" ]; then mv -n "$OFF/$b" "$MODS/$b"; echo "restored $b"
  elif [ -f "$MODS/$b" ]; then echo "already in mods: $b"
  else echo "MISSING: $b (in neither folder)" >&2; fi
done < "$LIST"
shopt -s nullglob
for f in "$MODS"/iroh-*.jar; do mv -f "$f" "$OFF/$(basename "$f")"; echo "moved out $(basename "$f")"; done
mv "$LIST" "$LIST.restored-$(date +%Y%m%d-%H%M%S)"
echo "done: Sodium/Iris pack restored"
