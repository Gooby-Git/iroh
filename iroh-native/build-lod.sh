#!/usr/bin/env bash
# Builds libiroh_lod (Iroh's LOD store/codec backend, Rust + JNI) from iroh-native/lod:
#   ./build-lod.sh [--seed] [--test] [--jobs N]
#     default       Linux x64 -> natives/linux-x64/libiroh_lod.so  (PUBLIC build: no `seed` code)
#     --seed        PRIVATE build with the `seed` cargo feature (Minecraft-derived ports, pending a clean-room rewrite) ->
#                   natives-private/linux-x64/libiroh_lod.so ; never goes into the jar or the public repo
#     --test        also runs cargo test --release (and with --seed, the seed tests too)
# The Windows DLL is built by build-windows.sh (lod step). Needs cargo (crates from the local registry cache or network).
# Checks: the exported JNI symbols equal the Java natives declared in lod/nat/LodNative.java (+ LodSeedNative.java with --seed),
# and a default build exports no seed symbol and contains none of the seed modules' symbols.
set -euo pipefail; export LC_ALL=C
HERE="$(cd "$(dirname "$0")" && pwd)"; L="$HERE/lod"; REPO="$(cd "$HERE/.." && pwd)"
SEED=0; TEST=0; JOBS=4; SFX=""; PFX=""
while [ $# -gt 0 ]; do case "$1" in --seed) SEED=1;; --test) TEST=1;; --jobs) JOBS="$2"; shift;; *) echo "unknown arg $1" >&2; exit 2;; esac; shift; done
if [ $SEED = 1 ]; then SFX=-seed; PFX=-private; fi
export CARGO_TARGET_DIR="${IROH_LOD_BUILD:-$HERE/build-lod}/target$SFX"
FEAT=(); if [ $SEED = 1 ]; then FEAT=(--features seed); fi
cd "$L"
if [ $TEST = 1 ]; then
  nice -n 10 cargo test --release --offline -j"$JOBS" 2>&1 | tail -5
  if [ $SEED = 1 ]; then nice -n 10 cargo test --release --offline -j"$JOBS" --features seed 2>&1 | tail -5; fi
fi
nice -n 10 cargo build --release --offline -j"$JOBS" "${FEAT[@]}"
OUT="$HERE/natives$PFX/linux-x64"; mkdir -p "$OUT"; cp "$CARGO_TARGET_DIR/release/libiroh_lod.so" "$OUT/libiroh_lod.so"
so="$OUT/libiroh_lod.so"
exp=$(nm -D --defined-only "$so" | awk '$2=="T"{print $3}' | grep '^Java_' | sort)
want=$(python3 -I "$HERE/lod/tools/java_natives.py" "$REPO" $([ $SEED = 1 ] && echo seed || true))
[ "$exp" = "$want" ] || { echo "build-lod: FAIL JNI export set differs from the Java natives" >&2; diff <(echo "$exp") <(echo "$want") >&2 || true; exit 1; }
if [ $SEED = 0 ]; then
  if nm -D "$so" | grep -qiE 'LodSeedNative|graphOpen|seedColumn|density|noise'; then echo "build-lod: FAIL seed symbols in the public build" >&2; exit 1; fi
  if strings -a "$so" | grep -qE 'larion|not a Vista density graph|bad column parameters'; then echo "build-lod: FAIL seed strings in the public build" >&2; exit 1; fi
fi
echo "build-lod: $so $(stat -c %s "$so") bytes, $(echo "$exp" | wc -l) JNI exports, seed=$SEED, sha256 $(sha256sum "$so" | cut -c1-16)"
