# Playback Modes: Classic · Streamlined · Instant

Plan file for Nuvio Z (`nuvio-z` = Android/iOS, `NuvioZDesktop` = desktop). Written to be
executable by a cold agent (Claude or Codex) with no access to this conversation.

**Read `nuvio-z/AGENTS.md` and `nuvio-z/STATUS.md` first.** They are canonical and cover both
repositories. Everything below assumes the two-repository mirroring rule: edit in `nuvio-z`,
`diff -q` and `cp` across, hand-port anything that already differs.

---

## START HERE (handoff, 2026-08-06)

**State: Phase 3 complete and verified in both repositories. Phase 4 is next.**

| | |
| --- | --- |
| Branch | `claude/desktop-download-queue-bug-vowjy8` in **both** repos |
| Current verification | Android host **590** tests · desktop **796** tests · both zero failures |
| Not pushed | Commits are local only. `main`/`Dev` do not yet have this work. |
| Not device-tested | No Android device was attached at any point. |

**Build and test — Gradle works on this machine.** `AGENTS.md`'s "Gradle cannot configure"
note applies to the agent sandbox, not here. Both variables are required:

```bash
# nuvio-z — Android host suite (~2 min)
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
ANDROID_HOME="C:\\Users\\Rayoa\\AppData\\Local\\Android\\Sdk" \
  ./gradlew.bat :composeApp:testAndroidHostTest --console=plain --max-workers=4

# NuvioZDesktop — desktop suite, and the only local thing that compiles desktopMain (~4 min)
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
ANDROID_HOME="C:\\Users\\Rayoa\\AppData\\Local\\Android\\Sdk" \
  ./gradlew.bat :composeApp:desktopTest --console=plain --max-workers=4
```

Do **not** write `sdk.dir` into `local.properties` — it is gitignored and holds the Supabase
configuration. Without `ANDROID_HOME` the build fails at *"SDK location not found"*, which
looks like a configuration error and is not one.

Phase 3 now includes `NetworkQualityPlatform` on all three targets, provider-aware passive
estimates, metered consent, and Instant's bounded failure chain.

**Three traps this session actually hit** — all cost a failed build or a caught near-miss:

1. **`App.kt`, `MetaDetailsScreen.kt`, `PlaybackSettingsPage.kt`, `SettingsSearch.kt` and the
   player/settings files genuinely differ between the two repos. Hand-port them; never `cp`.**
   A straight copy silently reverts the desktop's `AppFeaturePolicy` gating and its
   Tracking-page rename. Files under `features/playback/`, `features/downloads/`,
   `features/debrid/` and `commonTest/` *are* identical and safe to copy.
2. **Desktop builds its settings-search rows with `buildList`/`add(...)` (one argument), not
   `listOfNotNull(...)`.** Porting a row across without adapting produced `add(a, b)` and would
   have broken the desktop build.
3. **The Nuvio theme extension is `com.nuvio.app.core.ui.nuvio`**, not `core.ui.theme.nuvio`.

**Two mistakes worth not repeating:** every new `expect` needs a `desktopMain` actual (run
`desktopTest` — it catches this locally, before CI), and new `strings.xml` keys go in **both**
repos or the other build fails on an unresolved `Res.string`.

---

## Execution ledger — update this as work lands

Keep this table current in the same commit as the code. It is how a cold agent (or a different
model) picks the work up mid-flight without re-deriving anything.

| Phase | State | Notes |
| --- | --- | --- |
| 1 — foundations + Classic parity | **complete** | Landed 2026-08-06 on `claude/desktop-download-queue-bug-vowjy8`. Verified: Android host suite 576 tests, desktop suite 782 tests, both zero failures, and `desktopMain` compiles. Playback behaviour is still unchanged by design — the mode is stored and selectable, but `entry<StreamRoute>` does not read it yet. Not smoke-tested on a device. |
| 2 — picker + Streamlined | **complete** | Router wired; plugin metadata preserved; `releaseGroup`/`seeders` extracted; shared `SourceRanking`; playback selector, quality sheet, sticky pins, persisted tiers and torrent gate landed. Android 585 and desktop 791 tests pass. Not smoke-tested. |
| 3 — Instant + network quality | **complete** | All platform actuals, estimator/cache, metered consent, tier routing, and three-attempt failure chain landed. Android 590 and desktop 796 tests pass. Not smoke-tested. |
| 4 — auto source-swap | **complete** | Precondition check found a real iOS bug (`demuxer-cache-time` read as a duration) and fixed it. Wall-clock trigger, arming conditions, swap constraints and the opt-in setting landed. Android 607 and desktop 813 tests pass. iOS Swift change uncompiled; not smoke-tested. |
| 6 — UX pass, 2026-08-16 | **complete** | Not a numbered phase in the original plan; added after a UX review of the shipped flows. **Instant's route paths are gone** - it is withdrawn by `isSelectable`, so the selection effect, metered dialog, "playing X" toast and `isAutoPickRoute` were unreachable. Do not re-derive them as missing: `AutoPick`, `AutoDownshiftDetector` and `NetworkQualityRepository` are kept as the re-entry point. **The sticky pin was withdrawn** (rule 3 of the precedence table) - reachable only from the escape hatch, invisible once set. **Streamlined's next episode reached parity** with its first: remembered band + a 3-source chain in the player. **`PlaybackQualityTier` removed.** Verified by `run-pure-suites.sh` in both repos (159 tests each); **no Gradle suite ran - no Android SDK on the machine** - and nothing is device-tested. |
| 7 — Instant returns, 2026-08-21 | **complete** | Not a numbered phase either. Instant was withdrawn twice - `0.4.10-beta` withheld the mode, `0.5.0-beta` deleted its route paths - both times because it decided a quality with no honest figure to decide from and no ceiling to hold it. Every one of those reasons was answered by work done for Streamlined since: the windowed throughput rate, the settled-before-shown connection signal, `playback_quality_ceiling_mbps`, absolute bands, the release-tag vocabulary, and the capped failure chain with its naming overlay. **Instant is now Streamlined with the sheet auto-answered** - one effect picks with `stickyAffordable` and hands off through the same `startAutoSelectedPlayback`; there is no second picker, no second chain and no second overlay owner. `streamRouteSurface` regained `isAutoPickRoute` (a route identity, not a second working flag) and `isStreamlinedPlaybackStarting` became `isAutoPlaybackStarting`. The metered ask is back as **Data saver / High quality**. **Auto-downshift is withheld separately** by `AutoDownshiftDetector.AUTO_DOWNSHIFT_AVAILABLE`, because it would otherwise have ridden back in on `isSelectable` having never once run on a device. Verified: pure suites **251** in both repos (up from 235), Android host **946** (up from 937), desktop **1159** (up from 1150), all zero failures. Not device-tested at the time of writing - that is the whole point of the debug line. |
| 5 — download entry point | **complete** | The "Decisions taken" item that no numbered phase had covered. Classic picks the release, Streamlined keeps the preset dialog, Instant starts from the connection tier. Needed a real `downloadIntent` flag through `StreamLaunch` — routing to the source list alone made Download behave as Play. Android 615 and desktop 821 tests pass. Not smoke-tested. |

**Per-file progress (Phase 1) — all complete.** The mode is persisted, selectable on first
launch and in Settings, and defaults to `CLASSIC`. Playback routing does not consult it yet;
that is Phase 2's first step.

| File | State |
| --- | --- |
| `features/playback/PlaybackModeModels.kt` | **done, mirrored** |
| `features/playback/PlaybackModeRouter.kt` | **done, mirrored** |
| `features/player/PlayerSettingsStorage.kt` (expect) | **done, mirrored** |
| ` └ android + ios actuals` | **done, both repos** |
| ` └ desktop actual` (`NuvioZDesktop` only) | **done** |
| `features/player/PlayerSettingsRepository.kt` | **done, both repos** |
| `commonTest/.../PlaybackModeRouterTest.kt` | **done — 11 tests pass** |
| `commonTest/.../PlaybackQualityTierTest.kt` | **done — 11 tests pass** |
| `features/settings/PlaybackSettingsPage.kt` (row + `PlaybackModeDialog`) | **done, both repos** |
| `features/settings/SettingsSearch.kt` | **done, both repos** |
| `values/strings.xml` (9 keys, both repos) | **done** |
| `features/playback/PlaybackModeSelectorScreen.kt` | **done, mirrored** |
| `App.kt` first-launch gate | **done, both repos** (hand-ported) |
| `features/details/MetaDetailsScreen.kt` manual-play override | **done, both repos** (hand-ported) |
| `entry<StreamRoute>` router wiring | **done in Phase 2, both repos** (hand-ported) |

**Per-file progress (Phase 2) — all complete.**

| File | State |
| --- | --- |
| `App.kt` StreamRoute router/Streamlined integration | **done, both repos** (hand-ported) |
| `features/streams/StreamModels.kt` + `StreamFetchSupport.kt` plugin metadata | **done, mirrored** |
| `features/downloads/SourceFacts.kt` release group/seeders | **done, mirrored** |
| `features/downloads/SourceRanking.kt` + preset refactor | **done, mirrored** |
| `features/playback/PlaybackSourceSelector.kt` | **done, mirrored** |
| `features/playback/PlaybackQualitySheet.kt` | **done, mirrored** |
| `features/streams/BingeGroupCacheRepository.kt` sticky-pin widening | **done, mirrored** |
| quality tiers + torrent gate storage/sync | **done, all three actuals** |
| settings implementation captions/search/Classic-only auto-play state | **done, both repos** (hand-ported) |
| Phase 2 common tests | **done — 9 new tests; both full suites pass** |

**Per-file progress (Phase 3) — implementation complete.**

| File | State |
| --- | --- |
| `core/network/NetworkQualityPlatform.kt` + Android/iOS/desktop actuals | **done** |
| `core/network/NetworkQualityRepository.kt` | **done, mirrored** |
| passive download-throughput samples and per-provider cache | **done, mirrored** |
| `App.kt` Instant tier selection + metered consent | **done, both repos** (hand-ported) |
| `StreamsRepository` ranked three-source failure chain | **done, both repos** (hand-ported) |
| player startup/error retry callbacks (8-second budget) | **done, both repos** (hand-ported) |
| profile-scoped `playback_metered_cap_height` + sync | **done, all three actuals** |
| `NetworkQualityRepositoryTest` | **done — 5 cases; both full suites pass** |

The mode is now **reachable and changeable** in Settings → Playback → Player → Playback mode,
and persists. Streamlined and Instant are selectable but carry a
`playback_mode_not_ready` caption ("Not ready yet - plays like Classic for now"), because
selecting a mode that silently behaves like Classic would read as a bug. **Remove that caption
in the phase that implements each mode** — `PlaybackMode.isImplemented()` in
`PlaybackSettingsPage.kt` is the single place to update.

### Two things discovered while building this — read before Phase 2

**The manual-selection escape hatch already existed.** `MetaDetailsScreen` has had a
"Play manually" action in the episode long-press overlay all along
(`PosterZoomOverlayAction`, using the existing `onPlayManually` callback that `App.kt` already
threads through). It was only gated on `StreamAutoPlayPolicy.isEffectivelyEnabled(...)`. So the
escape hatch was a one-condition change — also show it when the mode is not `CLASSIC` — rather
than new long-press plumbing. `onPlayManually` sets `manualSelection = true`, which is
precedence rule 1, so this is already correct for Streamlined and Instant.

**`entry<StreamRoute>` wiring is deliberately deferred to Phase 2.** In Phase 1 every mode
resolves to the source list, so calling `PlaybackModeRouter.decide` there would be a pure
refactor of the single riskiest block in the app (~550 lines carrying reuse-last-link, auto-play
evaluation, debrid resolution and P2P consent) in exchange for zero behaviour. Do it as the
*first* step of Phase 2, when `ShowQualitySheet` has something to show — the router and its
tests are already in place and unchanged.

**Per-file progress (Phase 4) — all complete.**

| File | State |
| --- | --- |
| `iosApp/.../MPVPlayerBridge.swift` buffered-position fix | **done, mirrored — uncompiled** |
| `features/playback/AutoDownshiftDetector.kt` (+ `AutoDownshiftCandidates`) | **done, mirrored** |
| `commonTest/.../AutoDownshiftDetectorTest.kt` | **done — 17 tests pass on both targets** |
| `playback_auto_downshift` + all three actuals + sync | **done** |
| `PlaybackSettingsPage.kt` / `SettingsSearch.kt` rows | **done, both repos** (hand-ported) |
| `PlayerScreenRuntimeState.kt` / `Effects.kt` / `Ui.kt` wiring | **done, both repos** (hand-ported) |
| `PlayerScreenRuntimeSourceActions.kt` trigger | **done, mirrored** |

**Per-file progress (Phase 5) — all complete.**

| File | State |
| --- | --- |
| `features/playback/PlaybackModeDownloadRouter.kt` | **done, mirrored** |
| `commonTest/.../PlaybackModeDownloadRouterTest.kt` | **done — 8 tests pass on both targets** |
| `features/streams/StreamLaunchStore.kt` `downloadIntent` | **done, mirrored** |
| `features/streams/StreamsScreen.kt` `downloadOnSelect` | **done, both repos** (hand-ported) |
| `App.kt` `onDownloadManually` + route wiring | **done, both repos** (hand-ported) |
| `features/details/MetaDetailsScreen.kt` entry-point branch | **done, both repos** (hand-ported) |

**Why Phase 5 existed at all:** "Modes change the download *entry point*, not the download
engine" was recorded under **Decisions taken** but never assigned to a numbered phase, so it
was still unbuilt when Phases 1–4 were finished. If a decision in this plan has no phase, it
does not get built — check that section against the ledger before declaring the plan done.

**The trap it hit:** routing Classic's Download tap to the source list is not enough. That
screen *plays* on tap and only offers download from the long-press sheet, so the download
intent was silently dropped and the button behaved as Play. `StreamLaunch.downloadIntent`
now carries it, `StreamsScreen.downloadOnSelect` makes a tap enqueue, and the flag also
forces `streamManualSelection` so nothing auto-plays under a Download press.

### What Phase 4 settled, for whoever picks this up

**The libmpv precondition failed on iOS and that was the phase's main finding.** iOS read
mpv's `demuxer-cache-time` — an absolute stream timestamp — as a duration and added it to the
position, so its reported buffer grew with playback and never looked starved. Android and the
desktop C++ both treat it as absolute; two implementations against one settles it without a
device. Fixed to match Android. It was already affecting the user-visible buffer readout.

**The trigger is wall-clock, not snapshot counts** — see `STATUS.md` for why "≥3 consecutive
snapshots" could not work across a 250 ms and a 500 ms poll. If you change the thresholds,
change them in `AutoDownshiftDetector`'s constants; the tests assert the two poll rates agree.

**Never identify the playing source by `activeSourceUrl`.** It holds the debrid-*resolved*
URL, which no candidate in `PlayerStreamsRepository.sourceState` carries, and for P2P it holds
a sentinel that matches nothing. Anything comparing the active source against the candidate
list wants `matchesActiveSource` in `PlayerScreenRuntimeSourceActions.kt`, whose last arm
(addon + label + subtitle) is the part that survives `withResolvedDebridUrl`. This is the
defect that would have made auto-swap a silent no-op for exactly the users Instant targets.

**Remaining gaps, both real:** the Swift fix has never been compiled (no macOS host), and
nothing here has been smoke-tested on a device or an installed desktop app. The setting is off
by default, so the blast radius until someone opts in is zero.

### Next actions

1. Compile the iOS app on a macOS host and confirm the buffer readout no longer grows with
   position — this is the one change with no local verification at all.
2. Smoke-test on Android and on the installed Windows app: Instant with the toggle on, against
   a deliberately throttled connection, confirming exactly one swap and a preserved position.
3. The download stream's outstanding item is unchanged: cover a real `NetworkStatusRepository`
   offline/online transition.

**Mirroring reminder:** every finished common file must be `diff -q`'d (add
`--strip-trailing-cr`; the desktop checkout is CRLF) and copied to `NuvioZDesktop`, and every
new `expect` needs a **`desktopMain` actual**. Verify it locally with
`:composeApp:desktopTest` in `NuvioZDesktop` — that compiles `desktopMain`, so it catches a
missing actual before CI does. Mirror `commonTest` too; those cases then run on both targets.

---

## Context

Picking something to watch in Nuvio Z today costs the same number of decisions every single
time: tap the episode, wait for the source list to fill in, read a wall of releases, judge
resolution against size against provider against whether the debrid has it cached, tap one.
Stremio's flow, inherited wholesale. It is powerful and it is exhausting, and it is the main
thing standing between this app and the "press play and it plays" feel of Netflix.

The app already has most of the machinery to do better and doesn't use it in the right places.
There is a good source scorer — but it only serves downloads. There is an auto-play selector on
the playback path — but it picks the *first* stream in list order and does no quality scoring at
all. Binge-group reuse and link caching exist but are opt-in settings buried in a settings page,
not a coherent product idea.

This pass turns that into **three explicit modes the user chooses once**, with a first-launch
selector explaining the trade-off:

| Mode | Taps to play | Who it's for |
| --- | --- | --- |
| **Classic** | episode → source list → source | The fallback, and full control: for when the auto source chooser doesn't do well with your particular plugins or debrid. |
| **Streamlined** | episode → quality → *(auto-picks source)* | Control over quality without reading release names. Optionally pins a release for the rest of a season. |
| **Instant** | episode → *plays* | Simplicity. Quality and source are chosen from your network connection. |

Selector-screen recommendation copy, in the user's own framing — carry it through to the UI:
*Instant for simplicity · Streamlined for control · Classic if the auto source chooser isn't
working well for your preferred plugins/debrid.*

The mode is global with a per-play escape hatch (long-press on mobile, right-click/context menu
on desktop) that always drops into the Classic source list for that one play, plus
**Change source** in the player, so the user is never trapped.

Non-goal: changing the download engine. That stack is mid-reliability-pass (`STATUS.md`,
"NEXT: make a download behave like a Netflix download") and must not be destabilised here.

**Is this a good idea?** Yes — the app already has every hard piece (a good scorer,
position-preserving source switching, debrid cache annotation, binge-group memory) and is only
missing the product layer that connects them. The concentrated risk is not the modes themselves,
it is that **two source-selection mechanisms already run inside `entry<StreamRoute>`** and a
third one added carelessly would produce exactly the finicky behaviour worth fearing. That is
why the precedence table below is normative, and why both it and the router are pure, tested
functions rather than another nested branch in a 550-line composable.

---

## The two questions asked up front, answered

### 1. Is Netflix-style mid-playback quality drop possible?

**Yes, but it is not ABR and it should not ship in phase 1.** The honest name for it is
**auto source-swap**, and the plan schedules it as Phase 4.

A "source" here is a discrete file (a debrid-minted HTTP URL, a torrent, a plugin link), not a
bitrate ladder. Netflix and YouTube swap *segments inside one manifest*; the player does it and
the buffer survives. Swapping here means tearing down the stream, opening a different file, and
seeking to the saved timestamp. That carries costs ABR does not:

- Timestamps do not map across release groups — different intros, edits, framerates. Off by
  seconds to minutes.
- The buffer is discarded, so the user eats a visible 1–3s hiccup *in order to avoid* a stall.
  Only ever worth it for sustained degradation, never one buffer event.
- Debrid links may need re-minting; audio/subtitle track selection resets.

Three things make it tractable, and the plan orders the work so they exist first:

- **The plumbing is already built and already position-preserving.**
  `PlayerScreenRuntimeSourceActions.kt:229` `switchToSource(stream)` captures
  `playbackSnapshot.positionMs`, flushes watch progress, reassigns the `active*` state and sets
  `activeInitialPositionMs` so recomposition re-attaches at the same spot. Automatic downshift is
  a *trigger* on top of a mechanism that already works and will have been hand-tested via the
  Change source affordance shipped in Phase 1.
- **Constrain swaps to the same release group.** Phase 2 adds release-group parsing for sticky
  source anyway. Same-group swaps are the only ones where the timestamp is actually correct.
- **Manifest sources get real ABR for free.** On Android, ExoPlayer with
  `media3-exoplayer-hls`/`-dash` does bitrate adaptation inside an HLS/DASH manifest with no work
  from us. So the story splits: *manifest source → let the player adapt; file source → discrete
  swap, same release group only, sustained degradation only.*

Trigger signal exists already: `PlayerPlaybackSnapshot` exposes `positionMs`,
`bufferedPositionMs` and `isLoading`, so buffer health = `bufferedPositionMs - positionMs`.
Caveat to respect: **desktop polls the snapshot every 500 ms**
(`PlayerEngine.desktop.kt:211-216`), so any trigger must be tolerant of coarse sampling.

**Ships as:** opt-in toggle inside Instant mode, default off until it has real-device evidence.

### 2. Is the auto source selector robust across plugins and debrids?

**Across debrids, yes. Across plugins, no — plugin sources never reach it at all.** Three
concrete defects, all fixable, all in the plan:

- **Plugin streams are structurally excluded from the download picker.**
  `AutomaticDownloadDiscovery` builds `DownloadSourceCandidate` from *installed addons only*.
  Nothing a JS scraper returns is ever a candidate.
- **Plugin metadata is destroyed on the way in.** `PluginRuntimeResult`
  (`features/plugins/PluginModels.kt:68`) carries real structured fields — `quality`, `size`,
  `seeders`, `peers`, `provider`, `language`. `StreamFetchSupport.kt:85`
  `PluginRuntimeResult.toStreamItem()` joins some of them into a display string with `" • "` and
  **drops `seeders` and `peers` entirely**. Downstream, `SourceFactsExtractor` has to regex that
  string back apart. This is the single biggest robustness gap: for a plugin-heavy user, the
  picker is guessing from prose.
- **No seeder signal anywhere.** `StreamItem` has no seeders field, and `SourceFacts` has none.
  For torrent/P2P sources that is the strongest predictor of whether playback will actually
  start. Nothing in the app can score on it today.

What *is* already good and should be reused rather than rewritten: `SourceFactsExtractor`'s
provenance ladder (Nuvio structured → AIO structured → Stremio hint → filename → display text),
`sizesMateriallyConflict`, and `PresetSourceSelector`'s comparator, which correctly ranks
resolution → language → HDR → codec → release quality → **debrid-cached** → direct-URL → size.
Debrid readiness is modelled (`SourceFacts.isDebridReady`, from `aio.debridCached` or
`clientResolve.isCached`) and `LocalDebridAvailabilityService` annotates cache state per
info-hash while the list is still building.

---

## Decisions taken (the two the user left open)

**Playback quality tiers are separate types from download presets, but share one scorer.**
A download preset is a *storage* budget (GB per hour of disk); a playback tier is a *bandwidth*
budget. Conflating them means a user who edits Saver to shrink a season silently degrades their
streaming. So: new `PlaybackQualityTier` type, and the ranking comparator is extracted out of
`PresetSourceSelector` into a shared `SourceRanking` object that both call. One place to fix a
ranking bug, two independent sets of user-facing knobs.

**Modes change the download *entry point*, not the download engine.** Reasoning from who uses
each mode: a Classic user wants to choose the file, so downloading from the detail screen should
offer manual source selection (the fallback path already exists per AGENTS.md, "keep manual
source selection available when automatic selection fails" — this just promotes it to a first
choice). A Streamlined user wants the preset dialog, which is exactly today's behaviour. An
Instant user wants no dialog: pre-select the preset that matches their current connection tier
and start, still honouring the existing `allowMeteredNetwork` checkbox. `DownloadsRepository`,
the queue, the transfer stack and `PresetSourceSelector` are untouched.

---

## What each mode does, precisely

Common to all three: the existing `launchPlaybackWithDownloadPreference` short-circuit stays —
a completed local download always plays directly, in every mode.

### Classic
Today's behaviour, unchanged. `StreamRoute` → `StreamsScreen` → user taps a source.
`StreamAutoPlayMode` and reuse-last-link settings continue to work as they do now, for users who
already tuned them.

### Streamlined
1. Tap episode → a compact **quality sheet** (not the full source list): the configured
   `PlaybackQualityTier`s, each with a one-line summary, plus a **Best available** row.
2. On choice, `PlaybackSourceSelector` scores the candidate set and plays the winner.
3. **Sticky source.** If a pin exists for this series+season and a candidate matches it, that
   candidate wins outright and the quality sheet is skipped entirely — one tap. Pin identity, in
   descending strictness, per the user's choice to include release group:
   `releaseGroup` → `bingeGroup` → `(addonId, providerId/debridService, resolution)`.
   A pin is offered after a manual pick ("Use this release for the rest of the season?") and is
   dropped silently when nothing matches, falling back to normal scoring.
   **Extend `BingeGroupCacheRepository`, do not add a parallel store.** It already is
   "remember the release for this content" — today a `contentId → bingeGroup` string over
   `BingeGroupCacheStorage`. Widen the stored value to a serialized `StickySourcePin` (with
   `bingeGroup` as one field) and keep the existing `save`/`get`/`remove` shape, so
   `streamAutoPlayPreferBingeGroup`/`ReuseBingeGroup` and sticky pins remain one mechanism
   rather than two that can disagree.
4. Source list remains one tap away.

### Instant
1. Tap episode → `NetworkQualityRepository` yields a tier → `PlaybackSourceSelector` → play.
2. **Metered connection: ask before playing** (user's decision). A sheet: *"You're on mobile
   data. Play in HD anyway?"* — confirming plays at the metered cap (720p, configurable);
   a **Play in full quality** action overrides for that play only. The answer is remembered per
   network for the session so it is asked once, not every episode.
3. **Failure chain, first-class — built on the existing mechanism.** Auto-pick will sometimes
   choose a source that is dead, evicted from the debrid, or a "preparing" placeholder. Instant
   must silently try the next ranked candidate, showing "Finding a source…" not an error.
   **Reuse `StreamsRepository.skipAutoPlayStream(stream)`** (`StreamsRepository.kt:767`): it
   already drops the failed candidate, advances `autoPlayStream` to the next of
   `autoPlayCandidates`, and returns whether one remains. The new work is only to seed
   `autoPlayCandidates` from `PlaybackSourceSelector`'s ranking instead of list order, plus a
   retry budget (3 attempts, ~8s each) and the overlay copy. Do **not** introduce a parallel
   `fallbacks` list — that would also raise the unsolved question of how it reaches the player
   through `PlayerLaunch`. Only after the chain is exhausted does it fall back to the Classic
   source list with a reason. This matters more for perceived quality than downshift does, and
   is cheaper.

---

## Precedence — normative, and the highest-risk part of this plan

Two selection mechanisms already run today and the new mode router is a third. Verified live
ordering in `App.kt`: `manualSelection` gates the local-download check (line 1584); the
reuse-last-link effect (line 2525) is gated on `!launch.manualSelection` and fires **before**
auto-play evaluation. So the existing order is
`manualSelection → local download → reuse-last-link → auto-play`.

`PlaybackModeRouter.decide(...)` must implement this, extending it rather than competing with it:

| # | Condition | Wins because |
| --- | --- | --- |
| 1 | `launch.manualSelection == true` | The explicit escape hatch. Always the source list, every mode. |
| 2 | A completed local download exists | Already unconditional today. Do not change. |
| 3 | A sticky pin matches (Streamlined only) | The user's own per-season decision beats a generic estimate. |
| 4 | `streamReuseLastLinkEnabled` and a valid cached link | Existing behaviour, preserved. |
| 5 | Mode router: Classic → source list · Streamlined → quality sheet · Instant → tier → pick | |

**`streamAutoPlayMode` (`MANUAL`/`FIRST_STREAM`/`REGEX_MATCH`) is ignored in Streamlined and
Instant.** It becomes a Classic-only setting; grey it out with an explanatory caption in
`StreamsSettingsPage.kt` when the mode is not Classic. Two pickers scoring the same candidate
set and disagreeing is the specific failure this rule prevents.

Concrete case the table fixes: mode = Streamlined with reuse-last-link on. Without rule 3 the
reuse branch fires first and the quality sheet never appears. With it, a deliberate pin wins and
reuse still serves the un-pinned case.

`PlaybackModeRouterTest` covers every row plus the combinations (pin + reuse both valid;
`manualSelection` with a local download present; Instant with a regex configured).

## Architecture

### New files (all `commonMain`, `features/playback/` unless noted)

| File | Contents |
| --- | --- |
| `PlaybackModeModels.kt` | `enum PlaybackMode { CLASSIC, STREAMLINED, INSTANT }`; `@Serializable data class PlaybackQualityTier(id, name, targetResolution: VideoResolution, megabitsPerSecondCeiling: Double, codecPreference, dynamicRangePolicy, allowTorrentSources: Boolean = false)`; `@Serializable data class StickySourcePin(seriesId, seasonNumber, releaseGroup?, bingeGroup?, addonId?, providerId?, resolution?)`; built-in tiers + `mergeStoredTiers` mirroring `mergeStoredPresets` |
| `PlaybackSourceSelector.kt` | `select(candidates, tier, context): PlaybackSelectionResult` — the streaming picker (below) |
| `PlaybackModeRouter.kt` | Pure implementation of the precedence table above |
| `PlaybackModeRepository.kt` | `StateFlow<PlaybackModeUiState>`; mode, tiers, pins, selector-seen flag, toggles |
| `PlaybackModeSelectorScreen.kt` | The first-launch three-card selector |
| `PlaybackQualitySheet.kt` | Streamlined's quality picker |
| `core/network/NetworkQualityRepository.kt` | `StateFlow<NetworkQualityUiState(connectionType, isMetered, estimatedMbps, confidence)>`; tier resolution |
| `core/network/NetworkQualityPlatform.kt` | **`expect`** — connection type + metered flag |
| `features/downloads/SourceRanking.kt` | Comparator extracted from `PresetSourceSelector`, shared by both pickers |

### New `actual`s — all three are mandatory

`NetworkQualityPlatform` needs actuals in **`androidMain`** (`ConnectivityManager` /
`NetworkCapabilities`: `TRANSPORT_WIFI|CELLULAR|ETHERNET`, `NET_CAPABILITY_NOT_METERED`),
**`iosMain`** (`NWPathMonitor`: `status`, `isExpensive`, `isConstrained`), and
**`desktopMain` in `NuvioZDesktop`** (report `ETHERNET`, unmetered — a desktop is not a phone).

> ⚠ **`desktopMain` has no counterpart in `nuvio-z`.** Android and iOS compile fine without the
> desktop actual and nothing catches it until the Windows CI job. This has broken the desktop
> build twice (AGENTS.md). Same applies to every new `PlayerSettingsStorage` key pair.

### Changed files

**`features/streams/StreamFetchSupport.kt` (~line 85) — the highest-value single change.**
`PluginRuntimeResult.toStreamItem()` must stop discarding structured metadata. Add a
`@Serializable data class PluginStreamMeta(quality, sizeBytes, seeders, peers, provider,
language)` to `StreamModels.kt`, carry it as `StreamItem.pluginMeta`, and populate it here.
Keep building the display string exactly as now so nothing in the UI changes.

**`features/downloads/SourceFacts.kt`** — three additions:
- read `stream.pluginMeta` in `SourceFactsExtractor.extract`, slotting it into the provenance
  ladder just below AIO structured and above filename parsing (new
  `SourceFactProvenance.PLUGIN_STRUCTURED`);
- `SourceFacts.releaseGroup: String?`, parsed from `clientResolve.stream.raw.parsed.group`
  first (it already exists there), then from the filename tail;
- `SourceFacts.seeders: Int?` from `pluginMeta`.

Guard the filename release-group regex against release names that are all-caps words; prefer
the structured `parsed.group` whenever present.

**`features/downloads/PresetDownloads.kt`** — `PresetSourceSelector` keeps its filters, result
type and public signature. Only the comparator body moves to `SourceRanking`. **The existing
`PresetDownloadsTest` cases must pass unchanged** — that is the regression guard for this
refactor.

**`App.kt`, `entry<StreamRoute>` (~2439–2990 mobile / ~2439 desktop)** — the mode branch. This
block already carries reuse-last-link, auto-play evaluation, debrid resolution, P2P consent and
`openSelectedStream`. It is ~550 lines and the highest-risk edit in the plan; **extract the mode
decision into a testable pure function** (`PlaybackModeRouter.decide(...) →
{ ShowSourceList | ShowQualitySheet | PlayDirectly(stream) | AskMetered }`) rather than adding
another nested branch inline.

**`App.kt` `AppGateScreen`** (~500–650) — add a gate value for the mode selector, shown when
`playbackModeSelectorSeen == false`. There is no onboarding anywhere in the app today, so this
is new construction; the profile-selection gate is the pattern to copy.

**`features/details/MetaDetailsScreen.kt`** — long-press (mobile) / right-click + context menu
(desktop) on an episode or the Play button → route with `manualSelection = true`, which
`StreamLaunch` already carries. ⚠ **This file legitimately differs between the two repos** —
hand-port, do not `cp`.

**`features/player/PlayerStreamsRepository.kt`** — a second, ~551-line copy of the fetch logic
that feeds the in-player source panel. Verified: it *does* fetch plugin scrapers
(`PluginRepository.getEnabledScrapersForType`, `toPluginProviderGroups`), so **Change source**
sees the same candidate universe Instant picked from. But it builds its `StreamItem`s through
the same `StreamFetchSupport` path, so the `pluginMeta` fix must be confirmed to flow through
here too — and if `PlaybackSourceSelector` is ever used to order the in-player panel, it wires
in here, not in `StreamsRepository`. ⚠ This file differs between the two repos — hand-port.

**`features/player/PlayerScreenRuntimeSourceActions.kt`** — Phase 1 needs only a **Change
source** entry already served by `openSourcesPanel()` + `switchToSource()`; verify it is
reachable from the player overflow in both apps. Phase 4 adds the automatic trigger here.

**Settings** — `PlayerSettingsStorage.kt` (+ 3 actuals), `PlayerSettingsRepository.kt`
(`PlayerSettingsUiState` fields), `features/settings/PlaybackSettingsPage.kt` (a **Playback
mode** section) and the `SettingsSearch.kt` index. Add every new key to `syncKeys` and to
`exportToSyncPayload`/`replaceFromSyncPayload` or it silently becomes device-local.

### Storage keys

All profile-scoped via `core/storage/ProfileScopedKey.of(...)`, in the existing
`nuvio_player_settings` store:

| Key | Meaning |
| --- | --- |
| `playback_mode` | `CLASSIC` / `STREAMLINED` / `INSTANT` |
| `playback_mode_selector_seen` | **Separate from the mode.** Without it, "chose Classic" is indistinguishable from "never chose", and the selector reappears forever. |
| `playback_quality_tiers` | JSON list of `PlaybackQualityTier` |
| *(sticky pins)* | **No new key** — widen the existing `BingeGroupCacheStorage` value to a serialized `StickySourcePin` |
| `playback_allow_torrent_autopick` | Off by default (below) |
| `playback_metered_cap_height` | Default 720 |
| `playback_auto_downshift` | Phase 4, off by default |

**Rollout:** the selector is shown once to *everyone*, existing installs included, pre-selected
to **Classic** so nothing changes for a user who just dismisses it.

---

## `PlaybackSourceSelector` — how it differs from the download picker

Same ranking, different gates. Four deliberate divergences:

1. **Protocols.** The download picker's `isAutomaticProtocol` rejects everything except plain
   HTTP(S) without `.m3u8`/`.mpd`/`.torrent`. That is correct AGENTS.md download policy and must
   stay. For playback: allow **HTTP(S), debrid-resolved links, and HLS/DASH manifests** — the
   player handles manifests and on Android they give real in-manifest ABR for free.
   **Torrent/magnet sources are excluded by default behind a user toggle**
   ("Allow torrent sources in auto-pick"), because P2P start times are unpredictable and a slow
   start is indistinguishable from a hang. When the toggle is on, torrent candidates rank last
   and only when a seeder count is known and healthy.
2. **Uncached debrid is excluded from ranking, then offered as a fallback.** An uncached request
   returns the provider's "preparing" placeholder video. Never auto-play one. If *nothing* is
   playable, surface a sheet: *"Nothing is cached. Start caching this source?"* rather than an
   error. (The download picker's `ApprovalNeeded` path is the analogous idea; playback needs a
   different UI for it.)
3. **The cap is bandwidth, not disk.** `sizeCapBytes = megabitsPerSecondCeiling / 8 * 1e6 *
   runtimeSeconds`. Use the same `runtimeMinutes ?: 45 (episode) / 120 (movie)` fallback as
   `DownloadPreset.sizeCapBytes`. Set the ceiling at **~60% of estimated throughput** for
   headroom; that headroom is what makes downshift rarely necessary.
4. **Result type** is playback-shaped: `Play(stream) | AskUncached(stream) | NeedsManual(reason)`
   plus an ordered `fallbacks: List<StreamItem>` for the Instant failure chain.

Everything else — resolution ceiling, language/codec/HDR requirements, release-quality score,
cached-before-size tie-break, deterministic final ordering — comes from the shared
`SourceRanking` comparator unchanged.

---

## Network quality estimation

Chosen approach: **passive and cached per-network, with an active probe only when there is no
estimate.** The output is a bucket, not a number — 4–5 tiers — so accuracy requirements are low
and this does not need to be a real speed test.

- **Connection type + metered** come from the platform (`NetworkQualityPlatform`). This alone
  resolves a usable tier and is the only signal needed on first launch.
- **Passive throughput** is the good signal and needs no new subsystem: the download stack
  already measures real bytes/sec on this network, and the player's
  `bufferedPositionMs - positionMs` trend over a known-bitrate file says directly whether that
  bitrate is sustainable. Record "highest bitrate that played without stalling" per network.
- **Active probe** — a bounded ~1.5s ranged GET against a CDN — runs only when a network has no
  cached estimate at all and the connection type is ambiguous.
- **Cache the estimate per network *and* per debrid provider.** On debrid, throughput is the
  *host's*, not the link's. A fast Wi-Fi with a slow provider must not read as "4K is fine" —
  this is the mistake that would make Instant feel worse than Classic.

Indicative mapping (tunable, tiers are user-editable):
`<3 Mbps → 480p · 3–8 → 720p · 8–18 → 1080p · 18–40 → 1080p high · >40 → 2160p`.
Unknown network → 720p, upgraded once the first real measurement lands.

---

## Phases

**Phase 1 — foundations and Classic parity (no behaviour change by default).**
`PlaybackMode` enum, repository, storage + all three actuals, settings UI, the first-launch
selector gate, the long-press / right-click manual override, and a verified **Change source**
path in the player. Ship with everyone on Classic. Nothing user-visible changes unless the mode
is switched.

**Phase 2 — the picker and Streamlined.**
The plugin-metadata fix in `StreamFetchSupport`, `releaseGroup` + `seeders` in `SourceFacts`,
`SourceRanking` extraction (with `PresetDownloadsTest` green), `PlaybackSourceSelector`, the
quality sheet, sticky pins. Streamlined becomes selectable.

**Phase 3 — Instant.**
`NetworkQualityPlatform` actuals, the estimator and its per-network/per-provider cache, the
metered confirm sheet, and the failure chain. Instant becomes selectable.

**Phase 4 — auto source-swap (opt-in, default off).**
**Precondition, verify before building:** that `bufferedPositionMs` is meaningful and monotonic
on **libmpv**, not just ExoPlayer. libmpv is the iOS engine, the desktop engine, and Android's
fallback engine — if it reports a useless buffered position on any of them, the trigger needs a
different signal (rebuffer counting via `isLoading` transitions) on that platform. Do not assume
ExoPlayer's behaviour generalises.
Trigger on sustained buffer starvation only (e.g. buffer health under ~4s across ≥3 consecutive
snapshots, tolerant of desktop's 500 ms polling), never a single rebuffer. Same release group
only. Never swaps *up* mid-playback. Hard cap of one swap per session before it gives up and
leaves the user alone. Manifest sources are exempt — the player already adapts.

---

## Verification

Gradle cannot configure in the agent sandbox (`dl.google.com` blocked); CI is the only compiler
there. See AGENTS.md "Verifying without Gradle".

Phase 2 was verified on the maintainer's Windows machine with forced task reruns: Android host
**585 tests across 85 classes**, and desktop **791 tests across 115 classes**, both with zero
failures, errors, or skips. The desktop run compiled `desktopMain` and ran the complete download
harness. No Android device or installed desktop app was available for runtime smoke testing.

Phase 3 was verified with the prescribed full commands: Android host **590 tests across 86
classes**, and desktop **796 tests across 116 classes**, both with zero failures, errors, or
skips. Desktop main compiled. iOS cannot compile on this Windows host; no Android device or
installed Windows app was available for runtime smoke testing.

- **Unit tests, `commonTest`** (these run in CI and are the main safety net, since all the new
  decision logic is deliberately pure):
  - `PlaybackSourceSelectorTest` — protocol gates, uncached exclusion, torrent toggle both ways,
    bandwidth cap arithmetic, fallback ordering.
  - `SourceRankingTest` — the extracted comparator, ranking unchanged.
  - **`PresetDownloadsTest` must pass unmodified** — the regression guard for the extraction.
  - `PlaybackModeRouterTest` — the `entry<StreamRoute>` decision function per mode, including
    `manualSelection`, an existing local download, and a matching sticky pin.
  - `StickySourcePinTest` — release-group match, bingeGroup fallback, no-match falls through.
  - `PluginStreamMetaTest` — a `PluginRuntimeResult` with seeders/size/quality survives into
    `SourceFacts` (this is the plugin-robustness fix; assert it end to end).
- **Local, without Gradle:** parser-check every changed file
  (`kotlinc -nowarn -d /tmp/out <file>.kt 2>&1 | grep -Ei "error:.*(expecting|unexpected|syntax)"`),
  and compile-and-run the pure-logic files standalone against the **shipped** sources —
  stub the neighbours, never the file under test, and record in `STATUS.md` which were stubbed.
- **Gradle, when available:**
  `.\gradlew.bat :composeApp:testAndroidHostTest --console=plain --max-workers=4` and
  `:androidApp:assembleFullDebug` in `nuvio-z`; `.\gradlew.bat :composeApp:desktopTest` in
  `NuvioZDesktop`.
- **CI:** `ci.yml` on both repos. The Windows MSI job is the only thing that compiles
  `desktopMain` — it is the check that catches a missing desktop `actual`.
- **On-device / on-desktop smoke, per phase** (the app has a documented history of shipping
  unverified — do not repeat it):
  - P1: selector appears once, choice survives force-stop, long-press reaches the source list,
    Change source in the player preserves position.
  - P2: Streamlined on a plugin-heavy profile and on a debrid profile; confirm the pick is
    sensible and that a pinned release carries across episodes of a season.
  - P3: Instant on Wi-Fi, then on mobile data — confirm the metered sheet appears once, and
    kill the chosen source (disable the addon mid-flight) to confirm the failure chain moves on
    silently rather than erroring.
  - Desktop: Linux has **no in-app playback** (`DesktopStubPlayerController`); scope desktop
    verification to Windows.

### Known platform gaps to respect
- Desktop `PlatformPlayerSurface` **does not forward** `streamType`, `externalSubtitles`,
  `sourceAudioUrl` or `useYoutubeChunkedPlayback` (`PlayerEngine.desktop.kt:58`). Do not assume
  HLS type-hinting works there — desktop relies on libmpv sniffing.
- HLS/DASH are explicit on Android via `media3-exoplayer-hls`/`-dash`. On desktop it is whatever
  libmpv does natively; **unverified** — verify before letting Instant prefer manifests there.
- iOS ignores `allowMeteredNetwork` for downloads (hardcoded `allowsCellularAccess = true`), a
  known parity gap in `STATUS.md`. Instant's metered handling is playback-side and unaffected,
  but do not claim iOS metered download parity.

---

## Handoff (required — the user may continue this in Codex)

Per AGENTS.md, `nuvio-z/STATUS.md` is the handoff for **both** repositories and its top table is
the first thing to update in any session.

1. Update the `STATUS.md` table (active branch, unreleased work, next step) **before** touching
   code, and again at the end of each phase.
2. Add a `STATUS.md` section per phase: what landed, what was verified and how, what was stubbed,
   what is explicitly not covered.
3. Add to `AGENTS.md` "Important Areas": `PlaybackSourceSelector.kt`, `SourceRanking.kt`,
   `NetworkQualityPlatform.kt`, `PlaybackModeRepository.kt`. Add a working rule stating that the
   download and playback pickers share `SourceRanking` but must keep separate protocol gates —
   HLS/DASH/torrent stay manual for downloads.
4. Put the updated `STATUS.md` and `AGENTS.md` on `main` before ending any session
   (`git checkout main && git checkout <branch> -- STATUS.md AGENTS.md`), so the next agent finds
   the branch.
5. **Commit docs before any version bump** — a `STATUS.md` commit after the bump fails the
   release validation.
