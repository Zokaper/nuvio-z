package com.nuvio.app.core.network

// No imports, and none may be added. Every platform's `httpMeasureThroughput` feeds this, so it
// is the one place the arithmetic lives - and it is the only part of the measurement that can be
// executed outside Gradle (`AGENTS.md`, "Verifying without Gradle"). A rate computed three times
// in three actuals is a rate that will eventually be computed three different ways.

/**
 * The best rate sustained over any window of the transfer, rather than the mean over all of it.
 *
 * **The defect this exists for.** A ranged GET's mean rate includes TCP slow start, and on a
 * short pull that ramp is most of the transfer: the congestion window doubles its way up to the
 * bandwidth-delay product, so the opening stretch runs nowhere near the line's real speed. The
 * mean of a ramp is a measurement of the ramp. Worse, it under-reads *more* the faster the
 * connection is - a fast line reaches its byte cap while still climbing - which is precisely
 * backwards for the question the quality sheet asks it. The reported case: a connection shown as
 * 57 Mb/s, with every 4K row warned against, streaming an 81 Mbps remux without a stall.
 *
 * Excluding time-to-first-byte, which the callers already do, removes the handshake and leaves
 * the ramp untouched. Only a window does that.
 *
 * **A window is bounded by bytes as well as by time, and the byte bound is the load-bearing one.**
 * The first cut required only a fixed 750 ms, which no transfer the probe could afford ever
 * contained: the neutral endpoint serves 4 MiB, so above ~44 Mb/s the whole pull finished inside
 * one window, [peakMbps] stayed null, and every caller fell back to the very mean this class
 * exists to replace. That is not a tuning miss, it is the wrong invariant - 750 ms was chosen so
 * that one delayed packet could not inflate the figure, and *that* is a statement about how many
 * bytes the window holds, not about how long it lasts. Stated in bytes it works at both ends: a
 * fast line closes a window inside a budget it can afford (250 ms at 100 Mb/s is 3 MB, thousands
 * of packets), and a slow line stretches its window until it has enough bytes to be steady.
 *
 * Feed [record] a cumulative byte count and the milliseconds since the first byte. Sample count
 * is bounded by the transfer's chunk count - about 512 for a 32 MiB body - so the scan below is
 * not worth indexing.
 */
class ThroughputWindow(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minWindowBytes: Long = DEFAULT_MIN_WINDOW_BYTES,
) {

    private val elapsed = ArrayDeque<Long>()
    private val cumulative = ArrayDeque<Long>()
    private var best: Double? = null

    /** Megabits per second over the best window seen so far, or null while none has closed. */
    val peakMbps: Double? get() = best

    fun record(elapsedMs: Long, cumulativeBytes: Long) {
        if (elapsedMs < 0L || cumulativeBytes < 0L) return
        elapsed.addLast(elapsedMs)
        cumulative.addLast(cumulativeBytes)

        // The newest start point far enough back to close a window. Newest rather than oldest so
        // the span stays close to `windowMs`: measuring across the whole transfer would drag the
        // ramp back in, which is the thing being escaped.
        //
        // Both bounds are monotone as `index` walks older - the span only grows and the byte
        // delta only grows - so the first index satisfying both, scanning newest to oldest, is
        // the newest qualifying one and the scan can still stop there.
        var index = elapsed.size - 2
        while (index >= 0) {
            val span = elapsedMs - elapsed[index]
            val bytes = cumulativeBytes - cumulative[index]
            if (span >= windowMs && bytes >= minWindowBytes) {
                val rate = bytes.toDouble() * 8.0 / span.toDouble() / 1_000.0
                if (rate > (best ?: 0.0)) best = rate
                // Everything older can only produce a longer span, which is a worse window.
                break
            }
            index -= 1
        }

        // Nothing before the cutoff can ever be the *newest* qualifying start again, except the
        // single most recent one, which is still needed to close the next window. Pruning has to
        // test the same pair of bounds as the scan: dropping an entry that is old enough but has
        // not yet carried `minWindowBytes` would discard the only start point a slow line can
        // ever close a window from.
        while (
            elapsed.size > 2 &&
            elapsedMs - elapsed[1] >= windowMs &&
            cumulativeBytes - cumulative[1] >= minWindowBytes
        ) {
            elapsed.removeFirst()
            cumulative.removeFirst()
        }
    }

    companion object {
        /**
         * The floor on a window's duration.
         *
         * Was 750 ms, which could not close inside any transfer the probe was willing to pay
         * for - see the class note. 250 ms is short enough that a 32 MiB budget holds several
         * windows past the ramp at 300 Mb/s, and the "one late packet must not matter" property
         * it used to carry alone is now held by [DEFAULT_MIN_WINDOW_BYTES].
         */
        const val DEFAULT_WINDOW_MS: Long = 250L

        /**
         * The floor on a window's size, which is what actually makes the figure steady.
         *
         * A mebibyte is ~700 full-size segments, so no single delayed or coalesced read can move
         * the rate meaningfully. On a slow line this is the binding constraint and the window
         * simply lasts longer than [DEFAULT_WINDOW_MS]; that is the intended behaviour, not a
         * fallback.
         */
        const val DEFAULT_MIN_WINDOW_BYTES: Long = 1024L * 1024L
    }
}
