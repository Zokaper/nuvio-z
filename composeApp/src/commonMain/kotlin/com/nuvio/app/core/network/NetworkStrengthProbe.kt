package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpMeasureThroughput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A bounded ranged GET, so the first play on a network is not decided by a guess.
 *
 * Everything else that feeds [NetworkQualityRepository] needs playback or a download to have
 * already happened, which is exactly the moment the quality sheet cannot wait for: it is asking
 * the user to choose a bitrate *before* anything has been streamed. This is the only signal
 * available at that point.
 *
 * **It measures the source, not the line.** When the option the user is about to play has a
 * direct URL, the probe pulls from that host with that source's own request headers, and the
 * answer is cached under the host's provider id. Throughput on debrid belongs to the provider,
 * and a fast Wi-Fi behind a slow host that read as "4K is fine" is the single mistake that would
 * make an automatic pick feel worse than choosing by hand. Only when there is no direct URL to
 * pull from - the link still needs minting, or it is a manifest - does it fall back to a neutral
 * CDN, and that result is stored against no provider at all.
 *
 * **No debrid link is ever minted to run a probe.** A measurement is not worth spending one of
 * the user's link resolutions on, and a minted-but-unplayed link is exactly the churn the
 * playback stack is built to avoid.
 *
 * **It runs on metered connections as well.** Skipping them left mobile data - the connection
 * whose speed varies most - as the one case still decided by a preset, which is the fault this
 * whole path exists to remove.
 *
 * [plan] is pure and holds every rule about whether and what to measure; [measure] is the only
 * part that touches the network.
 */
object NetworkStrengthProbe {

    /**
     * The byte ceiling, which is what stops a **fast** line.
     *
     * A windowed rate needs its window to fit inside the transfer *past the ramp*, so this is
     * sized in bytes against the fastest line worth distinguishing, not in seconds. 32 MiB is
     * 268 Mb: ~0.9 s at 300 Mb/s, which is a quarter second of congestion window ramp and then
     * two and a half 250 ms windows. The reading stays honest to roughly 500 Mb/s, past which
     * the transfer no longer clears the ramp and the figure floors out - which costs nothing,
     * because nothing in any catalogue needs more than ~160.
     *
     * The predecessors were both too small to work at all. 4 MiB, then 8 MiB, were sized as if
     * more bytes merely diluted a mean - but 8 MiB is 66.6 Mb, under one 750 ms window above
     * ~89 Mb/s, so the faster the line the more certainly the window failed to close and the
     * caller fell back to the ramp-contaminated mean. That is the fault this file exists to fix,
     * and it survived the first attempt at fixing it.
     */
    const val MAX_BYTES = 32L * 1024L * 1024L

    /**
     * The ceiling on a metered connection.
     *
     * Half the budget. 16 MiB is 134 Mb, so the reading is honest to roughly 270 Mb/s - past
     * anything a metered link sustains - while the allowance spent stays a fraction of the video
     * it is about to choose. Metered is also the only case that stops early, so in practice it
     * usually spends far less than this: see [plan].
     *
     * Skipping metered links entirely was the earlier answer and was worse: mobile data is the
     * connection whose speed varies most, so it became the one case decided purely by a preset.
     */
    const val METERED_MAX_BYTES = 16L * 1024L * 1024L

    /**
     * The time ceiling, which is what stops **everyone else** - and that is most people.
     *
     * The byte cap only binds above ~107 Mb/s (268 Mb in 2.5 s); below that this is the real
     * stop, so it, not [MAX_BYTES], decides what an ordinary connection spends: ~22 MB at
     * 72 Mb/s, ~1.5 MB at 5.
     *
     * 2.5 s rather than 3 buys nothing on a fast line and saves a fifth of the transfer on a
     * middling one. The floor on it is the slow end: a window needs
     * [ThroughputWindow.DEFAULT_MIN_WINDOW_BYTES], which takes 2.1 s to arrive at 4 Mb/s, so
     * cutting this much further would push slow lines onto the mean. That is a mild loss - slow
     * start is a small fraction of a multi-second transfer, which is exactly why the mean was
     * only ever wrong at the top end - but there is no reason to take it.
     */
    const val MAX_TRANSFER_MS = 2_500L

    /**
     * How long anything waiting on a measurement should wait before giving up on it.
     *
     * Nothing else bounds this. [MAX_TRANSFER_MS] only stops the read *loop*, and the underlying
     * clients allow 60 s for a single read, so a host that answers its headers and then goes
     * quiet stalls the whole probe - and the quality sheet now withholds its figure until this
     * probe settles, which turns a slow host into a surface stuck on "Checking your
     * connection…". Comfortably past [MAX_TRANSFER_MS] plus a slow debrid TTFB.
     *
     * ⚠ **Applying it here does not on its own bound a caller, and callers must not assume it
     * does.** The Android and desktop readers block in `InputStream.read`, which coroutine
     * cancellation cannot interrupt, so `withTimeoutOrNull` around them only returns once the
     * read does. It is a real bound on iOS, whose reader genuinely suspends, and it is what
     * stops a *stalled* probe from being recorded anywhere. Anything whose UI depends on this
     * probe finishing needs its own timer racing it - see `connectionSettledNonce` in `App.kt`.
     */
    const val PROBE_DEADLINE_MS = 5_000L

    /**
     * How recent an estimate has to be for the probe to skip entirely.
     *
     * A measurement from four minutes ago against the same host is better evidence than a fresh
     * 4 MB one, and re-probing every time the sheet opens would charge the user repeatedly for
     * an answer already in hand.
     */
    const val FRESH_ESTIMATE_MS = 10L * 60L * 1_000L

    /**
     * The largest `?bytes=` the neutral endpoint will serve.
     *
     * Measured, not assumed: 96,000,000 answers 200 and 100,000,000 answers **403**, so the
     * ceiling sits just under 1e8. The first fix for the under-reading asked for 128 MB on the
     * theory that a body far larger than the budget could only help, and got a 403 on every
     * probe - which `httpMeasureThroughput` reports as a zero-byte sample, so the probe recorded
     * nothing at all and the stale estimate survived exactly as it had before. Silent, and
     * indistinguishable from the original fault.
     */
    const val CDN_ENDPOINT_MAX_BYTES = 100_000_000L

    /**
     * Neutral fallback. Records against no provider - it describes the line and nothing else.
     *
     * ⚠ **Two bounds, and breaking either one is silent.** The body must be larger than
     * [MAX_BYTES] and smaller than [CDN_ENDPOINT_MAX_BYTES]; 64 MiB is double the budget with
     * room to spare below the cap. Under the budget, `?bytes=` *becomes* the budget and raising
     * [MAX_BYTES] changes nothing - that is how a 4 MiB body under an 8 MiB cap made every
     * reading a 585 ms pull too short to hold a window, reading 56 Mb/s on a line carrying 211.
     * Over the cap, the endpoint 403s and nothing is recorded at all. Both failures look
     * identical from the outside: a figure that will not update.
     *
     * Note the endpoint **ignores `Range`** - it returns 200 with the whole body - so the size
     * here is the only thing bounding what the server starts sending. The reader still stops at
     * [MAX_BYTES] and closes the stream, so the extra is never pulled.
     */
    const val CDN_FALLBACK_URL = "https://speed.cloudflare.com/__down?bytes=67108864"

    /**
     * Floors for the **mean**, which is the fallback figure and the only one that needs guarding.
     *
     * A closed window is self-validating: it spanned [ThroughputWindow.DEFAULT_WINDOW_MS] and
     * carried [ThroughputWindow.DEFAULT_MIN_WINDOW_BYTES] by construction - and so did a closed
     * partition, which is admitted on those same two floors. Applying these to either is what
     * discarded the fast-line samples the window was added to rescue: a 4 MiB pull above
     * ~83 Mb/s finishes inside 400 ms, so the probe threw its own answer away and the stale
     * estimate survived, which is what "it won't update" looked like from outside.
     */
    private const val MIN_SAMPLE_BYTES = 512L * 1024L
    private const val MIN_SAMPLE_MS = 400L

    /**
     * Stop once the running rate clears the option's requirement by this much. Proving a fast
     * line is fast does not need the whole budget, and on a metered-adjacent connection the
     * bytes not spent are the point.
     */
    private const val EARLY_EXIT_MARGIN = 1.5

    /**
     * The early exit may never be set below this, whatever the sheet is asking for.
     *
     * ⚠ **The rate a probe stops at is the rate it records, and that figure is persisted and read
     * by everything else** - `resolutionForEstimate` picks a download preset from it, and the next
     * sheet on the same network reads it back for ten minutes. Scaling the exit to the options
     * currently on screen therefore looked like a saving and was a trap: a title whose most
     * expensive release is a 5 Mb/s 720p encode would have stopped the probe at 7.5 and written
     * "your connection: 8 Mb/s" for a line carrying 200.
     *
     * 200 Mb/s is comfortably past the most expensive thing in any catalogue - a ~120 Mb/s remux
     * needs ~160 with headroom - so an exit here can never depress a figure any *decision* depends
     * on. It can still depress the figure the user is **shown**, which is why it is metered-only:
     * see [plan].
     */
    private const val EARLY_EXIT_FLOOR_MBPS = 200.0

    /** Everything [plan] needs, as plain values, so the rules are testable without a network. */
    data class Inputs(
        /**
         * Sets the byte ceiling ([METERED_MAX_BYTES] rather than [MAX_BYTES]); it does not
         * suppress the probe. See the note on [plan].
         */
        val isMetered: Boolean,
        val isOffline: Boolean,
        /**
         * Age of the estimate stored under the source's **own host**, or null if that host has
         * never been measured. Only consulted when the probe would actually pull from that host.
         */
        val sourceEstimateAgeMs: Long?,
        /**
         * Age of the line-wide estimate - the one a CDN probe writes and reads. Consulted
         * whenever the probe falls back to the neutral endpoint, or when the source it would
         * pull from carries no provider to file the answer under.
         */
        val lineEstimateAgeMs: Long?,
        /** The top option's playable direct URL, when it has one that is worth pulling bytes from. */
        val sourceUrl: String?,
        val sourceHeaders: Map<String, String> = emptyMap(),
        val providerId: String? = null,
        /**
         * What the **most expensive** option on the sheet needs, for the early exit.
         *
         * Not the first option: that is Best available, whose `requiredMbps` is null by
         * construction, so feeding it here left [Plan.stopAboveMbps] null on every probe the
         * shipped app has ever run and the early exit was dead code.
         */
        val requiredMbps: Double? = null,
        /**
         * Skips the freshness gate. Set only when the user has explicitly asked to re-measure -
         * they are saying they do not believe the stored figure, and answering with it again is
         * not a reply.
         */
        val force: Boolean = false,
    )

    data class Plan(
        val url: String,
        val headers: Map<String, String>,
        /** Null for the CDN target: a neutral endpoint must never speak for a provider. */
        val providerId: String?,
        val stopAboveMbps: Double?,
        /** [MAX_BYTES], or [METERED_MAX_BYTES] on a metered link. */
        val maxBytes: Long,
        /** True when this plan came from an explicit re-test. See [Inputs.force]. */
        val isForced: Boolean,
    )

    private val logger = Logger.withTag("NetworkStrengthProbe")

    private val _isProbing = MutableStateFlow(false)

    /**
     * True while a transfer is in flight. The single-flight guard, and a diagnostic.
     *
     * ⚠ **Not the signal for "is a figure still coming".** It goes true only once the transfer
     * starts, which is well after a caller decides to measure, so a UI driven by this shows its
     * old figure first and swaps it when the flow finally flips - which is exactly the fault the
     * quality sheet was reported for. Ask [plan] instead: a non-null plan means a measurement is
     * going to happen, and it is knowable before anything is launched.
     */
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    fun plan(inputs: Inputs): Plan? {
        if (inputs.isOffline) return null

        // **Metered only, and that is the whole rule.** The exit is a thrift measure - it exists
        // so proving a fast line is fast need not spend the user's allowance - and thrift is
        // only worth a worse number where the bytes cost something.
        //
        // On an unmetered line it was worse than useless. The rate a probe stops at is the rate
        // it records, so an exit at 200 Mb/s writes ~200 for a line doing 380, and the user is
        // shown a figure capped by the measurement rather than by the connection. That is the
        // same complaint this whole path exists to answer, arriving from the other direction -
        // and it buys about half a second on a budget that already costs under a second there.
        //
        // Never below the floor even when it does apply: scaling it to whatever is on screen
        // would let a title whose most expensive release is a 5 Mb/s encode stop the probe at
        // 7.5 and write "your connection: 8 Mb/s".
        val stopAbove = if (inputs.isMetered) {
            maxOf(
                inputs.requiredMbps
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { it * EARLY_EXIT_MARGIN }
                    ?: 0.0,
                EARLY_EXIT_FLOOR_MBPS,
            )
        } else {
            null
        }
        val sourceUrl = inputs.sourceUrl?.trim()?.takeIf(::isProbeableUrl)

        // **The freshness question is about the key this probe would write, not the one the
        // sheet would read.** A source probe refreshes its host's entry; a CDN probe refreshes
        // the line-wide one. Asking the wrong one breaks in both directions: check the line's
        // age before a host probe and a new debrid host is never measured at all, check the
        // host's age before a CDN probe and - since a CDN result is filed under no provider -
        // that host's entry stays empty forever and the sheet re-probes on every single open.
        val writesToHost = sourceUrl != null && inputs.providerId != null
        val relevantAgeMs = if (writesToHost) inputs.sourceEstimateAgeMs else inputs.lineEstimateAgeMs
        // **Metered connections are probed too.** The first cut skipped them to protect the
        // user's allowance, and the result was that mobile data - the connection whose speed
        // varies most and matters most - was the one case decided entirely by a preset. At
        // 4 MiB, and only once the stored answer has gone stale, the measurement costs a
        // fraction of the first few seconds of the video it is about to choose.
        if (!inputs.force && relevantAgeMs != null && relevantAgeMs < FRESH_ESTIMATE_MS) return null

        val maxBytes = if (inputs.isMetered) METERED_MAX_BYTES else MAX_BYTES

        return if (sourceUrl != null) {
            Plan(
                url = sourceUrl,
                headers = inputs.sourceHeaders,
                providerId = inputs.providerId,
                stopAboveMbps = stopAbove,
                maxBytes = maxBytes,
                isForced = inputs.force,
            )
        } else {
            Plan(
                url = CDN_FALLBACK_URL,
                headers = emptyMap(),
                // Deliberately null even when a provider is known. This endpoint says nothing
                // about that host, and filing it under one would let a fast CDN vouch for a
                // slow debrid.
                providerId = null,
                stopAboveMbps = stopAbove,
                maxBytes = maxBytes,
                isForced = inputs.force,
            )
        }
    }

    /**
     * Whether pulling bytes from this URL would measure anything.
     *
     * A manifest is a few kilobytes of text pointing at segments elsewhere, so a ranged GET
     * against one measures the playlist server and finishes below the sample floor. Magnets and
     * torrent URLs are not HTTP byte pipes at all.
     */
    fun isProbeableUrl(url: String?): Boolean {
        val normalized = url?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        return ".m3u8" !in normalized && ".mpd" !in normalized && ".torrent" !in normalized
    }

    /**
     * Runs [inputs]' plan, if there is one, and records the result.
     *
     * Returns the measured Mbps, or null when nothing was measured - no plan, a failed request,
     * or a sample too small to mean anything. The estimate simply stays where it was, and the
     * caller keeps whatever confidence it already had.
     *
     * **When this returns, a measurement has settled.** That is the contract callers gate a UI
     * on, and it is why a second caller now *waits* for an in-flight probe instead of being
     * refused. Refusing returned null immediately, which is indistinguishable from "measured and
     * found nothing" - so a quality sheet withholding its figure until the probe finished got a
     * false "finished" a millisecond after the user asked, and committed to the stale number
     * while the real measurement was still running.
     *
     * A failure is no longer silent. Not being able to measure is a normal outcome, but it looks
     * exactly like the fault this whole path exists to fix - a figure that will not update - so
     * it has to be findable in a log rather than inferred from an estimate that did not move.
     */
    suspend fun probe(inputs: Inputs): Double? {
        // Wait rather than refuse, then re-plan: whatever the first probe recorded may have made
        // this one unnecessary, and `plan` is the only thing that gets to decide that.
        if (_isProbing.value) _isProbing.first { !it }
        val plan = plan(inputs) ?: return null
        if (_isProbing.value) return null
        _isProbing.value = true
        try {
            val outcome = runCatching {
                withTimeoutOrNull(PROBE_DEADLINE_MS) {
                    httpMeasureThroughput(
                        url = plan.url,
                        headers = plan.headers,
                        maxBytes = plan.maxBytes,
                        maxMillis = MAX_TRANSFER_MS,
                        stopAboveMbps = plan.stopAboveMbps,
                    )
                }
            }
            val sample = outcome.getOrNull()
            if (sample == null) {
                logger.w {
                    "probe: no sample from ${plan.url} " +
                        "(${outcome.exceptionOrNull()?.message ?: "timed out after ${PROBE_DEADLINE_MS}ms"})"
                }
                return null
            }
            if (sample.status !in 200..299 || sample.bytes <= 0L) {
                // The 403 case. `CDN_FALLBACK_URL` asking for more than the endpoint would serve
                // produced exactly this on every probe, and it recorded nothing - which from
                // outside is the same symptom as measuring badly.
                logger.w { "probe: ${plan.url} answered ${sample.status} with ${sample.bytes} bytes" }
                return null
            }

            // **The floors guard the mean, never a windowed figure.** A closed window already
            // spanned its minimum duration and carried its minimum bytes, and so did a closed
            // partition - it is admitted on exactly the same two floors. Re-testing either
            // against a transfer-wide floor can only reject good readings, and it rejected them
            // fastest on the fastest lines, which is where they matter most.
            val hasWindowedRate = sample.sustainedMbps != null || sample.peakWindowMbps != null
            if (!hasWindowedRate &&
                (sample.bytes < MIN_SAMPLE_BYTES || sample.transferMs < MIN_SAMPLE_MS)
            ) {
                logger.w {
                    "probe: sample too small - ${sample.bytes} bytes in ${sample.transferMs}ms, no window"
                }
                return null
            }
            // Sustained, then the window, then the mean - see `ThroughputSample.bestEffortMbps`.
            // The mean carries TCP slow start and under-reads by more the faster the line is
            // (the 56-vs-81 Mb/s case); the window is a maximum and over-reads on a bursty link
            // (the 538-vs-416 case). The median of the byte partition is neither.
            val mbps = sample.bestEffortMbps ?: return null
            // **All three statistics, every time.** The gap between them is the only thing that
            // separates "measured badly upwards" from "measured badly downwards" from outside a
            // device, and the line this replaced printed one of them as `window=`, which did not
            // even say which statistic it was.
            logger.i {
                "probe: ${mbps.toInt()} Mb/s from ${plan.url} " +
                    "(${sample.bytes} bytes in ${sample.transferMs}ms, ttfb ${sample.ttfbMs}ms, " +
                    "sustained=${sample.sustainedMbps?.toInt() ?: "none"}, " +
                    "peak=${sample.peakWindowMbps?.toInt() ?: "none"}, " +
                    "mean=${sample.mbps?.toInt() ?: "none"}, forced=${plan.isForced})"
            }
            NetworkQualityRepository.recordProbeResult(
                mbps = mbps,
                providerId = plan.providerId,
                // A re-test is the user saying the stored number is wrong. Averaging the new
                // reading with the one they just rejected answers a question nobody asked.
                replaceExisting = plan.isForced,
            )
            return mbps
        } finally {
            _isProbing.value = false
        }
    }

    internal fun resetForTest() {
        _isProbing.value = false
    }
}
