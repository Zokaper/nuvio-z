package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.core.network.NetworkQualityRepository
import com.nuvio.app.core.network.NetworkThroughputMeter
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.p2pSentinelUrl
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_source_failed_advancing
import nuvio.composeapp.generated.resources.playback_source_failed_advancing_unnamed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.resolveDebridForPlayer(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    onResolved: (StreamItem) -> Unit,
    onStale: () -> Unit,
): Boolean {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return false
    scope.launch {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = season,
            episode = episode,
        )
        when (resolved) {
            is DirectDebridPlayableResult.Success -> onResolved(resolved.stream)
            else -> {
                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                if (resolved == DirectDebridPlayableResult.Stale) {
                    onStale()
                }
            }
        }
    }
    return true
}

internal fun PlayerScreenRuntime.isP2pStream(stream: StreamItem): Boolean =
    stream.needsLocalDebridResolve && stream.p2pInfoHash != null

internal fun PlayerScreenRuntime.openExternalSourceUrl(stream: StreamItem): Boolean {
    if (!stream.shouldOpenExternally) return false
    val url = stream.externalOpenUrl ?: return false
    val openExternalUrl = args.onOpenExternalUrl ?: return false
    openExternalUrl(url)
    showSourcesPanel = false
    showEpisodesPanel = false
    controlsVisible = true
    return true
}

internal fun StreamItem.playerSourceIdentityKey(): String? {
    p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "torrent:$hash:${p2pFileIdx ?: -1}"
    }

    clientResolve?.let { resolve ->
        val raw = resolve.stream?.raw
        val keyParts = listOf(
            addonId,
            resolve.service,
            resolve.serviceIndex?.toString(),
            resolve.infoHash?.trim()?.lowercase(),
            resolve.fileIdx?.toString(),
            resolve.magnetUri,
            resolve.torrentName,
            resolve.filename,
            raw?.torrentName,
            raw?.filename,
            raw?.size?.toString(),
            behaviorHints.filename,
            behaviorHints.videoSize?.toString(),
            streamLabel,
            streamSubtitle,
        ).map { it.orEmpty().trim() }
        if (keyParts.any { it.isNotBlank() }) {
            return "resolve:${keyParts.joinToString("|")}"
        }
    }

    behaviorHints.videoHash?.trim()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "hash:$addonId:$hash:${behaviorHints.videoSize ?: ""}:${behaviorHints.filename.orEmpty()}"
    }

    playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
        return "url:$url"
    }

    val fallbackParts = listOf(
        addonId,
        addonName,
        streamLabel,
        streamSubtitle.orEmpty(),
        behaviorHints.filename.orEmpty(),
        behaviorHints.videoSize?.toString().orEmpty(),
        sourceName.orEmpty(),
        sources.joinToString(","),
    ).map { it.trim() }
    return fallbackParts
        .takeIf { parts -> parts.any { it.isNotBlank() } }
        ?.joinToString(separator = "|", prefix = "meta:")
}

internal fun PlayerScreenRuntime.stopActiveP2pStream() {
    if (activeTorrentInfoHash != null || p2pResolvedSourceUrl != null) {
        P2pStreamingEngine.stopStream()
    }
    activeTorrentInfoHash = null
    activeTorrentFileIdx = null
    activeTorrentFilename = null
    activeTorrentTrackers = emptyList()
    p2pResolvedSourceUrl = null
}

internal fun PlayerScreenRuntime.switchToP2pSourceStream(stream: StreamItem) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = null, isAutoPlay = false)
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.switchToP2pEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    isAutoPlay: Boolean = false,
) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = episode, isAutoPlay = isAutoPlay)
        return
    }
    val preserveTransition = markNextEpisodeStarting(episode, stream.addonName)
    resetEpisodePanelAndNextEpisodeState(preserveTransition = preserveTransition)
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    applyEpisodeStreamMetadata(stream, episode, resume)
}

/**
 * Turns "this bitrate played fine" into a network measurement.
 *
 * The estimate exists so Instant can tell a 5 Mbps encode from a 40 Mbps remux, and until
 * now nothing on the playback path ever fed it - only the download stack did, so a user who
 * never downloads was permanently judged by a hardcoded platform guess.
 *
 * What is recorded is a **lower bound**, and only that. A stream arrives at the file's own
 * bitrate and no faster, so a clean playback proves the line can carry *at least* this much
 * and says nothing about the ceiling; [NetworkQualityRepository.recordSustainedBitrate] is
 * monotonic for exactly that reason. Sampling is deliberately once per source and only after
 * a full minute of unstarved playback, because the interesting failure - a source that
 * starts fine and starves at the two-minute mark - must not be counted as a success.
 */
internal fun PlayerScreenRuntime.observePlaybackForNetworkEstimate() {
    if (networkEstimateRecorded || networkEstimateStalled) return
    val snapshot = playbackSnapshot
    if (snapshot.isEnded || !snapshot.isPlaying) return

    val start = networkEstimateStartPositionMs ?: snapshot.positionMs.also {
        networkEstimateStartPositionMs = it
    }
    val played = snapshot.positionMs - start

    // Startup buffering is not starvation, and judging it as such would disqualify every
    // source before it ever settled: `PlayerPlaybackSnapshot` starts with `isLoading = true`
    // and the buffer is empty by definition.
    if (played < NETWORK_ESTIMATE_SETTLE_GRACE_MS) return

    // Past the grace, a stall disqualifies this source for the rest of the session: a file
    // that starts fine and starves two minutes in is not evidence the line can carry it.
    if (snapshot.isLoading ||
        snapshot.bufferedPositionMs - snapshot.positionMs <= NETWORK_ESTIMATE_STARVED_BUFFER_MS
    ) {
        networkEstimateStalled = true
        NetworkQualityRepository.cancelPlaybackObservation()
        return
    }
    if (played < NETWORK_ESTIMATE_CLEAN_PLAYBACK_MS) return
    networkEstimateRecorded = true
    NetworkQualityRepository.confirmPlaybackBitrate()
}

/** One minute of playback, the settle grace included, before a bitrate counts as sustained. */
private const val NETWORK_ESTIMATE_CLEAN_PLAYBACK_MS = 60_000L
private const val NETWORK_ESTIMATE_SETTLE_GRACE_MS = 12_000L
private const val NETWORK_ESTIMATE_STARVED_BUFFER_MS = 750L

/**
 * Measures what the connection is actually delivering, from the buffer the player already has.
 *
 * This is the signal [observePlaybackForNetworkEstimate] cannot give. That one confirms a
 * *lower bound* after a full minute - useful, but a 6 Mbps file playing perfectly can never
 * contradict a 50 Mbps platform guess, and the quality sheet quoting that guess has long since
 * closed. Buffer growth against the file's own bitrate is a real rate, arrives within seconds,
 * and can correct the estimate downwards.
 *
 * The two run together deliberately and neither replaces the other: this needs a known file
 * bitrate, and the lower-bound path is what still works for a source whose size nobody reported.
 */
internal fun PlayerScreenRuntime.observePlaybackForThroughput() {
    val armed = NetworkQualityRepository.armedPlayback ?: return
    val snapshot = playbackSnapshot
    val outcome = NetworkThroughputMeter.observe(
        state = networkThroughputState,
        sample = NetworkThroughputMeter.Sample(
            elapsedRealtimeMs = playbackObservationClock.elapsedNow().inWholeMilliseconds,
            positionMs = snapshot.positionMs,
            bufferedPositionMs = snapshot.bufferedPositionMs,
            isPlaying = snapshot.isPlaying,
            isLoading = snapshot.isLoading,
            isEnded = snapshot.isEnded,
        ),
        fileBitrateMbps = armed.mbps,
    )
    networkThroughputState = outcome.state
    outcome.measuredMbps?.let { mbps ->
        NetworkQualityRepository.recordMeasuredThroughput(mbps, armed.providerId)
    }
}

/**
 * Whether [stream] is the one currently playing, tried from most to least specific.
 *
 * The label arm is the one that carries a debrid source across resolution:
 * `withResolvedDebridUrl` rewrites `url` and may rewrite `behaviorHints.filename` and
 * `videoSize`, but it leaves `addonId`, `streamLabel` and `streamSubtitle` alone - and those
 * are exactly what the identity key stops being stable across.
 */
internal fun PlayerScreenRuntime.matchesActiveSource(stream: StreamItem): Boolean {
    val activeHash = activeTorrentInfoHash?.trim()?.lowercase()
    if (activeHash != null) {
        return stream.p2pInfoHash?.trim()?.lowercase() == activeHash &&
            stream.p2pFileIdx == activeTorrentFileIdx
    }
    stream.playerSourceIdentityKey()?.let { key ->
        if (key == activeSourceIdentityKey) return true
    }
    stream.playableDirectUrl?.let { url ->
        if (url == activeSourceUrl) return true
    }
    return stream.addonId == activeProviderAddonId &&
        stream.streamLabel == activeStreamTitle &&
        stream.streamSubtitle == activeStreamSubtitle
}

/**
 * A source the *user* picked from the sources panel.
 *
 * Only this refunds the credential-refresh budget. [switchToSource] itself must not: it also
 * serves automatic downshifts, the debug forced swap, and its own re-entrant debrid resolve, so
 * refunding there would hand an automatic retry of a dying source a fresh budget every swap -
 * which is the shape of the loop this budget exists to stop.
 */
internal fun PlayerScreenRuntime.switchToUserSelectedSource(stream: StreamItem) {
    credentialRefreshesUsed = 0
    credentialRefreshAttemptedSourceUrl = null
    // An explicit pick retires the automatic chain. Without this the eight-second watchdog
    // still fires against the chosen source, and a large remux that is merely slow to prepare
    // gets swapped out for a source the user did not ask for.
    nextEpisodeFallbacks = emptyList()
    switchToSource(stream)
}

internal fun PlayerScreenRuntime.switchToSource(stream: StreamItem) {
    // A real source change, so the next URL change is not a re-mint however it got here. Belt
    // and braces - the flag is set immediately before the assignment it excuses - but the cost of
    // it being wrong is a swap that silently keeps the previous source's subtitles and never
    // shows its opening overlay, which would read as the swap not having happened.
    isCredentialRefreshHandoff = false
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            onResolved = { switchToSource(it) },
            onStale = {
                val vid = activeVideoId
                if (vid != null) {
                    PlayerStreamsRepository.loadSources(
                        type = contentType ?: parentMetaType,
                        videoId = vid,
                        season = activeSeasonNumber,
                        episode = activeEpisodeNumber,
                        forceRefresh = true,
                    )
                }
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pSourceStream(stream)
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    val sourceIdentityKey = stream.playerSourceIdentityKey()
    if (url == activeSourceUrl) {
        activeSourceIdentityKey = sourceIdentityKey ?: activeSourceIdentityKey
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSourceIdentityKey = sourceIdentityKey
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.switchToEpisodeStream(stream: StreamItem, episode: MetaVideo) {
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = episode.season,
            episode = episode.episode,
            onResolved = { resolvedStream -> switchToEpisodeStream(resolvedStream, episode) },
            onStale = {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pEpisodeStream(
            stream = stream,
            episode = episode,
            isAutoPlay = nextEpisodeTransition.origin == PlayerNextEpisodeOrigin.AUTOMATIC,
        )
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    val preserveTransition = markNextEpisodeStarting(episode, stream.addonName)
    resetEpisodePanelAndNextEpisodeState(preserveTransition = preserveTransition)
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    applyEpisodeStreamMetadata(stream, episode, resume)
}

internal fun PlayerScreenRuntime.switchToDownloadedEpisode(downloadItem: DownloadItem, episode: MetaVideo) {
    val localFileUri = DownloadsRepository.playableLocalFileUri(downloadItem) ?: return
    val preserveTransition = markNextEpisodeStarting(
        episode = episode,
        sourceName = downloadItem.providerName.takeIf { it.isNotBlank() },
    )
    resetEpisodePanelAndNextEpisodeState(preserveTransition = preserveTransition)
    flushWatchProgress()
    stopActiveP2pStream()

    val fallbackVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        fallbackVideoId = episode.id,
    )
    val resolvedVideoId = episode.id.takeIf { it.isNotBlank() } ?: fallbackVideoId
    val epEntry = WatchProgressRepository.progressForVideo(
        videoId = resolvedVideoId,
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )
        ?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L

    activeSourceUrl = localFileUri
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceIdentityKey = null
    activeStreamTitle = downloadItem.streamTitle.ifBlank {
        episode.title.ifBlank { title }
    }
    activeStreamSubtitle = downloadItem.streamSubtitle
    activeProviderName = downloadItem.providerName.ifBlank { downloadedLabel }
    activeProviderAddonId = downloadItem.providerAddonId
    currentStreamBingeGroup = null
    activeSeasonNumber = episode.season
    activeEpisodeNumber = episode.episode
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview
    activeVideoId = resolvedVideoId
    activeInitialPositionMs = epResumePositionMs
    activeInitialProgressFraction = epResumeFraction
    shouldPlay = true
    controlsVisible = true
}

internal fun PlayerScreenRuntime.playNextEpisode() {
    resolveNextEpisodeVideo()?.let { episode ->
        startNextEpisodeResolution(episode, PlayerNextEpisodeOrigin.AUTOMATIC)
    }
}

/**
 * Handles an explicit Next episode tap as an episode choice, not as background auto-play.
 *
 * The old callback entered [launchPlayerNextEpisodeAutoPlay], whose Classic-era timeout and
 * source rules made the button appear inert and could bypass the active playback mode.
 */
internal fun PlayerScreenRuntime.playNextEpisodeFromControls() {
    val nextVideo = resolveNextEpisodeVideo() ?: return
    val existing = nextEpisodeTransition
    if (existing.targetVideoId == nextVideo.id && existing.isActive) {
        if (
            playerSettingsUiState.playbackMode == com.nuvio.app.features.playback.PlaybackMode.INSTANT &&
            existing.origin == PlayerNextEpisodeOrigin.AUTOMATIC
        ) {
            nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.promoteToManual(existing)
            showNextEpisodeCard = true
            return
        }
        if (existing.origin == PlayerNextEpisodeOrigin.MANUAL) return
    }
    playEpisodeFromPicker(nextVideo)
}

internal fun PlayerScreenRuntime.playEpisodeFromPicker(episode: MetaVideo) {
    beginNextEpisodeTransition(
        episode = episode,
        origin = PlayerNextEpisodeOrigin.MANUAL,
        phase = PlayerNextEpisodePhase.AWAITING_CHOICE,
    )
    if (
        selectDownloadedEpisodeForPlayback(
            parentMetaId = parentMetaId,
            episode = episode,
            onDownloadedEpisodeSelected = { item, video ->
                // The chain was ranked for whatever was playing before this pick; carrying it
                // into a different episode is how a stalled file retries the wrong video.
                nextEpisodeFallbacks = emptyList()
                switchToDownloadedEpisode(item, video)
            },
        )
    ) return

    when (playerEpisodeModeRoute(playerSettingsUiState.playbackMode)) {
        PlayerEpisodeModeRoute.SOURCE_LIST -> openEpisodeSourceList(episode)
        PlayerEpisodeModeRoute.QUALITY_SHEET -> openEpisodeQualitySheet(episode)
        PlayerEpisodeModeRoute.AUTO_PICK ->
            startNextEpisodeResolution(episode, PlayerNextEpisodeOrigin.MANUAL)
    }
}

private fun PlayerScreenRuntime.resolveNextEpisodeVideo(): MetaVideo? {
    val info = nextEpisodeInfo?.takeIf { it.hasAired } ?: return null
    return playerMetaVideos.firstOrNull { video ->
        video.season == info.season && video.episode == info.episode
    } ?: playerMetaVideos.firstOrNull { video -> video.id == info.videoId }
}

private fun PlayerScreenRuntime.beginNextEpisodeTransition(
    episode: MetaVideo,
    origin: PlayerNextEpisodeOrigin,
    phase: PlayerNextEpisodePhase,
): PlayerNextEpisodeTransition {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.begin(
        previousRequestId = nextEpisodeTransition.requestId,
        currentVideoId = activeVideoId,
        targetVideoId = episode.id,
        origin = origin,
        phase = phase,
    )
    return nextEpisodeTransition
}

private fun PlayerScreenRuntime.startNextEpisodeResolution(
    episode: MetaVideo,
    origin: PlayerNextEpisodeOrigin,
) {
    val existing = nextEpisodeTransition
    val preparedManualRequest =
        origin == PlayerNextEpisodeOrigin.MANUAL &&
            existing.targetVideoId == episode.id &&
            existing.origin == PlayerNextEpisodeOrigin.MANUAL &&
            existing.phase == PlayerNextEpisodePhase.AWAITING_CHOICE
    if (existing.targetVideoId == episode.id && existing.isActive) {
        if (preparedManualRequest) {
            nextEpisodeTransition = existing.copy(phase = PlayerNextEpisodePhase.RESOLVING)
        } else if (origin == PlayerNextEpisodeOrigin.MANUAL) {
            nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.promoteToManual(existing)
            showNextEpisodeCard = true
            return
        } else {
            showNextEpisodeCard = true
            return
        }
    }

    val request = if (preparedManualRequest) {
        nextEpisodeTransition
    } else {
        beginNextEpisodeTransition(
            episode = episode,
            origin = origin,
            phase = PlayerNextEpisodePhase.RESOLVING,
        )
    }
    showNextEpisodeCard = true
    val requestId = request.requestId
    val targetVideoId = episode.id

    fun isCurrent(): Boolean = nextEpisodeTransition.isRequest(requestId, targetVideoId)

    nextEpisodeAutoPlayJob = scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = null,
        nextEpisodeInfo = null,
        targetEpisode = episode,
        allEpisodes = playerMetaVideos,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        settings = playerSettingsUiState,
        currentStreamBingeGroup = currentStreamBingeGroup,
        onFallbacksChanged = { nextEpisodeFallbacks = it },
        isRequestCurrent = ::isCurrent,
        shouldCountDownBeforePlayback = {
            isCurrent() && nextEpisodeTransition.shouldCountDown()
        },
        onResult = { result, video ->
            if (!isCurrent()) return@launchPlayerNextEpisodeAutoPlay
            when (result) {
                is PlayerNextEpisodeResolutionResult.DownloadReady ->
                    switchToDownloadedEpisode(result.item, video)
                is PlayerNextEpisodeResolutionResult.StreamReady ->
                    switchToEpisodeStream(result.stream, video)
                is PlayerNextEpisodeResolutionResult.ManualSelectionRequired -> {
                    nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.update(
                        state = nextEpisodeTransition,
                        requestId = requestId,
                        targetVideoId = targetVideoId,
                        phase = PlayerNextEpisodePhase.FAILED,
                    )
                    showNextEpisodeCard = false
                    openEpisodeSourceList(video, automaticSelectionFailure = result.reason)
                }
            }
        },
        onResolving = {
            nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.update(
                state = nextEpisodeTransition,
                requestId = requestId,
                targetVideoId = targetVideoId,
                phase = PlayerNextEpisodePhase.RESOLVING,
                sourceName = null,
            )
        },
        onCountdown = { sourceName, seconds ->
            nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.update(
                state = nextEpisodeTransition,
                requestId = requestId,
                targetVideoId = targetVideoId,
                phase = PlayerNextEpisodePhase.COUNTDOWN,
                sourceName = sourceName,
                countdownSeconds = seconds,
            )
        },
        onStarting = { sourceName ->
            nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.update(
                state = nextEpisodeTransition,
                requestId = requestId,
                targetVideoId = targetVideoId,
                phase = PlayerNextEpisodePhase.STARTING,
                sourceName = sourceName,
            )
            showNextEpisodeCard = true
        },
    )
}

/**
 * Advances an auto-played next episode to the next ranked source, if there is one.
 *
 * Returns false when the chain is empty or spent, which is the caller's cue to fall through
 * to whatever it did before this existed - `onFatalPlaybackError` for the stream route's own
 * chain, or the episode panel.
 *
 * The episode is resolved from `activeVideoId` rather than carried alongside the chain:
 * `switchToEpisodeStream` has already set the `active*` fields to the episode these fallbacks
 * belong to, so a second copy of that fact could only ever disagree with it. If it cannot be
 * resolved the chain is dropped, because switching to a source without knowing which episode
 * it is for is how a retry plays the wrong video.
 */
internal fun PlayerScreenRuntime.tryNextEpisodeFallback(): Boolean {
    val next = nextEpisodeFallbacks.firstOrNull() ?: return false
    val episode = playerMetaVideos.firstOrNull { it.id == activeVideoId }
    if (episode == null) {
        nextEpisodeFallbacks = emptyList()
        return false
    }
    nextEpisodeFallbacks = nextEpisodeFallbacks.drop(1)
    // Named, because a silent swap mid-binge is indistinguishable from a stutter - the same
    // reasoning as `announceSourceFailure` on the stream route.
    // Only ever the source that died - naming `next` here told the user the source about to
    // play had already failed. Downloaded and P2P sources can leave the title blank, so the
    // provider name stands in before giving up on naming it at all.
    val failed = activeStreamTitle.takeIf { it.isNotBlank() }
        ?: activeProviderName.takeIf { it.isNotBlank() }
    scope.launch {
        NuvioToastController.show(
            if (failed == null) {
                getString(Res.string.playback_source_failed_advancing_unnamed)
            } else {
                getString(Res.string.playback_source_failed_advancing, failed)
            },
        )
    }
    switchToEpisodeStream(next, episode)
    return true
}

internal fun PlayerScreenRuntime.openEpisodeSourceList(
    episode: MetaVideo,
    automaticSelectionFailure: PlayerNextEpisodeFailureReason? = null,
) {
    episodeQualitySheetEpisode = null
    if (nextEpisodeTransition.targetVideoId == episode.id) {
        nextEpisodeTransition = nextEpisodeTransition.copy(
            phase = PlayerNextEpisodePhase.AWAITING_CHOICE,
            countdownSeconds = null,
        )
    }
    PlayerStreamsRepository.loadEpisodeStreams(
        type = contentType ?: parentMetaType,
        videoId = episode.id,
        season = episode.season,
        episode = episode.episode,
    )
    episodeStreamsPanelState = EpisodeStreamsPanelState(
        showStreams = true,
        selectedEpisode = episode,
        automaticSelectionFailure = automaticSelectionFailure,
    )
    showEpisodesPanel = true
    controlsVisible = false
}

private fun PlayerScreenRuntime.openEpisodeQualitySheet(episode: MetaVideo) {
    PlayerStreamsRepository.loadEpisodeStreams(
        type = contentType ?: parentMetaType,
        videoId = episode.id,
        season = episode.season,
        episode = episode.episode,
    )
    episodeStreamsPanelState = EpisodeStreamsPanelState(selectedEpisode = episode)
    episodeQualitySheetEpisode = episode
    showEpisodesPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.openSourcesPanel() {
    val vid = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = vid,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    showSourcesPanel = true
    showEpisodesPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.openEpisodesPanel() {
    if (playerMetaVideos.isEmpty()) {
        scope.launch {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }
    showEpisodesPanel = true
    showSourcesPanel = false
    controlsVisible = false
}

private data class EpisodeResume(val positionMs: Long, val fraction: Float?)

private fun PlayerScreenRuntime.markNextEpisodeStarting(
    episode: MetaVideo,
    sourceName: String?,
): Boolean {
    val transition = nextEpisodeTransition
    if (transition.targetVideoId != episode.id || !transition.isActive) return false
    nextEpisodeTransition = transition.copy(
        phase = PlayerNextEpisodePhase.STARTING,
        sourceName = sourceName ?: transition.sourceName,
        countdownSeconds = null,
    )
    showNextEpisodeCard = true
    return true
}

internal fun PlayerScreenRuntime.cancelNextEpisodeTransition(suppressForCurrentEpisode: Boolean) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    if (suppressForCurrentEpisode) {
        nextEpisodeDismissedForVideoId = activeVideoId
    }
    nextEpisodeTransition = PlayerNextEpisodeTransitionPolicy.cancel(nextEpisodeTransition)
    showNextEpisodeCard = false
}

internal fun PlayerScreenRuntime.completeNextEpisodeTransitionIfStarted() {
    val transition = nextEpisodeTransition
    if (
        transition.phase != PlayerNextEpisodePhase.STARTING ||
        transition.targetVideoId != activeVideoId
    ) return
    nextEpisodeAutoPlayJob = null
    nextEpisodeTransition = transition.copy(
        phase = PlayerNextEpisodePhase.IDLE,
        origin = null,
        currentVideoId = null,
        targetVideoId = null,
        sourceName = null,
        countdownSeconds = null,
    )
    showNextEpisodeCard = false
}

private fun PlayerScreenRuntime.resetEpisodePanelAndNextEpisodeState(
    preserveTransition: Boolean = false,
) {
    showNextEpisodeCard = preserveTransition
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    episodeQualitySheetEpisode = null
    if (!preserveTransition) {
        cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
    }
    PlayerStreamsRepository.clearEpisodeStreams()
}

private fun PlayerScreenRuntime.resolveEpisodeResume(epVideoId: String, episode: MetaVideo): EpisodeResume {
    val epResumeVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        fallbackVideoId = epVideoId,
    )
    val epEntry = WatchProgressRepository.progressForVideo(
        videoId = epVideoId.takeIf { it.isNotBlank() } ?: epResumeVideoId,
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L
    return EpisodeResume(positionMs = epResumePositionMs, fraction = epResumeFraction)
}

private fun PlayerScreenRuntime.applyEpisodeStreamMetadata(
    stream: StreamItem,
    episode: MetaVideo,
    resume: EpisodeResume,
) {
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeSeasonNumber = episode.season
    activeEpisodeNumber = episode.episode
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview
    activeVideoId = episode.id
    activeInitialPositionMs = resume.positionMs
    activeInitialProgressFraction = resume.fraction
    shouldPlay = true
    controlsVisible = true
}
