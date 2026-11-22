#!/usr/bin/env bash
# Fetches NVIDIA RTX NRD (Real-time Denoisers) v4.18.0 pinned by commit + content hash into <dest> (default ./nrd-src).
# NVIDIA RTX SDKs licence applies (LICENSE.txt in the source, copied next to the built library): this source is NEVER
# committed to the Iroh repo and NRD is stock (no patches, no forks). Needs: git, tar, sha256sum, network (first run only).
set -euo pipefail
COMMIT=8f12a22fc90e6a3c7f5da4c9c3103633c842963a   # tag v4.18.0 (9 October 2026)
TAR_SHA256=bbe5a72c3f14bc357528121a9ab37cd9db42420b6ddfa07656253860cf706eaa   # git archive of the whole tree at COMMIT
URL=https://github.com/NVIDIAGameWorks/RayTracingDenoiser.git
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="${1:-$HERE/nrd-src}"
CACHE="${NRD_SRC_CACHE:-$HERE/.nrd-cache}"
if [ -f "$DEST/.iroh-ready-$COMMIT" ]; then echo "fetch-nrd: $DEST already ready"; exit 0; fi
if [ ! -d "$CACHE/.git" ]; then mkdir -p "$CACHE"; git -C "$CACHE" init -q; git -C "$CACHE" remote add origin "$URL"; fi
git -C "$CACHE" cat-file -e "$COMMIT^{commit}" 2>/dev/null || git -C "$CACHE" fetch -q --depth 1 origin "$COMMIT"
GOT=$(git -C "$CACHE" archive --format=tar "$COMMIT" | sha256sum | cut -d' ' -f1)
[ "$GOT" = "$TAR_SHA256" ] || { echo "fetch-nrd: content hash mismatch: $GOT (expected $TAR_SHA256)" >&2; exit 1; }
rm -rf "$DEST"; mkdir -p "$DEST"
git -C "$CACHE" archive --format=tar "$COMMIT" | tar -x -C "$DEST"
touch "$DEST/.iroh-ready-$COMMIT"
echo "fetch-nrd: ready in $DEST (commit $COMMIT, hash ok)"
