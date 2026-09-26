package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamDebridCacheState

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DownloadScope {
    @Serializable
    @SerialName("movie")
    data object Movie : DownloadScope()

    @Serializable
    @SerialName("episode")
    data class Episode(val season: Int, val episode: Int) : DownloadScope()

    @Serializable
    @SerialName("season")
    data class Season(val season: Int) : DownloadScope()

    /** Every episode of [season] that is not watched yet, so an in-progress season resumes from where it stopped. */
    @Serializable
    @SerialName("season_unwatched")
    data class SeasonUnwatched(val season: Int) : DownloadScope()

    @Serializable
    @SerialName("selected_seasons")
    data class SelectedSeasons(
        val seasons: Set<Int>,
        /** The whole-show chooser's "Only unwatched episodes" (Phase 9). */
        val unwatchedOnly: Boolean = false,
    ) : DownloadScope()
}

@Serializable
enum class DownloadBatchEntryState {
    DISCOVERING,

    /**
     * Assisted, several episodes (Phase 9, "choose when ready"): this entry's sources are found and
     * it waits for the user to pick a quality for the whole selection. A normal step of the
     * Assisted flow - never "Needs you", which is for things that went wrong.
     */
    AWAITING_CHOICE,
    READY,
    APPROVAL_NEEDED,
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    FAILED,
    COMPLETED,
    SKIPPED,
    CANCELLED,
}

@Serializable
data class DownloadBatchEntry(
    val id: String,
    val videoId: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int? = null,
    val state: DownloadBatchEntryState = DownloadBatchEntryState.DISCOVERING,
    val selection: SourceSelectionResult? = null,
    val streamTitle: String? = null,
    val streamSubtitle: String? = null,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    /**
     * The selected source before debrid resolution. Preparation never mints its
     * playable link; the queue does so only after this entry owns a transfer slot.
     */
    val sourceOrigin: DownloadSourceOrigin? = null,
    val failureMessage: String? = null,
    /** Why this entry waits for the user (Phase 9); null on entries from before stage 6. */
    val decision: DownloadEntryDecisionKind? = null,
    /**
     * Whether discovery found at least one source that could become a download. False means
     * "Choose manually" would open a list of rows that all fail, so it is not offered.
     */
    val hasUsableSources: Boolean? = null,
) {
    /**
     * The source picked for review is a debrid torrent the service has not cached.
     *
     * Approving it cannot help. The download resolver answers "not cached" for such a
     * source without asking the service again, so an approved entry sat at "Waiting for
     * provider" through every retry and then failed. It needs a different source.
     */
    val selectsUncachedDebrid: Boolean
        get() {
            val approval = selection as? SourceSelectionResult.ApprovalNeeded ?: return false
            return approval.facts.isDebridReady == false ||
                (approval.sourceOrigin ?: sourceOrigin).isKnownUncached()
        }

    /**
     * Only a source the user picks can move this entry forward - and there is one to pick.
     * Nothing cached / no sources are excluded: their honest actions are Check again and Remove.
     */
    val needsManualSource: Boolean
        get() = (
            state == DownloadBatchEntryState.SKIPPED ||
                state == DownloadBatchEntryState.FAILED ||
                (state == DownloadBatchEntryState.APPROVAL_NEEDED && selectsUncachedDebrid)
            ) && DownloadFlowRules.offersChooseManually(decision, hasUsableSources)

    /** Nothing usable was found; re-running discovery is the only thing that can change that. */
    val canCheckAgain: Boolean
        get() = state == DownloadBatchEntryState.SKIPPED && DownloadFlowRules.offersCheckAgain(decision)

    /** Waiting only for the user to accept an unknown size or unclear metadata. */
    val canBeApproved: Boolean
        get() = state == DownloadBatchEntryState.APPROVAL_NEEDED && !selectsUncachedDebrid
}

/**
 * The addon reported this source as not cached on the user's debrid service.
 *
 * `DirectDebridResolver` trusts that snapshot and answers "not cached" without asking
 * the service, so a download carrying it can never resolve, however long it waits.
 */
internal fun DownloadSourceOrigin?.isKnownUncached(): Boolean =
    this?.stream?.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED

@Serializable
data class DownloadBatch(
    val id: String,
    /** See [DownloadItem.ownerProfileId]. */
    val ownerProfileId: Int? = null,
    val scope: DownloadScope,
    val contentType: String = "",
    val parentMetaId: String = "",
    val parentMetaType: String = "",
    val title: String = "",
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    /** The retired preset a pre-Phase-9 batch was started with; null from stage 6 on. */
    val presetSnapshot: DownloadPreset? = null,
    val sourcePolicySnapshot: DownloadSourcePolicy,
    val entries: List<DownloadBatchEntry>,
    val allowMeteredNetwork: Boolean = false,
    val createdAtEpochMs: Long,
    /**
     * Assisted "choose when ready": discovery runs in the background and the user picks the quality
     * once it is done. Cleared when the choice is made. Persisted so a process death leaves a batch
     * that knows to find its sources again, rather than one that reads as failed.
     */
    val awaitsQualityChoice: Boolean = false,
    /** When the user was told this batch is ready (in-app or notification), so a refresh does not say it twice. */
    val choiceAnnouncedAtEpochMs: Long? = null,
    /**
     * Assisted "Choose now": the resolution the user picked **before** discovery finished, from
     * estimates. A real choice, never asked again: when the sources are in, every entry is decided
     * for it as Automatic would decide its preference (fallback, size rule, Needs you). Persisted
     * with the batch so a process death keeps it; the candidates themselves never are.
     */
    val earlyResolutionHeight: Int? = null,
) {
    val requiresReview: Boolean
        get() = entries.size > 10 ||
            entries.any {
                it.state == DownloadBatchEntryState.APPROVAL_NEEDED ||
                    it.state == DownloadBatchEntryState.SKIPPED ||
                    it.state == DownloadBatchEntryState.FAILED
            }

    fun requiresReview(freeStorageBytes: Long): Boolean {
        val estimated = entries.sumOf { entry ->
            when (val result = entry.selection) {
                is SourceSelectionResult.Selected -> result.facts.sizeBytes ?: 0L
                is SourceSelectionResult.ApprovalNeeded -> result.facts.sizeBytes ?: 0L
                else -> 0L
            }
        }
        return requiresReview ||
            (freeStorageBytes > 0L && estimated > freeStorageBytes / 2L)
    }
}

/** True while at least one entry is still looking for, or resolving, a source. */
val DownloadBatch.isPreparing: Boolean
    get() = entries.any { it.state.isPreparing }

/** Assisted: every source is found and the batch waits for the user's quality choice. */
val DownloadBatch.isAwaitingQualityChoice: Boolean
    get() = awaitsQualityChoice && !isPreparing && entries.any { it.state == DownloadBatchEntryState.AWAITING_CHOICE }

/**
 * Assisted "Choose now": the sources are in and each entry is being decided for the quality chosen
 * early (a direct source's size is checked over the network first). The batch no longer awaits a
 * choice but nothing is queued yet - without this state it belonged to no row at all.
 */
val DownloadBatch.isStartingEarlyChoice: Boolean
    get() = !awaitsQualityChoice && earlyResolutionHeight != null && isPreparing

/**
 * The Assisted row on the Downloads screen, from the moment discovery starts until the chosen
 * quality is queued: finding, ready to choose, or (after "Choose now") checking the sources found.
 */
val DownloadBatch.showsAsChoiceRow: Boolean
    get() = (awaitsQualityChoice && (isPreparing || isAwaitingQualityChoice)) || isStartingEarlyChoice

/** What an Assisted batch's row, and the iOS Live Activity, say it is doing. */
enum class DownloadChoicePhase { REFRESHING, FINDING, CHECKING, READY }

data class DownloadChoiceStatus(
    val phase: DownloadChoicePhase,
    val done: Int,
    val total: Int,
    /** The quality picked with "Choose now", while it is not yet queued. */
    val chosenHeight: Int?,
)

/** One reading of an Assisted batch for every surface that shows it; null when it has no such row. */
fun DownloadBatch.choiceStatus(refreshing: Boolean): DownloadChoiceStatus? {
    if (!showsAsChoiceRow) return null
    val phase = when {
        isStartingEarlyChoice -> DownloadChoicePhase.CHECKING
        !isPreparing -> DownloadChoicePhase.READY
        refreshing -> DownloadChoicePhase.REFRESHING
        else -> DownloadChoicePhase.FINDING
    }
    return DownloadChoiceStatus(phase, preparedEntryCount, entries.size, earlyResolutionHeight)
}

/** Entries that have finished preparation, whatever the outcome was. */
val DownloadBatch.preparedEntryCount: Int
    get() = entries.count { !it.state.isPreparing }

val DownloadBatchEntryState.isPreparing: Boolean
    get() = this == DownloadBatchEntryState.DISCOVERING ||
        this == DownloadBatchEntryState.RESOLVING

/**
 * States an entry only reaches once a real download exists for it.
 *
 * Used to spot an entry whose download has been deleted. [DownloadBatchEntryState.FAILED]
 * is deliberately excluded: discovery failures and queueing failures both land there
 * without a download ever existing, and those entries have to stay in review so the
 * user can still pick a source by hand.
 */
val DownloadBatchEntryState.isItemBacked: Boolean
    get() = this == DownloadBatchEntryState.QUEUED ||
        this == DownloadBatchEntryState.DOWNLOADING ||
        this == DownloadBatchEntryState.PAUSED ||
        this == DownloadBatchEntryState.COMPLETED

/**
 * Points every batch entry back at the download that backs it.
 *
 * An entry whose download has been deleted is marked cancelled rather than left at its
 * last state. The detail screens fall back to batch entries wherever no item exists, so
 * a frozen `DOWNLOADING` or `COMPLETED` entry made a deleted episode still read as
 * downloading or downloaded on the series page long after the file and the queue row
 * were gone. A batch left with nothing but cancelled entries is dropped, because there
 * is nothing left in it to show or act on.
 */
internal fun reconcileBatches(
    batches: List<DownloadBatch>,
    items: List<DownloadItem>,
): List<DownloadBatch> = batches.mapNotNull { batch ->
    val entries = batch.entries.map { entry ->
        val item = items.firstOrNull {
            // Another profile's download of the same episode is not this batch's.
            (batch.ownerProfileId == null || it.ownerProfileId == batch.ownerProfileId) &&
                it.parentMetaId == batch.parentMetaId &&
                it.videoId == entry.videoId &&
                it.seasonNumber == entry.season &&
                it.episodeNumber == entry.episode
        }
        when {
            item != null -> entry.copy(
                state = when (item.status) {
                    DownloadStatus.Queued -> DownloadBatchEntryState.QUEUED
                    DownloadStatus.Downloading -> DownloadBatchEntryState.DOWNLOADING
                    DownloadStatus.Paused -> DownloadBatchEntryState.PAUSED
                    DownloadStatus.Completed -> DownloadBatchEntryState.COMPLETED
                    DownloadStatus.Failed -> DownloadBatchEntryState.FAILED
                },
                failureMessage = item.errorMessage,
            )
            // Entries still being planned, or waiting on the user, never had a download
            // of their own and keep the state they have.
            !entry.state.isItemBacked -> entry
            else -> entry.copy(
                state = DownloadBatchEntryState.CANCELLED,
                failureMessage = null,
            )
        }
    }

    if (entries.isNotEmpty() && entries.all { it.state == DownloadBatchEntryState.CANCELLED }) {
        null
    } else {
        batch.copy(entries = entries)
    }
}

data class BatchEpisode(
    val videoId: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val runtimeMinutes: Int? = null,
    val released: Boolean = true,
    val available: Boolean = true,
    val watched: Boolean = false,
)

object DownloadBatchPlanner {
    fun episodesForScope(
        episodes: List<BatchEpisode>,
        scope: DownloadScope,
        existingLogicalKeys: Set<String>,
        parentMetaId: String,
    ): List<BatchEpisode> {
        val selectedSeasons = when (scope) {
            is DownloadScope.Episode -> setOf(scope.season)
            is DownloadScope.Season -> setOf(scope.season)
            is DownloadScope.SeasonUnwatched -> setOf(scope.season)
            is DownloadScope.SelectedSeasons -> if (scope.seasons.isNotEmpty()) {
                scope.seasons
            } else {
                episodes.mapNotNull { it.season }.filter { it > 0 }.toSet()
            }
            DownloadScope.Movie -> emptySet()
        }
        return episodes
            .asSequence()
            .filter { it.released && it.available }
            .filter { episode ->
                when (scope) {
                    is DownloadScope.Episode ->
                        episode.season == scope.season && episode.episode == scope.episode
                    DownloadScope.Movie -> false
                    else -> episode.season in selectedSeasons
                }
            }
            .filter { scope !is DownloadScope.SeasonUnwatched || !it.watched }
            .filter { scope !is DownloadScope.SelectedSeasons || !scope.unwatchedOnly || !it.watched }
            .filter { it.season != 0 || 0 in selectedSeasons }
            .filter {
                downloadLogicalKey(parentMetaId, it.season, it.episode) !in existingLogicalKeys
            }
            .distinctBy { Triple(it.videoId, it.season, it.episode) }
            .sortedWith(compareBy<BatchEpisode> { it.season }.thenBy { it.episode }.thenBy { it.videoId })
            .toList()
    }

    fun defaultSelectedSeasons(currentSeason: Int?, availableSeasons: Set<Int>): Set<Int> =
        currentSeason
            ?.takeIf { it != 0 && it in availableSeasons }
            ?.let(::setOf)
            ?: availableSeasons.filter { it != 0 }.minOrNull()?.let(::setOf)
            ?: emptySet()
}

/**
 * A process death while a "Choose now" batch was checking its sources. The candidates went with the
 * process, so the entries not yet queued find them again: the batch awaits discovery once more and
 * keeps its early choice, which is applied when discovery ends - nothing is asked twice. Entries
 * already decided READY were never queued (a batch queues in one step at the end), so they go back too.
 */
internal object EarlyChoiceRestart {
    fun resume(batch: DownloadBatch): DownloadBatch = batch.copy(
        awaitsQualityChoice = true,
        entries = batch.entries.map { entry ->
            when (entry.state) {
                DownloadBatchEntryState.RESOLVING,
                DownloadBatchEntryState.READY,
                -> entry.copy(state = DownloadBatchEntryState.DISCOVERING)
                else -> entry
            }
        },
    )
}
