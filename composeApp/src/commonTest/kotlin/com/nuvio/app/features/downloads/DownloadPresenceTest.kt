package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadPresenceTest {
    private val showId = "tt0903747"
    private val movieId = "tt1375666"

    @Test
    fun logicalKeysMatchPersistedDownloadKeys() {
        val episode = downloadItem(parentMetaId = " $showId ", season = 2, episode = 5)
        val movie = downloadItem(parentMetaId = movieId, season = null, episode = null)

        assertEquals(episode.logicalContentKey, downloadLogicalKey(showId, 2, 5))
        assertEquals(movie.logicalContentKey, downloadLogicalKey(movieId, null, null))
        // A half-specified episode is a movie key, matching how enqueueing stores it.
        assertEquals(downloadLogicalKey(showId, null, null), downloadLogicalKey(showId, 2, null))
    }

    @Test
    fun everyDownloadStatusMapsToAPresence() {
        assertEquals(DownloadPresence.Downloading, DownloadStatus.Downloading.toPresence(false))
        assertEquals(DownloadPresence.Paused, DownloadStatus.Paused.toPresence(false))
        assertEquals(DownloadPresence.NeedsApproval, DownloadStatus.Paused.toPresence(true))
        assertEquals(DownloadPresence.Completed, DownloadStatus.Completed.toPresence(false))
        assertEquals(DownloadPresence.Failed, DownloadStatus.Failed.toPresence(false))
    }

    @Test
    fun everyBatchEntryStateMapsToAPresence() {
        val expected = mapOf(
            DownloadBatchEntryState.DISCOVERING to DownloadPresence.Preparing,
            DownloadBatchEntryState.RESOLVING to DownloadPresence.Preparing,
            DownloadBatchEntryState.READY to DownloadPresence.Queued,
            DownloadBatchEntryState.QUEUED to DownloadPresence.Queued,
            DownloadBatchEntryState.APPROVAL_NEEDED to DownloadPresence.NeedsApproval,
            DownloadBatchEntryState.DOWNLOADING to DownloadPresence.Downloading,
            DownloadBatchEntryState.PAUSED to DownloadPresence.Paused,
            DownloadBatchEntryState.FAILED to DownloadPresence.Failed,
            DownloadBatchEntryState.COMPLETED to DownloadPresence.Completed,
            DownloadBatchEntryState.SKIPPED to DownloadPresence.None,
            DownloadBatchEntryState.CANCELLED to DownloadPresence.None,
        )

        assertEquals(DownloadBatchEntryState.entries.toSet(), expected.keys)
        expected.forEach { (state, presence) ->
            assertEquals(presence, state.toPresence(), "unexpected presence for $state")
        }
    }

    @Test
    fun batchEntriesSurfaceBeforeATransferExists() {
        val state = buildTitleDownloadState(
            items = emptyList(),
            batches = listOf(
                batch(
                    entries = listOf(
                        batchEntry(season = 1, episode = 1, state = DownloadBatchEntryState.DISCOVERING),
                        batchEntry(season = 1, episode = 2, state = DownloadBatchEntryState.QUEUED),
                    ),
                ),
            ),
            parentMetaId = showId,
        )

        assertEquals(DownloadPresence.Preparing, state.forEpisode(1, 1).presence)
        assertEquals(DownloadPresence.Queued, state.forEpisode(1, 2).presence)
        assertEquals(2, state.activeCount)
        assertEquals(0, state.completedCount)
        assertNull(state.forEpisode(1, 1).item)
        assertEquals("batch-1", state.forEpisode(1, 1).batchId)
    }

    @Test
    fun skippedAndCancelledBatchEntriesLeaveNoTrace() {
        val state = buildTitleDownloadState(
            items = emptyList(),
            batches = listOf(
                batch(
                    entries = listOf(
                        batchEntry(season = 1, episode = 1, state = DownloadBatchEntryState.SKIPPED),
                        batchEntry(season = 1, episode = 2, state = DownloadBatchEntryState.CANCELLED),
                    ),
                ),
            ),
            parentMetaId = showId,
        )

        assertTrue(state.isEmpty)
        assertEquals(DownloadPresence.None, state.forEpisode(1, 1).presence)
    }

    @Test
    fun aPersistedItemWinsOverTheBatchEntryForTheSameEpisode() {
        val item = downloadItem(
            parentMetaId = showId,
            season = 1,
            episode = 1,
            status = DownloadStatus.Completed,
            downloadedBytes = 900L,
            totalBytes = 900L,
        )

        val state = buildTitleDownloadState(
            items = listOf(item),
            batches = listOf(
                batch(
                    entries = listOf(
                        batchEntry(season = 1, episode = 1, state = DownloadBatchEntryState.DISCOVERING),
                    ),
                ),
            ),
            parentMetaId = showId,
        )

        val resolved = state.forEpisode(1, 1)
        assertEquals(DownloadPresence.Completed, resolved.presence)
        assertSame(item, resolved.item)
        assertNull(resolved.batchId)
        assertEquals(1, state.completedCount)
    }

    @Test
    fun movieAndEpisodeKeysDoNotCollide() {
        val movie = downloadItem(parentMetaId = movieId, season = null, episode = null)
        val state = buildTitleDownloadState(
            items = listOf(movie),
            batches = emptyList(),
            parentMetaId = movieId,
        )

        assertEquals(DownloadPresence.Downloading, state.forMovie().presence)
        assertEquals(DownloadPresence.None, state.forEpisode(1, 1).presence)
        assertEquals(DownloadPresence.None, state.forEpisode(null, null).presence)
    }

    @Test
    fun otherTitlesAreExcludedAndSeasonsRollUp() {
        val items = listOf(
            downloadItem(showId, season = 1, episode = 1, status = DownloadStatus.Completed, downloadedBytes = 100L),
            downloadItem(showId, season = 1, episode = 2, status = DownloadStatus.Completed, downloadedBytes = 200L),
            downloadItem(showId, season = 2, episode = 1, status = DownloadStatus.Downloading, downloadedBytes = 50L),
            downloadItem("tt0000000", season = 1, episode = 1, status = DownloadStatus.Completed, downloadedBytes = 999L),
        )

        val state = buildTitleDownloadState(items = items, batches = emptyList(), parentMetaId = showId)

        assertEquals(3, state.byLogicalKey.size)
        assertEquals(2, state.forSeason(1).size)
        assertEquals(1, state.forSeason(2).size)
        assertEquals(2, state.completedCount)
        assertEquals(1, state.activeCount)
        assertEquals(350L, state.bytesOnDisk)
    }

    @Test
    fun seasonRollUpDoesNotMatchSeasonNumberPrefixes() {
        val items = listOf(
            downloadItem(showId, season = 1, episode = 1, status = DownloadStatus.Completed),
            downloadItem(showId, season = 11, episode = 1, status = DownloadStatus.Completed),
        )

        val state = buildTitleDownloadState(items = items, batches = emptyList(), parentMetaId = showId)

        assertEquals(1, state.forSeason(1).size)
        assertEquals(1, state.forSeason(11).size)
    }

    @Test
    fun anEmptyParentMetaIdResolvesToNothing() {
        val state = buildTitleDownloadState(
            items = listOf(downloadItem(showId, season = 1, episode = 1)),
            batches = emptyList(),
            parentMetaId = "   ",
        )

        assertTrue(state.isEmpty)
    }

    @Test
    fun sizeApprovalIsReportedAsNeedingAttention() {
        val state = buildTitleDownloadState(
            items = listOf(
                downloadItem(
                    parentMetaId = showId,
                    season = 1,
                    episode = 1,
                    status = DownloadStatus.Paused,
                    sizeApprovalRequired = true,
                ),
            ),
            batches = emptyList(),
            parentMetaId = showId,
        )

        assertEquals(DownloadPresence.NeedsApproval, state.forEpisode(1, 1).presence)
        assertEquals(1, state.needsAttentionCount)
        assertTrue(state.forEpisode(1, 1).presence.needsAttention)
    }

    private fun downloadItem(
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        status: DownloadStatus = DownloadStatus.Downloading,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        sizeApprovalRequired: Boolean = false,
    ): DownloadItem = DownloadItem(
        id = "$parentMetaId-$season-$episode",
        contentType = if (season != null) "series" else "movie",
        parentMetaId = parentMetaId,
        parentMetaType = if (season != null) "series" else "movie",
        videoId = "$parentMetaId:$season:$episode",
        title = "Title",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "provider",
        sourceUrl = "https://example.test/file.mkv",
        fileName = "file.mkv",
        status = status,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        sizeApprovalRequired = sizeApprovalRequired,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun batchEntry(
        season: Int,
        episode: Int,
        state: DownloadBatchEntryState,
    ): DownloadBatchEntry = DownloadBatchEntry(
        id = "entry-$season-$episode",
        videoId = "$showId:$season:$episode",
        title = "Episode $episode",
        season = season,
        episode = episode,
        state = state,
    )

    private fun batch(entries: List<DownloadBatchEntry>): DownloadBatch = DownloadBatch(
        id = "batch-1",
        scope = DownloadScope.Season(1),
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        title = "Show",
        presetSnapshot = DownloadPreset.Balanced,
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = entries,
        createdAtEpochMs = 0L,
    )
}
