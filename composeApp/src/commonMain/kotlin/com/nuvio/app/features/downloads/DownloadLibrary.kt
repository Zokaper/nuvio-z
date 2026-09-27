package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import kotlinx.serialization.Serializable

/**
 * The downloaded library (Phase 9, library pass): what is finished on this device, grouped by
 * title, with enough of the title's own metadata to look like the rest of the app offline.
 *
 * Title metadata is cached in memory only, so on a plane the library used to be a poster and a
 * byte count. [DownloadTitleMetadata] is a small snapshot - synopsis, year, genres, rating and,
 * per downloaded season, each episode's still, overview and runtime - kept in its own payload
 * ([DownloadTitleMetadataStore]) so the engine's hot payload does not grow with it.
 */

@Serializable
data class DownloadEpisodeMetadata(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val runtimeMinutes: Int? = null,
    val released: String? = null,
)

@Serializable
data class DownloadTitleMetadata(
    val parentMetaId: String,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val episodes: List<DownloadEpisodeMetadata> = emptyList(),
    val capturedAtEpochMs: Long = 0L,
) {
    fun episode(season: Int?, episode: Int?): DownloadEpisodeMetadata? =
        if (season == null || episode == null) null else episodes.firstOrNull { it.season == season && it.episode == episode }

    companion object {
        /** Keeps at most three genres and only the seasons in [seasons] (every season when null). */
        fun from(meta: MetaDetails, seasons: Set<Int>?, nowEpochMs: Long): DownloadTitleMetadata =
            DownloadTitleMetadata(
                parentMetaId = meta.id.trim(),
                description = meta.description?.trim()?.takeIf { it.isNotBlank() },
                releaseInfo = meta.releaseInfo?.trim()?.takeIf { it.isNotBlank() },
                genres = meta.genres.map { it.trim() }.filter { it.isNotBlank() }.take(3),
                imdbRating = meta.imdbRating?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" },
                ageRating = meta.ageRating?.trim()?.takeIf { it.isNotBlank() },
                runtime = meta.runtime?.trim()?.takeIf { it.isNotBlank() },
                logo = meta.logo,
                poster = meta.poster,
                background = meta.background,
                episodes = meta.videos.mapNotNull { video ->
                    val season = video.season ?: return@mapNotNull null
                    val episode = video.episode ?: return@mapNotNull null
                    if (seasons != null && season !in seasons) return@mapNotNull null
                    DownloadEpisodeMetadata(
                        season = season,
                        episode = episode,
                        title = video.title.trim().takeIf { it.isNotBlank() },
                        overview = video.overview?.trim()?.takeIf { it.isNotBlank() },
                        thumbnail = video.thumbnail,
                        runtimeMinutes = video.runtime?.takeIf { it > 0 },
                        released = video.released,
                    )
                },
                capturedAtEpochMs = nowEpochMs,
            )
    }
}

/** One title in the downloaded library: a film, or every finished episode of a show. */
data class DownloadLibraryTitle(
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    /** Finished downloads, in episode order. */
    val completed: List<DownloadItem>,
    /** This title's downloads that are not finished yet (queued, downloading, paused, failed). */
    val unfinishedCount: Int,
) {
    val isSeries: Boolean get() = completed.first().isEpisode
    val representative: DownloadItem get() = completed.first()
    val bytesOnDisk: Long get() = completed.sumOf { it.totalBytes ?: it.downloadedBytes }
    val seasons: List<Int> get() = completed.mapNotNull { it.seasonNumber }.distinct().sortedWith(SeasonOrder)
    val lastCompletedAtEpochMs: Long get() = completed.maxOf { it.updatedAtEpochMs }
}

/** Specials (season 0) after the numbered seasons, as a tab row reads. */
internal val SeasonOrder: Comparator<Int> = compareBy<Int> { if (it == 0) 1 else 0 }.thenBy { it }

object DownloadLibrary {
    /**
     * Every title with at least one finished download, most recently finished first - the one
     * the user just downloaded is the one they are about to watch.
     */
    fun titles(items: List<DownloadItem>): List<DownloadLibraryTitle> =
        items.groupBy { it.parentMetaId }
            .mapNotNull { (parentMetaId, all) ->
                val completed = all.filter { it.status == DownloadStatus.Completed }
                if (completed.isEmpty()) return@mapNotNull null
                val first = completed.first()
                DownloadLibraryTitle(
                    parentMetaId = parentMetaId,
                    parentMetaType = first.parentMetaType,
                    title = first.title,
                    logo = completed.firstNotNullOfOrNull { it.logo },
                    poster = completed.firstNotNullOfOrNull { it.poster },
                    background = completed.firstNotNullOfOrNull { it.background },
                    completed = completed.sortedForLibrary(),
                    unfinishedCount = all.size - completed.size,
                )
            }
            .sortedWith(compareByDescending<DownloadLibraryTitle> { it.lastCompletedAtEpochMs }.thenBy { it.title.lowercase() })

    /** Specials last, then season and episode. */
    fun List<DownloadItem>.sortedForLibrary(): List<DownloadItem> =
        sortedWith(
            compareBy<DownloadItem, Int?>(nullsFirst(SeasonOrder)) { it.seasonNumber }
                .thenBy { it.episodeNumber ?: 0 },
        )

    /**
     * What the title's Play button plays.
     *
     * 1. The downloaded episode (or film) the user is part-way through, the most recent first.
     * 2. Otherwise the first unwatched download after the furthest watched one.
     * 3. Otherwise the first unwatched download anywhere, then simply the first download.
     *
     * [watch] answers for one download: its progress fraction (null when never started) and
     * whether it counts as watched.
     */
    fun nextUp(completed: List<DownloadItem>, watch: (DownloadItem) -> DownloadWatchState): DownloadNextUp? {
        if (completed.isEmpty()) return null
        val ordered = completed.sortedForLibrary()
        val states = ordered.map { it to watch(it) }
        states
            .filter { (_, state) -> state.isInProgress }
            .maxByOrNull { (_, state) -> state.updatedAtEpochMs }
            ?.let { (item, state) -> return DownloadNextUp(item, DownloadNextUpKind.RESUME, state.fraction, state.remainingMs) }

        val lastWatched = states.indexOfLast { (_, state) -> state.watched }
        if (lastWatched >= 0) {
            states.drop(lastWatched + 1).firstOrNull { (_, state) -> !state.watched }
                ?.let { (item, _) -> return DownloadNextUp(item, DownloadNextUpKind.NEXT) }
            states.firstOrNull { (_, state) -> !state.watched }
                ?.let { (item, _) -> return DownloadNextUp(item, DownloadNextUpKind.NEXT) }
            return DownloadNextUp(ordered.first(), DownloadNextUpKind.REWATCH)
        }
        return DownloadNextUp(ordered.first(), DownloadNextUpKind.START)
    }

    /** One season's figures for its header line. */
    fun seasonSummary(episodes: List<DownloadItem>, watch: (DownloadItem) -> DownloadWatchState): DownloadSeasonSummary {
        val completed = episodes.filter { it.status == DownloadStatus.Completed }
        val watched = completed.filter { watch(it).watched }
        return DownloadSeasonSummary(
            episodeCount = completed.size,
            bytes = completed.sumOf { it.totalBytes ?: it.downloadedBytes },
            watched = watched,
            unfinishedCount = episodes.size - completed.size,
        )
    }
}

data class DownloadWatchState(
    /** 0..1 of the way through, or null when never started. */
    val fraction: Float? = null,
    val watched: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
    val remainingMs: Long? = null,
) {
    /** Started and not finished. The ends are ignored: a peek is not a start, the credits are not a stop. */
    val isInProgress: Boolean get() = !watched && fraction != null && fraction >= 0.02f && fraction < 0.95f

    companion object {
        val Unwatched = DownloadWatchState()
    }
}

enum class DownloadNextUpKind { RESUME, NEXT, START, REWATCH }

data class DownloadNextUp(
    val item: DownloadItem,
    val kind: DownloadNextUpKind,
    val fraction: Float? = null,
    val remainingMs: Long? = null,
)

data class DownloadSeasonSummary(
    val episodeCount: Int,
    val bytes: Long,
    val watched: List<DownloadItem>,
    val unfinishedCount: Int,
)
