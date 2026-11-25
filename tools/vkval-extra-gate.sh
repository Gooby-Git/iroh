#!/usr/bin/env bash
# P2-3: GPU-assisted validation pass of the offline gate for int-2b / int-3b (int-4c has its own V-0 based version, H1C).
#   build.gradle -Pvalidate=gpuav writes a gpuav vk_layer_settings.txt and force-enables VK_LAYER_KHRONOS_validation on every
#   instance (also harnesses that do not load it themselves); the layer logs "Validation Error: [ <id> ]" lines to stdout.
#   A task passes when rc=0 and every Validation Error id is allowlisted for it in tools/vkval-gpuav-allowlist.txt (written reason).
# usage: tools/vkval-extra-gate.sh [worktree=.] [task-regex=.]  -> build/vkval-extra/gpuav.txt, exit 1 when a task fails
# Offline only: lavapipe, no game, no Proctor, no GPU.
set -uo pipefail
WT=$(readlink -f "${1:-.}"); FILTER=${2:-.}; cd "$WT"
export JAVA_HOME=${IROH_JAVA_HOME:-/home/gooby/.local/share/mise/installs/java/temurin-25.0.5.1+2.0.LTS}; export PATH=$JAVA_HOME/bin:$PATH
OUT=$WT/build/vkval-extra; mkdir -p "$OUT"
ALLOW=$WT/tools/vkval-gpuav-allowlist.txt
TASKS=("terrainIndirectRepro" "apiReplay" "uploadSyncRepro" "hwCapsRepro" "pipelinePrewarmRepro" "packReplay" "packReplay -Ppost" "packReplay -Pfull" "packFlightRepro -Pmode=new" "packFullVkRepro" "mcReplay" "fsrReplay" "chainReplay" "fsrAdapterReplay")
have() { grep -q "register('$1'" build.gradle; }
allowed() { # task id -> 0 when allowlisted
  [ -f "$ALLOW" ] && grep -v '^#' "$ALLOW" | awk -F' [|] ' -v t="$1" -v id="$2" '$1==id && ($2=="*" || $2=="task="t) {f=1} END {exit !f}'
}
rc=0; : > "$OUT/gpuav.txt"
for t in "${TASKS[@]}"; do
  name=${t%% *}; have "$name" || continue; [[ "$t" =~ $FILTER ]] || continue
  log="$OUT/gpuav-$(echo "$t" | tr ' =' '__').log"
  timeout 900 ./gradlew $t -Pvalidate=gpuav --offline --console=plain -q >"$log" 2>&1; r=$?
  verdict=$(grep -aE '^# [a-zA-Z0-9-]+ (PASS|FAIL|SKIP)' "$log" | tail -1 | cut -c1-140)
  ids=$(grep -aoE 'Validation Error: \[ [^ ]+ \]' "$log" | awk '{print $4}' | sort | uniq -c | awk '{print $2"x"$1}' | paste -sd' ')
  bad=""; for e in $ids; do id=${e%x*}; allowed "$name" "$id" || bad="$bad $e"; done
  crash=$(grep -aqE 'SIGSEGV|hs_err_pid|A fatal error has been detected|core dumped' "$log" && echo " CRASH")
  st=PASS; { [ $r -ne 0 ] || [ -n "$bad" ] || [ -n "$crash" ]; } && { st=FAIL; rc=1; }
  echo "gpuav | $t | $st rc=$r$crash | errors: ${ids:-none}${bad:+ | NOT allowlisted:$bad} | ${verdict:-no verdict line}" | tee -a "$OUT/gpuav.txt"
done
echo "gate: $([ $rc = 0 ] && echo PASS || echo FAIL) (gpuav clean or allowlisted; logs $OUT)"
exit $rc
