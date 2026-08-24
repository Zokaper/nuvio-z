package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.debug.PlaybackDebugSettings
import com.nuvio.app.core.debug.isDebugBuild
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.core.network.NetworkThroughputMeter
import com.nuvio.app.features.playback.AutoDownshiftDetector
import com.nuvio.app.features.playback.PlaybackStartupWatchdog
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.streams.CredentialRefreshDecision
import com.nuvio.app.features.streams.credentialRefreshDecision
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.hasLikelyExpiringPlaybackCredentials
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.time.TimeSource

/**
 * The startup watchdog's own tag, because it is the one thing here that ends a play by itself.
 *
 * `adb logcat -s PlaybackStartup` is the whole diagnosis for "it loads, then it tries again":
 * three lines means the chain burned three sources, and `reason=` says whether the host answered
 * at all. There was no line at all before, which is how the fault survived three releases.
 */
private val startupLog = Logger.withTag("PlaybackStartup")

@Composable
internal fun PlayerScreenRuntime.BindPlayerRuntimeEffects() {
    val currentFeedback = liveGestureFeedback ?: gestureFeedback
    LaunchedEffect(currentFeedback) {
        if (currentFeedback != null) {
            renderedGestureFeedback = currentFeedback
        }
    }

    // "Change" on the reused-link toast. Raised outside the player - the route that reused
    // the link pops itself on the way here - so it arrives as a request rather than a call.
    // Skips the value present on the first composition: that one is history, not a request,
    // and acting on it would open the panel every time the player is entered.
    val sourcePanelRequest by PlayerSourcePanelRequest.requests.collectAsStateWithLifecycle()
    var lastHandledSourcePanelRequest by remember { mutableStateOf(sourcePanelRequest) }
    LaunchedEffect(sourcePanelRequest) {
        if (sourcePanelRequest == lastHandledSourcePanelRequest) return@LaunchedEffect
        lastHandledSourcePanelRequest = sourcePanelRequest
        openSourcesPanel()
    }

    LaunchedEffect(parentMetaType, parentMetaId) {
        playerMetaVideos = MetaDetailsRepository.peek(parentMetaType, parentMetaId)?.videos ?: emptyList()
        if (playerMetaVideos.isEmpty()) {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }

    LaunchedEffect(metaUiState.meta, parentMetaType, parentMetaId) {
        val currentMeta = metaUiState.meta ?: return@LaunchedEffect
        if (currentMeta.type == parentMetaType && currentMeta.id == parentMetaId) {
            playerMetaVideos = currentMeta.videos
        }
    }

    LaunchedEffect(currentStreamBingeGroup, parentMetaId) {
        val bg = currentStreamBingeGroup
        if (bg != null && parentMetaId.isNotBlank()) {
            BingeGroupCacheRepository.save(parentMetaId, bg)
        }
    }

    LaunchedEffect(activeSourceUrl, activeSourceAudioUrl, activeSourceHeaders, activeSourceResponseHeaders) {
        // A re-mint of the source already playing is a *continuation*, not a new item. Consumed
        // here so it can only excuse the one change it was set for.
        val isContinuation = isCredentialRefreshHandoff
        isCredentialRefreshHandoff = false
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        // `credentialRefreshesUsed` and `credentialRefreshAttemptedSourceUrl` deliberately do
        // **not** reset here, for the same reason the swap budget below does not: a successful
        // refresh is itself what changes `activeSourceUrl`, so clearing the budget here handed
        // every re-mint a fresh one. A source that died a second after starting therefore
        // re-minted forever - new URL, `initialLoadCompleted = false` on the line below, the
        // opening overlay again, dead again - and because the refresh swallowed each error, the
        // player's fatal handler was never reached and the failure chain never ran.
        //
        // They reset where a new thing is genuinely being watched: `LaunchedEffect(activeVideoId)`.
        //
        // ⚠ **`initialLoadCompleted` is what puts the opening overlay back up**, so clearing it
        // for a re-mint of the file already playing is the "loads, restarts, loads again" the
        // user sees before a debrid stream begins. The controller above genuinely must be torn
        // down - the URL is different and a new engine instance is coming - but the *presentation*
        // should not start over for a file that never changed.
        if (!isContinuation) initialLoadCompleted = false
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        accumulatedSeekResetJob?.cancel()
        accumulatedSeekResetJob = null
        accumulatedSeekState = null
        speedBoostRestoreSpeed = null
        preferredAudioSelectionApplied = false
        preferredSubtitleSelectionApplied = false
        showSourcesPanel = false
        showEpisodesPanel = false
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        // Both describe the *content*, which a re-mint does not change. Clearing them made the
        // refresh throw away the source list it had just loaded to find the replacement, and
        // dropped subtitles the user had already chosen for a file that is still playing.
        if (!isContinuation) {
            PlayerStreamsRepository.clearEpisodeStreams()
            SubtitleRepository.clear()
        }
        WatchProgressRepository.ensureLoaded()
    }

    LaunchedEffect(
        activeTorrentInfoHash,
        activeTorrentFileIdx,
        activeTorrentFilename,
        activeTorrentTrackers,
        p2pSettingsUiState.p2pEnabled,
    ) {
        val infoHash = activeTorrentInfoHash
        if (infoHash == null) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }
        if (!P2pSettingsRepository.isVisible || !p2pSettingsUiState.p2pEnabled) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }

        p2pResolvedSourceUrl = null
        val requestedFileIdx = activeTorrentFileIdx
        val requestedFilename = activeTorrentFilename
        val requestedTrackers = activeTorrentTrackers
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        initialLoadCompleted = false

        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = requestedFileIdx,
                    filename = requestedFilename,
                    trackers = requestedTrackers,
                ),
            )
            if (activeTorrentInfoHash == infoHash && activeTorrentFileIdx == requestedFileIdx) {
                activeSourceAudioUrl = null
                activeSourceHeaders = emptyMap()
                activeSourceResponseHeaders = emptyMap()
                p2pResolvedSourceUrl = localUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = getString(
                Res.string.player_error_failed_start_torrent,
                error.message ?: genericUnknownLabel,
            )
            controlsVisible = !playerControlsLocked
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(p2pStreamingState, activeTorrentInfoHash) {
        val state = p2pStreamingState
        if (activeTorrentInfoHash != null && state is P2pStreamingState.Error) {
            p2pResolvedSourceUrl = null
            playerController = null
            playerControllerSourceUrl = null
            playbackSnapshot = PlayerPlaybackSnapshot()
            initialLoadCompleted = true
            errorMessage = getString(Res.string.player_error_torrent, state.message)
            controlsVisible = !playerControlsLocked
        }
    }

    LaunchedEffect(playbackSession.videoId) {
        subtitleDelayMs = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(playbackSession.videoId) ?: 0
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleDelayMs) {
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    }

    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl) {
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleStyle) {
        playerController?.applySubtitleStyle(subtitleStyle)
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        title,
        activeStreamTitle,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        poster,
        background,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        controller.updateNowPlayingMetadata(buildNowPlayingInfo())
    }

    LaunchedEffect(
        activeSourceUrl,
        addonSubtitleFetchKey,
        playerSettingsUiState.addonSubtitleStartupMode,
        playerController,
        playerControllerSourceUrl,
    ) {
        val fetchKey = addonSubtitleFetchKey ?: return@LaunchedEffect
        val playerInitialized = playerController != null && playerControllerSourceUrl == activeSourceUrl
        val canFetch = canAutomaticallyFetchAddonSubtitles(
            mode = playerSettingsUiState.addonSubtitleStartupMode,
            playerInitialized = playerInitialized,
        )
        if (!canFetch) return@LaunchedEffect
        if (autoFetchedAddonSubtitlesForKey == fetchKey) return@LaunchedEffect
        autoFetchedAddonSubtitlesForKey = fetchKey
        fetchAddonSubtitlesForActiveItem()
    }

    LaunchedEffect(playbackSnapshot.isLoading, playerController) {
        if (!playbackSnapshot.isLoading && playerController != null) {
            refreshTracks()
        }
    }

    // A new source - whether the user picked it or a downshift did - starts its own settle
    // window. Without this, a position-preserving switch inherits the previous source's
    // "already settled" state and its perfectly normal startup buffering reads as
    // starvation. The swap budget deliberately does *not* reset here.
    LaunchedEffect(activeSourceUrl) {
        autoDownshiftState = AutoDownshiftDetector.initial(autoDownshiftState.swapsUsed)
        autoDownshiftClock = TimeSource.Monotonic.markNow()
        autoDownshiftSourcesRequested = false
        // A different file is a different bitrate, so the measurement starts over.
        networkEstimateStartPositionMs = null
        networkEstimateStalled = false
        networkEstimateRecorded = false
        networkThroughputState = NetworkThroughputMeter.initial()
    }

    // A session is one thing being watched. Moving to the next episode earns a fresh swap - and
    // a fresh credential-refresh budget, for exactly the same reason. Keyed on the *video*, not
    // on the source URL, because re-minting changes the URL and would otherwise refund the
    // budget it just spent.
    LaunchedEffect(activeVideoId) {
        autoDownshiftState = AutoDownshiftDetector.initial()
        credentialRefreshesUsed = 0
        credentialRefreshAttemptedSourceUrl = null
    }

    LaunchedEffect(
        activeSourceUrl,
        args.onFatalPlaybackError,
        // An auto-played next episode carries its chain here rather than through
        // `PlayerLaunch`, so the budget has to be armed for it too - see
        // `nextEpisodeFallbacks`. Keying on it gives every source in the chain its own
        // deadline instead of sharing the first one's.
        nextEpisodeFallbacks,
        PlaybackDebugSettings.hudEnabled,
    ) {
        val hasChain = args.onFatalPlaybackError != null || nextEpisodeFallbacks.isNotEmpty()
        if (!hasChain) return@LaunchedEffect
        // While diagnosing startup/buffering, abandoning the source hides the useful state, so
        // leave the player open for inspection.
        if (isDebugBuild && PlaybackDebugSettings.hudEnabled) return@LaunchedEffect
        // ⚠ **Armed only for automatic picks**, because `onFatalPlaybackError` is only passed by
        // Streamlined and Instant - the same file tapped by hand in Classic has no deadline at
        // all. That asymmetry is why the rule this loop replaced was reported as a mode fault
        // rather than as a player one: it abandoned any auto-picked source that had not started
        // in eight seconds, and it could not see a buffer, so a debrid mint or a large remux
        // doing exactly the right thing was killed, three candidates in a row, and blamed on the
        // catalogue. See `PlaybackStartupWatchdog` for the whole argument.
        val startedAt = TimeSource.Monotonic.markNow()
        var watch = PlaybackStartupWatchdog.initial()
        while (true) {
            delay(PlaybackStartupWatchdog.POLL_INTERVAL_MS)
            val snapshot = playbackSnapshot
            val sample = PlaybackStartupWatchdog.PlaybackStartupSample(
                elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
                isPlaying = snapshot.isPlaying,
                positionMs = snapshot.positionMs,
                bufferedPositionMs = snapshot.bufferedPositionMs,
                durationMs = snapshot.durationMs,
                // Where this play began. An engine reports a pending seek target as its
                // position immediately, so without this a resumed episode looked like 22
                // minutes of progress on its first sample and any dead source was declared
                // started - see `PlaybackStartupSample.baselineMs`.
                baselineMs = activeInitialPositionMs.coerceAtLeast(0L),
            )
            watch = PlaybackStartupWatchdog.observe(watch, sample)
            when (watch.verdict) {
                PlaybackStartupWatchdog.Verdict.Waiting -> Unit
                PlaybackStartupWatchdog.Verdict.Started -> return@LaunchedEffect
                PlaybackStartupWatchdog.Verdict.Abandon -> {
                    val reason = watch.reason
                    // ⚠ **A source abandoned in silence is unfalsifiable from outside a device.**
                    // This is the same rule `NetworkStrengthProbe` carries: "cannot measure" and
                    // "measured badly" look identical on screen. Nothing logged this, so a chain
                    // burning three healthy sources looked exactly like three dead ones.
                    startupLog.w {
                        "abandoning $activeStreamTitle: reason=$reason " +
                            "elapsed=${sample.elapsedMs}ms progress=${watch.bestProgressMs}ms " +
                            "lastAdvance=${watch.lastAdvanceMs}ms duration=${sample.durationMs}ms " +
                            "engine=${snapshot.engineName}"
                    }
                    StreamsRepository.noteAutoPickFailureReason(
                        when (reason) {
                            PlaybackStartupWatchdog.Reason.NeverStarted ->
                                getString(Res.string.playback_startup_never_started)
                            PlaybackStartupWatchdog.Reason.Stalled ->
                                getString(Res.string.playback_startup_stalled)
                            PlaybackStartupWatchdog.Reason.TooSlow ->
                                getString(Res.string.playback_startup_too_slow)
                            null -> null
                        },
                    )
                    if (tryNextEpisodeFallback()) return@LaunchedEffect
                    args.onFatalPlaybackError?.invoke()
                    return@LaunchedEffect
                }
            }
        }
    }

    LaunchedEffect(
        playerController,
        playbackSnapshot.isLoading,
        preferredAudioSelectionApplied,
        preferredSubtitleSelectionApplied,
    ) {
        if (playerController == null || playbackSnapshot.isLoading) {
            return@LaunchedEffect
        }
        if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
            return@LaunchedEffect
        }

        repeat(10) {
            refreshTracks()
            if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        initialSeekApplied,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        if (initialSeekApplied || playbackSnapshot.isLoading) return@LaunchedEffect

        val progressFraction = activeInitialProgressFraction
            ?.takeIf { it > 0f }
            ?.coerceIn(0f, 1f)
        val targetPositionMs = when {
            activeInitialPositionMs > 0L -> activeInitialPositionMs
            progressFraction != null && playbackSnapshot.durationMs > 0L -> {
                (playbackSnapshot.durationMs.toDouble() * progressFraction.toDouble()).toLong()
            }
            progressFraction != null -> return@LaunchedEffect
            else -> 0L
        }
        if (targetPositionMs <= 0L) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        controller.seekTo(targetPositionMs)
        initialSeekApplied = true
    }

    BindPlayerUiVisibilityEffects()
    BindPlayerMetadataAndSkipEffects()

    DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
        val effectVideoId = playbackSession.videoId
        val effectSourceUrl = activeSourceUrl
        val effectSourceAudioUrl = activeSourceAudioUrl
        onDispose {
            if (
                playbackSession.videoId == effectVideoId &&
                activeSourceUrl == effectSourceUrl &&
                activeSourceAudioUrl == effectSourceAudioUrl
            ) {
                flushWatchProgress()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerController?.clearNowPlayingInfo()
            P2pStreamingEngine.shutdown()
            PlayerStreamsRepository.clearAll()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerUiVisibilityEffects() {
    LaunchedEffect(
        controlsVisible,
        isScrubbingTimeline,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        showParentalGuide,
        errorMessage,
    ) {
        if (
            !controlsVisible ||
            isScrubbingTimeline ||
            !playbackSnapshot.isPlaying ||
            playbackSnapshot.isLoading ||
            showParentalGuide ||
            errorMessage != null
        ) {
            return@LaunchedEffect
        }
        delay(3500)
        controlsVisible = false
    }

    LaunchedEffect(playerControlsLocked, lockedOverlayVisible) {
        if (!playerControlsLocked || !lockedOverlayVisible) return@LaunchedEffect
        delay(PlayerLockedOverlayDurationMs)
        lockedOverlayVisible = false
    }

    LaunchedEffect(playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.durationMs, errorMessage) {
        pausedOverlayVisible = false
        if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
            return@LaunchedEffect
        }
        delay(5000)
        pausedOverlayVisible = true
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
    ) {
        if (playbackSnapshot.isEnded) {
            flushWatchProgress(TrackingScrobbleAction.STOP)
            previousIsPlaying = false
            pendingSeekScrobbleRestart = false
            return@LaunchedEffect
        }

        if (previousIsPlaying && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
            pendingSeekScrobbleRestart = false
            flushWatchProgress(TrackingScrobbleAction.PAUSE)
        }

        if (playbackSnapshot.isPlaying && pendingSeekScrobbleRestart) {
            pendingSeekScrobbleRestart = false
            if (hasRequestedScrobbleStartForCurrentItem) {
                emitTrackingSeekScrobbleStart()
            } else {
                emitTrackingScrobbleStart()
            }
        } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
            emitTrackingScrobbleStart()
        }

        if (!playbackSnapshot.isLoading) {
            previousIsPlaying = playbackSnapshot.isPlaying
        }
        if (playbackSnapshot.isPlaying) {
            persistPlaybackProgressTick()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerMetadataAndSkipEffects() {
    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber, parentMetaId, parentMetaType) {
        parentalWarnings = emptyList()
        showParentalGuide = false
        parentalGuideHasShown = false
        playbackStartedForParentalGuide = false

        val imdbId = resolveParentalGuideImdbId() ?: return@LaunchedEffect
        val guide = ParentalGuideRepository.getParentalGuide(imdbId) ?: return@LaunchedEffect
        parentalWarnings = buildParentalWarnings(guide, parentalGuideLabels)

        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, parentalWarnings) {
        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber) {
        skipIntervals = emptyList()
        activeSkipInterval = null
        skipIntervalDismissed = false
        showNextEpisodeCard = false
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlaySearching = false

        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val vid = activeVideoId
        if (season == null || episode == null || vid == null) return@LaunchedEffect

        launch {
            val intervals = when {
                vid.startsWith("mal:") -> {
                    val malId = vid.removePrefix("mal:").substringBefore(':')
                    SkipIntroRepository.getSkipIntervalsForMal(malId = malId, episode = episode)
                }
                vid.startsWith("kitsu:") -> {
                    val kitsuId = vid.removePrefix("kitsu:").substringBefore(':')
                    SkipIntroRepository.getSkipIntervalsForKitsu(kitsuId = kitsuId, episode = episode)
                }
                else -> SkipIntroRepository.getSkipIntervals(
                    imdbId = vid.substringBefore(':').takeIf { it.startsWith("tt") },
                    season = season,
                    episode = episode,
                )
            }
            skipIntervals = intervals
        }
    }

    LaunchedEffect(playbackSnapshot.positionMs, skipIntervals) {
        if (skipIntervals.isEmpty()) {
            activeSkipInterval = null
            return@LaunchedEffect
        }
        val positionSec = playbackSnapshot.positionMs / 1000.0
        val current = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec < interval.endTime
        }
        if (current != activeSkipInterval) {
            activeSkipInterval = current
            if (current != null) skipIntervalDismissed = false
        }
    }

    LaunchedEffect(
        playerMetaVideos,
        activeSeasonNumber,
        activeEpisodeNumber,
        watchProgressUiState.entries,
        watchedUiState.watchedKeys,
    ) {
        if (!isSeries || playerMetaVideos.isEmpty()) {
            nextEpisodeInfo = null
            return@LaunchedEffect
        }
        val curSeason = activeSeasonNumber ?: return@LaunchedEffect
        val curEpisode = activeEpisodeNumber ?: return@LaunchedEffect
        val nextVideo = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = playerMetaVideos,
            currentSeason = curSeason,
            currentEpisode = curEpisode,
        )
        val nextSeason = nextVideo?.season
        val nextEpisode = nextVideo?.episode
        nextEpisodeInfo = if (nextVideo != null && nextSeason != null && nextEpisode != null) {
            val playbackVideoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
                fallbackVideoId = nextVideo.id,
            )
            val isWatched = watchProgressUiState.progressForVideo(
                videoId = playbackVideoId,
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
            )?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = nextVideo,
            )
            NextEpisodeInfo(
                videoId = nextVideo.id,
                season = nextSeason,
                episode = nextEpisode,
                title = nextVideo.title,
                thumbnail = nextVideo.thumbnail,
                overview = nextVideo.overview,
                released = nextVideo.released,
                hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released),
                isWatched = isWatched,
                unairedMessage = if (!PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)) {
                    "$airsPrefix ${nextVideo.released ?: tbaLabel}"
                } else null,
            )
        } else null
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.durationMs,
        nextEpisodeInfo,
        skipIntervals,
        playerSettingsUiState.nextEpisodeThresholdMode,
        playerSettingsUiState.nextEpisodeThresholdPercent,
        playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    ) {
        if (nextEpisodeInfo == null || playbackSnapshot.durationMs <= 0L) {
            showNextEpisodeCard = false
            return@LaunchedEffect
        }
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        if (shouldShow && !showNextEpisodeCard) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true) {
                playNextEpisode()
            }
        } else if (!shouldShow) {
            showNextEpisodeCard = false
        }
    }

    LaunchedEffect(playbackSnapshot.isEnded, nextEpisodeInfo) {
        if (playbackSnapshot.isEnded && nextEpisodeInfo != null && !showNextEpisodeCard) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true) {
                playNextEpisode()
            }
        }
    }
}

private fun PlayerScreenRuntime.buildNowPlayingInfo(): PlayerNowPlayingInfo {
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    return PlayerNowPlayingInfo(
        title = title.ifBlank { activeStreamTitle },
        subtitle = buildNowPlayingSubtitle(
            isEpisode = isEpisode,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        ),
        artworkUrl = firstNonBlankUrl(poster, background),
    )
}

private fun buildNowPlayingSubtitle(
    isEpisode: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String? {
    if (!isEpisode) return null

    val episodeParts = buildList {
        if (seasonNumber != null && episodeNumber != null) {
            add("S${seasonNumber}E${episodeNumber}")
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    return when (episodeParts.size) {
        0 -> null
        1 -> episodeParts.first()
        else -> "${episodeParts[0]} - ${episodeParts[1]}"
    }
}

private fun firstNonBlankUrl(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

internal fun PlayerScreenRuntime.removeFailedStreamFromCache() {
    val currentVideoId = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = currentVideoId,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.remove(cacheKey)
}

/**
 * Ends this play for good, and says so everywhere that has to hear it.
 *
 * ⚠ **Every fatal route has to go through here, including the ones that decide they are fatal
 * only later.** This used to live inline in the `onError` branch of the player surface, which
 * meant the credential refresh had no way to reach it: `tryRefreshCredentialedSourceAfterError`
 * returns `true` the moment it decides to refresh, spending the budget and telling its caller
 * the error is handled, and the work itself happens in a launched job. When that job came back
 * with no candidate - an addon that is down, an item that is gone - it painted `errorMessage`
 * and returned, so `onFatalPlaybackError` never fired and the ranked fallbacks sitting behind it
 * were never tried. A debrid link expiring mid-episode parked the player on a message with a
 * live chain behind it, which is the outcome the `Decline` branch was added to prevent.
 *
 * The debug-HUD guard is deliberate and must stay: diagnostics keeps the failure screen up so a
 * tester can read the engine's real error instead of being returned to details.
 *
 * A null [message] is the engine clearing a previous error rather than reporting one, and it is
 * not a failure at all - it only reaches here because the surface's `onError` hands both through
 * one callback.
 */
internal fun PlayerScreenRuntime.failPlaybackFatally(message: String?) {
    if (message == null) {
        errorMessage = null
        return
    }
    removeFailedStreamFromCache()
    if (isDebugBuild && PlaybackDebugSettings.hudEnabled) {
        errorMessage = message
        controlsVisible = !playerControlsLocked
        return
    }
    // An auto-played next episode advances to its next ranked source rather than showing the
    // user an error mid-binge. The error is deliberately not painted first: the swap is meant
    // to be the only thing they notice.
    if (tryNextEpisodeFallback()) {
        errorMessage = null
        return
    }
    errorMessage = message
    controlsVisible = !playerControlsLocked
    // The engine's own words, carried to the progress overlay of the *next* attempt. This route
    // bumped the attempt counter in silence, and it is the one that covers the most visible
    // failure there is - a source that opens, plays a second and dies.
    StreamsRepository.noteAutoPickFailureReason(message)
    args.onFatalPlaybackError?.invoke()
}

internal fun PlayerScreenRuntime.tryRefreshCredentialedSourceAfterError(message: String?): Boolean {
    val failedUrl = activeSourceUrl
    when (
        credentialRefreshDecision(
            failedUrl = failedUrl,
            refreshesUsed = credentialRefreshesUsed,
            isRefreshInFlight = credentialRefreshJob?.isActive == true,
            lastAttemptedUrl = credentialRefreshAttemptedSourceUrl,
        )
    ) {
        CredentialRefreshDecision.AwaitInFlight -> return true
        // Returning false hands the error to `onFatalPlaybackError`, which is where the failure
        // chain lives. That is the fix: this used to swallow every error forever, so a dead
        // source could never be named, stepped past, or given up on.
        CredentialRefreshDecision.Decline -> return false
        CredentialRefreshDecision.Refresh -> Unit
    }

    val currentVideoId = activeVideoId ?: return false
    credentialRefreshesUsed += 1
    credentialRefreshAttemptedSourceUrl = failedUrl
    removeFailedStreamFromCache()

    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val expectedProviderAddonId = activeProviderAddonId
    val expectedProviderName = activeProviderName
    val expectedStreamTitle = activeStreamTitle
    val expectedBingeGroup = currentStreamBingeGroup
    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber

    errorMessage = null
    controlsVisible = !playerControlsLocked

    credentialRefreshJob = scope.launch {
        PlayerStreamsRepository.loadSources(
            type = type,
            videoId = currentVideoId,
            season = season,
            episode = episode,
            forceRefresh = true,
        )

        var refreshedStream: StreamItem? = null
        var pollCount = 0
        while (pollCount < CREDENTIAL_REFRESH_POLL_COUNT && refreshedStream == null) {
            val state = PlayerStreamsRepository.sourceState.value
            refreshedStream = findCredentialRefreshCandidate(
                streams = state.groups.flatMap { it.streams },
                failedUrl = failedUrl,
                expectedProviderAddonId = expectedProviderAddonId,
                expectedProviderName = expectedProviderName,
                expectedStreamTitle = expectedStreamTitle,
                expectedBingeGroup = expectedBingeGroup,
            )
            if (
                refreshedStream != null ||
                state.emptyStateReason != null ||
                (!state.isAnyLoading && state.groups.isNotEmpty())
            ) {
                break
            }
            delay(CREDENTIAL_REFRESH_POLL_INTERVAL_MS)
            pollCount++
        }

        // ⚠ **Both of these are fatal, not merely disappointing.** The caller was told `true`
        // before this job ran, so nothing else will treat this error as unhandled: if the
        // re-fetch found no candidate, or found only the URL that just died, the failure chain
        // has to be advanced from here or it never runs at all. Painting `errorMessage` and
        // returning left the player parked on a message with live ranked fallbacks behind it.
        val stream = refreshedStream
        if (stream == null) {
            failPlaybackFatally(message)
            return@launch
        }

        val refreshedUrl = stream.playableDirectUrl
        if (refreshedUrl.isNullOrBlank() || refreshedUrl == failedUrl) {
            failPlaybackFatally(message)
            return@launch
        }

        flushWatchProgress()
        stopActiveP2pStream()
        // Same file, new signature. Set before the assignment below, because that assignment is
        // what wakes the reset effect that reads it.
        isCredentialRefreshHandoff = true
        activeSourceUrl = refreshedUrl
        activeSourceAudioUrl = null
        activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
        activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
        activeStreamType = stream.streamType
        activeStreamTitle = stream.streamLabel
        activeStreamSubtitle = stream.streamSubtitle
        activeProviderName = stream.addonName
        activeProviderAddonId = stream.addonId
        currentStreamBingeGroup = stream.behaviorHints.bingeGroup
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
        showSourcesPanel = false
        controlsVisible = true
    }
    return true
}

private fun findCredentialRefreshCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    expectedProviderAddonId: String?,
    expectedProviderName: String,
    expectedStreamTitle: String,
    expectedBingeGroup: String?,
): StreamItem? =
    streams
        .asSequence()
        .mapNotNull { stream ->
            val refreshedUrl = stream.playableDirectUrl?.takeIf { it.isNotBlank() && it != failedUrl }
                ?: return@mapNotNull null
            val providerMatches = if (!expectedProviderAddonId.isNullOrBlank()) {
                stream.addonId == expectedProviderAddonId
            } else {
                stream.addonName == expectedProviderName
            }
            if (!providerMatches) return@mapNotNull null

            var score = 100
            if (stream.streamLabel == expectedStreamTitle) score += 40
            if (!expectedBingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == expectedBingeGroup) {
                score += 20
            }
            if (refreshedUrl.hasLikelyExpiringPlaybackCredentials()) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

private const val CREDENTIAL_REFRESH_POLL_COUNT = 30
private const val CREDENTIAL_REFRESH_POLL_INTERVAL_MS = 500L
