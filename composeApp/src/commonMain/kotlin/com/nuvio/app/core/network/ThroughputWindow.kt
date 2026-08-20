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
 * Feed [record] a cumulative byte count and the milliseconds since the first byte. Sample count
 * is bounded by the transfer's chunk count - about 128 for an 8 MiB body - so the scan below is
 * not worth indexing.
 */
class ThroughputWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

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
        var index = elapsed.size - 2
        while (index >= 0) {
            val span = elapsedMs - elapsed[index]
            if (span >= windowMs) {
                val rate = (cumulativeBytes - cumulative[index]).toDouble() * 8.0 /
                    span.toDouble() / 1_000.0
                if (rate > (best ?: 0.0)) best = rate
                // Everything older can only produce a longer span, which is a worse window.
                break
            }
            index -= 1
        }

        // Nothing before the cutoff can ever be the *newest* qualifying start again, except the
        // single most recent one, which is still needed to close the next window.
        while (elapsed.size > 2 && elapsedMs - elapsed[1] >= windowMs) {
            elapsed.removeFirst()
            cumulative.removeFirst()
        }
    }

    companion object {
        /**
         * Long enough that one delayed packet cannot inflate the figure, short enough that a
         * 2-3 second budget holds several windows and the ramp occupies only the first.
         */
        const val DEFAULT_WINDOW_MS: Long = 750L
    }
}
