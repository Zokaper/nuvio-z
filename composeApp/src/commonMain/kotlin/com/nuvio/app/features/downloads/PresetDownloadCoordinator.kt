package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object PresetDownloadCoordinator {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(
        meta: MetaDetails,
        scope: DownloadScope,
        preset: DownloadPreset,
        allowMeteredNetwork: Boolean,
    ): Deferred<DownloadBatch> = backgroundScope.async {
        prepare(meta, scope, preset, allowMeteredNetwork)
    }

    suspend fun prepare(
        meta: MetaDetails,
        scope: DownloadScope,
        preset: DownloadPreset,
        allowMeteredNetwork: Boolean,
    ): DownloadBatch = coroutineScope {
        DownloadsRepository.ensureLoaded()
        AddonRepository.initialize()
        val policy = DownloadsRepository.sourcePolicy.value.snapshot()
        val targets = AddonRepository.uiState.value.addons
            .mapIndexedNotNull { index, addon ->
                val manifest = addon.manifest ?: return@mapIndexedNotNull null
                if (!addon.enabled || manifest.resources.none { it.name == "stream" }) {
                    return@mapIndexedNotNull null
                }
                AutomaticAddonTarget(
                    manifestId = manifest.id,
                    manifestName = manifest.name,
                    manifestUrl = addon.manifestUrl,
                    logoUrl = manifest.logoUrl,
                    addonOrder = index,
                )
            }
        val batchTargets = targetsFor(meta, scope)
        val createdAt = DownloadsClock.nowEpochMs()
        val batchId = "batch_${createdAt.toString(36)}_${meta.id.hashCode().toUInt().toString(36)}"
        val initialBatch = DownloadBatch(
            id = batchId,
            scope = scope,
            contentType = if (scope == DownloadScope.Movie) "movie" else "series",
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            presetSnapshot = preset,
            sourcePolicySnapshot = policy,
            entries = batchTargets.map { target ->
                DownloadBatchEntry(
                    id = "${target.videoId}|${target.season ?: -1}|${target.episode ?: -1}",
                    videoId = target.videoId,
                    title = target.title,
                    season = target.season,
                    episode = target.episode,
                    runtimeMinutes = target.runtimeMinutes,
                )
            },
            allowMeteredNetwork = allowMeteredNetwork,
            createdAtEpochMs = createdAt,
        )
        DownloadsRepository.saveBatch(initialBatch)
        val semaphore = Semaphore(AutomaticDownloadDiscovery.MAX_CONCURRENT_EPISODE_DISCOVERIES)
        val entries = batchTargets.map { target ->
            async {
                semaphore.withPermit {
                    prepareEntry(meta, target, preset, policy, targets).also { entry ->
                        DownloadsRepository.updateBatchEntry(batchId, entry)
                    }
                }
            }
        }.awaitAll()

        val preparedBatch = initialBatch.copy(
            entries = entries,
        ).also(DownloadsRepository::saveBatch)
        if (!preparedBatch.requiresReview(DownloadsPlatformDownloader.freeStorageBytes())) {
            DownloadsRepository.queueBatch(preparedBatch.id, approveUnknownSizes = false)
        }
        preparedBatch
    }

    private suspend fun prepareEntry(
        meta: MetaDetails,
        target: BatchTarget,
        preset: DownloadPreset,
        policy: DownloadSourcePolicy,
        addons: List<AutomaticAddonTarget>,
    ): DownloadBatchEntry {
        return runCatching {
            val discovered = AutomaticDownloadDiscovery.discover(
                type = target.contentType,
                videoId = target.videoId,
                addons = addons,
                policySnapshot = policy,
            )
            val resolved = discovered.mapNotNull { candidate ->
                if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate.stream)) {
                    candidate
                } else {
                    when (
                        val result = DirectDebridPlaybackResolver.resolveToPlayableStream(
                            candidate.stream,
                            target.season,
                            target.episode,
                        )
                    ) {
                        is DirectDebridPlayableResult.Success -> {
                            val context = AioDetectionContext(
                                manifestId = candidate.addonKey.manifestId,
                                manifestName = candidate.stream.addonName,
                                manifestUrl = candidate.addonKey.manifestUrl,
                                treatAsAioStreams = candidate.addonKey in policy.aioOverrides,
                            )
                            candidate.copy(
                                stream = result.stream,
                                resolvedUrl = result.stream.playableDirectUrl,
                                facts = SourceFactsExtractor.extract(result.stream, context),
                            )
                        }
                        else -> null
                    }
                }
            }
            val initial = PresetSourceSelector.select(
                candidates = resolved,
                preset = preset,
                policy = policy,
                runtimeMinutes = target.runtimeMinutes,
                isEpisode = target.season != null,
            )
            val verifiedCandidates = when (initial) {
                is SourceSelectionResult.Selected,
                is SourceSelectionResult.ApprovalNeeded,
                -> {
                    val url = when (initial) {
                        is SourceSelectionResult.Selected -> initial.streamUrl
                        is SourceSelectionResult.ApprovalNeeded -> initial.streamUrl
                        else -> ""
                    }
                    resolved.map { candidate ->
                        if (candidate.resolvedUrl == url) {
                            AutomaticDownloadDiscovery.verifyCandidateSize(candidate)
                        } else {
                            candidate
                        }
                    }
                }
                is SourceSelectionResult.NoMatch -> resolved
            }
            val selection = PresetSourceSelector.select(
                candidates = verifiedCandidates,
                preset = preset,
                policy = policy,
                runtimeMinutes = target.runtimeMinutes,
                isEpisode = target.season != null,
            )
            val selectedCandidate = when (selection) {
                is SourceSelectionResult.Selected ->
                    verifiedCandidates.firstOrNull { it.resolvedUrl == selection.streamUrl }
                is SourceSelectionResult.ApprovalNeeded ->
                    verifiedCandidates.firstOrNull { it.resolvedUrl == selection.streamUrl }
                is SourceSelectionResult.NoMatch -> null
            }
            DownloadBatchEntry(
                id = "${target.videoId}|${target.season ?: -1}|${target.episode ?: -1}",
                videoId = target.videoId,
                title = target.title,
                season = target.season,
                episode = target.episode,
                runtimeMinutes = target.runtimeMinutes,
                state = when (selection) {
                    is SourceSelectionResult.Selected -> DownloadBatchEntryState.READY
                    is SourceSelectionResult.ApprovalNeeded -> DownloadBatchEntryState.APPROVAL_NEEDED
                    is SourceSelectionResult.NoMatch -> DownloadBatchEntryState.SKIPPED
                },
                selection = selection,
                streamTitle = selectedCandidate?.stream?.streamLabel,
                streamSubtitle = selectedCandidate?.stream?.streamSubtitle,
                providerName = selectedCandidate?.stream?.addonName,
                providerAddonId = selectedCandidate?.stream?.addonId,
                sourceHeaders = selectedCandidate?.stream?.behaviorHints?.proxyHeaders?.request.orEmpty(),
                failureMessage = (selection as? SourceSelectionResult.NoMatch)?.reason,
            )
        }.getOrElse { error ->
            DownloadBatchEntry(
                id = "${target.videoId}|${target.season ?: -1}|${target.episode ?: -1}",
                videoId = target.videoId,
                title = target.title,
                season = target.season,
                episode = target.episode,
                runtimeMinutes = target.runtimeMinutes,
                state = DownloadBatchEntryState.FAILED,
                failureMessage = error.message ?: "Source discovery failed",
            )
        }
    }

    private fun targetsFor(meta: MetaDetails, scope: DownloadScope): List<BatchTarget> {
        if (scope == DownloadScope.Movie) {
            return listOf(
                BatchTarget(
                    videoId = meta.defaultVideoId ?: meta.id,
                    title = meta.name,
                    contentType = meta.type,
                    runtimeMinutes = meta.runtime.runtimeMinutesOrNull(),
                ),
            )
        }
        val existing = DownloadsRepository.uiState.value.items
            .map(DownloadItem::logicalContentKey)
            .toSet()
        val today = CurrentDateProvider.todayIsoDate()
        val episodes = meta.videos.mapNotNull { it.toBatchEpisode() }
            .map { episode ->
                episode.copy(
                    released = meta.videos.firstOrNull { it.id == episode.videoId }
                        ?.released
                        ?.take(10)
                        ?.let { it <= today }
                        ?: true,
                )
            }
        return DownloadBatchPlanner.episodesForScope(
            episodes = episodes,
            scope = scope,
            existingLogicalKeys = existing,
            parentMetaId = meta.id,
        ).map {
            BatchTarget(
                videoId = it.videoId,
                title = it.title,
                contentType = meta.type,
                season = it.season,
                episode = it.episode,
                runtimeMinutes = it.runtimeMinutes,
            )
        }
    }

    private fun MetaVideo.toBatchEpisode(): BatchEpisode? {
        val seasonNumber = season ?: return null
        val episodeNumber = episode ?: return null
        return BatchEpisode(
            videoId = id,
            title = title,
            season = seasonNumber,
            episode = episodeNumber,
            runtimeMinutes = runtime,
            available = available,
        )
    }

    private fun String?.runtimeMinutesOrNull(): Int? =
        this?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    private data class BatchTarget(
        val videoId: String,
        val title: String,
        val contentType: String,
        val season: Int? = null,
        val episode: Int? = null,
        val runtimeMinutes: Int? = null,
    )
}
