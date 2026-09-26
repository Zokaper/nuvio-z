# Nuvio Z Status

Last updated: 2026-09-26

## Phase 9 — Downloads Redesign: IN PROGRESS (opened 2026-09-24)

**Plan:** `Nuvio Z/PLAN-phase-9-downloads-redesign.md` (the maintainer-approved product model and
the staged build sequence). Branches: `nuvio-z` `claude/phase-9-downloads`, `NuvioZDesktop`
`claude/phase-9-downloads`. Shared commits reach desktop by **cherry-pick** of the mobile commit
(a branch merge drags in mobile history desktop never merged - the Phase 8 convergence applied a
diff), plus desktop-only actuals.

**What the maintainer reported at the opening (the standing question):**
- iOS `.48`: checklist not formally run; "Choose source manually" downloads; downloads functional.
- **Android: screen off -> rows read "Waiting for connection" on return; no notification at all.**
- **Desktop: a friend on the live release, offline on a plane, pressed play on a downloaded
  Modern Family episode -> error dialog -> OK -> the app closed.** Worked later online. That
  "dialog, then exit" is Compose Desktop's default window exception handler: an uncaught
  exception, not a file fault. **Not yet reproduced** - see the offline harness below.

**Stage 0 (done):** desktop `Dev` got Phase 7 and the Phase 8 convergence as two separate
`--no-ff` merges after verifying Phase 7 on its own (`desktopTest` 2,427/2,427 on `9a1489645`).
`Dev` = `ca11c0cb7`, tree identical to the tested convergence (2,512/2,512). Pushed.

**Checkpoint 1 (`c4ba61523`; desktop `7ab4d6edc` + actual):**
- Android: downloads ask for `POST_NOTIFICATIONS` at the first request; the user-initiated
  job's schedule result is checked, with a `dataSync` foreground-worker fallback; a
  `ConnectivityManager` callback re-checks connectivity on every change; foreground and host
  start resume System-paused items (`resumeSystemPausedDownloads` had no production caller).
  One summary notification (the host's own id) + a per-title/season "finished" notification.
  Debug builds log to `Download/NuvioZ-diagnostics/`.
- Offline play, all platforms: `LocalPlaybackPolicy` (offline never reaches for the network),
  missing-file message, offline title page -> Downloads view, offline autoplay through
  downloaded episodes (stops at a gap), damaged-local-file message, offline Home banner.
- Verification: pure 872/872, Android host 2,392/2,392, desktop 2,533/2,533. **No device yet.**
- Published for QA: mobile `debug-v0.4.13-z1.49` (run `36045406001`), desktop debug 62
  (run `36047294182`).

**Stage 5 policy core (`8207ceba9`):** `DownloadPolicy` / `DownloadSizeLevels` /
`DownloadEntryRouter` (pure), `DownloadSourceSelector`, `DownloadPolicyRepository` with its own
`download_policy` sync payload and preset migration. Not wired into any flow yet. Size-level
numbers are **provisional** until calibrated and reviewed. Pure 883/883, host 2,425/2,425.
Also fixed: `SocialFeaturePreferencesStorage` was never initialized on Android.

**Offline reproduction harness (desktop):** the shipped runtime is Java 17, so the resolver SPI
is unavailable; `-Djdk.net.hosts.file=<file listing only localhost>` makes one JVM offline.
`offline-repro.ps1` runs an installed build against a *copy* of its data (`APPDATA` redirected),
with `-Dnuvio.debugTools=true` so the release build writes its debug log. A synthetic Completed
Modern Family S01E01 (libmpv-encoded) can be injected. Startup offline was verified clean; the
play-path clicks need the maintainer. Tools are in the session scratchpad; they move into
`NuvioZDesktop/scripts/` with the next desktop commit.

**Stage 4, first slice - device settings (`bf0aa7697`; desktop cherry-pick):** `DownloadDeviceSettings`
(device-local, stored with the download state, never synced): mobile data Wi-Fi only (default) /
Ask / Always; downloads at once 1-4 (default 2, Android + desktop; iOS keeps window 12). Items the
network may not carry read **"Waiting for Wi-Fi"** with a **Download now anyway** row action.
The platform request carries the effective permission, so iOS `allowsCellularAccess` and the
Android job constraint agree with the queue (before this, rule Always would still have been
refused by both). Wi-Fi return: Android's network callback; elsewhere a recheck loop runs only
while something waits for Wi-Fi. iOS background behaviour is unchanged.
Known gaps, by plan: **Ask** behaves like Wi-Fi only until the Ask prompt (stage 6); no settings
UI yet (stage 8); on desktop the setting is profile-scoped until the device-wide store (rest of
stage 4). This session resumed an interrupted one: the partial diff was intact; added on top were
the platform-request fix, the iOS Live Activity mapping, the recheck loop, the row action and the
desktop E2E cases. Pure 883/883, Android host 2,430/2,430, common + Android compile pass;
desktop `desktopTest` 2,573/2,573 (with policy core cherry-picked + a desktop `DownloadPolicyStorage` actual).

**Physical `.49` (maintainer, 2026-09-24, Android on mobile data / hotspot, not Wi-Fi):** screen off,
back later -> the row read "Waiting to retry... retrying in 5, 4, 3...". **Android background
downloading is NOT physically passed.** This is an unresolved observation from a metered
environment, not a clean Wi-Fi baseline. Code has changed since (`.49` scheduled the background
job with an UNMETERED constraint for every item, since nothing could allow mobile data - so on
mobile data the host job could never run; that is a *hypothesis* for this run, not a confirmed
cause). Re-test the final stage 4 build, ideally on Wi-Fi. Desktop debug 62: no result yet.

**Stage 4 - engine simplification (`514b2faae`; desktop `bf282a4c2` + `f12dbcee5`):**
`DownloadsRepository` (2,766 lines) keeps the public API; the engine moved out, code verbatim
where it could be:
- `DownloadStore` - persistence, **one device-wide store**, `ownerProfileId` on items and batches,
  per-profile views (`uiState`, `batches`) that follow the active profile reactively. A profile
  switch changes the view only (it used to reload the queue). The Android notification, the iOS
  Live Activity, the Android host's idle wait and "Pause all" use every profile's items
  (`deviceItems`). Migration: owner-less payloads -> primary profile (1); desktop's
  `downloads_<profile>` payloads merge into `downloads_device`, tagged, and are **left on disk**.
- `DownloadScheduler` - slots, connectivity + Wi-Fi gates, retry/watchdog timers, reclaim sweep,
  transfer callbacks and the generation fence.
- `SourceRealizer` - re-minting, size verification, freshness. `failureOutcome` is pure and
  tested (a known-uncached source fails at once - the NothingCached rule).
- `SystemOwnedTransfers` - the iOS background-session model (inventory, claims, snapshot,
  ordered submission, resolve-ahead) **moved unchanged**, reached only via
  `TransferHost.SystemOwned(window = 12)`.
- `TransferHost` replaces seven platform members (five were no-ops off iOS). Android
  `InProcess(recoversSystemPauses = true)`, desktop `InProcess(false)`, iOS `SystemOwned`.
- Queue moves: To top / To bottom queue-wide; Up / Down swap with the **visible** neighbour.
- Android host: network requirement is queue-wide (`DownloadHostPlanner`), not the first
  transfer's - addresses the `.49` hypothesis, **unverified on a device**.
- Diagnostics (all platforms): failure / retry / connection-wait lines now carry
  `net=<type> metered=<bool>` and on Android `foreground=` / `hosting=`; new events
  `engine_start`, `store_loaded`, `store_migrated`, `store_corrupt`, `profile_view`, `wifi_wait`,
  `inventory_plan`, `load_placeholder_requeued`, host queue summaries. Nothing was removed.
- JVM loops: the 416 and 206/200 decisions are shared pure functions. **No `jvmCommon` source
  set:** the loops differ in HTTP stack (OkHttp vs `java.net.http`) and stall mechanics, and a
  merge would rewrite the Android path whose screen-off behaviour is still unresolved.
- **Not done in stage 4, deliberately:** `DownloadPresentation` (the shared user-facing state
  mapping). It lands with the stage 7 screen/notification redesign that consumes it.
- Known gap: deleting a profile leaves its downloads in the device store (not shown anywhere).

Verification: pure 883/883; Android host 2,452/2,452 (`--rerun-tasks`, +22 tests); common +
Android compile pass; CI + iOS quick check green on `514b2faae`; desktop `desktopTest`
2,597/2,597 (`--rerun-tasks`, JBR SDK; +2 E2E: per-profile views over one engine, per-profile
payload migration). Debug build **50** ([`debug-v0.4.13-z1.50`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.50), prerelease, Debug release run `36065558816`, IPA + APK, commit `94fa7fdbf`) is published for the physical re-test:
iPhone locked-queue regression (must match `.46`), Android screen-off **on Wi-Fi**, desktop.
None of those are verified until the maintainer reports them.

**Stage 6 - flows UI (`7a99073aa` + render fix `c6b501ecb`; desktop cherry-picked, see
`NuvioZDesktop/STATUS.md`):** `DownloadFlowController` is the only way into a download - title
button, episode rows, seasons, long-press sheets, "Change" (toast and queue-row menu), "Choose
manually" on attention cards. By Download Mode:
- **Automatic**: starts at once; a whole show asks for seasons first (All / Unwatched / None, "Only
  unwatched episodes"; opens on Unwatched for a started show, All otherwise). Toast "Downloading ·
  1080p · 2.1 GB" with **Change** -> the Assisted sheet for that item.
- **Assisted**: one row per resolution (best match each, preferred pre-selected; season rows show
  "about 24 GB for 22 episodes" and "not for N episodes" -> those become Use-nearest entries while
  the rest proceed). "Choose manually" at the bottom for a single item.
- **Manual**: single -> the download source list (tap enqueues with **Undo**, never plays, goes
  back); several -> new **Choose sources** screen (Pick per episode, "Let Nuvio pick the rest" =
  the Assisted sheet over what is left).
- `DownloadBatchCoordinator` replaces `PresetDownloadCoordinator`. Entries carry their reason
  (`OVER_LIMIT`, `RESOLUTION_MISSING`, `NOTHING_CACHED`, `NO_SOURCES`, `MANUAL_PICK`).
  **NothingCached rule held:** discovery now runs the local debrid cache check, and the selector
  reads the service's answer before the addon's text (before this, an unmarked but cached torrent
  would have been NothingCached for lack of a marker). NothingCached/NoSources offer **Check again**
  and never the manual list; known-uncached rows in the download source list are disabled and
  labelled "Not cached on your debrid service".
- Free-space warning before a batch starts (Download what fits / Cancel); mobile-data rule **Ask**
  now asks once per app session; delete confirms (title-page season delete, manage sheet, queue rows).
- Retired: `PresetDownloadDialog`, `PlaybackModeDownloadRouter` (+ tests), the stream list's preset
  sheet, the dead `onDownloadManually` plumbing. The Playback Mode card's download line now names the
  derived Download Mode. The review card only stops offering impossible actions; its redesign is stage 7.
- Presets still exist in Settings -> Downloads until stage 8 replaces that screen.
- **Size levels are still provisional** (calibration + maintainer review owed).

Verification: pure **900/900**; Android host **2,468/2,468** (results deleted, `--rerun`: +33 new,
-17 retired router/copy tests); `:androidApp:compileFullDebugKotlin` passes; desktop `desktopTest`
2,620/2,620 and pure 890/890. Render review: 11 surfaces x 4 widths, PNGs read; three defects fixed.
**Nothing physical** - no debug build cut for stage 6 alone; the next one carries stages 6+7.

**Stage 7 - Downloads screen + attention (`301137be4`, render fix `b6e23a9a6`; desktop cherry-picked):**
- **`DownloadPresenter`** (`DownloadPresentation.kt`) is the one vocabulary: Finding a source / Queued /
  Downloading / Waiting (connection, Wi-Fi, retrying shortly, starting, resuming) / Paused / Needs you /
  Downloaded. Plain line only; attempts, provider, last error, retry countdown and engine state are the
  **detail on tap**. The screen, the **Android summary notification** (waiting reason + "N need you") and the
  **iOS Live Activity** state all read it (`plainText` / `plainTextOf` share one string mapping). The widget's
  state names are unchanged; a system pause still shows as paused there. The `.49` "Waiting to retry...
  retrying in 5, 4, 3" line is now "Retrying shortly" with the countdown in the detail - a wording change,
  **not** a fix for whatever made Android retry (still unresolved physically).
- **Needs you = exactly four kinds.** `DownloadItem.failureKind` (STORAGE / NOT_CACHED) is set where a download
  fails, so nothing parses localized error text. `AttentionGrouping`: one card per title/season and reason;
  actions Allow (with size) / Use nearest / Check again / Retry / Free up space / Choose sources + Let Nuvio pick /
  Remove, per-episode "Choose" only where a pick can help (never for nothing-cached, no-sources or storage).
- Screen: storage bar (all profiles + free space), Needs you, "Free up X of watched episodes" (suggested,
  confirmed, never automatic), queue with a **season as one expandable row** (combined progress, "Season 2 · 4
  left · 2 done", pause/resume all, move up/down past the neighbour row, cancel remaining keeps completed), detail
  sheet (Pause/Resume, Change, Download next, Delete), On this device. Remove/cancel/delete all confirm.
- **Deliberately not done:** `DownloadBatchEntryState` was not shrunk to Deciding/NeedsDecision/Enqueued (it is
  persisted; the presenter maps the old states instead). The title page's download controls still use
  `DownloadPresence`, not the presenter.
- **Found by CI:** stage 6 used JVM-only `toSortedMap`, so the iOS link failed from `7a99073aa` until
  the fix `709531113`. Android/desktop never saw it.

Verification: pure **900/900**; Android host **2,491/2,491** (results deleted, `--rerun`; +17 presentation
tests, +6 summary/flow); `:androidApp:compileFullDebugKotlin` passes; desktop `desktopTest` **2,638/2,638**
(+ `DownloadsScreenRenderHarness`). iOS build (dispatched: it only runs on pushes touching iOS paths, which is how the stage 6 break got through) **passed** on `709531113`, run `36081660584`.
Render review: stage 6 (11 surfaces) + stage 7 (screen + detail) x 4 widths, PNGs read, five defects fixed.

**Stage 7 render review (maintainer, 2026-09-25):** the functionality and information model are **approved**; the
visual design was **not** ("a debug/admin dashboard, not a media app": identical rounded rectangles, 1,900px desktop
rows with actions at the far end, Needs you painted pink, a busy season mega-card, "Unwatched" twice in the season
chooser, six floating "Pick" labels, no artwork in the fixtures). Decisions carried with it: **no physical-QA build
and no stage 8 until the revised renders are approved**; the size levels stay provisional; the next physical-QA build
must carry lightweight debug catalogue/source-size logging (no URLs, no headers) so the levels can be calibrated.

**Stage 7 composition pass (`de8038613`; desktop `8fc71cb03` + harness `cb51188ce`):** presentation only - no state,
engine or presenter change. Made on desktop (where the render harness lives) and cherry-picked here; `strings.xml`
auto-merged.
- Artwork everywhere (`DownloadsArtwork.kt`: poster / still, the title's initial when there is none). The Downloads
  screen caps at 880dp and Choose sources at 720dp, centred (`Modifier.downloadsContentWidth`), header included.
- **Needs you** = one panel: poster, title, the problem in one line with the colour on its icon only, up to 3
  episodes (+N more; "Choose" kept within 440dp of its episode), one filled primary pill + tonal alternatives.
  **Remove is a corner close control** (still confirms). "Season 3 · 2 episodes"; a single episode drops the count.
- Queue rows are flat. A season shows **its own totals** ("1.7 GB of 3.7 GB · 45%", previously the lead episode's
  bytes against the season's bar), chevron by the title, and a dense inset episode list (E3 · title · state).
  **"Download now anyway" lives inside the Wi-Fi-waiting episode's text column.** Row controls tinted (they
  rendered dim outside a Surface).
- Watched cleanup moved under the storage bar as a quiet outlined line. "On this device" rows flat, "3 episodes"
  instead of "3 downloaded episode(s)".
- **Season chooser - one selection model:** Unwatched / All episodes (shown only once the show is started) + a
  checklist with per-season counts; a fully watched season cannot be ticked under Unwatched; Select all / Clear.
  The All / Unwatched / None presets and the "Only unwatched episodes" switch are gone. Rules:
  `DownloadFlowRules.isSelectable / selectionForMode / selectAll / offersUnwatchedMode` (+3 tests).
- **Choose sources:** poster header with progress, one list with three visibly different states (tick / spinner /
  open ring + chevron); the whole row is the pick target. **Resolution sheet:** poster heading, radio + 2dp accent
  border, size on the right with "about X each" for seasons, cautions behind a warning icon.
- Render harnesses: generated poster/still art per title (`DownloadRenderFixtures.kt`, via Coil's preview handler,
  offline) and plausible titles/episodes/sizes; the screen harness now renders the production
  `downloadsRootContent` inside `NuvioScreen`. The review caught and fixed: Choose sources' primary button eating
  the title column on desktop (title rendered one letter per line), a heading line-cap truncating dialog bodies,
  pills wrapping one per line on phones.
- **Revised render set:** `Nuvio Z/render-review/phase-9-stage-7-composition/` (`screen/` 8, `flows/` 44 PNGs;
  360 / 420 / 1280 / 1920 wide). Source: `NuvioZDesktop/composeApp/build/{downloads-screen-render,download-flow-render}/`.

Verification: pure **903/903**; Android host **2,494/2,494** (results deleted, `--rerun-tasks`) +
`:androidApp:compileFullDebugKotlin`; desktop `desktopTest` **2,641 run, 2,640 pass** - the one failure is
`NetworkQualityPlatformDesktopTest.currentReturnsPromptlyWithoutBlockingCaller` (a 200ms wall-clock assertion on a
PowerShell probe), which failed twice while other Gradle builds loaded the machine and then passed 2/2 on this tree
and 2/2 at the pre-pass commit `890518221`: load-sensitive, not this change. iOS build (dispatched) **passed** on
`d1ac7d472`, run `36145095349`.

**Stage 7 render review (maintainer, 2026-09-25): revised set APPROVED in direction.** Do not redesign stage 7
again. Five small polish items were asked for and done (`822563046`; desktop `41f0d6e0b` + fixture `3a1da754d`):
- Choose sources: "Let Nuvio pick the rest" -> a **tonal "Auto-pick remaining"** (the Needs you action uses the
  same words); the episode list stays the focus.
- Chosen rows read **"1080p · 2.3 GB · WEB-DL"** (resolution · size · `releaseQuality`), not the stream's file name.
- Season chooser: **"187 episodes selected"** over **"9 seasons"**. The count already followed Unwatched / All
  episodes - the old one-line "N episodes · N seasons" just did not say so, and wrapped mid-phrase on phones.
- Resolution sheet: **"4K unavailable for 2 episodes" / "720p unavailable for 1 episode"** (plurals), one caution
  per full-width line under the row. Before, the caution shared a column with the size and wrapped letter by
  letter at 360dp.
- Needs you: several problems on one title/season render under **one poster and name**, each with its own
  problem line, Remove, episodes and actions. Presentation only (`groupBy(parentMetaId, season)` in the panel);
  `AttentionGrouping` and the cards are unchanged.
Re-rendered at four widths and read; no further full render review is owed unless the UI changes materially.

**Size telemetry (`575f247eb`; desktop cherry-pick):** `DownloadSizeTelemetry`, **debug builds only**. Every
discovery writes `event=size_sample kind=episode|movie runtime=N total=N part=i/n sources=...`, 20 sources per line,
80 max; each source is `height:MB:GBh:cache:quality:codec:hdr:dur` (`GBh` from the source's own duration `s`, else
the title runtime `t`; `cache` C/H/N is the selector's evidence). Every decided entry - Automatic, Assisted
resolution pick, Use nearest - writes `event=size_pick ... decision=... source=<token>`. **No URL, header, token,
file name, stream title, provider, addon or title**; quality/codec are reduced to 12-char tags and anything URL- or
header-shaped becomes `other` (tested). Lines land where `DownloadDiagnostics` already goes: Android
`Download/NuvioZ-diagnostics/`, the iOS debug probe log, the desktop debug log. **The size levels stay
provisional**; proposed calibrated values go to the maintainer once real samples exist.

Verification: pure **903/903**; Android host **2,498/2,498** (results deleted, `--rerun-tasks`; +4 telemetry) +
`:androidApp:compileFullDebugKotlin`; desktop `desktopTest` **2,645/2,645** (results deleted,
`--rerun-tasks`; the load-sensitive `NetworkQualityPlatformDesktopTest` passed this time).

**Published for physical QA (2026-09-25):** mobile **debug 51**
([`debug-v0.4.13-z1.51`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.51), run `36166846582`,
IPA + APK, commit `1d8c9ee32` - the IPA build is also the iOS compile check for the polish) and desktop **debug 63**
(`debug-v0.1.23-alpha-z6.63`, run `36167041339`). What to test on it: stage 6 flows (Automatic / Assisted / Manual,
season chooser, Choose sources, free-space warning, mobile-data Ask, delete confirms, NothingCached); stage 7 screen
and Needs you; **iPhone locked-screen queue regression** (must match `.46`); **Android screen-off/background on
Wi-Fi**; Android mobile-data vs Wi-Fi rules (the rule is still only settable from stage 8, which is *not* in 51 -
Wi-Fi only is the default); size samples (`size_sample` / `size_pick` in the diagnostics logs). **Nothing is
verified until the maintainer reports it.**

**Stage 8 - wizard + settings (`1ebb6da97`, fix `d92655767`; desktop `aaf98b2b3` + `8997cffcd` + actual/harness
`e9d3d0f90`). Not in debug 51.**
- **Wizard revision 10.** Two new steps after Playback setup: **Download Mode** (Automatic "Recommended" /
  Assisted / Manual cards; the mode storyboard reuses playback's three processes - Manual = Classic, Assisted =
  Streamlined, Automatic = Instant - ending on a download icon) and **Download setup**, per mode (plan stage 2):
  Automatic = resolution, file size (with "About 2 GB per hour of video at 1080p"), fallback; Assisted = file size;
  Manual = nothing; phones add mobile data. Manual on desktop drops the step.
- **Three runs** (`setupWizardRun`, pure, tested): **Full** (fresh, or completed < 8); **Upgrade** (8 or 9: only
  the two download steps, no Welcome, subtitle "New: downloads have their own mode now", closable - the close
  control is "Not now"); **Device** (profile current, this *phone* never set up: the mobile-data question alone).
  Skipping any run records its revision; skipping an upgrade leaves `DownloadPolicy.mode` null (derived). Walking
  past the mode step commits the preselected (derived) mode, as the social step does. Sources is still never
  replayed for revision 8.
- **Device revision:** new device-local `DeviceSetupStorage` (Android SharedPreferences, iOS NSUserDefaults,
  desktop `DesktopStorage`). Its own store because the gate reads it before anything starts and loading the
  download store starts the engine. The gate re-reads it in the wizard's `onFinished`.
- Android asks for `POST_NOTIFICATIONS` once, when leaving the download-setup step (the existing request-once path).
- **Settings -> Downloads rebuilt:** Download Mode cards ("Following Playback Mode until you choose one" while
  unanswered), Preferences (resolution, file size + GB/hour, fallback, pick rule, HDR), On this device (mobile data
  on phones; downloads at once 1-4 and the folder off iOS), Advanced (addon filter, unchanged). **Preset editor
  retired** (the repository's preset API stays for migration). Settings search indexes the new rows. All labels live
  once in `DownloadModeUi.kt`.
- Renders (desktop harnesses): `setup-wizard-render/` gains the download steps at three desktop sizes, a phone pass
  (full x3 modes, upgrade, device at 360 and 420) and the download storyboard frames; `downloads-screen-render/`
  gains `settings-*` at four widths. Read; one defect fixed (the empty-addons line rendered near-black on black -
  older code, newly in view). Known: Automatic's four controls scroll inside the panel on a 360x780 phone, as
  Playback setup already does. The storyboard PNGs show only the title frame - a harness limit (a single render at a
  virtual time does not advance the `delay` loop), the same for the existing playback storyboards; the frame data is
  pure-tested.
- **Deliberately not done:** the separate "Set up this device" notification step - the request rides on the
  download-setup step instead; ROADMAP §D wording ("Automatic never asks") still to be updated at the release gate.

Verification: pure **918/918** (setup group 72 -> 87); Android host **2,507/2,507** (results deleted,
`--rerun-tasks`) + `:androidApp:compileFullDebugKotlin`.

iOS build (dispatched) **passed** on stage 8, run `36174107743`.

**Stage 9 - What's New (`3889e237e`; desktop `5fc9479dc` + actual/release step/test).**
- **One changelog for both repos:** `composeApp/src/commonMain/composeResources/files/changelog.json`. Releases by
  family and `RELEASE_SERIAL`; entries feature/improvement/fix tagged android/ios/desktop; `debug` lines per debug
  build. **Deviation from plan 4.10, implementation only:** it ships as a Compose resource parsed at runtime
  (`ChangelogCatalog`), not a Gradle-generated Kotlin catalog - equally offline, shared by cherry-pick, and no
  generator to port into desktop's divergent `build.gradle.kts`.
- **Seeded:** mobile **127 / `0.4.13-z1`** - 127 is the *unreleased* serial (the last stable is `0.5.0-beta+126`),
  so it carries the Phase 8 notes that were hand-written in `CurrentReleaseNotes` (moved verbatim) plus draft Phase 9
  entries; desktop **132 / `0.1.23-alpha-z7`** (desktop is on 131) with the desktop subset. Both dated
  `unreleased`. **The copy is a draft for the maintainer.** Entries that claim physical behaviour (Android
  notification, offline) must match the QA results before release. Version strings for 127/132 are placeholders the
  release sets. No history before these (older releases still come from the releases feed in Settings).
- **Selection** (`WhatsNewSelection.kt`, import-free, pure-suite tested): fresh install (no ack, no old key) shows
  nothing and acks; upgrade from the old `last_seen_version` shows only the current release (nothing if the old key
  already names it); otherwise every missed release of the family merged by category, newest first, with version
  tags when more than one release is merged; debug builds add unseen debug lines ("THIS DEBUG BUILD"). Device-local
  ack `(serial, debugBuild)` in new keys; Continue acknowledges; a downgrade never lowers it.
- **Identity per platform** (`WhatsNewStorage.releaseIdentity`): mobile = `RELEASE_SERIAL` / `VERSION_NAME` /
  `DEBUG_BUILD` when `isDebugBuild`; **desktop** = `DESKTOP_VERSION_NAME` (debug number from its fourth component)
  and desktop's own serial - the desktop fix. ⚠ iOS debug lines depend on `Platform.isDebugBinary`; if the debug IPA
  is a release binary they simply don't show.
- Post-update screen = missed notes only; **Settings -> What's new** = this release + shipped history + older feed
  releases not in the changelog.
- **Release guard:** `scripts/check-changelog.py --family --serial check|notes`, wired into `android-release.yml`
  and desktop's `desktop-release.yml` (no notes for the serial fails; curated notes lead the body under "What's
  new"). `ChangelogFileTest` (mobile host) runs the same guard on every push; desktop's test checks shape and
  identity only (desktop's current serial predates the changelog). `AGENTS.md` updated.
- Retired: `CurrentReleaseNotes`, `shouldShowWhatsNew` (+ its test).

Verification: pure **931/931** (+13); Android host **2,519/2,519** (results deleted, `--rerun-tasks`) +
`:androidApp:compileFullDebugKotlin`.

**Published (2026-09-25):** mobile **debug 52** ([`debug-v0.4.13-z1.52`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.52),
run `36182730459`, IPA + APK - the IPA build is the iOS compile check for stage 9) and desktop **debug 64**
(`debug-v0.1.23-alpha-z6.64`, run `36182733969`). Both carry stages 6-9.

### Physical `.52` findings (Android, 2026-09-25) - diagnosed from ADB, fixed, debug 53 / desktop 65

Lanterns S1, Assisted, 6 episodes over StremThru -> TorBox (`sourceOrigin: null`: the resolver mints
the TorBox link *inside* our GET, then 302s to `store-0xx.wnam.tb-cdn.io`).

**Root cause of the eps 3/4/6 "Starting" -> "Retrying shortly" loop: a dead pooled HTTP/2 connection.**
From ~23:35 every request in the app hit the 60 s watchdog with no response headers (`bytes=0`, no
`transfer_open`), retries included, for 20 minutes. Evidence: `curl` on the *same phone and cellular
network* fetched all six links in 1.6-3.8 s at the same time; a PC OkHttp 4.12 repro showed the
StremThru hop is h2 (one shared connection for every episode) and the CDN hop HTTP/1.1; and after
`am force-stop`, Retry opened eps 3 and 4 in ~2 s. OkHttp never learns about a dead connection from a
*cancelled* call (our watchdog cancels), and there was no `pingInterval`. Not explained: ep 4's very
first attempt (23:31:55) also hung while ep 5 at 23:32:55 went through - slow first TorBox link
generation or the same fault; the new `http_*` diagnostics will say next time.
- Ep 3's first failure was a genuine mid-body stall; attempts 2-5 were unanswered requests, after which
  the restart-from-zero rule **discarded its 2.47 GB partial**. Eps 4/6 failed with "stopped part-way
  through" though they never received a byte.
- FIFO held: every slot went to the lowest-ranked eligible item. Ep 5 starting while ep 4 was in a 2 s
  backoff is the planner's intended work-conserving behaviour (backoff items are skipped); cost: that
  2 s became ~3 min. Left as is.
- No lifecycle involvement (`foreground=true hosting=true` throughout, no process death, no host
  loss); no sign of the `.49` screen-off/mobile-data problem (no connection/Wi-Fi waits; Ask worked).
- **Pause all** (notification, 23:26:58) paused one item at a time; each pause freed a slot the queue
  refilled - three real requests fired and cancelled (eps 4, 5, 6 `slot` burst).

**Fixes (`87db7ee3e`, `74b205152`; desktop `565e83140`, `4e1bb07ca` + desktop actual):**
- Android: a `ConnectionPool` per transfer attempt, evicted at its end, + h2 pings (15 s). Desktop: an
  `HttpClient` per attempt (`shutdownNow` at the end); its request timeout now reads
  `DownloadsTiming.stallTimeoutMs` so the harness can drive it.
- `DownloadFailureReason.NoResponse` (a stall/timeout before any response): Transient budget, never
  restart-from-zero, message "This source isn't answering". Desktop E2E `requests nobody answers keep
  the partial file and a retry resumes it` (`FaultyMediaServer.Behavior.NeverAnswer`).
- `DownloadDiag` `http_request` (hop, conn new/reused, protocol), `http_response`, `http_failed`.
- `DownloadsRepository.pauseDownloads` pauses a set atomically; `pauseDownload` and Pause all use it.
- **Season/batch progress** (`DownloadAggregateProgress`): the selection is the denominator - unfinished
  episodes (Needs-you included), finished ones from the same run, batch entries still preparing. Bytes
  when every size is known (advertised size until the transfer opens), otherwise episode-weighted;
  indeterminate when nothing is measurable. One model for the season row ("5.2 GB of 12 GB · 43%" /
  "2 of 6 episodes · 38%"), the Android notification ("2 of 6 done · 38%", bar = whole queue) and the
  iOS Live Activity (same queue aggregate; iOS compile is checked only by the debug IPA build).

Verification: Android host downloads package **370/370** (results deleted, `--rerun`) incl. 11 new
aggregate tests; `:androidApp:compileFullDebugKotlin` green; desktop `features.downloads.*` **415/415** (414 on the first
run; the new E2E then asserted exactly one-third of the file on disk, but the drop fault's RST discards
unread bytes - it now asserts recorded bytes == partial file and that the retry resumes from there).

**Assisted discovery UX - DECIDED 2026-09-25, refined 2026-09-26, BUILT (see below).** The maintainer's
refinement supersedes two details recorded here the day before: the "ready" state is **not** a Needs-you card, and
the system notification is sent **only when Nuvio is in the background** (in-app prompt otherwise).

**Published (2026-09-26):** mobile **debug 53** (`debug-v0.4.13-z1.53`, run `36194043123`, APK + IPA - the IPA
build is the iOS compile check for the Live Activity change) and desktop **debug 65** (`debug-v0.1.23-alpha-z6.65`, run
`36195874627`; the first dispatch `36194046412` failed: CI compiles desktop against JDK 17, which has no
`HttpClient.shutdownNow`, now called reflectively - `60a3ea1d3`).

### Phase 9 - `.53` results, long-pause contract, Assisted "choose when ready" (2026-09-26)

**Physical Android `.53` (maintainer):** Lanterns S1 completed; Pause all no longer bursts; Resume works; season
progress and the notification match the screen; the `.52` dead-connection loop did not recur. **Passed.**

**`.53` throughput, episode 1 vs 2 (ADB log `downloads-20260926-013530-18397`, concurrency 2, metered):** both took
the same path (StremThru h2 hop, 302 to a TorBox CDN over HTTP/1.1, **new connections each**), with the same latencies
(302 in ~2.0-2.4 s, 200 at 2.8/3.4 s). First pass until Pause all: E1 **11.0 MB/s** (2.51 GB file), E2 **29.8 MB/s**
(2.20 GB). After Resume - new connections, range 206s from the partials - E1 **21.0**, E2 **21.8 MB/s**; the pair's
combined rate stayed ~41-43 MB/s throughout. E3-E6 ran at 12.6-25.2 MB/s with the same per-pass variance. Same code,
same host path, parity once the connections were re-made: **per-connection (CDN node / TCP share) variance, not
app-side. No scheduler/network change.** No stalls, timeouts or reconnects. The two `SocketException` lines are the
Pause all cancellations. Backing hosts are not logged (no URLs, by design). Added for next time: debug
`transfer_progress` every 15 s per transfer (bytes, total, window KB/s).

**Long-pause contract - verified, one gap fixed (`f623e6796`; desktop `1491a44b3`, E2E `716b9db19`).** Already true:
Resume keeps the partial (reads it from disk), clears the resolve stamp, and every start of a download with an origin
re-mints before the transfer, which then sends `Range` from the partial's length (`If-Range` with the old validator;
`.53` re-mints answered 206 four times out of four). **Gap:** the stall rule's restart-from-zero also fired on
`SourceExpired` - after the re-mint budget, and **at once** for a download with no origin (a 403 is not retryable
there) - deleting the partial to replay the same dead URL. `canRestartFromZero` (pure, tested) now excludes
`SourceExpired` as it already excluded `NoResponse`/`Fatal`: an expired link fails the download with the partial
kept for a later Retry. Desktop E2E: `a long pause whose link expired resumes on a fresh link from the partial file`
(dead link never replayed, fresh link's first range = the paused length, no zero-start) and `an expired link that
cannot be re-minted keeps the partial file` (**fails on the old rule** - mutation-checked). Residual, not changed: if a
fresh link's host answers `If-Range` with a different validator for the same bytes, the server's 200 still restarts
the file - that is the corruption guard, and `.53` gave no sign of it.

**Assisted "choose when ready" (`cecfdce2f` + render fix; desktop cherry-picks + desktop actual/tests):**
- Assisted with **more than one** episode (not Change, not Manual's "pick the rest") saves a batch at once
  (`awaitsQualityChoice`, entries `DISCOVERING`) and `AssistedDiscovery` finds sources outside the flow session. The
  finding sheet says "You can leave this…" with **Continue in background**; closing it never cancels. One film or
  episode keeps the modal sheet (seconds).
- Downloads: **"Lanterns S1 · Finding sources · 7 of 22"** with progress, then **"Ready to choose quality · 22
  episodes"** + **Choose quality** + Remove (confirms, cancels discovery). Tapping a still-finding row opens the
  finding sheet, which moves to the choice by itself.
- New entry state `AWAITING_CHOICE` / phase `READY_TO_CHOOSE` - **not** Needs you (tested: no attention card). Only a
  discovery that found nothing at all converts entries to NO_SOURCES / NOTHING_CACHED Needs-you cards.
- When done (`AssistedChoiceRules.announcement`): sheet still open -> straight to the quality sheet; Nuvio on screen ->
  in-app toast "Lanterns S1 is ready · Choose download quality" with **Choose**; backgrounded -> system notification
  (Android channel "Ready to choose"; iOS local notification), deep link `nuvio://downloads?choose=<batch>` ->
  Downloads + the quality sheet. Never both. Desktop is always "on screen". Choosing clears the notification.
- The choice writes into the same batch (flag cleared, candidates dropped) and queues through the existing free-space
  check. Totals are exact - the sheet only opens once every source is found.
- **Process death:** the batch survives the store load (no longer turned into "Preparation was interrupted");
  candidates were **memory only** (no provider URLs on disk); `DownloadFlowHost` re-runs discovery for such batches
  ("Refreshing sources…") and does not prompt a second time if it already did.
- Keep-alive: Android schedules its download host while discovery runs (`awaitDownloadQueueIdle` waits for it too; the
  summary notification already shows "Finding sources"); iOS takes background time (a few minutes; what is left
  finishes when the app is back). **Neither is physically verified.**
- Tests: `AssistedChoiceTest` (8, pure + deep link) and desktop `AssistedChoiceFlowTest` (8, real controller + store:
  dismiss keeps discovery, in-app vs system, sheet-open straight to choice with real totals, choice into the same batch,
  process-death refresh without a second prompt, choose-while-finding, nothing-found -> Needs you, remove stops it).
  Render review: Downloads screen + finding sheet (background / refreshing) x 4 widths, read; one defect fixed (the
  pill beside the text cut the phone title to "Lantern…").

Verification: pure **932/932**; Android host **2,546/2,546** (results deleted, `--rerun-tasks`) +
`:androidApp:compileFullDebugKotlin`. Desktop: see `NuvioZDesktop/STATUS.md`.

**Published (2026-09-26):** mobile **debug 54** (`debug-v0.4.13-z1.54`, run `36203714804`, APK + IPA - the IPA
build is the iOS compile check for the new notification / background-task code) and desktop **debug 66**
(`debug-v0.1.23-alpha-z6.66`, run `36203717889`, MSI + DMG).

### Phase 9 - `.54` physical results, the iOS profile-loading fix, paused notification, Assisted "Choose now" (2026-09-26)

**Physical `.54` (maintainer):** Android essentially **passed** - Assisted background discovery, the background "ready"
notification (present; DND kept it from interrupting), downloads complete, Pause all / Resume, whole-season and notification
progress, deleting active downloads and discovery batches (no zombie work or notification), Lanterns season complete, a
~1-minute pause resumed fine. Two findings: the Android notification vanished while the queue was paused, and **iOS stuck on
the loading screen after choosing a profile** (new, blocks iOS QA). Desktop `.66`: no result yet.

**iOS profile-loading hang - root cause from the code, fixed (`f2bfbf475`; desktop `19190b5c8`).** No device log was
available on this machine (no libimobiledevice), so this is a code diagnosis, **not yet confirmed on the phone**. On iOS the
gate hosts no `MainAppContent` (`renderMainContent = false`, the native tabs render it) and shows its launch overlay (profile
backdrop + spinner, `AppLaunchOverlay`) until the native main content reports ready. Since Phase 8's iOS bring-up
(`7e293c7de`), main content is deliberately **not mounted while the setup wizard gates the app** - so it can never report
ready, and the overlay sat on top of the wizard forever. Latent until stage 8: revision 10 made the Upgrade run owed by every
existing profile, and the new per-device run is owed by every phone that never answered it - so every existing iPhone hit it.
Android renders main content inline and never shows that overlay. Fix: `appLaunchOverlayVisible` (pure, pure-suite group 3,
3 tests) is false while the wizard gates. Expected on the phone: the wizard's download steps (or the mobile-data question)
appear after choosing a profile, then Home.

**Paused queue keeps its Android notification (`65b94b458`; desktop `832bca85d`).** Paused work is unfinished work in
`DownloadsSummaryPolicy` (`pausedCount`, `isPausedOnly`); when it is all that is left the summary reads **"Downloads paused ·
N remaining · 38%"** with a static bar and **Resume** (every paused item, user or system, device-wide). It posts under its own
id (`0x4e5a47`): the host runs under the summary's id with `JOB_END_NOTIFICATION_POLICY_REMOVE` (WorkManager likewise), and a
paused queue lets the host go idle - under the same id the job's end would remove it the moment it was posted. Exactly one of
the two ids carries the summary. Removed only when nothing is downloading, queued, preparing or paused (failed/Needs-you
alone still removes it, as before). +3 `DownloadsSummaryPolicyTest`. **Not physically verified.**

**Published:** mobile **debug 55** (`debug-v0.4.13-z1.55`, run `36210064323`, APK + IPA) carries the two fixes above - the
IPA build is the iOS compile check. No desktop build for them (desktop renders main content inline; no Android notification).

**Assisted "Choose now" (`ff2ef1715` + `4908e540c`; desktop `b69eaaff7` + `22b201950`).** Waiting for the exact sizes stays the
default. The finding sheet of a background batch now offers **Choose now** under **Continue in background**:
- The familiar resolution sheet at **4K / 1080p / 720p** (the resolutions a preference can name - which exist is unknown yet),
  preferred pre-selected, each row **"~12–25 GB · Estimated"**, plus "Estimated size · Exact size available after source
  discovery"; primary button **Choose**. Estimate (`DownloadSizeLevels.estimateBytes`, pure): the episodes' runtimes (unknown
  ones = the known average) x the size level's GB/hour, from the level below to the level itself. **"Size estimate
  unavailable"** when not one runtime is known or the level is Any - no assumed runtimes, no fake precision. Provisional with
  the size table.
- The choice is stored on the batch (`DownloadBatch.earlyResolutionHeight`, persisted - survives a process death; candidates
  still memory-only) and never asked again. Toast "1080p chosen · downloads start when sources are found"; the Downloads row
  reads **"Finding sources · 7 of 22 · 1080p chosen"**; tapping it opens the finding sheet with that line and **Change
  quality**.
- When discovery ends, each entry is decided **as Automatic would with the chosen resolution as the preference**
  (`automaticEntry` with `preferredResolution` overridden): inside the size rule; missing resolution -> the user's fallback
  (Lower / Higher, or **Ask -> the grouped Use-nearest Needs you card**); real sizes over the rule -> the over-limit decision
  (the estimate was not a promise); nothing cached / no sources -> Needs you. Then the usual free-space check. No "ready"
  prompt; foreground toasts what started, background relies on the summary notification (a system notice only if nothing
  could start). Queued rows carry the real sizes.
- If the estimate sheet is still open when discovery ends, the exact sheet replaces it (like the finding sheet); a choice that
  lands in that instant is applied at once.
- Tests: pure +5 (estimate range, unknown runtimes, no estimate; early heights / preference mapping; range label); desktop
  `AssistedChoiceFlowTest` +8 (real controller + store: estimates and no start, Any -> no estimate, starts without a second
  prompt, row-opened sheet closes at the end, fallback Ask -> `RESOLUTION_MISSING`, fallback Lower -> 1080p, over the rule ->
  `OVER_LIMIT`, survives a process death, estimate sheet -> exact sheet). Renders (desktop flow harness): `finding-chosen`,
  `resolution-estimated`, `resolution-estimate-unavailable` x 4 widths, read; no defects.

Verification: pure **940/940**; Android host **2,557/2,557** (results deleted, `--rerun`) + `:androidApp:compileFullDebugKotlin`;
desktop `desktopTest` **2,723/2,723** (results deleted, `--rerun`, JBR SDK).

**Published (2026-09-26):** mobile **debug 56** (`debug-v0.4.13-z1.56`, run `36212095114`, APK + IPA - the IPA build is the
iOS compile check for Choose now) and desktop **debug 67** (run `36212103659`). Both carry everything above.
**Nothing in this section is physically verified.**

**Owed:** Phase 9 has no rows in `Docs/Z-FEATURES.md` yet (stages 6-9 and this) - due before the release gate.

**Next:** physical QA of debug 56 / desktop 67 - **iPhone first**: choosing a profile reaches the wizard's download steps
(or the mobile-data question) and then Home; then the iOS baseline owed since `.54` (Assisted background discovery, ready
notification, Ready to choose quality, locked-screen queue vs `.46`, aggregate Live Activity, long-pause resume, Choose now);
Android: paused queue keeps "Downloads paused · N remaining" with Resume, Choose now (estimates, chosen row, starts without a
second prompt, fallback/over-limit cases); desktop `.67`: fully offline playback, season/autoplay, no network fallback,
background discovery, long-pause resume. Then size-level calibration from the `size_sample` logs (maintainer approval), iOS
experiments 10a/10b only if still wanted, `Docs/Z-FEATURES.md` rows, changelog QA, release gate (12). Cleanup list: the iOS
workflow's path filter misses shared-code-only pushes (keep dispatching by hand).

### Phase 9 - `.56` findings, Choose-now visibility, tabs, desktop destination, iOS experiments 10a/10b (2026-09-26)

**Physical `.56` (maintainer):** iOS profile -> wizard -> Home **passes** (the `.54` hang is fixed). Tapping **Choose now**
works. **Bug:** after Choose now the Downloads screen was empty while the season was being resolved. iOS still starts
~6 downloads at once (the known system-owned behaviour; window 12).

**Choose-now visibility - root cause and fix (`5cdf0bbd9`; desktop `f6b9afc62`).** Not iOS-specific. When discovery
ended, `applyEarlyChoice` "claimed" the batch by clearing `awaitsQualityChoice` and only then decided each entry -
and `automaticEntry` HEAD-checks a direct source's size (through StremThru that mints the TorBox link: seconds each),
one episode after another. For that whole pass the batch was neither an Assisted row (flag cleared) nor preparing
(entries still `AWAITING_CHOICE`) and had no items: the screen, the iOS Live Activity (`firstOrNull { isPreparing }` ->
no payload -> activity ended) and the Android summary all showed nothing, for minutes on a 22-episode season.
- The claim moves the entries to `RESOLVING` in the same write; entries are decided 3 at a time and written as
  decided; a batch removed meanwhile stops and queues nothing.
- `DownloadBatch.showsAsChoiceRow` / `choiceStatus` - one reading for the row and the Live Activity: **"Finding sources ·
  7 of 22 · 1080p chosen"**, then **"Checking sources · 3 of 22 · 1080p chosen"**. The Live Activity now shows
  "Lanterns S1" with that line instead of a bare "Finding sources". The empty state no longer shows under a finding row.
- Process death mid-check: `EarlyChoiceRestart` re-runs discovery and applies the same early choice (was "Preparation
  was interrupted").
- Tests: `AssistedChoiceTest` +4 (including the old claim's no-row state); desktop `AssistedChoiceFlowTest` +2 (size
  check held open: still an Assisted CHECKING row and still preparing; removing it then queues nothing).
- **Not physically verified.** Next iPhone check: Choose now on a season, stay on Downloads - the row never disappears;
  lock the phone during "Checking sources" - the Live Activity shows the same line.

**Mobile Library/Downloads tabs (`b013fd855`).** Two layout causes, no animation: (1) `LibraryScreen` runs an unpadded
`NuvioScreen` and pads its switcher 16dp; `DownloadsScreen` kept the default 16dp screen padding and padded the switcher
**another** 16dp - the chips jumped 16dp sideways on every switch; (2) `LibraryChip` renders the selected label
SemiBold, so selecting changed the chip's width and moved its neighbour. Fixed by dropping the second padding and
having the chip always measure its SemiBold label (drawn invisibly, no semantics). Verified by desktop
`LibraryTabSwitcherRenderHarness` - the **production** Library and Downloads screens at 360/420, chip bounds equal to the
pixel across both tabs; mutation-checked (the old padding fails it with a 32px shift). Header icons tinted like
Library's (`f404a7a37` desktop / mobile `fix(downloads): header icons tinted`). Renders:
`Nuvio Z/render-review/phase-9-tabs-and-desktop/`.

**Desktop: Downloads is its own destination (decided 2026-09-26, supersedes "inside Library").** `downloadsIsOwnDestination
= isDesktop` (`AppScreenTab.kt`): sidebar item restored, `AppScreenTab.Downloads` is a real tab, `coerceAvailableTab` /
`NavigationIntent.fromTab` keep it, and every "open Downloads" (toast, notification, deep link, choose-quality link) goes
through `openDownloads()`. One `DownloadsScreen`, no Library switcher on desktop. Phones unchanged (tested both ways in
`SocialTabAvailabilityTest`).

**Desktop Downloads width.** Cause: the screen's own 880dp cap (`DownloadsContentMaxWidth`) - ~290dp of nothing each side
at 1920 (logical width ~1458dp at UI scale 1.32). Widening the column would bring back rows whose actions sit a screen
from their title, so from 1000dp of content the destination is **two panes** (`DownloadsWideLayout`): Needs you + the
downloads under way (max 860dp) and a 340dp rail with storage, the watched-cleanup suggestion and On this device - the
same sections in the same order, 32dp gutters, the pair centres beyond ~1300dp. Narrow windows and a show's page keep one
column; everything finished -> "Nothing downloading right now" in the main pane. Rendered 960 / 1280 / 1440 / 1920 (+ full
height) by `DownloadsScreenRenderHarness` with a 68dp sidebar.

**iOS transfer experiments (not adopted - awaiting the maintainer's physical comparison).**

| Build | Branch | Transfer model |
| --- | --- | --- |
| **debug 57** `debug-v0.4.13-z1.57` | `claude/phase-9-downloads` | **Baseline** = `.56` exactly (window 12, created and resumed in the foreground). Carries the fixes above + diagnostics. |
| **debug 58** `debug-v0.4.13-z1.58` | `claude/phase-9-ios-exp-10a` | **10a controlled resume**: window's tasks still created in the foreground, at most **2** resumed; the rest held suspended in the session, each finished task (also on a background wake) resumes the next held one in queue order. Held tasks keep their window slot. |
| **debug 59** `debug-v0.4.13-z1.59` | `claude/phase-9-ios-exp-10b` | **10b connection limit**: `.56` window unchanged; `HTTPMaximumConnectionsPerHost = 2` on the background session. |

58 and 59 are each 57 + one change; neither is merged. Diagnostics in all three (`0973dc707`, iOS probe log): `session_config`
(variant, window, the session's per-host limit - 57 logs iOS's default), `concurrency` (held / running / suspended / moving in
the last 5 s; every 15 s while bytes arrive and at each completion), `metrics` gains `hosts` and a 24-bit `hostTag` (never a
host or URL), 12 log files kept. `scripts/ios-transfer-report.py <folder>` prints peak/average concurrent responses from the
system's own metrics (valid while locked), per locked period the transfers started / finished, outcomes, errors, throughput
and distinct final hosts. **Adoption needs the maintainer's physical evidence**; if neither keeps `.57`'s locked progression,
the `.56` model stays for Phase 9 and iOS keeps no "Downloads at once" setting.

**Verification:** Android host **2,562 run, 2,561 pass** (results deleted, `--rerun`) - the one failure is
`WatchedItemsStoreTest.concurrent updates publish coherent item snapshots`, unrelated (watched store) and failing again when run
alone; `:androidApp:compileFullDebugKotlin` passes. Desktop: see `NuvioZDesktop/STATUS.md`. iOS: compiled only by the Debug
release runs below.

**Published:** mobile **debug 57** (`debug-v0.4.13-z1.57`, run `36239064765`; the first dispatch `36238405990` failed the iOS compile - an `@OptIn` displaced by the host-tag helper, fixed in `fix(ios): keep ExperimentalForeignApi opt-in`), **debug 58** = 10a (run `36239750427`), **debug 59** = 10b (run `36239759753`), each APK + IPA. Desktop **debug 68** (run `36238907396`). ⚠ The debug updater offers the newest prerelease (59) to 57/58 - install each IPA by hand and decline the update prompt during the comparison. **Nothing here is physically verified.**

**Next:** the maintainer's physical comparison of 57 / 58 / 59 (procedure in the session handoff and below), then adopt a
winner or keep the baseline; size-level calibration; desktop offline QA if still pending; `Docs/Z-FEATURES.md` rows (owed);
changelog audit; final matrix; release gate.

**iOS comparison procedure (each of 57, 58, 59, same phone, same conditions):** Wi-Fi, battery > 50 %, not charging, Low
Power Mode off. Delete `nuvio_diagnostics` files (Files -> On My iPhone -> Nuvio Z Debug) before each run. Same ~10-episode
season each time (delete it between runs), Automatic or Assisted at the same quality. Start it in the foreground; after ~30 s
count the rows whose bytes are moving; lock the phone and leave it untouched **30-40 min**; unlock, open Downloads, note
completed / failed / still waiting and any Live Activity oddity. Pause one downloading episode > 1 min and resume it (must
continue from its partial). Export the whole `nuvio_diagnostics` folder and run `python scripts/ios-transfer-report.py
<folder>`. In 58, rows beyond the first two read "Starting" while held - expected.

## Phase 8 closeout: DONE WITH DOCUMENTED DEBT (2026-09-24)

> ⛔ **No stable mobile release follows Phase 8, and no TestFlight upload.** This is a maintainer
> decision. The next stable Android/iOS release waits for **Phase 9 — Downloads Redesign** and
> its release gate (`ROADMAP.md`, Phase 9 §L). The `debug-v*` prereleases are QA artifacts only.
> Do not read "Phase 8 closed" as permission to publish mobile stable.

**Roadmap renumbered:** Phase 9 is now **Downloads Redesign**, a full phase (it was queued as a
"Phase 8 follow-up"). TV / Tizen / webOS moves to **Phase 10** with its scope unchanged. The
per-item evidence and debt tables are in `ROADMAP.md`, Phase 8. This section records the closeout
mechanics.

**Branches and merges:**
- Mobile: `claude/phase-8-ios-queue-ownership` (last code commit `223443bf1`) merged to `main` as
  `fcffb844f` (feed conflict resolved to `main`'s canonical `source-debug.json`), then the
  closeout fix `4b172884c` on `main`; the branch was fast-forwarded to it.
- Desktop: the Phase 8 shared Kotlin is on `NuvioZDesktop` branch
  `claude/phase-8-shared-convergence` (`04538a637`), **not merged to `Dev`**. It carries:
  - the `commonMain`/`commonTest` diff `cd08ca322..223443bf1` and the unbuilt
    `androidMain`/`iosMain` copies, 3-way applied;
  - desktop's own `strings.xml` plus Phase 8's new Z block;
  - `MetaDetailsScreen.kt` (never copied), with the whole-title season scope ported by hand to
    both of desktop's call sites;
  - six new `DownloadsPlatformDownloader.desktop.kt` actuals: `maxConcurrentTransfers = 2`,
    `ownsTransferLiveness = false`, and no-op `schedulingDeferredToPlatform`,
    `requestTransferInventory` (answers `null`), `suspendTransfer` and `cancelTransfer`.

  **Why not `Dev`:** the shared `.43` navigation change moves Downloads under Library, and that
  also takes Downloads out of the **desktop sidebar**, which was never reviewed for desktop.
  Desktop is live, so this waits for Phase 9 §J. The branch stays mergeable, and the ledger (D6)
  says so. `iosApp/` in the desktop repo was already a stale, unbuilt copy before Phase 8 and was
  not touched.
- Desktop pre-existing: `codex/phase-7-release-engineering` (48 commits: the Phase 7 desktop
  release hardening) is **still not on `Dev`**. The convergence branch is cut from it. The next
  desktop release from `Dev` would not carry the Phase 7 guards until that merge happens. This is a
  maintainer call and is recorded here rather than made silently.

**One must-fix found at closeout, fixed (`4b172884c`).** `prepareUpcomingTransfers` (from `.44`)
resolves the next queued sources ahead of their slots so the iOS background session can chain past
the submitted window. It ran on **every** platform. On Android and desktop it contacted providers
while offline and resolved episodes ahead of their turn, breaking the Phase 3 queue contract.
`NuvioZDesktop`'s `DesktopDownloadQueueE2ETest` ("unresolved sources wait for slots and never
resolve while offline") failed deterministically on the convergence branch. It now returns
unless `ownsTransferLiveness` is set, so iOS is byte-for-byte unchanged and Android/desktop are back
to pre-`.44` behaviour. This is not in the `.48` build. The next Android debug build carries it,
and no new build was cut for it.

**`.48`:** published. [`debug-v0.4.13-z1.48`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.48)
is a prerelease targeting `223443bf1` (Debug release run `36014554236`, 2026-09-24 14:59 UTC). It
contains the unsigned IPA `Nuvio-Z-iOS-0.4.13-z1-48-debug-unsigned.ipa` (sha256 `fab650c1…a76c5a74`), the
APK `androidApp-full-debug.apk` (sha256 `d34ad0a1…0e93a00f`), `SHA256SUMS-Debug.txt` and
`source-debug.json`. The canonical SideStore debug feed on `main` lists `com.nuvio.app.z.debug`
`0.4.13-z1.48` (48) first, with a matching sha (`f2e4369ff`, feed file only). `.48` is the final
Phase 8 debug build. It does **not** contain `4b172884c`.

**Verification at closeout:**
- Mobile, local, on `223443bf1` and again on `4b172884c` (results dir deleted, `--rerun-tasks`):
  pure suites **859 / 859** (8 groups); Android host suite **2,371 / 2,371**; `compileCommonMainKotlinMetadata` and
  `:androidApp:compileFullDebugKotlin` pass.
- Release guards: `scripts/test-store-source.py` passes (feed isolation, cross-talk, stale-build
  race, workflow static checks). `release-metadata.sh` resolves stable `0.4.13-z1+127`, and nothing
  was tagged or published.
- CI: `ci.yml` passed on `223443bf1` (`36014183651`), `ecfd5fd48`, `fcffb844f` (`36017025481`) and
  `4b172884c` (`36021522325`). The push quick iOS check passed on `223443bf1` (`36014182993`).
- **Full iOS checkpoint** (`ios-build.yml` by `workflow_dispatch`: the device framework link,
  the simulator framework link, and the unsigned Xcode app): **passed** on the merge `fcffb844f` (run `36017039908`): device
  link, simulator link and unsigned Xcode build all succeeded. It was re-dispatched on the final `main` `4b172884c`
  (run `36022650848`, iOS no-op change). Its result was pending when this was written; check the run.
- Desktop, on the convergence branch: JBR SDK, results dir deleted, `--rerun-tasks`: pure **780 / 780**;
  `compileKotlinDesktop` passes; `desktopTest` **2,512 / 2,512** after the fix (the first run was 2,511 / 2,512,
  with the E2E regression above). No MSI was built and nothing was published.

**Physically validated on iPhone during Phase 8** (SideStore Debug builds):
- foreground episode download and offline playback (`.42`);
- byte delivery under lock (`.43`);
- queue order, and the Delete freeze gone (`.45`);
- **downloads continuing while locked** (`.46`, and the `.47` log with six episodes completing
  in locked wakes);
- diagnostics in Files (`.47`).

**Not physically run:** the `.48` fixes, and most `.43`-`.48` checklist rows. **No UltraReview
was run.** Ultra 2 is still held.

## Phase 8 Batch 7 — `.48` stabilization after physical `.47` (2026-09-24)

`.47` made the diagnostics readable, and the `.46` logs explain Pilot (`Docs/PHASE-8-IOS-BRINGUP-AUDIT.md`,
"Batch 7"). In short:
- iOS resumed Pilot's transfer with range requests, and the last response was a 206;
- `.46` sized the file from that 206's Content-Length, called it an overrun, and deleted it;
- the retry attached to the finished task, which a `===` comparison had left in `tasksById`, and
  "resumed" it. That is a no-op, so the row stayed at 282.9 / 282.9.

`.47` did not cover this path.

Fixes (`DownloadsPlatformDownloader.ios.kt`, `IosBackgroundTransferReconciler.kt`):
- tasks are matched by `taskIdentifier`;
- `dropFinishedTasks()` runs before every inventory and start;
- a 206 is sized from Content-Range (`finishedTransferTotal`, 3 new tests).

Also: the batch's **Choose source manually** opened the player. It now opens the download-intent
source list (`MainAppContent.kt`, 1 call site).

**Scope held by maintainer decision:**
- no source-selection redesign;
- no "nearest acceptable source" automation;
- no review-card redesign.

All of that is queued as the **cross-platform Downloads UX and source-policy pass**, now **Phase 9 — Downloads
Redesign** in `ROADMAP.md` (it was "Phase 8 follow-up" when written). Concurrency stays at the submitted window of 12 (Batch 6 decision).

Debug counter 48. Not published until the maintainer asks.

Verification (local; results dir deleted, `--rerun-tasks`):
- pure suites **859 / 859**;
- Android host suite **2,371 / 2,371**; `compileCommonMainKotlinMetadata` and `:androidApp:compileFullDebugKotlin` pass. The quick iOS build on push is the gate for the iOS Kotlin change.

## Phase 8 Batch 6 — `.47` follow-ups to physical `.46` (2026-09-24)

Physical `.46`: **locked-screen downloading works. Do not regress the submitted window.**

Findings and changes (full analysis: `Docs/PHASE-8-IOS-BRINGUP-AUDIT.md`, "Batch 6"):

1. **The Live Activity went stale while locked, and it named one arbitrary episode.** While the app is
   backgrounded with an active queue, the payload is now a fixed, queue-level "Nuvio Z Downloads /
   Downloading in background", with no title, percentage, bytes or counts. The widget shows only
   that. The rich view returns in the foreground. Files: `DownloadsLiveStatusPlatform.ios.kt`,
   `DownloadsLiveActivityWidget.swift`, and two new Z strings.
2. **Six simultaneous transfers:** no code change. iOS offers no reliable native concurrency cap
   for submitted background tasks; the reasoning is in the audit doc. The window (12) is the only
   honest lever, and it is kept at 12 for locked-screen reliability.
3. **Needs your attention was a dead end.** The review ▶ bulk-approved uncached debrid sources,
   which `DirectDebridResolver` rejects from the addon's `NOT_CACHED` snapshot forever ("Waiting
   for provider 1/5", then failure). Fixes:
   - `DownloadBatchEntry.needsManualSource` / `canBeApproved`;
   - `queueBatch` approves only what an approval can help;
   - the review card sends uncached, skipped and failed entries to **Choose source manually**,
     with a localized summary;
   - a queued known-uncached download fails at once with a clear message;
   - a failed batch download offers **Choose source manually** under its row.

   Scoped to the Z download/batch code. `DirectDebridResolver` is unchanged.
4. **Pilot stuck at 282.9 / 282.9 MB (release blocker).** The cause is unconfirmed, because the `.46`
   logs were unreadable (see 5). Both `.46` holes that can freeze a row at 100% are closed:
   - a Running task holding every byte for 2+ minutes is cancelled and retried (`isStalledAtEnd`,
     checked at each foreground inventory);
   - a held claim already handed to the session, with no task left, is released and requeued
     (`planAdoption.releaseLost`).

   New `finalize`, `inventory_task`, `stalled_at_end` and `repo` log lines will show which case
   Pilot hit.
5. **Diagnostics were never in Files.** Xcode drops `INFOPLIST_KEY_UIFileSharingEnabled` from the
   generated plist. `scripts/build-ios-ipa.sh` now sets it and `LSSupportsOpeningDocumentsInPlace`
   on the **Debug** app only, and fails the build otherwise. The same bundle id keeps the `.46`
   container.

Debug counter 47. Published [`debug-v0.4.13-z1.47`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.47)
from `e15e7843c` (run `36008713787`). The IPA is unsigned, `com.nuvio.app.z.debug` 0.4.13-z1.47 (47), with
`UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace` both true and the widget extension
present. IPA sha256 `a7e31a4c…3b1d1c` and APK `2d097d0b…5138a8` match `SHA256SUMS-Debug.txt`. The
SideStore debug feed on `main` lists `.47` first (`fcb92a008`, feed file only).

Next: the physical `.47` pass (audit doc section 10). Install over `.46` and send every
`downloads-*.jsonl` from `Files > On My iPhone > Nuvio Z Debug > nuvio_diagnostics`, including `.46`'s if
they survived.

Verification (local; results dir deleted, `--rerun-tasks`):
- pure suites **856 / 856**;
- Android host suite **2,368 / 2,368**; `compileCommonMainKotlinMetadata` and `:androidApp:compileFullDebugKotlin` pass. iOS CI and a Debug test IPA are the gate for the iOS Kotlin, Swift and packaging changes.

## Phase 8 Batch 5 — iOS submitted window (`.46`) (2026-09-24)

Branch `claude/phase-8-ios-queue-ownership`, continuing from `.45`. **Not published**: the maintainer
reviews the design before a release. The debug counter is already at 46.

Physical `.45` findings:
- queue order is fixed;
- the Delete freeze is gone;
- while the phone is locked, #3/#4 never start when #1/#2 finish. They show `Starting` on unlock;
- the Live Activity only moves while unlocked.

Cause: `.45` created each next task from a background completion wake. Apple documents that such a
task is discretionary and rate-limited, with a delay that grows per wake and resets only in the
foreground. The quote and the analysis are in `Docs/PHASE-8-IOS-BRINGUP-AUDIT.md`, "Batch 5".

Requirement change from the maintainer: **no concurrency cap on iOS**. A queue built while Nuvio is
open must keep moving after the lock. Order is kept where URLSession allows it.

Code changes:
- **`expect` surface:** new `maxConcurrentTransfers` (iOS 12, Android 2) and `ownsTransferLiveness`
  (iOS true). `DownloadPlatformRequest` gains `queuePosition` and `sourceUrlResolvedAtEpochMs`, both
  defaulted. **NuvioZDesktop needs `maxConcurrentTransfers = 2` and `ownsTransferLiveness = false`
  at the next merge**, on top of the four `.45` actuals already pending.
- **`DownloadsRepository.kt`:**
  - every `MAX_CONCURRENT_TRANSFERS` use now reads the platform value;
  - with `ownsTransferLiveness` set, the silence watchdog and the stall wake are off;
  - resolved starts are parked and released in queue order when `ownsTransferLiveness` is set
    (`releaseInQueueOrder`, pure and tested).
- **`DownloadsPlatformDownloader.ios.kt`:**
  - window constant, with the rationale in its KDoc;
  - task priority is re-ranked by queue position, as a hint only;
  - a system cancellation (a force-quit) is no longer claimed and failed. A live one becomes a
    system pause;
  - `didFinishCollectingMetrics` is logged;
  - the Live Activity is refreshed on background/active transitions.
- **`DownloadsProbeLog.ios.kt` (new):** Debug-only JSONL in `nuvio_diagnostics/downloads-*.jsonl`,
  enabled from `OrientationLockCoordinator.swift` under `#if DEBUG`. No URLs or headers are logged.
- **Live Activity:** the payload and `ContentState` gain an optional `backgroundStatusText` (new Z
  string `downloads_live_in_background`). While it is set, the widget hides the percentage, bytes and
  bar and shows it with the queue summary.

Not changed:
- `SOURCE_URL_FRESHNESS_MS` (15 min). It is the resolver's cache TTL, and the metrics will show real
  link ages;
- best-effort background chaining beyond the window;
- Android behaviour.

Not done:
- resume data from a force-quit cancellation is not used. The restart is from zero, in place.

Verification (local; results dir deleted, `--rerun-tasks`):
- pure suites **850 / 850** (9 new in `IosBackgroundTransferReconcilerTest`);
- Android host suite **2,355 / 2,355**; `compileCommonMainKotlinMetadata` and `:androidApp:compileFullDebugKotlin` pass.

The iOS source set cannot compile on Windows, so iOS CI is the gate. On `bd28fcd8c`, `ci.yml` passed
(run `35990649203`) and `ios-build.yml` passed (run `35990649250`: device and simulator framework
links and the unsigned Xcode build). Nothing here is physical verification.

Published [`debug-v0.4.13-z1.46`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.46)
from `5bfff9597` (Debug release run `35995592422`), a prerelease:
- IPA `Nuvio-Z-iOS-0.4.13-z1-46-debug-unsigned.ipa`, unsigned. Bundle `com.nuvio.app.z.debug`,
  version `0.4.13-z1.46` (build 46), `DownloadsWidgetExtension.appex` at the same version;
- IPA sha256 `d7533e33…87b8261`; APK sha256 `1e5e9945…8f20f834`. Both match `SHA256SUMS-Debug.txt`;
- the canonical `source-debug.json` on `main` lists `.46` first (`1aaaf47da`, feed file only).
  The stable `source.json` is untouched.

Next: the physical `.46` pass (audit doc section 9). Return the `downloads-*.jsonl` files. **No
architecture changes until those metrics are back.**

## Phase 8 Batch 4 — iOS queue ownership (`.45`) (2026-09-23)

Active branch: `claude/phase-8-ios-queue-ownership`, from `gemini/phase-8-ios-background-orchestration`
(`ac84da767`, the `.44` commit). Not merged to `main`; debug prerelease only.

Physical `.44` findings:
- the season stayed at `Queued #1`;
- after reopening, #3 and #4 started first, then fell back to Queued;
- screen-off was unreliable;
- intermittent total input freeze (Delete on a Downloads episode reproduces it).

Root causes and the new ownership model are in `Docs/PHASE-8-IOS-BRINGUP-AUDIT.md`
("Batch 4"). In short:
- `.44` ran a second, native scheduler on a persisted copy of the queue whose `RUNNING` meant "the
  repository has claimed it", not "a task exists".
- Its journal demoted items the repository was resolving.
- Every progress tick did queue sync and Live Activity work.

Code changes:
- **`IosBackgroundTransferReconciler.kt`:** the `.44` prepared-state/journal/codec machinery is
  replaced by two pure policies:
  - `scheduleNextTransfers`: slots are running tasks ∪ repository claims; strict FIFO with a stale
    boundary; suspended tasks are resumed.
  - `planAdoption`: adopt real running tasks in queue order, suspend any beyond capacity, cancel
    ones nobody wants, and re-queue stale `Downloading` in place with no attempt charged.
- **`DownloadsPlatformDownloader.ios.kt`:** all state is confined to the session's serial delegate
  queue. There is no lock, one task per download, and an inventory from `getAllTasks` before any
  task is created.
  - Background slot-fill pulls `DownloadsRepository.nativeSchedulingSnapshot()`.
  - Tasks with no listener are handed to `claimNativeTransfer`.
  - Progress is reported at most once a second.
  - The background-session completion handler is called on main.
  - `recoversSystemPauses` is now `false`: nothing on iOS has resumed a system pause since `.43`.
  - The `.44` NSUserDefaults keys are deleted.
- **`DownloadsRepository.kt`:**
  - Scheduling defers while iOS is backgrounded and is held until the platform inventory arrives
    (3 s fallback). This runs on load, profile change and return to the foreground.
  - Adoption and native claims go through the normal generation-fenced listener.
  - Removed: the `.43`/`.44` direct `reconcileIosBackground*` paths, the per-publish native sync
    and the journal. Every completion now goes through `onTransferCompleted`'s
    implausibly-small check.
- **`expect` surface:** the three `.44` members are replaced by `schedulingDeferredToPlatform`,
  `requestTransferInventory`, `suspendTransfer` and `cancelTransfer`, with no-op Android actuals.
  **`NuvioZDesktop` needs the same four no-op actuals at the next merge** (`requestTransferInventory`
  must call `onResult(null)`).
- **Live Activity:**
  - progress-only payload writes are limited to one a second;
  - the queue summary and "Finding sources" are localized (new Z strings block);
  - `DownloadsLiveActivityManager.swift` applies updates serially, latest wins.
- **Freeze:** not root-caused. The candidates are a main-thread stall (the progress floods above are
  fixed) or a window-level view taking touches (`AppGateComposeView`, Issue 2's sibling). The Debug
  configuration only adds:
  - `FreezeDiagnostics.swift`: a watchdog, a touch hit-test probe, a window/overlay dump on
    background, gate notes and MetricKit hangs;
  - Files-app visibility, so the logs can be retrieved from `Nuvio Z Debug/nuvio_diagnostics`.

Verification: local, after a clean results dir and `--rerun-tasks`:
- pure suites **841 / 841**;
- Android host suite **2,346 / 2,346**;
- `compileCommonMainKotlinMetadata` and `:androidApp:compileFullDebugKotlin` pass.

The new regressions are in `IosBackgroundTransferReconcilerTest`.

CI on the branch:
- `ci.yml` passed: run `35923370814`.
- `ios-build.yml` passed: run `35923371207`, covering the Kotlin framework device and simulator links and the unsigned Xcode build. The first attempt, `35922141103`, failed because `NSURLSessionTaskState` imports as constants in Kotlin/Native; this was fixed in `cb95f4bf2`.

Published [`debug-v0.4.13-z1.45`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.45) from `cb95f4bf2` (run `35926684747`):
- a prerelease with the Android debug APK and the iOS Debug unsigned IPA;
- the SideStore debug feed was updated on `main` by the workflow (`f3fb3e6eb`, feed file only).

Nothing here is physical verification.

Next: physical `.45` pass (checklist in the audit doc, section 8). Send the diagnostics folder if
the freeze recurs.

## Phase 8 Batch 3 — iOS background queue, completion journaling, and stable Live Activity (2026-09-23)

Continuation work is on `gemini/phase-8-ios-background-orchestration`, branched from `gemini/phase-8-ios-downloads-nav-hardening` (`4d338bc09`).
Physical `.43` validation confirmed native `NSURLSessionDownloadTask` maintains byte delivery while the screen is locked,
resolving the raw transport boundary. However, it surfaced three higher-level orchestration boundaries:

1. **Stalled completion bookkeeping**: Downloads finishing while suspended had their files saved to sandbox disk, but
   Kotlin/Compose state did not finalize until app foregrounding because the Kotlin runtime is paused while suspended.
2. **Stalled queue advancement**: Subsequent queued items in a multi-episode batch did not start while locked because
   Kotlin coroutines cannot run in background to resolve debrid download URLs.
3. **Live Activity churn & concurrency collision**: Live Activity flickered, progress ping-ponged between concurrent downloads,
   and ActivityKit rejected rapid recreation loops caused by tying activity lifecycle to individual `downloadId`s.

Code-fixed for `.44`:
- **Durable Completion Journal**: Native delegate synchronously logs atomic events (`COMPLETED`, `FAILED`, `NEEDS_SOURCE_REFRESH`, `PROGRESS`)
  to `NSUserDefaults` (`nuvio.ios_downloads.journal.v1`). `DownloadsRepository.reconcileIosBackgroundJournal()` idempotently reconciles
  and acknowledges events on app resume via pure reconciler `IosBackgroundTransferReconciler.kt`.
- **Ahead-of-Time Source Preparation**: While foregrounded, resolves direct URLs for up to 5 upcoming items in approved batches with
  15-minute debrid TTL, persisting descriptors in `nuvio.ios_downloads.prepared_queue.v1`.
- **Synchronous Native Queue Scheduler**: When a transfer finishes in background, `advanceNativeQueueLocked()` checks active slots
  (concurrency limit: 2) and launches the next fresh prepared transfer immediately before the background completion handler returns.
- **Strict FIFO Queue Ordering at Stale Boundaries**: If the next queued item's prepared link is stale (>15m), the native scheduler halts
  queue advancement at that boundary, records `NeedsSourceRefresh`, and pauses until foreground wake. It preserves episode order without
  corrupting downstream transfers.
- **Stable Live Activity Identity & Sticky Selection**: Migrated to a single persistent session (`sessionKey = "nuvio.downloads.session"`).
  Selection policy prioritizes lowest queue rank and remains sticky to prevent flip-flop. Presentation includes queue summary text
  (e.g., `2 downloading • 3 remaining`). Native layer directly updates the Live Activity payload during background transfers.

Verification:
- Pure test suites: **825 / 825 passing** (+13 new pure tests in `IosBackgroundTransferReconcilerTest` and `DownloadsLiveStatusPolicyTest`).
- Android host suite: **2,324 / 2,324 passing** (+13 new tests).
- Kotlin metadata: `compileCommonMainKotlinMetadata` passes cleanly.
- Debug build counter: bumped to 44 (`DEBUG_BUILD=44`) for `0.4.13-z1.44` prerelease.
- Status: Awaiting physical `.44` device validation.

## Phase 8 Batch 2 — iOS downloads and navigation hardening (2026-09-23)

Continuation work is on `gemini/phase-8-ios-downloads-nav-hardening`; it preserves and completes
the interrupted Gemini patch. The `.42` device pass established the following physical facts:

- individual episode download and offline playback pass;
- screen-off transfer fails (it stops, then foreground recovery shows a synthetic retry);
- six native iPhone tabs produce a `More` destination;
- whole-title context-menu Download does not produce a usable batch;
- Live Activity shows dishonest unknown progress and can remain after completion;
- the season download control is unreadable.

Code-fixed for `.43`, awaiting physical validation: a stable per-bundle background
`URLSessionDownloadTask` session with durable task metadata and relaunch attachment; no
background-triggered repository pause; Library + Downloads as one top-level destination; old
Downloads intent/deep-link/toast migration; whole-title season targeting; honest Live Activity
states and orphan cleanup; semantic season-control colors; and removal of inert iOS navigation
appearance choices. Legacy `.42` `.part` files restart once rather than risk corrupt concatenation.
Android shares the routing, batch, Library and color changes and requires physical spot-checking.
The desktop completed-file report belongs to `NuvioZDesktop` (this repository has no desktop
downloads actual) and remains an explicit desktop retest.

The inherited pure-suite state was **799 / 799** (the previous 790 plus Gemini's 9 reconciler
tests). After completing the reconciler and Live Activity policy matrices it is **812 / 812**.
The Android host suite is **2,311 / 2,311** and common Kotlin metadata compiles.
The dedicated native iOS build succeeded in GitHub Actions run `35810809573` following commit
`64d349231` (declaring the background download manager as a class with a lazy singleton reference to satisfy Kotlin/Native
LLVM lowering). Normal CI passed in run `35810809530`. Debug counter is bumped to 43 for `.43` publication.
None is represented here as physical verification.

## Phase 8 — iOS Device Validation & Bringup Audit (2026-09-22)

**Physical-iPhone QA bringup completed on branch `gemini/phase-8-ios-bringup-audit`.**
All 9 physical-iPhone findings were traced, root-caused, cross-checked against Android, and audited
mobile-wide for bug-class prevalence. 8 issues resolved with targeted fixes; Issue 4 (platform settings
ownership) formally deferred with comprehensive architectural specification and migration blueprint.

- **Issue 1 (Watch Together Join Indicator Obscured)**: Anchored mobile `WatchTogetherDock` to
  `Alignment.TopEnd` with `safeDrawing.only(Top) + 12.dp`, avoiding bottom navigation bar (64–84dp)
  and notification toast conflicts.
- **Issue 2 (Setup Wizard Non-Interactive on iOS)**: Fixed `AppGate` readiness gate race condition
  where fast background home query triggered `onAppReady(true)` and caused SwiftUI
  `AppGateComposeView` to apply `.allowsHitTesting(false)`. Gate now holds readiness and delays
  `onMainContentMountChanged(true)` while `isSetupWizardActive` or `isWhatsNewActive`.
- **Issue 3 (Email Sign-In / Sign-Up Unreliable)**: Fixed anonymous user retention trap in
  `AuthRepository`. Removed `sessionStatus.collect` drop when anonymous ID was present, immediately
  cleared anonymous user ID on successful auth, and synchronously updated `_state.value = Authenticated`.
- **Issue 4 (Platform Settings Ownership)**: DEFERRED. Documented architectural analysis of cross-device
  settings collisions. Recommended Hybrid A + C model where global profile preferences remain synced
  via Supabase while platform-specific hardware capabilities and wizard completion revisions reside in
  device-local storage (`nuvio_device_settings`).
- **Issue 5 (Settings Parity & Run Setup Again Missing)**: Added `runSetupAgainRequests` and
  `whatsNewRequests` channels to `AppGateController` and connected iOS `bypassAppGate = true` root
  tabs, enabling on-demand setup wizard and release notes.
- **Issue 6 (Social vs Downloads Navigation Bar Collision)**: Restored `downloads` tab and
  `socialCoordinator` in `ContentView.swift`. Downloads and Social tabs now operate independently
  with dedicated icons, labels, and coordinators.
- **Issue 7 (Downloads Route Mismatch & iOS Storage Parity)**: Repaired `DownloadsDestination`
  in `SettingsDestinations.kt` to route to `DownloadsSettingsScreen` instead of `DownloadsScreen`.
  Confirmed iOS native background download engine (`NSURLSession` + file storage) is operational.
- **Issue 8 (Playback Preferences Dialog Layered Behind Sheet)**: Plumbed `preferencesDialog`
  slot directly into `PlaybackQualitySheet`, rendering preferences inside the sheet's active
  `UIViewController` hierarchy on iOS and within the modal bottom sheet container.
- **Issue 9 (Playback Startup Insets & Dynamic Island Collision)**: Replaced `safeContent` with
  `safeDrawing` in `PlaybackLoadingScreen`, adding explicit `WindowInsets.safeDrawing.only(Top + Start)`
  padding to back button and horizontal/bottom insets to metadata and loading bands.
- **Pure Test Suites**: All 8 pure test suite groups (790 tests total) pass cleanly. Added unit
  regression suites in `BottomNavItemIdentityTest` and `SetupWizardStepsTest`.
- **Audit Documentation**: Canonical audit published in `Docs/PHASE-8-IOS-BRINGUP-AUDIT.md`.
- **Debug Prerelease 0.4.13-z1.42**: Published for physical iPhone QA validation via SideStore Developer Channel.
  - GitHub Actions Run: `35773022968` (all 4 jobs green).
  - Release: [`debug-v0.4.13-z1.42`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.42)
  - iOS IPA: `Nuvio-Z-iOS-0.4.13-z1-42-debug-unsigned.ipa` (73,004,543 bytes, SHA-256 `0983212e7f61f0d3ae3f63d2d226d7dd4e7109f18e027465f2a7613da2b5013e`).
  - Identity: `com.nuvio.app.z.debug`, `Nuvio Z Debug`, unsigned, contains `DownloadsWidgetExtension.appex` (`com.nuvio.app.z.debug.DownloadsWidgetExtension`).
  - Android APK: `androidApp-full-debug.apk` (151,789,765 bytes, SHA-256 `9ef2faf32aaac876b8e9a0a2db82c8e92d86692e214ccd47e42f7b04214c9753`).
  - **Feed Promotion & Workflow Hardening**: Canonical `distribution/sidestore/source-debug.json`
    on `main` promoted to `0.4.13-z1.42` (`cd08ca322`) without rebuilding IPA or merging feature branch.
    Hardened `.github/workflows/debug-release.yml` to clone canonical `main` and push feed updates directly
    to `main` with rebase retry and race condition prevention (`update-store-source.py`). Feed isolation
    and promotion test suite expanded to 10/10 tests.


## Nuvio Z iOS Setup GUI v1 (2026-09-22)

**A portable Compose Desktop setup wizard now replaces the terminal bootstrap as the intended
SideStore onboarding path.** The PowerShell and shell scripts remain in place as advanced fallback
and diagnostic tools. No Nuvio Z IPA, stable release, or release workflow was changed.

- New `iosSetup/` Kotlin/JVM + Compose Desktop module packages a self-contained jpackage app-image
  for Windows and macOS. The acceptance artifacts are portable ZIPs; no MSI/PKG installation is
  required to run the setup utility.
- The explicit 14-step state machine keeps USB detection, iloader installation, SideStore
  appearance, pairing placement, profile trust, Developer Mode, first refresh, source addition and
  Nuvio Z installation as separate gates. A child-process exit is not a state-machine event, so
  closing iloader cannot advance any human-controlled step.
- Stable is the default. The Debug developer channel is available only through Advanced settings
  or `--developer`, and requires a deliberate warning confirmation before selecting
  `source-debug.json` / `com.nuvio.app.z.debug`.
- Progress is stored in per-user app data as `setup-state.json`; it contains only the schema,
  current/completed steps, channel and boolean confirmation/repair/override flags. Credentials,
  Apple Account details, 2FA codes and tokens have no model fields and are not logged.
- Current official SideStore guidance is reflected in separate install, pairing, trust, Developer
  Mode and first-refresh pages. Pairing repair includes Reset Pairing File, Delete Stored Pairing,
  re-trust, Manage Pairing File, Place and the required green success message.
- The source page makes manual URL copy/paste the reliable path and keeps a locally generated ZXing
  QR as optional convenience. A round-trip decode test pins the exact `sidestore://source?url=...`
  payload.
- `.github/workflows/ios-setup-build.yml` builds and uploads
  `Nuvio-Z-iOS-Setup-Windows-x64.zip` and `Nuvio-Z-iOS-Setup-macOS.zip` without attaching either to
  normal Nuvio Z releases.

Local verification: `:iosSetup:test` **11 / 11**, `:iosSetup:createDistributable` successful,
packaged Windows executable launched and remained responsive, and the existing SideStore feed
isolation suite passed all stable/debug cross-talk checks. Physical iPhone flow and macOS runtime
remain acceptance-test work; CI packaging is tracked by the dedicated workflow.

### Acceptance hotfix 1.0.1

The first tester installed iTunes successfully but the Apple Device Support page stayed blocked.
The page had made one exact `Apple Mobile Device Service == Running` probe part of its completion
condition even after the driver/package probe succeeded. Detection now recognizes registry,
installed-package, driver-directory and Apple mobile-service variants; installed Apple device
support completes that page even if the service is stopped or starts only when the phone is
connected. The next page's real USB-device detection remains the authoritative gate, so this does
not let the wizard skip the prerequisite in practice.

Automated operations now draw an in-page indeterminate progress bar and a concrete activity label
for downloading/installing Apple support, downloading/installing iloader, rechecking, and opening
iloader. Buttons remain disabled while the operation is active. `:iosSetup:test` is now **12 / 12**,
including the stopped-service regression. Installer-only `ios-setup-v*` prereleases are explicitly
excluded from `update-store-source.yml`, so publishing the portable GUI cannot mutate either
SideStore feed or be mistaken for a mobile debug release.

### Acceptance hotfix 1.0.2

The same tester's machine still reported iTunes as current through winget while the service,
registry/driver-directory and current-user Store-package probes remained invisible to the setup
process. Automatic detection now also uses `winget list --id Apple.iTunes -e`, the same package
authority used by the install button, and diagnostics record each Apple probe separately. Step 3
also has an explicit **iTunes or Apple Devices is already installed** escape from detection-only
failure. This is safe because it advances only to Step 4, whose actual USB Apple-device detection
remains mandatory. Setup tests are now **14 / 14**.

### Acceptance hotfix 1.0.3

The next physical run reached iloader, which then reported **failed to connect to devices:
usbmuxd**. Windows PnP visibility is not sufficient evidence for iloader: its transport also needs
Apple Mobile Device Service's usbmuxd endpoint. The Connect iPhone page now checks both the real
USB device and `127.0.0.1:27015`, logs `usbmuxd_ready`, and will not present the normal Next path
until both are live. If Windows sees the phone but usbmuxd is unavailable, the wizard names the
problem, opens Windows Services, and embeds Apple's AMDS restart sequence. This keeps the failure
in our guided UI instead of discovering it only after iloader opens. The probe was verified against
the local physical iPhone/AMDS setup and the portable app still builds with **14 / 14** tests.

The first 1.0.3 CI attempt exposed the actual regression before publication: the GUI had replaced
the CLI's proven direct Apple `iTunes64Setup.exe` with `winget Apple.iTunes`. A package being listed
by winget did not guarantee that Apple Mobile Device Support/usbmuxd was installed and live, while
the original physical CLI run's Apple desktop installer did exactly that without a reboot. The GUI
now downloads and launches the same official Apple installer URL as the retained CLI. If Windows
sees the phone but usbmuxd is absent, **Repair with Apple's desktop installer** is the recommended
first action; restarting AMDS is the fallback rather than the happy path. The intermediate artifact
was never published as a prerelease.

The corrected portable packages passed both jobs in dedicated build run `35762793455` and were
published as prerelease `ios-setup-v1.0.3-beta.1`. The Windows ZIP SHA-256 is
`6450D2647316C99509EB59931A66300B7ED437A45FE13295B060C80586A36071`; the macOS ZIP SHA-256 is
`F688CEC67D145D9D88B18537C7D33FDFCC793452A4DDEE30026883B8664570FA`. The release was created
with the SideStore-source workflow temporarily disabled and that workflow was immediately restored
to active, so neither source feed was changed.

## iOS Debug Releases & SideStore Developer Channel (2026-09-22)

**First-Ever iOS Debug Release published & Hidden SideStore Developer Channel established.**
As part of our dual-track SideStore distribution system, iOS debug builds now publish automatically
alongside Android debug APKs, and a dedicated Developer Channel provides side-by-side sideloading.

### 1. First-Ever iOS Debug Release (debug-v0.4.13-z1.41)
- **GitHub Actions Run**: `35729744670` completed all jobs successfully (`Resolve debug version`, `Build Android debug APK`, `Build iOS Debug unsigned IPA`, `Publish to debug channel`).
- **Release**: [`debug-v0.4.13-z1.41`](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.41) (GitHub prerelease, invisible to stable updater).
- **iOS Artifact**: `Nuvio-Z-iOS-0.4.13-z1-41-debug-unsigned.ipa` (72,903,576 bytes).
  - SHA256: `5a5102889a3c613ec5ef92c8ee2a9d392df0a56c70ca0914ecc5fd4cfe70972a` (verified against `SHA256SUMS-Debug.txt`).
  - Bundle Identifier: `com.nuvio.app.z.debug` (confirmed via extracted `Info.plist`).
  - Display Name: `Nuvio Z Debug` (confirmed via extracted `Info.plist`).
  - Code Signature: Unsigned (verified no `_CodeSignature` in IPA archive).
  - Deployment Target: iOS 16.1+ (`MinimumOSVersion: 16.1`).

### 2. SideStore Developer Channel (source-debug.json)
- **Feed Isolation**:
  - `distribution/sidestore/source-debug.json`: Dedicated SideStore source containing only `Nuvio Z Debug` (`com.nuvio.app.z.debug`).
  - `distribution/sidestore/source.json`: Remains strictly stable-only (`com.nuvio.app.z`).
  - Unit test suite `scripts/test-store-source.py` added and passing; verifies bidirectional cross-talk prevention via `--expected-bundle-id`.
- **Interactive Bootstrap Integration**:
  - Windows (`setup-windows.ps1 -DeveloperMode`) and macOS (`setup-macos.sh --developer`).
  - Mandatory interactive confirmation prompt (default No) detailing:
    1. Bundle ID: `com.nuvio.app.z.debug`.
    2. Independent app container: side-by-side coexistence with separate database, preferences, and keychain partition.
    3. Apple Account slot limits: Free accounts allow 3 active sideloaded apps. SideStore (1) + Stable (1) + Debug (1) = 3/3 (all slots consumed).
  - Switches default source URL to `source-debug.json`.
  - Generates developer-styled HTML setup helper (orange accent, developer preview warning, QR code, deep links).
- **CI / Automation Isolation**:
  - `.github/workflows/debug-release.yml`: Automatically updates `source-debug.json` and attaches it to the debug prerelease.
  - `.github/workflows/update-store-source.yml`: Routes prereleases to `source-debug.json` (`com.nuvio.app.z.debug`) and stable releases to `source.json` (`com.nuvio.app.z`), with `workflow_dispatch` support.

## iOS Sideload Distribution via SideStore & iloader (2026-09-22)

**iOS distribution path added: unsigned IPA releases via SideStore/AltStore source.**
Following upstream Nuvio's distribution model, Nuvio Z now distributes unsigned iOS IPAs directly
through GitHub Releases and provides an official SideStore source feed. A fully-guided bootstrap
experience has been implemented for both Windows (PowerShell) and macOS (Bash).

### What landed

- **Guided Bootstrap Scripts**:
  - `distribution/sidestore/setup-windows.ps1`: 7-step guided terminal experience for Windows.
    Checks 64-bit OS and internet connectivity; checks and offers automatic installation for Apple
    Mobile Device drivers/iTunes; auto-detects USB connection; guides on-device LocalDevVPN setup;
    downloads and launches official `nab138/iloader`; walks through developer certificate trust and
    iOS 16+ Developer Mode approvals; generates and launches an interactive HTML helper with a crisp
    QR code and deep links (`sidestore://source?url=...`) to add the Nuvio Z source and install;
    explains 7-day wireless refreshing and in-app updates.
  - `distribution/sidestore/setup-macos.sh`: Parity implementation for macOS with native device
    detection, DMG mounting, standard OS security / Gatekeeper approval guidance (no automatic quarantine
    bypass), and HTML QR code helper launching.
- **Safety & Correctness Pass**:
  - Removed automatic Gatekeeper quarantine bypass (`xattr -dr com.apple.quarantine`); user guided through
    Apple's standard Privacy & Security approval flow.
  - Clarified pairing file lifespan: corrected UI/docs to state that the computer is normally only needed
    for initial setup, but pairing files can expire (iOS updates, device resets, or Apple lifecycle), requiring
    re-pairing via iloader.
  - Explicit Wi-Fi requirements: both scripts and docs explicitly state that an active Wi-Fi connection on
    the iPhone is strictly required (cellular data alone is not supported for SideStore loopback).
  - Release-time bundle ID invariant: `scripts/update-store-source.py` strictly validates extracted IPA
    `CFBundleIdentifier` against `--expected-bundle-id` (`com.nuvio.app.z`), failing loudly on mismatch.
  - Sideload source workflow audit: confirmed recursion safety, validated `contents: write` permissions,
    added `[skip ci]` to metadata commits, and verified fail-visible behavior on branch protection rejections.
- **SideStore Source Metadata**:
  - `distribution/sidestore/source.json`: Canonical AltStore/SideStore source feed for Nuvio Z
    (`com.nuvio.app.z`), configured with `sourceURL` for automatic app updates.
- **Source Sync & Release Automation**:
  - `scripts/update-store-source.py`: Updated to target `distribution/sidestore/source.json` by default,
    updating versions, checksums, and app permissions from compiled unsigned IPAs.
  - `.github/workflows/android-release.yml`: Release publication now publishes signed Android APKs
    alongside the unsigned iOS IPA, `SHA256SUMS-iOS.txt`, `setup-windows.ps1`, and `setup-macos.sh`.
    Unsigned IPA compilation (`ios_unsigned`) runs in all modes (`build-only`, `dry-run`, `publish`).
  - `.github/workflows/update-store-source.yml`: Updated to synchronize `distribution/sidestore/source.json`,
    commit feed updates to `main`, and attach `source.json` as a release asset on published releases.
- **Documentation & User Guide**:
  - `distribution/sidestore/README.md`: Comprehensive walkthrough covering architecture, prerequisites,
    step-by-step installation, 7-day refreshing, updates, deep links, security guarantees, and FAQ.
  - `Docs/RELEASES.md`: Updated to document the SideStore unsigned IPA distribution model.

## Phase 7 closeout: Release Engineering v1 (2026-09-22)

**Phase 7 is complete.** The superseding road-map scope is live-desktop release hardening plus
joint Android/iOS release readiness; Tizen and webOS distribution moved to Phase 9 (the TV phase, renumbered Phase 10 on 2026-09-24). Desktop keeps
its installed-user version/serial/MSI lineage. Android and iOS share the mobile marketing version
and build number. The policy and channel semantics are unified without forcing the two families
onto equal numbers. The operational runbook is `Docs/RELEASES.md`. No stable release, tag,
TestFlight upload or updater-visible artifact was created.

### What landed

- Stable and debug updater channels now fail closed. Stable rejects drafts, every prerelease,
  `debug-v*`, malformed tags and missing/excess serials; debug requires both a prerelease and the
  debug tag grammar. Equal/lower serials remain non-updates, so rollback is forward-only.
- `Build Mobile Release` has real `build-only`, `dry-run` and `publish` modes. Build-only/dry-run
  produce four metadata-checked ABI APKs, checksums, release notes and an unsigned iOS verification
  IPA. Publish requires both signing families, uploads iOS to TestFlight first, and only then may
  create the stable GitHub APK release. There is no Android-only stable or public unsigned-IPA path.
- Android release signing is explicit: all keystore fields or none; partial configuration fails.
  Credential-free verification requires `-Pnuvio.android.unsignedRelease=true`. Stable remains
  `com.nuvio.app.z`; debug remains isolated at `com.nuvio.app.z.debug`.
- iOS is now `Nuvio Z`, stable bundle `com.nuvio.app.z` with matching widget identity, and the
  debug `.debug` family. The historical team and signing identity were removed from source. The
  unsigned packaging script validates version/build/bundle/arm64/widget/signature; the TestFlight
  script provides the automatic-provisioning archive/export/upload seam without storing credentials.
- Release guards cover source branch/ref, clean checkout, duplicate tag/release, final bump,
  release notes, missing/partial secrets, stale outputs, artifact count/name/metadata/signature,
  and forbidden IPA publication. The first rehearsal caught and fixed the x86 glob also matching
  x86_64 (`f64f3c602`).

### Verification and artifacts

- pure suites: **8/8, 788 tests** on mobile; **8/8, 778 tests** on desktop
- mobile `:composeApp:testAndroidHostTest`: **2286 / 0 failures**
- desktop `:composeApp:desktopTest`: **2427 / 0 failures**, BUILD SUCCESSFUL
- Android debug: `androidApp-full-debug.apk`, `com.nuvio.app.z.debug`,
  `0.4.13-z1.40` / versionCode `125040`
- Android release: four explicit unsigned ABI APKs, `com.nuvio.app.z`,
  `0.4.13-z1` / versionCode `125`; signing-ready and locally verified unsigned
- desktop local: stable `Nuvio-Z-Windows-x64-0.1.23-alpha-z6.msi` at ProductVersion
  `2.0.131`; debug `Nuvio-Z-Debug-Windows-x64-0.1.23-alpha-z6.61.msi` at `1.45.61`
- desktop build-only CI `35666931626`: Windows x64 MSI, macOS arm64 DMG, macOS x86_64 DMG,
  release notes and consolidated checksums all verified; publish job skipped
- mobile build-only CI `35710339961` (active run on `ae9324ec6` with bounded 3G/4G memory and 120m timeout, superseding `35702518407`): four Android ABI artifacts plus checksums and release notes verified; TestFlight/GitHub publication jobs skipped; unsigned iOS verification IPA in flight
- both release workflows pass `actionlint`; release metadata and iOS shell scripts pass their
  syntax/contract checks

### Credentials and next phase

The existing Android CI keystore secret produced signature-verified build-only APKs; no signing
material was exposed or committed. Apple still requires the Developer team id, distribution
certificate/password, and App Store Connect key/id/issuer plus the app/identifier/capability
records. Those values and the exact account-side TestFlight steps are listed in
`Docs/RELEASES.md`; none is committed. Phase 8 owns Apple credentials, the first signed upload,
processing/group/beta-review steps, physical iPhone QA and launch fixes. Missing Apple access did
not block this credential-neutral Phase 7 seam.

## Phase 6 closeout: DONE WITH NON-BLOCKING QA DEBT (2026-09-21)

**Phase 6 (Social + Watch Together to mobile) is closed.** The mobile responsive/UI pass was
physically tested on the S25 and accepted by the maintainer. Together with the Android↔desktop
hardware runs of 2026-09-20/21, that meets the Android gate, with the lifecycle rows below carried
as debt by the maintainer's call. Ledger: `Docs/Z-FEATURES.md` revision 11. Roadmap and plan
ledger updated. **No code changed in the closeout.**

### Deliverables

| Deliverable | State |
| --- | --- |
| A - shared-code inventory | complete (`Docs/PHASE-6-CONVERGENCE-INVENTORY.md`) |
| B - mobile repointed to the Z backend | complete; the formal `/security-review` was never recorded. A light pass at closeout found no secrets in the tree, and the Z session stays memory-only and derived from the official one. The formal review moves to Phase 7's backend `/security-review` |
| C - Social runtime on mobile | complete, on hardware (S25) |
| D - shared Watch Together core | complete (the convergence merge) |
| E - mobile player contract | Android complete (`samplePositionMs`, `seekToExact`, `engineReadiness` on media3 and libmpv). iOS: `seekToExact` done, `engineReadiness` not reported (falls back to buffered-ahead) - Phase 8 |
| F - Android Watch Together | complete; background behaviour decided and built as Away (S10), verified in `z1.40` |
| G - iOS Watch Together | builds in CI (green on `8e38804f8`); `NowPlayingController.swift` still bypasses the party transport - Phase 8 |
| H - phone UI | complete, accepted on the S25 |
| I - cross-platform matrix | partial, closed as QA debt (below) |
| Backend drift | resolved in `nuvio-z-backend` `62673cf`/`cc81430` (only the enum ordinal order of `watch_party_ready_state` differs, deliberately) |

### Desktop -> mobile parity audit

Method: `scripts/shared-code-drift.sh desktop/claude/mobile-ui-responsive-pass --expected`, every
differing `commonMain` file read line by line, then every desktop commit since 2026-09-10 not
mirrored on mobile checked for what it touched. **No desktop feature is missing on mobile.** Of the
26 differing `commonMain` files, all are mobile ahead (the phone surfaces, `PlayerExternalTransport`,
the notification-by-id fix, `socialRuntimeProfileId`, `MemorySessionManager`,
`playerMayOfferSourceList`, updater channel refactor, the details menu) or a platform adaptation
(desktop's `onPointerEvent` / mouse back-button code behind `platformPointer*` expect/actuals,
`atomicfu` instead of `@Volatile`/`System.nanoTime`). Desktop's `androidMain` copy is stale by design:
desktop has no Android target.

| Area | Result |
| --- | --- |
| Playback: shared state, modes, source selection/realization/switching, loading surface, failure chain, watchdog, next episode (P12), language decision (S14-S16), resume | shared, identical `commonMain`, exercised by Android |
| Playback: engine contract | Android implements the full contract incl. engine-native readiness; iOS partial (above) |
| Playback: premature end-of-stream guard (P18), mpv readiness bridge, native player handoff, Next Episode button (P11), wide quality columns (P17) | desktop/mpv only by design; Android has its own ExoPlayer/libmpv equivalents where needed |
| Downloads | shared queue/state/presets/ranking; Android has its own downloader and stall watchdog (D11); the desktop E2E harness (D15) is desktop infrastructure; iOS notification hook is the known D12 gap |
| Social | same shared implementation, on hardware (S25) |
| Watch Together | same shared implementation; Android↔desktop on hardware both directions; mobile owns its lobby, room rail, status pill and route |
| Onboarding / settings | the same wizard (revision 9, Sources step included), Social opt-in and ordered shutdown (W8/W9) shared and wired through `MainAppContent`. **Gap:** the ledger said iOS adapts the Sources step out (W10); it does not - `setupWizardSteps` keeps it everywhere. Phase 8 |
| Metadata / UI | shared (logos, loading surface, cards, window classes); the details screen is a deliberate `MetaDetailsScreen` divergence |
| Updater | mobile ahead (pure `isChannelEligible`); desktop's MSI install path is desktop-only |

**Reverse drift (mobile -> desktop), not fixed:** desktop's in-player social card still acts on the
*first* unread notification with actions, while the card itself skips join requests - so with a
join request pending, Accept on a friend request's card would let the requester into the party.
Mobile fixed this (by notification id, `inPlayerSocialCardNotification`). Desktop is closed for
feature work; this is a small bug fix for the next desktop touch.

### QA debt carried out of Phase 6

- Android↔Android party (S20+/S25+); doze after 10+ minutes; an incoming call; Wi-Fi↔cellular handover.
- Natural source-failure paths: host failover, guest compatible and incompatible fallback.
- iOS: all physical verification; the two adapter items above; W10's iOS Sources step.
- Join-request "Let in" hidden while the player chrome is hidden; a solo party's pill reading
  "Reconnecting to the party..." while polling carries it.

### Verification at closeout

- mobile pure suites: 8 / 8 groups, 788 tests, OK
- mobile `:composeApp:testAndroidHostTest --rerun`, results dir cleared first: **2278 / 0 failures**
- mobile `:androidApp:assembleFullDebug`: BUILD SUCCESSFUL (`0.4.13-z1.40`, unchanged)
- desktop `:composeApp:desktopTest`: **2425 / 0 failures**, BUILD SUCCESSFUL
- shared-code drift vs `desktop/claude/mobile-ui-responsive-pass`: 68 differing files, all read; none is a desktop change mobile lacks
- iOS: CI `iOS build` green on the closeout HEAD `8e38804f8` (run 35648673700, dispatched by hand at wrap-up because the UI-pass commits touch only `commonMain`, which the workflow's path filter does not watch). Mobile `CI` green on the same commit (run 35648657705). Desktop `CI` on `f7e1d841` fails in `buildNativeLinux` (`frame_copy_test`) exactly as it has on every push since before Phase 6 - pre-existing, not a Phase 6 regression; its Windows MSI job passes

Next: **Phase 7 - identity and release engineering** (`ROADMAP.md`). Not started.

## Details: Watch Together folded into the three-dot menu (2026-09-21)

The phone details screen still carried the old standalone "Watch Together" button above the
actions row. It is now the last entry of the play button's three-dot menu (People icon,
`watch_party_title`), matching `DesktopDetailHero`. Plumbed as `onWatchTogetherClick` through
`configuredMetaSectionItems` -> `ConfiguredMetaSections`; hidden when `onWatchTogether` is null.
Mobile only; desktop's `MetaDetailsScreen` was not touched. Built and installed on the S25
(`0.4.13-z1.40`); the maintainer checks it on device.

Follow-up: with four actions the expanded row squeezed Play to "Re..." on a portrait phone.
`DetailActionButtons` (upstream-owned) now keeps Play at >= 128dp; when the full-size row does
not fit, the circles shrink (floor 40dp) and gaps drop to 8dp, interpolated with the menu's
open progress so the collapsed row is unchanged. Rows that already fit (tablet, desktop, wide
landscape) are untouched.

## Mobile UI pass, stages 5-10: phone lobby and phone Social, render-verified (2026-09-21)

Stages 5-8 of `../HANDOFF-mobile-ui-responsive-pass.md` landed. **Presentation only** - no change
to the realizer, sync, the barrier, host authority, transport health, the session state machine,
source matching, RPCs or social behaviour; callbacks were moved, not redefined. Nothing pushed,
no version bump: `androidApp-full-debug.apk` is `0.4.13-z1.40` / versionCode 125040, the same
as the last hardware APK, so `adb install -r` accepts it over that install.

**Structure first.** Both screens now split into a state-gathering wrapper and a stateless layout,
so the desktop render harnesses compose the real thing instead of a replica:
`WatchPartyLobbyFrame` + `PartyLobbyContent(PartyLobbyModel, PartyLobbyActions)`, and
`SocialFeed(SocialFeedModel, SocialFeedActions)`. `SocialRenderHarness.SocialFeedSceneBody` (the
hand-built replica the handoff warned about) is gone.

**Lobby** picks one of four compositions from `nuvioWindowClass()`:
- wide and not short -> the desktop two-pane (old code, moved verbatim)
- short and not compact-width -> **landscape phone**: left region (hero, invite card capped at the
  pane, notices), right region (participant rows, host settings), each scrolling on its own, over
  a pinned full-width action strip
- compact width -> **portrait phone**: 64x96 hero with the stage rail as four dots naming only the
  current step, invite code as its own card, participant rows (name ellipsizes, pill keeps its
  width), one-line expandable addon notice, host switches beside their explanations, pinned
  action bar with Change source as a text button beside the host sentence
- anything else -> the tablet column (old code, moved verbatim)

**Social** gets a phone density chosen inside `socialFeedMetrics` from the window's shape
(`isPhoneSurface`), never from the nav style: label-style section headings, a row-shaped Watching
Now card (~100dp, was ~150dp+ stacked), 8dp rhythm and 16dp margins, a header that slims on scroll
(latched on the feed's own list so it cannot oscillate) and stays slim in landscape, the roster
first and folded to three with search under it and privacy behind a disclosure, and join-by-code
as a one-line disclosure at the end. Landscape needed nothing else: the existing grid gives two
Watching Now and two activity columns at 891dp.

**Verification**
- mobile `:composeApp:testAndroidHostTest` **2278 / 0 failures** (2277 + `densityFollowsTheWindowsShapeNotItsWidthAlone`); pure suites 8/8
- desktop `:composeApp:desktopTest` **2425 / 0 failures** after the cherry-picks (the only desktopMain compile); `NavigationBarRenderHarness`, `SocialRenderHarness` and `WatchPartyLobbyRenderHarness` all inside it
- renders looked at: lobby 5 fixture parties x 411x914 / 891x411 / 360x780 / 320x600 / 800x1280
  plus desktop 1280x820 and 1920x1080; Social typical/full/empty at the same phone sizes
- desktop Social scenes (1280-3840, and Home's shelves at every size) **byte-identical** before and
  after the redesign; only the 420dp scene changed, which is the phone path
- **Not on a device.** No phone was attached over adb (USB or wireless) for this session, so the
  consolidated physical pass - the plan's rows 1-11 plus nav/insets - is still owed. The APK below
  is built from the final state and is ready for it.

**Defects the renders caught before commit:** the invite code ellipsized at 320dp (the Copy pill's
label now drops below a 340dp card, the code never does); the not-yet-reached stage dots were
invisible (`outline` on a phone card); the compact error notice was red on pale grey over the
backdrop (now grounded on the card surface).

**Deferred / known:** join-request "Let in" still hidden while the player chrome is hidden (left
alone, per the maintainer); the solo-party "Reconnecting to the party…" pill is sync scope; the
desktop title rail in the lobby harness is a stand-in (it fetches metadata over the network).

Commits - mobile: `06479f313` (stage 5), `b2a18ce8e` (6), `e394abd6c` (7a refactor), `b17110ff0`
(7b), `3839d94a4` (8), `734fda8c2` (test fixture). Desktop: the same six by cherry-pick
(`d79c88d6`, `d098792d`, `7692602a`, `91a0ff21`, `e296258f`, `93537c00`) plus the harnesses
`c757619e`.

## Mobile UI pass, stages 2-4: first device pass, three defects fixed (2026-09-21)

The responsive/stabilization pass (`../HANDOFF-mobile-ui-responsive-pass.md`, stages 0-4 of 10)
was put on the S25 (411x891dp, and 360dp via `wm density 320`) for the first time. Nothing pushed,
no version bump; the hardware APK is the ordinary `androidApp-full-debug.apk` (`0.4.13-z1.40`).

**Passed on device:** Social portrait/landscape insets (header under the status bar, list end
clears the nav, landscape cutout side padded); lobby portrait/landscape insets; nav at 411
(six labels, no clipping) and 360 (clean icon-only); landscape uses upstream's
`TabletFloatingTopBar`, whose labels are complete; party pill compact placement, gesture
hand-off (pill drops below the volume pill, no overlap) and the dot-for-tone treatment.

**Found and fixed** (mobile `62769eddb`, `bebe2df44`, `b5551e112`; desktop `931cbfad`,
`e53c0dfc`, `666de3cb`):

1. **Nav labels gone for good after one scroll.** The fit check fired mid-collapse (padding
   animates 12->58dp under still-drawn labels) and latched the demote. Now asked only at
   `labelFraction == 1`. A new harness scene drives the collapse/expand and fails without the fix.
2. **Lobby rows crushed on a phone.** "Wait for everyone" switch off the card; "End session" one
   letter wide. `LeadingBesideTrailingRow` wraps by measurement; wide layouts unchanged.
3. **Landscape party pill with buttons would land on play/pause** (render harness had no
   transport). Short surface + chrome up: pill at 64dp, buttons inline, one row.

**Not verified live:** the join-request pill (needs a second participant); judged from renders.
**Seen, not touched (sync scope):** a solo party's pill read "Reconnecting to the party..." for
the whole session, with realtime at `subscribed-unverified` and polling carrying it.

## Away return: fixed for the right reason this time, and verified on the phone (2026-09-21)

**The third attempt at this, and the first with evidence instead of a theory.** `z1.38` and `z1.39`
each fixed a real defect in this path and neither fixed the bug, because both were reasoned from
the code rather than from the device. This one was diagnosed on a live S25 over adb and then
verified on it. Cut as Android **`0.4.13-z1.40`** (versionCode 125040) against desktop
**`z6.61`** (MSI ProductVersion 1.45.61) - debug channel only, stable untouched.

### What the device actually said

With `0.4.13-z1.39` installed and a party live, `adb logcat` showed:

```
11:48:02.253  presence Watching -> Away reason=lock
                     ... nothing at all for eight minutes ...
11:56:52.944  presence Away -> Watching reason=foreground     <- only when the app was foregrounded again
11:56:54.725  peer publish ... away=false
```

Three facts settled it, none of which could be read off the source:

1. **`reason=lock`**, so the stuck fact is `screenLocked` and not `appForeground`.
2. **The receiver was registered the whole time.** `dumpsys activity broadcasts` on the live pid
   showed the filter holding `SCREEN_OFF`, `SCREEN_ON` **and** `USER_PRESENT`. Nothing had torn
   down, so every "the composable was disposed" theory was wrong.
3. **`USER_PRESENT` never arrived anyway.** The process is cached while the screen is locked, and
   broadcasts to a cached process are dropped. This is exactly the case the `ON_START` keyguard
   re-read was written for on 2026-09-20 - and it missed it too, because the only `ON_START` of an
   unlock-and-return fires *during* the keyguard dismiss animation, where `isKeyguardLocked` still
   answers `true`.

Foregrounding the app again minutes later - a *second* `ON_START`, with the keyguard long settled -
cleared it instantly. That is the whole bug in one line: **it cleared only by accident of timing,
and an unlock-and-return never supplies that accident.** "Sometimes" was whether a second
foreground happened to occur.

### The fix

`ON_RESUME`. An activity cannot be RESUMED behind the keyguard - this app sets `showWhenLocked`
nowhere, and a picture-in-picture window is paused rather than resumed - so **being resumed is proof
the lock is gone**. No keyguard read to get wrong, no broadcast to be dropped, and it arrives after
the dismiss animation rather than during it. It is the same kind of fact as `USER_PRESENT`, in the
one channel a cached process cannot lose.

`partyScreenLockedOnForeground` now takes `resumed`, so both callers keep their rule in the pure
file: resumed clears unconditionally; started remains the weaker second chance that may clear a
lock but never declare one. The two are deliberately not merged - they carry different evidence.

### Verified on hardware, not just in tests

Local build installed to the S25 over adb, party rejoined against the desktop:

```
12:21:29.924  presence Watching -> Away reason=lock          away roster [d3397924]
12:21:36.059  presence Away -> Watching reason=foreground    away roster []
12:21:36.149  away return catchup targetMs=171546 localMs=171566 reachable=true
12:21:43.305  presence Watching -> Away reason=lock
12:21:46.252  presence Away -> Watching reason=foreground    away roster []
12:21:46.317  away return catchup targetMs=178220 localMs=178219 reachable=true
```

Two lock/unlock cycles, two returns, roster cleared both times, and the catch-up landed 20ms and
1ms from target on the existing source (`reachable=true`) rather than re-realizing. A third lock is
in the log with no return because the phone was still locked when the capture was taken.

**The starve-recovery policy was confirmed on the same run**, which is what it was cut for:
`drift ... action=TEMPORARY_SPEED ... starveRecovery=true` with the gap closing 806 -> 727 -> 668
-> ... -> 108ms. No seek, no loop.

**Tests.** 8/8 pure groups; `:composeApp:testAndroidHostTest` **2264 tests, 0 failures**;
`:androidApp:assembleFullDebug`. `PartyPresenceTest` gains the unlock the device actually performed
- no `USER_PRESENT` at all and an `ON_START` whose keyguard read is wrong - which ends Watching only
because of the resume.

### One trap worth writing down

A locally built debug APK ships with **no Z backend** unless `local.properties` carries
`NUVIO_Z_SUPABASE_URL` and `NUVIO_Z_SUPABASE_PUBLISHABLE_KEY`. CI injects them from
`NUVIO_LOCAL_PROPERTIES_BASE64`; a dev machine may not have them, and the build succeeds anyway.
The app then reports **"social features are not enabled on this server"** and no party can be
joined - which is a missing key, not a regression. `nuviozdesktop/local.properties` has both.
Check `composeApp/build/generated/runtime-config/.../SupabaseConfig.kt` for
`ZSupabaseConfig.isConfigured` before blaming the build.

## The z1.38/z6.59 run: the unlock race, and the seek that was eating the buffer (2026-09-21)

Two findings from the `0.4.13-z1.38` / `z6.59` hardware run. Cut for retest as Android
**`0.4.13-z1.39`** (`debug-v0.4.13-z1.39`, versionCode 125039) against desktop **`z6.60`**
(`debug-v0.1.23-alpha-z6.60`, MSI ProductVersion 1.45.60) - debug channel only, prereleases, the
stable updater and the release line untouched. Only the debug counter moved to cut them.

### 1. Unlock cleared Away only sometimes, because the fix left the same bad read in a second place

Reported as "coming back from lock screen *sometimes* clears away but its inconsistent", which is
the shape of a race rather than a wrong rule - and it was one. `partyScreenLockedAfter` was fixed
on 2026-09-20 so that `USER_PRESENT` means unlocked without re-reading the keyguard. The
`ProcessLifecycleOwner` observer was not: `ON_START` still did `screenLocked = isKeyguardLocked`,
the same read, through a different door.

Unlocking delivers `ACTION_USER_PRESENT` and the process foreground at nearly the same instant, in
**no guaranteed order**. `USER_PRESENT` last cleared the lock and the member returned; `ON_START`
last re-read a keyguard still playing its going-away animation, got `true`, and the member stayed
Away. The same unlock on the same phone went both ways depending on which landed last, which is
exactly "sometimes".

`partyScreenLockedOnForeground(heldScreenLocked, keyguardLocked) = held && keyguard` - **one
direction only**, the same rule shape as `partyStaleAwayNeedsClearing`. A foreground may *clear* a
lock that is no longer there, which is the whole of what the second chance was for (a broadcast
dropped while the process was cached); it may never *declare* one. Both orderings now converge.

**Why the tests passed and the phone did not.** `PartyPresenceTest` modelled the unlock only as a
broadcast, and never modelled `ON_START` arriving after `USER_PRESENT` - so the one ordering that
failed was the one nothing exercised. `unlockReturnsWatchingWhicheverOfForegroundAndUserPresentLandsLast`
runs the cycle both ways round with the keyguard answering `true` at every read.

### 2. The buffer -> seek -> buffer loop is two constants, not a detection limit

Reported as the laptop taking "a couple seconds to realise the android is struggling to buffer" and
keeping "the buffer -> sync ahead -> buffer loop going", with the reasonable guess that this is just
how fast buffering can be detected. **It is not.** The couple of seconds is
`WatchPartyGuestBufferingGraceMs = 2_500`, a deliberate choice so a routine one-second rebuffer does
not stop the film for everyone. The loop is a different thing:

- a guest takes a corrective **seek** once it is `WatchPartySeekThresholdMs = 1_000` behind;
- the host only **holds** the party after 2.5s of *continuous* buffering.

So any rebuffer between 1s and 2.5s guaranteed a seek while the host was still deciding whether to
wait - and a seek discards the buffer that had just been rebuilt, which starved the stream again.
Self-sustaining, and the host's hold never fired to break it because no single rebuffer lasted long
enough. The two constants were chosen independently and nothing stated their relationship. This is
the same family as the bug already recorded on `WatchPartyGuestBufferingGraceMs` itself, where it
being *equal* to the guest's own hold made the host pause and resume on every corrective seek.

**A bounded recent-starvation recovery policy**, inside the existing drift machinery rather than
beside it. `DriftTracker` gains `starvedAtMs`, recorded by `followPartyTick` before any of its
guards return - by the time a gap is measurable the starve is over, so asking "is this buffering
now" always answers no. While recovering, an ordinary corrective seek becomes a nudge instead.

Bounded three ways, because none of these may be suppressed indefinitely:

- `WatchPartyStarveRecoverySeekSuppressionMs = 20_000` - the window, sized so the nudge can
  actually finish: at 10% max rate, absorbing the ~2.5s the host tolerates takes ~25s.
- `WatchPartyStarveRecoverySeekOverrideMs = 3_000` - drift past which it seeks anyway. Deliberately
  just **above** the host's 2.5s grace, so the two mechanisms hand over instead of both standing
  down. `theStarveOverrideStaysAboveTheHostsBufferingGrace` asserts that ordering, because the pair
  being ordered the wrong way round is the entire bug.
- A taken seek clears the starve; the buffer it was protecting is the one that seek just spent.

**Nothing else is suppressed.** Commanded seeks, host scrubs, source-generation changes and Away
returns are scheduled through `partySeekPlan` against the barrier, and `followPartyTick` returns
before the tracker is consulted at all - the guard is structural, not a condition that could be got
wrong. `aCommandedSeekIsUnaffectedByAStarve` pins it. The host's 2.5s hold is untouched.

**Verified here.** 8/8 pure groups; `:composeApp:testAndroidHostTest` **2262 tests, 0 failures**
(2254 before this change, on the full distribution); `:androidApp:compileFullDebugKotlin`.

Note on that count: the plain `:composeApp:testAndroidHostTest` builds the **playstore**
distribution and reports **2248**, because `androidFullHostTest` - `AndroidUpdateChannelTest` and
`PluginScraperCodeFileStoreTest`, 6 between them - is not in that variant. Running it alongside
`:androidApp:compileFullDebugKotlin` selects `full` and reports 6 more. Both numbers are honest;
they are different suites, and a 2248 is not a regression against a 2254.

**Still needs hardware.**

- Lock and unlock repeatedly with the party live. The point is the *repetition*: one success never
  distinguished these two orderings.
- A guest on a struggling source: the loop must stop. `drift ... action=` should show
  `TEMPORARY_SPEED` with `starveRecovery=true` after a rebuffer, not `SEEK`.
- A guest that falls genuinely far behind must still seek, and the host's hold must still fire.
- A host scrub and an Away return while a guest is freshly rebuffered: both must still seek at once.

## Phase 6 Away hardware run: three defects found and fixed, none verified again yet (2026-09-21)

The `0.4.13-z1.37` / `z6.58` device run passed on almost everything Away was written for - ordinary
background and return, PiP without a flap, the away hold, a host going away, Play anyway, and the
source surviving a backgrounding. Two things failed; chasing the first one turned up a third
that nobody had reported. **Cut for hardware as Android `0.4.13-z1.38` (`debug-v0.4.13-z1.38`,
versionCode 125038) against desktop `z6.59` (`debug-v0.1.23-alpha-z6.59`, MSI ProductVersion
1.45.59) - debug channel only, prereleases, the stable updater and the release line untouched.
Nothing in the fixes moved to cut these builds; the only change is the debug counter. None of the
three defects has been re-tested on a device yet.**

### 1. Screen-lock return got stuck Away, and `USER_PRESENT` was being second-guessed

Reproduced live on the S25 through `adb`, with the party still up: lock, and
`presence Watching -> Away reason=lock` is correct. Unlock, and **nothing** - no transition, no
publish - while `dumpsys` says `isKeyguardShowing=false`, the app is `topResumedActivity` and
`USER_PRESENT` was broadcast. The receiver was alive with all three actions registered, and it had
demonstrably just received `SCREEN_OFF`, so the broadcasts were arriving and the *answer* was wrong.

`ACTION_USER_PRESENT` re-read `KeyguardManager.isKeyguardLocked`. On this device that read returns
`true` while the keyguard is still playing its going-away animation. `screenLocked` latched there,
nothing else was coming to correct it, and `screenLocked` outranks `appForeground` in
`partyPresenceFor` - so the member stayed Away for the rest of the session with the film in front of
them.

`USER_PRESENT` **is** the fact: it is broadcast for exactly one reason. It now means unlocked, full
stop. `SCREEN_ON` keeps the keyguard read, which is the one honest question and the only signal a
device with no lock set will ever produce. The mapping moved into `PartyPresence.kt` as
`partyScreenLockedAfter`, so the adapter still decides nothing and the rule is executed by the pure
suites. `ON_START` also re-reads the keyguard now, as a second chance at a lock fact that went
missing - a broadcast dropped while the process was cached, an unlock straight into the app.

### 2. The away flag outlived its party, so every publish said `away=true`

Found in the same logs and easy to miss: **every** `peer publish` in the buffer carried `away=true`,
across two consecutive parties, including while the phone was demonstrably in a hand and playing.
The live repro settled which way round it was - the runtime logged `Watching -> Away` on lock, so it
had thought itself Watching all along while the wire said otherwise.

`WatchPartySyncTransport.selfAway` deliberately survives a channel reset and a generation change,
which is right: a reconnect does not put the phone back into a hand. But that left a return and
nothing else able to clear it, and a *new* party cannot produce a return - a fresh player composes
as Watching, and a presence that never transitions never calls `setLocalPresence`. So a member who
went away, left, and joined again reported away forever.

Two cheap closures, both in `PlayerWatchPartyEffect`: leaving a party clears it
(`presence cleared reason=left-party`), and a member holding `Watching` while the transport still
reports away withdraws it (`presence reconciled away=false`). **One direction only** -
`partyStaleAwayNeedsClearing` refuses to *declare* an absence, because an away that reached the wire
without `enterPartyAway` would have no generation key captured at away-time and no retained intent,
and the return would have nothing to compare against or restore.

### 3. A host changing source in the native controls told nobody

Separate bug, same run. The desktop host opened Change Source, picked another source, loaded and
played it; the Android guest stayed on the old source completely and went on syncing its timeline
against a host that had left it.

`"selectSource"` in the native/HTML player-controls handler called `switchToSource(stream)`. The
Compose sources panel calls `switchToUserSelectedSource(stream)`, and that is the one that calls
`publishPartySourceChange` and advances `sourceGeneration`. Two panels, one person, two functions.
Nothing threw and nothing logged: `switchToSource` is a legitimate call from a dozen automatic
paths, and the panel looked like one more.

The handler now routes through `switchToUserSelectedSource`. **Base `switchToSource` still publishes
nothing** - automatic retries, party adoption, credential re-mints and debrid re-resolution all
reach it, and none of them is somebody choosing what everyone watches.

**P2P had the same bypass, and one of its own.** The native consent continuation
(`enableP2pForPlayerControls`) called `switchToP2pSourceStream` directly, so a host enabling P2P for
a hand-picked torrent hit the identical silence one dialog further along. The Compose path had the
inverse defect: `switchToUserSelectedSource` published *before* the dialog, so cancelling moved the
whole party onto a source nobody started. Both are now the same rule - a pick that stops at the
consent dialog has not happened yet, and `switchToUserSelectedSourceAfterP2pConsent` publishes it
when the dialog is answered. The automatic chain reaches that dialog too and still stays local;
`PendingPlayerP2pSwitch.userSelected` is what tells them apart.

**Play-anyway needed no fix.** The desync followed a phone that was falsely Away, so the host was
pressing Play anyway at a member that had never left. The existing Away return catch-up
(`partySeekPlan` plus the barrier park) is the mechanism for it, and it was never reached because
the return never happened. It stays on the hardware list rather than in the changelog: if it
reproduces once lock return works, it is a real second bug.

**Tests.** `PartyPresenceTest` gains 9: the three screen signals, the whole lock/unlock cycle run
with the keyguard answering `true` at every single read - which is what the S25 did, and is the
assertion the shipped build fails - and the four reconciliation cases including a PiP watcher, which
withdraws a stale away because the PiP exception outranks both away rules. `PlayerSourcePickRouting-
Test` (6) is a source-contract test, deliberately: the defect it exists for is invisible to every
other kind of test, since both functions compile, neither throws, and the difference is only which
one talks to the party. It pins both panels, both consent continuations, and that `switchToSource`
and the automatic consent branch stay silent. It lives in `androidHostTest` here and `desktopTest`
on the desktop side - the one deliberate divergence in this change.

**Verified here.** Mobile: 8/8 pure groups, `:composeApp:testAndroidHostTest` **2254 tests, 0
failures**, plus `:androidApp:compileFullDebugKotlin`. Desktop: 8/8 pure groups,
`:composeApp:desktopTest` **2397 tests, 0 failures**. All shared Watch Together and player files are
byte-identical across the two repos (`diff --strip-trailing-cr`).

**Still needs hardware, and nothing here has had any.** The list below is what
`z1.38`/`z6.59` were cut to answer.

- Lock and unlock with the party live, on the S25: `presence Away -> Watching reason=foreground`
  must appear, and the peer publish after it must say `away=false`. This is the one that was
  reproduced failing, so it is the one to run first.
- Leave a party while away, join another: the first `peer publish` of the new party must say
  `away=false`.
- Host Change Source, desktop host to Android guest, through the **native** controls: the guest must
  reach "Host source changed", re-realize and resume through the readiness barrier.
- The same pick through the Compose panel, to confirm it was not disturbed.
- A P2P source picked with P2P disabled, accepted **and** cancelled, on both panels: accepting must
  move the party, cancelling must move nobody.
- Play anyway after a *correct* away and return, to see whether the residual desync survives the
  lock fix at all.

## Phase 6 Away lifecycle - PUBLISHED FOR HARDWARE, UNVERIFIED ON HARDWARE (2026-09-20)

Mobile `a1cb8afd` on `claude/phase-6-convergence-linear`, desktop `f7bec859` on
`claude/heartbeat-session-renewal`. Cut for the device run as Android **`0.4.13-z1.37`**
(`debug-v0.4.13-z1.37`) against desktop **`z6.58`** (`debug-v0.1.23-alpha-z6.58`) - debug channel
only, prereleases, the stable updater untouched. Ledger: **S10** in `Docs/Z-FEATURES.md`.

Nothing in the Away implementation moved to cut these builds; the only change is the debug counter.

**What it fixes.** `PlayerEngine.android.kt` pauses the engine at `ON_STOP`, so a member who pressed
Home reached the party as a member that had stopped reporting `playing` - indistinguishable from a
stalled stream. The host's stall guard held the party for them and abandoned them at
`WatchPartyStallHoldMaxMs`. `ON_START` then restored `playWhenReady`, so they resumed at the frame
they were frozen at and the drift tracker dragged them forward through a seek. None of that was a
fault in the stall guard; it was a fact the party was never told.

**Where it lives.** `features/watchparty/PartyPresence.kt` - import-free, executed by the pure
suites - holds every decision. `PartyLifecycleMonitor` (expect/actual, three actuals) reports
platform facts and decides nothing. The runtime folds them in `PlayerWatchPartyEffect`.

**The three things most likely to be wrong on a device, and how they are guarded.**

1. **PiP ordering.** The facts arrive as a *snapshot with a monotonic sequence*, not as events.
   Entering PiP delivers a pause, a configuration change, a mode change and on some devices a stop,
   in an order that is not guaranteed; read together the PiP exception cannot be got wrong, and a
   late callback carrying an older sequence is dropped whole.
2. **Source preservation.** Nothing on the away or return path touches the realizer, the route, the
   readiness row or the descriptor. `partyReturnAction` compares the generation key captured at
   away-time with the current one; unchanged means catch up, changed means the existing realization
   flow owns it.
3. **Hold cross-talk.** The away hold has its own reactor and its own retained list
   (`partyAutoPausedForAway`), never the stall guard's. A user transport command and "Don't wait"
   clear both; nothing else can clear one from the other.

**On the wire.** Two additive optional fields, **no protocol bump**. A guest reports its own
presence on its peer status (`aw`); the host aggregates and republishes the roster on its tick
(`away`, absent unless somebody is away). The currently shipped desktop release decodes every
message unchanged, and this build reads an older peer's silence as "nobody is away" - which is that
build's actual behaviour. **No backend change was needed and none was made.**

**"Pause for away users"** is a separate host switch from "Pause when someone buffers", defaulting
**off** (the party plays on; whoever returns catches up). Session-scoped and host-side, exactly like
`waitForEveryone`. A host's own absence pauses the party whatever the switch says - the host is the
clock - and it is issued as a real command so it is ordered and attributable.

**Host authority is untouched.** Away writes no member row; transfer remains
`party_transfer_stale_host`.

**Logs to grep on the hardware run:**

```
presence Watching -> Away reason=background|lock|interrupt|pip-dismissed
presence Away -> Watching reason=foreground
presence Watching -> Watching reason=pip        # the exception doing its job
peer publish ... away=true
away roster party=... away=[...]
party hold reason=away waitingOn=[...]
party release reason=away returned=[...]
away return catchup targetMs=... localMs=... status=... reachable=...
away return realize awayAt=... now=...          # generation moved while away
away return host intent=playing|paused
```

**Verified here.** Mobile: 8/8 pure groups, `:composeApp:testAndroidHostTest` 2235 tests, 0
failures. Desktop: 8/8 pure groups, `:composeApp:desktopTest` 2384 tests, 0 failures. All shared
Watch Together files are byte-identical across the two repos (`diff --strip-trailing-cr`).

**Not verified, and the next session should not assume otherwise.** Every one of the 21 edge cases
is covered by `PartyPresenceTest` against the pure model; **none has been exercised on a phone.**
The ones a device can contradict:

- PiP entry/exit ordering on the actual S25 (case 5-8, 21). The model cannot flap, but the *facts*
  this build feeds it come from `ProcessLifecycleOwner` plus
  `addOnPictureInPictureModeChangedListener`, and whether those two agree on a real device is the
  open question.
- Screen lock (case 4). `ACTION_SCREEN_OFF` plus `KeyguardManager.isKeyguardLocked`; on a device
  with no lock set this should read as an ordinary background, not as a lock.
- The catch-up seek after a long absence (case 9). It reuses `partySeekPlan`, so it should behave
  like any large drift correction, but nobody has watched one land after four minutes away.
- A phone call (`PartyAwayReason.Interrupted`). **Nothing sets it on Android.** The model carries and
  tests it, and the adapter does not supply it, because the only honest sources are audio focus -
  which would mean requesting focus and changing playback behaviour - and telephony permissions. A
  call backgrounds the app and turns the screen off, so it is already covered as `background` or
  `lock`; the reason code is reserved for an adapter that can do better.
- iOS: `didEnterBackground` rather than `willResignActive` (a notification shade is not somebody
  walking away), `screenLocked` always false. Compiled only - iOS is SKIPPED on Windows - and, per
  the chunk brief, no physical iOS verification was attempted.

**Also fixed on the way.** Groups 6 and 7 of `run-pure-suites.sh` had been failing to compile in
**both** repos since the source-resolution UX pass: `PartyPlaybackStatus.kt` calls into
`PartySourceActivity.kt` and neither group listed it. A group that does not compile does not report
as missing - JUnitCore prints one `initializationError` per named class - so the run showed eleven
and six nameless "failures" right after five groups of OK lines. Mobile `1f25f6e8`; folded into the
desktop commit. 236 tests are running again that were not.

## Phase 6 Watch Together playback/source stabilization - DONE WITH NON-BLOCKING QA DEBT (2026-09-20)

The chunk is closed. Published as Android **`0.4.13-z1.36`** and desktop **`z6.57`**. What it
contains, end to end:

- **Engine-native starvation and readiness.** Android and desktop both ask the engine whether it is
  buffering or ready instead of inferring it from roughly a second of buffered-ahead. Reactive stall
  handling stays for the buffering nobody expected.
- **The watchdog's viability rule.** A party hold freezes startup-watchdog time only once the local
  source has proven itself through real media progress, so a dead source can no longer become
  immortal behind a hold. Headers, a reachable URL, a parsed duration and a party-commanded seek are
  explicitly not proof.
- **The seek positive-readiness barrier.** Seek, everyone stays paused, clients seek, fresh post-seek
  readiness is reported, the host checks its own, and the play barrier resumes the party together.
  Every exit is named in the log: `all-ready`, `dont-wait`, `ceiling`, `degraded`.
- **Timeline-safe source failover.** Different URLs are fine, different timelines are not. A host
  staying on its release retries locally; a host leaving it advances the authoritative source and
  `sourceGeneration`. A guest's failure never advances it, its local fallback must be
  timeline-compatible, and an incompatible one never silently reports ready.
- **Source-resolution UX.** Joining, matching the host's source, host source changed, own source
  failed, trying a compatible fallback, cannot match, waiting for everyone and ordinary buffering are
  distinct states. They must not regress into a generic "Loading".
- **Explicit host manual source authority.** A host picking a different `EquivalentMedia` release
  from the sources panel is an authoritative source change. Re-picks of the party's own release,
  automatic paths and guests all keep the full duplicate test.

**Hardware-verified** on 2026-09-20 in both directions (Android `z1.35` host + desktop `z6.56` guest,
and the reverse): the host waited for the slower member rather than playing ahead, and **every
observed barrier resume was `reason=all-ready`** - no `ceiling`, no `degraded`, no `dont-wait`.
Observed waits: 478 ms / 3.6 s / 3.9 s with the Android host, 3.7 s / 3.9 s / 8.5 s with the desktop
host. **The 12 s ceiling is unchanged**, and 8.5 s is 71% of it.

**Non-blocking QA debt**, carried deliberately rather than folded into the pass. Real source failures
cannot be forced reliably, so these three remain opportunistic, trial-by-fire from normal usage:

- a natural **host** source failure,
- a natural **guest** compatible fallback,
- a natural **guest** incompatible / no-compatible-fallback.

When one happens, preserve the live state and logs and debug that incident. None of them blocks the
next chunk.

Ledger: **S9** in `Docs/Z-FEATURES.md`.

## Phase 6 hardware run: clean, and the last source-authority gap is closed (2026-09-20)

**The run passed.** Both seek directions (desktop host → Android guest, Android host → desktop
guest) held the readiness barrier and resumed together; the host waited rather than playing ahead;
no buffering or readiness regression; the source status copy read correctly in every case exercised.
Captured on Android `0.4.13-z1.35` (125035) against desktop `z6.56` (1.45.56).

**Every barrier resume was `reason=all-ready`. The 12 s ceiling never fired on either side**, and
neither did `dont-wait` or `degraded`. Observed waits: 478 ms, 3.6 s, 3.9 s on the Android host and
3.7 s, 3.9 s, 8.5 s on the desktop host. The 8.5 s is the number to keep: it is 71% of the budget,
so the ceiling is doing real work and there is no evidence for lowering it. Leave it at 12 s.

Real source failures could not be forced - they are too random to manufacture - so host failover,
guest compatible fallback and guest-with-no-fallback remain **trial by fire from normal usage**. If
one happens, preserve the live state and logs and inspect that incident rather than reproducing it.

With that evidence in hand, `2b81383d9` (desktop `ccac89fe`) closes the one known inconsistency the
run was gating: **a manual host pick of an `EquivalentMedia` look-alike now advances the party.**
The sources panel is a person, and the party's timeline is whatever the host is watching, so an
explicit host pick narrows the duplicate test from `PartyExactMatchTiers` to `PartySameReleaseTiers`
and a look-alike release falls outside it. It moves the door rather than opening one - re-picking
the release the party is already on is still a change nobody made, and still refused.

Deliberately narrow, and the tests assert the narrowness as hard as the fix:

- only the sources panel passes `explicitHostSelection`, so **automatic duplicate protection is
  unchanged** - a realization that flaps onto a look-alike still says nothing to the party;
- only the **host** may use it. A collaborative guest's pick faces the full test whatever flag it
  arrives with, because a guest promoting a look-alike asserts a timeline it does not define;
- the one-shot `publishedSourceGeneration` guard sits above both doors, so `sourceGeneration` still
  advances exactly once;
- guests reach the existing "Host source changed" resolution UI and the existing readiness barrier.
  Nothing in either was touched.

`PartyHostManualSourcePickTest` (11) covers it: the tier fixtures themselves, the look-alike pick in
both control modes, re-picking the party's own source and the same release through another provider
and the same origin, a guest's look-alike refused with the flag set, a guest's plainly different
release still publishing, a guest under host-only control refused, the automatic path keeping the
full test, and no second advance for the same generation.

Verified: mobile **2199 tests, 0 failures** (`:composeApp:testAndroidHostTest`, full suite) plus
`:androidApp:compileFullDebugKotlin`; desktop **2348 tests, 0 failures** (`:composeApp:desktopTest`, full suite).

Published as Android `0.4.13-z1.36` / desktop `z6.57`. **Away is not implemented and was deliberately not part of this change.**

## The host's republish was suppressed twice, and the check could not fire (2026-09-20)

`84c3ec5cc`, published as Android **`.35`** (`0b7fb77b8`) and desktop **z6.56** (`95ebc494` +
`fb4d327c`). The edge was **not** already correct; it needed a fix, and the test written for it found
a second defect of the same shape.

1. **The duplicate test swallowed the publish.** `shouldPublishPartySourceChange` refuses anything
   inside `PartyExactMatchTiers`, which is right for a hand-picked source and wrong for a host
   realignment - and it covered *both* shapes the realignment exists for: a file re-cut under the
   same descriptor, and a fall back to a look-alike release, which is `EquivalentMedia` and therefore
   inside that set. `partySourceTimelineDecision` said `AdvancePartySource`; the heuristic said
   duplicate; the heuristic won with nothing in the log.
2. **The host was excluded from its own duration comparison** (`takeIf { !isHost }`), so
   `durationsAgree` was vacuously true for the one client whose duration defines the timeline. The
   contradiction branch was reachable only from its own unit test.

The fix is the host realignment path passing its own verdict - `timelineChanged`, reached only
through `AdvancePartySource` - and the duplicate test being bypassed for that caller alone. Every
other caller keeps it exactly as it was; a manual pick still refuses to republish an equivalent.
(Superseded for the **host's** manual pick by `2b81383d9` above - an explicit host selection of a
look-alike now advances the party. An automatic path, and any guest, still refuse it.) The
one-shot guard on `publishedSourceGeneration` sits outside the bypass, so "exactly once" holds
whichever way a publish was justified. `partyHostTimelineContradicted` is the narrow duration
reading: same release identity, both durations known, genuinely disagreeing - a missing duration is
never evidence, or a host would republish its own source at every start.

`EquivalentMedia` is unchanged for guests: with a compatible duration it is still `KeepLocal` and
still reports ready.

`PartyHostSourceRepublishTest` (12) covers every case: same descriptor + compatible duration, a
credential re-mint and the same release through another provider, the proven contradiction, the
ordinary guard still refusing that same descriptor without one, no double advance on recomposition,
the contradiction going away once the new duration is on record, a different release, a look-alike
release, unknown durations, the bypass never opening for a tier that is not the party's own release,
and the two guest halves.

Verified: mobile **910 tests, 0 failures** (`watchparty`, `player`, `playback`) plus
`:androidApp:compileFullDebugKotlin`; desktop **942 tests, 0 failures**.

## Different URLs are fine. Different timelines are not. (2026-09-20)

`7ff3a02ba` on `claude/phase-6-convergence-linear`, `8044f0b1` on desktop. Not in any build yet.

### The rule

The failure chain is a route-level mechanism with no idea a party exists, and every step of it was
local. That is right for most of what it does - a credential re-mint, a renewed debrid link, the same
torrent file through another provider are all new URLs for bytes the party has already agreed on -
and wrong in exactly one way: a host whose chain lands on a **different release** is playing
different frames at the same timestamps, and nothing told the party.

| Who | What changed | What happens now |
| --- | --- | --- |
| Host | same release, new URL (`ExactTorrentFile` / `ExactOriginRelease` / `ExactRelease`) | nothing - stays local, as before |
| Host | a different release, including a look-alike (`EquivalentMedia`) | `publishHostPartySourceRealignment` advances the authoritative source: gate closes, guests re-realize, readiness barrier resumes everyone |
| Host | same release identity, contradicted by duration | treated as a release change - the host's timeline is the party's |
| Guest | same release | local, reports `ready` |
| Guest | `EquivalentMedia` **and** duration compatible | local, reports `ready` |
| Guest | `Fallback`, `None`, or any duration contradiction | reports `choosing_fallback`, never `ready`; stays in the party |

A guest never advances the authoritative source, whatever it lands on. `PartySameReleaseTiers` is the
new narrower set for the host's publish decision; `PartyExactMatchTiers` keeps its old meaning of
"close enough not to re-adopt" for the handoff and realizer. Duration can only ever subtract: two
cuts can run to the same minute and differ by a distributor logo at the head.

### The copy, derived rather than guessed

`PartySourceActivity` is a pure state machine over facts the player already holds - realization
phase, whether the party has played this generation, whether the party's source moved, the local
attempt number, the timeline verdict - and `partySourceMessage` is the only place the words live.
Ordering is the design: a client with no source open yet is *not* buffering, which is how every one
of these collapsed into "Matching source…" before.

| State | Headline | Detail |
| --- | --- | --- |
| InitialPartyMatch | Joining playback | Matching <host>'s source… |
| HostSourceChangedMatching | Host source changed | Finding a compatible source… |
| HostSourceChangedResolving | Host source changed | Resolving the new source… |
| HostSwitchingSource | Switching source | The current source failed. Finding a replacement… |
| LocalSourceRetry (host) | Source failed | Trying another connection… |
| LocalSourceRetry (guest) | Your source stopped working | Trying another compatible source… |
| LocalCompatibleFallback | Using a compatible source | Preparing playback… |
| PartySourceUnmatched | Couldn't match the party source | Choose another source to continue with the group. |
| FailedTryingNext | Source didn't work | Trying the next option… |
| WaitingForPartyReadiness | Waiting for everyone… | - |
| Buffering | Buffering… | - |

`PartyStatusLine` gained `detail`, the mobile pill stacks it dimmed under the headline, and the
desktop controls got the same pair in `controls.html/css/js`. The host's member list says **Needs
source** for `choosing_fallback` rather than "Choosing alternate". The generic "Matching …'s source…"
row is still there as the answer for a caller that has not derived an activity - a projector that
drops a row because an input defaulted is worse than a generic sentence.

### Coverage

`PartySourceTimelineTest` (12): host re-mint, host alternate realization, host different release,
host look-alike release, host duration contradiction, guest on the party's release, guest compatible
equivalent, guest duration-incompatible, guest arbitrary `Fallback`, guest contradicted identity, a
guest never advancing the source, and unknown durations leaving the identity verdict standing.
`PartySourceActivityTest` (11) asserts the copy itself, including that a client with no source is
never called buffering and that no tier name reaches a person.

Verified: mobile `:composeApp:testAndroidHostTest` over `watchparty`, `player`, `playback` - **898
tests, 0 failures**; `:composeApp:compileAndroidMain` green. Desktop `:composeApp:desktopTest` over
`watchparty` and `player` - **571 tests, 0 failures**.

**Owed**: no hardware run for any of it. The DV7 selection question is still open, and Away is still
not implemented.

## A dead source, a deadlock, and a seek that waits for people (2026-09-20)

Four things, in the order they happened: the desktop mirror of the engine-native starvation signal
was published as **`debug-v0.1.23-alpha-z6.54`**; a two-client hardware run on the S25 and that build
named the black-picture cause and found a worse defect behind it; the deadlock was fixed
(`989e1d10c`); and seeks stopped resuming on a timer (`2554f48c8`). Mobile commits on
`claude/phase-6-convergence-linear`, desktop on `claude/heartbeat-session-renewal` (`280d11d2`,
`6c9a0843`). No build has been cut for either fix yet.

### Desktop now answers the same question Android does

`NuvioZDesktop` carried the 1000 ms heuristic until this run. The mirror adds one JNI export,
`engineReadinessFlags`, to all three bridges - `paused-for-cache`, `cache-buffering-state`, `pause`,
`seeking`, `core-idle` packed into one int - and maps them in Kotlin with the same rules the Android
bridge uses. `isLoading` could not be reused: its `core-idle && !paused` term is blended with intent,
so the instant the party pauses a starving guest the guest stops reporting that it is empty.

First live evidence, from the desktop guest of the Android-hosted party:

```
peer status settled=buffering starved=false engine=Desktop-mpv/NoSource bufferedAheadMs=0
peer status settled=paused    starved=false engine=Desktop-mpv/Ready    bufferedAheadMs=0
```

A missing export is caught and latched rather than left to `snapshot`'s `runCatching`: the Gradle
task skips the native build whenever a DLL is already there, so a stale local bridge would otherwise
turn every poll into an all-loading snapshot and break playback outright.

### The black picture was Dolby Vision profile 7

`videoPresentation=no_supported_video` on the first hardware attempt, which is exactly what the
`.33` diagnostics were added to answer. The candidate's only video track was
`mime=video/dolby-vision codecs=dvhe.07.06`, 3840x2160, `supported=false decodable=false` - dual-layer
DV, which the S25's decoders do not take. Classified fatal, fell back to libmpv as designed, and
libmpv then said `Dolby Vision enhancement-layer playback is not supported`. **The `.32` audio-only
run is almost certainly the same source shape.** No fix is owed for the classification; what to do
about DV7 sources (reject at selection? prefer a profile-8 or HDR10 release?) is still open.

### A party hold made a dead source immortal — fixed

Behind the DV7 track, the same URL was serving a 21 KB placeholder claiming 59.4 GB:
`probe status=206 total=21982 verdict=placeholder:served_21982_claimed_59400000000`. The failure
chain exists for exactly this and would have run in twenty seconds outside a party.

Inside one it could not run at all. The host's own start gate held the player at
`WAITING_FOR_PARTICIPANTS` while it waited for the guest; `PlaybackStartupSample.isHeld` froze every
deadline; and a frozen deadline cannot fail a source. The host waited for a guest that was waiting
for the host, and the one clock that could have broken the tie had been stopped by the wait itself -
seven minutes on the loading screen when the run was stopped by hand, with the probe's verdict
already in the log.

**The rule now**: a hold stops the clock only for a source that has delivered media.
`State.hasProvenViability` is sticky and set by `progressMs` alone, so a parsed duration (a header), a
successful probe (a reachable host), the URL existing (a string) and a party-commanded seek (the
baseline moves with it, so the jump measures zero) are all worth nothing. An unproven source falls
through to the ordinary deadline it would have had outside a party - 20 s, or 35 s with evidence of
life - and fails over normally. Everything the hold was built for is untouched: every case of it had
already produced media, and those tests pass unchanged.

### Seeks wait for readiness instead of a lead

The same run measured the other half of the host's behaviour. The host released its seek barrier at
10:14:31.516 and the guest's `starved=true` arrived at 10:14:31.954. Neither client was slow: a
member holding for a barrier publishes nothing by design, so the answer could not exist before the
host had already started, and the stall guard then pulled the party back - a pause, a resume and a
pause, for a cost that was known in advance.

A seek that resumes is now issued with `playAfter = false`. Everybody lands on the frame and stays
there; the resume is a second command, issued once they have all said they can play it. The rule is
the start barrier's own - `partyStartPlaybackRelease` - with `freshSincePartyMs` added so a report
from before the seek cannot answer a question the seek asked (the sender's stamp, not the receipt: a
report that crossed with the command arrives after it and describes the member before it). It now
also requires `!starved` of a `paused` member, which is the 2026-09-19 failure in its second form.

Bounded by the start barrier's 12 s ceiling, released immediately by "Don't wait", revoked by any
user transport command, never waiting on members who are disconnected, failed, gone or not in a
player, and skipped entirely when there is no live peer plane. The reactive stall guard is untouched
and still owns buffering nobody asked for. **No wire or backend change was needed**: `starved` was
already on the peer plane, and only `PartyPeerTelemetry` had to start carrying it inward.

**One engine change came with it.** libmpv's `cache-buffering-state` was read only while playback was
intended, against a stale percentage. Measured against the shipped libmpv (a 16 Mbps file served at
250 KB/s, the player held paused the moment the cache ran dry) it is not stale: it reads 100 whenever
mpv is not buffering, playing and paused alike, and climbed 0 → 96 → 100 live through a refill the
player was held paused for. The `&& !paused` guard is gone in both repositories, which corrects the
table in the 2026-09-19 entry above. Without it the barrier had no answer on desktop: a member seeked
into an unbuffered region and told to wait there reported `Ready` with an empty cache.

### Coverage and what is still owed

`PlaybackStartupWatchdogTest` (+5: the held dead source at the ordinary deadline, the header-and-probe
case at the 35 s one, repeated party seeks under a hold, one millisecond of media buying the
protection back, and proof earned after a hold began). `PartySeekReadinessBarrierTest` (10, new:
staleness, a report that crossed with the command, `paused`-but-starved, the ceiling, members who
cannot be waited for, a degraded plane, and the start barrier's own call unchanged).
`NativePlayerReadinessTest` and `PlayerEngineReadinessAndroidTest` updated for the pause-flag change.

Verified: mobile `:composeApp:testAndroidHostTest` over `watchparty`, `player` and `playback` -
**873 tests, 0 failures**; `:composeApp:compileAndroidMain` green. Desktop `:composeApp:desktopTest`
over the same packages - **905 tests, 0 failures**. Both with `--rerun-tasks` and the results
directory deleted first.

**Owed**: a hardware run for both fixes - the deadlock case (a dead source inside a party must now
fail over) and the seek barrier (the host must not start before the guest can). Neither has been on a
device. The DV7 selection question is open. Away is still not implemented.

## Starvation is the engine's verdict now, not a one-second buffer (2026-09-19)

`6b937f65c`, on `claude/phase-6-convergence-linear`, and published as debug **`.33`**
(`debug-v0.4.13-z1.33`). This is the fix the inspection below pointed at; the inspection's reading of
the defect stands unchanged.

`PlayerPlaybackSnapshot` now carries `engineReadiness` - `Buffering`, `Ready`, `NoSource` or
`Unknown` - and `partyStarvedFor` believes it:

| Engine state | Readiness | Starved |
| --- | --- | --- |
| ExoPlayer `STATE_BUFFERING` | `Buffering` | **yes**, at any buffer level |
| ExoPlayer `STATE_READY` / `STATE_ENDED` | `Ready` | no, at any buffer level |
| ExoPlayer `STATE_IDLE` | `NoSource` | no |
| libmpv `paused-for-cache` | `Buffering` | **yes**, even while `pause` is set |
| libmpv `cache-buffering-state` 0-99 **and not paused** | `Buffering` | **yes** |
| libmpv cache filled, or `cache-buffering-state` standing while paused | `Ready` | no |
| libmpv `seeking` | `Unknown` | falls back to buffer occupancy |
| anything else - iOS today | `Unknown` | falls back to buffer occupancy |

Two exclusions sit in the party rather than in the engines, because both are facts about the party:
a client holding for a barrier or its own corrective seek is never starved, and neither is one with
no duration yet - that one belongs to the start gate, and calling it starved would have every join
hold the party from outside.

**Why the engine and not a bigger constant.** The engine is what decides when playback resumes.
Raising 1000 to 5000 would have matched today's `bufferForPlaybackAfterRebuffer` and drifted from it
the next time the load control is tuned, and it would still have been guessing on libmpv, which sets
no cache timing in this repo at all. `bufferedAheadMs` stays in the peer-status log line (now beside
`engine=<name>/<readiness>`) and stays the rule where nothing better exists, but it no longer
overrules an engine that says it is still buffering.

**What did not change**: the host grace, the hold budget and its ceiling, the transport and its wire
format, Away, and the backend. `starved` is the same boolean on the same field - only how a member
decides its own value moved.

**Coverage.** `PartyStarvationTest` (9, `commonTest`) and `PlayerEngineReadinessAndroidTest` (6,
`androidHostTest`, mapping the Media3 states and the libmpv properties). Buffering at 1200 ms ahead
is starved; engine-ready at 120 ms is not; a host-forced pause over a buffering engine still is; an
ordinary pause is not; a barrier hold never is; `paused-for-cache` is and a recovered cache is not.
The stall-hold feedback-loop regressions in `WatchPartySyncTest` take `starved` as an input and are
unchanged and green.

**Verified**: `:composeApp:testAndroidHostTest --rerun` 2137 tests, 0 failures, 0 skipped (results
directory deleted first); `:androidApp:compileFullDebugKotlin` green; the `debug-release.yml` run for
`.33` green, which is the host suite and `assembleFullDebug` again in CI.

**Not carried to desktop.** `NuvioZDesktop`'s copy of `PlayerWatchPartyEffect.kt` was byte-identical
before this commit and is now behind it, so desktop parties still clear starvation on the 1000 ms
heuristic. Mirroring it also means teaching the desktop native bridge's snapshot to report
readiness - it already reads `paused-for-cache` - or desktop silently keeps the old rule through the
`Unknown` fallback. No desktop build was cut.

## Android black picture: the diagnostic that skipped video, and a dead escape hatch (2026-09-19)

Three Android changes, all on `claude/phase-6-convergence-linear`. None of them is a fix for the
audio-only run itself - that run is still undiagnosed, deliberately. They make the next occurrence
say which of four things it is, and they stop the user being stranded meanwhile.

### 1. `logCurrentTracks` could not answer the question it was being read for

On debug `.32` a clean Matroska remux played audio for ~27 minutes across three attempts with
`onRenderedFirstFrame` firing **zero** times. The logs could not say whether the video track was
supported, selected, or even present, and that was structural rather than bad luck:

```kotlin
C.TRACK_TYPE_VIDEO -> "VIDEO"                                        // label built
...
if (group.type != C.TRACK_TYPE_TEXT && group.type != C.TRACK_TYPE_AUDIO) continue   // then skipped
```

It also described each group by `getFormat(0)` and by the group-level `isSupported`/`isSelected`,
so a multi-track group was reported through one arbitrary member of it.

**Now**: one line per *track*, video included, carrying group index, track index, format id,
sample MIME, codecs, WxH, frame rate, bitrate, rotation, `selected`, `supported` (Media3's strict
answer) and `decodable` (the same question allowing a format above the device's advertised decoder
headroom). `onVideoSizeChanged` logs dimensions, pixel ratio, unapplied rotation and whether a
first frame has arrived; `onRenderedFirstFrame` and the dropped-frame batches are unchanged.

### 2. A black picture is classified from track evidence, never from a timer

`features/playback/VideoPresentation.kt` (pure, in the standalone pure-suite group) takes a census
- video groups, tracks, decodable tracks, selected tracks, selected-and-decodable tracks - and
separates the four causes `STATE_READY + no first frame` collapses into one:

| verdict | meaning | acted on |
| --- | --- | --- |
| `NoVideoTracks` | no video groups at all | no - audio-only content is legitimate |
| `NoSupportedVideoTrack` | groups exist, **nothing decodable** | **yes** - fatal, source fallback |
| `NoSelectedVideoTrack` | decodable track exists, none selected | no - a selection bug, logged |
| `SelectedNotRendering` | decodable and selected, no frame | no - a presentation bug, logged |

Only the codec verdict is fatal (`isFatalVideoPresentation` has one member, and a test pins that).
There is deliberately **no elapsed-time parameter**: a timer cannot tell a dead decoder from a slow
one, and one would have reported "unsupported codec" about whichever cause happened to be slow.

`allowExceedsCapabilities` is true in the census on purpose - Media3's strict answer is false for
any format above a device's rated decoder headroom, which `DefaultTrackSelector` selects anyway and
which usually plays. Counting those out would fire the one fatal verdict on ordinary 4K remuxes.
The strict answer is still printed per track, where it diagnoses rather than decides.

### 3. The loading surface's escape hatch was drawn but wired to a dead lambda

`PlaybackLoadingHost` passes `PlaybackLoadingController.actions` straight through, and in the
automatic modes those actions belong to `entry<StreamRoute>` - which has stopped composing while
the player is on top, so its `giveUpToSourceList` writes flags into saved state nobody reads. This
is the same defect `StreamsRepository.signalManualSourceRequest` documents for the player's own
copy of the button, and the player's copy was fixed while the surface's was not. A start that hung
behind the loading surface therefore offered a way out that did nothing, and Back - which abandons
the play rather than dropping to the list - was the only working exit.

The player now **takes the actions over** while it is on top and signals-and-pops instead, which is
the path that works. It restores the previous registration on disposal, because a failover disposes
the player and re-enters the route, which must get its own escape back untouched. `Choose source
manually` is offered only where a list exists behind the player - the route registered one, or the
launch is an auto-pick whose `StreamRoute` is deliberately retained (`playerMayOfferSourceList`).
Continue Watching, a next episode and a resumed download get Back and nothing else, or
`manualSourceRequestPending` would be left set for whatever played next.

Watch Together is unaffected: nothing here touches transport, readiness or the hold.

### Verification

- Pure suites (`scripts/run-pure-suites.sh`) — all eight groups green; 279 + 121 + 70 + 17 + 29 +
  137 + 63 + 3 tests, 0 failures. Includes the 9 new `VideoPresentationTest` cases and the new
  `playerMayOfferSourceList` case in `StreamRouteSurfaceTest`.
- `:composeApp:testAndroidHostTest` — **2128 tests, 0 failures** (up from 2112).
- `:androidApp:compileFullDebugKotlin` — green.

### What this does *not* establish

- **The audio-only run is still undiagnosed.** No verdict has been observed on hardware yet. The
  next occurrence should print exactly one of `no_video_tracks`, `no_supported_video`,
  `no_selected_video` or `selected_not_rendering`, and until it does, none of the four may be
  asserted.
- The fatal path (`no_supported_video` → error → source fallback) has unit coverage and **no
  hardware run**.
- The 1000 ms starvation recovery threshold was **untouched by the three changes above**, as
  instructed, and has since been replaced by the engine-native signal at the top of this file.
  Inspected first, and the inspection already said it was the wrong number:

  - `PartyStarvedBufferMs` (`PlayerWatchPartyEffect.kt:139`) is `1_000L`.
  - The Android `DefaultLoadControl` is built with
    `setBufferDurationsMs(15_000, 70_000, DEFAULT_BUFFER_FOR_PLAYBACK_MS, 5_000)`, so ExoPlayer's
    own **resume-after-rebuffer** figure is **5000 ms** (and its initial-playback figure is
    Media3's 2500 ms default). Nothing in the app is configured at 1000 ms.
  - So a guest publishes `starved=false` at ~1000 ms of buffer while its own engine will not leave
    `STATE_BUFFERING` until 5000 ms. That is exactly the 1001-1276 ms cluster the hardware run
    produced: the party un-starves roughly four seconds before the player actually resumes.
  - libmpv is configured with `demuxer-max-bytes` only - no `cache-secs`, `demuxer-readahead-secs`
    or `cache-pause-wait` is set, so those run at mpv's defaults and are not pinned by this repo.
    It does, however, already expose and observe two **engine-level** readiness facts that need no
    threshold at all: `paused-for-cache` and `cache-buffering-state` (0-100), both read in
    `readSnapshotNow`.

  The shape of the fix this points at - report the engine's own readiness rather than compare a
  buffer figure to a constant - is what `6b937f65c` implements; see the top of this file. The
  constant survives as the fallback for engines that cannot answer, under the name
  `PartyStarvedFallbackBufferMs`.

## The stall hold released itself, and a party seek looked like startup progress (2026-09-19)

Second S25 handset run, desktop host (`debugmain`, debug **z6.52** / 1.45.52, `716195ff`) and
Android guest (`debug-v0.4.13-z1.31`, `f45aea06c`). The guest never finished starting: it was
kicked back to the source list with "No safe source found", having been the only candidate the
party had. Two independent defects, each sufficient on its own, and they compound.

**Both are fixed** in mobile `c3ef420e7` and desktop `26f96d6b` (same change, cherry-picked; the
ten touched files are byte-identical across the repos).

### 1. A host hold was released by the guest obeying it

From the two logs, to the millisecond:

| time | who | what |
| --- | --- | --- |
| 13:32:34.544 | guest | `peer publish status=buffering` (edge at .344) |
| 13:32:36.141 | host | `peer status from=d3397924 status=buffering transitMs=173` |
| 13:32:43.354 | host | `waiting for d3397924 intent=playing` → `pause src=stall-guard` |
| 13:32:42.612 | guest | `peer publish status=paused` — obeying that pause, still empty |
| 13:32:44.192 | host | `peer status … status=paused` |
| 13:32:44.624 | host | `stalled guests recovered, resuming for=d3397924` |
| 13:32:43.992 | guest | `peer publish status=buffering` — it had never recovered |

The hold lasted **1.27 s**. `GuestBufferingWatch` read `paused` as recovery, per a doc comment
asserting that a starving member says `buffering` instead. **That assertion was false.**
`partyStatusFor` tests `snapshot.isLoading`, which is starvation measured *against an intent to
play*; the host's own pause removes the intent, so a starving player stops looking starved the
moment it is held. The host read its own command coming back as evidence the guest was fine.

The spurious hold also spent the single hold `StallHoldBudget` allowed, so the guest's real
buffering at 13:32:43.992 produced nothing at all and the host played on for **26 s**.

**Fix.** `PartyPeerStatusMessage.starved` carries buffer occupancy - a fact no command can change -
beside the status. `GuestBufferingWatch.observe` normalises a starved `paused` to the stall it is,
at the edge, so such a member both *enters* the stall window and stays in it. A hold now ends on
genuine recovery or on the ceiling, never on obedience.

Two consequences that needed handling, both caught by the new tests rather than by reasoning:

- `WatchPartyStallHoldMaxMs` was reachable only for a member that had *stopped* reporting a stall,
  which was survivable only because of the bug. It is now checked ahead of "still stalling", or one
  guest that never recovers would hold the party for the rest of the film.
- A member released by that ceiling keeps no stall window, or the next `advance` would promote it
  straight back and the party would flap once per poll. A later stall is a new fact, and how often
  a host may act on one remains `StallHoldBudget`'s decision.

Wire-compatible: absent from older senders, where it decodes `false` - exactly what those builds
already do. Both ends of a party need `.32`/`z6.53` for the fix to apply.

### 2. A commanded seek counted as startup progress

```
13:32:56 W PlaybackStartup: abandoning [TB⚡] ComeTorz 2160p: reason=Stalled
  elapsed=43419ms effective=20147ms heldTotal=23272ms progress=12012ms lastAdvance=8086ms
```

The hold-exclusion worked: all 23.3 s of held time was subtracted. What killed the source is that
`progress=12012ms` was reached by the host's pause-align **seek** (4339 → 12012), not by decoding.
The engine reports a seek target immediately, so `PlaybackStartupWatchdog` recorded 12012 ms of
progress no byte had been fetched for. That flipped the play off the patient `bestProgressMs <= 0`
path (35 s) onto `STALL_DEADLINE_MS` (12 s), measured against a position only the host could move.

**Fix.** `seekPartyToExact` - the one choke point for every authoritative move of a party
playhead - publishes its target as `partyAlignedBaselineMs`, the sampler passes it as the
watchdog's baseline, and `observe` rebases `bestProgressMs` into the new baseline's units. The jump
measures zero; real advancement past the target measures normally. Nothing else is touched:
`elapsedMs`, `holdMs` and `lastAdvanceMs` all carry, so a correction resets no deadline, and both
deadlines that survive a rebase are absolute in effective elapsed time - which is what makes
"repeated corrections cannot extend the watchdog" a property rather than an intention.

### Verification

- Mobile `:composeApp:testAndroidHostTest` — **2112 tests, 0 failures** (full suite, `--rerun`,
  results directory deleted first).
- Mobile `:androidApp:compileFullDebugKotlin` — green.
- Desktop `:composeApp:desktopTest` — **2271 tests, 0 failures** (full suite, `--rerun`, results
  directory deleted first). The three known flakes - `WatchedItemsStoreTest`,
  `DesktopDownloadQueueE2ETest`, `NativePlayerControllerTeardownTest` - all passed this run.
- All 14 new tests confirmed present in both result sets, not silently skipped.
- New tests: `aHostHoldDoesNotReleaseItselfWhenTheGuestObeysIt` replays the eight steps above;
  plus a starved `paused` starting a stall, an ordinary user pause not becoming one, a barrier park
  creating none, the ceiling still abandoning a guest that never recovers, and a second genuine
  stall still reaching the budget. Watchdog: party seek counts as zero progress, grants no fresh
  startup, cannot extend the watchdog when repeated, real advancement past the target still counts,
  and held time stays excluded exactly as before. Protocol: `starved` round-trips, an older
  sender decodes as not starved, and an older decoder sees every field it knew unchanged.

### Carried forward, unresolved

- **#2/#4 (frozen frame under running audio) was not exercised by this run** and must not be
  diagnosed from it: ExoPlayer failed the container outright
  (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`, 13:32:16), libmpv took over on a **58.9 GB 4K
  BluRay MPEG-TS remux**, the phone was genuinely starving, and there was exactly one candidate.
  Re-run on a normal-bitrate MP4/MKV with several candidates.
- **#3 measured, and it is worse than the static 3.3 s estimate.** Guest edge → desktop pause was
  **9.01 s**: 200 ms settle, ~1.4 s publish/transport (`transitMs` reports only 173 ms, so ~1.2 s
  is queueing on the publish side and is worth its own look), then **7.21 s** of host-side grace.
  The grace is unchanged, as instructed.
- **#1 considered fixed.** All party and barrier state stayed `speed=1.0` on both sides. The
  `1.1x` in the Android media session is the real engine rate during drift correction, which that
  surface uses deliberately for position extrapolation.
- **Heartbeat silence did not recur** (no desktop poll gap over 8 s). But the desktop log contains
  **zero** lines matching `heartbeat`, so this run does not establish that the new diagnostics can
  fire at all. Confirm that before reading silence as a pass.
- **Risk to watch on the next run.** `starved` is `bufferedPositionMs - positionMs < 1000 ms`. Both
  engines behaved on this run - 1.4-7.2 s ahead when healthy, exactly 0 when frozen - but an engine
  that never reports a buffer ahead of the playhead would read as permanently starved and stall a
  party for up to `WatchPartyStallHoldMaxMs`. The guest's `peer status settled=` line now carries
  `bufferedAheadMs=` so this is measurable rather than assumed.
- **Away is still blocked**, deliberately: it sits on the same readiness model this entry corrects.

## Host-authority incident: root cause, fixes, and the handset findings (2026-09-18)

**Timeline, from `realtime.messages`** (party-row broadcasts carry each host position heartbeat):
the desktop host's heartbeats land every ~5.2 s until **10:31:37.166**, then stop. At 10:31:50.968 the
phone pauses (seq 19). At **10:31:54.969** host moves to the phone (epoch 0→1). The desktop had been
silent for 17.8 s. At 10:32:00.208 the phone's heartbeat marks the desktop disconnected (20 s). At
**10:32:48.938** the desktop's first heartbeat back takes host again (epoch 1→2), because by then the
phone had been silent for more than 15 s. The desktop was silent for **~71 s**.

**Ruled out:** Z-token expiry. `auth.sessions` shows the desktop exchanged once, at 10:22:24, and
not again on recovery. The laptop's Windows System, WLAN, NCSI and DHCP logs show no sleep, power
or connectivity event between 13:28 and 13:37 local. The installed release (2.0.131) writes no
debug log, so why its requests stopped landing for 71 s is still **unknown**.

**Fixed:**

- **Backend** `nuvio-z-backend` `claude/host-authority-grace` (`cc81430`), migration
  `202609180001_host_authority_grace.sql`:
  - Host moves only after **60 s of continuous absence**.
  - An Away host (new nullable `away_since`) is never replaced for being silent.
  - The successor prefers a member who is watching.
  - Explicit leave is still immediate, and a completed transfer is still permanent.
  - Test suite `host_authority_grace.sql`: 25 assertions; 12 of them fail on the old body.
    Backend suite **323/323**.
  - **Deployed 2026-09-18** by the maintainer (`supabase db push --linked`). Read back:
    `202609170002` and `202609180001` recorded, `away_since` present and nullable, both function
    bodies hash-identical to the tested local database, grants still service-role only, the
    heartbeat trigger enabled, and `party_heartbeat`/`party_create` signatures unchanged, so released
    desktop is unaffected.
- **Client, both repos** (mobile `575f27af7`, `313a96910`; desktop `claude/heartbeat-session-renewal`
  off `Dev`):
  - `ZSessionBridge.ensureSession` renews a Z token within 2 min of its expiry. A token that is
    still fresh costs one in-memory comparison and takes no lock.
  - A token in its renewal window never blocks a caller, and a failed renewal is retried after 15 s.
  - The exchange HTTP client is now bounded.
  - The poll heartbeat runs through `runWithZSession`: it makes sure a session exists first, and
    re-exchanges once on a 401.
  - Each heartbeat attempt is capped at 10 s end to end, and a failed one is retried after 2 s.
  - One `poll silent` / `poll recovered` warning pair per silence of 15 s or more, with failure
    class, attempt duration and loop lag.

**Next occurrence of the 71 s gap:** reproduce it on the desktop **debug** build. Its log now names
whether requests failed, timed out, or were never run.

**Handset findings (S25, same run):**

1. **Tiny speeds such as `1.0035x` shown on the speed control: confirmed and fixed.**
   - Cause: drift correction wrote the corrected rate to the engine, and every reader used the
     engine rate.
   - It was also a correctness bug. `startPartyPlayback`, `pausePartyPlayback` and
     `submitPartySeek` stamped that rate on commands as the party's speed, and host ticks and
     presence published it.
   - Fix: every party rate write records the nominal speed alongside it
     (`partyNominalSpeedDuringCorrection`). Controls, the speed sheet, speed gestures, presence,
     commands and ticks all read `nominalPlaybackSpeed`.
   - The lock-screen media session keeps the real engine rate, because it uses it to extrapolate
     position.
2. **Frozen picture under running audio after a resync (#2), and after pause → play (#4): two
   hypotheses, not a diagnosis.**
   - (a) `seekPartyToExact` treats a seek as landed once the engine's position reaches the
     target. ExoPlayer reports the target position immediately, so `play()` can arrive while the
     decoder is still working forward from a keyframe up to ~9 s back.
   - (b) Every barrier and drift correction calls `setPlaybackSpeed` right before `play()`.
   - (b) fits #4, where an aligned pause → play needs no seek; (a) does not.
   - Engine diagnostics added (`NuvioPlayerDiag`: `speed=`, `droppedFrames=` bursts, alongside the
     existing `firstFrame`/`state` lines). One logcat reproduction will separate the two.
     **Not fixed yet.**
3. **Buffering hold ~3 s late: cause measured statically.**
   - Pipeline: snapshot ~250 ms + settle 200 ms + transit ~0.2 s + **`WatchPartyGuestBufferingGraceMs`
     2,500 ms** + stall poll 150 ms ≈ 3.3 s.
   - The grace has two stated reasons:
     - a guest's own corrective hold, now covered by status silence while
       `partyHoldingForBarrier` is set (also in released z6);
     - brief torrent rebuffers.
   - A shorter grace needs one measurement first: how long the post-barrier refill on the S25
     reports `buffering`.
   - The grace runs on the **host**, so for a desktop host only a desktop build changes it.
   - Timing fields added: guest `peer status settled=… afterEdgeMs=`, host `peer status … transitMs=`.

## INCIDENT: host moved to the phone mid-party without anyone leaving (2026-09-18, evidence as captured)

First S25 handset run on `debug-v0.4.13-z1.29` (commit `1f159e76f`). Android (Big Z, profile
`d3397924…`, handle `zokaper`) and desktop (debugmain, `11cee346…`, the installed **release**
2.0.131 from `C:\Program Files\Nuvio Z`, started 10:22:07 UTC). The desktop hosted. Host moved to
the phone with no leave, end, transfer or sign-out.

**Live server capture, read-only, taken 10:35 UTC** (party `31d64d49-c761-47e1-82f8-cab1bb8a0d72`,
created 10:24:05):

- party: `status=paused`, `stage=playing`, `control_mode=collaborative`, **`authority_epoch=2`**,
  `sequence=21`, `host_profile_id=debugmain`, `host_disconnected_at=NULL`,
  `state_updated_at=10:32:54.19`.
- debugmain: `role=host`, `ready`, `connected=true`, `last_seen_at=10:35:36`, `client_location=player`.
- zokaper: `role=participant`, `ready_state=disconnected`, `connected=false`,
  `last_seen_at=10:34:49`, `client_location=lobby`, `left_at=NULL`.
- `watch_party_commands` holds seq 3–19 (10:24:54 → 10:31:50). Every row carries
  `authority_epoch=0`. Seq 20 and 21 have no command rows.

**What that proves:**

1. Authority moved **twice**: desktop → phone (epoch 1), then phone → desktop (epoch 2). The
   only deployed code that bumps `authority_epoch` on a live party is `party_transfer_stale_host`
   (`party_reap_stale` bumps it only when ending a party). Both transfers happened after
   10:31:50, with the last one at or before 10:32:54.
2. The mechanism is `party_transfer_stale_host`. The `watch_party_heartbeat_transfer` trigger runs
   it on **every member's** `last_seen_at` update. It moves host to `party_live_successor` (connected,
   seen within 20 s, earliest joiner) as soon as the host's `last_seen_at` is **more than 15 s** old.
   Nothing gives host back. It returns only if the new host goes stale as well, which explains the
   ping-pong.
3. `last_seen_at` is written only by the `party_heartbeat` RPC (the 5 s poll). Realtime
   presence is not consulted. **Missing three polls is enough to lose host**, even with a healthy
   socket.
4. For epoch 1, the desktop must have gone ≥15 s without a successful heartbeat while the phone kept
   succeeding. For epoch 2, the phone must then have gone ≥15 s stale while the desktop was fresh.
   That points to a shared network outage lasting at least 15 s (both clients on one network),
   with the first client to recover taking host. This is a **hypothesis**: no request log has been
   read yet.

**Latent defect found while tracing, not the cause here:** the poll heartbeat
(`WatchPartyRepository.startPolling`) is deliberately not routed through `call()`, so a 401 from an
expired Z token is never re-exchanged. After the Z JWT expires (about 1 h), an idle host's
heartbeats all fail and it loses host 15 s later. This did not happen here: the desktop's Z token
was minted around 10:22 and was still valid at 10:32.

**Evidence still to collect:** `realtime.messages` for this party's topic (party-row broadcasts give
per-heartbeat timing for both transfers), Supabase edge logs for `party_heartbeat` around
10:31:50–10:33, and the S25's logcat. Desktop evidence is gone: the release build writes no debug log.

**Invariant to restore:** a transient network, Realtime, buffering or lifecycle gap must not
permanently move host while the host is still a member. Fix this before building Away, because Away
deliberately makes a member go quiet.

## Phase 6: Android system transport now goes through the party (2026-09-18)

The media notification, the lock screen, headset and Bluetooth buttons, and the picture-in-picture
play/pause button all moved the Android engine **directly** (`exoPlayer.pause()` and
`view.setPaused(true)`), bypassing the runtime. That produced two defects:

- **In a party**, a lock-screen pause never became a party command. From a host it read as
  "buffering" to every guest. A guest without control could stop their own player, and drift
  correction would then restart it behind their back.
- **Outside a party**, `shouldPlay` never learned about the pause. `ON_START` restores
  `playWhenReady` from it, so a film paused from the notification **started again when the app
  came back to the foreground**.

`PlayerExternalTransport` (`commonMain`, 4 tests) now sends these requests through
`setPlaybackStateQuiet` and the finished-scrub seek, the same events desktop's controls page uses.
So the party's permission check, refusal and barrier apply as they do for an on-screen button. With
no party active the engine is also moved directly, because the runtime reaches the engine only
through recomposition, and nothing recomposes while the app is in the background. PiP **dismissal**
is unchanged: it is still a local pause (see the open decision below).
Android host **2,081/2,081**; pure 695/695.

`/code-review high` over `390bf66c7..HEAD` found two issues, both fixed in the follow-up commit. **(1)** The
transport decided "a party is active" from the controls state, which is only as fresh as the last
frame, while the command path asks the live repository. A party that ended while the phone was
locked left the lock-screen buttons doing nothing. The runtime now decides, through the answer to a
dedicated `externalSetPlaybackState` event. **(2)** The rail, pill and card were drawn inside the
PiP window. They are now hidden in PiP, like the controls. Host suite **2,080/2,080**: the removed
state-based check took one test with it.

**iOS has the same bypass, and it is left unfixed.** `iosApp/Player/NowPlayingController.swift`
calls `owner.pausePlayback()` and its siblings directly. The fix needs the Swift bridge to call back
into Kotlin's `onPlayerControlsEvent`, and without a local iOS compiler that should not be written
blind. **It is an iOS adapter item**: route the remote-command targets through the same
`PlayerExternalTransport` semantics.

### ⚠ Open product decision: what a party member's phone does when it leaves the foreground

The current behaviour is inherited, not designed. On Android the Now Playing session is normally
active, so backgrounding does **not** pause: the film keeps playing (with its audio) under the
media foreground service, and the party carries on. A client whose session is inactive pauses on
`ON_STOP`, and in a party that pause reads as buffering. Dismissing PiP pauses locally. Nothing
marks a backgrounded member as "away". Invites still have **no delivery path while the app is in
the background** (there is no FCM/APNs, as recorded at Stage C). Choosing among "keep playing in
the background", "pause yourself and show as away" and "pause the whole party" changes what every
peer sees, so it needs the maintainer's call rather than an agent's. Physical testing on the
S20+/S25+ should record what actually happens first: screen lock, home button, PiP
dismiss, an incoming call, doze after 10+ minutes, and Wi-Fi↔cellular handover, each with the
host and then a guest backgrounded.

## Phase 6: the mobile in-player Watch Together surface (2026-09-18)

Branch `claude/phase-6-convergence-linear`. **The push blocker recorded below is resolved.** The
convergence was reproduced as the single-parent commit `e606c2290` (parent `3ac11a9b9`), so no
desktop LFS history is reachable; the branch is pushed. iOS CI run `35300592476` (*Compile Kotlin
framework and Xcode app*, on `4d34c1fee`) **passed**. That is buildability evidence only: there is
still no Apple Developer account, and nothing on iOS is physically verified. `390bf66c7` then
implemented exact seek on both Android engines (media3 `SeekParameters.EXACT`; libmpv `hr-seek`)
with three contract tests. The Android host suite was **2,065/2,065** at that point.

### What was missing

Shared Watch Together state reached Android and iOS, but **nothing on mobile drew it inside the
player**. Desktop renders three things on its native HTML controls page, and mobile had no Compose
counterpart for any of them:

- **the party room.** Mobile had no header button and no panel.
- **the status pill's actions.** Mobile drew the pill's text and no buttons, so a host held at the
  start gate had no *Start anyway*, a guest with no source had no *Choose source*, and a stall hold
  had no *Don't wait*.
- **the in-player social card.** `MainAppContent` suppresses its floating prompt on `PlayerRoute`
  because desktop's page draws the card. On mobile, a party invite or friend request that arrived
  mid-playback was invisible until the user left the player.

### What landed

- `MobileWatchTogetherPanel.kt` renders `WatchTogetherBridgeState`. **It uses `PlayerSidePanel`,
  the same right-hand rail as Sources and Episodes, not a dialog.** The mobile player is locked to
  landscape (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`). The previous session's WIP used a
  `Dialog` sized to 94%×92%, which covered the whole video and opened a second window that
  immersive mode does not govern. The panel covers every bridge state: unshareable, idle (Start),
  starting/connecting, start failed (Try again / Open existing), active host or guest,
  active elsewhere (Open / Leave), and ended (Keep watching / Exit). It also shows the connection
  chip and delayed tooltip, people with Host/You badges and tone-coloured status, and incoming
  (Let in / Decline) and outgoing (Cancel, or Join now / Not now) requests. There are dismissable
  errors, the host's switches, Who can join (Direct / Ask / Off, locked while saving), per-friend
  invites with an Invited state, and a tap-to-copy invite code. End asks for confirmation inline.
- `MobilePartyOverlays.kt` draws the status pill with the bridge's own actions, plus the in-player
  social card (Join / Accept / Decline / Dismiss). They stack at the top centre, so neither can sit
  on the other. The pill stays visible while the player is locked; its buttons do not.
- `PlayerControls` got a Groups header button, labelled with the bridge's `buttonLabel`. It shows
  wherever desktop offers the control, and also whenever the badge is not `none`. So a running
  party, or a request in either direction, always has a way back into the room.
- Every button emits an existing `handlePlayerControlsEvent` command. Each one was traced to its
  handler, and no new event or party state was invented. The action tables are pure functions
  (`mobilePartyStateActions`, `mobileOutgoingRequestActions`, `mobilePartyStatusActions`,
  `mobileSocialNotificationActions`, `mobileWatchTogetherButtonVisible`) with 12 tests in
  `MobileWatchTogetherPanelTest`.

### A shared bug fixed on the way (desktop inherits it on the next merge)

The `socialNotificationAccept` / `Decline` / `Join` / `Dismiss` events acted on *the first unread
notification offering that action*. The card deliberately skips `WatchingNowJoinRequest`, because
those belong to the panel and the pill. So with a join request pending, pressing **Accept on a
friend request's card let the join requester into the party** instead. The card and its handlers
now resolve the same notification through `inPlayerSocialCardNotification` and act on it by id.
This is `commonMain`, so desktop has the same defect today. It was **not** changed in
`nuviozdesktop` (closed for feature work). Carry it across with the next shared merge.

### Verification

- `:androidApp:compileFullDebugKotlin` passes.
- Android host suite **2,077/2,077**, zero failures, errors or skips. That is 2,065 plus exactly the
  12 new tests. ⚠ A first read of the results said 2,127: eight stale XML files from an earlier run
  were still in the results directory. The directory was deleted and the task run again with
  `--rerun`.
- Pure suites **695/695**.
- iOS CI run `35326357624` (a manual dispatch on `a53e0eb30`) **passed**: the Kotlin framework and the Xcode app
  compile with the new common UI. That shows it builds; it is not an iOS verification.
- The new files use only common APIs (FlowRow, `LocalClipboardManager`, and icons already used in
  `commonMain`), with no `java.*` or `android.*` imports. iOS compilation remains CI's gate.
- **Rendered.** An `ImageComposeScene` harness ran nine scenes at 915×412, 800×360, 640×360 and
  1280×800 dp. It ran in a throwaway, detached `nuviozdesktop` worktree, because the mobile repo
  has no desktop target; the worktree was removed afterwards, and desktop `Dev` and the desktop
  branches were untouched. Long names ellipsize, badges fit, the rail leaves the video visible, and
  the pill and card stack cleanly. Some lines broke mid-word in the render ("in st/ep"). That is
  probably the test renderer's text shaping, but **confirm it on a handset**.

### Pending physical checks for this surface

On a handset (S20+/S25+), with desktop as the reference peer:

1. The header button appears, opens and closes the rail, and system Back closes the rail before
   leaving the player.
2. The rail does not break immersive mode.
3. Start a party from the rail.
4. As host: switches, Who can join, invite a friend, copy the invite code, End → confirm.
5. As a guest: Leave.
6. An incoming join request: Let in and Decline, from both the rail and the pill.
7. Your own request: Cancel, then Join now / Not now.
8. The pill's Start anyway / Choose source / Don't wait each do what they say.
9. A party invite arriving mid-playback shows the card, and Join works.
10. Test 9 again with a join request also pending (the fixed bug).
11. The pill stays up while the player is locked, with no buttons.
12. Text wraps at word boundaries.

## Phase 6 Stage B client configuration landed (2026-09-18)

The mobile client now has an explicit, secret-free configuration path for the Nuvio Z Supabase
project (`pzbpghmmordvzcfbayoh`). `NUVIO_Z_SUPABASE_URL` and
`NUVIO_Z_SUPABASE_PUBLISHABLE_KEY` are read from ignored `local.properties` first and the process
environment second; blank configuration leaves the Social/Watch Together runtime unavailable and
never falls back to the official `api.nuvio.tv` client. The repository Actions secret
`NUVIO_LOCAL_PROPERTIES_BASE64` was updated out of band to include the two publishable-client
properties. No credential value was written to Git, and no backend deployment occurred.

`ZSupabaseProvider` installs Auth, PostgREST and Realtime and now explicitly keeps its derived Z
session and PKCE verifier in memory. `ZSessionBridge` remains the only bridge from the official
session. The application exposes an active profile to the Social repositories only while the
profile-scoped Social preference is enabled, so disabled mode cannot begin capability discovery,
session exchange or Realtime startup. `SocialRepository` retains `explicitNulls = false`, matching
the deployed `202609110002` JSON-null contract.

Read-only production checks reached the canonical Z host and returned both Social and Watch Party
capabilities enabled with party contract version 2. A linked schema query confirmed the current
`party_*`, `social_get_state_v2`, `social_publish_presence`, and `social_upsert_profile` RPCs. The
current party client still sends `authority_epoch`, matching production; the legacy compatibility
window is therefore retained rather than removed while older clients remain in circulation.

Verification: configured Android full-debug compilation passes; blank-config and configured-client
tests both pass; the complete Android host suite is **2,063/2,063** with zero failures, errors or
skips; and all pure suites pass (**695 tests**). The increase from the convergence baseline of
2,060 is exactly three new Stage B tests: configured Z-client initialization plus the disabled and
enabled runtime-gate cases.

Remaining verification debt is explicit. A real signed-in handset session is still needed to
exercise official-token exchange, profile/social calls, and an authenticated Realtime channel end
to end. Windows cannot compile the cinterop-backed iOS targets; the iOS workflow is the compiler
gate, and physical iOS behavior remains unverified until Apple signing/device access exists.

The feature branch is committed locally but cannot yet be pushed to GitHub. Although the controlled
merge's final tree contains no LFS pointers, its `desktop/Dev` second-parent history makes 142 old
desktop LFS objects reachable; GitHub rejects the ref because those objects do not exist in the
mobile LFS store. Both the ordinary LFS upload and a refs-only push were rejected. Resolving this
requires an explicit topology choice: retain the true merge locally, or reproduce the exact final
tree as a single-parent convergence commit that does not import desktop history. Until that choice
is made, mobile CI—including the manually dispatched macOS/iOS compiler gate—cannot run on this
branch. No LFS checks were disabled persistently and no missing binary was added to mobile.

## Phase 6 convergence implementation verified and committed (2026-09-18)

The controlled `desktop/Dev` merge is committed as `45ab72994` on
`claude/phase-6-convergence`, with no unresolved index entries and no `desktopMain` import. The
first compile corrected two Stage A classifications:
`AppFeaturePolicy.kt` is a shared contract rather than a whole-file mobile divergence, and
`strings.xml` must converge shared resource keys while preserving mobile-only values additively.
Keeping the old mobile files caused roughly 40 policy errors and 335 unresolved resource errors.

Shared Compose UI had also leaked JVM mouse APIs into `commonMain`. The three affected call sites
now use `PlatformPointerBackNavigation`: desktop retains mouse Back/Forward guarding and hover-wheel
dismissal, while Android/iOS actuals deliberately do nothing because system Back remains handled by
`PlatformBackHandler`. The matching desktop change is isolated on
`claude/phase-6-pointer-seam` as `2b8b8708`; local `Dev` still equals `origin/Dev`.
That branch is pushed. Its Windows CI MSI build passed, which compiles the desktop seam; the Linux
test job stopped earlier in the vendored compose-media-player native `frame_copy_test`
(`player != NULL`), before Kotlin desktop tests, and is unrelated to the pointer-only diff. Local
desktop compilation, all 695 pure tests, and 14 focused player navigation tests pass.

Verification on the committed mobile tree: all pure-suite groups pass (695 tests); Android full
debug compilation passes; the Android host suite reports **2,060/2,060** with zero failures, errors,
or skips; `git lfs fsck` passes; and every current common expect has an Android and iOS actual by
static inspection. The handoff's 2,059 figure was one lower, but no test source changed during the
pointer cleanup and a fresh generated JUnit report contains 2,060 tests. iOS native compilation is
disabled on Windows because of its cinterop targets, so CI/macOS compilation remains required and
no iOS runtime behaviour is claimed verified.

## Phase 6 Stage A closed: the convergence is a merge, not a port (2026-09-17)

Branch `claude/phase-6-stage-a`, commit `a6a78669`. Deliverable:
`Docs/PHASE-6-CONVERGENCE-INVENTORY.md`. **No product code was touched** — Stage A is a measurement
gate. Desktop received one docs commit on its own branch
(`nuviozdesktop` `claude/phase-6-stage-a-verification-record`, `5fd878a6`); `Dev` is untouched.

### The finding that resizes the phase

`nuvio-z` has **35 commits `desktop/Dev` does not contain, and 34 are documentation.** The one code
commit already landed on desktop as `276489e5`. So the 203 desktop-only and 286 differing shared
files are **desktop moving ahead of a static mobile**, not two-way divergence — there are no
competing mobile edits to reconcile against. A trial merge gives **0 conflicts in `composeApp/src`**
(2 total, both docs) and leaves `commonMain`/`commonTest`/`androidMain`/`iosMain` **byte-identical
to `desktop/Dev`**.

Stage A and Stage D are therefore **one mechanical merge**, and the plan's "repo topology — High,
and the real cost" is downgraded. Mobile's stale watchparty trio is overwritten as ordinary
content; "delete, do not reconcile" needs no deletion commit. `nuvio-z` now has `desktop` as a
remote, mirroring desktop's `mobile`.

### The merge is clean, which is exactly what makes it dangerous

Five silent reverts, **none of which git reports as a conflict**:

1. It reverts **all four documented-deliberate divergences** — `SetupHomeStill.kt` included, which
   has broken the setup wizard before.
2. It **deletes** the Android launcher icons, `nuvio-debug.keystore`, `.github/workflows/ios-build.yml`
   (the iOS CI Stage G depends on) and `Docs/UPSTREAM.md`/`PATCH-SURFACE.md`/`VANILLA-BUGS.md` —
   the docs `AGENTS.md` says govern every repo.
3. It imports 324 `desktopMain` files and **141 LFS pointers whose objects are not on mobile's LFS
   remote**. This aborted the first trial merge outright and forecloses the full-tree merge.
4. `samplePositionMs()` and `seekToExact()` arrive as **no-op defaults** (`= null`,
   `= seekTo(...)`) that **no** mobile engine overrides — media3, libmpv and iOS all inherit them.
   Compiles, runs, never converges.
5. ⚠ **Desktop declares no `androidTarget` at all.** Its `androidMain` is dead source no compiler in
   that repo has ever read — and it has already rotted: `c9e47509` ("add NVIDIA RTX VSR support", a
   desktop-only feature) deleted the `platformDisplayMaxHeight` actuals from `Platform.android.kt`
   *and* `Platform.ios.kt`, while `commonMain` still declares the `expect` and calls it. Taking
   desktop's platform source sets wholesale **breaks the Android and iOS builds**, silently.

So "byte-identical to desktop" is the right goal for `commonMain`/`commonTest` and **not** for the
66 `androidMain`/`iosMain` files, which stay a reviewed subset. `Docs/PHASE-6-CONVERGENCE-INVENTORY.md`
§5 is the executable procedure, with assertions rather than eyeballs.

**Standing rule this exposes:** desktop can silently break mobile's platform source sets, because
nothing in its CI compiles them. Every desktop→mobile sync must gate on the *mobile* compile.

### Also corrected

- **`scripts/shared-code-drift.sh`** pointed at `desktop/claude/upstream-doctrine-stage0`, deleted
  long ago, so it exited 2 rather than reporting. Now `desktop/Dev`, and it no longer counts
  whitespace-only drift (498 → **495**, agreeing exactly with an independent measurement).
- **`AGENTS.md` rule 5's CRLF warning does not apply to blob comparison.** `.gitattributes`
  normalizes to LF in the index; across all 495 differing files **not one** differs by line endings
  alone. The 3 phantoms are a trailing blank line. The warning remains true for working-tree diffs.
- **`Z-FEATURES.md` revision 9** — the state sweep revision 8 asked Stage A for. 54 rows move from
  `**branch**` to a new `shipped **DSK**` (desktop's `Dev` tip *is* the `z6` bump, so everything
  before it shipped). Shipping is not verification: rows saying *unverified on hardware* keep saying
  it. Three rows carried the missing-cell defect the Phase 2 audit fixed for **P11**; on **C19** the
  shifted column was one step from recording a web-only fix as a desktop release.
- **`PHASE-4-TWO-CLIENT-VERIFICATION.md`** — its header claimed it "records no manual result", which
  stopped being true when the 2026-09-10 retest was appended below it. Phase 6 planning read the
  blanket `NOT RUN` as "never worked". Added: three-user field use with host transfer through
  `z2`..`z6`, recorded as weaker evidence than the 2026-09-10 run, with every table row still
  `NOT RUN`. Consequence: **desktop is a trusted reference peer** for Android↔desktop testing.

**Stage B (backend repointing) is next.**


## Phase 6 opened and rescoped: Social **+ Watch Together** to mobile (2026-09-17)

**Planning only. No code was touched in any repo.** Deliverables: `ROADMAP.md` (Phase 6 rescoped,
Phase 9 (now Phase 10) TV target set, stale ordering/review-budget text corrected), `Docs/Z-FEATURES.md` revision 8
(Android/iOS/TV targets), and `PLAN-phase-6-social-watch-together-mobile.md` at the workspace root.

⚠ **`ROADMAP.md` and the `PLAN-*.md` files are not under version control** - the workspace root has
no `.git`. Only `Docs/Z-FEATURES.md` and this file are committed. Anyone reading a Phase 6 commit
should not expect to find the roadmap or the plan in its diff.

### The rescope, and what it rests on

Phase 6 was "Social to mobile", with Watch Together deliberately staying desktop-only. It now
carries **both** to Android and iOS. That was not assumed - the load-bearing question was whether
Phase 4 genuinely separated the party architecture from desktop player ownership, and it was checked
against the code:

- **All 35 Watch Together files are in `commonMain`. Zero in `desktopMain`.**
- `features/watchparty` and the party player glue contain **no** `java.*`, `javax.*`, AWT, Swing or
  `System.getProperty` reference at all. `WatchPartyBarrier.kt` is deliberately import-free.
- Party code reaches the player **only** through `PlayerEngineController`, whose `samplePositionMs()`
  and `seekToExact()` exist purely for sync and whose documentation already reasons about Android's
  250ms polling and mpv's keyframe under-shoot.

So the durable model, membership, invites, join requests, Direct/Ask/Off, authority, host transfer,
lifecycle, transport, sync, drift, source descriptors, matching, readiness, recovery and episode
handoff are **reused unchanged**, and the platform work is the player adapter, lifecycle, navigation,
source realization, phone UI and QA.

### Four findings the plan has to absorb

- **The obstacle is repo topology, not architecture.** No shared module - two forks kept in step by
  merge. 690 desktop vs 587 mobile `commonMain` files, **103 desktop-only, 204 shared files already
  differing**, 383 byte-identical. `nuviozdesktop` has `mobile` as a remote; **`nuvio-z` has no
  `desktop` remote** - adding it is Stage A step one. Measure with `scripts/shared-code-drift.sh`.
- **Mobile's party code is a stub to delete.** Three files from 2026-09-01/02 against desktop's 35,
  with no barrier, clock, drift, protocol, transport, coordinator, session state or realizer - and
  it calls the *official* `SupabaseProvider`, which hosts none of the `party_*` RPCs. It cannot ever
  have worked. Delete, do not reconcile.
- **Mobile's `PlayerEngineController` is an older fork** - no `samplePositionMs`, `seekToExact`,
  `trySeekTo` or `releaseBeforeNavigation`, and neither repo's `androidMain`/`iosMain` implements the
  two sync methods. Android carries **two** engines (media3 + `NuvioLibmpvView`), so the mpv seek
  trap applies there too, not only on iOS.
- **Background lifecycle is genuinely new.** Desktop never backgrounds, dozes, loses cellular or
  takes a phone call mid-party. None of its experience transfers.

### Ledger corrections made while doing this

- **S1, S2 and S3 read `yes`/`yes*` on AND/iOS. That was false** - the code is present but points at
  `api.nuvio.tv`, which hosts no `social_*` or `party_*` RPCs, so it has never functioned on a
  handset. Corrected to targets.
- **W10 added.** The built-in Z source setup (AIOStreams + TorBox) shipped on desktop in
  `0.1.23-alpha-z4` **with no ledger row at all**. It is `iOS = **defer**`, to minimise App Store
  review risk - deferred and unresolved, not solved, with no workaround and nothing designed to hide
  functionality from review. It costs iOS nothing architecturally: addons are per-profile and sync
  through the official backend, so iOS inherits a source set configured on Android or desktop.
- Two new legend markers, `**defer**` and `**publish**`, so a *planned* state is expressible without
  corrupting current-state columns. `port`, `defer` and `publish` are all targets; a row becomes
  `yes` only in the commit that makes it true.
- **§12 gained the iOS physical-verification debt entry**, and a correction to the desktop Watch
  Together entry (below).

### Verification ground truth (standing rule, from the maintainer)

Recent desktop builds have had **real three-user party use, host transfer included**, and it held up.
No formal matrix has been walked. So `PHASE-4-TWO-CLIENT-VERIFICATION.md`'s blanket `NOT RUN`
**understates** reality; what is genuinely open is that the untested edges are unknown rather than
known-good. Practical consequence: **desktop is a trusted reference peer** for Android↔desktop
testing. Stage A records what has actually been observed beside the still-unwalked table.

### Decisions

- **Ultra 2 is held for Phase 8 (iOS).** It is the last non-renewable run. The roadmap contained
  contradictory history - an allocation table saying #2 was banked on 2026-09-06, and a prose
  paragraph saying all three were spent by Phase 4. The table and the dated decision win; the prose
  is corrected in place rather than quietly overwritten. Phase 6 uses `/code-review high --fix` per
  stage and `/security-review` on Stage B.
- **Hardware: Galaxy S20+, S25+, desktop.** Android↔Android is not redundant with Android↔desktop -
  two mobile peers is the only pair exercising both ends backgrounding, both on cellular, and two
  doze timers at once.
- **iOS is implemented in Phase 6, and honestly unverified.** No Apple Developer account, so no
  device run. Phase 6 maximizes shared code, compiles in CI, leans on pure tests, and keeps a precise
  **physical-verification debt checklist** for Phase 8 to discharge. Nothing untested is called
  verified, and no iOS row becomes `yes` on the strength of a compile.

### Two risks worth stating outside the plan

- **The Android seek-landing check (Stage E) is the highest-value single test in the phase.** An
  under-shooting `seekToExact` is re-measured as the same gap and **the guest never converges** - it
  presents as "sync is broken" and costs days if it is not caught at the seam.
- **Mobile has no FCM/APNs.** In-app notification state ports without push, but a party invite has
  **no delivery path while the app is backgrounded**. Open product question, to settle in Stage C
  rather than discover in Stage F.

**Nothing in Phase 6 has started. Stage A is next.**

## Desktop is consolidated and closed for feature work; Phase 6 is next (2026-09-16)

**No mobile code was touched.**

Desktop's completed work had split across two branches that both forked from the same commit and
**neither of which contained the other** - the 2,016-vs-1,990 desktop test counts were the symptom.
They are now merged onto one canonical branch, `nuviozdesktop`'s **`claude/desktop-consolidation`**,
and gated there: `compileKotlinDesktop` green, `desktopTest` **2,050 tests / 0 failures** with results
cleared first, all eight pure-suite groups green, backend pgTAP 12 files / 286 tests PASS. Detail and
the merge-conflict reasoning are in `nuviozdesktop/STATUS.md`.

**Desktop feature development through the current roadmap work is complete enough to move on.** What
is included: Phase 5 onboarding, Social + Watch Together stabilization and the UX pass, the later
Direct Join / Next Episode / party-lifecycle fixes, the friend-machine playback fixes, source-language
inference and "Prefer built-in subtitles", and the unified playback-preferences pass. Remaining desktop
issues are **backlog and QA debt, not an active development phase**.

⚠ **Broad physical QA is deferred, and nothing on the consolidated branch has been on hardware.** The
friend's previously reproducible playback failures *did* pass on his dedicated test build, but that
build was `01524bb1` - before the built-in-subtitle work and on the other side of the merge from the
preferences pass. A short checklist covering only the changed seams is at the workspace root
(`HANDOFF-desktop-consolidation.md`).

### What Phase 6 inherits that the earlier entries do not already list

Two feature areas landed on the branch the previous desktop write-ups never saw, so they are new rows
in `Docs/Z-FEATURES.md` (**S15**, **S16**, **P18**) and new porting work for mobile:

- **S15 - the language slot tells the truth or says nothing.** The band printed a release-name claim
  verbatim, and a release name can only ever *confirm* a language, never deny one. English is the
  unmarked case, so English releases showed nothing and the blank read as "no language". Audio evidence
  and subtitle evidence are now split; the title's own language is read **synchronously from already-
  loaded meta** so the band cannot change under the reader; and a production country is never read as a
  language on this path, because this value is displayed.
- **S16 - Prefer built-in subtitles.** Off by default, automatic picks only, nothing fetched or probed
  before open, and post-open verification against mpv's `track-list`. Mobile's player will need its own
  answer to the verification half. **The quality panel deliberately says nothing about subtitles** - a
  chip was built and reverted the same day on hardware evidence; do not re-derive it as missing work.
- **P18 - premature EOF and probing after the player opens.** The preflight probe ran from the app's
  own connection, so an AIOStreams link bound to the *player's* egress IP failed as **Wrong IP** on a
  source that would have played. Mobile shares the probe (P16) and so shares the trap.

## The desktop preference pass changes shared playback rules Phase 6 inherits (2026-09-16)

**No mobile code was touched.** Desktop landed the playback-preferences cleanup on
`claude/social-wt-ux-pass` (ledger: `nuviozdesktop/STATUS.md`, 2026-09-16). Mobile has the same defect,
the same files and the same sentinels, so this is a port rather than a desktop quirk.

**The defect.** `preferredAudioLanguage` ships as the sentinel `device`, and `rankableAudioLanguage`
stripped `device`/`default`/`original` to null before `SourceRanking` saw it. `LanguageStrictness.REQUIRE`
- the shipped default - was therefore **inert for every profile that had never opened the language
dialog**, and "original audio, subtitles in my language" could never influence which file opened.

What a Phase 6 merge must carry, and must not "fix back":

- `features/playback/PlaybackLanguageResolution.kt` is **new, pure and import-free** - copy it. It is the
  only place the sentinels are interpreted for ranking: `device` to the OS locale, `original` to the
  title's own language, everything else that names no language to **no opinion rather than a guess**.
  `rankableAudioLanguage` / `rankableSecondaryAudioLanguage` are deleted.
- `features/playback/PlaybackSelectionContextFactory.kt` is **new** - one builder for every
  `PlaybackSelectionContext`. Three hand-built copies had drifted; the in-player next-episode one set 6
  of 13 fields, so episode 2 was chosen without the ceiling or the language rule episode 1 honoured.
- `SourceRanking` gains `subtitleLanguageBonus`: a separate comparator key below `languageScore`,
  **promoting only**. Source names carry subtitle metadata far less reliably than audio, so a release
  that names nothing must score the same as one that names the wrong thing.
- **`playbackLanguageStrictness` now defaults to `PREFER`, not `REQUIRE`.** Deliberate and paired: the
  sentinel fix makes the setting live for the first time, and shipping it live *and* strict in one change
  would alter what plays for every existing install on a preference none of them stated.
- A one-shot migration keyed on `playback_language_migrated_v1` (synced) writes the resolved device code
  over the sentinel. It must run **before** the first sync import - on desktop it lives in `loadFromDisk`,
  which `ProfileSettingsSync.ensureRepositoriesLoaded()` calls first. The subtitle preference is **not**
  migrated: `none` is a deliberate answer, not an unanswered question.
- `playback_mode_selector_seen` is **deleted** as a preference and kept in `syncKeys` as a tombstone, so
  an older client's payload still clears the orphaned local value. `intro_submit_enabled` was decoded on
  import, never exported and missing from `syncKeys`; it now round-trips.
- Settings IA: one **Language** section holding all four language rows, never greyed on playback mode
  (they drive the player's own track selection, which runs in Classic too). Mobile's Subtitles page keeps
  the appearance rows and a pointer.
- The setup wizard is **revision 8** on desktop with a new `SetupStep.Language` shown in all three mode
  branches. Mobile is still on revision 6; adopting the step means adopting 7 and 8 together.
## The stabilization pass's second hardware run changed shared rules Phase 6 inherits (2026-09-15)

**No mobile code was touched.** Desktop fixed six hardware defects on `claude/phase-5-onboarding`
(ledger: workspace-root `PLAN-social-watch-together-stabilization.md`, Stage 16). The platform-neutral
parts a Phase 6 merge must carry, and must not "fix back":

- `PartyContentSwitch.kt` - `ownsNextEpisodeChoice` is read against the party's **title**, not
  `matchesPlayback`; a guest behind the host mid-transition does not own its next episode. And
  `decidePartyContentHandoff` takes the host's in-flight publish latch, or the host is pulled back to
  the episode it just left.
- The guest content handoff realizes from the **episode** streams catalogue it requested
  (`partyEpisodeCatalogueFor`), never from the sources panel's.
- `PartyTermination.kt` - a guest is **never** sent "ended": `party_close_ended` stamps every member
  `left_at`, so every member RPC afterwards raises `party_membership_required`. Confirm through
  `party_get_active` and conclude locally. `observePartySnapshot` owns the terminal transition.
- `WatchPartyLobbyExit.kt` - the lobby is on the back stack exactly as long as its party; a system
  back (Android Back, desktop Escape) asks to leave, never pops.
- `WatchingNowJoin.kt` - nothing on the backend tells a requester it was accepted or a host that a
  direct join promoted its playback; both are discovered through `party_get_active`.
- `playerOpeningPresentation` - a next episode draws the show logo like a first launch.
- `PlayerEpisodeModeRouter` changed for **desktop only**: Streamlined auto-picks within its
  preferences in the player. Mobile Streamlined still opens the quality sheet.

## A desktop stabilization pass changed what Phase 6 inherits (2026-09-11)

**No mobile code was touched.** Desktop ran a **Desktop Social + Watch Together Stabilization Pass**
- a named pre-Phase-6 release gate, **not** a numbered phase; Phases 6-9 are unchanged. Ledger:
workspace-root `PLAN-social-watch-together-stabilization.md`. Stages 0-11 are code complete on
`claude/phase-5-onboarding`; the physical matrix is **not** run and the pass is **not closed**.

⚠ **One backend change is already deployed and Phase 6 depends on it.**
`202609110002_json_null_is_an_absent_payload.sql` makes `sanitize_source_descriptor_v2`,
`sanitize_party_track_intent` and `sanitize_party_content` treat an explicit JSON `null` exactly as
they treat an absent key. **Mobile would have hit this the moment it was repointed at the Z
backend**: kotlinx defaults `explicitNulls` to true, and a nullable field serialized as an explicit
null was aborting `social_publish_presence` outright - which is why Watching Now showed nobody on
desktop for three days. Desktop also fixed it at its encoder (`explicitNulls = false` on
`SocialRepository`'s Json); **mobile should do the same rather than rely on the backend alone.**

What Phase 6 inherits beyond the Phase 5 list below, all of it platform-neutral:

- `features/watchparty/PartyContentSwitch.kt` - the host-only next-episode content change,
  `decidePartyContentHandoff`, and `ownsNextEpisodeChoice` (guests get no countdown).
- `features/watchparty/PartyLaunchArtwork.kt` - local artwork hydration, because the party wire
  carries identity and not presentation. **Do not widen the wire to ship artwork URLs.**
- `features/social/SocialCards.kt` - `SocialActivityCard` and `SocialWatchingNowCard` over shared
  primitives. ⚠ **Do not reuse `TitlePresentationCard` for social content**; that is the mistake
  this file exists to undo, and a phone has even less room to spare for it.
- The `PlaybackModeRouter` rule that a party host's source choice is routed by the *host's* mode,
  with no party-specific input on `PlaybackRouteInputs`.

⚠ Watch Together itself remains **desktop-only**; mobile still points at `api.nuvio.tv`.

## Desktop Phase 5 changed the shared setup model — mobile is now behind (2026-09-10)

**No mobile code was touched, deliberately.** Desktop's `claude/phase-5-onboarding` rebuilt the
setup wizard as revision 7, and `features/setup/SetupWizardSteps.kt` — until now byte-identical
across both repositories — has diverged. Mirroring it here on its own **breaks this build**: this
repo's `SetupWizardScreen.kt` and `SetupDiagram.kt` reference `SetupStep.Cards`, `Home` and
`Details` in nine places, and revision 7 deletes all three. Reconciling means porting the wizard
body, which is Phase 6 work rather than a merge.

What Phase 6 can take wholesale, all of it import-free or platform-neutral:

- `features/setup/SetupWizardSteps.kt` **entire** — the nine-step model, `SetupWizardPlan`,
  `PlaybackSetupVariant` + `playbackSetupVariant` (the playback branch rule), `SocialIdentityProbe`
  + `resolveSocialFeaturesEnabled` (the social migration rule), and `SETUP_WIZARD_REVISION = 7`.
- `features/social/SocialFeaturePreferences{Repository,Storage}` — the app-level social preference.
  ⚠ Its own repository with its own `ProfileSettingsSync` payload, **not** a field on
  `PlayerSettingsStorage`; the android and ios `actual`s are already written on the desktop branch.
- `SocialFeatureGate`, `SocialFeatureShutdown`, `SocialIdentityBody`, `coerceAvailableTab`.

What needs a phone design rather than a port: the wizard body and the Settings social page.

Still identical and to be left alone: `SetupModeStoryboard.kt`, `SetupSampleTitle.kt`.
Still divergent by design: `SetupHomeStill.kt` — its header forbids `cp`.

⚠ **Roadmap renumbering.** Onboarding took the Phase 5 slot on 2026-09-10, so "Social to mobile" is
**Phase 6** now. Anything in this repo or in the handoffs that says "Phase 5 repointing" means
Phase 6.

## Active work

| | |
| --- | --- |
| Active branch | `codex/watch-together-architecture` in both KMP repositories. |
| Current work | Watch Together deterministic architecture. Stage 4 is `DONE`; Stages 5 and 6 are at automated checkpoints and Stage 7's deletions are begun. Stages 2, 3, 5 and 6 remain open only for recorded physical gates, Stage 7 on the full physical matrix, and the Stage 5 migration awaits `supabase db push`. Persistent ledger: workspace-root `PLAN-watch-together-architecture.md`. |
| Verified | Desktop `7365d45`: compile passes, focused Stage 2 tests 32/32, and mandatory full `desktopTest` 1,687/1,687. Debug-tools MSI is packaged; physical Stage 2 timing and recovery evidence is not run. |
| Constraint | Do not start Stage 3 until Stage 2 proves p95 command delivery below 500 ms/no sample above 1 s, local directive below 50 ms, truthful degradation/recovery, and per-member labels on two clients. Faster durable polling remains out of scope. |
| Join invariant | Joining an existing party always exits any active player through Phase 2F and launches a fresh party attachment/`PlayerRoute`; only explicit creation around current playback may promote in place. |

Stage 7 begins at desktop `5ba9858b` with the deletions that are provably safe. `WatchPartyUiState`
no longer caches `connection` or `connectionBannerMessage`: both were copies of projector output that
made the repository a second presentation authority, and the banner field had no reader at all. The
Stage 1 shadow comparison against the legacy snapshot is gone, having had no reader since the
switchover. Four further Stage 7 targets were checked and need nothing: location publishing is
already intent-driven rather than disposal-driven, and Stages 3, 4 and 5 removed the destructive
lobby flow, the repository launch latch and the duplicate host claim. v2 contract removal is
deliberately not started - mobile still calls the v2 party RPCs, so it waits on the Phase 5
repointing. Stage 7's exit is the full physical matrix and remains open.

Stage 6 desktop implementation `f98adb69` makes an active party source switch in-route. A member
picking from the player's own sources panel moves the whole party, gated on matching content, on
permission - host always, guest only while collaborative - and on the pick not being the source the
party is already on; the advance names the generation it expects, so simultaneous picks produce one
advance and one rejection, and a local latch prevents a retry advancing it twice. Every other member
adopts it in place: the player loads its own catalogue, runs the Stage 4 strict matcher over it and
hands off with the same `switchToSource` an in-player pick uses, with the old source playing
throughout and route, controller and HWND untouched. A member who cannot realize the new pick reports
`choosing_fallback` and keeps playing what they have - no silent generation rollback, and no retry
against a catalogue that has already answered. The player's party identity key now carries the whole
authority tuple rather than party and content generation alone, and an active player spends the
automatic-launch claim for the authority it is playing. Focused party/player tests 289/289 and
desktop compilation pass; the two-client switch gate is physical and outstanding.

Stage 5 pairs desktop `ca8677a4` with backend `b681c45` and is deliberately the minimum the Stage
1-4 client evidence proved necessary. The party state broadcast now carries `authority_epoch`, which
a transfer bumps alongside the sequence - without it a client installed the new host under the epoch
that transfer replaced and then rejected every command that host sent. The member broadcast fires on
`client_location`, the one member field the presentation reads that nothing announced, while a bare
`last_seen_at` write stays silent. Liveness has one owner per question: 15 seconds is host-transfer
grace only, a member is offline at 20 in both the heartbeat and the reaper, and a party is abandoned
at 60. Host transfer has a single implementation - `party_transfer_stale_host`, restricted to live
members - and the client's duplicate grace-and-claim race is deleted; `party_claim_or_transfer_host`
survives as a delegating RPC because mobile still calls it. On the client, broadcast application is
now a pure typed function that treats an authority advance as an invalidation. pgTAP 165/165 on a
fresh local database, focused desktop tests 280/280, desktop compilation green. **The migration is
committed but not deployed:** `supabase db push` against `pzbpghmmordvzcfbayoh` is the maintainer's
to run, and both directions are compatible so client and backend may land in either order.

Stage 4 desktop implementation `3940ddb0` moves party source realization out of navigation and into
a process-scoped `PartySourceRealizer` keyed on `(partyId, contentGeneration, sourceGeneration,
descriptor)`. It owns the work states, the one-shot automatic-launch claim, and the sensitive
resolved `PlayerLaunch`, of which only an opaque realization ID is ever exposed; every entry point
is rejected unless it names the current authority, so work that completes after a source change can
neither report into nor resolve for the party as it now is. The repository launch latch, the
party-launch retention methods on `PlayerLaunchStore`, and the composition-local strict-match rule
are all removed - matching and the settle gate are now pure functions that need no composition to
exercise. Readiness is published from realizer transitions rather than from route lifecycle, so the
matching and resolving window the host's wait gate reads is finally reported. Realization is dropped
on generation advance, leave, end, profile change and account wipe; returning from the player to the
lobby reuses the retained realization without a `StreamRoute` and cannot re-arm the automatic launch.
Focused party/player tests 270/270, the mandatory Stage 4 full `desktopTest` gate 1,708/1,708 with
zero failures, and desktop compilation pass. Stage 4 has no physical gate of its own.

Stage 3 desktop architecture is now at an automated checkpoint: the active player owns Party Room
open/closed state, Back/Escape closes it before player exit, the destructive active-player lobby
command is gone, and a typed Kotlin view state drives native participants, health/sync, content,
invitations, settings, lifecycle, and recovery UI. Existing-party joins use an explicit
`OpenPrePlaybackLobby` outcome and never promote the current player. Focused tests, JavaScript
syntax validation, and desktop compilation pass; physical UI/controller/HWND verification remains.

The pending desktop Stage 2 stabilization checkpoint fixes the physical-test auth/Realtime race
without changing mobile code: rejected Z sessions are replaced atomically without publishing an
intermediate unauthenticated state; only HTTP 401 triggers re-exchange; platform auth auto-setup is
disabled; Realtime close/send cancellation is lifecycle-safe; and player removal no longer mutates
Swing visibility after Compose has disposed its `SkiaLayer`. Focused auth/transport/airspace tests
and desktop compilation pass. The physical two-client sync/leave gate remains outstanding.

Stage 2 desktop implementation commit `7365d45` moves authenticated private-channel lifecycle,
reconnect, protocol collectors, acknowledged broadcast sends, generation invalidation, and live
health reporting into the Realtime transport. Accepted local directives now precede both
asynchronous Realtime send and asynchronous durable persistence. Fresh peer telemetry is projected
per member on every client while only the host consumes it for the unchanged wait-for-everyone
hold logic. A pure presentation projector now supplies shared connection banners, fresh host state,
and participant labels to the lobby and native player surface; global durable party status is no
longer used as a participant engine proxy. Desktop handoff commit `a5aba1a0` records the full
verification and MSI hash. Backend authorization fix `67d4ced` was already deployed and its live
migration history repaired; no backend work was repeated in this checkpoint.

Stage 1 introduces domain-only durable/live seams and a serialized process-scoped session reducer,
with attachment loss distinct from lobby entry and a shadow comparison against the legacy
repository snapshot. Health now separates API reachability, polling/heartbeat, Realtime channel
lifecycle, send outcomes, peer freshness, and clock freshness; subscription/send success alone does
not claim live delivery. Durable heartbeat moved out of player composition into the process-scoped
poll and uses only fresh exact-generation player telemetry. Existing backend RPC/broadcast
contracts, generation validation, fallback cadence, and wait behavior are unchanged; no backend
deployment occurred. Detailed implementation and verification evidence is in the desktop handoff.
Desktop implementation and handoff commit: `2a05e116`.

Stage 0 instrumentation is implemented on the desktop branch: privacy-safe debug traces cover
T0–T4, Realtime send outcomes, channel instances, durable poll/broadcast arrival, independent
observed transport facts, clock/tick freshness, and guest hold transitions. Desktop compilation
passes; focused Watch Together/player-launch tests pass 96/96; the preceding complete instrumentation
revision passed the full desktop suite 1,674/1,674. Physical two-client evidence remains **NOT RUN**,
so Stage 0 remains active and behavioral work is blocked by design. See desktop
`WATCH-TOGETHER-STAGE0-TRACE.md` for the exact matrix.

The Gradle-managed JetBrains JDK completed release-style packaging with debug tools enabled. The
physical-test artifact is
`nuviozdesktop/composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
(258,631,455 bytes; SHA-256
`133AEEA118C756CC326DD4EC33CA85B7F84A1C7513DED44476176DBC92E63F25`). All three generated and
published MSI copies are byte-identical. This verifies packaging only; Stage 0 remains blocked on
the physical run.

A partial two-client run on one physical Windows machine now reproduces the reported delay. Five
pauses, four resumes, and three seeks show every host T2 broadcast returning success while the guest
receives zero timing-plane commands, clocks, or ticks despite both clients reporting
`realtime=subscribed`. The guest instead follows durable state after 1,045–6,097 ms (3,560 ms median
across 11 observed user sequences); one short pause is coalesced before guest observation. This
attributes the symptom to absent peer live delivery plus durable fallback, but does not yet establish
why the private channel delivers nothing. Seven more seeks and the controlled Realtime
interruption/recovery segment remain outstanding, so Stage 0 remains active and Stage 1 remains
blocked. Detailed evidence is in desktop `WATCH-TOGETHER-STAGE0-TRACE.md`.

The maintainer explicitly accepted the attribution and closed Stage 0 without the remaining five
ordinary seeks or controlled interruption/recovery segment; those cases are **SKIPPED**, not passed.
Stage 1 is now `IN_PROGRESS`. The absent private-channel delivery remains unsolved and Stage 1/2
must diagnose and correct the live transport/health architecture, not hide it with faster polling.

## Phase 4 follow-up hardening: lifecycle resilience, truthful presence & UI fidelity (2026-09-08)

Completed an ironclad hardening pass for Watch Together covering disconnects, app exits, stale parties, reconnects, lobby/player transitions, truthful presence, and remaining Phase 4 UI issues:

1. **Friends Recently Watched Presentation Fidelity:**
   - Unified `TitlePresentationCard` with Continue Watching presentation modes: `Card` (landscape artwork with dark gradient overlay, top-right duration badge, bottom-left title/episode metadata, bottom progress bar), `Wide` (horizontal split card with fixed-width artwork strip and structured metadata block), and `Poster` (vertical 2:3 poster card with title block below).
   - In `HomeSocialSections.kt`, wrapped `SocialHomeRow` in `BoxWithConstraints` to dynamically evaluate layout style and card metrics (`continueWatchingLandscapeCardMetrics`), eliminating fixed 310.dp sizing and matching Continue Watching styling across all viewports.
2. **Notification Card Differentiation & Metadata Projection:**
   - Social notifications now render with contextual headers ("Watch Together", "Friend Request") and distinct social icons (`Icons.Filled.People`), dropping the misleading playback-resume header/icon.
   - Fixed backend `social_get_state_v2` and `SocialNotifications.kt` to project complete `content_summary` (`content_id`, `content_type`, `video_id`, `title`, `poster`, `release_year`, etc.), ensuring Watch Together invitation/join prompts display the target media's artwork and title.
   - Progress bar is suppressed on notification prompts (`showProgress = false`).
3. **Truthful Disconnect & Offline Presence:**
   - Expanded member status model (`DerivedMemberStatus` & `PartyReadyTone`: Ready, Working, Paused, Buffering, Reconnecting, Failed, Offline) across lobby tiles, player pills, and CEF HTML controls.
   - Reconnecting or disconnected members are honestly reported as "Reconnecting..." or "Offline" rather than claiming "Ready" or "Playing Together".
   - Local playback continues uninterrupted during network outages while honest offline banners and member statuses reflect actual connection state.
   - Reconnect heartbeat and epoch recovery restore live membership without duplicate entries.
4. **Stale Party & Content Guarding:**
   - Added content-identity guard in `resolveWatchPartyEntry`: entering a watch party for a specific target content verifies whether any held or active party matches the exact content; mismatched parties are automatically departed/closed first.
   - Backend migration `202609080002_party_lifecycle_and_notification_hardening.sql` deployed and recorded on Supabase project `pzbpghmmordvzcfbayoh`: provides `party_set_client_location` (updates `last_seen_at` and presence), automated stale reap (`party_reap_stale`), and snake_case `content_summary` projection in `social_get_state_v2`.
5. **Verification & Artifacts:**
   - Backend pgTAP tests pass: 153/153 tests green across all 6 test files (`stage14_lifecycle_hardening.sql` included).
   - Pure test suites pass: 495/495 tests green across all 8 groups.
   - Desktop test suite passes: 1,674/1,674 tests green in `:composeApp:desktopTest` with zero failures, errors, or skips.
   - Release-style debug-tools MSI packaged: `nuviozdesktop/composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,619,167 bytes; SHA-256 `935F0A239BA845AE714B50BF7F056B519A716028BB3E85B293FE792CDA29D937`).
   - All physical verification scenarios in `PHASE-4-TWO-CLIENT-VERIFICATION.md` remain marked **NOT RUN** for physical maintainer validation; Phase 4 is not complete.

## Phase 4 Stage 14 desktop lifecycle correction ready for physical retest (2026-09-08)

The desktop PlayerRoute/lobby failure was a client lifecycle conflation, not a backend-contract defect. Player disposal downgraded durable readiness to `resolving`, while lobby Start invoked `party_begin_source_selection` again; that correctly advanced `source_generation`, cleared the selected descriptor, and reset members to `waiting_for_host`, but was wrong for a route-only detach/reattach. Desktop commit `25e8508b` now preserves party/content/source authority across lobby transitions, reuses the exact process-local resolved launch when valid, locally rematches the same authoritative descriptor when needed, rejects delayed older snapshots, serializes member location/readiness mutations, and prevents party resolution from entering the ordinary source-list surface. Real content/source generations still invalidate local realization and staged picks.

Desktop verification passes: pure suites 495/495, focused lifecycle/player/navigation/surface tests, full desktop tests 1,672/1,672, and desktop compilation. Replacement debug-tools MSI: `nuviozdesktop/composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,590,495 bytes; SHA-256 `67C32A059D7866A21B478AC46A5FD346CA551825F46BD794B506DA5D744796D6`). The native bridge was unchanged and reused. No backend code/schema was changed or deployed. Stage 14 remains pending maintainer physical retest and is not marked PASS; Phase 4 remains incomplete.

## Phase 4 Stage 14 live source-selection hotfix (2026-09-08)

The first physical two-client run exposed a backend contract bug: desktop's repository serializer legitimately emitted nullable `info_hash` and `file_index` properties as JSON `null`, but `sanitize_source_descriptor_v2` treated the presence of the `file_index` key as a supplied integer and raised `invalid_file_index` for a normal non-torrent source. Additive migration `202609080001_accept_null_party_file_index.sql` now treats explicit JSON null like an omitted unknown index while retaining rejection of negative indices and real indices without an info hash. It is deployed and recorded only on Nuvio Z project `pzbpghmmordvzcfbayoh`; live behavioral probes pass.

Desktop production code and the Stage 14 MSI are unchanged. Focused descriptor/source tests pass, pure suites pass 484/484, and the reset-backed backend suite passes 142/142. The physical two-client source-selection scenario remains pending maintainer retest and is not marked PASS.

## Phase 4 desktop: automated gate green; watched matrix pending (2026-09-08)

Desktop Stages 1-13 are implemented in `nuviozdesktop`. Stage 12 now uses the shared title presentation on both Home and Social surfaces and retires `SocialActivityChip`. Verification passes: native controls 7/7, pure suites 481/481, desktop compilation, targeted player/navigation/social/watchparty tests, and full desktop tests 1,647/1,647 with zero failures/errors/skips. A release-style debug-tools MSI with a freshly rebuilt Windows native player bridge was built (252,670,144 bytes; SHA-256 `9083BFE6B7A2C1C579475635CE532612EF1CD1CCA27C4475DB8C568BF1B7A4B9`). Backend pgTAP passes 130/130 after the reconciliation migration was updated to preserve `party_member_broadcast_update` across the live `source_match` type conversion (`nuvio-z-backend` commit `3ddc6eb`).

The two scoped Phase 4 migrations, `202609030003` and `202609040001`, are deployed and recorded on Nuvio Z Supabase project `pzbpghmmordvzcfbayoh`. Live catalog verification confirms the four new enums and labels, `watch_join_requests`, all Phase 4 columns, both partial unique indexes, the checked-in RPC signatures, lifecycle/Realtime helpers, and the Realtime validator policy. `get_social_capabilities()` reports contract version 2 while preserving `social_enabled=true` and `watch_party_enabled=true`; live sanitizer probes reject URI-, URL-, header-, and credential-bearing source descriptors. No client scenario was run during deployment.

Phase 4 is **not complete**: the exact two-client physical matrix is prepared in `nuviozdesktop/PHASE-4-TWO-CLIENT-VERIFICATION.md` with every result marked `NOT RUN`. `ROADMAP.md` and feature-completion docs remain unchanged until that real matrix passes.

## Phase 2 follow-up: Seamless desktop player handoff (2026-09-07)

Watched desktop MSI update: Maintainer watched verification has been completed and Phase 2F is **COMPLETE / ACCEPTED (2026-09-07)**. Confirmed working in real use: mixed HDR/DV presentation (`HDR10/DV`), clean source/play to loading, 1dp startup-airspace gate preventing white-screen occlusion, real-first-frame promotion (`hasFirstFrame`) eliminating pre-frame flash, canonical failover ranking integrity, and one-press Escape directly popping to previous screen without returning to stale loading or white/black flashing. Full detail is in `nuviozdesktop/STATUS.md`.

**Accepted Presentation Limitation:**
- Desktop transitions involving the heavyweight native AWT/Win32 player (`NativePlayerHost` HWND) are now functionally clean and substantially smoother, but player <-> Compose transitions are not true crossfades.
- Loading -> player and player -> previous screen can still feel like controlled cuts rather than fully blended fades due to heavyweight HWND/AWT airspace dominance.
- The existing smooth loading -> cancel/details fade remains intact.
- Recorded as an accepted presentation limitation for Phase 2F, not an open blocker. No further presentation or native-airspace work is scheduled.

Desktop-only follow-up in repository `nuviozdesktop` on branch `claude/phase-2-desktop-handoff`:
- **Part A (Source → Player Startup & Airspace Gating):** Elevated existing Phase 2 loading surface across Classic, Streamlined, and Instant modes so it renders immediately upon candidate selection (before route change or debrid link resolution), persisting through automatic failovers without attempt 2 reload stutter. Added Win32 native airspace gating: `NativePlayerHost` HWND is kept concealed (`isVisible = false`) across source resolution, player initialization, and retries (attempt N -> N+1) without occluding Compose, with `SwingPanel` size-gated (`requiredSize(1.dp)` while unpromoted) to prevent the internal `SwingInteropViewGroup` (`JPanel`) from clearing Skia or occluding Compose in white, and is promoted (`promoteNativeSurface` -> `fillMaxSize()`) only when the first real video frame is decoded (`PlaybackHandover.hasFirstFrame`).
- **Part B (Player → Previous Screen Exit & EDT Probe):** Instrumented timestamped diagnostics (`T0`–`T4`). Concealed native surface synchronously on Swing EDT at `T0` inside `releaseBeforeNavigation`, ensuring `T3` (0 ms) occurs before `T1` (pop) and `T2` (previous destination paint at ~16 ms), completely eliminating the ~1.5s `#0D0D0D` dark gray frame on exit while teardown proceeds in background thread (`T4`). Eliminated ~891 ms Swing EDT stall by offloading the Windows PowerShell network probe to a background daemon executor.
- **Part C (Player → Previous Destination Direct Exit):** Solved exit loading loop by bypassing the transient failover-retained `StreamRoute` via atomic backstack pruning (`NuvioNavigator.popPlayerExit`), smoothly popping directly to the preceding destination (`DetailRoute`) in one action without recomposing `StreamRoute`, opening new loading tokens, or reloading sources, while strictly preserving `StreamRoute` retention for automatic failovers and manual source selection.
- **Final Verified State (Phase 2F COMPLETE / ACCEPTED):**
  - *Startup Handoff:* 1dp `SwingPanel`/native-airspace gate verified working; source → loading is visually smooth with heavyweight native player concealed underneath until first real frame.
  - *Native Airspace:* AWT/Win32 HWND occludes Compose; fullscreen hidden `SwingPanel` causes white screen via Skia hole-punching. 1dp parking until first real decoded frame is the proven working architecture.
  - *Player Exit:* Requires only one Escape, skipping transient `StreamRoute`. Fatal error and manual source selection retention preserved. Black gap and white flash eliminated.
  - *Mixed HDR/DV:* Shared parsing already retained both capabilities internally; shared UI formatter now emits composite labels (`HDR10/DV` or `HDR/DV`) with regression test coverage.
  - *Verification & Artifacts:* Pure test suites green (460 desktop, 409 mobile); mobile Android host tests passed (1,398/1,398); desktop unit tests passed clean (`NativePlayerAirspaceGateTest`, `NativePlayerControllerTeardownTest`, `PlayerExitNavigationTest`, `PlayerExitOrderingTest`). Release-style debug Windows MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,298,898 bytes). Maintainer watched verification passed. Phase 2F closed.

## Phase 2 manual-verification finding: Startup watchdog evidence-of-life deadline (2026-09-06)

Branch `claude/phase-2-playback`.

Following Phase 2 manual/diagnostic verification of playback failover, resolved false-positive timeouts where healthy-but-slow sources were prematurely abandoned at 20s despite showing credible evidence of life (parsed container duration, HTTP probe response, demuxer buffer):

1. **Two-Tier Startup Deadline (`PlaybackStartupWatchdog.kt`):**
   - Retained `NO_PROGRESS_DEADLINE_MS = 20_000L` for completely dead sources with no evidence of life.
   - Added `EVIDENCE_OF_LIFE_DEADLINE_MS = 35_000L` (`SLOW_STARTUP_DEADLINE_MS`) for slow-starting sources that have demonstrated credible evidence of life but have not yet advanced playback position.
   - Preserved `STALL_DEADLINE_MS = 12_000L` and `MAX_STARTUP_MS = 60_000L`.
2. **Evidence-of-Life Signals (`PlaybackStartupWatchdog.kt`, `PlayerScreenRuntimeEffects.kt`):**
   - Candidate handoff or URL existence alone explicitly does not constitute evidence of life.
   - Evidence of life is credited when `durationMs > 0L`, `bufferedPositionMs > 0L`, `progressMs > 0L`, or `hasExternalEvidenceOfLife` (successful HTTP probe `PlaybackProbeVerdict.Pass`).
   - Sticky state in `PlaybackStartupWatchdog.State`: once credible life is established, the 35s deadline protects the startup until first position advance or 35s timeout.
   - Added diagnostic logging for evidence-of-life transitions and watchdog abandon events.
3. **Regression Tests (`PlaybackStartupWatchdogTest.kt`):**
   - Dead sources abandoned at 20s.
   - Slow-but-alive sources with duration or probe evidence not abandoned at 20s.
   - Slow-but-alive sources starting between 22–30s succeed without false failover.
   - Sources with evidence of life that fail to advance by 35s properly abandon.
   - Stall deadline (12s) preserved after initial progress.
   - Strict deadline hierarchy enforced: `STALL_DEADLINE_MS < NO_PROGRESS_DEADLINE_MS < EVIDENCE_OF_LIFE_DEADLINE_MS < MAX_STARTUP_MS`.
4. **Verification:**
   - Pure test suites and mobile tests passed clean (`:androidApp:testFullDebugUnitTest`).

## Phase 2 manual-verification finding: 8K AI upscale & CAM/TS ranking and presentation (2026-09-06)

Branch `claude/phase-2-playback`.

Following Phase 2 manual/watched playback verification, corrected the selector and presentation logic for AI-upscaled releases (particularly nominal 8K streams) and theatrical captures (CAM/TS):

1. **AI Upscale Classification (`ReleaseTags.kt`, `SourceFacts.kt`):**
   - Added conservative `isAiUpscaled` detection for release title tokens (`AI Upscale`, `AI-Upscaled`, `AI Enhanced`, `AI Remastered`, `Topaz`, `Upscaled`, etc.).
   - Exposed on `SourceFacts` and pure-suite neighbour stubs.
2. **Display Capability Propagation (`Platform.kt`, `Platform.android.kt`, `Platform.ios.kt`):**
   - Added `platformDisplayMaxHeight()` to detect device display height (e.g. 1080/2160) and passed through `PlaybackSelectionContext` into `SourceRankingPreferences`.
3. **Automatic Ranking (`SourceRanking.kt`):**
   - `resolutionTier`: When display max height is below 8K (< 4320p), 8K and 4K fold into the same resolution tier (tier 5). On displays >= 8K, 8K native retains tier 6.
   - `mediaScore`: Added `AI_UPSCALE_PENALTY` (-8) and `CAM_TS_PENALTY` (-20).
   - Both Instant and Best Available inherit the model; proper 4K releases now comfortably beat nominal 8K AI upscales on 4K-or-lower displays.
4. **CAM/TS Demotion and Restrained Treatment:**
   - CAM/TS releases assigned `THEATRICAL_CAPTURE_TIER = -1`, ensuring any standard release (even SD) wins over CAM/TS, while preserving selectability if CAM is the sole available source.
5. **Restrained UI Presentation (`PlaybackQualitySheet.kt`):**
   - Added `AI Upscale` chip with restrained danger styling (`tokens.colors.danger` at 12% alpha bg, 35% hairline border) without interfering with HDR/DV/Atmos feature chips.
   - Set CAM/TS provenance text to restrained danger color without adding duplicate badges.
6. **Verification & Packages:**
   - Passed regression test Cases A through G (`SourceRankingTest.kt`), `ReleaseTagsTest.kt`, and `PlaybackQualityOptionsTest.kt`.
   - Pure test suites (402 tests) passed clean outside Gradle.
   - `:composeApp:testAndroidHostTest` passed clean (107 tests).
   - Packaged Android full debug APK: `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk` (151,080,900 bytes).

Branch `claude/phase-2-playback`.

### UltraReview #1 remediation
All six review findings from UltraReview #1 are integrated on mobile, alongside the mobile-specific back-press lifecycle fix:
1. **Finding 1 (P2P auto-play failover):** Propagated `autoPickedWithFailureChain` for P2P auto-play in `StreamDestination`, kept `StreamRoute` on back stack during active failure chain, updated `lastHandedOffLabel`, and reset `autoPickFailure`.
2. **Finding 2 (P2P external subtitles):** Propagated `stream.externalSubtitles` into `buildP2pPlayerLaunch` so P2P retains external subtitles into `PlayerLaunch`.
3. **Finding 3 (Manual choice routing):** Routed choose-manually paths in quality sheet and uncached stream dialog through canonical `giveUpToSourceList` to preserve provenance and surface rules.
4. **Finding 4 (Loading escape clock guard):** Added token guard to `PlaybackLoadingSessions.tick` so superseded session escape clock coroutines cannot contaminate subsequent sessions.
5. **Finding 5 (Pure stream label fallback):** Removed `runBlocking` and Compose resource lookup from `StreamModels.kt`, providing pure stream label fallback and passing localized strings at Compose call sites.
6. **Finding 6 (Canonical P2P sentinel helper):** Extracted canonical `p2pSentinelUrl` helper to `StreamModels.kt` and eliminated duplicate definitions.
7. **Mobile Back-Press Lifecycle Fix:** Moved `autoPlayStream` null check in `StreamDestination` down into the retry branch so user back presses with spent/null `autoPlayStream` properly abandon auto-play and navigate out to details instead of being silently dropped.

### Product decision: P7 deleted
**P7 (auto source-swap / automatic downshift)** will not ship and is completely removed:
- Detector (`AutoDownshiftDetector.kt`), candidate builder (`AutoDownshiftCandidates`), and 334-line test suite removed.
- Diagnostic swap log (`SwapDiagnosticsLog.kt`, `SwapDiagnosticsLogTest.kt`) and HUD forced-swap controls removed.
- Setting keys (`playback_auto_downshift`), storage actuals, repository state, and settings page UI removed; leftover descriptions cleaned up.
- **Normal ranked-candidate failover remains completely intact**: automatic candidate failover across Classic/Streamlined/Instant, P2P failure chains, fatal playback error handling, manual fallback, and dead-source reporting are all preserved.

### Verification status
- Non-device verification (pure test suites: 408 passed, Android compilation, testAndroidHostTest: 107 passed, testFullDebugUnitTest passed) passes clean.
- **Manual verification passed on packaged builds** (2026-09-06) following the two-tier startup watchdog evidence-of-life fix. Exit gate passed; Phase 2 closed.

## Ultra 1 review record (2026-09-05)

Branch `claude/phase-2-playback`, open as [nuvio-z#1](https://github.com/Zokaper/nuvio-z/pull/1)
against `claude/upstream-sync-0.4.13` - 73 files, +6,110/−1,790. ⚠ **Not `main`**, which does not
carry the upstream sync; based there the PR would bundle 104 commits of it.

`ROADMAP.md` puts Ultra 1 on whichever repo holds the more divergent mode router, and the answer
was measured rather than argued: `PlaybackModeRouter.kt`, `PlaybackModeModels.kt`,
`PlaybackSourceSelector.kt`, `PlaybackQualityOptions.kt` and `StreamRouteSurface.kt` are
**byte-identical** to `nuviozdesktop`, and no file in `features/playback/` is unique to either
repo. All divergence is in `StreamDestination.kt` - 132 differing lines, 86 desktop-only against
46 mobile-only. So the shared logic is covered by the desktop run, and this repo's companion is a
`/code-review high` aimed at that 46-line delta and the Android-specific surfaces around it.

⚠ **The `high` draws on the weekly limit**, so it can wait for the ultra's findings - anything it
turns up in shared code applies here unchanged, and running the two together would spend the
weekly budget re-reading code that is byte-identical to what the ultra already read.

## Back was taken, then answered with another play (2026-09-05)

Branch `claude/phase-2-playback`. Reported as "pressing Escape mid-loading or mid-player is
jank - I had to spam it a few times to get out", plus a second report that sounded unrelated:
after escaping an Instant play, switching to Classic and returning to the same title started
the source Instant had picked. One bug, both faces.

**Escape was working. The app was restarting the source behind it.** From the z1.44 debug log:

```
22:03:54.697  PlayerControls action=Back   pos=360902        <- the Escape
22:03:55.116  loading surface token=2 closed (visibleMs=19229)
22:03:56.934  StreamsRepo Found 1 addons  (same title)
22:03:56.938  StreamsRepo Fetching streams ...               <- catalogue request starts
22:03:57.195  loading surface token=3 opened                 <- 257 ms later, already playing
22:03:57.326  attach requested ... initialPositionMs=361499
22:03:58.006  probe total=47595678623 host=store-071...      <- same file as token=2
22:03:58.578  PlayerControls action=Back   pos=0             <- the second Escape
```

⚠ **That fetch never logs a `Got ... streams` line - it was cancelled.** So the source that
relaunched came entirely from state held in `StreamsRepository`, with no catalogue in hand, and
it re-attached at 361499 ms against the 360902 ms the user had just exited at. Every press was
answered by a fresh play of the thing being escaped, which is what "spam it a few times" was.

### What was wrong

On the pop back from the player, two effects in `StreamDestination` wake on the same
`autoPlayStream`: the retry effect, which decides the user left and exits, and the auto-play
effect, which starts whatever is armed. **Whichever ran first decided what happened.**
`userAbandonedPlayback` already existed for exactly this distinction and its own KDoc said
"Read only by the stall backstop" - which was the fault, because the effect that *starts
playback* never consulted it.

The second face is `consumeAutoPlay`, which **retires** the chain into `retiredAutoPlayStream`
rather than dropping it. That is right for a source dying after the first frame and wrong for a
back press: it left the retained chain for `failOverAfterPlaybackStarted` and the live one for
`carriedAutoPlayChain`, which hands a chain back to the next load of the same request token.

### The fix

- `StreamsRepository.abandonAutoPlay()` - drops the live chain, the retired chain, and the
  pending retry signal. Deliberately not `consumeAutoPlay`; the difference is the bug.
- The auto-play effect returns early on `userAbandonedPlayback`. ⚠ Ordering two effects is not
  a fix - the abandon is a fact, and a fact outranks a race.
- `leaveToDetails()` abandons the chain and cancels the in-flight fetch, so "takes you back"
  and "stops what is running" are one action rather than two halves with one written.
- The route's `onDispose` does the same for exits the route does not own - the window closing,
  a deep link - keeping the hand-off exemption that lets the surface outlive the route.
- ⚠ **On this repo that `onDispose` had to be written, not extended: it did not exist.** The
  "or the user leaves" arm was added to `nuviozdesktop` in `0dc5776d` and never ported, so
  backing out of a loading play here left the chain armed *and* the loading session running
  above `NavDisplay` - the stuck-behind-the-loading-screen fault desktop had already closed.
  Both are closed here now.

**Verified:** `AutoPlayFailoverTest` passes on both repos - 14 tests, 0 failures - including two
new cases: an abandon leaves nothing for either mechanism, and an abandoned chain is not carried
into the next load of the same title. ⚠ The existing `a reload for the same video keeps a
re-armed chain` still passes, so the legitimate carry is intact and only the abandoned case is
cut.

**Not** verified: not yet watched on a packaged build. The effect race and the route teardown
are precisely what a hot run cannot exercise; z1.45 / mobile build 28 are cut for this.

Desktop source-to-player jank investigation continues on `claude/phase-2-playback`.
The native bridge now builds locally; a fresh run confirmed the loading scale is clamped to 1
by the controls JSON writer. See `nuviozdesktop/STATUS.md` for measurements and verification.

| | |
| --- | --- |
| Active branch | `codex/phase-3-downloads` in both KMP repositories, cut from mobile `main` and desktop `Dev` after Phase 2 merged. Phase 3 implements reliable downloads with lazy source resolution. |
| Version in the files | mobile **`0.4.13-z1`** and desktop **`0.1.22-alpha-z1`**, both with release serial **127**. |
| Released | bridge `0.5.0-beta+126`, published in both KMP repositories on 2026-08-24 |
| Next version | mobile has adopted `0.4.13-z1`. Desktop adopts `<vanilla>-z1` when its own sync to `0.1.22-alpha` lands. Note that the debug channel carries no serial, so a debug install on `0.5.0-beta.25` will not be offered a `0.4.13-z1` debug build - it needs one manual sideload. |
| Verified | the Z backend is deployed and live: 8 migrations applied to `pzbpghmmordvzcfbayoh`, `get_social_capabilities()` now returns both flags **true** - `202609010009_enable_social.sql` enabled them, and the desktop client renders the invite-code field and the Watch Together action, which it only does when `watchPartyEnabled` is set, so the earlier "both flags false" reading in this table predates that migration - direct table reads return 401, the `z-session` function is deployed and rejects every unauthenticated path correctly, and 61 pgTAP assertions pass on matching Postgres 17. Both standalone suites pass (290 tests each); focused Android host and desktop Gradle runs compile the real source sets and pass all 16 next-episode tests. Desktop Watch Together propagation measured about 225 ms in a real two-client session; the buffering-race follow-up compiles and all 1,318 desktop tests pass. Mobile CI `33327792025`, repaired desktop CI `33328140034`, mobile debug publish `33328752860` and desktop debug publish `33615211655` all pass. For the sync rework: `scripts/run-pure-suites.sh` passes all six groups (131, 64, 49, 17, 29, 42) with the new group 6 compiling the shipped sync sources and no stubs, and desktop CI `33627311248` passes the full `:composeApp:desktopTest` run and the Windows MSI build. For the UI rebuild: 1,360/1,360 desktop tests pass locally, and the social tab and the party lobby were both driven in the running app over Compose Hot Reload - the lobby across three live stage transitions. |
| **Not** verified | **Nothing in the Watch Together sync rework has run against a live party.** It compiles and both suites pass, and that is all: the party clock, the tick, the barriers and the wait-for-everyone policy have never had two machines on them. The matrix is cold start; pause and resume ten times, measuring the spread; seek ten times; a real host rebuffer; a real guest rebuffer with the toggle on and off; host migration; the socket killed mid-film; and a `debug-v0.5.0-beta.36` client against a `.35` one, which must degrade to the old five-second behaviour rather than break. Carried forward from `debug-v0.5.0-beta.35` and still open independently of the rework: every host transport action must bump `sequence`, offline Leave/End must permit a new party immediately, and the corrected next-episode transition and the desktop HTML button still need a device/install pass. Mobile is still not wired to the Z backend and has none of this; manual iOS verification remains outstanding. From the UI rebuild: the **in-player Watch Together panel has never been seen** - reaching it needs playback, and its CSS and JS are desktop resources that a Compose reload does not pick up, so it wants a deliberate restart. Nor has any **multi-member lobby state**: a one-person party cannot produce a green `ready` tile, a red `failed` tile with its error text, the alternate-source chip, or the dimming of a disconnected member, and a still cannot judge the resolving ring's animation. All of those fall out of the two-desktop matrix. |
| Next work | Build/install Phase 2 and run its watched playback matrix before release; then run the two-desktop Watch Together matrix against the current debug line with the playback HUD on. Port Watch Together to mobile only after that passes and after mobile reaches `ZSupabaseProvider`/`ZSessionBridge`; stable `0.5.0-beta+126` remains untouched. |
| Debug channel | desktop `debug-v0.5.0-beta.36` carries the sync rework, published 2026-09-02 from `claude/watch-together-sync-7ceki1`; `debug-v0.5.0-beta.35` from `f0aef43f` is the build the current sync report came from. Mobile `debug-v0.5.0-beta.25` was published 2026-08-30. Stale pre-sync desktop `debug-v0.4.14-beta.18` and mobile `debug-v0.4.14-beta.25` are superseded. |

> **The history moved.** Everything before 2026-08-24 is in [`Docs/STATUS-ARCHIVE.md`](Docs/STATUS-ARCHIVE.md) -
> 48 sections, kept whole and in order. This file is the live handoff only: the
> state table above, the work since the last release, and what is still open below.

## Phase 2 closing polish (2026-09-05)

Branch `claude/phase-2-playback`. Three closing polish designs address presentation feedback on the surfaces built in Phase 2, preparing the branch for Ultra 1 review:

1. **Streamlined Quality Columns (wide branch):** The wide-window branch of `PlaybackQualitySheet` (`isWide`, ≥768 dp — on this repo, large tablets in landscape) leads with Best available as a full-width strip — release, `Size`, `Needs` and the connection meter — and lays the alternatives out as **one column per resolution**, each column stacking only the bands that title actually has. ⚠ **Nothing scrolls, and that is the design.** `VideoResolution` has six members and `optionsForBucket` emits at most four bands each, so the offer is bounded and fits the width. Panel max width went 920 → 1200 dp (`wideDialogMaxWidth`). A matching skeleton renders on the same footprint before the figures settle.

   This replaced a quality *table* taken on the same day, which was watched on a desktop debug hot run and found to be a spreadsheet: a 480 dp cap slicing its last row mid-glyph with no scrollbar, a `RELEASE` column identical for two adjacent rows with ~270 dp of dead air beside it, a `FIT` column of five visually identical meters, and `Best available` printing `—` for a size the row beneath it printed in full.

   ⚠ **A collapsed bucket gets the class it would have been.** `Variant.SINGLE` carries no band
   - banding needs two sized sources to compare - and the row used to read "Only option", which
   told the reader nothing about what they would get. `PlaybackQualityOptions.bandFor` derives
   the class from the row's own bitrate against the same absolute boundaries, so a lone 8K
   release at 41 Mb/s reads `Mid (Max)`: a Mid-class file, and the best 8K this title has. The
   one row that keeps a fallback label is a release nobody reported a size for - there is no
   bitrate to band by, and handing an unmeasurable file a class is exactly what banding on sized
   sources alone exists to prevent.

   **What a cell says, and in what order.** Band name, then dynamic range and audio as outlined
   marks, then size and needs, then rip type and host on the last line in muted small caps. That
   order is the fix for "there isn't much differentiating between the cells": down a column
   `BLURAY` repeats four times and `DV / Atmos 7.1` does not, so the old order led with the
   repeating part. `describeProvenance` splits the rip type and host back out of
   `describeRelease`, which had folded the dynamic range into a sentence, so nothing is printed
   twice. `SDR` is drawn (via `PlaybackLoadingFacts.dynamicRangeSlot`, the same earned default
   the loading band uses) but **muted**, never accented - an empty mark row reads as a fact that
   failed to load, and an accented `SDR` spends the panel's one emphasis on the ordinary case.
   Cells sit on `surfaceCard`: `surface`, `surfaceElevated` and `surfaceDialog` are the same
   colour in this theme, so the first attempt tinted the panel over itself and drew nothing.
   The cell Best available resolves to is outlined rather than restated
   (`PlaybackQualityOptions.sourceKey`) - it is routinely the very row beneath the hero, and two
   identical offers side by side read as two files.

   **`High (Max)`.** A resolution whose releases all fall under its Max boundary offers no Max row, so its top row reads "High" — and a lone "High" reads as a middling pick rather than as this title's ceiling at that resolution. `PlaybackQualityOptions.isTopBandBelowMax` marks it and the cell appends the Max word. ⚠ The band word itself is **never** rewritten: the bands are absolute, and relabelling one would be exactly the catalogue-relative naming `Variant` exists to end. `Variant.SINGLE` is excluded — a collapsed bucket has no bands to top — and reads "Only option" instead.
2. **Fixed 5-Slot Loading Metadata Rail:** The loading band across Compose (`PlaybackLoadingScreen`) and desktop JCEF/HTML (`controls.html`, `controls.css`, `controls.js`) now renders a fixed five-slot spec strip: Resolution, Audio/Subs, Range, Audio, and Size. Absent metadata displays an honest em-dash (`—`) rather than phantom guesses; dynamic range safely falls back to `SDR`; the "Choose source manually" escape hatch resides in a reserved 36 dp row above the progress line so its appearance at 5 seconds never shifts the layout under the reader.
3. **Seamless Entrance Motion:** Pop and dip artifacts entering playback are resolved via `PlaybackEntranceMotion` (260 ms coordinated curve: color-alpha scrim, logo, and band arrival) and a desktop navigator fade-through on `entry<StreamRoute>` (220 ms in with 90 ms delay + 90 ms out).

**Verified:** `scripts/run-pure-suites.sh` passes, including the new `PlaybackQualityOptionsTest`
coverage for `bandFor`, `isTopBandBelowMax` and `sourceKey`. The same code passes
`:composeApp:desktopTest` on `nuviozdesktop`, where `PlaybackSourceSelectorTest` also runs.

**Not** verified: ⚠ **the columns panel has never been run on this repo at all** - not on a
device, not on an emulator. It is shared code ported from `nuviozdesktop`, where the only run
against it was a hot run that cannot reach a first frame, so neither repo has watched it. On
mobile the wide branch is reachable only on a large tablet in landscape; handsets keep the card
grid and are unaffected by everything above. A debug APK is what this needs.

**Deliberately NOT changed:**
- **Phone Card Grid:** The narrow branch of `PlaybackQualitySheet` (<768 dp) retains its proven touch-card layout and bottom sheet mechanics for phones and small tablets.
- **`entry<PlayerRoute>` No-Transition Rule:** Retains `EnterTransition.None`. Because `PlaybackLoadingHost` draws the identical loading surface across the entire route crossing at `zIndex(18f)`, adding any transition here would create a redundant crossfade between two identical frames.
- **Connection Figure Latch:** The bandwidth measurement figure and verdict remain latched upon initial determination; late background probes never cause figures or column alignments to jump under the reader.

## Phase 2 Playback: the hand-off made seamless, and a source that is actually there (2026-09-05)

Branch `claude/phase-2-playback`, continuing the work below. Three agents have now worked this
branch; **the previous round was left entirely uncommitted** - 18 modified files on desktop and 11
on mobile, with nothing written down anywhere. It is committed now, split by concern: `72029b43`
(the twelve code-review findings) and `ef5e209d` (the desktop native loading band). See the rule
about this added to the new parent `AGENTS.md`.

### What was reported

1. Choosing a source produced a UI stutter, then a black screen, then the loading screen popping
   in. The previous round shortened it but could not remove it.
2. *The Secret Woman*, 4K High: attempt 1 never produced a frame and cost 20 s; attempt 2 played
   the debrid provider's "being prepared" slate and the chain stopped there, satisfied. The
   loading screen also visibly **reloaded** to say "Attempt 2".

### Why the hand-off was not seamless

The pixels were already shared - Phase 2 made both sides render one `PlaybackLoadingState`. **The
lifetime was not.** A route entry stops composing when it is not on top and is re-created by a pop,
so the surface was destroyed and rebuilt at every hand-off and every failover. On desktop that
window contained four further faults, in this order:

| # | What | Where |
| --- | --- | --- |
| 1 | `entry<StreamRoute>` fades out over 160 ms while `entry<PlayerRoute>` had **no desktop spec** and fell through to `NavDisplay`'s much longer default - two crossfades running against each other | `MainAppContent.kt` |
| 2 | the player's root was `Color.Black` under a loading screen painted on `#0D0D0D` | `PlayerEngine.desktop.kt` |
| 3 | the AWT canvas filled `Color.BLACK` and, being heavyweight, painted over every Compose layer the instant the `SwingPanel` was promoted | `NativePlayerHost.kt` |
| 4 | the JCEF overlay then faded its artwork in over 260/520/620 ms - a re-entrance of a screen already at rest | `controls.css` |

**The fix is one move: the surface is owned above the navigator.** `PlaybackLoadingController` holds
one session; `PlaybackLoadingHost` draws it as a sibling of `NavDisplay` at `zIndex(18f)`. The
navigation now happens *underneath* a screen that never stops drawing, so there is nothing left to
animate or re-enter - and a failover becomes a state change, which is what "it should just say
attempt 2 of 3" asks for. `entry<PlayerRoute>` is given an explicit `EnterTransition.None` on
desktop (an `emptyMap()` is not "no animation"), the native canvas and the JCEF overlay are painted
the app's own background, and the JCEF artwork intro is gone.

Motion is now exactly two beats, both defined in `PlaybackLoadingMotion`: a 220 ms entrance when the
source list is replaced (backdrop first, band on an 80 ms stagger) and a 300 ms exit into the first
frame. **Everything between them is zero-duration by construction.**

### Why a placeholder played

`%APPDATA%\Nuvio Z\logs\nuvio-debug-20260905-005434.log`:

```
00:55:02.905  attach created  length=3092   <- [TB(bolt)] MediaFusion 2160p, marked cached
00:55:22.614  abandoning ...: reason=NeverStarted elapsed=20240ms duration=0ms engine=Unknown
00:55:24.418  attach created  length=1395
00:55:31.613  updateControls  pos=10160 duration=120960   <- 2:01, for a feature film
```

**Cache detection was not the fault, and mostly already worked.** `parseDebridCacheMarker` read the
cached marker correctly. Two other things were true:

- `PlaybackSourceSelector.isDebridBacked` did not recognise AIOStreams. It hands back a plain
  `https://` link to its own proxy, so a candidate through it had no `debridService`, no
  `clientResolve` and was not an `isDirectDebridStream` - `isUncachedDebrid` therefore never
  applied and an **unknown** cache state was auto-played. `isAioStreams` is now on that list.
- Nothing ever checked what the URL actually *returned*. A stale cached marker was
  indistinguishable from a true one, and nothing logged the response to a URL handed to the
  engine - which is why attempt 1's twenty seconds are, in that log, unexplainable after the fact.

So: **one `Range: bytes=0-1` before any frame is attached** (`PlaybackSourceProbe`). Status, content
type, and the served total against the release's claim. A rejected source never opens the player, so
the chain steps with nothing on screen changing but the attempt number. Every unknown passes, and a
failed or timed-out probe passes - it must never block a working play. `PlaybackDurationPlausibility`
is the backstop for what the probe cannot judge, and is deliberately conservative: both a
fifth-of-expected ratio **and** an absolute duration under ten minutes.

### Also fixed

- `PlaybackAttemptLog`'s give-up line read `streamsUiState.autoPlayStream` *after* the chain had
  moved on, so it printed `addon=unknown cached=unknown` on exactly the lines that needed them.
  It reads `lastHandedOffFacts` now.
- The stall backstop logged `uncover=dead_end_backstop` six seconds after the user pressed Back -
  a false entry in the one log that exists to explain why the source list appeared.
- The loading surface's exit is gated on a **decoded frame** (`videoWidth`/`videoHeight`, or real
  advancing playback), not on `isLoading` going false, which the engine drops before it has decoded
  anything. `firstFrameReached` is a second flag rather than a redefinition of
  `initialLoadCompleted`, which the seek, subtitle and watchdog paths all read and mean the weaker
  thing by.

### Verified

| | |
| --- | --- |
| Pure suites, desktop | **417** (from 397) |
| Pure suites, mobile | **365** (from 345) |
| `:composeApp:compileKotlinDesktop` | clean |
| `:androidApp:compileFullDebugKotlin` | clean |
| `NativePlayerControlsPageTest` | passes |
| **Watched run** | **not done - still the exit gate** |

New pure files, both wired into `scripts/run-pure-suites.sh`: `PlaybackLoadingSession.kt` (group 1,
it reads `SourceFacts`) and `PlaybackSourceProbe.kt` (group 2).
`scripts/pure-suite-stubs/Neighbours.kt` gained `SourceFacts.isAioStreams` - the stub had drifted
again, and per the script's own doctrine a failing compile is the alarm and the stub gets fixed.

### Still open

- **Nothing here has been watched.** The whole point is a transition, and a transition cannot be
  verified by a test or a compiler. It needs a debug MSI - Compose Hot Reload cannot attach the
  native player bridge, so the player route opens to an empty surface that looks exactly like the
  bug being fixed.
- **The remaining desktop hand-over gap is now measurable but has not been measured.** `controls.js`
  reports `didPaintOpening` and `NativePlayerController` logs `afterAttachMs=`. Read that figure on
  the first real run before deciding whether anything more is needed there; WebView2 is already
  warmed at process start, so there may be nothing left to win.
- The probe adds one round trip to every automatic play. It runs under a loading screen that is
  already up, so it should be invisible - but it is a real cost and worth watching on a slow
  connection.
- Mobile still has no debug build on the post-sync base.

## Phase 2 Playback: implementation complete, watched exit gate open (2026-09-04)

Branch `claude/phase-2-playback`, cut from the Phase 1 sync branch - **not** from trunk, which is
362 commits behind. Full handoff: `../HANDOFF-phase-2-playback.md`.

**Stages 0-4 and 6 are complete in both repos.** The code review and automated verification pass
are complete. Stage 5's installed playback matrix remains the release exit gate; Compose Hot
Reload cannot exercise the native player bridge on this machine.

### The finding that matters most

**The `0.1.22-alpha` sync silently disabled desktop's whole playback recovery path.** The App.kt
dissolution recorded above moved `MainAppContent`'s `onFatalPlaybackError`/`onPlaybackStarted`
handler nowhere: `PlayerDestination` stopped passing them, while `PlayerScreen` still declared
both. Nothing was deleted, everything compiled, and the deletion check the sync brief mandates
could not see it - a lambda simply stopped being passed.

Three things were dead in production until this phase:

- `PlaybackStartupWatchdog` arms only when `onFatalPlaybackError != null`, so **it never ran**;
- the post-playback-started failover chain never advanced;
- `consumeFailoverRetry()` always answered false, so every return from the player read as a back
  press.

`nuvio-z` kept its copy, which is exactly why the loading loop was reported on desktop only.
**Worth a rule for the next sync: a lambda that stops being passed is invisible to both the
conflict list and the deletion sweep.** Grep the callers of anything the dissolution moved.

### What landed

- **One loading surface** from chosen source to first frame, rendered by both the route overlay
  and the player's opening overlay from one state object. Three loading surfaces and four
  indeterminate motions became one.
- **Every duration-derived position bounded** through pure `PlaybackPosition`. The watchdog's
  baseline ignored the fraction-only resume path, so a dead source could be declared Started -
  bugs 1 and 2 shared that root.
- **`AddonStreamGroup.error` stops being discarded**, in the list and as the failure reason.
- **Content-identity gate**, auto modes only, a partition rather than a filter.
- **All 13 ways into the source list named and logged**, with `hasSilentUncover` making a
  reasonless uncover a failing test.
- **The route audit is closed**: download launches cannot enter auto playback, P2P consent no
  longer destroys an untried failure chain, rejected external-player launches advance or uncover
  honestly, and process restoration cannot preserve a phantom in-flight debrid resolve.
- **P7 automatic source-swap was deleted**, including its setting/storage/sync key, detector,
  candidates, forced-swap HUD controls and swap log. It had been held since `0.4.9`, had never run
  on a device, and Phase 2 confirmed that null direct URLs discarded every unresolved alternative.
  Passive network measurement and manual in-player source switching remain.

### Verified, and not

Suites green: pure 389 desktop / 337 mobile; Android host 1,312; desktop 1,518. All runs have zero
failures, errors or skips. Desktop compiled the native bridge and real desktop source set; its one
reported configuration-cache problem is the existing non-serializable bridge `Exec` task, and
Gradle discarded that cache entry after the successful run.

⚠ **Nothing here has been exercised against real playback.** Hot reload cannot reach a first
frame on this machine - the native bridge fails to attach (`java.desktop does not "opens
java.awt"`), so the player route opens to an empty surface that looks exactly like a hang. That
is a second, separate reason for the debug-MSI rule already recorded below. The watched matrix is
still the exit gate: Classic manual selection, Streamlined selection, Instant failover, P2P
consent/decline, external-player reject, debrid resolution, next episode, and back navigation.

## Mobile is synced to vanilla 0.4.13 (2026-09-04)

Phase 1 of `ROADMAP.md`, on `nuvio-z` branch `claude/upstream-sync-0.4.13`, merged in `bacb3a23`.
90 commits of vanilla, a 27-file conflict surface, 11 files actually in conflict.

The work was not the conflicts. Upstream dissolved `App.kt` into a 98-line shell plus 13 new
files, and our copy carried 1,639 lines of Z changes across 44 commits, so there was no "keep
ours": the new files merged in cleanly as additions and their declarations collided with the
monolith. Z's hunks were routed into `MainAppContent.kt`, `StreamDestination.kt` and the six
destination files, then read by hand.

**Five pieces of Z code went missing with no conflict marker.** Four were found by the compiler.
The fifth was not: `AddonSubtitleStartupPolicy.kt` and its 40-test suite were deleted outright
together with their last caller, so everything compiled while the Fast-startup subtitle setting
sat in the UI wired to nothing. The check that catches this class of loss is
`git diff --cached --diff-filter=D --name-only`, and it belongs in every sync.

Pure suites 285 green - the pre-merge baseline exactly - and the Android host suite 1,286 green.
`scripts/run-pure-suites.sh` had to be repaired first: its serialization compiler plugin was
pinned to Kotlin 2.3.0 against a 2.4.10 compiler, and there is no `java` on PATH on this machine,
so it had been exiting 0 while running nothing at all. CI `33861273289` passes, and the iOS build
`33861273328` compiles the Kotlin framework and the Xcode app in 38 minutes - the merge brought
upstream's `ios-test-build.yml` and `scripts/build-ios-ipa.sh` with it, so the IPA pipeline is
inherited rather than owed.

Full analysis, and the brief for the desktop sync, in `Docs/UPSTREAM-SYNC-0.4.13.md`.

## The social tab, the party lobby and the in-player panel were rebuilt (2026-09-03)

Landed on `NuvioZDesktop` branch `claude/watch-together-sync-7ceki1` in `12542f2f`, `4d63cbfa` and
`99b115ef`. **Desktop only.** Two of the three surfaces were verified in the running app; the third
was not - see the table above.

All three had been written without anyone being able to look at them, and it showed. Every element
was the same `tonalElevation` grey box, so an error message, the profile header and a privacy
toggle all carried equal weight; and the states people actually read - is this person ready, did my
friend request send - were the least legible things on screen.

**One vocabulary for readiness.** `WatchPartyPresentation.kt` names the tone
(ready/working/failed/offline), the label and the stage rail once. The lobby and the player were
each spelling this out for themselves and neither carried severity: the player sent
`readyState.name` with the underscores swapped - the codebase's words rather than the viewer's - and
disagreed with the lobby beside it. `PlayerPartyMember` now carries `statusTone` across the native
bridge, so the controls layer can colour a pill instead of printing a grey caption.

**Semantic colour is fixed, not themed.** Ready and live are a fixed green. `colorScheme.primary`
follows the user's theme picker, and under Crimson a red "ready" sits beside a pink
`colorScheme.error` "no source found" and the pair says nothing. The accent still carries emphasis -
the stage rail, the invite tile - where no state is meant.

`avatarUrl` and `avatarColorHex` were on `SocialProfileSummary`, `PartyParticipantProfile` and
`PlayerPartyMember` from the day the feature shipped and were drawn by nothing, so every avatar on
every social surface was a monogram. They render now.

**What looking at it actually caught.** Ten defects, none of which a test would have found:

- The lobby's blurred backdrop was invisible. The scrim ran `0.86 -> 1.0` alpha over the art, which
  is opaque; the screen read as flat black and the art might as well not have been fetched.
- `Modifier.fillMaxSize().widthIn(max = ...)` silently defeats the cap - `fillMaxSize` forces the
  node to the parent's width - so the lobby stretched across a 2880px window. The same line was
  written twice, in `SocialScreen.kt` and then again in `WatchPartyLobbyScreen.kt`.
- `NO SOURCE YET` ellipsised to `NO SOURCE ...` in a 150dp tile, so the longest and most alarming
  states were exactly the ones truncated away.
- The lobby was never centred: `widthIn` with no centring parent pinned it to the left edge of a
  wide monitor.
- Activity cropped a 2:3 poster into a 76x48 letterbox, mangling the art on every card, on the
  social tab and the home rows alike.
- The social header's tint had two hard edges and read as a mis-drawn panel rather than a header.
- Empty states stretched to about 1250px for two short lines, so an empty tab looked broken rather
  than empty.
- The recent-activity row's trailing avatar sat alone at the far right, separated from the name it
  belongs to by the entire empty middle, repeating what the subtitle already said.
- A dark `avatarColorHex` - zokaper's is near-black green - left the monogram floating with no
  visible circle.
- `social_no_activity` already says "Add friends to see what they watch", and the detail line under
  it said the same thing again.

**What the verification run established.** The social tab was driven against real friend activity.
`social_get_state` returns `activity` correctly, and the empty feed seen first was accurate, because
`zokaper` has published **zero** `social_activity_events` rows. That is not a fault:
`SocialWatchedActivity` bridges *explicit local mark-watched mutations* only and is not a backfill,
so an account whose history predates the social wiring has nothing to publish. `seraph`'s four rows
prove the path works end to end. Worth knowing before reading an empty feed as a bug.

The lobby was driven across three live stage transitions - the rail filling one, two and three
segments; the headline moving through "Waiting in the lobby", "Waiting for the host to pick a
source" and "Everyone is finding their source - 1 to go"; the pill through `NO SOURCE YET`,
`FINDING SOURCE` and `PICKING A SOURCE`; and the primary action flipping to "Resolve source" once a
fingerprint existed. Both test parties were ended afterwards.

**Two housekeeping notes from that run.** There are **20 open `watch_parties` rows** with
`ended_at is null`, from test sessions on 2026-09-01 and 02 that were never ended; they accumulate.
And `DesktopDownloadQueueE2ETest > a source that trickles and drops forever fails instead of
retrying forever` failed once in a full sweep and then passed 20/20 in isolation - it is a
wall-clock simulation of a starving source, and the failing run shared the machine with a running
Compose app and Gradle daemons. Run `desktopTest` on a quiet machine before believing it.

## Watch Together sync: the timing plane moved off the database (2026-09-02)

Landed on `NuvioZDesktop` branch `claude/watch-together-sync-7ceki1`. **Desktop only, and
unverified against a live party** - see the table above.

The "few seconds apart, pause is slow, unpause jumps" report is one design fault with three
faces, and none of them was a tuning problem:

- **The anchor was biased.** Party state carried `(position_ms, state_updated_at)`, where the
  position was a host sample lifted from a 500ms Compose polling loop and the timestamp was the
  *server's* `now()` at commit. Those are different instants, so every guest computed a position
  behind the host by the host's sample age plus its uplink - a constant, re-applied on every
  anchor, invisible from either side because both machines computed the same wrong number.
  `WatchPartyDriftDeadbandMs = 750` then declared that in sync, which is why no amount of
  correction tuning ever moved it.
- **A pause took two server hops** - PostgREST, a Postgres write, a trigger calling
  `realtime.send`, then Realtime - for which the recorded best case was about 225ms.
- **Every transition jumped by construction.** A guest was handed `position + delay` on resume
  and jumped forward by exactly the delay it had spent waiting; on pause it played on and was
  seeked backwards.

Postgres keeps everything it was already good at - membership, readiness, the host's identity,
content, the late-joiner snapshot, every authorization decision. What moved is the timing plane,
onto the private party channel that was already open and already RLS-gated. **No backend
migration.**

- The position now travels with **the instant it was read**. `PlayerEngineController` gained
  `samplePositionMs()`, answered on desktop straight off the mpv handle, and the host broadcasts
  that pair twice a second. This is the single change that removes the standing offset.
- The party clock is the **host's**, estimated NTP-style over the same socket the positions
  arrive on: min-RTT selection across a sliding window, slew-limited once locked, re-locked on a
  step no drift could produce. It replaces three PostgREST round trips taken once at party start
  and never again.
- Play and seek are **barriers**: a position and the party instant to be playing it at, executed
  by every client including the host through one code path. Pause deliberately carries no lead
  and aligns while paused, where closing a gap costs one frame rather than a visible jump.
- Bands retuned for an unbiased anchor - deadband 750ms to 200ms, seek 4s to 1.5s, with a seek
  needing the gap seen twice. The old bands are kept intact as `partyFallbackDriftCorrection` for
  the database path, which is still biased; exactly one of the two paths runs at a time.
- The host holds the party for a guest stalled past 1.5s and starts everyone together again,
  behind a lobby toggle. It is host-side and this session only: there is no party settings
  surface to persist it into yet.
- A guest without control can no longer move its own player. It always could - the press looked
  like it worked and silently desynced them - and it now says why instead.

**Three faults were found by executing rather than reading.** `minByOrNull` keeps the *first*
minimum, and on a steady link every round trip measures the same, so the opening exchange of a
party won every comparison for the life of the window and the clock estimate could never move. A
barrier scheduled against an offset of zero - which means "no estimate", not "no error" - would
be either far in the future or long past. And three helpers were top-level extensions the player
file never imported: eight files passed the `kotlinc` parser check cleanly while none of them
compiled, which is exactly the gap `AGENTS.md` names about that check. Only CI could see it.

The decision layer is deliberately import-free, for the reason `core/media/ReleaseTags.kt` is:
Gradle cannot configure in the agent sandbox, and two clients disagreeing about a clock is not
something a single-machine build can find. `scripts/run-pure-suites.sh` gained a **group 6** that
compiles the shipped sync sources - no stubs - and runs 42 tests against them.

A Watch Together row was added to the playback HUD (`errMs`, `offsetMs`, `rttMs`, `tickAgeMs`),
so the two-client matrix can be read off the screen instead of by lining up two log files
afterwards. Every previous round of this work was measured the second way.

**Verified:** all six pure-suite groups pass (131, 64, 49, 17, 29, 42); desktop CI
`33627311248` passes `:composeApp:desktopTest` in full and builds the Windows MSI.
**Not verified:** anything requiring two machines. Nothing here has met a live party.

## Desktop Watch Together recovery and control audit (2026-09-02)

The remaining desktop fixes landed on `codex/next-episode-debug-hotfix` in commits `d6f2e440`,
`2383ac75`, and `f0aef43f`, continuing commits `d9fb00a5` and `4b287aa1`:

- A guest labels a transient host stall as **Host is buffering** and remains held while the connected
  host continues to report buffering. The earlier 12-second auto-resume was removed after a live
  two-instance run proved it let a guest diverge while the host was genuinely stalled.
- Periodic and status-transition heartbeats are serialized. The same run caught `playing` and
  `buffering` requests starting four milliseconds apart and completing in the opposite order: the
  stale `playing` write undid a buffering broadcast after 26 ms, so the guest did not stay paused
  until the next five-second heartbeat. An older sampled request can no longer finish after a newer
  one and overwrite it.
- Every inspected desktop user seek path now submits the party command: native-fallback double tap,
  the native scrub bar, the Compose scrub bar, horizontal swipe, and skip-intro/outro. Play/pause,
  seek-by, and playback speed were already covered. The on-device invariant is one `sequence` bump
  for every permitted host action.
- Leave and End clear the held party, poll and realtime channel before issuing a bounded background
  RPC. This also covers `end()` failing before its RPC block because session refresh was unavailable;
  local teardown no longer depends on any network operation returning.
- Expected-position arithmetic now uses `Double`, preserving millisecond precision for long content
  rather than promoting the whole sum to 24-bit `Float` precision.

**Verified:** `:composeApp:compileKotlinDesktop` passes; the full `:composeApp:desktopTest` run passes
1,318/1,318 with no failures or skips. Desktop debug workflow `33615211655` built, verified and
published the Windows x64 MSI and unsigned macOS arm64 DMG as `debug-v0.5.0-beta.35`. Device
verification remains required: confirm a real host rebuffer pauses the guest promptly and keeps it
held until the host actually plays, exercise every host transport path while watching `sequence`,
and press Leave/End with the network down before creating a new party.

## Watch Together sync fixes carried ahead of the mobile port (2026-09-02)

**The tested client was desktop, not this one.** The two-device run that produced the five second
sync report was Windows host and macOS guest on the debug channel; mobile is still not wired to the
Z backend - `SupabaseConfig.URL` here is the official `api.nuvio.tv`, which hosts none of the party
RPCs. The diagnosis, the fixes and the verification for that run are in `NuvioZDesktop/STATUS.md`.

What landed here is the shared half of that work, applied early so the mobile port does not inherit
faults already understood. It compiles and the host suite passes, but **none of it has run against a
live party**, and it cannot until mobile reaches the Z backend.

- The drift policy: a nudge proportional to the gap and capped at +-10%, replacing a fixed 1.03x
  that recovered 300ms over its ten second hold and so escalated every drift that mattered to a
  seek; the band widened to 4s; the blocking `delay(10_000L)` removed so a snapshot arriving mid
  correction is no longer skipped; and a corrective seek that leads by the resume cost, because
  seeking to where the party is now lands where the party was.
- The `buffering` branch holds position instead of realigning. A host publishes `buffering` from its
  own `isLoading` and `expectedPartyPositionMs` freezes for any non-playing status, so the 500ms
  test passed almost every time and every host stutter cost every guest a seek.
- An `isLoading` guard and a duration clamp on the correction path, matching desktop.
- A bounded `subscribe`, the clock offset measured from a polling floor rather than behind the
  subscription, coalesced refreshes, broadcast payload decode ordered over
  `(sequence, state_updated_at)`, readiness keyed on whether a duration is known rather than on its
  value, and the `WatchParty` / `WatchPartyPlayer` log tags.

Mobile still lacks `ZSupabaseProvider` and `ZSessionBridge`, so the port itself is unstarted.

**Verified:** `:composeApp:testAndroidHostTest` passes, 1,246 tests, including new coverage of the
drift bands, the proportional nudge and its cap, and the seek lead applying only when behind.

## The social backend is Z-owned, and why it had to be (2026-09-01)

The social schema was written to extend `public.profiles`, with thirteen foreign keys into it. That
table lives in the **official Nuvio** project at `api.nuvio.tv`, which NuvioMedia operates and we
have no administrative relationship to. Nuvio Z is a mod of Nuvio, not a product we run. As written,
the feature was only deployable by NuvioMedia, and the local-only fixture that stood in for their
table during tests is precisely what kept that hidden - the suite passed against a stand-in for
infrastructure we cannot deploy to.

Nuvio Z now has its own Supabase project, `pzbpghmmordvzcfbayoh` (eu-central-1, Postgres 17), holding
**only** the social and Watch Together surface. Accounts, profiles and all base user data stay on the
official backend, so a Z install remains cross-compatible with vanilla Nuvio. `AGENTS.md` carries the
full rules under **The Two Backends**; the short version is that nothing may ever deploy to theirs.

`public.z_identities` replaces `public.profiles` as the anchor. Because every table is now ours, a
fresh database applies all migrations unaided and the fixture is deleted - `supabase db reset &&
supabase test db` works directly.

### One identity, two backends

Users still sign in once, to official Nuvio. Supabase third-party auth trusts only five named
providers, so the Z project cannot simply be told to trust that issuer; the `z-session` Edge Function
bridges instead. It works without any cooperation from NuvioMedia because their project publishes an
asymmetric ES256 JWKS, so a user's token can be verified with the public key alone.

The function verifies the presented token, then confirms the claimed profile belongs to its subject
by querying the official REST API **with the caller's own token** - their RLS answers it, so a
profile the caller does not own returns nothing. That step is load-bearing: profile UUIDs are visible
to friends through the feed, so without it any user could claim an identity they had merely seen.
`owns_profile()` then requires the token and the registry to agree, and reads the active profile from
the token rather than from a caller-supplied argument.

Two things were found by executing rather than reviewing, both of which would have failed in the
field:

- The exchange originally minted its own HS256 token signed with the project JWT secret. The Z
  project signs asymmetrically, so no shared secret exists and the token would have been rejected.
  Supabase now issues the session (`generateLink` + `verifyOtp`), which also yields refresh tokens.
- The follow-up fix was first written into migration `202609010002`, which was already applied.
  `db push` tracks migrations by version rather than content, so the change would silently never have
  reached the database - passing locally, failing live. It shipped as `202609010008` instead, and
  applied migrations stay immutable.

### Desktop client wiring

`ZSupabaseProvider` is a second Supabase client alongside the official one, and `ZSessionBridge`
performs the exchange, caches the session per profile, and re-exchanges once when a Z token is
rejected. `SocialRepository` and `WatchPartyRepository` now talk exclusively to the Z client; the
official client keeps playback and sign-in. Realtime is gated on a live Z session because both social
and party topics are private channels authorized by RLS on `realtime.messages`.

Endpoints come from the ignored `local.properties` as `NUVIO_Z_SUPABASE_URL` and
`NUVIO_Z_SUPABASE_PUBLISHABLE_KEY`. Blank values leave `ZSupabaseConfig.isConfigured` false and every
social surface hidden, which is the same degradation an undeployed backend produces, so a build
without them is valid rather than broken.

Desktop is wired first deliberately, to get one real exchange through before the shape is duplicated
into mobile.

## Social foundation and Watch Together implementation (2026-09-01)

A new private `Zokaper/nuvio-z-backend` repository now owns versioned social/party migrations,
RPC-only mutation boundaries, RLS/private-Realtime authorization, throttling and pgTAP coverage.
The KMP client has Realtime installed, stable profile-UUID activation, handle/friend/feed UI,
offline activity outbox, sanitized player presence, Home rows, a Social root tab, retained Downloads
routes plus a Library shortcut, and Watch Together lobby/player synchronization primitives.
Backend capabilities default off, so an undeployed or older server disables the surfaces cleanly.

The plan itself was only ever held in a Codex session; it is now checked in as
`Docs/SOCIAL-WATCH-TOGETHER-PLAN.md` and is the source of truth for this work.

Verified on 2026-09-01: `:composeApp:compileAndroidMain` and `:composeApp:testAndroidHostTest` pass
in `nuvio-z`. The desktop port landed the same day and is covered below. Backend deployment,
staging E2E and the iOS workflow remain required before either gate is enabled.

### Desktop port (2026-09-01)

`nuviozdesktop` now carries the shared `features/social` and `features/watchparty` packages, a
`DesktopStorage`-backed `SocialStorage` actual, Realtime installed on its Supabase client, the
`WatchPartyLobbyRoute`, Home social rows, the details and player Watch Together entries, and the
watched-activity publish/remove hooks. Three divergences from mobile are deliberate and recorded in
`Docs/PATCH-SURFACE.md`: Social is added *beside* the desktop Downloads sidebar entry rather than
replacing it, the Library Downloads shortcut is not ported, and the Watch Together entry is inserted
into both of desktop's mutually exclusive detail layouts. Folding Downloads into Library is a
separate pass the maintainer has deferred.

### The backend SQL had never been executed until 2026-09-01

Codex wrote the migrations but no database ever ran them. Installing the Supabase CLI and standing
up a local stack changed that, and `scripts/test-db.sh` now resets a database, applies all seven
migrations and runs the pgTAP suite in one command: **54 assertions across 3 files, all passing.**

`public.profiles` belongs to the main Nuvio project, not to the backend repository, so a fresh
database cannot apply migration 0001 unaided. `supabase/fixtures/` holds a local-only stand-in that
the script stages as a temporary first migration and always removes again, so it can never reach a
real project through `supabase db push`.

Executing the SQL immediately found a **blocking bug**. `party_change_broadcast_trigger` picked the
party id with a single CASE expression referencing both `new.id` and `new.party_id`. PL/pgSQL
compiles that assignment as one SQL expression and resolves every field in it against the real row
type regardless of branch, so inserting into `watch_parties` raised `record "new" has no field
"party_id"`. **Every watch party creation failed**, which also meant invites, joins and the whole
party flow were dead. `202609010007_fix_party_broadcast_trigger.sql` splits the branches so only the
field that exists on the triggering table is dereferenced.

The suite also grew beyond the original structural checks. `social_authorization.sql` drives real
`request.jwt.claims` through the RPCs to prove that profile impersonation is refused on every
mutation, that the two sharing toggles gate independently, that unfriending revokes feed access at
once, and that watched publishing is idempotent. `party_security.sql` pushes a payload containing a
stream URL, request headers, a debrid token and an addon key through `party_create` and asserts none
of it survives into the snapshot every guest receives, while the legitimate info hash still does.

### Two fixes found while verifying (2026-09-01)

`202609010006_sanitize_party_payloads.sql` adds a server-side key whitelist for
`watch_parties.content`, `.source_fingerprint` and `.quality_intent`. Those columns were free-form
`jsonb` written verbatim from client input, and `party_snapshot` fans the whole row out to every
member, so a buggy or hostile client could have published a resolved stream URL, request headers, an
addon credential or a debrid identifier to the party. The plan places that guarantee on the RPC
layer, not only on the client, and it is now enforced there, and `party_security.sql` proves it
end to end rather than only unit-testing the projection.

`generateInviteCode` drew from `kotlin.random.Random`, whose sequence is predictable once a few
outputs are observed. Party invite codes are a bearer credential, so both repositories now derive
them from `Uuid.random`, which is specified to use the platform secure generator on every target.
The 32-character alphabet keeps the five-bit mask uniform.

## Correcting the stale next-episode debug builds (2026-08-30)

The first 2026-08-30 debug builds came from `claude/release-0.5.0-beta-polish-ivcjsl`, whose merge
base predates the published bridge and the named upstream sync. The transition work was valid, but
the builds omitted the release-serial updater, About/version work, upstream merges and later Z
fixes. They are not promoted or merged wholesale.

Only the coordinated next-episode change is ported here onto `claude/upstream-doctrine-stage0`.
Manual Next episode actions now follow the active mode (Classic source list, Streamlined quality
sheet, Instant automatic pick); automatic transitions resolve without stopping the current
episode, count down with cancellation, ignore stale results and stay covered until the replacement
episode produces its first playable frame. The current failure chain, ranking preferences,
unwatched-artwork blur and desktop stream-settlement coordinator were retained during conflict
resolution. Desktop also carries the native HTML-overlay Next Episode control.

Verification and publishing: `scripts/run-pure-suites.sh` passes 290 tests in each repository;
focused `testAndroidHostTest` and `desktopTest` runs compile the real platform source sets and pass
the 16 new routing, transition and threshold tests. Mobile CI `33327792025` passes. Desktop's first clean CI run built and uploaded
the Windows MSI, then exposed a pre-existing Ubuntu job gap: the synced `compose-media-player`
module now builds a GStreamer shim during `desktopTest`, but CI installed no GStreamer development
packages. The job now installs the required core/base headers before Gradle, and replacement CI
`33328140034` passes. Debug publish runs `33328752860` and `33328752260` released mobile
`debug-v0.5.0-beta.25` from `921a62dc` and desktop `debug-v0.5.0-beta.18` from `d316a28e`.
Both tags resolve to those exact current-line commits. The behavior is not yet device-verified.

## The first named KMP upstream sync is complete (2026-08-24)

Mobile now contains upstream Nuvio `0.4.8` (`e27b9195`) via merge `33f368a5`, followed by the
compiler-led integration repairs at `2c24ffb7` and the two previously documented iOS portability
repairs at `21fd0d20`. Android run `32781826587` passed the host suite, built the debug APK and
uploaded it from the final commit. The merge keeps Z's download grouping, AIO request policy,
details actions, Continue Watching details affordance and next-episode safeguards while adding
upstream's tracking, app-icon, subtitle rendering, stream autoplay, P2P and cache-refresh work.

Desktop now contains NuvioDesktop `0.1.20-alpha` (`b32dd57b`) via merge `e649ff75`. The full local
desktop suite passed, and build-only run `32781339968` compiled the final tree, built the MSI,
verified it and uploaded it. Upstream Sentry credentials are optional in this fork: absent secrets
produce an explicit warning and skip only source-bundle upload, not compilation or packaging.

Mobile `0.4.9` and `0.4.10` both replace the monolithic app host with `MainAppContent.kt`. Merging
that split wholesale on top of Z's current host creates two application hosts, so it is deliberately
the next focused migration rather than an unsafe conflict choice inside this sync. Measured against
current upstream tips, mobile is now **266 ahead / 21 behind**, patch surface **138**, conflict
surface **7**; desktop is **193 ahead / 162 behind**, patch surface **144**, conflict surface **44**.

## The numbering bridge is published (2026-08-24)

Stable `0.5.0-beta+126` is live in both KMP repositories. It is the one-time bridge that ranks above
`0.4.14-beta` for old updaters while carrying the serial-aware updater needed for the later
`<vanilla>-z1` name. Mobile run `32777297537` published four signed ABI APKs from `6778a89f`;
desktop run `32777297995` published the verified Windows MSI and checksum file from `ee193661`.
Both tags resolve to those exact commits and both repositories return the bridge from
`/releases/latest`.




## KMP About names the vanilla base (2026-08-24)

Settings → About in both KMP repos now derives the vanilla base from the Z version name: a build
named `0.6.0-z2` shows `Based on Nuvio 0.6.0`, and a debug build `0.6.0-z2.3` names the same base.
The bridge (`0.5.0-beta`) and malformed/pre-scheme names show no base rather than inventing one.
The rule lives in the new import-free `core/build/NuvioZVersion.kt` instead of in updater or
platform code, with three tests per repo. Both `scripts/run-pure-suites.sh` runs pass: **287 tests,
0 failures** in each repo. Focused Gradle runs exceeded their bounded local runtime and were
stopped; Compose wiring remains a CI gate.

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
