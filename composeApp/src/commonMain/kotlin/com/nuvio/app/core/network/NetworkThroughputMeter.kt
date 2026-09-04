package com.nuvio.app.core.network

/**
 * Turns the player's buffer readout into a real throughput measurement.
 *
 * This is the signal the estimate was missing. `recordSustainedBitrate` is deliberately a
 * *lower bound* - a file arrives at its own bitrate and no faster, so "5 Mbps played cleanly"
 * can never contradict a 50 Mbps platform guess - and it is only recorded after a full minute
 * of playback, long after the quality sheet that quoted the guess has closed. Nothing else on
 * the playback path measured anything, so a user who never downloads was judged forever by
 * [NetworkQualityRepository]'s connection-type presets.
 *
 * The measurement itself needs no new bytes off the network. `bufferedPositionMs` is the
 * absolute stream timestamp of the end of the buffer, so over a window it advances by exactly
 * the amount of *content* that arrived, whatever playback was doing meanwhile:
 *
 * ```
 * throughputMbps = fileBitrateMbps x (delta bufferedPositionMs / delta wallClockMs)
 * ```
 *
 * Unlike the sustained-bitrate path this can read *below* the current estimate, which is the
 * whole point: it is the only thing in the app that can tell a 50 Mbps preset it was wrong.
 *
 * Pure and clock-free, like the other playback decision helpers and
 * `PlaybackModeRouter`: the caller supplies a monotonic timestamp with every sample, so the
 * shipped code runs in tests outside Gradle.
 */
object NetworkThroughputMeter {

    /**
     * Shortest window that may produce a number, in **wall-clock time, never sample counts**.
     *
     * Android polls the player about every 250 ms and desktop every 500 ms, so "eight samples"
     * would mean 2 s on one platform and 4 s on the other. Two seconds is long enough that a
     * single late snapshot cannot dominate the ratio.
     */
    const val MIN_WINDOW_MS = 2_000L

    /**
     * A position jump beyond the window's own duration by more than this is a seek.
     *
     * Matched to the player's seek tolerance
     * rather than re-derived: a jump either is a seek for both of them or for neither.
     */
    private const val SEEK_TOLERANCE_MS = 3_000L

    /**
     * Buffer-ahead change within this band over a whole window counts as flat.
     *
     * Flat is the one shape that says nothing. **Shrinking is not flat** and is a perfectly
     * good measurement - a buffer draining while playback advances is the line failing to keep
     * up, which is precisely the reading a monotonic lower bound can never produce.
     */
    private const val MIN_BUFFER_GROWTH_MS = 250L

    /**
     * Consecutive uninformative windows before the meter stops.
     *
     * A full buffer back-pressures the transfer down to the file's own bitrate, so windows
     * past that point measure the *file*, not the line. They are not evidence of a slow
     * connection and must not be allowed to look like it.
     */
    private const val SATURATED_WINDOWS_BEFORE_STOP = 2

    /**
     * Above this, believe the buffer readout rather than the arithmetic and throw the window
     * away. A single glitched `bufferedPositionMs` on a 40 Mbps file implies an absurd line
     * speed, and one poisoned sample outlives the playback it came from.
     */
    private const val IMPLAUSIBLE_MBPS = 2_000.0

    /** Carried by the caller across samples. Start from [initial] on every new source. */
    data class State(
        val windowStart: Sample? = null,
        val bestMbps: Double? = null,
        val saturatedWindows: Int = 0,
        val isFinished: Boolean = false,
    )

    data class Sample(
        /** Monotonic wall clock, e.g. `TimeSource`/`nanoTime` based - not epoch time. */
        val elapsedRealtimeMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val isEnded: Boolean,
    ) {
        val bufferedAheadMs: Long get() = (bufferedPositionMs - positionMs).coerceAtLeast(0L)
    }

    /**
     * [measuredMbps] is non-null when this window beat every earlier one, **or** when the
     * buffer drained during it.
     *
     * The maximum is otherwise the honest reading: emitting every window would feed the
     * repository's blend a run of flat windows sitting at the file's own bitrate and teach it
     * that the line is exactly as fast as whatever the user last watched - the same drift
     * `recordSustainedBitrate`'s monotonicity exists to avoid, arriving by the other door.
     *
     * A draining buffer is the exception because it is not merely a lower reading: it is direct
     * evidence the line *cannot* sustain what is playing, and suppressing it for being below an
     * earlier burst is how an estimate survives being disproved.
     */
    data class Outcome(val state: State, val measuredMbps: Double?)

    fun initial(): State = State()

    /**
     * Folds one player snapshot into [state].
     *
     * [fileBitrateMbps] is the playing file's own bitrate; without it a buffer delta is a
     * duration and converts to nothing. Callers that cannot compute one should keep using
     * [NetworkQualityRepository.recordSustainedBitrate], which needs no conversion.
     */
    fun observe(state: State, sample: Sample, fileBitrateMbps: Double): Outcome {
        if (state.isFinished) return Outcome(state, measuredMbps = null)
        if (!fileBitrateMbps.isFinite() || fileBitrateMbps <= 0.0) {
            return Outcome(state, measuredMbps = null)
        }
        if (sample.isEnded) return Outcome(state.copy(isFinished = true), measuredMbps = null)

        val start = state.windowStart
            ?: return Outcome(state.copy(windowStart = sample), measuredMbps = null)

        val elapsedMs = sample.elapsedRealtimeMs - start.elapsedRealtimeMs
        // A seek moves the position discontinuously and rebuilds the buffer from the new
        // point, so the two deltas stop describing the same stretch of file. Restart rather
        // than discard the meter: the window after a seek is another startup fill, which is
        // the best measurement this meter gets.
        if (isDiscontinuous(start, sample, elapsedMs)) {
            return Outcome(state.copy(windowStart = sample), measuredMbps = null)
        }
        if (elapsedMs < MIN_WINDOW_MS) return Outcome(state, measuredMbps = null)

        val deliveredMs = sample.bufferedPositionMs - start.bufferedPositionMs
        val grewMs = sample.bufferedAheadMs - start.bufferedAheadMs
        // A buffer sitting still while playback advances is the *player* declining to ask for
        // more, not the line failing to supply it, and reporting the file's own bitrate as the
        // connection speed is how the estimate drifts down to whatever was last watched. A
        // buffer that is *draining* is the opposite - the line is the bottleneck and the rate
        // is real - so only the flat band is refused. Nothing arriving at all is a stall, which
        // says something about the source rather than the connection.
        val isFlat = grewMs > -MIN_BUFFER_GROWTH_MS && grewMs < MIN_BUFFER_GROWTH_MS
        if (deliveredMs <= 0L || isFlat) {
            val saturated = state.saturatedWindows + 1
            return Outcome(
                state.copy(
                    windowStart = sample,
                    saturatedWindows = saturated,
                    isFinished = saturated >= SATURATED_WINDOWS_BEFORE_STOP,
                ),
                measuredMbps = null,
            )
        }

        val mbps = fileBitrateMbps * deliveredMs.toDouble() / elapsedMs.toDouble()
        if (!mbps.isFinite() || mbps <= 0.0 || mbps > IMPLAUSIBLE_MBPS) {
            return Outcome(state.copy(windowStart = sample), measuredMbps = null)
        }

        val isBest = state.bestMbps == null || mbps > state.bestMbps
        val isDraining = grewMs <= -MIN_BUFFER_GROWTH_MS
        return Outcome(
            state.copy(
                windowStart = sample,
                bestMbps = if (isBest) mbps else state.bestMbps,
                saturatedWindows = 0,
            ),
            measuredMbps = mbps.takeIf { isBest || isDraining },
        )
    }

    /**
     * Whether the stream jumped rather than played between these two samples.
     *
     * Backwards on either axis is unambiguous. Forwards, the position may not outrun the
     * window itself by more than the seek tolerance - playback advances at wall-clock rate
     * (speed changes aside, which read as a seek here and cost one window).
     */
    private fun isDiscontinuous(start: Sample, sample: Sample, elapsedMs: Long): Boolean =
        elapsedMs < 0L ||
            sample.positionMs < start.positionMs ||
            sample.bufferedPositionMs < start.bufferedPositionMs ||
            sample.positionMs - start.positionMs > elapsedMs + SEEK_TOLERANCE_MS
}
