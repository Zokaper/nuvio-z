package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.AppDeepLinkRepository
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.RealtimeSyncConfig
import com.nuvio.app.core.sync.RealtimeSyncInvalidationService
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioContinueWatchingActionSheet
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterZoomActionOverlay
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.PosterZoomAnchorHolder
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.platformExitApp
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.ui.NuvioToastAction
import com.nuvio.app.core.ui.NuvioToastHost
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioFloatingPrompt
import com.nuvio.app.core.ui.ProfileMeshBackground
import com.nuvio.app.core.ui.TraktListPickerDialog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NativeNavigationTab
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.core.ui.localizedContinueWatchingSubtitle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.catalog.CatalogScreen
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryPlaybackResult
import com.nuvio.app.features.cloud.CloudLibraryPlaybackTargetLookupResult
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.cloud.providerPosterUrl
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.downloads.DownloadBatch
import com.nuvio.app.features.downloads.DownloadBatchEntry
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.features.downloads.DownloadsSettingsScreen
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.PersonDetailScreen
import com.nuvio.app.features.details.TmdbEntityBrowseScreen
import com.nuvio.app.features.tmdb.TmdbEntityKind
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.ExternalPlayerPlaybackRequest
import com.nuvio.app.features.player.rememberExternalPlayerLauncher
import com.nuvio.app.features.player.prepareExternalPlayerLaunch
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.profiles.parseHexColor
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.settings.HomescreenSettingsScreen
import com.nuvio.app.features.settings.MetaScreenSettingsScreen
import com.nuvio.app.features.settings.ContinueWatchingSettingsScreen
import com.nuvio.app.features.settings.AddonsSettingsScreen
import com.nuvio.app.features.settings.PluginsSettingsScreen
import com.nuvio.app.features.settings.AccountSettingsScreen
import com.nuvio.app.features.settings.SupportersContributorsSettingsScreen
import com.nuvio.app.features.settings.LicensesAttributionsSettingsScreen
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.collection.CollectionManagementScreen
import com.nuvio.app.features.collection.CollectionEditorScreen
import com.nuvio.app.features.collection.CollectionEditorRepository
import com.nuvio.app.features.collection.CollectionEditorPage
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.disposeCollectionEditorPage
import com.nuvio.app.features.collection.FolderDetailScreen
import com.nuvio.app.features.collection.FolderDetailRepository
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsScreen
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackModeRouter
import com.nuvio.app.features.playback.PlaybackProgress
import com.nuvio.app.features.playback.PlaybackProgressInputs
import com.nuvio.app.features.playback.PlaybackProgressOverlay
import com.nuvio.app.features.playback.StreamRouteSurface
import com.nuvio.app.features.playback.StreamRouteSurfaceInputs
import com.nuvio.app.features.playback.streamRouteSurface
import com.nuvio.app.features.playback.PlaybackModeSelectorScreen
import com.nuvio.app.features.playback.PlaybackQualityOption
import com.nuvio.app.features.playback.PlaybackQualityOptions
import com.nuvio.app.features.playback.PlaybackQualitySheet
import com.nuvio.app.features.playback.PlaybackRouteDecision
import com.nuvio.app.features.playback.PlaybackRouteInputs
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSelectionResult
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.PlaybackSourceSelector
import com.nuvio.app.features.playback.qualityLabel
import com.nuvio.app.features.playback.StickySourcePin
import com.nuvio.app.core.network.MeteredPlaybackChoice
import com.nuvio.app.core.network.NetworkConnectionType
import com.nuvio.app.core.network.NetworkQualityRepository
import com.nuvio.app.core.network.NetworkStrengthProbe
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktListTab
import com.nuvio.app.features.trakt.TraktScrobbleRepository
import com.nuvio.app.features.updater.AppUpdaterHost
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.rememberAppUpdaterController
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.updater.AppReleaseNotes
import com.nuvio.app.features.updater.fetchRecentReleaseNotes
import com.nuvio.app.features.whatsnew.CurrentReleaseNotes
import com.nuvio.app.features.whatsnew.WhatsNewScreen
import com.nuvio.app.features.whatsnew.WhatsNewStorage
import com.nuvio.app.features.whatsnew.shouldShowWhatsNew
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import com.nuvio.app.navigation.*
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.app_logo_wordmark
import nuvio.composeapp.generated.resources.compose_catalog_subtitle_library
import nuvio.composeapp.generated.resources.compose_catalog_subtitle_trakt_library
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val navigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(TabsRoute::class, TabsRoute.serializer())
            subclass(DetailRoute::class, DetailRoute.serializer())
            subclass(PersonDetailRoute::class, PersonDetailRoute.serializer())
            subclass(EntityBrowseRoute::class, EntityBrowseRoute.serializer())
            subclass(SettingsPageRoute::class, SettingsPageRoute.serializer())
            subclass(HomescreenSettingsRoute::class, HomescreenSettingsRoute.serializer())
            subclass(MetaScreenSettingsRoute::class, MetaScreenSettingsRoute.serializer())
            subclass(ContinueWatchingSettingsRoute::class, ContinueWatchingSettingsRoute.serializer())
            subclass(DownloadsSettingsRoute::class, DownloadsSettingsRoute.serializer())
            subclass(DownloadShowRoute::class, DownloadShowRoute.serializer())
            subclass(AddonsSettingsRoute::class, AddonsSettingsRoute.serializer())
            subclass(PluginsSettingsRoute::class, PluginsSettingsRoute.serializer())
            subclass(AccountSettingsRoute::class, AccountSettingsRoute.serializer())
            subclass(SupportersContributorsSettingsRoute::class, SupportersContributorsSettingsRoute.serializer())
            subclass(LicensesAttributionsSettingsRoute::class, LicensesAttributionsSettingsRoute.serializer())
            subclass(CollectionsRoute::class, CollectionsRoute.serializer())
            subclass(CollectionEditorRoute::class, CollectionEditorRoute.serializer())
            subclass(CollectionEditorPageRoute::class, CollectionEditorPageRoute.serializer())
            subclass(FolderDetailRoute::class, FolderDetailRoute.serializer())
            subclass(StreamRoute::class, StreamRoute.serializer())
            subclass(CatalogRoute::class, CatalogRoute.serializer())
            subclass(PlayerRoute::class, PlayerRoute.serializer())
        }
    }
}

private data class PendingP2pStreamOpen(
    val stream: StreamItem,
    val resumePositionMs: Long?,
    val resumeProgressFraction: Float?,
    val forceExternal: Boolean,
    val forceInternal: Boolean,
    val isAutoPlay: Boolean,
)

private data class PendingStickyStreamOpen(
    val stream: StreamItem,
    val resumePositionMs: Long?,
    val resumeProgressFraction: Float?,
)

private data class CatalogLaunch(
    val title: String,
    val subtitle: String,
    val target: CatalogTarget,
)

private object CatalogLaunchStore {
    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, CatalogLaunch>()

    fun put(launch: CatalogLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = launch
        return launchId
    }

    fun get(launchId: Long): CatalogLaunch? = launches[launchId]

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }
}

/** Idempotent cleanup used by both Navigation 3 and SwiftUI interactive-pop handling. */
fun disposeRoute(route: AppRoute) {
    when (route) {
        is StreamRoute -> {
            StreamsRepository.clear()
            StreamLaunchStore.remove(route.launchId)
        }

        is PlayerRoute -> {
            ResumePromptRepository.markPlayerExitedNormally()
            PlayerLaunchStore.remove(route.launchId)
        }

        is CatalogRoute -> {
            CatalogRepository.clear()
            CatalogLaunchStore.remove(route.launchId)
        }

        is CollectionEditorRoute -> CollectionEditorRepository.clear()
        is CollectionEditorPageRoute -> {
            runCatching { CollectionEditorPage.valueOf(route.pageName) }
                .getOrNull()
                ?.let(::disposeCollectionEditorPage)
        }
        is FolderDetailRoute -> FolderDetailRepository.clear()
        else -> Unit
    }
}

private data class PosterActionTarget(
    val preview: MetaPreview,
    val libraryItem: LibraryItem? = null,
    val libraryListKey: String? = null,
)

enum class AppScreenTab {
    Home,
    Search,
    Library,
    Downloads,
    Settings,
    ;

    companion object {
        fun fromName(name: String): AppScreenTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

private fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Search -> NativeNavigationTab.Search
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.Downloads -> NativeNavigationTab.Downloads
    AppScreenTab.Settings -> NativeNavigationTab.Settings
}

private fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.Downloads -> AppScreenTab.Downloads
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}

private fun PlayerLaunch.toExternalPlayerPlaybackRequest(): ExternalPlayerPlaybackRequest =
    ExternalPlayerPlaybackRequest(
        sourceUrl = sourceUrl,
        title = title,
        streamTitle = streamTitle,
        sourceHeaders = sourceHeaders,
        resumePositionMs = initialPositionMs,
        season = seasonNumber,
        episode = episodeNumber,
        episodeTitle = episodeTitle,
    )

/**
 * Carries the stream route's decision across the route leaving composition.
 *
 * Key and reason are saved separately rather than the decision being re-derived, because the
 * inputs genuinely change while the player is open - a play writes a reuse-last-link entry, so
 * re-running the router on the way back answers `ReuseLastLink` where it first answered
 * `AutoPick`, and Instant's failure chain is gated on that answer.
 *
 * The saver lives here rather than beside `PlaybackRouteDecision` on purpose:
 * `PlaybackModeRouter.kt` has no imports at all, which is what lets it be compiled and run
 * outside Gradle per `AGENTS.md`. A Compose import there would end that.
 */
private val PlaybackRouteDecisionSaver: Saver<PlaybackRouteDecision?, Any> = listSaver(
    save = { decision -> decision?.let { listOf(it.key, it.reason) } ?: emptyList() },
    restore = { saved ->
        PlaybackRouteDecision.fromKey(
            key = saved.getOrNull(0),
            reason = saved.getOrNull(1).orEmpty(),
        )
    },
)

private enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileEdit,
    Main,
}

private object NativeAppGateRequests {
    val profileSelection = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestProfileSelection() {
        profileSelection.tryEmit(Unit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App(
    initialTab: AppScreenTab = AppScreenTab.Home,
    initialRoute: AppRoute = TabsRoute,
    useNativeNavigation: Boolean = false,
    useNativeTabBar: Boolean = false,
    useTabletFloatingTabBar: Boolean = false,
    ownsAppRuntime: Boolean = true,
    bypassAppGate: Boolean = false,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    onGoBack: (() -> Unit)? = null,
    onReplace: ((AppRoute) -> Unit)? = null,
    onActivate: ((AppScreenTab) -> Unit)? = null,
    onAppReady: ((Boolean) -> Unit)? = null,
    onTabTitles: ((home: String, search: String, library: String, downloads: String, profile: String, switchProfile: String, addProfile: String) -> Unit)? = null,
    nativeProfileSwitcherController: NativeProfileSwitcherController? = null,
) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(SvgDecoder.Factory())
            }
            .configurePlatformImageLoader()
            .build()
    }
    val selectedTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
    NuvioTheme(appTheme = selectedTheme, amoled = amoledEnabled) {
        if (bypassAppGate) {
            MainAppContent(
                initialTab = initialTab,
                initialRoute = initialRoute,
                useNativeNavigation = useNativeNavigation,
                useNativeTabBar = useNativeTabBar,
                useTabletFloatingTabBar = useTabletFloatingTabBar,
                ownsAppRuntime = false,
                onNavigate = onNavigate,
                onGoBack = onGoBack,
                onReplace = onReplace,
                onActivate = onActivate,
                onTabTitles = onTabTitles,
                nativeProfileSwitcherController = nativeProfileSwitcherController,
                onSwitchProfile = {
                    onActivate?.invoke(AppScreenTab.Home)
                    NativeAppGateRequests.requestProfileSelection()
                },
            )
            return@NuvioTheme
        }

        LaunchedEffect(Unit) {
            if (!ownsAppRuntime) return@LaunchedEffect
            AuthRepository.initialize()
        }

        LaunchedEffect(Unit) {
            if (!ownsAppRuntime) return@LaunchedEffect
            NetworkStatusRepository.ensureStarted()
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

        // Gates the first-launch playback-mode selector. Read here rather than as a new
        // AppGateScreen value because five separate transitions set the gate to Main;
        // wrapping the Main branch covers every one of them with a single decision.
        val gatePlayerSettings by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        var gateScreen by rememberSaveable { mutableStateOf(AppGateScreen.Loading.name) }
        var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
        var isNewProfile by remember { mutableStateOf(false) }
        var autoSkipProfileSelection by rememberSaveable { mutableStateOf(false) }
        val whatsNewSections = remember {
            CurrentReleaseNotes.sections(isDesktop = WhatsNewStorage.isDesktop)
        }
        var showWhatsNew by remember { mutableStateOf(false) }
        // Opened from Settings rather than shown after an update: dismissible, and it must not
        // record the version as seen or the post-update showing would be skipped.
        var showWhatsNewOnDemand by remember { mutableStateOf(false) }
        // null while loading, empty when it could not be fetched. Either way the curated
        // sections still render - this screen has to work offline and on builds where the
        // in-app updater is disabled.
        var whatsNewHistory by remember { mutableStateOf<List<AppReleaseNotes>?>(null) }

        LaunchedEffect(ownsAppRuntime) {
            if (!ownsAppRuntime) return@LaunchedEffect
            showWhatsNew = shouldShowWhatsNew(
                lastSeenVersion = WhatsNewStorage.loadLastSeenVersion(),
                currentVersion = AppVersionConfig.VERSION_NAME,
                sections = whatsNewSections,
            )
        }

        LaunchedEffect(showWhatsNew, showWhatsNewOnDemand) {
            if (!showWhatsNew && !showWhatsNewOnDemand) return@LaunchedEffect
            if (whatsNewHistory != null) return@LaunchedEffect
            whatsNewHistory = fetchRecentReleaseNotes()
                .getOrNull()
                ?.filter { it.tag.trimStart('v', 'V') != AppVersionConfig.VERSION_NAME }
                ?: emptyList()
        }

        LaunchedEffect(gateScreen, onAppReady) {
            if (gateScreen != AppGateScreen.Main.name) {
                onAppReady?.invoke(false)
            }
        }

        LaunchedEffect(useNativeNavigation, ownsAppRuntime) {
            if (!useNativeNavigation || !ownsAppRuntime) return@LaunchedEffect
            NativeAppGateRequests.profileSelection.collect {
                autoSkipProfileSelection = false
                gateScreen = AppGateScreen.ProfileSelection.name
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

        fun enterProfileGate(profiles: List<NuvioProfile>, syncOnEnter: Boolean) {
            if (profiles.isEmpty()) {
                autoSkipProfileSelection = true
                gateScreen = AppGateScreen.ProfileSelection.name
                return
            }

            rememberedStartupProfile(profiles)?.let { profile ->
                ProfileRepository.selectProfile(profile.profileIndex)
                if (syncOnEnter) {
                    SyncManager.pullAllForProfile(profile.profileIndex)
                }
                gateScreen = AppGateScreen.Main.name
                autoSkipProfileSelection = false
                return
            }

            autoSkipProfileSelection = true
            if (profiles.size == 1) {
                val onlyProfile = profiles.first()
                if (onlyProfile.pinEnabled) {
                    gateScreen = AppGateScreen.ProfileSelection.name
                    return
                }
                ProfileRepository.selectProfile(onlyProfile.profileIndex)
                if (syncOnEnter) {
                    SyncManager.pullAllForProfile(onlyProfile.profileIndex)
                }
                gateScreen = AppGateScreen.Main.name
                autoSkipProfileSelection = false
            } else {
                gateScreen = AppGateScreen.ProfileSelection.name
            }
        }

        LaunchedEffect(authState, networkStatusUiState.condition, profileState.profiles) {
            val cachedProfiles = profileState.profiles
            val hasCachedProfileAccess =
                cachedProfiles.isNotEmpty() &&
                    authState !is AuthState.Authenticated
            val allowCachedProfileAccess =
                hasCachedProfileAccess &&
                    (
                        networkStatusUiState.condition != NetworkCondition.Online ||
                            gateScreen != AppGateScreen.Auth.name
                    )

            when (authState) {
                is AuthState.Loading -> {
                    if (hasCachedProfileAccess) {
                        enterProfileGate(cachedProfiles, syncOnEnter = false)
                    } else {
                        gateScreen = AppGateScreen.Loading.name
                    }
                }
                is AuthState.Unauthenticated -> {
                    if (allowCachedProfileAccess) {
                        enterProfileGate(cachedProfiles, syncOnEnter = false)
                    } else {
                        ProfileRepository.clearInMemory()
                        gateScreen = AppGateScreen.Auth.name
                    }
                }
                is AuthState.Authenticated -> {
                    val authenticatedState = authState as AuthState.Authenticated
                    ProfileRepository.ensureLoaded(authenticatedState.userId)
                    if (gateScreen == AppGateScreen.Loading.name || gateScreen == AppGateScreen.Auth.name) {
                        enterProfileGate(ProfileRepository.state.value.profiles, syncOnEnter = true)
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
                    ProfileRepository.selectProfile(profile.profileIndex)
                    SyncManager.pullAllForProfile(profile.profileIndex)
                    gateScreen = AppGateScreen.Main.name
                    autoSkipProfileSelection = false
                    return@LaunchedEffect
                }

                if (profileState.profiles.size != 1) return@LaunchedEffect

                val onlyProfile = profileState.profiles.first()
                if (onlyProfile.pinEnabled) return@LaunchedEffect

                ProfileRepository.selectProfile(onlyProfile.profileIndex)
                SyncManager.pullAllForProfile(onlyProfile.profileIndex)
                gateScreen = AppGateScreen.Main.name
                autoSkipProfileSelection = false
            }
        }

        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                    .togetherWith(fadeOut(tween(250)))
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Loading.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator(color = MaterialTheme.nuvio.colors.accent)
                    }
                }
                AppGateScreen.Auth.name -> {
                    AuthScreen(modifier = Modifier.fillMaxSize())
                }
                AppGateScreen.ProfileSelection.name -> {
                    PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileSelection.name) {
                        if (!autoSkipProfileSelection) {
                            gateScreen = AppGateScreen.Main.name
                        }
                    }
                    ProfileSelectionScreen(
                        onProfileSelected = { profile ->
                            ProfileRepository.selectProfile(profile.profileIndex)
                            if (authState is AuthState.Authenticated) {
                                SyncManager.pullAllForProfile(profile.profileIndex)
                            }
                            gateScreen = AppGateScreen.Main.name
                        },
                        onEditProfile = { profile ->
                            editingProfile = profile
                            isNewProfile = false
                            gateScreen = AppGateScreen.ProfileEdit.name
                        },
                        onAddProfile = {
                            editingProfile = null
                            isNewProfile = true
                            gateScreen = AppGateScreen.ProfileEdit.name
                        },
                        modifier = Modifier.fillMaxSize(),
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
                AppGateScreen.Main.name -> if (!gatePlayerSettings.playbackModeSelectorSeen) {
                    PlaybackModeSelectorScreen(
                        initialMode = gatePlayerSettings.playbackMode,
                        onConfirm = { mode ->
                            // Both, always: choosing Classic is a no-op for the mode, so
                            // the seen flag is the only thing that dismisses the selector.
                            PlayerSettingsRepository.setPlaybackMode(mode)
                            PlayerSettingsRepository.markPlaybackModeSelectorSeen()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MainAppContent(
                        onWhatsNewClick = { showWhatsNewOnDemand = true },
                        initialTab = initialTab,
                        initialRoute = initialRoute,
                        useNativeNavigation = useNativeNavigation,
                        useNativeTabBar = useNativeTabBar,
                        useTabletFloatingTabBar = useTabletFloatingTabBar,
                        ownsAppRuntime = ownsAppRuntime,
                        onNavigate = onNavigate,
                        onGoBack = onGoBack,
                        onReplace = onReplace,
                        onActivate = onActivate,
                        onTabTitles = onTabTitles,
                        nativeProfileSwitcherController = nativeProfileSwitcherController,
                        onRootContentReady = { ready ->
                            onAppReady?.invoke(
                                ready && gateScreen == AppGateScreen.Main.name,
                            )
                        },
                        onSwitchProfile = {
                            autoSkipProfileSelection = false
                            gateScreen = AppGateScreen.ProfileSelection.name
                        },
                    )
                }
            }
        }

        if (showWhatsNew && gateScreen == AppGateScreen.Main.name) {
            WhatsNewScreen(
                versionName = AppVersionConfig.VERSION_NAME,
                sections = whatsNewSections,
                history = whatsNewHistory,
                onContinue = {
                    WhatsNewStorage.saveLastSeenVersion(AppVersionConfig.VERSION_NAME)
                    showWhatsNew = false
                },
            )
        } else if (showWhatsNewOnDemand) {
            WhatsNewScreen(
                versionName = AppVersionConfig.VERSION_NAME,
                sections = whatsNewSections,
                history = whatsNewHistory,
                dismissible = true,
                onContinue = { showWhatsNewOnDemand = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun MainAppContent(
    // Hoisted rather than owned here: the post-update showing lives in App(), and one flag for
    // both keeps "opened from Settings" from recording the version as seen. Null on the
    // bypass-gate path, which renders no What's New dialog - a row that opened nothing would
    // be worse than no row.
    onWhatsNewClick: (() -> Unit)? = null,
    initialTab: AppScreenTab = AppScreenTab.Home,
    initialRoute: AppRoute = TabsRoute,
    useNativeNavigation: Boolean = false,
    useNativeTabBar: Boolean = false,
    useTabletFloatingTabBar: Boolean = false,
    ownsAppRuntime: Boolean = true,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    onGoBack: (() -> Unit)? = null,
    onReplace: ((AppRoute) -> Unit)? = null,
    onActivate: ((AppScreenTab) -> Unit)? = null,
    onTabTitles: ((home: String, search: String, library: String, downloads: String, profile: String, switchProfile: String, addProfile: String) -> Unit)? = null,
    nativeProfileSwitcherController: NativeProfileSwitcherController? = null,
    onRootContentReady: ((Boolean) -> Unit)? = null,
    onSwitchProfile: () -> Unit = {},
) {
        val navBackStack = rememberNavBackStack(navigationSavedStateConfiguration, initialRoute)
        val routeDisposalDecorator = remember {
            RouteDisposalNavEntryDecorator<NavKey> { key ->
                if (key is AppRoute) disposeRoute(key)
            }
        }
        val navController = remember(navBackStack, onNavigate, onGoBack, onReplace) {
            NuvioNavigator(
                backStack = navBackStack,
                onExternalNavigate = onNavigate,
                onExternalBack = onGoBack,
                onExternalReplace = onReplace,
            )
        }
        val appUpdaterController = rememberAppUpdaterController()
        if (ownsAppRuntime) {
            remember {
                EpisodeReleaseNotificationsRepository.ensureLoaded()
            }
            remember {
                CollectionSyncService.startObserving()
            }
            remember {
                ProfileSettingsSync.startObserving()
            }
        }
        val hapticFeedback = LocalHapticFeedback.current
        val focusManager = LocalFocusManager.current
        val uriHandler = LocalUriHandler.current
        val coroutineScope = rememberCoroutineScope()
        var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
        var searchFocusRequestCount by remember { mutableStateOf(0) }
        val homeScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val searchScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val libraryScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val downloadsScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val settingsRootActionRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val currentRoute = navBackStack.lastOrNull() as? AppRoute
        val liquidGlassNativeTabBarEnabled by remember {
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled
        }.collectAsStateWithLifecycle()
        val liquidGlassNativeTabBarSupported = remember { isLiquidGlassNativeTabBarSupported() }
        var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
        var selectedPosterActionTarget by remember { mutableStateOf<PosterActionTarget?>(null) }
        var selectedPosterAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
        val posterOverlayHazeState = rememberHazeState()
        var selectedContinueWatchingForActions by remember { mutableStateOf<ContinueWatchingItem?>(null) }
        var selectedContinueWatchingZoomAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
        var requestedSettingsPageName by rememberSaveable { mutableStateOf<String?>(null) }
        var showLibraryListPicker by remember { mutableStateOf(false) }
        var pickerItem by remember { mutableStateOf<LibraryItem?>(null) }
        var pickerTitle by remember { mutableStateOf("") }
        var pickerTabs by remember { mutableStateOf<List<TraktListTab>>(emptyList()) }
        var pickerMembership by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        var pickerPending by remember { mutableStateOf(false) }
        var pickerError by remember { mutableStateOf<String?>(null) }
        val addonsUiState by remember {
            AddonRepository.initialize()
            AddonRepository.uiState
        }.collectAsStateWithLifecycle()
        val libraryUiState by remember {
            LibraryRepository.ensureLoaded()
            LibraryRepository.uiState
        }.collectAsStateWithLifecycle()
        val authState by AuthRepository.state.collectAsStateWithLifecycle()
        val openPosterActions: (PosterActionTarget) -> Unit = { target ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            focusManager.clearFocus(force = true)
            selectedPosterAnchor = PosterZoomAnchorHolder.consume()
            coroutineScope.launch {
                withFrameNanos { }
                selectedPosterActionTarget = target
            }
        }
        val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
        val launchOverlayProfileColor = remember(profileState.activeProfile, profileState.profiles) {
            val sourceProfile = profileState.activeProfile ?: profileState.profiles.firstOrNull()
            sourceProfile?.avatarColorHex?.let(::parseHexColor) ?: Color(0xFF1E88E5)
        }
    val playerSettingsUiState by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pSettingsUiState by remember {
        P2pSettingsRepository.ensureLoaded()
        P2pSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloadsUiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val networkStatusUiState by remember {
        NetworkStatusRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloadedProviderLabel = stringResource(Res.string.provider_downloaded)
    val externalPlayerNotConfiguredText = stringResource(Res.string.external_player_not_configured)
    val externalPlayerUnavailableText = stringResource(Res.string.external_player_unavailable)
    val externalPlayerFailedText = stringResource(Res.string.external_player_failed)
    val failedOpenBrowserText = stringResource(Res.string.settings_trakt_failed_open_browser)
    val cloudLibraryPlayFailedText = stringResource(Res.string.cloud_library_play_failed)
    val cloudLibraryPlayDisabledText = stringResource(Res.string.cloud_library_play_disabled)
    val cloudLibraryPlayNotConnectedText = stringResource(Res.string.cloud_library_play_not_connected)
    val nativeTabHomeTitle = stringResource(Res.string.compose_nav_home)
    val nativeTabSearchTitle = stringResource(Res.string.compose_nav_search)
    val nativeTabLibraryTitle = stringResource(Res.string.compose_nav_library)
    val nativeTabDownloadsTitle = stringResource(Res.string.compose_nav_downloads)
    val nativeTabProfileTitle = stringResource(Res.string.compose_nav_profile)
    val nativeSwitchProfileTitle = stringResource(Res.string.compose_settings_root_switch_profile_title)
    val nativeAddProfileTitle = stringResource(Res.string.compose_profile_add_profile)
    val homescreenSettingsTitle = stringResource(Res.string.compose_settings_page_homescreen)
    val metaScreenSettingsTitle = stringResource(Res.string.compose_settings_page_meta_screen)
    val continueWatchingSettingsTitle = stringResource(Res.string.compose_settings_page_continue_watching)
    val debridSettingsTitle = stringResource(Res.string.compose_settings_page_debrid)
    val downloadsSettingsTitle = stringResource(Res.string.downloads_settings_title)
    val addonsSettingsTitle = stringResource(Res.string.compose_settings_page_addons)
    val pluginsSettingsTitle = stringResource(Res.string.compose_settings_page_plugins)
    val accountSettingsTitle = stringResource(Res.string.compose_settings_page_account)
    val supportersSettingsTitle = stringResource(Res.string.compose_settings_page_supporters_contributors)
    val licensesSettingsTitle = stringResource(Res.string.compose_settings_page_licenses_attributions)
    val collectionsTitle = stringResource(Res.string.collections_header)
    val newCollectionTitle = stringResource(Res.string.collections_new)
    val detailsFallbackTitle = stringResource(Res.string.meta_section_details_title)
    val isTraktLibrarySource = libraryUiState.sourceMode == LibrarySourceMode.TRAKT
    var initialHomeReady by rememberSaveable(ownsAppRuntime) {
        mutableStateOf(!ownsAppRuntime)
    }
    var offlineLaunchRouteHandled by rememberSaveable { mutableStateOf(false) }
    var networkToastBaselineReady by rememberSaveable { mutableStateOf(false) }
    var lastNetworkToastCondition by rememberSaveable { mutableStateOf(NetworkCondition.Unknown.name) }
    var watchSourceReconnectPending by remember { mutableStateOf(false) }

    fun activateTab(tab: AppScreenTab) {
        if (useNativeNavigation && onActivate != null) {
            onActivate(tab)
        } else {
            selectedTab = tab
        }
    }

    /**
     * Brings the Downloads tab to the front, from wherever the user currently is.
     *
     * Selecting the tab is not enough on its own while a pushed route - the details
     * screen a download is normally started from - is covering the tabs, so the
     * Compose stack is unwound back to [TabsRoute] first. Native navigation owns its
     * own stacks and switches to the Downloads one by itself.
     */
    fun openDownloadsTab() {
        activateTab(AppScreenTab.Downloads)
        if (!useNativeNavigation && navController.currentRoute !is TabsRoute) {
            navController.navigate(TabsRoute) {
                popUpTo<TabsRoute>()
                launchSingleTop = true
            }
        }
    }

    fun handleRootTabClick(tab: AppScreenTab) {
        if (selectedTab != tab) {
            activateTab(tab)
            return
        }

        when (tab) {
            AppScreenTab.Home -> homeScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Search -> {
                searchFocusRequestCount++
                searchScrollToTopRequests.tryEmit(Unit)
            }
            AppScreenTab.Library -> libraryScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Downloads -> downloadsScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Settings -> settingsRootActionRequests.tryEmit(Unit)
        }
    }

    LaunchedEffect(
        liquidGlassNativeTabBarSupported,
        liquidGlassNativeTabBarEnabled,
        useNativeNavigation,
        currentRoute,
        selectedTab,
    ) {
        NativeTabBridge.requestedTabs.collectLatest { requestedTab ->
            val requestedAppTab = requestedTab.toAppScreenTab()
            if (
                useNativeNavigation &&
                currentRoute is TabsRoute &&
                requestedAppTab == selectedTab
            ) {
                handleRootTabClick(requestedAppTab)
            } else if (
                !useNativeNavigation &&
                liquidGlassNativeTabBarSupported &&
                liquidGlassNativeTabBarEnabled
            ) {
                handleRootTabClick(requestedAppTab)
            }
        }
    }

    LaunchedEffect(
        nativeTabHomeTitle,
        nativeTabSearchTitle,
        nativeTabLibraryTitle,
        nativeTabDownloadsTitle,
        nativeTabProfileTitle,
        nativeSwitchProfileTitle,
        nativeAddProfileTitle,
        onTabTitles,
    ) {
        NativeTabBridge.publishTabTitles(
            home = nativeTabHomeTitle,
            search = nativeTabSearchTitle,
            library = nativeTabLibraryTitle,
            downloads = nativeTabDownloadsTitle,
            profile = nativeTabProfileTitle,
        )
        onTabTitles?.invoke(
            nativeTabHomeTitle,
            nativeTabSearchTitle,
            nativeTabLibraryTitle,
            nativeTabDownloadsTitle,
            nativeTabProfileTitle,
            nativeSwitchProfileTitle,
            nativeAddProfileTitle,
        )
    }

    LaunchedEffect(selectedTab) {
        NativeTabBridge.publishSelectedTab(selectedTab.toNativeNavigationTab())
        if (selectedTab != AppScreenTab.Search) {
            searchFocusRequestCount = 0
        }
    }

    var profileSwitchLoading by remember { mutableStateOf(false) }

    LaunchedEffect(nativeProfileSwitcherController, ownsAppRuntime) {
        if (!ownsAppRuntime) return@LaunchedEffect
        nativeProfileSwitcherController?.selectedProfileIndices?.collectLatest { profileIndex ->
            val profile = ProfileRepository.state.value.profiles
                .firstOrNull { it.profileIndex == profileIndex }
                ?: return@collectLatest
            profileSwitchLoading = true
            activateTab(AppScreenTab.Home)
            ProfileRepository.selectProfile(profile.profileIndex)
            SyncManager.pullAllForProfile(profile.profileIndex)
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, ownsAppRuntime, onSwitchProfile) {
        if (!ownsAppRuntime) return@LaunchedEffect
        nativeProfileSwitcherController?.requestedManageProfiles?.collectLatest {
            activateTab(AppScreenTab.Home)
            onSwitchProfile()
        }
    }
    val launchOverlayState = remember(ownsAppRuntime) {
        MutableTransitionState(
            ownsAppRuntime && (!initialHomeReady || profileSwitchLoading),
        )
    }
    launchOverlayState.targetState =
        ownsAppRuntime && (!initialHomeReady || profileSwitchLoading)

    LaunchedEffect(
        launchOverlayState.targetState,
        ownsAppRuntime,
        onRootContentReady,
    ) {
        if (ownsAppRuntime) {
            onRootContentReady?.invoke(!launchOverlayState.targetState)
        }
    }

    LaunchedEffect(
        currentRoute,
        liquidGlassNativeTabBarSupported,
        liquidGlassNativeTabBarEnabled,
        initialHomeReady,
        profileSwitchLoading,
        useNativeNavigation,
    ) {
        val visible = !useNativeNavigation &&
            liquidGlassNativeTabBarSupported &&
            liquidGlassNativeTabBarEnabled &&
            initialHomeReady &&
            !profileSwitchLoading &&
            currentRoute is TabsRoute
        NativeTabBridge.publishTabBarVisible(visible)
    }

    DisposableEffect(Unit) {
        onDispose {
            NativeTabBridge.publishTabBarVisible(false)
        }
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        NetworkStatusRepository.ensureStarted()
        EpisodeReleaseNotificationsRepository.refreshAsync()
        kotlinx.coroutines.delay(5_000)
        initialHomeReady = true
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        AppForegroundMonitor.events().collect {
            NetworkStatusRepository.requestForegroundRefresh()
            DeviceSessionRegistration.registerIfAuthenticated()
        }
    }

    LaunchedEffect(networkStatusUiState.condition) {
        if (!ownsAppRuntime) return@LaunchedEffect
        val condition = networkStatusUiState.condition
        if (!networkToastBaselineReady) {
            networkToastBaselineReady = true
            lastNetworkToastCondition = condition.name
            return@LaunchedEffect
        }

        val previousConditionName = lastNetworkToastCondition
        if (previousConditionName == condition.name) return@LaunchedEffect

        when (condition) {
            NetworkCondition.NoInternet -> {
                NuvioToastController.show(getString(Res.string.network_no_internet_connection))
            }

            NetworkCondition.ServersUnreachable -> {
                NuvioToastController.show(getString(Res.string.network_cannot_reach_servers))
            }

            NetworkCondition.Online -> {
                if (
                    previousConditionName == NetworkCondition.NoInternet.name ||
                    previousConditionName == NetworkCondition.ServersUnreachable.name
                ) {
                    NuvioToastController.show(getString(Res.string.network_back_online))
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }

        lastNetworkToastCondition = condition.name
    }

    LaunchedEffect(
        networkStatusUiState.condition,
        (authState as? AuthState.Authenticated)?.userId,
        profileState.activeProfile?.profileIndex,
    ) {
        if (!ownsAppRuntime) return@LaunchedEffect
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> watchSourceReconnectPending = true

            NetworkCondition.Online -> {
                if (!watchSourceReconnectPending) return@LaunchedEffect

                val profileId = profileState.activeProfile?.profileIndex
                    ?: ProfileRepository.activeProfileId
                val authenticatedState = authState as? AuthState.Authenticated
                if (authenticatedState != null && !authenticatedState.isAnonymous) {
                    SyncManager.requestForegroundPull(profileId = profileId, force = true)
                    watchSourceReconnectPending = false
                } else {
                    val result = WatchProgressSourceCoordinator.refreshActiveSource(
                        profileId = profileId,
                        force = true,
                    )
                    if (result.succeeded) {
                        watchSourceReconnectPending = false
                    }
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    LaunchedEffect(
        initialHomeReady,
        offlineLaunchRouteHandled,
        networkStatusUiState.condition,
        downloadsUiState.completedItems,
    ) {
        if (!ownsAppRuntime) return@LaunchedEffect
        if (!initialHomeReady || offlineLaunchRouteHandled) return@LaunchedEffect

        when (networkStatusUiState.condition) {
            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> return@LaunchedEffect

            NetworkCondition.Online -> {
                offlineLaunchRouteHandled = true
            }

            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                offlineLaunchRouteHandled = true
                val hasPlayableDownload = downloadsUiState.completedItems.any {
                    DownloadsRepository.playableLocalFileUri(it) != null
                }
                if (hasPlayableDownload) {
                    activateTab(AppScreenTab.Settings)
                    navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle)) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    LaunchedEffect(authState, profileState.activeProfile?.profileIndex) {
        if (!ownsAppRuntime) return@LaunchedEffect
        if (!RealtimeSyncConfig.ENABLED) {
            RealtimeSyncInvalidationService.stop()
            return@LaunchedEffect
        }

        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        if (authenticatedState.isAnonymous) return@LaunchedEffect

        val activeProfileId = profileState.activeProfile?.profileIndex ?: return@LaunchedEffect
        RealtimeSyncInvalidationService.start(
            userId = authenticatedState.userId,
            profileId = activeProfileId,
        )
    }

    DisposableEffect(authState, profileState.activeProfile?.profileIndex) {
        val authenticatedState = authState as? AuthState.Authenticated
        if (ownsAppRuntime && (
            !RealtimeSyncConfig.ENABLED ||
            authenticatedState == null ||
            authenticatedState.isAnonymous ||
            profileState.activeProfile == null
        )) {
            RealtimeSyncInvalidationService.stop()
        }
        onDispose {
            if (ownsAppRuntime) RealtimeSyncInvalidationService.stop()
        }
    }

    DisposableEffect(authState, profileState.activeProfile?.profileIndex) {
        val authenticatedState = authState as? AuthState.Authenticated
        val activeProfileId = profileState.activeProfile?.profileIndex
        if (ownsAppRuntime && authenticatedState != null && !authenticatedState.isAnonymous && activeProfileId != null) {
            SyncManager.startPeriodicNuvioSyncPull(activeProfileId)
        } else if (ownsAppRuntime) {
            SyncManager.stopPeriodicNuvioSyncPull()
        }
        onDispose {
            if (ownsAppRuntime) SyncManager.stopPeriodicNuvioSyncPull()
        }
    }

    LaunchedEffect(authState, profileState.activeProfile?.profileIndex) {
        if (!ownsAppRuntime) return@LaunchedEffect
        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        if (authenticatedState.isAnonymous) return@LaunchedEffect

        val activeProfileId = profileState.activeProfile?.profileIndex ?: return@LaunchedEffect
        SyncManager.pullAllForProfile(activeProfileId)
        AppForegroundMonitor.events().collect {
            SyncManager.requestForegroundPull(activeProfileId, force = true)
        }
    }
    var resumePromptItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var lastExternalPlayerLaunch by remember { mutableStateOf<PlayerLaunch?>(null) }
    val activePlaybackProfileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    val launchExternalPlayer = rememberExternalPlayerLauncher { result ->
        if (result != null && result.positionMs > 0L) {
            coroutineScope.launch {
                val durationMs = result.durationMs
                val progressPercent = if (durationMs != null && durationMs > 0L) {
                    (result.positionMs.toFloat() / durationMs.toFloat() * 100f).coerceIn(0f, 100f)
                } else {
                    null
                }
                val playerLaunch = lastExternalPlayerLaunch
                if (TraktAuthRepository.isAuthenticated.value && progressPercent != null && playerLaunch != null) {
                    val scrobbleItem = TraktScrobbleRepository.buildItem(
                        contentType = playerLaunch.parentMetaType,
                        parentMetaId = playerLaunch.parentMetaId,
                        videoId = playerLaunch.videoId,
                        title = playerLaunch.title,
                        seasonNumber = playerLaunch.seasonNumber,
                        episodeNumber = playerLaunch.episodeNumber,
                        episodeTitle = playerLaunch.episodeTitle,
                    )
                    if (scrobbleItem != null) {
                        runCatching {
                            TraktScrobbleRepository.scrobbleStop(
                                profileId = playerLaunch.profileId,
                                item = scrobbleItem,
                                progressPercent = progressPercent,
                            )
                        }
                    }
                }
                playerLaunch?.let { playerLaunch ->
                    val session = WatchProgressPlaybackSession(
                        profileId = playerLaunch.profileId,
                        contentType = playerLaunch.contentType ?: playerLaunch.parentMetaType,
                        parentMetaId = playerLaunch.parentMetaId,
                        parentMetaType = playerLaunch.parentMetaType,
                        videoId = playerLaunch.videoId ?: playerLaunch.parentMetaId,
                        title = playerLaunch.title,
                        logo = playerLaunch.logo,
                        poster = playerLaunch.poster,
                        background = playerLaunch.background,
                        seasonNumber = playerLaunch.seasonNumber,
                        episodeNumber = playerLaunch.episodeNumber,
                        episodeTitle = playerLaunch.episodeTitle,
                        episodeThumbnail = playerLaunch.episodeThumbnail,
                        providerName = playerLaunch.providerName,
                        providerAddonId = playerLaunch.providerAddonId,
                        lastStreamTitle = playerLaunch.streamTitle,
                        lastSourceUrl = playerLaunch.sourceUrl,
                    )
                    val snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = !result.endedByUser,
                        durationMs = durationMs ?: 0L,
                        positionMs = result.positionMs,
                    )
                    WatchProgressRepository.upsertPlaybackProgress(
                        session = session,
                        snapshot = snapshot,
                    )
                }
            }
        }
    }
    val continueWatchingPreferencesUiState by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    LaunchedEffect(
        initialHomeReady,
        profileSwitchLoading,
        profileState.activeProfile?.profileIndex,
        continueWatchingPreferencesUiState.showResumePromptOnLaunch,
    ) {
        if (!ownsAppRuntime) return@LaunchedEffect
        if (!initialHomeReady || profileSwitchLoading) return@LaunchedEffect
        if (resumePromptItem != null) return@LaunchedEffect
        if (continueWatchingPreferencesUiState.showResumePromptOnLaunch) {
            resumePromptItem = ResumePromptRepository.consumeResumePrompt()
        }
    }

    LaunchedEffect(currentRoute) {
        val inPlaybackFlow = currentRoute is StreamRoute || currentRoute is PlayerRoute
        if (inPlaybackFlow) {
            resumePromptItem = null
        }
    }

        LaunchedEffect(navController) {
            if (!ownsAppRuntime) return@LaunchedEffect
            AppDeepLinkRepository.pendingDeepLink.collectLatest { deepLink ->
                when (deepLink) {
                    is AppDeepLink.Meta -> {
                        activateTab(AppScreenTab.Home)
                        val routeTitle = runCatching {
                            MetaDetailsRepository.fetch(deepLink.type, deepLink.id)?.name
                        }.getOrNull().orEmpty().ifBlank { detailsFallbackTitle }
                        navController.navigate(
                            DetailRoute(
                                type = deepLink.type,
                                id = deepLink.id,
                                title = routeTitle,
                            )
                        ) {
                            launchSingleTop = true
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    is AppDeepLink.AddonInstall -> {
                        activateTab(AppScreenTab.Settings)
                        navController.navigate(AddonsSettingsRoute(addonsSettingsTitle)) {
                            launchSingleTop = true
                        }
                        NuvioToastController.show(getString(Res.string.addons_modal_checking_title))
                        AddonRepository.initialize()
                        when (val result = AddonRepository.addAddon(deepLink.manifestUrl)) {
                            is AddAddonResult.Success -> {
                                NuvioToastController.show(
                                    getString(Res.string.addons_modal_success_message, result.manifest.name),
                                )
                            }

                            is AddAddonResult.Error -> {
                                NuvioToastController.show(result.message)
                            }
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    AppDeepLink.Downloads -> {
                        activateTab(AppScreenTab.Settings)
                        navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle)) {
                            launchSingleTop = true
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    null -> Unit
                }
            }
        }

        suspend fun openExternalPlayback(launch: PlayerLaunch): Boolean {
            lastExternalPlayerLaunch = launch

            // Persist binge group for subsequent episode plays (same as internal player)
            val bingeGroup = launch.bingeGroup
            if (bingeGroup != null && launch.parentMetaId.isNotBlank()) {
                BingeGroupCacheRepository.save(launch.parentMetaId, bingeGroup)
            }

            val baseRequest = launch.toExternalPlayerPlaybackRequest()
            val shouldForwardSubtitles = playerSettingsUiState.externalPlayerForwardSubtitles &&
                !playerSettingsUiState.preferredSubtitleLanguage.equals(SubtitleLanguageOption.NONE, ignoreCase = true)
            val shouldSendSkipSegments = playerSettingsUiState.externalPlayerSendSkipSegments
            if (shouldForwardSubtitles) {
                StreamsRepository.setOverlayVisible(true, getString(Res.string.streams_loading_subtitles))
            } else if (shouldSendSkipSegments) {
                StreamsRepository.setOverlayVisible(true, getString(Res.string.streams_loading_skip_segments))
            }
            val enrichedRequest = prepareExternalPlayerLaunch(
                request = baseRequest,
                type = launch.contentType ?: launch.parentMetaType,
                videoId = launch.videoId ?: launch.parentMetaId,
                forwardSubtitles = playerSettingsUiState.externalPlayerForwardSubtitles,
                sendSkipSegments = shouldSendSkipSegments,
                preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                onOverlayMessage = { _ -> },
            )
            StreamsRepository.setOverlayVisible(false)
            return when (
                val intentResult = ExternalPlayerPlatform.buildIntent(
                    request = enrichedRequest,
                    playerId = playerSettingsUiState.externalPlayerId,
                )
            ) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    }
                    launched
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                    false
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                    false
                }
            }
        }

        fun openDownloadedItem(item: DownloadItem) {
            val sourceUrl = DownloadsRepository.playableLocalFileUri(item) ?: return
            val resumeEntry = item.videoId
                .takeIf { it.isNotBlank() }
                ?.let(WatchProgressRepository::progressForVideo)
                ?.takeIf { it.isResumable }

            val playerLaunch = PlayerLaunch(
                profileId = activePlaybackProfileId,
                title = item.title,
                sourceUrl = sourceUrl,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                externalSubtitles = emptyList(),
                streamType = null,
                logo = item.logo,
                poster = item.poster,
                background = item.background,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                episodeThumbnail = item.episodeThumbnail,
                streamTitle = item.streamTitle,
                streamSubtitle = item.streamSubtitle,
                providerName = item.providerName,
                providerAddonId = item.providerAddonId,
                contentType = item.contentType,
                videoId = item.videoId,
                parentMetaId = item.parentMetaId,
                parentMetaType = item.parentMetaType,
                initialPositionMs = resumeEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L,
                initialProgressFraction = resumeEntry?.progressFraction?.takeIf { it > 0f },
            )
            if (playerSettingsUiState.externalPlayerEnabled) {
                coroutineScope.launch { openExternalPlayback(playerLaunch) }
                return
            }
            val launchId = PlayerLaunchStore.put(playerLaunch)
            navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title))
        }

        fun openExternalStreamUrl(url: String): Boolean {
            val opened = runCatching {
                uriHandler.openUri(url)
            }.isSuccess
            if (!opened) {
                NuvioToastController.show(failedOpenBrowserText)
            }
            return opened
        }

        suspend fun launchCloudLibraryFile(
            item: CloudLibraryItem,
            file: CloudLibraryFile,
            resumePositionMs: Long? = null,
            resumeProgressFraction: Float? = null,
            startFromBeginning: Boolean = false,
        ): Boolean {
            return when (
                val resolved = CloudLibraryRepository.resolvePlayback(
                    item = item,
                    file = file,
                )
            ) {
                is CloudLibraryPlaybackResult.Success -> {
                    val playbackTitle = resolved.filename
                        ?.takeIf { it.isNotBlank() }
                        ?: file.name.ifBlank { item.name }
                    val playerLaunch = PlayerLaunch(
                        profileId = activePlaybackProfileId,
                        title = playbackTitle,
                        sourceUrl = resolved.url,
                        streamTitle = playbackTitle,
                        streamSubtitle = item.name.takeIf { it != playbackTitle },
                        providerName = item.providerName,
                        providerAddonId = "cloud:${item.providerId}",
                        poster = item.providerPosterUrl(),
                        contentType = CloudLibraryContentType,
                        videoId = item.playbackVideoId(file),
                        parentMetaId = item.stableKey,
                        parentMetaType = CloudLibraryContentType,
                        initialPositionMs = if (startFromBeginning) 0L else (resumePositionMs ?: 0L),
                        initialProgressFraction = if (startFromBeginning) null else resumeProgressFraction,
                    )
                    if (playerSettingsUiState.externalPlayerEnabled) {
                        openExternalPlayback(playerLaunch)
                        true
                    } else {
                        val launchId = PlayerLaunchStore.put(playerLaunch)
                        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title))
                        true
                    }
                }

                else -> false
            }
        }

        fun launchPlaybackWithDownloadPreference(
            type: String,
            videoId: String,
            parentMetaId: String,
            parentMetaType: String,
            title: String,
            logo: String?,
            poster: String?,
            background: String?,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeTitle: String?,
            episodeThumbnail: String?,
            pauseDescription: String?,
            runtimeMinutes: Int?,
            resumePositionMs: Long?,
            resumeProgressFraction: Float?,
            manualSelection: Boolean,
            startFromBeginning: Boolean,
        ) {
            val targetResumePositionMs = if (startFromBeginning) 0L else (resumePositionMs ?: 0L)
            val targetResumeProgressFraction = if (startFromBeginning) null else resumeProgressFraction

            if (!manualSelection) {
                val downloadedItem = DownloadsRepository.findPlayableDownload(
                    parentMetaId = parentMetaId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    videoId = videoId,
                )
                val localSourceUrl = downloadedItem?.let(DownloadsRepository::playableLocalFileUri)
                if (!localSourceUrl.isNullOrBlank()) {
                    val playerLaunch = PlayerLaunch(
                        profileId = activePlaybackProfileId,
                        title = title,
                        sourceUrl = localSourceUrl,
                        sourceHeaders = emptyMap(),
                        sourceResponseHeaders = emptyMap(),
                        externalSubtitles = emptyList(),
                        logo = logo,
                        poster = poster,
                        background = background,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeThumbnail = episodeThumbnail,
                        streamTitle = downloadedItem.streamTitle.ifBlank { title },
                        streamSubtitle = downloadedItem.streamSubtitle,
                        pauseDescription = pauseDescription,
                        providerName = downloadedItem.providerName.ifBlank { downloadedProviderLabel },
                        providerAddonId = downloadedItem.providerAddonId,
                        contentType = type,
                        videoId = videoId,
                        parentMetaId = parentMetaId,
                        parentMetaType = parentMetaType,
                        initialPositionMs = targetResumePositionMs,
                        initialProgressFraction = targetResumeProgressFraction,
                    )
                    if (playerSettingsUiState.externalPlayerEnabled) {
                        coroutineScope.launch { openExternalPlayback(playerLaunch) }
                        return
                    }
                    val launchId = PlayerLaunchStore.put(playerLaunch)
                    navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title))
                    return
                }
            }

            val streamLaunchId = StreamLaunchStore.put(
                StreamLaunch(
                    profileId = activePlaybackProfileId,
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    runtimeMinutes = runtimeMinutes,
                    resumePositionMs = if (startFromBeginning) 0L else resumePositionMs,
                    resumeProgressFraction = targetResumeProgressFraction,
                    manualSelection = manualSelection,
                    startFromBeginning = startFromBeginning,
                ),
            )
            navController.navigate(
                StreamRoute(launchId = streamLaunchId, title = title),
            )
        }

        val onPlay: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?, String?, Int?, Long?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, runtimeMinutes, resumePositionMs ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    runtimeMinutes = runtimeMinutes,
                    resumePositionMs = resumePositionMs,
                    resumeProgressFraction = null,
                    manualSelection = false,
                    startFromBeginning = false,
                )
            }

        val onPlayManually: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?, String?, Int?, Long?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, runtimeMinutes, resumePositionMs ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    runtimeMinutes = runtimeMinutes,
                    resumePositionMs = resumePositionMs,
                    resumeProgressFraction = null,
                    manualSelection = true,
                    startFromBeginning = false,
                )
            }

        /**
         * Classic's download entry point: open the source list with the download intent set.
         *
         * Deliberately not routed through `launchPlaybackWithDownloadPreference`. That path
         * short-circuits to playing a completed local download, which is right for a play
         * but wrong here - the user asked to download this title, so the source list is the
         * destination whether or not a copy already exists.
         */
        val onDownloadManually: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail ->
                val downloadLaunchId = StreamLaunchStore.put(
                    StreamLaunch(
                        profileId = activePlaybackProfileId,
                        type = type,
                        videoId = videoId,
                        parentMetaId = parentMetaId,
                        parentMetaType = parentMetaType,
                        title = title,
                        logo = logo,
                        poster = poster,
                        background = background,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeThumbnail = episodeThumbnail,
                        manualSelection = true,
                        downloadIntent = true,
                    ),
                )
                navController.navigate(
                    StreamRoute(launchId = downloadLaunchId, title = title),
                )
            }

        val onCatalogClick: (HomeCatalogSection) -> Unit = { section ->
            val launchId = CatalogLaunchStore.put(
                CatalogLaunch(
                    title = section.title,
                    subtitle = section.subtitle,
                    target = section.target,
                ),
            )
            navController.navigate(
                CatalogRoute(
                    launchId = launchId,
                    title = section.title,
                    subtitle = section.subtitle,
                ),
            )
        }

        val librarySectionSubtitle = if (libraryUiState.sourceMode == LibrarySourceMode.TRAKT) {
            stringResource(Res.string.compose_catalog_subtitle_trakt_library)
        } else {
            stringResource(Res.string.compose_catalog_subtitle_library)
        }

        val onLibrarySectionViewAllClick: (LibrarySection, LibrarySortOption) -> Unit = { section, sortOption ->
            val launchId = CatalogLaunchStore.put(
                CatalogLaunch(
                    title = section.displayTitle,
                    subtitle = librarySectionSubtitle,
                    target = CatalogTarget.Library(
                        contentType = section.items.firstOrNull()?.type ?: "movie",
                        sectionType = section.type,
                        sortOption = sortOption,
                    ),
                ),
            )
            navController.navigate(
                CatalogRoute(
                    launchId = launchId,
                    title = section.displayTitle,
                    subtitle = librarySectionSubtitle,
                ),
            )
        }

        val openContinueWatching: (ContinueWatchingItem, Boolean, Boolean) -> Unit = { item, manualSelection, startFromBeginning ->
            resumePromptItem = null
            if (item.isCloudLibraryContinueWatchingItem()) {
                coroutineScope.launch {
                    when (
                        val lookup = CloudLibraryRepository.findPlaybackTargetForProgressResult(
                            contentId = item.parentMetaId,
                            videoId = item.videoId,
                        )
                    ) {
                        is CloudLibraryPlaybackTargetLookupResult.Found -> {
                            val launched = launchCloudLibraryFile(
                                item = lookup.target.item,
                                file = lookup.target.file,
                                resumePositionMs = item.resumePositionMs,
                                resumeProgressFraction = item.resumeProgressFraction,
                                startFromBeginning = startFromBeginning,
                            )
                            if (!launched) {
                                NuvioToastController.show(cloudLibraryPlayFailedText)
                            }
                        }

                        CloudLibraryPlaybackTargetLookupResult.Disabled -> {
                            NuvioToastController.show(cloudLibraryPlayDisabledText)
                        }

                        is CloudLibraryPlaybackTargetLookupResult.NotConnected -> {
                            val providerName = lookup.providerName?.takeIf { it.isNotBlank() }
                            NuvioToastController.show(
                                providerName?.let { name ->
                                    getString(Res.string.cloud_library_play_provider_not_connected, name)
                                }
                                    ?: cloudLibraryPlayNotConnectedText,
                            )
                        }

                        CloudLibraryPlaybackTargetLookupResult.NotFound -> {
                            NuvioToastController.show(cloudLibraryPlayFailedText)
                        }
                    }
                }
            } else {
                launchPlaybackWithDownloadPreference(
                    type = item.parentMetaType,
                    videoId = item.videoId,
                    parentMetaId = item.parentMetaId,
                    parentMetaType = item.parentMetaType,
                    title = item.title,
                    logo = item.logo,
                    poster = item.poster,
                    background = item.background,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.episodeTitle,
                    episodeThumbnail = item.episodeThumbnail,
                    pauseDescription = item.pauseDescription,
                    runtimeMinutes = null,
                    resumePositionMs = item.resumePositionMs,
                    resumeProgressFraction = item.resumeProgressFraction,
                    manualSelection = manualSelection,
                    startFromBeginning = startFromBeginning,
                )
            }
        }

        val onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, false, false)
        }

        val onContinueWatchingDetails: (ContinueWatchingItem) -> Unit = { item ->
            navController.navigate(
                DetailRoute(
                    type = item.parentMetaType,
                    id = item.parentMetaId,
                    title = item.title,
                ),
            )
        }

        val onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, false, true)
        }

        val onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, true, false)
        }

        val onContinueWatchingRemove: (ContinueWatchingItem) -> Unit = { item ->
            if (item.isNextUp) {
                ContinueWatchingPreferencesRepository.addDismissedNextUpKey(
                    nextUpDismissKey(
                        item.parentMetaId,
                        item.nextUpSeedSeasonNumber,
                        item.nextUpSeedEpisodeNumber,
                    ),
                )
            } else {
                WatchProgressRepository.removeProgress(contentId = item.parentMetaId)
            }
        }

        val onContinueWatchingLongPress: (ContinueWatchingItem) -> Unit = { item ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            val zoomAnchor = PosterZoomAnchorHolder.consume()
            selectedContinueWatchingZoomAnchor = zoomAnchor
            selectedContinueWatchingForActions = item
        }

        AppUpdaterHost(
            controller = appUpdaterController,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.nuvio.colors.background),
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selectedPosterActionTarget != null || selectedContinueWatchingZoomAnchor != null) {
                            Modifier.hazeSource(state = posterOverlayHazeState)
                        } else {
                            Modifier
                        },
                    )
                    .background(MaterialTheme.nuvio.colors.background),
            ) {
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalUseNativeNavigation provides useNativeNavigation,
                    LocalNativeNavigationBarHidden provides (currentRoute?.hidesNavigationBar == true),
                ) {
                NavDisplay(
                    backStack = navBackStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { navController.popBackStack() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                        routeDisposalDecorator,
                    ),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    entryProvider = entryProvider<NavKey> {
                entry<TabsRoute> {
                    PlatformBackHandler(
                        enabled = true,
                        onBack = {
                            if (selectedTab != AppScreenTab.Home) {
                                activateTab(AppScreenTab.Home)
                            } else {
                                showExitConfirmation = !showExitConfirmation
                            }
                        },
                    )

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isTabletLayout = useTabletFloatingTabBar || maxWidth >= 768.dp
                        val useNativeBottomTabs = if (useNativeNavigation) {
                            useNativeTabBar
                        } else {
                            liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
                        }
                        val tabsRouteActive = currentRoute is TabsRoute
                        val navBarScrollState = rememberNuvioNavBarScrollState()
                        val navBarHazeState = rememberHazeState()
                        val navBarStyleSetting by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
                        val onProfileSelected: (NuvioProfile) -> Unit = { profile ->
                            profileSwitchLoading = true
                            NativeTabBridge.publishTabBarVisible(false)
                            activateTab(AppScreenTab.Home)
                            ProfileRepository.selectProfile(profile.profileIndex)
                            com.nuvio.app.core.sync.SyncManager.pullAllForProfile(profile.profileIndex)
                        }

                        Scaffold(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (initialHomeReady) 1f else 0f),
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0),
                            bottomBar = {
                                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting == NavBarStyle.CLASSIC) {
                                    NuvioClassicNavigationBar {
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Home,
                                            onClick = { handleRootTabClick(AppScreenTab.Home) },
                                            icon = Icons.Filled.Home,
                                            contentDescription = stringResource(Res.string.compose_nav_home),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Search,
                                            onClick = { handleRootTabClick(AppScreenTab.Search) },
                                            icon = Res.drawable.sidebar_search,
                                            contentDescription = stringResource(Res.string.compose_nav_search),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Library,
                                            onClick = { handleRootTabClick(AppScreenTab.Library) },
                                            icon = Res.drawable.sidebar_library,
                                            contentDescription = stringResource(Res.string.compose_nav_library),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Downloads,
                                            onClick = { handleRootTabClick(AppScreenTab.Downloads) },
                                            icon = Icons.Filled.Download,
                                            contentDescription = stringResource(Res.string.compose_nav_downloads),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Settings,
                                            onClick = { handleRootTabClick(AppScreenTab.Settings) },
                                        ) {
                                            ProfileSwitcherTab(
                                                selected = selectedTab == AppScreenTab.Settings,
                                                onClick = { handleRootTabClick(AppScreenTab.Settings) },
                                                onProfileSelected = onProfileSelected,
                                                onAddProfileRequested = onSwitchProfile,
                                            )
                                        }
                                    }
                                }
                            },
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                CompositionLocalProvider(
                                    LocalNuvioBottomNavigationOverlayPadding provides if (useNativeBottomTabs) 49.dp else if (!isTabletLayout && navBarStyleSetting != NavBarStyle.CLASSIC) 72.dp else 0.dp,
                                    LocalNuvioNavBarScrollState provides navBarScrollState,
                                ) {
                                    AppTabHost(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(if (navBarStyleSetting != NavBarStyle.CLASSIC) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                                            .then(if (navBarStyleSetting == NavBarStyle.ADAPTIVE) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                                            .padding(innerPadding),
                                        selectedTab = selectedTab,
                                        searchFocusRequestCount = searchFocusRequestCount,
                                        rootActionsEnabled = tabsRouteActive,
                                        homeScrollToTopRequests = homeScrollToTopRequests,
                                        searchScrollToTopRequests = searchScrollToTopRequests,
                                        libraryScrollToTopRequests = libraryScrollToTopRequests,
                                        downloadsScrollToTopRequests = downloadsScrollToTopRequests,
                                        settingsRootActionRequests = settingsRootActionRequests,
                                        animateHomeCollectionGifs = tabsRouteActive,
                                        onCatalogClick = onCatalogClick,
                                        onPosterClick = { meta ->
                                            navController.navigate(DetailRoute(type = meta.type, id = meta.id, title = meta.name))
                                        },
                                        onPosterLongClick = { meta ->
                                            openPosterActions(PosterActionTarget(preview = meta))
                                        },
                                        onLibraryPosterClick = { item ->
                                            navController.navigate(DetailRoute(type = item.type, id = item.id, title = item.name))
                                        },
                                        onLibraryPosterLongClick = { item, section ->
                                            openPosterActions(
                                                PosterActionTarget(
                                                    preview = item.toMetaPreview(),
                                                    libraryItem = item,
                                                    libraryListKey = section.type,
                                                ),
                                            )
                                        },
                                        onLibrarySectionViewAllClick = onLibrarySectionViewAllClick,
                                        onCloudFilePlay = { item, file ->
                                            coroutineScope.launch {
                                                val resumeItem = WatchProgressRepository
                                                    .progressForVideo(
                                                        videoId = item.playbackVideoId(file),
                                                        parentMetaId = item.id,
                                                    )
                                                    ?.takeIf { it.isResumable }
                                                    ?.toContinueWatchingItem()
                                                if (
                                                    !launchCloudLibraryFile(
                                                        item = item,
                                                        file = file,
                                                        resumePositionMs = resumeItem?.resumePositionMs,
                                                        resumeProgressFraction = resumeItem?.resumeProgressFraction,
                                                    )
                                                ) {
                                                    NuvioToastController.show(cloudLibraryPlayFailedText)
                                                }
                                            }
                                        },
                                        onConnectCloudClick = {
                                            if (useNativeNavigation && !isTabletLayout) {
                                                activateTab(AppScreenTab.Settings)
                                                navController.navigate(
                                                    SettingsPageRoute(
                                                        pageName = "Debrid",
                                                        title = debridSettingsTitle,
                                                    )
                                                )
                                            } else {
                                                requestedSettingsPageName = "Debrid"
                                                activateTab(AppScreenTab.Settings)
                                            }
                                        },
                                        onContinueWatchingClick = onContinueWatchingClick,
                                        onContinueWatchingDetails = onContinueWatchingDetails,
                                        onContinueWatchingLongPress = onContinueWatchingLongPress,
                                        onSwitchProfile = onSwitchProfile,
                                        onSettingsPageClick = if (useNativeNavigation && !isTabletLayout) {
                                            { pageName, title ->
                                                navController.navigate(SettingsPageRoute(pageName, title))
                                            }
                                        } else {
                                            null
                                        },
                                        onHomescreenSettingsClick = { navController.navigate(HomescreenSettingsRoute(homescreenSettingsTitle)) },
                                        onMetaScreenSettingsClick = { navController.navigate(MetaScreenSettingsRoute(metaScreenSettingsTitle)) },
                                        onContinueWatchingSettingsClick = { navController.navigate(ContinueWatchingSettingsRoute(continueWatchingSettingsTitle)) },
                                        onDownloadsSettingsClick = { navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle)) },
                                        onOpenDownload = ::openDownloadedItem,
                                        onDownloadShowClick = { showId, title ->
                                            navController.navigate(DownloadShowRoute(showId, title))
                                        },
                                        onChooseBatchEntryManually = { batch, entry ->
                                            onPlayManually(
                                                batch.parentMetaType,
                                                entry.videoId,
                                                batch.parentMetaId,
                                                batch.parentMetaType,
                                                batch.title,
                                                batch.logo,
                                                batch.poster,
                                                batch.background,
                                                entry.season,
                                                entry.episode,
                                                entry.title.takeIf { entry.season != null },
                                                null,
                                                null,
                                                null,
                                                null,
                                            )
                                        },
                                        onAddonsSettingsClick = { navController.navigate(AddonsSettingsRoute(addonsSettingsTitle)) },
                                        onPluginsSettingsClick = {
                                            if (AppFeaturePolicy.pluginsEnabled) {
                                                navController.navigate(PluginsSettingsRoute(pluginsSettingsTitle))
                                            }
                                        },
                                        onAccountSettingsClick = { navController.navigate(AccountSettingsRoute(accountSettingsTitle)) },
                                        onSupportersContributorsSettingsClick = {
                                            if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                                                navController.navigate(SupportersContributorsSettingsRoute(supportersSettingsTitle))
                                            }
                                        },
                                        onLicensesAttributionsSettingsClick = {
                                            navController.navigate(LicensesAttributionsSettingsRoute(licensesSettingsTitle))
                                        },
                                        onWhatsNewClick = onWhatsNewClick,
                                        onCheckForUpdatesClick = if (AppFeaturePolicy.inAppUpdaterEnabled) {
                                            {
                                                appUpdaterController.checkForUpdates(
                                                    force = true,
                                                    showNoUpdateFeedback = true,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        onTestUpdateBannerClick = if (
                                            AppFeaturePolicy.inAppUpdaterEnabled && AppUpdaterPlatform.isDebugBuild
                                        ) {
                                            appUpdaterController::showDebugTestUpdate
                                        } else {
                                            null
                                        },
                                        onCollectionsSettingsClick = { navController.navigate(CollectionsRoute(collectionsTitle)) },
                                        onFolderClick = { collectionId, folderId ->
                                            val folderTitle = CollectionRepository.collections.value
                                                .firstOrNull { it.id == collectionId }
                                                ?.folders
                                                ?.firstOrNull { it.id == folderId }
                                                ?.title
                                                .orEmpty()
                                            navController.navigate(
                                                FolderDetailRoute(
                                                    collectionId = collectionId,
                                                    folderId = folderId,
                                                    title = folderTitle.ifBlank { collectionsTitle },
                                                )
                                            )
                                        },
                                        requestedSettingsPageName = requestedSettingsPageName,
                                        onRequestedSettingsPageConsumed = {
                                            requestedSettingsPageName = null
                                        },
                                        onInitialHomeContentRendered = { initialHomeReady = true },
                                    )
                                }

                                if (isTabletLayout && !useNativeBottomTabs) {
                                    TabletFloatingTopBar(
                                        selectedTab = selectedTab,
                                        onTabSelected = ::handleRootTabClick,
                                        onProfileSelected = onProfileSelected,
                                        onAddProfileRequested = onSwitchProfile,
                                    )
                                }

                                // Floating pill navigation bar overlay
                                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting != NavBarStyle.CLASSIC) {
                                    // Force expand/collapse for non-adaptive modes
                                    when (navBarStyleSetting) {
                                        NavBarStyle.EXPANDED -> navBarScrollState.expand()
                                        NavBarStyle.COMPACT -> navBarScrollState.collapse()
                                        else -> {} // ADAPTIVE — scroll controls it
                                    }
                                    NuvioNavigationBar(
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                        scrollState = navBarScrollState,
                                        hazeState = navBarHazeState,
                                    ) {
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Home,
                                            onClick = { handleRootTabClick(AppScreenTab.Home) },
                                            icon = Icons.Filled.Home,
                                            contentDescription = stringResource(Res.string.compose_nav_home),
                                            label = stringResource(Res.string.compose_nav_home),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Search,
                                            onClick = { handleRootTabClick(AppScreenTab.Search) },
                                            icon = Res.drawable.sidebar_search,
                                            contentDescription = stringResource(Res.string.compose_nav_search),
                                            label = stringResource(Res.string.compose_nav_search),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Library,
                                            onClick = { handleRootTabClick(AppScreenTab.Library) },
                                            icon = Res.drawable.sidebar_library,
                                            contentDescription = stringResource(Res.string.compose_nav_library),
                                            label = stringResource(Res.string.compose_nav_library),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Downloads,
                                            onClick = { handleRootTabClick(AppScreenTab.Downloads) },
                                            icon = Icons.Filled.Download,
                                            contentDescription = stringResource(Res.string.compose_nav_downloads),
                                            label = stringResource(Res.string.compose_nav_downloads),
                                        )
                                        NavItem(
                                            selected = selectedTab == AppScreenTab.Settings,
                                            onClick = { handleRootTabClick(AppScreenTab.Settings) },
                                            label = stringResource(Res.string.compose_nav_profile),
                                        ) {
                                            ProfileSwitcherTab(
                                                selected = selectedTab == AppScreenTab.Settings,
                                                onClick = { handleRootTabClick(AppScreenTab.Settings) },
                                                onProfileSelected = onProfileSelected,
                                                onAddProfileRequested = onSwitchProfile,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                entry<DetailRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(navController, route)
                    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
                    val directorRole = stringResource(Res.string.person_role_director)
                    val writerRole = stringResource(Res.string.person_role_writer)
                    val creatorRole = stringResource(Res.string.person_role_creator)
                    MetaDetailsScreen(
                        type = route.type,
                        id = route.id,
                        onBack = onBack,
                        onPlay = onPlay,
                        onPlayManually = onPlayManually,
                        onDownloadManually = onDownloadManually,
                        onPlayDownloadedItem = ::openDownloadedItem,
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = if (preview.id.startsWith("tmdb:")) {
                                    val tmdbId = preview.id.removePrefix("tmdb:").toIntOrNull()
                                    tmdbId?.let {
                                        TmdbService.tmdbToImdb(
                                            tmdbId = it,
                                            mediaType = preview.type,
                                        )
                                    } ?: preview.id
                                } else {
                                    preview.id
                                }
                                navController.navigate(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                        title = preview.name,
                                    ),
                                )
                            }
                        },
                        onCastClick = { person, avatarTransitionKey ->
                            val tmdbId = person.tmdbId
                            if (tmdbId != null && tmdbId > 0) {
                                navController.navigate(
                                    PersonDetailRoute(
                                        personId = tmdbId,
                                        personName = person.name,
                                        personPhoto = person.photo,
                                        castAvatarTransitionKey = avatarTransitionKey,
                                        preferCrew = person.role?.let {
                                            it.equals("Director", ignoreCase = true) ||
                                                it.equals(directorRole, ignoreCase = true) ||
                                                it.equals("Writer", ignoreCase = true) ||
                                                it.equals(writerRole, ignoreCase = true) ||
                                                it.equals("Creator", ignoreCase = true)
                                                || it.equals(creatorRole, ignoreCase = true)
                                        } ?: false,
                                    ),
                                )
                            }
                        },
                        onCompanyClick = { company, entityKind ->
                            val tmdbId = company.tmdbId
                            if (tmdbId != null && tmdbId > 0) {
                                navController.navigate(
                                    EntityBrowseRoute(
                                        entityKind = entityKind,
                                        entityId = tmdbId,
                                        entityName = company.name,
                                        sourceType = route.type,
                                    ),
                                )
                            }
                        },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<PersonDetailRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(navController, route)
                    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
                    PersonDetailScreen(
                        personId = route.personId,
                        personName = route.personName,
                        initialProfilePhoto = route.personPhoto,
                        avatarTransitionKey = route.castAvatarTransitionKey,
                        preferCrew = route.preferCrew,
                        onBack = onBack,
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = if (preview.id.startsWith("tmdb:")) {
                                    val tmdbId = preview.id.removePrefix("tmdb:").toIntOrNull()
                                    tmdbId?.let {
                                        TmdbService.tmdbToImdb(
                                            tmdbId = it,
                                            mediaType = preview.type,
                                        )
                                    } ?: preview.id
                                } else {
                                    preview.id
                                }
                                navController.navigate(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                        title = preview.name,
                                    ),
                                )
                            }
                        },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<EntityBrowseRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(navController, route)
                    TmdbEntityBrowseScreen(
                        entityKind = TmdbEntityKind.fromRouteValue(route.entityKind),
                        entityId = route.entityId,
                        entityName = route.entityName,
                        sourceType = route.sourceType,
                        onBack = onBack,
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = if (preview.id.startsWith("tmdb:")) {
                                    val tmdbId = preview.id.removePrefix("tmdb:").toIntOrNull()
                                    tmdbId?.let {
                                        TmdbService.tmdbToImdb(
                                            tmdbId = it,
                                            mediaType = preview.type,
                                        )
                                    } ?: preview.id
                                } else {
                                    preview.id
                                }
                                navController.navigate(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                        title = preview.name,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<StreamRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(navController, route)
                    val launch = remember(route.launchId) {
                        StreamLaunchStore.get(route.launchId)
                    }
                    if (launch == null) {
                        LaunchedEffect(route.launchId) {
                            onBack()
                        }
                        return@entry
                    }
                    val pauseDescription = launch.pauseDescription
                    val streamRouteScope = rememberCoroutineScope()
                    var resolvingDebridStream by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    var pendingP2pStreamOpen by remember { mutableStateOf<PendingP2pStreamOpen?>(null) }
                    var pendingStickyStreamOpen by remember { mutableStateOf<PendingStickyStreamOpen?>(null) }
                    var pendingUncachedStream by remember { mutableStateOf<StreamItem?>(null) }
                    var qualitySheetDismissed by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    var streamlinedSelectionPending by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    // Ids are resolution+variant, so they survive the refetch this round-trips through.
                    var pendingStreamlinedOptionId by rememberSaveable(route.launchId) { mutableStateOf<String?>(null) }
                    var manualSourceListRequested by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    // Backing out of the quality sheet means "not now", so it returns to details
                    // rather than uncovering the Classic source list the user chose Streamlined
                    // to avoid - behind a bottom sheet a stray swipe would otherwise land there.
                    //
                    // `onBack` can silently do nothing: rememberGuardedPopBackStack pops only
                    // while this route is current and returns Unit, so the caller cannot tell.
                    // With `qualitySheetDismissed` set and `manualSourceListRequested` false the
                    // opaque hand-off Box keeps painting over StreamsScreen, which would leave a
                    // blank screen with no affordance - the same fault onPlaybackFailureExit
                    // exists to prevent. Declared out here, beside the flags: this effect must
                    // outlive the sheet, which leaves composition the moment `onDismiss` runs.
                    var qualitySheetDismissRequested by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    LaunchedEffect(qualitySheetDismissRequested) {
                        if (!qualitySheetDismissRequested) return@LaunchedEffect
                        withFrameNanos { }
                        if (navController.currentRoute == route) {
                            // The pop no-oped. Uncovering the source list is what every other
                            // dead end in this route does, and it leaves the user able to act.
                            manualSourceListRequested = true
                        }
                    }
                    val noAutomaticSourceMessage = stringResource(Res.string.playback_quality_no_match)

                    /**
                     * Gives the screen back to the user, with a reason.
                     *
                     * Every automatic path can end without a source: the chain runs out, a
                     * protocol is refused, a consent dialog is declined. Each of those used to
                     * be its own two lines at its own call site, and the ones that forgot them
                     * left the opaque hand-off surface - or the progress overlay - up over a
                     * screen the user could neither read nor leave. There is one way out now,
                     * and it always says something.
                     */
                    fun fallBackToSourceList(reason: String? = null) {
                        qualitySheetDismissed = true
                        manualSourceListRequested = true
                        (reason?.takeIf { it.isNotBlank() } ?: noAutomaticSourceMessage)
                            .let(NuvioToastController::show)
                    }

                    var instantSelectionHandled by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    // Streamlined covers the source list with the quality sheet until a tier is
                    // picked; from that point until playback starts the progress overlay owns the
                    // screen, so the list is never what the user is left looking at.
                    var streamlinedPlaybackStarting by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    // 1-based, and only ever advanced by the auto-pick failure chain. The overlay
                    // shows it so a silent retry does not read as a hang.
                    var autoPickAttempt by rememberSaveable(route.launchId) { mutableStateOf(1) }
                    // Set at *every* exit to playback, not just the reuse-last-link one.
                    // Instant deliberately leaves StreamRoute on the back stack so the failure
                    // chain survives, so without this, backing out of the player lands on an
                    // opaque overlay with nothing to interact with.
                    var playbackHandedOff by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    var meteredChoice by remember(route.launchId) {
                        mutableStateOf(NetworkQualityRepository.meteredChoiceForCurrentNetwork())
                    }
                    val shouldResolveEpisodeVideoId =
                        launch.parentMetaId != null &&
                            launch.seasonNumber != null &&
                            launch.episodeNumber != null
                    var effectiveVideoId by rememberSaveable(
                        launch.videoId,
                        launch.parentMetaId,
                        launch.seasonNumber,
                        launch.episodeNumber,
                    ) {
                        mutableStateOf(launch.videoId)
                    }
                    var hasResolvedVideoId by rememberSaveable(
                        launch.videoId,
                        launch.parentMetaId,
                        launch.seasonNumber,
                        launch.episodeNumber,
                    ) {
                        mutableStateOf(!shouldResolveEpisodeVideoId)
                    }

                    LaunchedEffect(
                        launch.videoId,
                        launch.parentMetaId,
                        launch.parentMetaType,
                        launch.type,
                        launch.seasonNumber,
                        launch.episodeNumber,
                    ) {
                        effectiveVideoId = launch.videoId
                        if (!shouldResolveEpisodeVideoId) {
                            hasResolvedVideoId = true
                            return@LaunchedEffect
                        }

                        hasResolvedVideoId = false
                        val metaType = launch.parentMetaType ?: launch.type
                        val metaId = launch.parentMetaId ?: return@LaunchedEffect
                        val resolvedVideoId = runCatching {
                            MetaDetailsRepository.fetch(metaType, metaId)
                        }.getOrNull()
                            ?.videos
                            ?.firstOrNull { video ->
                                video.season == launch.seasonNumber &&
                                    video.episode == launch.episodeNumber
                            }
                            ?.id
                            ?.takeIf { it.isNotBlank() }

                        effectiveVideoId = resolvedVideoId ?: launch.videoId
                        hasResolvedVideoId = true
                    }

                    val playerSettings by remember {
                        PlayerSettingsRepository.ensureLoaded()
                        PlayerSettingsRepository.uiState
                    }.collectAsStateWithLifecycle()
                    // Streamlined and Instant own source selection. Passing them through the
                    // legacy auto-play policy would run two pickers over the same candidates.
                    val streamManualSelection = launch.manualSelection ||
                        // A download-intent launch must never auto-play: the user pressed
                        // Download, so every automatic playback path stays out of the way.
                        launch.downloadIntent ||
                        playerSettings.playbackMode != PlaybackMode.CLASSIC

                    fun p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
                        "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

                    fun openP2pStream(
                        stream: StreamItem,
                        resolvedResumePositionMs: Long?,
                        resolvedResumeProgressFraction: Float?,
                        replaceStreamRoute: Boolean,
                    ) {
                        val infoHash = stream.p2pInfoHash ?: return
                        val sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
                        if (playerSettings.streamReuseLastLinkEnabled) {
                            val cacheKey = StreamLinkCacheRepository.contentKey(
                                type = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId,
                                season = launch.seasonNumber,
                                episode = launch.episodeNumber,
                            )
                            StreamLinkCacheRepository.save(
                                contentKey = cacheKey,
                                url = "",
                                streamName = stream.streamLabel,
                                addonName = stream.addonName,
                                addonId = stream.addonId,
                                requestHeaders = emptyMap(),
                                responseHeaders = emptyMap(),
                                filename = stream.behaviorHints.filename,
                                videoSize = stream.behaviorHints.videoSize,
                                infoHash = infoHash,
                                fileIdx = stream.p2pFileIdx,
                                sources = stream.sources,
                                bingeGroup = stream.behaviorHints.bingeGroup,
                            )
                        }
                        val playerLaunch = PlayerLaunch(
                            profileId = launch.profileId,
                            title = launch.title,
                            sourceUrl = sentinelUrl,
                            sourceHeaders = emptyMap(),
                            sourceResponseHeaders = emptyMap(),
                            streamType = stream.streamType,
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            seasonNumber = launch.seasonNumber,
                            episodeNumber = launch.episodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            streamTitle = stream.streamLabel,
                            streamSubtitle = stream.streamSubtitle,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                            pauseDescription = pauseDescription,
                            providerName = stream.addonName,
                            providerAddonId = stream.addonId,
                            contentType = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            torrentInfoHash = infoHash,
                            torrentFileIdx = stream.p2pFileIdx,
                            torrentFilename = stream.behaviorHints.filename,
                            torrentTrackers = stream.p2pTrackers,
                            initialPositionMs = resolvedResumePositionMs ?: 0L,
                            initialProgressFraction = resolvedResumeProgressFraction,
                        )

                        val launchId = PlayerLaunchStore.put(playerLaunch)
                        StreamsRepository.cancelLoading()
                        playbackHandedOff = true
                        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
                            if (replaceStreamRoute) {
                                popUpTo<StreamRoute> { inclusive = true }
                            }
                        }
                    }

                    fun requestOrOpenP2pStream(
                        stream: StreamItem,
                        resolvedResumePositionMs: Long?,
                        resolvedResumeProgressFraction: Float?,
                        forceExternal: Boolean,
                        forceInternal: Boolean,
                        isAutoPlay: Boolean,
                    ) {
                        // Both of these advance the chain and *must* honour the answer. They
                        // discarded it, so a refusal on the last candidate advanced to nothing
                        // and returned - leaving whatever was covering the screen still up,
                        // with no candidate left to take it down.
                        if (stream.p2pInfoHash == null) {
                            if (isAutoPlay && !StreamsRepository.skipAutoPlayStream(stream)) {
                                fallBackToSourceList()
                            }
                            return
                        }
                        if (!P2pSettingsRepository.isVisible) {
                            if (isAutoPlay && !StreamsRepository.skipAutoPlayStream(stream)) {
                                fallBackToSourceList()
                            }
                            return
                        }
                        if (!p2pSettingsUiState.p2pEnabled) {
                            pendingP2pStreamOpen = PendingP2pStreamOpen(
                                stream = stream,
                                resumePositionMs = resolvedResumePositionMs,
                                resumeProgressFraction = resolvedResumeProgressFraction,
                                forceExternal = forceExternal,
                                forceInternal = forceInternal,
                                isAutoPlay = isAutoPlay,
                            )
                            return
                        }
                        openP2pStream(
                            stream = stream,
                            resolvedResumePositionMs = resolvedResumePositionMs,
                            resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                            replaceStreamRoute = isAutoPlay,
                        )
                    }

                    val streamsUiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
                    val expectedStreamsRequestToken = StreamsRepository.requestToken(
                        type = launch.type,
                        videoId = effectiveVideoId,
                        season = launch.seasonNumber,
                        episode = launch.episodeNumber,
                        manualSelection = streamManualSelection,
                    )
                    val playbackCandidates = remember(
                        streamsUiState.groups,
                        streamsUiState.requestToken,
                        expectedStreamsRequestToken,
                    ) {
                        if (streamsUiState.requestToken != expectedStreamsRequestToken) {
                            emptyList()
                        } else {
                            streamsUiState.groups.flatMapIndexed { addonOrder, group ->
                                group.streams.map { stream ->
                                    PlaybackSourceCandidate(
                                        stream = stream,
                                        facts = SourceFactsExtractor.extract(stream),
                                        addonOrder = addonOrder,
                                    )
                                }
                            }
                        }
                    }
                    val playbackSelectionContext = remember(
                        launch.runtimeMinutes,
                        launch.seasonNumber,
                        launch.episodeNumber,
                        playerSettings.playbackAllowTorrentAutopick,
                    ) {
                        PlaybackSelectionContext(
                            runtimeMinutes = launch.runtimeMinutes,
                            isEpisode = launch.seasonNumber != null && launch.episodeNumber != null,
                            allowTorrentSources = playerSettings.playbackAllowTorrentAutopick,
                        )
                    }
                    // The quality choices for *this* title, derived from what the addons actually
                    // returned. A quality nobody released simply produces no row.
                    val playbackQualityOptions = remember(playbackCandidates, playbackSelectionContext) {
                        PlaybackQualityOptions.build(playbackCandidates, playbackSelectionContext)
                    }
                    val stickyContentId = remember(launch.parentMetaId, launch.seasonNumber) {
                        val seriesId = launch.parentMetaId
                        val season = launch.seasonNumber
                        if (seriesId != null && season != null) {
                            BingeGroupCacheRepository.stickyContentId(seriesId, season)
                        } else {
                            null
                        }
                    }
                    val stickyPin = remember(stickyContentId) {
                        stickyContentId?.let(BingeGroupCacheRepository::sessionPin)
                    }
                    val matchingStickyCandidate = remember(stickyPin, playbackCandidates) {
                        stickyPin?.let { pin ->
                            playbackCandidates.filter { it.stream.hasPlayableSource }.maxByOrNull { candidate ->
                                pin.matchStrength(
                                    candidateReleaseGroup = candidate.facts.releaseGroup,
                                    candidateBingeGroup = candidate.stream.behaviorHints.bingeGroup,
                                    candidateAddonId = candidate.stream.addonId,
                                    candidateProviderId = candidate.facts.providerId,
                                    candidateResolutionHeight = candidate.facts.resolution?.height,
                                ) ?: Int.MIN_VALUE
                            }?.takeIf { candidate ->
                                pin.matchStrength(
                                    candidateReleaseGroup = candidate.facts.releaseGroup,
                                    candidateBingeGroup = candidate.stream.behaviorHints.bingeGroup,
                                    candidateAddonId = candidate.stream.addonId,
                                    candidateProviderId = candidate.facts.providerId,
                                    candidateResolutionHeight = candidate.facts.resolution?.height,
                                ) != null
                            }
                        }
                    }

                    // Reuse Last Link: auto-play from cache if enabled (only on first entry).
                    // A stored Streamlined pin must wait for candidates so it can outrank reuse.
                    // Saved, not remembered. A mode with a failure chain keeps this route on the
                    // back stack while the player is open, and NavDisplay composes only the top
                    // entry - so a plain `remember` came back null while `reuseHandled` (which
                    // is saved) stayed true and blocked the effect that would set it again.
                    // Every branch below then read "no decision": no sheet, no overlay, and the
                    // opaque hand-off surface still painting. That was the blank screen after
                    // backing out of the player.
                    var playbackRouteDecision by rememberSaveable(
                        launch.videoId,
                        effectiveVideoId,
                        playerSettings.playbackMode,
                        stateSaver = PlaybackRouteDecisionSaver,
                    ) { mutableStateOf<PlaybackRouteDecision?>(null) }
                    var reuseHandled by rememberSaveable(
                        launch.videoId,
                        effectiveVideoId,
                        playerSettings.playbackMode,
                    ) { mutableStateOf(false) }
                    var reuseNavigated by remember { mutableStateOf(false) }
                    LaunchedEffect(
                        effectiveVideoId,
                        hasResolvedVideoId,
                        playerSettings.playbackMode,
                        playerSettings.streamReuseLastLinkEnabled,
                        playerSettings.streamReuseLastLinkCacheHours,
                        launch.manualSelection,
                        stickyPin,
                        matchingStickyCandidate,
                        streamsUiState.requestToken,
                        streamsUiState.isAnyLoading,
                    ) {
                        if (!hasResolvedVideoId) return@LaunchedEffect
                        if (reuseHandled) return@LaunchedEffect
                        if (
                            playerSettings.playbackMode == PlaybackMode.STREAMLINED && stickyPin != null &&
                            (streamsUiState.requestToken != expectedStreamsRequestToken || streamsUiState.isAnyLoading)
                        ) return@LaunchedEffect
                        reuseHandled = true
                        val cacheKey = StreamLinkCacheRepository.contentKey(
                            type = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId,
                            season = launch.seasonNumber,
                            episode = launch.episodeNumber,
                        )
                        val maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                        val cached = if (playerSettings.streamReuseLastLinkEnabled) {
                            StreamLinkCacheRepository.getValid(cacheKey, maxAgeMs)
                        } else {
                            null
                        }
                        val decision = PlaybackModeRouter.decide(
                            PlaybackRouteInputs(
                                mode = playerSettings.playbackMode,
                                manualSelection = launch.manualSelection,
                                // Completed downloads are consumed before StreamRoute is created.
                                hasCompletedLocalDownload = false,
                                hasMatchingStickyPin = matchingStickyCandidate != null,
                                reuseLastLinkEnabled = playerSettings.streamReuseLastLinkEnabled,
                                hasValidCachedLink = cached != null,
                            ),
                        )
                        playbackRouteDecision = decision
                        if (decision is PlaybackRouteDecision.ReuseLastLink && cached != null) {
                            if (cached.url.isBlank() && !cached.infoHash.isNullOrBlank()) {
                                val cachedStream = StreamItem(
                                    name = cached.streamName,
                                    url = null,
                                    infoHash = cached.infoHash,
                                    fileIdx = cached.fileIdx,
                                    sources = cached.sources,
                                    addonName = cached.addonName,
                                    addonId = cached.addonId,
                                    behaviorHints = StreamBehaviorHints(
                                        filename = cached.filename,
                                        videoSize = cached.videoSize,
                                        bingeGroup = cached.bingeGroup,
                                    ),
                                )
                                requestOrOpenP2pStream(
                                    stream = cachedStream,
                                    resolvedResumePositionMs = launch.resumePositionMs,
                                    resolvedResumeProgressFraction = launch.resumeProgressFraction,
                                    forceExternal = false,
                                    forceInternal = true,
                                    isAutoPlay = true,
                                )
                                reuseNavigated = true
                                return@LaunchedEffect
                            }
                            val playerLaunch = PlayerLaunch(
                                profileId = launch.profileId,
                                title = launch.title,
                                sourceUrl = cached.url,
                                sourceHeaders = sanitizePlaybackHeaders(cached.requestHeaders),
                                sourceResponseHeaders = sanitizePlaybackResponseHeaders(cached.responseHeaders),
                                externalSubtitles = emptyList(),
                                streamType = cached.streamType,
                                logo = launch.logo,
                                poster = launch.poster,
                                background = launch.background,
                                seasonNumber = launch.seasonNumber,
                                episodeNumber = launch.episodeNumber,
                                episodeTitle = launch.episodeTitle,
                                episodeThumbnail = launch.episodeThumbnail,
                                streamTitle = cached.streamName,
                                streamSubtitle = null,
                                bingeGroup = cached.bingeGroup,
                                pauseDescription = pauseDescription,
                                providerName = cached.addonName,
                                providerAddonId = cached.addonId,
                                contentType = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                                parentMetaType = launch.parentMetaType ?: launch.type,
                                initialPositionMs = launch.resumePositionMs ?: 0L,
                                initialProgressFraction = launch.resumeProgressFraction,
                                contentLanguage = cached.contentLanguage,
                            )
                            if (playerSettings.externalPlayerEnabled) {
                                playbackHandedOff = true
                                openExternalPlayback(playerLaunch)
                                StreamsRepository.setOverlayVisible(false)
                                reuseNavigated = true
                                return@LaunchedEffect
                            }
                            StreamsRepository.clear()
                            reuseNavigated = true
                            val launchId = PlayerLaunchStore.put(playerLaunch)
                            playbackHandedOff = true
                            navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
                                popUpTo<StreamRoute> { inclusive = true }
                            }
                        }
                    }

                    // Coming back from a player that died with the chain re-armed: this is a
                    // retry, so re-show the progress overlay the hand-off had hidden. Gated on
                    // this route being current because Instant deliberately leaves
                    // `autoPlayStream` set while the player is open - without that check this
                    // would fire the moment playback was handed off and uncover the overlay
                    // underneath the player.
                    LaunchedEffect(streamsUiState.autoPlayStream, navController.currentRoute) {
                        if (navController.currentRoute != route) return@LaunchedEffect
                        if (!playbackHandedOff) return@LaunchedEffect
                        if (streamsUiState.autoPlayStream == null) return@LaunchedEffect
                        playbackHandedOff = false
                        autoPickAttempt += 1
                    }

                    /**
                     * Says which source just failed and why, on the way to the next one.
                     *
                     * Instant and Streamlined both step past a dead candidate silently today,
                     * which is indistinguishable from the app hanging - and when the chain then
                     * runs out, the only thing left on screen is whatever the last provider
                     * said, out of context. Naming the source turns "unknown error" into
                     * something the user can act on, or at least recognise.
                     */
                    suspend fun announceSourceFailure(stream: StreamItem, reason: String?) {
                        val label = PlaybackSourceSelector.describe(
                            playbackCandidates.firstOrNull { it.stream === stream }?.facts,
                        ).takeIf { it.isNotBlank() } ?: stream.streamLabel
                        NuvioToastController.show(
                            if (reason.isNullOrBlank()) {
                                getString(Res.string.playback_source_failed_advancing, label)
                            } else {
                                getString(Res.string.playback_source_failed_advancing_reason, label, reason)
                            },
                        )
                    }

                    var autoPlayHandled by rememberSaveable(launch.videoId, effectiveVideoId) { mutableStateOf(false) }
                    LaunchedEffect(
                        streamsUiState.autoPlayStream,
                        streamsUiState.requestToken,
                        expectedStreamsRequestToken,
                        reuseHandled,
                        playbackRouteDecision,
                        playerSettings.playbackMode,
                        launch.manualSelection,
                        streamlinedPlaybackStarting,
                    ) {
                        if (!reuseHandled) return@LaunchedEffect
                        if (launch.manualSelection) return@LaunchedEffect
                        val isClassicAutoPlay = playerSettings.playbackMode == PlaybackMode.CLASSIC &&
                            playbackRouteDecision is PlaybackRouteDecision.ShowSourceList
                        val isInstantAutoPlay = playerSettings.playbackMode == PlaybackMode.INSTANT &&
                            playbackRouteDecision is PlaybackRouteDecision.AutoPick
                        // Streamlined runs through the same chain from the moment a tier is
                        // chosen. Before that `streamlinedPlaybackStarting` is false and nothing
                        // has been seeded, so the sheet is still the user's to answer.
                        val isStreamlinedAutoPlay =
                            playerSettings.playbackMode == PlaybackMode.STREAMLINED &&
                                streamlinedPlaybackStarting
                        // Everything below that used to read `isInstantAutoPlay` was really
                        // asking "is there a next candidate to fall to?". Streamlined now has
                        // one, and answering that question in two different ways is how the
                        // chain ends up half-wired.
                        val hasFailureChain = isInstantAutoPlay || isStreamlinedAutoPlay
                        if (!isClassicAutoPlay && !hasFailureChain) return@LaunchedEffect
                        if (reuseNavigated) return@LaunchedEffect
                        if (autoPlayHandled && !hasFailureChain) return@LaunchedEffect
                        if (streamsUiState.requestToken != expectedStreamsRequestToken) return@LaunchedEffect
                        val selectedStream = streamsUiState.autoPlayStream ?: return@LaunchedEffect
                        val stream = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(selectedStream)) {
                            when (
                                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                                    stream = selectedStream,
                                    season = launch.seasonNumber,
                                    episode = launch.episodeNumber,
                                )
                            ) {
                                is DirectDebridPlayableResult.Success -> resolved.stream
                                else -> {
                                    val hasNextCandidate = StreamsRepository.skipAutoPlayStream(selectedStream)
                                    if (hasNextCandidate && hasFailureChain) {
                                        autoPickAttempt += 1
                                        // Silently stepping to the next source is how "not
                                        // cached" turned into a spinner nobody could explain.
                                        // Name the source and the reason on the way past.
                                        announceSourceFailure(
                                            stream = selectedStream,
                                            reason = resolved.toastMessage(),
                                        )
                                    }
                                    if (!hasNextCandidate) {
                                        // The chain is spent and nothing else will move. Without
                                        // this the progress overlay keeps covering the source
                                        // list with "Starting playback" for a playback that is
                                        // never going to start - a hang wearing a spinner.
                                        fallBackToSourceList(resolved.toastMessage())
                                    }
                                    if (!hasNextCandidate && resolved == DirectDebridPlayableResult.Stale) {
                                        StreamsRepository.reload(
                                            type = launch.type,
                                            videoId = effectiveVideoId,
                                            parentMetaId = launch.parentMetaId,
                                            season = launch.seasonNumber,
                                            episode = launch.episodeNumber,
                                            manualSelection = streamManualSelection,
                                        )
                                    }
                                    return@LaunchedEffect
                                }
                            }
                        } else {
                            selectedStream
                        }
                        if (isInstantAutoPlay) {
                            // Record what is actually about to play, not what was first chosen -
                            // the failure chain above may have advanced past a dead candidate to
                            // a different resolution, and both the pin and the toast have to
                            // describe the source the user ends up watching.
                            val openedFacts = playbackCandidates
                                .firstOrNull { it.stream === selectedStream }?.facts
                            openedFacts?.resolution?.height?.let { height ->
                                BingeGroupCacheRepository.saveSessionInstantHeight(
                                    parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                                    height = height,
                                )
                            }
                            // Instant otherwise gives no indication of what it decided, which is
                            // most of why a defensible pick reads as a random one.
                            val detail = listOfNotNull(
                                openedFacts?.resolution.qualityLabel.takeIf { it.isNotBlank() },
                                openedFacts?.releaseQuality?.takeIf { it.isNotBlank() },
                                (openedFacts?.providerName ?: openedFacts?.debridService)
                                    ?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (detail.isNotBlank()) {
                                NuvioToastController.show(
                                    getString(Res.string.playback_instant_selected, detail),
                                )
                            }
                        }
                        val sourceUrl = stream.playableDirectUrl
                        if (sourceUrl == null && stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
                            autoPlayHandled = true
                            requestOrOpenP2pStream(
                                stream = stream,
                                resolvedResumePositionMs = launch.resumePositionMs,
                                resolvedResumeProgressFraction = launch.resumeProgressFraction,
                                forceExternal = false,
                                forceInternal = true,
                                isAutoPlay = true,
                            )
                            StreamsRepository.consumeAutoPlay()
                            return@LaunchedEffect
                        }
                        if (sourceUrl == null) {
                            if (StreamsRepository.skipAutoPlayStream(selectedStream)) {
                                if (hasFailureChain) {
                                    autoPickAttempt += 1
                                    announceSourceFailure(stream = selectedStream, reason = null)
                                }
                            } else if (hasFailureChain) {
                                // Same reasoning as the resolve-failure arm: an exhausted chain
                                // must uncover the list rather than leave the overlay up.
                                fallBackToSourceList()
                            }
                            return@LaunchedEffect
                        }
                        autoPlayHandled = true
                        if (playerSettings.streamReuseLastLinkEnabled) {
                            val cacheKey = StreamLinkCacheRepository.contentKey(
                                type = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId,
                                season = launch.seasonNumber,
                                episode = launch.episodeNumber,
                            )
                            StreamLinkCacheRepository.save(
                                contentKey = cacheKey,
                                url = sourceUrl,
                                streamName = stream.streamLabel,
                                addonName = stream.addonName,
                                addonId = stream.addonId,
                                requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                                responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                                filename = stream.behaviorHints.filename,
                                videoSize = stream.behaviorHints.videoSize,
                                bingeGroup = stream.behaviorHints.bingeGroup,
                                streamType = stream.streamType,
                            )
                        }
                        val playerLaunch = PlayerLaunch(
                            profileId = launch.profileId,
                            title = launch.title,
                            sourceUrl = sourceUrl,
                            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                            externalSubtitles = stream.externalSubtitles,
                            streamType = stream.streamType,
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            seasonNumber = launch.seasonNumber,
                            episodeNumber = launch.episodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            streamTitle = stream.streamLabel,
                            streamSubtitle = stream.streamSubtitle,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                            pauseDescription = pauseDescription,
                            providerName = stream.addonName,
                            providerAddonId = stream.addonId,
                            contentType = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            initialPositionMs = launch.resumePositionMs ?: 0L,
                            initialProgressFraction = launch.resumeProgressFraction,
                            autoPickedWithFailureChain = hasFailureChain,
                        )
                        if (playerSettings.externalPlayerEnabled) {
                            playbackHandedOff = true
                            openExternalPlayback(playerLaunch)
                            if (!hasFailureChain) StreamsRepository.consumeAutoPlay()
                            StreamsRepository.cancelLoading()
                            return@LaunchedEffect
                        }
                        if (!hasFailureChain) StreamsRepository.consumeAutoPlay()
                        StreamsRepository.cancelLoading()
                        val launchId = PlayerLaunchStore.put(playerLaunch)
                        playbackHandedOff = true
                        // A mode with a chain keeps StreamRoute on the back stack: that route
                        // owns the auto-play effect, the attempt counter and the overlay, so
                        // popping it is popping the thing that does the retrying.
                        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
                            if (!hasFailureChain) popUpTo<StreamRoute> { inclusive = true }
                        }
                    }

                    if (!hasResolvedVideoId) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            NuvioLoadingIndicator(color = MaterialTheme.nuvio.colors.accent)
                        }
                        return@entry
                    }

                    fun openSelectedStream(
                        stream: StreamItem,
                        resolvedResumePositionMs: Long?,
                        resolvedResumeProgressFraction: Float?,
                        forceExternal: Boolean,
                        forceInternal: Boolean,
                    ) {
                        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                            if (resolvingDebridStream) return
                            streamRouteScope.launch {
                                resolvingDebridStream = true
                                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                                    stream = stream,
                                    season = launch.seasonNumber,
                                    episode = launch.episodeNumber,
                                )
                                resolvingDebridStream = false
                                when (resolved) {
                                    is DirectDebridPlayableResult.Success -> openSelectedStream(
                                        stream = resolved.stream,
                                        resolvedResumePositionMs = resolvedResumePositionMs,
                                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                        forceExternal = forceExternal,
                                        forceInternal = forceInternal,
                                    )
                                    else -> {
                                        resolved.toastMessage()?.let { NuvioToastController.show(it) }
                                        if (resolved == DirectDebridPlayableResult.Stale) {
                                            StreamsRepository.reload(
                                                type = launch.type,
                                                videoId = effectiveVideoId,
                                                parentMetaId = launch.parentMetaId,
                                                season = launch.seasonNumber,
                                                episode = launch.episodeNumber,
                                                manualSelection = streamManualSelection,
                                            )
                                        }
                                    }
                                }
                            }
                            return
                        }
                        if (stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
                            requestOrOpenP2pStream(
                                stream = stream,
                                resolvedResumePositionMs = resolvedResumePositionMs,
                                resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                forceExternal = forceExternal,
                                forceInternal = forceInternal,
                                isAutoPlay = false,
                            )
                            return
                        }
                        if (stream.shouldOpenExternally) {
                            val opened = stream.externalOpenUrl?.let(::openExternalStreamUrl) == true
                            if (opened) {
                                StreamsRepository.cancelLoading()
                            }
                            return
                        }
                        val sourceUrl = stream.playableDirectUrl ?: return
                        if (playerSettings.streamReuseLastLinkEnabled) {
                            val cacheKey = StreamLinkCacheRepository.contentKey(
                                type = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId,
                                season = launch.seasonNumber,
                                episode = launch.episodeNumber,
                            )
                            StreamLinkCacheRepository.save(
                                contentKey = cacheKey,
                                url = sourceUrl,
                                streamName = stream.streamLabel,
                                addonName = stream.addonName,
                                addonId = stream.addonId,
                                requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                                responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                                filename = stream.behaviorHints.filename,
                                videoSize = stream.behaviorHints.videoSize,
                                bingeGroup = stream.behaviorHints.bingeGroup,
                                streamType = stream.streamType,
                            )
                        }
                        val playerLaunch = PlayerLaunch(
                            profileId = launch.profileId,
                            title = launch.title,
                            sourceUrl = sourceUrl,
                            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                            externalSubtitles = stream.externalSubtitles,
                            streamType = stream.streamType,
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            seasonNumber = launch.seasonNumber,
                            episodeNumber = launch.episodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            streamTitle = stream.streamLabel,
                            streamSubtitle = stream.streamSubtitle,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                            pauseDescription = pauseDescription,
                            providerName = stream.addonName,
                            providerAddonId = stream.addonId,
                            contentType = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            initialPositionMs = resolvedResumePositionMs ?: 0L,
                            initialProgressFraction = resolvedResumeProgressFraction,
                        )

                        if (!forceInternal && (forceExternal || playerSettings.externalPlayerEnabled)) {
                            streamRouteScope.launch {
                                playbackHandedOff = true
                                openExternalPlayback(playerLaunch)
                                StreamsRepository.cancelLoading()
                            }
                            return
                        }

                        val launchId = PlayerLaunchStore.put(playerLaunch)
                        StreamsRepository.cancelLoading()
                        // The flag's own comment says "set at *every* exit to playback" and this
                        // exit did not set it. That mattered for the Streamlined sticky-pin path,
                        // which reaches the player through here and leaves StreamRoute on the
                        // stack: coming back, nothing knew playback had ever been handed off, so
                        // the opaque surface kept painting over a list nobody could see.
                        playbackHandedOff = true
                        navController.navigate(
                            PlayerRoute(launchId = launchId, title = playerLaunch.title)
                        )
                    }

                    var stickyPinHandled by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    LaunchedEffect(playbackRouteDecision, matchingStickyCandidate, stickyPinHandled) {
                        if (playbackRouteDecision !is PlaybackRouteDecision.PlayStickyPin) return@LaunchedEffect
                        val candidate = matchingStickyCandidate ?: return@LaunchedEffect
                        if (stickyPinHandled) return@LaunchedEffect
                        stickyPinHandled = true
                        openSelectedStream(
                            stream = candidate.stream,
                            resolvedResumePositionMs = launch.resumePositionMs,
                            resolvedResumeProgressFraction = launch.resumeProgressFraction,
                            forceExternal = false,
                            forceInternal = false,
                        )
                    }

                    fun openManualStreamOrOfferPin(
                        stream: StreamItem,
                        resolvedResumePositionMs: Long?,
                        resolvedResumeProgressFraction: Float?,
                    ) {
                        val shouldOfferPin = playerSettings.playbackMode == PlaybackMode.STREAMLINED &&
                            stickyContentId != null && (launch.manualSelection || manualSourceListRequested)
                        if (shouldOfferPin) {
                            pendingStickyStreamOpen = PendingStickyStreamOpen(
                                stream = stream,
                                resumePositionMs = resolvedResumePositionMs,
                                resumeProgressFraction = resolvedResumeProgressFraction,
                            )
                        } else {
                            openSelectedStream(
                                stream = stream,
                                resolvedResumePositionMs = resolvedResumePositionMs,
                                resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                forceExternal = false,
                                forceInternal = false,
                            )
                        }
                    }


                    /**
                     * Arms the passive network measurement with what this source really costs.
                     *
                     * Confirmed only after a minute of unstarved playback, in
                     * `observePlaybackForNetworkEstimate` - starting a stream proves nothing.
                     */
                    fun armNetworkObservation(stream: StreamItem) {
                        val candidate = playbackCandidates.firstOrNull { it.stream === stream }
                            ?: return
                        val mbps = PlaybackQualityOptions.bitrateMbps(candidate, playbackSelectionContext)
                            ?: return NetworkQualityRepository.cancelPlaybackObservation()
                        NetworkQualityRepository.notePlaybackBitrate(
                            mbps = mbps,
                            providerId = candidate.facts.debridService ?: candidate.facts.providerId,
                        )
                    }

                    fun completeStreamlinedOptionSelection(option: PlaybackQualityOption) {
                        when (
                            val result = PlaybackSourceSelector.select(
                                option = option,
                                context = playbackSelectionContext,
                            )
                        ) {
                            is PlaybackSelectionResult.Play -> {
                                qualitySheetDismissed = true
                                streamlinedPlaybackStarting = true
                                armNetworkObservation(result.stream)
                                // `select` has already ranked the whole row and handed back
                                // everything behind the winner. Throwing those away is what made
                                // one "not cached" answer the end of the road in Streamlined,
                                // while Instant - seeding the very same chain - stepped past it.
                                // Seeding rather than opening also puts the auto-play effect in
                                // charge, so resolve failures, P2P, reuse-last-link and the
                                // attempt counter all behave identically in both modes.
                                StreamsRepository.seedAutoPlayCandidates(
                                    listOf(result.stream) + result.fallbacks,
                                )
                            }
                            is PlaybackSelectionResult.AskUncached -> {
                                pendingUncachedStream = result.stream
                            }
                            is PlaybackSelectionResult.NeedsManual -> fallBackToSourceList(result.reason)
                        }
                    }

                    /**
                     * Starts an uncached debrid source the user accepted, with a chain behind it.
                     *
                     * It used to call `openSelectedStream` directly, which made this the one
                     * Streamlined start with no fallbacks at all - and it is the start most
                     * likely to need them, because the provider has already said it does not
                     * hold this file. Seeding puts it on the same auto-play path as every
                     * other tier pick.
                     */
                    fun startUncachedStream(uncached: StreamItem) {
                        qualitySheetDismissed = true
                        streamlinedPlaybackStarting = true
                        armNetworkObservation(uncached)
                        // A chain of one, deliberately. Everything else in this row failed the
                        // same cache gate, so there is no better candidate to fall to - what
                        // this buys is the *path*: the progress overlay while the mint runs,
                        // the provider's reason named if it refuses, and the source list
                        // uncovered afterwards instead of a toast under an opaque surface.
                        StreamsRepository.seedAutoPlayCandidates(listOf(uncached))
                    }

                    fun selectStreamlinedOption(option: PlaybackQualityOption) {
                        pendingStreamlinedOptionId = option.id
                        streamlinedSelectionPending = true
                    }

                    // The connection figure on the sheet belongs to the host that would serve
                    // the stream, not to the line in the abstract: on debrid the throughput is
                    // the provider's. Scoped to what Best available would open, which is the
                    // card the estimate is read against most often.
                    val qualityProbeTarget = remember(playbackQualityOptions, playbackSelectionContext) {
                        playbackQualityOptions.firstOrNull()?.let { option ->
                            PlaybackSourceSelector.probeTarget(option, playbackSelectionContext)
                        }
                    }
                    // The flow is a change signal only - it carries whichever provider asked
                    // last. `peek` is the read, and it is pure, which is what keeps a value
                    // derived during composition from writing back into the flow driving it.
                    val networkSignal by NetworkQualityRepository.uiState.collectAsStateWithLifecycle()
                    val isProbingConnection by NetworkStrengthProbe.isProbing.collectAsStateWithLifecycle()
                    val sheetNetworkQuality = remember(networkSignal, qualityProbeTarget?.providerId) {
                        NetworkQualityRepository.peek(qualityProbeTarget?.providerId)
                    }

                    // **Launched into the sheet's own scope, not the effect's.** `qualityProbeTarget`
                    // is derived from `playbackQualityOptions`, which is rebuilt every time an addon
                    // answers - so keying a `LaunchedEffect` body on it cancelled the transfer part
                    // way through on every new source, and the probe was killed by the very fetch it
                    // was meant to run beside. Re-triggering is harmless here: `probe` refuses to
                    // start a second one while the first is in flight, and this scope is cancelled
                    // when the sheet leaves composition, which is the cancellation actually wanted.
                    val probeScope = rememberCoroutineScope()
                    LaunchedEffect(playbackRouteDecision, qualityProbeTarget, qualitySheetDismissed) {
                        if (playbackRouteDecision !is PlaybackRouteDecision.ShowQualitySheet) {
                            return@LaunchedEffect
                        }
                        if (qualitySheetDismissed) return@LaunchedEffect
                        val platform = NetworkQualityRepository.peek(qualityProbeTarget?.providerId)
                        val inputs = NetworkStrengthProbe.Inputs(
                            isMetered = platform.isMetered,
                            isOffline = platform.connectionType == NetworkConnectionType.OFFLINE,
                            // Both keys, because only `plan` knows which one this probe would
                            // end up writing to - a source with a direct URL refreshes its own
                            // host, anything falling back to the CDN refreshes the line.
                            sourceEstimateAgeMs = qualityProbeTarget?.providerId?.let {
                                NetworkQualityRepository.estimateAgeMs(it)
                            },
                            lineEstimateAgeMs = NetworkQualityRepository.estimateAgeMs(null),
                            sourceUrl = qualityProbeTarget?.url,
                            sourceHeaders = qualityProbeTarget?.headers.orEmpty(),
                            providerId = qualityProbeTarget?.providerId,
                            requiredMbps = playbackQualityOptions.firstOrNull()?.requiredMbps,
                        )
                        probeScope.launch { NetworkStrengthProbe.probe(inputs) }
                    }

                    LaunchedEffect(
                        streamlinedSelectionPending,
                        pendingStreamlinedOptionId,
                        playbackQualityOptions,
                        streamsUiState.requestToken,
                        streamsUiState.isAnyLoading,
                        streamsUiState.emptyStateReason,
                    ) {
                        if (!streamlinedSelectionPending) return@LaunchedEffect
                        if (
                            !com.nuvio.app.features.playback.isStreamlinedSelectionReady(
                                requestToken = streamsUiState.requestToken,
                                expectedRequestToken = expectedStreamsRequestToken,
                                isAnyLoading = streamsUiState.isAnyLoading,
                                candidateCount = playbackCandidates.size,
                                hasTerminalEmptyState = streamsUiState.emptyStateReason != null,
                                hasStreams = streamsUiState.groups.any { it.streams.isNotEmpty() },
                            )
                        ) return@LaunchedEffect

                        // Every exit past this point clears the flag. It gates the sheet's
                        // spinner, so a path that returns while it is still set leaves the
                        // user looking at disabled rows with nothing left to complete them.
                        streamlinedSelectionPending = false
                        val optionId = pendingStreamlinedOptionId
                        val option = playbackQualityOptions.firstOrNull { it.id == optionId }
                        // The tapped row can genuinely vanish - a refetch may return a
                        // different catalogue - so fall back to Best available rather than
                        // stranding a user who has already chosen.
                            ?: playbackQualityOptions.firstOrNull()
                        if (option == null) {
                            fallBackToSourceList()
                            return@LaunchedEffect
                        }
                        completeStreamlinedOptionSelection(option)
                    }

                    LaunchedEffect(
                        playbackRouteDecision,
                        playbackCandidates,
                        streamsUiState.requestToken,
                        streamsUiState.isAnyLoading,
                        meteredChoice,
                        instantSelectionHandled,
                    ) {
                        if (playbackRouteDecision !is PlaybackRouteDecision.AutoPick) return@LaunchedEffect
                        if (instantSelectionHandled || reuseNavigated) return@LaunchedEffect
                        if (streamsUiState.requestToken != expectedStreamsRequestToken || streamsUiState.isAnyLoading) {
                            return@LaunchedEffect
                        }
                        val network = NetworkQualityRepository.current()
                        if (network.isMetered && meteredChoice == null) return@LaunchedEffect

                        // The metered cap is a resolution ceiling, applied to the derived rows
                        // exactly as it used to be applied to the preset list.
                        val maxHeight = playerSettings.playbackMeteredCapHeight
                            .takeIf { network.isMetered && meteredChoice == MeteredPlaybackChoice.CAPPED }
                        // Keep giving this show the resolution it got last episode, so long as
                        // the estimate still carries it and the cap still allows it.
                        val instantStickyId = launch.parentMetaId ?: effectiveVideoId
                        val pinnedHeight = BingeGroupCacheRepository
                            .sessionInstantHeight(instantStickyId)
                        fun pickFor(estimateMbps: Double) = PlaybackQualityOptions.stickyAffordable(
                            options = playbackQualityOptions,
                            pinnedHeight = pinnedHeight,
                            estimatedMbps = estimateMbps,
                            maxHeight = maxHeight,
                        )

                        val option = pickFor(network.estimatedMbps)
                        val first = option?.let {
                            PlaybackSourceSelector.select(option = it, context = playbackSelectionContext)
                        } ?: PlaybackSelectionResult.NeedsManual(noAutomaticSourceMessage)
                        // On debrid the throughput belongs to the host, not to the line: a fast
                        // connection through a slow provider must not read as "4K is fine".
                        val selection = if (first is PlaybackSelectionResult.Play && !network.isMetered) {
                            val facts = playbackCandidates
                                .firstOrNull { it.stream === first.stream }?.facts
                            val provider = facts?.debridService ?: facts?.providerId
                            val providerEstimate = NetworkQualityRepository.current(provider).estimatedMbps
                            val providerOption = pickFor(providerEstimate)
                            if (providerOption != null && providerOption.id != option?.id) {
                                PlaybackSourceSelector.select(
                                    option = providerOption,
                                    context = playbackSelectionContext,
                                )
                            } else {
                                first
                            }
                        } else {
                            first
                        }
                        instantSelectionHandled = true
                        when (selection) {
                            is PlaybackSelectionResult.Play -> {
                                // The pin and the "playing X" toast are deliberately NOT written
                                // here. This is the source Instant *chose*; the failure chain
                                // below can still advance past it to a different resolution, and
                                // pinning an intent that never played would reintroduce exactly
                                // the churn this is meant to remove. Both happen at the open.
                                armNetworkObservation(selection.stream)
                                StreamsRepository.seedAutoPlayCandidates(
                                    listOf(selection.stream) + selection.fallbacks,
                                )
                            }
                            is PlaybackSelectionResult.AskUncached -> fallBackToSourceList()
                            is PlaybackSelectionResult.NeedsManual ->
                                fallBackToSourceList(selection.reason)
                        }
                    }

                    // Hide overlay when reuse navigated to external player (prevents reload from showing it again)
                    LaunchedEffect(reuseNavigated) {
                        if (reuseNavigated) {
                            StreamsRepository.setOverlayVisible(false)
                        }
                    }

                    // Instant and Streamlined must never leave the user reading the source list
                    // while the app is still deciding. The overlay covers it - it cannot replace
                    // it, because StreamsScreen owns the fetch this is reporting on.
                    val awaitingUserAnswer = pendingUncachedStream != null ||
                        pendingStickyStreamOpen != null ||
                        pendingP2pStreamOpen != null ||
                        (NetworkQualityRepository.current().isMetered && meteredChoice == null)
                    val streamSurface = streamRouteSurface(
                        StreamRouteSurfaceInputs(
                            isClassic = playerSettings.playbackMode == PlaybackMode.CLASSIC,
                            isManualLaunch = launch.manualSelection || launch.downloadIntent,
                            manualSourceListRequested = manualSourceListRequested,
                            isRouteCurrent = navController.currentRoute == route,
                            hasNavigatedAway = reuseNavigated || playbackHandedOff,
                            hasArmedFailureChain = streamsUiState.autoPlayStream != null,
                            isQualitySheetRoute =
                                playbackRouteDecision is PlaybackRouteDecision.ShowQualitySheet,
                            qualitySheetDismissed = qualitySheetDismissed,
                            isAutoPickRoute = playbackRouteDecision is PlaybackRouteDecision.AutoPick,
                            isStreamlinedPlaybackStarting = streamlinedPlaybackStarting,
                            awaitingUserAnswer = awaitingUserAnswer,
                        ),
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        StreamsScreen(
                            type = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            title = launch.title,
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            seasonNumber = launch.seasonNumber,
                            episodeNumber = launch.episodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            resumePositionMs = launch.resumePositionMs,
                            resumeProgressFraction = launch.resumeProgressFraction,
                            manualSelection = streamManualSelection,
                            startFromBeginning = launch.startFromBeginning,
                            downloadOnSelect = launch.downloadIntent,
                            showRepositoryAutoPlayOverlay = playerSettings.playbackMode == PlaybackMode.CLASSIC,
                            onStreamSelected = { stream, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                                openManualStreamOrOfferPin(
                                    stream = stream,
                                    resolvedResumePositionMs = resolvedResumePositionMs,
                                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                )
                            },
                            onStreamActionOpen = { stream, openExternally, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                                openSelectedStream(
                                    stream = stream,
                                    resolvedResumePositionMs = resolvedResumePositionMs,
                                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                    forceExternal = openExternally,
                                    forceInternal = !openExternally,
                                )
                            },
                            onBack = onBack,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // StreamsScreen owns the fetch, but its list is an implementation
                        // detail in Streamlined and Instant. Paint an opaque hand-off surface
                        // from the first frame; sheets and PlaybackProgressOverlay render above
                        // it, while every bail-out removes it - see `streamRouteSurface`.
                        //
                        // It consumes pointer input on purpose. Without that it painted over a
                        // source list that was still fully tappable underneath, so the one
                        // state where this surface should never be resting was also one where
                        // an invisible row could be started by a stray tap.
                        if (streamSurface != StreamRouteSurface.SourceList) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.nuvio.colors.background)
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent().changes.forEach { it.consume() }
                                            }
                                        }
                                    },
                            )
                        }
                        if (streamSurface == StreamRouteSurface.QualitySheet) {
                            PlaybackQualitySheet(
                                options = playbackQualityOptions,
                                // Only "the figures are still moving" belongs here. Selection
                                // pending is passed separately: folding it in would replace
                                // the grid with a skeleton the instant the user tapped a card
                                // - and on the uncached-debrid path, which leaves the sheet
                                // composed under the consent dialog, they would watch it
                                // happen.
                                isLoading = streamsUiState.requestToken != expectedStreamsRequestToken ||
                                    streamsUiState.isAnyLoading,
                                isSelecting = streamlinedSelectionPending,
                                selectionContext = playbackSelectionContext,
                                // Always a figure, never an unlabelled one. Hiding it until a
                                // measurement landed took the connection meters off every card
                                // as well, so a connection that could not be measured showed
                                // less than before the measuring existed. The sheet says which
                                // kind of number it is holding.
                                estimatedMbps = sheetNetworkQuality.estimatedMbps,
                                isConnectionMeasured = sheetNetworkQuality.isMeasured,
                                isMeasuringConnection = isProbingConnection,
                                onOptionSelected = ::selectStreamlinedOption,
                                onChooseManually = {
                                    streamlinedSelectionPending = false
                                    pendingStreamlinedOptionId = null
                                    qualitySheetDismissed = true
                                    manualSourceListRequested = true
                                },
                                onDismiss = {
                                    streamlinedSelectionPending = false
                                    pendingStreamlinedOptionId = null
                                    qualitySheetDismissed = true
                                    qualitySheetDismissRequested = true
                                    onBack()
                                },
                            )
                        }
                        pendingUncachedStream?.let { uncached ->
                            AlertDialog(
                                onDismissRequest = { pendingUncachedStream = null },
                                title = { Text(stringResource(Res.string.playback_uncached_title)) },
                                text = { Text(stringResource(Res.string.playback_uncached_description)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            pendingUncachedStream = null
                                            startUncachedStream(uncached)
                                        },
                                    ) { Text(stringResource(Res.string.playback_uncached_start)) }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            pendingUncachedStream = null
                                            qualitySheetDismissed = true
                                            manualSourceListRequested = true
                                        },
                                    ) { Text(stringResource(Res.string.playback_quality_manual)) }
                                },
                            )
                        }
                        if (
                            playbackRouteDecision is PlaybackRouteDecision.AutoPick &&
                            NetworkQualityRepository.current().isMetered &&
                            meteredChoice == null
                        ) {
                            AlertDialog(
                                onDismissRequest = {
                                    NetworkQualityRepository.rememberMeteredChoice(MeteredPlaybackChoice.CAPPED)
                                    meteredChoice = MeteredPlaybackChoice.CAPPED
                                },
                                title = { Text(stringResource(Res.string.playback_metered_title)) },
                                text = { Text(stringResource(Res.string.playback_metered_description)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        NetworkQualityRepository.rememberMeteredChoice(MeteredPlaybackChoice.CAPPED)
                                        meteredChoice = MeteredPlaybackChoice.CAPPED
                                    }) { Text(stringResource(Res.string.playback_metered_capped)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        NetworkQualityRepository.rememberMeteredChoice(MeteredPlaybackChoice.FULL_QUALITY)
                                        meteredChoice = MeteredPlaybackChoice.FULL_QUALITY
                                    }) { Text(stringResource(Res.string.playback_metered_full)) }
                                },
                            )
                        }
                        pendingStickyStreamOpen?.let { pending ->
                            AlertDialog(
                                onDismissRequest = {
                                    pendingStickyStreamOpen = null
                                    openSelectedStream(
                                        pending.stream,
                                        pending.resumePositionMs,
                                        pending.resumeProgressFraction,
                                        forceExternal = false,
                                        forceInternal = false,
                                    )
                                },
                                title = { Text(stringResource(Res.string.playback_sticky_title)) },
                                text = { Text(stringResource(Res.string.playback_sticky_description)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            val facts = SourceFactsExtractor.extract(pending.stream)
                                            stickyContentId?.let { contentId ->
                                                BingeGroupCacheRepository.saveSessionPin(
                                                    contentId,
                                                    StickySourcePin(
                                                        releaseGroup = facts.releaseGroup,
                                                        bingeGroup = pending.stream.behaviorHints.bingeGroup,
                                                        addonId = pending.stream.addonId,
                                                        providerId = facts.providerId,
                                                        resolutionHeight = facts.resolution?.height,
                                                    ),
                                                )
                                            }
                                            pendingStickyStreamOpen = null
                                            openSelectedStream(
                                                pending.stream,
                                                pending.resumePositionMs,
                                                pending.resumeProgressFraction,
                                                forceExternal = false,
                                                forceInternal = false,
                                            )
                                        },
                                    ) { Text(stringResource(Res.string.playback_sticky_use)) }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            pendingStickyStreamOpen = null
                                            openSelectedStream(
                                                pending.stream,
                                                pending.resumePositionMs,
                                                pending.resumeProgressFraction,
                                                forceExternal = false,
                                                forceInternal = false,
                                            )
                                        },
                                    ) { Text(stringResource(Res.string.playback_sticky_once)) }
                                },
                            )
                        }
                        pendingP2pStreamOpen?.let { pending ->
                            P2pConsentDialog(
                                onEnableP2p = {
                                    P2pSettingsRepository.setP2pEnabled(true)
                                    pendingP2pStreamOpen = null
                                    openP2pStream(
                                        stream = pending.stream,
                                        resolvedResumePositionMs = pending.resumePositionMs,
                                        resolvedResumeProgressFraction = pending.resumeProgressFraction,
                                        replaceStreamRoute = pending.isAutoPlay,
                                    )
                                },
                                onDismiss = {
                                    if (pending.isAutoPlay) {
                                        StreamsRepository.skipAutoPlayStream(pending.stream)
                                        // The chain is retired here, not advanced: declining
                                        // P2P is a decision about every torrent candidate, not
                                        // about this one. So there is nothing left to run, and
                                        // without uncovering the list the progress overlay sat
                                        // on "Starting playback" for a playback that had just
                                        // been called off.
                                        StreamsRepository.consumeAutoPlay()
                                        fallBackToSourceList()
                                    }
                                    pendingP2pStreamOpen = null
                                },
                            )
                        }
                        if (streamSurface == StreamRouteSurface.ProgressOverlay) {
                            PlaybackProgressOverlay(
                                step = PlaybackProgress.step(
                                    PlaybackProgressInputs(
                                        isLoadingSources = streamsUiState.requestToken != expectedStreamsRequestToken ||
                                            streamsUiState.isAnyLoading,
                                        hasChosenSource = instantSelectionHandled || streamlinedPlaybackStarting,
                                        isResolvingLink = resolvingDebridStream,
                                        attempt = autoPickAttempt,
                                    ),
                                ),
                                attempt = autoPickAttempt,
                            )
                        } else if (resolvingDebridStream) {
                            // Classic and every manual path keep the lighter scrim: the source
                            // list behind it is what the user chose from and is worth keeping
                            // visible.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.nuvio.colors.overlayScrim.copy(alpha = MaterialTheme.nuvio.opacity.overlayHeavy)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.cardPadding),
                                ) {
                                    NuvioLoadingIndicator(color = MaterialTheme.nuvio.colors.playerControlsForeground)
                                    Text(
                                        text = stringResource(Res.string.streams_finding_source),
                                        color = MaterialTheme.nuvio.colors.playerControlsForeground.copy(alpha = MaterialTheme.nuvio.opacity.overlayHeavy),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                entry<PlayerRoute>(
                    metadata = if (isIos) {
                        NavDisplay.transitionSpec {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(220))
                        } + NavDisplay.popTransitionSpec {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(220))
                        }
                    } else {
                        emptyMap()
                    },
                ) { route ->
                    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
                    if (launch == null) {
                        val onBack = rememberGuardedPopBackStack(navController, route)
                        LaunchedEffect(route.launchId) {
                            onBack()
                        }
                        Box(modifier = Modifier.fillMaxSize())
                        return@entry
                    }
                    val detailsRoute = remember(launch.parentMetaType, launch.parentMetaId, launch.title) {
                        DetailRoute(
                            type = launch.parentMetaType,
                            id = launch.parentMetaId,
                            title = launch.title,
                        )
                    }
                    val onBackToDetails: () -> Unit = remember(navController, route, detailsRoute) {
                        {
                            val hasMatchingDetails = navController.routes.any { candidate ->
                                candidate is DetailRoute &&
                                    candidate.type == detailsRoute.type &&
                                    candidate.id == detailsRoute.id
                            }
                            when {
                                hasMatchingDetails -> navController.navigate(detailsRoute) {
                                    popUpTo<DetailRoute>()
                                    launchSingleTop = true
                                }
                                navController.routes.any { it is StreamRoute } -> navController.navigate(detailsRoute) {
                                    popUpTo<StreamRoute> { inclusive = true }
                                }
                                else -> navController.navigate(detailsRoute) {
                                    popUpTo<PlayerRoute> { inclusive = true }
                                }
                            }
                        }
                    }
                    /**
                     * Where Instant goes when a source dies: back to the `StreamRoute` it
                     * deliberately left on the back stack, not out to details.
                     *
                     * That route hosts the whole failure chain - the auto-play effect keyed on
                     * `autoPlayStream`, the retry counter and the "Finding a source" overlay - so
                     * popping past it is what turned a recoverable failure into a dead end and
                     * dropped the user on the details screen mid-play. When the chain is
                     * exhausted it is also the right destination: the plan's fallback is the
                     * Classic source list with a reason, and with `autoPlayStream` cleared that
                     * is exactly what `StreamRoute` renders.
                     *
                     * Falls back to details when there is no `StreamRoute` to return to, which
                     * the reuse-last-link and P2P paths can both produce.
                     */
                    val onPlaybackFailureExit: () -> Unit = remember(navController, route, onBackToDetails) {
                        {
                            // The pop is a no-op unless the player is genuinely on top, so it
                            // falls through to the navigating exit rather than leaving the user
                            // on a dead player with `instantFailureHandled` already spent.
                            val popped = navController.routes.any { it is StreamRoute } &&
                                navController.popBackStack(expectedRoute = route)
                            if (!popped) onBackToDetails()
                        }
                    }
                    val noAutomaticSourceText = stringResource(Res.string.playback_quality_no_match)
                    var instantFailureHandled by rememberSaveable(route.launchId) { mutableStateOf(false) }
                    LaunchedEffect(launch.videoId) {
                        launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
                    }
                    PlayerScreen(
                        profileId = launch.profileId,
                        title = launch.title,
                        sourceUrl = launch.sourceUrl,
                        sourceAudioUrl = launch.sourceAudioUrl,
                        sourceHeaders = launch.sourceHeaders,
                        sourceResponseHeaders = launch.sourceResponseHeaders,
                        externalSubtitles = launch.externalSubtitles,
                        streamType = launch.streamType,
                        logo = launch.logo,
                        poster = launch.poster,
                        background = launch.background,
                        seasonNumber = launch.seasonNumber,
                        episodeNumber = launch.episodeNumber,
                        episodeTitle = launch.episodeTitle,
                        episodeThumbnail = launch.episodeThumbnail,
                        streamTitle = launch.streamTitle,
                        streamSubtitle = launch.streamSubtitle,
                        initialBingeGroup = launch.bingeGroup,
                        pauseDescription = launch.pauseDescription,
                        providerName = launch.providerName,
                        providerAddonId = launch.providerAddonId,
                        contentType = launch.contentType,
                        videoId = launch.videoId,
                        parentMetaId = launch.parentMetaId,
                        parentMetaType = launch.parentMetaType,
                        torrentInfoHash = launch.torrentInfoHash,
                        torrentFileIdx = launch.torrentFileIdx,
                        torrentFilename = launch.torrentFilename,
                        torrentTrackers = launch.torrentTrackers,
                        initialPositionMs = launch.initialPositionMs,
                        initialProgressFraction = launch.initialProgressFraction,
                        contentLanguage = launch.contentLanguage,
                        onBack = onBackToDetails,
                        onOpenInExternalPlayer = { request ->
                            val playerLaunch = PlayerLaunch(
                                profileId = launch.profileId,
                                title = launch.title,
                                sourceUrl = request.sourceUrl,
                                sourceHeaders = request.sourceHeaders,
                                logo = launch.logo,
                                poster = launch.poster,
                                background = launch.background,
                                seasonNumber = launch.seasonNumber,
                                episodeNumber = launch.episodeNumber,
                                episodeTitle = launch.episodeTitle,
                                episodeThumbnail = launch.episodeThumbnail,
                                streamTitle = request.streamTitle ?: launch.streamTitle,
                                streamSubtitle = launch.streamSubtitle,
                                bingeGroup = launch.bingeGroup,
                                pauseDescription = launch.pauseDescription,
                                providerName = launch.providerName,
                                providerAddonId = launch.providerAddonId,
                                contentType = launch.contentType,
                                videoId = launch.videoId,
                                parentMetaId = launch.parentMetaId,
                                parentMetaType = launch.parentMetaType,
                                initialPositionMs = request.resumePositionMs,
                            )
                            lastExternalPlayerLaunch = playerLaunch
                            val intentResult = ExternalPlayerPlatform.buildIntent(
                                request = request,
                                playerId = playerSettingsUiState.externalPlayerId,
                            )
                            when (intentResult) {
                                is ExternalPlayerIntentResult.Success -> {
                                    val launched = launchExternalPlayer(intentResult)
                                    if (!launched) {
                                        NuvioToastController.show(externalPlayerFailedText)
                                    }
                                }
                                ExternalPlayerIntentResult.NotConfigured -> {
                                    NuvioToastController.show(externalPlayerNotConfiguredText)
                                }
                                ExternalPlayerIntentResult.Failed -> {
                                    NuvioToastController.show(externalPlayerFailedText)
                                }
                            }
                        },
                        onOpenExternalUrl = { url ->
                            openExternalStreamUrl(url)
                        },
                        onFatalPlaybackError = if (launch.autoPickedWithFailureChain) {
                            {
                                if (!instantFailureHandled) {
                                    instantFailureHandled = true
                                    val failed = StreamsRepository.uiState.value.autoPlayStream
                                    // A null `autoPlayStream` here does not mean the chain is
                                    // spent - it means playback started, and `onPlaybackStarted`
                                    // consumed it. That is the common failure: a source that
                                    // opens, plays a second, and dies.
                                    val hasNext = if (failed != null) {
                                        StreamsRepository.skipAutoPlayStream(failed)
                                    } else {
                                        StreamsRepository.failOverAfterPlaybackStarted()
                                    }
                                    if (!hasNext) {
                                        StreamsRepository.consumeAutoPlay()
                                        NuvioToastController.show(noAutomaticSourceText)
                                    }
                                    onPlaybackFailureExit()
                                }
                            }
                        } else null,
                        onPlaybackStarted = if (launch.autoPickedWithFailureChain) {
                            { StreamsRepository.consumeAutoPlay() }
                        } else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<CatalogRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(navController, route)
                    val launch = remember(route.launchId) { CatalogLaunchStore.get(route.launchId) }
                    if (launch == null) {
                        LaunchedEffect(route.launchId) {
                            onBack()
                        }
                        return@entry
                    }
                    val target = launch.target
                    CatalogScreen(
                        title = launch.title,
                        subtitle = launch.subtitle,
                        target = target,
                        onBack = onBack,
                        onPosterClick = { meta ->
                            navController.navigate(DetailRoute(type = meta.type, id = meta.id, title = meta.name))
                        },
                        onPosterLongClick = { meta ->
                            openPosterActions(
                                if (target is CatalogTarget.Library) {
                                    PosterActionTarget(
                                        preview = meta,
                                        libraryItem = meta.toLibraryItem(savedAtEpochMs = 0L),
                                        libraryListKey = target.sectionType,
                                    )
                                } else {
                                    PosterActionTarget(preview = meta)
                                },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<HomescreenSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    HomescreenSettingsScreen(
                        onBack = onBack,
                    )
                }
                entry<MetaScreenSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    MetaScreenSettingsScreen(
                        onBack = onBack,
                    )
                }
                entry<ContinueWatchingSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    ContinueWatchingSettingsScreen(
                        onBack = onBack,
                    )
                }
                entry<SettingsPageRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        initialPageName = route.pageName,
                        rootActionsEnabled = false,
                        onNavigatePage = { pageName, title ->
                            navController.navigate(SettingsPageRoute(pageName, title))
                        },
                        onExternalBack = onBack,
                        showInternalHeader = !useNativeNavigation,
                        onDownloadsClick = {
                            navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle))
                        },
                        onCollectionsClick = {
                            navController.navigate(CollectionsRoute(collectionsTitle))
                        },
                        onWhatsNewClick = onWhatsNewClick,
                        onCheckForUpdatesClick = if (AppFeaturePolicy.inAppUpdaterEnabled) {
                            {
                                appUpdaterController.checkForUpdates(
                                    force = true,
                                    showNoUpdateFeedback = true,
                                )
                            }
                        } else {
                            null
                        },
                        onTestUpdateBannerClick = if (
                            AppFeaturePolicy.inAppUpdaterEnabled && AppUpdaterPlatform.isDebugBuild
                        ) {
                            appUpdaterController::showDebugTestUpdate
                        } else {
                            null
                        },
                    )
                }
                entry<DownloadsSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    DownloadsSettingsScreen(
                        onBack = onBack,
                    )
                }
                entry<DownloadShowRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    DownloadsScreen(
                        onBack = onBack,
                        onOpenDownload = ::openDownloadedItem,
                        initialShowId = route.showId,
                        onBackFromShow = onBack,
                    )
                }
                entry<AddonsSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    AddonsSettingsScreen(
                        onBack = onBack,
                    )
                }
                if (AppFeaturePolicy.pluginsEnabled) {
                    entry<PluginsSettingsRoute> { route ->
                        val onBack = rememberGuardedPopBackStack(
                            navController = navController,
                            route = route,
                        )
                        PluginsSettingsScreen(
                            onBack = onBack,
                        )
                    }
                }
                entry<AccountSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    AccountSettingsScreen(
                        onBack = onBack,
                    )
                }
                entry<SupportersContributorsSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                        SupportersContributorsSettingsScreen(
                            onBack = onBack,
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            onBack()
                        }
                    }
                }
                entry<LicensesAttributionsSettingsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    LicensesAttributionsSettingsScreen(
                        onBack = onBack,
                    )
                }
                entry<CollectionsRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    CollectionManagementScreen(
                        onBack = onBack,
                        onNavigateToEditor = { collectionId ->
                            val editorTitle = collectionId
                                ?.let { id ->
                                    CollectionRepository.collections.value.firstOrNull { it.id == id }?.title
                                }
                                .orEmpty()
                            navController.navigate(
                                CollectionEditorRoute(
                                    collectionId = collectionId,
                                    title = editorTitle.ifBlank { newCollectionTitle },
                                )
                            )
                        },
                    )
                }
                entry<CollectionEditorRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    CollectionEditorScreen(
                        collectionId = route.collectionId,
                        onBack = onBack,
                        initialPage = if (useNativeNavigation) CollectionEditorPage.Root else null,
                        onNavigateToPage = if (useNativeNavigation) {
                            { page, title ->
                                navController.navigate(
                                    CollectionEditorPageRoute(
                                        collectionId = route.collectionId,
                                        pageName = page.name,
                                        title = title,
                                    )
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                entry<CollectionEditorPageRoute> { route ->
                    val page = remember(route.pageName) {
                        runCatching { CollectionEditorPage.valueOf(route.pageName) }.getOrNull()
                    }
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        route = route,
                    )
                    if (page == null || page == CollectionEditorPage.Root) {
                        LaunchedEffect(route) { onBack() }
                        return@entry
                    }
                    CollectionEditorScreen(
                        collectionId = route.collectionId,
                        initialPage = page,
                        initializeRepository = false,
                        onBack = onBack,
                        onNavigateToPage = { nextPage, title ->
                            navController.navigate(
                                CollectionEditorPageRoute(
                                    collectionId = route.collectionId,
                                    pageName = nextPage.name,
                                    title = title,
                                )
                            )
                        },
                    )
                }
                entry<FolderDetailRoute> { route ->
                    val onBack = rememberGuardedPopBackStack(navController, route)
                    LaunchedEffect(route.collectionId, route.folderId) {
                        FolderDetailRepository.initialize(route.collectionId, route.folderId)
                    }
                    FolderDetailScreen(
                        onBack = onBack,
                        onCatalogClick = onCatalogClick,
                        onPosterClick = { meta ->
                            navController.navigate(DetailRoute(type = meta.type, id = meta.id, title = meta.name))
                        },
                    )
                }
                    }.let { provider ->
                        { key ->
                            routeDisposalDecorator.register(
                                key = key,
                                entry = provider(key),
                            )
                        }
                    },
                )
                }
            }
            }

            selectedPosterActionTarget?.let { posterActionTarget ->
                key(posterActionTarget) {
                    val preview = posterActionTarget.preview
                    val isSaved = LibraryRepository.isSaved(preview.id, preview.type)
                    val isWatched = WatchingState.isPosterWatched(
                        watchedKeys = watchedUiState.watchedKeys,
                        item = preview,
                    )
                    // Trakt items long-pressed outside the library open the list picker
                    // instead of removing, so only true removals disintegrate.
                    val removesFromLibrary = isSaved &&
                        (posterActionTarget.libraryItem != null || !isTraktLibrarySource)
                    NuvioPosterZoomActionOverlay(
                        imageUrl = selectedPosterAnchor?.imageUrl ?: preview.poster,
                        title = preview.name,
                        subtitle = preview.releaseInfo
                            ?.takeIf { it.isNotBlank() }
                            ?.let { formatReleaseDateForDisplay(it) }
                            ?: preview.type.replaceFirstChar { char ->
                                if (char.isLowerCase()) char.titlecase() else char.toString()
                            },
                        isWatched = isWatched,
                        anchor = selectedPosterAnchor,
                        actions = listOf(
                            PosterZoomOverlayAction(
                                icon = if (isSaved) Icons.Default.DeleteOutline else Icons.Default.Add,
                                label = if (isSaved) {
                                    stringResource(Res.string.hero_remove_from_library)
                                } else {
                                    stringResource(Res.string.hero_add_to_library)
                                },
                                isDestructive = removesFromLibrary,
                                onSelected = {
                                    val libraryItem = posterActionTarget.libraryItem
                                        ?: preview.toLibraryItem(savedAtEpochMs = 0L)
                                    if (posterActionTarget.libraryItem != null) {
                                        if (isTraktLibrarySource) {
                                            coroutineScope.launch {
                                                runCatching {
                                                    val listKey = posterActionTarget.libraryListKey
                                                    if (listKey.isNullOrBlank()) {
                                                        val currentMembership = LibraryRepository.getMembershipSnapshot(libraryItem)
                                                        LibraryRepository.applyMembershipChanges(
                                                            item = libraryItem,
                                                            desiredMembership = currentMembership.mapValues { false },
                                                        )
                                                    } else {
                                                        LibraryRepository.removeFromList(libraryItem, listKey)
                                                    }
                                                }.onFailure { error ->
                                                    NuvioToastController.show(
                                                        error.message ?: getString(Res.string.trakt_lists_update_failed),
                                                    )
                                                }
                                            }
                                        } else {
                                            LibraryRepository.remove(libraryItem.id)
                                        }
                                    } else {
                                        if (!isTraktLibrarySource) {
                                            LibraryRepository.toggleSaved(libraryItem)
                                        } else {
                                            pickerItem = libraryItem
                                            pickerTitle = preview.name
                                            pickerTabs = LibraryRepository.libraryListTabs()
                                            pickerMembership = pickerTabs.associate { it.key to false }
                                            pickerPending = true
                                            pickerError = null
                                            showLibraryListPicker = true
                                            coroutineScope.launch {
                                                runCatching {
                                                    val snapshot = LibraryRepository.getMembershipSnapshot(libraryItem)
                                                    val tabs = LibraryRepository.libraryListTabs()
                                                    pickerTabs = tabs
                                                    pickerMembership = tabs.associate { tab ->
                                                        tab.key to (snapshot[tab.key] == true)
                                                    }
                                                }.onFailure { error ->
                                                    pickerError = error.message ?: getString(Res.string.trakt_lists_load_failed)
                                                }
                                                pickerPending = false
                                            }
                                        }
                                    }
                                },
                            ),
                            PosterZoomOverlayAction(
                                icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                                label = if (isWatched) {
                                    stringResource(Res.string.hero_mark_unwatched)
                                } else {
                                    stringResource(Res.string.hero_mark_watched)
                                },
                                onSelected = {
                                    coroutineScope.launch {
                                        WatchingActions.togglePosterWatched(preview)
                                    }
                                },
                            ),
                        ),
                        hazeState = posterOverlayHazeState,
                        onDismissed = {
                            selectedPosterActionTarget = null
                            selectedPosterAnchor = null
                        },
                    )
                }
            }

            selectedContinueWatchingForActions?.let { item ->
                selectedContinueWatchingZoomAnchor?.let { anchor ->
                    key(item.videoId, anchor) {
                        val showManualPlayOption = StreamAutoPlayPolicy.isEffectivelyEnabled(playerSettingsUiState)
                        val showDetailsOption = !item.isCloudLibraryContinueWatchingItem()
                        NuvioPosterZoomActionOverlay(
                            imageUrl = cloudLibraryDisplayArtworkUrl(anchor.imageUrl ?: item.poster ?: item.imageUrl),
                            title = item.title,
                            subtitle = localizedContinueWatchingSubtitle(item),
                            depthSurface = NuvioCardDepthSurface.ContinueWatching,
                            anchor = anchor,
                            actions = buildList {
                                if (showDetailsOption) {
                                    add(
                                        PosterZoomOverlayAction(
                                            icon = Icons.Default.Info,
                                            label = stringResource(Res.string.cw_action_go_to_details),
                                            onSelected = {
                                                navController.navigate(
                                                    DetailRoute(
                                                        type = item.parentMetaType,
                                                        id = item.parentMetaId,
                                                        title = item.title,
                                                    ),
                                                )
                                            },
                                        ),
                                    )
                                }
                                if (showManualPlayOption) {
                                    add(
                                        PosterZoomOverlayAction(
                                            icon = Icons.Default.PlayArrow,
                                            label = stringResource(Res.string.play_manually),
                                            onSelected = { onContinueWatchingPlayManually(item) },
                                        ),
                                    )
                                }
                                if (!item.isNextUp) {
                                    add(
                                        PosterZoomOverlayAction(
                                            icon = Icons.Default.Replay,
                                            label = stringResource(Res.string.cw_action_start_from_beginning),
                                            onSelected = { onContinueWatchingStartFromBeginning(item) },
                                        ),
                                    )
                                }
                                add(
                                    PosterZoomOverlayAction(
                                        icon = Icons.Default.DeleteOutline,
                                        label = stringResource(Res.string.cw_action_remove),
                                        isDestructive = true,
                                        onSelected = { onContinueWatchingRemove(item) },
                                    ),
                                )
                            },
                            hazeState = posterOverlayHazeState,
                            onDismissed = {
                                selectedContinueWatchingForActions = null
                                selectedContinueWatchingZoomAnchor = null
                            },
                        )
                    }
                }
            }

            NuvioContinueWatchingActionSheet(
                item = selectedContinueWatchingForActions.takeIf { selectedContinueWatchingZoomAnchor == null },
                showManualPlayOption = StreamAutoPlayPolicy.isEffectivelyEnabled(playerSettingsUiState),
                showDetailsOption = selectedContinueWatchingForActions?.isCloudLibraryContinueWatchingItem() != true,
                onDismiss = { selectedContinueWatchingForActions = null },
                onOpenDetails = {
                    selectedContinueWatchingForActions?.let { item ->
                        navController.navigate(
                            DetailRoute(
                                type = item.parentMetaType,
                                id = item.parentMetaId,
                                title = item.title,
                            ),
                        )
                    }
                },
                onStartFromBeginning = selectedContinueWatchingForActions
                    ?.takeIf { !it.isNextUp }
                    ?.let { item -> { onContinueWatchingStartFromBeginning(item) } },
                onPlayManually = selectedContinueWatchingForActions
                    ?.let { item -> { onContinueWatchingPlayManually(item) } },
                onRemove = {
                    selectedContinueWatchingForActions?.let(onContinueWatchingRemove)
                },
            )

            TraktListPickerDialog(
                visible = showLibraryListPicker,
                title = pickerTitle,
                tabs = pickerTabs,
                membership = pickerMembership,
                isPending = pickerPending,
                errorMessage = pickerError,
                onToggle = { listKey ->
                    pickerMembership = pickerMembership.toMutableMap().apply {
                        this[listKey] = !(this[listKey] == true)
                    }
                },
                onDismiss = {
                    if (!pickerPending) {
                        showLibraryListPicker = false
                        pickerItem = null
                        pickerError = null
                    }
                },
                onSave = {
                    val item = pickerItem ?: return@TraktListPickerDialog
                    coroutineScope.launch {
                        pickerPending = true
                        pickerError = null
                        runCatching {
                            LibraryRepository.applyMembershipChanges(
                                item = item,
                                desiredMembership = pickerMembership,
                            )
                        }.onSuccess {
                            showLibraryListPicker = false
                            pickerItem = null
                            pickerError = null
                        }.onFailure { error ->
                            pickerError = error.message ?: getString(Res.string.trakt_lists_update_failed)
                        }
                        pickerPending = false
                    }
                },
            )

            NuvioStatusModal(
                title = stringResource(Res.string.app_exit_title),
                message = stringResource(Res.string.app_exit_message),
                isVisible = showExitConfirmation,
                confirmText = stringResource(Res.string.action_yes),
                dismissText = stringResource(Res.string.action_no),
                onConfirm = {
                    showExitConfirmation = false
                    platformExitApp()
                },
                onDismiss = {
                    showExitConfirmation = false
                },
            )

            androidx.compose.animation.AnimatedVisibility(
                visibleState = launchOverlayState,
                enter = fadeIn(),
                exit = fadeOut(androidx.compose.animation.core.tween(400)),
            ) {
                AppLaunchOverlay(
                    profileColor = launchOverlayProfileColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Auto-dismiss profile switch overlay
            if (profileSwitchLoading) {
                LaunchedEffect(Unit) {
                    // Brief loading screen while home refreshes for the new profile
                    kotlinx.coroutines.delay(1200)
                    profileSwitchLoading = false
                }
            }

            NuvioFloatingPrompt(
                visible = resumePromptItem != null,
                imageUrl = resumePromptItem?.poster ?: resumePromptItem?.imageUrl,
                title = resumePromptItem?.title.orEmpty(),
                subtitle = resumePromptItem?.let { localizedContinueWatchingSubtitle(it) }.orEmpty(),
                progressFraction = resumePromptItem?.progressFraction ?: 0f,
                actionLabel = stringResource(Res.string.resume_prompt_action),
                onAction = {
                    val item = resumePromptItem ?: return@NuvioFloatingPrompt
                    resumePromptItem = null
                    openContinueWatching(item, false, false)
                },
                onDismiss = { resumePromptItem = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(15f),
            )

            NuvioToastHost(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(20f),
                onAction = { action ->
                    when (action) {
                        NuvioToastAction.OpenDownloads -> openDownloadsTab()
                    }
                },
            )

            }
        }
}

@Composable
private fun rememberGuardedPopBackStack(
    navController: NuvioNavigator,
    route: AppRoute,
    beforePop: () -> Unit = {},
): () -> Unit {
    var popHandled by remember(route) { mutableStateOf(false) }

    return remember(navController, route, popHandled, beforePop) {
        {
            if (!popHandled && navController.currentRoute == route) {
                popHandled = true
                beforePop()
                navController.popBackStack(expectedRoute = route)
            }
        }
    }
}

@Composable
private fun AppTabHost(
    selectedTab: AppScreenTab,
    modifier: Modifier = Modifier,
    searchFocusRequestCount: Int = 0,
    rootActionsEnabled: Boolean = true,
    homeScrollToTopRequests: Flow<Unit>,
    searchScrollToTopRequests: Flow<Unit>,
    libraryScrollToTopRequests: Flow<Unit>,
    downloadsScrollToTopRequests: Flow<Unit>,
    settingsRootActionRequests: Flow<Unit>,
    animateHomeCollectionGifs: Boolean = true,
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onLibraryPosterClick: ((LibraryItem) -> Unit)? = null,
    onLibraryPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    onLibrarySectionViewAllClick: ((LibrarySection, LibrarySortOption) -> Unit)? = null,
    onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    onConnectCloudClick: (() -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingDetails: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSettingsPageClick: ((pageName: String, title: String) -> Unit)? = null,
    onHomescreenSettingsClick: () -> Unit = {},
    onMetaScreenSettingsClick: () -> Unit = {},
    onContinueWatchingSettingsClick: () -> Unit = {},
    onDownloadsSettingsClick: () -> Unit = {},
    onOpenDownload: ((DownloadItem) -> Unit)? = null,
    onDownloadShowClick: ((showId: String, title: String) -> Unit)? = null,
    onChooseBatchEntryManually: ((DownloadBatch, DownloadBatchEntry) -> Unit)? = null,
    onAddonsSettingsClick: () -> Unit = {},
    onPluginsSettingsClick: () -> Unit = {},
    onAccountSettingsClick: () -> Unit = {},
    onSupportersContributorsSettingsClick: () -> Unit = {},
    onLicensesAttributionsSettingsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onWhatsNewClick: (() -> Unit)? = null,
    onTestUpdateBannerClick: (() -> Unit)? = null,
    onCollectionsSettingsClick: () -> Unit = {},
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    requestedSettingsPageName: String? = null,
    onRequestedSettingsPageConsumed: () -> Unit = {},
    onInitialHomeContentRendered: () -> Unit = {},
) {
    val tabStateHolder = rememberSaveableStateHolder()

    Box(modifier = modifier.fillMaxSize()) {
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppScreenTab.Home -> {
                    HomeScreen(
                        modifier = Modifier.fillMaxSize(),
                        animateCollectionGifs = animateHomeCollectionGifs,
                        scrollToTopRequests = homeScrollToTopRequests,
                        onCatalogClick = onCatalogClick,
                        onPosterClick = onPosterClick,
                        onPosterLongClick = onPosterLongClick,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingDetails = onContinueWatchingDetails,
                        onContinueWatchingLongPress = onContinueWatchingLongPress,
                        onFolderClick = onFolderClick,
                        onFirstCatalogRendered = onInitialHomeContentRendered,
                    )
                }

                AppScreenTab.Search -> {
                    SearchScreen(
                        modifier = Modifier.fillMaxSize(),
                        onPosterClick = onPosterClick,
                        onPosterLongClick = onPosterLongClick,
                        searchFocusRequestCount = searchFocusRequestCount,
                        scrollToTopRequests = searchScrollToTopRequests,
                    )
                }

                AppScreenTab.Library -> {
                    LibraryScreen(
                        modifier = Modifier.fillMaxSize(),
                        scrollToTopRequests = libraryScrollToTopRequests,
                        onPosterClick = onLibraryPosterClick,
                        onPosterLongClick = onLibraryPosterLongClick,
                        onSectionViewAllClick = onLibrarySectionViewAllClick,
                        onCloudFilePlay = onCloudFilePlay,
                        onConnectCloudClick = onConnectCloudClick,
                    )
                }

                AppScreenTab.Downloads -> {
                    DownloadsScreen(
                        onOpenDownload = onOpenDownload ?: {},
                        scrollToTopRequests = downloadsScrollToTopRequests,
                        onNavigateToShow = onDownloadShowClick,
                        onOpenSettings = onDownloadsSettingsClick,
                        onChooseBatchEntryManually = onChooseBatchEntryManually,
                    )
                }

                AppScreenTab.Settings -> {
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        rootActionRequests = settingsRootActionRequests,
                        requestedPageName = requestedSettingsPageName,
                        onRequestedPageConsumed = onRequestedSettingsPageConsumed,
                        rootActionsEnabled = rootActionsEnabled,
                        onNavigatePage = onSettingsPageClick,
                        onSwitchProfile = onSwitchProfile,
                        onHomescreenClick = onHomescreenSettingsClick,
                        onMetaScreenClick = onMetaScreenSettingsClick,
                        onContinueWatchingClick = onContinueWatchingSettingsClick,
                        onDownloadsClick = onDownloadsSettingsClick,
                        onAddonsClick = onAddonsSettingsClick,
                        onPluginsClick = onPluginsSettingsClick,
                        onAccountClick = onAccountSettingsClick,
                        onSupportersContributorsClick = onSupportersContributorsSettingsClick,
                        onLicensesAttributionsClick = onLicensesAttributionsSettingsClick,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                        onWhatsNewClick = onWhatsNewClick,
                        onTestUpdateBannerClick = onTestUpdateBannerClick,
                        onCollectionsClick = onCollectionsSettingsClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabletFloatingTopBar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding + NuvioTokens.Space.s10, bottom = tokens.spacing.controlGap),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = tokens.colors.surface.copy(alpha = tokens.opacity.visible - tokens.opacity.subtle),
            shape = tokens.shapes.chip,
            tonalElevation = tokens.elevation.playerControls,
            shadowElevation = tokens.elevation.overlay,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s10, vertical = tokens.spacing.controlGap),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_home),
                    selected = selectedTab == AppScreenTab.Home,
                    onClick = { onTabSelected(AppScreenTab.Home) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Home) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_search),
                    selected = selectedTab == AppScreenTab.Search,
                    onClick = { onTabSelected(AppScreenTab.Search) },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_search),
                            contentDescription = stringResource(Res.string.compose_nav_search),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Search) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_library),
                    selected = selectedTab == AppScreenTab.Library,
                    onClick = { onTabSelected(AppScreenTab.Library) },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_library),
                            contentDescription = stringResource(Res.string.compose_nav_library),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Library) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_downloads),
                    selected = selectedTab == AppScreenTab.Downloads,
                    onClick = { onTabSelected(AppScreenTab.Downloads) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(Res.string.compose_nav_downloads),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Downloads) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                Surface(
                    color = if (selectedTab == AppScreenTab.Settings) {
                        tokens.colors.overlaySelected
                    } else {
                        tokens.colors.surface
                    },
                    shape = tokens.shapes.chip,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = tokens.spacing.listGap, vertical = tokens.spacing.controlGap),
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileSwitcherTab(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                            onProfileSelected = onProfileSelected,
                            onAddProfileRequested = onAddProfileRequested,
                        )
                        Text(
                            text = stringResource(Res.string.compose_nav_profile),
                            modifier = Modifier.clickable { onTabSelected(AppScreenTab.Settings) },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedTab == AppScreenTab.Settings) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

@Composable
private fun TabletTopPillItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        color = if (selected) tokens.colors.overlaySelected else tokens.colors.surface,
        shape = tokens.shapes.chip,
        tonalElevation = if (selected) tokens.elevation.raised else tokens.elevation.flat,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.components.chipHorizontalPadding, vertical = NuvioTokens.Space.s10),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    tokens.colors.textPrimary
                } else {
                    tokens.colors.textMuted
                },
            )
        }
    }
}

@Composable
private fun AppLaunchOverlay(
    profileColor: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .zIndex(NuvioTokens.Z.dialog),
        contentAlignment = Alignment.Center,
    ) {
        ProfileMeshBackground(
            profileColor = profileColor,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.app_logo_wordmark),
                contentDescription = stringResource(Res.string.app_brand_name),
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(44.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(modifier = Modifier.height(tokens.spacing.sectionGap))
            NuvioLoadingIndicator(color = tokens.colors.accent)
        }
    }
}
