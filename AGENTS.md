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

Release builds run R8 and can use substantial CPU. Use a bounded worker count
unless the user explicitly prefers maximum throughput.

For device testing, discover the current serial with `adb devices -l`; do not
hardcode a personal device serial. Install with `adb install -r` to preserve app
data. Use UI automation and filtered `AndroidRuntime` logs for smoke testing,
and never queue real bulk downloads merely to test a review screen.

## Status Handoff

Keep `STATUS.md` concise and factual:

- record completed work and exact verification;
- distinguish comprehensive tests from focused follow-up tests;
- list current blockers and safe next actions;
- never include credentials, private addon URLs, or personal account data.
