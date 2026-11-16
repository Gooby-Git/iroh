#!/usr/bin/env bash
# Build Iroh. INSTALL=1 also copies the jar into the Vulkan test instance, unless the game is running.
set -euo pipefail
cd "$(dirname "$0")"

# Loom 1.9 / Gradle 8.12 need JDK 21 (the system default, Java 27, is too new for Gradle 8.12).
export JAVA_HOME="${IROH_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

INSTANCE="$HOME/.local/share/atlauncher/instances/Vulkan"

if [ "${INSTALL:-0}" = 1 ]; then
  # Match only a real game JVM: KnotClient AND --gameDir equal to the instance (P1-1 fix: the old
  # `grep -F "[K]notClient"` searched for the literal text "[K]notClient" and never matched anything).
  # Proctor clones run from ~/.cache/proctor/<name>/instance and do not block an install.
  if ps -eo args | grep -E "KnotClient" | grep -E -- "--gameDir $INSTANCE/?( |$)" >/dev/null; then
    echo "build.sh: the Vulkan instance game is running; refusing to install." >&2
    exit 1
  fi
fi

./gradlew build --console=plain "$@"

JAR=$(ls build/libs/iroh-*.jar | grep -v -- '-sources' | head -n1)
echo "jar: $JAR"

if [ "${INSTALL:-0}" = 1 ]; then
  mkdir -p "$INSTANCE/mods"
  rm -f "$INSTANCE"/mods/iroh-*.jar
  cp "$JAR" "$INSTANCE/mods/"
  echo "installed to $INSTANCE/mods/"
fi
