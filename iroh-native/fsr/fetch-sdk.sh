#!/usr/bin/env bash
# Fetches AMD FidelityFX SDK v1.1.4 (MIT) pinned by commit + content hash, applies our patch, into <dest> (default ./sdk-src).
# The SDK is NOT vendored in this repo. Needs: git, patch, sha256sum, network (first run only).
set -euo pipefail
COMMIT=c6efa6bf7f2027b3ec94f28578bb5965eabb9e55          # tag v1.1.4
TAR_SHA256=9cbc49fb5169fadfad739d33995b844ff7cbbbc94e72ef6c65f1c032af20645e   # git archive of sdk/include sdk/src LICENSE.txt at COMMIT
URL=https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="${1:-$HERE/sdk-src}"
CACHE="${FFX_SDK_CACHE:-$HERE/.sdk-cache}"             # bare-ish clone kept between runs
if [ -f "$DEST/.iroh-patched-$COMMIT" ]; then echo "fetch-sdk: $DEST already ready"; exit 0; fi
if [ ! -d "$CACHE/.git" ]; then
  mkdir -p "$CACHE"; git -C "$CACHE" init -q; git -C "$CACHE" remote add origin "$URL"
fi
git -C "$CACHE" cat-file -e "$COMMIT^{commit}" 2>/dev/null || git -C "$CACHE" fetch -q --depth 1 origin "$COMMIT"
GOT=$(git -C "$CACHE" archive --format=tar "$COMMIT" sdk/include sdk/src LICENSE.txt | sha256sum | cut -d' ' -f1)
[ "$GOT" = "$TAR_SHA256" ] || { echo "fetch-sdk: SDK content hash mismatch: $GOT (expected $TAR_SHA256)" >&2; exit 1; }
rm -rf "$DEST"; mkdir -p "$DEST"
git -C "$CACHE" archive --format=tar "$COMMIT" sdk/include sdk/src LICENSE.txt | tar -x -C "$DEST" --strip-components=0
# layout: $DEST/sdk/{include,src}, $DEST/LICENSE.txt
( cd "$DEST/sdk" && patch -p1 -s < "$HERE/patches/ffx-1.1.4-iroh.patch" )
touch "$DEST/.iroh-patched-$COMMIT"
echo "fetch-sdk: ready in $DEST (commit $COMMIT, hash ok, patch applied)"
