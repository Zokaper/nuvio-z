package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The downloaded library: grouping by title, what the Play button plays, a season's figures and
 * the metadata snapshot that makes it look like itself offline.
 */
class DownloadLibraryTest {
    private val gb = 1_000_000_000L

    private fun ep(
        season: Int,
        episode: Int,
        show: String = "tt-mf",
        status: DownloadStatus = DownloadStatus.Completed,
        updated: Long = 100L,
    ) = DownloadItem(
        id = "$show-$season-$episode",
        ownerProfileId = 1,
        contentType = "series",
        parentMetaId = show,
        parentMetaType = "series",
        videoId = "$show:$season:$episode",
        title = if (show == "tt-mf") "Modern Family" else "Severance",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "s",
        providerName = "p",
        fileName = "$show-$season-$episode.mkv",
        localFileUri = "file:///$show-$season-$episode.mkv",
        status = status,
        downloadedBytes = 2 * gb,
        totalBytes = 2 * gb,
        createdAtEpochMs = updated,
        updatedAtEpochMs = updated,
    )

    private fun movie(id: String, updated: Long) = ep(0, 0, show = id, updated = updated).copy(
        contentType = "movie",
        parentMetaType = "movie",
        seasonNumber = null,
        episodeNumber = null,
        title = id,
    )

    private fun states(vararg pairs: Pair<DownloadItem, DownloadWatchState>): (DownloadItem) -> DownloadWatchState {
        val map = pairs.associate { (item, state) -> item.id to state }
        return { map[it.id] ?: DownloadWatchState.Unwatched }
    }

    @Test
    fun titlesGroupFinishedDownloadsMostRecentFirstAndCountTheRest() {
        val titles = DownloadLibrary.titles(
            listOf(
                ep(1, 2, updated = 10),
                ep(1, 1, updated = 10),
                ep(1, 3, status = DownloadStatus.Downloading),
                movie("tt-film", updated = 50),
                ep(2, 1, show = "tt-sev", status = DownloadStatus.Queued),
            ),
        )
        assertEquals(listOf("tt-film", "tt-mf"), titles.map { it.parentMetaId })
        val mf = titles.last()
        assertEquals(listOf(1, 2), mf.completed.map { it.episodeNumber })
        assertEquals(1, mf.unfinishedCount)
        assertEquals(4 * gb, mf.bytesOnDisk)
    }

    @Test
    fun specialsSortAfterTheNumberedSeasons() {
        val title = DownloadLibrary.titles(listOf(ep(0, 1), ep(2, 1), ep(1, 5))).single()
        assertEquals(listOf(1, 2, 0), title.seasons)
        assertEquals(listOf(1, 2, 0), title.completed.map { it.seasonNumber })
    }

    @Test
    fun nextUpResumesTheMostRecentEpisodeInProgress() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val e3 = ep(1, 3)
        val next = DownloadLibrary.nextUp(
            listOf(e1, e2, e3),
            states(
                e1 to DownloadWatchState(fraction = 0.4f, updatedAtEpochMs = 1),
                e3 to DownloadWatchState(fraction = 0.6f, updatedAtEpochMs = 9, remainingMs = 600_000),
            ),
        )!!
        assertEquals(e3.id, next.item.id)
        assertEquals(DownloadNextUpKind.RESUME, next.kind)
        assertEquals(600_000L, next.remainingMs)
    }

    @Test
    fun aPeekOrTheCreditsIsNotInProgress() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val next = DownloadLibrary.nextUp(
            listOf(e1, e2),
            states(e1 to DownloadWatchState(fraction = 0.01f), e2 to DownloadWatchState(fraction = 0.97f)),
        )!!
        assertEquals(DownloadNextUpKind.START, next.kind)
        assertEquals(e1.id, next.item.id)
    }

    @Test
    fun nextUpIsTheFirstUnwatchedAfterTheFurthestWatched() {
        val items = (1..6).map { ep(3, it) }
        val next = DownloadLibrary.nextUp(
            items,
            states(
                items[0] to DownloadWatchState(watched = true),
                items[3] to DownloadWatchState(watched = true),
                items[4] to DownloadWatchState(watched = true),
            ),
        )!!
        assertEquals(DownloadNextUpKind.NEXT, next.kind)
        assertEquals(6, next.item.episodeNumber)
    }

    @Test
    fun watchedToTheEndFallsBackToTheFirstUnwatchedThenToRewatch() {
        val items = (1..3).map { ep(1, it) }
        val gap = DownloadLibrary.nextUp(items, states(items[0] to DownloadWatchState(watched = true), items[2] to DownloadWatchState(watched = true)))!!
        assertEquals(2, gap.item.episodeNumber)
        assertEquals(DownloadNextUpKind.NEXT, gap.kind)

        val all = DownloadLibrary.nextUp(items, { DownloadWatchState(watched = true) })!!
        assertEquals(1, all.item.episodeNumber)
        assertEquals(DownloadNextUpKind.REWATCH, all.kind)
    }

    @Test
    fun nothingDownloadedHasNoNextUp() {
        assertNull(DownloadLibrary.nextUp(emptyList()) { DownloadWatchState.Unwatched })
    }

    @Test
    fun seasonSummaryCountsFinishedEpisodesAndTheWatchedOnes() {
        val items = listOf(ep(2, 1), ep(2, 2), ep(2, 3, status = DownloadStatus.Downloading))
        val summary = DownloadLibrary.seasonSummary(items, states(items[0] to DownloadWatchState(watched = true)))
        assertEquals(2, summary.episodeCount)
        assertEquals(4 * gb, summary.bytes)
        assertEquals(listOf(items[0].id), summary.watched.map { it.id })
        assertEquals(1, summary.unfinishedCount)
    }

    @Test
    fun metadataKeepsOnlyTheDownloadedSeasonsAndThreeGenres() {
        val meta = MetaDetails(
            id = " tt-mf ",
            type = "series",
            name = "Modern Family",
            description = "Three families.",
            releaseInfo = "2009-2020",
            imdbRating = "0.0",
            genres = listOf("Comedy", " ", "Family", "Romance", "Drama"),
            videos = listOf(
                MetaVideo(id = "a", title = "Pilot", season = 1, episode = 1, overview = "  ", runtime = 22),
                MetaVideo(id = "b", title = "Dude Ranch", season = 3, episode = 1, overview = "Phil tries.", runtime = 0),
                MetaVideo(id = "c", title = "Trailer"),
            ),
        )
        val snapshot = DownloadTitleMetadata.from(meta, seasons = setOf(3), nowEpochMs = 7)
        assertEquals("tt-mf", snapshot.parentMetaId)
        assertEquals(listOf("Comedy", "Family", "Romance"), snapshot.genres)
        assertNull(snapshot.imdbRating)
        assertEquals(listOf(3 to 1), snapshot.episodes.map { it.season to it.episode })
        val dude = snapshot.episode(3, 1)!!
        assertEquals("Phil tries.", dude.overview)
        assertNull(dude.runtimeMinutes)
        assertNull(snapshot.episode(1, 1))

        val every = DownloadTitleMetadata.from(meta, seasons = null, nowEpochMs = 7)
        assertNull(every.episode(1, 1)!!.overview)
        assertEquals(22, every.episode(1, 1)!!.runtimeMinutes)
    }
}
