package com.nuvio.app.features.downloads

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
    data class SelectedSeasons(val seasons: Set<Int>) : DownloadScope()
}

@Serializable
enum class DownloadBatchEntryState {
    DISCOVERING,
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
    val failureMessage: String? = null,
)

@Serializable
data class DownloadBatch(
    val id: String,
    val scope: DownloadScope,
    val contentType: String = "",
    val parentMetaId: String = "",
    val parentMetaType: String = "",
    val title: String = "",
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val presetSnapshot: DownloadPreset,
    val sourcePolicySnapshot: DownloadSourcePolicy,
    val entries: List<DownloadBatchEntry>,
    val allowMeteredNetwork: Boolean = false,
    val createdAtEpochMs: Long,
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
            is DownloadScope.SelectedSeasons -> scope.seasons
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
