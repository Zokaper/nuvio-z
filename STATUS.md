# Nuvio Z Status

Last updated: 2026-08-07

| | |
| --- | --- |
| **Active branch** | `codex/smoke-0.4.3-followup` in **both** repositories |
| **Released** | `nuvio-z` `0.3.10` · `NuvioZDesktop` `0.1.23-alpha` |
| **Unreleased work** | Two streams. (1) The stranded-download fix plus an expanded desktop harness and four provider-safety fixes. Queue controls now have load/restart coverage; provider resolution is bounded and finite; resumed bytes and materially truncated replacements are rejected when a re-minted URL changes identity; and every debrid transfer forces a real provider readiness check immediately before starting. The credential-safe, provider-backed TorBox season mode has passed against a real account after aging prepared links for sixteen minutes. (2) **Playback modes (Classic / Streamlined / Instant) — all five phases complete and locally verified. See `PLAYBACK_MODES_PLAN.md`.** Both merged into `main` / `Dev` for the `0.4.0-beta` release. |
| **Next** | Re-run the `0.4.3-beta` packaged smoke checklist on Android and desktop. The follow-up is implemented on the active branch: cached/unknown debrid infohash handling, mode-aware episode switching, player-to-details back navigation, source-list masking, direct source downloads, next-episode control, responsive mode selector, top-level mode setting, Advanced badges, Continue Watching details, conservative bandwidth defaults, two-device sync coverage, and Windows network cost/type detection. Focused Android and desktop tests compile and pass. |
| **Also unpushed** | `codex/whats-new` (local only, in `nuvio-z`): one commit, "feat: show release notes after updates". Not merged, not verified, not part of `0.4.0-beta`. |

This table is the first thing to update in any session, and it is kept current on
`main` as well as on the working branch - see "Keeping `main` current" in
`AGENTS.md`. If it names a branch, the newest work is on that branch, not here.

**Read `AGENTS.md` first.** It carries the two-repository mirroring rules, the
full release procedure, which secrets exist and where, and how to verify code in a
sandbox where Gradle cannot configure.

## Playback modes: Classic / Streamlined / Instant (2026-08-06, in progress)

**The plan is `PLAYBACK_MODES_PLAN.md` in this repository, and it is self-contained** — a cold
agent can execute it without this conversation. It covers both repositories. Its execution
ledger is the resume point; keep it current in the same commit as the code.

Three global playback modes, chosen once on a new first-launch selector, with a per-play escape
hatch (long-press on mobile, right-click on desktop) that always reaches the Classic source list:

- **Classic** — today's flow, unchanged, and the fallback when auto-pick misjudges a user's
  plugins or debrid.
- **Streamlined** — pick a quality tier, the app picks the source. Optional sticky pin
  (release group → bingeGroup → addon/provider/resolution) makes the rest of a season one tap.
- **Instant** — quality and source chosen from the network connection; metered connections ask
  before playing.

**Phase 1 landed so far — logic and persistence only, nothing user-visible.** The mode is
stored and defaults to `CLASSIC`, and no code reads it yet, so behaviour is unchanged:

- `features/playback/PlaybackModeModels.kt` — `PlaybackMode`, `PlaybackQualityTier` (a
  *bandwidth* budget, deliberately not a `DownloadPreset`, with a 60% headroom constant),
  `mergeStoredTiers` mirroring `mergeStoredPresets`, and `StickySourcePin` with a scored match.
- `features/playback/PlaybackModeRouter.kt` — the precedence table as a pure function.
- `playback_mode` and `playback_mode_selector_seen` through `PlayerSettingsStorage` with
  **all three actuals** (android, ios, and the desktop one in `NuvioZDesktop`), added to
  `syncKeys` and both sync payload paths, and surfaced on `PlayerSettingsUiState` with
  `setPlaybackMode` / `markPlaybackModeSelectorSeen`.

**A correction to this document's own build advice: Gradle works on the maintainer's Windows
machine.** It only needs `JAVA_HOME` (Android Studio's JBR) and `ANDROID_HOME` set per
invocation — the failure without them is "SDK location not found" during task dependency
resolution, which reads like a configuration failure and is not one. `AGENTS.md` now records
the exact invocation. The sandbox limitation is real but is a sandbox limitation, not a
project one, and the standalone-kotlinc workarounds should be a second choice from now on.

Verification of the above: `:composeApp:testAndroidHostTest` run in full on this machine —
**576 tests, zero failures, errors or skips**, across 82 classes. That is the documented 554
baseline plus the 22 new cases exactly, so nothing was displaced. `PlaybackModeRouterTest` (11)
and `PlaybackQualityTierTest` (11) execute against the shipped sources — including the
regression case that a sticky pin must outrank reuse-last-link, which is the specific bug the
precedence table exists to prevent.
Shared files are mirrored and the only diffs against `NuvioZDesktop` are the pre-existing,
documented ones (its `AppFeaturePolicy` external-player gating and the NVIDIA RTX setting).

`NuvioZDesktop :composeApp:desktopTest` also passed in full: **782 tests, zero failures,
errors or skips**, across 112 classes — the 760 baseline plus the same 22 cases, which run on
the desktop target too. **This compiled `desktopMain`, so the new desktop `actual` is verified
rather than assumed**, and the download harness stayed green. Since `desktopTest` compiles
`desktopMain` on the real machine, `desktop-release.yml mode=build-only` is now only *CI's*
way of catching a missing actual, not the only way.

**The mode is now reachable in the UI.** Settings → Playback → Player → **Playback mode** opens
a `PlaybackModeDialog` listing all three modes with descriptions, and the row is in the settings
search index. Streamlined and Instant are selectable but carry a "Not ready yet - plays like
Classic for now" caption, since nothing consumes them until Phases 2 and 3; `isImplemented()`
in `PlaybackSettingsPage.kt` is the one place to update as each lands. Nine new string keys in
both `values/strings.xml`; the other 24 locales fall back to English as usual.

Both suites re-run green after the UI landed: Android **576** and desktop **782**, zero
failures, errors or skips. Note the settings files genuinely differ between the repositories
(desktop gates the external player behind `AppFeaturePolicy`, renamed the Trakt page to
Tracking, and builds the search rows with `buildList`/`add` rather than `listOfNotNull`), so
these were hand-ported, not copied — a straight `cp` would have broken the desktop build, and
the first attempt did pass two arguments to a single-argument `add(...)`.

**Phase 1 is complete.** Also landed: `PlaybackModeSelectorScreen`, shown on first launch to
everyone (existing installs included) and pre-selected to Classic, so dismissing it changes
nothing. It is gated by **wrapping the `AppGateScreen.Main` branch in `App.kt` rather than
adding a gate value** — five separate transitions set the gate to `Main`, and wrapping covers
every one of them with a single decision instead of five edits that could drift.

Two findings from building the UI:

- **The manual-selection escape hatch already existed.** `MetaDetailsScreen` has always had a
  "Play manually" action in the episode long-press overlay, using the `onPlayManually` callback
  `App.kt` already threads through — it was just gated on
  `StreamAutoPlayPolicy.isEffectivelyEnabled(...)`. Showing it when the mode is not `CLASSIC`
  was a one-condition change, not new plumbing, and since `onPlayManually` sets
  `manualSelection = true` it already satisfies precedence rule 1.
- **`entry<StreamRoute>` wiring is deliberately deferred to Phase 2**, and is its first step.
  In Phase 1 every mode resolves to the source list, so calling `PlaybackModeRouter.decide`
  there would refactor the riskiest block in the app — ~550 lines carrying reuse-last-link,
  auto-play evaluation, debrid resolution and P2P consent — for zero behaviour change. The
  router and its tests are in place and unchanged, waiting for it.

Both suites green after the UI landed: Android **576**, desktop **782**, zero failures, errors
or skips. `App.kt` and `MetaDetailsScreen.kt` were hand-ported, not copied — both already
differ between the repositories.

**Still not smoke-tested on a device or a real desktop install.** No Android device was
attached (`adb devices` empty), so nothing has run against real storage. When one is available:
launch and confirm the selector appears once and only once; pick Classic and confirm nothing
about playback changes; change the mode in Settings, force-stop, relaunch, confirm it held;
switch profiles and confirm the mode is per-profile. The selector shows for existing installs
too, so **that first-launch behaviour is the thing most worth watching** on a device that
already has data.

### Phase 2 complete — picker and Streamlined (2026-08-06)

- `entry<StreamRoute>` now delegates precedence to `PlaybackModeRouter`: explicit manual play,
  completed local download (consumed before the route), matching season pin, valid cached link,
  then playback mode. Non-Classic modes cannot run the legacy `streamAutoPlayMode` picker.
- Plugin scraper metadata now survives ingestion as `PluginStreamMeta`; quality, byte size,
  seeders/peers, provider, and language no longer depend on parsing the display subtitle.
  `SourceFacts` adds plugin-structured provenance, seeders, and release-group extraction.
- Download and playback selection share `SourceRanking` while retaining separate protocol gates.
  Streamlined allows HTTP(S), HLS/DASH, safe debrid candidates, and opt-in torrents only with a
  known healthy seeder count; download protocol policy is unchanged.
- Streamlined shows configured quality tiers plus Best available, handles uncached debrid as an
  explicit choice, and can pin a manually chosen release for the rest of a season through the
  widened `BingeGroupCacheRepository`. The pin outranks cached-link reuse and falls through when
  no candidate matches.
- Quality tiers and the torrent auto-pick toggle are profile-scoped and included in settings sync;
  Android, iOS, and desktop actuals are present. The old Stream auto-play section is disabled with
  an explanation outside Classic, and only Instant retains the not-ready caption.
- Verification: forced full Android host run **585 tests across 85 classes**, and forced full
  desktop run **791 tests across 115 classes**; both had zero failures, errors, or skips. The
  desktop run compiled `desktopMain` and ran the complete download harness. Nine new cases cover
  plugin metadata, release groups, shared ranking, selector gates/caps/fallbacks, and sticky
  release-group precedence. Focused suites also passed on both targets before the full runs.
- **Not verified:** no Android device was attached and no installed Windows build was launched,
  so the quality sheet, manual sticky prompt, persistence across app restart/profile switching,
  plugin-heavy/debrid pick quality, and HLS/DASH playback remain runtime smoke-test work.

### Phase 3 implementation complete — Instant and network quality (2026-08-06)

- Added `NetworkQualityPlatform` using Android `ConnectivityManager`/`NetworkCapabilities`, iOS
  `NWPathMonitor`, and an unmetered Ethernet desktop actual. The per-network estimator caches
  passive throughput separately for each debrid/provider and resolves configured quality tiers.
- Real download progress now feeds bounded throughput samples into the estimator. Unknown
  networks remain conservative at 720p until a real measurement is available.
- `PlaybackRouteDecision.AutoPick` selects the estimated tier, re-checks provider-specific
  throughput, and seeds the existing `StreamsRepository` auto-play candidates in ranked order.
- Instant retries at most three sources. A player error or failure to start within eight seconds
  advances the existing chain; exhaustion returns to the Classic source list with a reason.
- Metered connections ask once per network per app session. The capped choice uses the
  profile-scoped, synced `playback_metered_cap_height` (720p default); full quality applies only
  to that session. Instant's not-ready caption has been removed.
- Added five common estimator tests. Desktop main and test source sets compile successfully.
- Verification: Android host **590 tests across 86 classes** and desktop **796 tests across 116
  classes**, both zero failures, errors, or skips. Desktop main compiled.
- **Not verified:** iOS cannot compile on this Windows host, and no device or installed Windows
  smoke test has been performed. Metered-session behavior and eight-second runtime failover
  therefore still need real-device/installed-app coverage.

Findings from the exploration that shaped it, worth recording independently of the plan:

- **Plugin sources are structurally invisible to the auto picker.**
  `AutomaticDownloadDiscovery` builds `DownloadSourceCandidate` from installed addons only, so
  nothing a JS scraper returns is ever a candidate.
- **Plugin metadata is destroyed on ingest.** `PluginRuntimeResult` carries `quality`, `size`,
  `seeders`, `peers`, `provider`, `language`; `StreamFetchSupport.kt:85`
  `PluginRuntimeResult.toStreamItem()` joins some into a `" • "` display string and **drops
  `seeders` and `peers` entirely**. `SourceFactsExtractor` then regexes that prose back apart.
  For a plugin-heavy profile the picker is guessing.
- **No seeder signal exists anywhere** in `StreamItem` or `SourceFacts` — the strongest
  predictor of whether a torrent source will actually start playing.
- **Two selection mechanisms already run inside `entry<StreamRoute>`.** Verified ordering:
  `manualSelection` gates the local-download check (`App.kt:1584`); the reuse-last-link effect
  (`App.kt:2525`) is gated on `!launch.manualSelection` and fires **before** auto-play
  evaluation. A third picker added without a precedence rule would break Streamlined outright
  (reuse-last-link would pre-empt the quality sheet). The plan's precedence table is normative
  and `streamAutoPlayMode` becomes Classic-only.
- **Reuse, do not rebuild:** `StreamsRepository.skipAutoPlayStream` (`StreamsRepository.kt:767`)
  is already the "candidate failed, advance to the next" mechanism the Instant failure chain
  needs; `BingeGroupCacheRepository` is already per-content release memory and should be widened
  to carry a `StickySourcePin` rather than gaining a parallel store.
- **Mid-playback source switching already exists and preserves position.**
  `PlayerScreenRuntimeSourceActions.kt:229` `switchToSource` captures `playbackSnapshot.positionMs`
  and restores it via `activeInitialPositionMs`. Automatic downshift (Phase 4) is a trigger on
  top of shipped, hand-testable plumbing — not adaptive bitrate, and not phase 1.
- **Nothing detects network type, metered status, or throughput.** `NetworkStatusRepository` is
  a reachability prober only. Instant needs a new `expect`/`actual` across Android, iOS and
  desktop.
- **There is no onboarding anywhere in the app**, so the mode selector is new construction on
  the `AppGateScreen` state machine. It needs `playback_mode_selector_seen` persisted separately
  from `playback_mode`, or "chose Classic" is indistinguishable from "never chose".

### Phase 4 complete — auto source-swap, opt-in and default off (2026-08-07)

**The precondition found a real bug, which is the main result of this phase.** Phase 4 was
gated on verifying that `bufferedPositionMs` is meaningful on libmpv, not just ExoPlayer. It
is not, on one platform:

- Android mpv does `maxOf(positionMs, cachePositionMs)` (`PlayerEngine.android.kt:1249`) and
  the desktop C++ does `cacheTime - effectivePosition` (`player_bridge.cpp:1896`). Both treat
  mpv's `demuxer-cache-time` as what it is: an **absolute** stream timestamp for the end of
  the cache.
- iOS did `position + cached` (`MPVPlayerBridge.swift:883`), treating that same absolute
  timestamp as a *duration ahead of the position*. So iOS reported a buffer that grew with
  playback position and never looked starved.

Two of three implementations of one libmpv property disagreed with the third, which settles
it without a device. **This was already a live bug**, not only a Phase 4 blocker:
`PlayerScreenRuntimeUi.kt` derives its user-visible buffer readout from
`bufferedPositionMs - positionMs`. Fixed to match Android exactly. **The Swift change cannot
be compiled on this Windows host and is unverified** — it is three lines and mirrors a
verified implementation, but it has not been run.

What landed on top of that:

- `features/playback/AutoDownshiftDetector.kt` — the trigger, pure and clock-free, plus
  `AutoDownshiftCandidates` for the swap constraints. **The run is measured in wall-clock
  time, not snapshot counts.** Android polls the player every ~250 ms and desktop every
  500 ms, so the plan's "≥3 consecutive snapshots" would have meant 0.75 s on one platform
  and 1.5 s on the other — neither is "sustained", and they would not have agreed. A
  duration threshold (4 s buffer-ahead, held 6 s continuously, minimum 3 samples) makes both
  platforms behave identically with no per-platform tuning.
- Arming conditions, each of which can otherwise burn the one-swap budget on a false
  positive: a 15 s settle grace (desktop's `effectiveCachePositionSeconds()` clamps the cache
  position to the resume point after a seek, so buffer-ahead is untrustworthy early); a run
  reset on pause, seek, or source change; and a stall (`paused-for-cache`) counted as
  starvation whatever the reported buffer says.
- Swap constraints: same release group only, never upward, manifests exempt (HLS/DASH adapt
  internally), never onto an uncached debrid candidate, and null — no swap — whenever the
  release group or resolution is unknown.
- `playback_auto_downshift` through `PlayerSettingsStorage` with **all three actuals**, in
  `syncKeys` and both sync payload paths, surfaced as an Instant-only settings row.

**"One swap per session" is read as one per playback session, reset on a new episode**, not
one per source: a position-preserving switch keeps the budget spent. The budget is charged by
`consumeSwap` at the call site, never by the detector — whether a swap is even possible
depends on the candidate list, and charging for one that never happened would silently
disable the feature for the rest of the episode.

**Identifying the playing source cannot be done by URL.** `switchToSource` re-enters with the
debrid-*resolved* stream, so `activeSourceUrl` holds a minted URL no candidate in the source
list carries, and a P2P source holds a sentinel URL that matches nothing. Since Instant's
users are mostly on debrid, URL matching would have made this a silent no-op on its main
path. `matchesActiveSource` tries info-hash, then identity key, then URL, then
addon + label — the last arm being the one that survives resolution, which rewrites `url`,
`filename` and `videoSize` but leaves `addonId`, `streamLabel` and `streamSubtitle` alone.

Verified: Android host **607 tests across 87 classes** and desktop **813 tests across 117
classes**, both zero failures, errors or skips — the documented 590/796 baselines plus the 17
new `AutoDownshiftDetectorTest` cases, which run on both targets. The desktop run compiled
`desktopMain`, so the new desktop `actual` is verified rather than assumed.

**Not covered:** the iOS Swift fix (no macOS host), and any on-device or installed-app
behaviour — no Android device was attached and the Windows app was not installed at any
point. The setting is off by default, so nothing here changes playback until a user opts in.

### Phase 5 complete — the download entry point follows the mode (2026-08-07)

**This phase existed because a decision had no phase.** "Modes change the download *entry
point*, not the download engine" was recorded in the plan's **Decisions taken** section and
never assigned to a numbered phase, so finishing Phases 1–4 left it unbuilt. `playbackMode`
did not reach `features/downloads/` at all.

- Classic downloading a **single** item opens the source list and the chosen release is
  downloaded. A season still gets the preset dialog — hand-picking twenty releases is a
  chore, not control.
- Streamlined keeps today's preset dialog, unchanged.
- Instant starts with no dialog, using the preset that matches the connection tier, capped
  by the same `allowMeteredNetwork = false` default the dialog itself uses.

**Routing Classic to the source list was not sufficient, and this is the part worth
remembering.** That screen plays on tap and offers download only from the long-press sheet,
so the Download button silently behaved as Play. `StreamLaunch.downloadIntent` now carries
the intent, `StreamsScreen.downloadOnSelect` makes a tap enqueue instead of playing, and the
same flag forces `streamManualSelection` so no automatic playback path can fire under a
Download press. `onDownloadManually` deliberately does **not** go through
`launchPlaybackWithDownloadPreference`: that short-circuits to playing a completed local
download, which is right for a play and wrong for a download request.

Every branch degrades rather than dead-tapping: no manual route (no handler, or no single
resolvable video) and no configured presets both fall back to the preset dialog.
`DownloadsRepository`, the queue, the transfer stack and `PresetSourceSelector` are
untouched, per the plan's non-goal of destabilising the download stack.

Verified: Android host **615 tests across 88 classes** and desktop **821 tests across 118
classes**, both zero failures, errors or skips, with `desktopMain` compiled. Not smoke-tested
on a device or an installed app.

### Uncached debrid auto-played, fixed in 0.4.2-beta (2026-08-07)

Instant started an ElfHosted placeholder — the two-minute `MEDIA_NOT_CACHED_YET` slate —
on a source whose display name plainly carried the not-cached hourglass. This is the exact
outcome `PLAYBACK_MODES_PLAN.md` said Instant must never produce, and it survived because
two gaps lined up:

- `SourceFactsExtractor` learned cache state only from the structured `debridCached` and
  `clientResolve.isCached` fields. Many debrid addons advertise it **only in the display
  name**, so `isDebridReady` was *null* — unknown — rather than false.
- `PlaybackSourceSelector.isUncachedDebrid` excluded only an explicit `false`, so unknown
  passed straight through to auto-play.

**The rule is now: unknown is not cached.** Auto-pick requires positive evidence of a cached
copy, and uncached candidates are kept out of the `fallbacks` list too — otherwise the
failure chain lands on a placeholder one retry later instead of never.

⚠ **The fail-safe is scoped to debrid-backed candidates only** (`debridService` set,
`clientResolve` present, or a direct debrid stream). Plugin scrapers and plain direct HTTP
links legitimately have no cache state at all; gating on null globally would empty the
candidate set and leave Instant unable to play anything. `aNonDebridSourceWithNoCacheStateStillPlays`
is the regression guard for that over-application — do not remove it.

Display-name parsing (`parseDebridCacheMarker`) is a second layer, not the fix. Negatives are
checked before positives so "not cached" cannot read as "cached", and `instant` is excluded
from the positive set because *Instant Family* exists.

Verified: Android **624 tests across 89 classes**, desktop **830 across 119**, zero failures.

### 0.4.0-beta regressions, fixed in 0.4.1-beta (2026-08-07)

Both found within minutes of installing `0.4.0-beta` on a real phone, and neither was
reachable by any unit test. This is the concrete cost of the "not smoke-tested" caveat that
had been carried since Phase 1.

**1. Every sync pull deleted the new playback settings.**
`PlayerSettingsStorage.replaceFromSyncPayload` cleared *all* of `syncKeys` before applying
the payload. The remote blob is authoritative for settings it knows about — but it had never
heard of any `playback_*` key, because none existed when it was last written. So a signed-in
user lost `playback_mode`, the quality tiers, the metered cap, the torrent toggle and
`playback_mode_selector_seen` on every pull. The visible symptom was the first-launch
selector reappearing straight after pressing Continue; silently, the chosen mode was being
reset to Classic at the same time.

Fixed by clearing only the keys the payload actually carries, through a shared
`syncKeysToClear` in `commonMain` so the rule cannot drift between the three actuals.
`SyncKeysToClearTest` reproduces the old-blob shape directly.

⚠ **The same wipe-then-apply pattern is still present in `MdbListSettingsStorage`,
`StreamBadgeSettingsStorage`, `TmdbSettingsStorage` and `TraktCommentsStorage`.** None of
them gained a key in this release, so none is currently losing data — but the next key added
to any of them will hit exactly this. Fix them before adding one.

**2. The selector captioned Instant "Not ready yet".**
`PlaybackModeSelectorScreen` had its own hardcoded `mode == PlaybackMode.INSTANT` check,
separate from `isImplemented()` in `PlaybackSettingsPage`. The plan said that function was
"the single place to update" and that was simply wrong — there were two. Both are gone now,
along with the dead gate and the unused string.

Verified: Android **619 tests across 89 classes**, desktop **825 across 119**, both zero
failures, `desktopMain` compiled.

## Polish pass on 0.4.2-beta (2026-08-07, unreleased)

Surface-tested `0.4.2-beta` on a phone: the playback-mode logic works, the presentation had not
caught up with it. Five workstreams, landing in order, each verified on both targets.

### 1 — The sync wipe pattern, everywhere it existed

`STATUS.md` named four stores still carrying `replaceFromSyncPayload`'s clear-everything-first
bug. **There were six.** `DebridSettingsStorage` and `ThemeSettingsStorage` had the same shape and
were not on the list; Debrid is the worst of them, because its key list is built at runtime from
`DebridProviders.all()`, so adding a provider would have deleted stored API keys for the others on
the next pull.

`syncKeysToClear` **moved from `features/player/PlayerSettingsStorage.kt` to
`core/sync/SyncPreferenceJson.kt`** (`com.nuvio.app.core.sync`), next to the `decodeSync*` helpers
every one of these stores already imports. It was `internal` in a feature package that five
unrelated features would have had to import from.

All six stores now clear only the keys the payload carries, across **19 actuals** (android, ios,
and the six desktop ones in `NuvioZDesktop`). `TraktCommentsStorage.desktop.kt` needed a `syncKeys`
list of its own — it had been removing its single key unconditionally, so a payload that omitted
`comments_enabled` silently switched comments back off.

`SyncKeysToClearTest` moved to `commonTest/.../core/sync/` and gained six cases, one per store,
each reproducing that store's old-blob shape.

### 2 — Instant and Streamlined no longer show the source list

The complaint that started this pass. `entry<StreamRoute>` rendered `StreamsScreen` unconditionally
as the base of its `Box` and drew the quality sheet on top, so Instant users watched a wall of
releases populate and then get replaced.

**The overlay covers `StreamsScreen`; it does not replace it.** `StreamsScreen.kt:203` owns
`LaunchedEffect { StreamsRepository.load(...) }` — composing it away would cancel the very fetch
the overlay reports on. This is the constraint that shaped the whole edit.

`features/playback/PlaybackProgressOverlay.kt` is new, and its decision half is pure:
`PlaybackProgress.step(...)` and `PlaybackProgress.isVisible(...)` are testable functions, with the
composable a thin renderer over them.

**Every step maps to state that already existed** — no timed or faked sequence:
`FindingSources` from `isAnyLoading`/`requestToken`, `ChoosingSource` from `instantSelectionHandled`,
`ResolvingLink` from the existing `resolvingDebridStream` flag, `StartingPlayback` otherwise.
Resolving is checked **first**, because a slow addon can leave `isAnyLoading` true long after the
pick while the debrid mint is the thing actually being waited on.

Two new `rememberSaveable(route.launchId)` flags: `streamlinedPlaybackStarting` (set when a tier is
picked, so Streamlined is covered from there to playback) and `autoPickAttempt` (advanced only by
the failure chain, so a silent retry reads as "Attempt 2 of 3" rather than a hang).

⚠ **The overlay uncovers the list for every path that needs the user**: `manualSourceListRequested`
(all four bail-outs already set it), the metered sheet, the uncached sheet, the sticky-pin prompt
and P2P consent. `everyBailOutToTheSourceListUncoversIt` is the regression guard — a spinner over a
screen the user has to read or answer is worse than never covering it.

⚠ **Scope boundary:** the overlay ends at navigation to `PlayerRoute`. The 8-second startup budget
and the `onFatalPlaybackError` / `onPlaybackStarted` retry callbacks run on the **player** screen
and are a separate surface. Classic and every manual path keep the old lighter scrim during debrid
resolution, because there the source list behind it is what the user chose from.

Verified: Android **639 tests across 90 classes**, desktop **845 across 120**, both zero failures,
errors or skips; `desktopMain` compiled. `App.kt` was hand-ported, not copied. **Not smoke-tested
on a device** — the step labels and the attempt counter are exactly what a device run is for.

### 3 — The modes explain themselves

`PlaybackModeCard` is one composable, used by both the first-launch selector and
`PlaybackModeDialog` in settings. Each mode is a card with a tagline and two labelled blocks,
**Streaming** and **Downloading**.

⚠ **Those two files describing the modes separately is how Instant kept a stale "Not ready yet"
caption in `0.4.0-beta`** after the other copy had been fixed (see the `0.4.1-beta` section).
`playbackModeTitle`/`playbackModeDescription` in `PlaybackSettingsPage` are gone; the shared
`playbackModeName` replaced them.

`PlaybackModeDownloadCopyTest` pins the download lines to `PlaybackModeDownloadRouter.decide`.
Classic is the only mode whose entry point depends on whether the scope is a single item, and its
card is the only one that says so — copy contradicting the router is worse than no copy.

### 4 — A global "Show advanced settings" toggle

One switch in Settings → Advanced. Rows tagged `isAdvanced = true` render nothing when it is off,
via `LocalShowAdvancedSettings` and a parameter on `SettingsNavigationRow` / `SettingsSwitchRow`.
Per-row annotation rather than restructuring pages: `PlaybackSettingsPage` alone is ~3700 lines,
and a defaulted parameter is something a future row gets right for free.

**The default when unset is the part most likely to read as data loss, so it does not guess how
old an install is.** `hasTunedAnAdvancedSetting` (`features/player/AdvancedSettingsDefault.kt`)
asks the question that actually matters — has this profile ever *stored* a value for an advanced
setting? — and a profile that has keeps them visible. An explicit stored `false` counts as
touched: turning something off is as deliberate as turning it on.

⚠ **Settings search deliberately ignores the flag.** `SettingsSearch` keeps indexing hidden rows
and reveals them on the page it lands on; ordinary navigation back to Root clears the reveal.
Hiding a setting the user just searched for by name would be worse than showing it.

`settings_show_advanced` is profile-scoped and went through `syncKeys` and both payload paths in
all three actuals — which is why item 1 landed first.

Currently tagged: the Advanced page row, torrent auto-pick, auto-downshift, reuse-last-link and
its cache duration, decoder priority, DV7→HEVC and tunneled playback. Deliberately small; nothing
a normal user changes is tagged.

### 5 — What's New, rebuilt with version history

**It did not work because it was never merged.** `codex/whats-new` was one local commit in both
repos; no shipped build contained it. Cherry-picked and then finished, because it had three gaps:

- **No `desktopMain` actual** for `internal expect object WhatsNewStorage` — the trap `AGENTS.md`
  flags twice. Added, plus the missing `WhatsNewStorage.initialize` in the desktop repo's
  `MainActivity`.
- **Single version, no history.** `AppUpdaterRepository` already fetched `releases?per_page=20`
  and discarded everything but the newest. `fetchRecentReleaseNotes` reads that same response, so
  the history costs no new kind of request.
- **Markdown rendered raw.** `ReleaseNotesDialog` pushed `update.notes` through a plain `Text`, so
  every heading showed as `## Fixes` and every bullet kept its literal `- `. `parseReleaseNotes`
  handles headings, bullets and paragraphs and strips inline markers; unrecognised syntax falls
  through as a paragraph, which is the safe direction — showing a line we did not understand beats
  dropping it. Both the What's New history and the update banner now use it.

The current version's notes stay **curated and offline** (`CurrentReleaseNotes`), because the
screen has to work on the first launch after an update and on builds where the updater is off. It
is **not** gated on `AppFeaturePolicy.inAppUpdaterEnabled` for the same reason; only the fetched
history degrades, to "needs a connection".

Also reachable on demand from Settings → About, dismissible there, and that path deliberately does
**not** record the version as seen — otherwise opening it early would skip the post-update showing.

⚠ **This needs a curated entry per release, committed before the version bump.** The bump-last
rule is enforced and a docs commit after the bump fails the release.

### Two faults found reviewing the pass, both fixed here

**1. The overlay never learned that playback had started — a regression this pass introduced.**
`isVisible` gated on `reuseNavigated`, which is set **only** in the reuse-last-link branch.
Nothing set it when the auto-play effect, `openSelectedStream` or `openExternalPlayback` reached
the player. Instant deliberately does **not** `popUpTo<StreamRoute>` (that is what keeps the
failure chain alive), so `StreamRoute` stays on the back stack with `instantSelectionHandled`
true — and backing out of the player landed on an opaque full-bleed overlay reading "Starting
playback" with nothing to interact with. `rememberSaveable` meant it survived process death too.
Before this pass that screen showed the source list: odd, but usable.

Fixed with `playbackHandedOff`, set at **every** exit to playback (six sites), and
`playbackHavingStartedHidesTheOverlay` is the regression guard. That test replaced
`theAttemptBudgetMatchesTheFailureChain`, which asserted `MAX_ATTEMPTS == 3` — a constant pinned
to itself, claiming more than it checked.

**2. Desktop What's New compared the wrong version.** The hand-port used
`AppVersionConfig.VERSION_NAME` in five places. On the desktop target that is the **base/mobile**
version; `AppVersionPolicy.displayVersionName` is `DESKTOP_VERSION_NAME`. They are equal today
(one shared version line since `0.4.0-beta`), so nothing misbehaves yet — but if they ever
diverge, `shouldShowWhatsNew` would compare against a string that does not change when the
desktop version bumps, and What's New would show once and never again. Swapped, matching what
the desktop `SettingsRootPage` already did.

Also: the displayed attempt is now `coerceAtMost(MAX_ATTEMPTS)`, because the seeded candidate
list is not itself capped, so "Attempt 5 of 3" was reachable.

**Known gap:** "Show advanced settings" is in the settings search index; the **What's New About
row is not**, because it opens a dialog rather than a page and would need a new
`SettingsSearchTarget` variant handled in all four `openSearchTarget` implementations.

### Verification for the whole pass

Android **653 tests across 94 classes**, desktop **859 across 124**, both zero failures, errors or
skips; `desktopMain` compiled. `App.kt`, `PlaybackSettingsPage.kt`, `SettingsScreen.kt`,
`SettingsRootPage.kt`, `SettingsComponents.kt`, `AppUpdater.kt`, `AppUpdaterBanner.kt` and
`strings.xml` were **hand-ported** — all of them already differed between the repositories, and
the desktop `SettingsRootPage` needed `AppVersionPolicy.displayVersionName` where mobile uses
`AppVersionConfig.VERSION_NAME`.

**Nothing here is smoke-tested on a device or an installed desktop app.** The parts a device run
has to cover, because no unit test reaches them:

1. Instant on Wi-Fi: progress overlay with changing step labels, never the source list.
2. Instant with the chosen source killed mid-flight: "Attempt 2 of 3", still no source list.
3. Instant on mobile data: the metered sheet appears *instead of* the overlay, once.
4. Streamlined: quality sheet → tier → overlay → player; "Choose source manually" still works.
5. Sign in on a second device and pull: playback mode, MDBList, TMDB, badge, Trakt-comment and
   **debrid API keys** all survive.
6. Advanced off/on, and settings search still finding and revealing a hidden row.
7. Install over an older build: What's New shows once, lists previous versions, does not reappear,
   and still opens from Settings → About with no network.

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

## Downloads that stopped moving, and a harness that finds them (2026-08-05, unreleased)

Reported as: a download reports a bare "closed", recovers on its own, but by the time
it does, the next item in the queue has started and *that* one sits unfinished.

**One fault, and it is a race, not a recovery gap.** A transfer does not stop when it
is cancelled; the read it is parked in has to end first, and the last thing it reports
arrives afterwards, from its own thread. Callbacks were keyed by download id alone, so
that last word was applied to whichever attempt was running by then - and the queue
routinely cancels and restarts an item in the *same locked section*, which is exactly
the window it lands in (`reclaimLostTransfersLocked`, and the preemption path behind
"move to top"). The stale report:

- took the live transfer's handle out of `activeHandles`, leaving a transfer nothing
  could pause or cancel and a slot the queue believed was free, and
- stamped the item `Paused/System` at the byte count the *previous* attempt reached.

From the outside that is a row frozen partway through while the queue moves on. The
download is not dead at that instant - its transfer is still running, invisibly, and
`onTransferProgress` drops every update because the item no longer reads as
downloading - so it becomes permanent only when that transfer also stops: a failure
reported against an item not marked downloading is recorded and never retried.
Nothing on desktop resumes a system pause either, so it stayed there until a restart.

Fixed by giving every attempt an `ActiveTransfer` carrying a **generation**. The
listener holds it and each of the five callbacks checks it, so a replaced attempt can
no longer speak for its download. The same object holds the slot while a source URL is
re-minted, which retires `PendingResolveTaskHandle` - a shared singleton that could
not tell two concurrent resolutions apart either.

Belt and braces, since a state nothing can leave is worth closing off for good:
`DownloadsPlatformDownloader.recoversSystemPauses` says whether the platform that
system-pauses transfers also brings them back. Android's background job and iOS's
foreground hook do; desktop has neither half, so there `lostTransfers` now takes a
system-paused item with no transfer behind it back into the queue. **The generation
fence alone fixes the reported fault** - verified by running the harness with only
that half in place - so this is a safety net, not the fix.

### The harness

`composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt`, with
`FaultyMediaServer.kt` beside it. It drives the real `DownloadsRepository` through the
real desktop downloader onto real disk; only the media host stands in, because the
faults that matter are things a *server* does. On a raw socket rather than
`com.sun.net.httpserver`, since a well-behaved HTTP server will not produce them:

| Fault | What it reproduces |
| --- | --- |
| `DropConnection` | the bare "closed" - a body that simply stops |
| `GoSilent` | headers, some bytes, then an open connection with nothing on it |
| `Reject(403)` | a signed link that expired before its turn came |
| `Placeholder` | the "your file is being prepared" video, complete and valid |

`DownloadsTiming` exists so the harness can turn the 60s stall deadline and the 5min
queue watchdog down to seconds; the shipped defaults are never changed outside one.
`DownloadsRepository.resolvePlayableStream` is a variable for the same reason - it is
the only way to reach re-minting without a real debrid account and a link left to
expire.

Run it with `./gradlew :composeApp:desktopTest`; CI already does, on every push. The
Gradle task points `user.home`, `APPDATA` and `XDG_CONFIG_HOME` at the build directory
so a test run cannot touch a developer's own Nuvio Z install, and the test asserts it
landed somewhere disposable before writing anything.

**Against real sources:** set `NUVIO_DOWNLOAD_TEST_URLS` to a comma-separated list of
direct media URLs and `real sources download end to end` runs the same queue against
them at the shipped deadlines; it skips when the variable is unset. That proves real
transfer and concurrency behavior only: raw signed links have no provider/hash origin
and cannot be re-minted.

The provider-backed TorBox case uses `NUVIO_TORBOX_TEST_SOURCES` to name a local JSON
fixture containing the original info hashes/file selectors and reads the API key from
`NUVIO_TORBOX_API_KEY`. It pre-resolves the entire season as production preparation
does, can wait more than fifteen minutes, then enqueues every resolved link with its
durable origin. Every transfer must perform a fresh real provider check and the final
files must match the queue's exact totals. `scripts/run-torbox-download-e2e.ps1`
prompts for the key without putting it in shell history, uses a single-use Gradle
daemon, and clears the environment afterward. The fixture and key are not logged or
committed.

## Preset UI and the mid-range size preference (2026-08-05, released in 0.3.10 / 0.1.23-alpha)

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

- Download reliability pass (2026-08-05):
  - Added the opt-in full-provider TorBox season case described above, its local
    fixture example, and a masked secure runner. The desktop suite passes **760
    tests**, zero failures/errors.
  - Ran that case against a real TorBox account with three cached episode files
    totalling about 228 MB. It prepared the three provider links up front, held them
    for **960 seconds**, then forced a fresh provider readiness check/re-mint for each
    queue transfer. The case completed in **1,004.398 seconds**, with zero failures or
    errors; all three files completed at the exact provider/HTTP totals and the queue
    stranded nothing. The report contained no skip marker.
  - The first live invocation exposed a harness-runner fault rather than a download
    fault: Gradle could reuse the earlier credential-free skip as an up-to-date test
    result. The secure runner now passes `--rerun-tasks` and disables configuration
    caching, matching the successful live run. A targeted post-run scan found zero copies
    of the credential in the temp log, fixture, disposable test profile, XML, or HTML
    reports; the temporary fixture/log were removed and the isolated runtime reset.
  - Extended the desktop E2E harness from 8 local fault/queue cases to 30. New
    coverage exercises every reorder direction under load, ranged preemption,
    user pause/resume during transfer and retry backoff, cancel and bulk delete,
    active queue reload with preserved rank/partial files, controls during a
    suspended re-mint, expiry after 20% and 90%, one-time and permanent re-mint
    failures, provider hangs, 429/503, dead accounts, changed/truncated identity,
    and the cached/not-cached/evicted/unknown/placeholder readiness outcomes.
  - The harness reproduced four production faults before their fixes: permanent
    re-mint failure retried forever; a hung provider call held a transfer slot
    forever; and a re-minted same-sized different file was appended to the old
    `.part` and marked complete; and a materially truncated replacement was
    accepted at its shorter HTTP total. It also proved that a fresh resolved URL
    skipped the provider cache check and downloaded even after the source was evicted.
  - Fixed those paths by applying the finite re-resolution budget before transfer,
    bounding provider calls at 60 seconds, retaining validators across re-mint so
    `If-Range` resets changed objects, bypassing the resolver's 15-minute success
    cache for download readiness, rejecting materially contradictory refreshed
    provider sizes, and distinguishing not-ready, retryable, changed, and fatal
    provider outcomes. Direct HTTP downloads remain direct.
  - `NuvioZDesktop :composeApp:desktopTest` passed in full: **760 tests**, zero
    failures/errors/skips, including all **30** local desktop download harness cases
    plus the opt-in real-provider case's safe no-credential path.
  - `nuvio-z :composeApp:testAndroidHostTest`: **554 passed**, zero failures,
    errors, or skips. `:androidApp:assembleFullDebug` also completed successfully.
    The four changed common files are byte-identical between repositories.
  - CI is green on both code commits: `nuvio-z` `a6170825` passed Android host
    tests and the debug APK build in run `31043186788`; `NuvioZDesktop`
    `223a396e` passed desktop tests and the Windows MSI build in run `31043196526`.
  - Real TorBox provider/hash coverage is complete as described above. The older
    `NUVIO_DOWNLOAD_TEST_URLS` raw-URL mode remains useful only for direct transfer
    and concurrency checks; it is not used as evidence for provider re-minting.
- Stranded downloads and the harness (2026-08-05). The first download work here with
  runtime evidence rather than an argument. Gradle still cannot configure in the
  sandbox, so Kotlin 2.3.0 was driven directly, describing the source set to the
  compiler as two fragments (`-Xfragments=common,desktop -Xfragment-refines`) so the
  real `expect`/`actual` pairs compile as they do in the build:
  - The harness ran against the **shipped** `DownloadsRepository`,
    `DownloadQueuePlanner`, `DownloadTransfer` and the **shipped**
    `DownloadsPlatformDownloader.desktop.kt`, over a real socket, writing real files.
    Stand-ins only for what is outside the download stack: compose-resources,
    `NetworkStatusRepository`, the debrid resolver, `ProfileRepository`,
    `AppFeaturePolicy`.
  - **The fault was reproduced.** With callback fencing disabled the regression case
    fails every time: episode 1 ends `Paused/System` at 1,687,355 of 6,291,456 bytes
    and never moves again, while episode 2 - the next in line - completes. That is the
    report, exactly.
  - **With the fix, 8/8 pass in ~18s, four runs in a row, no flakes.** Re-run with
    only the generation fence and not the desktop system-pause recovery: still green,
    which is how the fence is known to be the load-bearing half.
  - `DownloadQueueTest` (2 new cases) and `DownloadTransferTest` were re-run against
    the shipped sources alongside it: **44 tests pass** in total.
  - The changed Android and iOS actuals passed the parser-only check but are not
    compiled by anything in the sandbox, so CI was the check that mattered for the
    new `recoversSystemPauses` `expect`. **Both repositories are green** at
    `1aa45d2` / `d2ab738`: nuvio-z ran the Android host suite and built the debug
    APK, and the desktop run passed both "Desktop tests" - which is where the
    harness itself now runs, on every push - and the Windows MSI job, the only
    thing that compiles `desktopMain`.
  - The first desktop run failed at configuration: `java.time.Duration` does not
    resolve in the Kotlin DSL, where `java` is Gradle's Java extension. Fixed with
    an import in `d2ab738`.
  - **Not run against a real debrid link.** The `NUVIO_DOWNLOAD_TEST_URLS` mode has
    never been exercised, because the sandbox has no route to a media host - the
    desktop downloader's `HttpClient` does not read the system proxy either. That
    run is part of the next step below.
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
  - Released as `0.3.10` (versionCode 109) and `0.1.23-alpha` (code 23) from the
    bump commits `b03d6ba` / `16c28910`. `android-release.yml mode=publish` on
    `main` attached the four ABI APKs; `desktop-release.yml mode=publish
    target=windows` on `Dev` attached the MSI and `SHA256SUMS.txt`. The separate
    `desktop-release.yml mode=build-only` pre-check was **skipped**: `ci.yml` now
    carries a Windows MSI job which compiled the exact release commit and passed,
    so AGENTS' claim that the release workflow is the only `desktopMain` compile is
    out of date.
  - **Still to do:** a device/desktop smoke test of the new picker, the editor
    controls, and the toast link. This shipped without any runtime testing.
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

### NEXT: make a download behave like a Netflix download

**This is the current priority, and it is the standard to hold the work to.** A
download in this app should be as boring and as certain as one in Netflix: you
start it, you can reorder it, pause it, resume it, close the app, lose the
network, come back tomorrow - and it either finishes or tells you plainly why it
cannot. No row that stops moving. No state only a restart can leave. Nothing that
needs the user to know what a debrid link is.

The harness in `NuvioZDesktop`
(`composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt` and
`FaultyMediaServer.kt`) is where that gets proven. It now covers the local,
deterministic parts of items 1-3 below: queue controls under load and across a
repository reload, provider failures and controls during them, byte identity
across re-mint, and provider readiness immediately before transfer. The harness
was extended first and reproduced every production fault fixed in this pass.
The real-account and real connectivity-transition work in item 4 remains.

**1. The queue controls, under load - covered locally.** Every one of these
cancels a running transfer, and cancelling is what the stranding bug came out of.

- Reorder while transferring: move to top, up, down, to bottom; the promoted item
  starts at once and the preempted one keeps its `.part` file and resumes from
  where it stopped rather than restarting.
- Pause and resume, by hand, mid-transfer and mid-retry-backoff. A user pause is
  sticky - it must survive a queue nudge, a reclaim sweep and an app restart, and
  must never be undone by the recovery paths.
- Cancel and delete mid-transfer, including the last item and the only running
  one; files and `.part` files actually go.
- Reorder, pause and resume *while a fault is in flight* - during the re-mint
  round trip, during a backoff, in the window where a cancelled transfer is
  reporting its last word. That window is exactly where the fixed bug lived, and
  the other three controls reach it the same way the reclaim sweep did.
- Close and reopen: a queue that was mid-transfer comes back in the same order,
  from the same bytes, with user pauses still paused. `loadFromDiskLocked` has
  never been exercised against a queue in a real intermediate state.

**2. Provider failures - covered locally except a real connectivity observer
transition.** `FaultyMediaServer` and the re-mint stand-in now fail on demand:

- a link that expires *mid-transfer* rather than before it starts, at 20% and
  again at 90%;
- re-minting that fails once, then succeeds; that fails every time (the download
  must end `Failed` with a message a human can act on, not retry forever);
- a re-minted link that points at a *different or truncated* file - `If-Range`
  and the overrun/short checks should catch it rather than silently corrupting
  the `.part` file;
- the provider timing out or hanging rather than answering - re-mint runs off the
  lock while holding a slot, and nothing bounds it today;
- 429 and 5xx from the provider, and the whole account failing (every call 401)
  while a season batch is in flight;
- the network dropping entirely and coming back, which on desktop only
  `NetworkStatusRepository` reports.

**3. Cached-on-the-debrid, checked immediately before transfer - implemented and
covered through the provider seam.** This was the weakest link behind "download
queued" placeholders reaching the disk.

Today readiness is whatever the *addon* claimed at selection time
(`SourceFacts.isDebridReady` from `aio.debridCached` / `clientResolve.isCached`),
consulted once in `PresetSourceSelector` and only when `preferCachedSources` is
on. Nothing ever asks the provider directly, and nothing re-checks between
planning a season and reaching episode 9 an hour later. The placeholder check
(`isImplausiblySmallForMedia`) is the only real defence and it is *post-hoc* - it
downloads the wrong file first, then retries on a 1-to-10-minute backoff.

The queue now bypasses the resolver's fifteen-minute success cache and asks the
provider again **before every debrid transfer starts**. Not-cached sources wait
without touching the media URL, provider uncertainty retries with a visible
reason, dead accounts fail plainly, and a placeholder that arrives after a
successful check is still rejected. Cached, not cached, cached-then-evicted,
provider unsure, and post-check placeholder outcomes all have harness cases.

**4. Prove it against a real account - still pending.** The local server cannot imitate provider
quirks, which is where every fault so far has come from. Run the same queue
against TorBox with `NUVIO_DOWNLOAD_TEST_URLS`, and run a real season batch left
going long enough to cross the fifteen-minute link window - that is the only
thing that exercises re-minting for real, and it has still never been done.

Whatever this turns up: fix it in `nuvio-z` and mirror to `NuvioZDesktop`, keep
the harness green in CI on both, and record here what was covered and what was
found. A fault reproduced in the harness is worth more than a fix argued for in a
commit message.

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
