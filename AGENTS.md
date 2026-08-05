# Nuvio Z Agent Guide

This file is the canonical instruction set for any coding agent working in this
repository, including Codex and Claude. It applies to the entire repository.
Read `STATUS.md` before making changes and update it when meaningful work is
completed, verified, deferred, or blocked.

## Product and Repository

- Product name: **Nuvio Z**
- Android release application ID: `com.nuvio.app.z`
- Android debug application ID: `com.nuvio.app.z.debug`
- Kotlin namespace remains `com.nuvio.app`.
- The project is based on NuvioMobile commit
  `979d5680d4a1a755a3e833332c36e5cb3b4d3f71`.
- `upstream` must continue to point to
  `https://github.com/NuvioMedia/NuvioMobile.git`.
- The private personal fork should use the `origin` remote.
- Preserve GPL-3.0 licensing and upstream notices.

## Security and Privacy

Never commit or print private configuration. In particular:

- `local.properties`
- `.signing/` and any `*.jks` or `*.keystore` file
- GitHub access tokens or credentials
- Trakt client secrets
- user-specific addon or AIOStreams manifest URLs
- debrid credentials, session tokens, or account identifiers
- locally built APK/AAB artifacts

The public Supabase client configuration used for personal builds is kept in
the ignored `local.properties`; do not move it into tracked source. Before a
commit or push, inspect staged files and run a targeted secret scan.

## Working Rules

- Preserve unrelated user changes in the working tree.
- Prefer small, focused changes with regression tests.
- Do not change the retained Kotlin namespace when changing app branding.
- Keep release and debug application IDs side-by-side installable.
- Keep existing Trakt callback URI schemes and deep links intact.
- Do not fetch a globally disallowed addon during automatic discovery.
- Snapshot presets and source policy into every download batch.
- Keep manual source selection available when automatic selection fails.
- Automatic downloads may use only direct HTTP(S) files or successfully
  debrid-resolved files. Magnets, torrent files, HLS, and DASH remain manual.
- Use the largest credible size for preset-cap enforcement.
- Only materially contradictory authoritative metadata should require user
  approval. Rounded filename or display-text estimates are not hard conflicts.
- Persist every download state transition and preserve resumable `.part` files.
- Resolve at most three episodes concurrently and transfer at most two files
  concurrently.

## Important Areas

- Presets and source policy:
  `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/PresetDownloads.kt`
- Source normalization:
  `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/SourceFacts.kt`
- Batch planning/discovery:
  `DownloadBatches.kt`, `AutomaticDownloadDiscovery.kt`,
  `PresetDownloadCoordinator.kt`
- Queue persistence:
  `DownloadsRepository.kt`
- Android transfer/background integration:
  `composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/`
- Preset and review UI:
  `PresetDownloadDialog.kt`, `DownloadsScreen.kt`,
  `features/details/MetaDetailsScreen.kt`
- Stream/AIO models:
  `features/streams/StreamModels.kt`, `StreamParser.kt`

## Build and Verification

Run commands from the repository root on Windows:

```powershell
.\gradlew.bat :composeApp:testAndroidHostTest --console=plain --max-workers=4
.\gradlew.bat :androidApp:assembleFullDebug --console=plain --max-workers=4
.\gradlew.bat :androidApp:assembleFullRelease --console=plain --max-workers=4
```

For focused download tests:

```powershell
.\gradlew.bat :composeApp:testAndroidHostTest `
  --tests "com.nuvio.app.features.downloads.SourceFactsExtractorTest" `
  --tests "com.nuvio.app.features.downloads.PresetDownloadsTest" `
  --console=plain --max-workers=4
```

In `NuvioZDesktop`, the desktop suite includes the download harness, which drives
the real queue and the real downloader against a deliberately faulty local media
host (see "Verifying without Gradle" below):

```powershell
.\gradlew.bat :composeApp:desktopTest --console=plain
```

Release builds run R8 and can use substantial CPU. Use a bounded worker count
unless the user explicitly prefers maximum throughput.

For device testing, discover the current serial with `adb devices -l`; do not
hardcode a personal device serial. Install with `adb install -r` to preserve app
data. Use UI automation and filtered `AndroidRuntime` logs for smoke testing,
and never queue real bulk downloads merely to test a review screen.

## The Two Repositories

This work spans two repositories that share history and must be kept in step:

- `Zokaper/nuvio-z` - Android/iOS. Default branch `main`. Has `AGENTS.md` and
  `STATUS.md`; **`STATUS.md` here is the handoff for both repositories.**
- `Zokaper/NuvioZDesktop` - the desktop fork. Default branch `Dev`. Its small
  `AGENTS.md` points back to these canonical files; it has no separate
  `STATUS.md`.

Almost every file under `composeApp/src/commonMain`, `androidMain` and `iosMain`
is **byte-identical** between the two. Before editing one, check:

```bash
diff -q /path/nuvio-z/<file> /path/NuvioZDesktop/<file>
```

If identical, edit in `nuvio-z` and `cp` the file across. If it differs, port the
change by hand. Things that legitimately differ: `MetaDetailsScreen.kt`,
`strings.xml` (desktop has extra keys), the desktop's `AppFeaturePolicy` gating,
and everything under `desktopMain`.

**`desktopMain` has no counterpart in `nuvio-z`.** Any `expect` declaration needs
a **desktop actual** in `NuvioZDesktop` as well as the android and ios ones. This
has broken the desktop build twice (`publishNativeTabTitles`, then nearly
`onBatchesChanged`): Android and iOS compile fine without it, so nothing catches
a missing desktop actual until the Windows job runs.

## Building and Releasing from CI

Gradle **cannot configure in the Claude/Codex sandbox** - the egress policy
blocks `dl.google.com`, so the Android Gradle Plugin never resolves. CI is the
only compiler available there. See "Verifying without Gradle" below for what can
still be checked locally.

### Workflows

| Repository | Workflow | Trigger | What it does |
| --- | --- | --- | --- |
| both | `ci.yml` | every push | nuvio-z: Android host tests + debug APK. Desktop: desktop tests. |
| `nuvio-z` | `android-release.yml` | `workflow_dispatch` | `mode`: `dry-run` / `draft` / `publish` |
| `NuvioZDesktop` | `desktop-release.yml` | `workflow_dispatch` | `mode`: `build-only` / `dry-run` / `draft` / `publish`, `target`: `windows` |

`desktop-release.yml` with `mode=build-only`, `target=windows` is **the only
thing that compiles `desktopMain`**. Run it before any desktop release.

### Release procedure

Versions live in files, not tags. The workflow derives the tag from the file and
refuses to run if the state is wrong.

| Repository | Version file | Keys |
| --- | --- | --- |
| `nuvio-z` | `iosApp/Configuration/Version.xcconfig` | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION` |
| `NuvioZDesktop` | `composeApp/Configuration/DesktopVersion.properties` | `VERSION_NAME`, `VERSION_CODE` |

`NuvioZDesktop` also carries `iosApp/Configuration/Version.xcconfig` as the
*base/mobile* version; the desktop release does **not** read it. Use
`./scripts/set-version.sh --desktop <version> --desktop-code <code>` there rather
than editing by hand (`--show` prints both).

Steps:

1. Land all the work, including the `STATUS.md` update, on the branch.
2. Merge into `main` (nuvio-z) / `Dev` (NuvioZDesktop).
3. **Bump the version as the final commit.** Nothing else may change after it.
4. Push, then dispatch the release workflow **against `main` / `Dev`**.

The bump-last rule is enforced. `Validate release state` runs

```
git diff --name-only "${CURRENT_BUMP}..${RELEASE_COMMIT}"
```

and fails on anything except the release workflow itself and the two release
scripts. A `STATUS.md` commit after the bump **will fail the release** - always
commit docs before the bump. It also fails if the tag or a GitHub release for
that version already exists, so a version is single-use: a failed publish that
already created the release needs a new version number.

Release notes come from `scripts/generate-release-notes.sh` walking
`previous_bump..current_bump`, so **two distinct version bumps must exist in the
version file's history** or `release-metadata.sh` fails.

Verify before dispatching `publish`:

```
# nuvio-z: host suite + debug APK - runs automatically on push
# NuvioZDesktop: the only desktopMain compile
desktop-release.yml -> mode=build-only, target=windows
```

### Secrets and credentials

Configured as GitHub Actions secrets; never present in the repository:

- `NUVIO_LOCAL_PROPERTIES_BASE64` (nuvio-z) - base64 `local.properties` with the
  Supabase backend configuration. Without it CI still builds, but the app ships
  with no backend and sign-in and Trakt will not work.
- `NUVIO_RELEASE_KEYSTORE_BASE64` (nuvio-z) - the release signing keystore.
  Required for any mode except `dry-run`.
- `NUVIO_DESKTOP_LOCAL_PROPERTIES_BASE64` (NuvioZDesktop) - same idea.

macOS desktop builds need Apple signing and notarisation secrets **this
repository does not hold**, which is why every macOS job failed at "Configure
desktop runtime" and `target` now offers only `windows`. The macOS job is still
in the workflow behind a guard that can no longer match; restoring it means
adding the secrets and putting the options back.

Signing matters for updates: CI releases from `0.3.3` on all carry signer
certificate SHA-256
`2325A3399F9BBF5ECE1391EBE6B5A0E0F016058520FB1597B1CF30CF6184787C`. A **locally
built APK signed with a different keystore cannot be updated over** by a CI
release - Android reports only "App not installed" - so a local build has to be
uninstalled first. CI-to-CI updates work; `0.3.5` to `0.3.6` was verified
on-device.

`Zokaper/nuvio-z` must stay **public**, because the in-app updater reads its
releases unauthenticated.

Never commit or print: `local.properties`, `.signing/`, any `*.jks` or
`*.keystore`, GitHub tokens, Trakt secrets, personal addon or AIOStreams manifest
URLs, debrid credentials, or built APK/AAB artifacts. Inspect staged files and
run a targeted secret scan before every commit.

## Verifying without Gradle

Gradle cannot configure in the sandbox, but three things still can be done, and
every one of them has caught a real fault:

1. **Parser check.** Run the Kotlin compiler over each changed file on its own
   and ignore everything caused by the missing classpath. Only
   `expecting` / `unexpected` / syntax errors are real:

   ```bash
   kotlinc -nowarn -d /tmp/out <file>.kt 2>&1 \
     | grep -Ei "error:.*(expecting|unexpected|syntax)"
   ```

2. **Standalone compile-and-run of pure-logic files.** `DownloadBatches.kt`,
   `DownloadQueuePlanner.kt`, `DownloadTransfer.kt` and `DownloadPresence.kt`
   have few enough dependencies to compile outside Gradle together with their
   tests, which means the **shipped** sources really execute.

   The compiler and jars are reachable from the sandbox even though
   `dl.google.com` is not:

   ```bash
   curl -sSL -o kotlin.zip \
     https://github.com/JetBrains/kotlin/releases/download/v2.3.0/kotlin-compiler-2.3.0.zip
   # jars from repo1.maven.org: kotlinx-serialization-core-jvm, kotlinx-coroutines-core-jvm,
   # junit 4.13.2, hamcrest-core 1.3. kotlin-test*.jar ship inside kotlinc/lib.
   kotlinc -cp "<jars>" -Xplugin=kotlinc/lib/kotlinx-serialization-compiler-plugin.jar \
     -d out <shipped sources> <test sources> <stubs>
   java -cp "out:<jars>" org.junit.runner.JUnitCore <test class>
   ```

   Neighbouring types that drag in Compose resources or the stream stack
   (`DownloadPreset`, `DownloadSourcePolicy`, `SourceSelectionResult`, the
   generated `Res`, `getString`) can be stubbed in the same package. **Stub the
   neighbours, never the file under test**, and say in `STATUS.md` which were
   stubbed - a test against a copy of the code proves nothing.

3. **The desktop download harness.** `NuvioZDesktop`'s
   `composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt` runs the real
   download queue and the real desktop downloader against a local server that
   misbehaves the way debrid hosts do - drops the body, goes quiet without closing,
   expires a link, serves a placeholder. **Use it before arguing about a download
   fault.** `./gradlew :composeApp:desktopTest` runs it, and CI runs it on every push.

   It also runs outside Gradle, which is how it was written here. Describe the source
   set to the compiler as fragments so the `expect`/`actual` pairs resolve:

   ```bash
   kotlinc -Xmulti-platform -Xexpect-actual-classes \
     -Xfragments=common -Xfragments=desktop -Xfragment-refines=desktop:common \
     -Xfragment-sources=common:<file> ... -Xfragment-sources=desktop:<file> ... \
     -Xplugin=kotlinc/lib/kotlinx-serialization-compiler-plugin.jar -cp "<jars>" -d out <sources>
   java -cp "out:<jars>" org.junit.runner.JUnitCore \
     com.nuvio.app.features.downloads.DesktopDownloadQueueE2ETest
   ```

   `DownloadsTiming` turns the minute-long stall and watchdog deadlines down to
   seconds; leave the shipped defaults alone outside a harness. Set
   `NUVIO_DOWNLOAD_TEST_URLS` to real media URLs to run the same queue against a real
   host at the real deadlines.

None of them substitutes for CI. Compose, multiplatform `expect`/`actual` matching,
and anything touching resources are only checked by a real build.

## Status Handoff

### Keeping `main` current

**`main` must never be stale.** An agent that reads only the default branch has to
be able to find the newest work from there. Feature work lives on a branch, so the
default branch has to point at it.

Rules:

- The table at the top of `STATUS.md` names the **active branch**, what is
  released, and whether the unreleased work has been verified. Update it first,
  in every session, before touching code.
- Whenever that table changes, **also put the updated `STATUS.md` and `AGENTS.md`
  on `main`**, even when the code stays on the branch:

  ```bash
  git checkout main
  git checkout <working-branch> -- STATUS.md AGENTS.md
  git commit -m "docs: point main at <working-branch>"
  git push -u origin main
  ```

  This is docs-only and safe. It does not disturb a release, because a release
  requires its own version bump commit *after* it (see "Release procedure").
- Do this **before ending a session**, not only when work is finished. A branch
  that `main` does not mention is a branch the next agent will not find.
- `NuvioZDesktop` carries a stable pointer `AGENTS.md` on `Dev` for the same
  reason. It points to `nuvio-z/main`, whose status table names the active
  branches, so do not duplicate a branch name in the desktop pointer.

### Writing `STATUS.md`

Keep `STATUS.md` concise and factual:

- record completed work and exact verification;
- distinguish comprehensive tests from focused follow-up tests;
- list current blockers and safe next actions;
- never include credentials, private addon URLs, or personal account data.
