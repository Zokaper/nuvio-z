# Nuvio Z Status

Last updated: 2026-08-03

## Current Snapshot

- Base: NuvioMobile commit `979d5680`.
- Working branch: `main` (the downloads redesign and the download
  transfer/queue rework are both merged into it).
- Official repository is configured as `upstream`.
- Public `origin` repository: `https://github.com/Zokaper/nuvio-z`
  (public so the unauthenticated in-app updater can read its releases).
- Android identity: Nuvio Z, `com.nuvio.app.z`
  (`com.nuvio.app.z.debug` for debug).
- Signed personal release builds use an ignored local keystore.
- Latest signed arm64 APK was installed successfully on a Samsung Android
  device and launches alongside official Nuvio.

## Completed

- Added Saver, Balanced, and Quality download presets with editable resolution,
  GB/hour cap, codec, HDR/Dolby Vision, and audio-language preferences.
- Added movie, episode, season, and selected-season batch planning.
- Added an unwatched-only season scope so a season in progress downloads from the
  current episode onwards instead of the whole season.
- Added generic Stremio source normalization and bounded AIOStreams structured
  metadata support.
- Added global addon allowlisting and nested AIO provider restrictions.
- Added automatic source ranking, direct/debrid resolution, size verification,
  unknown-size review, and manual-source fallback.
- Added persistent batch/download models, resumable Android transfers,
  concurrency limits, network constraints, notifications, and queue actions.
- Added Nuvio Z application identity and launcher assets while retaining the
  upstream Kotlin namespace and callback schemes.
- Configured the official Nuvio backend through ignored local build properties;
  fixed the earlier `https://localhost` authentication fallback.
- Fixed preset editing crashes by enabling structured JSON map keys in download
  persistence.
- Fixed false “Conflicting source metadata” results by separating authoritative
  byte reports from rounded filename/display estimates and tolerating equivalent
  hard reports while retaining the largest cap-enforcement size.
- Promoted downloads from a settings page to a first-class part of the app:
  a dedicated Downloads tab, artwork-driven queue and on-device lists, and live
  download state on movie and series entries.
- Reworked download transfers so a finished byte loop is only treated as a
  completed download when the bytes on disk match the authoritative total, and a
  total is never inferred from a transfer that stopped early.
- Added `If-Range` validators, correct 416 handling, and honest short/overrun
  outcomes to the Android, desktop, and iOS downloaders.
- Made pause a first-class outcome rather than a swallowed cancellation, split
  user pauses from system pauses, and added automatic resume on app foreground,
  reload, and connectivity recovery (including the missing iOS foreground hook).
- Added an explicit `Queued` state with persisted queue ranks, append-on-enqueue
  ordering, menu-based reordering with preemption, and retry with backoff.
- Coalesced progress persistence and notification updates instead of rewriting the
  whole payload on every chunk, and serialised repository mutations behind a lock.

## Verification

- Earlier comprehensive Android host suite: 477 tests passed.
- Latest focused source/preset suite:
  - `SourceFactsExtractorTest`: 8 passed.
  - `PresetDownloadsTest`: 10 passed (12 after the unwatched-scope tests were
    added; not yet executed, see below).
- The downloads integration redesign **compiles**: `assembleFullRelease`
  succeeded in CI on the third attempt and published `0.3.4`. The first two
  attempts failed on `MetaScreenSectionKey.DOWNLOADS`, first a non-exhaustive
  `when` in `MetaScreenSettingsPage.kt`, then the two Compose resource
  accessors that file needs as explicit imports.
- `DownloadPresenceTest` (11 tests) has **not been executed**: the release
  workflow only assembles, and no Gradle task can configure in the sandbox.
  Nothing in the redesign has been exercised on a device yet.
- Download transfer/queue rework (2026-08-03): Gradle still cannot configure here,
  so `DownloadTransfer.kt` and `DownloadQueuePlanner.kt` were compiled standalone
  against Kotlin 2.3.0 together with the two new test files, and all 27 tests
  (71 assertions) passed. This exercised the shipped sources, not copies, but it
  covers only those two files; no Android/iOS/desktop code was compiled. Every
  changed Kotlin file additionally passed a parser-only check.
- Signed `assembleFullRelease` completed successfully after the latest metadata
  fix.
- On-device preset smoke test:
  - edited and restored Saver and Quality controls;
  - survived process death and persistence reload;
  - no `AndroidRuntime` crash.
- On-device Daredevil Season 3 Balanced discovery:
  - all 13 episodes reached review with normal 1080p selections;
  - displayed sizes were approximately 0.3–0.8 GB;
  - no conflicting-metadata approvals;
  - review was dismissed without queuing downloads.

## Pending / Follow-up

- No Gradle task can configure in this sandbox: `dl.google.com` is denied by
  the egress policy, so the Android Gradle Plugin never resolves. CI is the only
  compiler available here, which makes each fix a full release-run round trip.
  Run `.\gradlew.bat :composeApp:testAndroidHostTest` locally to get the host
  suite, including the new `DownloadPresenceTest`, actually executed.
- The download transfer/queue rework **compiles** - CI built and published
  `0.3.6` from it - but its behaviour is still unverified. Only the two new
  pure-logic files have executing tests (see Verification); the repository, the
  three platform downloaders and the screen have never been run. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` locally to execute the host
  suite, which CI's assemble-only release job never runs.
- Smoke-test the reworked transfers on-device with a deliberately small file:
  pause/resume mid-transfer, resume after the source URL has expired (must not
  report a completed download at the partial size), process death mid-transfer,
  background/foreground on iOS, and a season batch to confirm E01 starts first and
  that "Download next" preempts.
- The unwatched-season download work has **not** been compiled or tested in this
  environment either: the sandbox blocks `dl.google.com`, so the Android Gradle
  Plugin cannot be resolved and no Gradle task can configure. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` and an `assembleFullDebug`
  locally before trusting it.
- Smoke-test the unwatched season download on-device: open a partly watched
  season, use the season download menu, and confirm only the current episode
  onwards is queued.
- Smoke-test the downloads redesign on-device: confirm the Downloads tab appears
  in the classic, adaptive and tablet nav bars; queue one small episode and check
  that the episode card ring, the tab's “Downloading now” row, and pause/resume
  stay in sync; confirm the “Downloaded” section appears on the entry once the
  transfer completes and disappears after deleting.
- The iOS Downloads tab currently falls back to the `arrow.down.circle.fill` SF
  Symbol. Add a `NuvioTabDownloads` xcasset to match the other tab icons.
- Existing profiles get the new meta-screen “Downloaded” section appended last in
  their saved section order, because `normalizePreferences` sorts unknown keys to
  the end. New profiles get it right after Actions.
- The local workspace directory is still named `stremio-z`; renaming it is
  deferred.
- Run the full host suite again after the next substantial code change.
- Test a real transfer end-to-end, including pause/resume, process death,
  network constraints, and cap-crossing approval, using a deliberately small
  file.
- Review lifecycle/cleanup for prepared batches dismissed from the review
  dialog so cancelled all-ready batches do not remain as hidden persisted
  records.
- Trakt functionality requires local client credentials and has not been
  reconfigured for this personal build.
- iOS parity gaps in the preset download feature, all in platform seams:
  `freeStorageBytes()` returns `-1` so low-space warnings and
  storage-triggered review never fire; `allowMeteredNetwork` is ignored
  because the iOS session hardcodes cellular access; downloads pause on
  app background because iOS uses a foreground `NSURLSession`.
- `DownloadsStorage.ios.kt` no longer profile-scopes its payload key,
  unlike every other iOS storage and unlike the desktop fork. Decide
  whether that de-scoping was intended.
- Desktop CI cannot be verified from a sandbox that blocks `dl.google.com`;
  the Android Gradle Plugin will not resolve there.
- `0.3.6` (versionCode 105) is released from `main` and is the first build to
  carry the download transfer/queue rework. `assembleFullRelease` succeeded, so
  the merged redesign and rework compile together; nothing in the rework has
  been exercised on a device yet.
- Queue reordering has a known rough edge: the needs-attention section is
  filtered out of the queue list, so a Move up/down that would swap with an
  attention item looks like it did nothing. "Download next" is unaffected.
- `Zokaper/nuvio-z` is public, which the unauthenticated updater requires.
  `0.3.5` (versionCode 104) is the current release; `0.3.4` and `0.3.3`
  precede it. All carry signed APKs for all four ABIs.
- CI release signing is stable: `0.3.3`, `0.3.4` and `0.3.5` all carry signer
  certificate SHA-256
  `2325A3399F9BBF5ECE1391EBE6B5A0E0F016058520FB1597B1CF30CF6184787C`.
  A locally built APK signed with a different keystore cannot be updated over
  by these releases, and Android reports only "App not installed". The installed
  build's version identifies which key it carries, because `0.3.3` and later
  exist only as CI output.
- The earlier "App not installed" in-app update failure is **resolved**: the
  in-app update from `0.3.5` to `0.3.6` succeeded on the Samsung device. It was
  the signing-key mismatch rather than Auto Blocker - once the installed build
  came from CI, later CI-signed releases update over it cleanly. A locally built
  APK still cannot be updated over by a CI release, so a local build has to be
  uninstalled first.
- `NuvioZDesktop` desktop releases are now Windows-only. Every macOS job failed
  at "Configure desktop runtime" because the repository holds none of the Apple
  signing and notarisation secrets it requires, so the target choice was
  narrowed to `windows`; the macOS job is still in the workflow behind a guard
  that can no longer match. Restoring macOS means adding the secrets and
  putting the options back.
- Compiling the desktop mirror for the first time found that the redesign added
  a `downloads` parameter to the `publishNativeTabTitles` expect and updated the
  Android and iOS actuals but not the desktop one. Fixed in `NuvioZDesktop`.
  A Windows build of the pre-redesign commit compiles, which is what identified
  the redesign mirror rather than the transfer rework as the source.
- The desktop Windows job now runs `compileKotlinDesktop` as its own step
  without `--stacktrace`, because packaging with it buried the compiler's `e:`
  lines under roughly 250 lines of Gradle internals.
- `NuvioZDesktop` has now been through a compiler for the first time, but has
  still **not** been released; no desktop artifact has been produced or run.

## Work Log

### 2026-08-03 (later: transfer/queue rework and the 0.3.6 release)

- Reworked download transfers so a finished byte loop only counts as a completed
  download when the bytes on disk match the authoritative total. The read loop
  had treated any end of stream as success and then adopted the truncated file's
  own length as the total, so a cut-short transfer rendered as finished at
  whatever byte count it had reached.
- Added `If-Range` on resume, correct 416 handling that finalizes an already
  complete `.part` instead of refetching, cooperative pause reporting, retry
  with backoff, an explicit `Queued` state with persisted ranks, menu-based
  reordering with preemption, and coalesced progress persistence.
- Reconciled this work with the downloads redesign. The two were siblings off
  the same base rather than one built on the other, so both had rewritten
  `DownloadsRepository`, `DownloadsModels` and `DownloadsScreen`. The redesign's
  `deleteDownloadsForTitle`/`ForSeason` auto-merged but still called the
  `publish`/`persist` helpers the rework had replaced, and touched
  `activeHandles` unsynchronised; both were rewritten onto the locked path.
- Released `0.3.6` from `main`. `assembleFullRelease` succeeded on the first
  attempt.
- Narrowed `NuvioZDesktop` desktop releases to Windows after every macOS job
  failed on missing Apple credentials, and fixed the desktop
  `publishNativeTabTitles` actual the redesign had left behind.
- Verified the two new pure-logic files by compiling them standalone against
  Kotlin 2.3.0 with their tests: 27 tests, 71 assertions, all passing. Gradle
  still cannot configure in the sandbox, so everything else was checked only by
  a parser pass locally and then by CI.

### 2026-08-03

- Added `DownloadPresence.kt`: a shared, Compose-free download-state layer
  (`DownloadPresence`, `ContentDownloadState`, `TitleDownloadState`,
  `buildTitleDownloadState`) that merges persisted downloads with in-flight batch
  entries so a title reads as “preparing” the moment a batch is created.
- Promoted the private `buildLogicalKey` to a shared `downloadLogicalKey` and
  pointed `DownloadsRepository` and `DownloadBatchPlanner` at it, so batch
  planning and download storage can no longer drift apart.
- Added `DownloadsRepository.deleteDownloadsForTitle` / `deleteDownloadsForSeason`
  and `DownloadsUiState.bytesOnDisk`.
- Made Downloads a top-level tab (`AppScreenTab.Downloads`,
  `NativeNavigationTab.Downloads`) across the classic, adaptive and tablet nav
  bars, the desktop sidebar, and the iOS native tab bar; widened the tab-title
  bridge with a `downloads` slot through to `ContentView.swift`.
- Split the old settings-only downloads page in two: `DownloadsSettingsScreen`
  keeps presets and allowed sources under Settings, while `DownloadsScreen`
  became the tab root with artwork, a needs-attention section, live transfers,
  and an on-device list grouped per title with per-title and per-season deletes.
- Added `DownloadStateButton` and `DownloadManageSheet`, and wired them into both
  episode card styles so a card shows idle / preparing / progress / paused /
  failed / downloaded and manages the download in place.
- Added a configurable `MetaScreenSectionKey.DOWNLOADS` section listing what is
  on the device for a title, and made the hero download action reflect a movie's
  own download state.
- Added `DownloadPresenceTest` covering key derivation, both presence mappings,
  item-over-batch precedence, season roll-ups, and prefix collisions.
- Mirrored the whole change into `Zokaper/NuvioZDesktop`, where the Downloads tab
  and sidebar entry are gated behind `AppFeaturePolicy.downloadsEnabled`.
- Root-caused the "resume says it completed" report: the read loop treated any end
  of stream as success, and `onSuccess(uri, totalBytes ?: finalSize)` adopted the
  truncated file's own length as the total, so a cut-short transfer rendered as a
  finished download at whatever byte count it had reached.
- Added `DownloadTransfer.kt` with the completion rule, retry policy, HTTP failure
  classification, progress-throttle thresholds, and the `resolveTotalBytes` /
  `parseContentRangeTotal` helpers that had been duplicated verbatim in all three
  platform downloaders.
- Replaced the platform downloader's loose callbacks with `DownloadTransferListener`
  so a pause can be reported as a pause; Android previously caught
  `CancellationException` in a generic `catch (Throwable)` and reported it as a
  failure, which only avoided showing up as a failed download because of a status
  check that lost the race.
- Fixed the 416 branch: an already complete `.part` is now finalized instead of
  being deleted and downloaded again from zero.
- Added `DownloadStatus.Queued`, persisted queue ranks, and `DownloadQueuePlanner`.
  Enqueue appends rather than prepends, so a season batch no longer downloads in
  reverse episode order.
- Added queue reordering (`Download next` / up / down / bottom) as a menu so it
  works with a TV remote, with preemption of the lowest priority running transfer.
- Split `DownloadPauseReason.User` from `System` and added
  `resumeSystemPausedDownloads`, wired to reload, connectivity recovery, and a new
  `applicationWillEnterForeground` hook on iOS — backgrounding the app used to
  pause every download permanently.
- Serialised repository mutations behind an atomicfu lock and coalesced persistence,
  replacing a full JSON payload rewrite on every 16 KiB chunk.
- Set aside an unparseable payload under a separate key instead of silently
  discarding every download, batch, and preset.
- Mirrored the same change into `Zokaper/NuvioZDesktop`, including the desktop
  downloader.

### 2026-08-02

- Added `DownloadScope.SeasonUnwatched`, an unwatched filter in
  `DownloadBatchPlanner`, and watch-state resolution in
  `PresetDownloadCoordinator`, so a season batch can skip everything already
  watched while keeping the episode currently in progress.
- Added `WatchingState.isEpisodeSeen` as the single watched-or-completed rule and
  pointed the details screen helper at it.
- Added the season download menu (whole season vs unwatched episodes) to the
  season header and a matching row in the season action sheet; both hide the
  unwatched option when a season has nothing left to watch.
- Stopped persisting a batch when a scope resolves to no episodes and reported
  the empty result in the preset dialog instead of queuing zero downloads.
- Added `PresetDownloadsTest` coverage for the unwatched scope, including the
  already-downloaded exclusion.
- Mirrored the same change into `Zokaper/NuvioZDesktop`.
- Reviewed iOS viability and distribution options for sharing personal
  builds; recorded the platform gaps above.
- Ported the preset and bulk download feature to the desktop fork
  (`Zokaper/NuvioZDesktop`, branch `claude/preset-bulk-downloads`) as a
  cherry-pick, since that repository shares history with this one.
  Resolved four conflicts, threaded the download callbacks through the
  desktop fork's two details layouts, and implemented `freeStorageBytes()`
  for desktop from the downloads directory's usable space.
- Excluded the Nuvio Z Android branding, the repository handoff documents,
  and the iOS profile-scoping change from that port.
- Added a `CI` workflow to both repositories: Android host tests and an
  unsigned debug APK artifact on every push, plus desktop compilation and
  tests in the desktop fork. Neither requires signing secrets.
- Not verified by a compiler in this environment; CI is the first real
  build of the ported feature.
- Repointed the in-app updater from `NuvioMedia/NuvioMobile` to
  `Zokaper/nuvio-z`, and the desktop fork's updater to
  `Zokaper/NuvioZDesktop`. Cleared the release channel filter, which
  matched a branch name against a release `targetCommitish` that is
  actually a commit SHA and so rejected every release.
- Scanned the full history of both repositories for committed secrets
  before recommending public visibility; none were found.

### 2026-07-30

- Forked the inspected NuvioMobile base and implemented Nuvio Z preset/bulk
  downloads.
- Built and installed the signed arm64 release through ADB.
- Diagnosed sign-in failure from device logs and restored production backend
  configuration locally.
- Reproduced and fixed preset persistence crash; added regression coverage.
- Reproduced season-wide AIO metadata false conflicts; fixed and verified all 13
  Daredevil Season 3 selections on-device.
- Added shared `AGENTS.md`, Claude handoff instructions, and this status log.
- Created the private `Zokaper/nuvio-z` repository, preserved official Nuvio as
  `upstream`, configured the private fork as `origin`, and pushed `main`.
