#!/usr/bin/env bash
# H-1: run Iroh's offline Vulkan harnesses (lavapipe) under the Khronos Profiles layer with real GPU profiles.
# usage: tools/hw-matrix.sh <worktree> [label] [profile-glob] [task-regex]     e.g. tools/hw-matrix.sh ~/Projects/iroh-wt-hw int2b 'nv-*'
# Needs: layer installed in ~/.local/share/vulkan/explicit_layer.d (see knowledge/build.md "Profiles layer"), JDK 25, jq.
# Output: build-hw/<label>/<profile>/<task>.log + build-hw/<label>/matrix.txt (table) ; exit 0 always (the table is the verdict).
# Offline only: lavapipe, no game, no Proctor, no GPU. Never touches the worktree except gradle's build/ dir.
set -uo pipefail
WT=$(readlink -f "${1:?worktree}"); LABEL=${2:-$(basename "$WT")}; GLOB=${3:-*}; TFILTER=${4:-.}
HERE=$(cd "$(dirname "$0")" && pwd); PROF=$HERE/hw-profiles
OUT=$HERE/../build-hw/$LABEL; mkdir -p "$OUT"; 
export JAVA_HOME=${IROH_JAVA_HOME:-/home/gooby/.local/share/mise/installs/java/temurin-25.0.5.1+2.0.LTS}; export PATH=$JAVA_HOME/bin:$PATH
LAYER_DIR=${VK_PROFILES_LAYER_DIR:-$HOME/.local/share/vulkan/explicit_layer.d}
[ -f "$LAYER_DIR/VkLayer_khronos_profiles.json" ] || { echo "profiles layer missing in $LAYER_DIR"; exit 2; }
# task list: "gradle args" ; tasks absent from the worktree are skipped
TASKS=("float16Repro" "attachLayoutRepro" "freshTexRepro" "vkStackRepro -PstackKb=48" "packThreadRepro" "packReplay" "rtDeviceChainRepro" "rtReplay" "hwCapsRepro")
have() { grep -q "register('$1'" "$WT/build.gradle"; }

run_cell() { # profile(or BASE) task-args -> prints "VERDICT|harness|valerr|note"
  local prof=$1 targs=$2 name log rc
  name=${targs%% *}; log=$OUT/$prof/$name.log; mkdir -p "$OUT/$prof"
  (
    cd "$WT"
    if [ "$prof" != BASE ]; then
      pf=$PROF/$prof.json
      export VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_profiles
      export VK_KHRONOS_PROFILES_PROFILE_FILE=$pf
      export VK_KHRONOS_PROFILES_PROFILE_NAME=$(jq -r '.profiles|keys[0]' "$pf")
      export VK_KHRONOS_PROFILES_SIMULATE_CAPABILITIES=SIMULATE_API_VERSION_BIT,SIMULATE_FEATURES_BIT,SIMULATE_PROPERTIES_BIT,SIMULATE_EXTENSIONS_BIT,SIMULATE_FORMATS_BIT
      export VK_KHRONOS_PROFILES_DEBUG_REPORTS=DEBUG_REPORT_ERROR_BIT
    fi
    timeout 900 ./gradlew $targs --offline --console=plain -q >"$log" 2>&1
  ); rc=$?
  local harness valerr note=""
  harness=$(grep -aoE '^# [a-z0-9]+ (PASS|FAIL|SKIP)[^ ]*' "$log" | tail -1 | awk '{print $3}')
  [ -z "$harness" ] && harness=NOLINE
  # validation errors: V-0 digest line (int-4c) else the harness' own "validation:" lines (int-2b)
  if grep -aq '^# vkval ' "$log"; then valerr=$(grep -aoE '^# vkval .* errors=[0-9]+' "$log" | grep -oE 'errors=[0-9]+' | cut -d= -f2 | paste -sd+ | bc)
  else valerr=$(grep -acE '^ *(validation:|! vk)' "$log"); fi
  grep -aqE 'terminate called|SIGSEGV|hs_err_pid|A fatal error has been detected|core dumped|Segmentation' "$log" && note="CRASH"
  grep -aq 'PROFILES ERROR' "$log" && note="$note layer-error"
  [ $rc -ne 0 ] && note="$note rc=$rc"
  echo "$harness|$valerr|$note"
}

tasks=(); for t in "${TASKS[@]}"; do have "${t%% *}" && [[ "${t%% *}" =~ $TFILTER ]] && tasks+=("$t"); done
[[ " ${tasks[*]} " == *hwCapsRepro* ]] && : > "$OUT/tiers.txt"  # only a run that includes hwCapsRepro rewrites the tier table
echo "worktree $WT ($(git -C "$WT" rev-parse --short HEAD)) tasks: ${tasks[*]}" | tee "$OUT/matrix.txt"
declare -A BH BV
for t in "${tasks[@]}"; do r=$(run_cell BASE "$t"); BH[$t]=${r%%|*}; rest=${r#*|}; BV[$t]=${rest%%|*}; echo "BASE  ${t%% *}: $r" | tee -a "$OUT/matrix.txt"; done
printf '\n%-30s' profile | tee -a "$OUT/matrix.txt"; for t in "${tasks[@]}"; do printf ' %-16s' "${t%% *}" | tee -a "$OUT/matrix.txt"; done; echo | tee -a "$OUT/matrix.txt"
for pf in "$PROF"/$GLOB.json; do
  p=$(basename "$pf" .json); line=$(printf '%-30s' "$p"); fails=""
  for t in "${tasks[@]}"; do
    r=$(run_cell "$p" "$t"); h=${r%%|*}; rest=${r#*|}; v=${rest%%|*}; n=${rest#*|}
    # PASS: same harness verdict as BASE, no crash, validation errors not above BASE
    if [[ "$n" == *CRASH* ]]; then c="FAIL(crash)"; elif [ "$h" = SKIP ] && [[ "$n" != *rc=* ]] && [ "${v:-0}" -le "${BV[$t]:-0}" ]; then c="SKIP"; elif [ "$h" = SKIP ]; then c="FAIL(skip-with-errors)"; elif [ "$h" != "${BH[$t]}" ]; then c="FAIL($h)"; elif [ "${v:-0}" -gt "${BV[$t]:-0}" ]; then c="FAIL(val+$((v-${BV[$t]:-0})))"; else c="PASS(v$v)"; fi
    [[ "$n" == *layer-error* ]] && c="$c*"
    line+=$(printf ' %-16s' "$c")
    # H1B: the production tier line of hwCapsRepro goes to tiers.txt (one row per profile)
    [ "${t%% *}" = hwCapsRepro ] && grep -ah '^# hwcaps-tier' "$OUT/$p/hwCapsRepro.log" | tail -1 | sed "s|^# hwcaps-tier|$p|" >> "$OUT/tiers.txt"
  done
  echo "$line" | tee -a "$OUT/matrix.txt"
done
echo "logs: $OUT/<profile>/<task>.log ; BASE = no profile layer ; * = profiles layer reported an error ; SKIP = harness precondition absent on that profile (not a failure) ; tiers: $OUT/tiers.txt" | tee -a "$OUT/matrix.txt"
