package com.nuvio.app.features.playback

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwapDiagnosticsLogTest {

    @BeforeTest
    fun setUp() = SwapDiagnosticsLog.clear()

    @AfterTest
    fun tearDown() = SwapDiagnosticsLog.clear()

    @Test
    fun aSwapIsRecordedUnresolvedUntilTheNewSourceStarts() {
        SwapDiagnosticsLog.record(swap())

        val pending = SwapDiagnosticsLog.entries.single()
        assertFalse(pending.isResolved)
        assertNull(pending.gapMs)

        SwapDiagnosticsLog.completePending(gapMs = 1_800L)

        val done = SwapDiagnosticsLog.entries.single()
        assertTrue(done.isResolved)
        assertEquals(1_800L, done.gapMs)
    }

    @Test
    fun aSupersededSwapStaysUnresolvedAndTheFrameGoesToTheNewestOne() {
        // A second swap can be requested while the first is still loading - the player tears
        // the first one down, so the next first frame belongs to the newer source. Crediting
        // the older one would report a gap for a stream that never actually started, and
        // would hide the fact that it was abandoned.
        SwapDiagnosticsLog.record(swap(fromHeight = 2160, toHeight = 1080))
        SwapDiagnosticsLog.record(swap(fromHeight = 1080, toHeight = 720))

        SwapDiagnosticsLog.completePending(gapMs = 900L)

        val entries = SwapDiagnosticsLog.entries
        assertNull(entries[0].gapMs, "the superseded swap never produced a frame")
        assertEquals(900L, entries[1].gapMs)
    }

    @Test
    fun aSwapThatNeverCompletesStaysVisibleAsUnresolved() {
        SwapDiagnosticsLog.record(swap())

        assertTrue(SwapDiagnosticsLog.format().contains("never completed"))
    }

    @Test
    fun completingWithNothingOutstandingIsANoOp() {
        SwapDiagnosticsLog.record(swap())
        SwapDiagnosticsLog.completePending(gapMs = 500L)
        SwapDiagnosticsLog.completePending(gapMs = 9_999L)

        assertEquals(500L, SwapDiagnosticsLog.entries.single().gapMs)
    }

    @Test
    fun aNegativeGapIsClampedRatherThanRecorded() {
        // Clocks that jump backwards must not produce a gap that reads as an improvement.
        SwapDiagnosticsLog.record(swap())
        SwapDiagnosticsLog.completePending(gapMs = -400L)

        assertEquals(0L, SwapDiagnosticsLog.entries.single().gapMs)
    }

    @Test
    fun theLogIsBoundedAndKeepsTheNewest() {
        repeat(SwapDiagnosticsLog.CAPACITY + 10) { index ->
            SwapDiagnosticsLog.record(swap(fromHeight = index))
        }

        val entries = SwapDiagnosticsLog.entries
        assertEquals(SwapDiagnosticsLog.CAPACITY, entries.size)
        assertEquals(SwapDiagnosticsLog.CAPACITY + 9, entries.last().fromHeight)
    }

    @Test
    fun theEmptyLogSaysSoRatherThanReturningBlank() {
        assertEquals("No source swaps recorded.", SwapDiagnosticsLog.format())
    }

    private fun swap(
        trigger: SwapDiagnosticsLog.Trigger = SwapDiagnosticsLog.Trigger.AUTO_DOWNSHIFT,
        fromHeight: Int? = 2160,
        toHeight: Int? = 1080,
    ) = SwapDiagnosticsLog.SwapRecord(
        trigger = trigger,
        fromLabel = "from",
        toLabel = "to",
        fromHeight = fromHeight,
        toHeight = toHeight,
        fromReleaseGroup = "NUVIO",
        toReleaseGroup = "NUVIO",
        bufferAheadMsAtTrigger = 1_200L,
        positionMsBefore = 600_000L,
    )
}
