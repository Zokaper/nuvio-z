package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkThroughputMeterTest {

    @Test
    fun startupFillMeasuresTheLineNotTheFile() {
        // The purest window there is: `isLoading`, position static, every buffered millisecond
        // is download. A 10 Mbps file whose buffer gains 12 s of content in 3 s of wall clock
        // is arriving at four times its own bitrate, so the line is carrying about 40.
        val measured = feed(
            fileBitrateMbps = 10.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 0, isLoading = true, isPlaying = false),
                sample(atMs = 3_000, positionMs = 0, bufferedMs = 12_000, isLoading = true, isPlaying = false),
            ),
        )

        assertEquals(40.0, assertNotNull(measured.lastOrNull()), 0.5)
    }

    @Test
    fun aMeasurementMayContradictAnOverGenerousDefault() {
        // The reason this exists at all. `recordSustainedBitrate` is monotonic, so nothing on
        // the playback path could ever report a line *slower* than the 50 Mbps Wi-Fi preset.
        // A 10 Mbps file whose buffer gains 3 s in 4 s is being delivered at about 7.5.
        val measured = feed(
            fileBitrateMbps = 10.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 5_000),
                sample(atMs = 4_000, positionMs = 4_000, bufferedMs = 8_000),
            ),
        )

        assertEquals(7.5, assertNotNull(measured.lastOrNull()), 0.2)
    }

    @Test
    fun theSameTraceAtTwoPollingRatesGivesTheSameAnswer() {
        // Android polls the player about every 250 ms and desktop every 500 ms. A window
        // counted in samples would mean two different durations on the two platforms, which
        // is the fault the repository's wall-clock rule exists to prevent - so it is pinned
        // here rather than described in a comment.
        val fast = feed(fileBitrateMbps = 8.0, samples = ramp(stepMs = 250, totalMs = 6_000, factor = 3.0))
        val slow = feed(fileBitrateMbps = 8.0, samples = ramp(stepMs = 500, totalMs = 6_000, factor = 3.0))

        assertEquals(assertNotNull(fast.maxOrNull()), assertNotNull(slow.maxOrNull()), 0.3)
    }

    @Test
    fun aSaturatedBufferNeverReadsAsASlowLine() {
        // Once the buffer is full the player stops asking for data and the transfer settles at
        // the file's own bitrate. Reporting that would teach the estimate that the connection
        // is exactly as fast as whatever the user last watched.
        val measured = feed(
            fileBitrateMbps = 6.0,
            samples = listOf(
                // A real fill first, so there is something to be dragged down from.
                sample(atMs = 0, positionMs = 0, bufferedMs = 0, isLoading = true, isPlaying = false),
                sample(atMs = 3_000, positionMs = 0, bufferedMs = 15_000, isLoading = true, isPlaying = false),
                // Then steady state: buffer-ahead stops growing, both clocks advance together.
                sample(atMs = 6_000, positionMs = 3_000, bufferedMs = 18_000),
                sample(atMs = 9_000, positionMs = 6_000, bufferedMs = 21_000),
                sample(atMs = 12_000, positionMs = 9_000, bufferedMs = 24_000),
            ),
        )

        assertEquals(1, measured.size)
        assertEquals(30.0, measured.single(), 0.5)
    }

    @Test
    fun aSeekIsNotAMeasurement() {
        // A seek rebuilds the buffer from a new point, so the two deltas stop describing the
        // same stretch of file. The window restarts rather than reporting the jump as delivery.
        val measured = feed(
            fileBitrateMbps = 10.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 5_000),
                sample(atMs = 2_500, positionMs = 600_000, bufferedMs = 610_000),
            ),
        )

        assertTrue(measured.isEmpty())
    }

    @Test
    fun aWindowShorterThanTheMinimumIsNotReported() {
        val measured = feed(
            fileBitrateMbps = 10.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 0, isLoading = true, isPlaying = false),
                sample(atMs = 1_000, positionMs = 0, bufferedMs = 9_000, isLoading = true, isPlaying = false),
            ),
        )

        assertTrue(measured.isEmpty())
    }

    @Test
    fun onlyAnImprovementIsReported() {
        // The maximum is the honest reading, so a second, slower window is folded into the
        // state without being handed to the repository to average in.
        val measured = feed(
            fileBitrateMbps = 10.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 0, isLoading = true, isPlaying = false),
                sample(atMs = 3_000, positionMs = 0, bufferedMs = 15_000, isLoading = true, isPlaying = false),
                sample(atMs = 6_000, positionMs = 1_000, bufferedMs = 22_000),
            ),
        )

        assertEquals(listOf(50.0), measured.map { it })
    }

    @Test
    fun anUnknownFileBitrateMeasuresNothing() {
        // A buffer delta is a duration; without the file's bitrate it converts to nothing, and
        // the monotonic lower-bound path is what still works for such a source.
        val measured = feed(
            fileBitrateMbps = 0.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 0),
                sample(atMs = 3_000, positionMs = 0, bufferedMs = 15_000),
            ),
        )

        assertTrue(measured.isEmpty())
    }

    @Test
    fun anAbsurdBufferJumpIsThrownAway() {
        // One glitched readout on a 40 Mbps file implies a line speed nothing has, and a
        // poisoned estimate outlives the playback it came from.
        val measured = feed(
            fileBitrateMbps = 40.0,
            samples = listOf(
                sample(atMs = 0, positionMs = 0, bufferedMs = 0, isLoading = true, isPlaying = false),
                sample(atMs = 2_000, positionMs = 0, bufferedMs = 400_000, isLoading = true, isPlaying = false),
            ),
        )

        assertTrue(measured.isEmpty())
    }

    @Test
    fun theStateIsInertOnceFinished() {
        var state = NetworkThroughputMeter.initial()
        state = NetworkThroughputMeter.observe(state, sample(0, 0, 0), 10.0).state
        state = NetworkThroughputMeter.observe(
            state,
            sample(atMs = 1_000, positionMs = 0, bufferedMs = 0, isEnded = true),
            10.0,
        ).state

        assertTrue(state.isFinished)
        assertNull(
            NetworkThroughputMeter.observe(
                state,
                sample(atMs = 4_000, positionMs = 0, bufferedMs = 40_000),
                10.0,
            ).measuredMbps,
        )
    }

    private fun feed(
        fileBitrateMbps: Double,
        samples: List<NetworkThroughputMeter.Sample>,
    ): List<Double> {
        var state = NetworkThroughputMeter.initial()
        val reported = mutableListOf<Double>()
        samples.forEach { sample ->
            val outcome = NetworkThroughputMeter.observe(state, sample, fileBitrateMbps)
            state = outcome.state
            outcome.measuredMbps?.let(reported::add)
        }
        return reported
    }

    /** Playback advancing normally while the buffer grows at [factor] times real time. */
    private fun ramp(stepMs: Long, totalMs: Long, factor: Double): List<NetworkThroughputMeter.Sample> =
        generateSequence(0L) { it + stepMs }
            .takeWhile { it <= totalMs }
            .map { atMs ->
                sample(
                    atMs = atMs,
                    positionMs = atMs,
                    bufferedMs = (atMs * factor).toLong(),
                )
            }
            .toList()

    private fun sample(
        atMs: Long,
        positionMs: Long,
        bufferedMs: Long,
        isPlaying: Boolean = true,
        isLoading: Boolean = false,
        isEnded: Boolean = false,
    ) = NetworkThroughputMeter.Sample(
        elapsedRealtimeMs = atMs,
        positionMs = positionMs,
        bufferedPositionMs = bufferedMs,
        isPlaying = isPlaying,
        isLoading = isLoading,
        isEnded = isEnded,
    )
}
