# Performance audit — desktop stutter and iPhone heat (2026-10-01)

> **Phase 1 implemented (2026-10-01):** Tier A items 1, 2 (desktop and iOS) and 4 of section 6 -
> measured before and after in section 8, on branch `claude/perf-phase-1`.
>
> **Phase 2 implemented (2026-10-01):** Tier B item 5 (poster scaling off the UI thread) and the
> CDS half of item 7 - measured in section 9, on branch `claude/perf-phase-2`. Only the JDK half of
> the CDS archive is shippable on JDK 17 MSI installs; section 9.2 says why.

Branch `claude/perf-investigation` in both Kotlin repos (isolated worktrees; no release, feed or
version changes). This is an **investigation only**: no production code was changed. The harness
that produced every desktop number below is committed in `nuviozdesktop/scripts/perf/`.

## 1. Executive summary

Performance is **not fundamentally unhealthy**, and most of what the maintainer sees on desktop is
**inherited from upstream Nuvio**, measured side by side against an installed vanilla Nuvio on the
same machine. Z adds three specific, fixable costs on desktop and one structural one on mobile.

| Symptom | What actually causes it | Attribution |
| --- | --- | --- |
| Desktop: rough first seconds after launch | One 3.2–3.7 s UI-thread task builds the window and the profile picker (under the profiler; 2.3 s for Z without it). Vanilla under the same profiler: 2.4–2.5 s. The ~0.8 s difference is mostly Z's settings-sync start running **inside the first frame** (MDBList's Ktor client, 17 repository loads). The rest is JVM class loading/JIT and Skia/D3D setup. | Upstream behaviour **made worse by Z** (Z regression ≈ 0.5–0.75 s) |
| Desktop: rough "around/after profile selection" | (a) Z blocks on a full network profile sync behind a spinner: click→Home **4.5–5.5 s** vs vanilla **2.3 s**; (b) then a single **1.3–1.5 s** freeze while the whole main shell + Home composes for the first time (same in vanilla); (c) ~45 s of hitches as rows/images stream in. | (a) Z; (b),(c) upstream |
| Desktop: Home scroll stutter | Scrolling is mostly cheap (median frame 3–6 ms) but **2–7 frames per 7.5 s of scrolling take 33–250 ms**. They are: composing newly visible rows/cards (~60%), and an upstream **CPU image rescale run inside `onDraw` on the UI thread** (`ScaledBitmapPainter`, 4.6–9.6 ms per poster, ~14 ms per Continue Watching card, ~20–35% of long-frame time). Vanilla has the same numbers. | Upstream. Z's blurred top bar adds a small, measurable extra (more ≥50 ms present gaps). |
| Desktop: work while "idle" | Hero carousel every ~8.5 s — **also while Home is hidden behind another tab** (151 frames in 26 s on a static, empty Library tab). Otherwise idle cost is low: 0.05–0.08 of a core without a profiler, mostly JIT. | Hidden-Home ticking is a **Z regression** (upstream's gating was dropped in the 0.5.4 merge) |
| iPhone warm | Every debug and Watch Together diagnostic IPA is built `-configuration Debug`, which links the **unoptimised Kotlin/Native debug framework** — all shared Compose UI runs without compiler optimisation. Separately, on iOS **every native tab hosts its own hidden, fully live HomeScreen**. WT diagnostic logging itself is low-volume. | Build configuration (testing only) + **Z regression** (hidden Home). Needs one physical A/B to size. |

**Feasibility:** the three Z regressions are small, local fixes with low risk. The upstream Home
scroll cost has one moderate, well-contained fix (move the image rescale off the UI thread) worth
roughly a quarter to a third of scroll hitches; the rest (composition cost of rich poster cards,
JVM warm-up) is upstream-level work. A JVM class-data-sharing archive is a packaging-only lever
measured in §2.4.

## 2. Desktop findings

### Method (why these numbers can be trusted)

- The **installed** apps were profiled, not a dev build: vanilla Nuvio (`C:\Program Files\Nuvio`,
  jpackage `1.1.26` — a *newer* upstream than Z desktop's `0.1.26-alpha` base (`d499bed3`), sidebar nav), Nuvio Z release
  (`2.0.131`). Same machine (Ryzen AI 9 HX 370, Radeon 890M, 2880×1800 @ 60 Hz, 150 % scale,
  window maximised = 1921×1178 logical), same JDK for both (Android Studio JBR 21, because the
  shipped JDK 17 runtimes do not include JFR), same scripted protocol:
  launch → picker → click profile → 45 s → 20 s idle on Home → 6 wheel passes (120 notches at
  50 ms, down/up ×3) → 30 s idle → (some runs) switch tab / 60 s idle → close.
- Input was driven **in-process** (AWT events posted to the Compose window), which works where
  Win32 synthetic input does not. The mouse rests over content, as a real wheel user's does.
- Frame cost was measured by wrapping the AWT event queue: every skiko `FrameDispatcher` task is
  one Compose frame (composition + layout + draw happen on the UI thread on desktop). Long frames
  were attributed with JFR samples falling inside each frame's window; stalls with an EDT stack
  sampler (same method as `EdtStallWatchdog`).
- Vanilla is signed into a **different account** (Z moved its session into per-app storage), so its
  Home content differs. Tokens were never copied between apps. For A/B experiments Z ran on a
  **copy** of its data with the official session file removed (signed out, so nothing can rotate a
  real token; settings sync is inert), which reproduced the signed-in render numbers.
- Other agents' Gradle daemons were running throughout; apps were always compared back-to-back.

### 2.1 Startup (launch → profile picker)

- **Symptom:** the app feels rough for the first seconds.
- **Measured:** the first UI-thread task (window creation + first composition of `AppGate` and the
  picker) blocks for **3.24–3.70 s in Z** (6 runs) vs **2.44–2.51 s in vanilla** (2 runs). Inside
  Z's task (JFR, 1 ms sampling): Skia/D3D window setup 36–42 %, class definition 25–30 %,
  `ProfileSettingsSync` **14–23 %** of which MDBList's client 11–19 %, Swing L&F font setup 6–11 %.
- **Cause (Z):** `AppGate` calls `remember { ProfileSettingsSync.startObserving() }` during
  composition (Z commit `bacb3a2343`, 2026-09-04, a correct fix for the setup wizard losing settings,
  but it moved the work onto the UI thread). `startObserving` → `ensureRepositoriesLoaded()` loads 17+
  repositories from disk, and `MdbListSettingsRepository`'s class init builds `MdbListNetworkEngine`,
  i.e. the process's **first Ktor `HttpClient`** (Ktor + slf4j class loading), plus
  `MetaScreenSettingsRepository.localizedString` (`runBlocking { getString() }`) and
  `EpisodeReleaseNotificationsRepository` (loads the library). Upstream runs the same call from
  `warmProfileBoundRepositories()` on `Dispatchers.Default`.
- **Upstream part:** window/Skia setup and cold class loading. The shipped runtime has **no CDS
  archive at all** (no `classes.jsa`, in vanilla or Z), so every JDK and app class is parsed and
  verified each launch. JIT compiler threads were ~76 % of all process CPU over a 5-minute session.
- **Fix:** start the observer off the UI thread (e.g. a `LaunchedEffect` that hops to
  `Dispatchers.Default`, keeping it idempotent and still before the wizard can write). Optionally
  make the MDBList client lazy.
- **Expected:** −0.5 to −0.75 s of the cold-start freeze. **Difficulty:** easy. **Risk:** low —
  the observer's contract is "started before settings are written", which a start a few ms later
  still satisfies; keep `SetupWizardClickTest`-style coverage of the wizard-writes-are-pushed case.

### 2.2 Profile selection → Home usable

- **Symptom:** rough "around/after profile selection".
- **Measured, three distinct parts:**
  1. **Spinner wait (Z):** click→main shell **4.5–5.5 s** signed in, **1.6–1.7 s** on the signed-out
     copy, **2.3 s** vanilla. The difference is `SyncManager.pullAllForProfileAndWait` — the gate
     holds the spinner until a full ordered profile sync completes (`04c6b4d55`, setup cross-family
     arrival). Upstream fires `pullAllForProfile` and shows Home from cache. The spinner itself is
     smooth (~50 fps); this is latency, not jank.
  2. **One big freeze (upstream):** the first composition of `MainAppContent` + tabs + Home is a
     single UI-thread frame of **1.27–1.47 s in Z** (6 runs) vs **1.46–1.55 s vanilla**. About half
     the samples are composition, the rest class loading/linking and cold (interpreted) code.
  3. **Burst while content streams in (upstream):** ~12–14 frames ≥33 ms over the next 45 s as
     catalog rows, Continue Watching enrichment and images arrive (same in vanilla).
- **Fixes:** (1) mount Home from cache and gate only the setup-wizard *decision* on the pull — moderate,
  medium risk (the reason it blocks is real: a stale local settings read can mis-gate setup).
  (2) Pre-load the main shell's classes on a background thread while the picker/spinner is up
  (precedent: `preloadP2pStreamingEngineAsync`), and/or a CDS archive (§2.4) — moderate, low risk.
- **Expected:** (1) −2 to −4 s of waiting on every profile entry. (2) part of the 1.3 s freeze.

### 2.3 Home initial load and Home scrolling

- **Symptom:** visible stutter scrolling Home, even after everything settles.
- **Measured (warm passes 2–3, 4 × 7.5 s, signed-in):** Z 266–274 frames, **12–16 frames ≥33 ms**,
  5–7 ≥50 ms, worst 86–87 ms. Vanilla: 233 frames, **13 ≥33 ms**, 5 ≥50 ms, worst 150 ms. First
  (cold) pass is worse in both: worst 140–244 ms. Median frame 3–6 ms; the UI thread is only 8–14 %
  busy while scrolling — it is a **hitch** problem, not a throughput problem.
- **What a long frame is made of** (Z run 3 / vanilla run 2, JFR inside each ≥25 ms frame):
  composition of newly visible rows and poster cards ≈1.0 s / 1.0 s, **image rescale in `onDraw`
  0.33 s / 0.62 s**, measure/layout 0.18 / 0.10 s, text layout ≈0.1 s each. GC is irrelevant
  (124 ms of pauses in 4 minutes, worst ~18 ms).
- **The image rescale (upstream `AsyncImage.desktop.kt`, 0 Z commits):** on Windows every poster is
  decoded at up to 1536 px, then the first time it is drawn at a size `ScaledBitmapPainter`
  copies the bitmap and runs Skia `scalePixels` with **mipmap generation, on the UI thread, inside
  draw**. The painter is created per `Success` state, so a card that leaves and re-enters
  composition rescales again. Benchmarked with the app's own skiko on cached posters:
  **4.6 ms** (500×750 → 378×567), **9.6 ms** (1280×720 → 378×567), **14.2 ms** for a Continue
  Watching card (744×419). A row of 6–7 posters scrolling into view is 35–65 ms of UI-thread work.
- **Composition cost (upstream):** `NuvioPosterCard` / `NuvioShelfSection` / `posterCardClickable` /
  `nuvioCardDepth` / poster style / watched badge — all byte-identical to the upstream base. Each
  card is a rich composable; a new row composes 6–10 of them in one frame.
- **Hover preview:** with the pointer resting over content while scrolling, cards pass under it and
  the (upstream) hover preview opens when the pointer settles; it appears in long-frame samples.
- **Z's top bar blur:** Z's desktop default top pill applies a 24 dp `haze` blur with the entire tab
  content as its source. A/B on the same data (2 pairs): UI-thread frame cost the same; **≥50 ms
  present gaps while scrolling 14, 14 (TopBar) vs 8, 8 (Sidebar)**. Real but small; probably GPU-side.
- **Fixes:** (A) do the high-quality downscale in Coil's pipeline off the UI thread (decode/transform
  to the measured size, or prepare the scaled bitmap asynchronously and draw the source until it is
  ready) — moderate, low-to-medium risk, **upstream-owned file** (widens the patch surface; worth
  offering upstream). Expected: roughly a quarter to a third fewer scroll hitches, most of the
  >50 ms ones on Continue Watching. (B) Poster-card composition cost — reduce per-card layers,
  verify `contentType`/stable keys, consider composing rows ahead — upstream-level work.

### 2.4 JVM class-data sharing (packaging lever)

Neither runtime ships a CDS archive. A static AppCDS archive was built from one run's
`-XX:DumpLoadedClassList` (12,283 classes, 107 MB, `-Xshare:dump`), then the same signed-out
protocol ran with and without it, **without JFR**:

| | no CDS | static AppCDS | change |
| --- | --- | --- | --- |
| First UI task (window + picker) | 2,317 ms | 1,431 ms | **−38 %** |
| Main-shell first composition after profile click | 1,109 ms | 786 ms | **−29 %** |
| Process CPU idle on Home | 0.07–0.08 cores | 0.05–0.07 cores | ~same |
| Warm scroll frames ≥33 ms | 6 | 16 | not significant (single runs vary 6–16) |

The JDK 17 runtime supports static AppCDS (and jlink's `--generate-cds-archive` for the JDK half);
a newer bundled JDK would allow `-XX:+AutoCreateSharedArchive` / AOT caches. The archive must match
the exact jar set, so it has to be generated in the packaging job. **Packaging-only, no app-code
risk, moderate CI work; worth −0.3 to −0.9 s on both freezes users notice most.**

> These no-JFR runs also show what JFR cost the other runs: about +1 s on the first task and roughly
> double the idle CPU (0.15–0.19 cores with JFR vs **0.05–0.08 without**). Every Z-vs-vanilla
> comparison above carried the same overhead on both sides, so the comparisons stand; the absolute
> numbers in §2.1–2.3 are upper bounds.

### 2.5 Desktop debug-build overhead

The installed Nuvio Z Debug (`-Dnuvio.debugTools=true`: `EdtStallWatchdog` posting to the UI
thread every ~16 ms, every stdout byte teed to a log file with a flush per line) was run on the same
signed-out data, without JFR, against the release build:

| | release | Debug |
| --- | --- | --- |
| First UI task | 2,317 ms | 2,420 ms (a first launch on fresh data: 3,229 ms) |
| Main-shell freeze after profile click | 1,109 ms | 1,382 ms |
| Process CPU idle on Home | 0.07 cores | 0.06 cores |
| Warm scroll frames ≥33 ms | 6 | 13 (release single runs range 6–16) |

The desktop debug tooling is **not** a meaningful cost: at most a few hundred ms at the big
transitions (the watchdog samples the UI thread's stack while it is stalled). A first Debug run on
new data also showed What's New over Home, which is why the first comparison run was discarded.

## 3. Mobile / iOS findings

No iPhone, Mac or running emulator was available, so nothing on iOS was measured. What follows is
traced in code and build configuration; §7 says exactly how to size it.

### 3.1 Debug and diagnostic IPAs run unoptimised Kotlin/Native — likely the dominant heat factor in testing

- `debug-release.yml` builds iOS with `IOS_CONFIGURATION: Debug` → `scripts/build-ios-ipa.sh` →
  `xcodebuild -configuration Debug` → Xcode's build phase runs
  `:composeApp:embedAndSignAppleFrameworkForXcode`, which picks the **debug** Kotlin framework from
  `CONFIGURATION` (no `KOTLIN_FRAMEWORK_BUILD_TYPE` override exists). Kotlin/Native debug binaries
  are not optimised; on iOS the whole Compose UI — composition, layout, Skia drawing calls, coroutines,
  JSON — is Kotlin/Native, so every frame and every background job costs more CPU. The Watch Together
  diagnostic IPAs use the same job.
- `isDebugBuild` on iOS **is** `Platform.isDebugBinary`, so the diagnostics are tied to the
  unoptimised binary: switching the framework to release would silently switch diagnostics off.
- **Normal (stable) builds** use the Release configuration and are not affected.
- **Attribution:** build configuration, testing only. **Fix:** a test build that links the release
  framework while keeping the Debug bundle id and diagnostics (decouple the diagnostics flag from
  `isDebugBinary`). Moderate; low risk to users (stable unaffected).

### 3.2 Every iOS native tab hosts its own hidden, live HomeScreen — Z regression

- On iOS 16+ each native tab is a separate Compose host:
  `NativeNavComposeView` → `MainViewController(initialTabName:)` → `App` → `MainAppContent` →
  `TabsRoute` → `MainTabsDestination` → **`AppTabHost`, which always composes `HomeScreen`** (at
  alpha 0, pointer-blocked) beside the selected tab. So the Search, Library, Social and Settings
  hosts each carry a complete Home: 16 `collectAsStateWithLifecycle` collectors in `HomeScreen`
  alone (RESUMED while that tab is visible), the Continue Watching/next-up derivation chain and its next-up resolution effect,
  catalog rows laid out at full size with their images, and the hero carousel.
- **Hero keeps animating while hidden:** `ScreenActivityEffect` reads `LocalScreenActive`, but no
  production code provides it (only tests), so it is always `true`. Measured on desktop with the
  same shared code: on a static Library tab the UI thread woke every ~8.5 s in `HomeHeroSection` /
  `PagerState` and rendered **151 frames in 26 s**. On a 120 Hz iPhone (Z allows ProMotion via
  `CADisableMinimumFrameDurationOnPhone`, inherited) each page change is a burst of high-rate frames.
- **History:** upstream tried "keep Home mounted" in July (`2309970f2`) and now uses
  `RootTabHost` on **both** mobile (`73005d996`, 2026-09-15) and desktop (`upstream/Dev`): tabs are
  composed only once visited, each pane has its own lifecycle (RESUMED only when selected) and
  `LocalScreenActive`. Z's convergence (`e606c2290`, 2026-09-18) brought an ungated always-mounted
  Home to mobile, the upstream 0.5.4 merge (`3e1da9efd`, 2026-09-28) dropped `RootTabHost.kt`
  (leaving `ScreenActivityEffect` without a provider), and desktop received the same host in
  `4afe6d9ad` (2026-09-29). Upstream's structure would compose only the selected tab in each native
  host.
- **Fix:** provide `LocalScreenActive` (and ideally a per-pane lifecycle) from `AppTabHost`; on iOS
  native navigation do not compose Home in non-Home hosts. Easy-to-moderate; low risk (restores
  upstream's behaviour). Also applies to desktop and Android.

### 3.3 Watch Together and diagnostics overhead

- Diagnostic log volume is low: a heartbeat send/ack per socket (~every 25 s), one poll line per 5 s
  in a party, and command/hold events; each is appended by `fopen/fputs/fclose` on a serial queue.
  Not a plausible heat source by itself.
- `FreezeDiagnostics` (Swift, `#if DEBUG`): a 250 ms main-thread ping and in-memory touch records.
  Negligible.
- In a party, inside the player (all Z, all reasonable): status-pill clock 250 ms (recomposes the
  pill), stall watch 150 ms, health monitor 1 s, clock ping 5 s, durable poll 5 s, presence
  heartbeat 20 s. Playback decoding dominates energy while watching regardless.
- The audio session is deactivated on player teardown, so the `audio` background mode does not keep
  the app alive after playback.

### 3.4 Upstream mobile items

Home on mobile renders through the same upstream composables (no desktop prescale, no hover
preview). Mobile UI was reported smooth; nothing here suggests a broad mobile CPU problem in a
Release build.

## 4. Performance architecture map (what keeps running)

| Loop / system | Cadence | Runs when | Origin | Justified? |
| --- | --- | --- | --- | --- |
| Home hero auto-advance | 8 s (+ animation frames) | Whenever a HomeScreen is composed — **including hidden behind other tabs and in every iOS native-tab host** | upstream loop, Z lost the gating | Only while Home is visible |
| `OutgoingJoinRequestStore.tick` | 500 ms | Process lifetime, from app shell start | Z | Only while a join request is pending |
| Watch Together party polling / clock / health | 5 s / 5 s / 1 s | In a party | Z | Yes |
| Party status pill clock / stall watch | 250 ms / 150 ms | In a party, player open | Z | Yes (cheap) |
| Social presence heartbeat | 20 s | In the player | Z | Yes |
| Social Realtime channel (Z backend) | socket heartbeat | Social enabled | Z | Yes |
| `SyncManager` periodic pull | 15 min | Signed in | upstream | Yes |
| `MemberAccessRepository` verification | 15 min | App lifetime | upstream | Yes |
| `TraktProgressRepository` refresh check | 60 s (network only with Trakt progress) | App lifetime | upstream | Mostly |
| Desktop `EdtStallWatchdog` | ~16 ms EDT heartbeat | **Debug builds only** | Z | Yes in debug |
| iOS `FreezeDiagnostics` | 250 ms main-thread ping | DEBUG only | Z | Yes in debug |
| Desktop player snapshot poll | 500 ms (native call on UI thread) | Player open | Z/desktop (known, see STATUS 2026-09-05) | Yes, but should leave the UI thread |

Nothing ticks every frame while idle. The only every-few-hundred-ms app-wide timer outside a party
is the 500 ms join-request tick (cheap, but it never sleeps).

## 5. Upstream vs Z

| Finding | Classification |
| --- | --- |
| 3.2–3.7 s first UI task at launch | Upstream behaviour **made worse by Z** (`ProfileSettingsSync` on the UI thread, ~0.5–0.75 s) |
| No CDS archive, JIT warm-up, cold class loading | Upstream (packaging) |
| 4.5–5.5 s spinner after choosing a profile | **Z regression** (blocking full sync, `04c6b4d55`) |
| 1.3–1.5 s freeze when the main shell first composes | Upstream |
| Home scroll hitches: card composition | Upstream |
| Home scroll hitches: `ScaledBitmapPainter` rescale in draw | Upstream |
| Extra ≥50 ms present gaps with the top-bar blur | Z (desktop top bar), small |
| Hidden Home keeps ticking (hero) on other tabs | **Z regression** (gating dropped in the 0.5.4 merge) |
| iOS: hidden live HomeScreen in every native-tab host | **Z regression** |
| iOS heat with debug/diagnostic IPAs | Build configuration (unoptimised K/N) — needs measurement to size |
| WT diagnostics logging | Z, negligible |
| GC / allocation churn | Not a factor (measured) |

## 6. Plan, ranked by impact vs engineering risk

**Tier A — high confidence, low risk**

1. Move `ProfileSettingsSync.startObserving()` off the UI thread in `AppGate` (desktop cold start
   −0.5–0.75 s; mobile benefits too). Easy.
2. Restore tab activity gating: provide `LocalScreenActive` (and a per-pane lifecycle) from
   `AppTabHost`; on iOS native navigation, compose Home only in the Home host. Stops hidden hero
   animation and duplicate Home work on all platforms. Easy-to-moderate.
3. Settle the iOS heat question with the physical A/B in §7 **before** changing anything else on iOS;
   if debug builds are confirmed, give test IPAs the release Kotlin framework with diagnostics on a
   separate flag. Moderate.
4. Gate `OutgoingJoinRequestStore`'s 500 ms tick on a pending request. Trivial, small win.

**Tier B — worthwhile, needs care**

5. Move `ScaledBitmapPainter`'s rescale off the UI thread (upstream file; offer upstream). Biggest
   single lever on Home scroll hitches that does not require reworking the cards.
6. Show Home from cache after profile selection and gate only the setup decision on the pull
   (−2 to −4 s per profile entry). Medium risk around setup cross-family import.
7. Warm main-shell classes during the picker/spinner, and/or ship a CDS archive (§2.4).

**Tier C — architectural, defer unless still unacceptable**

8. Poster-card composition cost and row prefetching in Home (upstream-owned composables).
9. Newer bundled JDK (AOT cache), renderer changes, Compose upgrades.
10. Reconsider the top-bar blur only if 1–7 still leave scrolling rough (measured effect is small).

## 7. What still requires physical profiling

**iPhone heat** — run each with the same phone, ~50 % brightness, not charging, Wi-Fi, Low Power
Mode off, case off, starting cool (lock screen 10 min):

1. Install three builds side by side if possible (stable `com.nuvio.app.z`, debug
   `com.nuvio.app.z.debug`; the WT diagnostic IPA replaces the debug one).
2. For each: open the app, pick a profile, then **10 min idle on Home** (screen on), then **5 min
   scrolling Home**, then **5 min on the Search tab idle**. Note battery % at each boundary and how
   warm the back feels (or an IR thermometer if available).
3. Then Settings → Battery → "Last 24 hours" → per-app on-screen/background minutes and %.
4. Send: the three battery deltas per phase, the warmth notes, the iPhone model and iOS version,
   and the `nuvio_diagnostics/` folder from the debug build.

Interpretation: stable ≈ cool and debug hot → build configuration (§3.1). Both hot on Search but not
on Home → hidden Home (§3.2). Both hot everywhere → a broader cause; then the next step is a short
in-app sampler (process CPU via `task_info`, `ProcessInfo.thermalState`, display-link rate every 5 s
into `nuvio_diagnostics/`), and MetricKit metric payloads (CPU time, display) which the debug build
already subscribes to but does not write. With a Mac, Instruments' Time Profiler + Energy Log on the
same three phases answers it directly.

**Desktop** — the harness reproduces everything here without the maintainer. Two things it cannot
show: how scrolling *feels* on a touchpad (high-rate precise wheel events; the harness sends notched
wheel events), and GPU-side cost. If touchpad scrolling feels worse than the numbers suggest, run
`scripts/perf/full.py` while scrolling by hand and send `out/<run>/frames.txt` and `driver.log`.

## 8. Performance Phase 1 — implemented and measured (2026-10-01)

Branch `claude/perf-phase-1` in both Kotlin repos (from `claude/perf-investigation`). Implemented in
`nuviozdesktop` and measured there, then carried to `nuvio-z` with `git format-patch | git am -3`
(production code and `commonTest` only; the JVM harness tests stay in `desktopTest`). The touched
shared files are identical across the repos afterwards. Not merged into any RC, auth or release
branch; no builds published.

**Method.** Local `createDistributable` builds of each step (baseline `50e501cc1` → B1 → B2 → B4),
copied aside, run by `scripts/perf/phase1.py` on a fresh copy of the signed-out data directory per
run, with an in-memory `java.util.prefs` so no run can touch the shared registry login. Before and
after runs were **interleaved**: other agents' Gradle builds were running throughout, and
uninterleaved startup runs drifted by more than the effect being measured. The decisive numbers
come from **JDK 25 JFR method tracing** (JEP 520), which counts and times a named method on a named
thread, instead of inferring work from timer wakeups (tried first: too noisy).

| # | Fix | Root cause | Before | After | Change |
|---|---|---|---|---|---|
| 1 | Settings-sync observer off the UI thread, after the first frame (`AppGate`, `ProfileSettingsSync` lock) | `remember { ProfileSettingsSync.startObserving() }` in `AppGate`'s first composition (Z `bacb3a2343`) | `startObserving` **984-994 ms on the UI thread**; window task 3,626 ms; first-frame task 797 ms; all UI tasks >= 100 ms before the picker **4,936 ms** | 0 ms on the UI thread (624-683 ms in the background); 2,556 ms; 883 ms; **4,042 ms** | **-894 ms (-18 %)** of UI-thread blocking before the picker (JDK 25 + JFR, n = 4 alternating; tracing inflates absolute numbers). Without JFR, interleaved: window 3,386 -> 2,479 ms, blocked 4,357 -> 3,773 ms (n = 3 vs 5) |
| 2 | Hidden Home paused (`TabPaneActivity`: `LocalScreenActive` + lifecycle capped at CREATED) | Upstream's `RootTabHost` gating dropped in the 0.5.4 merge; nothing provided `LocalScreenActive` | Home hidden behind Library, 60 s: **200-404 frames/min**, UI busy 2.2-3.0 %, CPU 0.17-0.20 cores, hero `animateScrollToPage` 14-15/min (n = 5) | **22-23 frames/min**, UI busy 0.1-0.2 %, CPU 0.10-0.12 cores, hero 0 (n = 5) | frames -90 %+, CPU ~-35-40 %; hero back at 15.0-15.3/min once Home is shown again. In the real `AppTabHost` (harness): Home's live collectors 21 -> 7 (Library's own), hero changes 10 -> 0, snapshot writes 182 -> 0 per 60 s |
| 3 | No Home in non-Home iOS native-tab hosts (`keepHomeBehindOtherTabs = !useNativeNavigation`) | `AppTabHost` composed `HomeScreen` behind every tab, and each iOS 16+ native tab is its own host | Native Library host (harness): 19 collectors, 49 semantics nodes, Home + hero composed and paging | 6 collectors, 21 nodes, Home not composed | Home's whole subtree gone from 4 of 5 hosts. **Not measured on a device** (no iPhone or Mac); §7's protocol sizes it |
| 4 | Join-request clock only while a request is in flight (`outgoingJoinRequestNeedsTicks`) | 500 ms loop started with the shell and never stopped | `OutgoingJoinRequestStore.tick` **116-120/min** in every idle phase | **0** | One background wakeup every 500 ms removed; its CPU (~13 ms/min) is below the harness's resolution, so process CPU shows no difference |

**Overall (baseline -> final build, Home hidden behind Library):** 211 -> 23 frames/min, UI busy
2.2 -> 0.1 %, process CPU 0.17 -> 0.11-0.12 cores, hero and join-request timers 15 + 118/min -> 0.
**Startup:** ~0.9 s less UI-thread blocking before the profile picker. Profile -> Home was not a
target and is unchanged within noise (largest after-click task 1.35-2.28 s across every build).

**First attempt at fix 1, kept for the record.** Hopping straight to `Dispatchers.Default` (B1)
removed ~1.0 s from the window task but made the first-frame task ~170 ms slower: the repository
load competed with the first frame for CPU and class loading. Waiting one frame (`withFrameNanos`)
recovered about half of that (797 -> 883 ms instead of 970 ms) and the net blocking win grew from
-734 to -894 ms.

**Regression checks.** New tests: `ProfileSettingsSyncStartTest` (eight concurrent starts leave one
observer), `AppTabHostHomeActivityTest` (hero pages while Home is shown, stops when hidden while Home
stays composed, resumes on return; a native host composes no Home behind its tab, and a working Home
when Home is its tab), two `OutgoingJoinRequestTest` cases (only Idle stops the clock; every state it
excludes ignores every Tick and never polls). The harness runs showed Home loading and returning with
its content and Continue Watching intact. **Not exercised end to end:** settings pushes and join
requests need a signed-in session, and the harness runs signed out on purpose; iOS native routing is
covered by the host-level tests only.

**Suites (2026-10-01).** Desktop full split suite (JBR SDK, results deleted, `--rerun`): rest 1779,
playback 1062, downloads 457, all green; the download E2E class 48 / 49 when run alone, and its one
failure passes by itself. A first E2E run under other agents' load crawled for two hours and failed
two different tests that then passed: the class is load-sensitive, and this branch touches no
download code. Mobile `:composeApp:testAndroidHostTest --rerun`: 3397 tests, 0 failures, 6 skipped;
`:androidApp:compileFullDebugKotlin` succeeds. iOS was not compiled (Windows only; the CI compile
publishes a debug build).

**Upstream vs Z.** Fix 1: Z's call site in an upstream file (`AppGate.kt`, already on the patch
surface), lock in upstream `ProfileSettingsSync.kt` (already on it). Fixes 2-3: Z's always-mounted
Home block in `AppShellComponents.kt` / one argument in `MainTabsDestination.kt` (both already on
it); the gating is the new Z file `core/ui/TabPaneActivity.kt` and restores upstream's own
`RootTabPane` behaviour. Fix 4: Z-owned files only. None widens the patch surface by a file.

**Worth keeping:** all four. 1 and 2 are the measurable wins; 3 is the same mechanism on iOS and
can only be sized on a phone; 4 is small but free and removes the only always-on sub-second timer.

**Recommended Phase 2 order.** (1) Upstream `ScaledBitmapPainter` rescale off the UI thread (§2.3 A,
the largest remaining lever on Home scroll hitches; offer upstream). (2) Windows AppCDS archive in the
packaging job (§2.4; -38 % first UI task, -29 % shell freeze, no app-code risk). (3) Mount Home from
cache after profile selection once the auth work has settled (§2.2; -2 to -4 s per profile entry,
medium risk). Run §7's iPhone A/B whenever a phone is at hand: it now also measures fix 3.

## 9. Performance Phase 2 — poster scaling off the UI thread, JDK class-data sharing (2026-10-01)

Branch `claude/perf-phase-2`, from `claude/perf-phase-1` (so it carries Phase 1): code in
`nuviozdesktop`, this document and STATUS in `nuvio-z`. Not merged into any RC, auth or release
branch; no builds published; no version, feed or workflow changes. Both parts are desktop-only:
`AsyncImage.desktop.kt` and the packaging step have no counterpart in this repository, so the mobile
and iOS image pipelines are untouched (`NuvioAsyncImage`'s Android/iOS actuals and `commonMain`
declaration are unchanged).

### 9.1 Part A — Home poster scaling

**Path traced.** Every Home poster (`ShelfComponents` poster card), Continue Watching card
(`TitlePresentationCard`), poster grid, hover preview, and the Social / lobby artwork goes through
`NuvioAsyncImage`, whose desktop actual is upstream's `AsyncImage.desktop.kt` (no Z commits before
this phase). Hero and detail backdrops pass `NuvioDesktopImageScaling.Disabled` and are unaffected.

| Step | Where it ran |
| --- | --- |
| Fetch (Ktor fetcher, disk cache) | Coil's IO dispatcher |
| Decode at `size(1536)` (precision INEXACT, so never upscaled) | Coil's decoder dispatcher. Coil 3.6.3's JVM decoder downsamples with `SamplingMode.DEFAULT` (nearest neighbour) - which is why upstream added its own scaler |
| Memory cache | Coil's, keyed by URL + size; 15 % of the JVM's max heap by default (~1.2 GB on the audit machine) |
| Transform | `transform` wraps each `Success` in a **new** `ScaledBitmapPainter` - per state, so per composition of a card |
| Rescale (`Image.makeFromBitmap` + mipmapped `scalePixels` into a new bitmap) | **UI thread, inside `onDraw`**, the first time a painter draws at a size |
| Draw | UI thread (composition, layout and draw all run there on desktop) |

**Root cause.** The rescale ran synchronously inside draw, and its result lived only as long as the
painter, so every card that re-entered composition - scrolling back, a row recomposing while
content streams in, returning to Home - paid for it again. JFR method tracing (JDK 25, one full
protocol run): **169 rescales on the UI thread, ~11 ms each**, 14 of them while Home sat idle.

**Change** (`896af1e3d`):

- New `core/ui/DesktopImageDownscaler.kt` (desktopMain, Z-new): the same mipmapped Skia scale on two
  `Dispatchers.Default` workers, newest request first. Identical requests share one job. A queued job
  whose painters have not drawn for 250 ms is skipped (the card left the screen); if it was in fact
  still visible, the skip invalidates it and its next draw asks again.
- Results are stored in **Coil's existing memory cache** under the source image's key plus
  `nuvio#desktopScaledSize`, so a returning card or the same poster in another row draws at once.
  No second cache: the scaled copies live inside Coil's budget and are evicted by it.
- `ScaledBitmapPainter` (upstream file, now on the patch surface) only looks the result up in
  `onDraw`. Until it lands the card draws nothing for a frame or two, or an earlier size stretched
  (window resize). A failed scale falls back to upstream's direct draw instead of retrying each frame.
  The scaled bitmap is now immutable.
- Unchanged on purpose: the 1536 px decode, the scaler, the size quantisation, the >1.25 MP and
  <1.08x cut-offs, and the Windows-only gate.

**Method.** Phase 1's final build (`1d259263d`, "before") against `896af1e3d` ("after"), both JBR 25,
no JFR, signed-out data copy, **4 rounds interleaved** (other agents' Gradle builds loaded the
machine at 60-99 % CPU throughout). New protocol `scripts/perf/phase2.py scroll`: cold Home load ->
Continue Watching right/left (Shift+wheel) -> 3 vertical wheel passes down/up -> Library and back x3
-> one more pass. Frames pooled per group across the 4 runs (`phase2_summary.py`).

| Group (4 runs pooled) | frames | p50 | p95 | p99 | worst | >=16.7 | >=33 | >=50 | >=100 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Cold Home load, before | 680 | 4.1 | 57.3 | 225 | 3041 | 94 | 67 | 39 | 18 |
| Cold Home load, after | 803 | 3.9 | 35.1 | 124 | 2229 | 94 | **45** | **27** | **10** |
| Continue Watching, before | 407 | 2.8 | 34.9 | 170 | 260 | 46 | 25 | 12 | 6 |
| Continue Watching, after | 365 | 4.0 | 24.2 | 49 | 75 | 27 | **6** | **3** | **0** |
| First vertical pass, before | 470 | 3.7 | 71.0 | 251 | 922 | 74 | 50 | 34 | 17 |
| First vertical pass, after | 511 | 3.5 | 49.8 | 155 | 302 | 46 | **31** | **25** | **9** |
| Warm passes 2-3, before | 1661 | 3.1 | 27.0 | 71 | 319 | 108 | 59 | 26 | 7 |
| Warm passes 2-3, after | 1730 | 3.3 | 12.0 | 46 | 66 | 56 | **40** | **13** | **0** |
| Library <-> Home x3, before | 3129 | 2.0 | 11.8 | 37 | 809 | 85 | 36 | 24 | 9 |
| Library <-> Home x3, after | 3258 | 1.9 | 8.9 | 16 | 275 | 29 | **12** | **6** | **4** |
| Pass after returning, before | 676 | 3.2 | 28.0 | 106 | 180 | 52 | 27 | 15 | 7 |
| Pass after returning, after | 908 | 2.9 | 8.2 | 34 | 50 | 26 | **10** | **1** | **0** |

Median frames are unchanged (3-4 ms): this was always a long-tail problem, and the tail is what
moved. Frames >= 33 ms per run, before / after: cold load 17,16,17,17 / 10,11,12,12; warm
8,16,25,10 / 10,9,12,9 (the warm group is the noisiest; its >= 50 and >= 100 counts and p95 are the
clearer signal). What remains is mostly card composition (section 2.3 B), which this phase did not
touch.

**Attribution** (JFR method tracing, one run each): time inside the painter's `onDraw` on the UI
thread over the whole protocol **1,179 ms -> 34 ms**; rescales **169 on the UI thread -> 18 on the
workers** (~8.5 ms each). After the first load every warm pass, tab switch and the return pass were
memory-cache hits with zero scales. No queued job was skipped in that run (the queue never backed up).

**Memory** (whole-process private bytes, median of 4 runs): before 1,171 MB at Home loaded -> 1,245-
1,263 MB after scrolling and tab switches; after 1,144 MB -> 1,195-1,218 MB. Flat across the three
Library round trips and the final pass in both - no growth. It goes *down* because the old painter
kept a scaled copy per live painter (and re-created it per composition) while the new path keeps one
per poster and size in Coil's cache. Coil's cache budget is unchanged and still bounds everything.

**Visual / caching behaviour.** Same scaler, same sizes: the seven posters of a scrolled row are
**pixel-identical** before and after (the only differing pixels in the screenshot are inside the
hover-preview card, which showed different content). A brand-new card can show its background for a
frame or two before the poster appears - indistinguishable from a network load, and absent for
returning cards (cache hits). Cancellation: a card that leaves the screen stops drawing, so its
queued job is skipped. Wrong images cannot appear: results are keyed by the source's Coil memory key
(or, uncached, the source bitmap's identity) plus the size.

**Tests.** `DesktopImageDownscalerTest` (10): one scale for identical requests, stored in Coil's cache
under its own key; newest first; skipped when nobody draws; the scale averages (a 1-px checkerboard
comes out mid-grey, where nearest-neighbour would be black or white); first draw queues instead of
scaling; a returning card draws from the cache at once; near-size sources drawn directly; a skipped
job is asked for again; a failure falls back without retrying every frame; uncached sources still
scale in the background.

**Upstream vs Z.** `AsyncImage.desktop.kt` is upstream's (NuvioDesktop `Dev`) and joins the patch
surface. `DesktopImageDownscaler.kt` depends only on Coil, Skia and coroutines - no Z code - so the
two files together are a self-contained patch. **Recommend offering it upstream**: upstream has the
same cost (section 2.3 measured vanilla's rescale at 0.62 s of long-frame time against Z's 0.33 s).

### 9.2 Part B — JDK class-data sharing for the Windows runtime

**What ships** (`1aeb9749a`, `b1c24b6fd`): right after `createRuntimeImage` (jlink), on Windows hosts,
a default CDS archive is dumped into the runtime at `bin/server/classes.jsa` from a checked-in list
of the JDK classes a real session loads (`composeApp/src/desktopMain/cds/windows-jdk-classlist.txt`,
5,153 entries recorded on the shipped Temurin 17 runtime over startup -> Home -> scrolling -> tabs).
jpackage copies that runtime into both the app image and the MSI. The JVM maps it by itself under its
default `-Xshare:auto` - no launcher option is needed to find it - and `-XX:+VerifySharedSpaces`
(Windows packages only) checksums it first. jpackage strips the runtime's `java.exe`, so the step
borrows the linking JDK's (it only loads the runtime's own `jli.dll`/`jvm.dll`) and deletes it
again; it skips with a warning, never failing the build, if the runtime and JDK versions differ or
the dump fails. `-Pnuvio.desktop.cds=false` turns it off.

**Why only JDK classes - the investigation's main finding.** The audit's -38 % / -29 % (section 2.4)
came from an archive holding the *app's* classes too. On JDK 17 (what `desktop-release.yml`
ships) such an archive cannot be prebuilt for an MSI install:

1. It records the app jars' absolute paths. Moved to another directory it is rejected outright
   (`APP classpath mismatch`; JDK 17 has no relocation support). An archive built in CI would only
   ever match the CI path.
2. It records each jar's modification time, and **an MSI install rewrites those**: the cabinet
   stores local time at 2-second resolution and the installer applies the installing machine's UTC
   offset. Shown both ways: the installed Debug 1.45.79 (CI run 11:26-11:43 UTC) has files stamped
   11:34 *local* (+03:00); a local MSI moved `classes.jsa` from 17:54:40.02 to 17:54:42.
3. Generating it on the user's machine does not help either: Program Files is not writable, and the
   JDK 17 jpackage launcher expands only `$APPDIR`/`$BINDIR`/`$ROOTDIR` in `java-options` - tested:
   `%LOCALAPPDATA%`, `$LOCALAPPDATA` and `${LOCALAPPDATA}` all pass through literally.

The JDK's own classes have none of these problems: the archive checks `lib/modules` by size only, so
it validates wherever the app is installed and whatever the timestamps.

**Benchmark** (Phase 1 jars on the shipped Temurin 17 runtime image, `phase2.py startup` = launch ->
picker -> click -> Home, no JFR, 5 rounds interleaved, quieter machine):

| median of 5 (ms) | no CDS | **JDK archive (shipped)** | JDK + app archive (ceiling, not shippable) |
| --- | --- | --- | --- |
| launch -> JVM main | 436 | **369** (-15 %) | 438 |
| first UI stall (window + picker) | 1,765 | **1,568** (-11 %) | 939 (-47 %) |
| UI blocked >= 100 ms before the picker | 4,211 | **3,890** (-8 %) | 1,812 (-57 %) |
| launch -> picker usable | 6,467 | **6,048** (-6 %) | 2,807 (-57 %) |
| first Home freeze after the click | 1,265 | **1,209** (-4 %) | 872 (-31 %) |
| spread of the first UI stall | 1,725-2,009 | 1,544-1,604 | 926-961 |

A repeat on the actual release image from a Temurin 17 `packageReleaseMsi` (8 rounds, alternating
order) ran under 60-99 % load from other agents and is too noisy to resolve a 10 % effect (the
first stall ranged 1.9-7.3 s within one variant). Its medians point the same way: UI-thread CPU of
the first stall 2,038 -> 1,874 ms, of the Home freeze 1,640 -> 1,453 ms, JVM boot 497 -> 468 ms.
"First launch after install" with a cold disk cache could not be measured (flushing the OS file
cache needs admin); every run here was a first launch for the app's *data* (fresh copy each run).

**Size.** Archive 27 MB on Temurin 17 (33 MB on JBR 25). MSI **256.5 -> 264.0 MB (+7.5 MB,
+2.9 %)**; installed 413 -> 441 MB.

**Fail-safe, verified through the real launcher** on the MSI's administratively extracted image
(`msiexec /a`, which registers nothing), signed-out data copy, in-memory prefs:

| `classes.jsa` | Result |
| --- | --- |
| present | picker renders; 3,928 classes from the archive |
| missing | normal start, no sharing ("Specified shared archive not found") |
| corrupted (zeroed pages) | normal start; "Checksum verification failed" - **without** `VerifySharedSpaces` the corrupted archive *was mapped*, which is why the flag is there |
| truncated / garbage | normal start ("Unable to read the file header") |
| another JVM's (JBR 25 archive in the 17 runtime) | normal start ("wrong version") |

Install / update: the archive is an ordinary file of the MSI (unversioned, like the jars), replaced
by an upgrade and removed on uninstall; one left behind by anything else is rejected by the same
version and size checks. The debug channel gets its own from its own build; nothing is
channel-specific. `VerifySharedSpaces` is accepted by JDK 17, 21 and 25.

**Regression risk: low.** No app code; the JVM decides per launch and falls back silently. Costs:
+7.5 MB download, +28 MB on disk, ~10 s more packaging per build. Release, update feeds and workflows
are untouched; the step runs inside the existing Gradle tasks.

### 9.3 Phase 2 verdict and next step

- **Part A: keep.** The largest scroll lever that did not need the cards reworked, measured on every
  group, memory down, pixels unchanged. Worth offering upstream.
- **Part B: keep, but expect little.** It is safe and real but small (~-0.2 s first stall). The big
  CDS win (-47 % first stall, -31 % Home freeze) is out of reach for MSI installs on JDK 17 for the
  reasons in 9.2. Getting it needs an MSI custom action that dumps the app archive into the install
  directory at install time (WiX work inside Compose's packaging - its own decision), or a runtime
  upgrade *plus* a way to generate the archive on the user's machine (out of scope here; newer JDKs'
  archives are also bound to the class path, so an upgrade alone is not assumed to fix it).
- **Next, once auth and the RC have settled:** mount Home from cache after profile selection
  (section 2.2; -2 to -4 s per profile entry). After that, card composition cost (section 2.3 B) is
  what remains of the scroll tail.
