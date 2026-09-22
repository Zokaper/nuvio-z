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

    private fun candidate(
        id: String,
        state: DownloadsLiveStatusPolicy.State,
        bytes: Long = 0,
        total: Long? = null,
        updated: Long = 0,
    ) = DownloadsLiveStatusPolicy.Candidate(id, state, bytes, total, updated)
}
