package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackProgress
import com.nuvio.app.features.playback.PlaybackQualityOptions
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSelectionResult
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.PlaybackSourceSelector
import com.nuvio.app.core.network.NetworkQualityRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun CoroutineScope.launchPlayerNextEpisodeAutoPlay(
    previousJob: Job?,
    nextEpisodeInfo: NextEpisodeInfo?,
    targetEpisode: MetaVideo? = null,
    allEpisodes: List<MetaVideo>,
    parentMetaId: String,
    parentMetaType: String,
    contentType: String?,
    settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo) -> Unit,
    /**
     * The ranked remainder behind the chosen source, for the in-player failure chain.
     *
     * The next episode switches source *inside* the running player rather than relaunching
     * through `PlayerLaunch`, so `autoPickedWithFailureChain` - which is how the stream route
     * arms its chain - cannot reach here. The player holds these instead.
     */
    onFallbacksChanged: (List<StreamItem>) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
    onSourceNameChanged: (String?) -> Unit,
    onCountdownChanged: (Int?) -> Unit,
    onNextEpisodeCardVisibleChanged: (Boolean) -> Unit,
): Job? {
    val nextVideo = targetEpisode ?: nextEpisodeInfo?.videoId?.let { nextVideoId ->
        allEpisodes.firstOrNull { video -> video.id == nextVideoId }
    } ?: return null
    if (targetEpisode == null && nextEpisodeInfo?.hasAired != true) return null

    val downloadedNextEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextVideo.season,
        episodeNumber = nextVideo.episode,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    previousJob?.cancel()
    onSearchingChanged(true)
    onSourceNameChanged(null)
    onCountdownChanged(null)

    val type = contentType ?: parentMetaType
    val shouldAutoSelectInManualMode =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                settings.streamAutoPlayNextEpisodeEnabled ||
                    settings.streamAutoPlayPreferBingeGroup
                )

    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            !settings.streamAutoPlayNextEpisodeEnabled &&
            settings.streamAutoPlayPreferBingeGroup

    val effectiveMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
    }
    val effectiveSource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        settings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedAddons
    }
    val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedPlugins
    }
    val effectiveRegex = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        settings.streamAutoPlayRegex
    }
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }

    return launch {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextVideo.id,
            season = nextVideo.season,
            episode = nextVideo.episode,
        )

        val installedAddonNames = AddonRepository.uiState.value.addons
            .enabledAddons()
            .map { it.displayTitle }
            .toSet()
        val debridSettings = DebridSettingsRepository.snapshot()

        val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
        var autoSelectTriggered = false
        var timeoutElapsed = false
        var selectedStream: StreamItem? = null
        val autoSelectSettled = CompletableDeferred<Unit>()

        fun settleAutoSelect() {
            if (!autoSelectSettled.isCompleted) {
                autoSelectSettled.complete(Unit)
            }
        }

        fun selectStream(stream: StreamItem) {
            autoSelectTriggered = true
            selectedStream = stream
            settleAutoSelect()
        }

        fun finishWithoutSelection() {
            autoSelectTriggered = true
            settleAutoSelect()
        }

        fun tryModeSourceSelection(streams: List<StreamItem>): StreamItem? {
            if (settings.playbackMode == PlaybackMode.CLASSIC) return null
            // Cleared on every attempt: a stale chain from the previous episode would send
            // a failure here to a source for the wrong video.
            onFallbacksChanged(emptyList())
            // The episode's own runtime, so a derived option's bitrate is honest here too.
            // This context used to omit it and always assumed the 45-minute fallback.
            val selectionContext = PlaybackSelectionContext(
                runtimeMinutes = nextVideo.runtime,
                isEpisode = true,
                allowTorrentSources = settings.playbackAllowTorrentAutopick,
                // The same preferences the first episode was picked with. Applying them only
                // at the stream route would mean a user's codec or HDR choice held until the
                // next episode auto-played and then quietly stopped.
                preferredAudioLanguage = settings.rankableAudioLanguage,
                codecPreference = settings.playbackCodecPreference,
                dynamicRangePolicy = settings.playbackDynamicRangePolicy,
            )
            val candidates = streams.mapIndexed { index, stream ->
                PlaybackSourceCandidate(
                    stream = stream,
                    facts = SourceFactsExtractor.extract(stream),
                    addonOrder = index,
                )
            }
            // The same derived options the quality sheet would show, picked the way the sheet
            // itself would pick within a row - one selector over one candidate set.
            val options = PlaybackQualityOptions.build(candidates, selectionContext)
            // Prefer the band the user actually chose in the sheet for this show, so episode
            // two is not re-derived from a bandwidth estimate that has moved since episode
            // one. `stickyAffordable` is a tie-break, not a ceiling: it drops the preference
            // the moment the estimate stops carrying it, and never invents a row this
            // episode has no release for.
            val chosenHeight = BingeGroupCacheRepository.sessionQualityHeight(parentMetaId)
            val option = PlaybackQualityOptions.stickyAffordable(
                options = options,
                pinnedHeight = chosenHeight,
                estimatedMbps = NetworkQualityRepository.current().estimatedMbps,
            ) ?: return null
            return when (val result = PlaybackSourceSelector.select(option, selectionContext)) {
                is PlaybackSelectionResult.Play -> {
                    // The rest of the ranked row, so a source that dies mid-binge advances
                    // instead of dropping the user into a manual list. Capped at the same
                    // budget the stream route's chain runs to, so the two cannot disagree
                    // about how many tries the user gets.
                    onFallbacksChanged(result.fallbacks.take(PlaybackProgress.MAX_ATTEMPTS - 1))
                    result.stream
                }
                // AskUncached lands here too: never start a "preparing" placeholder
                // unattended, and there is no one watching to answer a dialog mid-binge.
                else -> null
            }
        }

        fun trySelectStream(streams: List<StreamItem>): StreamItem? =
            if (settings.playbackMode != PlaybackMode.CLASSIC) {
                tryModeSourceSelection(streams)
            } else StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferBingeGroupInSelection = settings.streamAutoPlayPreferBingeGroup,
                bingeGroupOnly = bingeGroupOnlyManualMode,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )

        fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? {
            if (settings.playbackMode != PlaybackMode.CLASSIC) return tryModeSourceSelection(streams)
            if (preferredBingeGroup == null || !settings.streamAutoPlayPreferBingeGroup) return null
            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferBingeGroupInSelection = true,
                bingeGroupOnly = true,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )
        }

        val innerJob = launch {
            PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
                if (state.groups.isEmpty() && state.isAnyLoading) return@collectLatest

                val allStreams = state.groups.flatMap { it.streams }

                if (autoSelectTriggered) {
                    // Already resolved.
                } else if (timeoutElapsed) {
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        }
                    }
                } else if (allStreams.isNotEmpty()) {
                    val earlyMatch = tryBingeGroupOnly(allStreams)
                    if (earlyMatch != null) {
                        selectStream(earlyMatch)
                    }
                }

                if (!autoSelectTriggered && !state.isAnyLoading) {
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        }
                    }
                    if (!autoSelectTriggered) {
                        finishWithoutSelection()
                    }
                    return@collectLatest
                }

                if (autoSelectTriggered) return@collectLatest
            }
        }

        val timeoutMs = timeoutSeconds * 1_000L
        val isBoundedTimeout = timeoutSeconds in 1..30

        if (isBoundedTimeout) {
            delay(timeoutMs)
            timeoutElapsed = true
            if (!autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) {
                    val candidate = trySelectStream(allStreams)
                    if (candidate != null) {
                        selectStream(candidate)
                    }
                }
            }
            if (selectedStream != null) {
                innerJob.cancel()
            } else if (PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }.isNotEmpty()) {
                innerJob.cancel()
                finishWithoutSelection()
            } else {
                val completed = withTimeoutOrNull(timeoutMs) { autoSelectSettled.await() }
                innerJob.cancel()
                if (completed == null && !autoSelectTriggered) {
                    val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                    if (allStreams.isNotEmpty()) {
                        selectedStream = trySelectStream(allStreams)
                    }
                    finishWithoutSelection()
                }
            }
        } else {
            timeoutElapsed = true
            if (!autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) {
                    trySelectStream(allStreams)?.let(::selectStream)
                }
            }
            val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
            innerJob.cancel()
            if (completed == null && !autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) {
                    selectedStream = trySelectStream(allStreams)
                }
                finishWithoutSelection()
            }
        }

        onSearchingChanged(false)
        val selected = selectedStream
        if (selected != null) {
            onSourceNameChanged(selected.addonName)
            for (i in 3 downTo 1) {
                onCountdownChanged(i)
                delay(1000)
            }
            onEpisodeStreamSelected(selected, nextVideo)
            onNextEpisodeCardVisibleChanged(false)
            onCountdownChanged(null)
            onSourceNameChanged(null)
        } else {
            onManualSelectionRequired(nextVideo)
            onNextEpisodeCardVisibleChanged(false)
        }
    }
}
