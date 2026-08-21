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

    @Test
    fun theFourMebibyteTransferThatReadFiftySixIsReadCorrectly() {
        // The reported fault, replayed from the estimate found on disk:
        //   {"mbps":56.470505050505054,"samples":14,"source":"PROBE"}
        // That is the neutral endpoint's 4 MiB body, less the uncounted first 64 KiB chunk, in
        // 585 ms. The mean over it is 56 Mb/s; most of those 585 ms is congestion window ramp,
        // and the line was carrying an 81 Mbps remux at the time. At the shipped defaults the
        // window has to close inside that transfer and report the steady rate, not the mean.
        // 5 ms slices: 250 ms of congestion window ramp, then 335 ms steady at 9 kB/ms = 72 Mb/s.
        // Those two stretches carry 4.16 MB in 585 ms, whose mean is 56.9 Mb/s - the number that
        // was on disk.
        val window = ThroughputWindow()
        var bytes = 0L
        (1..50).forEach { step ->
            bytes += 900L * step
            window.record(elapsedMs = step * 5L, cumulativeBytes = bytes)
        }
        (51..117).forEach { step ->
            bytes += 45_000L
            window.record(elapsedMs = step * 5L, cumulativeBytes = bytes)
        }

        val mean = bytes.toDouble() * 8.0 / 585.0 / 1_000.0
        val peak = assertNotNull(window.peakMbps, "a 585 ms transfer must still close a window")

        assertEquals(56.9, mean, 1.0, "the replayed transfer must reproduce the figure on disk")
        assertEquals(72.0, peak, 3.0)
        assertTrue(peak > mean * 1.2, "peak $peak should clearly beat the ramp-heavy mean $mean")
    }

    @Test
    fun aFastLineClosesAWindowInsideABudgetItCanAfford() {
        // 300 Mb/s against the 32 MiB ceiling: the whole transfer is ~0.9 s, so a 750 ms window
        // could never have closed past the ramp. This is the case the old fixed window silently
        // failed, and it failed *upwards* - the faster the line, the worse the reading.
        val window = ThroughputWindow()
        var bytes = 0L
        (1..90).forEach { step ->
            // 37.5 kB/ms = 300 Mb/s, held from the start for simplicity.
            bytes += 375_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }

        assertEquals(300.0, assertNotNull(window.peakMbps), 5.0)
    }

    @Test
    fun aSlowLineStretchesItsWindowRatherThanReportingAJitteryQuarterSecond() {
        // 5 Mb/s delivers 156 kB in 250 ms - well under the byte floor - so the window has to
        // widen until it holds a mebibyte. Reporting the shortest legal span here would let one
        // late read swing the figure, which is exactly what the byte floor is for.
        val window = ThroughputWindow()
        var bytes = 0L
        // 625 B/ms = 5 Mb/s over 3 s, the probe's whole time budget on a line this slow.
        (1..60).forEach { step ->
            bytes += 31_250L
            window.record(elapsedMs = step * 50L, cumulativeBytes = bytes)
        }

        assertEquals(5.0, assertNotNull(window.peakMbps), 0.2)
    }

    @Test
    fun aSteadyLineReadsTheSameSustainedRateAsItDoesPeakRate() {
        // Nothing to discard, so the median of the blocks and the best window agree. If these
        // two ever disagree on a flat input, the block partitioning is wrong.
        val window = ThroughputWindow()
        var bytes = 0L
        (1..200).forEach { step ->
            bytes += 125_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }

        assertEquals(100.0, assertNotNull(window.peakMbps), 1.0)
        assertEquals(100.0, assertNotNull(window.sustainedMbps), 1.0)
    }

    @Test
    fun aDrainedReceiveBufferDoesNotBecomeTheAnswer() {
        // The reported fault, and the reason `sustainedMbps` exists. The line holds 100 Mb/s the
        // whole way - the byte total over the whole transfer proves it - but the reader falls
        // behind for half a second and then catches up in 50 ms, emptying the receive buffer at
        // memory speed. Those bytes arrived while the reader was behind. The sliding maximum
        // credits them to the window it drained them in and reads nearly twice the truth.
        val window = ThroughputWindow()
        var bytes = 0L
        // 12.5 kB/ms = 100 Mb/s, keeping up.
        (1..100).forEach { step ->
            bytes += 125_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }
        // 1000 -> 1500 ms: the reader is descheduled down to 60 Mb/s and 2.5 MB piles up.
        (101..150).forEach { step ->
            bytes += 75_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }
        // 1500 -> 1550 ms: the backlog plus 50 ms of live data, all read in 50 ms.
        (1..5).forEach { step ->
            bytes += 625_000L
            window.record(elapsedMs = 1_500L + step * 10L, cumulativeBytes = bytes)
        }
        (156..400).forEach { step ->
            bytes += 125_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }

        // 50 MB in 4 s: the line really did carry 100 Mb/s.
        val mean = bytes.toDouble() * 8.0 / 4_000.0 / 1_000.0
        assertEquals(100.0, mean, 0.5, "the scenario must conserve bytes or it proves nothing")

        val peak = assertNotNull(window.peakMbps)
        val sustained = assertNotNull(window.sustainedMbps)

        assertEquals(100.0, sustained, 3.0)
        assertTrue(peak > 150.0, "the maximum should visibly over-read here, but read $peak")
    }

    @Test
    fun aTrailingStallIsInNoBlockAtAll() {
        // The byte partitioning earns its keep here. A stall at the end of a transfer carries no
        // bytes, so it falls outside every block by construction rather than by a rule - which is
        // how `sustainedMbps` keeps the same answer `peakMbps` gives above.
        val window = ThroughputWindow()
        var bytes = 0L
        (1..100).forEach { step ->
            bytes += 125_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }
        window.record(elapsedMs = 2_000L, cumulativeBytes = bytes)

        assertEquals(100.0, assertNotNull(window.sustainedMbps), 2.0)
    }

    @Test
    fun aMidTransferStallIsDiscardedByTheMedian() {
        // One block goes slow and the middle pair never sees it. A stall says the line paused,
        // not that it cannot go fast, and every signal feeding the estimate is a lower bound.
        val window = ThroughputWindow()
        var bytes = 0L
        (1..100).forEach { step ->
            bytes += 125_000L
            window.record(elapsedMs = step * 10L, cumulativeBytes = bytes)
        }
        // 300 ms in which almost nothing arrives.
        (1..3).forEach { step ->
            bytes += 1_000L
            window.record(elapsedMs = 1_000L + step * 100L, cumulativeBytes = bytes)
        }
        (1..100).forEach { step ->
            bytes += 125_000L
            window.record(elapsedMs = 1_300L + step * 10L, cumulativeBytes = bytes)
        }

        assertEquals(100.0, assertNotNull(window.sustainedMbps), 5.0)
    }

    @Test
    fun theReplayedFourMebibyteTransferReadsItsSteadyRateAsWell() {
        // The same replay as above, read the way the probe now records it. The point is that the
        // median must NOT drift back towards the 56.9 Mb/s mean: this statistic replaces a
        // maximum that over-read, and it must not re-open the under-read the maximum was added
        // to fix.
        val window = ThroughputWindow()
        var bytes = 0L
        (1..50).forEach { step ->
            bytes += 900L * step
            window.record(elapsedMs = step * 5L, cumulativeBytes = bytes)
        }
        (51..117).forEach { step ->
            bytes += 45_000L
            window.record(elapsedMs = step * 5L, cumulativeBytes = bytes)
        }

        val mean = bytes.toDouble() * 8.0 / 585.0 / 1_000.0
        val sustained = assertNotNull(window.sustainedMbps)

        assertEquals(72.0, sustained, 4.0)
        assertTrue(sustained > mean * 1.2, "sustained $sustained should clearly beat the mean $mean")
    }

    @Test
    fun aTransferTooSmallToHoldBlocksReportsNoSustainedFigure() {
        // The same honest null `peakMbps` reports. Three blocks is not a median.
        val window = ThroughputWindow()
        (1..20).forEach { step ->
            window.record(elapsedMs = step * 100L, cumulativeBytes = step * 25_000L)
        }

        assertNull(window.sustainedMbps)
    }

    @Test
    fun aTransferTooSmallToHoldAWindowStillReportsNothing() {
        // The honest null. Under a mebibyte there is no window, whatever the clock says, and the
        // caller falls back to the mean under its own sample floors rather than being handed a
        // rate computed from a handful of packets.
        val window = ThroughputWindow()
        (1..20).forEach { step ->
            window.record(elapsedMs = step * 100L, cumulativeBytes = step * 25_000L)
        }

        assertNull(window.peakMbps)
    }
}
