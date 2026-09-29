package com.nuvio.app.features.setup

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import com.nuvio.app.features.social.holdsLiveParty
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.SubtitleRenderer
import com.nuvio.app.features.settings.ZNestedSettingsPage
import com.nuvio.app.features.settings.ZNestedSettingsPageHost
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedChoice
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.details.EpisodeRatingsVisibility
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.downloads.DownloadDeviceSettings
import com.nuvio.app.features.downloads.DownloadMobileDataRule
import com.nuvio.app.features.downloads.DownloadModeCard
import com.nuvio.app.features.downloads.DownloadModeOrder
import com.nuvio.app.features.downloads.DownloadPolicyRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.downloadMobileDataLabel
import com.nuvio.app.features.downloads.downloadModeName
import com.nuvio.app.features.downloads.forDownloads
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleBackgroundColorSwatches
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackModeCard
import com.nuvio.app.features.playback.playbackModeName
import com.nuvio.app.features.playback.playbackQualityLimitShortName
import com.nuvio.app.features.downloads.AudioPreference
import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.LanguageSelectionDialog
import com.nuvio.app.features.settings.LanguageSelectionOption
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.settings.ZSettingsNavigation
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialIdentityBody
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.social.shutdownSocialLayer
import com.nuvio.app.features.streams.StreamBackgroundMode
import com.nuvio.app.features.streams.StreamBadgePlacement
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

// String keys by wildcard, as `SetupWizardScreen.kt` does: this screen reads about a hundred.

/**
 * Advanced Setup (plan §5): an optional hub over the app, rerunnable, never gating.
 *
 * ## What it is, and what it is not
 *
 * A **hub of curated categories**, each one to three short panels over a live preview, every
 * control writing the same repository its Settings row writes - the setup wizard's "no draft"
 * rule. It is not Settings one page at a time: the category list, the panels and their controls
 * all come from `AdvancedSetupModel.kt` (pure, tested), which is where "what is worth guiding" is
 * decided. This file only draws that answer.
 *
 * - Categories open in any order and can be revisited; finishing one returns to the hub with a
 *   session-only tick.
 * - "Take the full tour" walks every panel of every listed category in hub order and ends on the
 *   hub. It is the same panels, not a second flow.
 * - Always dismissible: ✕, system back from the hub, or Done on the last panel. Its only side
 *   effects are the settings themselves and the "opened" flag behind the Settings badge.
 *
 * ## Layout
 *
 * The hub is a card grid (one column on a phone, two on a tablet, three on a desktop window). A
 * panel reuses the wizard's frames: on a desktop window at least 1000 dp wide, the preview and the
 * controls side by side through `SetupDesktopSplitFrame`; everywhere else the wizard's stacked
 * shape - preview band on top, opaque panel below, a hairline between, nothing drawn behind text.
 *
 * ⚠ **Drawn over the app, so it consumes pointer input** - the wizard's `nuvioConsumePointerEvents`
 * lesson, which has shipped as a bug twice.
 */
@Composable
fun AdvancedSetupScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val live = rememberAdvancedSetupLive()
    val socialFailed = stringResource(Res.string.advanced_setup_social_save_failed)
    val social = remember(scope) { AdvancedSocialWrites(scope) { NuvioToastController.show(socialFailed) } }
    val partySession by remember { WatchPartySessionCoordinator.state }.collectAsStateWithLifecycle()
    // The switches show the user's choice at once; the repositories catch up (`OptimisticSetting`).
    social.Observe(live.values)
    val values = social.applyTo(live.values)
    val facts = live.facts.copy(socialEnabled = values.socialEnabled)

    // Opening the hub is what clears Settings' "New" badge, for this profile on this device.
    LaunchedEffect(Unit) { AdvancedSetupBadge.markOpened(ProfileRepository.activeProfileId) }

    var panelName by rememberSaveable { mutableStateOf<String?>(null) }
    var touring by rememberSaveable { mutableStateOf(false) }
    // "Set up in Settings": the real page, drawn over the hub (which stays exactly where it was).
    var nestedSettingsName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmLeaveParty by remember { mutableStateOf(false) }
    // Session-only on purpose: "reviewed" means "you looked at it this time", not a stored fact.
    val reviewed = remember { mutableStateListOf<AdvancedSetupCategory>() }
    val panel = advancedSetupPanelForSavedName(panelName)

    // A panel can leave the plan under the user (Classic chosen on the mode panel drops the source
    // preferences; social switched off drops sharing). Heal forward like the wizard, never strand.
    LaunchedEffect(facts, panel) {
        val current = panel ?: return@LaunchedEffect
        if (advancedSetupControls(current, facts).isEmpty()) {
            val next = if (touring) nextAdvancedSetupTourStop(current, facts)?.panel else nextAdvancedSetupPanel(current, facts)
            if (next == null) {
                if (current.category !in reviewed) reviewed.add(current.category)
                touring = false
            }
            panelName = next?.name
        }
    }

    fun markReviewed(category: AdvancedSetupCategory) {
        if (category !in reviewed) reviewed.add(category)
    }

    fun openCategory(category: AdvancedSetupCategory) {
        touring = false
        panelName = advancedSetupPanels(category, facts).firstOrNull()?.name
    }

    fun startTour() {
        val first = advancedSetupTour(facts).firstOrNull() ?: return
        touring = true
        panelName = first.panel.name
    }

    fun advance() {
        val current = panel ?: return
        val next = if (touring) {
            nextAdvancedSetupTourStop(current, facts)?.panel
        } else {
            nextAdvancedSetupPanel(current, facts)
        }
        if (next == null || next.category != current.category) markReviewed(current.category)
        if (next == null) touring = false
        panelName = next?.name
    }

    fun back() {
        val current = panel ?: return
        val previous = if (touring) {
            previousAdvancedSetupTourStop(current, facts)?.panel
        } else {
            previousAdvancedSetupPanel(current, facts)
        }
        if (previous == null) touring = false
        panelName = previous?.name
    }

    PlatformBackHandler(enabled = true) {
        if (panel != null) back() else onClose()
    }

    fun setSocialEnabled(enabled: Boolean) {
        // Leaving a party is not a silent side effect of a switch - the same confirmation Settings asks.
        if (!enabled && partySession.phase.holdsLiveParty) {
            confirmLeaveParty = true
            return
        }
        social.setEnabled(enabled)
    }

    fun openSettings(page: SettingsPage) {
        if (ZNestedSettingsPage.supports(page)) {
            nestedSettingsName = page.name
        } else {
            // A page this overlay does not host: leave for real Settings, as before.
            onClose()
            ZSettingsNavigation.open(page)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background)
            .nuvioConsumePointerEvents(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            val wideDesktop = isDesktop && maxWidth >= AdvancedSetupDesktopMinWidth
            // Only the hub <-> panel change crossfades the whole surface. Moving between panels -
            // Next, Back, the whole tour - keeps the frame, the preview area and the footer in place
            // and animates only what changes inside them, as Initial Setup does.
            AnimatedContent(
                targetState = panel == null,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "advanced_setup_hub",
            ) { onHub ->
                if (onHub) {
                    AdvancedSetupHub(
                        facts = facts,
                        values = values,
                        reviewed = reviewed,
                        insets = insets,
                        windowWidth = maxWidth,
                        onOpenCategory = ::openCategory,
                        onStartTour = ::startTour,
                        onClose = onClose,
                    )
                } else {
                    // The last panel shown, held while the hub fades back in.
                    val held = lastPanelShown(panel)
                    val current = panel ?: held
                    if (current != null) {
                        val position = if (touring) {
                            val tour = advancedSetupTour(facts)
                            (tour.indexOfFirst { it.panel == current } + 1).coerceAtLeast(1) to tour.size
                        } else {
                            val panels = advancedSetupPanels(current.category, facts)
                            (panels.indexOf(current) + 1).coerceAtLeast(1) to panels.size
                        }
                        val isLast = if (touring) {
                            nextAdvancedSetupTourStop(current, facts) == null
                        } else {
                            nextAdvancedSetupPanel(current, facts) == null
                        }
                        AdvancedSetupPanelFrame(
                            panel = current,
                            facts = facts,
                            values = values,
                            touring = touring,
                            position = position,
                            isLast = isLast,
                            wideDesktop = wideDesktop,
                            windowHeight = maxHeight,
                            insets = insets,
                            onBack = ::back,
                            onAdvance = ::advance,
                            onClose = onClose,
                            onSocialEnabledChange = ::setSocialEnabled,
                            onSocialSharingChange = social::setSharing,
                            onJoinPolicyChange = social::setJoinPolicy,
                            onOpenSettings = ::openSettings,
                        )
                    }
                }
            }
        }

        val nestedPage = nestedSettingsName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        androidx.compose.animation.AnimatedVisibility(
            visible = nestedPage != null,
            enter = fadeIn(tween(200)) + slideInHorizontally(tween(260, easing = LinearOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 8 },
        ) {
            val held = lastNestedPage(nestedPage)
            val page = nestedPage ?: held
            if (page != null) {
                ZNestedSettingsPageHost(page = page, onBack = { nestedSettingsName = null })
            }
        }
    }

    if (confirmLeaveParty) {
        AlertDialog(
            onDismissRequest = { confirmLeaveParty = false },
            title = { Text(stringResource(Res.string.settings_social_leave_party_title)) },
            text = { Text(stringResource(Res.string.settings_social_leave_party_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeaveParty = false
                        social.setEnabled(false)
                    },
                ) {
                    Text(stringResource(Res.string.settings_social_leave_party_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeaveParty = false }) {
                    Text(stringResource(Res.string.settings_social_leave_party_cancel))
                }
            },
        )
    }
}

/** Remembers the last non-null panel, so an exit animation keeps drawing what was on screen. */
@Composable
private fun lastPanelShown(current: AdvancedSetupPanel?): AdvancedSetupPanel? {
    val last = remember { mutableStateOf<AdvancedSetupPanel?>(null) }
    if (current != null) last.value = current
    return last.value
}

@Composable
private fun lastNestedPage(current: SettingsPage?): SettingsPage? {
    val last = remember { mutableStateOf<SettingsPage?>(null) }
    if (current != null) last.value = current
    return last.value
}

/**
 * Everything Advanced Setup draws, read live from the repositories its controls write - also what
 * Device Setup's navigation and player steps render from, so those steps and the panels they borrow
 * cannot disagree.
 */
@Immutable
internal class AdvancedSetupLive(val facts: AdvancedSetupFacts, val values: AdvancedSetupValues)

@Composable
internal fun rememberAdvancedSetupLive(): AdvancedSetupLive {
    val player by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val posterStyle by remember {
        PosterCardStyleRepository.ensureLoaded()
        PosterCardStyleRepository.uiState
    }.collectAsStateWithLifecycle()
    val depthStyle by remember {
        CardDepthStyleRepository.ensureLoaded()
        CardDepthStyleRepository.uiState
    }.collectAsStateWithLifecycle()
    val homeSettings by remember {
        HomeCatalogSettingsRepository.ensureLoaded()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val metaSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val streamSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val selectedTheme by remember { ThemeSettingsRepository.selectedTheme }.collectAsStateWithLifecycle()
    val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
    val navBarStyle by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
    val navBarGlow by remember { ThemeSettingsRepository.navBarGlowEnabled }.collectAsStateWithLifecycle()
    val liquidGlass by remember { ThemeSettingsRepository.liquidGlassNativeTabBarEnabled }.collectAsStateWithLifecycle()
    val desktopNavLayout by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.desktopNavigationLayout
    }.collectAsStateWithLifecycle()
    val downloadPolicy by remember {
        DownloadPolicyRepository.ensureLoaded()
        DownloadPolicyRepository.policy
    }.collectAsStateWithLifecycle()
    // Read, not loaded: loading the download store starts the engine, so that waits for the panel
    // that asks a device question - the wizard's rule.
    val deviceDownloads by remember { DownloadsRepository.deviceSettings }.collectAsStateWithLifecycle()
    val socialPreferences by remember {
        SocialFeaturePreferencesRepository.ensureLoaded()
        SocialFeaturePreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val socialState by remember { SocialRepository.uiState }.collectAsStateWithLifecycle()
    val tmdb by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val mdbList by remember {
        MdbListSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val profileState by remember { ProfileRepository.state }.collectAsStateWithLifecycle()
    val randomEpisodeAvailable = rememberAdvancedRandomEpisodeAvailable()

    val effectiveDownloadMode = downloadPolicy.effectiveMode(player.playbackMode.forDownloads())
    val platformFacts = remember {
        AdvancedSetupFacts(
            isAndroid = !isIos && !isDesktop,
            isIos = isIos,
            isDesktop = isDesktop,
            navGlowSupported = !isIos && !isDesktop && floatingNavigationGlowSupported,
            liquidGlassSupported = isIos && isLiquidGlassNativeTabBarSupported(),
            downloadsEnabled = AppFeaturePolicy.downloadsEnabled,
            hasTrackingCredentials = TraktAuthRepository.hasRequiredCredentials() || SimklAuthRepository.hasRequiredCredentials(),
        )
    }
    val facts = platformFacts.copy(
        playbackModeName = player.playbackMode.name,
        downloadModeName = effectiveDownloadMode.name,
        socialEnabled = socialPreferences.enabled,
        offerSocialIdentity = socialState.me == null && !socialPreferences.hasKnownIdentity,
        externalPlayer = player.externalPlayerEnabled,
    )
    val values = AdvancedSetupValues(
        playbackMode = player.playbackMode,
        player = player,
        posterWidthDp = posterStyle.widthDp,
        posterCornerRadiusDp = posterStyle.cornerRadiusDp,
        landscapeCards = posterStyle.catalogLandscapeModeEnabled,
        hidePosterLabels = posterStyle.hideLabelsEnabled,
        hoverPreview = posterStyle.hoverPreviewEnabled,
        hoverTrailer = posterStyle.hoverPreviewTrailerEnabled,
        hoverTrailerSound = posterStyle.hoverPreviewTrailerSoundEnabled,
        cardDepth = depthStyle.enabled,
        cardDepthEdge = depthStyle.edgeStrength,
        heroEnabled = homeSettings.heroEnabled,
        showCatalogType = homeSettings.showCatalogType,
        continueWatchingVisible = continueWatching.isVisible,
        continueWatchingStyle = continueWatching.style,
        useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
        blurNextUp = continueWatching.blurNextUp,
        detailBackground = metaSettings.backgroundMode,
        detailTabLayout = metaSettings.tabLayout,
        showOverallRatings = metaSettings.showOverallRatings,
        episodeCardStyle = metaSettings.episodeCardStyle,
        blurUnwatchedEpisodes = metaSettings.blurUnwatchedEpisodes,
        episodeRatings = metaSettings.episodeRatingsVisibility,
        randomEpisodeAvailable = randomEpisodeAvailable,
        mdbListActive = mdbList.isActive,
        streamBackground = streamSettings.backgroundMode,
        streamSizeBadges = streamSettings.showFileSizeBadges,
        streamBadgePlacement = streamSettings.badgePlacement,
        streamAddonLogo = streamSettings.showAddonLogo,
        selectedTheme = selectedTheme,
        amoled = amoledEnabled,
        navBarStyle = navBarStyle,
        navBarGlow = navBarGlow,
        liquidGlass = liquidGlass,
        desktopNavLayout = desktopNavLayout,
        downloadMode = effectiveDownloadMode,
        downloadPolicy = downloadPolicy,
        deviceDownloads = deviceDownloads,
        socialEnabled = socialPreferences.enabled,
        socialSignedIn = profileState.activeProfile?.id?.isNotBlank() == true,
        socialReady = socialState.me != null,
        shareWatchingNow = socialState.me?.shareWatchingNow ?: true,
        shareWatched = socialState.me?.shareRecentlyWatched ?: true,
        joinPolicy = socialState.me?.defaultJoinPolicy ?: WatchJoinPolicy.approval,
        tmdbEnabled = tmdb.enabled,
        tmdbLanguage = tmdb.language,
    )
    return AdvancedSetupLive(facts = facts, values = values)
}

/**
 * The Social switches' writes, shown at once (setup polish, physical QA). Every switch here used to
 * wait for its server round trip before it moved, which read as a frozen control for about two
 * seconds. Each value now goes through an [OptimisticSetting]: shown immediately, written in the
 * background, reverted with a toast only if the server refuses. Turning social off keeps its ordered
 * shutdown - teardown before the flag - it just no longer holds the switch still while it runs.
 */
@Stable
internal class AdvancedSocialWrites(
    private val scope: CoroutineScope,
    private val onFailed: () -> Unit,
) {
    private var enabled by mutableStateOf(OptimisticSetting<Boolean>())
    private var watchingNow by mutableStateOf(OptimisticSetting<Boolean>())
    private var watched by mutableStateOf(OptimisticSetting<Boolean>())
    private var joinPolicy by mutableStateOf(OptimisticSetting<WatchJoinPolicy>())
    private var confirmedWatchingNow = true
    private var confirmedWatched = true

    fun applyTo(values: AdvancedSetupValues): AdvancedSetupValues = values.copy(
        socialEnabled = enabled.shown(values.socialEnabled),
        shareWatchingNow = watchingNow.shown(values.shareWatchingNow),
        shareWatched = watched.shown(values.shareWatched),
        joinPolicy = joinPolicy.shown(values.joinPolicy),
    )

    /** Lets each pending choice clear once the repository agrees (or something newer speaks). */
    @Composable
    fun Observe(confirmed: AdvancedSetupValues) {
        LaunchedEffect(confirmed.socialEnabled) { enabled = enabled.observed(confirmed.socialEnabled) }
        LaunchedEffect(confirmed.shareWatchingNow) {
            confirmedWatchingNow = confirmed.shareWatchingNow
            watchingNow = watchingNow.observed(confirmed.shareWatchingNow)
        }
        LaunchedEffect(confirmed.shareWatched) {
            confirmedWatched = confirmed.shareWatched
            watched = watched.observed(confirmed.shareWatched)
        }
        LaunchedEffect(confirmed.joinPolicy) { joinPolicy = joinPolicy.observed(confirmed.joinPolicy) }
    }

    fun setEnabled(value: Boolean) {
        enabled = enabled.begin(value)
        val generation = enabled.generation
        if (value) {
            SocialFeaturePreferencesRepository.setEnabled(true)
            enabled = enabled.succeeded(generation)
            return
        }
        scope.launch {
            // Teardown before the flag, never after - the wizard's ordered shutdown, unchanged.
            runCatching { shutdownSocialLayer() }
            SocialFeaturePreferencesRepository.setEnabled(false)
            enabled = enabled.succeeded(generation)
        }
    }

    fun setSharing(watchingNowValue: Boolean, watchedValue: Boolean) {
        val nowChanged = watchingNowValue != watchingNow.shown(confirmedWatchingNow)
        val watchedChanged = watchedValue != watched.shown(confirmedWatched)
        if (nowChanged) watchingNow = watchingNow.begin(watchingNowValue)
        if (watchedChanged) watched = watched.begin(watchedValue)
        val nowGeneration = watchingNow.generation
        val watchedGeneration = watched.generation
        scope.launch {
            SocialRepository.setPrivacy(watchingNowValue, watchedValue)
                .onSuccess {
                    watchingNow = watchingNow.succeeded(nowGeneration)
                    watched = watched.succeeded(watchedGeneration)
                }
                .onFailure {
                    val a = watchingNow.failed(nowGeneration)
                    val b = watched.failed(watchedGeneration)
                    watchingNow = a.state
                    watched = b.state
                    if (a.notify || b.notify) onFailed()
                }
        }
    }

    fun setJoinPolicy(value: WatchJoinPolicy) {
        joinPolicy = joinPolicy.begin(value)
        val generation = joinPolicy.generation
        scope.launch {
            SocialRepository.setDefaultJoinPolicy(value)
                .onSuccess { joinPolicy = joinPolicy.succeeded(generation) }
                .onFailure {
                    val failure = joinPolicy.failed(generation)
                    joinPolicy = failure.state
                    if (failure.notify) onFailed()
                }
        }
    }
}

/** Same threshold as the wizard's two-pane layout, for the same reasons (`SetupWizardScreen`). */
private val AdvancedSetupDesktopMinWidth = 1000.dp

/**
 * Every value a panel or preview shows, gathered once by [AdvancedSetupScreen] from the live
 * repositories. Bodies and previews read this and nothing else, which is what lets the render
 * harness draw any panel with fixed values.
 */
internal data class AdvancedSetupValues(
    val playbackMode: PlaybackMode = PlaybackMode.Default,
    val player: com.nuvio.app.features.player.PlayerSettingsUiState = com.nuvio.app.features.player.PlayerSettingsUiState(),
    val posterWidthDp: Int = 126,
    val posterCornerRadiusDp: Int = 12,
    val landscapeCards: Boolean = false,
    val hidePosterLabels: Boolean = false,
    val hoverPreview: Boolean = true,
    val hoverTrailer: Boolean = true,
    val hoverTrailerSound: Boolean = false,
    val cardDepth: Boolean = false,
    val cardDepthEdge: Int = 28,
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val continueWatchingVisible: Boolean = true,
    val continueWatchingStyle: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Card,
    val useEpisodeThumbnails: Boolean = true,
    val blurNextUp: Boolean = false,
    val detailBackground: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Default,
    val detailTabLayout: Boolean = false,
    val showOverallRatings: Boolean = true,
    val episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
    val blurUnwatchedEpisodes: Boolean = false,
    val episodeRatings: EpisodeRatingsVisibility = EpisodeRatingsVisibility.SHOW_ALL,
    /** Random Episode (mobile only): the detail page's Shuffle action is available. */
    val randomEpisodeAvailable: Boolean = false,
    /** MDBList is configured and on, so the detail page shows its multi-source ratings row. */
    val mdbListActive: Boolean = false,
    val streamBackground: StreamBackgroundMode = StreamBackgroundMode.Normal,
    val streamSizeBadges: Boolean = true,
    val streamBadgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    val streamAddonLogo: Boolean = false,
    val selectedTheme: com.nuvio.app.core.ui.AppTheme = com.nuvio.app.core.ui.AppTheme.entries.first(),
    val amoled: Boolean = false,
    val navBarStyle: NavBarStyle = NavBarStyle.entries.first(),
    val navBarGlow: Boolean = true,
    val liquidGlass: Boolean = false,
    val desktopNavLayout: DesktopNavigationLayout = DesktopNavigationLayout.entries.first(),
    val downloadMode: com.nuvio.app.features.downloads.DownloadMode = com.nuvio.app.features.downloads.DownloadMode.MANUAL,
    val downloadPolicy: com.nuvio.app.features.downloads.DownloadPolicy = com.nuvio.app.features.downloads.DownloadPolicy(),
    val deviceDownloads: DownloadDeviceSettings = DownloadDeviceSettings(),
    val socialEnabled: Boolean = false,
    val socialSignedIn: Boolean = false,
    val socialReady: Boolean = false,
    val shareWatchingNow: Boolean = true,
    val shareWatched: Boolean = true,
    val joinPolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
    val tmdbEnabled: Boolean = true,
    val tmdbLanguage: String = "en",
)

// --- hub ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedSetupHub(
    facts: AdvancedSetupFacts,
    values: AdvancedSetupValues,
    reviewed: List<AdvancedSetupCategory>,
    insets: PaddingValues,
    windowWidth: Dp,
    onOpenCategory: (AdvancedSetupCategory) -> Unit,
    onStartTour: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val categories = advancedSetupCategories(facts)
    val columns = when {
        windowWidth >= 1000.dp -> 3
        windowWidth >= 600.dp -> 2
        else -> 1
    }
    val gutter = if (windowWidth >= 600.dp) 32.dp else 20.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Its own background, not only the screen's: the render harness draws the hub alone.
            .background(tokens.colors.background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxWidth()
                .padding(
                    start = gutter,
                    end = gutter,
                    top = insets.calculateTopPadding() + if (windowWidth >= 600.dp) 32.dp else 20.dp,
                    bottom = insets.calculateBottomPadding() + 28.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(Res.string.advanced_setup_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.advanced_setup_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
                AdvancedCloseButton(onClose = onClose)
            }
            AdvancedTourCard(
                panelCount = advancedSetupTour(facts).size,
                onStartTour = onStartTour,
            )
            AdvancedSetupGroup.entries.forEach { group ->
                val inGroup = categories.filter { it.group == group }
                if (inGroup.isEmpty()) return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(group.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                    inGroup.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { category ->
                                AdvancedCategoryCard(
                                    category = category,
                                    summary = advancedCategorySummary(category, facts, values),
                                    reviewed = category in reviewed,
                                    onClick = { onOpenCategory(category) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            SetupFootnote(stringResource(Res.string.advanced_setup_footnote))
        }
    }
}

/**
 * "Take the full tour" as the hub's clear primary path (setup polish, physical QA): an accent card
 * with what it is and how long, and a filled button - where it was a quiet outlined button that read
 * as a footnote. The categories below stay the pick-and-choose route; nothing here is mandatory.
 */
@Composable
private fun AdvancedTourCard(panelCount: Int, onStartTour: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(tokens.colors.accent.copy(alpha = 0.26f), tokens.colors.accent.copy(alpha = 0.08f)),
                ),
            )
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onStartTour)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tokens.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = tokens.colors.onAccent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.advanced_setup_tour),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.advanced_setup_tour_body, panelCount),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
        Button(onClick = onStartTour) {
            Text(stringResource(Res.string.advanced_setup_tour_start))
        }
    }
}

@Composable
private fun AdvancedCategoryCard(
    category: AdvancedSetupCategory,
    summary: String,
    reviewed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .border(
                width = if (reviewed) 1.dp else tokens.borders.hairline,
                color = if (reviewed) tokens.colors.accent.copy(alpha = 0.55f) else tokens.colors.borderSubtle,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(category.icon, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(category.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (reviewed) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(tokens.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = stringResource(Res.string.advanced_setup_reviewed),
                    tint = tokens.colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun AdvancedCloseButton(onClose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(tokens.colors.overlayHover)
            .clickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(Res.string.advanced_setup_close),
            tint = tokens.colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The card's live one-line summary: the current values that matter most, joined. */
@Composable
internal fun advancedCategorySummary(
    category: AdvancedSetupCategory,
    facts: AdvancedSetupFacts,
    values: AdvancedSetupValues,
): String {
    val on = stringResource(Res.string.advanced_setup_on)
    val off = stringResource(Res.string.advanced_setup_off)
    val p = values.player
    val parts: List<String> = when (category) {
        AdvancedSetupCategory.PlaybackMode -> buildList {
            add(playbackModeName(values.playbackMode))
            if (playbackSetupVariant(values.playbackMode.name) != PlaybackSetupVariant.None) {
                add(playbackQualityLimitShortName(p.playbackQualityCeilingMbps))
            }
        }
        AdvancedSetupCategory.Player -> buildList {
            if (facts.isMobile) {
                add(stringResource(if (p.useLegacyPlayerLayout) Res.string.advanced_setup_layout_legacy else Res.string.advanced_setup_layout_current))
            }
            add(stringResource(Res.string.settings_playback_pause_overlay) + " " + (if (p.pauseOverlayEnabled) on else off).lowercase())
        }
        AdvancedSetupCategory.Skipping -> listOf(
            stringResource(Res.string.settings_playback_skip_intro_outro_recap) + " " + (if (p.skipIntroEnabled) on else off).lowercase(),
            stringResource(if (p.streamAutoPlayNextEpisodeEnabled) Res.string.advanced_setup_next_auto else Res.string.advanced_setup_next_ask),
        )
        AdvancedSetupCategory.Subtitles -> listOf(
            stringResource(Res.string.compose_player_font_size_value, p.subtitleStyle.fontSizeSp),
            stringResource(if (p.subtitleStyle.outlineEnabled) Res.string.settings_playback_subtitle_outline else Res.string.advanced_setup_subtitle_no_outline),
        )
        AdvancedSetupCategory.Downloads -> listOf(downloadModeName(values.downloadMode))
        AdvancedSetupCategory.Navigation -> when {
            facts.isDesktop -> listOf(stringResource(values.desktopNavLayout.labelRes), stringResource(values.navBarStyle.labelRes))
            facts.isIos -> listOf(stringResource(Res.string.settings_appearance_liquid_glass) + " " + (if (values.liquidGlass) on else off).lowercase())
            else -> buildList {
                add(stringResource(values.navBarStyle.labelRes))
                if (facts.navGlowSupported && values.navBarStyle != NavBarStyle.CLASSIC) {
                    add(stringResource(if (values.navBarGlow) Res.string.settings_nav_bar_glow_on else Res.string.settings_nav_bar_glow_off))
                }
            }
        }
        AdvancedSetupCategory.Theme -> buildList {
            add(stringResource(values.selectedTheme.labelRes))
            if (values.amoled) add(stringResource(Res.string.settings_appearance_amoled_black))
        }
        AdvancedSetupCategory.Posters -> listOf(
            stringResource(if (values.landscapeCards) Res.string.setup_cards_shape_landscape else Res.string.setup_cards_shape_poster),
            posterWidthLabel(values.posterWidthDp),
            posterRadiusLabel(values.posterCornerRadiusDp),
        )
        AdvancedSetupCategory.Home -> listOf(
            stringResource(Res.string.advanced_setup_hero) + " " + (if (values.heroEnabled) on else off).lowercase(),
            if (values.continueWatchingVisible) continueWatchingStyleLabel(values.continueWatchingStyle) else stringResource(Res.string.advanced_setup_cw_hidden),
        )
        AdvancedSetupCategory.DetailPage -> listOf(
            detailBackgroundLabel(values.detailBackground),
            stringResource(
                if (values.episodeCardStyle == MetaEpisodeCardStyle.List) Res.string.settings_meta_episode_style_list else Res.string.settings_meta_episode_style_horizontal,
            ),
        )
        AdvancedSetupCategory.SourceList -> listOf(
            stringResource(
                if (values.streamBackground == StreamBackgroundMode.Cinematic) Res.string.settings_meta_background_mode_cinematic else Res.string.settings_meta_background_mode_normal,
            ),
            stringResource(Res.string.settings_stream_size_badges_title) + " " + (if (values.streamSizeBadges) on else off).lowercase(),
        )
        AdvancedSetupCategory.Social -> listOf(if (values.socialEnabled) on else off)
        AdvancedSetupCategory.Metadata -> if (values.tmdbEnabled) listOf(on, languageName(values.tmdbLanguage)) else listOf(off)
        AdvancedSetupCategory.Tracking -> listOf(stringResource(Res.string.advanced_setup_tracking_summary))
    }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun languageName(code: String): String =
    AvailableLanguageOptions.firstOrNull { it.code.equals(code.substringBefore('-'), ignoreCase = true) }
        ?.let { stringResource(it.labelRes) }
        ?: code

private val AdvancedSetupGroup.labelRes: StringResource
    get() = when (this) {
        AdvancedSetupGroup.Playback -> Res.string.advanced_setup_group_playback
        AdvancedSetupGroup.Downloads -> Res.string.advanced_setup_group_downloads
        AdvancedSetupGroup.Look -> Res.string.advanced_setup_group_look
        AdvancedSetupGroup.Screens -> Res.string.advanced_setup_group_screens
        AdvancedSetupGroup.SocialAndServices -> Res.string.advanced_setup_group_social
    }

internal val AdvancedSetupCategory.titleRes: StringResource
    get() = when (this) {
        AdvancedSetupCategory.PlaybackMode -> Res.string.advanced_setup_category_playback_mode
        AdvancedSetupCategory.Player -> Res.string.advanced_setup_category_player
        AdvancedSetupCategory.Skipping -> Res.string.advanced_setup_category_skipping
        AdvancedSetupCategory.Subtitles -> Res.string.advanced_setup_category_subtitles
        AdvancedSetupCategory.Downloads -> Res.string.advanced_setup_category_downloads
        AdvancedSetupCategory.Navigation -> Res.string.advanced_setup_category_navigation
        AdvancedSetupCategory.Theme -> Res.string.advanced_setup_category_theme
        AdvancedSetupCategory.Posters -> Res.string.advanced_setup_category_posters
        AdvancedSetupCategory.Home -> Res.string.advanced_setup_category_home
        AdvancedSetupCategory.DetailPage -> Res.string.advanced_setup_category_detail
        AdvancedSetupCategory.SourceList -> Res.string.advanced_setup_category_source_list
        AdvancedSetupCategory.Social -> Res.string.advanced_setup_category_social
        AdvancedSetupCategory.Metadata -> Res.string.advanced_setup_category_metadata
        AdvancedSetupCategory.Tracking -> Res.string.advanced_setup_category_tracking
    }

private val AdvancedSetupCategory.icon: ImageVector
    get() = when (this) {
        AdvancedSetupCategory.PlaybackMode -> Icons.Rounded.PlayCircleOutline
        AdvancedSetupCategory.Player -> Icons.Rounded.Tv
        AdvancedSetupCategory.Skipping -> Icons.Rounded.SkipNext
        AdvancedSetupCategory.Subtitles -> Icons.Rounded.Subtitles
        AdvancedSetupCategory.Downloads -> Icons.Rounded.Download
        AdvancedSetupCategory.Navigation -> Icons.Rounded.Explore
        AdvancedSetupCategory.Theme -> Icons.Rounded.Palette
        AdvancedSetupCategory.Posters -> Icons.Rounded.ViewCarousel
        AdvancedSetupCategory.Home -> Icons.Rounded.Home
        AdvancedSetupCategory.DetailPage -> Icons.Rounded.ViewAgenda
        AdvancedSetupCategory.SourceList -> Icons.Rounded.ViewList
        AdvancedSetupCategory.Social -> Icons.Rounded.Groups
        AdvancedSetupCategory.Metadata -> Icons.Rounded.Tune
        AdvancedSetupCategory.Tracking -> Icons.Rounded.Sync
    }

// --- one panel ---------------------------------------------------------------------------

@Composable
internal fun AdvancedSetupPanelFrame(
    panel: AdvancedSetupPanel,
    facts: AdvancedSetupFacts,
    values: AdvancedSetupValues,
    touring: Boolean,
    position: Pair<Int, Int>,
    isLast: Boolean,
    wideDesktop: Boolean,
    windowHeight: Dp,
    insets: PaddingValues,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    onClose: () -> Unit,
    onSocialEnabledChange: (Boolean) -> Unit,
    onSocialSharingChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    onJoinPolicyChange: (WatchJoinPolicy) -> Unit = {},
    onOpenSettings: (SettingsPage) -> Unit = ZSettingsNavigation::open,
) {
    val tokens = MaterialTheme.nuvio
    // Which way the panel content slides: forward on Next, backward on Back, as Initial Setup does.
    var lastOrdinal by remember { mutableStateOf(panel.ordinal) }
    val goingForward = panel.ordinal >= lastOrdinal
    LaunchedEffect(panel) { lastOrdinal = panel.ordinal }

    val header: @Composable () -> Unit = {
        AdvancedPanelHeader(panel = panel, facts = facts, touring = touring, position = position, onClose = onClose)
    }
    val footer: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(
                    stringResource(
                        if (position.first == 1) Res.string.advanced_setup_all_categories else Res.string.setup_back,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onAdvance) {
                Text(
                    stringResource(
                        when {
                            !isLast -> Res.string.setup_next
                            touring -> Res.string.advanced_setup_finish_tour
                            else -> Res.string.advanced_setup_done
                        },
                    ),
                )
            }
        }
    }
    // ⚠ **Only the panel's own content moves between panels** - `SetupStepBody`'s transition,
    // reused. The whole-screen slide this replaced re-drew the preview, the header and the footer on
    // every Next of the tour, which read as a new screen each time.
    val body: @Composable () -> Unit = {
        AnimatedContent(
            targetState = panel,
            transitionSpec = { advancedPanelTransition(goingForward) },
            label = "advanced_setup_body",
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AdvancedPanelBody(
                    panel = current,
                    facts = facts,
                    values = values,
                    onSocialEnabledChange = onSocialEnabledChange,
                    onSocialSharingChange = onSocialSharingChange,
                    onJoinPolicyChange = onJoinPolicyChange,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
    // The preview is swapped only when the panel needs a different one, and crossfades when it is:
    // two panels over the same preview (Player controls and Touch, the Home panels) keep it on screen
    // and it changes in place, as the wizard's band does.
    val preview: @Composable (Boolean) -> Unit = { desktop ->
        AnimatedContent(
            targetState = panel.previewKey,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "advanced_setup_preview",
        ) { _ ->
            AdvancedPreviewFrame(Modifier.fillMaxSize(), interactive = panel.previewIsInteractive) {
                AdvancedPanelPreview(panel = panel, facts = facts, values = values, desktop = desktop)
            }
        }
    }

    if (wideDesktop) {
        SetupDesktopSplitFrame(
            topInset = insets.calculateTopPadding(),
            bottomInset = insets.calculateBottomPadding(),
            header = {
                AnimatedContent(targetState = panel, transitionSpec = { advancedPanelTransition(goingForward) }, label = "advanced_setup_header") { _ -> header() }
            },
            footer = footer,
            preview = { modifier -> Box(modifier) { preview(true) } },
            body = body,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        val band by animateDpAsState(
            targetValue = panel.previewHeight(desktop = false).coerceAtMost(windowHeight * 0.46f),
            animationSpec = tween(320, easing = LinearOutSlowInEasing),
            label = "advanced_setup_band",
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(band + insets.calculateTopPadding())
                .background(tokens.colors.background)
                .padding(top = insets.calculateTopPadding()),
        ) {
            preview(false)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.borders.hairline)
                .background(tokens.colors.borderSubtle.copy(alpha = 0.6f)),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(tokens.colors.surface),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 14.dp + insets.calculateBottomPadding()),
            ) {
                AnimatedContent(targetState = panel, transitionSpec = { advancedPanelTransition(goingForward) }, label = "advanced_setup_header") { _ -> header() }
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    body()
                }
                Spacer(Modifier.height(12.dp))
                footer()
            }
        }
    }
}

/** `SetupStepBody`'s transition: a short slide in the direction of travel under a fade. */
private fun advancedPanelTransition(goingForward: Boolean): ContentTransform {
    val offset = if (goingForward) 1 else -1
    return (
        fadeIn(tween(220)) +
            slideInHorizontally(tween(280, easing = LinearOutSlowInEasing)) { it / 6 * offset }
        ) togetherWith (
        fadeOut(tween(140)) +
            slideOutHorizontally(tween(280, easing = LinearOutSlowInEasing)) { -it / 6 * offset }
        )
}

@Composable
private fun AdvancedPanelHeader(
    panel: AdvancedSetupPanel,
    facts: AdvancedSetupFacts,
    touring: Boolean,
    position: Pair<Int, Int>,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (touring) {
                    stringResource(Res.string.advanced_setup_tour_progress, position.first, position.second)
                } else {
                    stringResource(Res.string.advanced_setup_progress, stringResource(panel.category.titleRes), position.first, position.second)
                },
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(panel.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(panel.subtitleRes(facts)),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
        AdvancedCloseButton(onClose = onClose)
    }
}

/**
 * Which previews are meant to be **tried** rather than looked at, and so let touches, clicks, hovers
 * and scrolls through the frame: the Touch stage, the real navigation bars, the hover cards, the
 * detail page and metadata miniatures (tabs, rails, a page that scrolls) and the source list. Every other preview stays inert, as before.
 */
internal val AdvancedSetupPanel.previewIsInteractive: Boolean
    get() = when (this) {
        AdvancedSetupPanel.PlayerTouch,
        AdvancedSetupPanel.NavigationStyle,
        AdvancedSetupPanel.PosterHover,
        AdvancedSetupPanel.DetailLayout,
        AdvancedSetupPanel.DetailEpisodes,
        AdvancedSetupPanel.SourceListLook,
        AdvancedSetupPanel.EnhancedMetadata,
        -> true
        else -> false
    }

/** Panels that share a preview share this key, so moving between them keeps the preview in place. */
private val AdvancedSetupPanel.previewKey: String
    get() = when (this) {
        AdvancedSetupPanel.PlaybackModeChoice,
        AdvancedSetupPanel.PlaybackSourcePreferences,
        AdvancedSetupPanel.PlaybackSourceFormat,
        -> "playback"
        AdvancedSetupPanel.DownloadModeChoice,
        AdvancedSetupPanel.DownloadPreferences,
        AdvancedSetupPanel.DownloadDevice,
        -> "downloads"
        AdvancedSetupPanel.SkipSegments, AdvancedSetupPanel.NextEpisode -> "skip"
        AdvancedSetupPanel.SubtitleLook, AdvancedSetupPanel.SubtitlePlacement -> "subtitles"
        AdvancedSetupPanel.PosterShape, AdvancedSetupPanel.PosterEffects -> "posters"
        AdvancedSetupPanel.HomeHero, AdvancedSetupPanel.HomeContinueWatching -> "home"
        AdvancedSetupPanel.SocialSharing, AdvancedSetupPanel.SocialIdentity -> "social"
        else -> name
    }

/** How tall a panel's preview band is on a phone (capped against the window by the caller). */
private fun AdvancedSetupPanel.previewHeight(desktop: Boolean): Dp = when (this) {
    AdvancedSetupPanel.PlaybackModeChoice,
    AdvancedSetupPanel.PlaybackSourcePreferences,
    AdvancedSetupPanel.PlaybackSourceFormat,
    AdvancedSetupPanel.DownloadModeChoice,
    AdvancedSetupPanel.DownloadPreferences,
    AdvancedSetupPanel.DownloadDevice,
    -> SetupSpecimen.Diagram.preferredHeight
    AdvancedSetupPanel.PlayerControls -> 300.dp
    AdvancedSetupPanel.PlayerTouch -> 250.dp
    AdvancedSetupPanel.SkipSegments -> 150.dp
    AdvancedSetupPanel.NextEpisode -> 230.dp
    AdvancedSetupPanel.SubtitleLook, AdvancedSetupPanel.SubtitlePlacement -> 210.dp
    AdvancedSetupPanel.NavigationStyle -> if (isDesktop) 300.dp else 250.dp
    AdvancedSetupPanel.ThemePalette -> SetupSpecimen.Theme.preferredHeight
    AdvancedSetupPanel.PosterShape, AdvancedSetupPanel.PosterEffects, AdvancedSetupPanel.PosterHover -> 300.dp
    AdvancedSetupPanel.HomeHero, AdvancedSetupPanel.HomeContinueWatching -> 360.dp
    AdvancedSetupPanel.DetailLayout -> 400.dp
    AdvancedSetupPanel.DetailEpisodes -> 360.dp
    AdvancedSetupPanel.SourceListLook -> 300.dp
    AdvancedSetupPanel.SocialSharing, AdvancedSetupPanel.SocialIdentity -> 200.dp
    AdvancedSetupPanel.EnhancedMetadata -> 360.dp
    AdvancedSetupPanel.TrackingServices -> 160.dp
}.let { if (desktop) it * DesktopSpecimenScale else it }

/** The renderer this device's player will draw subtitles with (`SubtitleRenderGeometry.kt`). */
internal fun subtitleRendererFor(player: com.nuvio.app.features.player.PlayerSettingsUiState): SubtitleRenderer = when {
    isIos || isDesktop -> SubtitleRenderer.Mpv
    player.androidPlaybackEngine == AndroidPlaybackEngine.Libmpv -> SubtitleRenderer.AndroidMpv
    else -> SubtitleRenderer.ExoPlayer
}

/**
 * The preview for [panel]. Wizard specimens are drawn through `SetupSpecimenBand` so the two flows
 * show exactly the same drawing for the same setting; the rest are `AdvancedSetupPreviews.kt`,
 * `AdvancedSetupPlayerPreviews.kt` and `AdvancedSetupScreenPreviews.kt`.
 */
@Composable
internal fun AdvancedPanelPreview(
    panel: AdvancedSetupPanel,
    facts: AdvancedSetupFacts,
    values: AdvancedSetupValues,
    desktop: Boolean,
) {
    val scale = if (desktop) DesktopSpecimenScale else 1f
    val p = values.player
    @Composable
    fun band(specimen: SetupSpecimen, step: SetupStep) {
        AdvancedSpecimenBand(panel = panel, specimen = specimen, step = step, values = values, desktop = desktop, scale = scale)
    }
    when (panel) {
        AdvancedSetupPanel.PlaybackModeChoice,
        AdvancedSetupPanel.PlaybackSourcePreferences,
        AdvancedSetupPanel.PlaybackSourceFormat,
        -> band(SetupSpecimen.Diagram, SetupStep.PlaybackMode)
        AdvancedSetupPanel.DownloadModeChoice,
        AdvancedSetupPanel.DownloadPreferences,
        AdvancedSetupPanel.DownloadDevice,
        -> band(SetupSpecimen.Diagram, SetupStep.DownloadMode)
        AdvancedSetupPanel.PlayerControls -> SpecimenPlayerChrome(
            legacyLayout = p.useLegacyPlayerLayout,
            showLayoutChoice = facts.isMobile,
            pauseOverlay = p.pauseOverlayEnabled,
            loadingOverlay = p.showLoadingOverlay,
            contentWarnings = p.showParentalGuide,
            desktop = desktop || facts.isDesktop,
        )
        AdvancedSetupPanel.PlayerTouch -> SpecimenPlayerTouch(
            legacyLayout = p.useLegacyPlayerLayout,
            showLayoutChoice = facts.isMobile,
            gestures = p.touchGesturesEnabled,
            holdToSpeed = p.holdToSpeedEnabled,
            holdSpeed = p.holdToSpeedValue,
            interactive = true,
        )
        AdvancedSetupPanel.SkipSegments,
        AdvancedSetupPanel.NextEpisode,
        -> SpecimenSkipTimeline(
            skipIntro = p.skipIntroEnabled,
            autoSkip = p.autoSkipSegmentTypes,
            showNextEpisode = panel == AdvancedSetupPanel.NextEpisode,
            autoPlayNext = p.streamAutoPlayNextEpisodeEnabled,
            thresholdMode = p.nextEpisodeThresholdMode,
            thresholdPercent = p.nextEpisodeThresholdPercent,
            thresholdMinutes = p.nextEpisodeThresholdMinutesBeforeEnd,
            scale = scale,
        )
        AdvancedSetupPanel.SubtitleLook,
        AdvancedSetupPanel.SubtitlePlacement,
        -> SpecimenSubtitles(style = p.subtitleStyle, renderer = subtitleRendererFor(p), desktop = desktop || facts.isDesktop)
        AdvancedSetupPanel.NavigationStyle -> when {
            facts.isDesktop -> SpecimenDesktopNavigation(
                layout = values.desktopNavLayout,
                style = values.navBarStyle,
                heroEnabled = values.heroEnabled,
                socialEnabled = values.socialEnabled,
                glowEnabled = values.navBarGlow,
            )
            facts.isIos -> SpecimenIosTabBar(liquidGlass = values.liquidGlass, scale = scale)
            else -> SpecimenAndroidNavigation(style = values.navBarStyle, glowEnabled = values.navBarGlow)
        }
        AdvancedSetupPanel.ThemePalette -> band(SetupSpecimen.Theme, SetupStep.Theme)
        AdvancedSetupPanel.PosterShape,
        AdvancedSetupPanel.PosterEffects,
        AdvancedSetupPanel.PosterHover,
        -> SpecimenPosterRail(
            landscape = values.landscapeCards,
            hideLabels = values.hidePosterLabels,
            basePosterWidthDp = desktopCatalogShelfPosterBaseWidthDp(values.posterWidthDp),
            showHoverCard = panel == AdvancedSetupPanel.PosterHover,
            hoverEnabled = values.hoverPreview,
            hoverTrailer = values.hoverTrailer,
            hoverSound = values.hoverTrailerSound,
        )
        AdvancedSetupPanel.HomeHero,
        AdvancedSetupPanel.HomeContinueWatching,
        -> SpecimenHomeScreen(
            heroEnabled = values.heroEnabled,
            showCatalogType = values.showCatalogType,
            continueWatchingVisible = values.continueWatchingVisible,
            continueWatchingStyle = values.continueWatchingStyle,
            useEpisodeThumbnails = values.useEpisodeThumbnails,
            blurNextUp = values.blurNextUp,
            focusContinueWatching = panel == AdvancedSetupPanel.HomeContinueWatching,
            desktop = desktop || facts.isDesktop,
        )
        AdvancedSetupPanel.DetailLayout,
        AdvancedSetupPanel.DetailEpisodes,
        -> SpecimenDetailPage(
            focus = if (panel == AdvancedSetupPanel.DetailLayout) DetailPreviewFocus.Layout else DetailPreviewFocus.Episodes,
            mode = values.detailBackground,
            tabLayout = values.detailTabLayout,
            showOverallRatings = values.showOverallRatings,
            mdbListActive = values.mdbListActive,
            episodeCardStyle = values.episodeCardStyle,
            blurUnwatched = values.blurUnwatchedEpisodes,
            episodeRatings = values.episodeRatings,
            randomEpisodeAvailable = facts.isMobile && values.randomEpisodeAvailable,
            cornerRadiusDp = values.posterCornerRadiusDp,
            desktop = desktop || facts.isDesktop,
        )
        AdvancedSetupPanel.SourceListLook -> SpecimenSourceList(
            backgroundMode = values.streamBackground,
            showSizeBadges = values.streamSizeBadges,
            badgePlacement = values.streamBadgePlacement,
            showAddonLogo = values.streamAddonLogo,
            scale = scale,
        )
        AdvancedSetupPanel.SocialSharing,
        AdvancedSetupPanel.SocialIdentity,
        -> SpecimenFriendActivity(
            socialEnabled = values.socialEnabled,
            shareWatchingNow = values.shareWatchingNow,
            shareWatched = values.shareWatched,
            scale = scale,
        )
        AdvancedSetupPanel.EnhancedMetadata -> SpecimenMetadata(enriched = values.tmdbEnabled, desktop = desktop || facts.isDesktop)
        AdvancedSetupPanel.TrackingServices -> SpecimenTracking(scale = scale)
    }
}

/**
 * A wizard specimen in an Advanced Setup preview.
 *
 * On a phone it is the band at the panel's height. On a desktop pane it is drawn exactly as
 * `SetupWizardDesktopLayout` draws it: at the specimen's own desktop budget (`desktopHeight`), capped
 * to the pane, between a `background` filler above and a `surface` filler below that continue the
 * band's own gradient - so the mocks are neither cropped nor stretched, and the pane reads as one
 * surface with no step at either edge.
 */
@Composable
private fun AdvancedSpecimenBand(
    panel: AdvancedSetupPanel,
    specimen: SetupSpecimen,
    step: SetupStep,
    values: AdvancedSetupValues,
    desktop: Boolean,
    scale: Float,
) {
    val tokens = MaterialTheme.nuvio
    @Composable
    fun drawBand(height: Dp) {
        SetupSpecimenBand(
            specimen = specimen,
            step = step,
            playbackMode = values.playbackMode,
            height = height,
            contentPaddingTop = 0.dp,
            posterWidthDp = values.posterWidthDp,
            posterCornerRadiusDp = values.posterCornerRadiusDp,
            landscapeCards = values.landscapeCards,
            showCardTitles = !values.hidePosterLabels,
            heroEnabled = values.heroEnabled,
            continueWatchingStyle = values.continueWatchingStyle,
            useEpisodeThumbnails = values.useEpisodeThumbnails,
            blurNextUp = values.blurNextUp,
            backgroundMode = values.detailBackground,
            episodeCardStyle = values.episodeCardStyle,
            blurUnwatchedEpisodes = values.blurUnwatchedEpisodes,
            tabLayout = values.detailTabLayout,
            nextUpLabel = stringResource(Res.string.setup_specimen_next_up),
            modifier = Modifier.fillMaxWidth(),
            scale = if (specimen == SetupSpecimen.Diagram) {
                if (desktop) DesktopSpecimenScale * 1.8f else 1f
            } else {
                scale
            },
            wide = desktop,
            downloadModeName = values.downloadMode.name,
        )
    }
    if (!desktop) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            drawBand(panel.previewHeight(desktop = false).coerceAtMost(maxHeight))
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = specimen.desktopHeight.coerceAtMost(maxHeight)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(tokens.colors.background))
            drawBand(height)
            Box(Modifier.weight(1f).fillMaxWidth().background(tokens.colors.surface))
        }
    }
}

// --- panel bodies ------------------------------------------------------------------------

/**
 * The controls of [panel], from `advancedSetupControls` - never a second decision about which rows a
 * platform gets. Every control writes the repository its Settings row writes, with that row's words.
 */
@Composable
internal fun AdvancedPanelBody(
    panel: AdvancedSetupPanel,
    facts: AdvancedSetupFacts,
    values: AdvancedSetupValues,
    onSocialEnabledChange: (Boolean) -> Unit,
    onSocialSharingChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    onJoinPolicyChange: (WatchJoinPolicy) -> Unit = {},
    onOpenSettings: (SettingsPage) -> Unit = ZSettingsNavigation::open,
    /** Draw only these controls instead of the panel's own list (Device Setup's borrowed steps). */
    controlsOverride: List<AdvancedSetupControl>? = null,
) {
    val controls = controlsOverride ?: advancedSetupControls(panel, facts)
    when (panel) {
        // Whole-panel bodies shared with the wizard, so the two can never ask differently.
        AdvancedSetupPanel.PlaybackModeChoice -> {
            PlaybackMode.entries.forEach { mode ->
                PlaybackModeCard(
                    mode = mode,
                    isSelected = mode == values.playbackMode,
                    onClick = { PlayerSettingsRepository.setPlaybackMode(mode) },
                    enabled = mode.isSelectable,
                )
            }
            SetupFootnote(stringResource(Res.string.playback_mode_escape_hatch))
            return
        }
        AdvancedSetupPanel.PlaybackSourcePreferences -> {
            SetupPlaybackSetupBody(
                variant = playbackSetupVariant(values.playbackMode.name),
                languageStrictness = values.player.playbackLanguageStrictness,
                dynamicRangePolicy = values.player.playbackDynamicRangePolicy,
                qualityCeilingMbps = values.player.playbackQualityCeilingMbps,
            )
            return
        }
        AdvancedSetupPanel.DownloadModeChoice -> {
            DownloadModeOrder.forEach { mode ->
                DownloadModeCard(
                    mode = mode,
                    isSelected = mode == values.downloadMode,
                    onClick = { DownloadPolicyRepository.setMode(mode) },
                )
            }
            return
        }
        AdvancedSetupPanel.DownloadPreferences -> {
            SetupDownloadSetupBody(
                variant = when (values.downloadMode.name) {
                    "AUTOMATIC" -> DownloadSetupVariant.Automatic
                    "ASSISTED" -> DownloadSetupVariant.Assisted
                    else -> DownloadSetupVariant.None
                },
                policy = values.downloadPolicy,
                mobileDataRule = values.deviceDownloads.mobileData,
                askMobileData = false,
                showNotificationNote = false,
            )
            return
        }
        AdvancedSetupPanel.ThemePalette -> {
            SetupThemeGrid(selected = values.selectedTheme, onSelected = ThemeSettingsRepository::setTheme)
            SetupToggleRow(
                title = stringResource(Res.string.setup_theme_amoled),
                description = stringResource(Res.string.setup_theme_amoled_description),
                checked = values.amoled,
                onCheckedChange = ThemeSettingsRepository::setAmoled,
            )
            return
        }
        AdvancedSetupPanel.SocialIdentity -> {
            AdvancedSocialIdentity(signedIn = values.socialSignedIn)
            return
        }
        AdvancedSetupPanel.DownloadDevice -> {
            // The one panel that needs the download store itself; loading it starts the engine,
            // which is why only this panel does.
            LaunchedEffect(Unit) { DownloadsRepository.ensureLoaded() }
        }
        else -> Unit
    }
    controls.forEach { control ->
        AdvancedControl(
            control = control,
            facts = facts,
            values = values,
            onSocialEnabledChange = onSocialEnabledChange,
            onSocialSharingChange = onSocialSharingChange,
            onJoinPolicyChange = onJoinPolicyChange,
            onOpenSettings = onOpenSettings,
        )
    }
    if (controlsOverride == null) panelFootnote(panel)?.let { SetupFootnote(stringResource(it)) }
}

private fun panelFootnote(panel: AdvancedSetupPanel): StringResource? = when (panel) {
    AdvancedSetupPanel.PlayerControls -> Res.string.advanced_setup_player_more
    AdvancedSetupPanel.SubtitlePlacement -> Res.string.advanced_setup_subtitles_more
    AdvancedSetupPanel.PosterShape -> Res.string.advanced_setup_posters_more
    AdvancedSetupPanel.DetailEpisodes -> Res.string.advanced_setup_detail_more
    else -> null
}

@Composable
private fun AdvancedControl(
    control: AdvancedSetupControl,
    facts: AdvancedSetupFacts,
    values: AdvancedSetupValues,
    onSocialEnabledChange: (Boolean) -> Unit,
    onSocialSharingChange: (Boolean, Boolean) -> Unit,
    onJoinPolicyChange: (WatchJoinPolicy) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit,
) {
    val p = values.player
    when (control) {
        AdvancedSetupControl.PlaybackModeCards,
        AdvancedSetupControl.QualityLimit,
        AdvancedSetupControl.LanguageMatching,
        AdvancedSetupControl.DynamicRange,
        AdvancedSetupControl.DownloadModeCards,
        AdvancedSetupControl.DownloadResolution,
        AdvancedSetupControl.DownloadSize,
        AdvancedSetupControl.DownloadFallback,
        AdvancedSetupControl.ThemePalette,
        AdvancedSetupControl.AmoledBlack,
        AdvancedSetupControl.SocialHandle,
        -> Unit // Drawn by the whole-panel bodies above.

        AdvancedSetupControl.CodecPreference -> SetupQuestion(
            title = stringResource(Res.string.settings_playback_codec_preference),
            detail = stringResource(Res.string.settings_playback_codec_preference_description),
        ) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_playback_audio_preference_any), CodecPreference.ANY),
                    NuvioSegment("HEVC", CodecPreference.HEVC),
                    NuvioSegment("AV1", CodecPreference.AV1),
                    NuvioSegment("H.264", CodecPreference.AVC),
                ),
                selected = p.playbackCodecPreference,
                onSelected = PlayerSettingsRepository::setPlaybackCodecPreference,
                compact = true,
            )
        }
        AdvancedSetupControl.AudioPreference -> SetupQuestion(
            title = stringResource(Res.string.settings_playback_audio_preference),
            detail = stringResource(Res.string.settings_playback_audio_preference_description),
        ) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_playback_audio_preference_any), AudioPreference.ANY),
                    NuvioSegment(stringResource(Res.string.advanced_setup_audio_surround), AudioPreference.PREFER_SURROUND),
                    NuvioSegment(stringResource(Res.string.advanced_setup_audio_lossless), AudioPreference.PREFER_LOSSLESS),
                    NuvioSegment(stringResource(Res.string.advanced_setup_audio_immersive), AudioPreference.PREFER_IMMERSIVE),
                ),
                selected = p.playbackAudioPreference.takeIf { it != AudioPreference.REQUIRE_LOSSLESS },
                onSelected = PlayerSettingsRepository::setPlaybackAudioPreference,
                compact = true,
            )
        }
        AdvancedSetupControl.PreferEmbeddedSubtitles -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_prefer_embedded_subtitles),
            description = stringResource(Res.string.advanced_setup_prefer_embedded_short),
            checked = p.playbackPreferEmbeddedSubtitles,
            onCheckedChange = PlayerSettingsRepository::setPlaybackPreferEmbeddedSubtitles,
        )

        AdvancedSetupControl.LegacyPlayerLayout -> SetupQuestion(title = stringResource(Res.string.advanced_setup_layout_title)) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.advanced_setup_layout_current), false),
                    NuvioSegment(stringResource(Res.string.advanced_setup_layout_legacy), true),
                ),
                selected = p.useLegacyPlayerLayout,
                onSelected = PlayerSettingsRepository::setUseLegacyPlayerLayout,
            )
        }
        AdvancedSetupControl.PauseOverlay -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_pause_overlay),
            description = stringResource(Res.string.settings_playback_pause_overlay_description),
            checked = p.pauseOverlayEnabled,
            onCheckedChange = PlayerSettingsRepository::setPauseOverlayEnabled,
        )
        AdvancedSetupControl.LoadingOverlay -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_show_loading_overlay),
            description = stringResource(Res.string.settings_playback_show_loading_overlay_description),
            checked = p.showLoadingOverlay,
            onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
        )
        AdvancedSetupControl.ContentWarnings -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_parental_guide),
            description = stringResource(Res.string.settings_playback_parental_guide_description),
            checked = p.showParentalGuide,
            onCheckedChange = PlayerSettingsRepository::setShowParentalGuide,
        )
        AdvancedSetupControl.TouchGestures -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_touch_gestures),
            description = stringResource(Res.string.settings_playback_touch_gestures_description),
            checked = p.touchGesturesEnabled,
            onCheckedChange = PlayerSettingsRepository::setTouchGesturesEnabled,
        )
        AdvancedSetupControl.HoldToSpeed -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_hold_to_speed),
            description = stringResource(Res.string.settings_playback_hold_to_speed_description),
            checked = p.holdToSpeedEnabled,
            onCheckedChange = PlayerSettingsRepository::setHoldToSpeedEnabled,
        )
        AdvancedSetupControl.HoldSpeed -> if (p.holdToSpeedEnabled) {
            SetupQuestion(title = stringResource(Res.string.settings_playback_hold_speed)) {
                val options = listOf(1.5f, 2f, 2.5f, 3f)
                NuvioSegmentedChoice(
                    segments = options.map { NuvioSegment("${formatSpeed(it)}×", it) },
                    selected = options.minByOrNull { abs(it - p.holdToSpeedValue) },
                    onSelected = PlayerSettingsRepository::setHoldToSpeedValue,
                )
            }
        }

        AdvancedSetupControl.SkipIntro -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_skip_intro_outro_recap),
            description = stringResource(Res.string.advanced_setup_skip_intro_short),
            checked = p.skipIntroEnabled,
            onCheckedChange = PlayerSettingsRepository::setSkipIntroEnabled,
        )
        AdvancedSetupControl.AutoSkipSegments -> SetupQuestion(
            title = stringResource(Res.string.settings_playback_auto_skip_segments),
            detail = stringResource(Res.string.advanced_setup_auto_skip_detail),
        ) {
            AdvancedChipGroup(
                options = listOf(
                    AutoSkipSegmentType.RECAP to Res.string.settings_playback_auto_skip_recap,
                    AutoSkipSegmentType.INTRO to Res.string.settings_playback_auto_skip_intro,
                    AutoSkipSegmentType.OUTRO to Res.string.settings_playback_auto_skip_outro,
                    AutoSkipSegmentType.MOVIE_CREDITS to Res.string.settings_playback_auto_skip_movie_credits,
                ).map { (type, label) -> stringResource(label) to type },
                isSelected = { it in p.autoSkipSegmentTypes },
                onToggle = { type -> PlayerSettingsRepository.setAutoSkipSegmentTypeEnabled(type, type !in p.autoSkipSegmentTypes) },
            )
        }
        AdvancedSetupControl.AutoPlayNextEpisode -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_auto_play_next_episode),
            description = stringResource(Res.string.settings_playback_auto_play_next_episode_description),
            checked = p.streamAutoPlayNextEpisodeEnabled,
            onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayNextEpisodeEnabled,
        )
        AdvancedSetupControl.NextEpisodeThreshold -> SetupQuestion(
            title = stringResource(Res.string.advanced_setup_threshold_title),
            detail = stringResource(Res.string.settings_playback_threshold_percentage_description),
        ) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_playback_threshold_mode_percentage), NextEpisodeThresholdMode.PERCENTAGE),
                    NuvioSegment(stringResource(Res.string.settings_playback_threshold_mode_minutes_before_end), NextEpisodeThresholdMode.MINUTES_BEFORE_END),
                ),
                selected = p.nextEpisodeThresholdMode,
                onSelected = PlayerSettingsRepository::setNextEpisodeThresholdMode,
            )
            if (p.nextEpisodeThresholdMode == NextEpisodeThresholdMode.PERCENTAGE) {
                val options = listOf(90f, 95f, 98f, 99f)
                NuvioSegmentedChoice(
                    segments = options.map { NuvioSegment("${it.toInt()}%", it) },
                    selected = options.minByOrNull { abs(it - p.nextEpisodeThresholdPercent) },
                    onSelected = PlayerSettingsRepository::setNextEpisodeThresholdPercent,
                    compact = true,
                )
            } else {
                val options = listOf(1f, 2f, 3f, 5f)
                NuvioSegmentedChoice(
                    segments = options.map { NuvioSegment(stringResource(Res.string.advanced_setup_minutes, it.toInt()), it) },
                    selected = options.minByOrNull { abs(it - p.nextEpisodeThresholdMinutesBeforeEnd) },
                    onSelected = PlayerSettingsRepository::setNextEpisodeThresholdMinutesBeforeEnd,
                    compact = true,
                )
            }
        }

        AdvancedSetupControl.SubtitleSize -> SetupQuestion(title = stringResource(Res.string.settings_playback_subtitle_size)) {
            val sizes = listOf(14, 18, 24, 30)
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.advanced_setup_size_small), 14),
                    NuvioSegment(stringResource(Res.string.advanced_setup_size_medium), 18),
                    NuvioSegment(stringResource(Res.string.advanced_setup_size_large), 24),
                    NuvioSegment(stringResource(Res.string.advanced_setup_size_huge), 30),
                ),
                selected = sizes.minByOrNull { abs(it - p.subtitleStyle.fontSizeSp) },
                onSelected = { PlayerSettingsRepository.setSubtitleStyle(p.subtitleStyle.copy(fontSizeSp = it)) },
            )
        }
        AdvancedSetupControl.SubtitleTextColor -> SetupQuestion(title = stringResource(Res.string.settings_playback_subtitle_text_color)) {
            AdvancedSwatches(
                colors = SubtitleColorSwatches,
                selected = p.subtitleStyle.textColor,
                onSelected = { PlayerSettingsRepository.setSubtitleStyle(p.subtitleStyle.copy(textColor = it)) },
            )
        }
        AdvancedSetupControl.SubtitleBackground -> SetupQuestion(title = stringResource(Res.string.settings_playback_subtitle_background_color)) {
            AdvancedSwatches(
                colors = SubtitleBackgroundColorSwatches,
                selected = p.subtitleStyle.backgroundColor,
                onSelected = { PlayerSettingsRepository.setSubtitleStyle(p.subtitleStyle.copy(backgroundColor = it)) },
            )
        }
        AdvancedSetupControl.SubtitleOutline -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_subtitle_outline),
            description = stringResource(Res.string.settings_playback_subtitle_outline_description),
            checked = p.subtitleStyle.outlineEnabled,
            onCheckedChange = { PlayerSettingsRepository.setSubtitleStyle(p.subtitleStyle.copy(outlineEnabled = it)) },
        )
        AdvancedSetupControl.SubtitleBold -> SetupToggleRow(
            title = stringResource(Res.string.settings_playback_subtitle_bold),
            description = stringResource(Res.string.settings_playback_subtitle_bold_description),
            checked = p.subtitleStyle.bold,
            onCheckedChange = { PlayerSettingsRepository.setSubtitleStyle(p.subtitleStyle.copy(bold = it)) },
        )
        AdvancedSetupControl.SubtitleOffset -> SetupQuestion(
            title = stringResource(Res.string.settings_playback_subtitle_vertical_offset),
            detail = stringResource(Res.string.advanced_setup_offset_detail),
        ) {
            val offsets = listOf(0, 20, 50, 100)
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.advanced_setup_offset_bottom), 0),
                    NuvioSegment(stringResource(Res.string.advanced_setup_offset_default), 20),
                    NuvioSegment(stringResource(Res.string.advanced_setup_offset_raised), 50),
                    NuvioSegment(stringResource(Res.string.advanced_setup_offset_high), 100),
                ),
                selected = offsets.minByOrNull { abs(it - p.subtitleStyle.bottomOffset) },
                onSelected = { PlayerSettingsRepository.setSubtitleStyle(p.subtitleStyle.copy(bottomOffset = it)) },
            )
        }

        AdvancedSetupControl.DownloadMobileData -> SetupQuestion(title = stringResource(Res.string.download_pref_mobile_data)) {
            NuvioSegmentedChoice(
                segments = DownloadMobileDataRule.entries.map { NuvioSegment(downloadMobileDataLabel(it), it) },
                selected = values.deviceDownloads.mobileData,
                onSelected = { value -> DownloadsRepository.updateDeviceSettings { it.copy(mobileData = value) } },
            )
        }
        AdvancedSetupControl.DownloadsAtOnce -> SetupQuestion(
            title = stringResource(Res.string.download_pref_concurrency),
            detail = stringResource(Res.string.advanced_setup_concurrency_detail),
        ) {
            NuvioSegmentedChoice(
                segments = (DownloadDeviceSettings.MIN_CONCURRENT..DownloadDeviceSettings.MAX_CONCURRENT).map { NuvioSegment(it.toString(), it) },
                selected = values.deviceDownloads.effectiveMaxConcurrent,
                onSelected = { value -> DownloadsRepository.updateDeviceSettings { it.copy(maxConcurrent = value) } },
            )
        }

        AdvancedSetupControl.NavBarStyle -> SetupQuestion(
            title = stringResource(
                when {
                    facts.isDesktop && values.desktopNavLayout == DesktopNavigationLayout.Sidebar -> Res.string.settings_appearance_sidebar_style
                    facts.isDesktop -> Res.string.settings_appearance_top_bar_style
                    else -> Res.string.settings_appearance_nav_bar_style
                },
            ),
        ) {
            val styles = when {
                facts.isDesktop -> listOf(NavBarStyle.ADAPTIVE, NavBarStyle.EXPANDED, NavBarStyle.COMPACT)
                facts.isIos -> NavBarStyle.entries.filter { it != NavBarStyle.CLASSIC }
                else -> NavBarStyle.entries
            }
            NuvioSegmentedChoice(
                segments = styles.map { NuvioSegment(stringResource(it.labelRes), it) },
                selected = values.navBarStyle,
                onSelected = ThemeSettingsRepository::setNavBarStyle,
                compact = styles.size > 3,
            )
        }
        AdvancedSetupControl.NavBarGlow -> if (values.navBarStyle != NavBarStyle.CLASSIC) {
            SetupToggleRow(
                title = stringResource(Res.string.settings_nav_bar_glow),
                description = stringResource(Res.string.advanced_setup_glow_detail),
                checked = values.navBarGlow,
                onCheckedChange = ThemeSettingsRepository::setNavBarGlowEnabled,
            )
        }
        AdvancedSetupControl.DesktopNavigationLayout -> SetupQuestion(
            title = stringResource(Res.string.settings_appearance_desktop_navigation),
            detail = stringResource(Res.string.advanced_setup_desktop_nav_detail),
        ) {
            NuvioSegmentedChoice(
                segments = listOf(DesktopNavigationLayout.Sidebar, DesktopNavigationLayout.TopBar)
                    .filter { it in DesktopNavigationLayout.entries }
                    .map { NuvioSegment(stringResource(it.labelRes), it) },
                selected = values.desktopNavLayout,
                onSelected = ThemeSettingsRepository::setDesktopNavigationLayout,
            )
        }
        AdvancedSetupControl.LiquidGlassTabBar -> SetupToggleRow(
            title = stringResource(Res.string.settings_appearance_liquid_glass),
            description = stringResource(Res.string.advanced_setup_liquid_glass_detail),
            checked = values.liquidGlass,
            onCheckedChange = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
        )

        AdvancedSetupControl.LandscapePosters -> SetupQuestion(title = stringResource(Res.string.setup_cards_shape)) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.setup_cards_shape_poster), false),
                    NuvioSegment(stringResource(Res.string.setup_cards_shape_landscape), true),
                ),
                selected = values.landscapeCards,
                onSelected = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
            )
        }
        AdvancedSetupControl.PosterWidth -> SetupQuestion(title = stringResource(Res.string.setup_cards_size)) {
            val widths = listOf(112, 126, 140)
            NuvioSegmentedChoice(
                segments = widths.map { NuvioSegment(posterWidthLabel(it), it) },
                selected = widths.minByOrNull { abs(it - values.posterWidthDp) },
                onSelected = PosterCardStyleRepository::setWidthDp,
            )
        }
        AdvancedSetupControl.PosterCorners -> SetupQuestion(title = stringResource(Res.string.settings_poster_card_radius)) {
            val radii = listOf(0, 4, 8, 12, 16)
            NuvioSegmentedChoice(
                segments = radii.map { NuvioSegment(posterRadiusLabel(it), it) },
                selected = radii.minByOrNull { abs(it - values.posterCornerRadiusDp) },
                onSelected = PosterCardStyleRepository::setCornerRadiusDp,
                compact = true,
            )
        }
        AdvancedSetupControl.HidePosterLabels -> SetupToggleRow(
            title = stringResource(Res.string.settings_poster_hide_labels),
            description = stringResource(Res.string.advanced_setup_hide_labels_detail),
            checked = values.hidePosterLabels,
            onCheckedChange = PosterCardStyleRepository::setHideLabelsEnabled,
        )
        AdvancedSetupControl.CardDepth -> SetupToggleRow(
            title = stringResource(Res.string.settings_card_depth_enabled),
            description = stringResource(Res.string.settings_card_depth_description),
            checked = values.cardDepth,
            onCheckedChange = CardDepthStyleRepository::setEnabled,
        )
        AdvancedSetupControl.CardDepthPreset -> if (values.cardDepth) {
            SetupQuestion(title = stringResource(Res.string.settings_card_depth_edge)) {
                val edges = listOf(28, 42, 56)
                NuvioSegmentedChoice(
                    segments = listOf(
                        NuvioSegment(stringResource(Res.string.settings_card_depth_edge_subtle), 28),
                        NuvioSegment(stringResource(Res.string.settings_card_depth_edge_balanced), 42),
                        NuvioSegment(stringResource(Res.string.settings_card_depth_edge_bold), 56),
                    ),
                    selected = edges.minByOrNull { abs(it - values.cardDepthEdge) },
                    onSelected = CardDepthStyleRepository::setEdgeStrength,
                )
            }
        }
        AdvancedSetupControl.HoverPreview -> SetupToggleRow(
            title = stringResource(Res.string.settings_hover_preview_enabled),
            description = stringResource(Res.string.advanced_setup_hover_detail),
            checked = values.hoverPreview,
            onCheckedChange = PosterCardStyleRepository::setHoverPreviewEnabled,
        )
        AdvancedSetupControl.HoverTrailer -> if (values.hoverPreview) {
            SetupToggleRow(
                title = stringResource(Res.string.settings_hover_preview_trailer_enabled),
                checked = values.hoverTrailer,
                onCheckedChange = PosterCardStyleRepository::setHoverPreviewTrailerEnabled,
            )
        }
        AdvancedSetupControl.HoverTrailerSound -> if (values.hoverPreview && values.hoverTrailer) {
            SetupToggleRow(
                title = stringResource(Res.string.settings_hover_preview_trailer_sound_enabled),
                checked = values.hoverTrailerSound,
                onCheckedChange = PosterCardStyleRepository::setHoverPreviewTrailerSoundEnabled,
            )
        }

        AdvancedSetupControl.HeroSection -> SetupToggleRow(
            title = stringResource(Res.string.settings_homescreen_show_hero),
            description = stringResource(Res.string.advanced_setup_hero_detail),
            checked = values.heroEnabled,
            onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
        )
        AdvancedSetupControl.CatalogTypeLabels -> SetupToggleRow(
            title = stringResource(Res.string.layout_catalog_type),
            description = stringResource(Res.string.layout_catalog_type_sub),
            checked = values.showCatalogType,
            onCheckedChange = HomeCatalogSettingsRepository::setShowCatalogType,
        )
        AdvancedSetupControl.ContinueWatchingVisible -> SetupToggleRow(
            title = stringResource(Res.string.settings_continue_watching_show_title),
            checked = values.continueWatchingVisible,
            onCheckedChange = ContinueWatchingPreferencesRepository::setVisible,
        )
        AdvancedSetupControl.ContinueWatchingStyle -> if (values.continueWatchingVisible) {
            SetupQuestion(title = stringResource(Res.string.advanced_setup_cw_style)) {
                NuvioSegmentedChoice(
                    segments = ContinueWatchingSectionStyle.entries.map { NuvioSegment(continueWatchingStyleLabel(it), it) },
                    selected = values.continueWatchingStyle,
                    onSelected = ContinueWatchingPreferencesRepository::setStyle,
                )
            }
        }
        AdvancedSetupControl.EpisodeThumbnails -> if (values.continueWatchingVisible) {
            SetupToggleRow(
                title = stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_title),
                checked = values.useEpisodeThumbnails,
                onCheckedChange = ContinueWatchingPreferencesRepository::setUseEpisodeThumbnails,
            )
        }

        AdvancedSetupControl.DetailBackground -> SetupQuestion(
            title = stringResource(Res.string.settings_meta_background_mode),
            detail = stringResource(Res.string.settings_meta_background_mode_description),
        ) {
            NuvioSegmentedChoice(
                segments = MetaScreenBackgroundMode.entries.map { NuvioSegment(detailBackgroundLabel(it), it) },
                selected = values.detailBackground,
                onSelected = MetaScreenSettingsRepository::setBackgroundMode,
            )
        }
        AdvancedSetupControl.DetailTabLayout -> SetupToggleRow(
            title = stringResource(Res.string.settings_meta_tab_layout),
            description = stringResource(Res.string.advanced_setup_tab_layout_detail),
            checked = values.detailTabLayout,
            onCheckedChange = MetaScreenSettingsRepository::setTabLayout,
        )
        AdvancedSetupControl.OverallRatings -> SetupToggleRow(
            title = stringResource(Res.string.layout_overall_ratings),
            checked = values.showOverallRatings,
            onCheckedChange = MetaScreenSettingsRepository::setShowOverallRatings,
        )
        AdvancedSetupControl.EpisodeCardStyle -> SetupQuestion(title = stringResource(Res.string.settings_meta_episode_cards)) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_meta_episode_style_horizontal), MetaEpisodeCardStyle.Horizontal),
                    NuvioSegment(stringResource(Res.string.settings_meta_episode_style_list), MetaEpisodeCardStyle.List),
                ),
                selected = values.episodeCardStyle,
                onSelected = MetaScreenSettingsRepository::setEpisodeCardStyle,
            )
        }
        AdvancedSetupControl.BlurUnwatchedEpisodes -> SetupToggleRow(
            title = stringResource(Res.string.settings_meta_blur_unwatched_episodes),
            checked = values.blurUnwatchedEpisodes,
            onCheckedChange = MetaScreenSettingsRepository::setBlurUnwatchedEpisodes,
        )
        AdvancedSetupControl.EpisodeRatings -> SetupQuestion(title = stringResource(Res.string.layout_episode_ratings)) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.layout_ratings_show), EpisodeRatingsVisibility.SHOW_ALL),
                    NuvioSegment(stringResource(Res.string.layout_ratings_hide_unwatched), EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES),
                    NuvioSegment(stringResource(Res.string.layout_ratings_hide), EpisodeRatingsVisibility.HIDE_EPISODES),
                ),
                selected = values.episodeRatings,
                onSelected = MetaScreenSettingsRepository::setEpisodeRatingsVisibility,
                compact = true,
            )
        }
        // Mobile only (the model never lists it on desktop), and the store behind it exists only in
        // the mobile repository - so the row lives in the per-repo `AdvancedSetupRepoBindings.kt`.
        AdvancedSetupControl.RandomEpisode -> AdvancedRandomEpisodeRow()

        AdvancedSetupControl.SourceBackground -> SetupQuestion(title = stringResource(Res.string.settings_stream_background_title)) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_meta_background_mode_normal), StreamBackgroundMode.Normal),
                    NuvioSegment(stringResource(Res.string.settings_meta_background_mode_cinematic), StreamBackgroundMode.Cinematic),
                ),
                selected = values.streamBackground,
                onSelected = StreamBadgeSettingsRepository::setBackgroundMode,
            )
        }
        AdvancedSetupControl.SourceSizeBadges -> SetupToggleRow(
            title = stringResource(Res.string.settings_stream_size_badges_title),
            description = stringResource(Res.string.advanced_setup_size_badges_detail),
            checked = values.streamSizeBadges,
            onCheckedChange = StreamBadgeSettingsRepository::setShowFileSizeBadges,
        )
        AdvancedSetupControl.SourceBadgePlacement -> SetupQuestion(title = stringResource(Res.string.settings_stream_badge_position_title)) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_stream_badge_position_top), StreamBadgePlacement.TOP),
                    NuvioSegment(stringResource(Res.string.settings_stream_badge_position_bottom), StreamBadgePlacement.BOTTOM),
                ),
                selected = values.streamBadgePlacement,
                onSelected = StreamBadgeSettingsRepository::setBadgePlacement,
            )
        }
        AdvancedSetupControl.SourceAddonLogo -> SetupToggleRow(
            title = stringResource(Res.string.settings_stream_addon_logo_title),
            description = stringResource(Res.string.advanced_setup_addon_logo_detail),
            checked = values.streamAddonLogo,
            onCheckedChange = StreamBadgeSettingsRepository::setShowAddonLogo,
        )

        AdvancedSetupControl.SocialFeatures -> {
            SetupToggleRow(
                title = stringResource(Res.string.setup_social_toggle),
                description = stringResource(Res.string.setup_social_toggle_description),
                checked = values.socialEnabled,
                onCheckedChange = onSocialEnabledChange,
            )
            if (!values.socialEnabled) SetupParagraph(stringResource(Res.string.setup_social_off_body))
        }
        AdvancedSetupControl.ShareWatchingNow -> SetupToggleRow(
            title = stringResource(Res.string.settings_social_share_watching),
            description = stringResource(Res.string.settings_social_share_watching_description),
            checked = values.shareWatchingNow,
            onCheckedChange = { share -> if (values.socialReady) onSocialSharingChange(share, values.shareWatched) },
        )
        AdvancedSetupControl.ShareWatched -> SetupToggleRow(
            title = stringResource(Res.string.settings_social_share_recent),
            description = stringResource(Res.string.settings_social_share_recent_description),
            checked = values.shareWatched,
            onCheckedChange = { share -> if (values.socialReady) onSocialSharingChange(values.shareWatchingNow, share) },
        )
        AdvancedSetupControl.JoinPolicy -> SetupQuestion(
            title = stringResource(Res.string.settings_social_join_policy),
            detail = if (values.socialReady) null else stringResource(Res.string.advanced_setup_social_not_ready),
        ) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.settings_social_join_policy_direct), WatchJoinPolicy.direct),
                    NuvioSegment(stringResource(Res.string.settings_social_join_policy_approval), WatchJoinPolicy.approval),
                    NuvioSegment(stringResource(Res.string.settings_social_join_policy_disabled), WatchJoinPolicy.disabled),
                ),
                selected = values.joinPolicy,
                onSelected = { policy -> if (values.socialReady) onJoinPolicyChange(policy) },
                compact = true,
            )
        }

        AdvancedSetupControl.TmdbEnrichment -> SetupToggleRow(
            title = stringResource(Res.string.advanced_setup_tmdb_toggle),
            description = stringResource(Res.string.advanced_setup_tmdb_detail),
            checked = values.tmdbEnabled,
            onCheckedChange = TmdbSettingsRepository::setEnabled,
        )
        AdvancedSetupControl.TmdbLanguage -> if (values.tmdbEnabled) {
            var showDialog by remember { mutableStateOf(false) }
            SetupLanguageRow(
                title = stringResource(Res.string.settings_tmdb_preferred_language),
                value = languageName(values.tmdbLanguage),
                onClick = { showDialog = true },
            )
            if (showDialog) {
                LanguageSelectionDialog(
                    title = stringResource(Res.string.settings_tmdb_preferred_language),
                    options = AvailableLanguageOptions.map { LanguageSelectionOption(it.code, stringResource(it.labelRes)) },
                    selectedValue = values.tmdbLanguage.substringBefore('-'),
                    onSelect = { value ->
                        if (value != null) TmdbSettingsRepository.setLanguage(value)
                        showDialog = false
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }
        AdvancedSetupControl.MdbListRatingsLink -> AdvancedSettingsLink(
            body = stringResource(Res.string.advanced_setup_mdblist_body),
            action = stringResource(Res.string.advanced_setup_open_in_settings),
            onClick = { onOpenSettings(SettingsPage.MdbListRatings) },
        )
        AdvancedSetupControl.TrackingLink -> AdvancedSettingsLink(
            body = stringResource(Res.string.advanced_setup_tracking_body),
            action = stringResource(Res.string.advanced_setup_open_integrations),
            onClick = { onOpenSettings(SettingsPage.TraktAuthentication) },
        )
    }
}

/** Explanation + a way to the real Settings page. Never a connect flow of its own (plan §12). */
@Composable
private fun AdvancedSettingsLink(body: String, action: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupParagraph(body)
        OutlinedButton(onClick = onClick) {
            Text(action)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AdvancedSocialIdentity(signedIn: Boolean) {
    val scope = rememberCoroutineScope()
    var handle by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    if (!signedIn) {
        SetupParagraph(stringResource(Res.string.setup_social_identity_signed_out))
        return
    }
    SetupParagraph(stringResource(Res.string.setup_social_identity_body))
    SocialIdentityBody(
        handle = handle,
        onHandleChange = {
            handle = it
            message = null
        },
        message = message,
        busy = busy,
        onSave = {
            saveSocialHandle(
                scope = scope,
                profileId = ProfileRepository.state.value.activeProfile?.id?.takeIf(String::isNotBlank),
                handle = handle,
                setBusy = { busy = it },
                onFailed = { message = it },
            )
        },
        showHeading = false,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> AdvancedChipGroup(
    options: List<Pair<String, T>>,
    isSelected: (T) -> Boolean,
    onToggle: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            val selected = isSelected(value)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .then(
                        if (selected) {
                            Modifier.background(tokens.colors.accent)
                        } else {
                            Modifier.border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(999.dp))
                        },
                    )
                    .selectable(selected = selected, role = Role.Checkbox) { onToggle(value) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.onAccent, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSwatches(
    colors: List<Color>,
    selected: Color,
    onSelected: (Color) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.overlayHover)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) tokens.colors.accent else tokens.colors.borderSubtle,
                        shape = CircleShape,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelected(color) }
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                if (color.alpha == 0f) {
                    // Transparent: a diagonal-free "none" mark, so the swatch is not an empty ring.
                    Box(Modifier.size(12.dp).clip(CircleShape).border(1.5.dp, tokens.colors.textMuted, CircleShape))
                }
            }
        }
    }
}

// --- labels ------------------------------------------------------------------------------

@Composable
private fun posterWidthLabel(widthDp: Int): String = stringResource(
    when (listOf(112, 126, 140).minByOrNull { abs(it - widthDp) }) {
        112 -> Res.string.settings_poster_width_dense
        140 -> Res.string.settings_poster_width_large
        else -> Res.string.settings_poster_width_balanced
    },
)

@Composable
private fun posterRadiusLabel(radiusDp: Int): String = stringResource(
    when (listOf(0, 4, 8, 12, 16).minByOrNull { abs(it - radiusDp) }) {
        0 -> Res.string.settings_poster_radius_sharp
        4 -> Res.string.settings_poster_radius_subtle
        8 -> Res.string.settings_poster_radius_classic
        16 -> Res.string.settings_poster_radius_pill
        else -> Res.string.settings_poster_radius_rounded
    },
)

@Composable
private fun continueWatchingStyleLabel(style: ContinueWatchingSectionStyle): String = stringResource(
    when (style.name) {
        "Poster" -> Res.string.settings_continue_watching_style_poster
        "Wide" -> Res.string.settings_continue_watching_style_wide
        else -> Res.string.settings_continue_watching_style_card
    },
)

@Composable
private fun detailBackgroundLabel(mode: MetaScreenBackgroundMode): String = stringResource(
    when (mode) {
        MetaScreenBackgroundMode.Normal -> Res.string.settings_meta_background_mode_normal
        MetaScreenBackgroundMode.Cinematic -> Res.string.settings_meta_background_mode_cinematic
        MetaScreenBackgroundMode.DominantColor -> Res.string.settings_meta_background_mode_dominant
    },
)

internal val AdvancedSetupPanel.titleRes: StringResource
    get() = when (this) {
        AdvancedSetupPanel.PlaybackModeChoice -> Res.string.playback_mode_selector_title
        AdvancedSetupPanel.PlaybackSourcePreferences -> Res.string.advanced_setup_panel_source_prefs
        AdvancedSetupPanel.PlaybackSourceFormat -> Res.string.advanced_setup_panel_source_format
        AdvancedSetupPanel.PlayerControls -> Res.string.advanced_setup_panel_player
        AdvancedSetupPanel.PlayerTouch -> Res.string.advanced_setup_panel_touch
        AdvancedSetupPanel.SkipSegments -> Res.string.advanced_setup_panel_skip
        AdvancedSetupPanel.NextEpisode -> Res.string.advanced_setup_panel_next_episode
        AdvancedSetupPanel.SubtitleLook -> Res.string.advanced_setup_panel_subtitle_look
        AdvancedSetupPanel.SubtitlePlacement -> Res.string.advanced_setup_panel_subtitle_placement
        AdvancedSetupPanel.DownloadModeChoice -> Res.string.download_mode_title
        AdvancedSetupPanel.DownloadPreferences -> Res.string.download_setup_title
        AdvancedSetupPanel.DownloadDevice -> Res.string.advanced_setup_panel_download_device
        AdvancedSetupPanel.NavigationStyle -> Res.string.advanced_setup_category_navigation
        AdvancedSetupPanel.ThemePalette -> Res.string.setup_theme_title
        AdvancedSetupPanel.PosterShape -> Res.string.advanced_setup_panel_poster_shape
        AdvancedSetupPanel.PosterEffects -> Res.string.advanced_setup_panel_poster_effects
        AdvancedSetupPanel.PosterHover -> Res.string.advanced_setup_panel_poster_hover
        AdvancedSetupPanel.HomeHero -> Res.string.advanced_setup_category_home
        AdvancedSetupPanel.HomeContinueWatching -> Res.string.compose_settings_page_continue_watching
        AdvancedSetupPanel.DetailLayout -> Res.string.advanced_setup_category_detail
        AdvancedSetupPanel.DetailEpisodes -> Res.string.settings_meta_episodes
        AdvancedSetupPanel.SourceListLook -> Res.string.advanced_setup_category_source_list
        AdvancedSetupPanel.SocialSharing -> Res.string.advanced_setup_category_social
        AdvancedSetupPanel.SocialIdentity -> Res.string.setup_social_identity_title
        AdvancedSetupPanel.EnhancedMetadata -> Res.string.advanced_setup_category_metadata
        AdvancedSetupPanel.TrackingServices -> Res.string.advanced_setup_category_tracking
    }

internal fun AdvancedSetupPanel.subtitleRes(facts: AdvancedSetupFacts): StringResource = when (this) {
    AdvancedSetupPanel.PlaybackModeChoice -> Res.string.playback_mode_selector_subtitle
    AdvancedSetupPanel.PlaybackSourcePreferences ->
        if (playbackSetupVariant(facts.playbackModeName) == PlaybackSetupVariant.AutomaticBand) {
            Res.string.setup_playback_setup_subtitle_instant
        } else {
            Res.string.setup_playback_setup_subtitle_streamlined
        }
    AdvancedSetupPanel.PlaybackSourceFormat -> Res.string.advanced_setup_panel_source_format_subtitle
    AdvancedSetupPanel.PlayerControls -> Res.string.advanced_setup_panel_player_subtitle
    AdvancedSetupPanel.PlayerTouch -> Res.string.advanced_setup_panel_touch_subtitle
    AdvancedSetupPanel.SkipSegments -> Res.string.advanced_setup_panel_skip_subtitle
    AdvancedSetupPanel.NextEpisode -> Res.string.advanced_setup_panel_next_episode_subtitle
    AdvancedSetupPanel.SubtitleLook -> Res.string.advanced_setup_panel_subtitle_look_subtitle
    AdvancedSetupPanel.SubtitlePlacement -> Res.string.advanced_setup_panel_subtitle_placement_subtitle
    AdvancedSetupPanel.DownloadModeChoice -> Res.string.download_mode_subtitle
    AdvancedSetupPanel.DownloadPreferences ->
        if (facts.downloadModeName == "AUTOMATIC") Res.string.download_setup_subtitle_automatic else Res.string.download_setup_subtitle_assisted
    AdvancedSetupPanel.DownloadDevice -> Res.string.advanced_setup_panel_download_device_subtitle
    AdvancedSetupPanel.NavigationStyle -> when {
        facts.isDesktop -> Res.string.advanced_setup_panel_navigation_desktop
        facts.isIos -> Res.string.advanced_setup_panel_navigation_ios
        else -> Res.string.advanced_setup_panel_navigation_android
    }
    AdvancedSetupPanel.ThemePalette -> Res.string.setup_theme_subtitle
    AdvancedSetupPanel.PosterShape -> Res.string.advanced_setup_panel_poster_shape_subtitle
    AdvancedSetupPanel.PosterEffects -> Res.string.advanced_setup_panel_poster_effects_subtitle
    AdvancedSetupPanel.PosterHover -> Res.string.advanced_setup_panel_poster_hover_subtitle
    AdvancedSetupPanel.HomeHero -> Res.string.advanced_setup_panel_home_subtitle
    AdvancedSetupPanel.HomeContinueWatching -> Res.string.advanced_setup_panel_cw_subtitle
    AdvancedSetupPanel.DetailLayout -> Res.string.advanced_setup_panel_detail_subtitle
    AdvancedSetupPanel.DetailEpisodes -> Res.string.advanced_setup_panel_episodes_subtitle
    AdvancedSetupPanel.SourceListLook -> Res.string.advanced_setup_panel_source_list_subtitle
    AdvancedSetupPanel.SocialSharing -> Res.string.advanced_setup_panel_social_subtitle
    AdvancedSetupPanel.SocialIdentity -> Res.string.setup_social_identity_subtitle
    AdvancedSetupPanel.EnhancedMetadata -> Res.string.advanced_setup_panel_metadata_subtitle
    AdvancedSetupPanel.TrackingServices -> Res.string.advanced_setup_panel_tracking_subtitle
}
