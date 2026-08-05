# Nuvio Z Status

Last updated: 2026-08-05

| | |
| --- | --- |
| **Active branch** | `claude/preset-page-download-toast-k63ufs` in **both** repositories - preset UI rework, a tappable Downloads link on the start-download toast, and a third size preference |
| **Released** | `nuvio-z` `0.3.9` · `NuvioZDesktop` `0.1.22-alpha` |
| **Unreleased work** | The preset work on `claude/preset-page-download-toast-k63ufs` (see "Preset UI and the mid-range size preference"). CI is **green on both repositories**, including the Windows MSI job that compiles `desktopMain`; **no device or desktop runtime testing yet**. `0.3.9` / `0.1.22-alpha` remain the published releases, carrying the download freezing fixes and the 4K preset split; their runtime testing on a device and a real desktop install is still pending, and the debrid re-resolution path has no runtime coverage at all. |

This table is the first thing to update in any session, and it is kept current on
`main` as well as on the working branch - see "Keeping `main` current" in
`AGENTS.md`. If it names a branch, the newest work is on that branch, not here.

**Read `AGENTS.md` first.** It carries the two-repository mirroring rules, the
full release procedure, which secrets exist and where, and how to verify code in a
sandbox where Gradle cannot configure.

## Current Snapshot

- Base: NuvioMobile commit `979d5680`.
- Working branches: released `main` (`nuvio-z`) and `Dev` (`NuvioZDesktop`).
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
- Made background source discovery visible: a Preparing section in the Downloads
  tab with per-episode state, and an ongoing Android notification while any batch
  is preparing.

## Download freezing (2026-08-05, unreleased)

Four separate faults, found while chasing downloads that stopped around 80% and
one that refused a source for exceeding the preset cap after it had already been
approved. Reported on the Windows desktop build through TorBox.

- **Desktop transfers could block forever.** `HttpRequest.timeout` bounds the
  arrival of the *response*, and with `BodyHandlers.ofInputStream` the response
  arrives with the headers - every byte after that was read from a stream with no
  deadline. A connection that went quiet without closing parked the read
  permanently, `job.cancel()` could not interrupt it, so pause and cancel did
  nothing and the item held one of the two transfer slots for good. Two of them
  stopped the queue outright. Added a stall watchdog that closes the stream, and
  a handle that closes it on cancel.
- **Nothing recovered a transfer the queue lost.** An item recorded as
  `Downloading` with no handle behind it was invisible to the planner (which
  starts queued items) and to the system-pause recovery (which looks at paused
  ones). `DownloadQueuePlanner.lostTransfers` now names that state and
  `startPendingTransfers` takes those items back, together with any transfer that
  has gone silent for far longer than the platform watchdog allows. A platform
  that refuses to start no longer strands the item either - Android's
  `JobScheduler` declines a user-initiated job outright when the app may not
  start one, and that used to throw straight out of `start()`.
- **The size cap stopped transfers it should not have.** It fired mid-transfer on
  the larger of the bytes received and the size reported, cancelled the handle and
  paused for approval - including for sources the user had already approved in the
  batch review dialog, because `queueBatch` never carried that decision onto the
  download. On resume it fired again at the resumed offset, so an item could wedge
  at the same percentage repeatedly, and `resumeDownload` refused those items so
  the resume button did nothing. The cap now decides which source to pick and
  nothing more; an oversize file is noted on the row and finishes. Items already
  stuck this way are healed on load.
- **Debrid links were minted once and never refreshed.** Preparation resolved
  every episode of a batch up front while only two transfer at a time, so links
  were routinely first used hours after they were minted - against a resolver that
  already treats a cached link as good for fifteen minutes. An expired TorBox
  `requestdl` URL answers 401/403/404/410, which classified as `Fatal`, so the
  download failed with a retry button that replayed the same dead URL forever.
  Those statuses are now `SourceExpired`, downloads carry a `DownloadSourceOrigin`
  (the stream before resolution), and a stale or rejected link is re-minted before
  the transfer starts and on retry.

Also: `StreamItem` and its nested models are now `@Serializable` so the origin can
be persisted faithfully, and an episode with no runtime of its own falls back to
the series runtime before the 45-minute default that was under-capping hour-long
episodes.

### The 4K preset split (same release)

The `Quality` preset asked for 2160p while capping at **4 GB/hour** - a 4 GB
ceiling for an hour-long episode, under every real 4K source. `PresetSourceSelector`
rejected all of them and reported that they exceeded the cap, which is the same
complaint the freezing work started from arriving by a different route. One cap
cannot serve both a 2160p web encode and a remux, so it is now two tiers:

| id | name | cap | ~54-minute episode |
| --- | --- | --- | --- |
| `quality_4k_low` | 4K Low | 8 GB/h | ~7.2 GB |
| `quality_4k_high` | 4K High | 15 GB/h | ~13.5 GB |

Presets are **persisted**, so a new built-in would have reached only fresh
installs - an existing device would have updated and seen no 4K tiers at all.
`mergeStoredPresets` appends built-ins the stored list has never seen and drops a
retired one that still matches its old default exactly; an edited copy is a
decision the user made and survives. Anything added to `BuiltIns` in future needs
nothing further, but anything *removed* needs an entry in `RetiredBuiltIns` or it
will linger on existing installs forever.

## Preset UI and the mid-range size preference (2026-08-05, unreleased)

Both preset surfaces were plain Material defaults that ignored the app's own
components, and the toast raised when a batch starts pointed nowhere.

- **A third `SizePreference`, `MID_RANGE`.** The choice used to be only
  `LARGEST_UNDER_CAP` or `SMALLEST`. `MID_RANGE` targets the **median size of the
  candidates that actually fit the cap** - a real candidate size rather than a share
  of the cap, so it stays meaningful when every source for a title sits far below
  the limit, and sizes above the cap are excluded so an unusable 20 GB remux cannot
  drag the target upwards. The upper middle of an even-sized list keeps it
  deterministic; an unknown size is treated as `Long.MAX_VALUE` away and still sorts
  last; with nothing to aim at, ordering falls back to largest-under-cap. The target
  is computed once over every matching candidate while the comparator still only
  decides *within* a tie group, so resolution, language, dynamic range, codec and
  release quality continue to outrank size. Built-in presets keep their existing
  preference: `mergeStoredPresets` never rewrites a stored preset, so changing one
  would split behaviour between existing and fresh installs.
- **The preset picker** (`PresetDownloadDialog.kt`) is rebuilt on `BasicAlertDialog`
  and the Nuvio tokens: a subtitle naming what will be downloaded, season chips with
  All/None instead of a checkbox list (and localised season names - it used to
  hardcode English), and one selectable card per preset carrying a plain-language
  summary. A preset is now **selected and then started** by a button; tapping one
  used to queue a whole season on the spot. The default selection is the preset of
  the newest batch.
- **The preset editor** (`DownloadsSettingsScreen.kt`) drops the `−`/`+` steppers and
  the rows that silently cycled an enum on tap. Resolution, codec, HDR policy and
  file size are `NuvioDropdownChip` pickers, the cap is a slider showing what it
  works out to for an episode and a film, and the switches carry descriptions. Raw
  enum names (`AVOID_HDR`) are gone: `PresetLabels.kt` holds one set of labels used
  by both surfaces. `DownloadsRepository.resetPresets()`, which had no UI at all, is
  wired to a confirmed "Reset presets" action.
- **The toast can be tapped through to the Downloads tab.** `NuvioToastMessage`
  carries an optional label and a typed `NuvioToastAction`; `App.kt` resolves
  `OpenDownloads` by selecting the tab and, under Compose navigation, unwinding the
  stack back to `TabsRoute` so the tab is actually visible from the details screen a
  download is started from. A typed action rather than a lambda keeps navigation out
  of `core/ui`, which is what let the dialog raise it at all. The download toast now
  lasts 5s rather than 2.5s so the link can be read and reached.

New string keys live in `values/strings.xml` in both repositories; the other 24
locales fall back to English until translated.

## Verification

- Preset UI and mid-range size preference (2026-08-05). **Nothing has run on a
  device or a real desktop install.** What was done:
  - `ci.yml` is green on both repositories at `ea6d95a` / `461d56d4`: nuvio-z ran
    the Android host suite and built the debug APK, and the desktop run passed both
    "Desktop tests" and the Windows MSI job - the only thing that compiles
    `desktopMain`.
  - Every changed Kotlin file in both repositories passed the parser-only check.
  - `PresetDownloadsTest.kt` was run against the **shipped** `PresetDownloads.kt` and
    `SourceFacts.kt`: **18 of its 25 cases passed**, including the three new
    size-preference cases. The seven excluded ones reach `DownloadsRepository`'s
    codec, the HTTP discovery path, or the batch models, none of which compile
    outside Gradle; only `StreamItem` and its nested stream models were stubbed. CI
    runs the class in full.
  - Both `values/strings.xml` files parse as XML and every string key the new code
    references resolves in both repositories.
  - **Still to do:** a device/desktop smoke test of the new picker, the editor
    controls, and the toast link.
- Download freezing work (2026-08-05). Gradle still cannot configure here, so
  Kotlin 2.3.0 was fetched and used directly:
  - `DownloadTransferTest` and `DownloadQueueTest` compiled against the **shipped**
    `DownloadTransfer.kt`, `DownloadQueuePlanner.kt` and `DownloadsModels.kt` and
    executed: **34 tests passed**, including the three new lost-transfer cases and
    the expired-link retry budget.
  - The whole downloads package - `DownloadsRepository.kt`, `DownloadsModels.kt`,
    `DownloadBatches.kt`, `DownloadQueuePlanner.kt`, `DownloadTransfer.kt`,
    `PresetDownloads.kt`, `SourceFacts.kt`, `DownloadPresence.kt` - plus the real
    `StreamModels.kt` **type-checks clean** with the serialization plugin, against
    stubs only for the platform singletons, the debrid resolver and atomicfu.
  - `DownloadsPlatformDownloader.desktop.kt` type-checks standalone, which is
    worth noting because `desktop-release.yml` is otherwise the only thing that
    compiles `desktopMain`.
  - The desktop freeze was **reproduced and confirmed fixed** against a local
    server that sends headers and part of a body then goes silent without
    closing: the transfer now ends after ~75s with
    `Transient: The source stopped sending data. Retrying.` instead of hanging
    forever, and cancelling a stalled transfer takes **2ms** instead of never
    returning.
  - Every changed Kotlin file in both repositories additionally passed a
    parser-only check.
  - After the preset split the three download suites were re-run the same way:
    **56 tests passed**. One pre-existing case,
    `disallowedAddonsAreRemovedBeforeDiscoveryRequests`, is excluded from the
    local harness because it reaches into the addon/network stack there is no
    stand-in for; CI runs it.
  - `ci.yml` passed on both repositories, and `desktop-release.yml`
    `mode=build-only`, `target=windows` compiled `desktopMain` - the only job
    that does, and where the stall watchdog lives.
  - **Not yet exercised on a device or a real desktop install.** The debrid
    re-resolution path in particular has no runtime coverage at all: it needs a
    real TorBox account and a batch left running past the fifteen-minute link
    window, which a 4K season batch will produce naturally.
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
- Preparation visibility work (2026-08-04): Gradle still cannot configure here,
  so `DownloadBatches.kt` was compiled standalone against Kotlin 2.3.0 together
  with the new `DownloadBatchPreparationTest`, and all 4 tests passed. That
  exercised the shipped source, but the neighbouring types it needs
  (`DownloadPreset`, `DownloadSourcePolicy`, `SourceSelectionResult`) were local
  stubs, because the real ones reach into the Compose resource and stream stacks.
  Every changed Kotlin file additionally passed a parser-only check. CI was the
  first real compiler, and it was green on the first attempt in both
  repositories:
  - nuvio-z `CI` on `55e8ccb`: Android host tests **passed** and
    `assembleFullDebug` succeeded. This is the first time the host suite has
    actually executed on the redesign and the transfer/queue rework, so
    `DownloadPresenceTest`, `DownloadQueueTest` and `DownloadTransferTest` have
    now all run for real, not just the two files compiled by hand.
  - NuvioZDesktop `desktop-release.yml` `build-only`/`windows` on `d74779f2`:
    `compileKotlinDesktop` succeeded and the MSI built and verified, so the
    desktop actual is in place and `desktopMain` compiles.
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

### Preset/discovery work: code complete, release not cut

All five planned pieces have landed. `4ba89f7`/`59fa2ecb` carried the first
three; `55e8ccb` (nuvio-z) and `d74779f2` (NuvioZDesktop), both on
`claude/status-md-continuation-tkc41p`, carry the last two. What is done:

- Per-preset `sizePreference`: `Balanced`/`Quality` take the largest source that
  still fits the cap, `Saver` keeps taking the smallest. This reversed the old
  behaviour, which sorted size ascending and so picked the *smallest* under the
  cap.
- Per-preset `preferCachedSources` (default on). `SourceFacts.isDebridReady` is
  now its own tie-break below every quality key, so cached never costs a
  resolution tier, and an uncached debrid winner is sent to review instead of
  started.
- `PresetDownloadDialog` no longer awaits preparation or blocks dismissal.
- A Preparing section in `DownloadsScreen.kt`, above review, driven by batches
  with any entry still `DISCOVERING`/`RESOLVING`: artwork, title, a
  "Finding sources · 4 of 13" count, a progress bar and per-episode state. A
  batch is held *out* of the review section while it is still preparing, so the
  user is not asked to review a list that is still growing.
- `DownloadsLiveStatusPlatform.onBatchesChanged(batches)` with all four actuals
  (android and ios in both repositories, desktop in `NuvioZDesktop`), and an
  ongoing low-priority Android notification while any batch is preparing. It is
  called from every batch mutation as well as from `publishLocked`: preparation
  moves through `saveBatch`/`updateBatchEntry`, which never touch the item list,
  so hanging it off item changes alone would show nothing for the whole
  discovery pass.
- The unreachable in-dialog review branch is gone from `PresetDownloadDialog`,
  along with the `batch`/`error`/`approveUnknown` state and the `onQueued` and
  `onChooseManually` parameters behind it.

Remaining:

1. **Smoke-test preparation on-device.** Start a season batch and confirm the
   Preparing section fills in episode by episode, that the ongoing notification
   appears and clears, and that the batch moves to review or straight to the
   queue when discovery finishes.
2. **Check the desktop in-app update path.** `0.1.20-alpha` is installed on a
   Windows machine and launches with a responsive main window and no matching
   Application event-log crash. The actual `0.1.19-alpha` to `0.1.20-alpha`
   in-app update path has not been exercised.

### Latest release: CI verified, runtime testing pending

Two changes shipped in `0.3.8` / `0.1.21-alpha`. The merged release branches
passed Android host tests/debug assembly in run `30944119268` and desktop tests/
Windows MSI assembly in run `30944124462`. Publish runs `30944744977` and
`30944920882` then built and published the signed APKs and verified MSI. They
have not been runtime-smoke-tested. On 2026-08-04 the release was explicitly
approved without an Android device; device verification remains a post-release
follow-up.

The former `claude/status-md-continuation-tkc41p` branches are merged. The code
below is released from `main` / `Dev`.

#### (a) The two missing preset controls

`4ba89f7`/`59fa2ecb` added `preferCachedSources` and `sizePreference` to
`DownloadPreset` and wired them into `PresetSourceSelector`, but **never added
editor UI**, so they were stuck at their built-in defaults and the user could not
reach them. Added to `PresetSettingsCard` in `DownloadsSettingsScreen.kt`:

- a row that toggles `sizePreference` between `LARGEST_UNDER_CAP` and `SMALLEST`;
- a `Prefer cached sources` switch for `preferCachedSources`.

Four new strings in both `strings.xml` files:
`download_preset_size_preference`, `download_preset_size_largest`,
`download_preset_size_smallest`, `download_preset_prefer_cached`.

Both fields are already `@Serializable` on `DownloadPreset` and go through
`DownloadsRepository.updatePreset`, so persistence needed no change.

#### (b) Series page and Downloads page disagreeing (reported bug)

**Symptom.** Delete everything from the Downloads tab, then open the series page:
episodes still show download states - some "downloading", some "downloaded".

**Cause.** `buildTitleDownloadState` (`DownloadPresence.kt`) layers batch entries
underneath persisted items, items winning. The old `publishLocked` only synced an
entry when a matching item still existed (`?: return@map entry`), so deleting a
download left its batch entry frozen at `DOWNLOADING`/`COMPLETED`/`QUEUED`
forever. With the item gone the detail screen fell through to that stale entry.
The Downloads tab looked correct because it renders items, not entries.

**Fix as written.** A new pure `reconcileBatches(batches, items)` in
`DownloadBatches.kt`, called from both `publishLocked` and `loadFromDiskLocked`:

- an entry with a matching item follows that item's status, as before;
- an entry in an *item-backed* state whose item is gone becomes `CANCELLED`,
  which `toPresence()` already maps to `DownloadPresence.None`;
- a batch whose entries are now all `CANCELLED` is dropped entirely;
- `isItemBacked` covers `QUEUED`, `DOWNLOADING`, `PAUSED`, `COMPLETED` and
  **deliberately excludes `FAILED`**, because discovery failures and queueing
  failures land there with no item ever created, and those entries must stay in
  review so the user can still pick a source by hand. The trade-off: deleting a
  *failed* download leaves the episode reading as failed until the batch is
  dismissed. Left as-is on purpose; revisit only with a way to tell the two
  failures apart.

Calling it from `loadFromDiskLocked` is what heals **installs that are already
broken**, including the reporter's device - it reconciles on the next launch
rather than waiting for the next queue change. That path also had to widen its
persist condition to `normalized != stored.items || reconciledBatches !=
stored.batches`.

`DownloadBatchReconcileTest` (8 tests) covers the delete cases, the `FAILED`
carve-out, idempotence, and the empty-batch case. It ran successfully in both
CI suites above.

#### Next steps, in order

1. **Smoke-test the bug fix when a device is available**, because this is a
   persistence fix and no test touches real storage: queue a season, let some
   episodes finish, delete everything from the Downloads tab, reopen the series
   page and confirm every episode reads as not downloaded; then force-stop,
   relaunch, and confirm it still does.
2. **Exercise the desktop updater** from the installed `0.1.20-alpha` to
   `0.1.21-alpha`; merely launching `0.1.20-alpha` did not verify replacement.


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
- `onBatchesChanged` is a no-op on iOS and desktop. The iOS bridge publishes one
  live item to Swift and a second payload needs matching Swift work; desktop has
  no notification surface at all. Both show preparation in the Downloads tab.
- A batch cannot be cancelled while it is preparing, on any platform. See the
  Work Log entry for why the obvious button would lie.
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
  `0.3.7` (versionCode 106) is the current release; `0.3.6`, `0.3.5`, `0.3.4`
  and `0.3.3` precede it. All carry signed APKs for all four ABIs.
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
- `NuvioZDesktop` compiles and produces a verified MSI in CI. `0.1.20-alpha` is
  the current release and `0.1.19-alpha` (2026-08-03) precedes it, each carrying
  one Windows x64 MSI and a `SHA256SUMS.txt`. `0.1.20-alpha` is installed and
  launches on Windows; the in-app replacement flow is still untested.

## Work Log

### 2026-08-05 (download freezing, the 4K preset split, and the releases)

- Traced downloads that stopped around 80% on the Windows build through TorBox to
  four separate faults, detailed above: a desktop body read with no deadline, a
  queue that could not see a transfer it had lost, a size cap enforced
  mid-transfer over sources already approved, and debrid links minted once and
  never refreshed.
- Split the 4K preset into 4K Low (8 GB/h) and 4K High (15 GB/h) after finding
  that the old `Quality` preset - 2160p capped at 4 GB/hour - rejected every real
  4K source with the same "exceeds the calculated size cap" message the freezing
  report started from.
- Fetched Kotlin 2.3.0 directly and ran the download suites against the shipped
  sources (56 passing), type-checked the whole downloads package with the
  serialization plugin, and reproduced the desktop freeze against a stalling
  server to confirm the watchdog ends it.
- Released `0.3.9` (versionCode 108) and `0.1.22-alpha` (code 22), both
  published: four APKs and one Windows x64 MSI with `SHA256SUMS.txt`. The Windows
  `build-only` job was run before the version bumps, since `desktopMain` compiles
  nowhere else and a failed publish would have burnt the version number.
- Note for the next release: the local `main` in a fresh checkout can lag
  `origin/main`, which produces a merge whose first parent is a stale commit.
  Reset to `origin/main` before merging.

### 2026-08-04 (desktop startup latency)

- Root-caused the roughly 20-second cold desktop launch. `Main` called the
  misleadingly named `preloadNativePlayerBridgeAsync` before creating the
  Compose window, but referencing `NativePlayerBridge` synchronously loaded its
  native runtime first. The Windows package also left
  `compose.application.resources.dir` empty and embedded the runtime in the app
  JAR, so each launch extracted the bundled player bridge, the approximately
  110 MB `libmpv-2.dll`, and its runtime DLLs before the first window. The same
  JAR also embedded the approximately 55 MB TorrServer executable.
- Made the complete native-player bootstrap genuinely asynchronous, including
  Kotlin object initialization and DLL loading.
- Moved the Windows player runtime and TorrServer into Compose native
  distribution app resources. Packaged playback now loads the DLLs directly and
  P2P resolves TorrServer directly; the JAR extraction paths remain only as
  development/backward-compatible fallbacks.
- Updated the desktop release workflow to reject native executables left in the
  app JAR and require each one under the packaged `app/resources` directory.
- Fast-forwarded the verified fix into desktop `Dev` at `4a4f4b88`, so the next
  desktop release will include it.
- Verification: both changed Kotlin files passed the standalone parser check.
  GitHub CI run `30948292711` on desktop commit `4a4f4b88` passed the desktop
  tests and built/uploaded the Windows MSI. Local Gradle verification was
  abandoned after its dependency resolver stalled in an HTTPS download; this
  local-machine failure was not attributed to the cloud-sandbox restriction.
- Remaining: run a timed cold and warm launch from the optimized MSI. The
  current installed `0.1.21-alpha` reached a first window in approximately 5.1
  seconds on a warm launch, and its temp extraction timestamps confirmed the old
  per-launch native-runtime path.

### 2026-08-04 (preset controls, batch reconciliation, and releases)

- Exposed the cached-source and file-size preferences in the preset editor.
- Reconciled persisted batch entries against download items on mutation and
  startup, fixing deleted episodes that still appeared downloaded or active on
  series pages; added eight regression tests.
- Merged and verified both repositories: Android host tests/debug APK and desktop
  tests/Windows MSI all passed on the release branches.
- Published `0.3.8` (versionCode 107) with four signed Android APKs and
  `0.1.21-alpha` (versionCode 21) with a verified Windows x64 MSI and checksum.
- Fixed desktop release-note generation so direct merge commits remain visible
  in first-parent repository history. The first desktop publish attempt stopped
  before building because its notes were empty; the corrected retry succeeded.
- Released without Android device smoke testing at the user's explicit direction;
  runtime verification remains pending.

### 2026-08-04 (preparation visibility and dialog cleanup)

- Added the Preparing section to the Downloads tab. Discovery had been moved to
  the background but nothing rendered it, so between the toast and the first
  queued episode the app looked idle for as long as a season took to resolve.
- Deliberately gave the preparing card no cancel: `PresetDownloadCoordinator`
  calls `saveBatch` again when discovery finishes, so a removal during
  preparation would silently come back. Cancelling a batch mid-discovery needs
  the coordinator to be interruptible first.
- Held preparing batches out of the review section, so review is only offered on
  a finished list.
- Added `DownloadsLiveStatusPlatform.onBatchesChanged` and updated all four
  actuals in the same change, including the desktop one - the mistake that broke
  the desktop build with `publishNativeTabTitles` was updating only Android and
  iOS, which compile fine without it.
- Wired the new hook into every batch mutation, not just `publishLocked`.
  Preparation only ever moves through `saveBatch`/`updateBatchEntry`, which never
  touch the item list, so the `publishLocked` call site alone would never have
  fired while a batch was preparing.
- Removed the unreachable in-dialog review branch from `PresetDownloadDialog`
  and the `onQueued`/`onChooseManually` plumbing behind it in both details
  screens.
- Released `0.3.7` (versionCode 106) from `main` and `0.1.20-alpha` from the
  desktop fork's `Dev`, both published for the in-app updaters. Android carries
  signed APKs for all four ABIs; desktop carries the Windows x64 MSI. Both
  release runs were green on the first attempt.

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
