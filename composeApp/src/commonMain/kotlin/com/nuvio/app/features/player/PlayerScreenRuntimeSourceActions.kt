package com.nuvio.app.features.player

import com.nuvio.app.isDesktop
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
import co.touchlab.kermit.Logger
import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.shouldPublishPartySourceChange
import com.nuvio.app.features.watchparty.matchesPlayback
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.streams.p2pSentinelUrl
import com.nuvio.app.features.watchparty.toPartySourceDescriptor
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_source_failed_advancing
import nuvio.composeapp.generated.resources.playback_source_failed_advancing_unnamed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch
import com.nuvio.app.features.watchparty.ownsNextEpisodeChoice

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
    activePartySourceDescriptor = stream.toPartySourceDescriptor()
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
 * serves in-player source changes and re-entrant debrid resolution, so refunding there would hand
 * an automatic retry of a dying source a fresh budget every attempt - which is the shape of the
 * loop this budget exists to stop.
 */
internal fun PlayerScreenRuntime.switchToUserSelectedSource(stream: StreamItem) {
    credentialRefreshesUsed = 0
    credentialRefreshAttemptedSourceUrl = null
    // An explicit pick retires the automatic chain. Without this the eight-second watchdog
    // still fires against the chosen source, and a large remux that is merely slow to prepare
    // gets swapped out for a source the user did not ask for.
    nextEpisodeFallbacks = emptyList()
    // A hand-picked source: "Prefer built-in subtitles" steps aside for the rest of this player.
    activeSourceAutoPicked = false
    publishPartySourceChange(stream)
    switchToSource(stream)
}

/**
 * Moves the whole party to the source this member just picked, exactly once.
 *
 * Only an explicit pick reaches here, and only while the party is playing this content and this
 * member is permitted to change it. Everything else - an automatic chain step, a debrid
 * re-resolution, a credential re-mint, a guest's alternate under host-only control - changes local
 * playback and says nothing to the party, because none of those is somebody choosing what everyone
 * watches.
 *
 * The generation is advanced with the one the party is currently on as the expected value, so two
 * members picking at the same instant produce one advance and one rejection rather than two
 * advances. `partyPublishedSourceGeneration` is the local half of the same guarantee: a retry or a
 * recomposition of the same pick cannot advance it twice.
 *
 * For the **host**, a pick of a different release advances the party even when the two look alike
 * enough to score `EquivalentMedia`. That tier is the one the automatic paths must treat as a
 * duplicate and the one a person cannot have meant: the host opened the panel and chose another
 * release, and the party's timeline is whatever the host is watching. Re-picking the release the
 * party is already on is still refused, for either member.
 */
private fun PlayerScreenRuntime.publishPartySourceChange(stream: StreamItem) {
    val party = WatchPartyRepository.uiState.value.party
        ?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
        ?: return
    val picked = stream.toPartySourceDescriptor() ?: return
    if (
        !shouldPublishPartySourceChange(
            party = party,
            profileId = WatchPartyRepository.uiState.value.activeProfileId,
            picked = picked,
            publishedSourceGeneration = partyPublishedSourceGeneration,
            // Only this call site sets it, and only because only this one is a person. The guard's
            // duplicate test cannot tell a host deliberately changing release from a realization
            // that flapped onto a look-alike, so for the host it narrows to the party's own
            // release: re-picking what is playing is still refused, picking a different release is
            // now the authoritative change the sources panel says it is.
            explicitHostSelection = true,
        )
    ) return
    partyPublishedSourceGeneration = party.sourceGeneration
    // This player is already on the new source the moment `switchToSource` runs, so it owes the
    // party no adoption for the generation it is about to create.
    partyHandledSourceGeneration = party.sourceGeneration + 1
    scope.launch {
        WatchPartyRepository.selectSource(
            fingerprint = picked,
            expectedSourceGeneration = party.sourceGeneration,
        ).onFailure {
            // The advance was refused - somebody else moved the party first. The local swap still
            // stands as an alternate, and the authoritative source is whatever the party says it
            // is: releasing both latches lets the next snapshot decide, including by handing this
            // player back to a source it has just left.
            partyPublishedSourceGeneration = null
            partyHandledSourceGeneration = null
        }
    }
}

/** The same tag the party effect logs under, so a realignment reads in sequence with the gate. */
private val sourceActionsPartyLog = Logger.withTag("WatchPartyPlayer")

/**
 * Moves the party onto the release the *host* has ended up on, after its own chain moved it there.
 *
 * The automatic chain is local by design and stays local for everything that produces another URL
 * for the same bytes - see `partySourceTimelineDecision`. This is the one case it cannot be: the
 * host defines the party's timeline, so a host on a different release has already changed what the
 * shared timestamp means, and the only honest thing left is to say so. The party then does what it
 * does for a hand-picked source: the generation advances, the start gate closes, every guest
 * re-realizes against the new descriptor, and the readiness barrier starts everyone together.
 *
 * Guarded exactly as the deliberate pick is - `shouldPublishPartySourceChange` refuses a
 * republication of the source the party is already on, and `partyPublishedSourceGeneration` refuses
 * a second advance for the same generation - because a realization that flaps must not turn into a
 * party that re-realizes on every flap.
 */
internal fun PlayerScreenRuntime.publishHostPartySourceRealignment(
    party: WatchPartyState,
    descriptor: PartySourceDescriptorV2,
    timelineChanged: Boolean = false,
) {
    if (
        !shouldPublishPartySourceChange(
            party = party,
            profileId = WatchPartyRepository.uiState.value.activeProfileId,
            picked = descriptor,
            publishedSourceGeneration = partyPublishedSourceGeneration,
            // The caller's verdict, which is stronger than the duplicate heuristic and is the
            // only thing that reaches this function: `AdvancePartySource`. Without it the guard
            // refuses a re-cut file as a duplicate descriptor and a look-alike release as an
            // equivalent, and the party keeps a timeline the host has already left - the same
            // silent divergence this path exists to end, arriving by the one door left open.
            timelineChanged = timelineChanged,
        )
    ) return
    partyPublishedSourceGeneration = party.sourceGeneration
    partyHandledSourceGeneration = party.sourceGeneration + 1
    sourceActionsPartyLog.i {
        "host source realignment party=${party.id} generation=${party.sourceGeneration} " +
            "release=${descriptor.releaseFingerprint}"
    }
    scope.launch {
        WatchPartyRepository.selectSource(
            fingerprint = descriptor,
            expectedSourceGeneration = party.sourceGeneration,
        ).onFailure {
            partyPublishedSourceGeneration = null
            partyHandledSourceGeneration = null
        }
    }
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
    activePartySourceDescriptor = stream.toPartySourceDescriptor()
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
    activePartySourceDescriptor = null
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
    // A downloaded episode has no descriptor a guest could match, so the party moves to the new
    // episode with no source and waits for the host to pick a shareable one. Leaving it on the
    // previous episode while the host watches this one would be the worse answer.
    publishPartyEpisodeChange(episode, descriptor = null)
}

/**
 * Whether this player decides its own next episode, or follows a party that decides for it.
 *
 * ⚠ **The countdown is host-only, and this is the whole of that rule.** A guest running its own
 * autoplay-next would advance one client while the party stayed where it was, and two members
 * choosing different episodes is precisely what one authoritative `content_generation` exists to
 * prevent. A guest gets the party's own loading and barrier feedback instead of a countdown it
 * cannot honour.
 *
 * Read against the party's *content*: a member watching something else with a party open in the
 * background is having an ordinary evening and keeps their Next episode button.
 */
internal val PlayerScreenRuntime.ownsNextEpisode: Boolean
    get() {
        val partyUi = WatchPartyRepository.uiState.value
        return ownsNextEpisodeChoice(
            party = partyUi.party,
            profileId = partyUi.activeProfileId,
            localContentId = parentMetaId,
            localVideoId = playbackSession.videoId,
        )
    }

internal fun PlayerScreenRuntime.playNextEpisode() {
    if (!ownsNextEpisode) return
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
    if (!ownsNextEpisode) return
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
    if (isDesktop) {
        // The Compose sheet would sit under the native video surface. The native controls layer
        // draws the same rows in its episode list - see `PlayerEpisodeQualityChooser.kt`.
        episodeQualitySheetEpisode = null
        episodeStreamsPanelState = EpisodeStreamsPanelState(
            showStreams = true,
            selectedEpisode = episode,
            qualityChooser = true,
        )
        showEpisodesPanel = true
        controlsVisible = false
        return
    }
    episodeStreamsPanelState = EpisodeStreamsPanelState(selectedEpisode = episode)
    episodeQualitySheetEpisode = episode
    showEpisodesPanel = false
    controlsVisible = false
}

/**
 * A row chosen in the native Streamlined chooser, resolved the way the Compose sheet's
 * `onOptionSelected` resolves one. Returns the stream to start, or null when the list was opened
 * instead (or the row no longer exists).
 */
internal fun PlayerScreenRuntime.resolveEpisodeQualityChoice(index: Int): StreamItem? {
    val episode = episodeStreamsPanelState.selectedEpisode ?: return null
    val context = streamlinedEpisodeSelectionContext(playerSettingsUiState, episode)
    val choice = episodeQualityChoices(episodeStreamsRepoState, context).getOrNull(index) ?: return null
    return when (val pick = decideEpisodeQualityPick(choice, context)) {
        is EpisodeQualityPick.Play -> {
            // The rest of the row, so a source that dies advances within the chosen quality.
            nextEpisodeFallbacks = pick.fallbacks.take(com.nuvio.app.features.playback.PlaybackProgress.MAX_ATTEMPTS - 1)
            pick.stream
        }
        EpisodeQualityPick.ShowSourceList -> {
            openEpisodeSourceList(
                episode,
                automaticSelectionFailure = PlayerNextEpisodeFailureReason.NO_SAFE_CANDIDATE
                    .takeIf { choice is EpisodeQualityChoice.Option },
            )
            null
        }
    }
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
    activePartySourceDescriptor = stream.toPartySourceDescriptor()
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
    // Every way of changing episode converges here, so this is where a host moves the party.
    // A no-op for a guest and for a host already on this content.
    publishPartyEpisodeChange(episode, activePartySourceDescriptor)
}
