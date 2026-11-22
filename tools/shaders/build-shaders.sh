#!/usr/bin/env bash
# Compiles Iroh's own small compute shaders to SPIR-V resources (checked in; needs glslangValidator).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../src/main/resources/dev/elemore/iroh/rt/denoise"
for s in temporal_accum svgf_temporal svgf_atrous; do glslangValidator -V --target-env vulkan1.2 -Os -S comp "$HERE/$s.comp" -o "$OUT/$s.spv"; spirv-val --target-env vulkan1.2 "$OUT/$s.spv"; done
echo "built $OUT/temporal_accum.spv"
