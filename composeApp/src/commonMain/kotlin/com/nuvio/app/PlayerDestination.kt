package com.nuvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.*
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_quality_no_match
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerDestination(
    route: PlayerRoute,
    navController: NuvioNavigator,
    externalPlayerId: String?,
    externalPlayerNotConfiguredText: String,
    externalPlayerFailedText: String,
    onExternalPlayerLaunch: (PlayerLaunch) -> Unit,
    launchExternalPlayer: (ExternalPlayerIntentResult.Success) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
) {
    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
    if (launch == null) {
        val onBack = rememberGuardedPopBackStack(navController, route)
        LaunchedEffect(route.launchId) {
            onBack()
        }
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val onBack = rememberGuardedPopBackStack(navController, route)
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
     * Where Instant goes when a source dies: back to the `StreamRoute` it deliberately left on
     * the back stack, not out to details.
     *
     * That route hosts the whole failure chain - the auto-play effect keyed on
     * `autoPlayStream`, the retry counter and the "Finding a source" overlay - so popping past
     * it is what turned a recoverable failure into a dead end and dropped the user on the
     * details screen mid-play. When the chain is exhausted it is also the right destination:
     * the plan's fallback is the Classic source list with a reason, and with `autoPlayStream`
     * cleared that is exactly what `StreamRoute` renders.
     *
     * Falls back to details when there is no `StreamRoute` to return to, which the
     * reuse-last-link and P2P paths can both produce.
     */
    val onPlaybackFailureExit: () -> Unit = remember(navController, route, onBackToDetails) {
        {
            // The pop is a no-op unless the player is genuinely on top, so it falls through to
            // the navigating exit rather than leaving the user on a dead player with
            // `instantFailureHandled` already spent.
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
        onStartWatchTogether = { content, fingerprint ->
            navController.navigate(
                WatchPartyLobbyRoute(
                    contentId = content.contentId,
                    contentType = content.contentType,
                    videoId = content.videoId,
                    title = content.title,
                    poster = content.poster,
                    season = content.season,
                    episode = content.episode,
                    episodeTitle = content.episodeTitle,
                    sourceAddonId = fingerprint.addonId,
                    sourceInfoHash = fingerprint.infoHash,
                    sourceFileIndex = fingerprint.fileIndex,
                    sourceReleaseFingerprint = fingerprint.releaseFingerprint,
                ),
            )
        },
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
            onExternalPlayerLaunch(playerLaunch)
            val intentResult = ExternalPlayerPlatform.buildIntent(
                request = request,
                playerId = externalPlayerId,
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
                    // Say so, rather than leaving the stream route to guess
                    // from state a back press produces just as well.
                    if (hasNext) StreamsRepository.signalFailoverRetry()
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
