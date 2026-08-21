package com.nuvio.app.features.addons

internal expect object AddonStorage {
    fun loadInstalledAddonUrls(profileId: Int): List<String>
    fun saveInstalledAddonUrls(profileId: Int, urls: List<String>)
    fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean>
    fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>)
}

data class RawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

/** Default safety limit for generic and plugin-provided HTTP responses. */
internal const val DefaultRawHttpResponseMaxBytes = 1024 * 1024

expect suspend fun httpGetText(url: String): String

expect suspend fun httpPostJson(url: String, body: String): String

expect suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String

expect suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String

expect suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean = true,
    maxResponseBodyBytes: Int = DefaultRawHttpResponseMaxBytes,
): RawHttpResponse

/**
 * What a bounded ranged GET actually delivered, for measuring a connection.
 *
 * [transferMs] runs from the **first byte to the last** and is what the rate must be computed
 * from. [ttfbMs] is reported separately rather than folded in because a debrid host that takes
 * two seconds to answer and then saturates the line is a fast source with a slow handshake, and
 * charging the handshake to the throughput would read it as a slow connection.
 *
 * [bytes] excludes the first chunk, whose arrival is what starts the clock; counting bytes whose
 * transfer time was not measured would bias every reading upwards.
 */
data class ThroughputSample(
    val status: Int,
    val bytes: Long,
    val transferMs: Long,
    val ttfbMs: Long,
    /**
     * The best rate sustained over any one window of the transfer, or null when the transfer was
     * too short or too small to hold one. See `ThroughputWindow` for what bounds a window.
     *
     * **This is the honest figure and [mbps] is not.** A mean over the whole body includes TCP
     * slow start, which on a short pull is most of it: the congestion window has to double its
     * way up to the bandwidth-delay product, so a few megabytes off a distant host spend their
     * first stretch nowhere near the line's real rate. The mean of that ramp is not a
     * measurement of the connection, it is a measurement of the ramp - and it under-reads by
     * more the faster the line is, which is exactly backwards for deciding whether 4K will
     * play. A reported "56 Mb/s" line that streamed an 81 Mbps remux without trouble is what
     * this is here to stop.
     *
     * Excluding TTFB, which [mbps] already does, removes the handshake but not the ramp.
     *
     * ⚠ **Every actual must populate this, and the desktop one did not.** It went out reporting
     * null unconditionally, so `bestEffortMbps` fell through to the mean on that platform and
     * the whole windowed-rate change was inert there while the other two carried it. A null here
     * has to mean "the transfer was too small to hold a window", never "this platform does not
     * compute one" - the callers cannot tell those apart.
     */
    val peakWindowMbps: Double? = null,
    /**
     * The median rate over byte-partitioned blocks past the ramp. **This is the honest figure**,
     * and [peakWindowMbps] is not - see `ThroughputWindow.sustainedMbps` for the arithmetic.
     *
     * A maximum over sliding windows is not an estimator: it hunts for the most flattering
     * position, so Wi-Fi burstiness and receive-buffer drains both inflate it. The reported case
     * is a gauge showing **538 Mb/s on a line Ookla measured at 416** multi-stream - and a single
     * TCP stream reading *above* a multi-stream figure is backwards.
     *
     * ⚠ **Every actual must populate this, on the same terms as [peakWindowMbps].** A null here
     * has to mean "the transfer was too brief to partition", never "this platform does not
     * compute one" - the callers cannot tell those apart, and the desktop actual has already
     * shipped once returning null unconditionally for exactly that field.
     */
    val sustainedMbps: Double? = null,
) {
    /** Mean over the whole measured body. Kept for diagnostics; prefer [bestEffortMbps]. */
    val mbps: Double?
        get() = if (bytes > 0L && transferMs > 0L) {
            bytes.toDouble() * 8.0 / transferMs.toDouble() / 1_000.0
        } else {
            null
        }

    /**
     * What a caller should record, in order of how well the statistic describes the line:
     *
     * 1. [sustainedMbps] - a median over a fixed byte partition taken past the ramp. Not a
     *    maximum, so no single lucky window can win it; not a mean, so it does not carry slow
     *    start.
     * 2. [peakWindowMbps] - a maximum, and the only figure a transfer too brief to partition
     *    still yields: above roughly 939 Mb/s unmetered, or after a metered `stopAboveMbps` exit
     *    truncates the pull below the region's span floor.
     * 3. [mbps] - the mean, under the probe's own sample floors.
     *
     * ⚠ **A precedence, and never `maxOrNull` again.** Taking the larger was right while both
     * candidates were *lower bounds* on what the line carried - a transfer cannot go faster than
     * it was asked to, so the bigger number was strictly the better evidence. That stopped being
     * true the moment one candidate became a maximum with a known upward bias: `max(median, max)`
     * **is** the max, and restoring it would leave this whole change doing nothing while looking
     * as though it worked. That is the shape of the first regression this file's `expect` warns
     * about, arriving a second time by a different route.
     */
    val bestEffortMbps: Double?
        get() = sustainedMbps ?: peakWindowMbps ?: mbps
}

/**
 * Streams up to [maxBytes] and reports how long they took, without materialising the body.
 *
 * [httpRequestRaw] cannot do this job: it decodes the body to a `String` and caps at 1 MiB, so
 * the fastest connection it could ever describe is about 11 Mbps.
 *
 * Stops at whichever comes first: [maxBytes], [maxMillis] of transfer, or - when [stopAboveMbps]
 * is non-null - the running rate clearing it, which is there so proving a fast line is fast does
 * not cost the full budget. Implementations must request `identity` encoding: a gzipped body
 * would time compressed bytes and report a rate no video file will ever reach.
 *
 * Every actual owes four things, and the desktop one shipped without two of them:
 *
 * 1. exclude the chunk that starts the clock, whose own transfer time was not measured;
 * 2. feed every subsequent chunk to a `ThroughputWindow` and report **both**
 *    [ThroughputSample.peakWindowMbps] and [ThroughputSample.sustainedMbps];
 * 3. judge [stopAboveMbps] on the **windowed** rate, not the cumulative mean - the mean lags the
 *    real rate, so on a fast line the exit fires late and on a slow one it cannot fire at all.
 *    It is judged on the peak rather than the sustained figure deliberately: the early exit needs
 *    a running answer, and the partition only has one once the transfer has ended.
 * 4. leave the arithmetic to `ThroughputWindow`. A rate computed three times in three actuals is
 *    a rate that will eventually be computed three different ways.
 */
expect suspend fun httpMeasureThroughput(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    maxMillis: Long,
    stopAboveMbps: Double? = null,
): ThroughputSample
