#!/usr/bin/env bash
# Builds libiroh_fsr (FSR 3.1 + frame interpolation + optical flow, Vulkan backend, JNI shim) from AMD's MIT SDK v1.1.4.
#   ./build-fsr.sh            Linux x64 -> natives/linux-x64/libiroh_fsr.so ; Windows x64 too when mingw-w64 is installed
#   ./build-fsr.sh --linux-only | --windows-only | --jobs N
# Needs: git, patch, python3, cmake >= 3.23, ninja, g++, glslangValidator (glslang), a JDK 21 (jni.h), Vulkan headers,
# libvulkan (linking). No GPU, no game, no network after the first fetch. Output natives/ is git-ignored.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
F="$HERE/fsr"; B="${IROH_FSR_BUILD:-$HERE/build-fsr}"
WANT_LINUX=1; WANT_WIN=auto; JOBS=$(( $(nproc) / 2 )); [ "$JOBS" -lt 1 ] && JOBS=1
while [ $# -gt 0 ]; do case "$1" in
  --linux-only) WANT_WIN=0;; --windows-only) WANT_LINUX=0; WANT_WIN=1;; --jobs) JOBS="$2"; shift;; *) echo "unknown arg $1" >&2; exit 2;; esac; shift; done
JAVA_HOME="${IROH_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
[ -f "$JAVA_HOME/include/jni.h" ] || { echo "build-fsr: no jni.h under $JAVA_HOME (set IROH_JAVA_HOME to a JDK 21)" >&2; exit 1; }
for t in git patch python3 cmake ninja g++ glslangValidator; do command -v $t >/dev/null || { echo "build-fsr: missing tool: $t" >&2; exit 1; }; done
mkdir -p "$B"
"$F/fetch-sdk.sh" "$B/sdk-src"
for e in fsr3upscaler frameinterpolation opticalflow; do
  if [ ! -f "$B/gen/$e/.done" ]; then
    python3 -I "$F/gen_permutations.py" "$B/sdk-src" "$B/gen/$e" --effect $e --jobs "$JOBS"
    touch "$B/gen/$e/.done"
  fi
done
if [ "$WANT_LINUX" = 1 ]; then
  cmake -S "$F" -B "$B/linux" -G Ninja -DCMAKE_BUILD_TYPE=Release -DIROH_SDK_DIR="$B/sdk-src" -DIROH_GEN_DIR="$B/gen" \
        -DIROH_JNI_INCLUDE="$JAVA_HOME/include;$JAVA_HOME/include/linux" >/dev/null
  ninja -C "$B/linux"
  mkdir -p "$HERE/natives/linux-x64"; strip -o "$HERE/natives/linux-x64/libiroh_fsr.so" "$B/linux/libiroh_fsr.so"
  echo "build-fsr: $HERE/natives/linux-x64/libiroh_fsr.so $(stat -c %s "$HERE/natives/linux-x64/libiroh_fsr.so") bytes sha256 $(sha256sum "$HERE/natives/linux-x64/libiroh_fsr.so" | cut -c1-16)"
fi
if [ "$WANT_WIN" != 0 ]; then
  if command -v x86_64-w64-mingw32-g++ >/dev/null; then
    VKINC="$B/vkinc"; mkdir -p "$VKINC"; cp -r /usr/include/vulkan /usr/include/vk_video "$VKINC/"   # headers only; never -I/usr/include
    x86_64-w64-mingw32-dlltool -d "$F/shim/vulkan-1.def" -l "$B/libvulkan-1.a"
    cmake -S "$F" -B "$B/windows" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_SYSTEM_NAME=Windows \
          -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
          -DIROH_SDK_DIR="$B/sdk-src" -DIROH_GEN_DIR="$B/gen" -DIROH_JNI_INCLUDE="$JAVA_HOME/include" \
          -DIROH_VK_INCLUDE="$VKINC" -DIROH_VULKAN_IMPORT_LIB="$B/libvulkan-1.a" >/dev/null
    ninja -C "$B/windows"
    mkdir -p "$HERE/natives/windows-x64"; x86_64-w64-mingw32-strip -o "$HERE/natives/windows-x64/iroh_fsr.dll" "$B/windows/iroh_fsr.dll"
    echo "build-fsr: $HERE/natives/windows-x64/iroh_fsr.dll $(stat -c %s "$HERE/natives/windows-x64/iroh_fsr.dll") bytes (NOT run: no Windows here)"
  elif [ "$WANT_WIN" = 1 ]; then echo "build-fsr: mingw-w64 missing (x86_64-w64-mingw32-g++)" >&2; exit 1
  else echo "build-fsr: mingw-w64 not installed, skipping the Windows DLL"; fi
fi
