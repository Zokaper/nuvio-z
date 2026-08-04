package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression cover for the series page disagreeing with the Downloads page.
 *
 * The detail screens fall back to batch entries wherever no download exists, so an
 * entry left frozen at its last state after its download was deleted showed the
 * episode as downloading or downloaded on the series page with nothing behind it.
 */
class DownloadBatchReconcileTest {
    private val showId = "tt0903747"

    @Test
    fun deletingEveryDownloadClearsTheBatchItLeftBehind() {
        val batch = batch(
            entry(1, 1, DownloadBatchEntryState.COMPLETED),
            entry(1, 2, DownloadBatchEntryState.DOWNLOADING),
            entry(1, 3, DownloadBatchEntryState.QUEUED),
        )

        val reconciled = reconcileBatches(listOf(batch), items = emptyList())

        // Every entry was item-backed and every item is gone, so nothing is left to show.
        assertEquals(emptyList(), reconciled)
    }

    @Test
    fun deletedEpisodesReadAsNothingOnTheDetailScreen() {
        val batch = batch(
            entry(1, 1, DownloadBatchEntryState.COMPLETED),
            entry(1, 2, DownloadBatchEntryState.DOWNLOADING),
            entry(1, 3, DownloadBatchEntryState.APPROVAL_NEEDED),
        )

        val reconciled = reconcileBatches(listOf(batch), items = emptyList())
        val state = buildTitleDownloadState(
            items = emptyList(),
            batches = reconciled,
            parentMetaId = showId,
        )

        assertEquals(DownloadPresence.None, state.forEpisode(1, 1).presence)
        assertEquals(DownloadPresence.None, state.forEpisode(1, 2).presence)
        // The approval still needs the user, so it survives the delete.
        assertEquals(DownloadPresence.NeedsApproval, state.forEpisode(1, 3).presence)
    }

    @Test
    fun anEntryStillFollowsItsDownload() {
        val batch = batch(entry(1, 1, DownloadBatchEntryState.QUEUED))
        val item = downloadItem(season = 1, episode = 1, status = DownloadStatus.Downloading)

        val reconciled = reconcileBatches(listOf(batch), items = listOf(item))

        assertEquals(
            DownloadBatchEntryState.DOWNLOADING,
            reconciled.single().entries.single().state,
        )
    }

    @Test
    fun entriesWithoutADownloadYetAreLeftAlone() {
        // Discovery has not produced anything for these to be backed by, and a batch
        // is persisted before discovery even starts.
        val batch = batch(
            entry(1, 1, DownloadBatchEntryState.DISCOVERING),
            entry(1, 2, DownloadBatchEntryState.READY),
            entry(1, 3, DownloadBatchEntryState.SKIPPED),
        )

        val reconciled = reconcileBatches(listOf(batch), items = emptyList())

        assertEquals(batch.entries, reconciled.single().entries)
    }

    @Test
    fun aDiscoveryFailureSurvivesWithNoDownloadBehindIt() {
        // FAILED is reached both by a failed transfer and by a failed discovery, so it
        // must not be cleared: those entries are what the review section offers a
        // manual source pick for.
        val batch = batch(entry(1, 1, DownloadBatchEntryState.FAILED))

        val reconciled = reconcileBatches(listOf(batch), items = emptyList())

        assertEquals(DownloadBatchEntryState.FAILED, reconciled.single().entries.single().state)
    }

    @Test
    fun deletingOneEpisodeLeavesTheRestOfTheBatch() {
        val batch = batch(
            entry(1, 1, DownloadBatchEntryState.COMPLETED),
            entry(1, 2, DownloadBatchEntryState.COMPLETED),
        )
        val surviving = downloadItem(season = 1, episode = 2, status = DownloadStatus.Completed)

        val reconciled = reconcileBatches(listOf(batch), items = listOf(surviving))
        val entries = reconciled.single().entries

        assertEquals(DownloadBatchEntryState.CANCELLED, entries.first().state)
        assertEquals(DownloadBatchEntryState.COMPLETED, entries.last().state)

        val state = buildTitleDownloadState(
            items = listOf(surviving),
            batches = reconciled,
            parentMetaId = showId,
        )
        assertEquals(DownloadPresence.None, state.forEpisode(1, 1).presence)
        assertEquals(DownloadPresence.Completed, state.forEpisode(1, 2).presence)
    }

    @Test
    fun anEmptyBatchIsNotMistakenForAFullyCancelledOne() {
        val batch = batch()

        val reconciled = reconcileBatches(listOf(batch), items = emptyList())

        assertEquals(1, reconciled.size)
        assertTrue(reconciled.single().entries.isEmpty())
    }

    @Test
    fun reconcilingTwiceChangesNothingFurther() {
        val batch = batch(
            entry(1, 1, DownloadBatchEntryState.COMPLETED),
            entry(1, 2, DownloadBatchEntryState.APPROVAL_NEEDED),
        )

        val once = reconcileBatches(listOf(batch), items = emptyList())
        val twice = reconcileBatches(once, items = emptyList())

        assertEquals(once, twice)
        assertNull(once.single().entries.first().failureMessage)
    }

    private fun entry(
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
        failureMessage = "stale".takeIf { state == DownloadBatchEntryState.COMPLETED },
    )

    private fun batch(vararg entries: DownloadBatchEntry): DownloadBatch = DownloadBatch(
        id = "batch-1",
        scope = DownloadScope.Season(1),
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        title = "Show",
        presetSnapshot = DownloadPreset.Balanced,
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = entries.toList(),
        createdAtEpochMs = 0L,
    )

    private fun downloadItem(
        season: Int,
        episode: Int,
        status: DownloadStatus,
    ): DownloadItem = DownloadItem(
        id = "$showId-$season-$episode",
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:$season:$episode",
        title = "Show",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "provider",
        sourceUrl = "https://example.test/file.mkv",
        fileName = "file.mkv",
        status = status,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
