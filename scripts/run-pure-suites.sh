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
# holds *neighbours* only - SourceFacts, StreamItem and the three ranking enums - because the
# real ones reach the whole stream stack and the generated Compose resource bundle, neither of
# which this logic touches.
#
# NEVER stub a file under test. A test against a copy of the code proves nothing, and AGENTS.md
# says so. If a stub drifts from the real declaration the compile fails, which is the intended
# alarm - fix the stub, do not work around it.
#
# ⚠ **A stub can also drift without the compile noticing, and that is the worse failure.**
# `PlaybackMode` was stubbed here and its stub carried a second copy of `isSelectable`. Nothing
# in group 1 called it, so the compile stayed green while the suite quietly asserted a rule the
# app no longer followed. It is the real file now. Prefer compiling the shipped source - even at
# the cost of a compiler plugin - over stubbing anything that carries a decision.
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
# Group 4 only. SyncPreferenceJson.kt reads JsonObject, but declares nothing @Serializable, so
# the serialization *compiler plugin* is not needed here - just the runtime jars.
[ -f serialization-core.jar ] || curl -sSL -o serialization-core.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.9.0/kotlinx-serialization-core-jvm-1.9.0.jar
[ -f serialization-json.jar ] || curl -sSL -o serialization-json.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.9.0/kotlinx-serialization-json-jvm-1.9.0.jar
# Group 5 only. DebridStreamPreferences is @Serializable and DebridSettingsRepository.kt encodes
# it, so unlike group 4 this one needs the serialization *compiler plugin*, which the kotlinc
# distribution does not carry - only the runtime jars are in it. The coroutines runtime the
# repository's StateFlow needs does ship inside kotlinc/lib.
[ -f serialization-plugin.jar ] || curl -sSL -o serialization-plugin.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/2.3.0/kotlin-serialization-compiler-plugin-2.3.0.jar

export PATH="$WORK/kotlinc/bin:$PATH"
KTJ="$WORK/kotlinc/lib/kotlin-test-junit.jar:$WORK/kotlinc/lib/kotlin-test.jar"
CP_BUILD="$WORK/junit.jar:$WORK/hamcrest.jar:$KTJ"
CP_RUN="$CP_BUILD:$WORK/kotlinc/lib/kotlin-stdlib.jar"
CP_JSON="$WORK/serialization-core.jar:$WORK/serialization-json.jar"
CP_COROUTINES="$WORK/kotlinc/lib/kotlinx-coroutines-core-jvm.jar"

M="$REPO/composeApp/src/commonMain/kotlin/com/nuvio/app"
T="$REPO/composeApp/src/commonTest/kotlin/com/nuvio/app"
STUBS="$SCRIPT_DIR/pure-suite-stubs"

# --- Group 1: selection, quality options and the stream route's covering rules ---------------
# These need the neighbour stubs, because PlaybackSourceCandidate carries a StreamItem.
rm -rf "$WORK/out-selection"
# `core/language/LanguageCodes.kt` is the real shipped file, not a stub: SourceRanking's
# language score calls straight into it, and stubbing the thing that decides whether a source is
# watchable would prove nothing about the fix it exists for. It is import-free by design so that
# it can be compiled here at all - see the note at the top of that file.
#
# `features/playback/PlaybackModeModels.kt` is the real file for the same reason, and it used to
# be a stub. That stub carried its own copy of `isSelectable` - the one predicate whose KDoc says
# it must be the only availability test in the codebase - so bringing Instant back would have
# flipped the shipped rule while the suite went on asserting the withdrawn one. Its only
# obstacle was `@Serializable`, hence the plugin and the JSON runtime below.
kotlinc -nowarn -cp "$CP_BUILD:$CP_JSON" -Xplugin="$WORK/serialization-plugin.jar" \
  -d "$WORK/out-selection" \
  "$STUBS"/*.kt \
  "$M/core/language/LanguageCodes.kt" \
  "$M/core/media/ReleaseTags.kt" \
  "$M/features/downloads/SourceRanking.kt" \
  "$M/features/playback/PlaybackModeModels.kt" \
  "$M/features/playback/PlaybackSourceSelector.kt" \
  "$M/features/playback/PlaybackQualityOptions.kt" \
  "$M/features/playback/StreamRouteSurface.kt" \
  "$M/features/playback/PlaybackModeRouter.kt" \
  "$T/core/language/LanguageCodesTest.kt" \
  "$T/core/media/ReleaseTagsTest.kt" \
  "$T/features/playback/PlaybackQualityOptionsTest.kt" \
  "$T/features/playback/StreamRouteSurfaceTest.kt" \
  "$T/features/playback/PlaybackModeRouterTest.kt" \
  "$T/features/playback/PlaybackModeAvailabilityTest.kt" \
  "$T/features/playback/StickySourcePinTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-selection:$CP_RUN:$CP_JSON" org.junit.runner.JUnitCore \
  com.nuvio.app.core.language.LanguageCodesTest \
  com.nuvio.app.core.media.ReleaseTagsTest \
  com.nuvio.app.features.playback.PlaybackQualityOptionsTest \
  com.nuvio.app.features.playback.StreamRouteSurfaceTest \
  com.nuvio.app.features.playback.PlaybackModeRouterTest \
  com.nuvio.app.features.playback.PlaybackModeAvailabilityTest \
  com.nuvio.app.features.playback.StickySourcePinTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# --- Group 2: files with no dependencies at all, so no stubs are involved --------------------
rm -rf "$WORK/out-standalone"
kotlinc -nowarn -cp "$CP_BUILD" -d "$WORK/out-standalone" \
  "$M/features/downloads/DownloadTransfer.kt" \
  "$M/features/streams/PlaybackUrlCredentials.kt" \
  "$M/core/network/ThroughputWindow.kt" \
  "$M/features/playback/PlaybackStartupWatchdog.kt" \
  "$T/features/downloads/DownloadTransferTest.kt" \
  "$T/features/streams/PlaybackUrlCredentialsTest.kt" \
  "$T/core/network/ThroughputWindowTest.kt" \
  "$T/features/playback/PlaybackStartupWatchdogTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-standalone:$CP_RUN" org.junit.runner.JUnitCore \
  com.nuvio.app.features.downloads.DownloadTransferTest \
  com.nuvio.app.features.streams.PlaybackUrlCredentialsTest \
  com.nuvio.app.core.network.ThroughputWindowTest \
  com.nuvio.app.features.playback.PlaybackStartupWatchdogTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# --- Group 3: the setup wizard's ordering, its show-once rule and its animation --------------
# Both files are import-free, so this group needs no stubs at all. The wizard itself is a Compose
# gate no test can reach once it is on screen, which is why the step machine and the playback-mode
# storyboard both live outside it.
rm -rf "$WORK/out-setup"
kotlinc -nowarn -cp "$CP_BUILD" -d "$WORK/out-setup" \
  "$M/features/setup/SetupWizardSteps.kt" \
  "$M/features/setup/SetupModeStoryboard.kt" \
  "$T/features/setup/SetupWizardStepsTest.kt" \
  "$T/features/setup/SetupModeStoryboardTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-setup:$CP_RUN" org.junit.runner.JUnitCore \
  com.nuvio.app.features.setup.SetupWizardStepsTest \
  com.nuvio.app.features.setup.SetupModeStoryboardTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# --- Group 4: the two rules that decide what a settings sync may overwrite -------------------
# SyncPreferenceJson.kt is shared by every settings store on every platform, so a fault in it is
# a fault in all of them at once - which is exactly what happened twice: `syncKeysToClear` exists
# because a pull wiped the playback settings, and `mergeMonotonicSyncInt` because a pull dragged
# the setup wizard's revision backwards and re-gated the app on every launch.
rm -rf "$WORK/out-sync"
kotlinc -nowarn -cp "$CP_BUILD:$CP_JSON" -d "$WORK/out-sync" \
  "$M/core/sync/SyncPreferenceJson.kt" \
  "$T/core/sync/SyncKeysToClearTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-sync:$CP_RUN:$CP_JSON" org.junit.runner.JUnitCore \
  com.nuvio.app.core.sync.SyncKeysToClearTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# --- Group 5: the debrid stream presentation pipeline ---------------------------------------
# StreamModels.kt is compiled from the shipped source, so StreamItem, AioStreamData and the cache
# status types are real - the stubs under pure-suite-stubs/debrid stand in only for the build
# config, the generated Compose resource bundle and the per-platform key store. This group is what
# proves the preference scope: the pipeline must reach addon-side debrid results with no resolver
# connected, and must still leave a plain addon row's own name alone.
rm -rf "$WORK/out-debrid"
kotlinc -nowarn -cp "$CP_BUILD:$CP_JSON:$CP_COROUTINES" \
  -Xplugin="$WORK/serialization-plugin.jar" \
  -d "$WORK/out-debrid" \
  "$STUBS"/debrid/*.kt \
  "$M/core/media/ReleaseTags.kt" \
  "$M/features/streams/StreamModels.kt" \
  "$M/features/debrid/DebridProvider.kt" \
  "$M/features/debrid/DebridSettings.kt" \
  "$M/features/debrid/DebridSettingsRepository.kt" \
  "$M/features/debrid/DebridStreamFormatterDefaults.kt" \
  "$M/features/debrid/DebridStreamTemplateEngine.kt" \
  "$M/features/debrid/DebridStreamFormatter.kt" \
  "$M/features/debrid/DebridStreamPresentation.kt" \
  "$T/features/debrid/DebridProviderTest.kt" \
  "$T/features/debrid/DebridSettingsTest.kt" \
  "$T/features/debrid/DebridStreamPresentationTest.kt" \
  2>&1 | grep -v "^warning:" | grep -v "Picked up JAVA" || true

java -cp "$WORK/out-debrid:$CP_RUN:$CP_JSON:$CP_COROUTINES" org.junit.runner.JUnitCore \
  com.nuvio.app.features.debrid.DebridProviderTest \
  com.nuvio.app.features.debrid.DebridSettingsTest \
  com.nuvio.app.features.debrid.DebridStreamPresentationTest 2>&1 | grep -v "Picked up JAVA_TOOL"

# Deliberately not run here, and CI is the gate for both:
#   PlaybackSourceSelectorTest  - reaches the real AIO types
#   AutoPlayFailoverTest        - reaches the real StreamItem and StreamsRepository
echo
echo "Not covered here (CI is the gate): PlaybackSourceSelectorTest, AutoPlayFailoverTest,"
echo "and everything Compose - App.kt and the player runtime are parser-checked only."
