# Social V2 upstream sync - 2026-10-20 (verification continued 2026-10-21)

This sync follows the completed mobile language/What's New port and Advanced Setup
Language pages. It does not publish the Social V2 stable release. Maintainer reports
**no additional installed-build testing** since the handoff.

| Family | Previous named base | New named base | Upstream commits |
| --- | --- | --- | --- |
| Mobile | 0.5.4-beta, 9bb601f04 | 0.6.2-beta, 1cfae0fe7402f5e6f1fedad393d50d83775586fa | 263 |
| Desktop | 0.1.26-alpha, d499bed3 | 0.1.31-alpha, e808a584e937d3ce148f5e023180586bc3545002 | 434 |

Merge commits: mobile `8dff1c90a`, desktop `74415dd0b`. The three new shared Z adapters
were split from the mobile resolution into `f5e1c0f6c` and cherry-picked to desktop
as `3d30d57d7`; divergent files were resolved from each family's upstream delta.
No rebases, wholesale source copies, upstream pushes or backend deployments.

## Inherited behavior

Media-server libraries/playback/resume, MDBList tracking authentication, subtitle
AutoSync on supported Android playback, Exo native-memory/buffer/VOD-cache settings,
stream information and engine selection, updated YouTube extraction, source metadata
and next-episode prefetching, locale/formatting fixes and upstream UI refinements.
The old source-reuse feature stays removed by Z's existing decision.

Mobile inherits iOS PiP/audio-session/system-control work while preserving Z's serial
mpv executor and deferred teardown. The new media-property snapshot reads native
properties only on that executor, publishes cached strings to Kotlin, clears on load,
and is covered by the Swift lifecycle regression in the iOS build workflow.

Desktop inherits the new Nuvio engine/JVM/native packaging in place of TorrServer,
window insets/back/fullscreen handling, 250 ms player snapshots, media metadata,
stream information and native controls improvements. Z's surface-promotion and
release-retention hooks, preferred audio selection, HTTP failure behavior, wheel
smoothing, prefetch, tray/startup and signed installer policy remain at their seams.
Old TorrServer-specific fixes are inapplicable after its upstream removal.

## Deliberate decisions and conflict resolutions

| Upstream difference | Resolution and reason |
| --- | --- |
| Official Party service/UI | Keep Z Watch Together (S07/S11/S17) as the active surface. Inherited official Party code is gated by `UPSTREAM_PARTY_UI_ENABLED=false`, including settings/player/deep links. Optional maintainer choice to expose both remains open; no second party system is silently activated. |
| Desktop episode shuffle | Keep the explicit mobile-only, off-by-default decision in UPSTREAM.md. Desktop settings/search hide it and repository reads cannot enable imported shuffle preferences. Mobile behavior stays inherited and off by default. |
| Source route, next-episode selection and loading | Keep Z Classic/Streamlined/Instant ranking, watchdog/failover, quality sheets and PlaybackLoadingHost. Carry server/YouTube preparation, stream identity and prefetch deltas through those paths. Deleted upstream tests of the replaced autoplay flags/loading screen stay deleted; Z coordinator/runtime tests remain. |
| Detail hero/actions | Inherit upstream tablet hero and availability logic; retain Download, Watch Together and Recommend through the new ZDetailActions adapter and defaulted prefix/suffix action seam. |
| Settings layout/language | Keep approved Z hub and language sections; expose new upstream controls through the appropriate existing pages. Buffer controls use a new Z-owned adapter rather than restoring the old monolithic playback page. |
| Download queue | Keep Z's device-wide queue and profile views; desktop's upstream completion badge observes real Z-store transitions and resets when changing profile. |
| Stream loading UI/native opening spinner | Keep the named Z loading-band decision, including native facts/manual escape; retain upstream buffering indicator and guard the absent opening canvas. |
| Release identity/configuration | Existing marketing versions, serials, identifiers, backend separation, the Z updater channels and optional credentials remain. The newly inherited standalone desktop UpdateChannelSettings page is inapplicable to Z's existing updater architecture and remains removed. Debug counters are separate from release metadata. |

Upstream player/settings conflict work extracts adapters at existing seams instead of
reorganizing upstream source. `Docs/PATCH-SURFACE.md` records the resulting surface.

## Verification

Before the sync: Android full compile and focused host suites **575/575**; desktop
language/Advanced Setup **111/111**; standalone language/setup/What's New **183/183**.
Actual 360/420/1280 dp Advanced Setup renders were inspected. Earlier desktop
language/What's New verification remains **580/580**, with live interaction checks.

Post-sync Android full compilation and the complete host suite pass **3,828/3,828**,
with zero failures/errors/skips (`build/upstream-mobile-verification-4.log`). All nine
standalone mobile groups pass **1,090/1,090** (`build/upstream-mobile-pure-4.log`).
All nine desktop pure groups pass **1,080/1,080**. The full local desktop suite
passes **3,874 tests**, zero failures and one existing ignored test, with promotional
frame capture disabled through its existing filters; real UI render tests remain enabled.
Android CI (39433923399), iOS framework/Xcode/Swift lifecycle CI (39433923450),
and mobile debug 108 publication (39433924208) pass at `e5d618e81`.
Clean Windows MSI also passes at `1d62ae692` (run 39440410030).
The first integration
compile found and corrected shared server serialization, runtime API and settings
signature mismatches; the desktop catalog also had a duplicate realtime dependency.

The prior Linux CI `frame_copy_test` failure occurred while creating the real
GStreamer player, before Kotlin tests. CI now installs runtime base plugins and
checks `playbin`/`appsink` availability. The next run passed frame copying, then exposed missing development packages for
the newly inherited Linux mpv/WebKit/GTK bridge. Those dependencies are now installed
in CI too. The inherited bridge then exposed synthetic lifecycle handles reaching JNI;
the test fixture now isolates all native calls, and that crash no longer occurs.
Linux's remaining two phone-navigation fitting failures used desktop's 1.08 font
scale in phone scenes; a2bb9db54 uses phone font scale 1 with unchanged assertions.
Routine CI also skips promotional frame generation through the existing filters.
Fresh Linux CI 39598087116 is running. Debug 116's first fresh run found the old
macOS TorrServer source/package requirement; 05bb85efb replaces it with real Nuvio
engine resource, architecture and dependency-path checks. The universal engine's
arm64 slice is verified directly with lipo (c94dc316c). Corrected debug build
39599213572 is running. YAML and all ten Bash steps parse.
Local desktop Gradle can use an older native DLL; only a clean CI/debug MSI proves
new C++ integration. Installed Android/iOS/Windows language/player, PiP, media server,
AutoSync and party acceptance remains pending. Final release notes/artwork are the
maintainer's work; no fixture changelog was promoted to product release copy.

Desktop full-suite integration corrections: registry now classifies the inherited
server_credentials store and the existing Social V2 popup ledger/tray stores (account
ledger/credential wipe; device tray preferences survive). Inherited DesktopCache JAR
handles are closed (V7; real Windows regression 2/2). The short-placeholder threshold
guard and realistic-duration fixture from mobile `a8dde1fbd` had not reached desktop;
they are now surgically carried into its divergent next-episode implementation. The
native opening-band policy removes the absent spinner lookup instead of weakening
the HTML/JS element contract test. Focused follow-up passed **335 tests** before
the complete passing run above. A later local native-isolation/navigation selection
also passed; the final phone-scale/native-lifecycle follow-up passes **28/28**, zero
failures/errors/skips (`build/upstream-final-nav-native.log`). Inspected 411/360 dp
PNGs retain full labels on the phone and demote them at the smaller width.
The unchanged TV/web port passes **184/184** Node checks on 2026-10-21.
