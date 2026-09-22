package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.playback.PlaybackLoadingController
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.partyRealizationCompletedByLaunch
import com.nuvio.app.features.watchparty.lobbyOwedOnPlayerExit
import com.nuvio.app.features.watchparty.matchesPlayback
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_quality_no_match

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
    onSystemBackHandlerChanged: (PlayerRoute, (() -> Unit)?) -> Unit,
) {
    val popBack = rememberGuardedPopBackStack(
        navController = navController,
        route = route,
        beforePop = ResumePromptRepository::markPlayerExitedNormally,
    )
    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            popBack()
        }
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val realization by PartySourceRealizer.state.collectAsStateWithLifecycle()
    // Tiered, not byte-equal: see `partyRealizationCompletedByLaunch` for the stuck "Matching" pill
    // an equality test here produced.
    val retainedPartyKey = partyRealizationCompletedByLaunch(
        party = partyUi.party,
        launchContentId = launch.parentMetaId,
        launchVideoId = launch.videoId,
        launchDescriptor = launch.partySourceDescriptor,
        realization = realization,
    )
    LaunchedEffect(route.launchId, retainedPartyKey) {
        retainedPartyKey?.let { PartySourceRealizer.retain(it, launch) }
    }
    var requestedPartyLobbyId by remember { mutableStateOf<String?>(null) }
    val onBack = rememberGuardedPlayerPopBackStack(
        navController = navController,
        route = route,
        skipRetainedStreamRoute = {
            launch.autoPickedWithFailureChain && !StreamsRepository.isManualSourceRequestPending
        },
        beforePop = {
            ResumePromptRepository.markPlayerExitedNormally()
            if (launch.autoPickedWithFailureChain && !StreamsRepository.isManualSourceRequestPending) {
                StreamsRepository.abandonAutoPlay()
                StreamsRepository.cancelLoading()
                PlaybackLoadingController.activeToken?.let(PlaybackLoadingController::close)
            }
        },
        afterPop = {
            // An explicit request (Open lobby, an accepted join), else the lobby a live party this
            // player was showing is owed - read against the stack the pop just left behind.
            val owedLobbyId = requestedPartyLobbyId ?: WatchPartyRepository.uiState.value.party.let { held ->
                lobbyOwedOnPlayerExit(
                    held = held,
                    playerMatchesParty = held?.matchesPlayback(launch.parentMetaId, launch.videoId) == true,
                    lobbyPartyIdsOnStack = navController.routes.filterIsInstance<WatchPartyLobbyRoute>().map { it.partyId },
                )
            }
            owedLobbyId?.let { partyId ->
                requestedPartyLobbyId = null
                navController.navigate(WatchPartyLobbyRoute(partyId = partyId)) {
                    launchSingleTop = true
                }
            }
        },
    )
    val registerSystemBack = remember(route, onSystemBackHandlerChanged) {
        { handler: (() -> Unit)? -> onSystemBackHandlerChanged(route, handler) }
    }
    val noAutomaticSourceText = stringResource(Res.string.playback_quality_no_match)
    // Single-shot per launch. The engine can report a fatal error more than once on the way
    // down, and a second pass would step the chain twice - burning a healthy candidate.
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
        sourceFacts = launch.sourceFacts,
        playbackAttempt = launch.playbackAttempt,
        expectedRuntimeMinutes = launch.expectedRuntimeMinutes,
        partySourceDescriptor = launch.partySourceDescriptor,
        automaticSourceSelection = launch.autoPickedWithFailureChain,
        onStartWatchTogether = { _, _, _, _ ->
            com.nuvio.app.features.watchparty.WatchPartySessionCoordinator.promoteCurrentPlayback()
        },
        onPartyLobbyRequested = { partyId -> requestedPartyLobbyId = partyId },
        onBack = onBack,
        onSystemBackHandlerChanged = registerSystemBack,
        onOpenInExternalPlayer = if (com.nuvio.app.core.build.AppFeaturePolicy.externalPlayerSupported) { { request ->
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
        } } else null,
        onOpenExternalUrl = { url ->
            openExternalStreamUrl(url)
        },
        /**
         * ⚠ **Restored.** This handler existed in `App.kt`'s `MainAppContent` and did not
         * survive the `0.1.22-alpha` sync, which split that file from ~4,400 lines to 112.
         * Nothing deleted a file and everything still compiled, so the sync brief's deletion
         * check could not see it - a lambda simply stopped being passed.
         *
         * Three things were dead in production because of it, and they are three of the bugs
         * this phase was opened for:
         *
         *  - `PlaybackStartupWatchdog` arms only when `onFatalPlaybackError != null`
         *    (`PlayerScreenRuntimeEffects.kt`), so **the watchdog never ran** - a source that
         *    played no frame was never abandoned;
         *  - the post-playback-started failover chain never advanced, so a source that opened
         *    and died was the end of the road;
         *  - `consumeFailoverRetry()` always answered false, so the stream route read every
         *    return from the player as a back press.
         *
         * `nuvio-z` kept its copy, which is exactly why the loading loop was reported on
         * desktop only.
         */
        onFatalPlaybackError = if (launch.autoPickedWithFailureChain) {
            {
                if (!instantFailureHandled) {
                    instantFailureHandled = true
                    val failed = StreamsRepository.uiState.value.autoPlayStream
                    // A null `autoPlayStream` here does not mean the chain is spent - it means
                    // playback started and `onPlaybackStarted` consumed it. That is the common
                    // failure: a source that opens, plays a second, and dies.
                    val hasNext = if (failed != null) {
                        StreamsRepository.skipAutoPlayStream(failed)
                    } else {
                        StreamsRepository.failOverAfterPlaybackStarted()
                    }
                    // Say so, rather than leaving the stream route to guess from state a back
                    // press produces just as well.
                    if (hasNext) StreamsRepository.signalFailoverRetry()
                    if (!hasNext) {
                        StreamsRepository.consumeAutoPlay()
                        NuvioToastController.show(noAutomaticSourceText)
                    }
                    // Back to `StreamRoute`, which the automatic modes deliberately leave on
                    // the back stack because it hosts the chain, the retry counter and the
                    // loading screen. `popBack` is a no-op unless the player is genuinely on
                    // top, so a race cannot strand the user on a dead player with
                    // `instantFailureHandled` already spent.
                    popBack()
                }
            }
        } else null,
        // Retires the chain the moment a frame actually plays, so a later failure falls to
        // `failOverAfterPlaybackStarted` rather than re-running the source that just worked.
        onPlaybackStarted = if (launch.autoPickedWithFailureChain) {
            { StreamsRepository.consumeAutoPlay() }
        } else null,
        modifier = Modifier.fillMaxSize(),
    )
}
