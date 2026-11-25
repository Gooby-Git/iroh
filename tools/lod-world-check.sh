#!/usr/bin/env bash
# VL-2: Java == native check on real Vista stores, using ONLY read-only reflink copies under ~/.cache/iroh-lod-world.
# Usage: tools/lod-world-check.sh <store dir> [<store dir>...]   (a world's vista/ folder or an instance's vista-cache/)
# The originals are never opened; the copies are never committed (world data is blocked by the pre-push hook).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${IROH_JAVA_HOME:-/home/gooby/.local/share/mise/installs/java/temurin-25.0.5.1+2.0.LTS}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$HOME/.cache/iroh-lod-world"
mkdir -p "$OUT"
copies=()
for src in "$@"; do
  name="$(echo "$src" | sed 's#^/##; s#[^A-Za-z0-9._-]#_#g')"
  dst="$OUT/$name"
  if [ ! -d "$dst" ]; then cp -r --reflink=always "$src" "$dst"; fi
  copies+=("$dst")
done
manifest() { find "${copies[@]}" -type f -printf '%p %s %T@\n' | sort; }
manifest > "$OUT/.before"
./gradlew lodNative lodWorldCheck -PlodWorld="$(IFS=,; echo "${copies[*]}")" 2>&1 | grep -E "sections|FAIL|PASS|stores|Exception" || true
manifest > "$OUT/.after"
if cmp -s "$OUT/.before" "$OUT/.after"; then echo "copy unchanged by the check (sizes + mtimes)"; else echo "WARNING: the check modified the copy" >&2; exit 1; fi
