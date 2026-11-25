#!/usr/bin/env bash
# Fetches the FSR 3.1.6 shader headers (+ licence text) of AMD FidelityFX SDK v2.4.0 (MIT) into <dest>, pinned by commit
# and content hash. Used ONLY by check-shader-delta.sh (the build itself stays on the patched 1.1.4 tree). Source files
# only: never anything under signedbin/ (signed AMD binaries are not ours to ship). Network on the first run only.
# FFX_SDK24_REPO overrides the clone URL (a local clone works).
set -euo pipefail
COMMIT=7539ae8b6568ad29ed78a753404351daf3b5cca7 # tag v2.4.0
TAR_SHA256=f21eacd567f509b1bbcba7bfd93c1e8a80bc0d1c7f63ac8d20efcab981116e71 # git archive of the paths below at COMMIT
URL="${FFX_SDK24_REPO:-https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="${1:-$HERE/sdk24-src}"
CACHE="${FFX_SDK24_CACHE:-$HERE/.sdk24-cache}"
PATHS=(Kits/FidelityFX/upscalers/fsr3/include/gpu/fsr3upscaler Kits/FidelityFX/docs/license.md)
if [ -f "$DEST/.iroh-fetched-$COMMIT" ]; then echo "fetch-sdk-2.4.0: $DEST already ready"; exit 0; fi
if [ ! -d "$CACHE/.git" ]; then mkdir -p "$CACHE"; git -C "$CACHE" init -q; git -C "$CACHE" remote add origin "$URL"; fi
git -C "$CACHE" cat-file -e "$COMMIT^{commit}" 2>/dev/null || git -C "$CACHE" fetch -q --depth 1 origin "$COMMIT"
GOT=$(git -C "$CACHE" archive --format=tar "$COMMIT" "${PATHS[@]}" | sha256sum | cut -d' ' -f1)
[ "$GOT" = "$TAR_SHA256" ] || { echo "fetch-sdk-2.4.0: content hash mismatch: $GOT (expected $TAR_SHA256)" >&2; exit 1; }
rm -rf "$DEST"; mkdir -p "$DEST"
git -C "$CACHE" archive --format=tar "$COMMIT" "${PATHS[@]}" | tar -x -C "$DEST"
touch "$DEST/.iroh-fetched-$COMMIT"
echo "fetch-sdk-2.4.0: ready in $DEST (commit $COMMIT, hash ok)"
