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

## What a Download Has To Be

**The standard is a Netflix download.** Start it, reorder it, pause it, resume
it, close the app, lose the network, come back tomorrow: it finishes, or it says
plainly why it cannot. Hold every change to the download stack against that.

Concretely, and each of these has been violated at least once:

- **No row that stops moving.** A download is either progressing, waiting for a
  named reason the user can see, or finished. "Stuck at 43%" is a bug even when
  something behind it is still running.
- **No state only a restart can leave.** Every stopped download needs something
  that will start it again - a retry timer, a platform that resumes it, or the
  queue itself. If nothing owns that, the state must not exist.
- **The user never has to know what a debrid link is.** Expiry, re-minting,
  cache misses and provider outages are the app's problem. What reaches the user
  is progress, or a message they can act on.
- **A finished download is the whole file.** Byte counts that match an
  authoritative total, `.part` files that survive, and no placeholder video
  accepted as an episode.

New download work is not done when it compiles and the unit tests pass. It is
done when the desktop harness covers the fault it claims to fix - see item 3 of
"Verifying without Gradle".

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
- The download picker and the playback picker share one ranking comparator
  (`features/downloads/SourceRanking.kt`) but **keep separate protocol gates**.
  HLS, DASH, magnets and torrent files stay manual for downloads; playback may
  auto-pick HLS/DASH, and torrent sources only behind the user's
  `playback_allow_torrent_autopick` toggle.
- **There is one release-tag vocabulary, `core/media/ReleaseTags.kt`, and it is import-free.**
  The app used to carry two parsers that disagreed about the same file: the debrid presentation
  layer drew the badges and read `hdr10+`, `hdr10plus` and `dovi` correctly, while `SourceFacts`
  fed the auto-picker and got all three wrong - an HDR10+ remux ranked as SDR, *below* a plain
  HDR release, under a preference asking for HDR. `SourceFacts` also parsed no audio at all.
  Both now delegate here, so what the badge says and what the picker believes cannot diverge.
  Import-free for the same reason as `core/language/LanguageCodes.kt`: `SourceFacts.kt` and
  `SourceRanking.kt` have to compile outside Gradle for group 1 of `run-pure-suites.sh`.
  ⚠ **Match short tokens with boundaries and long ones without.** `"cam" in "Camelot"` scored a
  Blu-ray as a cam rip; requiring a boundary in front of `remux` would lose every `UHDRemux`.
  Channel layouts are bounded by **digits only** - `DDP5.1` and `AAC2.0` glue the layout to the
  codec, and a letter boundary threw away most of the catalogue.
- **Dynamic range, audio, codec and release quality are one additive `mediaScore`**, not four
  lexicographic keys. As a chain, the first key that discriminated decided the pick and nothing
  below it could speak, so "lossless **and** HDR10" was decided entirely by the HDR key. Two
  asymmetries in it are deliberate: unstated audio scores **mid** while unstated dynamic range
  scores as SDR (release names carry HDR reliably and audio only sometimes, and scoring silence
  at the floor would demote most WEB-DLs for a user who asked for lossless), and `REQUIRE_*`
  **demotes by -100 rather than excluding**, so the source stays in the failure chain - the same
  rule as the language gate being "a partition, never a filter". Downloads still *exclude* on an
  unmet requirement; only the comparator is shared.
  ⚠ **`SourceFacts.dynamicRange` can now contain `SDR` as a positive claim, so
  `isNotEmpty()` no longer means "has HDR".** Use `SourceRanking.claimsHdr`; the emptiness test
  it replaced would have read an SDR-tagged release as satisfying `REQUIRE_HDR`.
- **`dynamicRange`, `audioCodecs` and `audioChannels` combine their sources; they do not walk
  the provenance ladder.** They are sets, and one file routinely states half of one in a
  structured field and the other half in its name - `HDR.DV.HEVC.DTS-HD.MA.Atmos-SGF` is two
  dynamic ranges and four audio codecs. An addon sending `hdr: ["DV"]` and `audio: ["Atmos"]`
  has not contradicted that name, it has under-reported it, so first-non-empty silently dropped
  the rest: with only `Atmos` seen, *Prefer lossless* scored a DTS-HD MA remux 3 instead of 6
  and *Require lossless* demoted it by 100 - a lossless release refused for having no lossless
  track. This is the same argument `isMultiLanguage` makes, and it is why the debrid badge row
  was right about a file the picker was wrong about: `DebridStreamPresentation` has always read
  the structured fields and the release text as one body of evidence. The single-valued facts -
  `codec`, `releaseQuality`, `resolution` - still walk the ladder, because for those a
  structured field really does beat a filename.
- **A source is abandoned for lack of progress, never for lack of a first frame**
  (`features/playback/PlaybackStartupWatchdog.kt`). The rule this replaced waited eight seconds
  and asked `isPlaying`, which is not the same question: a debrid mint, a cold provider or a
  60 GB remux seeking its first keyframe are all healthy at eight seconds with a buffer visibly
  filling, and nothing in that check could see a buffer. ⚠ **It is armed by
  `onFatalPlaybackError`, so it only ever ran against Streamlined and Instant** - the same file
  tapped by hand in Classic had no deadline at all - which is why a player fault was reported as
  a mode fault: the two modes whose promise is "you do not have to choose" were the only ones
  that threw good sources away, one per candidate, and then said *"No safe automatic source
  matched"* about a catalogue that was fine. Three clocks now, and the ordering between them is
  the argument: nothing at all gets `NO_PROGRESS_DEADLINE_MS`, a source that advanced and then
  stopped gets the shorter `STALL_DEADLINE_MS` from its last advance (it has already proved it
  can reach the host, so silence from it is evidence), and `MAX_STARTUP_MS` ends the one that
  creeps forever - without that, "measure progress instead" trades a false positive for a hang.
  A longer wait is affordable **only because `shouldOfferManualEscape` exists**: the source list
  is one tap away after 5 s, so the cost is a wait somebody can walk out of rather than the
  source itself. Do not shorten these to "feel faster" without moving that escape hatch first.
- **Every path that ends a play has to say why, in a log and in the overlay.** The startup
  watchdog did neither - no log line, and `noteSourceFailure(reason = null)` - so a chain burning
  three healthy sources looked exactly like three dead ones from outside a device, and the fault
  survived three releases on that alone. It is the same rule `NetworkStrengthProbe` carries for
  the same reason. The reason travels on `StreamsRepository.noteAutoPickFailureReason`, not on
  `onFatalPlaybackError`'s signature: that callback is threaded from `App.kt` through
  `PlayerScreen`, `PlayerScreenArgs` and two runtime files on three platforms, and the value is
  wanted in one of them. `adb logcat -s PlaybackStartup` is the whole diagnosis.
- ⚠ **`consumeAutoPlay` is called more than once per play, so retiring is not a reset.**
  `onPlaybackStarted` fires on every not-playing → playing transition - a pause and resume, or a
  rebuffer the engine reports as a stop and a start - and the second call read an `autoPlayStream`
  the first had already cleared and retained **null** over the real chain. A source that then died
  had `failOverAfterPlaybackStarted` answer false with two ranked candidates in hand: no failover,
  and "No safe automatic source matched" over a chain that was never spent. It only writes the
  retained chain when there is one to write. `AutoPlayFailoverTest` therefore tears down with an
  **empty `seedAutoPlayCandidates`** - that call is what retires a chain for good - because a bare
  `consumeAutoPlay` no longer doubles as one and a case that left a chain retained would hand it
  to the next.
- A player property read on more than one engine must mean the same thing on each. mpv's
  `demuxer-cache-time` is an **absolute** stream timestamp, not a duration ahead of the
  position; iOS shipped it as a duration and its buffer readout grew with playback until
  2026-08-07. When the three engines disagree, two agreeing implementations settle it - a
  device is not required.
- Anything that samples `PlayerPlaybackSnapshot` over time must be expressed in **wall-clock
  duration, not snapshot counts**. Android polls every ~250 ms and desktop every 500 ms, so a
  count-based threshold silently means two different things.
- The playback mode selects the download **entry point** only
  (`features/playback/PlaybackModeDownloadRouter.kt`). It must never reach
  `DownloadsRepository`, the queue, the transfer stack or `PresetSourceSelector`.
- A launch into `StreamsScreen` carries why it was opened. `StreamLaunch.downloadIntent`
  makes a tap enqueue rather than play and forces manual selection; without it a Download
  button that routes to the source list silently behaves as Play.
- Every `replaceFromSyncPayload` must clear only the keys the payload carries, through
  `syncKeysToClear` in `core/sync/SyncPreferenceJson.kt`. Clearing all of `syncKeys`
  first deletes anything added since the remote blob was last written - it wiped the
  playback settings in `0.4.0-beta` and would have wiped stored debrid API keys the
  next time a provider was added.
- ⚠ **Anything drawn OVER the app rather than in place of it must consume pointer input**, with
  `nuvioConsumePointerEvents()` in `core/ui/Components.kt`. A `background()` does not: without it
  every tap that misses a control lands on whatever is underneath, and the user cannot see what
  they hit. **This has shipped twice** - the stream route's hand-off surface left an invisible
  source list fully tappable in `0.5.0-beta`, and the setup wizard's "Run setup again" was
  opening links on the settings page behind it in revision 6. Both were found on a device,
  because nothing else can find them. A `Dialog` is immune by construction; a full-screen
  sibling `Box` is not. The related `nuvioBlockPointerEvents()` makes a subtree inert instead
  (Initial pass), which is what a crossfade's outgoing half needs - it stays laid out and
  hit-testable for the whole animation.
- **A `replaceFromSyncPayload` bypasses every guard a repository puts on a setter**, because it
  writes through the store directly. A value the repository refuses to lower must be merged
  through `mergeMonotonicSyncInt` in the same file - reading the local value **before** the
  clear - or a stale remote blob drags it backwards on every pull. That is what re-gated the app
  with the first-launch setup wizard on every single launch, and it can never self-correct: the
  gated screen is the one that would have pushed the newer value. Any monotonic sync key added
  later needs the same treatment, in **every** actual (three here, four in `NuvioZDesktop`).
- Instant and Streamlined must never leave the user reading the source list while the
  app is still deciding. `PlaybackProgressOverlay` **covers** `StreamsScreen` rather
  than replacing it - that screen owns the fetch the overlay reports on - and every
  path that needs an answer from the user uncovers it again.
- **What `entry<StreamRoute>` shows is decided in one place, `streamRouteSurface`**
  (`features/playback/StreamRouteSurface.kt`), and that file has no imports so it can
  be run outside Gradle. Do not add a fifth thing to that Box with its own inline
  condition: four separate dead ends reached "covered screen, nothing behind it" that
  way, and the worst of them was a blank screen with a fully tappable source list
  underneath. A new terminal state is a new case in that function and its test.
  ⚠ **`isAutoPickRoute` is a route *identity*, not a second "the automatic path is working"
  flag.** The note left when it was deleted warned against two of the latter, and it was
  right: there is still exactly one, `isAutoPlaybackStarting`, serving both modes. Instant's
  rule sits **above** `awaitingUserAnswer` on purpose, so its metered question is asked over
  the progress overlay rather than over the Classic source list the mode exists to avoid -
  dismissing that dialog answers Data saver and the play continues, so there is nothing for
  an uncovered list to be the fallback for.
- A settings row hidden by `LocalShowAdvancedSettings` is still indexed by
  `SettingsSearch` and is revealed on the page the search lands on. Hiding a setting
  the user searched for by name is worse than showing it.
- What's New keeps the current version's notes curated in `CurrentReleaseNotes` and
  fetches only older releases. **Add an entry per release before the version bump** -
  a docs commit after the bump fails release validation. Never gate the screen on the
  in-app updater; it has to work offline.
- **The setup wizard writes every choice immediately, through the real repository setter**
  (`features/setup/`). That is what lets `SetupPreviewStage` render the shipped
  `HomeHeroSection` / `HomeContinueWatchingSection` / `HomeCatalogRowSection` / `DetailHero`
  against real state instead of a mock. Do not add preview-only state or override parameters:
  a second rendering path is a preview that can lie. The stage composes at a fixed logical
  390x620 dp and scales to fit, because those composables branch their metrics on their
  container's width.
- `features/setup/SetupPreviewStage.kt` is **not** byte-identical across the repositories and
  must never be `cp`'d - desktop's `HomeContinueWatchingSection` takes a required
  `dataSourceKey`. Use **named arguments** at every call into a diverged composable; desktop's
  `HomeHeroSection` and `DetailHero` have both gained parameters mid-list.
- Source selection inside `entry<StreamRoute>` follows one precedence order:
  `manualSelection` > completed local download > reuse-last-link > playback mode.
  `streamAutoPlayMode` applies to Classic only - two pickers scoring the same
  candidates must never both run. A **sticky pin** rule sat between the download and
  reuse-last-link until `0.5.0-beta`; it was withdrawn because the pin could only be
  created from the long-press escape hatch and, once created, silently stopped the
  quality sheet appearing with nothing in the UI to say why or to clear it.
  `StickySourcePin` and `StickySourcePinTest` are kept for a surfaced version -
  re-adding it means re-adding a row to `PlaybackModeRouterTest`, not just a branch.
- **Streamlined's next episode must behave like its first.** The stream route's failure
  chain lives in `StreamsRepository` and is armed through
  `PlayerLaunch.autoPickedWithFailureChain`; neither reaches an auto-played next
  episode, because that path calls `switchToEpisodeStream` and swaps source *inside* the
  running player with no relaunch. Its chain is `PlayerScreenRuntime.nextEpisodeFallbacks`,
  consumed by `tryNextEpisodeFallback()` before either fatal-error path gives up. Never
  route it through `seedAutoPlayCandidates` - that store is owned by `StreamRoute`, which
  is not on the back stack in this flow.
- **Streamlined's quality bands are absolute, not relative** (`bandBoundariesMbps` in
  `PlaybackQualityOptions.kt`). They were the bucket's own bitrate spread cut into thirds until
  `0.5.0-beta`, which made every label a statement about one title's catalogue and about nothing
  else - "4K High" was an 88 GB remux on one title and a 14 GB WEB-DL on the next. Two consequences
  for anyone moving the boundaries: the **collapse guard is load-bearing now** (fixed boundaries do
  not guarantee the extreme bands are occupied, so a bucket can land entirely in one and must
  produce a single unlabelled row), and a source with no credible size must be banded **out** and
  appended to the cheapest occupied band, never treated as 0.0 - that mints a "Low" row quoting a
  nominal bitrate for a file nobody knows the size of.
- **A demoted resolution is what the whole picker sees, not just the row it lands on**
  (`PlaybackQualityOptions.build`). `bucketFor` had always moved a source whose bitrate is far
  below its claimed floor onto the row its bitrate supports - but it left `facts.resolution`
  alone, so `SourceRanking`'s leading key still read the claim. Reported: `8K · HDR · 18 GB ·
  Needs 33 Mb/s` - **24 Mb/s, a 1080p-grade bitrate** - at the head of **Best available**, above a
  genuine 61 GB 4K remux, with the caption still saying 8K. It is decided once now, as
  `effectiveResolution`, and `MeasuredCandidate` stores a `facts.copy(resolution = …)` candidate,
  so ranking, captions and labels agree without a second vocabulary. Three rules hold it:
  **demote only** (`aLargeReleaseIsNeverPromotedAboveWhatItClaims`); ⚠ **rewrite a stated claim
  only** - `effectiveResolution` also *infers* one for a source that stated none, capped at 1080p,
  and writing that back would **promote** it (an unstated resolution sorts at the bottom of
  `SourceRanking`, so 1080p lifts it over labelled 720p) *and* tighten `bitrateCeilingMbps` under
  `requiredMbpsFor` until an 80 Mb/s unlabelled source headed a row quoting no bandwidth and
  drawing no meter; and **plausibility is judged against the claim**, never the demotion.
  `bitrateFloorMbps(UHD_4320)` is **40.0**, which is `nominalBitrateMbps` for the same resolution -
  a file cheaper than what this app's own tables say 8K costs has no claim to the word. It was
  8.0, which admitted anything above a good 720p encode, so the check was inert for the one
  resolution nothing reaches by accident. Do not tighten the other rows: 2160's 3.0 is deliberately
  low, and raising it would demote efficient AV1 4K encodes.
  ⚠ **The download path is deliberately not included.** `PresetSourceSelector`'s `targetResolution`
  test is a **ceiling**, so an inflated label already excludes the file from every shipped preset;
  demoting it there would flip an exclusion into an *inclusion* on a path that spends storage and
  metered allowance, and `SourceSelectionResult.Selected.facts` is `@Serializable` and persisted
  with the download record. If it is ever wanted, the shape is "send it to **approval**", not
  "rewrite its facts".
- **`SourceFacts.languages` holds normalized codes (`en`, `pt-BR`), never uppercase two-letter.**
  Compare with `languageMatchesPreference`, never with `in`. The tables live in the import-free
  `core/language/LanguageCodes.kt` so `SourceFacts.kt` can still be compiled outside Gradle;
  `features/player/PlayerLanguagePreferences.kt` delegates to them and keeps the localized labels,
  which reach the generated resource bundle. ⚠ **`releaseLanguagesIn` refuses bare two-letter
  codes on purpose** - `IT.Chapter.Two`, `De.Palma` and any group with `LA` in it all look like
  language tags to that scan. Structured fields go to `normalizeLanguageCode` instead, which does
  accept them, because there the value means what it says.
- **The language gate is a partition, never a filter** (`PlaybackSourceSelector.byLanguage`). An
  unwatchable source moves behind every watchable one and stays in the failure chain; deleting it
  would leave a title whose every release is tagged for another market with nothing to play, which
  is the dead end the mode exists to avoid. Only `NAMES_OTHER_ONLY` fails the gate - wrong audio
  *and* no readable subtitles - because the complaint it answers is "no English audio **or** subs".
- **Throughput is measured over the transfer's steady stretch - never as a mean, and never as a
  maximum** (`core/network/ThroughputWindow.kt`).
  A ranged GET's mean carries TCP slow start, and it under-reads *more* the faster the line is,
  because a fast line hits the byte cap while still climbing - a connection shown as 56 Mb/s was
  streaming 81. Excluding TTFB removes the handshake, not the ramp.
  ⚠ **Shipping the window is not the same as the window working, and the first attempt shipped
  three ways of not working.** All three are now covered by tests; do not undo any of them.
  1. **Every `httpMeasureThroughput` actual must feed `ThroughputWindow` and report
     `peakWindowMbps`.** The desktop actual did not - it returned null unconditionally while
     Android and iOS carried the change - so `bestEffortMbps` fell back to the mean on the one
     platform the fault was reported from. A null there must mean "the transfer was too small to
     hold a window", never "this platform does not compute one".
  2. **The byte budget must be able to hold a window at the speeds being measured**, and it is
     sized in bytes against the fastest line worth distinguishing - a time budget cannot do that
     job, because it is the byte cap that binds once the line is fast. 8 MiB is 66.6 Mb, under
     one 750 ms window above ~89 Mb/s, which is why the window silently never closed for exactly
     the users it was written for. Check both stops when moving either: at 32 MiB / 2.5 s the
     byte cap binds above ~107 Mb/s and the clock binds below it.
  3. **The neutral endpoint's body must be larger than the budget *and* smaller than what the
     endpoint will serve.** Both bounds have been broken, one release apart, with the same
     outward symptom each time - a figure that will not update. Under the budget, `?bytes=`
     silently *becomes* the budget: 4 MiB under an 8 MiB cap made every reading a 4 MiB pull no
     matter what `MAX_BYTES` said. Over the endpoint's ceiling, it 403s and nothing is recorded
     at all: `speed.cloudflare.com/__down` serves 96,000,000 and refuses 100,000,000, and a fix
     that asked for 128 MB "for headroom" recorded nothing on every probe. `CDN_ENDPOINT_MAX_BYTES`
     and `theNeutralEndpointIsAskedForABodyItWillActuallyServe...` pin both ends. **Curl the
     endpoint before changing the number** - neither bound is visible from the code.
  4. **A probe that cannot measure has to say so in a log.** "Cannot measure" and "measured badly"
     look identical on screen, so a silent failure is unfalsifiable from the outside; both faults
     above survived a release because of it.
  5. **`probe` waits for an in-flight measurement, it does not refuse.** Callers gate a UI on it
     returning, and an immediate null reads as "measured, found nothing" - which committed the
     quality sheet to the stale figure a millisecond after a re-test while the real measurement
     was still running. Its contract is "when this returns, a measurement has settled"; do not add
     a caller-side `isProbing` guard back, it strands the sheet on "Checking".
  A window is bounded by **bytes as well as time** for the same reason: 750 ms was chosen so one
  late packet could not inflate the figure, which is a statement about bytes, not duration.
  ⚠ **The probe's sample floors guard the mean, never the window or the partition.** A closed
  window already met its own minimums; re-testing it against `MIN_SAMPLE_MS` discarded the
  fast-line samples the window exists to rescue - above ~83 Mb/s the probe threw its own answer
  away and the stale estimate survived, which is what "it won't update" looked like from outside.
  ⚠ **The window's *maximum* was the second half of the same mistake, and it shipped.** The mean
  was refused because slow start contaminates it. A sliding maximum answers that and brings a
  bias of its own in the other direction, because a maximum over positions **hunts** for the most
  flattering window. Two things feed it: Wi-Fi aggregation makes the per-window rate vary by
  ~10%, so the best of eight positions reads well above the typical one; and the readers timestamp
  bytes when `read()` **returns**, not when they arrive, so a descheduled reader lets the kernel
  receive buffer fill and then drains it at memcpy speed into whichever window the maximum is
  looking for. An autotuned 4 MB buffer at 500 Mb/s is ~64 ms of data - +26% inside a 250 ms
  window on its own. Reported: the gauge reading **538 Mb/s on a line Ookla measured at 416**
  multi-stream, 9 ms RTT. A single TCP stream reading 29% *above* a multi-stream figure is
  backwards, and it is not cosmetic - **Instant decides which source to play from this number.**
  The fix keeps the original argument and removes only the selection. `sustainedMbps` skips the
  first eighth of the bytes - a *fraction*, because the ramp's byte cost scales with the
  bandwidth-delay product exactly as a proportional skip does - partitions the rest into eight
  fixed **byte** blocks and reports the **lower median**. A trailing stall carries no bytes and
  therefore joins no block, so it is excluded by construction rather than by being out-voted; a
  mid-transfer stall makes one block slow; a buffer drain makes one block fast; the median
  discards both. The mean is still refused, for the reason it always was.
  **The partition is admitted on exactly the evidence one window needs** - `minWindowBytes` of
  region and `windowMs` of span - so the two statistics see the same transfers and the median is
  simply the better one over them. `peakMbps` **stays, and stays a maximum**, for the two jobs
  that need a running figure (`stopAboveMbps` and the log) and as the fallback above ~939 Mb/s,
  where there is no partition. Its ten tests stand unchanged; do not "simplify" them to the median.
  ⚠ **`bestEffortMbps` is a precedence, not a `maxOrNull`.** It took the larger of the window and
  the mean, which was right while both were *lower bounds* on what the line carried. Once one
  candidate is a maximum with a known upward bias, `max(median, max)` **is** the max and the whole
  change is inert while looking as though it works - regression 1 above, arriving a second time by
  a different route. Order is **sustained -> window -> mean**.
  ⚠ **The probe log prints all three.** `sustained=`, `peak=` and `mean=` on one line. The gap
  between them is the only thing that separates "measured badly upwards" from "measured badly
  downwards" from outside a device, and the line it replaced printed one of them as `window=`,
  which did not even say which statistic it was.
  ⚠ **`NetworkThroughputMeter` is not demand-limited and must keep its blend.** It emits only a new
  maximum or a window in which the buffer drained, and a draining buffer is direct evidence the
  line is the bottleneck. Making `recordMeasuredThroughput` monotonic looks obviously right and
  would discard the one signal that can disprove an over-generous estimate.
- **The over-connection warning requires a measurement and a margin**
  (`PlaybackQualityOptions.connectionFit`). It used to be scored against `defaultMbps`' 50 Mbps
  Wi-Fi guess, so a connection nobody had measured still put a red line under half the catalogue.
  Meters may draw on an unmeasured figure; the verdict has to be earned.
- **A connection figure that is about to be replaced is not shown at all.** While a measurement is
  pending the quality sheet says it is checking and passes a **null** estimate down, so no card
  draws a meter or an over-connection verdict either - withholding only the header still let the
  meters jump when the probe landed. Two things this depends on, both of which were wrong first
  time: the "checking" branch is tested **before** the measured one (a `CACHED` estimate counts as
  measured, so the stored number printed and swapped seconds later), and the signal is
  `NetworkStrengthProbe.plan(inputs) != null` rather than `isProbing`, which only goes true once
  the transfer starts and left a stale-then-fresh flicker before it. It is **not** the older "hide
  until measured" rule, which stripped the meters off a connection that simply could not be
  measured; once the probe settles - landed, failed, or past `PROBE_DEADLINE_MS` - the sheet
  commits to whatever it has and latches it. Nothing else bounds that wait, so the deadline is
  load-bearing.
- **A credential re-mint is a continuation, not a new item.**
  `PlayerScreenRuntimeState.isCredentialRefreshHandoff` suppresses `initialLoadCompleted = false`
  (and the subtitle/source-list clears) for the one `activeSourceUrl` change a re-mint causes.
  Without it the opening overlay runs twice on any debrid start that hits a transient error, which
  is most of them: `hasLikelyExpiringPlaybackCredentials` matches nearly every debrid URL.
- **Never auto-apply a source-list filter that empties the list.** `StreamsScreen`'s preferred-addon
  filter gated on the addon having a *group*, and a group exists for every addon that is asked -
  so filtering to one that answered with nothing drew "No streams found" over a full catalogue.
  Any filter applied automatically must check `streams.isNotEmpty()`, and any empty state that a
  filter caused must offer the way back out of it.
- The quality band the user picks in Streamlined's sheet is remembered for the sitting
  (`BingeGroupCacheRepository.sessionQualityHeight`, keyed by `parentMetaId`) and applied
  by `PlaybackQualityOptions.stickyAffordable`. It is a **tie-break, never a ceiling or a
  floor**: it is dropped the moment the estimate stops carrying it, and it never invents a
  row the episode has no release for. Do not persist it - a stored value outlives the
  decision that produced it.

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
- Language normalization, shared by source selection and player track selection:
  `core/language/LanguageCodes.kt` (**import-free**, covered by group 1 of
  `scripts/run-pure-suites.sh`), with `features/player/PlayerLanguagePreferences.kt` delegating to
  it and keeping the localized labels.
- Connection measurement: `core/network/ThroughputWindow.kt` (**import-free**, group 2),
  `NetworkStrengthProbe.kt`, `NetworkQualityRepository.kt`, `NetworkThroughputMeter.kt`, and the
  `httpMeasureThroughput` actuals in `features/addons/AddonPlatform.*.kt`.
- Playback modes - see `PLAYBACK_MODES_PLAN.md`:
  `features/playback/PlaybackModeModels.kt`, `PlaybackModeRouter.kt`,
  `PlaybackSourceSelector.kt`, `PlaybackQualityOptions.kt`, `StreamRouteSurface.kt`,
  `features/downloads/SourceRanking.kt`, `core/network/NetworkQualityPlatform.kt`,
  `features/playback/AutoDownshiftDetector.kt`,
  `features/playback/PlaybackStartupWatchdog.kt` (**import-free**, group 2 of
  `scripts/run-pure-suites.sh`), consumed by `features/player/PlayerScreenRuntimeEffects.kt`,
  `features/playback/PlaybackProgressOverlay.kt`,
  `features/playback/PlaybackPreferencesDialog.kt`.
  ⚠ **All three modes ship.** `PlaybackMode.isSelectable` is still the *only* availability
  test and `coerceSelectable` is still applied at read time; both are now no-ops, kept
  because they are the withdrawal mechanism and it has been used twice. Automatic downshift
  is withheld **separately**, by `AutoDownshiftDetector.AUTO_DOWNSHIFT_AVAILABLE` - it used
  to ride on `INSTANT.isSelectable` and would otherwise have shipped a never-once-observed
  mid-playback source swap in the same release the mode returned.
  There is no `PlaybackModeRepository.kt` - the mode lives in `PlayerSettingsRepository`.
- **Instant is not a third selection mechanism; it is Streamlined with the quality sheet
  auto-answered by the connection.** One effect in `entry<StreamRoute>` picks an option with
  `PlaybackQualityOptions.stickyAffordable` and hands off through the same
  `startAutoSelectedPlayback` the sheet and the remembered band use, so both modes share one
  picker, one failure chain, one overlay and one set of dead ends. A second ordering scoring
  the same candidates is what got Instant withdrawn the first time. Four rules hold it:
  - **It waits for the connection measurement to settle** (`connectionSettled`, bounded by the
    raced `NetworkStrengthProbe.PROBE_DEADLINE_MS`) before it decides. The sheet merely prints
    that figure; Instant *decides* on it, so choosing early means choosing from `defaultMbps`'
    unmeasured 50 Mbps Wi-Fi guess - which is the sentence the mode was withdrawn under.
  - ⚠ **`instantSelectionHandled` is latched for the life of the route and must never be
    reset.** It guards the seed, so clearing it on a retry re-seeds the chain back to
    candidate 1 and the same failure loops forever instead of advancing.
  - **The toast and the remembered resolution are written where the source *opens*, not where
    Instant chooses.** The chain can advance past a dead candidate to a different resolution;
    recording the intent would remember something that never played, and the next episode
    would then prefer a resolution that had just failed.
  - **Instant passes `rememberBand = false`.** The band id is Streamlined's record of a
    question the user answered and is what makes the route skip the sheet; Instant asked
    nothing, so it records only the height, which `stickyAffordable` reads as a tie-break.
- First-launch setup wizard: `features/setup/` - `SetupWizardSteps.kt` (the ordering and the
  show-once revision rule) and `SetupModeStoryboard.kt` (what each playback mode's animation
  claims) are **import-free** and covered by `scripts/run-pure-suites.sh`; everything else there
  is Compose and is CI-only. Keep both import-free - the wizard is a Compose gate no test in
  either repository can reach once it is on screen, so anything decided outside them is decided
  nowhere a test can see.
- Debrid stream presentation: `features/debrid/DebridStreamPresentation.kt`,
  `DebridStreamFormatter.kt`, `DebridSettings.kt`, `DebridProvider.kt`, covered by group 5 of
  `scripts/run-pure-suites.sh`.
  ⚠ **The filter/sort/cap/template pipeline no longer implies a connected account.** Its gate is
  `DebridSettings.appliesStreamPresentation` (from `streamPreferenceScope`), *not*
  `canResolvePlayableLinks` - a user whose debrid runs inside the addon has no provider of their
  own and their preferences must still apply. Keep `canResolvePlayableLinks` for anything that
  actually calls a provider: `DirectDebridResolver`, `DirectDebridStreamPreparer`,
  `LocalDebridAvailabilityService`, `PlayerNextEpisodeAutoPlay`, the `isSelectableForPlayback`
  sites and the Link preparation settings section.
  ⚠ **Services an addon names are not registered providers.** `DebridProviders.registered` feeds
  `all()` → `syncKeys()` in all five storage actuals; adding AllDebrid and friends there would
  write dead API-key entries on every platform. They live in the display-only alias map.
  ⚠ **The default name template renames anything with a known service.** Whether a stream is
  renamed is decided per stream, not per group - widen `isPresentableStream` and every plain addon
  row would read "1080p Cloud Instant".
- Release-tag vocabulary: `core/media/ReleaseTags.kt` (**import-free**, group 1 of
  `scripts/run-pure-suites.sh`), read by `features/downloads/SourceFacts.kt` and by
  `features/debrid/DebridStreamPresentation.kt`.
- Settings sync rules: `core/sync/SyncPreferenceJson.kt` (`syncKeysToClear`,
  `mergeMonotonicSyncInt`), covered by the pure suites
- Advanced settings gating: `features/settings/SettingsComponents.kt`
  (`LocalShowAdvancedSettings`), `features/player/AdvancedSettingsDefault.kt`
- Settings page layout: `PlaybackSettingsPage.kt` (Player, Source Preferences, Audio, Skip
  Segments, Next Episode), `SubtitlesSettingsPage.kt`, and `AdvancedSettingsPage.kt`, which
  received Decoder, the iOS output sections, P2P, Stream Selection and Stream Auto-Play in
  `0.5.0-beta`. Three rules for anything moved between them:
  - **The moved rows read their state in place**, through `PlayerSettingsRepository.uiState`,
    rather than being threaded down. `SettingsScreen.kt` differs by 602 lines between the
    repositories, so a value parameter is a hand-port on both sides for every row.
  - **`SettingsSearch.kt` must be repointed in the same change.** A row indexed against its old
    page is the silent failure here: search still finds it and then navigates somewhere that
    does not contain it. Rows carry `sectionOverride` / `pageOverride` for groups that ended up
    split across two pages.
  - **The Advanced nav row is no longer `isAdvanced`.** Playback Engine lives on that page now
    and it is the main lever for fixing broken playback; gating it behind "Show advanced
    settings" would hide it from exactly the users who need it. The per-row gates inside the
    page stay.
  - Row *placement* is not covered by any test in either repository - `AdvancedSettingsDefault`
    keys on stored values, not on where a row is drawn - so a move is only verified on screen.
- What's New and release notes: `features/whatsnew/`,
  `features/updater/AppUpdater.kt` (`fetchRecentReleaseNotes`)

## Build and Verification

**Gradle does work on the maintainer's own Windows machine**, even though it cannot
configure in the Claude/Codex sandbox. It needs two environment variables, because
`JAVA_HOME` is unset there and `local.properties` is ignored and may carry no
`sdk.dir`:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
ANDROID_HOME="C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk" \
  ./gradlew.bat :composeApp:testAndroidHostTest --console=plain --max-workers=4
```

The same two variables run the desktop suite in `NuvioZDesktop`:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
ANDROID_HOME="C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk" \
  ./gradlew.bat :composeApp:desktopTest --console=plain --max-workers=4
```

**That compiles `desktopMain`**, so on the real machine `desktop-release.yml
mode=build-only` is no longer the only way to catch a missing desktop `actual` - it is
just the only way in CI. Run `desktopTest` locally after touching any `expect`.

**The paths above are not the only ones that work, and neither variable is mandatory.** As of
2026-08-18 this machine has no Android Studio JBR: a JDK 21 on `PATH` serves, and the SDK is at
`A:\AndroidSDK`, so `ANDROID_HOME="A:\\AndroidSDK"` alone runs both suites. Check what is actually
installed before concluding there is no SDK - two sessions recorded "no Android SDK on this machine"
while one was present under a different root.

⚠ **`nuvio-z` may have no `local.properties` at all.** `:composeApp:generateRuntimeConfigs` declares
it as a task *input*, so configuration fails with *"An input file was expected to be present but it
doesn't exist"* - which reads like a missing SDK and is not one. `composeApp/build.gradle.kts:52`
already handles the file being absent at execution time, so an **empty placeholder file** is enough
to run the suites; delete it afterwards and never put invented values in it.

Set both per-invocation rather than writing `sdk.dir` into `local.properties` - that
file is ignored, carries the Supabase configuration, and must not be edited casually.
Without `ANDROID_HOME` the build fails at *"SDK location not found"* during task
dependency resolution, which reads like a configuration failure but is not one. A
first run takes 3-4 minutes; later runs are much faster. Prefer this over the sandbox
workarounds below whenever the real machine is available - it runs the actual suite
instead of a hand-assembled subset.

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
| `nuvio-z` | `debug-release.yml` | `workflow_dispatch` | Publishes a debug APK as a `debug-v*` prerelease. |
| `NuvioZDesktop` | `desktop-release.yml` | `workflow_dispatch` | `mode`: `build-only` / `dry-run` / `draft` / `publish`, `target`: `windows` |
| `NuvioZDesktop` | `desktop-debug-release.yml` | `workflow_dispatch` | Publishes a debug MSI as a `debug-v*` prerelease. |

Both debug workflows refuse to run if their tag already exists. Bump the counter
instead - `DEBUG_BUILD` in `iosApp/Configuration/DebugVersion.xcconfig` for mobile, and
in `composeApp/Configuration/DesktopDebugVersion.properties` for desktop.

⚠ **Publish debug builds *before* a release bump, never after.** `Validate release
state` rejects any file changed between the bump and the release commit except the
release workflows and the two release scripts, and the debug counter is not on that
list. This is the same trap as a `STATUS.md` commit after the bump.

`desktop-release.yml` with `mode=build-only`, `target=windows` compiles `desktopMain`.
Run it before any desktop release - but it is **not** the only thing that does:
`ci.yml`'s Windows MSI job compiles it on every push to `NuvioZDesktop`, and that is
what caught the nullable-`response.body` skew in `0.4.13-beta`. Keep running
build-only before a release; the every-push net is better than this line used to claim.

### Release procedure

### Versioning

**From `0.4.0-beta` (2026-08-07) the two apps share one version name.** Before that
they ran independent lines inherited from upstream Nuvio - mobile had reached
`0.3.10` and desktop `0.1.23-alpha`, which meant nothing to each other. A single
number means "Nuvio Z 0.4.0-beta" is the same product on both platforms.

Rules:

- `MARKETING_VERSION` (nuvio-z) and `VERSION_NAME` (NuvioZDesktop) are **always
  equal**. Bump both, in the same release.
- The internal codes stay independent and **only ever increase**.
  `CURRENT_PROJECT_VERSION` *is* the Android `versionCode`
  (`androidApp/build.gradle.kts`); lowering it means existing installs can never
  update again. It does not need to match the desktop's `VERSION_CODE`.
- Stay pre-1.0 until the app has earned it. `1.0.0` should mean device-verified,
  not just green tests.
- A `-beta` suffix is safe for the in-app updater: `parseVersionParts` reads the
  leading digits of each dot-separated token, so `0.4.0-beta` compares as
  `[0, 4, 0]`, `releaseChannelBranch` is `null` so channel matching always passes,
  and `android-release.yml` never passes `--prerelease`. Re-check those three
  before adopting any new suffix.
- `NuvioZDesktop/iosApp/Configuration/Version.xcconfig` is **not read by anything**.
  The desktop release uses `DesktopVersion.properties`. Ignore that file; do not
  treat it as a version source.

### Release mechanics

Versions live in files, not tags. The workflow derives the tag from the file and
refuses to run if the state is wrong.

| Repository | Version file | Keys |
| --- | --- | --- |
| `nuvio-z` | `iosApp/Configuration/Version.xcconfig` | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION` |
| `nuvio-z` | `iosApp/Configuration/DebugVersion.xcconfig` | `DEBUG_BUILD` |
| `NuvioZDesktop` | `composeApp/Configuration/DesktopVersion.properties` | `VERSION_NAME`, `VERSION_CODE` |
| `NuvioZDesktop` | `composeApp/Configuration/DesktopDebugVersion.properties` | `DEBUG_BUILD` |

⚠ **Both debug counters live in their own file, and neither may move back.**
`release-metadata.sh` walks the commits that touch the *version file* newest-first and
takes the first one whose `MARKETING_VERSION` **differs** from the newest as
`previous_bump`. Same-version commits are skipped, so a debug commit is invisible while
the version has not moved - and then the release bump changes it, every prior
`0.4.14-beta` commit differs, and the newest of them wins. Notes run
`previous_bump..current_bump`, so **the newest debug commit becomes the start of the
next release's notes**.

Mobile only got its own file on 2026-08-20 (`iosApp/Configuration/DebugVersion.xcconfig`),
and moving it does **not** repair what already happened: `chore: debug build 15` is the
newest `Version.xcconfig` touch before `0.5.0-beta`, so that release's generated body
starts there and omits everything before it, including `5058a313` - the whole Streamlined
pass. Only rewriting history could undo it. **Curate `0.5.0-beta`'s notes by hand** and
check the generated range before publishing; `CurrentReleaseNotes` is curated anyway,
which is what makes this survivable rather than fatal.

`NuvioZDesktop` also carries `iosApp/Configuration/Version.xcconfig` as the
*base/mobile* version; the desktop release does **not** read it. Use
`./scripts/set-version.sh --desktop <version> --desktop-code <code>` there rather
than editing by hand (`--show` prints both, plus the debug channel's next tag).
`--desktop-debug <n>` moves the debug counter.

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

   ⚠ **A single-file parse resolves no references, so it cannot see a name that
   is gone.** A rewrite that deletes a private composable and leaves its call
   site behind passes this cleanly - that has happened. After rewriting or
   heavily editing a file, `grep` for each helper it calls and each argument
   name at every call site into another file; the parser check is *necessary,
   never sufficient*, and this is the specific gap.

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

   ⚠ **A stub can also drift without the compile noticing, which is worse than a
   loud failure.** `PlaybackModeStub.kt` carried its own `isSelectable`, hardcoded to
   `this != INSTANT`; nothing in group 1 called it, so the compile stayed green and
   the suite would have gone on asserting a rule the app had stopped following.
   **Prefer compiling the shipped source over stubbing anything that carries a
   decision**, even at the cost of a compiler plugin - group 1 gained `-Xplugin`
   and the JSON runtime purely so `PlaybackModeModels.kt` could be the real file.

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
   seconds; leave the shipped defaults alone outside a harness.
   `DownloadsRepository.resolvePlayableStream` stands in for the debrid provider so
   the re-minting path is reachable without an account. Set
   `NUVIO_DOWNLOAD_TEST_URLS` to real media URLs to run the same queue against a real
   host at the real deadlines.

   **Extend it rather than reasoning about the queue.** What it covers today is
   listed in `STATUS.md`; the queue controls under load, the ways a provider fails,
   and a real cached-on-the-provider check are the named next steps there. A fault
   reproduced here is worth more than a fix argued for in a commit message.

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
