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
import com.nuvio.app.features.playback.AutoDownshiftCandidates
import com.nuvio.app.features.playback.AutoDownshiftDetector
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.SwapDiagnosticsLog
import com.nuvio.app.features.playback.qualityLabel
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_source_failed_advancing
import nuvio.composeapp.generated.resources.playback_source_failed_advancing_unnamed
import nuvio.composeapp.generated.resources.player_source_switched
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

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

internal fun PlayerScreenRuntime.p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
    "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

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

internal fun PlayerScreenRuntime.saveP2pStreamForReuse(
    stream: StreamItem,
    videoId: String?,
    season: Int?,
    episode: Int?,
) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled || videoId == null) return
    val infoHash = stream.p2pInfoHash ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
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
    saveP2pStreamForReuse(
        stream = stream,
        videoId = activeVideoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
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
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    saveP2pStreamForReuse(
        stream = stream,
        videoId = epVideoId,
        season = episode.season,
        episode = episode.episode,
    )
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
    // and the buffer is empty by definition. This is the same reason
    // [AutoDownshiftDetector.SETTLE_GRACE_MS] exists, and it is the same grace.
    if (played < AutoDownshiftDetector.SETTLE_GRACE_MS) return

    // Past the grace, a stall disqualifies this source for the rest of the session: a file
    // that starts fine and starves two minutes in is not evidence the line can carry it.
    if (snapshot.isLoading ||
        snapshot.bufferedPositionMs - snapshot.positionMs <= AutoDownshiftDetector.STARVED_BUFFER_MS
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
            // The same monotonic clock the downshift detector folds, for the same reason: a
            // window measured in snapshots means 2 s on Android and 4 s on desktop.
            elapsedRealtimeMs = autoDownshiftClock.elapsedNow().inWholeMilliseconds,
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
 * Instant's automatic source downshift, folded once per playback snapshot.
 *
 * Everything that decides *whether* to swap is in [AutoDownshiftDetector] and
 * [AutoDownshiftCandidates], both pure and both tested; this function only supplies the
 * clock, the candidate list and the existing position-preserving [switchToSource] path.
 *
 * The session's one swap is charged only when a swap actually happens. Identifying the
 * currently playing candidate is the fiddly part and it must not be done by URL alone:
 * `switchToSource` re-enters with the *debrid-resolved* stream, so `activeSourceUrl` holds
 * a minted URL that no candidate in the source list carries, and a P2P source holds a
 * sentinel URL that matches nothing at all. Instant's users are mostly on debrid, so URL
 * matching would make this a silent no-op on the main path.
 */
internal fun PlayerScreenRuntime.observePlaybackForAutoDownshift() {
    val settings = playerSettingsUiState
    // The availability test comes first and is not the mode's. `playbackAutoDownshift` could
    // have been stored true by a profile that was on Instant in `0.4.9-beta`, and bringing
    // Instant back must not silently wake a mid-playback source swap nobody has watched.
    if (!AutoDownshiftDetector.AUTO_DOWNSHIFT_AVAILABLE) return
    if (!settings.playbackAutoDownshift || settings.playbackMode != PlaybackMode.INSTANT) return

    val sample = AutoDownshiftDetector.Sample(
        elapsedRealtimeMs = autoDownshiftClock.elapsedNow().inWholeMilliseconds,
        positionMs = playbackSnapshot.positionMs,
        bufferedPositionMs = playbackSnapshot.bufferedPositionMs,
        isPlaying = playbackSnapshot.isPlaying,
        isLoading = playbackSnapshot.isLoading,
        isEnded = playbackSnapshot.isEnded,
    )

    // Warm the source list while the run is still building, so a fired trigger has
    // something to choose from without waiting on a fetch mid-stall.
    if (sample.isStarved && sample.isActive && !autoDownshiftSourcesRequested) {
        autoDownshiftSourcesRequested = true
        val videoId = activeVideoId
        if (videoId != null) {
            scope.launch {
                PlayerStreamsRepository.loadSources(
                    type = contentType ?: parentMetaType,
                    videoId = videoId,
                    season = activeSeasonNumber,
                    episode = activeEpisodeNumber,
                )
            }
        }
    }

    val outcome = AutoDownshiftDetector.observe(autoDownshiftState, sample, enabled = true)
    autoDownshiftState = outcome.state
    if (!outcome.shouldDownshift) return

    val streams = PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }
    val candidates = streams.map { PlaybackSourceCandidate(stream = it) }
    val current = candidates.firstOrNull { matchesActiveSource(it.stream) } ?: return
    val replacement = AutoDownshiftCandidates.select(current, candidates) ?: return
    autoDownshiftState = AutoDownshiftDetector.consumeSwap(autoDownshiftState)
    beginDiagnosedSwap(
        trigger = SwapDiagnosticsLog.Trigger.AUTO_DOWNSHIFT,
        current = current,
        replacement = replacement,
        bufferAheadMs = sample.bufferedAheadMs,
    )
    switchToSource(replacement.stream)
}

/**
 * Logs a swap, starts its clock, and tells the user, then leaves the caller to perform it.
 *
 * The announcement is deliberately *not* inside `switchToSource`: that path also serves the
 * user picking a source by hand, where a toast saying what they just chose is noise. It is an
 * automatic change of quality that is indistinguishable from a bug when it happens silently.
 *
 * **The clock starts here rather than in `switchToSource`, and both reasons matter.**
 * `switchToSource` re-enters itself for debrid - the first call kicks off an async link mint
 * and returns, the resolved stream comes back through a second call - so starting it there
 * would exclude the minting wait on exactly the path Instant users are almost always on, and
 * understate what they sit through. And because `switchToSource` also serves manual picks, a
 * mark set there could be closed by a hand-picked source's first frame and credited to an
 * earlier automatic swap that never rendered at all. Pairing the mark with the record makes
 * both impossible: no record, no clock.
 */
internal fun PlayerScreenRuntime.beginDiagnosedSwap(
    trigger: SwapDiagnosticsLog.Trigger,
    current: PlaybackSourceCandidate,
    replacement: PlaybackSourceCandidate,
    bufferAheadMs: Long,
) {
    SwapDiagnosticsLog.record(
        SwapDiagnosticsLog.SwapRecord(
            trigger = trigger,
            fromLabel = current.stream.streamLabel,
            toLabel = replacement.stream.streamLabel,
            fromHeight = current.facts.resolution?.height,
            toHeight = replacement.facts.resolution?.height,
            fromReleaseGroup = current.facts.releaseGroup,
            toReleaseGroup = replacement.facts.releaseGroup,
            fromProvider = current.facts.providerName ?: current.facts.providerId,
            toProvider = replacement.facts.providerName ?: replacement.facts.providerId,
            fromAddon = current.stream.addonName,
            toAddon = replacement.stream.addonName,
            bufferAheadMsAtTrigger = bufferAheadMs,
            positionMsBefore = playbackSnapshot.positionMs.coerceAtLeast(0L),
        ),
    )
    swapStartedAt = TimeSource.Monotonic.markNow()
    val label = replacement.facts.resolution.qualityLabel
    if (label.isNotBlank()) {
        scope.launch {
            NuvioToastController.show(getString(Res.string.player_source_switched, label))
        }
    }
}

internal fun PlayerScreenRuntime.forceDebugSourceSwap(upshift: Boolean) {
    val candidates = PlayerStreamsRepository.sourceState.value.groups
        .flatMap { it.streams }
        .map { PlaybackSourceCandidate(stream = it) }
    val current = candidates.firstOrNull { matchesActiveSource(it.stream) }
    if (current == null) {
        debugStatusMessage = "Current source is not in the loaded catalogue."
        return
    }
    val replacement = if (upshift) {
        AutoDownshiftCandidates.selectUpshift(current, candidates)
    } else {
        AutoDownshiftCandidates.select(current, candidates)
    }
    if (replacement == null) {
        debugStatusMessage = if (upshift) {
            "No safe higher source in the same release group."
        } else {
            "No safe lower source in the same release group."
        }
        return
    }
    debugStatusMessage = "Forcing ${if (upshift) "upshift" else "downshift"}…"
    beginDiagnosedSwap(
        trigger = if (upshift) {
            SwapDiagnosticsLog.Trigger.FORCED_UPSHIFT
        } else {
            SwapDiagnosticsLog.Trigger.FORCED_DOWNSHIFT
        },
        current = current,
        replacement = replacement,
        bufferAheadMs = (playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs)
            .coerceAtLeast(0L),
    )
    switchToSource(replacement.stream)
}

internal fun PlayerScreenRuntime.resetDebugSwapBudget() {
    autoDownshiftState = AutoDownshiftDetector.initial()
    autoDownshiftClock = TimeSource.Monotonic.markNow()
    autoDownshiftSourcesRequested = false
    debugStatusMessage = "Automatic swap budget reset."
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
    val currentVideoId = activeVideoId
    if (playerSettingsUiState.streamReuseLastLinkEnabled && currentVideoId != null) {
        saveDirectStreamForReuse(stream, url, currentVideoId, activeSeasonNumber, activeEpisodeNumber)
    }
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
        switchToP2pEpisodeStream(stream, episode)
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    if (playerSettingsUiState.streamReuseLastLinkEnabled) {
        saveDirectStreamForReuse(stream, url, epVideoId, episode.season, episode.episode)
    }
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    applyEpisodeStreamMetadata(stream, episode, resume)
}

internal fun PlayerScreenRuntime.switchToDownloadedEpisode(downloadItem: DownloadItem, episode: MetaVideo) {
    val localFileUri = DownloadsRepository.playableLocalFileUri(downloadItem) ?: return
    resetEpisodePanelAndNextEpisodeState()
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
    controlsVisible = true
}

internal fun PlayerScreenRuntime.playNextEpisode() {
    scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = nextEpisodeAutoPlayJob,
        nextEpisodeInfo = nextEpisodeInfo,
        allEpisodes = playerMetaVideos,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        settings = playerSettingsUiState,
        currentStreamBingeGroup = currentStreamBingeGroup,
        onDownloadedEpisodeSelected = { item, episode -> switchToDownloadedEpisode(item, episode) },
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onFallbacksChanged = { nextEpisodeFallbacks = it },
        onManualSelectionRequired = { nextVideo ->
            episodeStreamsPanelState = EpisodeStreamsPanelState(
                showStreams = true,
                selectedEpisode = nextVideo,
            )
            showEpisodesPanel = true
        },
        onSearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onSourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        onCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeCardVisibleChanged = { showNextEpisodeCard = it },
    )?.let { job ->
        nextEpisodeAutoPlayJob = job
    }
}

internal fun PlayerScreenRuntime.playEpisodeFromPicker(episode: MetaVideo) {
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

    if (playerSettingsUiState.playbackMode == com.nuvio.app.features.playback.PlaybackMode.CLASSIC) {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = contentType ?: parentMetaType,
            videoId = episode.id,
            season = episode.season,
            episode = episode.episode,
        )
        episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
        return
    }

    nextEpisodeAutoPlayJob = scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = nextEpisodeAutoPlayJob,
        nextEpisodeInfo = null,
        targetEpisode = episode,
        allEpisodes = playerMetaVideos,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        settings = playerSettingsUiState,
        currentStreamBingeGroup = currentStreamBingeGroup,
        onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
        onEpisodeStreamSelected = { stream, video -> switchToEpisodeStream(stream, video) },
        onFallbacksChanged = { nextEpisodeFallbacks = it },
        onManualSelectionRequired = { video ->
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = video)
            showEpisodesPanel = true
        },
        onSearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onSourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        onCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeCardVisibleChanged = { showNextEpisodeCard = it },
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

private fun PlayerScreenRuntime.resetEpisodePanelAndNextEpisodeState() {
    showNextEpisodeCard = false
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
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
    controlsVisible = true
}

private fun PlayerScreenRuntime.saveDirectStreamForReuse(
    stream: StreamItem,
    url: String,
    videoId: String,
    season: Int?,
    episode: Int?,
) {
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = url,
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
        responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
        filename = stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        bingeGroup = stream.behaviorHints.bingeGroup,
        streamType = stream.streamType,
        contentLanguage = resolveContentLanguage(
            language = metaUiState.meta?.language,
            country = metaUiState.meta?.country,
        ),
    )
}
