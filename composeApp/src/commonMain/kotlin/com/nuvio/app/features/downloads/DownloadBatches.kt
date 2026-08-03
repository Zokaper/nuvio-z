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
