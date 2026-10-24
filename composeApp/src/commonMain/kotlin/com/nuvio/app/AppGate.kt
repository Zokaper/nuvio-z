package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AppGateTransitionDecision
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.AuthStateMachine
import com.nuvio.app.core.auth.DeviceSessionRegistration
import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.maskId
import com.nuvio.app.core.auth.safeAuthStateDescription
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_auth_sign_in
import nuvio.composeapp.generated.resources.z_auth_signed_out_notice
import org.jetbrains.compose.resources.getString
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.CrossFamilyImportOutcome
import com.nuvio.app.core.sync.CrossFamilySettingsImport
import com.nuvio.app.core.sync.pullThenImportCrossFamilySettings
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioToastAction
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.features.setup.AdvancedSetupLauncher
import com.nuvio.app.features.setup.advancedSetupAreasChangedSince
import com.nuvio.app.features.setup.advancedSetupAreasChangedIn
import com.nuvio.app.features.setup.AdvancedSetupCategory
import com.nuvio.app.features.setup.AdvancedSetupScreen
import com.nuvio.app.features.setup.DeviceSetupStorage
import com.nuvio.app.features.setup.SETUP_WIZARD_REVISION
import com.nuvio.app.features.setup.SetupProfileFlags
import com.nuvio.app.features.setup.SetupWizardRun
import com.nuvio.app.features.setup.SetupWizardScreen
import com.nuvio.app.features.setup.setupWizardRun
import com.nuvio.app.features.setup.DeviceSetupLauncher
import com.nuvio.app.features.setup.DeviceSetupSeed
import com.nuvio.app.features.setup.SETUP_DEVICE_REVISION
import com.nuvio.app.features.setup.deviceSetupSeed
import com.nuvio.app.features.setup.isDeviceSetupStale
import com.nuvio.app.features.setup.shouldOfferDeviceSetup
import com.nuvio.app.features.whatsnew.DeviceSetupOffer
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.updater.AppReleaseNotes
import com.nuvio.app.features.updater.fetchRecentReleaseNotes
import com.nuvio.app.features.whatsnew.ChangelogAction
import com.nuvio.app.features.whatsnew.NewSettingsOffer
import com.nuvio.app.features.whatsnew.ChangelogCatalog
import com.nuvio.app.features.whatsnew.WhatsNewBadge
import com.nuvio.app.features.whatsnew.WhatsNewEventUi
import com.nuvio.app.features.whatsnew.WhatsNewSection
import com.nuvio.app.features.whatsnew.WhatsNewState
import com.nuvio.app.features.whatsnew.decideWhatsNew
import com.nuvio.app.features.whatsnew.eligibleEvents
import com.nuvio.app.features.whatsnew.isCoveredByChangelog
import com.nuvio.app.features.whatsnew.markWhatsNewViewed
import com.nuvio.app.features.whatsnew.resolveWhatsNewState
import com.nuvio.app.features.whatsnew.whatsNewBadge
import com.nuvio.app.features.whatsnew.whatsNewDebugSection
import com.nuvio.app.features.whatsnew.whatsNewEventsUi
import com.nuvio.app.features.whatsnew.WhatsNewScreen
import com.nuvio.app.features.whatsnew.WhatsNewStorage
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal suspend fun warmProfileBoundRepositories() {
    withContext(Dispatchers.Default) {
        AddonRepository.initialize()
        CollectionRepository.initialize()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        DownloadsRepository.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
        SocialFeaturePreferencesRepository.ensureLoaded()
        HomeCatalogSettingsRepository.snapshot()
        LibraryRepository.ensureLoaded()
        P2pSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        CollectionSyncService.startObserving()
        ProfileSettingsSync.startObserving()
    }
}

private val log = Logger.withTag("AppGate")

internal enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileSwitching,
    ProfileEdit,
    Main,
}

private data class PendingProfileSwitch(
    val profile: NuvioProfile,
    val syncOnEnter: Boolean,
)

@Composable
internal fun AppGate(
    initialTab: AppScreenTab,
    initialRoute: AppRoute,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    ownsAppRuntime: Boolean,
    bypassAppGate: Boolean,
    renderMainContent: Boolean,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)?,
    onGoBack: (() -> Unit)?,
    onReplace: ((AppRoute) -> Unit)?,
    onActivate: ((AppScreenTab) -> Unit)?,
    onAppReady: ((Boolean) -> Unit)?,
    onMainContentMountChanged: ((Boolean) -> Unit)?,
    onMainContentVisibleChanged: ((Boolean) -> Unit)?,
    onTabTitles: ((home: String, search: String, library: String, downloads: String, profile: String, switchProfile: String, addProfile: String) -> Unit)?,
    nativeProfileSwitcherController: NativeProfileSwitcherController?,
    appGateController: AppGateController?,
) {
    if (bypassAppGate) {
        MainAppContent(
            onWhatsNewClick = appGateController?.let { controller ->
                { controller.requestWhatsNew() }
            },
            onRunSetupAgainClick = appGateController?.let { controller ->
                { controller.requestRunSetupAgain() }
            },
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            showLaunchOverlay = appGateController == null,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onTabTitles = onTabTitles,
            appGateController = appGateController,
            onRootContentReady = appGateController?.let { controller ->
                controller::reportMainContentReady
            },
            onSwitchProfile = appGateController?.let { controller ->
                controller::requestProfileSelection
            } ?: {},
        )
        return
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        AuthRepository.initialize()
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        AvatarRepository.fetchAvatars()
    }

    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileAvatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val networkStatusUiState by remember {
        NetworkStatusRepository.uiState
    }.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        if (!ownsAppRuntime) return@LaunchedEffect
        DeviceSessionRegistration.registerIfAuthenticated(force = true)
    }

    LaunchedEffect(
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.name,
        profileState.activeProfile?.avatarColorHex,
        profileState.activeProfile?.avatarId,
        profileState.activeProfile?.avatarUrl,
        profileAvatars,
    ) {
        val activeProfile = profileState.activeProfile
        val avatarItem = activeProfile?.avatarId?.let { avatarId ->
            profileAvatars.find { it.id == avatarId }
        }
        NativeTabBridge.publishProfileTabIcon(
            name = activeProfile?.name,
            avatarColorHex = activeProfile?.avatarColorHex,
            avatarImageUrl = activeProfile?.let { profileAvatarImageUrl(it, avatarItem) },
            avatarBackgroundColorHex = avatarItem?.bgColor,
        )
    }

    // ⚠ Here rather than inside `MainAppContent`, because the setup wizard replaces
    // `MainAppContent` while it is gating the app - so every choice the wizard wrote went
    // unobserved, and the observer's own `combine(...).drop(1)` then discarded the first
    // signature it saw once the wizard finished. That is the signature carrying the
    // completed revision, so the remote never learned it and the next startup pull re-gated
    // the app with the old one. Settings written while the wizard is up are settings.
    // `startObserving` is idempotent, so this and any other call site are safe together.
    //
    // Started off the UI thread and after the first frame (Performance Phase 1): it loads 17+
    // repositories from disk and builds MDBList's Ktor client, which ran inside the first
    // composition and was 0.5-0.75 s of the cold-start freeze. Run beside the first frame instead,
    // it competed with it for CPU and class loading and made that frame slower, so it waits for
    // one. A start a few hundred ms later still precedes anything the wizard can write - the wizard
    // is only reached through the profile gate - and the observer baselines on whatever it first
    // sees, as it always has.
    if (ownsAppRuntime) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withContext(Dispatchers.Default) { ProfileSettingsSync.startObserving() }
        }
    }

    // Gates the first-launch playback-mode selector. Read here rather than as a new
    // AppGateScreen value because five separate transitions set the gate to Main;
    // wrapping the Main branch covers every one of them with a single decision.
    val gatePlayerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    // A window re-created by desktop tray mode resumes the app it left; see [AppGateResume].
    var gateScreen by rememberSaveable {
        mutableStateOf(if (AppGateResume.canResumeMain()) AppGateScreen.Main.name else AppGateScreen.Loading.name)
    }
    var pendingProfileSwitch by remember { mutableStateOf<PendingProfileSwitch?>(null) }
    var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var autoSkipProfileSelection by rememberSaveable { mutableStateOf(false) }
    // The global changelog (release events, see `WhatsNewSelection.kt`): after an update, a screen of
    // the events this device has not acknowledged; in Settings, a "New" badge while any is unviewed.
    // A fresh install never opens on it - the badge invites a look instead.
    val whatsNewIdentity = remember { WhatsNewStorage.releaseIdentity }
    var changelog by remember { mutableStateOf<ChangelogCatalog.Loaded?>(null) }
    var whatsNewEvents by remember { mutableStateOf<List<WhatsNewEventUi>>(emptyList()) }
    var whatsNewDebug by remember { mutableStateOf<WhatsNewSection?>(null) }
    var whatsNewState by remember { mutableStateOf<WhatsNewState?>(null) }
    var whatsNewStateAfterContinue by remember { mutableStateOf<WhatsNewState?>(null) }
    var showWhatsNew by remember { mutableStateOf(false) }
    // Opened from Settings rather than shown after an update: dismissible. Opening it views (and
    // acknowledges) every released event, which is what clears the badge.
    var showWhatsNewOnDemand by remember { mutableStateOf(false) }
    // Settings -> "Run setup again". Hoisted here for the same reason the What's New flag
    // is: the gating showing lives in this function, and one flag for both is what keeps
    // an on-demand run from being confused with the first-launch one.
    var showSetupWizardOnDemand by remember { mutableStateOf(false) }
    // Z: the "New settings available" offer and what it opens. `whatsNewDecided` holds the offer back
    // until the post-update What's New has been decided, so it never flashes up before that screen.
    var whatsNewDecided by remember { mutableStateOf(false) }
    // Z (Device Setup redesign, 2026-10-24): the first launch of the redesign decides what this
    // installation is - fresh, or one that predates it, whose profiles all count as set up here so
    // nobody meets a wizard on update day (`deviceSetupSeed`). Idempotent: it writes a revision.
    remember {
        when (deviceSetupSeed(DeviceSetupStorage.loadRevision(), ProfileRepository.state.value.profiles.isNotEmpty())) {
            DeviceSetupSeed.Fresh -> DeviceSetupStorage.saveRevision(0)
            DeviceSetupSeed.Legacy -> {
                SetupProfileFlags.markLegacyInstallation()
                DeviceSetupStorage.saveRevision(SETUP_DEVICE_REVISION)
            }
            DeviceSetupSeed.None -> Unit
        }
    }
    // Device Setup on demand: from the "First time on this device" pop-up, or Settings.
    var showDeviceSetup by remember { mutableStateOf(false) }
    var deviceSetupEpoch by remember { mutableStateOf(0) }
    // Once it has been offered this launch, "New settings available" waits for the next one: the two
    // pop-ups never follow each other, and a setup never interleaves with another (maintainer).
    var deviceSetupOfferedThisLaunch by remember { mutableStateOf(false) }
    var arrivalPending by remember {
        mutableStateOf(SetupProfileFlags.arrivalPending(ProfileRepository.activeProfileId))
    }
    var importOutcome by remember { mutableStateOf<CrossFamilyImportOutcome?>(null) }
    var reviewSetupRequested by remember { mutableStateOf(false) }
    var setupWizardOnDemandEpoch by remember { mutableStateOf(0) }
    // Advanced Setup (setup + settings pass): an optional hub over the app, from Settings, the
    // wizard's Done page or What's New. A request made while something gates the app (the wizard,
    // the profile picker) waits for it: the hub opens only over the mounted app, see below.
    var advancedSetupRequested by remember { mutableStateOf(false) }
    // The areas a review opens onto; null for the hub itself.
    var advancedSetupReview by remember { mutableStateOf<List<AdvancedSetupCategory>?>(null) }
    // null while loading, empty when it could not be fetched. Either way the shipped events
    // still render - this screen has to work offline and on builds where the in-app updater is
    // disabled.
    var whatsNewHistory by remember { mutableStateOf<List<AppReleaseNotes>?>(null) }

    LaunchedEffect(ownsAppRuntime) {
        if (!ownsAppRuntime) return@LaunchedEffect
        val loaded = ChangelogCatalog.load(whatsNewIdentity.family)
        changelog = loaded
        val decision = decideWhatsNew(loaded.events, loaded.debugNotes, whatsNewIdentity.viewer, WhatsNewStorage.load())
        // Saved at once: a migration from the old keys happens exactly once, and a fresh install is
        // recorded as one (acknowledged, not viewed) before anything else can run.
        WhatsNewStorage.save(decision.state)
        whatsNewState = decision.state
        WhatsNewBadge.update(decision.badge)
        if (decision.shouldShow) {
            whatsNewEvents = whatsNewEventsUi(decision.events, whatsNewIdentity.platform)
            whatsNewDebug = whatsNewDebugSection(decision.debugNotes)
            whatsNewStateAfterContinue = decision.stateAfterContinue
            showWhatsNew = true
        }
        whatsNewDecided = true
    }

    // Settings -> What's new: every event released to this platform, from the shipped changelog
    // (offline), then whatever older releases the releases feed has that the changelog does not.
    LaunchedEffect(showWhatsNewOnDemand) {
        if (!showWhatsNewOnDemand) return@LaunchedEffect
        val loaded = changelog ?: ChangelogCatalog.load(whatsNewIdentity.family).also { changelog = it }
        val viewer = whatsNewIdentity.viewer
        whatsNewEvents = whatsNewEventsUi(eligibleEvents(loaded.events, viewer), whatsNewIdentity.platform)
        whatsNewDebug = null
        val current = whatsNewState ?: resolveWhatsNewState(loaded.events, viewer, WhatsNewStorage.load()).first
        val viewed = markWhatsNewViewed(current, loaded.events, viewer)
        WhatsNewStorage.save(viewed)
        whatsNewState = viewed
        WhatsNewBadge.update(whatsNewBadge(viewed, loaded.events, viewer))
        whatsNewHistory = null
        whatsNewHistory = fetchRecentReleaseNotes()
            .getOrNull()
            ?.filter { !isCoveredByChangelog(it.tag, loaded.events, whatsNewIdentity.platform) }
            .orEmpty()
    }
    // Done on the post-update screen, or one of its action cards: everything released here is now
    // acknowledged and viewed.
    val acknowledgeWhatsNew: () -> Unit = {
        whatsNewStateAfterContinue?.let { after ->
            WhatsNewStorage.save(after)
            whatsNewState = after
            changelog?.let { WhatsNewBadge.update(whatsNewBadge(after, it.events, whatsNewIdentity.viewer)) }
        }
        showWhatsNew = false
    }
    var profileSelectionLoading by rememberSaveable { mutableStateOf(false) }
    var profileSelectionTransitionActive by rememberSaveable { mutableStateOf(false) }
    var skipProfileSelectionEnterAnimation by remember { mutableStateOf(false) }
    var mainContentStarted by rememberSaveable { mutableStateOf(false) }
    val externalMainContentReady = if (!renderMainContent && appGateController != null) {
        val ready by appGateController.mainContentReady.collectAsStateWithLifecycle()
        ready
    } else {
        false
    }

    fun reportAppReady(ready: Boolean) {
        appGateController?.reportAppReady(ready)
        onAppReady?.invoke(ready)
    }

    // Full, an upgrade (offered, see below), a cross-family arrival's Device run, or nothing.
    val gateSetupRun = setupWizardRun(
        profileRevision = gatePlayerSettings.setupWizardCompletedRevision,
        currentRevision = SETUP_WIZARD_REVISION,
        arrivalPending = arrivalPending,
    )
    // Re-read when the gate moves (a profile switch) or a setup finishes or is declined.
    val deviceSetupDone = remember(gateScreen, deviceSetupEpoch) {
        SetupProfileFlags.deviceSetupDone(ProfileRepository.activeProfileId)
    }
    val deviceSetupOffered = shouldOfferDeviceSetup(gateSetupRun, deviceSetupDone) && !reviewSetupRequested
    // "Not now", or leaving the run early: the current values stand, and this device counts as set up.
    val declineDeviceSetup: () -> Unit = {
        SetupProfileFlags.markDeviceSetupDone(ProfileRepository.activeProfileId)
        if (isDeviceSetupStale(DeviceSetupStorage.loadRevision())) DeviceSetupStorage.saveRevision(SETUP_DEVICE_REVISION)
        deviceSetupEpoch++
    }
    // Z (2026-10-20): an Upgrade no longer gates the app. It is offered after What's New instead -
    // "New settings available" - unless it carries a cross-family arrival, which is device setup.
    val upgradeOffered = gateSetupRun == SetupWizardRun.Upgrade && !arrivalPending && !reviewSetupRequested
    // Z (2026-10-24, maintainer): a debug build offers this release's settings again, once per profile
    // per debug build, so a tester who already answered can still walk them. Never on a stable build.
    // Re-read when the gate moves (a profile switch) or the offer is answered.
    val debugOfferBuild = whatsNewIdentity.debugBuild
    var debugOfferEpoch by remember { mutableStateOf(0) }
    val debugOfferPending = remember(gateScreen, debugOfferEpoch) {
        debugOfferBuild != null &&
            !SetupProfileFlags.debugSettingsOfferAnswered(ProfileRepository.activeProfileId, debugOfferBuild)
    }
    val debugOffered = debugOfferPending && !upgradeOffered && gateSetupRun == SetupWizardRun.None && !reviewSetupRequested
    val answerDebugOffer: () -> Unit = {
        if (debugOfferBuild != null) {
            SetupProfileFlags.markDebugSettingsOfferAnswered(ProfileRepository.activeProfileId, debugOfferBuild)
            debugOfferEpoch++
        }
    }
    val gatingRun = when {
        reviewSetupRequested -> SetupWizardRun.Full
        upgradeOffered -> SetupWizardRun.None
        else -> gateSetupRun
    }
    val isSetupWizardActive = gatingRun != SetupWizardRun.None || showSetupWizardOnDemand || showDeviceSetup
    val isAdvancedSetupActive = advancedSetupRequested &&
        gateScreen == AppGateScreen.Main.name &&
        gatingRun == SetupWizardRun.None &&
        !showSetupWizardOnDemand &&
        !showDeviceSetup

    val isWhatsNewActive = (showWhatsNew && gateScreen == AppGateScreen.Main.name) || showWhatsNewOnDemand

    LaunchedEffect(gateScreen, onAppReady, isSetupWizardActive, isWhatsNewActive, isAdvancedSetupActive) {
        if (gateScreen != AppGateScreen.Main.name || isSetupWizardActive || isWhatsNewActive || isAdvancedSetupActive) {
            reportAppReady(false)
        }
    }

    LaunchedEffect(gateScreen, renderMainContent, onMainContentMountChanged, isSetupWizardActive) {
        if (renderMainContent) return@LaunchedEffect
        when (gateScreen) {
            AppGateScreen.Main.name -> {
                if (!isSetupWizardActive) {
                    mainContentStarted = true
                    onMainContentMountChanged?.invoke(true)
                }
            }
            AppGateScreen.Loading.name,
            AppGateScreen.ProfileSwitching.name,
            AppGateScreen.Auth.name,
            -> {
                mainContentStarted = false
                appGateController?.reportMainContentReady(false)
                onMainContentMountChanged?.invoke(false)
            }
            else -> onMainContentMountChanged?.invoke(mainContentStarted)
        }
    }

    LaunchedEffect(appGateController, renderMainContent) {
        if (renderMainContent) return@LaunchedEffect
        appGateController?.profileSelectionRequests?.collect {
            autoSkipProfileSelection = false
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            skipProfileSelectionEnterAnimation = true
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    LaunchedEffect(appGateController, renderMainContent) {
        if (renderMainContent) return@LaunchedEffect
        appGateController?.runSetupAgainRequests?.collect {
            setupWizardOnDemandEpoch++
            showSetupWizardOnDemand = true
        }
    }

    LaunchedEffect(appGateController, renderMainContent) {
        if (renderMainContent) return@LaunchedEffect
        appGateController?.whatsNewRequests?.collect {
            showWhatsNewOnDemand = true
        }
    }

    // ⚠ Collected by the gate that draws overlays, whichever that is: the only gate on Android and
    // desktop, `AppGateOverlay`'s on iOS (never the `bypassAppGate` one, which returns above).
    LaunchedEffect(ownsAppRuntime) {
        if (!ownsAppRuntime) return@LaunchedEffect
        AdvancedSetupLauncher.requests.collect { areas ->
            advancedSetupReview = areas
            advancedSetupRequested = true
        }
    }

    LaunchedEffect(ownsAppRuntime) {
        if (!ownsAppRuntime) return@LaunchedEffect
        DeviceSetupLauncher.requests.collect { showDeviceSetup = true }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.requestedManageProfiles?.collect {
            appGateController.requestProfileSelection()
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.selectedProfileIndices?.collect { profileIndex ->
            if (profileIndex == ProfileRepository.state.value.activeProfile?.profileIndex) return@collect
            val profile = ProfileRepository.state.value.profiles
                .firstOrNull { it.profileIndex == profileIndex }
                ?: return@collect
            autoSkipProfileSelection = false
            profileSelectionLoading = true
            profileSelectionTransitionActive = true
            skipProfileSelectionEnterAnimation = true
            appGateController.beginContentReload()
            pendingProfileSwitch = PendingProfileSwitch(profile, syncOnEnter = true)
            gateScreen = AppGateScreen.ProfileSwitching.name
            onActivate?.invoke(AppScreenTab.Home)
        }
    }

    LaunchedEffect(externalMainContentReady, renderMainContent) {
        if (!renderMainContent && externalMainContentReady) {
            profileSelectionLoading = false
        }
    }

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        onMainContentVisibleChanged,
    ) {
        if (!renderMainContent) {
            onMainContentVisibleChanged?.invoke(
                gateScreen == AppGateScreen.Main.name && externalMainContentReady,
            )
        }
    }

    fun rememberedStartupProfile(profiles: List<NuvioProfile>): NuvioProfile? {
        val currentProfileState = ProfileRepository.state.value
        if (
            !currentProfileState.rememberLastProfileEnabled ||
            !currentProfileState.hasEverSelectedProfile
        ) {
            return null
        }

        return profiles
            .find { it.profileIndex == ProfileRepository.activeProfileId }
            ?.takeUnless { it.pinEnabled }
    }

    fun requestProfileSwitch(profile: NuvioProfile, sync: Boolean) {
        if (!renderMainContent) {
            appGateController?.beginContentReload()
        }
        autoSkipProfileSelection = false
        profileSelectionLoading = true
        pendingProfileSwitch = PendingProfileSwitch(profile, sync)
        gateScreen = AppGateScreen.ProfileSwitching.name
    }

    LaunchedEffect(pendingProfileSwitch) {
        val request = pendingProfileSwitch ?: return@LaunchedEffect
        importOutcome = null
        runCatching {
            ProfileRepository.switchToProfile(request.profile.profileIndex)
            warmProfileBoundRepositories()
            if (request.syncOnEnter) {
                importOutcome = withContext(Dispatchers.Default) {
                    pullThenImportCrossFamilySettings(
                        profileId = request.profile.profileIndex,
                        pullAll = SyncManager::pullAllForProfileAndWait,
                        importSettings = CrossFamilySettingsImport::importIfArriving,
                    )
                }
            }
            arrivalPending = SetupProfileFlags.arrivalPending(request.profile.profileIndex)
            reviewSetupRequested = false
        }.onSuccess {
            pendingProfileSwitch = null
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            gateScreen = AppGateScreen.Main.name
        }.onFailure {
            pendingProfileSwitch = null
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    fun enterProfileGate(profiles: List<NuvioProfile>, syncOnEnter: Boolean) {
        profileSelectionLoading = false
        profileSelectionTransitionActive = false
        if (profiles.isEmpty()) {
            autoSkipProfileSelection = true
            gateScreen = AppGateScreen.ProfileSelection.name
            return
        }

        rememberedStartupProfile(profiles)?.let { profile ->
            requestProfileSwitch(profile, sync = syncOnEnter)
            return
        }

        autoSkipProfileSelection = true
        if (profiles.size == 1) {
            val onlyProfile = profiles.first()
            if (onlyProfile.pinEnabled) {
                gateScreen = AppGateScreen.ProfileSelection.name
                return
            }
            requestProfileSwitch(onlyProfile, sync = syncOnEnter)
        } else {
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    LaunchedEffect(gateScreen) {
        log.i { "[AppGate] gateScreen transitioned to: $gateScreen" }
        AppGateResume.onGateScreen(gateScreen)
    }

    // Nuvio Z: "Sign in" from a session running signed out on cached profiles. The `ShowAuth`
    // branch below, reached without the sign-out that wipes account data. Clearing the profiles in
    // memory (not on disk) is what keeps the transition below from walking straight back in.
    LaunchedEffect(ownsAppRuntime) {
        if (!ownsAppRuntime) return@LaunchedEffect
        com.nuvio.app.features.auth.SignInNavigation.requests.collect {
            if (AuthRepository.state.value !is AuthState.Unauthenticated) return@collect
            ProfileRepository.clearInMemory()
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            gateScreen = AppGateScreen.Auth.name
        }
    }

    // Nuvio Z: a login lost by itself - no stored session at launch, a refresh the server refused -
    // leaves the app on cached profiles looking signed in; only Social said otherwise, once opened.
    // Here, not in `MainAppContent`, because this gate owns the runtime on every platform (on iOS
    // the content instances do not). An explicit sign-out never gets here: it wipes the cached
    // profiles and the gate leaves `Main` for `Auth` inside the delay.
    val signedOutOnMain = authState is AuthState.Unauthenticated && gateScreen == AppGateScreen.Main.name
    LaunchedEffect(ownsAppRuntime, signedOutOnMain) {
        if (!ownsAppRuntime || !signedOutOnMain) return@LaunchedEffect
        delay(1_500)
        NuvioToastController.show(
            message = getString(Res.string.z_auth_signed_out_notice),
            durationMillis = 10_000L,
            actionLabel = getString(Res.string.compose_auth_sign_in),
            action = NuvioToastAction.SignIn,
        )
    }

    LaunchedEffect(authState, networkStatusUiState.condition, profileState.profiles) {
        val currentAuth = authState
        val cachedProfiles = profileState.profiles
        val authDesc = safeAuthStateDescription(currentAuth)
        log.i { "[AppGate] Evaluating transition: authState=$authDesc, gateScreen=$gateScreen, cachedProfiles=${cachedProfiles.size}" }

        val decision = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = gateScreen,
            authState = currentAuth,
            cachedProfiles = cachedProfiles,
            isOnline = networkStatusUiState.condition == NetworkCondition.Online,
        )

        when (decision) {
            is AppGateTransitionDecision.StayOnCurrent -> {
                if (currentAuth is AuthState.Authenticated) {
                    ProfileRepository.ensureLoaded(currentAuth.userId)
                }
            }
            is AppGateTransitionDecision.ShowLoading -> {
                gateScreen = AppGateScreen.Loading.name
            }
            is AppGateTransitionDecision.ShowAuth -> {
                ProfileRepository.clearInMemory()
                profileSelectionLoading = false
                profileSelectionTransitionActive = false
                gateScreen = AppGateScreen.Auth.name
            }
            is AppGateTransitionDecision.EnterProfileGate -> {
                if (currentAuth is AuthState.Authenticated) {
                    ProfileRepository.ensureLoaded(currentAuth.userId)
                    log.i { "[AppGate] Authenticated state transitioning gate away from $gateScreen to ProfileGate" }
                    enterProfileGate(ProfileRepository.state.value.profiles, syncOnEnter = decision.syncOnEnter)
                } else {
                    enterProfileGate(decision.profiles, syncOnEnter = decision.syncOnEnter)
                }
            }
        }
    }

    LaunchedEffect((authState as? AuthState.Authenticated)?.userId) {
        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        ProfileRepository.ensureLoaded(authenticatedState.userId)
        ProfileRepository.pullProfiles()
    }

    LaunchedEffect(
        gateScreen,
        autoSkipProfileSelection,
        profileState.profiles,
        profileState.hasEverSelectedProfile,
        profileState.rememberLastProfileEnabled,
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.pinEnabled,
    ) {
        if (
            autoSkipProfileSelection &&
            gateScreen == AppGateScreen.ProfileSelection.name
        ) {
            rememberedStartupProfile(profileState.profiles)?.let { profile ->
                requestProfileSwitch(profile, sync = true)
                return@LaunchedEffect
            }

            if (profileState.profiles.size != 1) return@LaunchedEffect

            val onlyProfile = profileState.profiles.first()
            if (onlyProfile.pinEnabled) return@LaunchedEffect

            requestProfileSwitch(onlyProfile, sync = true)
        }
    }

    val profileOverlayVisible =
        gateScreen == AppGateScreen.ProfileSelection.name || profileSelectionLoading
    val profileOverlayState = remember {
        MutableTransitionState(profileOverlayVisible)
    }
    profileOverlayState.targetState = profileOverlayVisible
    val launchOverlayVisible = appLaunchOverlayVisible(
        renderMainContent = renderMainContent,
        gateIsMain = gateScreen == AppGateScreen.Main.name,
        externalMainContentReady = externalMainContentReady,
        setupWizardGating = gatingRun != SetupWizardRun.None,
    )
    val launchOverlayState = remember {
        MutableTransitionState(launchOverlayVisible)
    }
    launchOverlayState.targetState = launchOverlayVisible

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        profileSelectionLoading,
        profileOverlayState.currentState,
        profileOverlayState.isIdle,
        launchOverlayState.currentState,
        launchOverlayState.isIdle,
        isSetupWizardActive,
        isWhatsNewActive,
        isAdvancedSetupActive,
        onAppReady,
    ) {
        if (renderMainContent) return@LaunchedEffect
        val overlaysHidden =
            profileOverlayState.isIdle &&
                !profileOverlayState.currentState &&
                launchOverlayState.isIdle &&
                !launchOverlayState.currentState
        val ready = gateScreen == AppGateScreen.Main.name &&
            externalMainContentReady &&
            !profileSelectionLoading &&
            overlaysHidden &&
            !isSetupWizardActive &&
            !isWhatsNewActive &&
            !isAdvancedSetupActive
        reportAppReady(ready)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                    .togetherWith(fadeOut(tween(250)))
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Loading.name,
                AppGateScreen.ProfileSwitching.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator()
                    }
                }
                AppGateScreen.Auth.name -> {
                    AuthScreen(modifier = Modifier.fillMaxSize())
                }
                AppGateScreen.ProfileSelection.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                    )
                }
                AppGateScreen.ProfileEdit.name -> {
                    PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileEdit.name) {
                        gateScreen = AppGateScreen.ProfileSelection.name
                    }
                    ProfileEditScreen(
                        profile = editingProfile,
                        onBack = { gateScreen = AppGateScreen.ProfileSelection.name },
                        onSaved = { gateScreen = AppGateScreen.ProfileSelection.name },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.Main.name -> if (gatingRun != SetupWizardRun.None) {
                    // The wizard replaced the standalone playback-mode selector, which used to
                    // stand here. Same reasoning as before: read at this one place rather than
                    // as a sixth AppGateScreen value, because five separate transitions set the
                    // gate to Main and wrapping the Main branch covers every one of them.
                    // Keyed on the run: a profile switch can change which one is owed, and the
                    // wizard captures its run's shape when it opens.
                    key(gatingRun) {
                        SetupWizardScreen(
                            // The profile revision needs nothing here: the wizard writes it through
                            // PlayerSettingsRepository, gatePlayerSettings collects that flow, and this
                            // branch re-evaluates. The device revision is not a flow, so it is re-read -
                            // from storage, which stays the one source of truth across a restart.
                            onFinished = {
                                deviceSetupEpoch++
                                arrivalPending = SetupProfileFlags.arrivalPending(ProfileRepository.activeProfileId)
                                // A whole Initial Setup has just asked this release's questions.
                                if (gatingRun == SetupWizardRun.Full) answerDebugOffer()
                                reviewSetupRequested = false
                            },
                            modifier = Modifier.fillMaxSize(),
                            run = gatingRun,
                            arrival = arrivalPending,
                            onReviewSetup = {
                                SetupProfileFlags.setArrivalPending(ProfileRepository.activeProfileId, false)
                                arrivalPending = false
                                reviewSetupRequested = true
                            },
                            onOpenAdvancedSetup = AdvancedSetupLauncher::open,
                            importRetry = if (
                                importOutcome == CrossFamilyImportOutcome.TransportFailure &&
                                gatingRun == SetupWizardRun.Full
                            ) {
                                suspend {
                                    importOutcome = runCatching {
                                        CrossFamilySettingsImport.importIfArriving(ProfileRepository.activeProfileId)
                                    }.getOrElse {
                                        CrossFamilyImportOutcome.TransportFailure
                                    }
                                    arrivalPending = SetupProfileFlags.arrivalPending(ProfileRepository.activeProfileId)
                                }
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    if (renderMainContent) {
                        MainAppContent(
                            onWhatsNewClick = { showWhatsNewOnDemand = true },
                            onRunSetupAgainClick = {
                                setupWizardOnDemandEpoch++
                                showSetupWizardOnDemand = true
                            },
                            initialTab = initialTab,
                            initialRoute = initialRoute,
                            useNativeNavigation = useNativeNavigation,
                            useNativeTabBar = useNativeTabBar,
                            useTabletFloatingTabBar = useTabletFloatingTabBar,
                            ownsAppRuntime = ownsAppRuntime,
                            showLaunchOverlay = !profileSelectionLoading,
                            onNavigate = onNavigate,
                            onGoBack = onGoBack,
                            onReplace = onReplace,
                            onActivate = onActivate,
                            onTabTitles = onTabTitles,
                            appGateController = appGateController,
                            onRootContentReady = { ready ->
                                if (ready) {
                                    profileSelectionLoading = false
                                }
                                onAppReady?.invoke(
                                    ready && gateScreen == AppGateScreen.Main.name,
                                )
                            },
                            onSwitchProfile = {
                                autoSkipProfileSelection = false
                                profileSelectionLoading = false
                                profileSelectionTransitionActive = false
                                skipProfileSelectionEnterAnimation = false
                                gateScreen = AppGateScreen.ProfileSelection.name
                            },
                        )
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = launchOverlayState,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppLaunchOverlay(
                profile = profileState.activeProfile ?: profileState.profiles.firstOrNull(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = profileOverlayState,
            enter = if (skipProfileSelectionEnterAnimation) {
                androidx.compose.animation.EnterTransition.None
            } else {
                fadeIn(tween(400))
            },
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(NuvioTokens.Z.dialog),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val onBack: (() -> Unit)? = if (!autoSkipProfileSelection) {
                    {
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.Main.name
                    }
                } else {
                    null
                }
                PlatformBackHandler(
                    enabled = gateScreen == AppGateScreen.ProfileSelection.name && !profileSelectionLoading,
                ) {
                    onBack?.invoke()
                }
                ProfileSelectionScreen(
                    onProfileSelected = { profile ->
                        if (
                            !profileSelectionLoading &&
                            (autoSkipProfileSelection || profile.profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex)
                        ) {
                            profileSelectionLoading = true
                            profileSelectionTransitionActive = true
                            skipProfileSelectionEnterAnimation = false
                            requestProfileSwitch(
                                profile = profile,
                                sync = authState is AuthState.Authenticated,
                            )
                            if (!renderMainContent) {
                                onActivate?.invoke(AppScreenTab.Home)
                            }
                        }
                    },
                    onEditProfile = { profile ->
                        editingProfile = profile
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    onAddProfile = {
                        editingProfile = null
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    interactionEnabled = !profileSelectionLoading,
                    onBack = onBack,
                    activeProfileIndex = if (autoSkipProfileSelection) null else profileState.activeProfile?.profileIndex,
                    contentVisible = !profileSelectionTransitionActive,
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = profileSelectionTransitionActive,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppLoadingContent(modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (showWhatsNew && gateScreen == AppGateScreen.Main.name && gatingRun == SetupWizardRun.None) {
            WhatsNewScreen(
                versionName = whatsNewIdentity.versionName,
                events = whatsNewEvents,
                debugSection = whatsNewDebug,
                // The post-update screen is the missed events; history is for Settings.
                history = emptyList(),
                showHistory = false,
                onContinue = acknowledgeWhatsNew,
                // "Try Advanced Setup": acknowledge exactly as Done would, close, open the hub.
                onAction = { action ->
                    acknowledgeWhatsNew()
                    when (action) {
                        ChangelogAction.ADVANCED_SETUP -> AdvancedSetupLauncher.open()
                        ChangelogAction.REVIEW_SETTINGS -> AdvancedSetupLauncher.review(advancedSetupAreasChangedIn(SETUP_WIZARD_REVISION))
                    }
                },
            )
        } else if (showWhatsNewOnDemand) {
            WhatsNewScreen(
                versionName = whatsNewIdentity.versionName,
                events = whatsNewEvents,
                history = whatsNewHistory,
                dismissible = true,
                onContinue = { showWhatsNewOnDemand = false },
                onAction = { action ->
                    showWhatsNewOnDemand = false
                    when (action) {
                        ChangelogAction.ADVANCED_SETUP -> AdvancedSetupLauncher.open()
                        ChangelogAction.REVIEW_SETTINGS -> AdvancedSetupLauncher.review(advancedSetupAreasChangedIn(SETUP_WIZARD_REVISION))
                    }
                },
            )
        }

        // Z: "First time on this device" (see `DeviceSetupOffer`), once What's New is out of the way.
        if (
            deviceSetupOffered && whatsNewDecided && gateScreen == AppGateScreen.Main.name &&
            !showWhatsNew && !showWhatsNewOnDemand && !showSetupWizardOnDemand && !isAdvancedSetupActive && !showDeviceSetup
        ) {
            LaunchedEffect(Unit) { deviceSetupOfferedThisLaunch = true }
            DeviceSetupOffer(
                onYes = { showDeviceSetup = true },
                onNo = declineDeviceSetup,
            )
        }

        // Z: "New settings available" (see `NewSettingsOffer`), once What's New is out of the way -
        // and never on a launch that offered Device Setup.
        if (
            (upgradeOffered || debugOffered) && whatsNewDecided && gateScreen == AppGateScreen.Main.name &&
            !showWhatsNew && !showWhatsNewOnDemand && !showSetupWizardOnDemand && !isAdvancedSetupActive &&
            !deviceSetupOffered && !deviceSetupOfferedThisLaunch && !showDeviceSetup
        ) {
            NewSettingsOffer(
                // The areas this profile owes, in Advanced Setup (2026-10-22) - not a wizard of their own.
                // Answering is opening them: the revision is written now, as leaving early always did.
                onYes = {
                    // A debug re-offer is for a profile already current: this release's areas.
                    val owed = if (upgradeOffered) {
                        advancedSetupAreasChangedSince(gatePlayerSettings.setupWizardCompletedRevision ?: 0)
                    } else {
                        advancedSetupAreasChangedIn(SETUP_WIZARD_REVISION)
                    }
                    PlayerSettingsRepository.markSetupWizardCompleted(SETUP_WIZARD_REVISION)
                    answerDebugOffer()
                    AdvancedSetupLauncher.review(owed)
                },
                onNo = {
                    PlayerSettingsRepository.markSetupWizardCompleted(SETUP_WIZARD_REVISION)
                    answerDebugOffer()
                },
                onBackToWhatsNew = {
                    if (whatsNewEvents.isNotEmpty() || whatsNewDebug != null) showWhatsNew = true else showWhatsNewOnDemand = true
                },
            )
        }

        // Opened from Settings rather than gating the app: it covers MainAppContent instead of
        // replacing it, and it is dismissible. Finishing still records the revision - a user
        // who walks the whole wizard has answered it, however they got there.
        if (showSetupWizardOnDemand && gateScreen == AppGateScreen.Main.name) {
            key(setupWizardOnDemandEpoch) {
                SetupWizardScreen(
                    onFinished = {
                        deviceSetupEpoch++
                        arrivalPending = SetupProfileFlags.arrivalPending(ProfileRepository.activeProfileId)
                        showSetupWizardOnDemand = false
                    },
                    dismissible = true,
                    onDismiss = { showSetupWizardOnDemand = false },
                    modifier = Modifier.fillMaxSize(),
                    onOpenAdvancedSetup = AdvancedSetupLauncher::open,
                )
            }
        }

        // Device Setup, from the pop-up or Settings: over the app and dismissible, like the re-run.
        // Leaving it early is "Not now" - the values set so far stand.
        if (showDeviceSetup && gateScreen == AppGateScreen.Main.name) {
            SetupWizardScreen(
                onFinished = {
                    deviceSetupEpoch++
                    showDeviceSetup = false
                },
                dismissible = true,
                onDismiss = {
                    declineDeviceSetup()
                    showDeviceSetup = false
                },
                modifier = Modifier.fillMaxSize(),
                run = SetupWizardRun.Device,
                onOpenAdvancedSetup = AdvancedSetupLauncher::open,
            )
        }

        // Last, so it sits above What's New: its action card opens the hub as it closes.
        if (isAdvancedSetupActive) {
            key(advancedSetupReview) {
                AdvancedSetupScreen(
                    onClose = {
                        advancedSetupRequested = false
                        advancedSetupReview = null
                    },
                    modifier = Modifier.fillMaxSize(),
                    review = advancedSetupReview,
                )
            }
        }
    }
}
