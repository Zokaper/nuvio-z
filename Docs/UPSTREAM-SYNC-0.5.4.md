# Pre-release upstream sync: mobile 0.5.4-beta, desktop 0.1.26-alpha (2026-09-28)

The durable record of the convergence that precedes the final release candidate. It replaces the
temporary `Nuvio Z/UPSTREAM-SYNC-0.5.4-LEDGER.tmp.txt`. Branch `claude/pre-release-upstream-sync` in
both repositories, cut from `claude/mobile-release-hardening` `32484c20a` (mobile) and
`045102e65` (desktop). **Not merged to `main` / `Dev`. Nothing published.**

| Repo | Old base | New base | Upstream commits | Conflicted files |
| --- | --- | --- | --- | --- |
| `nuvio-z` | `0.4.13` (`42a9febfe`) | `0.5.4-beta` (`9bb601f04`) | ~370 | 122 |
| `nuviozdesktop` | `0.1.23-alpha` (`af480339`) | `0.1.26-alpha` (`d499bed3`) | 344 | 46 |

Desktop `0.1.26-alpha` already carries mobile `cmp-rewrite` through `0.5.2-beta` (`a3bee1ac0`), so a
shared file can legitimately trail mobile by the 0.5.2 -> 0.5.4 delta. That is the reason some
shared files are not byte-identical across the repos after this sync; see "Shared code" below.

**Version files were not moved.** `MARKETING_VERSION` (mobile, `0.4.13-z1`) and the desktop version
properties keep their values: `ReleaseSerial.xcconfig` requires the base bump to land in the same commit
as the stable serial bump. At the release the names become mobile `0.5.4-z1` (upstream's tag reads
`0.5.4`) and desktop `0.1.26-alpha-z1`, and the unreleased changelog entries (mobile serial 127, desktop
serial 132) take the new version strings in that same commit.

## Z subsystems kept whole (upstream's replacement rejected)

- **Updater / release channels.** Upstream's stable/beta channel stack (`UpdateChannel`,
  `UpdatePreferences`, `ReleaseSelector`, `VersionUtils`, `AppUpdaterRepository`,
  `hasSingleUpdateChannel`) removed on every platform. Desktop's `android-release`, `desktop-release`
  and `update-store-source` workflows kept as Z's: upstream retargeted releases at the branch and
  re-derived the tag from the version.
- **Downloads.** Phase 9 kept whole. Upstream's Android download scheduler/worker and Library
  Downloads entry removed; `AndroidDownloadScheduler` stays deleted on desktop. Upstream's
  `DownloadSubtitles` read side is kept and inert.
- **Navigation.** Z's navigation bar, tab host and standalone Downloads route kept. Upstream's
  `FloatingNavigationBar`, jelly bars (`DesktopNavigationBar`, `core/ui/jelly/*`), `RootTabHost` and
  `NavigationBarSettingsSheet` removed.

## Z behaviour kept over upstream reworks

Loading surface (`PlaybackLoadingHost`), next-episode transition machine (the party host owns the
advance; download-aware), party ownership and party-aware seeks, credential-refresh failover,
`StreamsScreen`, home hero, PIN dialog and profile switcher (the compact profile picker, `19aad9b2b`
and follow-ups, is deferred), settings screens (upstream's page navigator and attribution footer are
deferred), per-item subtitle restore (upstream's `c9d6f5f63` not adopted), per-language audio resolver
(subsumes desktop `8f5dfee7f`), MAL/Kitsu absolute-episode skip lookup, playback-mode stream routing,
and the "older sync payload keeps the local value" rule (`SyncPreferenceJson.syncKeysToClear`).
Upstream's next-episode autoplay state machine and reuse-last-link cache are not adopted.

## Upstream adopted

- **Player:** lifecycle keyed by source + episode (`PlaybackKey`, `updatePlaybackSnapshot`,
  `finishTimelineScrub`); the new default controls layout with the legacy-layout toggle (phones);
  movie credits and post-credits skipping; new loading spinner; Android buffering fixes; subtitle
  loading fixes and scrollable subtitle rails; Android engine `0.1.1 -> 0.1.2`; iOS engine and the
  MPVKit submodule.
- **Content:** custom poster URLs, episode shuffle (mobile), MDBList, ratings visibility, the stream
  background setting (now honoured by Z's phone streams layout), provider filter reset.
- **Desktop:** native Picture-in-Picture (wired into Z's surface promotion), macOS title bar, Linux
  HiDPI, Discord presence, the pause-overlay setting in the native controls, poster navigation
  motion (off on desktop).

## Repairs after the merge (both repos)

The merges compiled only after these. Each was a real seam, not 321 bugs:

- `PlayerSettingsRepository`: one closing brace lost in the merge swallowed every member after it.
- **Auto-skip arrived twice.** Z carried an older copy of upstream's auto-skip via desktop
  convergence, and upstream mobile then shipped its own. Upstream's storage, dialog and summary kept;
  Z's keyed, resume-aware effect kept. One effect condition in both repos: Z's checks plus upstream's
  controller-belongs-to-source and resume-seek-landed guards.
- **Engine snapshots go through `updatePlaybackSnapshot`.** The merge kept Z's surface callback, which
  assigned the snapshot directly, so `playbackSnapshotKey` was never set: the next-episode card could
  never appear and a scrub released while buffering stayed pinned. Found after the first push,
  fixed in `f9e25eb1a` (mobile) and the desktop merge; `PlayerSnapshotRoutingTest` guards it in both.
- Helpers dropped when a file was taken whole from mobile upstream, although Z's kept call sites use
  them (vanilla-desktop code, still in desktop 0.1.26): `PlayerResizeMode.Stretch`,
  `persistedAddonSubtitleUrlForItem`, `AddonFilterChip` visibility, the tablet streams layout.
- Episode change clears the previous item's subtitle selection (upstream's key now includes the
  episode, and its reset block had dropped four fields).
- The Infuse hand-off closes the player through its release-first back, not a bare pop.
- `HomeCatalogSettingsRepository` takes desktop 0.1.26's variant (upstream `73005d996`'s input-keyed
  dedupe), so a collection source edit reloads the Home hero.
- Dead toggles removed or wired: the stream background mode (wired), "show loading status" (desktop
  native overlay only), legacy controls (hidden on desktop).
- Orphans of deferred work deleted: `ProfilePopupContent`, `ProfilePinEntry`,
  `ProfilePopupPositionProvider`, `StreamLoadingScreen`, `SettingsAttribution`, the compottie
  `ResourceLottieCompositionSpec`.

## Tests

Upstream tests of work not adopted were removed (autoplay machine, per-episode subtitle restore,
hero pager); tests that assert upstream's sync rule were rewritten to Z's; three upstream tests whose
fixtures predate upstream's own `80860602f` placeholder rule (< 121 s is an error clip) got realistic
durations. `DownloadsAndroidLifecycle` creates its `Dispatchers.Main` scope lazily: its static
initializer made the object unloadable for the rest of a host-test JVM after any test reset Main.

**Pre-existing, not changed:** Z's default stream-presentation scope (`ALL_ADDON_STREAMS`, `5058a313f`)
applies upstream's minimum-quality filter to every installed-addon stream, and that filter excludes
unknown resolution - so a direct addon stream whose name carries no resolution is hidden by default.
Found through `StreamOrientationTest`'s fixture; the fixture now names a resolution. A product decision,
flagged for the maintainer.

## Shared code after the sync

Byte-identical across the repos where desktop upstream is not behind: the player runtime
(`SourceActions`, next-episode helpers, controls and the WT seam), settings search, the Social
recovery and auth patches. Legitimately different: files where desktop upstream trails mobile
0.5.2 -> 0.5.4 (dialog styling on the Playback settings page, `isShortPlaceholderDuration` in the
next-episode guard, shuffle), desktop-only files, and the files `nuviozdesktop/AGENTS.md` lists as
never copied.

## Final verification and audit

The exact pre-documentation heads were mobile `671237076` and desktop `9a095d8cd`; both dedicated
branches were pushed before the long verification runs.

- Mobile Android host: **3,187 total / 3,181 passed / 6 skipped / 0 failed**. Full-debug assembly,
  release Kotlin compilation and release R8 pass. Release assembly reaches signing and stops only
  because the local release keystore is absent.
- Mobile CI: push run `36459921568` and iOS run `36460723843` pass. The iOS run links device and
  simulator frameworks and builds the unsigned Xcode app.
- Desktop split suite: **3,184 / 3,184**, no skips/failures/errors or duplicate cases (rest 1,651,
  playback 1,027, Downloads 457, E2E 49). CI run `36460011350` builds the Windows MSI; its Linux job
  stops at the known vendored `frame_copy_test` `player != NULL` assertion before Kotlin tests.
- Pure group 1 passes (mobile 279, desktop 278). Group 2 retains its pre-existing standalone source
  list failure around the Downloads model/format helper; the authoritative Gradle suites compile and
  execute those tests.

The final conflict-resolution review found no remaining release correctness issue. Conflict markers
are absent. Upstream's scheduler, updater/channel stack and replacement navigation were not
resurrected; the Z toolbar retains Watch Together; identities and version files are unchanged. All
235 raw shared-file differences were classified: 146 are the expected mobile 0.5.2 -> 0.5.4 gap,
59 pre-date the sync, 24 belong to desktop's upstream delta, 3 are declared never-copy files, and 3
are inspected intentional seams (the mobile-only lazy Android Downloads host scope and two
short-placeholder fixtures). Shared Social/auth recovery is byte-identical; the bridge retains its
required Native-atomic versus JVM-volatile implementation difference. No missed Z shared change was
found.

## Required final-RC physical pass

The existing debug-65 mobile Watch Together checklist remains required. On the eventual synced RC,
smoke-test Android, iOS and desktop player entry/exit, source and episode changes, both control
layouts, the Watch Together toolbar entry and party-controlled play/pause/seek; credits skipping,
subtitle loading, custom posters and shuffle where supported; and Phase 9 Downloads/offline playback.
Exercise Social after sleep/token expiry. On desktop, verify release, debug and vanilla installs do
not steal or revoke one another's official login. Only after those results may the version/serial
bump be the final tracked release commit. This convergence did not cut or publish that RC.
