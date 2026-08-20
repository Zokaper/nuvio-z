package com.nuvio.app.core.network

import com.nuvio.app.features.addons.httpMeasureThroughput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Half the budget. 16 MiB is 134 Mb, so the reading is still honest to roughly 270 Mb/s -
     * past anything a metered link sustains - while the allowance spent stays a fraction of the
     * video it is about to choose. Skipping metered links entirely was the earlier answer and was
     * worse: mobile data is the connection whose speed varies most, so it became the one case
     * decided purely by a preset.
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
     * Neutral fallback. Records against no provider - it describes the line and nothing else.
     *
     * ⚠ **The body this serves must stay strictly larger than [MAX_BYTES].** `?bytes=` fixes the
     * resource size, and a resource smaller than the budget silently *becomes* the budget: the
     * `Range` header is then unsatisfiable in full, the transfer ends early, and no amount of
     * raising [MAX_BYTES] changes anything. This endpoint asked for 4 MiB while the budget said
     * 8 MiB, which is why every reading on every platform was a 4 MiB pull - about 585 ms on a
     * 72 Mb/s line, too short to hold a window, so the mean was recorded and the connection read
     * 56 Mb/s while streaming 81. Nothing here is free-running: `Range` bounds what is received,
     * and closing the stream at the ceiling ends the transfer, so a large figure costs nothing.
     */
    const val CDN_FALLBACK_URL = "https://speed.cloudflare.com/__down?bytes=134217728"

    /**
     * Floors for the **mean**, which is the fallback figure and the only one that needs guarding.
     *
     * A closed window is self-validating: it spanned [ThroughputWindow.DEFAULT_WINDOW_MS] and
     * carried [ThroughputWindow.DEFAULT_MIN_WINDOW_BYTES] by construction. Applying these to it
     * as well is what discarded the fast-line samples the window was added to rescue - a 4 MiB
     * pull above ~83 Mb/s finishes inside 400 ms, so the probe threw its own answer away and the
     * stale estimate survived, which is what "it won't update" looked like from outside.
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
     * needs ~160 with headroom - so an exit here can never depress a figure any decision depends
     * on, while still cutting the budget on a line fast enough not to need measuring precisely.
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

        // Always set, and never below the floor. Leaving it null when no option quoted a cost
        // meant Best available - the only option on the sheet without one - disabled the exit
        // entirely, which combined with `requiredMbps` reading `firstOrNull()` is why it has
        // never fired.
        val stopAbove = maxOf(
            inputs.requiredMbps
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { it * EARLY_EXIT_MARGIN }
                ?: 0.0,
            EARLY_EXIT_FLOOR_MBPS,
        )
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
     * or a sample too small to mean anything. A failure is deliberately silent: the estimate
     * simply stays where it was, and the caller keeps whatever confidence it already had.
     *
     * Only one probe runs at a time. A second caller is refused rather than queued, because by
     * the time the first finishes its answer is the one the second wanted.
     */
    suspend fun probe(inputs: Inputs): Double? {
        val plan = plan(inputs) ?: return null
        if (_isProbing.value) return null
        _isProbing.value = true
        try {
            val sample = runCatching {
                withTimeoutOrNull(PROBE_DEADLINE_MS) {
                    httpMeasureThroughput(
                        url = plan.url,
                        headers = plan.headers,
                        maxBytes = plan.maxBytes,
                        maxMillis = MAX_TRANSFER_MS,
                        stopAboveMbps = plan.stopAboveMbps,
                    )
                }
            }.getOrNull() ?: return null

            // **The floors guard the mean, never the window.** A closed window already spanned
            // its minimum duration and carried its minimum bytes, so re-testing it against a
            // transfer-wide floor can only reject good readings - and it rejected them fastest
            // on the fastest lines, which is where the window matters most.
            val hasWindow = sample.peakWindowMbps != null
            if (!hasWindow && (sample.bytes < MIN_SAMPLE_BYTES || sample.transferMs < MIN_SAMPLE_MS)) {
                return null
            }
            // The windowed rate, falling back to the mean only when the transfer was too short
            // to hold a window. The mean includes TCP slow start and therefore under-reads by
            // more the faster the line is - see `ThroughputWindow`, and the 56-vs-81 Mb/s case
            // that is the whole reason this changed.
            val mbps = sample.bestEffortMbps ?: return null
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
