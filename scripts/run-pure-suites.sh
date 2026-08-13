#!/usr/bin/env bash
#
# Compile and run the shipped playback-selection logic outside Gradle.
#
# This is item 2 of "Verifying without Gradle" in AGENTS.md, made repeatable. It exists because
# Gradle cannot configure in the Claude/Codex sandbox - `dl.google.com` is blocked, so the
# Android Gradle Plugin never resolves - and without it the only local check is a parser pass,
# which catches syntax and nothing else. Every playback fault in 0.5.0-beta was found by reading
# and confirmed by this.
#
#   ./scripts/run-pure-suites.sh [repo-path] [work-dir]
#
# Defaults to this repository and /tmp/nuvio-pure-suites. Downloads kotlinc and the JUnit jars
# on first run (~80 MB) and reuses them afterwards.
#
# WHAT IS REAL AND WHAT IS NOT
#
# Everything under composeApp/ is the shipped source, unmodified. `scripts/pure-suite-stubs/`
# holds *neighbours* only - SourceFacts, StreamItem, PlaybackMode and the three ranking enums -
# because the real ones reach kotlinx.serialization, the whole stream stack and the generated
# Compose resource bundle, none of which this logic touches.
#
# NEVER stub a file under test. A test against a copy of the code proves nothing, and AGENTS.md
# says so. If a stub drifts from the real declaration the compile fails, which is the intended
# alarm - fix the stub, do not work around it.
#
# This does not replace CI. Compose, expect/actual matching and anything touching resources are
# only checked by a real build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"
WORK="${2:-/tmp/nuvio-pure-suites}"
KOTLIN_VERSION="2.3.0"

mkdir -p "$WORK"
cd "$WORK"

if [ ! -x "$WORK/kotlinc/bin/kotlinc" ]; then
  echo "Fetching kotlinc $KOTLIN_VERSION ..."
  curl -sSL -o kotlin.zip \
    "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"
  unzip -q -o kotlin.zip
fi
[ -f junit.jar ] || curl -sSL -o junit.jar \
  https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
[ -f hamcrest.jar ] || curl -sSL -o hamcrest.jar \
  https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar

export PATH="$WORK/kotlinc/bin:$PATH"
KTJ="$WORK/kotlinc/lib/kotlin-test-junit.jar:$WORK/kotlinc/lib/kotlin-test.jar"
CP_BUILD="$WORK/junit.jar:$WORK/hamcrest.jar:$KTJ"
CP_RUN="$CP_BUILD:$WORK/kotlinc/lib/kotlin-stdlib.jar"

M="$REPO/composeApp/src/commonMain/kotlin/com/nuvio/app"
T="$REPO/composeApp/src/commonTest/kotlin/com/nuvio/app"
STUBS="$SCRIPT_DIR/pure-suite-stubs"

# --- Group 1: selection, quality options and the stream route's covering rules ---------------
# These need the neighbour stubs, because PlaybackSourceCandidate carries a StreamItem.
rm -rf "$WORK/out-selection"
kotlinc -nowarn -cp "$CP_BUILD" -d "$WORK/out-selection" \
  "$STUBS"/*.kt \
  "$M/features/downloads/SourceRanking.kt" \
  "$M/features/playback/PlaybackSourceSelector.kt" \
  "$M/features/playback/PlaybackQualityOptions.kt" \
  "$M/features/playback/StreamRouteSurface.kt" \
  "$M/features/playback/PlaybackModeRouter.kt" \
  "$T/features/playback/PlaybackQualityOptionsTest.kt" \
  "$T/features/playback/StreamRouteSurfaceTest.kt" \
  "$T/features/playback/PlaybackModeRouterTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-selection:$CP_RUN" org.junit.runner.JUnitCore \
  com.nuvio.app.features.playback.PlaybackQualityOptionsTest \
  com.nuvio.app.features.playback.StreamRouteSurfaceTest \
  com.nuvio.app.features.playback.PlaybackModeRouterTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# --- Group 2: files with no dependencies at all, so no stubs are involved --------------------
rm -rf "$WORK/out-standalone"
kotlinc -nowarn -cp "$CP_BUILD" -d "$WORK/out-standalone" \
  "$M/features/downloads/DownloadTransfer.kt" \
  "$M/features/streams/PlaybackUrlCredentials.kt" \
  "$T/features/downloads/DownloadTransferTest.kt" \
  "$T/features/streams/PlaybackUrlCredentialsTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-standalone:$CP_RUN" org.junit.runner.JUnitCore \
  com.nuvio.app.features.downloads.DownloadTransferTest \
  com.nuvio.app.features.streams.PlaybackUrlCredentialsTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# --- Group 3: the setup wizard's ordering and its show-once rule -----------------------------
# SetupWizardSteps.kt is import-free, so this group needs no stubs at all. The wizard itself is
# a Compose gate no test can reach once it is on screen, which is why the step machine lives
# outside it.
rm -rf "$WORK/out-setup"
kotlinc -nowarn -cp "$CP_BUILD" -d "$WORK/out-setup" \
  "$M/features/setup/SetupWizardSteps.kt" \
  "$T/features/setup/SetupWizardStepsTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-setup:$CP_RUN" org.junit.runner.JUnitCore \
  com.nuvio.app.features.setup.SetupWizardStepsTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# Deliberately not run here, and CI is the gate for both:
#   PlaybackSourceSelectorTest  - reaches the real AIO types
#   AutoPlayFailoverTest        - reaches the real StreamItem and StreamsRepository
echo
echo "Not covered here (CI is the gate): PlaybackSourceSelectorTest, AutoPlayFailoverTest,"
echo "and everything Compose - App.kt and the player runtime are parser-checked only."
