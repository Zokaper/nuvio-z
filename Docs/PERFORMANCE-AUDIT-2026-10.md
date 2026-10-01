# Performance audit — desktop stutter and iPhone heat (2026-10-01)

> **Phase 1 implemented (2026-10-01):** Tier A items 1, 2 (desktop and iOS) and 4 of section 6 -
> measured before and after in section 8, on branch `claude/perf-phase-1`.

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
