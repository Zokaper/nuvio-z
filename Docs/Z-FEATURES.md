# Nuvio Z Feature Ledger

**This file is the canonical answer to "what is Nuvio Z".** Every feature Nuvio Z adds on top of
vanilla Nuvio has a numbered row here, with its state and the platforms it exists on. It covers all
three clients; `STATUS.md` remains the working handoff and records *how* each change was made and
what is still unverified.

Base: NuvioMobile `979d5680` (2026-07-29), which is **205 commits behind** `upstream/cmp-rewrite`
as of 2026-08-23 - run `scripts/upstream-drift.sh` for the current distance. 245 commits in
`nuvio-z`, 176 in `NuvioZDesktop`, 12 in `NuvioZWeb`. Last updated 2026-09-04.

**Audited 2026-08-23.** Every `feat:` and `refactor:` subject in all three repositories since its
fork base was mapped to a row here: 39 subjects in `nuvio-z`, 38 in `NuvioZDesktop`, 7 in
`NuvioZWeb`. Three desktop-only features had no row and were added - **P11**, **C17**, **C18**.
Everything else already had one. The audit also turned up two live faults in the web updater, now
fixed and recorded in **C5** and **C19**.

> Nuvio Z is a **mod** of Nuvio: a bounded, named set of patches that rides on a stated vanilla
> base. Vanilla features arrive by inheritance, not by re-implementation. See `Docs/UPSTREAM.md`.

## How to read this

**State**

| | |
| --- | --- |
| shipped | in a published release, through `0.4.14-beta` |
| **branch** | built and on `claude/setup-wizard-final-pass-wy7csp`, unreleased - this is `0.5.0-beta` material |
| **held** | built and deliberately not enabled |
| removed | built, then withdrawn; kept here so nobody re-derives it as missing work |

**Platforms** - `AND` Android, `iOS`, `DSK` Windows desktop, `TV` Tizen + webOS.

| | |
| --- | --- |
| yes | present |
| **port** | planned for that platform |
| **no** | deliberately absent - the reason is in the row or in `nuvioweb/docs/Z-PORT-MATRIX.md` |
| n/a | the feature is that platform's own infrastructure |

**`v1`** - the **target** state: does this feature ship in the first real release? Added 2026-09-04
in Phase 0 of `ROADMAP.md`. The State column says what exists; this column says what is *intended*.

| | |
| --- | --- |
| ships | in v1 |
| **held** | built, deliberately off, and a decision is owed before release. No feature is currently held |
| dropped | not in v1. The reason is in the row and in §11 |

**v1 means all six platforms in one release** - Android, iOS, Windows, macOS, Tizen, webOS - so a
feature reached by a later phase still targets v1. `ships, **desktop**` on **S3** is the one
platform-split target: Watch Together stays desktop-only, per Phase 5.

**The TV column carries no target on purpose.** What reaches the television is not a subset to be
read off this ledger - most features will not be ported, and those that are will need meaningful
change on the way. Phase 8 opens by going through this entire list and cherry-picking, and it sets
the TV targets then. Until then the TV column states only what *exists*, and
`nuviozweb/docs/Z-PORT-MATRIX.md` - which is more current than this file has been - is the better
answer for that platform.

> ⚠ **Three rows are still missing a platform cell**: **C17**, **C18**, **C19** carry six
> columns where the table has seven, so at least one platform value is absent or shifted. Do not
> read those three rows' platform columns until they are corrected; they belong to whichever
> phase owns them.
>
> **P11 and P12 were corrected in Phase 2**, which owns the playback area they sit in. Both were
> missing their **iOS** cell, which had shifted every value after it one column left - so P11
> read `AND=n/a iOS=yes DSK=n/a` for a feature whose own text says *desktop only*. P11 is now
> `AND=n/a iOS=n/a DSK=yes TV=n/a`; P12's transition lives in `commonMain`, so it is
> `AND=yes iOS=yes DSK=yes TV=n/a`.

**iOS carries a standing caveat.** Everything in `commonMain` applies to iOS in principle, and
**iOS compiles** - the Kotlin framework and the Xcode app both build in CI, unsigned, since
`21fd0d20` and `43155318` on 2026-08-25. But it has never been *run*: no signing, no installable
build, nobody has held it. An `iOS: yes` below means "shared code, no iOS-specific gap", not "seen
working". See §12 and Phase 7 of `ROADMAP.md`.

**Rule:** a new Z feature is not done until it has a row here with its platform column filled in.

---

## 0. Social and Watch Together (unreleased, feature-gated)

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **S1** | **Mutual friends and social profiles** — stable profile-UUID identity, unique handles, requests, removal, separate live/recent privacy controls, private Realtime invalidations and offline feed cache. | **branch** | ships | yes | yes* | yes | **no** |
| **S2** | **Watching Now and Friends Recently Watched** — sanitized 20-second playback presence with a 90-second server TTL, permanent idempotent watched events, consecutive episode runs, Home rows and the Social tab. Provider imports never create activity. | **branch** | ships | yes | yes* | yes | **no** |
| **S3** | **Watch Together** — private eight-profile lobby, invite codes, independent source fingerprints, host/collaborative commands, deterministic host transfer, and a timing plane carried between clients over the party channel: positions paired with the instant they were read, a host-anchored clock, and play and seek scheduled at a shared instant. The host picks a source and then starts the party as two separate presses, so nobody leaves the lobby until the host says so. Desktop only so far; verified 2026-09-03 against a live two-profile party (both members reached `ready` on one source, `status=playing`). | **branch** | ships, **desktop** | yes | yes* | yes | **no** |

Both capabilities default off in the backend and must be enabled independently after migrations and staging checks. Letterboxd, provider-history ingestion, OS push and NuvioZWeb are intentionally outside this release. `yes*` retains the standing manual-iOS verification caveat.

---

## 1. Downloads

The largest area, and the one the TV app cannot have at all - NuvioWeb has no download stack, and
anything reading a completed local download there is a constant `false` kept only so shared
ordering stays identical.

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **D1** | **Download presets** - Saver / Balanced / Quality / 4K Low / 4K High, each with an editable target resolution, GB-per-hour cap, codec preference, HDR/Dolby-Vision policy, preferred audio language and a prefer-cached switch. Picking a preset is how any bulk download starts, so nobody hand-picks twenty releases. `mergeStoredPresets` + `RetiredBuiltIns` carry new built-ins to existing installs. Quality was originally 2160p at 4 GB/h, which rejected every real 4K source; it was split into 4K Low (8) and 4K High (15). | shipped | ships | yes | yes | yes | **no** |
| **D2** | **Size preference within a preset** - largest-under-cap, smallest, or `MID_RANGE`: the median of the candidates that actually fit, so a title whose sources all sit far below the cap still lands somewhere sensible. Sizes above the cap are excluded first so an unusable remux cannot drag the target up. | shipped | ships | yes | yes | yes | **no** |
| **D3** | **Preset picker dialog** - the app's own tokens rather than Material defaults, a subtitle naming exactly what will download, localised season chips with All/None, one card per preset with a plain-language summary, and **select-then-start**. Tapping a preset used to queue a whole season on the spot. | shipped | ships | yes | yes | yes | **no** |
| **D4** | **Preset editor** - dropdown chips instead of tap-to-cycle enum rows, a cap slider showing what it works out to for an episode *and* a film, switches carrying descriptions, and a confirmed **Reset presets** wired to a repository function that had no UI at all. Raw enum names like `AVOID_HDR` are gone. | shipped | ships | yes | yes | yes | **no** |
| **D5** | **Batch downloads and the unwatched-season scope** - movie, episode, whole-season and selected-season planning, plus **unwatched episodes only**: a part-watched season downloads from where you are, not from episode 1. The option hides itself when nothing is left, and a scope resolving to zero episodes says so instead of persisting an empty batch. | shipped | ships | yes | yes | yes | **no** |
| **D6** | **Downloads as a first-class surface** - promoted from a settings page to a top-level tab in the classic, adaptive and tablet nav bars, the desktop sidebar and the iOS native tab bar. Artwork-driven needs-attention / live / **Preparing** / on-device sections, per-title and per-season delete, live download state on movie and series entries, per-episode idle / preparing / progress / paused / failed / downloaded controls, and a configurable **Downloaded** section on the meta screen. | shipped | ships | yes | yes* | yes | **no** |
| **D7** | **A real queue** - explicit `Queued` state with persisted ranks, append-on-enqueue (a season batch no longer downloads in reverse episode order), menu-based reorder with preemption of the lowest-priority running transfer, retry with backoff, cancel and bulk delete. Menu-based rather than drag so it works with a TV remote. | shipped | ships | yes | yes | yes | **no** |
| **D8** | **Transfer integrity** - a finished byte loop only counts as complete when bytes on disk match an authoritative total, and a total is never inferred from a transfer that stopped early. `If-Range` validators, correct 416 handling, honest short and overrun outcomes on all three downloaders. | shipped | ships | yes | yes | yes | **no** |
| **D9** | **Pause as a first-class outcome** - split into User and System rather than swallowed as a cancellation, with automatic resume on app foreground, reload and connectivity recovery. A user pause is sticky and survives a queue nudge, a reclaim sweep and a restart. | shipped | ships | yes | yes | yes | **no** |
| **D10** | **The download-freezing family** - four faults behind downloads stopping near 80%: body reads with no deadline, transfers the queue had lost, a size cap firing mid-transfer on an already-approved source, and debrid links minted once and never refreshed. Plus a generation fence so a cancelled attempt's last callback cannot be applied to its replacement. | shipped | ships | yes | yes | yes | **no** |
| **D11** | **Android stall watchdog** - Android sat on OkHttp's hardcoded 60 s read timeout and ignored the configured stall timeout entirely, so a silent source held a slot. It now decides before the read timeout and reports a stall as a stall rather than as a user pause. | **branch** | ships | yes | n/a | n/a | **no** |
| **D12** | **Notifications and background scheduling** - an ongoing notification while any batch prepares, and JobScheduler/WorkManager transfers with user-initiated-job handling. A declined job used to throw straight out of `start()`. | shipped | ships | yes | **gap** | **gap** | **no** |
| **D13** | **Batch reconciliation** - deleting everything from the Downloads tab used to leave series pages showing phantom "downloading" episodes. Reconciliation runs on publish and on load-from-disk, so already-broken installs heal on next launch. `FAILED` is excluded so discovery failures stay reviewable. | shipped | ships | yes | yes | yes | **no** |
| **D14** | **Tappable downloads toast** - unwinds the nav stack back to the tabs so the Downloads tab is actually visible from the details screen you started at. Typed action rather than a lambda, so navigation stays out of `core/ui`. Duration 2.5 s to 5 s. | shipped | ships | yes | yes | yes | **no** |
| **D15** | **Desktop download E2E harness** - the real repository and the real downloader over a raw socket with injectable faults. 30 local scenarios plus opt-in real-TorBox runs. It reproduced four production faults before their fixes: permanent re-mint failure retrying forever, a hung provider holding a slot forever, a same-sized different file appended to the old part file and marked complete, and a truncated replacement accepted at its shorter total. | shipped | ships | n/a | n/a | yes | n/a |

\* **D6 iOS gap:** the Downloads tab falls back to a generic system symbol; there is no Nuvio Z tab
asset for it.

**D12 gap:** the live-status hook is a **no-op on iOS and desktop**. Both only show preparation
inside the Downloads tab; neither has an equivalent of the Android ongoing notification.

**Known limitation, all platforms:** a batch **cannot be cancelled while it is preparing** - the
coordinator would re-save it.

---

## 2. Playback modes

Three global modes, chosen once, stored per profile and synced across devices, with a per-play
escape hatch - long-press on mobile, right-click on desktop, the existing hold menu on TV - that
always reaches the Classic source list. The player keeps a "Change source" action in every mode.

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **P1** | **The mode system** - Classic is upstream's flow untouched and the fallback when auto-pick misjudges. Streamlined asks one question. Instant asks none. The router is a pure function carrying the precedence table: explicit manual play, then a completed local download, then the mode. One shared mode card with a tagline and Streaming / Downloading blocks is used by both the settings dialog and the wizard, so the copy cannot drift - that drift is exactly how Instant kept a stale "Not ready yet" caption in `0.4.0-beta`. | **branch** | ships | yes | yes | yes | in flight |
| **P2** | **Streamlined** - show a quality sheet, pick the source, cover the source list while deciding, and carry a capped three-source failure chain. The sheet is shown on every title and every episode: the remembered quality band and reuse-last-link both skipped it for a choice made earlier, which read as the app deciding for you with nothing on screen to disagree with. | **branch** | ships | yes | yes | yes | reachable, **unwatched** |
| **P3** | **Instant** - Streamlined with the sheet auto-answered: same picker, same failure chain, same overlay. Waits for the connection measurement to settle before deciding, asks once on a metered connection (Data saver / High quality; dismissing answers Data saver), and raises *"Playing 1080p - WEB-DL - TorBox - Change"* naming what actually opened. Withdrawn twice; every reason it was pulled has been answered by work done for Streamlined. | **branch** | ships | yes | yes | yes | **port**, Phase D |
| **P4** | **Mode-aware download entry point** - the mode changes where a download *starts*, not the engine. Classic downloading a single item opens the source list and downloads the release you tap, but a whole season still gets the preset dialog. Streamlined keeps the dialog. Instant starts with no dialog, on the preset matching the connection tier. | **branch** | ships | yes | yes | yes | **no** |
| **P5** | **Progress overlay and the covered-screen rules** - Instant and Streamlined no longer make you watch a wall of releases populate and get replaced. Named steps - Finding sources, Choosing source, Checking connection, Resolving link, Starting playback - every one mapped to state that already existed rather than a timed fake, plus an "Attempt 2 of 3" counter naming the source that died and a **Choose source manually** escape after the first failure or 5 s. A pure function decides the whole stack in one place, and **every bail-out uncovers the list**: a spinner over a screen the user has to answer is worse than never covering it. | **branch** | ships | yes | yes | yes | **port**, wired but not in the player |
| **P6** | **Startup watchdog** - the old rule waited 8 s and asked "is it playing yet", which cannot see a filling buffer, so Streamlined and Instant were discarding perfectly healthy sources - a debrid mint, a cold provider, a 60 GB remux seeking a keyframe - and then blaming the catalogue. Replaced by a progress-measuring watchdog with four clocks / two-tier startup deadline: 20 s for a completely dead source with no evidence of life, 35 s when credible evidence of life exists (duration > 0, buffer > 0, progress > 0, or HTTP probe pass), 12 s stall deadline after progress, and a 60 s hard ceiling. Failures log the reason, elapsed time, best progress and engine. Verified on desktop and packaged builds. | **branch** | ships | yes | yes | yes | **wired**, auto picks |
| **P7** | **Auto source-swap / automatic downshift** - the proposed mid-playback swap was never enabled or run on a device. Phase 2 deleted the detector, candidate builder, setting/storage/sync key, forced-swap HUD controls and swap log after confirming that null direct URLs made its identity check discard every unresolved alternative. Manual in-player source switching remains. | removed | dropped | - | - | - | **no** |
| **P8** | **First-launch mode selector** - originally a standalone full-screen question; now step 2 of the setup wizard on the KMPs. On TV it stays standalone, because the TV has no wizard. | removed as standalone | via wizard | via wizard | via wizard | via wizard | **port** |
| **P9** | **Sticky season pin** - release group, then binge group, then addon/provider/resolution, with a scored match. | removed | dropped | - | - | - | **no** |
| **P10** | **`PlaybackQualityTier`** - the original preset-shaped bandwidth budget, replaced by catalogue-derived options and then deleted outright with its storage key and all four actuals. | removed | dropped | - | - | - | **no** |
| **P11** | **Desktop player Next Episode control** (desktop only) - the desktop player had no Next Episode button. The Compose player has had one all along, but desktop does not render that control bar: the desktop player moved to a native HTML overlay and its action row was never given a next-episode entry. `controls.html` / `controls.js` / `NativePlayerController` gain a `nextEpisode` command wired to the same `playNextEpisode()` the Compose pill calls, reusing the existing `#icon-skip-next` symbol and the `player_next_episode` string - no new icon, no new string key. The `!isDesktop` guards on the Compose next-episode card and skip prompt are deliberate and stay: the HTML layer owns both. ⚠ `controls.js` has no automated coverage and **the button has never been clicked**. | **branch** | ships | n/a | n/a | yes | n/a |
| **P12** | **Coordinated in-player next-episode transition** - manual actions follow the active mode (Classic list, Streamlined sheet, Instant auto-pick), while automatic transitions resolve without interrupting the current episode, count down with cancellation, reject stale results and remain covered until the next episode's first playable frame. Repeated taps cannot create a second request or player swap. | **branch** | ships | yes | yes | yes | n/a |
| **P14** | **One loading surface, from chosen source to first frame** - `PlaybackLoadingScreen` is owned by `PlaybackLoadingController` and drawn by `PlaybackLoadingHost` **above `NavDisplay`**, so one surface spans the route change and every failover rather than being destroyed and re-created by whichever route was on top. Rendering it from one `PlaybackLoadingState` was not enough: the *lifetime* was the fault. A failover is now a state change under a screen that never stops drawing, so it reads as "Attempt 2 of 3" rather than as a reload. Features a fixed five-slot rail (Resolution, Audio/Subs, Range, Audio, Size) with honest `—` unknown indicators and SDR fallback, plus a reserved escape-hatch row above the progress line so "Choose source manually" appearing at 5 s never grows the band under the reader. One entrance (`PlaybackEntranceMotion`, 260 ms, backdrop then staggered title and band; fade-through navigator on desktop), one exit (300 ms, gated on a decoded frame via `PlaybackHandover.hasFirstFrame`), and nothing in between may animate. On desktop the native canvas and the JCEF overlay are painted the app's background so the takeover is invisible; `didPaintOpening` measures what is left. | **branch** | ships | yes | yes | yes | **port**, adapt to web player |
| **P15** | **Content-identity gate** - `ContentIdentityGuard` demotes confidently wrong season/episode/year candidates in auto modes. It partitions rather than filters, so a bad catalogue cannot create a dead end, and every demotion is logged. | **branch** | ships | yes | yes | yes | **port**, Phase 8 |
| **P16** | **Preflight source probe** - one `Range: bytes=0-1` GET against a resolved URL before a frame is attached (`PlaybackSourceProbe`, `probePlaybackSource`). Turns two things that were previously invisible into facts: a source that answers 4xx/5xx or an HTML/JSON body is failed in one round trip instead of costing the startup watchdog twenty seconds, and a debrid provider's "being prepared" slate is caught by its served size against the release's claim. Backed by `PlaybackDurationPlausibility`, which abandons a source whose reported duration is both a fifth of the expected runtime and under ten minutes - the case that *played*, and so was scored as a successful start. Every unknown passes; the probe never blocks a working play. | **branch** | ships | yes | yes | yes | **port**, Phase 8 |
| **P17** | **Streamlined quality columns (wide)** - the wide branch (>= 768 dp, so desktop and large tablets) leads with Best available as a full-width strip quoting its release, size, needs and connection meter, then lays the alternatives out as one column per resolution, each stacking only the bands that title has. ⚠ **Nothing scrolls, by construction:** `VideoResolution` has six members and `optionsForBucket` emits at most four bands each, so the offer is bounded and fits the width. The card grid it replaced was a phone layout scaled up; the quality table between them was capped at 480 dp and cut its last row mid-glyph with no scrollbar. A band that tops its resolution without reaching Max is marked `High (Max)` (`PlaybackQualityOptions.isTopBandBelowMax`) - the band word is never rewritten, because the bands are absolute. A resolution with one release has no band of its own, so `bandFor` derives its class from its own bitrate against the same boundaries and it reads `Mid (Max)` as well; only a release nobody sized keeps a fallback label, because inventing a class for an unmeasurable file is what banding on sized sources alone exists to prevent. Each cell leads with dynamic range and audio as marks (`describeProvenance` splits the rip type and host back out of `describeRelease`, which had folded `DV` into a sentence whose loudest tokens were the two least useful facts on offer); `SDR` is drawn muted rather than accented, since the ordinary case must not wear the emphasis. The cell Best available resolves to is outlined, not restated (`PlaybackQualityOptions.sourceKey`). Panel max width 920 -> 1200 dp. The phone branch keeps its card grid. | **branch** | ships | n/a | n/a | yes | no |

**P7 was deleted rather than promoted.** It had been held since `0.4.9`, had never run on a
device, and Phase 2 confirmed a catalogue-wide failure for unresolved sources. Mid-playback source
replacement is too invasive to keep as dormant release code without evidence that it is clearly
better than the manual action already available.

**P9 was withdrawn for cause**: reachable only from the escape hatch, invisible once set, and it
silently suppressed the quality sheet for a whole season with no way to see or clear it. The model
survives in code with no readers.

**Do not reset the "Instant selection handled" latch.** Resetting it re-seeds the failure chain and
loops forever. Recorded here because it is a trap, not an implementation detail.

**Phase 8 web drift:** NuvioZWeb reimplements the Kotlin playback decisions rather than sharing
them. Its port must explicitly pick up P14/P15 and `PlaybackPosition`: the web loading screen still
has separate ownership, the content gate is absent, and duration-derived seeks do not yet share the
KMP plausibility/clamping policy.

---

## 3. Source and stream selection

The largest *shared* area - almost all of it pure logic with no framework in it, which is why it
ported to a completely different codebase at all.

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **S1** | **Quality options derived from the catalogue** - instead of a fixed tier list filtered down, the real candidates are bucketed by resolution and each bucket banded. An empty bucket produces no row, so a title with no 4K release simply has no 4K option, and each row quotes the connection speed the file it would actually open needs. | **branch** | ships | yes | yes | yes | modules **landed** |
| **S2** | **Absolute quality bands** - Max / High / Mid / Low with fixed Mb/s boundaries (4K 10/25/50, 1080p 3/8/16), replacing geometric thirds of each title's own spread where "4K High" meant an 88 GB remux on one title and a 14 GB WEB-DL on the next. Fewer than two occupied bands collapse to one unlabelled row: a lone row reading "1080p Mid" is a comparison with nothing to compare against. Sizeless sources join the cheapest band rather than forming a phantom Low row. | **branch** | ships | yes | yes | yes | modules **landed** |
| **S3** | **Quality selector grid** - one card per resolution with the bands stacked inside as the tap targets, in a responsive grid: a centred panel at 768 dp and above, a modal bottom sheet below. The body renders **one of three states and never a blend** - a skeleton while the figures are still moving, the final grid, or the no-match text. Dismissing returns to details; only "Choose source manually" reaches the source list. | **branch** | ships | yes | yes | yes | **port** |
| **S4** | **Preferences dialog on the sheet** - a dialog, not a navigation, because Settings is a different back stack and going there would cost the user the episode. Rows cycle rather than opening pickers, writes go through the real repository setters, and the grid behind rebuilds itself. | **branch** | ships | yes | yes | yes | **port** |
| **S5** | **Codec, HDR, audio-format and language preferences applied to playback** - these existed for downloads only and were dead on the playback path, so `REQUIRE_HDR` meant nothing. They now decide what auto-pick opens. `REQUIRE_*` **demotes by -100 rather than excluding**, so the source stays in the failure chain; downloads still exclude. | **branch** | ships | yes | yes | yes | **port** |
| **S6** | **One release-name parser** - the app had **two that disagreed about the same file**: the badge row read HDR10+, Dolby Vision and Atmos correctly while the picker read them as SDR. Both now delegate to one import-free parser, and four defects died with it - a regex that backtracked so every HDR10+ release was labelled HDR10, `hdr10plus` matching nothing at all (so such a release read as SDR and was ranked *below* a plain HDR one by the very preference asking for HDR), `dovi` unrecognised, and substring matching so `cam` hit inside *Camelot*. | **branch** | ships | yes | yes | yes | **landed** |
| **S7** | **Language vocabulary and the language gate** - the token list knew **seven** languages, so Hindi, Italian and Russian releases declared nothing and were indistinguishable from an untagged English one; `MULTi` and `DUAL` were unrecognised and are not languages; flag emoji were unhandled, which is how Torrentio, Comet and MediaFusion all label audio. Now ~120 ISO aliases and ~72 names. The gate **partitions, never filters**: deleting unwatchable candidates empties the chain on a title released only for another market. | **branch** | ships | yes | yes | yes | **landed** |
| **S8** | **Plausibility ceiling, fake-8K demotion and AI upscale / CAM ranking** - an 85 GB "1080p" season pack used to head every row because size sorts descending. A per-resolution plausibility ceiling now stops an implausible size heading a row, setting its bandwidth or setting its displayed size. Separately, nominal 8K AI upscales fold into 4K tier on displays below 8K (< 4320p), AI-upscaled releases receive an `AI_UPSCALE_PENALTY` (-8) and restrained danger chip, and CAM/TS releases receive a `CAM_TS_PENALTY` (-20, tier -1) with restrained provenance styling. Proper 4K releases comfortably beat nominal 8K AI upscales. | **branch** | ships | yes | yes | yes | **landed** |
| **S9** | **Failure chain and dead ends** - a family of fixes so no path leaves the user on a covered blank screen: the chain is capped to its budget, the P2P-decline and uncached and early-return paths all report something, both unbounded waits are bounded, a dead debrid link can no longer loop forever inside the player, backing out of a not-yet-started player no longer relaunches it, and backing out of Streamlined lands on details in **one** press rather than the source list. **Phase 2 extended this.** Desktop's `onFatalPlaybackError`/`onPlaybackStarted` had been dropped by the `0.1.22-alpha` sync, which left the startup watchdog unarmed, the post-playback failover chain unable to advance, and `consumeFailoverRetry()` permanently false - restored. Every position derived from a duration now goes through pure `PlaybackPosition`, which clamps and refuses implausible durations by name rather than seeking into them. `AddonStreamGroup.error` is no longer discarded: it prints in the list and becomes the failure reason when the caller has none. All 13 ways into the source list carry a named path and log it, and `hasSilentUncover` makes "the list appeared and nothing said why" a failing test rather than a convention. | **branch** | ships | yes | yes | yes | **port** |
| **S10** | **Unknown is not cached** - Instant auto-played a not-cached-yet placeholder because the cached flag was *null* rather than false. Auto-pick now requires positive evidence of a cached copy, and uncached candidates stay out of the fallbacks list. Scoped to debrid-backed candidates only: a plain HTTP link legitimately has no cache state. | **branch** | ships | yes | yes | yes | **landed** |
| **S11** | **Debrid stream preferences without a built-in cloud account** - a user whose debrid runs *inside* the addon (AIOStreams and anything like it) had the **entire Debrid settings page doing nothing**: filter, sort, cap and name templates were all gated on owning your own API key. New persisted scope - resolver-only / debrid / **all addon streams**, defaulting to all - with a Stream preferences section that is always enabled, a hint when no account is connected, and AIO metadata read as a fallback for resolution, codec, HDR, audio, languages, size and sub-addon name. | **branch** | ships | yes | yes | yes | **port**, after Phase E |
| **S12** | **"No streams found" over a full catalogue** - the list auto-filtered to the addon that last served a show, but a group exists for every addon that was *asked*; filter to one that answered nothing and the empty state drew over everything the others found. | **branch** | ships | yes | yes | yes | **no** - upstream bug, send it to vanilla |
| **S13** | **Plugin metadata survives ingestion** - scraper results had quality, size, seeders, peers, provider and language joined into one display string, dropping seeders and peers entirely, which the facts extractor then regexed back apart. Now a typed record. | **branch** | ships | yes | yes | yes | **no** - no JS plugin runtime on TV |

**S11 has a behaviour change to declare in release notes.** Under the default scope, existing users
*with* an account now see non-resolver addon results participate in filtering, sorting and result
caps where they previously passed through untouched. The sharpest edge: a plain addon row with an
unparseable name reads as unknown resolution, so a minimum-quality setting of 1080p now hides it.
Resolver-only is the one-tap opt-out on the same page.

**S6, S7, S8 and S10 have already landed on TV** as Phase A of the web port. There they are a
larger upgrade than they were here: vanilla NuvioWeb's only structured reading of a source was four
string-contains checks returning one of five quality labels.

---

## 4. Network strength

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **N1** | **Measured, not assumed** - the quality sheet was printing a *preset* as if it were a measurement: 50 Mb/s for any Wi-Fi, rendered verbatim as "Your connection: about 50 Mb/s" with no hedge, and every meter and over-connection warning scored against it. Now a bounded ranged GET runs beside the source fetch the screen is already waiting on, a passive meter converts buffer growth against the playing file's bitrate into a real rate, estimates persist across cold starts (aged out at 7 days, 32 entries), and the connection line is tappable to re-test - there was previously no way to ask for a fresh reading at all. | shipped `0.4.13-beta` | ships | yes | yes | yes | **port**, Phase C |
| **N2** | **The throughput gauge** - the measured figure has been wrong in both directions across three corrective passes. Final shape: skip the first eighth of the bytes (TCP slow start), partition the rest into eight fixed byte blocks, report the **lower median** - so a mid-transfer stall and a kernel-buffer drain are both discarded. | **branch** | ships | yes | yes | yes | **port**, Phase C |
| **N3** | **The figure does not move while it is read** - the sheet used to show a stale number, then "Checking", then a new one. A device falsified the first fix: the five-second deadline published a link-type guess while the real probe was still running, then the late measurement replaced it. Decision settlement and figure settlement are now separate: Instant may choose at the deadline, but the sheet withholds its figure and derived verdicts until the probe actually finishes. Three pure cases pin the deadline-first/probe-later ordering. **Confirmed on the reporting handset:** `.24` displayed 541 Mb/s once without changing, versus 497 Mb/s from Ookla. | **branch** | ships | yes | yes | yes | **port**, Phase C |

**The probe measures the source, not the line.** When the top option has a direct URL it pulls from
that host with that source's own headers and files the answer under its provider id. Only a
candidate that still needs resolution, or a manifest, falls back to a generic speed test, and that
result is stored against **no provider** - a fast CDN must never vouch for a slow debrid. **No
debrid link is ever minted to run a probe**, and metered connections are never probed at all.

**A flat buffer and a draining buffer are not the same reading.** A full buffer back-pressures the
transfer down to the file's own bitrate, so a flat window measures the *file*; two of them stop the
meter. A *draining* buffer is the line failing to keep up and is reported even when it is below an
earlier window, because suppressing it is how an estimate survives being disproved.

**TV open question:** the meter reads the video element's buffered range. Tizen's AVPlay pipeline
has no such element. Settle that before Phase C is written - a different reader, or null and no
figure.

---

## 5. Setup wizard

Vanilla Nuvio has **no onboarding at all**. Everything below is Nuvio Z's, is unreleased, and has
never been rendered outside CI.

**Not ported to TV.** It is a redesign, not a port: the shape is phone-specific throughout, and the
TV already has its own onboarding. The one step Z genuinely adds is the playback-mode question,
which ports on its own as **P8**. See `nuvioweb/docs/Z-PORT-MATRIX.md`.

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **W1** | **The eight-step wizard** - Welcome, playback mode, cards, home, details, theme, sources, done. Two opaque regions and **nothing is ever drawn behind text**: a full-bleed specimen band showing what this step changes, an opaque panel of at most four controls, a hairline between. Nothing exceeds four controls so **no panel scrolls on a phone**. Sources is *dropped*, not shown-and-skipped, when the profile already has an enabled addon. Choices apply the instant they are tapped, through the real settings setters. | **branch** | ships | yes | yes | yes | **no** |
| **W2** | **Welcome is a real screenshot** - the app's actual home composables at the app's real metrics with a seeded list state so the hero parallax is the app's own, under a blur, pointer-blocked so it cannot scroll under your thumb. | **branch** | ships | yes | yes | yes | **no** |
| **W3** | **The playback-mode storyboard** - an animated loop per mode from tapping a title to playing it. Classic stops on a wall of releases with a finger walking every row; Streamlined asks one short question and settles a release **with no pointer anywhere**; Instant goes straight to play. The sequences are data in an import-free file, so "Streamlined picks the release itself" is a claim a test can hold. | **branch** | ships | yes | yes | yes | **no** |
| **W4** | **Show-once-by-revision** - an integer, profile-scoped and synced, currently at revision 6. A boolean could never re-ask, and keying on the app version would re-show the whole wizard every release. Finishing also marks the old mode-selector seen, so a downgrade does not re-prompt. Settings, About, **Run setup again** re-runs it dismissibly, over the app rather than gating it. | **branch** | ships | yes | yes | yes | **no** |
| **W5** | **Fetched sample artwork** - public metadata-hub URLs, never bundled, because poster art is copyright and both repos are public. The wizard must be fully usable with no network. | **branch** | ships | yes | yes | yes | **no** |
| **W6** | **Wizard-adjacent fixes that reached the real app** - a root that consumed no pointer input, so taps missing a control went through to the page underneath (**the second time that defect shipped**; now a rule in `AGENTS.md`); "Group sections into tabs" doing nothing anywhere, because a tab group needs more than one member and normalisation seeded every section with none; and details-section tabs below the interactive minimum tap target, with the outgoing crossfade half hit-testable for 200 ms. | **branch** | ships | yes | yes | yes | **no** |
| **W7** | **Render harness** - every specimen at two widths in every palette, with and without AMOLED, plus every storyboard frame, written as PNGs and uploaded by CI on every push. | **branch** | ships | n/a | n/a | yes | n/a |

**Five earlier shapes shipped to the debug line and were wrong. Do not restore any of them:**
preset-first; a translucent sheet over a live preview (on a device the home screen read straight
through it, and the sheet's gradient was most transparent at its top edge, so the worst of it was
behind the heading); a preview that followed whichever control was last touched (every control
changed something visible, and the object being studied kept getting swapped out mid-thought); a
Trakt step (it offered a connection that is not functional yet); and a hand-drawn home miniature.

**The specimens are purpose-built, and that was a reversal.** Revisions 1 and 2 rendered the
shipped composables on the argument that a preview built from the real thing can never drift. That
argument is true and it was still the wrong trade.

**Nobody has looked at the render-harness PNGs yet** - five passes running.

---

## 6. Settings

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **C1** | **Global "Show advanced settings"** - one switch; rows tagged advanced render nothing when it is off. Per-row annotation rather than restructuring pages, because a defaulted parameter is something a future row gets right for free. **The default when unset does not guess how old an install is**: it asks whether this profile has ever *stored* a value for an advanced setting, and an explicit stored `false` counts - turning something off is as deliberate as turning it on. Settings search deliberately keeps indexing hidden rows and reveals them on the page it lands on; hiding a setting the user just searched for by name would be worse than showing it. | shipped | ships | yes | yes | yes | **no** |
| **C2** | **Settings page reorganisation** - Playback was 3,903 lines carrying 11 sections, with decoder options sitting next to Content Warnings while the Advanced page had four rows. Playback is now Player, Source Preferences, Audio, Skip Segments, Next Episode, plus a Subtitles page of its own; decoder, the iOS output sections, P2P, Stream Selection and Stream Auto-Play moved to Advanced. Nothing was deleted and every row kept its storage key, so there is no migration. Settings content on a wide monitor is clamped to 960 dp centred - it was spanning ~2,200 px on a 2560 px window. | **branch** | ships | yes | yes | yes | **no** |
| **C3** | **Sync-wipe fix across every settings store** - the replace-from-payload path cleared **all** sync keys before applying, so a remote blob written by an older build silently deleted every new key. That is what wiped playback settings in `0.4.0-beta` and what re-gated the app behind the wizard. Six stores across **19 actuals** now clear only the keys the payload carries. | **branch** | ships | yes | yes | yes | n/a |
| **C17** | **Adjustable interface size** (desktop only) - the automatic UI scale clamped at 1.18x, so a 3840x2160 window asked for 2.63 and was handed 1.18, laying the app out into a 3254x1831 dp space and drawing every fixed dp at roughly a third of its intended size. Nothing was broken; there was simply no headroom, and on a 4K panel it read as tiny. The automatic ceiling is raised to 2.2 and an explicit setting sits on top of it, with Ctrl +, Ctrl - and Ctrl 0. The stored value is a zoom **percentage of the automatic scale**, not an absolute density, so 100% means the same thing on a laptop and on a 4K panel and Ctrl 0 is simply "reset". At the default, 1280x820 is unchanged at 1.00, 1920x1080 goes 1.18 -> 1.32, 2560x1440 -> 1.76, 3840x2160 -> 2.20. The 1080p change is the one worth watching. | **branch** | ships | n/a | yes | n/a |

**C1's advanced tagging is deliberately small** - the Advanced page row, torrent auto-pick,
decoder priority, and two playback compatibility switches. Nothing a normal user
changes is tagged.

**The Advanced nav row is no longer tagged advanced.** Playback Engine lives there now and it is
the main lever for fixing broken playback; hiding it behind the switch would hide it from exactly
the users who need it.

**C2's silent failure mode:** settings search was repointed in the same change. A row indexed
against its old page is still *found*, and then navigates somewhere that does not contain it. Two
groups ended up split across two pages, so rows carry a section and a page override.

**C2 has never been seen on a screen**, and no test in either repo can see where a settings row is
drawn.

**C2 is also the case study for the patch-surface rules** in `Docs/UPSTREAM.md`: a pure re-layout
of upstream-owned settings pages, still unverified, permanently on the merge path. Under the
doctrine we stop making changes shaped like this.

**Why C2 is not ported to TV:** the TV's settings are twelve sections organised differently, and
the mobile layout does not map onto them.

**Why C1 is not ported to TV:** the TV already ships an Essential/Advanced experience mode chosen
at first launch - vanilla's own answer to the same problem. Porting ours would give one television
two competing advanced-mode concepts. If TV settings need trimming, extend the existing experience
mode instead.

---

## 7. What's New and the updater

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **C4** | **What's New** - after an update, a screen listing what changed. The current version's notes are **curated and offline**, because the screen has to work on the first launch after an update and on builds where the updater is off; the fetched history below it reuses the request the updater already makes. Markdown is actually rendered rather than shown as literal `## Fixes` and `- ` text, and unrecognised syntax falls through as a paragraph - showing a line we did not understand beats dropping it. Also reachable from Settings, About, and **that path deliberately does not record the version as seen**. | shipped | ships | yes | yes | yes | defer |
| **C5** | **Fork update line** - in-app updates repointed from upstream to `Zokaper/nuvio-z`, which is why that repo must stay public: the updater is unauthenticated. The release-channel filter was cleared - it matched a branch name against a release's target commitish, which is a commit SHA, so it rejected every release. **On web this was still pointed at `NuvioMedia/NuvioWeb`** and was found by the 2026-08-23 audit: a Nuvio Z install would have been offered a vanilla NuvioWeb package and overwritten the mod with the thing it is a mod of. `scripts/release-build-poller.mjs` had the same default. Both repointed at `Zokaper/NuvioZWeb`, with the updater's first tests. | shipped | ships | yes | yes | yes | **fixed, unwatched** |
| **C6** | **Debug update line** - debug builds install under their own application id, so stable APKs could never update them and testing a fix meant sideloading by hand every time. Debug builds now read prereleases on a `debug-v*` tag prefix; the stable channel already discards prereleases, so the two lines cannot see each other. | shipped | ships | yes | n/a | yes | built, **never dispatched** |
| **C7** | **Desktop debug channel** - a separate "Nuvio Z Debug" application installing beside the release app, switched by one build flag: different package name, different MSI upgrade UUID, different macOS bundle id, separate data directory, diagnostics HUD and file log on by default. | shipped | ships | n/a | n/a | yes | n/a |

**Three pieces of C6 exist for reasons that are not obvious:**

- **A committed debug keystore**, with an explicit ignore negation. It is not a secret - it signs
  debug builds only. It exists because Android refuses an install whose signature changed, and the
  build tool's default debug key lives per machine, so two machines (or a machine and CI) produce
  mutually un-installable APKs. The release keystore is still excluded and must stay that way.
- **A debug counter** producing a fourth version component and a derived version code. Without it,
  every debug APK cut from one release version looks identical to the installed one and no update
  is ever offered. **Bump it for every debug build you publish** - that is the whole mechanism.
- **Version normalisation strips the `debug-` prefix before the `v`.** Left on, `debug-v0.4.9-beta.2`
  tokenises to `[4, 9, 2]` - the leading zero is lost with the `v0` token - and every debug release
  outranks every local version permanently.

**C7's load-bearing detail is the MSI upgrade UUID.** Sharing it would make debug MSIs *replace*
the release install. The desktop channel is keyed on the **tag prefix**, not the prerelease flag,
because the desktop updater already includes prereleases - so a naive mirror of the mobile approach
would have offered every debug build to every release install.

**C4 requires a curated entry per release, committed before the version bump.** The release
workflow rejects any file changed between the bump and the release commit.

**The debug counter must live in its own file.** A counter bump inside the release version file was
read as a release bump by the notes script, which truncated the next release's notes to whatever
came after it. `0.5.0-beta`'s generated notes are **already broken** by this and must be curated by
hand.

**C5 on TV is unverified**: check whether `js/core/update/appUpdateService.js` points at
`Zokaper/NuvioZWeb` or is still on upstream.

**C4 on TV is deferred, not refused.** Cheap - the TV already fetches releases for its update
prompt - but a full-screen changelog on a television at launch is more intrusive than on a phone,
and nothing on the TV has been watched running yet. Revisit after Phase E.

---

## 8. Diagnostics

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **C8** | **Playback diagnostics HUD** - debug-gated and off by default. Real buffer-ahead, position and duration **labelled with the live engine**, source resolution / release group / provider / addon, the provider-keyed network estimate and its confidence, attempt state, and the last failure. It warns explicitly when libmpv is live, because the ExoPlayer throttle cannot reach it. P7's forced-swap controls were deleted with automatic downshift. | **branch** | ships | yes | yes | yes | log lines only |
| **C9** | **Debug bandwidth throttle** - live throttling of the player's download speed to off / 20 / 10 / 5 / 2 Mb/s, so "I walked downstairs" becomes a button and buffering behaviour can be reproduced on demand. | **branch** | ships | yes | **no** | **no** | **no** |
| **C10** | **Swap log** - the bounded diagnostic log for automatic or forced P7 swaps. | removed with P7 | dropped | - | - | - | **no** |
| **C11** | **Desktop self-test harness** - one debug-only button (or a keyboard shortcut, or a build flag) that runs the device script against **real services** and writes a report plus screenshots. Suites: environment, real source fan-out with per-addon latency, debrid resolve plus a 1 MiB range GET proving the link serves bytes, real playback read straight out of mpv, settings and sync round-trip, and a screen-grabbing UI walk **with a network** so the artwork is real. | **branch** | ships | n/a | n/a | yes | n/a |
| **C18** | **Desktop debug run mode and session log** (desktop only) - a desktop playback failure gets reported as "it just stopped", and a packaged build has no console, so the evidence is gone. `DesktopDebugLog` tees stdout and stderr into a timestamped file under the app data dir, beside the state it explains, plus a default uncaught-exception handler - the AWT event thread is how a desktop playback crash normally presents, and it would otherwise vanish with the window. Capture is via the stream tee, deliberately **not** a Kermit `LogWriter`: Kermit's JVM writer already prints to stdout, so a file writer on top wrote every line twice. The tee buffers to the newline before stamping, because print-without-terminator shredded single log lines across several entries. Both observed, not theorised. Gated by `-Pnuvio.desktop.debugTools=true`, the same flag as the HUD, so both stay out of the shipped app. | **branch** | ships | n/a | yes | n/a |
| **C19** | **Z-revision ordering in the web updater** - a Nuvio Z version is a vanilla version plus a Z revision, and the web comparison could not see the revision at all: `parseAppVersionParts` splits on `-` and keeps only leading digits, so `z2` yielded nothing and `0.3.40-z2` parsed to the same `[0, 3, 40]` as `0.3.40-z1`. The *first* `-z1` release would have shipped fine, because the base moves forward from `0.3.37`; every release after it on that base would silently never have been offered. `parseZRevision` reads the suffix and the comparison uses it as a tie-break **after** the vanilla base, so the base decides first (which is what lets the revision reset), a suffix-less build is revision 0, and the debug counter does not hide it (`0.3.40-z1.3` is revision 1). Known limit, pinned by a test: a base going *backwards* still cannot be ordered by the string - that is what `RELEASE_SERIAL` is for on the KMP apps. | **branch** | ships | n/a | n/a | yes |

**C8's flag is a non-persisted in-memory value** - it resets on every app start.

**C11's shared-code cost is 12 lines with no expect/actual.** On mobile every hook stays null,
which hides the row. Two new mpv bridge exports are gated behind the debug-build check.

**Three C11 suites are not written yet:** seek and track switching, playback modes and the failure
chain, and a real download. Two findings from actually running it are open: the home screen draws
with **zero catalog rows** on a profile whose addons return 88 to 1,020 sources, reproduced 3 of 3
and unresolved.

**C10 on TV should go into the debug console the TV already has**, not as a port of the HUD. Cheap,
and the difference between a bug report and a guess when something goes wrong on a television.

---

## 9. Product identity

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **C12** | **Nuvio Z identity** - Android label, application ids for release and debug, launcher icons, and the downloads notification. The Kotlin namespace and callback schemes stay upstream's on purpose. | shipped, **partial** | ships | yes | **gap** | yes | minimal only |

**The rename is deliberately incomplete, and this is the honest statement of it:**

- **42 of the 43 product-name strings still say "Nuvio"**, including the brand name, each with
  about 20 locale variants. Only the wizard's own copy was renamed.
- **The logo wordmark has "Nuvio" baked in as pixels** and is drawn on the splash and both auth
  screens.
- One licences row still says "Nuvio Mobile".
- **iOS's product name is still `Nuvio`**, so the iOS home-screen name is wrong.

**Finishing it is a patch-surface decision, not a cosmetic one.** The shared strings file is our
**single worst conflict file - 47 of our commits on mobile**. On TV it is 2,884 strings across 30
locales, all upstream-owned. Recommendation: rename the app title and one About line, and leave
`res/values*/` alone.

---

## 10. Test, build and release plumbing

| # | Feature | State | **v1** | AND | iOS | DSK | TV |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **C13** | **Pure-suite harness** - compiles and runs the **shipped** sources with a standalone compiler and JUnit, outside the build system, because the agent sandbox cannot resolve the Android plugin. Five groups; **284 tests** in each KMP repo. This is why so many Nuvio Z files are deliberately import-free. | shipped | ships | yes | yes | yes | `npm test`, 177 |
| **C14** | **CI on every push** - host tests and an unsigned debug build. Upstream had none. | shipped | ships | yes | n/a | yes | yes |
| **C15** | **Dispatchable debug-release workflow** - runs the suite, builds, and publishes a `debug-v*` prerelease with the artifact attached. It replaced a manual release ritual that needed a working local build, so every device-testing loop had to wait for the maintainer. Tags are single-use and the workflow refuses to run if one exists. | shipped | ships | yes | n/a | yes | built, unrun |
| **C16** | **iOS build workflow** - build-only, unsigned, dispatch plus an iOS paths filter because macOS runners bill at 10x. **The only thing that compiles iOS.** Needs no Apple Developer account. | shipped | ships | n/a | yes | n/a | n/a |

**The pure suites are load-bearing, not a convenience.** They are why the playback and source logic
is pure in the first place, and they are why that logic ported to a completely different codebase
in twelve commits.

**Do not put a second copy of a rule in a test stub.** A stub once carried its own hardcoded copy of
the mode-availability rule and would have gone on asserting a withdrawn one.

---

## 11. Removed - do not re-derive as missing work

| | What | Why |
| --- | --- | --- |
| **P7** | Auto source-swap | **Deleted in Phase 2.** It was built, then held behind `AUTO_DOWNSHIFT_AVAILABLE = false` from `0.4.9-beta` onward and never ran on a device. The bar the phase set was "clearly good", not "no worse", and nothing cleared it: a mid-playback source swap is the riskiest possible addition to the subsystem the phase existed to stabilise. The Stage 1 audit also found it defective - `AutoDownshiftDetector.kt:236` and `:269` used `playableDirectUrl` equality as an identity check, so an unresolved current source matched `null == null` and discarded **every** other unresolved candidate, meaning `select` returned null for a whole class of catalogues. Removed: the detector and its 334-line test, `AutoDownshiftCandidates`, `SwapDiagnosticsLog`, the diagnostics HUD's forced-swap buttons, the settings row and its search entry, and the `playback_auto_downshift` key from the storage actuals and the repository read. A profile carrying `playback_auto_downshift = true` from `0.4.9-beta` now reads a key nothing consumes. |
| **P9** | Sticky season pin | Withdrawn. Reachable only from the escape hatch, invisible once set, and it silently suppressed the quality sheet for a whole season with no way to clear it. |
| **P10** | `PlaybackQualityTier` | Removed entirely, with its storage key and all four actuals. Replaced by catalogue-derived options. |
| **P8** | Standalone mode-selector screen | Deleted on the KMPs, replaced by wizard step 2. Still the right shape on TV, which has no wizard. |
| **P2-web** | The remembered quality band, on the web port | Removed 2026-09-04, bringing the web port into line with the decision the KMPs had already taken. A band chosen earlier in the sitting silently answered the sheet, which reads as the app deciding for you with nothing on screen to disagree with. It also forced a second mechanism to exist - `hasRememberedBand`, which suppressed a skeleton grid that would otherwise be drawn and withdrawn on every episode. Both are gone; `streamPreferencesStore.js` was deleted outright with its settings row, its profile sync key and its player wiring. |
| - | The ranking helper on the source selector | Deleted; it had no production callers. The comparator survives only inside its own test. |
| **W-** | Five earlier wizard shapes | See §5. Preset-first, translucent-sheet, preview-follows-touch, Trakt step, hand-drawn miniature. |
| - | Metered cap height and auto-downshift keys on TV | Never ported. A television is never metered and neither platform reports it honestly. |
| - | Downloads on TV | There is no download stack. Anything reading a completed local download is a constant `false`, kept only so shared ordering stays identical. |

---

## 12. Standing verification debt

**This list is the point of the ledger.** Everything above compiles and passes its tests. This
section records what has been *watched*, and what has not.

**Refreshed 2026-09-04** by asking the maintainer directly, per the standing rule in `ROADMAP.md`.
The previous revision of this section listed as never-watched several things that had in fact been
used for weeks. The testing had happened; the write-up had not.

**Three states, because two were not enough.**

| | |
| --- | --- |
| **watched** | somebody deliberately looked at this thing doing its job |
| **in daily use** | the maintainer has run Nuvio Z for weeks and nothing attributable to it has failed. This is real evidence and it is not the same as the check having been run - these systems are invisible when working, so "no complaint" is the only signal they emit |
| **open** | genuinely unchecked, or checked and found wanting |

### Watched

| What | Where it was seen | Caveat |
| --- | --- | --- |
| **Instant** | On the debug line, in normal use; watched on packaged builds (2026-09-06). | Exit gate met in Phase 2. |
| **Debrid playback** | In normal use, including resolver-backed streams; failover and recovery verified (2026-09-06). | Exit gate met in Phase 2. |
| **The startup watchdog** | Under real playback load on desktop and packaged builds (2026-09-06). | Two-tier evidence-of-life model verified in Phase 2. |
| **The setup wizard** | Rendered on a real screen, not only in CI. | - |
| **The settings reorganisation** | Playback, Advanced and Subtitles walked on a real screen. | - |
| **The web port on a television** | It ran on a TV. | Buggy. Phase 8 owns the bugs; the claim being settled here is only that it runs at all. |
| **The connection-gauge correction** | Confirmed on the handset that reported the over-read. | - |

### In daily use, never isolated

These are under-the-hood systems with no visible output when correct. Weeks of use without an
attributable failure is the evidence available, and it is worth recording - but none of them has
had its specific check run.

| What | The check that would settle it |
| --- | --- |
| **The nine review-pass fixes** | The three device checks in the 2026-08-22 review-pass entry, now in `Docs/STATUS-ARCHIVE.md`. |
| **Downloads across a connectivity transition** | Item 4 of the downloads follow-up in `STATUS.md`. The link-expiry half has been run; this half has not. |

**Why these stay listed.** When something does break in this area, the failure is rarely
attributable to one system - source resolution, the watchdog, the debrid mint and the network probe
all participate in the same second of wall-clock time, and a user-visible symptom names none of
them. So the phase that owns each of these opens with investigation rather than with a fix. That is
the reason the checks are still worth running even though nothing is currently complaining.

### Open

| What | Where it stands |
| --- | --- |
| **The home screen sometimes draws only the Continue Watching row.** | **Revised 2026-09-04, and the revision matters.** The self-test recorded "zero catalog rows, 3 of 3" on an addon-heavy profile. Observed behaviour is narrower and stranger: it is **intermittent**, Continue Watching **survives**, and **scrolling down sometimes forces the missing rows to load**. That is not a catalogue-fetch failure - a failed fetch would not be rescued by a scroll. It points at row virtualisation or a lazy-load viewport calculation losing its first pass. **Unattributed: this may be a vanilla Nuvio bug.** Settle that first, per Rule 7 in `Docs/UPSTREAM.md` - if vanilla reproduces it, it goes to `Docs/VANILLA-BUGS.md` and the Phase 1 sync may fix it for nothing. |
| **iOS has never been run.** | The ledger's old claim - "does not compile, seven errors, the linker has never run" - **was true on 2026-08-23 and was fixed on 2026-08-25** by `21fd0d20` and `43155318`. The Kotlin framework and the Xcode app both build in CI today, unsigned. What remains is not compilation: it is signing, an installable build, and somebody holding an iPhone. Phase 7. |


---

## Maintaining this file

- A new Z feature gets a numbered row **in the same commit as the code**, with its platform column
  filled in. Absent is `no` with a reason, never a blank.
- A feature that is withdrawn moves to §11 with the reason. It does not get deleted - the reason is
  the whole value.
- When something is finally watched running, move it out of §12 and say where it was seen.
- Verify completeness against the commit log:

```
git log --author=Claude --author=Codex --author=Zokaper --format='%s' | grep -E '^(feat|refactor)'
```

Every subject should map to a row here.

**Related:** `Docs/UPSTREAM.md` (the doctrine, versioning and sync), `Docs/PATCH-SURFACE.md` (every
upstream-owned file we modify), `nuvioweb/docs/Z-PORT-MATRIX.md` (the TV app's own answer),
`STATUS.md` (the working handoff), `PLAYBACK_MODES_PLAN.md` (the mode plan and its ledger).
