#!/usr/bin/env bash
# Cross-builds Iroh's Windows x64 natives with mingw-w64 (no Windows needed, no sudo):
#   iroh-native/natives/windows-x64/iroh_fsr.dll        (FSR 3.1 + frame interpolation + optical flow, Vulkan backend; goes in the jar)
#   iroh-native/natives-nv/windows-x64/iroh_nrd.dll     (NVIDIA NRD, stock; NOT in the jar, fetched/vendored at runtime like the .so)
# then checks imports/exports and runs a Wine smoke test (LoadLibrary + GetProcAddress of every JNI export).
#   ./build-windows.sh [--no-nrd] [--no-smoke] [--jobs N]        (default jobs: min(4, nproc/2))
# Needs: mingw-w64 gcc/g++/dlltool/objdump, cmake, ninja, python3, glslangValidator, git, patch; wine for the smoke step.
# NRD needs the Linux NRD build first for its SPIR-V (platform independent): this script runs ./build-nrd.sh when missing.
# JNI headers: IROH_WIN_JAVA_HOME (a Windows JDK, e.g. Temurin 21 zip from adoptium.net, sha256 verified) else the Linux JDK's
# jni.h + our fsr/shim/win32/jni_md.h. AMD / NVIDIA signed binaries are never built in or committed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="$HERE/toolchains/mingw-w64-x86_64.cmake"; F="$HERE/fsr"; N="$HERE/nrd"
B="${IROH_WIN_BUILD:-$HERE/build-windows}"; FB="${IROH_FSR_BUILD:-$HERE/build-fsr}"
WANT_NRD=1; SMOKE=1; JOBS=$(( $(nproc) / 2 )); [ "$JOBS" -gt 4 ] && JOBS=4; [ "$JOBS" -lt 1 ] && JOBS=1
while [ $# -gt 0 ]; do case "$1" in
  --no-nrd) WANT_NRD=0;; --no-smoke) SMOKE=0;; --jobs) JOBS="$2"; shift;; *) echo "unknown arg $1" >&2; exit 2;; esac; shift; done
P=x86_64-w64-mingw32
for t in $P-gcc $P-g++ $P-dlltool $P-objdump $P-strip cmake ninja python3 git patch glslangValidator; do
  command -v $t >/dev/null || { echo "build-windows: missing tool: $t (mingw-w64 is a system package: sudo pacman -S mingw-w64-gcc)" >&2; exit 1; }; done
WJ="${IROH_WIN_JAVA_HOME:-$(ls -d "$HOME"/.local/share/iroh-win-jdk/jdk21/jdk-* 2>/dev/null | head -1)}"
if [ -n "$WJ" ] && [ -f "$WJ/include/win32/jni_md.h" ]; then JNI_INC="$WJ/include;$WJ/include/win32"; echo "build-windows: JNI headers from Windows JDK $WJ"
else
  LJ="${IROH_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"; [ -f "$LJ/include/jni.h" ] || { echo "build-windows: no jni.h (set IROH_WIN_JAVA_HOME or IROH_JAVA_HOME)" >&2; exit 1; }
  JNI_INC="$LJ/include;$F/shim/win32"; WJ=""; echo "build-windows: JNI headers from $LJ + shim/win32/jni_md.h (no Windows JDK found)"
fi
mkdir -p "$B" "$FB"
# ---- FSR ----
"$F/fetch-sdk.sh" "$FB/sdk-src"
for e in fsr3upscaler frameinterpolation opticalflow; do
  if [ ! -f "$FB/gen/$e/.done" ]; then python3 -I "$F/gen_permutations.py" "$FB/sdk-src" "$FB/gen/$e" --effect $e --jobs "$JOBS"; touch "$FB/gen/$e/.done"; fi
done
VKINC="$B/vkinc"; mkdir -p "$VKINC"; cp -r /usr/include/vulkan /usr/include/vk_video "$VKINC/"   # headers only; never -I/usr/include
$P-dlltool -d "$F/shim/vulkan-1.def" -l "$B/libvulkan-1.a"
cmake -S "$F" -B "$B/fsr" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$TC" -DIROH_SDK_DIR="$FB/sdk-src" -DIROH_GEN_DIR="$FB/gen" \
      -DIROH_JNI_INCLUDE="$JNI_INC" -DIROH_VK_INCLUDE="$VKINC" -DIROH_VULKAN_IMPORT_LIB="$B/libvulkan-1.a" >/dev/null
ninja -C "$B/fsr" -j "$JOBS"
mkdir -p "$HERE/natives/windows-x64"; $P-strip -o "$HERE/natives/windows-x64/iroh_fsr.dll" "$B/fsr/iroh_fsr.dll"
DLLS=("$HERE/natives/windows-x64/iroh_fsr.dll")
# ---- NRD ----
if [ "$WANT_NRD" = 1 ]; then
  NB="${IROH_NRD_BUILD:-$HERE/build-nrd}"
  [ -f "$NB/nrd-src/_Shaders/Clear.cs.spirv.h" ] || "$HERE/build-nrd.sh" --jobs "$JOBS"
  cmake -S "$N" -B "$B/nrd" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$TC" -DIROH_NRD_SRC="$NB/nrd-src" -DIROH_NRD_QUAD=ON \
        -DIROH_NRD_MATHLIB_DIR="$NB/linux/_deps/mathlib-src" -DIROH_NRD_SHADERMAKE_DIR="$NB/linux/_deps/shadermake-src" -DIROH_JNI_INCLUDE="$JNI_INC" >/dev/null
  ninja -C "$B/nrd" -j "$JOBS" iroh_nrd
  mkdir -p "$HERE/natives-nv/windows-x64"; $P-strip -o "$HERE/natives-nv/windows-x64/iroh_nrd.dll" "$B/nrd/out/iroh_nrd.dll"
  DLLS+=("$HERE/natives-nv/windows-x64/iroh_nrd.dll")
fi
# ---- static checks: imports only system DLLs, every JNI export the Java side declares is present ----
REPO="$(cd "$HERE/.." && pwd)"
python3 -I - "$REPO" "$B" <<'PY'
import re,sys,os
repo,b=sys.argv[1:3]
for cls,pkg,out in (("FsrNative","dev/elemore/iroh/upscale/fsr","fsr"),("NrdNative","dev/elemore/iroh/rt/denoise/nrd","nrd")):
    src=open(f"{repo}/src/main/java/{pkg}/{cls}.java").read()
    names=re.findall(r"^\s*(?:public\s+)?static\s+native\s+[^;(]*?\b(\w+)\s*\(",src,re.M)
    pre="Java_"+pkg.replace("_","_1").replace("/","_")+"_"+cls+"_"
    open(f"{b}/exports-{out}.txt","w").write("".join(pre+n+"\n" for n in names))
PY
ALLOW='^(KERNEL32|msvcrt|USER32|ADVAPI32|SHELL32|ole32|ntdll|VCRUNTIME140|vulkan-1|api-ms-win-crt-[a-z-]+-l1-[0-9]-[0-9]|ucrtbase|bcrypt|WS2_32)\.dll$'
rc=0
for d in "${DLLS[@]}"; do
  base=$(basename "$d" .dll); tag=${base#iroh_}
  imps=$($P-objdump -p "$d" | sed -n 's/^\s*DLL Name: //p' | sort -u)
  echo "== $base.dll imports: $(echo $imps | tr '\n' ' ')"
  for i in $imps; do echo "$i" | grep -qiE "$ALLOW" || { echo "FAIL unexpected import $i in $d" >&2; rc=1; }; done
  exp=$($P-objdump -p "$d" | awk '/\[Ordinal\/Name Pointer\] Table/{f=1;next} f&&/\]/{print $NF}')
  while read -r want; do grep -qxF "$want" <<<"$exp" || { echo "FAIL export missing in $base.dll: $want" >&2; rc=1; }; done < "$B/exports-$tag.txt"
  echo "$base.dll: $(wc -l < "$B/exports-$tag.txt") Java natives, $(echo "$exp" | grep -c '^Java_') JNI exports, $(stat -c %s "$d") bytes, sha256 $(sha256sum "$d" | cut -c1-16)"
done
[ "$rc" = 0 ] || exit 1
# ---- Wine smoke ----
if [ "$SMOKE" = 1 ]; then
  command -v wine >/dev/null || { echo "build-windows: wine missing, smoke skipped" >&2; exit 0; }
  $P-gcc -O1 -o "$B/winsmoke.exe" "$HERE/smoke/winsmoke.c" -static
  export WINEPREFIX="$B/wineprefix" WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml=" WINEARCH=win64
  unset DISPLAY WAYLAND_DISPLAY
  W() { timeout 180 wine "$@"; }
  W "$B/winsmoke.exe" "$(winepath -w "$HERE/natives/windows-x64/iroh_fsr.dll" 2>/dev/null || echo "$HERE/natives/windows-x64/iroh_fsr.dll")" "$B/exports-fsr.txt" \
    Java_dev_elemore_iroh_upscale_fsr_FsrNative_structSize=296 || rc=1
  if [ "$WANT_NRD" = 1 ]; then
    W "$B/winsmoke.exe" "$HERE/natives-nv/windows-x64/iroh_nrd.dll" "$B/exports-nrd.txt" \
      Java_dev_elemore_iroh_rt_denoise_nrd_NrdNative_commonStructSize=364 || rc=1
  fi
  if [ -n "$WJ" ] && [ -x "$WJ/bin/java.exe" ]; then
    # Iroh's REAL loaders on a Windows JVM: (1) -D path, (2) from a jar-layout dir natives/windows-x64/ (extract + load), (3) vendor folder
    S="$REPO/src/main/java/dev/elemore/iroh"; rm -rf "$B/jsmoke"; mkdir -p "$B/jsmoke/classes" "$B/jsmoke/jar/natives/windows-x64" "$B/jsmoke/vendor"
    javac --release 21 -sourcepath "$REPO/src/main/java" -d "$B/jsmoke/classes" "$S/natives/NativeLib.java" "$S/upscale/fsr/NativeLoader.java" "$S/upscale/fsr/FsrNative.java" "$S/upscale/fsr/FsrDispatchStruct.java" \
      "$S/rt/denoise/nrd/NrdLoader.java" "$S/rt/denoise/nrd/NrdNative.java" "$HERE/smoke/LoaderSmoke.java"
    cp "$HERE/natives/windows-x64/iroh_fsr.dll" "$B/jsmoke/jar/natives/windows-x64/"; cp "$HERE/natives-nv/windows-x64/iroh_nrd.dll" "$B/jsmoke/vendor/" 2>/dev/null || true
    WJAVA() { W "$WJ/bin/java.exe" -Xshare:auto -Diroh.natives.cache="$(winepath -w "$B/jsmoke/cache")" "$@" 2>&1 | grep -v "process group affinity\|^\.\.\."; return "${PIPESTATUS[0]}"; }
    CP="$(winepath -w "$B/jsmoke/classes");$(winepath -w "$B/jsmoke/jar")"
    WJAVA -Diroh.fsr.lib="$(winepath -w "$HERE/natives/windows-x64/iroh_fsr.dll")" -cp "$CP" LoaderSmoke fsr || rc=1
    WJAVA -cp "$CP" LoaderSmoke fsr || rc=1
    if [ "$WANT_NRD" = 1 ]; then WJAVA -Diroh.vendor.dir="$(winepath -w "$B/jsmoke/vendor")" -cp "$CP" LoaderSmoke nrd || rc=1; fi
  fi
fi
exit $rc
