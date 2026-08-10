package com.nuvio.app.core.network

import com.nuvio.app.features.addons.httpMeasureThroughput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * [plan] is pure and holds every rule about whether and what to measure; [measure] is the only
 * part that touches the network.
 */
object NetworkStrengthProbe {

    /**
     * The transfer budget.
     *
     * 4 MiB over 2.5 s tops out around 13 Mbps of *guaranteed* resolution, and well beyond that
     * in practice because a fast line hits the byte cap long before the time cap - a 100 Mbps
     * connection delivers the whole 4 MiB in a third of a second. The earlier sketch of ~1.5 MB
     * could not tell 20 Mbps from 100, which is precisely the distinction 4K depends on.
     */
    const val MAX_BYTES = 4L * 1024L * 1024L
    const val MAX_TRANSFER_MS = 2_500L

    /**
     * How recent an estimate has to be for the probe to skip entirely.
     *
     * A measurement from four minutes ago against the same host is better evidence than a fresh
     * 4 MB one, and re-probing every time the sheet opens would charge the user repeatedly for
     * an answer already in hand.
     */
    const val FRESH_ESTIMATE_MS = 10L * 60L * 1_000L

    /** Neutral fallback. Records against no provider - it describes the line and nothing else. */
    const val CDN_FALLBACK_URL = "https://speed.cloudflare.com/__down?bytes=4194304"

    /** Below either of these the sample is noise, matching the repository's own sample floors. */
    private const val MIN_SAMPLE_BYTES = 512L * 1024L
    private const val MIN_SAMPLE_MS = 400L

    /**
     * Stop once the running rate clears the option's requirement by this much. Proving a fast
     * line is fast does not need the whole budget, and on a metered-adjacent connection the
     * bytes not spent are the point.
     */
    private const val EARLY_EXIT_MARGIN = 1.5

    /** Everything [plan] needs, as plain values, so the rules are testable without a network. */
    data class Inputs(
        val isMetered: Boolean,
        val isOffline: Boolean,
        /** Age of the estimate [NetworkQualityRepository] would use, or null if there is none. */
        val estimateAgeMs: Long?,
        /** The top option's playable direct URL, when it has one that is worth pulling bytes from. */
        val sourceUrl: String?,
        val sourceHeaders: Map<String, String> = emptyMap(),
        val providerId: String? = null,
        /** What the option the user would play needs, for the early exit. */
        val requiredMbps: Double? = null,
    )

    data class Plan(
        val url: String,
        val headers: Map<String, String>,
        /** Null for the CDN target: a neutral endpoint must never speak for a provider. */
        val providerId: String?,
        val stopAboveMbps: Double?,
    )

    private val _isProbing = MutableStateFlow(false)

    /** True while a probe is in flight, so the sheet can say it is still checking. */
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    fun plan(inputs: Inputs): Plan? {
        if (inputs.isOffline) return null
        // The user's allowance is not ours to spend on a measurement they did not ask for. The
        // buffer meter still measures cellular for free once something is playing.
        if (inputs.isMetered) return null
        if (inputs.estimateAgeMs != null && inputs.estimateAgeMs < FRESH_ESTIMATE_MS) return null

        val stopAbove = inputs.requiredMbps
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { it * EARLY_EXIT_MARGIN }
        val sourceUrl = inputs.sourceUrl?.trim()?.takeIf(::isProbeableUrl)
        return if (sourceUrl != null) {
            Plan(
                url = sourceUrl,
                headers = inputs.sourceHeaders,
                providerId = inputs.providerId,
                stopAboveMbps = stopAbove,
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
                httpMeasureThroughput(
                    url = plan.url,
                    headers = plan.headers,
                    maxBytes = MAX_BYTES,
                    maxMillis = MAX_TRANSFER_MS,
                    stopAboveMbps = plan.stopAboveMbps,
                )
            }.getOrNull() ?: return null

            if (sample.bytes < MIN_SAMPLE_BYTES || sample.transferMs < MIN_SAMPLE_MS) return null
            val mbps = sample.mbps ?: return null
            NetworkQualityRepository.recordProbeResult(mbps, plan.providerId)
            return mbps
        } finally {
            _isProbing.value = false
        }
    }

    internal fun resetForTest() {
        _isProbing.value = false
    }
}
