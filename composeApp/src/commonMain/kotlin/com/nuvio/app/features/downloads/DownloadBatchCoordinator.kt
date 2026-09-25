package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.playback.playbackSelectionContextOf
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The title a download belongs to, as the queue stores it. */
data class DownloadTitleRef(
    val parentMetaId: String,
    val parentMetaType: String,
    /** "movie" or "series": the batch's content type. */
    val contentType: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
)

/** One film or episode to download. */
data class DownloadTarget(
    val videoId: String,
    val title: String,
    /** The addon content type the stream request uses. */
    val contentType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int? = null,
    val thumbnail: String? = null,
) {
    val entryId: String get() = "$videoId|${season ?: -1}|${episode ?: -1}"
    val isEpisode: Boolean get() = season != null
}

/**
 * Discovery and selection for every download that Nuvio picks a source for (Phase 9, stage 6).
 * Replaces `PresetDownloadCoordinator`: the rules are the profile's [DownloadPolicy], applied by
 * [DownloadSourceSelector], and the user's choices (an Assisted resolution, a Manual pick) are
 * inputs rather than separate code paths.
 *
 * Nothing here decides *which* flow runs - that is [DownloadFlowController]'s. This turns targets
 * into batch entries and hands them to the queue.
 */
object DownloadBatchCoordinator {

    /** Test seam: replaces addon discovery. */
    internal var discoverOverride: (suspend (DownloadTarget) -> List<DownloadSourceCandidate>)? = null

    /** Test seam: replaces the HEAD size check of a picked direct source. */
    internal var verifySizeOverride: (suspend (DownloadSourceCandidate) -> DownloadSourceCandidate)? = null

    /** Test seam: the selection context. */
    internal var contextOverride: ((DownloadTarget, DownloadPolicy) -> DownloadSourceSelector.Context)? = null

    fun titleRefOf(meta: MetaDetails): DownloadTitleRef = DownloadTitleRef(
        parentMetaId = meta.id,
        parentMetaType = meta.type,
        contentType = if (meta.isSeriesLike()) "series" else "movie",
        title = meta.name,
        logo = meta.logo,
        poster = meta.poster,
        background = meta.background,
    )

    fun titleRefOf(item: DownloadItem): DownloadTitleRef = DownloadTitleRef(
        parentMetaId = item.parentMetaId,
        parentMetaType = item.parentMetaType,
        contentType = if (item.seasonNumber != null) "series" else "movie",
        title = item.title,
        logo = item.logo,
        poster = item.poster,
        background = item.background,
    )

    fun titleRefOf(batch: DownloadBatch): DownloadTitleRef = DownloadTitleRef(
        parentMetaId = batch.parentMetaId,
        parentMetaType = batch.parentMetaType,
        contentType = batch.contentType,
        title = batch.title,
        logo = batch.logo,
        poster = batch.poster,
        background = batch.background,
    )

    fun targetOf(item: DownloadItem): DownloadTarget = DownloadTarget(
        videoId = item.videoId,
        title = item.episodeTitle?.takeIf { item.seasonNumber != null } ?: item.title,
        // Items from a batch store "series" as their contentType; the meta type is the request's.
        contentType = item.parentMetaType.ifBlank { item.contentType },
        season = item.seasonNumber,
        episode = item.episodeNumber,
        runtimeMinutes = null,
        thumbnail = item.episodeThumbnail,
    )

    fun targetOf(batch: DownloadBatch, entry: DownloadBatchEntry): DownloadTarget = DownloadTarget(
        videoId = entry.videoId,
        title = entry.title,
        // The addon request type; the batch's own contentType is only "movie" / "series".
        contentType = batch.parentMetaType,
        season = entry.season,
        episode = entry.episode,
        runtimeMinutes = entry.runtimeMinutes,
    )

    // --- discovery -----------------------------------------------------------------------------

    /**
     * Every source the addon filter allows for [target], debrid ones kept lazy (never minted
     * here), with the debrid cache state resolved where the service can be asked - so an
     * unmarked torrent is judged on the service's answer, not treated as uncached for lack of one.
     */
    suspend fun discover(target: DownloadTarget): List<DownloadSourceCandidate> {
        discoverOverride?.let { return it(target) }
        DownloadsRepository.ensureLoaded()
        AddonRepository.initialize()
        val filter = DownloadsRepository.sourcePolicy.value.snapshot()
        val addons = AddonRepository.uiState.value.addons
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
        val discovered = AutomaticDownloadDiscovery.discover(
            type = target.contentType,
            videoId = target.videoId,
            addons = addons,
            policySnapshot = filter,
        )
        val annotated = annotateCacheState(discovered)
        return annotated.map { candidate ->
            if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate.stream)) {
                candidate.copy(
                    resolvedUrl = null,
                    sourceOrigin = DownloadSourceOrigin(
                        stream = candidate.stream,
                        season = target.season,
                        episode = target.episode,
                    ),
                )
            } else {
                candidate
            }
        }.also { DownloadSizeTelemetry.discovered(target, it) }
    }

    private suspend fun annotateCacheState(candidates: List<DownloadSourceCandidate>): List<DownloadSourceCandidate> {
        if (candidates.isEmpty()) return candidates
        val group = AddonStreamGroup(
            addonName = "downloads",
            addonId = "downloads",
            streams = candidates.map { it.stream },
        )
        val checked = runCatching { LocalDebridAvailabilityService.annotateCachedAvailability(listOf(group)) }
            .getOrNull()
            ?.firstOrNull()
            ?.streams
            ?: return candidates
        if (checked.size != candidates.size) return candidates
        return candidates.mapIndexed { index, candidate -> candidate.copy(stream = checked[index]) }
    }

    fun contextFor(target: DownloadTarget, policy: DownloadPolicy): DownloadSourceSelector.Context {
        contextOverride?.let { return it(target, policy) }
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        val playback = playbackSelectionContextOf(
            settings = settings,
            isEpisode = target.isEpisode,
            runtimeMinutes = target.runtimeMinutes,
            // A download is watched on any screen later; the display ceiling is playback's.
            displayMaxHeight = null,
        )
        return DownloadSourceSelector.Context(
            policy = policy,
            runtimeMinutes = target.runtimeMinutes,
            isEpisode = target.isEpisode,
            // Language is shared with playback; codec is not a download preference any more.
            rankingPreferences = playback.rankingPreferences.copy(codecPreference = CodecPreference.ANY),
            playbackDynamicRange = settings.playbackDynamicRangePolicy,
            addonFilter = DownloadsRepository.sourcePolicy.value.snapshot(),
        )
    }

    // --- entries -------------------------------------------------------------------------------

    /** Automatic: the policy decides. A picked direct file has its size checked first. */
    suspend fun automaticEntry(
        target: DownloadTarget,
        candidates: List<DownloadSourceCandidate>,
        context: DownloadSourceSelector.Context,
    ): DownloadBatchEntry {
        val first = DownloadSourceSelector.select(candidates, context)
        val decision = if (first is DownloadDecision.Pick && first.candidate.sourceOrigin == null) {
            val verified = verifySize(first.candidate)
            if (verified == first.candidate) {
                first
            } else {
                DownloadSourceSelector.select(candidates.map { if (it == first.candidate) verified else it }, context)
            }
        } else {
            first
        }
        return entryFor(target, decision, candidates, context)
    }

    /**
     * Assisted, and "Use nearest": the user named [height]. The chosen row is approved even when
     * it is over the size level - the user saw its size and chose it.
     */
    fun resolutionEntry(
        target: DownloadTarget,
        candidates: List<DownloadSourceCandidate>,
        height: Int,
        context: DownloadSourceSelector.Context,
    ): DownloadBatchEntry {
        val option = DownloadSourceSelector.assistedOptions(candidates, context).firstOrNull { it.height == height }
        if (option == null) {
            val missing = DownloadDecision.NeedsDecision(
                DownloadDecisionReason.ResolutionMissing(
                    preferredHeight = height,
                    nearestLowerHeight = heightsOf(candidates, context).filter { it < height }.maxOrNull(),
                    nearestHigherHeight = heightsOf(candidates, context).filter { it > height }.minOrNull(),
                ),
            )
            return entryFor(target, missing, candidates, context)
        }
        return baseEntry(target).copy(
            state = DownloadBatchEntryState.READY,
            selection = SourceSelectionResult.Selected(
                streamUrl = option.candidate.resolvedUrl,
                facts = option.candidate.facts,
                addonKey = option.candidate.addonKey,
                // Chosen by the user with its size on screen: no cap may re-ask mid-transfer.
                calculatedCapBytes = 0L,
                sourceOrigin = option.candidate.sourceOrigin,
            ),
            hasUsableSources = true,
        ).withSource(option.candidate).also(DownloadSizeTelemetry::picked)
    }

    /** Manual: waiting for the user to pick this episode's source. */
    fun manualPickEntry(target: DownloadTarget): DownloadBatchEntry = baseEntry(target).copy(
        state = DownloadBatchEntryState.SKIPPED,
        decision = DownloadEntryDecisionKind.MANUAL_PICK,
    )

    internal fun entryFor(
        target: DownloadTarget,
        decision: DownloadDecision,
        candidates: List<DownloadSourceCandidate>,
        context: DownloadSourceSelector.Context,
    ): DownloadBatchEntry {
        val base = baseEntry(target)
        return when (decision) {
            is DownloadDecision.Pick -> base.copy(
                state = DownloadBatchEntryState.READY,
                selection = SourceSelectionResult.Selected(
                    streamUrl = decision.candidate.resolvedUrl,
                    facts = decision.candidate.facts,
                    addonKey = decision.candidate.addonKey,
                    calculatedCapBytes = decision.limitBytes ?: 0L,
                    sourceOrigin = decision.candidate.sourceOrigin,
                ),
                hasUsableSources = true,
            ).withSource(decision.candidate)

            is DownloadDecision.NeedsDecision -> when (val reason = decision.reason) {
                is DownloadDecisionReason.OverLimit -> base.copy(
                    state = DownloadBatchEntryState.APPROVAL_NEEDED,
                    selection = SourceSelectionResult.ApprovalNeeded(
                        streamUrl = reason.smallest.resolvedUrl,
                        facts = reason.smallest.facts,
                        addonKey = reason.smallest.addonKey,
                        calculatedCapBytes = 0L,
                        reason = "Over your size level",
                        sourceOrigin = reason.smallest.sourceOrigin,
                    ),
                    decision = DownloadEntryDecisionKind.OVER_LIMIT,
                    hasUsableSources = true,
                ).withSource(reason.smallest)

                is DownloadDecisionReason.ResolutionMissing -> {
                    val nearest = reason.nearestLowerHeight ?: reason.nearestHigherHeight
                    val nearestDecision = nearest?.let { DownloadSourceSelector.bestAt(candidates, it, context) }
                    val nearestCandidate = when (nearestDecision) {
                        is DownloadDecision.Pick -> nearestDecision.candidate
                        is DownloadDecision.NeedsDecision ->
                            (nearestDecision.reason as? DownloadDecisionReason.OverLimit)?.smallest
                        null -> null
                    }
                    if (nearestCandidate == null) {
                        base.copy(
                            state = DownloadBatchEntryState.SKIPPED,
                            decision = DownloadEntryDecisionKind.NO_SOURCES,
                            hasUsableSources = false,
                            failureMessage = "No downloadable source found",
                        )
                    } else {
                        base.copy(
                            state = DownloadBatchEntryState.APPROVAL_NEEDED,
                            selection = SourceSelectionResult.ApprovalNeeded(
                                streamUrl = nearestCandidate.resolvedUrl,
                                facts = nearestCandidate.facts,
                                addonKey = nearestCandidate.addonKey,
                                calculatedCapBytes = 0L,
                                reason = "No ${DownloadFlowRules.resolutionLabel(reason.preferredHeight)} source · " +
                                    "${nearestCandidate.facts.resolution?.height?.let(DownloadFlowRules::resolutionLabel) ?: "another resolution"} available",
                                sourceOrigin = nearestCandidate.sourceOrigin,
                            ),
                            decision = DownloadEntryDecisionKind.RESOLUTION_MISSING,
                            hasUsableSources = true,
                        ).withSource(nearestCandidate)
                    }
                }

                DownloadDecisionReason.NothingCached -> base.copy(
                    state = DownloadBatchEntryState.SKIPPED,
                    decision = DownloadEntryDecisionKind.NOTHING_CACHED,
                    hasUsableSources = false,
                    failureMessage = "Not available on your debrid service right now",
                )

                DownloadDecisionReason.NoSources -> base.copy(
                    state = DownloadBatchEntryState.SKIPPED,
                    decision = DownloadEntryDecisionKind.NO_SOURCES,
                    hasUsableSources = false,
                    failureMessage = "No downloadable source found",
                )
            }
        }.also { entry ->
            DownloadDiagnostics.selection(
                provider = entry.providerName,
                season = entry.season,
                episode = entry.episode,
                lazy = entry.sourceOrigin != null,
                outcome = entry.decision?.name ?: entry.state.name,
            )
            DownloadSizeTelemetry.picked(entry)
        }
    }

    private fun heightsOf(candidates: List<DownloadSourceCandidate>, context: DownloadSourceSelector.Context): List<Int> =
        DownloadSourceSelector.assistedOptions(candidates, context).map { it.height }

    private fun baseEntry(target: DownloadTarget) = DownloadBatchEntry(
        id = target.entryId,
        videoId = target.videoId,
        title = target.title,
        season = target.season,
        episode = target.episode,
        runtimeMinutes = target.runtimeMinutes,
    )

    private fun DownloadBatchEntry.withSource(candidate: DownloadSourceCandidate) = copy(
        streamTitle = candidate.stream.streamLabel,
        streamSubtitle = candidate.stream.streamSubtitle,
        providerName = candidate.stream.addonName,
        providerAddonId = candidate.stream.addonId,
        sourceHeaders = candidate.stream.behaviorHints.proxyHeaders?.request.orEmpty(),
        sourceOrigin = candidate.sourceOrigin,
    )

    private suspend fun verifySize(candidate: DownloadSourceCandidate): DownloadSourceCandidate =
        verifySizeOverride?.invoke(candidate)
            ?: runCatching { AutomaticDownloadDiscovery.verifyCandidateSize(candidate) }.getOrDefault(candidate)

    // --- batches -------------------------------------------------------------------------------

    fun newBatch(
        title: DownloadTitleRef,
        scope: DownloadScope,
        targets: List<DownloadTarget>,
        allowMeteredNetwork: Boolean,
    ): DownloadBatch {
        val createdAt = DownloadsClock.nowEpochMs()
        return DownloadBatch(
            id = "batch_${createdAt.toString(36)}_${title.parentMetaId.hashCode().toUInt().toString(36)}",
            scope = scope,
            contentType = title.contentType,
            parentMetaId = title.parentMetaId,
            parentMetaType = title.parentMetaType,
            title = title.title,
            logo = title.logo,
            poster = title.poster,
            background = title.background,
            sourcePolicySnapshot = DownloadsRepository.sourcePolicy.value.snapshot(),
            entries = targets.map(::baseEntry),
            allowMeteredNetwork = allowMeteredNetwork,
            createdAtEpochMs = createdAt,
        )
    }

    /**
     * Runs discovery for every entry of [batch] that [entryIds] names (all when null), at most
     * three at a time, storing each entry as it resolves so the Downloads screen shows progress.
     */
    suspend fun prepareEntries(
        batchId: String,
        targets: List<DownloadTarget>,
        build: suspend (DownloadTarget, List<DownloadSourceCandidate>) -> DownloadBatchEntry,
        onProgress: (done: Int) -> Unit = {},
    ): List<DownloadBatchEntry> = coroutineScope {
        val semaphore = Semaphore(AutomaticDownloadDiscovery.MAX_CONCURRENT_EPISODE_DISCOVERIES)
        var done = 0
        targets.map { target ->
            async {
                semaphore.withPermit {
                    val entry = runCatching { build(target, discover(target)) }.getOrElse { error ->
                        baseEntry(target).copy(
                            state = DownloadBatchEntryState.FAILED,
                            failureMessage = error.message ?: "Source discovery failed",
                        )
                    }
                    DownloadsRepository.updateBatchEntry(batchId, entry)
                    done += 1
                    onProgress(done)
                    entry
                }
            }
        }.awaitAll()
    }

    /** Discovery only, for the Assisted sheet: candidates per target, with progress. */
    suspend fun discoverAll(
        targets: List<DownloadTarget>,
        onProgress: (done: Int) -> Unit = {},
    ): Map<DownloadTarget, List<DownloadSourceCandidate>> = coroutineScope {
        val semaphore = Semaphore(AutomaticDownloadDiscovery.MAX_CONCURRENT_EPISODE_DISCOVERIES)
        var done = 0
        targets.map { target ->
            async {
                semaphore.withPermit {
                    val found = runCatching { discover(target) }.getOrDefault(emptyList())
                    done += 1
                    onProgress(done)
                    target to found
                }
            }
        }.awaitAll().toMap()
    }

    // --- targets -------------------------------------------------------------------------------

    fun targetsFor(meta: MetaDetails, scope: DownloadScope): List<DownloadTarget> {
        if (scope == DownloadScope.Movie) {
            return listOf(
                DownloadTarget(
                    videoId = meta.defaultVideoId ?: meta.id,
                    title = meta.name,
                    contentType = meta.type,
                    runtimeMinutes = meta.runtime.runtimeMinutesOrNull(),
                ),
            )
        }
        val seriesRuntimeMinutes = meta.runtime.runtimeMinutesOrNull()
        val existing = DownloadsRepository.uiState.value.items
            .map(DownloadItem::logicalContentKey)
            .toSet()
        val today = CurrentDateProvider.todayIsoDate()
        val seenEpisodes = seenVideoIdsFor(meta, scope)
        val episodes = meta.videos.mapNotNull { it.toBatchEpisode() }
            .map { episode ->
                episode.copy(
                    released = meta.videos.firstOrNull { it.id == episode.videoId }
                        ?.released
                        ?.take(10)
                        ?.let { it <= today }
                        ?: true,
                    watched = episode.videoId in seenEpisodes,
                )
            }
        return DownloadBatchPlanner.episodesForScope(
            episodes = episodes,
            scope = scope,
            existingLogicalKeys = existing,
            parentMetaId = meta.id,
        ).map { episode ->
            DownloadTarget(
                videoId = episode.videoId,
                title = episode.title,
                contentType = meta.type,
                season = episode.season,
                episode = episode.episode,
                // The series' own runtime before the 45-minute default: plenty of metas carry it
                // only at the show level, and guessing 45 for an hour-long episode sets a limit a
                // quarter under what the size level allows.
                runtimeMinutes = episode.runtimeMinutes ?: seriesRuntimeMinutes,
                thumbnail = meta.videos.firstOrNull { it.id == episode.videoId }?.thumbnail,
            )
        }
    }

    /** The whole-show chooser's rows: per season, its released episodes and how many are unwatched. */
    fun seasonChoices(meta: MetaDetails): List<DownloadFlowRules.SeasonChoice> {
        val today = CurrentDateProvider.todayIsoDate()
        val seen = seenVideoIds(meta)
        return meta.videos
            .filter { it.season != null && it.episode != null && it.available }
            .filter { video -> video.released?.take(10)?.let { it <= today } ?: true }
            .groupBy { it.season!! }
            // Not toSortedMap(): that is JVM-only and broke the iOS link.
            .entries
            .sortedBy { it.key }
            .map { (season, videos) ->
                DownloadFlowRules.SeasonChoice(
                    season = season,
                    episodeCount = videos.size,
                    unwatchedCount = videos.count { it.id !in seen },
                )
            }
    }

    /**
     * Ids of the episodes the user has already seen. Only resolved for scopes that filter on it,
     * so ordinary season and episode batches keep their behaviour and do not depend on watch
     * state being loaded.
     */
    private fun seenVideoIdsFor(meta: MetaDetails, scope: DownloadScope): Set<String> = when {
        scope is DownloadScope.SeasonUnwatched -> seenVideoIds(meta)
        scope is DownloadScope.SelectedSeasons && scope.unwatchedOnly -> seenVideoIds(meta)
        else -> emptySet()
    }

    private fun seenVideoIds(meta: MetaDetails): Set<String> {
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        val watchedKeys = WatchedRepository.uiState.value.watchedKeys
        val progressByVideoId = WatchProgressRepository.uiState.value.byVideoIdForContent(meta.id)
        return meta.videos
            .filter { video ->
                WatchingState.isEpisodeSeen(
                    watchedKeys = watchedKeys,
                    progressByVideoId = progressByVideoId,
                    metaType = meta.type,
                    metaId = meta.id,
                    episode = video,
                )
            }
            .mapTo(mutableSetOf()) { it.id }
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

    private fun MetaDetails.isSeriesLike(): Boolean =
        type.lowercase() in setOf("series", "show", "tv", "tvshow") || videos.any { it.season != null }
}
