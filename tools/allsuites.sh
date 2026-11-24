#!/bin/bash
# INT-3 (+INT-4 RT suites): run every JavaExec suite of an Iroh worktree + the 16 pack replay goldens + apiReplay golden + FULL round trip;
# one summary line each (rc, last "#" line, golden status). usage: tools/allsuites.sh <worktree> <outdir> [task ...]
# V-0: lavapipe suites run under VK_LAYER_KHRONOS_validation by default (VKVAL=off|gpuav|bestpractices to change);
# the "vkval" column is the error count from build.gradle's vkvalCheck ("-" = suite does not use lavapipe).
# Run ./build.sh first (bridgeLinkTest). After ./gradlew clean, customEvalTest needs the Navidson packHarness dump: rerun it last.
WT="$1"; OUT="$2"; shift 2
export JAVA_HOME=${IROH_JAVA_HOME:-/home/gooby/.local/share/mise/installs/java/temurin-25.0.5.1+2.0.LTS}
mkdir -p "$OUT"
cd "$WT" || exit 1
CAP="$HOME/Projects/proctor/knowledge/perf/p3/capture1.irohframe"
NAV="$HOME/.local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson"
SKIP=" fsrNative nrdNative printTestCp packHarness packGraph packReplay "
runs=()
if [ $# -gt 0 ]; then
  for t in "$@"; do runs+=("$t|$t"); done
else
  for t in $(grep -oP "tasks.register\('\K[A-Za-z0-9]+(?=', JavaExec)" build.gradle); do
    case "$SKIP" in *" $t "*) continue;; esac
    [ "$t" = rtLook ] && continue   # INT-4: run below with -Pout inside the worktree
    runs+=("$t|$t")
  done
  # INT-4: the RT suites (card/P5-0..P5-3) must be among the discovered ones; fail loudly if a rename drops one
  for t in rtReplay rtSeamTest rtProbe rtEntities rtLights rtLook memorySharesTest rtHangRepro rtWorldReplay; do   # R1-HANG: +2
    grep -q "tasks.register('$t', JavaExec)" build.gradle || echo "MISSING RT/INT-4 task $t" | tee -a "$OUT/summary.txt"
  done
  runs+=("rtLook|rtLook -Pout=build/rt-allsuites")
  runs+=("packHarness-self|packHarness -Ppack=src/test/packs")
  runs+=("packHarness-nav|packHarness -Ppack=$NAV")
  runs+=("packGraph-nav|packGraph -Ppack=$NAV")
  runs+=("packGraph-self|packGraph -Ppack=src/test/packs")
  runs+=("golden-synthetic|packReplay -Pgolden=check")
  runs+=("golden-post|packReplay -Ppost -Pgolden=check")
  runs+=("golden-capture1|packReplay -Pcapture=$CAP -Pgolden=check")
  runs+=("golden-full|packReplay -Pfull -Pgolden=check")
  for c in beacon block breaking entities glint hand outline particles sky weather; do
    runs+=("golden-full-$c|packReplay -Pfull -PgeoClasses=$c -Pgolden=check")
  done
  runs+=("golden-up0.5|packReplay -Pupscale=0.5 -Pgolden=check")
  runs+=("golden-full-up0.5|packReplay -Pfull -Pupscale=0.5 -Pgolden=check")
  runs+=("apiReplay-golden|apiReplay -Pgolden=a82fa578f9d425d30c2da326e5ae2d9a449469609950d27a0635f9a56d6c5e46")
  runs+=("roundtrip-full|packReplay -Pfull -Proundtrip")
fi
for r in "${runs[@]}"; do
  name="${r%%|*}"; cmd="${r#*|}"
  timeout 1800 ./gradlew -q $cmd ${VKVAL:+-Pvalidate=$VKVAL} > "$OUT/$name.log" 2>&1; rc=$?
  g=$(grep -oE "golden (ok|MISMATCH|FAIL|updated)[^ ]* ?[0-9a-f]{0,12}|SKIP[^\n]{0,60}" "$OUT/$name.log" | head -1)
  v=$(grep -oP '^# vkval .* mode=\S+ messages=\d+ errors=\K\d+' "$OUT/$name.log" | awk '{s+=$1} END {if (NR) print s}')
  last=$(grep -E '^#|PASS|FAIL|SKIP|golden|diff' "$OUT/$name.log" | grep -v '^\s*at \|^# vkval\|^#   ' | tail -1 | cut -c1-140)
  printf "%-22s rc=%-3s vkval=%-4s %s %s\n" "$name" "$rc" "${v:--}" "$last" "$g" | tee -a "$OUT/summary.txt"
done
