package com.nuvio.app.features.playback

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [PlaybackSourceProbe] against a resolved URL. The only part of the probe that touches the
 * network; every rule is in the pure file beside it.
 *
 * **Never throws and never blocks for long.** A probe that fails, times out, or is handed a URL
 * it cannot make sense of answers [PlaybackProbeVerdict.Pass] - the source is then played
 * unjudged, exactly as it was before this existed. That default is deliberate: this gate runs on
 * every automatic play, and the cost of a false negative (a twenty-second watchdog wait, which is
 * the behaviour we already have) is far below the cost of a false positive (refusing a source the
 * user could have watched).
 */
suspend fun probePlaybackSource(
    url: String,
    headers: Map<String, String>,
    expectedBytes: Long?,
): PlaybackProbeResult? {
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        // Torrents, magnets and local files. There is no response to read and no meaning to a
        // range request; the P2P engine and the file system answer for these instead.
        return null
    }
    return withTimeoutOrNull(PlaybackSourceProbe.PROBE_TIMEOUT_MS) {
        runCatching {
            val response = httpRequestRaw(
                method = "GET",
                url = url,
                headers = headers + mapOf("Range" to PlaybackSourceProbe.PROBE_RANGE_HEADER),
                body = "",
                followRedirects = true,
                // The body is not wanted at all; this is the smallest the API allows and the two
                // bytes the range asks for fit inside it many times over.
                maxResponseBodyBytes = 1024,
            )
            val lookup = { name: String ->
                response.headers.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
            }
            val contentRange = lookup("Content-Range")
            val total = PlaybackSourceProbe.totalBytes(
                status = response.status,
                contentRange = contentRange,
                contentLength = lookup("Content-Length")?.toLongOrNull(),
            )
            PlaybackProbeResult(
                verdict = PlaybackSourceProbe.verdict(
                    status = response.status,
                    contentType = lookup("Content-Type"),
                    reportedTotalBytes = total,
                    expectedBytes = expectedBytes,
                ),
                status = response.status,
                contentType = lookup("Content-Type"),
                totalBytes = total,
                finalUrl = response.url,
            )
        }.getOrNull()
    }
}

/**
 * The verdict plus what it was decided from, because the figures are the point.
 *
 * The whole reason a source that never produced a frame could not be explained afterwards is that
 * nothing recorded the response. These fields exist to be logged even when the verdict is
 * [PlaybackProbeVerdict.Pass].
 */
data class PlaybackProbeResult(
    val verdict: PlaybackProbeVerdict,
    val status: Int,
    val contentType: String?,
    val totalBytes: Long?,
    val finalUrl: String,
) {
    /** `status=206 type=video/mp4 total=2952790016 host=…` - one line, for the playback log. */
    fun toLogFields(): String = buildString {
        append("status=").append(status)
        append(" type=").append(contentType?.substringBefore(';')?.trim() ?: "unknown")
        append(" total=").append(totalBytes?.toString() ?: "unknown")
        append(" host=").append(finalUrl.substringAfter("://", "").substringBefore('/'))
    }
}
