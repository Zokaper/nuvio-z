# Nuvio Z Status

Last updated: 2026-08-22

| | |
| --- | --- |
| Active branch | `claude/setup-wizard-final-pass-wy7csp` in **both** repositories |
| Version in the files | `0.4.14-beta` (mobile `CURRENT_PROJECT_VERSION=124`, desktop `VERSION_CODE=38`) |
| Unreleased on the branch | the debrid stream-preference scope work (2026-08-18), the Streamlined refinement, the connection-gauge fix **and its over-read follow-up**, the **fake-8K demotion**, the settings reorganisation + audio/HDR-aware source preferences, **Instant brought back**, the **startup-watchdog fix for the reported retry loop**, and the **nine fixes from the 0.5.0-beta review pass** - all below. **Pushed to the branch in both repositories; no release tag** |
| Next version | the work on this branch is `0.5.0-beta` material; bump as the **final** commit, after the docs |
| Verified | Android host **975**, pure suites **284** in both repositories, zero failures. `desktopMain` compiles - build-only and the desktop debug release both ran green. The desktop suite has not been re-run since **1181** |
| **Not** verified | the **nine review-pass fixes have not been seen on a device** - see that section's own verification note for the three device checks; **Instant has never been watched running**, which is the entire reason it was withheld and the reason to test the debug line before the release; **nothing in the settings reorganisation has been seen on a screen**, and no test in either repository can see where a settings row is drawn; the Streamlined refinement and both gauge passes are still undevice-tested - **the 538 → ~416 correction has not been seen on the handset that reported it**; **the retry loop was diagnosed by reading and has not been watched not-happening** - the confirming check is the `PlaybackStartup` log line, below; iOS is not compiled |
| Debug channel | desktop `debug-v0.4.14-beta.15`, mobile `debug-v0.4.14-beta.22` - both 2026-08-22, **carrying the nine review-pass fixes**. ⚠ Desktop `.14` published before the ninth fix and is superseded; mobile `.21` is the last one that got out before this pass. This is the line the Instant device script below is to be run on. Mobile's `DEBUG_BUILD` lives in `iosApp/Configuration/DebugVersion.xcconfig` |

## The 0.5.0-beta review pass: nine defects, seven of them in both apps (2026-08-22, unreleased, both repositories)

Two `/code-review high` runs against the release candidate - `origin/main...HEAD` in `nuvio-z`,
`origin/Dev...HEAD` in `NuvioZDesktop` - found eight distinct defects, and fixing the first one
turned up a ninth. Seven are in files the two repositories share, so they are one fix applied
twice. The through-line is that **each of this
release's headline features has one path that does the opposite of what its own doc comment
says**, which is why reading the comments was not enough to find them.

### `isMultiLanguage` counted audio codecs as audio languages

`nuvioParsed.audio` and `aio.parsedFile.audio` are **codec** lists - they sit beside separate
`languages` and `channels` fields, and `audioCodecs` one field above already reads them as
`ReleaseTags.audioCodecs`. Reading them a second time as language evidence meant the entirely
ordinary `audio: ["DTS-HD MA", "Atmos"], languages: ["hi"]` claimed to be multi-language.

That defeats the gate end to end: `languageScore` short-circuits on `isMultiLanguage` to
`UNDECLARED` instead of `NAMES_OTHER_ONLY`, `isLanguageWatchable` returns true, and
`PlaybackSourceSelector.byLanguage` leaves the release in the watchable partition. A Hindi-only
file auto-played to somebody who asked for English - **the exact failure the language gate was
written to stop**, silently, on the most common shape of addon response there is.

### ...and the same codecs were being written into `languages` itself

**Found by CI, from the test written for the fix above**, whose
`assertEquals(setOf("hi"), languages)` failed. `normalizeLanguages` folded `parsed.audio` into
the language values too - and `normalizeLanguageCode` passes anything it does not recognise
straight through, so those two codec names did not merely fail to add a language: they went
*into* `SourceFacts.languages`.

That is the set `languageScore` matches a preference against, so it broke the gate **in both
directions**. A release declaring no language at all came out declaring two that match nothing,
and `NAMES_OTHER_ONLY` demoted a perfectly good English WEB-DL for carrying an Atmos track. The
review found the `isMultiLanguage` half; this half was one function down the same file.

### The startup watchdog declared a resume seek to be playback

`PlaybackStartupSample.progressMs` was the absolute furthest point reached, with no baseline
subtracted. Resuming episode 3 at 22 minutes therefore read 1,320,000 ms of progress on the very
first sample, before a byte had arrived - and `isPlaying` came back true off the pending seek,
because ExoPlayer answers `currentPosition` with the seek target the instant `seekTo` is called.
The class's own KDoc for `progressMs` said so.

So `observe` returned `Verdict.Started` immediately for **any play that begins at a resume point**,
which is how most people start most videos. On a dead link the failure chain never ran and the
player sat on the startup overlay indefinitely - the case the comment above that check claims to
guard against, which only ever held when the resume position was zero. The `bestProgressMs <= 0L`
branch and the stall clock were unreachable for the same reason.

Samples carry `baselineMs` now and progress is measured from it. The runtime passes
`activeInitialPositionMs`, already reassigned on every source swap and credential refresh, and the
effect is keyed on `activeSourceUrl`, so each source gets its own baseline.

### `lastHandedOffStream` was a plain `remember` on a route that leaves composition

A mode with a failure chain keeps `StreamRoute` on the back stack on purpose, and `NavDisplay`
composes only the top entry - so the value was gone by the time the player handed control back.
Two effects, both silence: the `?.let { noteSourceFailure(...) }` was skipped, so the overlay
bumped its attempt counter with no account of what died, and `consumeAutoPickFailureReason()` was
never called, leaving a stored reason to be blamed on a later unrelated source. **This is the
route the previous section had just finished making talk.**

It holds the resolved label in a `rememberSaveable` now, like every sibling flag that makes the
same trip. The label is all `noteSourceFailure` ever needed, a `String?` needs no `Saver`, and the
identity lookup that produces it could not survive process death anyway.

### The Android stall watchdog leaked a forever-looping coroutine

Launched beside the transfer coroutine rather than inside it, so it outlived the one exit that
happens before the `try`: a queued download that starts before `initialize` has run fails with
`Fatal` and returns above it, `finally { watchdog.cancel() }` never runs, and the loop goes on
waking, marking the item stalled and resetting for the life of the process - holding that
transfer's `SupervisorJob` and `CoroutineScope` with it. One leak per affected item, reachable
whenever the system restarts the process with work queued. It is a child of the transfer coroutine
now, inside the scope the existing `finally` covers. **The desktop downloader was already clean**;
this was only ever the Android actual, which both repositories carry.

### A credential refresh that found nothing swallowed the error

`tryRefreshCredentialedSourceAfterError` returns `true` the moment it decides to refresh - budget
spent, caller told the error is handled - and the work happens in a launched job. When that job
came back with no candidate, or with only the URL that had just died, it painted `errorMessage`
and returned. Nothing else still considered the error unhandled, so `onFatalPlaybackError` never
fired: a debrid link expiring mid-episode against a down addon parked the player on a message with
live ranked fallbacks behind it - **the outcome the `Decline` branch exists to prevent**. The fatal
tail is `failPlaybackFatally` now and both dead ends go through it; the debug-HUD guard that keeps
the failure screen up for a tester survives the extraction.

### `claimsHdr` read unrecognised strings as HDR

`normalizeDynamicRange` keeps anything `ReleaseTags` does not recognise, uppercased, so
`hdr: ["None"]` produced `{"NONE"}` - not `SDR`, so the `!= SDR` test read a release saying plainly
it has no HDR as a positive claim. `REQUIRE_HDR` scored it 6 instead of `UNSATISFIED_REQUIREMENT`,
`PresetDownloads.matchesRequirements` admitted it to a REQUIRE_HDR preset, and `AVOID_HDR`
*penalised* it. And the two gates disagreed about one file: `PREFER_HDR` scored it 0, because that
path resolves the name through `dynamicRangeNamed` and `"NONE"` resolves to nothing. `claimsHdr`
resolves first now, as `dynamicRangeScore` one function below already did. Dolby Vision still
satisfies it - deliberately wider than `ReleaseTags.claimsHdrFamily`, which excludes DV for the
badge row - and there is a case pinning that.

### Two desktop-only diagnostics faults

`player_bridge.cpp` streamed `avsync`, `container-fps`, `estimated-vf-fps` and the two
`demuxer-cache-*` values as raw doubles. mpv returns NaN for `avsync` whenever there is no audio
track; MSVC writes that as `nan` or `-nan(ind)`, which is not JSON, so `NativeMpvDiagnostics.parse`
threw and **one bad field cost all seventeen** - including `hwdec-current`, the reason the export
exists. `finiteProperty` applies the guard `rawPositionSeconds` already used. ⚠ The macOS bridge is
safe from this **by accident** - `NSJSONSerialization` rejects the dictionary and returns `@"{}"` -
so the two bridges diverge here.

`NativePlayerDiagnostics.writeFrame` never deleted the target before asking mpv for a screenshot.
The settle loop only checks that the file is non-empty and unchanged across two polls, so a file
left by an earlier capture satisfied it before mpv had touched the path, and the harness verified a
frame from the *previous* source - a false pass on the one check whose whole purpose is proving
that this frame decoded.

### Verification

Android host **975**, pure suites **284** in both repositories (was 968 and 272), zero failures
after the ninth fix. The first CI run on this branch is what found that one: 975 tests, 1 failed,
at the new `assertEquals(setOf("hi"), languages)`. `SourceRankingTest` **joined
group 1** while this was in hand: it compiles against the shipped `SourceRanking.kt` and the
neighbour stubs, so the dynamic-range rules now execute outside Gradle instead of waiting for CI.
`SourceFactsExtractorTest` deliberately did **not** join it - `SourceFacts` and its extractor are
both stubs there, so the suite would assert against the stub rather than the shipped file, which is
exactly the failure AGENTS.md warns about. Every changed Kotlin file passes the parser check, and
all eight shared files are byte-identical across the repositories again.

⚠ **Not verified.** The desktop suite is expected at **1189** and has not been confirmed.
`player_bridge.cpp` **is** compiled - `desktop-release.yml` `mode=build-only, target=windows` and
the desktop debug release both ran green on this branch - but nothing has executed it. `DesktopDownloadQueueE2ETest` was **not** run for the
downloader fix, and would not have covered it either: it drives the desktop downloader, and the
leak is in the Android actual. That one was confirmed by reading the control flow - the context
check is the only `return@launch` above the `try`, and every other one is inside it.

Three device checks, on the debug line below:

- Resume a part-watched episode on a source known to be dead. The chain must advance;
  `adb logcat -s PlaybackStartup` should print `abandoning ... reason=NeverStarted` rather than
  nothing at all. **This is the one that matters most** - it is the most common playback path in
  the app, and it currently hangs.
- Let an auto-picked source die a second into playing. The retry overlay must name the source that
  died, not just show a bumped counter.
- Under a strict language preference, a release with several audio codecs and one non-preferred
  language must no longer be auto-played.

## Streamlined and Instant were throwing away sources that worked (2026-08-22, unreleased, both repositories)

**Reported from debug 19/20, and reproduced on a second device the build was handed to.** Sources
picked by Streamlined and Instant "keep doing the looping thing where the video play loads it then
it tries again", ending on *"No safe automatic source matched"*. Manual picks in Classic were fine.

The two halves of that report are **one event**. A retry is the auto-pick failure chain advancing:
the player abandons the source, `onFatalPlaybackError` fires, the chain steps to the next candidate
and `StreamRoute` relaunches it. Three candidates, three loads, and then the chain is spent and the
route toasts `playback_quality_no_match`. So the question was never "why does it loop" but "why is
every auto-picked source being abandoned".

### The eight-second startup deadline could not see a buffer

`PlayerScreenRuntimeEffects.kt` waited eight seconds and asked one question: `!isPlaying &&
positionMs <= 0`. **"Has not started yet" is not "is not going to start."** A debrid link being
minted, a cold provider, or a 60 GB remux seeking its first keyframe are all perfectly healthy at
eight seconds - with a buffer visibly filling - and nothing in that check could see a buffer.

⚠ **It is armed by `onFatalPlaybackError`, which only Streamlined and Instant pass.** The identical
file tapped by hand in Classic had no deadline at all. That asymmetry is the whole reason a player
fault was reported as a mode fault: the two modes whose promise is "you do not have to choose" were
the only ones that discarded working sources, one per candidate, and then blamed the catalogue.

`PlaybackStartupWatchdog` measures progress instead - import-free, group 2 of the pure suites, so
the rule is executable where the player is not. Three clocks, and their ordering is the argument:
a source that has produced nothing at all gets `NO_PROGRESS_DEADLINE_MS` (20 s); one that advanced
and then stopped gets the shorter `STALL_DEADLINE_MS` (12 s) **from its last advance**, because it
has already proved it can reach the host and silence from it is evidence rather than an absence of
one; and `MAX_STARTUP_MS` (60 s) ends the source whose buffer creeps forever, without which
"measure progress instead" would have traded a false positive for a hang. Position and buffer are
taken as a **maximum**, not the buffer alone - mpv reports a cache position first and ExoPlayer
sometimes a play position - and a known duration counts as life without shortening any deadline,
which is exactly the state a big file sits in between the header and the first keyframe.

The longer wait is affordable **only because `shouldOfferManualEscape` already exists**: the source
list is one tap away after five seconds. The cost is now a wait somebody can walk out of, where
before it was the source itself.

### It gave up in complete silence

No log line, and `noteSourceFailure(reason = null)`, so the overlay read *"1080p · WEB-DL · TorBox
did not start"* with no account of why. A chain burning three healthy sources was indistinguishable
from three dead ones **from outside a device** - which is how this survived three releases. Same
rule `NetworkStrengthProbe` carries, arriving by a different route.

There is a `PlaybackStartup` tag now printing the reason, the elapsed time, the best progress seen
and the engine, and the reason reaches the overlay through
`StreamsRepository.noteAutoPickFailureReason` - deliberately not through `onFatalPlaybackError`'s
signature, which is threaded from `App.kt` through `PlayerScreen`, `PlayerScreenArgs` and two
runtime files on three platforms for the sake of one value. The engine's own error message takes
the same road from `onError`, so the *other* silent failure route - a source that opens, plays a
second and dies - now names itself too.

### A pause and a resume discarded the failure chain

Found while tracing the above, and it produces the same toast with no retries at all.

`onPlaybackStarted` fires on **every** not-playing → playing transition, so a pause and resume - or
a rebuffer the engine reports as a stop and a start - calls `consumeAutoPlay` again. The second
call read an `autoPlayStream` the first had already cleared and retained **null** over the real
chain. A source that then died had `failOverAfterPlaybackStarted` answer false with two ranked
candidates still in hand: no failover, and "No safe automatic source matched" over a chain that was
never spent. Retiring an empty chain is a no-op now.

⚠ **Retiring is no longer a reset**, so `AutoPlayFailoverTest` tears down with an empty
`seedAutoPlayCandidates` - the call that retires a chain for good - instead of a bare
`consumeAutoPlay`. Without that, a case that left a chain retained handed it to the next one.

### Verification

Android host **968** (was 957), desktop **1181** (was 1170), pure suites **272** in both
repositories (was 262), all zero failures. `a second start does not discard the retained chain` was
confirmed to **fail** against the old `consumeAutoPlay` and pass against the fix. The ten watchdog
cases all fail against the rule they replace by construction - three of them assert `Waiting` at
exactly the eight-second mark. Nothing under `commonTest` was stubbed; `PlaybackStartupWatchdog.kt`
compiles as shipped source in group 2.

⚠ **Not watched on a device.** The whole diagnosis was made by reading, from a description. The
confirming check is one command while reproducing the report:

```
adb logcat -s PlaybackStartup
```

Nothing at all, and the source plays: fixed, and the old deadline was the cause. Three
`abandoning …` lines with `reason=NeverStarted`: the sources really are dead and the loop is
correct behaviour badly explained - look at the addon, not at this. `reason=Stalled` or
`reason=TooSlow` on a source that plays fine in Classic: the new deadlines are still too tight,
and the figures to move are in `PlaybackStartupWatchdog`, not in the player.

## The gauge was over-reading, and a fake 8K led the sheet (2026-08-21, unreleased, both repositories)

Both reported from one screenshot of Streamlined's quality sheet on a Galaxy S25+ over Wi-Fi.

### The gauge read 538 Mb/s on a line Speedtest measured at 416

The window fix was right about the mean and wrong about the remedy. `ThroughputWindow.peakMbps`
is a **maximum** over sliding windows, and a maximum over positions hunts for the most flattering
one. Two things feed it: Wi-Fi aggregation makes the per-window rate vary by ~10%, so the best of
eight positions sits well above the typical one; and the readers timestamp bytes when `read()`
*returns*, not when they arrive, so a descheduled reader lets the kernel receive buffer fill and
then drains it at memcpy speed into whichever window the maximum is looking for - an autotuned
4 MB buffer at 500 Mb/s is ~64 ms of data, +26% inside a 250 ms window on its own. A single TCP
stream reading 29% *above* a multi-stream Ookla figure is the tell.

Not cosmetic: the sheet only prints this number, but **Instant decides on it**.

What changed. `ThroughputWindow` gained `sustainedMbps` - skip the first eighth of the bytes (a
fraction, because the ramp's byte cost scales with the bandwidth-delay product), partition the
rest into eight fixed **byte** blocks, report the lower median. Byte partitioning is the whole
idea: a trailing stall carries no bytes and so joins no block, a mid-transfer stall makes one
block slow, a buffer drain makes one block fast, and the median discards both. The partition is
admitted on exactly the two floors a window needs, so both statistics see the same transfers.
`peakMbps` is untouched and still serves `stopAboveMbps` and the log. `bestEffortMbps` became a
**precedence** - sustained, then window, then mean - because `max(median, max)` is the max and
would have left the change inert. All three actuals report the new field; the probe log now prints
`sustained=`, `peak=` and `mean=` side by side.

The stale 538 on disk is deliberately **not** invalidated - `FRESH_ESTIMATE_MS` retires it within
ten minutes and `NetworkQualityStorage` is untouched.

### An 18 GB "8K" upscale headed Best available

18 GB over ~100 minutes is 24 Mb/s - a 1080p-grade bitrate wearing an 8K label - sorting above a
genuine 61 GB 4K remux. `bitrateFloorMbps(UHD_4320)` was 8.0, which admits anything above a good
720p encode, so the demotion check was inert for the one resolution nothing reaches by accident.
It is 40.0 now, matching `nominalBitrateMbps` for the same resolution.

Raising the floor alone only fixed the *grouping*. `facts.resolution` still said `UHD_4320`, so
`SourceRanking`'s leading key kept the fake at the head of Best available and the caption kept
saying 8K. The rule is decided once now, as `effectiveResolution`, and `MeasuredCandidate` carries
a rewritten candidate so ranking, captions and labels agree. ⚠ **Only a stated claim is rewritten** -
writing back the *inference* made for a source that stated none would promote it over labelled
720p and would tighten `bitrateCeilingMbps` until an 80 Mb/s unlabelled source headed a row quoting
no bandwidth at all. That is pinned by `aSourceThatStatedNoResolutionIsNeverRelabelled`.

**Considered and declined: the download path.** `PresetSourceSelector`'s `targetResolution` test is
a *ceiling*, so an inflated 8K label already excludes the file from every shipped preset. Demoting
it there would flip an exclusion into an inclusion on a path that spends storage and metered
allowance, and `SourceSelectionResult.Selected.facts` is `@Serializable` and persisted with the
download record. If it is ever wanted the shape is "send it to approval", not "rewrite its facts".

### Verification

Android host **957** (was 946), desktop **1170** (was 1159), pure suites **262** in both
repositories (was 251), all zero failures. The desktop suite is the only thing that compiles
`desktopMain`, so it is what proves the desktop actual reports the new field - the one that
shipped returning null unconditionally last time. The three 8K tests were confirmed to **fail** against the old floor of 8.0 and pass
against 40.0. No file under `commonTest` was stubbed; `PlaybackQualityOptions.kt` and
`ThroughputWindow.kt` both compile as shipped source in groups 1 and 2.

⚠ **Not verified on a device.** Nobody has yet seen the corrected figure on the handset that
reported it. The check is one log line: open the quality sheet on that Wi-Fi and read
`sustained=` against `peak=` under tag `NetworkStrengthProbe`. `sustained=` should sit near 416
with `peak=` near 538 above it. If the two are equal the partition is not closing; if `sustained=`
reads `none` the region floors are rejecting a transfer they should admit.

## Instant is back (2026-08-21, unreleased, both repositories)

Instant was withdrawn twice, and both times for the same sentence: it picked a quality from a
line nobody had measured properly and had no ceiling to hold what it picked. `0.4.10-beta`
(`8a61c993`) withheld the mode behind `PlaybackMode.isSelectable`; `0.5.0-beta` (`476eef64`)
deleted its route paths, because a third of `entry<StreamRoute>` existed to serve a mode no
profile could be on.

**Every reason it was pulled has been answered since, and by work done for Streamlined rather
than for Instant.** The estimate is a windowed sustained rate that every platform reader feeds
(`ThroughputWindow`, the gauge fix, the 403 follow-up); a figure that is still moving is
withheld rather than shown; `playback_quality_ceiling_mbps` is applied before bucketing so even
Best available honours it; bands are absolute; the picker reads HDR, audio and language
correctly; and a dead source is named, counted and stepped past by a capped chain with an
escape hatch. `isSelectable`'s own KDoc had already recorded that the original reason no longer
applied and that *"what is left is evidence"*. This change is about going and getting it.

### Instant is Streamlined with the sheet auto-answered

That is the whole design, and it is what keeps the diff small. Streamlined already had a path
that skips its sheet and plays - the remembered band, which picks an option, announces
*"Playing 1080p High · Change"* and hands off. Instant is that path with
`PlaybackQualityOptions.stickyAffordable` in place of `rememberedOption`.

One picker, one failure chain, one overlay, one set of dead ends. `PlaybackModeRouter` did not
change: its Instant arm was never removed, and neither was `PlaybackModeDownloadRouter`'s, which
is still wired to `MetaDetailsScreen` - Instant's download entry point has worked the whole
time. `PlayerNextEpisodeAutoPlay` branches on `!= CLASSIC`, so Instant's next episode reached
parity for free.

What was actually built:

| | |
| --- | --- |
| `PlaybackMode.isSelectable` | true for all three. Kept, with `coerceSelectable`, because they are the withdrawal mechanism and it has been used twice. A profile stored on Instant since `0.4.9-beta` **comes back to Instant on update** - storage was never rewritten, which was the point of coercing at read time |
| `streamRouteSurface` | `isAutoPickRoute` restored, plus one rule. Without it an Instant play matched nothing and fell to the final `HandOff`: an opaque, empty, pointer-consuming screen over a tappable source list - the exact fault that function exists to prevent |
| `isStreamlinedPlaybackStarting` → `isAutoPlaybackStarting` | one flag for both modes, which is what the deletion note was actually protecting |
| The selection effect | waits for the fetch **and** for `connectionSettled`, then `stickyAffordable` → `startAutoSelectedPlayback(option, rememberBand = false)` |
| Metered | the ask is back, as **Data saver / High quality**. Dismissing answers Data saver |
| The toast | `Playing 1080p · WEB-DL · TorBox`, with **Change** |
| Auto-downshift | **withheld separately**, see below |

### Four things worth not re-deriving

- ⚠ **Instant waits for the connection measurement.** The quality sheet only *prints* that
  figure; Instant *decides* on it, so choosing before the probe settles means choosing from
  `defaultMbps`' unmeasured 50 Mbps Wi-Fi guess. It gates on the same `connectionSettled` nonce
  the sheet uses, so the two cannot disagree, and it inherits the raced `PROBE_DEADLINE_MS` that
  bounds it - `probe` itself cannot, because the Android and desktop readers block in
  `InputStream.read`. The wait is usually invisible: the probe runs beside the fetch and the
  fetch is slower. `PlaybackProgressStep.CheckingConnection` names it for when it is not, reusing
  the sheet's own wording rather than inventing a second one.
- ⚠ **`instantSelectionHandled` is latched and must never be reset.** It guards the seed.
  Clearing it on a retry re-seeds the chain back to candidate 1 and the failure loops forever
  instead of advancing - the same warning this file has carried since `0.4.9-beta`.
- ⚠ **The toast and the remembered resolution are written where the source *opens*, not where
  Instant chooses.** The chain can advance past a dead or evicted candidate to a different
  resolution; recording the intent would remember something that never played, and the next
  episode would then prefer a resolution that had just failed. That is the churn the
  predictability pass removed and it would have come straight back.
- **`rememberBand = false`.** The band id is Streamlined's record of a question the user
  answered and is what makes the route skip its sheet. Instant asked nothing, so it writes only
  the height, which `stickyAffordable` treats as a tie-break - never a ceiling, never a floor.

### Three faults found while building it, all the same shape

None was in the plan, and they share one cause: **Instant is the first thing in
`entry<StreamRoute>` that waits on purpose.** Every guard in that route was written on the
assumption that a wait there is a stall, and three of them acted on a deliberate one.

1. **The stall backstop would have killed the probe wait.** Its guard is "overlay up, no
   candidate armed, nothing resolving, fetch settled" and it gives up after
   `PLAYBACK_PROGRESS_STALL_GRACE_MS` - 1.5 s. Instant standing by for `connectionSettled` matches
   that exactly, and the probe deadline is 5 s. So on any connection that actually needed
   measuring, Instant would have dropped to the Classic source list a second and a half in -
   the one outcome the mode exists to prevent, on precisely the users it exists for. The guard
   now excludes an `AutoPick` route whose measurement has not settled, and the effect is keyed on
   `connectionSettled` so it re-arms the moment it does. **A wait with a named owner and its own
   deadline is not a dead end**; that is the distinction the backstop was missing.
2. **Instant probed the user's data plan before asking to use it.** A metered probe is capped at
   `METERED_MAX_BYTES` (16 MiB), and the mode card promises it *"asks once before using mobile
   data"*. Measuring first and asking afterwards spends the allowance to decide a question the
   user has not yet agreed to be asked. The probe effect now returns - **without settling the
   nonce**, so the selection effect keeps waiting - while the question is unanswered, and re-runs
   when the answer lands.
3. **The 20-second selection timeout would have fired on someone reading that dialog**, toasting
   *"sources timed out"* at a question the app itself had asked. That clock is for an addon that
   never answers; a user deciding whether to spend their data can easily take longer.

`awaitingMeteredAnswer` is hoisted to one `val` with **four** readers - the probe, the selection
timeout, the stall backstop (through `awaitingUserAnswer`) and the surface - because four copies
of one condition is how three of them end up agreeing and the fourth does not. It is gated on the
**route**, not the mode: Streamlined has no such dialog, its `meteredChoice` is null forever, and
waiting on it there would strand its sheet on "Checking your connection…".

### The two smaller decisions

**The metered ask is Data saver / High quality**, and "High quality" is not "unlimited" - it
only removes the cap, so the pick still comes from the measured line. That is exactly
`maxHeight = null`, so `MeteredPlaybackChoice` needed no new members, only new copy. The dialog
is drawn over the **progress overlay**, not over an uncovered source list: Instant's surface rule
sits above `awaitingUserAnswer` on purpose, because dismissing this dialog answers Data saver and
the play continues - there is nothing for an uncovered list to be the fallback for. It is still
counted in `awaitingUserAnswer` so the stall backstop does not give up on a question that is on
screen.

**Automatic downshift stays withheld, and now on its own terms.** Its availability was
`PlaybackMode.INSTANT.isSelectable`, so bringing the mode back would have handed every Instant
user a mid-playback source swap nobody has ever watched run, in the same release, for free.
`AutoDownshiftDetector.AUTO_DOWNSHIFT_AVAILABLE` holds it instead, checked **before** the
setting - a profile that stored `playback_auto_downshift = true` back in `0.4.9-beta` must not
wake up on update. The KDoc says what evidence flips it.

### A stub that had drifted without failing

`scripts/pure-suite-stubs/PlaybackModeStub.kt` carried its own copy of `isSelectable`, hardcoded
to `this != INSTANT` - a second definition of the one predicate whose KDoc says it must be the
only availability test in the codebase. Nothing in group 1 called it, so the compile stayed green
while the suite would have gone on asserting the withdrawn rule after the shipped one changed.
**That is the failure mode a stub has that a missing file does not.**

Group 1 now compiles the shipped `PlaybackModeModels.kt`; the only obstacle was `@Serializable`,
so the group gained `-Xplugin` and the JSON runtime (both already downloaded for group 5) and the
stub is deleted. `PlaybackModeAvailabilityTest` and `StickySourcePinTest` run there now, so the
single switch this whole change turns is executable without Gradle.

### Verified, and what is not

- **`scripts/run-pure-suites.sh`: 251 tests in both repositories**, zero failures, up from 235.
  All sixteen land in group 1, which went from 101 to 117: five new `StreamRouteSurfaceTest`
  Instant rows, plus the five `PlaybackModeAvailabilityTest` cases (which **inverted**, as the
  tripwire intended) and six `StickySourcePinTest` cases, neither of which had ever run outside
  Gradle.
  ⚠ **Pass that script an absolute repository path.** It `cd`s to its work directory before
  resolving `$REPO`, so `./scripts/run-pure-suites.sh .` fails with fifteen "source file not
  found" errors that look like a missing checkout. Pre-existing; noted because it cost a run.
- **Android host suite: 946 tests, 0 failures**, up from 937 (`ANDROID_HOME="A:\AndroidSDK"`,
  empty `local.properties` placeholder, deleted afterwards). The nine are the five surface rows,
  three `PlaybackProgressTest` step cases and the downshift-availability case.
- **Desktop suite in `NuvioZDesktop`: 1159 tests, 0 failures**, up from 1150, in 9m10s. It is
  the only local compile of `desktopMain` and therefore the only check on the ported
  `entry<StreamRoute>`; it also ran the full download E2E harness. **No new `expect` was
  introduced** - every storage key Instant needs already had all four actuals.

**The port was mechanical and that is worth recording.** All nineteen `App.kt` hunks applied to
`NuvioZDesktop` at an offset with **zero fuzz**, so every edited region was byte-identical
between the repositories; the three `externalPlayerSupported` guards that legitimately differ
were not in any hunk. `features/playback/*` and the four test files were copied outright.
`PlaybackSettingsPage.kt`, `PlayerScreenRuntimeSourceActions.kt` and `strings.xml` were edited by
hand, as the never-`cp` rule requires.

⚠ **Nothing here has been watched running, and that is the whole point of the debug line.**
Instant is a mode whose value is entirely in what happens in the first three seconds, and no test
in either repository can see any of it. iOS is not compiled.

### The Instant device script

The evidence `isSelectable`'s KDoc has been asking for since `0.4.10-beta`. Run it on the debug
line **before** the release bump. It is published and waiting: mobile
`debug-v0.4.14-beta.19`, desktop `debug-v0.4.14-beta.11`, both cut from this branch on
2026-08-21.

1. **Settings → Playback → Playback mode.** Instant is selectable, not greyed. Pick it. Then
   confirm *"Switch source when buffering persists"* is **still** greyed and still says why -
   that row is the one thing this pass deliberately did not unlock.
2. **A movie on Wi-Fi.** Plays with no sheet and no source list, and raises
   *"Playing 1080p · WEB-DL · TorBox"* naming what actually opened.
3. **A fresh network.** Delete `nuvio_network_quality.properties` first. The overlay must show
   *"Checking your connection…"* and then play - never sit past ~5 s, and never pick before the
   figure lands.
4. **Three consecutive episodes of one show.** All three at the same resolution. Then press
   **Change** on the toast: the player's source panel opens, and the *next* episode re-decides.
5. **Kill the chosen source mid-start** (disable the addon). The overlay must **name** the dead
   source, the counter must stop at 3, *"Choose source manually"* must appear, and exhaustion
   must land on a **populated** source list - not "No streams found".
6. **Mobile data, Android only** (desktop reports unmetered by construction). The Data saver /
   High quality dialog appears **once**, not per episode, and is drawn over the progress overlay
   rather than over the source list. Data saver caps at 720p.
7. **Back out of the player with the system gesture**, not the in-app button - that asymmetry has
   hidden two separate faults in this route. Details screen, one press, no blank frame, no list.
8. **Long-press an episode** (right-click on desktop) → the Classic source list, that play only.
9. **The download button** on a single episode starts straight away with a preset, no dialog.
10. **Reuse-last-link on, re-watch a finished episode.** *"Resuming your last source · Change"*
    must appear - Instant skipping its own decision silently is the same defect that toast was
    added to fix for Streamlined.

## Settings reorganisation, and an auto-picker that knows what Atmos is (2026-08-21, unreleased, both repositories)

Three problems reported together after using the shipped `0.5.0-beta` build.

### 1. The Playback page was a dumping ground

3,903 lines carrying **11 sections**, with the decoder options - engine choice, renderer, hardware
decoding, decoder priority, DV7 fallback, tunneling - sitting next to "Content Warnings", while
the Advanced page had four rows. On Stream Auto-Play, in the user's own words: *"i dont even know
what autoplay is honestly for classic."* It is a Classic-only section that was shown to everyone.

Playback now has five sections - Player, **Source Preferences** (new), **Audio** (new), Skip
Segments, Next Episode - plus a nav row to a **Subtitles page** of its own. Decoder, the two iOS
output sections, P2P, Stream Selection and Stream Auto-Play moved to Advanced. Nothing was
deleted and every row kept its storage key, so there is no migration and
`AdvancedSettingsDefault.hasTunedAnAdvancedSetting` is untouched - it keys on stored values, not
on where a row is drawn.

Three things worth not re-deriving:

- **The moved rows read their state in place** through `PlayerSettingsRepository.uiState`, the
  pattern `advancedSettingsContent` and `SettingsRootPage` already used. Threading them as value
  parameters would have meant ~20 more params through `MobileSettingsScreen` *and*
  `TabletSettingsScreen` and both call sites, by hand, in both repositories -
  `SettingsScreen.kt` differs by 602 lines.
- **The Advanced nav row is no longer `isAdvanced`.** Playback Engine lives there now and it is
  the main lever for fixing broken playback; hiding it behind "Show advanced settings" would
  hide it from exactly the users who need it. That reasoning was already written two lines above
  the switch itself. Per-row gates inside the page are unchanged.
- **The dialogs did not move, only the rows did.** Every settings dialog in this package is still
  declared in `PlaybackSettingsPage.kt`; the ones a moved row opens are now `internal` rather
  than `private`. Moving fifteen dialog composables across files would have been churn with no
  user-visible effect and more ways to get it wrong.

`SettingsSearch.kt` was repointed in the same change. **This is the step that fails silently** - a
row indexed against its old page is still *found* and then navigates somewhere that does not
contain it. Two groups ended up split across two pages, so rows carry `sectionOverride` and a new
`pageOverride` (`sectionOverride` had been declared in that file and read by nothing).

### 2. Streamlined ignored audio entirely and read HDR wrong

Reproduced against the exact filenames in the report by running `SourceFactsExtractor` verbatim:

| Screenshot row | What the badge said | What `SourceFacts` saw |
| --- | --- | --- |
| MediaFusion 95 GB IMAX | `HDR \| DV \| IMAX` | `{DOLBY_VISION, HDR}` |
| Comet 76 GB FGT | `Atmos \| DTS-HD MA \| TrueHD 7.1` | `{}` - **read as SDR** |
| any `HDR10Plus` release | `HDR10+` | `{}` - **read as SDR** |

**The app had two release-name parsers that disagreed about the same file, and the picker was on
the poorer one.** `features/debrid/DebridStreamPresentation.kt` drew the badges and had `hdr10+`,
`hdr10plus` and `dovi` right the whole time. Both now delegate to a new, import-free
`core/media/ReleaseTags.kt`; `DebridStreamPresentationTest` passes unmodified, which is what
proves the labels did not move.

Four defects died with it:

- **`\bhdr10\+?\b` backtracked.** `\b` after `+` never matches, so the engine fell back to bare
  `hdr10` and every HDR10+ release was labelled HDR10.
- **`hdr10plus` matched nothing at all**, so such a release read as SDR and was ranked *below* a
  plain HDR one under `PREFER_HDR` - actively demoted by the preference asking for it.
- **`dovi` was not recognised.**
- **`releaseQuality` used substring matching**, so `"cam"` hit inside *Camelot*. It is
  token-bounded now for the short tokens only: demanding a boundary in front of `remux` would
  lose every `UHDRemux`, which is the opposite error. Channel layouts are bounded by **digits**
  rather than letters, because `DDP5.1` and `AAC2.0` glue the layout onto the codec and a letter
  boundary threw away most of the catalogue.

`SourceFacts` gained `audioCodecs` and `audioChannels`, fed through the existing provenance
ladder. `nuvioParsed.channels` had been decoded off the wire since `StreamParser` was written and
read by nothing.

### 2b. The follow-up: half a release's tags were being thrown away

**Reported straight after the first debug build, against a stream whose badge row read
`HDR | DV` and `Atmos | DTS-HD MA`:** are releases carrying *both* recognised?

Within one piece of evidence, yes - `HDR.DV.HEVC.DTS-HD.MA.Atmos-SGF` parses to
`{DOLBY_VISION, HDR}` and `{ATMOS, DTS_HD_MA, DTS_HD, DTS}`. **Across pieces of evidence, no**,
and that was a real defect. `extract` walked a first-non-empty provenance ladder for these three
facts, so an addon reporting `hdr: ["DV"]` and `audio: ["Atmos"]` shadowed the release name
entirely: the `HDR` and the `DTS-HD MA` in it were never read. With only `Atmos` seen, *Prefer
lossless* scored that remux 3 instead of 6 and *Require lossless* demoted it by 100 - **a
lossless release refused for having no lossless track.**

A ladder is the wrong shape for a set. A structured field naming one member does not contradict
a filename naming another, it under-reports it, which is the argument `isMultiLanguage` already
made one field below in the same file. `dynamicRange`, `audioCodecs` and `audioChannels` now take
the structured fields and the release text as one body of evidence, exactly as
`DebridStreamPresentation` always has - which is why the badge the user could see was right about
a file the picker was wrong about. The single-valued facts still walk the ladder.

Four new `SourceFactsExtractorTest` cases, built on the reported filename, including the
under-reporting addon and the `DDP5.1` layout that only the release name carries.

### 3. The four middle ranking keys became one score

`SourceRanking`'s chain was `resolution → language → HDR(bool) → codec(bool) → releaseQuality →
cached → direct → size`. As a chain, the first key that discriminated decided the pick outright,
so "lossless audio **plus** HDR10" was settled entirely by the HDR key - and since audio was not
parsed, "lossless" never entered the comparison at all. That is exactly the report: *"if I wanted
lossless audio plus HDR10, the current preferences might serve me that 88gb one which has no
lossless audio."*

The four collapse into an additive `mediaScore`. Resolution and language stay hard leading keys.
`REQUIRE_HDR` and `REQUIRE_DOLBY_VISION` finally mean something in playback - they were selectable
in Playback settings and fell to `else -> true`, honoured only by downloads.

Two asymmetries that look like inconsistencies and are not, both covered by tests:

- **Unstated audio scores mid; unstated dynamic range scores as SDR.** Release names carry HDR
  reliably and audio format only sometimes. Scoring silence at the floor would demote most
  WEB-DLs for a user who asked for lossless, which is a refusal wearing a preference's name.
- **`REQUIRE_*` demotes by -100 rather than excluding**, so the source stays in the failure
  chain. Same rule as the language gate being "a partition, never a filter". Downloads still
  exclude; only the comparator is shared.

⚠ **`SourceFacts.dynamicRange` can now hold `SDR` as a positive claim**, so
`dynamicRange.isNotEmpty()` has stopped meaning "has HDR". `PresetSourceSelector.matchesRequirements`
was the one site relying on it and would have accepted an SDR-tagged release for `REQUIRE_HDR`; it
uses `SourceRanking.claimsHdr` now.

One new setting, `playback_audio_preference` (Automatic / Prefer surround / Prefer lossless /
Prefer immersive / Require lossless), in Playback → Source Preferences. All five actuals, plus
`syncKeys`, plus **both** `PlaybackSelectionContext` build sites - `App.kt` and
`PlayerNextEpisodeAutoPlay.kt` - because missing the second one would have made the in-player next
episode silently ignore it.

### 4. Settings had no width limit on a wide monitor

`TabletSettingsScreen`'s content `LazyColumn` was `fillMaxSize()` with 40 dp padding, so on a
2560 px window the cards spanned ~2,200 px. Clamped to **960 dp, centred**, expressed as a gutter
that never falls below the original 40 dp so nothing touches the edge on a narrow window. The
desktop scrollbar stays pinned to the container rather than to the clamped content.

### Verified, and what is not

Android host **937 tests across 109 classes**, desktop **1150 across 141**, pure suites **235**
each, all zero failures, errors or skips. The desktop run compiled `desktopMain`. New:
`ReleaseTagsTest` (the four parse fixes as named cases), five `SourceRankingTest` cases and
four `SourceFactsExtractorTest` cases,
including the reported Spider-Man ordering, which fails before this change.
`DebridStreamPresentationTest` and `PresetDownloadsTest` both pass **unmodified**, which is the
regression guard for the extraction and for downloads being untouched.

**Nothing here has been seen on a screen, and no test in either repository can see one.** Compose
is CI-and-device-only, and row placement is exactly what no unit test covers. What needs a human:

- Windows desktop: sidebar → Advanced reaches Decoder with "Show advanced settings" **off**;
  content is clamped and centred at 1080p, 1440p and 2160p; the scrollbar stays at the window
  edge.
- Settings search for "decoder priority", "libass", "torrent profile", "regex", "preferred
  audio" - each must land on its **new** page with the right breadcrumb.
- Android: the Subtitles page opens and returns, and subtitle style changes still apply in the
  player.
- Streamlined on a real 4K title with Prefer lossless set: the quality sheet's caption must name
  the release that actually opens.

iOS is not compiled - there is no macOS host here.

## Mobile's debug counter moved to its own file (2026-08-20, unreleased, `nuvio-z` only)

Prompted by wanting a mobile debug build for the gauge fix, which `AGENTS.md` flagged as a live
trap. `DEBUG_BUILD` now lives in `iosApp/Configuration/DebugVersion.xcconfig`, matching what
`NuvioZDesktop` has always done. Readers repointed: `androidApp/build.gradle.kts`,
`composeApp/build.gradle.kts` (twice - the value and the generated `AppVersionConfig` comment) and
`.github/workflows/debug-release.yml`, whose `read_value` now takes the file as its first argument.

⚠ **This stops the trap recurring. It does not repair `0.5.0-beta`'s notes, and an earlier note in
this file implying otherwise was wrong.** The mechanism, checked against the script rather than
assumed: `release-metadata.sh` takes the newest `Version.xcconfig` commit whose `MARKETING_VERSION`
*differs* from the newest one as `previous_bump`. While the version has not moved, debug commits are
skipped and invisible - which is why the range looks fine today. The release bump changes it, every
prior `0.4.14-beta` commit then differs, and **the newest of them wins**. That is `d83894f8`
(`chore: debug build 15`), so `0.5.0-beta`'s generated body starts there and omits `5058a313`, the
whole Streamlined pass, which sits immediately before it. Nothing short of rewriting history undoes
that. Curate `0.5.0-beta`'s notes by hand and check the range before publishing.

Verified: `:composeApp:generateRuntimeConfigs` emits `DEBUG_BUILD = 16` and
`DEBUG_VERSION_NAME = "0.4.14-beta.16"`; `:androidApp:tasks` configures; the workflow's version
resolution was run as a shell dry-run and produced `debug-v0.4.14-beta.16`. Android host 915, 0
failures. The workflow itself is CI-only.

## The gauge fix's own follow-up: a 403 nobody could see (2026-08-20, unreleased, both repositories)

**Reported after installing `debug-v0.4.14-beta.7`:** no automatic probe on open (still 56), one
re-test tap still 56, a second tap finally 211 Mb/s.

Diagnosed from the same file, which is why it was quick:

```
{"networkId":"desktop:2fa9bc","mbps":211.335168,"samples":15,...}
```

`samples` went **14 → 15**. Exactly one probe recorded across all three attempts, so the two 56s
were the stale figure being shown, not fresh bad measurements. Reproduced with curl:

```
bytes=67108864  -> 200
bytes=96000000  -> 200
bytes=100000000 -> 403      <- the cap
bytes=134217728 -> 403      <- what the fix asked for
```

**The fix's own `CDN_FALLBACK_URL` was over the endpoint's limit.** The invariant written down was
"the body must exceed the budget"; the endpoint also has a *ceiling*, which was never checked, and
128 MB is over it. `httpMeasureThroughput` reports a non-2xx as a zero-byte sample, so every CDN
probe recorded nothing - the same outward symptom as the original bug, by the opposite mechanism.
The 211 came from the one attempt that had a direct source URL by then and measured that host
instead. `CDN_FALLBACK_URL` is now 64 MiB: double the budget, well under the cap, both bounds
pinned by `CDN_ENDPOINT_MAX_BYTES` and a test.

Two structural faults behind it, both of which made a broken probe indistinguishable from a
working one:

- **`probe` failed silently.** Deliberately, and it was wrong: "cannot measure" and "measured
  badly" produce the same thing on screen - a figure that will not update - so the difference has
  to exist somewhere findable. It now logs the status, the byte count and the reason at `w`, and
  logs a successful reading with its window and TTFB at `i`.
- **The single-flight guard returned null instantly**, and callers gate a UI on `probe` returning.
  An immediate null reads as "measured, found nothing", so the sheet would commit to the stale
  figure a millisecond after a re-test while the real measurement was still running - the second
  tap succeeding where the first did not is exactly that shape. `probe` now **waits** for an
  in-flight measurement and re-plans, so its contract is "when this returns, a measurement has
  settled". `App.kt`'s matching `isProbing` guard is gone: with the wait in place it was
  redundant, and it could strand the sheet on "Checking" because nothing else would write that
  ask's nonce.

### The early exit is metered-only now

The same report exposed it. The reading that did land was **211 Mb/s** on a line curl measures at
239-377, because the exit fired the moment a window cleared `EARLY_EXIT_FLOOR_MBPS` (200) and
**the rate a probe stops at is the rate it records**. The floor was reasoned about correctly for
*decisions* - nothing in any catalogue needs more than ~160, so no pick changes - and that is the
wrong test, because the figure is also the one the user is shown. A number capped by the
measurement rather than by the connection is the complaint this whole path exists to answer,
arriving from the other direction for the third time.

The exit is a thrift measure, so it now applies only where bytes cost something. On an unmetered
line the probe spends its whole budget, which is under a second at 300 Mb/s. On metered it still
stops early and is still floored, so a title whose most expensive release is a 5 Mb/s encode
cannot stop the probe at 7.5 and write "your connection: 8 Mb/s".

## The connection gauge, actually fixed (2026-08-20, unreleased, both repositories)

**Reported: still ~56 Mb/s, unchanged across app restarts, on the Windows desktop build.** The
windowed-rate work in the pass below was supposed to have fixed exactly this and was inert.

Unlike that pass, this one was **diagnosed from the stored estimate rather than from the code.**
`%APPDATA%\Nuvio Z Debug\nuvio_network_quality.properties` held:

```
estimates_json=[{"networkId":"desktop:2fa9bc","mbps":56.470505050505054,
                 "samples":14,"atEpochMs":1787231352214,"source":"PROBE"}]
```

Three facts follow directly, and they killed three plausible theories:

- `"samples":14` - the probe **was** running and re-recording every time. Not a stuck cache, not a
  suppressed probe, not a persistence bug.
- **no `providerId`** - every reading was the neutral CDN fallback. Debrid links still need minting
  when the sheet opens, so `probeTarget` never had a direct URL to pull from.
- That endpoint serves exactly 4 MiB. Less the uncounted first 64 KiB chunk that starts the clock,
  33.03 Mb; at 56.47 Mb/s the transfer lasted **585 ms**. Model a 250 ms ramp inside it and the
  steady rate is ~72-79 Mb/s, which is the 81 Mbps remux that had been playing without a stall.

### Four faults, and the first one made the previous fix a no-op on every platform

1. **The budget could not hold a window.** `CDN_FALLBACK_URL` asked for `?bytes=4194304` while
   `MAX_BYTES` said 8 MiB, so the *resource size* was the real cap and the `Range` header was
   moot. Against a 750 ms window that gave: **≤44 Mb/s** the window closes and all is well;
   **44-83 Mb/s** no window closes and the ramp-heavy mean is recorded - this is where 56 lived;
   **>83 Mb/s** the transfer finishes inside `MIN_SAMPLE_MS` and the sample is discarded entirely,
   so nothing is recorded at all. The faster the line, the worse the answer, which is the same
   inversion the window was written to remove.
2. **The desktop `httpMeasureThroughput` never got the window.** It built no `ThroughputWindow`,
   returned `peakWindowMbps = null` unconditionally, and still early-exited on the cumulative mean.
   `AGENTS.md` already required the opposite. The pass below claimed "Both platform readers feed
   it"; that was true of Android and iOS, and the report came from the third.
3. **The sample floors were applied to the window.** `probe()` rejected on `bytes`/`transferMs`
   *before* reading `bestEffortMbps`, so a closed window - self-validating by construction - was
   thrown out along with the fast short transfers it exists to rescue.
4. **The early exit was unreachable.** `App.kt` passed `playbackQualityOptions.firstOrNull()
   ?.requiredMbps`, and the first option is always Best available, whose `requiredMbps` is null by
   construction. `stopAboveMbps` has been null on every probe the app has ever run.

### What changed

- `ThroughputWindow` gains a **byte floor** (1 MiB) beside the time floor, which drops to 250 ms.
  750 ms was the wrong invariant: it was chosen so one late packet could not inflate the figure,
  and that is a statement about bytes. Stated in bytes it works at both ends - a fast line closes a
  window inside a budget it can afford, a slow line stretches its window until it is steady.
- Budget **32 MiB / 2.5 s**, halved to 16 MiB on metered, so `Inputs.isMetered` stops being a field
  that is carried and ignored. `Plan` carries `maxBytes` so the rule stays pure and tested.
- `CDN_FALLBACK_URL` serves 128 MB, with the invariant written down: **the body must exceed the
  budget**, or the resource size silently becomes the budget.
- The floors now guard the mean only; a closed window is accepted on its own terms.
- `requiredMbps` is the **max** across the sheet's options, so the early exit can fire.
- The desktop reader feeds `ThroughputWindow` and judges its early exit on the windowed rate.
- `NetworkStrengthProbe.PROBE_DEADLINE_MS` (5 s) bounds the whole measurement. Nothing else did -
  the client allows 60 s to read a body - and the sheet now *waits* on it, so an unbounded probe
  would have been an unbounded "Checking your connection…".

### And the figure no longer changes while it is being read

Reported separately, and the more visible half. The header's `when` tested `isConnectionMeasured`
**before** `isMeasuringConnection`, and a `CACHED` estimate counts as measured - so a sheet that
was actively re-measuring printed the stored number and replaced it a second or two later.
`isProbing` made it worse: it only goes true once the transfer starts, which waits on the option
list, so the real sequence was **old number → "Checking…" → new number**.

- "Checking" is tested **first** now, and the signal is `NetworkStrengthProbe.plan(inputs) != null`
  - the same pure function the probe obeys, so the header and the probe cannot disagree. When no
  probe is planned (a fresh estimate, or offline) the figure appears immediately with no flash.
- **The null travels down to the cards.** `estimatedMbps` also feeds every `connectionFit`, so
  withholding only the header would have left the meters and the over-connection warnings to jump
  at the same moment. `connectionFit` already returns null for a null estimate.
- ⚠ **This is not the older "hide until measured" rule**, which stripped the meters off a
  connection that simply could not be measured. Once the probe settles - landed, failed or timed
  out - the sheet commits to whatever it has, link-type guess included.
- The upward-only latch is cleared whenever a measurement begins, so a re-test that comes back
  *lower* is still shown. That is the answer the user asked for.
- The connection line is **tappable to re-test**. There was previously no way to ask for a fresh
  reading at all: the estimate outlives the process by a week and the probe is suppressed for ten
  minutes after each one, so closing and reopening the app - the only lever available - did
  nothing. A forced probe skips the freshness gate and **replaces** rather than averaging; handing
  back the mean of the new reading and the one the user just rejected is not an answer.

⚠ **The deadline is raced in `App.kt`, not awaited inside `probe`.** `probe` does wrap its transfer
in `withTimeoutOrNull`, but the Android and desktop readers block in `InputStream.read`, which
coroutine cancellation cannot interrupt - a host that answers its headers and then goes silent
holds the probe for the client's own 60 s read timeout, and the wrapper returns no earlier than the
read does. A second coroutine that only ever suspends in `delay` therefore settles the sheet
independently. The wrapper is still worth having: iOS's reader genuinely suspends, and it is what
keeps a stalled transfer from being recorded anywhere.

### Verified

- **Android host suite: 915 tests, 0 failures** (`ANDROID_HOME="A:\AndroidSDK"`, empty
  `local.properties` placeholder, deleted afterwards). Up from 907.
- **Desktop suite in `NuvioZDesktop`: 1128 tests, 0 failures**, up from 1120. This is the only
  thing that compiles `desktopMain`, and therefore the only check on the ported reader.
- **`scripts/run-pure-suites.sh` in both repositories: 222 tests**, up from 218. The four new
  `ThroughputWindowTest` cases run the shipped arithmetic outside Gradle, including a replay of
  the 4 MiB / 585 ms transfer that produced 56.47 Mb/s - it now reports 72.

⚠ **Not verified on a device or an installed app.** The end-to-end check is: delete
`%APPDATA%\Nuvio Z Debug\nuvio_network_quality.properties`, open a title in Streamlined, and read
the file back. Expect `"source":"PROBE"` with `mbps` in the 80-150 range rather than 56.47; a figure
still near 56 means the reader is not feeding the window. The sheet must show "Checking your
connection…" with **no** number and **no** card meters until it commits, then hold one figure.
iOS is not compiled.

⚠ **One open question, deliberately not chased here.** The stored blob held a single provider-less
key and no `PASSIVE` entry at all, so `recordMeasuredThroughput` from
`PlayerScreenRuntimeSourceActions.kt:321` appears never to have landed on this install. After a few
minutes of playback there should be a second estimate keyed to the debrid provider. If there is
not, the passive path has its own defect and wants its own pass.

## Streamlined: absolute quality bands, a real language rule, an honest connection figure (2026-08-20, unreleased, both repositories)

**Reported from daily use, and every item is a reason the user ended up in the source list -
which is the one outcome the mode exists to prevent.**

### 1. The bands meant nothing consistent

`PlaybackQualityOptions.optionsForBucket` split each resolution's *own* bitrate spread into
geometric thirds, so "4K High" meant "the fattest 4K release this particular title happens to
have" - an 88 GB remux on one title, a 14 GB WEB-DL on the next, under the same word. Mid and Low
moved with it. Nothing could be aimed at, and the reported behaviour was picking manually anyway.

Bands are now **absolute**, from `bandBoundariesMbps`, and there is a fourth: `MAX`, so "High"
stops being the word for a remux. At 4K the boundaries are 10 / 25 / 50 Mbps; at 1080p 3 / 8 / 16.
A 20 Mbps 1080p release is `MAX` whether or not the title also has a 4 Mbps encode.

Three properties were kept exactly as they were, and one was newly load-bearing:

- an empty band produces no row; fewer than two occupied bands collapse to `SINGLE`;
- banding still requires **two measured sources** - one figure is not a comparison;
- ⚠ **the collapse guard used to be a formality and is now doing real work.** The old boundaries
  came from the bucket's own extremes, so the top and bottom bands were occupied by construction
  and only `MID` could be empty. Fixed boundaries have no such guarantee - a title whose only
  1080p releases are 5 and 6 Mbps puts everything in one band - and a lone row reading "1080p Mid"
  is a comparison with nothing to compare against. Pinned by
  `aBandedBucketNeverProducesExactlyOneRow`.

**A defect found while writing it:** sizeless sources were about to form their own `LOW` row.
`bandOf` returned `0.0` for a source with no credible size, which is below every absolute
boundary - harmless under a relative split, a row quoting a nominal bitrate for a file nobody
knows the size of under this one. They are now banded out entirely and appended to the cheapest
band that exists.

**New, off by default:** `playback_quality_ceiling_mbps`. Applied in `build()` *before* bucketing,
so **Best available honours it too** - that card is the most-tapped and its source can be the most
expensive in the catalogue. A ceiling nothing fits under is ignored for that title rather than
emptying the sheet, and a source that reported no size is never judged by it.

### 2. Language was not enforced, and could not have been

Three things were wrong at once and each made the others invisible:

| | |
| --- | --- |
| **The vocabulary** | `SourceFacts.LANGUAGE_TOKENS` knew seven languages. A Hindi, Italian or Russian release declared *nothing* - indistinguishable from an untagged English one |
| **`MULTi` and `DUAL`** | not recognised at all, in either repository. They are the two commonest markers in the wild and **neither is a language** |
| **Flag emoji** | no regional-indicator handling anywhere. Torrentio, Comet and MediaFusion all label audio this way |

Worse, `SourceRanking`'s language key was a **boolean** - `preferred in facts.languages` - sitting
second in the comparator, immediately under resolution. Against a set that was empty on both sides
it discriminated nothing, so any source one step sharper on any other key won regardless. That is
the whole mechanism behind "I keep getting sources with no English audio or subs".

**The normalizer it needed already existed and was wired to nothing that reads a release.**
`PlayerLanguagePreferences` carries ~120 ISO aliases, ~72 language names and
`languageMatchesPreference`, used only for the player's own track selection. Those tables moved to
a new **import-free** `core/language/LanguageCodes.kt` (that file reaches the generated Compose
resource bundle for its labels, and `SourceFacts.kt` is compiled outside Gradle); the two public
functions stay in `features/player` as delegates so no player call site churned.

New on top of the move: `releaseLanguagesIn`, which reads a *release name* rather than a tagged
field. ⚠ **Two-letter codes are deliberately refused there** - `IT.Chapter.Two`, `De.Palma` and
any group with `LA` in it all look like language tags to a bare two-letter scan, and
`DebridStreamPresentation.hasToken` is the standing proof that this misfires. Structured fields
still go straight to `normalizeLanguageCode`, which does accept short codes, because there the
value means what it says.

`SourceFacts` gains `isMultiLanguage` and `subtitleLanguages`, and `languages` changes
representation from uppercase two-letter (`"EN"`) to normalized codes (`"en"`, `"pt-BR"`,
`"es-419"`). `SourceRanking.languageScore` replaces the boolean with five ranks, and `UNDECLARED`
sits **above** `NAMES_SECONDARY` on purpose: most English releases name no language because English
is the unmarked case, so ranking "says nothing" below "says your fallback" would hand a user their
second choice on every title that has both.

The gate is `PlaybackSourceSelector.byLanguage`, and it is a **partition, never a filter**. Under
`REQUIRE` an unwatchable source moves behind every watchable one and stays in the failure chain;
a title whose every release is tagged for another market still plays. Only `NAMES_OTHER_ONLY`
fails - wrong audio *and* no subtitles you can read - because the complaint was "no English audio
**or** subs".

**Two standing bugs fell out of this:**

1. `DownloadPreset.preferredAudioLanguage` is a free-text field (`DownloadsSettingsScreen`), and
   `matchesRequirements` compared `uppercase()` against a set holding `"EN"`. Typing "english" -
   the obvious thing to type - matched nothing, so *Require preferred audio language* silently
   rejected every source. It matches now.
2. `normalizeLanguageValues` used to `uppercase()` anything unrecognized, so an addon sending
   `["Latino"]` produced `"LATINO"`, a value no preference could ever equal, on a source that had
   said exactly what it was. `latino`, `latin american` and `brazilian` are now aliases.

### 3. The connection figure under-read, then moved while being read

The reported case: an 88 GB 4K remux needing ~81 Mbps played without trouble against a sheet
reading **57 Mb/s**, with every 4K row flagged "May be more than your connection carries".

**The arithmetic was the fault, not the direction.** `httpMeasureThroughput` reported the *mean*
over the whole body, and a ranged GET's mean includes TCP slow start - on a short pull that ramp
is most of the transfer, and it under-reads *more* the faster the line is, because a fast line
reaches the byte cap while still climbing. Excluding TTFB, which the readers already did, removes
the handshake and leaves the ramp untouched.

New `core/network/ThroughputWindow.kt` (import-free, executable outside Gradle) reports the best
rate sustained over any 750 ms window. `ThroughputSample` gains `peakWindowMbps` and
`bestEffortMbps`, and the probe records that. The budget went 4 MiB / 2.5 s → **8 MiB / 3.5 s**,
because a window has to *fit inside* the transfer where a mean only wanted more samples: 4 MiB at
200 Mbps is 0.17 s, not one window let alone one past the ramp. `EARLY_EXIT_MARGIN` now judges the
windowed rate too - against the cumulative mean it fired late on a fast line and could not fire at
all on a slow one.

⚠ **None of the paragraph above worked, and "Both platform readers feed it" was wrong when it was
written.** See "The connection gauge, actually fixed" below - the budget could still not hold a
window, the desktop reader was never updated, and the sheet still swapped the figure under the
reader. Read that section before trusting any number in this one.

⚠ **One change was made and then reverted after reading the code it touched.**
`recordMeasuredThroughput` was going to become monotonic on the grounds that buffer-derived rates
are demand-limited. `NetworkThroughputMeter` already solves that: it emits only a new maximum
**or** a window in which the buffer *drained*, and a draining buffer is direct evidence the line is
the bottleneck. Making it raise-only would have discarded the one signal that can disprove an
over-generous estimate. The blend stands; only a comment was added saying why.

The sheet no longer moves under the user. `PlaybackQualitySheet` latches one figure for its own
lifetime (upward only, and a real measurement always supersedes a link-type guess), and
`connectionFit` gained two conditions the warning never had: the estimate must be a **measurement**
- it was being scored against `defaultMbps`' 50 Mbps Wi-Fi guess - and the option must exceed it by
`OVER_CONNECTION_MARGIN` (1.15). `requiredMbps` already carries a third of headroom and the estimate
under it is a lower bound; warning the instant they crossed flagged rows that play fine, which is
what taught the user to ignore the warning. Meters still draw on an unmeasured figure. Only the
verdict has to be earned.

### 4. The HDR/DV preference existed and could not be found

It was `isAdvanced = true`, along with codec - so a user asking where to set one was looking at a
page that genuinely did not have it. Both are now plain rows, joined by **Audio language** and
**Quality ceiling**, and all four are indexed by `SettingsSearch`.

The quality sheet also grows a **Preferences** button opening `PlaybackPreferencesDialog`. A
dialog, not a navigation: Settings is on a different back stack, so opening the real page would
pop `StreamRoute` and cost the user the episode they just asked for. Rows cycle rather than
opening a picker - three to five values each, all of which fit in the row - and writes go through
the real repository setters, so the grid behind rebuilds on its own.

Two new profile-scoped keys, `playback_language_strictness` and `playback_quality_ceiling_mbps`,
in **all four** storage actuals (android, ios, desktop, plus the expect) and in `syncKeys()`,
`exportToSyncPayload` and `replaceFromSyncPayload`.

### 5. Two bugs

**The player appeared to load twice.** `LaunchedEffect(activeSourceUrl)` clears
`initialLoadCompleted`, which is what puts the opening overlay back up - right for a *different*
source, wrong for the same file behind a fresh signature. `hasLikelyExpiringPlaybackCredentials`
matches nearly every debrid URL (its key set includes bare `t` and `e`), so any transient startup
error spends the one permitted refresh and the user watches the load finish, restart and finish
again. New `isCredentialRefreshHandoff` marks that one URL change as a continuation: the controller
is still torn down, but the presentation does not start over, and `SubtitleRepository.clear()` /
`clearEpisodeStreams()` are skipped - the refresh had just loaded that source list to find the
replacement, and the subtitles belong to a file that is still playing.

**"No streams found" over a full catalogue.** `StreamsScreen` auto-filters to the addon that last
served this show, gated on `groups.any { it.addonId == preferred }` - but a group is created for
every addon that is *asked*, whether or not it answers. Filter to one that returned nothing and
`hasAnyStreams` is false while `groups` is full, so the screen draws its empty state over
everything the other addons found. Streamlined is what made it visible: `giveUpToSourceList` drops
the user straight onto that screen, so they pick a quality, are told no source matched, and land on
what looks like an empty library.

Three fixes, each independently worth having: the auto-filter now requires the addon to have
streams; the empty state offers **Show all sources** whenever a filter is hiding a non-empty
catalogue; and `giveUpToSourceList` clears the filter, because the addon it would select is quite
possibly the one that just failed.

### Verified

- **Android host suite: 907 tests, 0 failures** (`ANDROID_HOME="A:\AndroidSDK"`, empty
  `local.properties` placeholder, deleted afterwards). Up from 872.
- **Desktop suite in `NuvioZDesktop`: 1120 tests, 0 failures**, up from 1085. This compiles
  `desktopMain`, which is the only check for the fourth `PlayerSettingsStorage` actual, and runs
  the download E2E harness.
- **`scripts/run-pure-suites.sh` in both repositories: 218 tests**, up from 196. Group 1 now
  compiles the shipped `core/language/LanguageCodes.kt` rather than stubbing it - stubbing the
  thing that decides whether a source is watchable would prove nothing about the fix - and group 2
  gained `ThroughputWindow`.

⚠ **Not verified.** iOS is not compiled; its `httpMeasureThroughput` and the two new storage
actuals are checked by name and by the shared `expect` only. **Nothing here has been smoke-tested
on a device or an installed desktop app**, and both bug fixes in §5 are reasoned from the code
rather than reproduced - §5's second item in particular is a strong hypothesis, not a confirmed
repro. The band boundaries are calibrated from format bitrates, not from this user's catalogue.

### Device script for this pass

1. Open the quality sheet on a title with a 4K remux: expect **Max / High / Low**, remux under
   Max. Then a title with only mid-range 4K releases: expect **one** unlabelled 4K row, not three.
2. Set a preferred audio language, strictness *Only play what I can watch*. A `MULTi` release must
   still be offered; a `HINDI`/`ITA` release must lose to an English alternative. Kill the winner
   mid-start and confirm the chain still reaches the rejected one rather than dead-ending.
3. Open the sheet twice on the same network: the figure must not jump, and must not read below a
   rate you have already streamed.
4. Watch a debrid start end to end: **one** logo overlay, not two.
5. Play an episode whose remembered addon has no source this time - the list must appear populated,
   never "No streams found".
6. Settings → Playback with advanced settings **off**: Audio language, Quality ceiling, Dynamic
   range and Video codec are all visible. The sheet's **Preferences** button changes the grid
   without losing the play.

## Stream preferences work without a built-in cloud account (2026-08-18, unreleased, both repositories)

**A user whose debrid runs inside the addon - AIOStreams and anything like it - had the entire
Debrid settings page doing nothing.** Playback was fine, because every playability gate keys off
`playableDirectUrl`, and AIOStreams hands back a plain `https://` URL. But the filter, sort, cap and
template pipeline was gated on `canResolvePlayableLinks`, which is false with no API key of your
own, so `DebridStreamPresentation.apply` returned the groups untouched at line 1. Even past that
gate, the selector was `isManagedDebridStream`, which needs a `clientResolve` or a `debridCacheStatus`
- AIOStreams streams have neither. Every row on that page was dead for them.

| | What changed |
| --- | --- |
| **The gate** | `apply` now tests `settings.appliesStreamPresentation`, driven by a new persisted `DebridStreamPreferenceScope { RESOLVER_ONLY, DEBRID, ALL_ADDON_STREAMS }` - **default `ALL_ADDON_STREAMS`**. `RESOLVER_ONLY` reproduces the old behaviour exactly and is the opt-out. `canResolvePlayableLinks` is untouched; its six other consumers keep their meaning |
| **The selector** | New `isPresentableStream(settings)`. Under the default it is any installed-addon stream with a `playableDirectUrl`, which is deliberately wider than AIO detection so a self-hosted instance `AioStreamsSupport.isAioStreams()` misses is still covered. `playableDirectUrl` already strips `magnet:`/`torrent://`, so unresolved magnets never enter - asserted by a test |
| **AIO metadata is read** | `DebridStreamMetadata.facts` and the formatter now fall back to `streamData.parsedFile` for resolution, quality, codec, HDR, audio, languages, title and size, and `streamSearchText` gains the AIO filename and parsed fields. **Every fallback sits after the resolver's own value**, so a resolver-resolved stream is bit-for-bit as before. `{stream.indexer}` falls back to the AIO sub-addon name ("Torrentio", "Comet"), which is exactly what the token means |
| **Service names** | `serviceId` reads `streamData.debridService`, and `DebridProviders` gained a **display-only** alias map (AllDebrid, Debrid-Link, Offcloud, EasyDebrid, put.io, PikPak, Seedr). Deliberately **not** in `registered` - that feeds `all()` → `syncKeys()` in all five storage actuals and would write dead `debrid_*_api_key` entries. The generic short-name fallback is now initials-or-first-two capped at 3, not `uppercase()`, because "ALLDEBRID" wrecks a name template |
| **Formatting is decided per stream** | `hasCustomStreamFormatting` was **always true** (lines 59-60 tested whether a *constant* was blank), which only stayed harmless while the pipeline was gated. It now means what it says - a template edited away from its default. Renaming is decided per stream: custom template, **or** a known service, **or** existing badges. Without that, widening the scope would have renamed every plain addon row to **"1080p Cloud Instant"** |
| **Settings page split in place** | The `if (!canResolvePlayableLinks) return` at line 357 is gone. New **Stream preferences** section carries the scope picker (always enabled), a hint when no account is connected, and a pointer to Downloads → *Treat as AIOStreams* - the only place a user can be told about that switch. **Link preparation stays resolver-gated**; it drives `DirectDebridStreamPreparer`, which genuinely needs an account. Nine result-management/formatting rows now gate on `appliesStreamPresentation` |

**Two things the plan did not anticipate, both found by running the suite:**

1. **`hasCustomStreamFormatting` had two consumers outside the debrid package** -
   `StreamsScreen.kt` and `PlayerStreamList.kt`, both as
   `canResolvePlayableLinks && !hasCustomStreamFormatting`, feeding `appendInstantServiceToDefaultName`.
   That expression was **always false**, so the "- TB Instant" suffix has never appeared. Fixing the
   property's meaning would have switched it on and produced **"2160p TB Instant - TB Instant"**,
   since the default template already writes the service into the name. Both call sites now read
   `!appliesStreamPresentation`, which keeps the suffix unreachable exactly as it has been. It is
   dead code either way; deleting it is a separate decision.
2. **`streamSearchText` never included the stream URL**, so a plain addon row whose only metadata is
   in its URL gets no facts at all. Pre-existing and left alone, but it is why the "formatted once a
   template is customised" test has to put the release name in `behaviorHints.filename`.

**The behaviour change to declare.** Under the default scope, existing users **with** an account now
see non-resolver addon results participate in filtering, sorting and result caps where they
previously passed through untouched. Names are unaffected on default templates. The sharpest edge:
a plain addon row with an unparseable name reads as `UNKNOWN` resolution, so a *Minimum quality*
of 1080p now hides it. `RESOLVER_ONLY` is the opt-out, one tap away on the same page.

**⚠ Gradle does work on this machine, and the two sections above are wrong to say otherwise.**
There is no Android Studio JBR and no SDK at the paths `AGENTS.md` names, but there is a JDK 21 on
`PATH` and an SDK at **`A:\AndroidSDK`**, and that is all it needs:

```bash
ANDROID_HOME="A:\\AndroidSDK" ./gradlew.bat :composeApp:testAndroidHostTest --console=plain --max-workers=4
ANDROID_HOME="A:\\AndroidSDK" ./gradlew.bat :composeApp:desktopTest --console=plain --max-workers=4
```

`JAVA_HOME` is not needed. **`nuvio-z` has no `local.properties`**, and
`:composeApp:generateRuntimeConfigs` declares it as a task input, so the mobile build fails
configuration with *"An input file was expected to be present"* - which reads like a missing SDK and
is not one. `build.gradle.kts:52` already tolerates the file being absent at execution time, so an
**empty placeholder is enough** to get the suite running; it was created for this run and deleted
afterwards. Do not put real values in it - it is gitignored and carries the Supabase configuration.

**Verified, with real builds:**

- **Android host suite: 872 tests, 0 failures, 0 skipped** (`DebridStreamPresentationTest` alone is
  17). This compiles the Android target and resolves the eight new string keys.
- **Desktop suite in `NuvioZDesktop`: 1085 tests, 0 failures, 0 skipped** (9m, the download E2E
  harness is most of it). This compiles `desktopMain` - the check for the fifth
  `DebridSettingsStorage` actual - and compiles `DebridSettingsPage.kt` through the Compose
  compiler, so the new section and scope dialog are no longer parser-checked only.
- `scripts/run-pure-suites.sh` green in **both** repositories - **196 tests each**
(72 selection/quality/route, 29 standalone, 49 setup, 17 sync, and **29 new in group 5**), zero
failures. Group 5 is new and compiles **the shipped `StreamModels.kt`**, so `StreamItem`,
`AioStreamData` and the cache-status types are real; `scripts/pure-suite-stubs/debrid/` stands in
only for `AppFeaturePolicy`, the generated Compose resource bundle and the `expect object`
`DebridSettingsStorage`. It needs the serialization *compiler plugin*, which the kotlinc
distribution does not ship - the script now fetches it. Every changed file parser-checks clean in
both repos, and all five `DebridSettingsStorage` actuals were checked by name.

**Two of the nine existing `DebridStreamPresentationTest` cases were changed**, not because they
broke but because they encoded the old gate: *"applies debrid sort filters ... without removing
normal urls"* and *"leaves cloud-service results untouched when link resolving is off"* now pin
`streamPreferenceScope = RESOLVER_ONLY`. They are the regression net for the resolver path; the new
cases cover the same fixtures under the default scope. The other seven pass unmodified.

⚠ **Not verified.** iOS is not compiled - no macOS host - so the iOS `DebridSettingsStorage` actual
is checked by name and by parser only. Nothing here is smoke-tested on a device or an installed
desktop app, and no sync round-trip was exercised against a real server.

**Device script for this pass** (run with **no debrid API key set**):

1. Settings → Debrid no longer stops after "Accounts": **Stream preferences**, Result management and
   Formatting are present and tappable, and **Link preparation is absent**.
2. Set *Minimum quality = 1080p* and *Sort = Quality*, then open a title served by AIOStreams. The
   720p rows go, the order changes.
3. Confirm plain non-debrid addon results keep their original names.
4. Set the name template to `{stream.resolution} {service.shortName} {stream.size::bytes}`. All three
   render, and the service reads `AD`/`RD`, never `ALLDEBRID`. If it renders blank, follow the
   in-page hint to Downloads → *Treat as AIOStreams* and re-check.
5. Switch the scope to *Only results Nuvio resolves*: the list reverts to the addon's own formatting.
6. With a TorBox key and the default scope, cached-torrent rows still read `"<res> TB Instant"` and
   uncached rows stay hidden - and the name must **not** end in a second "- TB Instant".
7. Sign in on a second install and confirm the scope round-trips. A missed `syncKeys()` actual shows
   up as the setting silently reverting after a pull.

## The desktop self-test harness (2026-08-17, unreleased, both repositories)

**A debug-only button that runs the device script against real services and writes evidence.**
Settings → Advanced → Diagnostics → **Run self-test**, or `Ctrl+Alt+T`, or
`-Pnuvio.desktop.selfTest=true` to start one automatically a few seconds after launch. Output goes
to `%APPDATA%\Nuvio Z Debug\self-test\<timestamp>\` as `report.md`, `report.json`, `env.json`,
`run.log` and PNGs.

The gap it fills is stated plainly by this file: nearly every section ends with *"nothing is
smoke-tested on a device or an installed desktop app"*, and the device scripts below have mostly
never been run. `commonTest` has 130 pure test files and **not one crosses a network boundary**;
`DesktopDownloadQueueE2ETest` is the only real-I/O suite and covers only downloads;
`SetupWizardRenderHarness` writes PNGs but offscreen with **no network**, so every poster is a
placeholder and the video region would be black regardless.

| Suite | What it does that nothing else can |
| --- | --- |
| **S0** Environment | GPU, display scale, mpv, WebView2, which addons are active, which debrid providers actually validate, whether the Trakt refresh token still works |
| **S1** Sources | Real `meta` fetch and real `StreamsRepository` fan-out, with **per-addon latency and result counts** |
| **S2** Debrid | `resolveToPlayableStream(forceRefresh = true)`, then a **1 MiB range GET to prove the link serves bytes** - the placeholder-body case looks like success from a status code - then a second mint for the re-mint path |
| **S3** Playback | Plays a real stream and reads mpv directly: **`hwdec-current`**, codecs, decoded size, fps, **dropped frames**, and both demuxer cache properties. Asserts the position advances on wall-clock and that the buffer is ahead of it and bounded - the `demuxer-cache-time` absolute-vs-duration trap |
| **S6** Settings and sync | Writes a playback setting, reloads from disk, forces a server pull, and checks the value **did not go backwards** - the fault that wiped playback settings in `0.4.0-beta` and re-gated the app behind the wizard |
| **S8** UI walk | Navigates the real app and screen-grabs it **with a network**, so artwork is real. Includes a mechanical version of the pointer-input check |

**Two new mpv JNI exports** in `player_bridge.cpp` (and the macOS `.mm`, kept in step):
`diagnosticsJson` and `screenshotToFile`. The C++ property helpers already existed; only the JNI
wrappers were missing. ⚠ **The exports are not stripped from a release DLL** - both channels compile
one source from one Gradle task - so the gate is in Kotlin, behind `isDebugBuild`, at every call
site.

Screenshots use `java.awt.Robot`, deliberately. The desktop player is not Compose - mpv renders into
a native child HWND and the controls are a WebView2 overlay - so `ImageComposeScene` returns a black
rectangle and no controls. mpv's own `screenshot-to-file` is taken **as well as** the grab: if the
frame is fine and the grab is black, compositing is at fault; if both are black, decoding is.

`commonMain` cost was kept to **one 12-line file with no `expect`/`actual`** (`SelfTestHooks.kt`)
plus ~30 lines in `AdvancedSettingsPage.kt` and ~20 in `App.kt`. On mobile every hook stays null,
which is what hides the settings row.

**Verified, on this machine, for real:**

- ⚠ **The harness was actually run, three times, and its output read.** That is the whole point and
  it is the step `setup-wizard-renders` never got - green for four passes while nobody opened it.
- `:composeApp:desktopTest` - **1072 tests, 0 failures**.
- `scripts/run-pure-suites.sh` green in **both** repositories, 167 tests each.
- The Windows bridge **compiles locally**, and `dumpbin /EXPORTS` confirms both new symbols.
  Local native builds work with
  `-Pnuvio.windows.vcvars.path="C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat"`
  - `vswhere`'s `-requires` filter does not match Build Tools, which is why it looked absent.
  ⚠ `buildWindowsPlayerBridge` has `onlyIf { !output.exists() }`: **delete the DLL to force a rebuild.**
- Two harness faults were found by running it and are fixed: the settle detector photographed the
  details screen **mid-dissolve** (now needs 3 consecutive still frames and a 1.5 s floor), and the
  status overlay appeared in the corner of every screenshot (now hidden during a grab).

**⚠ Not verified:** nothing was compiled for Android or iOS - still no SDK on this machine, so
`nuvio-z`'s three shared files are parser-checked and grepped only, per "Verifying without Gradle".
**CI is the gate.** S4 (seek and tracks), S5 (playback modes and the failure chain) and S7 (a real
download) are **not written yet** - `DesktopSelfTest.suites()` names them as outstanding.

**Two findings from the runs themselves, both needing a second opinion:**

1. ⚠ **Home draws with zero catalog rows.** `HomeRepository` reported `isLoading = false`, **0
   sections and 0 hero items after 30 s**, on a profile whose settings page says "4 of 4 catalogs
   visible" and whose addons returned 88-1020 sources for the same titles seconds earlier. The
   screenshot is a black screen with one Continue Watching card. Reproduced on all three runs.
   Refresh is a `LaunchedEffect(catalogRefreshKey)` in `HomeScreen.kt:527` that early-returns when
   the key is empty. **Not yet confirmed against the installed MSI rather than a Gradle `run`.**
2. **Episode stills do differ per row** on the series details screen - check 13 of the setup wizard
   device script, previously listed there as "the least proven thing in the change". See
   `S8.3-details-series.png`.

The `Nuvio Z Debug` profile has **no debrid provider and no Trakt connection**, so S2 and S3 - the
playback core - skipped on every run. A skip is never counted as a pass; the report lists each one
with its reason. Configure debrid in the debug install before the next run, or the suite cannot
answer the questions it was built for.

## Playback review pass (2026-08-17, unreleased, both repositories)

A `/code-review high` over `features/playback`, `features/player` and desktop's
`desktopMain/player` returned seven findings, all fixed here. One was a build break; three were
the same root cause in the next-episode failure chain.

| | What changed | Risk |
| --- | --- | --- |
| **Desktop build restored** | `b71378c1` deleted the two `loadNvidiaRtxSuperResolutionEnabled` / `save…` lines from the `expect object` in `PlayerSettingsStorage.kt` while leaving both commonMain callers and **all three actuals**. commonMain had an unresolved reference and every platform had an orphan actual - the module compiled on no target. Restored the `expect` pair; signatures checked against all three actuals. **Desktop-only: `nuvio-z` has no NVIDIA references at all** | none - restores a deleted declaration |
| **Chain cleared on the downloaded-episode paths** | `launchPlayerNextEpisodeAutoPlay` returned at the downloaded branch *before* the clear inside `tryModeSourceSelection`, and `playEpisodeFromPicker`'s `selectDownloadedEpisodeForPlayback` had the identical hole. A stalled local file (or the 8 s watchdog) then took the **previous** episode's stream while progress was written against the new one. The comment claiming this was prevented predates the branch that skipped it | low - clears state earlier |
| **Explicit pick retires the chain** | `switchToUserSelectedSource` exists to distinguish user picks from automatic ones but left `nextEpisodeFallbacks` armed, so the watchdog swapped a hand-picked remux out for an auto-ranked source 8 s in, while it was still buffering | low - removes an automatic override |
| **Failure toast names the source that died** | The `activeStreamTitle.isBlank()` branch filled the placeholder with `next.streamLabel` - the source about to *play* - telling the user it had already failed. Falls back to `activeProviderName`, then to the new `playback_source_failed_advancing_unnamed` | none - copy |
| **"Best available" is remembered** | `saveSessionQualityBand` was gated on `option.resolution?.height`, which is null for `Variant.BEST` **by construction** - so the most-tapped row was the one row never stored, and the sheet reappeared every episode against the shipped copy's promise. `height` is now `Int?`; the id is written unconditionally. `rememberedOption` matches by id alone, so the skip now works for it | low - the sheet skips where the copy said it would |
| **Backstop yields to a live question** | `giveUpToSourceList()` checked `ProgressOverlay`/`HandOff` but not `awaitingUserAnswer`. Under a remembered band `AskUncached` leaves the surface on `ProgressOverlay` (rule 3 outranks rule 5), so the backstop tore down the "Nothing is cached" dialog mid-question and toasted "no matching source". Added the guard **and the effect key** - a dialog raised during the grace period must restart the effect | low - one more way the backstop declines to fire |
| **Armed band-change cannot leak across shows** | `armBandChange` is documented as "disarmed by whatever happens next", but an *ignored* toast survived indefinitely; a later Change on a different show - including reuse-last-link's, which raises the same typed action - cleared the first show's band. New `disarmBandChangeIfNot(parentMetaId)` runs as each play opens | low - narrows an existing clear |

Also removed three imports (`SizePreference`, `SourceRanking`, `SourceRankingPreferences`) left
unused in `PlaybackSourceSelector.kt` after `rank` was deleted.

**Verified:** `scripts/run-pure-suites.sh` green in **both** repositories - **167 tests each**
(72 selection/quality/route, 29 standalone, 49 setup, 17 sync), zero failures. Every edited file
parser-checks clean in both repos. Every changed reference was grepped at each call site, per the
gap named in "Verifying without Gradle": the sole production `saveSessionQualityBand` caller uses
named arguments and the six test callers pass `Int`, which still binds to `Int?`;
`disarmBandChangeIfNot` and the new string are defined and used in both repos; `awaitingUserAnswer`
is declared above the backstop in both.

⚠ **Not verified.** Still **no Android SDK and no Android Studio JBR on this machine**, so no
Gradle suite ran and `desktopMain` was not compiled.

- **The `expect`/`actual` fix is the one that most needs a real build.** `desktopTest` locally, or
  `desktop-release.yml mode=build-only`, is the first thing that will confirm it - exactly the
  case AGENTS.md says only a real build catches.
- `App.kt` and the player runtime are **parser-checked only** - no type checking, no Compose
  compiler, no resource resolution. The two new `LaunchedEffect`s and the new string are in that
  set.
- No regression test was added for the three chain-clearing fixes; they live in the player
  runtime, which no suite in either repository can reach. Device script below.
- Nothing is smoke-tested on a device or an installed desktop app.

**Device script for this pass:**

1. Streamlined, series with episode 2 downloaded: auto-play episode 1, let it roll into 2. The
   local file must play; if it stalls, the fallback must be a source list, never episode 1's stream.
2. Mid-binge, open sources and pick the largest remux. It must be given more than 8 s to buffer and
   must not be swapped out automatically.
3. Pick "Best available" on episode 1. Episode 2 must play straight away, announcing the band.
4. With a remembered band, force an uncached answer: the "Nothing is cached" dialog must stay up
   until answered, with no "no matching source" toast underneath it.
5. Skip a sheet on show A, ignore the toast, open show B and press Change on *its* toast. Show A
   must still skip its sheet next episode.

## Playback-mode UX correctness pass (2026-08-16, unreleased, both repositories)

A second read of the shipped mode surfaces - after the pass below - found seven places where
what the UI *says* and what it *does* had drifted apart. None were engine faults; all were
visible to a user.

| | What changed | Risk |
| --- | --- | --- |
| **Card copy corrected** | Streamlined's second bullet advertised *"Pin a release to reuse it for the rest of a season"* - withdrawn in `0.5.0-beta`. Now describes the remembered band. `PlaybackModeCard`'s "must match the router" contract extended from the download line to the streaming lines, which is how it drifted | none - copy |
| **Failure chain capped** | `entry<StreamRoute>` seeded the *whole* ranked row while the overlay coerced "Attempt N of 3", so a deep bucket ground through nine candidates with the counter pinned. `playbackChain` now applies the budget, in `StreamRouteSurface.kt` where the pure suite runs it | low - fewer retries than before, never more |
| **Remembered band reaches the details screen** | **behaviour change** - see below |
| **Overlay has a way out** | "Choose source manually" appears after the first failure or 5 s (`shouldOfferManualEscape`). Before this, Back was the only exit and it abandoned the play | low - additive |
| **Failures named, not toasted** | One toast per dead candidate over an overlay already counting attempts; the third failure route (player-requested retry) reported nothing at all. The overlay now names the source, all three routes report, and the terminal give-up still toasts | low |
| **Greyed settings say why** | Torrent autopick, codec preference and dynamic range disable on Classic; only auto-downshift explained itself. All four now do | none - copy |
| **Escape-hatch copy per platform** | `playback_mode_escape_hatch` says "long-press" in `nuvio-z` and "right-click" in `NuvioZDesktop`. **Deliberately divergent - do not `diff -q` this key.** Confirmed against `secondaryClick` in `DetailSeriesContent.kt` / `DetailActionButtons.kt`. TV wording is still a gap; there is no TV detection in the codebase | none - copy |

**The behaviour change, in full.** Streamlined stored the chosen band from the day it shipped,
but only `PlayerNextEpisodeAutoPlay` read it - so bingeing *from the player* skipped the quality
sheet and bingeing *from the details screen* did not. Same show, same sitting, same choice
already made, and the app asked again depending on which door you came through.

Now the stream route reads it too and plays straight away, announcing
*"Playing 1080p High · Change"*. Three things make that safe rather than silent:

- The band is stored **twice on purpose**, and the two readers need opposite failure modes.
  `sessionQualityHeight` still feeds the in-player next episode through `stickyAffordable`,
  which *substitutes* when the band is unavailable - nobody is there to answer a sheet mid-binge.
  The new `sessionQualityBandId` feeds the route through `rememberedOption`, which returns
  **null** instead, because there the sheet is being *skipped* and a substitution would be one
  the user never sees. `PlaybackQualityOptionsTest` pins both halves against each other.
- The id carries the **variant**, not just the resolution: someone who picked "1080p Low" to
  stay inside a data cap has not picked "1080p High".
- "Change" retires the band, so the next episode asks again. The toast action is a typed enum
  with one central handler and no content identity, so the show travels as data
  (`armBandChange` / `consumeArmedBandChange`), armed only by a play that actually skipped a
  sheet.

**Verified:** `scripts/run-pure-suites.sh` green in **both** repositories - **167 tests each**
(72 selection/quality/route, 29 standalone, 49 setup, 17 sync), zero failures, up from 159. The
8 new cases that run there are in `StreamRouteSurfaceTest` and `PlaybackQualityOptionsTest`.
Every edited Kotlin file parser-checks clean in both repos.

⚠ **Not verified.** There is still **no Android SDK and no Android Studio JBR on this machine**,
so no Gradle suite ran. That means:

- **7 new tests never executed** - 2 in `StreamlinedFailureChainTest` (the chain cap) and 5 in
  `BingeGroupCacheRepositoryTest` (band storage, `clearSessionQualityBand`, arm/consume/disarm).
  They need `StreamsRepository` and the real `StreamItem`, so the pure suite cannot reach them.
  **CI is the first thing that will run them.**
- `App.kt`, `PlaybackSettingsPage.kt`, `PlaybackProgressOverlay.kt` and `PlaybackQualitySheet.kt`
  are **parser-checked only** - no type checking, no Compose compiler, no resource resolution.
- Nothing is smoke-tested on a device or an installed desktop app.

**Device script for this pass:**

1. Streamlined, series: play episode 1, pick a band. Back out to details, tap episode 2. It must
   play straight away with the "Playing … · Change" toast and **no sheet**.
2. Press "Change" on that toast - the player's source panel opens. Back out, tap episode 3: the
   sheet must return.
3. Pick a band on a show whose next episode has no release at that resolution. The sheet must
   appear rather than something else playing silently.
4. Kill the chosen source mid-start (disable the addon). The overlay must **name** the dead
   source, the counter must stop at 3, and "Choose source manually" must appear.
5. Settings → Playback → Player on Classic: all four greyed rows say why.
6. Desktop setup wizard: the escape-hatch line says right-click.

**Out of scope, and next.** The **next-episode stack is untouched and is suspected broken on
desktop** - `PlayerNextEpisodeAutoPlay`, `NextEpisodeCard`, `PlayerNextEpisodeRules`,
`PlayerScreenRuntime*`, the episode selector and the newer play button overlap there across
~21 files. Change 3 was deliberately shaped to leave `sessionQualityHeight` and its single
reader alone so this pass could not disturb it. That is the next round.

## Playback-mode UX pass (2026-08-16, unreleased, in both repositories)

A UX review of the three playback modes found the code correct and the product confused:
Instant is withdrawn (`PlaybackMode.isSelectable`) yet roughly a third of
`entry<StreamRoute>` existed only to serve it, and four independent bail-out mechanisms had
grown for what the plan describes as one pure decision. Six changes landed together, and
**three of them are behaviour changes that have not been on a device**:

| | What changed | Risk |
| --- | --- | --- |
| Instant paths deleted | The selection effect, metered dialog, "playing X" toast, `instantSelectionHandled`/`meteredChoice`, `StreamRouteSurfaceInputs.isAutoPickRoute` | none - unreachable code |
| `PlaybackQualityTier` removed | Type, `playback_quality_tiers`, all four actuals, sync entries | low - nothing read it for a decision |
| Bail-outs collapsed | `giveUpToSourceList` + `leaveToDetails` replace `fallBackToSourceList`, `qualitySheetDismissRequested` and a duplicated pop-then-uncover guard | low - `StreamRouteSurfaceTest` unchanged and green |
| **Sticky pin dropped** | `PlayStickyPin` router arm, its dialog and all its state. `StickySourcePin` + tests kept | **behaviour change** |
| **Next-episode parity** | Streamlined's next episode now prefers the band chosen in the sheet and carries a 3-source failure chain (`nextEpisodeFallbacks`) instead of dropping to a manual list | **behaviour change** |
| **Reuse-last-link is explained** | Streamlined raises "Resuming your last source · Change" instead of silently skipping its sheet | **behaviour change** |
| Binge rows nested | Hidden while "Auto-play next episode" is off | cosmetic |

**Verified:** `scripts/run-pure-suites.sh` green in **both** repositories - 159 tests each
(64 selection/quality/route, 29 standalone, 49 setup, 17 sync), zero failures - and every
edited Compose file parser-checks clean in both.

⚠ **Not verified, and this is the gap:** there is **no Android SDK and no Android Studio JBR
on this machine**, so `:composeApp:testAndroidHostTest` and `:composeApp:desktopTest` did
**not** run. CI is the only compiler for `App.kt`, the player runtime and the settings page,
and the only check of the four `PlayerSettingsStorage` actuals after the tier-key removal.
Nothing has been smoke-tested on a device or an installed desktop app.

**Device script for this pass** - the three behaviour changes, in order of risk:

1. Streamlined, series: play episode 1, pick a band that is *not* the top one. Let episode 2
   auto-play. It must open at the same band with no quality sheet.
2. Same, then kill the source mid-binge (disable the addon). It must name the source and
   advance, not open the episode panel. Exhaust three and it may then open the panel.
3. Streamlined with reuse-last-link on: re-watch a finished episode. The toast must appear
   and "Change" must open the player's source panel.
4. Confirm no season-pin prompt appears anywhere, and that the long-press escape hatch still
   reaches the source list.

| | |
| --- | --- |
| **Active branch** | `claude/setup-wizard-final-pass-wy7csp` in both repositories, cut from `claude/onboarding-setup-wizard-7juovt` (**not** from `main` / `Dev`). Carries **revision 6 of the setup wizard** plus three unversioned fix passes on top of phase 2, which sits on top of the phase-1 polish pass. **Not yet released and the version is deliberately not bumped.** |
| **Released** | `0.4.14-beta` on both. Superseded once phase 1 and phase 2 ship together as `0.5.0-beta`. |
| **Next** | **Push, so CI runs the 7 new tests this machine cannot** (`StreamlinedFailureChainTest`, `BingeGroupCacheRepositoryTest`) and type-checks the four Compose files the UX-correctness pass edited. Then **the next-episode stack**, which is the outstanding suspected fault - see that pass's section. Then: **download the `setup-wizard-renders` artifact from the `NuvioZDesktop` CI run and look at the PNGs** — the harness has been green for four passes while nobody opened its output, and every Welcome defect since would have been plain in it; CI now uploads it. Then **run both device scripts** — "The 0.5.0-beta device script" for phase 1 and "The setup wizard device script", whose first checks are the outstanding faults. Test with **`debug-v0.4.14-beta.13`**. Then merge to `main` / `Dev`, bump both version files as the final commit, and dispatch the release workflows. |
| **Also unpushed** | `codex/whats-new` (local only, in `nuvio-z`): one commit, "feat: show release notes after updates". Not merged, not verified. |
| **Desktop debug line** | New, on this branch: **`NuvioZDesktop` now has a debug update channel** matching mobile's - a separate "Nuvio Z Debug" install fed by `debug-v*` prereleases, published with `desktop-debug-release.yml`. Nothing compiled locally; the Windows MSI job is the gate. See "The desktop debug line" below. |

This table is the first thing to update in any session, and it is kept current on
`main` as well as on the working branch - see "Keeping `main` current" in
`AGENTS.md`. If it names a branch, the newest work is on that branch, not here.

## The desktop debug line (2026-08-15, unreleased)

**`NuvioZDesktop` only; nothing in `nuvio-z` changed but these docs.** The desktop app now has
the equivalent of the mobile debug channel: a separate **Nuvio Z Debug** application that
installs beside the release app and updates itself from `debug-v*` prereleases in
`Zokaper/NuvioZDesktop`. Dispatch **`desktop-debug-release.yml`** to publish one.

### It is a different application, not a differently-configured one

Everything that decides identity is switched by one Gradle flag,
`-Pnuvio.desktop.debugChannel=true`, which is **off everywhere else** - `ci.yml` and
`desktop-release.yml` are untouched and still build the release app:

| | Release | Debug |
| --- | --- | --- |
| Package / display name | `Nuvio Z` | `Nuvio Z Debug` |
| Windows MSI upgrade UUID | `7b1f2c94-…` | `3e6c8d15-…` |
| macOS bundle ID | `com.nuvio.media.z.desktop` | `…z.desktop.debug` |
| App data directory | `%APPDATA%\Nuvio Z` | `%APPDATA%\Nuvio Z Debug` |
| Version name | `0.4.14-beta` | `0.4.14-beta.<DEBUG_BUILD>` |
| Diagnostics HUD + file log | off | on by default |

⚠ **The upgrade UUID is the load-bearing one.** Sharing it would make every debug MSI
*replace* the release install rather than sit beside it - the desktop equivalent of shipping
the debug APK under `com.nuvio.app.z`.

⚠ **The data directory is separate, so the debug app starts empty.** That is deliberate and
matches mobile, where the separate application ID gives the same result: a build published to
test a fix must not be able to corrupt the state of the app it is being compared against. Note
it only isolates *local* state - sign in on both and the Supabase settings blob is shared, which
is the path `mergeMonotonicSyncInt` exists for.

### Three things that could not be copied from mobile

1. ⚠ **The channel is keyed on the tag prefix, not the prerelease flag.** Mobile can use
   `prerelease` because its stable updater already discarded prereleases. Desktop's
   `includePrereleases` was **`true`**, so a `debug-v*` prerelease would have been offered to
   every release install the moment it was published. `includePrereleases` is now true only for
   a debug build, *and* the release path rejects a `debug-` tag outright - either alone would
   do, and both are cheap.
2. ⚠ **`debugChannel` is a new field on `AppUpdateReleaseSource`, deliberately not derived from
   `AppUpdaterPlatform.isDebugBuild`.** That flag means "this binary is debuggable", which is
   true of the Android debug APK built from `NuvioZDesktop` - and that APK belongs on the
   ordinary release line. Keying the channel off it would have pointed that build at a feed
   whose only assets are Windows MSIs, failing with "update asset missing".
3. ⚠ **Windows decides an upgrade from the MSI `ProductVersion`, which jpackage limits to three
   numeric components.** The debug counter cannot ride in the `-beta.3` suffix the way it does
   in the version *name*, so the MSI version is `1.<VERSION_CODE>.<DEBUG_BUILD>`. `VERSION_CODE`
   only ever increases, so that is monotonic across the whole debug line even while the
   marketing version stands still - which is the case the counter exists for. **Without this
   every debug MSI would carry the same ProductVersion and Windows would reinstall rather than
   upgrade.**

### The counter lives in its own file, and mobile's does not

`composeApp/Configuration/DesktopDebugVersion.properties` holds `DEBUG_BUILD` alone.

⚠ **This is a fixed bug, not a style choice.** `release-metadata.sh` finds a bump by walking
commits that touch the *version file* and reading the version key at each - it does not require
the value to have changed. A commit that only moved the counter is therefore read as a bump, and
release notes are generated across `previous_bump..current_bump`. ⚠ **Mobile has this trap
today:** bumping `DEBUG_BUILD` in `Version.xcconfig` between two releases will truncate the next
release's notes. Moving that key to its own file is the same change and has not been made.

Also: **publish debug builds before a release bump, never after.** `Validate release state`
rejects anything changed between the bump and the release commit except the release workflows
and two scripts, and the debug counter is not on that list.

⚠ **The workflow file must reach `Dev` before it can be dispatched at all**, exactly as the
mobile one must be on `main`: that is where GitHub looks to decide whether `workflow_dispatch`
exists. It targets the dispatched commit, so once it is on `Dev` a debug build can be cut from
any working branch. ⚠ Pushing it also needs a token carrying the **`workflow` scope** -
`gh auth refresh -h github.com -s workflow` - which the repo-scoped default does not have.

### Verification

**Parser check clean** over all five changed/added Kotlin files. ⚠ The first run of it was a
**false pass** - `kotlinc` was not on `PATH`, the `grep` matched nothing, and every file
reported OK. The harness was re-run against a deliberate syntax error until it failed, then
re-run for real. Worth remembering: an empty grep is indistinguishable from a clean parse.

**Cross-file greps**, because a single-file parse resolves no references: every renamed build
symbol counted at its call sites (`desktopBaseVersionName` is the rename, 5 uses, all after its
declaration), and all four `AppUpdateReleaseSource(` construction sites confirmed to use named
arguments, so the added field's default reaches them.

**The workflow YAML parses** (`js-yaml`, alongside `ci.yml` and `desktop-release.yml` as
controls), and its version-resolution shell block was **run locally** against the real files:
`debug-v0.4.14-beta.1`, ProductVersion `1.38.1`, artifact
`Nuvio-Z-Debug-Windows-x64-0.4.14-beta.1.msi`. `set-version.sh` passes `bash -n`, and
`--desktop-debug 4 --dry-run` writes nothing.

**The version derivation was compiled and run as a copy**, which proves the arithmetic rather
than the build script: the release path still yields `Nuvio-Z-Windows-x64-0.4.14-beta.msi` and
ProductVersion `1.4.14` - byte-identical to what `desktop-release.yml` already greps for, which
is the check that matters, since a debug channel that quietly renamed the release artifact would
break the release workflow. The debug ProductVersion sequence `1.38.1 → 1.38.2 → 1.38.12 →
1.39.1` was asserted strictly increasing.

**Not verified:**

1. ⚠ **Nothing has been compiled.** No Android SDK and no JBR on this machine, so Gradle cannot
   configure here either - the build-script changes are the least-covered part and **the Windows
   MSI job is the gate**. A Gradle Kotlin DSL error is exactly what the parser check cannot see,
   since it never reads `build.gradle.kts`.
2. ⚠ **`DebugChannelVersionTest` has never run.** It reaches `VersionUtils` inside
   `AppUpdater.kt`, which imports Compose resources, so it cannot join the pure suites. CI runs
   it via `:composeApp:desktopTest`. **Extracting `VersionUtils` into an import-free file would
   fix that permanently** and matches what was already done for `StreamRouteSurface.kt` and
   `SyncPreferenceJson.kt` - not done here to keep this change to one subject.
3. **Nobody has installed the debug MSI.** The checks are: it appears as "Nuvio Z Debug" beside
   the release app, both launch, and their settings do not move together. Then publish a second
   one and confirm the first offers it.
4. **The release app must be shown to ignore the debug channel** - the half that cannot be
   proved by installing the debug build alone.
5. macOS and Linux are untested and unpublished; the workflow builds Windows only, matching
   `desktop-release.yml`'s existing `target: windows` constraint.

**Read `AGENTS.md` first.** It carries the two-repository mirroring rules, the
full release procedure, which secrets exist and where, and how to verify code in a
sandbox where Gradle cannot configure.

## The Welcome still is a screenshot now, not a layout fitted to a hole (2026-08-14, unreleased)

**Same branch, and `SETUP_WIZARD_REVISION` deliberately stays at 6** - the eight questions are
unchanged, so there is nothing to re-ask. Reach it with **Settings → Run setup again**.

Reported as *"almost perfect, this is just way too messy - I want it to effectively be a
screenshot of the thing with the wizard overlayed, frosted kinda"*.

### ⚠ One root cause, three symptoms

The previous pass fixed "the hero swallows the frame" by **fitting the still into the space the
panel left**, and every complaint that came back is a consequence of that:

- The nav pill **floated mid-screen over Continue Watching**, because it was padded up by the
  visible height so it would clear the panel. `App.kt` draws the real one pinned to the window's
  bottom edge as a later sibling with `align(Alignment.BottomCenter)`.
- The hero had **proportions the app never draws**: `viewportHeight` was the visible band, then
  capped again by `continueWatchingHeroViewportReserveHeight`, and `mobileHeroHeight` takes 82% of
  whatever it is handed.
- The poster row was **a squeezed sliver**, because the column was padded to stop above the panel.

**The rule now, and it is the whole design of the file:** everything is laid out at the app's real
metrics inside the app's own scroll container, and the sheet simply covers the bottom of it.
Nothing is resized, repositioned or padded to fit. `SetupHomeStill` lost its
`visibleHeightBelowTop` parameter entirely; the panel no longer measures itself with
`onSizeChanged`, so the frame-late 340 dp estimate is gone too.

### What it is built out of

`SetupHomeStill` is now **`NuvioScreen`** - the app's own container - called exactly as
`HomeScreen` calls it with a hero on: `horizontalPadding = 0.dp`, `topPadding = 0.dp`. Same
`listGap`, same content padding, so fidelity stops being something to hand-maintain.

⚠ **The scroll is a seeded `LazyListState`, not a `Modifier.offset`, and that is not cosmetic.**
`HomeHeroSection` derives its parallax (`translationY = offset * 0.3f`) and background scale from
`listState.firstVisibleItemScrollOffset`. Offset on a plain `Column`, the backdrop sits in its
*unscrolled* position - part of why the old still read as not-the-app. The list gets
`LazyListState(0, scrollPx)` and the hero section gets the same state, so the parallax is the
app's own. `scrollPx` is derived from `homeHeroLayout(...).heroHeight` minus a 250 dp tail rather
than guessed, so it holds on every window size: it frames on the hero's content block - logo,
metadata line, button - with Continue Watching under it.

Three smaller fidelity fixes fell out of reading `HomeScreen` properly:

- Rows are **24 dp apart**, not 16: `Arrangement.spacedBy(12.dp)` from the container **plus** a
  per-item `padding(bottom = 12.dp)`. The still had the container gap only.
- Sections take `sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth)` (16 dp on a
  phone), which the still never passed.
- ⚠ `HomeContinueWatchingSection` **only honours `sectionPadding` when `layout` comes with it** -
  pass one alone and it silently drops into its own `BoxWithConstraints` and re-derives both. The
  argument reads as load-bearing while doing nothing. Both are passed now.

**One hero item, not the row.** The hero is an auto-advancing pager, and a still that rotates is
not a still; it also meant the screenshot landed on whichever title's backdrop happened to be
missing from the artwork host, which is what produced a hero that was simply black. The cost is
the pager dots, which only draw for more than one item.

**The whole still is inert** (`nuvioBlockPointerEvents()`, `Initial` pass). A real `LazyColumn` is
draggable, and a screenshot that scrolls out from under your thumb while you read the panel is a
new kind of mess.

⚠ **The nav bar composable was dropped and restored.** The rewrite deleted
`SetupStillNavigationBar` along with the fitting code and the call site kept compiling in the
parser check, because a single-file parse resolves no references. Caught by grepping for the
definition, not by the check - the parser check is *necessary, never sufficient*, and this is a
concrete example of what it cannot see.

### The panel is frosted rather than solid

New **`internal expect fun isBackdropBlurSupported()`** in `core/ui/BackdropBlur.kt`, with
`SDK_INT >= 31` on Android and `true` on iOS and desktop. Same idiom as
`isLiquidGlassNativeTabBarSupported()`.

⚠ **Thinning the tint globally would have re-shipped revision 2's unreadable sheet to every device
below Android 12**, where Haze cannot reach `RenderEffect` and the "frost" is a plain scrim. So the
alphas are conditional: blur available → **0.68 / 0.60 / 0.54**, genuinely translucent, which a
40 dp blur makes safe by destroying the high-frequency detail that makes text hard to read over a
picture. No blur → **0.94 / 0.88 / 0.82**, the previous scrim, unchanged. `NuvioNavigationBar`
already does the same thing one step cruder (`alpha = if (hazeState != null) 0.55f else 0.82f`).

⚠ **A new `expect` needs an actual in every compiled source set.** `nuvio-z` has a `desktopMain`
directory with three stale files in it but **no `jvm(` target** - only `NuvioZDesktop` declares
`jvm("desktop")`, so the desktop actual lives there alone and the **Windows MSI job is what proves
it exists**.

### CI now uploads the render harness PNGs

`SetupWizardRenderHarness` can only assert that things render without throwing, so the images are
the actual check - and every Welcome defect that came back from a device was plain in them while
the harness passed green. `ci.yml`'s desktop job now uploads
`composeApp/build/setup-wizard-render/` as `setup-wizard-renders` on **`always()`**, so a failed
assertion still yields the picture. ⚠ It renders **without a blur**, so those images are the
API-30 legibility check and not a fair view of the frosted case.

### Verification

**CI green on both repositories** at `79013f12` / `f7c5cb88`. ⚠ The **Windows MSI job passed**,
which is the only thing that proves the new `expect` got its `desktopMain` actual. Desktop tests
passed, so the render harness composed the rewritten still without throwing. `Debug release`
published **`debug-v0.4.14-beta.13`**.

**Pure suites unchanged at 67 + 29 + 49 + 17 = 162**, both repositories.

**Parser check clean** over all six changed/added files in each repository. **Six setup files
byte-identical** (`SetupWizardScreen.kt`, `SetupSpecimen.kt`, `SetupSampleTitle.kt`,
`SetupDiagram.kt`, `SetupModeStoryboard.kt`, plus the three `BackdropBlur` files);
`SetupHomeStill.kt` still differs by exactly its three documented hunks. Every composable the still
calls was checked against **both** repositories' signatures before the port - `NuvioScreen` is
identical in both despite `Components.kt` being divergent, and desktop's `HomeHeroSection` inserts
`sectionPadding` mid-list while `homeHeroLayout` gains a trailing `preferDesktopLayout`, which is
why every argument at every call site is named.

**Not verified:**

1. ⚠ **Nobody has still looked at the harness PNGs.** They are now a real artifact -
   `setup-wizard-renders`, 16 MB, on the `NuvioZDesktop` CI run for the commit - but ⚠ **the agent
   sandbox cannot fetch them**: the proxy returns 403 on `api.github.com`, so artifact download is
   a maintainer action and a `git clone` is not a substitute. Download it from the run page. This
   is the fifth pass in which the harness has been green and unseen.
2. **The device check:** the still reads as a screenshot - nothing floating, nothing squeezed, the
   poster row cut by the panel edge rather than shrunk above it - and the panel is visibly frosted
   rather than solid.
3. ⚠ **With no network the hero is flat black**, because `HomeHeroSection`'s backdrop `AsyncImage`
   carries no `placeholder`, `error` or `fallback` - unlike the logo right below it. That is what
   the real home screen looks like with no network, and making the wizard nicer than the app is
   the failure this file exists to avoid, so it is left alone. Worth seeing once in aeroplane mode.
4. The fall-through fix from the section below is **still unseen on a device**.

## The wizard was letting taps through to the app behind it (2026-08-14, unreleased)

**Same branch, and `SETUP_WIZARD_REVISION` deliberately stays at 6** - the flow asks exactly the
same eight questions, so there is nothing to re-ask. Reach it with **Settings → Run setup again**,
which is also the path that was broken.

### ⚠ The headline was not a wizard bug at all

Reported as *"pressing the tabs takes me to the nuvio mobile vanilla repo? also for some reason
this random website"*. Those are `https://github.com/NuvioMedia/NuvioMobile` and
`https://www.premiumize.me` - **two rows of Settings → Licenses & attributions**
(`LicensesAttributionsPage.kt:45,49`). The wizard opened neither, and the details screen cannot:
the entire `features/details/` tree contains exactly one `openUri` call and it is on a trailer
card.

`Settings → Run setup again` draws `SetupWizardScreen` **over** `MainAppContent` rather than in
place of it - `App.kt` says so in its own comment - and the screen's root was
`fillMaxSize().background(...)`. **A `background()` does not consume pointer input.** Every tap
that missed one of the wizard's own controls went through to the settings page underneath, and
the preview's tab row happened to sit over those two links.

⚠ **This is the second time this exact defect has shipped**, which is why it is now a rule in
`AGENTS.md` rather than a comment. `0.5.0-beta` item 1 was the same thing on the stream route:
*"the surface consumed no pointer input, so the invisible source list underneath was fully
tappable"*. The fix written then - `nuvioConsumePointerEvents()` - is what this uses; it consumes
on `PointerEventPass.Final`, so the wizard's own controls still receive events first.

Two things kept it hidden. The **gate** path never shows it, because there `MainAppContent` is not
composed at all - only the dismissible re-run is affected. And the other on-demand overlay,
`WhatsNewScreen`, is a `Dialog` and is immune by construction, so there was no second example to
compare against.

### The Welcome still was all hero

⚠ **Superseded** - see "The Welcome still is a screenshot now" above. The fix described here
(measuring the visible height and fitting the layout into it) is the thing that came back as
*"way too messy"*, and all of it has been removed.

`SetupHomeStill` passed `viewportHeight = maxHeight` - the whole window - and no
`mobileBelowSectionHeightHint` at all. `mobileHeroHeight` takes `MOBILE_HERO_VIEWPORT_RATIO =
0.82f` of whatever it is given, so on an 800 dp window that is a **656 dp banner inside a ~440 dp
visible band**: the still showed nothing but hero.

It now takes the height **visible above the panel**, which the panel measures with
`onSizeChanged` and reports, and caps the hero with
`continueWatchingHeroViewportReserveHeight(...)` - the same helper `HomeScreen` uses for the same
parameter, rather than a number picked here. Plus a 28 dp nudge upward, which is the "scroll down
a bit" that was asked for.

⚠ The panel height is a frame late, so it is seeded with a 340 dp estimate rather than zero. Being
slightly wrong for one frame beats a hero visibly resizing on the very first screen of the app.

### The tabs

**In the preview**, they now work: tapping Cast / Trailers / Details switches the rail, and there
is a Details rail for the first time - a tab that switched to nothing would be revision 5's empty
heading one indirection further along. ⚠ This is the **only interactive control in any specimen
band**; every other band is a picture the panel's controls change.

**In the real details screen the wiring was always correct**, so the reported symptom was the
fall-through and not this. Three real defects were found next to it and fixed anyway:

- The tap target was the bare glyph box, about **26 dp** against Android's 48 dp guidance -
  foundation's `clickable` does not apply `minimumInteractiveComponentSize` the way a Material
  component would. A tap a few dp high or low did nothing, which is most of "the tabs don't work".
- `remember` rather than `rememberSaveable`, so scrolling the group out of the lazy viewport
  disposed it and the selection snapped back to the first tab.
- ⚠ **`Crossfade` leaves the outgoing section laid out and hit-testable for the full 200 ms**,
  because it animates alpha and nothing else. One of these three sections is Trailers, whose cards
  open a URL the addon supplies **verbatim** (`HeroTrailerSelector.youtubePlaybackUrl` passes any
  `http`-prefixed `key` straight through). An invisible thing that opens an arbitrary third-party
  link on touch is the same defect as the headline, so the fading half is made inert with the new
  `nuvioBlockPointerEvents()` - `Initial` pass, so children never see the event at all.

### Verification

**Pure suites unchanged at 67 + 29 + 49 + 17 = 162**, both repositories - none of this is
reachable from a pure file, which is the point.

**Parser check clean.** **Five setup files byte-identical**; `SetupHomeStill.kt` still differs by
exactly its three documented hunks. ⚠ `Components.kt` and `MetaDetailsScreen.kt` were already
divergent between the repositories and were hand-ported.

**Not verified:**

1. ⚠ **The fall-through fix has not been seen.** The check is: Settings → Run setup again, then
   tap the preview band, the paragraphs and the gaps on every step, and confirm nothing behind
   reacts. Open Licenses & attributions first if you want the original repro.
2. **Nobody has still looked at the harness PNGs.** The Welcome hero defect would have been plain
   in `welcome-420x900.png`. Rendering without throwing is not looking.
3. The preview's tabs and the real screen's tap target are both layout judgements a device makes.

## Revision 6 of the setup wizard (2026-08-14, unreleased)

**Same branch.** Revision 5 fixed the gate and got the wizard onto a screen, which is what let the
next four faults be seen at all. Three were reported; the fourth is visible in the screenshots and
is a device-script check that was already failing.

⚠ Two findings reshaped this pass before any of it was written, and both contradict something
this file previously assumed:

1. **The app already has real backdrop blur.** `dev.chrisbanes.haze:haze:1.7.2` is a **commonMain**
   dependency in both repositories and ships in four places - the floating nav bar, the poster and
   episode zoom overlays, and the streams tablet side panel. Revision 2's "a frosted pane would
   mean rendering the stage into a `GraphicsLayer`… which is API 31+ and would degrade on exactly
   the devices that need it most" was reasoning about `Modifier.blur`, and it skipped the library
   already in the build. `core/ui/NavigationBar.kt:143-156` is the pattern.
2. **The real home composables render standalone, and it was already proved here.** The deleted
   `SetupPreviewStage.kt` (at `18de5ae^`) called them from a plain `Column` with no nav controller
   and no network. The whole call surface is three composables and four required arguments.

### 1. Welcome shows the app, under a frost

Revision 5 replaced the wordmark with a hand-drawn miniature - hero, one Continue Watching row,
one poster row - and the verdict was that it *"just doesn't look like the actual home"*. It did
not: no top chrome, no section headings, no tab bar, and a 344 dp band above a short panel that
left a large dead gap.

**New `features/setup/SetupHomeStill.kt`** draws the real `HomeHeroSection`,
`HomeContinueWatchingSection` and two `HomeCatalogRowSection`s, full-bleed, with a real
`NuvioNavigationBar` at the bottom. Row titles come free - each section calls `NuvioShelfSection`
internally. The wizard's panel floats over it behind a `hazeEffect`.

⚠ **`SetupHomeStill.kt` is the one setup file that is NOT byte-identical and must never be
`cp`'d.** Desktop's `HomeContinueWatchingSection` takes a **required** `dataSourceKey:
WatchProgressSource` in third position, from `features.tracking` rather than `features.trakt`. The
two copies differ by exactly three hunks and both say so at the top. Named arguments at every call
site are what makes that portable - desktop's `HomeHeroSection` also inserts `sectionPadding`
mid-list and `NuvioShelfSection` reorders `rowModifier`.

⚠ **Why this is not revision 2 returning**, since that is the obvious objection. Revision 2 put a
**translucent** panel over a **live** preview on **every** step. All three words differ here: a
real blur, over a **still**, on the one step that carries **no controls**. Steps 2-8 keep the
two-opaque-regions layout, and that is not a compromise - they have four control labels to read
while the preview changes underneath them, which is precisely the case that failed.

⚠ **The tint carries legibility on its own.** `minSdk` is 24 and Haze cannot reach `RenderEffect`
below API 31, so on a large part of the range this is a scrim and nothing else. The three stops
(0.94 / 0.88 / 0.82) are tuned for the **no-blur** case. **Do not thin them after looking at an
Android 14 device**, and note the harness renders without a blur too, so its PNGs are close to the
worst case.

⚠ **The tint is strongest at the top**, which inverts revision 2's specific mistake: its gradient
was most transparent at the top edge, which is where the heading sits.

`SetupSpecimen.Welcome` and its `SpecimenWelcome` are deleted. Do not add one back.

### 2. The animation loop no longer jumps

*"A tad janky"*, and the screenshot shows exactly why: the picked `4K` chip sits about 12 dp left
of the two below it.

- ⚠ **The cause is a reserved-width problem, not an animation one.** Only the highlighted row
  carried the pointer, so only that row was wider, and the column centred each row on its own
  width. There is now a **fixed 26 dp gutter on every row** and the pointer is positioned over it.
- **One pointer whose offset animates**, instead of one drawn inside whichever row was
  highlighted. Revision 5's vanished from one row and reappeared on the next, which reads as a
  cut. Reading the list *is* Classic's cost; a teleport shows that cost as an instant.
- **Stages slide as well as fade.** Cross-dissolving two things at the same position was most of
  the janky feel - for a quarter of a second the title card and the release list were both
  half-drawn on top of each other.
- **Classic's rows carry text now**, on the maintainer's explicit go-ahead:
  `2160p · HDR · 18.4 GB` and two more. "You read the releases and pick one" cannot be drawn as
  five blank bars. Same rule as the quality tokens - resolutions, source tags and sizes are
  locale-independent, so they stay Kotlin constants with nothing to translate.

### 3. Cast and Trailers draw content

*"Nothing loads for cast/trailers"* - and it was two faults, both in `SpecimenDetailSections`:

- ⚠ **The tiles were `skeleton`-filled.** That token is `Color.White.copy(alpha = 0.06f)`, which
  composites to about `#1B1B1B` over the standard background and **`#0F0F0F` on AMOLED**. The real
  sections use `surfaceVariant` (`#222222`). Six sites in `SetupSpecimen.kt` used `skeleton` for
  artwork placeholders and all six are now `surfaceCard` - so every card in the wizard, not only
  these rails, had an invisible placeholder on an AMOLED theme.
- **A rail was drawn only under the first heading**, so "Trailers" was a heading over nothing.
  Every heading has one now.

Cast is circular `surfaceVariant` avatars carrying **initials** - which is exactly what
`DetailCastSection` draws when `person.photo == null`, so the specimen shows the app's own
no-photo state rather than inventing a placeholder. There is no artwork host keyed by person that
a first launch could reach. Trailers are 16:9 thumbnails carrying real backdrops, mirroring
`TrailerCard`'s aspect and its `Color.Black @ 0.2f` scrim.

### 4. The playback-mode panel was clipped

Not reported, but plain in the screenshot: the Instant card is cut mid-subtitle and its "Not
available in this version" line is gone. **Device-script check 10 already forbids this** - that
step must show all three `PlaybackModeCard`s without scrolling - and it has now failed in two
successive revisions.

`SetupSpecimen.Diagram.preferredHeight` goes 200 → **150 dp**, and the storyboard is budgeted to
fit that rather than the other way round. The tallest stage is the three chips at
`3 x 30 + 2 x 6 = 102`.

⚠ **`SetupSpecimenBand` now clips.** Every `preferredHeight` is arithmetic fitted by hand and then
capped against the window, so a specimen can be handed less room than it budgeted for - and
without the clip that overflow painted straight over the panel's text rather than stopping at the
seam. `Details` also went 380 → 400 dp with a smaller hero and seam to pay for the sections strip.

### Verification

**Pure suites, both repositories, identical: 67 + 29 + 49 + 17 = 162 tests, zero failures**, up
from 157. The five new cases pin the sequence rather than the drawing:
`classicsPointerVisitsEveryRowInOrderAndSkipsNone` is the one that matters - the pointer's offset
is animated between consecutive frames, so a skipped row is a visible jump, and revision 5 went
`0 → 2`.

⚠ **`SetupWizardRenderHarness` is now committed**, at
`NuvioZDesktop/composeApp/src/desktopTest/.../SetupWizardRenderHarness.kt`. This file has claimed
for three revisions that a harness was "provided"; **it was in neither repository** and lived in a
session scratchpad, so neither the next agent nor the maintainer could run it - the exact failure
that moved `scripts/run-pure-suites.sh` into the repo. Every defect this wizard has shipped was
something only looking could catch, and **nothing in either repository executed a line of its
Compose code** until now. It renders the frosted Welcome step at two widths in two palettes with
and without AMOLED, eight band variants at two widths, and every storyboard frame of every mode -
advancing the loop through `ImageComposeScene`'s virtual clock rather than a frame-override
parameter, because a second way into the drawing is a preview that can lie about the first. It
asserts each scene renders without throwing and writes PNGs to
`composeApp/build/setup-wizard-render/`.

**Parser check clean** over every changed file in both repositories - **necessary, never
sufficient**.

**Eight files confirmed byte-identical** across the repositories; `SetupHomeStill.kt` is the
deliberate exception at three hunks.

**CI is green in both repositories, first attempt each:**

- `nuvio-z` `debug-release.yml` run `31785524190` at `ee283cd9` - host suite and
  `:androidApp:assembleFullDebug`. Published as **`debug-v0.4.14-beta.11`** (versionCode 124011).
- `NuvioZDesktop` `ci.yml` run `31785506352` at `62fbcac7` - **both** jobs. The Windows MSI job
  compiles `desktopMain`, which is the only thing that proves the hand-ported `SetupHomeStill.kt`
  passes `dataSourceKey` correctly.

⚠ **And `SetupWizardRenderHarness > renderEveryWizardSurface` PASSED in that desktop run.** That
is a first: **the wizard's Compose code had never executed anywhere before this**. Every scene -
the frosted Welcome step composing `SetupHomeStill` and the real home sections through Haze, eight
band variants at two widths, every storyboard frame of all three modes - composed and rendered
without throwing. A runtime crash on any step would now be caught by CI rather than by a device.

**Not verified:**

1. ⚠ **Nobody has looked at the PNGs.** The harness *ran* in CI, but "Upload test reports" is
   `if: failure()`, so the images stay in the runner's workspace and are discarded. Run it on the
   Windows host to actually see them - or make CI upload
   `composeApp/build/setup-wizard-render/` as an artifact, which would let the output be read
   without a working Gradle at all. **Rendering without throwing is not the same as looking**, and
   every fault in this wizard's history was a looking fault.
2. ⚠ **The frost has never been seen**, and it is the highest-risk thing in this pass: revision 2
   failed on exactly this, and the API 30 case has no blur behind it at all.
3. **The band heights are still arithmetic.** `Details` at 400 dp is close to the `windowHeight *
   0.5f` cap on a phone, so the trailers rail is the part that clips first. Deliberate, and what
   the harness's 120 dp taller frames are for.
4. **Artwork has still never loaded here.** The harness renders with no network, so every poster
   and backdrop in those PNGs is a placeholder fill - which makes them a good aeroplane-mode check
   and a poor artwork one.

## Revision 5 of the setup wizard (2026-08-14, unreleased)

**Branch `claude/setup-wizard-final-pass-wy7csp`, cut from the phase-2 branch in both
repositories.** Revision 4 was looked at on a device and came back with four faults. Three are
presentation; the fourth made the flow unusable, and it is the same *class* of bug that
`syncKeysToClear` was written for in `0.4.0-beta`.

⚠ The section below this one describes revisions 1-4 and is kept for the process failures it
records. Where the two disagree, this section is current.

### 1. The wizard would not stay dismissed

**Reported as "the bug where the wizard won't go away is back, something to do with
configuration sync". It is, and the trace is short.**

`PlayerSettingsRepository.markSetupWizardCompleted` enforces "only ever increases".
`PlayerSettingsStorage.replaceFromSyncPayload` writes through the store **directly** and
bypasses it: it clears every key the payload carries and then saves the remote value
unconditionally. So a remote blob written by an older build carries revision 3, every startup
pull (`App.kt` → `SyncManager.pullAllForProfile` → `ProfileSettingsSync.applyRemoteBlob`) wrote
3 back over the local 5, `PlayerSettingsRepository.onProfileChanged()` republished it, and the
gate re-evaluated to "show the wizard". On every launch, permanently.

⚠ **It self-perpetuated, and that is the part worth remembering.**
`ProfileSettingsSync.startObserving()` was called inside `MainAppContent` - which the wizard
*replaces* while it gates the app. So nothing observed the wizard's writes, observing began
only after completion, and `combine(...).drop(1)` then discarded the first signature it saw:
the one carrying the revision written a moment earlier. Nothing else changes a setting right
after setup, so the remote never learned the new number and went on answering with the old one.

Three fixes, because each closes a different half:

- **`mergeMonotonicSyncInt`** in `core/sync/SyncPreferenceJson.kt`, beside `syncKeysToClear`
  and for the same stated reason - *the remote is authoritative for what it knows, never for
  what it has not caught up with*. Every `PlayerSettingsStorage` actual now reads the local
  revision **before** the clear and stores the larger of the two. ⚠ **Shared rather than
  repeated per platform**: there are three actuals in `nuvio-z` and four in `NuvioZDesktop`,
  and a rule copied seven times is a rule that drifts.
- **The wizard pushes on completion.** `complete()` launches
  `ProfileSettingsSync.pushCurrentProfileToRemote()`. `exportSettingsBlob` exports the whole
  blob, so this carries every choice the wizard made, and it does not depend on debounce timing.
- **Observing moved above the gate** in `App.kt`, under `ownsAppRuntime`. Settings written while
  the wizard is up are settings. `startObserving` is idempotent. ⚠ In `NuvioZDesktop` the call
  lives in `warmProfileBoundRepositories()` instead, and every call to *that* sits behind either
  `MainAppContent` or a profile switch - so the gate-level call is **added alongside** there
  rather than moved.

⚠ **`SETUP_WIZARD_REVISION` goes to 5, so the wizard appears once more on the first launch of
this build, by design.** That is the revision bump, not the bug. **The launch that proves
anything is the second one.** The two were already confused once.

### 2. The Welcome step showed a logo; it now shows the app

`app_logo_wordmark` over an accent wash read as a splash screen bolted onto a settings flow -
and the asset has "Nuvio" baked in as pixels above copy that says "Nuvio Z", a mismatch this
file has recorded twice as known-and-accepted. The wizard no longer draws it at all, which
removes one of the three places that mismatch appears.

In its place, `SetupSpecimen.Welcome`: a still of the home screen - banner, Continue Watching,
a catalog row. It answers *what is this?* with the app rather than with the one word the panel
underneath has already said, and it is an establishing shot for the three steps that follow,
each of which takes one row of it apart.

⚠ **The one specimen drawn at its own scale rather than at the app's real metrics.** Three real
rows stack to roughly 470 dp; no band a phone can give is that tall, and half a catalog row cut
off at the seam reads as a broken layout rather than as a screen continuing below the fold. Card
shape and corner radius are still honoured. Top-aligned and `clipToBounds`, so a band capped
short cuts the bottom row instead of shaving both ends.

`SetupDiagram.kt` now reads **no string and no drawable at all** - the wordmark was its last
resource - which keeps its "cheap to delete" promise intact.

### 3. The playback-mode diagram is a loop now, not a picture

Three grey bars, an arrow and a play circle were being asked to carry the difference between
three modes that differ only in *process*. The verdict was "vague as hell" and it was fair: a
still picture cannot show a process.

Each mode now loops through its own path from tapping a title to playing it. Classic stops on a
wall of releases and a finger walks down it; Streamlined asks one short question and then
settles a release **with no pointer anywhere**; Instant goes straight to play.

⚠ **The sequences live in a new import-free `features/setup/SetupModeStoryboard.kt`**, covered
by `scripts/run-pure-suites.sh`, for the same reason `SetupWizardSteps.kt` and
`StreamRouteSurface.kt` are import-free: the wizard is a Compose gate no test in either
repository can reach. "Streamlined picks the release itself" is a claim about the product and
should not rest on someone re-reading a layout. The case that matters is
`streamlinedPicksItsReleaseWithNoPointer` - both modes end with one release lit up, and the
pointer's absence *is* the difference between them.

⚠ **The three quality tokens (`4K` / `1080p` / `720p`) are Kotlin constants, not string
resources**, and are the only text in the whole diagram. They are locale-independent format
names the app already renders verbatim, so there is nothing to translate and nothing stranded
when the provisional diagram is deleted.

`SetupSpecimen.Diagram.preferredHeight` 180 → 200 dp for the five-row list. It has to stay
small: the playback-mode step has the tallest panel in the flow and revision 2 cut it off.

### 4. "Group sections into tabs" did nothing, anywhere

Revision 4's file comment called this "the one control in the wizard whose effect the band does
not show". ⚠ **That understated it. The control did nothing in the real app either.**

`normalizePreferences` seeds every section with `tabGroup = null`, and `ConfiguredMetaSections`
draws a `TabbedSectionGroup` only for a non-null group with **more than one** member. So on a
fresh profile `tabLayout = true` rendered identically to `tabLayout = false` in
`MetaDetailsScreen` - the switch moved and the page did not, until the user went and grouped
sections by hand in Settings. The wizard's own copy promises otherwise, and a first-run flow is
the last place that should offer a switch that does not move.

`setTabLayout` now seeds **Cast + Trailers + Details as one group** the first time tabs are
switched on and nothing is grouped yet - exactly the three the copy names, and three is the
per-group maximum `setTabGroup` already enforces. ⚠ **Only when nothing is grouped**: a user's
own arrangement must never be replaced by toggling the switch off and on. Switching tabs *off*
leaves the grouping in place for the same reason.

`SpecimenDetailSections` then draws it - stacked headings, or one `Cast | Trailers | Details`
row. ⚠ Mirrors `TabbedSectionGroup`: the `|` separator, the active heading at full opacity, the
inactive ones at **0.55**, the separator at **0.45**. If that treatment changes, change it here.

Height budget for `SetupSpecimen.Details`, which went 320 → **380 dp**: 110 hero + 18 seam + 14
gap + ~76 sections strip leaves ~160 for the episode list. The hero and the seam are the parts
that gave, because the strip and the episode list are what the step's controls act on. The
band's cap went `windowHeight * 0.45f` → `0.5f`; ⚠ that cap only bites on Cards, Home and
Details, so the playback-mode step is untouched by it.

### Verification

**Pure suites via `scripts/run-pure-suites.sh`, both repositories, identical: 67 + 29 + 44 + 17
= 157 tests, zero failures.** Up from 118, and the two new groups are the point:

- **`SetupModeStoryboardTest`, 20 cases**, no stubs - the file is import-free. Beyond the
  per-mode claims above it pins the loop itself: every mode starts on a title and ends on
  playing, walking forward from *any* frame returns to the start, a stale index outside the
  frame list restarts rather than crashing, and an unknown mode name falls back to Classic
  rather than to an empty band. The step gates the app; a blank band is a worse answer than the
  wrong mode's loop.
- **A fourth pure-suite group for `core/sync/SyncPreferenceJson.kt`**, which was previously
  covered only by CI. It is shared by every settings store on every platform, so a fault in it
  is a fault in all of them at once - which has now happened twice. Needs the kotlinx
  serialization runtime jars but **not** the compiler plugin: the file reads `JsonObject` and
  declares nothing `@Serializable`.

**Parser check clean** over every changed file in both repositories, `desktopMain` included -
**necessary, never sufficient.** Three of the four failures in this wizard's history were
ordinary cross-file resolution that a single-file parse structurally cannot see.

**Nine files confirmed byte-identical across the repositories** and copied rather than
hand-ported: the five setup files, the new `SetupModeStoryboard.kt`, `SyncPreferenceJson.kt`,
both changed test files and `run-pure-suites.sh`. ⚠ Four were hand-ported because they
legitimately differ: `MetaScreenSettingsRepository.kt` (desktop carries
`desktopHeroOwnedMetaSectionKeys` and a different `MetaScreenBackgroundMode` default), `App.kt`,
and the `PlayerSettingsStorage` actuals - three of them in `nuvio-z`, four in `NuvioZDesktop`.

**CI is green in `nuvio-z`** — `debug-release.yml` run `31782422013` at `7874f5cc`, first attempt:
the Android host suite passes and `:androidApp:assembleFullDebug` builds. That is the **first
time any of revision 5 has been compiled**; everything before it was the parser check plus the
pure suites. Revisions 1 and 2 each needed a second push to compile, 3, 4 and 5 did not.
⚠ **It says nothing about `desktopMain`** — `NuvioZDesktop`'s Windows MSI job is what compiles
that, and it runs on every push to that repository.

**Published for the revision-5 device run: `debug-v0.4.14-beta.10`** (versionCode 124010,
`versionName 0.4.14-beta.10`, `com.nuvio.app.z.debug`), cut from `7874f5cc`. It supersedes
`debug-v0.4.14-beta.9`, which carried revision 4. The backend secret decoded, so sign-in and
Trakt work — which matters here, because **device check 1 needs a signed-in account** to
exercise the settings pull that was re-gating the app.

**Not verified:**

1. ⚠ **Nothing Compose has been rendered, again.** Gradle still cannot configure in the sandbox.
   The storyboard is the first thing here with *timing*, and neither the parser check nor the
   pure suites can see a frame. Run `SetupWizardRenderHarness` on the Windows host; advance the
   storyboard with `ImageComposeScene`'s virtual clock rather than adding a frame-override
   parameter, because a second rendering path is a preview that can lie.
2. **The band heights are arithmetic, not observation** - `Details` at 380 and `Welcome` at 344
   were fitted by hand. `Welcome` is the one that clips by design, so confirm what it cuts.
3. **The seeded tab grouping has never been seen in the real details screen**, only reasoned
   about from `ConfiguredMetaSections`. Device check 5 below.
4. The metahub artwork URLs have still never returned a byte here.

## Phase 2 of 0.5.0-beta: the setup wizard (2026-08-12, unreleased)

**Branch `claude/onboarding-setup-wizard-7juovt`, cut from the phase-1 branch in both
repositories.** Phase 1 fixed the Streamlined flow; this is the other half of a first
impression. Until now the app's entire onboarding was one full-screen question about playback
mode, asked before the user had seen anything it applied to - and every visual option sat
behind five sub-pages of `Settings → Appearance` that nobody was going to find.

⚠ **Three earlier shapes shipped and were wrong.** `debug-v0.4.14-beta.6` was preset-first;
`.7` previewed a whole fake home screen behind a translucent sheet; `.8` moved the preview to
whichever control was last touched. See "What the earlier attempts got wrong". Do not restore
any of them.

### The shape

**Two opaque regions, and nothing is ever drawn behind the text.** A full-bleed specimen band
on top, an opaque panel of controls below, a hairline between them. Readability is a property
of the layout rather than something to re-check in every theme - which is what revision 2 got
wrong, and badly: on a device the home screen read straight through the sheet, and because the
sheet's gradient was most transparent at its top edge, the worst of it was behind the heading.

**The band shows what the current step changes.** Eight steps: Welcome → playback mode → cards
→ home → details → theme → sources → done. Sources is **dropped, not shown-and-skipped**, when
the profile already has an enabled addon.

Appearance is four steps grouped by surface, down from six. Two of the six carried a single
control each - a whole screen, a whole preview and two taps to answer one toggle. Nothing now
exceeds four controls, so **no panel scrolls on a phone**, which is what put the Cards step's
first control group off-screen and cut the playback-mode step off mid-card in revision 2.

⚠ **The band is fixed per step and does not move while you are on it.** Revision 3 held the
current specimen in state and let each control move it, on the reasoning that every control
should change something visible. It does achieve that, and on a device it was still worse: the
object being studied kept getting swapped out mid-thought. The merged steps now draw everything
they cover at once - Home is the banner *and* the Continue Watching row, Details is one small
details screen - and the controls change that in place. There is no specimen state left.

The one control whose effect the band cannot show is "Group sections into tabs", which regroups
the sections *below* the episode list. It is commented as such at the call site.

**Trakt is gone** (revision 4). It offered a connection that is not functional yet, and a
first-run flow that asks for an account it cannot use is worse than one that does not ask.
`TraktAuthRepository` is untouched - the settings page still owns it.

### The specimens are purpose-built, and that was a reversal

Revisions 1 and 2 both rendered the **shipped** composables - `HomeHeroSection`,
`HomeContinueWatchingSection`, `HomeCatalogRowSection`, `DetailHero` - on the argument that a
preview built from the real thing can never drift from the app. That argument is true and it
was still the wrong trade:

- Those composables read their settings repository **internally** and apply a change the
  instant it is written. You cannot tween between two values you never hold, so every choice
  snapped. Smooth transitions were the maintainer's specific ask.
- They own their own section padding and sizing, so they cannot be framed full-bleed at a
  chosen height.
- ⚠ **They diverge between the repositories.** Desktop's `HomeContinueWatchingSection` takes a
  *required* `dataSourceKey` this repository's does not, so `SetupPreviewStage.kt` was the one
  setup file that could never be `cp`'d and had to be hand-maintained twice.

`SetupSpecimen.kt` draws from primitives, takes **every** setting as a parameter and reads no
repository. That buys the tweening, the framing, and - because it calls nothing divergent -
**every setup source file is now byte-identical in both repositories**, so `diff -q` is a real
check on them rather than a formality.

⚠ **The cost is a second implementation that can drift from the real cards, and nothing will
catch it.** The file header carries the table; the four things it mirrors are `NuvioPosterCard`
(via the `internal` `landscapePosterWidth` / `landscapePosterHeightForWidth` and a copy of the
0.675 poster aspect), `HomeContinueWatchingSection`'s three styles and its 18 dp blur,
`MetaDetailsScreen`'s three background treatments including the 0.92 scrim and the 0.42
dominant blend, and `DetailSeriesContent`'s two episode card styles. **Change one of those and
change this too.**

The apply-as-you-go mechanic is unchanged: every settings repository here is a singleton
`MutableStateFlow`, and the wizard writes each choice through the real setter the moment it is
tapped. There is no undo, which is how every settings page here already behaves.

⚠ **Band heights are hand-fitted arithmetic and will clip if content grows.** Each
`SetupSpecimen` carries a `preferredHeight` sized against its content at the *largest* settings,
capped at 45% of the window. The tight ones are commented in place: `Home` (150 dp banner + 14
+ the Poster-style Continue Watching row, inside 330) and `Details` (126 hero + 22 seam +
episodes, inside 320). **None of these sums has ever been checked against a renderer** - see
the harness note under Verification.

⚠ **The details specimen must keep matching `MetaDetailsScreen`, and it has already drifted
once.** It blurred Cinematic at 18 dp for a whole release where the real screen uses **30 dp**
under a `background @ 0.92` scrim, which overstated that mode badly and is part of why the
maintainer could not tell what the background step was showing. Three things to hold: the 30 dp
blur, the 0.92 scrim, and that **only `DominantColor` tints the hero** - `heroGradientColor` is
passed for that mode and null for the other two, and that tint reaching into the hero is the
single most visible difference between the three. Normal and Cinematic looking similar is
correct; about 8% of the artwork survives that scrim in the real app too.

### The product is "Nuvio Z", and the copy mostly still says "Nuvio"

The Android label, applicationId, launcher icons and downloads notification all say **Nuvio Z**.
**42 of the 43 product-name strings in `values/strings.xml` say "Nuvio"**, including the
canonical `app_brand_name`, and every one of those has ~20 locale variants that say the same.

Revision 4 renamed **the setup wizard's own copy only** - `setup_welcome_title`,
`setup_welcome_body`, `setup_home_subtitle`, `setup_sources_subtitle`, `setup_sources_body` -
on the maintainer's instruction, because that is what was on screen when they noticed.
`playback_mode_selector_*` is deliberately untouched: it is shared with the settings dialog.

⚠ **The rest is a known, deliberate inconsistency, not an oversight.** A full rename is 42
English strings plus ~20 locale files, and it should be its own change rather than riding along
inside a UI pass. Two things it must also cover:

- `app_logo_wordmark.png` has "Nuvio" **baked in as pixels** and is drawn on the splash screen,
  both auth screens and now the wizard's welcome step. Strings cannot fix it; the asset needs
  redrawing.
- `settings_licenses_attributions_nuvio_title` says "Nuvio Mobile", a third variant.
- iOS `PRODUCT_NAME` in `Config.xcconfig` is still `Nuvio`, so the iOS home-screen name is wrong.

### Sample artwork: two hosts, one of them unproven

`images.metahub.space` is keyed by the **show's** IMDb id, so it has no per-episode images -
which is why the episode list showed one frame repeated. `SetupSampleTitle.episodeStillUrl`
uses the sibling host `episodes.metahub.space/<imdbId>/<season>/<episode>.jpg`, keyed by
episode and equally keyless.

⚠ **That host has never returned a byte here** - the sandbox blocks metahub, as it does the
show-artwork host. Every still falls back to the show backdrop on error, which is the same
chain `DetailSeriesContent` uses (`video.thumbnail ?: meta.background ?: meta.poster`), so a
dead host degrades the specimen to precisely what the app shows for a series with no episode
artwork. **If the stills come back identical on a device, the URL shape is wrong and the
fallback is hiding it.** Device check.

The details subject is Breaking Bad (`tt0903747`), and `rowItems` must keep it **first**: the
Continue Watching specimen captions its in-progress card with a named episode of the featured
title, so a different show in slot 0 would claim one series is playing another's episode.

⚠ **The illustrative diagram on the five non-visual steps is provisional.** The maintainer
approved it with "be prepared for me to tell you to remove it". It is therefore one file,
`SetupDiagram.kt`, with one public composable and exactly one call site; it is wordless, so it
holds no string keys; and it uses no `Canvas`, no assets and no animation of its own. Removing
it is: delete the file, replace the `Diagram` branch of `SetupSpecimenBand` with a `Spacer`.

### Sample artwork is fetched, never bundled

Poster art is copyrighted, `Zokaper/nuvio-z` must stay public, and every release ships a signed
APK and an MSI. `SetupSampleTitle` holds IMDb ids plus constant public artwork URLs on
`images.metahub.space`, which needs no API key and no installed addon and is keyed by IMDb id -
one id yields poster, backdrop **and** logo, the exact triple the wide-card and blurred-art
options need to look different. **TMDB cannot do this job**: `TmdbService.currentApiKey()` is
null until the user enters a personal key, so a first launch has no TMDB access.

⚠ **Those URLs are still unverified.** The sandbox egress policy answers 403 to
`CONNECT images.metahub.space:443`. Device check 1.

⚠ **The wizard must be fully usable with no network.** The stage paints a token gradient behind
the artwork and `NuvioPosterCard` already draws a titled placeholder. Device check 2.

### Shown once per profile, by revision

`setup_wizard_completed_revision` on `PlayerSettingsStorage` (profile-scoped, synced, four
actuals), with **`SETUP_WIZARD_REVISION = 2`**.

⚠ **An integer, and both alternatives are worse.** A boolean can never re-ask when a later
release changes the flow. The app version - what `WhatsNewStorage` stores - would re-show the
whole wizard on *every* release. **Revision 2 exists precisely because revision 1 shipped**:
anyone who completed the preset-fork wizard answered a flow that no longer exists and never saw
most of these options. A *higher* stored revision does not re-show; the value syncs, so a
profile can arrive from a newer build.

`syncKeysToClear` was **not** touched. Finishing writes both `markSetupWizardCompleted` and
`markPlaybackModeSelectorSeen`, the latter so a downgrade to 0.4.x does not re-prompt.

Settings → About → **Run setup again** re-runs it dismissible, over `MainAppContent` rather
than gating it, indexed by `SettingsSearch` as `run-setup-again`.

### What the first two attempts got wrong

Worth keeping in full, because most of these are process failures rather than design ones.

**Revision 1 (`debug-v0.4.14-beta.6`)**

1. **The shape.** Presets did the choosing, so most users would never have reached the
   individual options at all, and the live preview the whole feature was built around got
   looked at once. Replaced by one topic per step.
2. **The preview was a scale model of a phone even on desktop.** Correct for the layout it was
   in; wrong once the preview became the background.
3. ⚠ **It did not compile, and the parser check said it was fine.** The first
   `debug-release.yml` run failed on five Compose errors, fixed by a second agent in `247be94`
   (nuvio-z) and `8e0f5b0` (desktop): two string keys used in `SettingsRootPage.kt` without
   the explicit imports that file requires, a `private` `HomeCatalogSettingsRepository.ensureLoaded()`,
   `maxHeight` read inside a nested layout receiver, and a desktop `AppSettingsTabContent`
   argument with no matching parameter. **Three of those four are ordinary cross-file
   resolution that a single-file parse structurally cannot see.** The rule `AGENTS.md` already
   states held: the parser check is necessary and never sufficient, and **CI is the gate**.

**Revision 2 (`debug-v0.4.14-beta.7`)** - and this one is the important entry, because it
**compiled cleanly, passed every suite, and was still bad on a screen**.

4. ⚠ **The translucent sheet was not readable.** The home screen showed through it - "Continue
   watching", episode titles, poster art, all behind the heading. This was written down as a
   known risk *in the plan*, shipped anyway on the reasoning that the alphas were tuned high
   enough, and it was the first thing the maintainer saw. The gradient made the sheet's **top**
   edge the most transparent part, which is where the heading sits, so it was worst exactly
   where it mattered most. Fixed by construction: opaque panel, no overlap, nothing behind text.
5. **Previewing a whole fake screen was the wrong idea.** Most of the band had nothing to do
   with the control being changed, and the per-step scroll anchoring that tried to correct that
   left rows half-clipped at the top. Now: only the component the step changes.
6. **Two steps carried one control each**, so the flow was longer than the content justified,
   while the steps that *did* have content overflowed and scrolled internally.
7. **Seven strings rendered a literal backslash** - `We\'ll`, `You\'ll`. Compose Multiplatform
   resources do not honour Android's `\'` escape. The other thirty apostrophes in the file are
   bare, so the convention was already there to copy and I invented a different one.

⚠ **The reusable lesson from 4 is not about alpha values.** "CI is the gate" was the lesson
from revision 1 and it is still true, but revision 2 shows it is not sufficient either:
**compiling is not looking.** A rendering pass is the cheapest thing that would have caught it,
and there was none. See the harness note below.

**Revision 3 (`debug-v0.4.14-beta.8`)** - "significantly better", and still four things.

8. ⚠ **Making the preview follow the last-touched control was a mistake.** It was introduced to
   guarantee no control changed nothing visible, which it did. On a device it read as the thing
   being studied getting swapped out mid-thought. **A preview that moves is worse than a
   preview that shows one thing less.** The merged steps now draw everything they cover at once.
9. ⚠ **Chip labels were never centred.** `SetupChoiceGroup` gave its label `weight(1f)` and no
   `textAlign`, so every chip in the wizard read hard left. It is visible in the *first*
   revision-2 screenshots and went unnamed for three rounds by three sets of eyes, mine
   included. Cheap to fix, embarrassing to have shipped, and exactly the class of thing a
   render pass surfaces immediately.
10. **Three abstract swatches could not explain the background modes**, because the thing that
    most separates them is a hero tint and the swatches had no hero. Replaced by one small
    details screen. The Cinematic blur had also drifted to 18 dp against the real 30 dp.
11. **The episode list showed the same frame three times**, because the artwork host has no
    per-episode images. Fixed with a second host, unverified - see "Sample artwork" above.

⚠ **The pattern across 8 and 10 is worth naming.** Both were mechanisms added to *guarantee* a
property - "every control does something visible", "you can compare all three at once" - that
cost more in legibility than the property was worth. Neither was wrong on paper. Both needed a
screen to judge, and neither got one before shipping.

### Verification

**Pure suites via `scripts/run-pure-suites.sh`, both repositories, identical: 67 + 29 + 22 =
118 tests, zero failures.** `SetupWizardSteps.kt` is **import-free** like
`StreamRouteSurface.kt`, so this group needs no stubs at all.

The cases worth naming are `everyStepInEveryPlanReachesTheEnd` and its mirror
`everyStepInEveryPlanReachesTheStart`: from any step, in any plan, walking forward terminates
and walking back reaches Welcome. A wizard that gates the app and can be entered at a step it
cannot leave is the failure the whole file exists to prevent - and it is reachable for real,
because installing an addon on the Sources step removes that step from the plan under the
user's feet.

`aSavedStepThatNoLongerExistsFallsBackToTheStart` matters more with every revision. The wizard
persists its position **by name** so that reordering the enum cannot resume someone on the wrong
step - and revision 3 deleted `ContinueWatching` and `Episodes` while revision 4 deleted
`Trakt`, so a wizard restored across an app update can be holding any of them.
`setupStepForSavedName` answers `Welcome` rather than throwing, because this gates the app and a
crash here is one the user cannot get past.

**Parser check clean** over every changed file in both repositories - **necessary, not
sufficient**; see point 3 above.

**Every setup file is now byte-identical across the repositories** - `SetupWizardSteps.kt`,
`SetupSpecimen.kt`, `SetupDiagram.kt`, `SetupWizardScreen.kt`, `SetupSampleTitle.kt`,
`SetupWizardStepsTest.kt` and `run-pure-suites.sh`. Revisions 1 and 2 could not say that:
`SetupPreviewStage.kt` called divergent screen composables and had to be hand-maintained in
both. That hazard is gone with the file.

**String keys cross-checked both ways in both repositories**: every `Res.string.*` the setup
package references exists, and no `setup_` key is defined without a reference. That is the
mechanical half of the miss that broke build 6 - the other half, the host file's import style,
is unchanged here because no new key went into `SettingsRootPage.kt`.

**CI is green in both repositories on the first push** - `nuvio-z@24e8bb4` (run 31712467776:
host suite and `:androidApp:assembleFullDebug`) and `NuvioZDesktop@7a553ab` (run 31712454251:
`ci.yml` including the Windows MSI job, which is what compiles `desktopMain` and therefore the
wizard's desktop path). Revisions 1 and 2 each needed a second push to compile; 3 and 4 did not.

**Not verified:**

1. ⚠ **Nothing Compose has been rendered here, again.** Gradle still cannot configure in the
   sandbox - `com.android.application:9.2.0` is unresolvable because `dl.google.com` is
   blocked - so the `ImageComposeScene` harness cannot be run from here. **This is the gap that
   let revision 2's unreadable sheet reach a device**, so it is worth closing on the machine
   that can: a ready-to-run `SetupWizardRenderHarness.kt` is provided. It renders every
   `SetupSpecimen` at 420 dp and 1100 dp, at the smallest and largest card settings, in every
   Continue Watching / episode / background variant, in all seven palettes and in AMOLED, and
   writes PNGs to `composeApp/build/setup-wizard-render/`. Each band is drawn inside a frame
   120 dp taller than its declared height, so content overflowing shows as a spill rather than
   being cropped by the scene edge. Unlike revision 2's stage this is now *possible*, because
   the specimens take every setting as a parameter and touch no repository.
   **Delete the harness again after reading the output** - it asserts nothing.
2. **The band heights are arithmetic, not observation.** They were fitted by hand against the
   largest settings each specimen can be asked to draw. Clipping is what the harness above is
   for, and failing that, device check 2.
3. The metahub artwork URLs have still never returned a byte here - the sandbox blocks that
   host. **`episodes.metahub.space` is new in revision 4 and is the least proven thing in this
   change**: its URL shape is inferred from the ecosystem's conventions, not observed.
4. **The chip-centring fix is one line and has not been seen either.** It is the first thing to
   confirm, because it is the one defect here that was visible in a screenshot from the start.

## The setup wizard device script

Run after the phase-1 script.

Checks 0a-0c are the newest faults - the still that read as messy - and come first. Checks 1-6 are
the revision-5 faults. Checks 7-11 are the revision-4 faults, which revision 6 rewrote three of and
must not have broken. The rest are the revision 2 and 3 regressions, which must not come back with
any of them.

0a. ⚠ **Welcome reads as a screenshot with a sheet over it, not a diagram of one.** Three
   specifics, because these are what came back: **nothing floats** - the nav bar is either pinned
   to the very bottom edge or hidden behind the panel, never sitting mid-screen over a row;
   **nothing is squeezed** - the poster row is cut off by the panel's edge at full size rather
   than shrunk to fit above it; and the hero is a **normal hero**, framed on its logo and metadata
   line, not a banner filling the frame or a stub.
0b. ⚠ **The panel is frosted, not solid** - colour and shape from the still come through it while
   the text stays readable. Then check 3 below on an old device, which is the other half of this
   and the one that constrains it.
0c. ⚠ **Drag the still.** It must not scroll, bounce or move at all. It is a real `LazyColumn`
   underneath.

1. ⚠ **Settings → Run setup again, then tap the preview band, the paragraphs and every gap, on
   every step.** Nothing behind the wizard may react. Open **Settings → Licenses & attributions**
   first for the original repro - that page is where the GitHub and premiumize.me links that got
   opened actually live. This check did not exist before and is the one that was missing.
2. ⚠ **Welcome reads as the real home screen** - hero, section headings, a Continue Watching row,
   catalog rows, the floating nav bar - **and the rows are visible**, not just the banner. Then
   the harder half: **is the panel's text legible over it?** Check the heading specifically; that
   is where revision 2's sheet failed.
3. ⚠ **The same, on an Android 12-or-older device.** `Modifier.blur` and Haze's `RenderEffect`
   path both need API 31, so below that the frosted panel is a plain scrim. **This is the check
   that matters** - if it only reads well on a modern phone, the alphas are too thin.
4. ⚠ **The playback-mode loop is smooth and nothing shifts sideways.** Watch the quality chips as
   the pointer lands on one: the chip must not move. Classic must show three lines of release
   text with a finger walking **every** row in order, and Streamlined must settle on a release
   **with no finger on it**. If Streamlined and Classic look the same, the one thing this
   animation exists to say has failed.
5. ⚠ **All three `PlaybackModeCard`s are fully visible without scrolling the panel**, including
   Instant's "Not available in this version" line. This has now been clipped in two successive
   revisions.
6. ⚠ **Cast and Trailers show content on the details step** - avatars with initials and names,
   and trailer thumbnails with artwork - both tabbed and stacked, and **on an AMOLED theme**,
   which is where the old placeholders were literally invisible.
7. ⚠ **Finish the wizard, force-stop, relaunch → no wizard. Relaunch a third time → still no
   wizard.** Then **sign out and back in** → still no wizard. This is the revision-5 gate fix and
   it needs more than one relaunch because the pull that used to re-gate the app runs at startup.
   ⚠ **The very first launch of this build WILL show the wizard** - the revision went to 6. That
   is expected; the second launch onwards is the check.
8. ⚠ **The details step's tab toggle changes the band**, and the section headings below the
   episode list become one `Cast | Trailers | Details` row.
9. ⚠ **Then open a real film or series and confirm the same thing happened there.** This is the
   half that was silently broken before revision 5 - the switch moved and the page did not. Check
   a title that actually has cast, trailers and details; with only one of the three present the
   app correctly draws no tab row.
10. ⚠ **Chip labels are centred.** "Poster / Wide", "Dense / Balanced / Large", "Sharp /
   Classic / Pill", "Card / Wide / Poster". This shipped left-aligned in three builds; it is
   the cheapest thing here to confirm and the most embarrassing to miss again.
11. ⚠ **Nothing renders behind the panel text, on any step.** If any artwork, row title or
   poster is visible through the panel, the layout is wrong, not the alpha.
12. ⚠ **The band does not move while you are on a step.** On Home, toggle the banner and then
   change the Continue Watching style: the banner should expand and collapse *inside* a band
   whose top and bottom edges stay put, and the Continue Watching row must remain visible in
   both states. On Details, all four controls act on one mock. If the band swaps what it is
   showing, revision 3's behaviour has come back.
13. ⚠ **Episode stills differ per row.** This is the `episodes.metahub.space` check and the
   least proven thing in the change. If all three rows show the same image, that host or the
   URL shape is wrong and the backdrop fallback is hiding it - say so rather than assuming it
   is fine, because the fallback makes failure look like a design choice.
14. **No step scrolls inside the panel**, at default font size on a phone. The playback-mode step
   must show all three `PlaybackModeCard`s; the Cards step all four control groups starting with
   "Card shape". Check the band is not clipping either - a card cut off at the top or bottom
   means a `preferredHeight` is too small.
15. **The band tweens, it does not snap.** On Cards change size, corners, and poster↔wide: each
   should animate. Toggling titles should slide them in rather than jump.
16. **Step transitions slide in the direction of travel**, and Back visibly reverses Next.
17. **The details step's three backgrounds are distinguishable.** Plain and Blurred art are
   *supposed* to look close - the real screen scrims the blur at 0.92. What must be obvious is
   Matched colour, where the tint reaches into the hero's bottom fade.
18. **Fresh profile → the wizard appears and the sample artwork loads.** Poster, backdrop and
    logo all present. If a logo is missing, swap that IMDb id in `SetupSampleTitle`.
19. **Aeroplane mode, fresh profile.** Every step readable and every option distinguishable with
    no artwork. Cards should show their title on the skeleton fill, not be blank grey boxes.
20. **Android API 30 or below**, beyond the frost in check 3. `Modifier.blur` is a no-op there, so
    "blur what's next" and "blur unwatched episodes" look inert in the band - the same as in the
    real app, so this is expected. Confirm nothing else differs.
21. **Skip for now** on the welcome step → the app is exactly as it is today, and the wizard does
    not return on relaunch. ⚠ It is on the frosted panel now, not in the old footer.
22. Covered by check 7, which is the same test done properly. Left numbered so the rest of this
    list keeps its numbering across revisions.
23. **Second profile** → the wizard runs again for it, and its choices do not disturb the first
    profile's.
24. **Upgrade from `debug-v0.4.14-beta.10`** → the wizard appears again, because the revision went
    to 6. Intended, not a bug, and not the same thing as check 6. If the device had a wizard open
    when it updated it must resume on Welcome rather than crash - `Trakt` was deleted from the
    enum in revision 4.
25. Settings → About → **Run setup again** → opens dismissible, escapable in one Back press,
    does not gate the app.
26. **Theme step:** all seven palettes and AMOLED. Band and panel must both stay legible, and the
    band's sample button, progress bar and chips should take the accent.
27. **Sources step with a deliberately bad URL** → a named error, still skippable. Then a good
    one → "Added <name>", and the Sources step is absent on a re-run.
28. **Copy reads "Nuvio Z"** on Welcome, Home and Sources, and the apostrophes render as
    apostrophes ("We'll", not "We\'ll"). The wizard has not drawn `app_logo_wordmark.png` since
    revision 6, so the "Nuvio" / "Nuvio Z" mismatch is gone from this flow - ⚠ but it is **still
    on the splash and both auth screens**, which draw that same PNG. Redrawing it fixes both.
29. **No Trakt step.**
30. **Desktop, resized wide and narrow:** the band stays full-bleed at both, the panel stays
    centred and capped at 620 dp rather than stretching, and nothing overflows horizontally.

A step that was not run is not a pass.

## The 0.5.0-beta polish pass (2026-08-11, unreleased)

**The first build going to other people**, so this is a bugfix pass rather than a feature
release. Instant stays withheld. Reading the Streamlined flow end to end turned up one family
of faults rather than a list of unrelated ones:

> **Every dead end that was not one of the two fixed in `0.4.10-beta` still left the user on a
> covered screen with nothing behind it.**

`entry<StreamRoute>` stacks four things over one `StreamsScreen` — the opaque hand-off surface,
the quality sheet, the progress overlay, and the list itself — and each was decided by its own
inline expression over the same six flags. Nothing held the rule that matters: whatever is on
top, the user must be able to act on it.

### 1. Backing out of the player landed on a blank screen

This was flagged in `0.4.10-beta` as check 1 and never run. It is real, and it was reachable on
**every** Streamlined play.

A mode with a failure chain deliberately leaves `StreamRoute` on the back stack, and
`NavDisplay(onBack = { navController.popBackStack() })` pops the player straight onto it. The
in-app back *button* calls `onBackToDetails`, which pops past it — which is why this was never
seen by whoever tested with the button. The **system Back gesture** is the common path and lands
there. `playbackRouteDecision` was a plain `remember` and `NavDisplay` composes only the top
entry, so it came back null, while `reuseHandled` — which is saved — stayed true and blocked the
effect that would set it again. No sheet, no overlay, opaque surface still painting.

⚠ **The surface consumed no pointer input**, so the invisible source list underneath was fully
tappable. A blank screen that starts a random episode if you touch it.

Fixed by `streamRouteSurface` (new `features/playback/StreamRouteSurface.kt`), which decides the
whole stack in one place. **It has no imports and never may** — that is what lets the route's
covering rules be compiled and run outside Gradle, which they never could be before.
`PlaybackProgress.isVisible` is gone: it answered only "does the overlay cover the list?", and
hiding the overlay while the surface underneath stayed up traded a blank screen for a blank
screen one layer down.

⚠ **`playbackRouteDecision` is saved, not re-derived.** Re-running the router on the way back is
not a substitute: the play just wrote a reuse-last-link entry, so the same inputs answer
`ReuseLastLink` where they first answered `AutoPick`, and Instant's retry chain is gated on that
answer. `openSelectedStream` also now sets `playbackHandedOff`, which that flag's own comment
already claimed happened at every exit to playback — it did not, and the Streamlined sticky-pin
path reaches the player through there.

### 2. Three more dead ends, all the same shape

Routed through one `fallBackToSourceList`, which always says something:

- **Declining the P2P consent dialog** retired the chain and set no flag, so the overlay sat on
  "Starting playback" for a playback that had just been called off. Retiring is correct —
  declining P2P is a decision about every torrent candidate, not about this one.
- **`requestOrOpenP2pStream`'s two early returns** called `skipAutoPlayStream` and discarded the
  answer, so a refusal on the last candidate advanced to nothing.
- **Uncached "Start anyway"** called `openSelectedStream` directly, making it the one Streamlined
  start with no chain behind it — and the start most likely to need one. It now seeds a chain of
  one, which buys the *path* rather than the fallbacks.

### 3. The mid-playback retry could not work

`STATUS.md` recorded this as "verified by reading, not by running". Reading it again against
`StreamsRepository` says it did not hold.

`consumeAutoPlay` clears `activeRequestKey`, so returning to the stream route after a play always
misses `load`'s no-op guard and does a full reload — and that reload was a blanket
`_uiState.value = StreamsUiState(requestToken)`, discarding `autoPlayStream` and
`autoPlayCandidates`. So `failOverAfterPlaybackStarted` re-armed the chain, popped to the stream
route, and the route's `StreamsScreen` re-mount wiped it. Nothing refilled it either: Streamlined
and Instant both load with `manualSelection = true`, so `isAutoPlayEnabled` is false.

A chain armed for the same request now survives, carried across the resets on the way *to* a
result but **not onto a terminal empty state** — "no addons installed" is not a screen a retained
candidate should be playing over. The rule is hoisted into `carriedAutoPlayChain` /
`withCarriedChain` so it is executable.

### 4. Best available was ranked by different rules from every other card

`rankingFor` leads with three keys — implausible sizes last, torrents behind everything, then
evidence of a cached copy. `bestAvailable` sorted with a **bare** `SourceRanking.comparator` and
applied none of them, so the top card, the one most people tap, was the only place the
catalogue's worst traps still led. `LARGEST_UNDER_CAP` sorts size descending, so an 85 GB "1080p"
season pack headed it every time — the precise defect `0.4.9-beta` fixed for the banded rows and
never applied here.

⚠ **It failed silently, which is why nothing caught it.** `requiredMbpsFor` returns null above the
plausibility ceiling, so a card led by a season pack drew no bandwidth figure and no connection
meter at all. The ceiling was protecting the label while the pick walked straight past it.

### 5. Two unbounded waits

- `isStreamlinedSelectionReady` closes every *known* way the settle signal fails to arrive, but it
  is still a wait on a condition owned by addons the app does not control. Twenty seconds, then
  the source list with a reason. One new string key, `playback_sources_timed_out`.
- A **backstop** for the dead ends nobody has found yet: overlay showing, no candidate armed,
  nothing resolving, fetch settled and matching — so whatever it is waiting for is not coming.
  Reachable today by a retry whose reload lands on a terminal empty state. The grace period is
  what makes it safe: every legitimate state there is transient.

### 6. Codec, HDR and audio-language preferences now apply to playback

Named twice in this file as a real defect deferred to its own commit. Two findings changed the
shape of the fix:

- ⚠ **`PlaybackSourceSelector.rank` had no *production* callers.** It was one of the two places
  listed as needing preferences wired in, which would have been wiring them into a function no
  playback ever reached. Deleted; `PlaybackQualityOptions.rankingFor` is now the only ordering.
  ⚠ **It did have one test caller**, and the first claim here said "no callers in either
  repository" - which was wrong, because the grep behind it covered `commonMain` only. That
  broke `testAndroidHostTest` on the first real CI run of this branch. The old comparator now
  lives in `PlaybackSourceSelectorTest` as `rankForGateTests`, unchanged, because those cases
  are about the protocol and cache gates and only need a deterministic input order.
  **`PlaybackSourceSelectorTest` is the one file the standalone harness cannot compile** - it
  reaches the real AIO types - so it is exactly where a deletion like this hides.
- **Only one of the three preferences existed.** Codec and dynamic range lived solely on
  `DownloadPreset`, so this adds `playback_codec_preference` and `playback_dynamic_range_policy`
  as profile-scoped keys with all three actuals across both repos, settings rows, search entries
  and both sync paths — through `syncKeysToClear`, unchanged.

⚠ **`ANY` means "no opinion", not "prefer nothing".** `preferencesFor` sets dynamic range *by
resolution* on purpose and that reasoning is sound, so an explicit setting composes with it rather
than replacing it. Leaving the new rows alone keeps today's behaviour exactly.

⚠ **`default`, `device` and `original` are not languages.** They instruct the player's own track
selection and match no release. `PlayerSettingsUiState.rankableAudioLanguage` resolves them in one
place, because `PlayerNextEpisodeAutoPlay` builds its own selection context — a rule applied in
only one of them holds for the first episode and not the next.

### 7. Android has a stall watchdog at last

Flagged after `0.4.10-beta`. Desktop has polled and force-closed a silent stream since
`0.4.5-beta`; Android sat on OkHttp's hardcoded 60-second read timeout, which consulted
`DownloadsTiming` not at all. Cancelling the call unblocks the read; the flag is set **before** the
cancel, and checked before `isCancelled`, because a user pause arrives as the same exception and
reporting a stall as a pause leaves the queue waiting for a resume that never comes.

Both deadlines now come from one rule in `DownloadTransfer.kt`: **the watchdog must decide before
the read timeout**, because only the watchdog knows why the connection ended.

### 8. A dead debrid link looped forever inside the player

**Reported after the rest of this pass landed**, and it is the one a user actually hits first:
choose a source from the Streamlined sheet, get the loading screen with the series logo, the
video loads, the player shows for about a second, then back to the loading screen — forever.

⚠ **It is inside the player**, which is why nothing above bounded it and why the failure chain
never ran. `tryRefreshCredentialedSourceAfterError` guarded against repeating itself by
remembering the URL it had already tried, and **the reset block keyed on `activeSourceUrl` nulled
that guard** — while a successful re-mint is precisely what changes `activeSourceUrl`. The next
line of the same block sets `initialLoadCompleted = false`, which is the logo overlay coming
back. Two independent reasons the guard never bit: it was cleared every iteration, and a re-mint
returns a freshly signed URL so the comparison would have failed anyway.

⚠ **`onError` returns early when the refresh is accepted**, so `onFatalPlaybackError` was never
invoked. Every error was swallowed. The failure chain, the attempt counter and every bail-out in
items 1–5 above were unreachable on this path.

Bounded by a budget now, not by a URL, and scoped to the item being watched rather than to the
source — because re-minting *is* a new source. One refresh: the premise is a link that expired
while playing, so a fresh one fixes it; if the replacement also dies in a second, the source is
the problem and declining is what lets the chain name it and move on. A deliberate source pick
by the user earns a new budget; an automatic retry does not.

**Not Streamlined-specific in the code** — any debrid source that fails early reaches it.
Streamlined hits it constantly because it picks one without the user vetting it, and essentially
every debrid link satisfies `hasLikelyExpiringPlaybackCredentials` (the key set includes the bare
`t` and `e`).

### 9. The stream route re-decided itself on every return

Found while tracing item 8, and it silently defeated item 1 for the content being tested.

`effectiveVideoId` is resolved asynchronously, and its effect — which restarts every time the
route re-enters composition, so every return from the player — **blanked it to `launch.videoId`
first**. So the value went resolved → placeholder → resolved on each return.
`playbackRouteDecision`, `reuseHandled` and `autoPlayHandled` were all keyed on it and were
therefore discarded twice per return, and `StreamsScreen` issued **two full stream loads**, the
first against the parent id.

That is exactly the saved decision item 1 added to stop the blank screen — discarded, for series
episodes specifically. Movies were unaffected. They now key on `route.launchId`, as every other
flag in that route already did, and the resolve effect no longer blanks an id it has resolved.

### 10. Backing out of a player that had not started relaunched it

Found by re-reading this branch, not by report, and the same shape as item 8: a cycle with no
way out.

The re-arm effect in `entry<StreamRoute>` decided "this is a retry" from state — route current
again, playback handed off, a candidate still armed. ⚠ **A back press produces exactly that
state**, because nothing consumes the chain until the first frame plays. So pressing system Back
during a slow debrid mint relaunched the source just walked out of, forever. The in-app back
button escaped only by accident: it pops this route on its way to details, so it never reached
the effect. The system gesture does — the same asymmetry as item 1.

A retry is now **signalled** by the player's fatal handler rather than deduced. Silence means the
user left, so the chain is retired instead of left armed, which also lets the surface rules
uncover the list. One-shot, and cleared by both `consumeAutoPlay` and `seedAutoPlayCandidates`.

Smaller, same pass: the credential-refresh budget from item 8 was refunded inside
`switchToSource`, which also serves automatic downshifts, the debug forced swap and its own
re-entrant debrid resolve — so an automatic retry of a dying source would have been handed a
fresh budget every swap, which is the loop that budget exists to stop. Only a user's own pick
refunds it now. Unreachable today because auto-downshift is Instant-only and Instant is withheld,
which is exactly why it would have shipped.

### 11. Backing out of Streamlined landed on the source list, and took two presses

**Found on the device, in the first Tier 1 pass.** Two faults in one report.

The route uncovered the source list when the user came back from the player. It was the wrong
destination *and* it did not finish the job: `consumeAutoPlay` clears `activeRequestKey`, so
`StreamsScreen` re-fetched the moment it was uncovered, and what the user actually saw was the
source **loading** screen. Nothing popped the route, so a second Back was needed to leave.

⚠ **The destination was wrong on principle, and the codebase already said so.** The quality
sheet's own dismiss carries the comment *"backing out of the quality sheet means 'not now', so
it returns to details rather than uncovering the Classic source list the user chose Streamlined
to avoid"*. The player-return path violated the rule the sheet already followed.

The rule, stated once: **in Streamlined and Instant the source list appears only when the app
could not choose — never because the user left.** Classic and an explicit manual launch came
*from* the list, so they still return to it.

- `streamRouteSurface` now answers `HandOff` for anything after a hand-off, in both directions,
  and `entry<StreamRoute>` pops itself through to details. `isRouteCurrent` and
  `hasArmedFailureChain` are gone from its inputs; the rule table is shorter than before.
- ⚠ **`HandOff` is therefore a navigation in flight and never a resting state**, which the pure
  function cannot enforce because it cannot see a navigation. Two things hold it: the route sets
  `manualSourceListRequested` if the pop no-ops - the same guard `qualitySheetDismissRequested`
  already carries - and the stall backstop now covers `HandOff` as well as the overlay, so
  resting on it after the fetch settles falls back to the list within `1.5 s`.

**Failure still goes to the list, with a reason.** An exhausted chain, no safely playable
source, or a timed-out fetch is the escape hatch `PLAYBACK_MODES_PLAN.md` specifies, and the
user is then one tap from choosing. Confirmed with the maintainer rather than assumed.

## Verification for 0.5.0-beta

**Standalone compile-and-run of the shipped sources** (`AGENTS.md` item 2), in **both**
repositories, with identical results:

- `PlaybackQualityOptionsTest` **45**, `StreamRouteSurfaceTest` **11**, `PlaybackModeRouterTest`
  **11** — 67 tests, zero failures.
- `DownloadTransferTest` **22** and `PlaybackUrlCredentialsTest` **7**, zero failures, both
  compiled with no stubs at all. **95 tests in total**, and the same 95 in `NuvioZDesktop`.
- **Stubbed neighbours, never a file under test:** `SourceFacts`, `SourceFactsExtractor`,
  `StreamItem` and its behaviour-hint types, `PlaybackMode`, and the three ranking enums.
  `SourceRanking`, `PlaybackSourceSelector`, `PlaybackQualityOptions`, `StreamRouteSurface`,
  `PlaybackModeRouter` and `DownloadTransfer` are the real shipped files, unmodified.
- **The harness is in the repository now**, at `scripts/run-pure-suites.sh`, with its neighbour
  stubs beside it. It takes an optional repository path and work directory, fetches `kotlinc`
  and the JUnit jars on first run, and works in both repositories. It previously lived in a
  session-scoped scratchpad, which meant the next agent could not run it and would have fallen
  back to "verified by reading" - the exact habit this release exists to break.
  **Run it before trusting any change to the playback selection logic.**

**Parser check** clean over every changed file in both repositories.

**Thirteen shared files confirmed byte-identical** across the two repositories after mirroring.

**CI is now green on `nuvio-z` (run `31486710102`, commit `3178ae9`).** The Android host suite
passes and `:androidApp:assembleFullDebug` builds, which is the **first time any of this pass
has been compiled at all** — everything before it was a parser pass plus the standalone pure
suites. Two things it settled, and one it did not:

- Every change to `App.kt`, the player runtime and the two new settings keys compiles on
  Android, and the full host suite passes with them.
- ⚠ It took two runs. The first failed to compile `commonTest`, because deleting
  `PlaybackSourceSelector.rank` left a caller in `PlaybackSourceSelectorTest` — see item 6.
- ⚠ **It says nothing about `desktopMain`.** Two new `expect` members landed this pass, and only
  `:composeApp:desktopTest` in `NuvioZDesktop` (or its Windows CI job) proves their desktop
  actuals exist. **Run that before the release.**

**Not covered, and CI is the gate:**

1. `PlaybackSourceSelectorTest` reaches the real AIO types and cannot run standalone — unchanged
   from `0.4.13-beta`.
2. **Nothing Compose was run at all.** `App.kt` and the player runtime files are parser-checked
   only; every behavioural claim about the route and the player above is reasoning from the code
   plus the pure tests underneath it. That covers items 1–3, 8's wiring and all of 9.
3. **The Android stall watchdog has no automated coverage and cannot get any here.** The desktop
   harness drives the *desktop* downloader; `FaultyMediaServer.GoSilent` already existed. What was
   missing was the Android implementation, and only CI compiles it and only a device runs it.
4. Two new `expect` members, so **`desktopTest` in `NuvioZDesktop` matters before release** — it is
   what catches a missing desktop actual locally.

## The 0.5.0-beta device script

**Hold the version bump until this has been run.** Set Playback mode = Streamlined.

1. Play an episode, let it start, **press system Back out of the player** (the gesture, not the
   on-screen button — they take different paths and only the gesture reaches the defect). Expect
   **the details screen, in one press**. Never a blank screen, never the quality sheet again,
   and never the source list — that is item 11.
2. Same again, then tap where a source row would be on the screen you land on. Nothing should
   happen.
3. An episode whose top source is uncached: it should name the source and move on, not stop.
4. Force chain exhaustion: expect the source list, not the overlay.
5. A P2P-only source with P2P disabled, then decline the consent dialog. Expect the source list.
6. A title with a known season pack in its catalogue: **Best available must not name the pack**,
   and must show a size and a speed figure.
7. Set a codec and an HDR preference. Confirm the pick changes, then force-stop, relaunch, and
   sign out and back in — both must survive. That is the sync-key check, and editing that key set
   is what wiped the playback settings in `0.4.0-beta`.
8. Three consecutive episodes: the resolution holds and Back works every time.
9. **Back out of a slow start.** Tap a quality, and while it is still preparing press **system
   Back**. Expect the details screen in one press — never to be thrown back into the player, and
   never a second press to escape.
10. **The loop, and the reason for this second pass.** Play a Streamlined episode from a debrid
   provider and let a source fail on its own. Expect **one** toast naming it and **one** advance
   to the next candidate — never the logo screen a second time for the same choice. If the chain
   runs out, expect the source list.
11. **Downloads, Android:** start one and cut the connection without disconnecting (aeroplane mode
   mid-transfer). The row should fail with a named reason and retry, not sit on its percentage.

Report what each step actually showed. A step that was not run is not a pass.

## A fresh line estimate suppressed probing a new host (merged into 0.5.0-beta)

**Was held on `claude/network-strength-sources-4rrysy` for whatever shipped next. That is
`0.5.0-beta`**, so it has been merged into the release branch in both repositories rather than
queued a second time.

`estimateAgeMs` answered with `peek`'s exact-then-generic fallback, so it reported the age of
the number the sheet would *show* rather than of the key the probe would *write*. A two-minute
old line-wide reading therefore declared a brand-new debrid host freshly measured, the host was
never probed, and it went on borrowing a figure measured somewhere else - which is precisely
what the per-provider keying exists to prevent.

⚠ **The obvious fix is wrong and would have been worse.** Making the check exact-key-only breaks
the other direction: a source that still needs minting has no direct URL, so the probe falls
back to the CDN and files the answer under **no** provider. That host's own entry then never
fills, an exact-key check is never satisfied, and the sheet re-probes 4 MiB on *every single
open*. On debrid - the main path - that is most opens.

So the freshness question now names the key the probe would write:
`plan` resolves its target first, then gates on the host's age when it will pull from that host
and file the answer under it, and on the line's age whenever it falls back to the CDN or the
source carries no provider. `Inputs` takes both ages because only `plan` knows which applies.

**Verified:** the three pure suites, **39 tests**, up from 34 - five new cases pin both
directions of the trap, including `aCdnBoundProbeIsJudgedByTheLineNotTheHost`. CI is the gate.

## The 0.4.13 picker showed nothing at all (2026-08-10, `0.4.14-beta`)

**Reported on sight, with a screenshot: the quality sheet had no connection line and no meters
on any card.** Not a rendering fault - it is what `0.4.13-beta` was built to do, and the design
was wrong. `estimatedMbps` was passed as null unless a measurement existed, so the header
collapsed to its non-breaking space *and* `connectionFit` returned null for every option, taking
the meters and the over-connection warnings with it. A connection that could not be measured
therefore displayed **less than before any of this existed**.

Three ways to reach that screen, and all three were silent:

1. ⚠ **The probe cancelled itself.** Its `LaunchedEffect` was keyed on `qualityProbeTarget`,
   which derives from `playbackQualityOptions` - rebuilt *every time an addon answers*
   (`App.kt:2779`). Every new source restarted the effect and killed the transfer part way
   through. The comment above it claimed it "runs beside the source fetch"; it was being killed
   by that fetch. It now launches into a `rememberCoroutineScope`, so re-triggering is harmless
   (`probe` refuses to start a second one while the first is in flight) and cancellation happens
   when the sheet actually leaves composition.
2. **Metered connections were skipped entirely**, which left mobile data - the connection whose
   speed varies most and matters most - as the one case still decided by a preset. **The skip is
   gone**: metered is probed too, at about 4 MB once per network per ten minutes, on the
   maintainer's explicit instruction.
3. **A failed probe reported nothing** - non-2xx, or a sample under the 512 KiB floor.

**The sheet now always shows a figure and labels its provenance:** `Estimated ~50 Mb/s for this
connection` until measured, `Your connection: about 42 Mb/s` after, `Checking your connection…`
while a probe is in flight. The meters are back on every card, scored against whichever figure
is current. This is a deliberate step back from "never show an unmeasured number" - the label
carries the truth instead, and a meter comparing 33 against 48 Mb/s is useful even when the
baseline is rough.

One new string key in both repos: `playback_quality_estimated_connection`.

**Verified:** the three pure suites still pass standalone - **34 tests** across
`NetworkThroughputMeterTest`, `NetworkStrengthProbeTest` (metered now asserts a probe *is*
planned) and `NetworkQualityRepositoryTest`. CI is the gate, as before.

⚠ **Still not device-verified, and the screenshot above is the reason that matters.** No amount
of green CI would have caught this; it took someone looking at the screen. The outstanding
checks below are unchanged and this release adds one: confirm the labelled figure appears on a
first play, and that the probe now completes rather than being cancelled.

## Network strength is measured, not assumed (2026-08-10, `0.4.13-beta`)

**The quality sheet was printing a preset as if it were a measurement.**
`NetworkQualityRepository.defaultMbps` returns 50 Mbps for any Wi-Fi, 100 for Ethernet, 10 for
cellular, and `PlaybackQualitySheet` rendered that verbatim as *"Your connection: about 50 Mb/s"*
with no hedge. Every `ConnectionMeter` and every "May be more than your connection carries" was
scored against a guess. Four things kept it one, and all four are now fixed:

- **Nothing measured throughput before the first play.** New `core/network/NetworkStrengthProbe.kt`
  runs a bounded ranged GET — 4 MiB or 2500 ms, whichever first — while the source fetch the
  skeleton is already waiting on is still running.
- **The playback signal was a lower bound that could not correct downwards.** New
  `core/network/NetworkThroughputMeter.kt` converts `bufferedPositionMs` growth against the
  playing file's bitrate into a real rate. `recordSustainedBitrate` stays, monotonic, as the
  fallback for sources whose size nobody reported.
- **Estimates were in-memory only**, so every cold start was a preset again. They now persist
  through `core/network/NetworkQualityStorage.kt` (a new `expect` with android/ios/**desktop**
  actuals), aged out at seven days, capped at 32 entries.
- **The sheet read the estimate wrong** — `NetworkQualityRepository.current()` called directly in
  composition, so no recomposition when a measurement landed, and no provider scope. It now
  collects `uiState` and reads through the new pure `peek(providerId)`, scoped to the host that
  would actually serve the stream.

⚠ **The probe measures the source, not the line.** When the top option has a direct URL it pulls
from that host with that source's own `proxyHeaders`, and files the answer under its provider id.
Only a candidate that still needs `clientResolve`, or a manifest, falls back to
`speed.cloudflare.com`, and that result is stored against **no provider** — a fast CDN must never
vouch for a slow debrid. **No debrid link is ever minted to run a probe.** Metered connections are
never probed at all; the buffer meter covers them for free.

⚠ **A flat buffer and a draining buffer are not the same reading.** A full buffer back-pressures
the transfer down to the file's own bitrate, so a flat window measures the *file*; two of them
stop the meter. A *draining* buffer is the line failing to keep up and is reported even when it is
below an earlier window, because suppressing it is how an estimate survives being disproved.

**Best available now says what the user gets.** Its card had no resolution badge and no bandwidth
figure, so `describeRelease` — `WEB-DL · TorBox` — was the whole card: the two facts named were
which rip it came from and which host serves it. It now leads with
`PlaybackSourceSelector.describeBestRelease` (`4K · DV · 18.2 GB`), quotes a real
`Needs 21 Mb/s` from `PlaybackQualityOptions.requiredMbpsFor` on the source that would actually
open, and therefore has a `ConnectionMeter` and an over-connection warning for the first time.
Resolution cards are unchanged except for gaining the dynamic-range token. Unknown fields are
omitted, never placeholdered, and a null `previewSelection` still yields an empty line rather than
`candidates.first()`.

**One new string key in both repos:** `playback_quality_checking_connection`. The header line is
now always drawn at fixed height with three states — the measured figure, "Checking your
connection…", or a non-breaking space — so the grid never jumps when a figure lands.

**Instant is still withdrawn, and this does not change that.** But `PlaybackMode.isSelectable`'s
comment was stale — it blamed tier-picking that `PlaybackQualityOptions` replaced — and now names
the real blocker: no device evidence for the failure chain, downshift, or metered consent.

**Verified — CI is green on both repositories** (`nuvio-z@6f55f51`, `NuvioZDesktop@eadaece`):
Android host suite **767 tests, zero failures**, debug APK built; desktop tests and the
**Windows MSI job**, which is what compiles `desktopMain` and therefore exercises both new
desktop actuals. That took three red runs to reach, and each failure was real:

1. `playback_quality_checking_connection` was in `strings.xml` but not imported —
   `PlaybackQualitySheet` imports every key explicitly, so `compileAndroidMain` failed.
2. `response.body` is non-null on androidMain and **nullable on desktopMain**, so
   `response.body.byteStream()` compiled for Android and failed `compileKotlinDesktop`. The
   read loop now takes a nullable `ResponseBody`, as every other body reader in that file does.
3. `theProbeMeasuresTheSourceThatWouldOpenNotTheFirstCandidate` asserted a premise its fixture
   did not meet: `isUncachedDebrid` treats a debrid-backed candidate whose `isDebridReady` is
   **null** as uncached, so both fixtures were uncached and `previewSelection` fell through to
   the first candidate. Production path unchanged; the fixture is now explicitly cached.

**None of the three was reachable by the sandbox checks below** — they catch syntax and pure
logic, never resources, never `expect`/`actual` nullability skew, never a wrong test premise.
Treat a green CI run as the gate, not this section.

Also verified in the sandbox:

- **Parser check** over every changed file in both repositories: clean.
- **Standalone compile-and-run of the shipped sources** with JUnit, per item 2:
  `NetworkThroughputMeterTest` **10/10**, `NetworkStrengthProbeTest` **9/9**,
  `NetworkQualityRepositoryTest` **15/15** (the six pre-existing cases plus nine new ones,
  covering the downward correction, `PROBED`, seven-day expiry, the cold-start restore as
  `CACHED`, a corrupt blob, and `peek` not publishing).
- **Stubbed neighbours, never a file under test:** `NetworkQualityPlatform` and
  `PlatformNetworkQuality`/`NetworkConnectionType` (the real file also declares the `expect`),
  `NetworkQualityStorage`, `DownloadsClock`, `VideoResolution`, and `httpMeasureThroughput`.
  `NetworkQualityRepository`, `NetworkThroughputMeter` and `NetworkStrengthProbe` are the
  shipped sources, unmodified.
- **`PlaybackSourceSelectorTest`'s five new cases are parser-checked only** — the real
  `StreamItem` reaches the generated resource bundle, so they need CI. **CI compiles them on
  push; treat that run as the gate.**

⚠ **`AGENTS.md` says `desktop-release.yml mode=build-only` is "the only thing that compiles
`desktopMain`". That is out of date** — `ci.yml`'s Windows MSI job compiles it on every push to
`NuvioZDesktop`, and it is what caught defect 2 above. Keep running build-only before a desktop
release, but the every-push safety net is better than that line claims.

**Not verified, and the next steps:**

1. **Nothing has run against a real socket or a real device.** Everything above is compile plus
   pure logic; `httpMeasureThroughput`'s three actuals have never opened a connection in anger,
   and the iOS one is compiled by **no** CI job at all (the Android job disables `iosArm64` /
   `iosSimulatorArm64` — cinterop cannot cross-compile on Linux). It uses Ktor's
   `bodyAsChannel()` / `readAvailable`, which nothing here has verified.
2. **Extend the desktop download harness with a rate-limited endpoint** and assert
   `httpMeasureThroughput` measures a known 20 Mbps server, that a delayed first byte does not
   depress the figure, and that both the time cap and the early exit fire. Nothing has yet
   exercised the platform actuals against a real socket.
3. **Render the sheet off-screen at 420 dp and 1100 dp** in both header states and confirm the
   height does not change, and that the Best available card's longest line
   (`4K · DV · 128.4 GB` plus `Needs 78 Mb/s`) fits the 280 dp `QUALITY_CARD_MIN_WIDTH`. Both
   defects `0.4.12-beta` caught this way were on that card.
4. **On device:** `DebugBandwidthThrottle` at 5 Mbps — the sheet should converge near 5 rather
   than sitting on the 50 Mbps Wi-Fi preset, with `PlaybackDiagnosticsHud` showing
   `confidence=PROBED`/`PASSIVE` and the provider key. Then cellular: confirm no probe runs.
5. Fresh install → play → force-stop → relaunch: the estimate survives and the sheet shows a
   figure immediately instead of "Checking your connection…".

## The debug update line (2026-08-08)

Debug builds install as `com.nuvio.app.z.debug`, so the stable channel's APKs can never update
them and testing a fix meant sideloading a file by hand every time. Debug builds now read GitHub
**prereleases** tagged `debug-v*` from `Zokaper/nuvio-z`. The stable channel already discarded
prereleases, so the two lines cannot see each other and the release flow is untouched — verified
after publishing: `0.4.9-beta` is still `latest`, `debug-v0.4.9-beta.1` is `prerelease`.

Only the Android **full debug** build takes this path; every other `isDebugBuild` actual (iOS,
desktop, Playstore) is `false`.

**Three pieces, and each exists for a reason that is not obvious:**

- **`androidApp/nuvio-debug.keystore` is committed**, with an explicit `.gitignore` negation. It
  is not a secret — it signs debug builds only. It exists because Android refuses an install
  whose signature changed, and AGP's default debug key lives in `~/.android/` per machine, so
  two machines (or a machine and CI) produce mutually un-installable debug APKs. The release
  keystore is still excluded and must stay that way.
- **`DEBUG_BUILD` in `Version.xcconfig`** is the debug counter. It produces a fourth version
  component (`0.4.9-beta.1`) and a derived `versionCode` (`releaseCode * 1000 + n`). Without it
  every debug APK cut from one release version looks identical to the installed one and no update
  is ever offered. **Bump it for every debug build you publish** — that is the whole mechanism.
- **`VersionUtils.normalize` strips `debug-` before `v`.** Left on, `debug-v0.4.9-beta.2`
  tokenises to `[4, 9, 2]` — the leading zero is lost with the `v0` token — and every debug
  release outranks every local version permanently. `DebugChannelVersionTest` pins this and the
  four-component ordering.

⚠ **The signing key changed, so the currently installed debug app must be uninstalled once.**
Every debug build after `0.4.9-beta.1` updates in place from inside the app.

**Latest debug build: `debug-v0.4.14-beta.10`** (versionCode 124010), cut from the revision-5
wizard branch — see that section. The note below is kept for the reasoning about why the
marketing version stays put while the debug counter moves.

**Published for the 0.5.0-beta device run: `debug-v0.4.14-beta.4`** (versionCode 124004), cut
from `claude/release-0.5.0-beta-polish-ivcjsl` at `3178ae9`. The installed debug app is
`0.4.9-beta.3` at 119003, so it should offer the update. The marketing version stays at
`0.4.14-beta` deliberately — bumping it to 0.5.0 now would break the release line's bump-last
rule with phase 2 still to come, so the debug counter carries the identity instead.

**Publishing a debug build (2026-08-11): dispatch `debug-release.yml`.** Bump `DEBUG_BUILD` in
`Version.xcconfig`, push, then run the workflow against whatever branch you want the build cut
from. It runs the host suite, builds `:androidApp:assembleFullDebug` and publishes
`debug-v<version>` as a prerelease with the APK attached.

It replaces the manual `gh release create` ritual, which required a machine with a working
Gradle and so could not be done from the agent sandbox at all - every device-testing loop had to
wait for the maintainer. `ci.yml` builds the same APK on every push but only uploads an Actions
artifact, which an installed app cannot update from.

⚠ **The tag is single-use** and the workflow refuses to run if it already exists: republishing
one would strand installs that already took it on older code carrying a newer name. Bump
`DEBUG_BUILD` instead. It targets the dispatched commit rather than `main`, so a working branch
is fine. The workflow file must also exist on `main`, because that is where GitHub looks to
decide whether `workflow_dispatch` is available at all.

⚠ **This paragraph used to say the channel was deliberately not mirrored to `NuvioZDesktop`.
That is no longer true** — see "The desktop debug line" at the top of this file. The reasoning
recorded here was also half wrong and is worth keeping for that: `includePrereleases` being
already `true` on desktop was described as making the split unnecessary, when it was in fact
the thing that made a naive mirror *dangerous*. The desktop release line publishes plain
releases, so a `debug-v*` prerelease added to that repository would have been offered to every
release install immediately. The desktop channel is keyed on the tag prefix rather than the
prerelease flag for exactly that reason.

**Verified:** Android **706 tests, zero failures** (six new `DebugChannelVersionTest` cases).
APK inspected: `com.nuvio.app.z.debug`, `versionCode 119001`, `versionName 0.4.9-beta.1`, signed
`CN=Nuvio Z Debug`. The in-app update flow itself is **not** device-tested — the first real proof
is publishing `debug-v0.4.9-beta.2` and watching `.1` offer it.

## One card per resolution, bands stacked inside it (2026-08-10, `0.4.12-beta`)

**Reported on sight: the `0.4.11-beta` selector reads worse than the stacked list it
replaced.** It was a flat grid of one card per `PlaybackQualityOption`, so "1080p High",
"1080p Mid" and "1080p Low" were three unrelated tiles competing with "4K" and "720p" — the
resolution, which is the first thing the user is choosing, said three times to say it once.
That layout shipped without anyone looking at it; this is the first correction from seeing it.

**The hierarchy already existed in the data and the sheet was flattening it.**
`PlaybackQualityOptions.build` emits Best available, then buckets high→low resolution, each
split High/Mid/Low. `PlaybackQualityOptions.group` now makes that explicit and the sheet draws
it: one card per resolution, badge once, bands stacked inside as the tap targets.

**Presentation only.** The option set, the banding, the ranking, `PlaybackSourceSelector` and
the `PlaybackQualitySheet` signature are untouched — which is why **`App.kt` and `strings.xml`
were not edited in either repository** and all four changed files were `cp`'d rather than
hand-ported. No new string keys.

⚠ **Bands are stacked full-width rows, not a row of chips, and that was the deciding
constraint.** Chips were the original sketch. A 3-across chip on a phone is about 105 dp,
which holds the band word and the figures and nothing else — so it would have cost both
`sourceLine` and the over-connection sentence. Neither can be lifted to card level: they are
**per-option**, so a card-level provider line names a release two of the three bands never
open, and the warning is true of one band and false of the one under it. `0.4.10-beta` added
that provider line precisely because naming `candidates.first()` was the same untruth.

⚠ **The card is not a tap target; the rows are.** A card holds up to three options with no
sensible default among them, so a tap on the header would either do nothing or silently pick
one.

**No source count in the header**, though the sketch had one: `option.candidates` is the whole
bucket including candidates the protocol and cache gates skip, so any number there overstates
what can play.

**Three constants moved, and each was documented in terms of the old card:**

- `QUALITY_CARD_MIN_WIDTH` 240 → **280 dp**. Its stated reason — the width below which the
  over-connection warning stops fitting on two lines — still holds, but that warning is now
  two levels of padding in rather than one.
- `NuvioComponentTokens.wideDialogMaxWidth` 880 → **920 dp**, following it: 880 was exactly
  three 240 dp columns and would have silently fallen to two. **Safe to change** —
  `wideDialogMaxWidth` is read only by `PlaybackQualitySheet.kt` in both repos, and
  `NuvioZDesktop`'s `TrackingAdaptivePicker.kt:132` / `TrackingProviderCards.kt:710` read
  `dialogMaxWidth` (460 dp), untouched. That check is the reason the two tokens are separate.
- `SKELETON_CARD_HEIGHT` / `SKELETON_CARD_COUNT` re-derived (taller cards, three not four).
  The skeleton exists so the surface does not jump when the figures arrive, so it is wrong the
  moment the real card's footprint changes.

The band row is drawn as an `overlayHover` lift plus a hairline `borderSubtle`, not a second
`Surface` colour: `surfaceCard` on `surfaceCard` is invisible and there is no third card
colour in the token set — the same trap that kept `NuvioSurfaceCard` off this sheet.

**This one was looked at before being called done, and looking caught two defects the suites
could not.** Both were on the Best available card, and both were the layout's own stated fault
committed again:

- It carried a **`★` badge over a row that then said "Best available"** — the same thing said
  twice, which is exactly what grouping by resolution exists to stop. The badge now carries
  the name, and `variantLabel` returns `""` for `BEST` for the same reason it does for
  `SINGLE`: the badge above it already names it.
- It **repeated the sheet's own description sentence** three lines under itself.
  `optionSummary` falls back to `playback_quality_description` when `requiredMbps` is null,
  which only Best available is — so only real figures take the trailing slot now.

With both gone the row held one muted caption in a full-height box and read as empty, so the
provider line is promoted to `bodyMedium`/`textPrimary` when it is a row's only content.

⚠ **Compose can be looked at on this machine without a device, and this is the general point,
not a footnote to this change.** `desktopTest` can construct an `ImageComposeScene`, render any
composable against synthetic state and write a PNG — `compose.desktop.currentOs` is already on
the desktop test classpath, so it needs no new dependency. That is the only local check that
sees layout at all; both suites and a careful read missed two defects it caught in one pass.

The harness used here is kept at
`C:\Users\Rayoa\.claude\plans\QualitySheetRenderHarness.kt` — drop it into
`NuvioZDesktop/composeApp/src/desktopTest/kotlin/com/nuvio/app/features/playback/`, run
`:composeApp:desktopTest --tests "*QualitySheetRenderHarness"`, and read the PNGs from
`composeApp/build/quality-sheet-render/`. It renders at 420 dp and 1100 dp, either side of the
768 dp threshold, so one run covers the bottom-sheet and centred-panel branches.
**Delete it again afterwards** — it asserts nothing, so it is not a test, and leaving it in
adds a rendering pass to every CI run.

**Verified:** Android **735 tests across 101 classes** and desktop **940 tests across 131
classes**, both zero failures, errors or skips — the 729 / 934 baselines plus exactly the six
new `group` cases, which run on both targets, so nothing was displaced. The desktop run
compiled `desktopMain`. `PlaybackQualitySheet.kt`, `PlaybackQualityOptions.kt`,
`PlaybackQualityOptionsTest.kt` and `Tokens.kt` are byte-identical across the repositories.

⚠ **Check the arithmetic, not the green tick.** An earlier desktop run here reported 939 and
was taken as passing; the real cause was that `PlaybackQualityOptionsTest.kt` had not been
re-copied after a sixth case was added, so the desktop target was silently running five of
six. A `cp` that happens before the last edit to a shared file is the failure mode, and a
green suite cannot see it — **`diff -q` all four shared files immediately before quoting a
count**, which is what AGENTS.md's mirroring rule is really protecting.

⚠ **`DesktopDownloadQueueE2ETest > a source that trickles and drops forever fails instead of
retrying forever` failed once here, then passed on a re-run — treat it as load-sensitive, not
flaky-in-principle.** It timed out at 240 s during a run that overlapped other Gradle work,
and passed on an idle machine. The scenario is documented above as taking ~134 s against that
240 s ceiling on a **real-time** backoff schedule (2 + 5 + 15 + 30 s per budget, twice), so
its margin is under 2x. Nothing in this change touches downloads. If it starts failing on an
idle machine, the fix is a `retryBackoffScale` in `DownloadsTiming` beside the stall and
watchdog knobs — not a longer timeout, and not shortening the schedule, which several other
scenarios observe.

**Published and checked after the fact, not just dispatched.** `0.4.12-beta` is `latest` on
both repositories, draft `false` and prerelease `false`, so the stable channel offers it and the
`debug-v*` line is unaffected. The arm64 APK was pulled back down from the release and
inspected: `com.nuvio.app.z`, `versionCode 122`, `versionName 0.4.12-beta`, signer SHA-256
`2325A339…84787C` — the same CI certificate as every release from `0.3.3`, which is what makes
the in-app update land rather than fail as "App not installed". Desktop shipped
`Nuvio-Z-Windows-x64-0.4.12-beta.msi` plus `SHA256SUMS.txt`.

**Committed directly on `main` / `Dev`** in both repositories and released as `0.4.12-beta`;
no branch, since the release procedure needs the bump as the last commit on the default branch.
`CurrentReleaseNotes` was rewritten for this release in the same pre-bump commit, per
`AGENTS.md`. **Still not smoke-tested in the running app**: the rendered PNGs are the real
composable but not the real data, so the five outstanding `0.4.11-beta` checks below carry
forward unchanged. Add two: a title with a three-way 1080p split should show one card with
three rows, and a title with one source per resolution should show single-row cards with no
band word.

## The quality selector is a grid, and it waits for its numbers (2026-08-09, `0.4.11-beta`)

⚠ **The flat-grid layout described here was replaced on 2026-08-10 — see "One card per
resolution" above.** Everything else in this section still stands: the three-state body, the
responsive container, the meter/warning coupling, the new width token and the dismiss
behaviour are all unchanged by that follow-up.


Streamlined's quality selector was a scrolling stack of rows in a `BasicAlertDialog` —
identical on a phone and on a 1080p desktop window, off the design system (raw
`MaterialTheme.colorScheme` and hardcoded `.dp` throughout, never `MaterialTheme.nuvio`), and
showing figures that changed under the user as addons answered. Branch
`claude/quality-selector-grid` in **both** repositories. The option set, the ranking, the
banding and everything under `PlaybackSourceSelector` are untouched: this is presentation, one
navigation change, and one gate on when the options become visible.

**1. Nothing is shown until the numbers are final.** `isLoading` used to only grey the rows
out, so partial options sat on screen for the whole time they were still changing — a row could
say "Needs about 9 Mb/s · 3.2 GB" and, a second later, say something else, with rows appearing
and re-banding around it. The body now renders **one of three states and never a blend**:
a skeleton grid on the same card footprint while loading; the option grid with final figures
once settled; and, for settled-but-nothing-selectable — which `isStreamlinedSelectionReady`
treats as terminal — the existing `playback_quality_no_match` text rather than an empty grid
with only "Choose source manually" under it, which is a dead end wearing a grid. No new state
and no timer: the gate is the `isLoading` value the call site already computed.

⚠ **`isLoading` and `isSelecting` are two parameters on the sheet and must not be merged
back.** The call site's old single flag was `tokenMismatch || isAnyLoading ||
streamlinedSelectionPending`, and that third term flips true the moment the user taps a card.
Under the old rendering it only greyed the rows out; under the three-state body it would have
replaced the grid with a skeleton *after* the user had chosen — and the uncached-debrid path
leaves the sheet composed under the consent dialog, so they would have watched it happen. So
`isLoading` now means only "the figures are still moving" and owns the skeleton, while
`isSelecting` leaves the grid exactly as it is and only stops it accepting taps (disabled, not
removed: a second tap would re-arm the selection effect against a different option). While
selecting, the subtitle is the progress overlay's own `playback_progress_choosing` rather than
"Finding available sources…", which after a choice is simply untrue.

⚠ **This trades an early wrong-looking choice for a wait**, deliberately. `StreamsRepository`
does settle, so the wait is bounded — but **how long the skeleton is up is the first thing to
watch in the smoke test.** If it feels broken, the fix is a "still searching" line, not
reinstating partial figures.

**2. A responsive surface, chosen from the real window.** `BoxWithConstraints` is at the top so
it measures `entry<StreamRoute>`'s full-screen `Box`, not a phone-sized dialog — a
`BasicAlertDialog` here clamps width and would have silently shipped the phone layout
everywhere. Wide (≥768 dp, the repo's threshold at `App.kt:2051` and
`ProfileSelectionScreen.kt:112`) gets a scrim and a centred panel; narrow gets
`NuvioModalBottomSheet`. The wide branch must **not** use the bottom sheet:
`usesNativeNuvioBottomSheet` is false on desktop, so it falls through to Material's
`ModalBottomSheet` and would pin a phone sheet to the bottom of a 1080p window. The body is a
`LazyVerticalGrid(GridCells.Adaptive(240.dp))`, so one composable serves phone, tablet and
desktop, and the height ceiling is derived from the measured window rather than the old literal
420 dp — which is what made the third quality band unreachable.

**3. The meter and the warning cannot disagree.** `PlaybackQualityOptions.connectionFit`
absorbed the sheet's private `isOverConnection` predicate, so the bar and the sentence are two
renderings of one pure function rather than two expressions of the same comparison in different
files. The track runs to twice the estimate, so the marker at its midpoint *is* the connection
and a fill past it is the warning restated. The over-connection text keeps its wording, its
prominence and its strong colour — it is the part of the old sheet the user asked to keep. The
marker's position is derived from `MAX_LOAD_FRACTION` rather than restating its inverse as a
literal, since the two constants live in different files.

**Everything is on `MaterialTheme.nuvio` tokens.** `NuvioSurfaceCard` and `NuvioInfoBadge` were
**not** reused, and that is not an oversight: both take their colour from `colors.surface` /
`colors.surfaceCard`, and `surfaceSheet == surface` in the token set, so a `NuvioSurfaceCard` on
this sheet would have been invisible against its own background, and `NuvioInfoBadge` invisible
against the card.

**4. `NuvioComponentTokens.wideDialogMaxWidth` (880 dp) is new, and is not a widening of
`dialogMaxWidth`.** 460 dp leaves 420 dp of content and therefore exactly **one** 240 dp column.
`dialogMaxWidth` looks unused in `nuvio-z` but **`NuvioZDesktop`'s `TrackingAdaptivePicker.kt`
and `TrackingProviderCards.kt` lay out against it**, so widening it would have stretched two
desktop settings pickers as a side effect. 880 dp is three columns plus their gaps and padding.

**5. Dismiss returns to details; only "Choose source manually" reaches the source list.**
`onDismiss` and `onChooseManually` were byte-identical — both set `manualSourceListRequested`,
uncovering the Classic source list. Tolerable behind a dialog, wrong behind a bottom sheet,
where a stray swipe drops the user into the list they chose Streamlined to avoid. Dismiss now
calls `onBack`.

⚠ **`onBack` can silently do nothing, so it has a fallback.** `rememberGuardedPopBackStack`
pops only while its route is current and returns `Unit`, so the caller cannot tell; with
`qualitySheetDismissed` set and `manualSourceListRequested` still false, the opaque hand-off
`Box` keeps painting over `StreamsScreen` and the user gets a blank screen with no affordance —
the same class of fault as `onPlaybackFailureExit`'s silent no-op. A `withFrameNanos` effect
re-checks the current route and uncovers the source list if the pop no-oped.

⚠ **That effect is declared beside the route's flags, not inside the sheet's `if`.** `onDismiss`
sets `qualitySheetDismissed = true`, which is part of that `if`'s own condition, so an effect
declared inside it would be cancelled mid-`withFrameNanos` by the very state change it exists to
observe — the fallback would never fire and the blank-screen strand would ship.

⚠ **The other five `manualSourceListRequested = true` sites are untouched**, deliberately: they
are the failure-chain exhaustion and uncached-debrid paths, and the exhaustion one is the
"hang wearing a spinner" the `0.4.10-beta` section exists to kill.

**Verified:** Android **729 tests across 101 classes** and desktop **934 tests across 131 classes**, both zero
failures, errors or skips — the 724 / 929 baselines plus the same five new `connectionFit` cases
(unknown estimate, unknown requirement, exactly at the line, over the line, and the display
clamp). The desktop run compiled `desktopMain`. `PlaybackQualitySheet.kt`,
`PlaybackQualityOptions.kt`, `PlaybackQualityOptionsTest.kt` and `Tokens.kt` are byte-identical
across the repos and were `cp`'d; `App.kt` and `strings.xml` were hand-ported.

**Not smoke-tested on a device or an installed desktop app — nothing here has been looked at.**
Compose is verified by compilation only, so the entire layout is unseen. In order:

1. **Numbers never change once visible.** On a plugin-heavy profile, watch from the first frame:
   skeleton, then the grid, and no figure that moves afterwards. Time the skeleton.
2. Swipe the sheet away → **details, not the source list**; hardware back the same; and never a
   blank opaque screen (the `onBack` no-op fallback).
3. An **uncached debrid** option → the consent `AlertDialog` is readable and on top. It is now a
   Material `AlertDialog` over a `ModalBottomSheet` — **two platform dialog windows on Android**,
   which the old `BasicAlertDialog` pairing did not produce. This is the most likely new defect.
4. Exhaustion (an episode whose whole chain fails) → still uncovers the source list, not details.
5. Desktop: resize across the 768 dp threshold with the sheet open — panel ↔ sheet without
   losing state, and 2–3 columns when wide.

## Streamlined made to work, Instant withdrawn, downloads that cannot loop (2026-08-09, `0.4.10-beta`)

Four commits in `nuvio-z`, mirrored to `NuvioZDesktop`. Reported as: AIOStreams errors
("stream not cached", an unnamed error) dead-ending Streamlined since `0.4.9-beta`; the
resolution selector being a plain list with no middle option; and a Punisher episode stuck at
5.8 of 6.2 GB saying "Retrying" forever, which pause/resume could not clear.

**1. Streamlined had no failure chain, and never had one.**
`completeStreamlinedOptionSelection` called `PlaybackSourceSelector.select`, took
`Play.stream` and threw `Play.fallbacks` away, so one `NotCached` answer from the provider was
the end of the road — while Instant, seeding the very same chain through
`seedAutoPlayCandidates`, stepped past it. `1df19a17` (the plausibility ceiling that shipped in
`0.4.9-beta`) is the likeliest *trigger*, because it changed which candidate heads each row,
but the missing chain is the fault and the fix is right either way. **Nobody should spend time
proving the 0.4.9 connection.** Streamlined now seeds the whole row and hands off to the
auto-play effect, so resolve failures, P2P, reuse-last-link and the attempt counter behave
identically in both modes. Every `isInstantAutoPlay` test in that effect was really asking "is
there a next candidate?", so they became one `hasFailureChain`.

Each advance names the source and the reason — stepping past a dead candidate silently is
indistinguishable from a hang, and "unknown error" is what the absence of that looks like.
An exhausted chain uncovers the source list instead of leaving the progress overlay up.

Cache evidence became the **third** ranking key, below plausibility and torrent-ness. Promoting
it above plausibility would let an implausible cached season pack head the row again and it
would not show, because `credibleBitrateMbps` filters the display — only what actually plays
would regress. Pinned by a test.

**2. Instant is withdrawn**, behind a single `PlaybackMode.isSelectable` predicate — the
`isImplemented()` machinery was deleted in `0.4.1-beta`, so this is new construction. A stored
`INSTANT` is coerced to `STREAMLINED` at **read** time; `fromStorage` still answers `INSTANT`,
so the key survives and those profiles come back if the mode returns. Auto source-swap needed
no behavioural change — `maybeDownshift` already returns early unless the mode is `INSTANT` —
only a caption saying it is withheld rather than broken.

**3. A third quality band.** Buckets split three ways once their spread reaches 2.25
(`SPLIT_RATIO²`) on geometric thirds, two ways at 1.5, otherwise not at all. Mid is a band, not
a fixed row, so a title with nothing in the middle still shows exactly High and Low — which is
why no existing test expectation moved. **A lone "Mid" row is unreachable**: the cheapest source
always falls below the lower boundary and the dearest always reaches the upper one, so High and
Low are always occupied. The collapse guard is kept for whoever moves those boundaries later,
not because a hole is open; `aThreeWaySplitAlwaysHasBothEnds` pins it. The
`PlaybackQualityTier` storage key set was **not** touched.

The sheet was two `Text`s in a `Column`. Rows now lead with a resolution badge and name the
provider and release of the source that would really open — `previewSelection`, not
`candidates.first()`, because the protocol and cache gates can skip several candidates.

**4. Downloads could retry forever.** `DownloadsRepository.kt:887` zeroed `attemptCount` on
*any* forward byte, so a source that trickled and dropped refreshed its budget every cycle:
`shouldRetry` never returned false and the row cycled Downloading → trickle → drop → Queued
indefinitely. Pause/resume could not clear it because nothing was wedged — during the backoff
the item is `Queued` with no handle, so `pauseDownload` had nothing to cancel and
`resumeDownload` zeroed `attemptCount`, which is what the loop was already doing to itself.

Now the budget only resets on progress measured from `retryCycleStartBytes`; an exhausted
budget restarts from zero **once** on a fresh link, then fails with a named message. No new
`expect` was needed: setting `downloadedBytes = 0` makes the existing downloaders take their
`appendToTemp = false` path and delete the `.part` themselves.

- **`meaningfulProgressBytes` scales both ways.** The plan called for `max(16 MiB, 1%)`, which
  is wrong at the small end — the harness serves 6 MiB episodes, so a flat 16 MiB floor could
  never be cleared and would have replaced one stuck state with another. It is now 1% on large
  files, capped at a quarter of the file on small ones.
- **`reclaimLostTransfersLocked` now charges an attempt.** It was a second, independent
  unbounded cycle: it never went through `onTransferFailed` and never touched `attemptCount`,
  so the queue watchdog could recycle an item forever for free. The two mechanisms cannot
  double-bill: `DownloadQueuePlanner.lostTransfers` (`:71-80`) matches only `Downloading` and
  system-paused items, never a `Queued` item waiting out `nextRetryAtEpochMs`. **Re-check that
  if the filter ever grows a `Queued` arm.**

**Verified:** Android **724 tests**, desktop **929 tests**, zero failures — baselines were 700
and 908. Three new harness scenarios drive the real queue against twelve queued
`DropConnection` faults: trickle-and-drop ends in a named failure, the restart happens exactly
once, and a source that recovers still completes.

**The harness is now slower on purpose.** `retryBackoffMs` is real time and deliberately not
turned down — 2 + 5 + 15 + 30 seconds per budget, twice, plus the restart between them, so a
trickling source takes about 130 s to be allowed to give up. Measured: the two exhaustion
scenarios take 134 s each and the recovery one 82 s, so `DesktopDownloadQueueE2ETest` went from
about 290 s to about 470 s. If that becomes intolerable, add a `retryBackoffScale` to
`DownloadsTiming` beside the stall and watchdog knobs rather than shortening the schedule
itself — but re-run the whole harness afterwards, because several existing scenarios observe
the `Queued`/backoff window and a short scale makes that window smaller than the poll interval.

**Not device-verified.** Released on the maintainer's explicit instruction with tests as the
only evidence. Two behaviours no test reaches:

1. **Streamlined no longer pops `StreamRoute`** (`if (!hasFailureChain) popUpTo<StreamRoute>`).
   Required for retries, but it changes Back-from-player for the *default* mode. Check that
   Back after a **successful** Streamlined start shows neither a re-displayed quality sheet nor
   a stuck progress overlay.
2. **Exhaustion sets `manualSourceListRequested`** while `qualitySheetDismissed` and
   `streamlinedPlaybackStarting` are both true. Confirm the source list actually wins over the
   overlay — that is the "hang wearing a spinner" this exists to kill.
3. The plan's "a Streamlined retry advances rather than re-seeding" case has **no unit test**:
   it is Compose state inside `entry<StreamRoute>`. Verified by reading — the re-arm effect at
   `App.kt:2929` touches only `playbackHandedOff`/`autoPickAttempt`, and the selection effect
   self-clears `streamlinedSelectionPending` — but not by running.

**Flagged, not fixed: Android has no active stall watchdog.** Desktop polls and force-closes a
silent stream (`DownloadsPlatformDownloader.desktop.kt:60-84`); Android relies on a
**hardcoded** `readTimeout(60s)` (`android.kt:26-34`) that ignores `DownloadsTiming.stallTimeoutMs`,
so the harness cannot exercise Android's stall path at all. Its own commit.

**Also deliberately out of scope:** `PlaybackSourceSelector.rank` and
`PlaybackQualityOptions.preferencesFor` still hardcode `CodecPreference.ANY` /
`DynamicRangePolicy.ANY` and never set `preferredAudioLanguage`. A real defect, but it changes
what gets picked for anyone with those preferences set — its own commit, its own smoke test.

## Instant's failure chain died the moment playback started (2026-08-08)

**Reported as "the debug video player keeps kicking me out": the logo overlay appears, the
episode plays for about a second, and the user is dropped back on the details screen.** That is
not a diagnostics bug. Instant's three-source failure chain was unreachable for the most common
failure there is - a source that opens, starts, and then dies.

Two independent defects, both in the same handler (`App.kt`, `onFatalPlaybackError`):

1. **It read state that had already been cleared.** `onPlaybackStarted` fires on the first
   `!wasPlaying && isPlaying` edge and calls `consumeAutoPlay()`, which nulls `autoPlayStream`
   **and** empties `autoPlayCandidates`. The handler then read `autoPlayStream`, found null,
   concluded `hasNext = false`, and took the exhausted branch - the "no automatic source" toast -
   on the *first* failure, with two ranked candidates untried. The chain only ever worked for
   sources that failed before rendering a frame.
   Consuming on the first frame is not the bug and must not be "fixed": Instant deliberately
   leaves `StreamRoute` on the back stack, so an unconsumed chain means backing out of the player
   relaunches it. `StreamsRepository` now *retains* what `consumeAutoPlay` retired, and
   `failOverAfterPlaybackStarted()` re-arms it and advances past the dead source. It is
   single-shot and is dropped by `seedAutoPlayCandidates`, so a chain can never fail over to
   candidates ranked for different content.
2. **It navigated past the thing that does the retrying.** The handler called
   `onBackToDetails()`, whose every branch pops `StreamRoute` - and `StreamRoute` is where the
   whole chain lives: the auto-play `LaunchedEffect` keyed on `autoPlayStream`, `autoPickAttempt`,
   and the "Finding a source" overlay. So even with a next candidate correctly selected, nothing
   was left alive to launch it. The comment at the `playbackHandedOff` declaration
   ("Instant deliberately leaves StreamRoute on the back stack so the failure chain survives")
   states the invariant this violated. `onPlaybackFailureExit` now pops only the `PlayerRoute`,
   falling back to `onBackToDetails()` when there is no `StreamRoute` to return to (the
   reuse-last-link and P2P paths both produce that) **and** when the pop itself no-ops.
   That second case matters: `popBackStack(expectedRoute)` returns `false` without moving if the
   player is not on top, and `instantFailureHandled` is already spent by then, so a silent no-op
   would strand the user on a dead player with neither a retry nor an exit.

Exhaustion now lands on `StreamRoute` too, not details. With `autoPlayStream` cleared that route
renders the plain source list, which is what `PLAYBACK_MODES_PLAN.md` specifies: *"Only after the
chain is exhausted does it fall back to the Classic source list with a reason."* It was going to
details instead - a deviation from the plan that no test covered because the whole chain is
UI-level navigation.

Returning to `StreamRoute` also had to un-hide the progress overlay: `playbackHandedOff` survives
in `rememberSaveable(route.launchId)` and forces `PlaybackProgress.isVisible` false, so a retry
would otherwise land on a bare source list. A `LaunchedEffect` gated on *this route being current*
resets it and advances `autoPickAttempt`. The gate matters - Instant leaves `autoPlayStream` set
while the player is open, so without it the reset fires at hand-off and uncovers the overlay
underneath the player.

⚠ **`instantSelectionHandled` must stay latched — do not reset it alongside `playbackHandedOff`.**
It guards the effect that *selects* Instant's source and calls `seedAutoPlayCandidates`. Clearing
it on a retry would re-seed the chain back to candidate 1, and the failure would loop forever
instead of advancing.

**Verified:** `:composeApp:testAndroidHostTest` in `nuvio-z` - **700 tests, zero failures**,
including five new `AutoPlayFailoverTest` cases. `:composeApp:desktopTest` in `NuvioZDesktop` -
**908 tests, zero failures**, and it compiled `desktopMain`. `:androidApp:assembleFullDebug`
rebuilt so the installed APK contains the fix. `StreamsRepository.kt` was hand-ported (the repos
already differ at `presentStreamGroup`), `App.kt` hand-ported per the never-`cp` rule, and the
test file copied. **Not smoke-tested on a device.**

⚠ **This makes the failure recoverable and visible; it does not explain why the source died after
a second.** To capture that, enable Settings → Playback → **Playback diagnostics HUD** before
playing: `f3a30dcb` makes the player retain the real error instead of exiting. Note the HUD flag
is a non-persisted `mutableStateOf`, so it resets on every app start and must be re-enabled.

## Playback connection-drop diagnostics (2026-08-08, Phase 1 complete in code)

The instrumented build from `~/.claude/plans/okay-we-need-to-humble-balloon.md` is implemented
in both repositories. It is debug-gated and off until Settings -> Playback -> **Playback
diagnostics HUD** is enabled. In a debug build the normally advanced automatic-downshift row
is visible without enabling all advanced settings.

The HUD reports real buffer ahead/position/duration and labels it with the live engine
(ExoPlayer or libmpv), source resolution/release group/provider/addon, the provider-keyed
network estimate and confidence, and every state-machine field plus time remaining to the
trigger. Android ExoPlayer can be throttled live to Off / 20 / 10 / 5 / 2 Mbps. The HUD also
forces one safe step down or up in the same release group and resets the automatic swap budget.
It explicitly warns when libmpv is live because the ExoPlayer throttle cannot affect it.

Every automatic or forced swap is recorded in a bounded, in-memory, copyable log: elapsed
timestamp, reason, from/to quality, group, provider and addon, buffer at trigger, position
before/after, and the gap until the replacement actually plays. Automatic downshift now shows
a user-facing toast instead of changing quality silently. Manual source choices are not logged
or toasted. No Phase 2 buffer tuning or Phase 3 automatic upshift/default change was made.

**Local verification:** Android host tests and desktop tests pass, including the new forced
upshift and swap-log cases; desktopMain compiled with the new debug actual. A clean
`:androidApp:assembleFullDebug` passes and produces the side-by-side-installable debug APK.
The first combined debug/release packaging attempt hit a stale Gradle transform pointing to
the repository's old path; cleaning generated build outputs fixed the debug build. A standalone
`:androidApp:assembleFullRelease` then compiled, passed lint, R8/minification and resource
optimization, and stopped only at final APK packaging because this checkout has no release
keystore (`SigningConfig "release" is missing storeFile`). No device verification.

### Device test script

0. Settings -> Playback: set **Playback mode = Instant**, enable **Switch source when buffering
   persists**, and enable **Playback diagnostics HUD**.
1. Start a 4K episode and confirm the HUD says **ExoPlayer**. If it says libmpv, throttle tests
   are invalid. Record buffer ahead after it settles.
2. Tap **Force down**. Check the preserved position, audio/subtitle selection, replacement
   quality, and the measured gap in **Log**.
3. Restart playback, confirm ExoPlayer again, let it settle for at least 15 seconds, then select
   **2 M**. Confirm the starvation run builds and fires after roughly 21 seconds total
   (15-second settle plus 6-second sustained starvation).
4. Turn the throttle **Off** and confirm there is no oscillation.
5. Tap **Reset budget**, restart if needed, and repeat with **10 M** to test a partial drop.
6. Copy the log and report it together with the settled buffer-ahead value and whether the
   forced swap preserved position and tracks.

## Instant predictability, and the missing desktop Next Episode button (2026-08-08)

Two user reports from the `0.4.9-beta` build: Instant "feels like spinning a roulette wheel on
what resolution I'm going to get", and there is still no Next Episode button in the player.
Branch `claude/instant-predictability-next-ep` in **both** repositories.

**The Next Episode button was a desktop-only gap, and not where it looked.** The Compose
player has had one since forever - `PlayerControls.kt` renders a `SkipNext` pill whenever
`nextEpisodeInfo?.hasAired == true`, and that file is byte-identical across the repos. But
**desktop never mounts that control bar.** `5b3fc81d` ("feat: skip intro/outro to native
player") moved the desktop player to a native HTML overlay
(`desktopMain/resources/player-ui/controls.html` + `controls.js`, driven by
`NativePlayerController`), and its action row had resize/speed/subs/audio/sources/episodes and
no next-episode entry. Added one: `data-command="nextEpisode"` →
`PlayerControlsAction.NextEpisode` → the same `playNextEpisode()` the Compose pill calls, with
`nextEpisodeLabel`/`showNextEpisode` crossing the bridge beside the `nextEpisodeVisible` fields
that were already there. Reuses `#icon-skip-next` and the existing `player_next_episode`
string, so no new icon and no new string key.

⚠ **The `!isDesktop` guards in the desktop `PlayerScreenRuntimeUi.kt` are correct - do not
"fix" them.** `showNextEpisodeCard && !isDesktop` and `activeSkipInterval.takeUnless
{ isDesktop }` suppress the *Compose* card and skip prompt because the HTML layer owns both
(`#nextEpisodeCard`, `#skipPrompt`). Removing them double-renders.

**Instant was never random - it was opaque, and it churned.** Checked before changing
anything: no `shuffled`/`Random` anywhere in `features/playback/` or `features/streams/`, and
`SourceRanking`'s comparator ends in `.thenBy(addonOrderOf).thenBy(stableUrlOf)`. The one
plausible real race was ruled out too - `isAnyLoading` cannot flip false while a debrid cache
check is outstanding, because a group awaiting annotation is not republished until
`publishAddonGroup` runs *inside* the availability job (`StreamsRepository.kt:302-339`), so the
pre-completion `isLoading = true` copy is what `anyLoading` sees. Instant genuinely waits for
settled cache state.

What actually varies between two taps that look identical: the derived rows come from *this*
episode's catalogue (an empty bucket produces no row), and the estimate ratchets upward as you
watch. Both are correct; neither is visible. So:

- **`PlaybackQualityOptions.stickyAffordable`** - `highestAffordable` biased towards the
  resolution this series already got in this sitting. It will not override a metered cap, will
  not hold a resolution the estimate can no longer carry, and will not invent a row the
  episode does not have. A tie-break towards stability, never a ceiling or a floor.
- **Instant now says what it opened** - a toast, `Playing 1080p · WEB-DL · TorBox`, raised
  before navigation so it works on both platforms without a Compose overlay over the desktop's
  native surface.

Two traps worth not re-stepping on:

- **The pin is written where a source *opens*, not where Instant *chooses*.** Instant's failure
  chain (`skipAutoPlayStream`) can advance past a dead or evicted candidate to a different
  resolution. Pinning the choice would record something that never played, and the next episode
  would then prefer a resolution that just failed - reintroducing exactly the churn this
  removes. Same reasoning for the toast.
- **`BingeGroupCacheRepository.sessionPin` could not be reused for this**, despite being the
  obvious home. `StickySourcePin.isEmpty` ignores `resolutionHeight`, so a resolution-only pin
  is *discarded* on save; and a non-empty one would make Streamlined skip its quality sheet.
  `sessionInstantHeight`/`saveSessionInstantHeight` is a separate map in the same file, keyed
  by `parentMetaId`, session-scoped for the same reason the sticky pins are, and cleared by the
  same `clearSessionPins()`.

**Two known gaps, both deliberate, neither started:**

- **No max-quality ceiling for Instant.** The user has no lever over resolution at all. It
  wants a profile-scoped key, which means three `PlayerSettingsStorage` actuals across both
  repos plus `syncKeys` and both sync-payload paths - and editing that key set is what wiped
  the playback settings in `0.4.0-beta`. Left for its own commit.
- **User codec/HDR/audio-language preferences are dead on the playback path.**
  `PlaybackSourceSelector.rank` hardcodes `CodecPreference.ANY` / `DynamicRangePolicy.ANY` and
  never populates `preferredAudioLanguage`; `PlaybackQualityOptions.preferencesFor` does the
  same. They work for downloads only. This is a real defect, not a missing feature, and fixing
  it changes what Instant picks for anyone who has set them - so it needs its own commit and
  its own smoke test.

**Verified:** `:composeApp:testAndroidHostTest` in `nuvio-z` - **687 tests across 96 classes**,
zero failures, errors or skips, including six new `stickyAffordable` cases.
`:composeApp:desktopTest` in `NuvioZDesktop` - **895 tests across 127 classes**, zero failures,
errors or skips, and it compiled `desktopMain`, which is the only local check that the new
`PlayerControlsAction.NextEpisode` arm and the `PlayerControlsState` fields actually build.
Four shared files are byte-identical across the repos
(`PlaybackQualityOptions.kt`, `BingeGroupCacheRepository.kt`, `PlaybackQualityOptionsTest.kt`,
`PlayerControls.kt`); `App.kt` and `PlayerScreenRuntimeUi.kt` were hand-ported.

⚠ **`controls.html` and `controls.js` have no automated coverage at all** - `desktopTest`
compiles `desktopMain` Kotlin and never parses the resources, so a typo there ships silently
and a duplicate `const` would blank the entire overlay. Checked by hand instead:
`node --check controls.js` passes, and `nextEpisodeLabel`/`nextEpisodeButton` are each declared
exactly once in the JS and appear exactly once as an id in the HTML.

**Not smoke-tested.** No Android device and no installed desktop app were available. **Nobody
has clicked the new button** - item A is verified by code inspection and a compiling desktop
build only. Still outstanding: the desktop button on a series (and hidden on a movie and on a
last episode), that the native next-episode card and skip prompt still work, three consecutive
Instant episodes holding one resolution, and Instant on a metered connection with a pin in play.

## Derived options: first smoke test, three fixes (2026-08-07, `0.4.9-beta`)

`0.4.8-beta` was tested on device and desktop. Three findings, all fixed:

- **"High" was the biggest file in the catalogue.** A Daredevil episode offered 85 GB as
  1080p High - 227 Mbps, which is a season pack's torrent-level size, not an episode.
  `SourceRanking` sorts size descending, so the largest number always headed the row and the
  quoted bandwidth was fiction. There is now a per-resolution plausibility ceiling (1080p
  50 Mbps, 2160p 150 - above the ~128 Mbps UHD Blu-ray maximum, so a genuine remux still
  leads). Implausible sizes cannot head a row, set its bandwidth, or set its displayed size;
  they sort last within it and stay reachable, because a pack often still resolves to the
  right file. A bucket with nothing credible falls back to an approximate estimate.
- **Instant still chose 1080p on a connection watching 4K.** Two causes, both too cautious.
  `HEADROOM` was 0.6 - a 1.67x margin, so a 19 Mbps 4K release read as needing 31. That suits
  a live ladder with no buffer, not a VOD player buffering seconds ahead with downshift behind
  it; it is now 0.75. And the Wi-Fi first-play default of 25 Mbps sat exactly on the boundary
  for a 7 GB 4K episode, so defaults are now Wi-Fi 50 / Ethernet 100 / cellular 10 /
  unknown 15. These are first-play guesses only; one minute of clean playback replaces them.
- **Streamlined on desktop skipped the sheet and went straight to the player.** Not a
  regression - a stored sticky pin outranks the quality sheet by design, and the pin was on
  that device and not on the phone. But a *persisted* pin turns Streamlined into Instant for
  that season with nothing in the UI to clear it and no clue why the sheet stopped appearing.
  Sticky pins are now session-scoped (`BingeGroupCacheRepository.sessionPin` /
  `saveSessionPin`), held in memory and gone on restart. The binge-group cache is untouched:
  it is a different key space (`parentMetaId`, not `stickyContentId`) and genuinely long-lived.

**Verified:** Android host suite and desktop suite both pass. Not re-smoke-tested.

## Quality options are derived from the catalogue (2026-08-07)

The preset model ran backwards. A fixed list of `PlaybackQualityTier`s was the input and the
addons' answers were filtered to fit it, so the sheet offered rows that matched nothing for a
given title, hid quality that was on offer, and quoted the preset's nominal bandwidth rather
than what the file you would actually receive costs.

Now the catalogue leads. `features/playback/PlaybackQualityOptions.kt` (pure, repository-free,
testable outside Gradle) buckets the real candidates by resolution, splits each bucket into
High/Low when its top source costs at least 1.5x its cheapest, and quotes
`fileBitrate / 0.6` - the connection speed that source actually needs. **An empty bucket
produces no row**, so a title with no 4K release simply has no 4K option. Instant takes the
highest option the estimate can carry; Streamlined shows them all with their bandwidth.

Details worth knowing:

- Bitrate uses the file's own runtime when an addon reports one. `SourceFacts.durationSeconds`
  now carries `clientResolve.parsed.duration`, which the extractor previously dropped; the unit
  is undocumented so it is inferred and discarded when not credible. Falls back to the title
  runtime, then the shared 45/120 minute default.
- `parseResolution` reads a bare `uhd`/`hd` out of a display name. A mislabel used to just fail
  a filter; now it would mint a visible 4K row that plays a 720p file, so a source whose bitrate
  is below the floor for what it claims is **demoted** to what its bitrate supports. Demotion
  only - a bloated 1080p remux is still 1080p.
- HDR policy follows resolution (2160 prefers, SD avoids), not the row's rank.
- Headroom is applied in exactly one place. `PlaybackQualityTier.sizeCapBytes` folded the same
  0.6 into a byte cap and is no longer on this path.
- **The network estimate had to move in the same change.** The effective gate was
  `fileBitrate <= 0.6 x estimate`, and the hardcoded WiFi default of 3 Mbps made that ~1.8 -
  below a real 720p encode. It only looked fine because unknown-size candidates skipped the cap
  entirely, which derived options remove. Defaults were raised again after this was written and
  are WiFi 50 / Ethernet 100 / cellular 10 / unknown 15 — and as of the network-strength work
  below they are never shown to the user as a connection speed. `recordSustainedBitrate` feeds
  the estimate from playback rather than
  only from downloads. It is **monotonic**: a stream arrives at the file's own bitrate and no
  faster, so a clean playback is a lower bound; smoothing it in would have dragged the estimate
  down towards whatever the user last watched and cost Instant its top qualities over time.
  Armed when a source is chosen, confirmed after a minute of unstarved playback. It reuses
  `AutoDownshiftDetector.SETTLE_GRACE_MS` before judging anything: a snapshot starts with
  `isLoading = true` and an empty buffer, so without the grace every source disqualified
  itself on frame one and the measurement could never fire.
- Two guards on erring high. `highestAffordable` returns null rather than Best available when
  a metered cap excludes every option - Best available is ordered resolution-descending, so
  the fallback would have handed a 4K remux to a capped mobile connection. And
  `resolutionForEstimate`, which feeds the download button that never asks, will not reach
  2160 on a `PLATFORM_DEFAULT` estimate; over-reaching costs a hiccup when streaming and ten
  times the disk when downloading.
- **`PlaybackQualityTier` is dormant, not deleted.** Nothing reads it to choose a source. Its
  storage key, sync entries and `mergeStoredTiers` are untouched on purpose: editing that key
  set is what wiped the playback settings in `0.4.0-beta`, and the removal buys nothing the user
  can see. Remove it in its own commit. `presetForTier` became `presetForResolution`, fed by a
  small `resolutionForEstimate` ladder, so the details-screen download button no longer keeps a
  second picker alive.
- Two stuck-spinner paths from the `0.4.3` smoke follow-up are fixed here, because this change
  rewrites the code they live in: `isStreamlinedSelectionReady` now treats "settled with streams
  but nothing selectable" as terminal (`toEmptyStateReason` reports no empty state in that
  case), and every early return in the streamlined effect clears the pending flag. Option ids
  are `resolution + variant` so they survive the `rememberSaveable` round-trip through a
  refetch.

**Verified:** `:composeApp:testAndroidHostTest` in `nuvio-z` - 675 tests, all pass;
`:composeApp:desktopTest` in `NuvioZDesktop` - passes, and it is the only local `desktopMain`
compile. Thirteen shared files are byte-identical across the repos; `App.kt`,
`MetaDetailsScreen.kt` and the three player runtime files were hand-ported.
**Not smoke-tested on a device or the desktop app yet.**

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
