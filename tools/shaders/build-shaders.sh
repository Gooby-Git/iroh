#!/usr/bin/env bash
# Compiles Iroh's own small compute shaders to SPIR-V resources (checked in; needs glslangValidator).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../src/main/resources/dev/elemore/iroh/rt/denoise"
glslangValidator -V --target-env vulkan1.2 -Os -S comp "$HERE/temporal_accum.comp" -o "$OUT/temporal_accum.spv"
spirv-val --target-env vulkan1.2 "$OUT/temporal_accum.spv"
echo "built $OUT/temporal_accum.spv"
