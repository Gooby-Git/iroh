#!/bin/bash
# INT-3: run every JavaExec suite of an Iroh worktree + the 16 pack replay goldens + apiReplay golden + FULL round trip;
# one summary line each (rc, last "#" line, golden status). usage: tools/allsuites.sh <worktree> <outdir> [task ...]
# Run ./build.sh first (bridgeLinkTest). After ./gradlew clean, customEvalTest needs the Navidson packHarness dump: rerun it last.
WT="$1"; OUT="$2"; shift 2
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
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
    runs+=("$t|$t")
  done
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
  timeout 900 ./gradlew -q $cmd > "$OUT/$name.log" 2>&1; rc=$?
  last=$(grep -E '^#|PASS|FAIL|SKIP|golden|diff' "$OUT/$name.log" | grep -v '^\s*at ' | tail -1 | cut -c1-160)
  g=$(grep -oE "golden (ok|MISMATCH|FAIL|updated)[^ ]* ?[0-9a-f]{0,12}|SKIP[^\n]{0,60}" "$OUT/$name.log" | head -1)
  printf "%-22s rc=%-3s %s %s\n" "$name" "$rc" "$last" "$g" | tee -a "$OUT/summary.txt"
done
