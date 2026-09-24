package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadsLiveStatusPolicyTest {
    private val p = DownloadsLiveStatusPolicy
    private val finding = DownloadsLiveStatusPolicy.State.FINDING_SOURCES

    @Test fun resolvingBatchShowsFindingSources() {
        assertEquals(finding, p.select(emptyList(), candidate("batch", finding))?.candidate?.state)
    }

    @Test fun preparingAndRetryingRemainHonestStates() {
        assertEquals(DownloadsLiveStatusPolicy.State.PREPARING, p.select(listOf(candidate("item", DownloadsLiveStatusPolicy.State.PREPARING)))?.candidate?.state)
        assertEquals(DownloadsLiveStatusPolicy.State.RETRYING, p.select(listOf(candidate("item", DownloadsLiveStatusPolicy.State.RETRYING)))?.candidate?.state)
    }

    @Test fun unknownTotalNeverInventsAPercentage() {
        assertNull(p.select(listOf(candidate("item", DownloadsLiveStatusPolicy.State.DOWNLOADING, bytes = 50)))?.progressPercent)
    }

    @Test fun knownTotalProducesBoundedProgress() {
        assertEquals(25, p.select(listOf(candidate("item", DownloadsLiveStatusPolicy.State.DOWNLOADING, bytes = 25, total = 100)))?.progressPercent)
        assertEquals(100, p.select(listOf(candidate("item", DownloadsLiveStatusPolicy.State.DOWNLOADING, bytes = 125, total = 100)))?.progressPercent)
    }

    @Test fun finalCompletionClearsLiveStatus() {
        assertNull(p.select(listOf(candidate("done", DownloadsLiveStatusPolicy.State.COMPLETED))))
    }

    @Test fun completingPrimaryPromotesNextQueuedItem() {
        val result = p.select(
            listOf(
                candidate("done", DownloadsLiveStatusPolicy.State.COMPLETED, updated = 20),
                candidate("next", DownloadsLiveStatusPolicy.State.WAITING, updated = 10),
            ),
        )
        assertEquals("next", result?.candidate?.id)
        assertEquals(DownloadsLiveStatusPolicy.State.WAITING, result?.candidate?.state)
    }

    @Test fun activeTransferWinsOverNewerPausedOrFailedRows() {
        val result = p.select(
            listOf(
                candidate("paused", DownloadsLiveStatusPolicy.State.PAUSED, updated = 30),
                candidate("failed", DownloadsLiveStatusPolicy.State.FAILED, updated = 40),
                candidate("active", DownloadsLiveStatusPolicy.State.DOWNLOADING, updated = 1),
            ),
        )
        assertEquals("active", result?.candidate?.id)
    }

    @Test fun twoActiveItemsSelectedByLowestQueuePosition() {
        val items = listOf(
            candidate("ep2", DownloadsLiveStatusPolicy.State.DOWNLOADING, queuePosition = 2),
            candidate("ep1", DownloadsLiveStatusPolicy.State.DOWNLOADING, queuePosition = 1),
        )
        val result = p.select(items)
        assertEquals("ep1", result?.candidate?.id)
        assertEquals(2, result?.activeCount)
        assertEquals("2 downloading", result?.queueSummaryText)
    }

    @Test fun interleavedProgressUpdatesKeepPrimaryStable() {
        val itemA = candidate("ep1", DownloadsLiveStatusPolicy.State.DOWNLOADING, bytes = 100, queuePosition = 1, updated = 10)
        val itemB = candidate("ep2", DownloadsLiveStatusPolicy.State.DOWNLOADING, bytes = 100, queuePosition = 2, updated = 10)

        // 1. Initial selection selects A
        val sel1 = p.select(listOf(itemA, itemB))
        assertEquals("ep1", sel1?.candidate?.id)

        // 2. B reports progress (updated = 20 > A's 10)
        val itemBProgress = itemB.copy(downloadedBytes = 200, updatedAtEpochMs = 20)
        val sel2 = p.select(listOf(itemA, itemBProgress), currentSelectedId = "ep1")
        assertEquals("ep1", sel2?.candidate?.id, "Sticky primary must remain A even when B updates")

        // 3. A reports progress
        val itemAProgress = itemA.copy(downloadedBytes = 300, updatedAtEpochMs = 30)
        val sel3 = p.select(listOf(itemAProgress, itemBProgress), currentSelectedId = "ep1")
        assertEquals("ep1", sel3?.candidate?.id)

        // 4. B reports again
        val itemBProgress2 = itemBProgress.copy(downloadedBytes = 400, updatedAtEpochMs = 40)
        val sel4 = p.select(listOf(itemAProgress, itemBProgress2), currentSelectedId = "ep1")
        assertEquals("ep1", sel4?.candidate?.id, "Sticky primary must NEVER flip back and forth")
    }

    @Test fun primaryCompletionTransitionsToNextActiveWithoutActivityRecreation() {
        val itemAComplete = candidate("ep1", DownloadsLiveStatusPolicy.State.COMPLETED, queuePosition = 1)
        val itemBActive = candidate("ep2", DownloadsLiveStatusPolicy.State.DOWNLOADING, bytes = 500, total = 1000, queuePosition = 2)
        val itemCQueued = candidate("ep3", DownloadsLiveStatusPolicy.State.WAITING, queuePosition = 3)

        val result = p.select(listOf(itemAComplete, itemBActive, itemCQueued), currentSelectedId = "ep1")
        assertEquals("ep2", result?.candidate?.id)
        assertEquals(50, result?.progressPercent)
        assertEquals(1, result?.activeCount)
        assertEquals(1, result?.remainingCount)
        assertEquals("1 remaining", result?.queueSummaryText)
    }

    @Test fun queueSummaryDisplaysActiveAndRemainingCounts() {
        val items = listOf(
            candidate("ep1", DownloadsLiveStatusPolicy.State.DOWNLOADING, queuePosition = 1),
            candidate("ep2", DownloadsLiveStatusPolicy.State.DOWNLOADING, queuePosition = 2),
            candidate("ep3", DownloadsLiveStatusPolicy.State.WAITING, queuePosition = 3),
            candidate("ep4", DownloadsLiveStatusPolicy.State.WAITING, queuePosition = 4),
            candidate("ep5", DownloadsLiveStatusPolicy.State.WAITING, queuePosition = 5),
        )
        val result = p.select(items)
        assertEquals(2, result?.activeCount)
        assertEquals(3, result?.remainingCount)
        assertEquals("2 downloading • 3 remaining", result?.queueSummaryText)
    }

    private fun candidate(
        id: String,
        state: DownloadsLiveStatusPolicy.State,
        bytes: Long = 0,
        total: Long? = null,
        queuePosition: Long = 0,
        updated: Long = 0,
    ) = DownloadsLiveStatusPolicy.Candidate(id, state, bytes, total, queuePosition, updated)
}
