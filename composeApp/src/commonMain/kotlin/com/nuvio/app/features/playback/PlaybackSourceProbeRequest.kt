package com.nuvio.app.features.playback

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException

/**
 * Runs [PlaybackSourceProbe] against a resolved URL. The only part of the probe that touches the
 * network; every rule is in the pure file beside it.
 *
 * ⚠ **This call is unbounded, and the caller must never block a play on it.**
 *
 * The first version wrapped it in `withTimeoutOrNull(2_500)` and awaited it before handing off to
 * the player. That timeout does not work and cannot be made to: `httpRequestRaw`'s desktop actual
 * is a blocking OkHttp `execute()` inside `withContext(Dispatchers.IO)`, so there is no suspension
 * point for the timeout to cancel at and it can only fire once the call has already returned. The
 * first real measurement was `probe failed=TimeoutCancellationException elapsedMs=8115` against a
 * 2,500 ms budget - **eight seconds added to a play**, to avoid a twenty-second watchdog wait.
 * Worse, AIOStreams appears to do the debrid resolution itself when asked for byte 0, so the slow
 * case is the normal case.
 *
 * The fix is not a better timeout, it is not awaiting it. Run this **beside** the attach, in the
 * player, and let a rejection step the chain through the same path the startup watchdog uses. The
 * loading surface covers the player until the first frame, so a source rejected while it is up
 * costs nothing visible - and a probe that answers after the first frame is simply ignored.
 *
 * Failures answer [PlaybackProbeOutcome.Failed] and the source plays unjudged: this runs on every
 * automatic play, and refusing a source the user could have watched is far worse than missing one
 * they could not.
 */
suspend fun probePlaybackSource(
    url: String,
    headers: Map<String, String>,
    expectedBytes: Long?,
): PlaybackProbeOutcome {
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        // Torrents, magnets and local files. There is no response to read and no meaning to a
        // range request; the P2P engine and the file system answer for these instead.
        return PlaybackProbeOutcome.NotApplicable
    }
    return try {
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
        val total = PlaybackSourceProbe.totalBytes(
            status = response.status,
            contentRange = lookup("Content-Range"),
            contentLength = lookup("Content-Length")?.toLongOrNull(),
        )
        PlaybackProbeOutcome.Completed(
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
                // An error answer is usually a short text page, and its words are the diagnosis:
                // "Wrong IP" could only be inferred before because nothing kept the body.
                bodyPreview = response.body
                    .takeIf { response.status !in 200..299 }
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.take(200)
                    ?.takeIf { it.isNotEmpty() },
            ),
        )
    } catch (cancellation: CancellationException) {
        // ⚠ **Never swallowed.** The first version wrapped this whole body in `runCatching`, which
        // catches `CancellationException` too - so a cancelled probe was reported as a probe
        // failure and the coroutine it belonged to was left believing it was still alive. That is
        // also how the timeout below came to be reported as an ordinary error.
        throw cancellation
    } catch (error: Throwable) {
        PlaybackProbeOutcome.Failed(error::class.simpleName ?: "error")
    }
}

/**
 * What running the probe produced - including the ways it produced nothing.
 *
 * ⚠ **This type exists because the first version returned a nullable and logged only successes.**
 * On its very first real run the probe answered null and there was no line anywhere saying so, so
 * "the probe passed the source" and "the probe never ran" looked identical - the exact silent-path
 * fault this whole phase keeps finding, reintroduced by the fix for it. Every outcome is now
 * nameable and every outcome is logged.
 */
sealed interface PlaybackProbeOutcome {

    /** Not an HTTP source. Nothing to ask and nothing wrong. */
    data object NotApplicable : PlaybackProbeOutcome

    /**
     * The request threw - a connect failure, a malformed URL, a host that closed the socket.
     *
     * **Plays the source anyway**, and since the probe no longer blocks the hand-off it costs
     * nothing when it fails. Still worth reading in the log: a probe that always fails is a probe
     * that gates nothing, and that should be visible rather than inferred.
     */
    data class Failed(val reason: String) : PlaybackProbeOutcome

    data class Completed(val result: PlaybackProbeResult) : PlaybackProbeOutcome
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
    /** The start of the response body, kept only for a non-2xx answer. */
    val bodyPreview: String? = null,
) {
    /** `status=206 type=video/mp4 total=2952790016 host=…` - one line, for the playback log. */
    fun toLogFields(): String = buildString {
        append("status=").append(status)
        append(" type=").append(contentType?.substringBefore(';')?.trim() ?: "unknown")
        append(" total=").append(totalBytes?.toString() ?: "unknown")
        append(" host=").append(finalUrl.substringAfter("://", "").substringBefore('/'))
    }
}
