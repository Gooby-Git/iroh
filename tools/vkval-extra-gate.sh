#!/usr/bin/env bash
# H1C (RQ-APP1-c): the extra validation passes of the offline gate, once per merge batch (int-4c and later).
#   gpuav          : GPU-assisted validation (BDA / descriptor / indirect-buffer content checks). Errors FAIL (allowlist: tools/vkval-allowlist.txt, written reason per entry).
#   bestpractices  : best-practices + AMD vendor checks. REPORT ONLY (perf advice, never fails the gate); counts per harness go to the table.
# usage: tools/vkval-extra-gate.sh [worktree=.]   -> build/vkval-extra/<mode>.txt, exit 1 when a gpuav run fails
# Offline only: lavapipe, no game, no Proctor. NOT run under gpuav (lavapipe SIGSEGVs in the JIT'd instrumented shader, hs_err in libvulkan_lvp,
# si_addr 0x64; only gpu-av shader instrumentation triggers it; UNVERIFIED which layer/lavapipe side): fsrVelocityReplay, rtReplay steps c..i.
set -uo pipefail
WT=$(readlink -f "${1:-.}"); cd "$WT"
export JAVA_HOME=${IROH_JAVA_HOME:-/home/gooby/.local/share/mise/installs/java/temurin-25.0.5.1+2.0.LTS}; export PATH=$JAVA_HOME/bin:$PATH
OUT=$WT/build/vkval-extra; mkdir -p "$OUT"
# MERGE-2B: + the int-2b/3b gpuav set (P2-3/7b/7c/11): terrainCullReplay / hizCullReplay twice (shader-instrumented without the horizon
# combinations, lavapipe SIGSEGVs in the instrumented horizon build shader, RQ-B-gpuav-lvp pattern; and every combination with instrumentation off).
TASKS=("terrainIndirectRepro" "terrainWorldReplay -Ptw.quick" "terrainCullReplay -Ptc.quick -Ptc.noHorizon" "terrainCullReplay -Ptc.quick -PvkvalSet.gpuav_shader_instrumentation=false" "hizCullReplay -Phz.quick -Phz.noHorizon" "hizCullReplay -Phz.quick -PvkvalSet.gpuav_shader_instrumentation=false" "hwCapsRepro" "pipelinePrewarmRepro" "packReplay" "packReplay -Ppost" "packReplay -Pfull" "packFlightRepro" "packFullVkRepro" "apiReplay" "uploadSyncRepro" "mcReplay" "rtReplay -Pquick=1 -Pstep=ab" "fsrReplay" "fsrConstantsTest" "fsrMetrics" "fsrMipMetrics" "chainReplay" "fsrAdapterReplay" "fgReplay" "rtHangRepro" "rtWorldReplay -Pframes=60")  # R1-HANG: +2 (AS builds with game upload order, world-scale); PACK-FULL: +packFullVkRepro; UPLOAD-SYNC: +uploadSyncRepro
have() { grep -q "register('$1'" build.gradle; }
rc=0
for mode in gpuav bestpractices; do
  : > "$OUT/$mode.txt"
  for t in "${TASKS[@]}"; do
    have "${t%% *}" || continue
    log="$OUT/$mode-$(echo "$t" | tr ' =' '__').log"
    timeout 900 ./gradlew $t -Pvalidate=$mode --offline --console=plain -q >"$log" 2>&1; r=$?
    line=$(grep -a '^# vkval ' "$log" | tail -1 | sed -E 's/ layer-loaded=[^ ]* implicit-off=[^ ]*//; s/ log=.*//')
    echo "$mode | $t | rc=$r | ${line:-NO-VKVAL-LINE}" | tee -a "$OUT/$mode.txt"
    if [ $r -ne 0 ] && [ "$mode" = gpuav ]; then rc=1; fi
  done
done
echo "gate: $([ $rc = 0 ] && echo PASS || echo FAIL) (gpuav must be clean or allowlisted; bestpractices is report-only: build/vkval-extra/bestpractices.txt)"
exit $rc
