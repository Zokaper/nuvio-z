# Phase 8 — iOS Device Validation & Bringup Audit

**Branch**: `gemini/phase-8-ios-bringup-audit`  
**Base**: `codex/ios-setup-gui-v1` (aligned with Phase 7 merge on `main`)  
**Target**: Do NOT merge to `main`. Physical iPhone QA pass (SideStore Debug bringup).

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
| **Background NSURLSession** | **Fully Implemented** | Native background sessions with `handleDownloadsBackgroundEvents` delegate. |
| **Byte Range Resumption** | **Fully Implemented** | HTTP `Range: bytes=X-` resumption from `.part` files with `resumesSystemPauses = true`. |
| **File Finalization & Storage** | **Fully Implemented** | POSIX `fwrite` file streaming with atomic rename via `NSFileManager.moveItemAtPath`. |
| **Live Activities / Notifications**| **Fully Implemented** | `DownloadsLiveStatusPlatform.ios.kt` emits notifications to `NSNotificationCenter` with serialized payloads. |
| **Navigation Target** | **FIXED** | Restored `downloads` tab in `ContentView.swift` (Bug 6); fixed `DownloadsDestination` route in `SettingsDestinations.kt`. |
| **Offline Playback** | **Shared Implementation** | `DownloadsStorage.ios.kt` resolves local file URLs for player consumption. |

**Verdict**: Downloads on iOS is **fully implemented and capable**. The observed breakdown was caused by route navigation target misdirection (`DownloadsSettingsRoute` pointing to the wrong screen) and the native tab collision (Bug 6).

---

## 5. Verification & Testing

### Test Execution Results
* **Pure Test Suites**: Ran `scripts/run-pure-suites.sh` via Git Bash.
  * All 8 pure test suite groups executed cleanly (676+ tests).
  * 0 failures, 0 errors.
* **Targeted Tests Added**:
  1. `com.nuvio.app.navigation.BottomNavItemIdentityTest`:
     * Asserts distinct identity for `Downloads` and `Social` in `AppScreenTab`.
     * Asserts distinct identity for `Downloads` and `Social` in `NativeNavigationTab`.
     * Asserts bidirectional lossless round-trip between Compose tabs and native bridge tabs.
  2. `com.nuvio.app.features.setup.SetupWizardStepsTest`:
     * Asserts `appGateMustNotReportReadyWhileSetupWizardIsActive`: validates that `isAppReady` cannot emit `true` while the first-run wizard is active, preventing SwiftUI touch shutoff.
     * Asserts `appGateMustNotReportReadyWhileOnDemandSetupWizardIsActive`: validates on-demand wizard readiness gating.

---

## 6. Physical Verification Checklist for iPhone QA

To be verified on physical iPhone via SideStore Debug build:

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
5. [ ] **Bottom Navigation Tabs**:
   * Verify 6 distinct tabs: Home, Search, Library, Downloads, Social, Settings.
   * Verify Downloads has the download arrow icon and opens the downloads screen.
   * Verify Social has the person icon and opens the social feed.
6. [ ] **Streamlined Quality Sheet Preferences**:
   * Open a title in Streamlined playback mode to show the quality sheet.
   * Tap "Adjust preferences".
   * Verify `PlaybackPreferencesDialog` renders cleanly on top of the sheet, responds to touch, and dismisses back to the quality sheet.
7. [ ] **Playback Loading Screen Safe Area**:
   * Start playback of any title in portrait.
   * Verify top back button is safely below Dynamic Island / notch.
   * Rotate to landscape.
   * Verify back button and bottom metadata band are safely indented past the camera notch on the side.
