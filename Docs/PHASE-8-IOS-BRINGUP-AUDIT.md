# Phase 8 — iOS Device Validation & Bringup Audit

**Branch**: `claude/phase-8-ios-queue-ownership` (from `gemini/phase-8-ios-background-orchestration`, `ac84da767`)
**Base**: `gemini/phase-8-ios-downloads-nav-hardening` (`4d338bc09`) (aligned with Phase 7 merge on `main`)
**Target**: Do NOT merge to `main`. Physical iPhone QA pass (SideStore Debug bringup).

---

## Batch 5 — physical `.45` findings and the `.46` submitted window

Physical `.45` on iPhone:
- queue order is fixed: #1/#2 run first and #3/#4 wait;
- **while the phone is locked, #3/#4 do not start when #1/#2 finish.** On unlock they show
  `Starting` and then begin;
- the Live Activity is stable but only moves while unlocked;
- the Delete freeze no longer reproduces.

### Why #3/#4 waited for the unlock: an iOS rule, not a race

`.45` advanced the queue from `didCompleteWithError` → `advanceIfBackgrounded()`, **creating a new
task while the app was in the background**. Apple's "Downloading files in the background" rules
that out as a way to keep a queue moving:

> "When your app starts a new download task while in the background, the task doesn't begin until
> the delay expires. The delay increases each time the system resumes or relaunches your app… if
> your app starts a single background download, gets resumed when the download completes, and then
> starts a new download, it will greatly increase the delay. Instead, use … ideally just one
> [session] … to start many download tasks at once."

The same article says a transfer initiated in the background is treated as `isDiscretionary`, and
that the delay resets only when the app returns to the foreground. `Starting` means Downloading with
0 bytes: a task that was created and claimed in the background, then held by the system until the
unlock. That is an inference from the label; the `.46` metrics log confirms or refutes it. The
code-side contributors were:
- the chaining pattern itself;
- a stale-boundary refresh that cannot finish in the background, because nothing re-runs the
  scheduler after it and the completion handler is already called.

### The `.46` model

The maintainer dropped the max-2 requirement on iOS (2026-09-24). The product requirement is that a
queue built while Nuvio is open keeps progressing after the lock. So:

- **One global queue, one submitted window.** While Nuvio is in the foreground, the repository
  resolves and submits up to `IOS_SUBMISSION_WINDOW` (12) queued items as real, *resumed* background
  tasks. It does this in queue order, whatever film, show or season each item belongs to. The system
  owns them from then on and decides how many move bytes at once. The window is a bound on minted
  links and task overhead, not a concurrency limit.
  - `DownloadsPlatformDownloader.maxConcurrentTransfers` is 12 on iOS and 2 on Android.
  - The repository's slot arithmetic (`startable`, `claimNativeTransfer`, `planAdoption`,
    `scheduleNextTransfers`) is unchanged; it just takes the platform value.
- **Created in queue order.** Sources in the window resolve in parallel. A resolved item is parked
  until everything ahead of it has been submitted or has stopped resolving
  (`IosBackgroundTransferReconciler.releaseInQueueOrder`). Task priority is re-ranked by queue
  position on every change, as a **hint only**. Network start and completion order belong to the
  system.
- **No silence watchdog on iOS** (`ownsTransferLiveness`). A submitted task may legitimately wait
  inside `nsurlsessiond`. The queue's 5-minute watchdog would have read that wait as a lost transfer
  and charged it an attempt each time. The session's request and resource timeouts decide failure
  instead.
- **Force-quit is not an adoption case.** The system cancels every task, and on relaunch the
  inventory holds none, so `planAdoption` requeues the whole window in place with no attempt charged.
  The cancellations themselves arrive later as `NSURLErrorCancelled`, with
  `NSURLErrorBackgroundTaskCancelledReasonKey` set and nobody listening. `.45` claimed those and
  failed them, charging an attempt. `.46` ignores them, and hands a live system cancel back as a
  system pause.
- **Normal backgrounding and suspension are unchanged:** the tasks stay with the system and are
  adopted on return, with capacity now the window.
- **Beyond the window**, `.45`'s background chaining remains as **best effort**: iOS delays it, but
  it costs nothing, and it keeps the FIFO stale boundary.
- **Source freshness.** The 15-minute `SOURCE_URL_FRESHNESS_MS` is the resolver's cache lifetime,
  not a measured link expiry. In this model it only gates *starting* a task, and the whole window
  starts while fresh. It is unchanged; the metrics log records URL age and HTTP status per task so it
  can be revisited on evidence.

### Live Activity while locked

A suspended app gets no `didWriteData`, so it has no progress to show. Its only execution time is the
rate-limited completion wakes. A push-updated activity would need a server that knows device
progress, and none does. **Continuous locked-screen progress is not honestly available.** While the
app is backgrounded, the payload carries `backgroundStatusText` ("Downloading in background",
localized). The widget shows that plus the queue summary in place of the percentage, bytes and bar,
and the counts still change on completion wakes. The real progress returns in the foreground.

### Debug metrics (`.46` only proves itself with these)

The Debug build appends JSON lines to `Files > Nuvio Z Debug > nuvio_diagnostics/downloads-*.jsonl`:
- events: `create` (app state, queue position, priority, URL age), `resume`, `suspend`, `cancel`,
  `first_progress`, `finish` (HTTP status), `complete` (error code, system-cancel reason), `wake`,
  `did_finish_events`, `inventory`, `boundary`, and app-state/lock transitions;
- **`metrics`**, from `didFinishCollectingMetrics`: `requestStart`/`responseStart` are recorded by
  the system even while the app is suspended. They are the proof of when bytes began while locked.

No URLs or headers are logged.

## Batch 4 — physical `.44` findings and the `.45` queue-ownership fix

Branch `claude/phase-8-ios-queue-ownership`, from `gemini/phase-8-ios-background-orchestration`
(`ac84da767`). Physical `.44` on iPhone:

- season sources resolved, but the season sat at `Queued #1`;
- after reopening, #3 and #4 started instead of #1 and #2, then fell back to Queued;
- screen-off continuation was unreliable;
- the app sometimes stopped taking any input (native tab bar included) until it was left and
  re-entered. Delete on a Downloads episode reproduces it; it was also seen once scrolling Home.

### Root causes (established from the `.43 → .44` diff)

1. **Two schedulers with two meanings of "running".** `.44` pushed a persisted copy of the queue
   to native code on *every* repository publish and mapped repository `Downloading` to native
   `RUNNING`. The repository marks #1/#2 `Downloading` *before* it resolves their sources, so
   no task exists yet. The native scheduler counted slots from real tasks (zero) and immediately
   started the first two `PREPARED` items: #3 and #4.
2. **Persisted `RUNNING` was sticky.** The merge preserved `RUNNING`, so after a force-quit
   (which cancels background tasks) or a process death, those entries were never scheduled again,
   and every relaunch skipped past them.
3. **The journal fought the repository.**
   - `NeedsSourceRefresh`, applied at the top of every `startPendingTransfers`, demoted the item the
     repository was *resolving* back to Queued. Its resolver then gave up, leaving #1 at Queued #1.
   - `TaskStarted` marked natively started items `Downloading` with no handle, so the reclaim sweep
     re-queued them (charging an attempt) while their tasks kept running: the "back to Queued".
   - Listener-delivered failures were journaled too, costing two attempts each.
4. **Unbounded hot-path work.**
   - Each native advance re-marked the stale head and appended a journal event, because the next
     sync reset it to `PREPARED`. The journal grew without bound and was re-decoded in full on
     each append.
   - Every `didWriteData` ran `getAllTasks` plus a Live Activity write.
   - Every repository progress publish rewrote the prepared queue.
   - Separately, and older than `.44`, `shouldReportProgress` reports on 512 KB *or* 500 ms. At
     debrid speeds that is dozens of publishes a second per transfer, each a recomposition plus a
     Live Activity notification to the main queue, and each of those an unawaited ActivityKit
     `Task`.

`.44`'s premise that Kotlin "cannot run" when a transfer ends in the background is not right for
this purpose. Delegate callbacks *are* Kotlin, running in-process, and the repository persists
synchronously. What cannot run while suspended is a network round trip, i.e. re-minting a
source URL. That is the only thing the background scheduler has to work around.

### The ownership model in `.45`

| Layer | Owns | Never |
| --- | --- | --- |
| Repository | queue order, user intent, every start while the app is active | treats persisted `Downloading` as proof of a transfer |
| Repository's persisted items | the prepared queue: URL, headers and resolvedAt for each queued item | — |
| `URLSession` tasks | the only proof of "running"; one task per download, seeded from `getAllTasks` before any task is created | — |
| Completed files | reported to the repository, which still applies the implausibly-small / expected-size check | become authoritative by existing on disk |

- **No lock in the native layer.**
  - All native state is confined to the session's serial delegate queue. Everything else only
    enqueues onto it.
  - Native code calls the repository, but the repository never waits on the queue, so the
    nested-lock hazard is gone.
- **While the app is active,** the repository schedules and native code only executes.
- **While the app is in the background,** the repository defers (`schedulingDeferredToPlatform`).
  When a slot frees, the session pulls `nativeSchedulingSnapshot()` (queued items in order, plus
  the repository's in-memory claims) and starts the next item:
  - Slots = 2 − |running tasks ∪ claims|. An item the repository is still resolving keeps its slot.
  - Strict FIFO. The first stale or blank URL is a boundary, reported once per background stay.
  - A suspended task is resumed, never duplicated.
- **Uncontrolled tasks are claimed.** Any task with no listener (started in the background, or
  found after a relaunch) is handed to `claimNativeTransfer`. The repository adopts it with a
  normal generation-fenced listener, asks for it to be suspended (over capacity, or user-paused),
  or has it cancelled (download gone, finished or failed).
- **Adoption on launch and on return to the foreground.** The repository holds scheduling, reads
  the real task inventory, and applies `planAdoption`:
  - running tasks are adopted in queue order, up to 2;
  - the rest are suspended, not cancelled;
  - `Downloading` items with no running task are re-queued at the same position with no attempt
    charged (stale-`RUNNING` recovery);
  - then the queue fills remaining slots FIFO.
  - A 3 s timeout covers a lost answer. Duplicates stay impossible either way.
- **Retired:** the `.44` prepared-queue and journal `NSUserDefaults` keys are deleted on first
  launch of `.45`.
- **Also fixed:**
  - native progress reaches the queue at most once a second;
  - progress-only Live Activity writes are limited to one a second;
  - the Swift Live Activity manager applies one update at a time, latest wins;
  - the background-session completion handler is now invoked on the main thread;
  - iOS no longer claims it resumes system pauses (nothing has done so since `.43`), so the queue
    reclaims them;
  - the Live Activity queue summary and "Finding sources" subtitle are localized.

### The freeze: hypotheses, not a cause

Input dies everywhere, including the UIKit tab bar, and leaving the app clears it. That fits two
faults:
- **(a) A main-thread stall.** The progress floods above are real contributors and are fixed in
  `.45`.
- **(b) A window-level view taking touches.** The candidate is the full-screen
  `AppGateComposeView` above the tab view, whose `.allowsHitTesting(!isAppReady)` failed the same
  way once before (Issue 2).

Neither is established. The debug build now ships `FreezeDiagnostics.swift` (`#if DEBUG` only):
- a main-thread watchdog;
- the view each touch lands on;
- a window/overlay dump on every trip to the background;
- gate readiness notes;
- MetricKit hang call stacks.

These are written to `Documents/nuvio_diagnostics/`, which Debug exposes in Files. If `.45` still
freezes, those logs decide (a) versus (b).

---

## Batch 3 — physical `.43` findings and `.44` background orchestration & Live Activity stability

Physical testing of debug prerelease `0.4.13-z1.43` on physical iPhone confirmed:
- **Native transfer continues while screen is off (PASS)**: Native `NSURLSessionDownloadTask` maintains
  byte transfer in the background without suspension.
- **Stalled completion bookkeeping when screen is off (FAIL)**: When a download finishes while locked,
  the file is finalized to disk by the native delegate, but Kotlin/Compose state does not update
  until the app is foregrounded. Root cause: the Kotlin runtime is paused while suspended; callbacks
  into Kotlin cannot reliably execute or persist state before iOS freezes the process.
- **Stalled queue advancement when screen is off (FAIL)**: Subsequent items in a season or bulk batch
  do not start downloading while the phone remains locked. Root cause: Kotlin coroutines cannot execute
  in the background to resolve debrid download URLs.
- **Live Activity churn & collision during concurrent downloads (FAIL)**: Under concurrent downloads,
  the Live Activity flickered, progress ping-ponged between items, or the activity was terminated by
  ActivityKit. Root cause: activity identity was tied to individual `downloadId`s (causing recreation loops),
  and primary selection sorted by raw update timestamps.

### Architectural fixes in `.44`:

1. **Durable Completion Journal (`nuvio.ios_downloads.journal.v1`)**:
   - Native delegate callbacks write atomic completion/failure/progress records directly into `NSUserDefaults`.
   - On app wake or foreground resume, `DownloadsRepository.reconcileIosBackgroundJournal()` polls the journal,
     reconciles state idempotently via `IosBackgroundTransferReconciler.reconcileJournal()`, and acknowledges processed events.
2. **Ahead-of-Time Source Preparation**:
   - While awake and connected, the app resolves direct URLs for up to 5 upcoming items in approved batches.
   - Each prepared item has an expiration timestamp (15-minute TTL aligned with Real-Debrid / debrid provider link validity).
   - Descriptors are persisted in native storage (`nuvio.ios_downloads.prepared_queue.v1`).
3. **Synchronous Native Queue Advancement**:
   - When a native download completes in the background, `advanceNativeQueueLocked()` synchronously runs inside
     `URLSession(didFinishDownloadingToURL)`. If an active slot is available (max concurrency: 2) and the next prepared
     item is fresh, the native download task is launched immediately before the system completion handler returns.
4. **Strict FIFO Queue Ordering at Stale Boundaries**:
   - If the next queued item's prepared link has expired (>15m), the native scheduler halts queue advancement at that
     boundary, emits a `NeedsSourceRefresh` journal event, and waits for foreground resumption. It does not skip ahead,
     preventing out-of-order episode downloads and cascading link expirations.
5. **Stable Live Activity Session Identity & Sticky Selection**:
   - Live Activity uses a stable session key (`nuvio.downloads.session`).
   - Dynamic attributes live in `ContentState`, enabling in-place updates via `apply(payload)` without recreation.
   - Primary item selection is **sticky** on the lowest `queuePosition` (lowest queue rank) until that item completes or is cancelled.
   - Presentation includes active and remaining counts (`2 downloading • 3 remaining`).
   - Direct native payload updates are dispatched from the native delegate callbacks during background downloads.

---

## Batch 2 — physical `.42` findings and `.43` fixes

The `.42` device pass invalidated this document's earlier claim that the iOS downloader was a
background implementation. It used a default `URLSession` data task, streamed bytes into `.part`
itself, and explicitly paused the repository in `applicationDidEnterBackground`. Locking the phone
therefore stopped the transfer and foreground recovery surfaced a fake retry. `.43` replaces that
path with one stable, bundle-specific background session (`<bundle-id>.downloads.background.v1`),
system-owned download tasks, durable task descriptions containing the Nuvio download id and
destination, existing-task enumeration before creation, and the existing AppDelegate background
event completion-handler bridge. User pause suspends the native task; screen lock does not.

Legacy `.42` `.part` files are deliberately restarted once. They were written by an unrelated data
task and have no system resume record, so combining them with a background task's temporary file
cannot prove byte continuity. The pure reconciler still pins Range behavior: only an aligned 206
may append, a 200 replaces, an exact 416 means complete, and a misaligned 206/overlong 416 restarts.

Physically confirmed on `.42`:

- an individual episode completed and played offline;
- screen lock stopped an active transfer and foregrounding showed `Retrying 1/5`;
- six native phone tabs pushed Social/Profile into `More`;
- whole-title context-menu Download found sources but created no visible batch;
- the season download control was unreadable;
- Live Activity began with `0 KB` / `--%` and could remain at stale progress after completion.

Code-fixed for `.43`, awaiting physical validation:

- whole-title selection now carries real seasons and legacy empty `SelectedSeasons` means all
  released non-special seasons;
- Library and Downloads are one top-level destination with an in-page switch; old Downloads
  intents, deep links, toasts, saved tab values and native requests canonicalize to
  `Library → Downloads`, leaving five iPhone tabs;
- Live Activity distinguishes finding sources, preparing, waiting, starting, unknown-size
  downloading, known progress, retrying, paused and failed; percent is absent without a total,
  and completion clears all orphan activities;
- the season control uses theme `onSurface`/`error` colors;
- iOS no longer exposes navigation-style or Liquid Glass settings that cannot affect its
  always-native iOS 26 phone bar.

Android shares the whole-title, canonical Downloads routing, Library information architecture,
semantic-color and completed-playback seams. Its notification already uses indeterminate progress
for unknown totals; `.43` also replaces the initial `0 B` subtitle with `Starting…`. These are
code-verified only and require an Android device spot-check.

Desktop completed-file playback is not implemented in this mobile repository: it has no desktop
`DownloadsPlatformDownloader` actual. The reported desktop failure belongs to `NuvioZDesktop` and
remains a dedicated physical/implementation retest there; no speculative mobile change was made.

### Navigation-setting support matrix after Batch 2

| Surface | Navigation owner | User-visible style choice |
|---|---|---|
| Android phone/tablet | Compose | Adaptive / Expanded / Compact / Classic remain visible and effective. |
| iOS below 26 | Native SwiftUI tab container | Compose style choices hidden; no separate Liquid Glass switch. |
| iOS 26+ phone | Native SwiftUI tabs / system material | Compose style choices hidden; system styling is automatic. |
| iPad | Native SwiftUI tab container | Compose style choices hidden; form-factor behavior stays system-owned. |
| Desktop concept | Desktop navigation shell | Desktop-supported navigation controls remain; Downloads is reached inside Library. |

The removed Liquid Glass row had no independent behavior to control, and Settings search no longer
indexes it. This leaves no iOS-visible choice whose value is ignored by the active native container.

---

## 1. Executive Summary & Findings Matrix

| ID | Observed Bug | Root Cause | Bug Class | Similar Findings Across Mobile | Fix Status | Physical Verification |
|:--:|:---|:---|:---|:---|:---:|:---:|
| **1** | Watch Together join indicator obscured by navigation | `WatchTogetherDock` anchored to `Alignment.BottomEnd` with `bottom = 24.dp`, colliding directly with mobile bottom navigation bar (64–84dp). | **Transient overlay / chrome occlusion** | Toast host placement, loading rail padding, floating player controls. | **FIXED** | Open (Test on real iPhone + Android) |
| **2** | Setup/onboarding wizard cannot be interacted with reliably on iOS | Premature `onAppReady(true)` emitted while setup wizard was active, triggering SwiftUI `.allowsHitTesting(!appCoordinator.isAppReady)` (`false`) on `AppGateComposeView`. | **Container hit testing shutoff / gate transition race** | `WhatsNewScreen` dismissal, `ProfileSelectionScreen` loading overlay pass-through. | **FIXED** | Open (Test on real iPhone) |
| **3** | Email login unreliable on iOS | `sessionStatus.collect` dropped authenticated events if anonymous ID was present (`AuthStorage.loadAnonymousUserId() != null`); `signInWithEmail` lacked immediate state adoption; `validateRemoteSession` network blips purged active sessions. | **Unsynchronized state adoption / mutation-flow race** | Anonymous to authenticated transition across all providers; sign-up flow; background session renewal. | **FIXED** | Open (Test on real iPhone + Android) |
| **4** | Platform-specific settings & onboarding ownership | Setup wizard completed revision (`setupWizardCompletedRevision`) is synced globally via Supabase profile settings. Onboarding on desktop marks revision 7+, completely bypassing platform setup on mobile. | **Cross-device sync vs device-local configuration collision** | Theme settings sync vs platform capabilities (blur/renderers); external player choices. | **DEFERRED (Design Approved)** | N/A (Architecture specification provided) |
| **5** | Settings menu differs on iOS & missing "Run setup again" | When `bypassAppGate = true` (native tab host on iOS), `MainAppContent` was invoked without `onRunSetupAgainClick` or `onWhatsNewClick`, rendering rows null. | **Platform container callback decoupling** | What's New row missing on iOS; profile switch callback decoupling. | **FIXED** | Open (Test on real iPhone) |
| **6** | Social and Downloads bottom nav collision | `NuvioAppTab` in `ContentView.swift` replaced `.downloads` with `.social`, mapped `.social: downloadsCoordinator`, and passed downloads title into Social tab. Downloads tab was completely removed. | **Enum route identity conflation / positional bridge mismatch** | Route keys in Swift-Kotlin bridge; tab title indexing in `updateTabTitles`. | **FIXED** | Open (Test on real iPhone) |
| **7** | Downloads appear broadly broken on iOS | `DownloadsDestination` in `SettingsDestinations.kt` called `DownloadsScreen` (active queue list) instead of `DownloadsSettingsScreen` (presets/discovery config); tab navigation banner failed due to Bug 6. | **Route destination target mismatch** | Settings route targets across mobile; download banner navigation handlers. | **FIXED** | Open (Test on real iPhone + Android) |
| **8** | Streamlined mobile sheet opens Preferences behind itself | On mobile, `NuvioModalBottomSheet` uses a UIKit presented `UIViewController` on iOS. `PlaybackPreferencesDialog` was rendered in parent Compose view hierarchy behind the presented sheet. | **Modal hierarchy & view controller layering violation** | Dialogs launched from bottom sheets; P2P profile selectors; metered network alerts. | **FIXED** | Open (Test on real iPhone + Android) |
| **9** | Playback startup loading screen collides with camera cutout | `PlaybackLoadingScreen` used top-only safe insets, omitting `safeDrawing.only(Start)` in landscape and lacking horizontal/bottom safe-area insets on metadata and title rails. | **Display cutout / window insets omission across orientations** | Landscape player controls; back button padding in full-screen routes; `OpeningOverlay`. | **FIXED** | Open (Test on real iPhone + Android) |

---

## 2. Deep Dive Root Cause & Bug-Class Analysis

### Bug 1: Watch Together Join Indicator Obscured by Navigation
* **Platform Scope**: **Shared mobile bug** (affects both iOS and Android).
* **Direct Root Cause**: In `MainAppContent.kt` (lines 2404–2412), `WatchTogetherDock` was wrapped in a `BoxWithConstraints` aligned to `Alignment.BottomEnd` with `.padding(end = 20.dp, bottom = 24.dp)`. On desktop, where no bottom navigation bar exists, bottom-right placement is correct. On mobile, the Compose `NuvioNavigationBar` (height 64–80dp) or the iOS native `UITabBar` (height 49dp + 34dp home inset = 83dp) sits directly over the bottom 84dp, completely concealing the dock indicator.
* **Bug Class**: **Transient Overlay / Chrome Occlusion**. Floating pills, indicators, or toasts anchored to edge coordinates without querying navigation chrome height or platform form factor.
* **Codebase Audit for Siblings**:
  * `NuvioToastHost`: Positioned at `Alignment.TopCenter` with `.zIndex(20f)`. Verified safe on mobile as it centers horizontally and does not collide with the corner dock.
  * `PlaybackLoadingHost`: Uses full screen overlay with `.zIndex(18f)`. Correctly layered below dock and toasts.
* **Fix Implemented**:
  * In `MainAppContent.kt`, conditionalized alignment: Desktop keeps `Alignment.BottomEnd` (`bottom = 24.dp, end = 20.dp`). Mobile uses `Alignment.TopEnd` with `top = WindowInsets.safeDrawing.only(Top).asPaddingValues().calculateTopPadding() + 12.dp, end = 16.dp`. This keeps the indicator visible below status bar / Dynamic Island, clear of centered toasts, and away from bottom navigation.

---

### Bug 2: Setup/Onboarding Wizard Touch Interaction Broken on iOS
* **Platform Scope**: **Shared Compose state machine bug with iOS-specific manifestation** (on Android, `renderMainContent = true` inline so touches are unaffected; on iOS, SwiftUI overlay `.allowsHitTesting` shuts off input).
* **Direct Root Cause**:
  1. `iosApp/iosApp/ContentView.swift` (lines 1369–1374) renders `AppGateComposeView` (hosting `AppGateOverlay`) at `zIndex(1)` with `.allowsHitTesting(!appCoordinator.isAppReady)`.
  2. In `AppGate.kt`, when profile selection completes on first run, `gateScreen` transitions to `AppGateScreen.Main.name`.
  3. `AppGate.kt` immediately invoked `onMainContentMountChanged(true)`, mounting the native background tabs at `zIndex(0)`.
  4. As soon as the background Home tab finished its catalog query, it reported `externalMainContentReady = true`.
  5. `AppGate.kt`'s `LaunchedEffect` then fired `onAppReady(true)` **while `shouldShowSetupWizard(...)` was true and `SetupWizardScreen` was actively displayed**!
  6. SwiftUI received `isAppReady = true` and applied `.allowsHitTesting(false)` and `.accessibilityHidden(true)` to `AppGateComposeView`, intercepting or passing through all touch events so the wizard buttons were completely non-interactive.
* **Bug Class**: **Container Hit Testing Shutoff / Premature Gate Transition**. Decoupled container state machines where an overlay's interactivity is tied to readiness of a background component it is supposed to gate.
* **Codebase Audit for Siblings**:
  * `WhatsNewScreen`: Suffered from the same premature hit testing shutoff if triggered during startup or on demand.
  * Re-running setup from Settings (`showSetupWizardOnDemand`): If `isAppReady` remained `true`, on-demand wizard touches would also be ignored.
* **Fix Implemented**:
  * In `AppGate.kt`, defined `isSetupWizardActive` and `isWhatsNewActive`.
  * `onAppReady` is guaranteed to emit `false` whenever `isSetupWizardActive || isWhatsNewActive`.
  * Background main content mounting (`onMainContentMountChanged`) is withheld until `!isSetupWizardActive`.
  * Touch hit testing on iOS remains active on `AppGateComposeView` for the entire life of the wizard and What's New surfaces.

---

### Bug 3: Email Login Unreliable on iOS
* **Platform Scope**: **Shared mobile bug** (present in `commonMain`, affects both Android and iOS).
* **Direct Root Cause**:
  1. In `AuthRepository.kt` (line 55): `sessionStatus.collect { status -> if (AuthStorage.loadAnonymousUserId() != null) return@collect ... }`. If a user had ever tapped "Continue without account" or run anonymously during bringup, an anonymous user ID was saved. When `signInWith(Email)` succeeded, Supabase emitted `SessionStatus.Authenticated`. The collector checked `loadAnonymousUserId() != null` and dropped the authenticated session on the floor!
  2. `signInWithEmail` and `signUpWithEmail` did not update `_state.value` or clear anonymous user ID upon return. They waited solely on `sessionStatus.collect`, which was blocked by the anonymous ID.
  3. `validateRemoteSession` called `retrieveUserForCurrentSession(false)` on every session change. If a transient error occurred, `clearLocalSessionAfterRemoteInvalidation()` cleared the session immediately, knocking the user back to `Unauthenticated`.
* **Bug Class**: **Unsynchronized State Adoption / Mutation-Flow Race**. Failing to adopt authoritative mutation results immediately, while relying on an asynchronous flow that has filtering preconditions.
* **Codebase Audit for Siblings**:
  * `signUpWithEmail`: Same vulnerability as `signInWithEmail`.
  * Anonymous sign-in vs authenticated account upgrade.
* **Fix Implemented**:
  * In `signInWithEmail` and `signUpWithEmail`: upon successful Supabase call, clear anonymous user ID, retrieve current session/user, set `validatedRemoteUserId`, and synchronously update `_state.value = AuthState.Authenticated(...)`.
  * In `sessionStatus.collect`: when `SessionStatus.Authenticated` is received, clear anonymous user ID and adopt the authenticated state without dropping it.
  * For unauthenticated/initializing statuses: only reset state if no anonymous user is active.

---

### Bug 4: Platform-Specific Settings & Onboarding Ownership
* **Platform Scope**: **Architectural cross-platform sync issue**.
* **Analysis & Inventory**: See Section 3 below for full inventory and architectural proposal.

---

### Bug 5: Settings Menu Parity & Missing "Run Setup Again"
* **Platform Scope**: **iOS-only omission**.
* **Direct Root Cause**:
  1. In `SettingsRootPage.kt` (lines 270–290), the "Run setup again" and "What's new" navigation rows are wrapped in `if (onRunSetupAgainClick != null)` and `if (onWhatsNewClick != null)`.
  2. In `AppGate.kt` (lines 135–158), when `bypassAppGate = true` (which is used by iOS native tab views), `MainAppContent` was called without `onRunSetupAgainClick` or `onWhatsNewClick` (both defaulted to `null`).
  3. As a result, neither row was ever rendered on iOS.
* **Bug Class**: **Platform Container Callback Decoupling**. Omission of feature callbacks in platform-divergent screen wrappers.
* **Fix Implemented**:
  * Added `runSetupAgainRequests` and `whatsNewRequests` channels to `AppGateController`.
  * Added `requestRunSetupAgain()` and `requestWhatsNew()` methods to `AppGateController`.
  * Passed `onRunSetupAgainClick = { appGateController?.requestRunSetupAgain() }` and `onWhatsNewClick = { appGateController?.requestWhatsNew() }` in `AppGate.kt` when `bypassAppGate = true`.
  * In `AppGate.kt`, observed these channels to present `SetupWizardScreen(dismissible = true)` and `WhatsNewScreen(dismissible = true)` on demand with full pointer hit-testing enabled.

---

### Bug 6: Social and Downloads Bottom Navigation Collision
* **Platform Scope**: **iOS-only native Swift / bridge bug**.
* **Direct Root Cause**:
  1. In `iosApp/iosApp/ContentView.swift` (commit `0351f9a5d4`), `NuvioAppTab` replaced `downloads` with `social`.
  2. `allCoordinators` retained `downloadsCoordinator` and mapped `case .social: return downloadsCoordinator`.
  3. `updateTabTitles` mapped `.social: downloads`.
  4. The result: The tab displayed the text "Downloads", the icon `person.2.fill` (Social), and mounted `SocialScreen`. The actual Downloads screen was completely unreachable on iOS native navigation.
* **Bug Class**: **Enum Route Identity Conflation / Positional Bridge Mismatch**. Reusing an existing enum slot and coordinator for a different feature rather than adding a new identity.
* **Fix Implemented**:
  * Restored `downloads` to `NuvioAppTab` alongside `social` (all 6 tabs: `home`, `search`, `library`, `downloads`, `social`, `settings`).
  * Added `socialCoordinator` to `AppNavigationCoordinator`.
  * Mapped distinct coordinators: `.downloads -> downloadsCoordinator`, `.social -> socialCoordinator`.
  * Mapped distinct system icons: `.downloads -> "arrow.down.circle.fill"`, `.social -> "person.2.fill"`.
  * Updated `updateTabTitles` and title fallbacks so Downloads and Social display their respective titles.

---

### Bug 7: Downloads Capability Audit on iOS
* **Platform Scope**: **Shared route bug** (`DownloadsSettingsRoute` calling wrong screen) + **iOS-only navigation target failure** (due to missing tab in Bug 6).
* **Direct Root Cause**:
  1. In `SettingsDestinations.kt` (lines 68–85), `DownloadsDestination` (handling `DownloadsSettingsRoute`) invoked `DownloadsScreen` (the active download queue / file manager) instead of `DownloadsSettingsScreen` (the presets and addon source discovery settings).
  2. On iOS, tapping a download notification banner failed because `NuvioAppTab.from(kotlinName: "downloads")` returned `nil` (Bug 6).
  3. The iOS native download engine itself (`NSURLSession`, background downloads, range resumption in `DownloadsPlatformDownloader.ios.kt`) is fully implemented.
* **Bug Class**: **Route Destination Target Mismatch**. Hooking a settings route to a content screen with a similar name.
* **Fix Implemented**:
  * Routed `DownloadsDestination` in `SettingsDestinations.kt` to `DownloadsSettingsScreen(onBack = onBack)`.
  * Restoring the `downloads` tab in `ContentView.swift` (Bug 6) unblocks download banner navigation.

---

### Bug 8: Streamlined Mobile Sheet Opens Preferences Behind Itself
* **Platform Scope**: **Shared mobile UX defect with iOS-severe manifestation**.
* **Direct Root Cause**:
  1. On iOS, `NuvioModalBottomSheet` uses `NuvioNativeModalBottomSheet` (`usesNativeNuvioBottomSheet = true`), presenting a `ComposeUIViewController` as a UIKit modal sheet (`UIModalPresentationPageSheet`) on top of `parentViewController`.
  2. In `StreamDestination.kt`, tapping "Preferences" set `showPlaybackPreferences = true`.
  3. `PlaybackPreferencesDialog` was rendered directly in `StreamDestination`'s Compose hierarchy under `parentViewController`.
  4. Because `parentViewController` was obscured by the UIKit presented sheet, the dialog rendered completely behind the sheet and was unreachable.
* **Bug Class**: **Modal Hierarchy & View Controller Layering Violation**. Presenting an in-scene dialog from an external/native modal container.
* **Fix Implemented**:
  * Added `preferencesDialog: (@Composable () -> Unit)? = null` parameter to `PlaybackQualitySheet`.
  * Composed `preferencesDialog` directly inside `PlaybackQualitySheet`'s container (inside `NuvioModalBottomSheet` on mobile, and inside `Surface` on wide layouts).
  * On iOS, the preferences dialog now renders within the presented `ComposeUIViewController` directly above the quality cards.

---

### Bug 9: Playback Startup Loading Screen Collides with Camera Cutout
* **Platform Scope**: **Shared mobile bug** (affects iOS Dynamic Island/notch and Android cutouts in portrait and landscape).
* **Direct Root Cause**:
  1. In `PlaybackLoadingScreen.kt` (lines 155–190), `NuvioBackButton` only applied `WindowInsets.safeContent.only(WindowInsetsSides.Top)`. In landscape orientation, the camera cutout / Dynamic Island is on the `Start` side; with `Start` omitted, the back button collided with the notch.
  2. `PlaybackLoadingBand` and `PlaybackLoadingTitle` had no `windowInsetsPadding` applied. In landscape on notch/cutout devices, the stage line, metadata chips, and manual escape button extended directly under the camera cutout.
* **Bug Class**: **Display Cutout / Window Insets Omission Across Orientations**. Applying top-only system insets to full-bleed media screens that rotate or run edge-to-edge.
* **Fix Implemented**:
  * Applied `WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start)` to `NuvioBackButton`.
  * Applied `WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)` to `PlaybackLoadingBand`.
  * Applied `WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)` to `PlaybackLoadingTitle`.

---

## 3. Platform Settings Parity & Architecture Analysis (Bug 4 & Bug 5)

### Platform Settings Parity Matrix

| Setting Category | Setting / Feature | Android | iOS | Desktop | Parity Classification | Notes |
|:---|:---|:---:|:---:|:---:|:---|:---|
| **App Icon** | Alternate App Icon Picker | YES | YES | NO | Intentional capability | Supported on mobile via activity-alias (Android) & `setAlternateIconName` (iOS). |
| **Navigation** | Navigation Bar Style | All styles | Liquid Glass, Floating, Compact | Sidebar, TopBar | Intentional capability | Desktop supports sidebar; iOS excludes Classic tab bar. |
| **Player** | External Player Selection | Intent (VLC, JustPlayer, MX) | URL scheme (Infuse, Outplayer, VLC) | MPV native / External CLI | Shared feature with platform variants | Platform-specific URL schemes on iOS, Android Intents on Android. |
| **Subtitles** | Libass Subtitle Rendering | YES (ExoPlayer/Libmpv) | NO (uses mpvkit native) | YES | Intentional platform architecture | iOS renders libass natively through mpvkit. |
| **P2P / Torrent** | Torrent Client Config | Foreground Service / Daemon | Embedded daemon | Embedded daemon | Shared implementation | Background constraints differ on iOS vs Android. |
| **System** | Run Setup Again | YES | FIXED (Was missing) | YES | Accidental omission (Bug 5) | Missing on iOS due to `bypassAppGate = true` callback omission. |
| **System** | What's New | YES | FIXED (Was missing) | YES | Accidental omission (Bug 5) | Missing on iOS due to `bypassAppGate = true` callback omission. |
| **Desktop-only** | Discord Rich Presence | NO | NO | YES | Intentional capability | Desktop IPC only. |
| **Desktop-only** | Window Controls / UI Zoom | NO | NO | YES | Intentional capability | Desktop window manager only. |

### Proposed Onboarding & Settings Ownership Architecture

#### Current Problem
Currently, `setupWizardCompletedRevision` is written to `PlayerSettingsRepository` and synced to Supabase profile settings payload (`setup_wizard_completed_revision`). When a user completes onboarding on desktop, revision 9 is synced to their profile. When that user subsequently logs into iOS or Android, the setup wizard is completely skipped. Consequently, platform-specific choices (e.g., App Icon, mobile navigation bar style, external player integration) are never presented.

#### Architectural Evaluation
* **Model A (Global setup + Per-platform setup completion)**: Keep global settings synced, but split completion flag into `setup_wizard_completed_global` (synced) and `setup_wizard_completed_local_<platform>` (stored in `NSUserDefaults` / `SharedPreferences`).
* **Model B (Global wizard + First-launch platform continuation)**: If global wizard was completed on another device, display a lightweight 2-step "Configure for this device" flow on first launch.
* **Model C (Keep platform settings in Settings only)**: Keep onboarding strictly for cross-platform playback fundamentals (Playback Mode, Addons, Debrid, Languages), and direct users to Settings for platform customizations.

#### Recommended Model: Hybrid A + C (Clean, Non-Disruptive)
1. **Core Wizard Scope**: The core onboarding wizard focuses exclusively on profile-scoped, cross-platform choices (Playback Mode, Addons, Debrid, Languages). These sync across all devices via the profile.
2. **Platform Settings Ownership**: Platform-specific settings (App Icon, External Player URL scheme, Mobile Nav Bar Style) remain housed in their respective Settings pages where users expect them.
3. **Local First-Launch Platform Gating**: Introduce a local device key `platform_setup_reviewed_v1`. On the first launch on a new platform (even if the profile was onboarded elsewhere), surface a non-modal prompt or Settings banner: *"Customize Nuvio Z for iOS / Android"*.

---

## 4. Downloads-on-iOS Capability Verdict (Bug 7)

| Component | Status | Verification & Evidence |
|:---|:---:|:---|
| **Download Queue Engine** | **Fully Implemented** | `DownloadsPlatformDownloader.ios.kt` implements complete queue management with concurrent transfer limits. |
| **Background NSURLSession** | **Code-fixed for `.43`** | Stable bundle-specific background session, `URLSessionDownloadTask`, durable task identity, relaunch enumeration and the AppDelegate completion-handler bridge. Physical lock/relaunch validation remains open. |
| **Pause / resume** | **Code-fixed for `.43`** | User pause suspends the native task and reattaches it on resume; normal app backgrounding does not pause. |
| **Legacy `.part` migration** | **Controlled restart** | `.42` data-task partials restart once because no native resume record can prove byte continuity. The pure reconciler rejects misaligned 206 responses. |
| **File Finalization & Storage** | **Code-fixed for `.43`** | iOS temporary download URL moves to `.part`, byte completion is verified, then the file is moved into its final location before repository completion. |
| **Live Activities / Notifications**| **Code-fixed for `.43`** | Honest preparation/wait/retry states, indeterminate progress for unknown totals, deterministic promotion/clearing, and orphan ActivityKit cleanup. Physical completion/cancel/failure validation remains open. |
| **Navigation Target** | **Code-fixed for `.43`** | Downloads is an internal Library surface. Legacy Downloads intents and persisted selections canonicalize to Library → Downloads across separate native iOS Compose controllers. |
| **Offline Playback** | **Shared Implementation** | `DownloadsStorage.ios.kt` resolves local file URLs for player consumption. |

**Verdict**: `.42` proved foreground download and offline playback, but not background transfer.
`.43` contains the real background-session architecture and must not be called physically verified
until the lock, relaunch and completion-while-suspended checklist passes.

---

## 5. Verification & Testing

### Test Execution Results
* **Pure Test Suites**: all 8 groups pass, **812 / 812**.
* **Android host tests**: `:composeApp:testAndroidHostTest` passes, **2,311 / 2,311**.
* **Common compilation**: `:composeApp:compileCommonMainKotlinMetadata` passes.
* **Targeted Tests Added**:
  1. `IosBackgroundTransferReconcilerTest`: stable session identity, relaunch attach, duplicate prevention,
     bytes, missing/stale/failed tasks, pause/cancel, completion, legacy partials and 200/206/416 boundaries.
  2. `DownloadsLiveStatusPolicyTest`: preparation/retry labels, unknown and known totals, final clearing,
     queued-item promotion and active-transfer priority.
  3. `PresetDownloadsTest`: movie/episode/season scopes and legacy whole-title season expansion.
  4. Navigation tests: old Downloads intent and saved-tab canonicalization to Library → Downloads while
     Social remains a separate top-level destination.

---

## 6. `.43` Physical Verification Checklist

To be verified on physical devices via the SideStore debug build. None of these unchecked rows is
claimed by the code or CI results above.

1. [ ] **Watch Together Dock**:
   * Initiate a Watch Together join request while on Home screen.
   * Verify pill appears in the top-right corner below Dynamic Island/status bar, clear of bottom navigation bar and toasts.
2. [ ] **Setup Wizard Touch Interaction**:
   * Reset app data or switch to a new profile with uncompleted setup.
   * Tap cards, buttons, and switches in every step of the setup wizard.
   * Verify all touch events register immediately with zero dropped taps.
3. [ ] **Email Sign-In**:
   * From a state where "Continue without account" was previously tapped, open Auth screen.
   * Enter email and password and tap Sign In.
   * Verify immediate transition to authenticated profile gate without freezing or requiring restart.
4. [ ] **Settings "Run setup again" & "What's new"**:
   * Open Settings tab.
   * Verify "Run setup again" and "What's new" rows are visible.
   * Tap "Run setup again" -> verify wizard opens above the app and all buttons respond to touch. Tap Close/Dismiss -> verify clean return to Settings.
5. [ ] **Background download**:
   * Start a large download, lock the phone for at least two minutes, and verify the Live Activity advances.
   * Unlock and verify bytes continued without a background-only `Retrying` transition.
   * Repeat with Home/app switching and complete one transfer while the app remains backgrounded.
6. [ ] **Whole-title entry points**:
   * Series context menu → Download → View; verify Library → Downloads opens and episodes queue.
   * Repeat for a movie. Verify episode and season entry points still enqueue independently.
7. [ ] **Known-good episode baseline**:
   * Download one episode, wait for completion, disable the network, and play the local file.
8. [ ] **Season control**:
   * Verify the season Download button is readable in light/dark themes, then complete and play a season item.
9. [ ] **Live Activity lifecycle**:
   * Observe Finding sources, Preparing/Starting, unknown-total downloading, known progress, retry, and pause.
   * Complete, cancel, fail, and delete transfers; verify no orphan remains after its represented row is gone.
   * Complete one item while another remains and verify the next item is promoted.
10. [ ] **Five-item navigation**:
   * Verify Home, Search, Library, Social, and Profile/Settings appear with no `More` tab.
   * Inside Library switch between Library and Downloads in one tap.
   * Verify View Downloads, notification actions, old Downloads deep links, and a saved Downloads tab all land on Library → Downloads.
11. [ ] **iOS-visible navigation settings**:
   * Inspect every visible navigation-style option on iPhone/iPad and verify each changes the UI.
   * Verify Compose-only style choices are absent while native SwiftUI tabs own navigation.
12. [ ] **Streamlined Quality Sheet Preferences**:
   * Open a title in Streamlined playback mode to show the quality sheet.
   * Tap "Adjust preferences".
   * Verify `PlaybackPreferencesDialog` renders cleanly on top of the sheet, responds to touch, and dismisses back to the quality sheet.
13. [ ] **Playback Loading Screen Safe Area**:
   * Start playback of any title in portrait.
   * Verify top back button is safely below Dynamic Island / notch.
   * Rotate to landscape.
   * Verify back button and bottom metadata band are safely indented past the camera notch on the side.
14. [ ] **Android shared-change spot check**:
   * Whole-title Download and View Downloads routing.
   * Five-item top navigation and Library/Downloads switch, including saved Downloads-tab migration.
   * Preparing/unknown-total notification wording, semantic season/download button colors, and completed local playback.
## 7. `.44` Physical Verification Checklist

To be verified on physical iPhone via SideStore debug build `0.4.13-z1.44`:

1. [ ] **Multi-Episode Screen-Off Queue Advancement**:
   * Queue 3+ episodes from a series in Library → Downloads.
   * Lock the phone while Episode 1 is actively downloading.
   * Wait for Episode 1 to complete (~2-3 minutes depending on network/size).
   * Verify via Live Activity that Episode 2 starts automatically without unlocking or waking the device.
2. [ ] **Background Completion Bookkeeping**:
   * With screen locked, allow a download to finish.
   * Unlock the phone and open Nuvio Z.
   * Verify the download appears immediately in Completed state with offline playback badge, without showing `Waiting` or `Retrying`.
3. [ ] **Live Activity Stability under Concurrent Downloads**:
   * Start 2 concurrent downloads.
   * Check Dynamic Island and Lock Screen Live Activity.
   * Verify Live Activity shows the primary item consistently without flickering or ping-ponging progress between the two.
   * Verify the queue summary shows `2 downloading • X remaining`.
4. [ ] **Debrid 15-Minute Link Freshness Boundary**:
   * Queue multiple episodes, then lock phone for >15 minutes after earlier episodes complete.
   * Verify queue safely pauses at the stale boundary without downloading corrupt/expired URLs out of order.
   * Reopen the app: verify fresh URLs are resolved immediately and queue resumes.
5. [ ] **User Pause vs Screen Lock**:
   * Pausing a download explicitly from UI pauses the native task and persists paused state.
   * Locking the screen maintains active byte transfer and advances the native queue.

## 8. `.45` Physical Verification Checklist

SideStore debug build `0.4.13-z1.45`. Installing over `.44` also exercises the upgrade path, since
`.44` may have left stale RUNNING entries.

1. [ ] **Order and concurrency.** Download a 6-episode season. #1 and #2 start first, #3 starts
   only when one finishes, and never more than 2 run at once.
2. [ ] **Screen off.** Lock the phone mid-season for 20+ minutes.
   - The running items finish, and the next item starts if its link is under 15 minutes old.
   - After a stale item, the queue waits there instead of skipping ahead.
   - Unlock and open the app: the waiting item resolves and the queue continues in order.
3. [ ] **Relaunch.**
   - (a) Swipe the app away mid-download and reopen: the queue order is kept, items resume at their
     positions, and there are no duplicate rows or double progress.
   - (b) Leave it backgrounded overnight: any transfers still running are adopted where they are,
     and nothing jumps ahead of them.
4. [ ] **Pause / resume / delete** on an active item, a queued item and a completed episode.
5. [ ] **Freeze (Delete ×10, then Home scrolled to the bottom several times).** If input dies:
   note the time, leave the app, come back, then send `Files > On My iPhone > Nuvio Z Debug >
   nuvio_diagnostics`.
6. [ ] **Live Activity.** One activity with a steady primary item. The summary reads
   "2 downloading • N remaining" in the app language, and the activity ends when the queue
   empties.

## 9. `.46` Physical Verification Checklist

SideStore debug build `0.4.13-z1.46`. After each run, send
`Files > On My iPhone > Nuvio Z Debug > nuvio_diagnostics/downloads-*.jsonl`.

1. [ ] **Queue, then lock.** Queue a season plus a film and an episode of another show, 8–12 items
   in total. Wait until every row shows Downloading/Starting, then lock for 30+ minutes.
   - Expect: the log shows every `create` with `"app":"active"`, and `metrics.responseStart` for
     them falls **before** the next `device_unlocked`;
   - Expect: several items finished while locked, with no duplicate rows, and the list still in
     queue order.
2. [ ] **Beyond the window.** Queue 15+ items and stay locked until the early ones finish. Note from
   the log whether items 13+ were created in the background, and when their `responseStart` came
   relative to the unlock. This is best-effort chaining; either outcome is acceptable, but nothing
   may duplicate or reorder.
3. [ ] **Force-quit.** Swipe Nuvio away mid-download and reopen. Expect:
   - `complete` lines with `systemCancel` set and `"listened":false`;
   - every row back at its place and restarting;
   - no attempt counts, retry badges or failure rows from the quit;
   - no duplicates.
4. [ ] **Plain background / overnight.** Leave it backgrounded without quitting, then return. Running
   tasks are adopted (`inventory` shows them) and nothing restarts from zero.
5. [ ] **Pause / resume / delete** on an active item, a waiting (0-byte) item and a completed
   episode. Delete must not freeze; a deleted item logs `cancel`.
6. [ ] **Old link.** Queue, stay unlocked for 20+ minutes, then lock. Note the `finish` HTTP status
   and `urlAgeSec` for anything started by background chaining.
7. [ ] **Live Activity.** On lock it shows "Downloading in background" plus e.g.
   "5 downloading • 3 remaining", with no percentage or bytes. The counts change after completions,
   the real percentage is back on unlock, and there is no flicker.
