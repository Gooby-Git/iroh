#!/usr/bin/env bash
# Proves the "FSR 3.1.6 shaders" claim: the FSR 3.1 upscaler shader headers of the PATCHED 1.1.4 tree (what we build)
# equal SDK v2.4.0's after normalisation (copyright year, #include lines, trailing blanks), and that the ONLY shader
# line that differs between pristine 1.1.4 (3.1.4) and 2.4.0 (3.1.6) is the FP16 reproject history-size line.
# Usage: check-shader-delta.sh <patched-sdk-root> [sdk24-dest]   exit 1 on any other difference.
# Skipped on purpose: *_callbacks_hlsl.h / *_callbacks_glsl.h (binding-declaration macro style only; we generate from our own GLSL callbacks).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PATCHED="$1"; S24="${2:-$HERE/sdk24-src}"
CACHE="${FFX_SDK_CACHE:-$HERE/.sdk-cache}"; C114=c6efa6bf7f2027b3ec94f28578bb5965eabb9e55
"$HERE/fetch-sdk-2.4.0.sh" "$S24" >/dev/null
P="$PATCHED/sdk/include/FidelityFX/gpu/fsr3upscaler"; N="$S24/Kits/FidelityFX/upscalers/fsr3/include/gpu/fsr3upscaler"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
git -C "$CACHE" archive --format=tar "$C114" sdk/include/FidelityFX/gpu/fsr3upscaler | tar -x -C "$T"
O="$T/sdk/include/FidelityFX/gpu/fsr3upscaler"
norm() { grep -v -e 'Copyright (C)' -e '^[[:space:]]*#include' "$1" | sed 's/[[:space:]]\+$//'; }
fail=0
echo "== patched 1.1.4 vs 2.4.0 (must be empty)"
for f in "$N"/*.h; do b=$(basename "$f"); case "$b" in *_callbacks_*) continue;; esac
  d=$(diff <(norm "$P/$b") <(norm "$f") | grep '^[<>]' || true)
  [ -n "$d" ] && { echo "DIFF in $b:"; echo "$d"; fail=1; }
done
echo "== pristine 1.1.4 (3.1.4) vs 2.4.0 (3.1.6): the complete shader delta"
n=0
for f in "$N"/*.h; do b=$(basename "$f"); case "$b" in *_callbacks_*) continue;; esac
  d=$(diff <(norm "$O/$b") <(norm "$f") | grep '^[<>]' || true)
  if [ -n "$d" ]; then n=$((n+1)); echo "[$b]"; echo "$d"
    [ "$b" = ffx_fsr3upscaler_reproject.h ] || { echo "UNEXPECTED delta file $b" >&2; fail=1; }; fi
done
[ "$n" = 1 ] || { echo "expected exactly 1 differing shader file, got $n" >&2; fail=1; }
[ "$fail" = 0 ] && echo "check-shader-delta: OK (3.1.4 -> 3.1.6 = the reproject PreviousFrameUpscaleSize line only, present in our patched tree)" || { echo "check-shader-delta: FAIL" >&2; exit 1; }
