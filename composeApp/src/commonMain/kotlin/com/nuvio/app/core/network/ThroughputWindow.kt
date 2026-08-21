package com.nuvio.app.core.network

// No imports, and none may be added. Every platform's `httpMeasureThroughput` feeds this, so it
// is the one place the arithmetic lives - and it is the only part of the measurement that can be
// executed outside Gradle (`AGENTS.md`, "Verifying without Gradle"). A rate computed three times
// in three actuals is a rate that will eventually be computed three different ways.

/**
 * What the line carried, measured two ways from one pass over the transfer.
 *
 * [sustainedMbps] is **the figure to record**: the median rate over byte-partitioned blocks taken
 * past the congestion-window ramp. [peakMbps] is the best rate over any one window, kept because
 * the readers need a running upper bound for their `stopAboveMbps` early exit and because it is
 * worth logging beside the median - a large gap between the two is a bursty link, which is
 * information. Neither is the whole-transfer mean, and the note below is why.
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

    // Retained for [sustainedMbps], and deliberately *not* subject to the window pruning below:
    // that prune throws away everything the peak can no longer use, which is precisely the
    // history a whole-transfer statistic needs.
    //
    // Primitive arrays rather than `ArrayDeque<Long>` because this is on the read loop and a
    // deque of a boxed type allocates twice per sample. Taken every `checkpointStride` bytes
    // rather than every sample for the same reason - at 64 KiB that is one per read on all
    // three actuals early on, which is the finest resolution the readers can offer anyway.
    private val checkpointMs = LongArray(MAX_CHECKPOINTS)
    private val checkpointBytes = LongArray(MAX_CHECKPOINTS)
    private var checkpointCount = 0
    private var checkpointStride = CHECKPOINT_STRIDE_BYTES

    // The last sample of all, which is not necessarily a checkpoint: a transfer that ends in a
    // stall closes on a sample carrying no new bytes, and that sample is where the region's span
    // ends even though it advances no byte boundary.
    private var lastMs = -1L
    private var lastBytes = -1L

    /**
     * Megabits per second over the best window seen so far, or null while none has closed.
     *
     * ⚠ **This is an upper bound, and it is read live by the `stopAboveMbps` early exit.** It is
     * not the figure to record - see [sustainedMbps], which exists because this one over-reads.
     */
    val peakMbps: Double? get() = best

    /**
     * The median rate over byte-partitioned blocks past the ramp. **This is the figure to record.**
     *
     * **The defect this exists for.** [peakMbps] is a *maximum* over sliding windows, and a
     * maximum is not an estimator: every source of jitter raises it without moving the true rate.
     * On Wi-Fi, where 802.11 aggregation makes short-window throughput bursty, a 250 ms peak
     * routinely sits 20-40% over the sustained rate. Worse, the readers timestamp bytes when
     * `read()` returns rather than when they arrive, so a reader thread descheduled for 40-60 ms
     * lets the kernel receive queue fill and then drain at memory speed - and those bytes, which
     * arrived *before* the window that counts them, land in whichever window the sliding maximum
     * is hunting for. An autotuned 4 MB receive buffer at 500 Mb/s is ~64 ms of data; dropped
     * into a 250 ms window that is +26% on its own. The reported case: a sheet showing
     * **538 Mb/s** on a line Ookla measured at **416**, and a single TCP stream reading *above* a
     * multi-stream test is the tell - single-stream should read at or below it.
     *
     * The window was still right to replace the whole-transfer mean, which carries TCP slow start
     * and under-reads by more the faster the line is. But the remedy for "the ramp is in my
     * average" is to *exclude the ramp*, not to take a maximum. The maximum fixed the under-read
     * and overshot past it. This excludes the ramp instead.
     *
     * **Blocks are partitioned by bytes, never by time, and that is the load-bearing choice.**
     * A block is a fixed number of bytes, timed between the first sample reaching each boundary:
     *
     * - A **trailing stall contributes no bytes to any block**, so it is excluded by construction
     *   rather than by a rule. That is what keeps a stall from becoming the answer here, exactly
     *   as it never became the answer for [peakMbps] - see `aBriefStallDoesNotBecomeTheAnswer`.
     * - A **mid-transfer stall** makes exactly one block slow, and the median discards it.
     * - A **receive-buffer drain** makes exactly one block fast, and the median discards it. That
     *   is the bias being removed.
     *
     * Null when the transfer was too small to hold [MIN_BLOCKS] blocks - the same honest null
     * [peakMbps] reports, and the caller falls back the same way.
     */
    val sustainedMbps: Double? get() = blockMedianMbps()

    fun record(elapsedMs: Long, cumulativeBytes: Long) {
        if (elapsedMs < 0L || cumulativeBytes < 0L) return
        recordCheckpoint(elapsedMs, cumulativeBytes)

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

    private fun recordCheckpoint(elapsedMs: Long, cumulativeBytes: Long) {
        lastMs = elapsedMs
        lastBytes = cumulativeBytes
        if (checkpointCount == 0) {
            checkpointMs[0] = elapsedMs
            checkpointBytes[0] = cumulativeBytes
            checkpointCount = 1
            return
        }
        if (cumulativeBytes - checkpointBytes[checkpointCount - 1] < checkpointStride) return
        // Halve the history and double the stride rather than growing. Keeping every other entry
        // is uniform, so what survives stays evenly spaced however often this runs, and index 0 -
        // the transfer's start - is always among them. At the shipped budget this never fires:
        // MAX_CHECKPOINTS * CHECKPOINT_STRIDE_BYTES is exactly `NetworkStrengthProbe.MAX_BYTES`.
        if (checkpointCount == MAX_CHECKPOINTS) {
            var write = 1
            var read = 2
            while (read < checkpointCount) {
                checkpointMs[write] = checkpointMs[read]
                checkpointBytes[write] = checkpointBytes[read]
                write += 1
                read += 2
            }
            checkpointCount = write
            checkpointStride *= 2L
        }
        checkpointMs[checkpointCount] = elapsedMs
        checkpointBytes[checkpointCount] = cumulativeBytes
        checkpointCount += 1
    }

    private fun blockMedianMbps(): Double? {
        if (checkpointCount < 2 || lastBytes <= 0L) return null
        // The ramp's byte cost scales with the bandwidth-delay product, and so does a proportional
        // skip - which is why this is a fraction and not a fixed number of bytes. A fixed 256 kB
        // would leave the ramp inside the blocks at 500 Mb/s; a fixed 4 MiB would swallow a
        // 5 Mb/s transfer whole.
        val skip = lastBytes / RAMP_SKIP_DIVISOR
        val startIndex = firstIndexReaching(skip, from = 0)
        if (startIndex < 0) return null

        // **Admitted on exactly the evidence one window needs.** A closed window carried
        // [minWindowBytes] and spanned [windowMs]; so must the region these blocks partition.
        // That keeps the two statistics measuring the same transfers - so the precedence that
        // prefers this one is a straight comparison rather than a wider net - and it is what
        // stops a partition closing over a stretch so brief that millisecond quantisation alone
        // moves each block by a tenth.
        val regionBytes = lastBytes - checkpointBytes[startIndex]
        val regionMs = lastMs - checkpointMs[startIndex]
        if (regionBytes < minWindowBytes || regionMs < windowMs) return null

        val blockBytes = regionBytes / BLOCK_COUNT
        if (blockBytes <= 0L) return null
        val rates = DoubleArray(BLOCK_COUNT)
        var measured = 0
        var previousIndex = startIndex
        var block = 1
        while (block <= BLOCK_COUNT) {
            // Absolute targets, never chained off the previous block's actual end: snapping a
            // boundary onto the next checkpoint overshoots slightly, and chaining lets that
            // overshoot accumulate until the last block has no bytes left to cover.
            val endIndex = firstIndexReaching(
                bytes = checkpointBytes[startIndex] + blockBytes * block,
                from = previousIndex,
            )
            if (endIndex < 0) break
            val spanMs = checkpointMs[endIndex] - checkpointMs[previousIndex]
            val bytes = checkpointBytes[endIndex] - checkpointBytes[previousIndex]
            // A block that closed inside one millisecond cannot be timed. Dropping it costs one
            // sample of an eight-sample median; refusing the whole reading over it would not.
            if (spanMs > 0L && bytes > 0L) {
                rates[measured] = bytes.toDouble() * 8.0 / spanMs.toDouble() / 1_000.0
                measured += 1
            }
            previousIndex = endIndex
            block += 1
        }
        if (measured < MIN_BLOCKS) return null

        val ordered = rates.copyOf(measured)
        ordered.sort()
        // The **lower** median on an even count, not the mean of the middle pair. This figure
        // decides what plays; a tie broken upward is a tie broken towards a stall.
        return ordered[(measured - 1) / 2]
    }

    /** The first checkpoint at or after [from] whose cumulative count has reached [bytes]. */
    private fun firstIndexReaching(bytes: Long, from: Int): Int {
        var index = from
        while (index < checkpointCount) {
            if (checkpointBytes[index] >= bytes) return index
            index += 1
        }
        return -1
    }

    companion object {
        /**
         * The share of the transfer thrown away as congestion-window ramp.
         *
         * Slow start doubles, so it is over in a few round trips and costs a small bounded share
         * of a byte-capped transfer: reaching a ~1 MB bandwidth-delay product from IW10 takes
         * about 7 round trips and ~1.9 MB, under 6% of a 32 MiB pull. An eighth clears that at
         * every speed worth measuring, and on a slow line - where the transfer is stopped by the
         * clock rather than the byte cap - an eighth of 2.5 s is 312 ms, comfortably past the
         * ramp there too.
         */
        const val RAMP_SKIP_DIVISOR: Long = 8L

        /**
         * How many blocks the region is cut into.
         *
         * A median of eight tolerates three contaminated blocks either side. At the 32 MiB budget
         * each block is 3.5 MiB - 3.5x [DEFAULT_MIN_WINDOW_BYTES], and ~54 ms at 538 Mb/s; at the
         * 2.5 s clock bound and 5 Mb/s each is 170 kB and 273 ms. Four would make "the median" a
         * two-sample average with no outlier tolerance, and sixteen would put a 27 ms block close
         * enough to millisecond quantisation to matter.
         *
         * The region floor is [minWindowBytes], so a block can never be smaller than an eighth of
         * a mebibyte - about 90 full segments, which is why no single coalesced read moves one.
         */
        const val BLOCK_COUNT: Int = 8

        /**
         * Below this there is no median worth the name, so the reading is refused outright.
         *
         * The partition nominally yields [BLOCK_COUNT]; snapping block ends onto retained
         * checkpoints can cost the last one or two on a coarse trace. Four is the smallest count
         * at which an outlier is still discarded by taking the middle, which is the entire reason
         * this statistic is a median.
         */
        const val MIN_BLOCKS: Int = 4

        /**
         * How often a checkpoint is taken, and how many are kept.
         *
         * 64 KiB is `READ_CHUNK_BYTES` on all three actuals, so early in a transfer every read is
         * a checkpoint and the partition snaps at the finest resolution the readers can offer.
         * `512 * 64 KiB` is exactly `NetworkStrengthProbe.MAX_BYTES`, so an unmetered probe fills
         * the arrays once and never decimates; the decimation path is there for a longer transfer
         * or a raised budget. 2 x 512 longs is 8 KiB, allocated once.
         */
        const val CHECKPOINT_STRIDE_BYTES: Long = 64L * 1024L
        const val MAX_CHECKPOINTS: Int = 512

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
