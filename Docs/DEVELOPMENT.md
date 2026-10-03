# Developing Nuvio Z (mobile)

This is the build-and-run guide for the Android and iOS app. For what Nuvio Z is, see the
[README](../README.md). For how the mod relates to upstream Nuvio, see
[`UPSTREAM.md`](./UPSTREAM.md), [`PATCH-SURFACE.md`](./PATCH-SURFACE.md) and
[`Z-FEATURES.md`](./Z-FEATURES.md). Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) before opening an
issue or pull request.

Working on this repository with an AI agent or as a maintainer? [`AGENTS.md`](../AGENTS.md) and
[`STATUS.md`](../STATUS.md) are the instructions and the live handoff, and they cover the desktop
repository too.

## Run it

```bash
git clone https://github.com/Zokaper/nuvio-z.git
cd nuvio-z
./scripts/run-mobile.sh android   # builds the debug app and installs it on emulators / connected devices
./scripts/run-mobile.sh ios       # macOS and Xcode only
```

`run-mobile.sh` takes optional arguments (emulator or physical device, `full` or `playstore`
flavour). Run it with no arguments to see its usage.

### Environment

Nothing is assumed to be on `PATH`. Gradle needs a JDK and, for Android, the SDK. On Windows with
Android Studio installed:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
```

Official builds also need runtime properties (`local.properties`) and a TMDB key. Local debug
builds work without them. Release signing material lives in CI secrets only.

## Project structure

- `composeApp/` is the shared Kotlin Multiplatform and Compose Multiplatform app.
  - `src/commonMain/` holds shared UI, features, repositories and platform-agnostic logic.
  - `src/androidMain/` holds Android-specific integrations.
  - `src/iosMain/` holds iOS-specific integrations.
- `androidApp/` is the Android application module. It has two distribution flavours, `full`
  (GitHub release builds, with the in-app updater) and `playstore`.
- `iosApp/` is the native Xcode project and iOS entry point.
- `iosSetup/` is the Compose Desktop wizard that installs Nuvio Z on an iPhone through SideStore.
  It has its own release line (`ios-setup-v*` tags).
- `distribution/sidestore/` holds the SideStore source feeds and the fallback setup scripts.
- `scripts/` holds the build, release and test helpers.

## Useful commands

```bash
# Android
./gradlew :androidApp:compileFullDebugKotlin
./gradlew :androidApp:assembleFullDebug

# Tests
./gradlew :composeApp:testAndroidHostTest
bash scripts/run-pure-suites.sh        # fastest real signal; needs JAVA_HOME but not Gradle

# iOS frameworks (macOS only)
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

The obvious task names (`compileDebugKotlinAndroid`, `testDebugUnitTest`) do not exist here: the
Android app has product flavours and KMP names its unit-test compilation `hostTest`.

If Gradle fails with `Failed to create MD5 hash for … shrunk-classpath-snapshot.bin`, two builds
are sharing one directory. Run `./gradlew --stop`, delete
`composeApp/build/kotlin/compileKotlinDesktop/classpath-snapshot/` and rebuild.

## Versioning

The mobile version is driven from `iosApp/Configuration/Version.xcconfig`, the shared source of
truth for both iOS and Android (`MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`). Release ordering
uses a separate monotonic serial in `iosApp/Configuration/ReleaseSerial.xcconfig`. A Nuvio Z
version is the upstream version plus a Z revision, such as `0.5.4-z1`; the revision resets when the
base moves. [`RELEASES.md`](./RELEASES.md) is the authoritative release policy: channels, tags,
what a release contains, and how Android and iOS ship together.

## Debug builds

Every device-testing loop goes through the debug channel: a `debug-v*` prerelease with an APK and
an unsigned IPA attached. Debug builds install as **Nuvio Z Debug**
(`com.nuvio.app.z.debug`), next to the release app and with their own data, and update from the
`debug-v*` line. The iPhone debug source is
`distribution/sidestore/source-debug.json`.

## Documentation map

| Document | What it covers |
| --- | --- |
| [`Z-FEATURES.md`](./Z-FEATURES.md) | Every Z feature, numbered, with its platforms and state |
| [`UPSTREAM.md`](./UPSTREAM.md) | Patch-surface rules, versioning and the upstream sync procedure |
| [`PATCH-SURFACE.md`](./PATCH-SURFACE.md) | Every upstream-owned file Nuvio Z modifies |
| [`VANILLA-BUGS.md`](./VANILLA-BUGS.md) | Bugs inherited from upstream, kept apart from ours |
| [`RELEASES.md`](./RELEASES.md) | Release families, channels, tags and publishing |
| [`Z-BACKEND-CONFIGURATION.md`](./Z-BACKEND-CONFIGURATION.md) | The backend Social and Watch Together use |
| [`../distribution/sidestore/README.md`](../distribution/sidestore/README.md) | iPhone install, SideStore and troubleshooting |
