package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQueueTest {

    @Test
    fun aSeasonEnqueuedInEpisodeOrderStaysInEpisodeOrder() {
        // The queue used to be built by prepending, so a season batch enqueued E01..E05
        // ran backwards, E05 first.
        var items = emptyList<DownloadItem>()
        (1..5).forEach { episode ->
            items = items + item(
                id = "e$episode",
                episode = episode,
                queuePosition = DownloadQueuePlanner.nextQueuePosition(items),
            )
        }

        assertEquals(
            listOf("e1", "e2", "e3", "e4", "e5"),
            items.sortedWith(downloadQueueComparator).map { it.id },
        )
        assertEquals(
            listOf("e1", "e2"),
            DownloadQueuePlanner.startable(
                items = items,
                activeIds = emptySet(),
                maxConcurrent = DownloadsRepository.MAX_CONCURRENT_TRANSFERS,
                nowEpochMs = NOW,
            ).map { it.id },
        )
    }

    @Test
    fun noMoreThanTheConcurrencyLimitRunsAtOnce() {
        val items = listOf(
            item(id = "a", queuePosition = 0L, status = DownloadStatus.Downloading),
            item(id = "b", queuePosition = 1L, status = DownloadStatus.Downloading),
            item(id = "c", queuePosition = 2L),
        )

        assertTrue(
            DownloadQueuePlanner.startable(
                items = items,
                activeIds = setOf("a", "b"),
                maxConcurrent = 2,
                nowEpochMs = NOW,
            ).isEmpty(),
        )
    }

    @Test
    fun freeingASlotLetsTheNextQueuedItemStart() {
        val items = listOf(
            item(id = "a", queuePosition = 0L, status = DownloadStatus.Paused),
            item(id = "b", queuePosition = 1L, status = DownloadStatus.Downloading),
            item(id = "c", queuePosition = 2L),
        )

        assertEquals(
            listOf("c"),
            DownloadQueuePlanner.startable(
                items = items,
                activeIds = setOf("b"),
                maxConcurrent = 2,
                nowEpochMs = NOW,
            ).map { it.id },
        )
    }

    @Test
    fun itemsServingARetryBackoffAreSkippedUntilItExpires() {
        val items = listOf(
            item(id = "a", queuePosition = 0L, nextRetryAtEpochMs = NOW + 5_000L),
            item(id = "b", queuePosition = 1L),
        )

        assertEquals(
            listOf("b"),
            DownloadQueuePlanner.startable(
                items = items,
                activeIds = emptySet(),
                maxConcurrent = 2,
                nowEpochMs = NOW,
            ).map { it.id },
        )
        assertEquals(
            listOf("a", "b"),
            DownloadQueuePlanner.startable(
                items = items,
                activeIds = emptySet(),
                maxConcurrent = 2,
                nowEpochMs = NOW + 5_001L,
            ).map { it.id },
        )
    }

    @Test
    fun movingToTopAndToBottomRepositionsTheItem() {
        val items = rankedItems("a", "b", "c", "d")

        assertEquals(
            listOf("c", "a", "b", "d"),
            DownloadQueuePlanner.reordered(items, "c", QueueMove.ToTop).ranked(),
        )
        assertEquals(
            listOf("b", "c", "d", "a"),
            DownloadQueuePlanner.reordered(items, "a", QueueMove.ToBottom).ranked(),
        )
    }

    @Test
    fun movingUpAndDownSwapsWithTheNeighbour() {
        val items = rankedItems("a", "b", "c", "d")

        assertEquals(
            listOf("a", "c", "b", "d"),
            DownloadQueuePlanner.reordered(items, "c", QueueMove.Up).ranked(),
        )
        assertEquals(
            listOf("a", "c", "b", "d"),
            DownloadQueuePlanner.reordered(items, "b", QueueMove.Down).ranked(),
        )
    }

    @Test
    fun movesAtTheBoundariesAndForUnknownItemsChangeNothing() {
        val items = rankedItems("a", "b", "c")

        assertEquals(items, DownloadQueuePlanner.reordered(items, "a", QueueMove.Up))
        assertEquals(items, DownloadQueuePlanner.reordered(items, "a", QueueMove.ToTop))
        assertEquals(items, DownloadQueuePlanner.reordered(items, "c", QueueMove.Down))
        assertEquals(items, DownloadQueuePlanner.reordered(items, "c", QueueMove.ToBottom))
        assertEquals(items, DownloadQueuePlanner.reordered(items, "missing", QueueMove.ToTop))
    }

    @Test
    fun ranksStayDenseAndCollisionFreeAcrossRepeatedMoves() {
        var items = rankedItems("a", "b", "c", "d")
        repeat(6) {
            items = DownloadQueuePlanner.reordered(items, "d", QueueMove.ToTop)
            items = DownloadQueuePlanner.reordered(items, "a", QueueMove.ToBottom)
        }

        val positions = items.map { it.queuePosition }
        assertEquals(positions.size, positions.toSet().size, "ranks collided: $positions")
        assertEquals(listOf(0L, 1L, 2L, 3L), positions.sorted())
    }

    @Test
    fun completedItemsKeepTheirRankAndAreNotRenumbered() {
        val items = listOf(
            item(id = "done", queuePosition = 99L, status = DownloadStatus.Completed),
            item(id = "a", queuePosition = 0L),
            item(id = "b", queuePosition = 1L),
        )

        val reordered = DownloadQueuePlanner.reordered(items, "b", QueueMove.ToTop)
        assertEquals(99L, reordered.first { it.id == "done" }.queuePosition)
        assertEquals(listOf("b", "a"), reordered.ranked())
    }

    @Test
    fun oldPayloadsWithNoRanksAreRenumberedFromTheirStoredOrder() {
        // Every item written before ranks existed carries the default of zero, so only
        // creation order separates them.
        val items = listOf(
            item(id = "first", queuePosition = 0L, createdAtEpochMs = 10L),
            item(id = "second", queuePosition = 0L, createdAtEpochMs = 20L),
            item(id = "third", queuePosition = 0L, createdAtEpochMs = 30L),
        )

        val normalized = DownloadQueuePlanner.normalized(items)
        assertEquals(listOf("first", "second", "third"), normalized.ranked())
        assertEquals(listOf(0L, 1L, 2L), normalized.map { it.queuePosition })
    }

    @Test
    fun promotingPastAFullSlateDisplacesTheLowestPriorityTransfer() {
        val items = rankedItems("a", "b", "c").map {
            if (it.id == "c") it else it.copy(status = DownloadStatus.Downloading)
        }
        val promoted = DownloadQueuePlanner.reordered(items, "c", QueueMove.ToTop)

        val displaced = DownloadQueuePlanner.preemptionCandidate(
            items = promoted,
            promotedId = "c",
            activeIds = setOf("a", "b"),
            maxConcurrent = 2,
        )

        // "b" is the furthest down the queue of the two running, so it gives up its slot.
        assertEquals("b", displaced?.id)
    }

    @Test
    fun nothingIsDisplacedWhenASlotIsFreeOrTheItemAlreadyRuns() {
        val items = rankedItems("a", "b", "c")

        assertNull(
            DownloadQueuePlanner.preemptionCandidate(
                items = items,
                promotedId = "c",
                activeIds = setOf("a"),
                maxConcurrent = 2,
            ),
        )
        assertNull(
            DownloadQueuePlanner.preemptionCandidate(
                items = items,
                promotedId = "a",
                activeIds = setOf("a", "b"),
                maxConcurrent = 2,
            ),
        )
    }

    @Test
    fun aPausedItemHoldsItsPlaceInLine() {
        val items = rankedItems("a", "b", "c")
        val paused = items.map {
            if (it.id == "b") {
                it.copy(status = DownloadStatus.Paused, pauseReason = DownloadPauseReason.User)
            } else {
                it
            }
        }

        assertEquals(listOf("a", "b", "c"), paused.ranked())
        // Paused means "not now", not "not ever": the slot goes to the next item, and
        // "b" is still second when it comes back.
        assertEquals(
            listOf("a", "c"),
            DownloadQueuePlanner.startable(
                items = paused,
                activeIds = emptySet(),
                maxConcurrent = 2,
                nowEpochMs = NOW,
            ).map { it.id },
        )
        val resumed = paused.map {
            if (it.id == "b") it.copy(status = DownloadStatus.Queued, pauseReason = null) else it
        }
        assertEquals(1L, resumed.first { it.id == "b" }.queuePosition)
    }

    // --- helpers ------------------------------------------------------------------

    private fun List<DownloadItem>.ranked(): List<String> =
        filter { it.status != DownloadStatus.Completed }
            .sortedWith(downloadQueueComparator)
            .map { it.id }

    private fun rankedItems(vararg ids: String): List<DownloadItem> =
        ids.mapIndexed { index, id -> item(id = id, queuePosition = index.toLong()) }

    private fun item(
        id: String,
        episode: Int? = null,
        queuePosition: Long = 0L,
        status: DownloadStatus = DownloadStatus.Queued,
        nextRetryAtEpochMs: Long? = null,
        createdAtEpochMs: Long = NOW,
    ): DownloadItem = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = "tt0000001",
        parentMetaType = "series",
        videoId = "tt0000001:1:${episode ?: 1}",
        title = "Show",
        seasonNumber = episode?.let { 1 },
        episodeNumber = episode,
        streamTitle = "1080p",
        providerName = "provider",
        sourceUrl = "https://example.test/$id.mp4",
        fileName = "$id.mp4",
        status = status,
        queuePosition = queuePosition,
        nextRetryAtEpochMs = nextRetryAtEpochMs,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = createdAtEpochMs,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
