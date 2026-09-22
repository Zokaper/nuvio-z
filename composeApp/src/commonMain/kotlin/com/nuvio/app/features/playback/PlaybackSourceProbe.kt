package com.nuvio.app.features.playback

/**
 * One bounded request against a resolved source, before a single frame is attached.
 *
 * ⚠ **Why this exists, from a real session.** *The Secret Woman*, 4K High, 2026-09-05:
 *
 * ```
 * 00:55:02.905  attach created   length=3092  ← [TB⚡] MediaFusion 2160p, marked cached
 * 00:55:22.614  abandoning …: reason=NeverStarted elapsed=20240ms progress=0ms
 *               duration=0ms engine=Unknown
 * 00:55:24.418  attach created   length=1395
 * 00:55:31.613  updateControls   pos=10160  duration=120960   ← 2:01, for a feature film
 * ```
 *
 * Two separate faults, and **nothing in the app could see either one**:
 *
 *  1. The first source was positively marked cached and still never produced a frame. Twenty
 *     seconds were spent learning that, and the log recorded `engine=Unknown duration=0`, so a
 *     403, a debrid mint that stalled and a proxy that sent no bytes were indistinguishable
 *     afterwards. Nothing anywhere logged the *response* to a URL handed to the engine.
 *  2. The second was the provider's "being prepared" slate - the two-minute placeholder
 *     `PlaybackSourceSelector.isUncachedDebrid` already documents - and it *played*, so the
 *     startup watchdog scored it as a success and the chain stopped.
 *
 * A single `Range: bytes=0-1` costs one round trip on the happy path, under a loading screen that
 * is already up, and turns both of those into facts. **Cache state stops being a guess about what
 * a release name says and becomes what the URL actually returns** - which is the only version of
 * it that cannot go stale.
 *
 * Pure and primitive-typed, so `scripts/run-pure-suites.sh` executes the whole table; the request
 * itself is the caller's, through `httpRequestRaw`.
 */
sealed interface PlaybackProbeVerdict {

    /** Nothing to object to. Not a promise the source is good, only that it is not known bad. */
    data object Pass : PlaybackProbeVerdict

    /** The source will not play. [reason] is a log key, not user-facing prose. */
    data class Dead(val reason: String) : PlaybackProbeVerdict

    /**
     * The source answers, but with something far too small to be the requested content.
     *
     * Kept apart from [Dead] because it means something different to the user and to the chain:
     * the provider is working and the file is simply not ready yet, which is worth saying.
     */
    data class Placeholder(val reason: String) : PlaybackProbeVerdict
}

/** One stable word per outcome, for the playback log. Never shown to the user. */
fun PlaybackProbeVerdict.logKey(): String = when (this) {
    is PlaybackProbeVerdict.Pass -> "pass"
    is PlaybackProbeVerdict.Dead -> "dead:$reason"
    is PlaybackProbeVerdict.Placeholder -> "placeholder:$reason"
}

object PlaybackSourceProbe {

    /** Enough to see the status, the type and the reported length. Nothing is buffered. */
    const val PROBE_RANGE_HEADER: String = "bytes=0-1"

    // ⚠ **There is deliberately no timeout constant here any more.** There was one, at 2,500 ms,
    // and it was a lie: `httpRequestRaw`'s desktop actual blocks inside `Dispatchers.IO`, so
    // `withTimeoutOrNull` had no suspension point to cancel at and the first real measurement
    // came back at 8,115 ms - eight seconds added to a play. A bound that cannot be enforced is
    // worse than none, because the caller writes code as though it holds. See
    // `probePlaybackSource`: the probe is unbounded and must be run beside the attach, never
    // awaited before it.

    /**
     * The absolute ceiling for "this cannot be the feature".
     *
     * Sized against the placeholder slates rather than against real content: providers ship them
     * at a few megabytes, and the smallest genuine thing that reaches this path - a short, a
     * heavily compressed 20-minute episode - is comfortably above it. Being wrong here refuses a
     * source the user could have watched, so it is deliberately far below anything plausible.
     */
    const val IMPLAUSIBLE_MAX_BYTES: Long = 64L * 1024L * 1024L

    /**
     * How far under its own claim a file must fall before the claim is treated as evidence.
     *
     * A release that says 20 GB and answers with 3 MB is not a variance, it is a different file.
     * Tenfold keeps honest mismatches - a repack, a mislabelled size, a provider reporting the
     * episode rather than the pack - well clear.
     */
    const val IMPLAUSIBLE_SIZE_RATIO: Long = 10L

    /**
     * The verdict.
     *
     * [reportedTotalBytes] is the *whole file's* size - from `Content-Range`'s total, or from
     * `Content-Length` when the server ignored the range. [expectedBytes] is what the release
     * claimed, `SourceFacts.sizeBytes`, and is very often null.
     *
     * **Null inputs always pass.** Only a fact that exists and disagrees may reject, which is the
     * same rule `ContentIdentityGuard` carries and for the same reason: this runs on every play,
     * and a gate that guesses is a gate that will one day refuse the only working source.
     */
    fun verdict(
        status: Int,
        contentType: String?,
        reportedTotalBytes: Long?,
        expectedBytes: Long?,
    ): PlaybackProbeVerdict {
        if (status !in 200..299) return PlaybackProbeVerdict.Dead("http_$status")

        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type.isNotBlank() && !isPlayableContentType(type)) {
            return PlaybackProbeVerdict.Dead("content_type_$type")
        }

        val total = reportedTotalBytes?.takeIf { it > 0L } ?: return PlaybackProbeVerdict.Pass
        if (total >= IMPLAUSIBLE_MAX_BYTES) return PlaybackProbeVerdict.Pass

        val claimed = expectedBytes?.takeIf { it > 0L } ?: return PlaybackProbeVerdict.Pass
        return if (claimed / IMPLAUSIBLE_SIZE_RATIO > total) {
            PlaybackProbeVerdict.Placeholder("served_${total}_claimed_$claimed")
        } else {
            PlaybackProbeVerdict.Pass
        }
    }

    /**
     * What a video URL is allowed to answer with.
     *
     * `application/octet-stream` is on the list because most debrid hosts send it for a plain
     * file download and refusing it would refuse the majority of working sources. What this
     * actually catches is the common failure shape: an HTML error page or a JSON body returned
     * with a 200, which the engine then sits on until the watchdog fires.
     */
    private fun isPlayableContentType(type: String): Boolean =
        type.startsWith("video/") ||
            type.startsWith("audio/") ||
            type == "application/octet-stream" ||
            type == "binary/octet-stream" ||
            type == "application/mp4" ||
            type == "application/x-mpegurl" ||
            type == "application/vnd.apple.mpegurl" ||
            type == "application/dash+xml"

    /**
     * The file's total size from a `Content-Range`, or null.
     *
     * The header reads `bytes 0-1/2952790016`; the part after the slash is the whole file. A `*`
     * there means the server declines to say, which is a legitimate answer and must not be read
     * as zero.
     */
    fun parseContentRangeTotal(header: String?): Long? {
        val total = header?.substringAfterLast('/')?.trim() ?: return null
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * The total size the response implies, preferring `Content-Range`.
     *
     * `Content-Length` on a ranged reply is the length of the *range* - two bytes - so reading it
     * without checking for a range first would call every source in existence a placeholder.
     * It is only meaningful when the server ignored the range and answered 200 with the lot.
     */
    fun totalBytes(status: Int, contentRange: String?, contentLength: Long?): Long? {
        parseContentRangeTotal(contentRange)?.let { return it }
        if (status == 206) return null
        return contentLength?.takeIf { it > 0L }
    }
}

/**
 * The backstop for a placeholder the probe could not judge.
 *
 * The probe needs the release to have claimed a size; very often it has not, and then a two-minute
 * slate passes every gate, plays, and is scored as a successful start - which is exactly what
 * happened to *The Secret Woman*: `duration=120960` against a feature film. The engine's own
 * reported duration is the one fact that is always available and cannot be faked by a display
 * name, so it is the last line.
 *
 * ⚠ **Deliberately far more conservative than it could be.** This runs on live playback and a
 * false positive kills a source the user is already watching. Two independent conditions must
 * both hold - a large *ratio* against the expected runtime, and a small *absolute* duration - so
 * a mis-tagged runtime alone can never trigger it. A 45-minute episode whose metadata wrongly
 * says 90 minutes is untouched; a 2-minute file claiming to be a 100-minute film is not.
 */
object PlaybackDurationPlausibility {

    /** Below a fifth of the expected runtime is not a variance, it is a different file. */
    const val IMPLAUSIBLE_RATIO: Int = 5

    /**
     * And it must also be short in absolute terms.
     *
     * Ten minutes clears every provider slate seen so far - they run two - while staying under
     * anything that could be a real feature or episode. Without this arm, a legitimately long
     * runtime paired with a part-file would be rejected on the ratio alone.
     */
    const val IMPLAUSIBLE_MAX_MS: Long = 10L * 60L * 1000L

    /**
     * Whether [reportedDurationMs] is too short to be [expectedRuntimeMinutes] of content.
     *
     * Both unknowns pass, the same rule the rest of this file carries: only two facts that exist
     * and disagree may reject.
     */
    fun isImplausiblyShort(reportedDurationMs: Long, expectedRuntimeMinutes: Int?): Boolean {
        val expectedMinutes = expectedRuntimeMinutes?.takeIf { it > 0 } ?: return false
        if (reportedDurationMs <= 0L) return false
        if (reportedDurationMs > IMPLAUSIBLE_MAX_MS) return false
        val expectedMs = expectedMinutes.toLong() * 60L * 1000L
        return reportedDurationMs < expectedMs / IMPLAUSIBLE_RATIO
    }
}
