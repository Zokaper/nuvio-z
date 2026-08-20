package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThroughputWindowTest {

    @Test
    fun reportsNothingUntilAWindowHasClosed() {
        val window = ThroughputWindow(windowMs = 750L)
        window.record(elapsedMs = 0L, cumulativeBytes = 0L)
        window.record(elapsedMs = 300L, cumulativeBytes = 1_000_000L)

        assertNull(window.peakMbps)
    }

    @Test
    fun measuresASteadyLineAtItsRealRate() {
        // 12.5 MB/s = 100 Mbps, held throughout.
        val window = ThroughputWindow(windowMs = 750L)
        (0..20).forEach { step ->
            window.record(elapsedMs = step * 100L, cumulativeBytes = step * 1_250_000L)
        }

        assertEquals(100.0, assertNotNull(window.peakMbps), 1.0)
    }

    @Test
    fun theRampDoesNotDragTheAnswerDown() {
        // The whole point. One second climbing from nothing to 100 Mbps, then one second at
        // 100 Mbps. The mean over the pair is about 75 Mbps; the line carries 100.
        val window = ThroughputWindow(windowMs = 750L)
        var bytes = 0L
        (1..10).forEach { step ->
            // Slow start: each 100 ms slice moves a little more than the last.
            bytes += 250_000L * step / 4
            window.record(elapsedMs = step * 100L, cumulativeBytes = bytes)
        }
        (11..20).forEach { step ->
            bytes += 1_250_000L
            window.record(elapsedMs = step * 100L, cumulativeBytes = bytes)
        }

        val peak = assertNotNull(window.peakMbps)
        val mean = bytes.toDouble() * 8.0 / 2_000.0 / 1_000.0

        assertEquals(100.0, peak, 2.0)
        assertTrue(peak > mean * 1.2, "peak $peak should clearly beat the ramp-contaminated mean $mean")
    }

    @Test
    fun aBriefStallDoesNotBecomeTheAnswer() {
        // Windows are a *maximum*, so a mid-transfer stall costs nothing - which is right: a
        // stall proves the line paused, not that it cannot go fast, and every signal feeding the
        // estimate is a lower bound already.
        val window = ThroughputWindow(windowMs = 750L)
        var bytes = 0L
        (1..10).forEach { step ->
            bytes += 1_250_000L
            window.record(elapsedMs = step * 100L, cumulativeBytes = bytes)
        }
        window.record(elapsedMs = 2_000L, cumulativeBytes = bytes)

        assertEquals(100.0, assertNotNull(window.peakMbps), 2.0)
    }

    @Test
    fun keepsWorkingAcrossALongTransferWithoutGrowingWithoutBound() {
        // The deque is pruned as it goes; this only asserts the pruning has not broken the
        // arithmetic for samples that arrive long after the start.
        val window = ThroughputWindow(windowMs = 750L)
        (0..500).forEach { step ->
            window.record(elapsedMs = step * 50L, cumulativeBytes = step * 625_000L)
        }

        assertEquals(100.0, assertNotNull(window.peakMbps), 1.0)
    }

    @Test
    fun ignoresNonsenseInput() {
        val window = ThroughputWindow(windowMs = 750L)
        window.record(elapsedMs = -1L, cumulativeBytes = 5L)
        window.record(elapsedMs = 5L, cumulativeBytes = -1L)

        assertNull(window.peakMbps)
    }
}
