#!/usr/bin/env bash
# Builds libiroh_nrd (NVIDIA NRD v4.18.0 stock + Iroh JNI shim) for Linux x64 -> natives-nv/linux-x64/libiroh_nrd.so
#   ./build-nrd.sh [--no-quad] [--normal-encoding N] [--jobs N]
#     --no-quad: NRD_SUPPORTS_QUAD_INTRINSICS=OFF (no VK_KHR_compute_shader_derivatives needed)
#     --normal-encoding N: nrd::NormalEncoding 0..4 (default 2 = R10_G10_B10_A2_UNORM, NVIDIA recommended; 4 = RGBA16_SNORM plain xyz, used by the offline tests)
# NVIDIA RTX SDKs licence: the output goes to natives-nv/, which the jar does NOT include unless -PbundleNv=true is given
# to Gradle (see THIRD_PARTY.md and docs/P6-PLAN.md). NRD's CMake downloads ShaderMake, MathLib and DXC at configure time.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
N="$HERE/nrd"; B="${IROH_NRD_BUILD:-$HERE/build-nrd}"
QUAD=ON; NENC=2; JOBS=$(( $(nproc) / 2 )); [ "$JOBS" -lt 1 ] && JOBS=1; TAG=""
while [ $# -gt 0 ]; do case "$1" in --no-quad) QUAD=OFF; TAG="$TAG-noquad";; --normal-encoding) NENC="$2"; TAG="$TAG-enc$2"; shift;; --jobs) JOBS="$2"; shift;; *) echo "unknown arg $1" >&2; exit 2;; esac; shift; done
JAVA_HOME="${IROH_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
[ -f "$JAVA_HOME/include/jni.h" ] || { echo "build-nrd: no jni.h under $JAVA_HOME (set IROH_JAVA_HOME to a JDK 21)" >&2; exit 1; }
for t in git cmake ninja g++ curl unzip; do command -v $t >/dev/null || { echo "build-nrd: missing tool: $t" >&2; exit 1; }; done
mkdir -p "$B"
# NRD's CMake writes generated files into its source tree: one source copy per variant
SRC="$B/nrd-src$TAG"
"$N/fetch-nrd.sh" "$SRC"
cmake -S "$N" -B "$B/linux$TAG" -G Ninja -DCMAKE_BUILD_TYPE=Release -DIROH_NRD_SRC="$SRC" -DIROH_NRD_QUAD=$QUAD -DNRD_NORMAL_ENCODING=$NENC \
      -DIROH_JNI_INCLUDE="$JAVA_HOME/include;$JAVA_HOME/include/linux" >/dev/null
ninja -C "$B/linux$TAG" -j "$JOBS" iroh_nrd
mkdir -p "$HERE/natives-nv/linux-x64"
OUT="$HERE/natives-nv/linux-x64/libiroh_nrd$TAG.so"
strip -o "$OUT" "$B/linux$TAG/out/libiroh_nrd.so"
cp "$SRC/LICENSE.txt" "$HERE/natives-nv/NVIDIA-RTX-SDKs-LICENSE.txt"
echo "build-nrd: $OUT $(stat -c %s "$OUT") bytes sha256 $(sha256sum "$OUT" | cut -c1-16) quad=$QUAD normalEncoding=$NENC"
