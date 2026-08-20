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
     * The best rate sustained over any [THROUGHPUT_WINDOW_MS] of the transfer, or null when the
     * transfer was too short to hold a window.
     *
     * **This is the honest figure and [mbps] is not.** A mean over the whole body includes TCP
     * slow start, which on a short pull is most of it: the congestion window has to double its
     * way up to the bandwidth-delay product, so a few megabytes off a distant host spend their
     * first stretch nowhere near the line's real rate. The mean of that ramp is not a
     * measurement of the connection, it is a measurement of the ramp - and it under-reads by
     * more the faster the line is, which is exactly backwards for deciding whether 4K will
     * play. A reported "57 Mb/s" line that streamed an 81 Mbps remux without trouble is what
     * this is here to stop.
     *
     * Excluding TTFB, which [mbps] already does, removes the handshake but not the ramp.
     */
    val peakWindowMbps: Double? = null,
) {
    /** Mean over the whole measured body. Kept for diagnostics; prefer [bestEffortMbps]. */
    val mbps: Double?
        get() = if (bytes > 0L && transferMs > 0L) {
            bytes.toDouble() * 8.0 / transferMs.toDouble() / 1_000.0
        } else {
            null
        }

    /**
     * What a caller should record: the windowed rate when the transfer was long enough to hold a
     * window, and the mean otherwise.
     *
     * Never the *lower* of the two. Both are lower bounds on what the line can carry - a
     * transfer cannot go faster than it was asked to - so taking the smaller would be choosing
     * the weaker evidence on purpose.
     */
    val bestEffortMbps: Double?
        get() = listOfNotNull(peakWindowMbps, mbps).maxOrNull()
}

/**
 * How much of the transfer one throughput window covers.
 *
 * Long enough that a single delayed packet cannot inflate the figure, short enough that a
 * 2-3 second budget holds several windows and the ramp occupies only the first.
 */
const val THROUGHPUT_WINDOW_MS: Long = 750L

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
 */
expect suspend fun httpMeasureThroughput(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    maxMillis: Long,
    stopAboveMbps: Double? = null,
): ThroughputSample
