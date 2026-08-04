package com.nuvio.app.features.downloads

/**
 * Shared transfer rules used by every platform downloader.
 *
 * The Android, desktop and iOS downloaders each run their own byte loop, but the
 * decisions around them - how large the file is meant to be, whether the bytes on
 * disk actually add up to a finished download, and whether a failure is worth
 * retrying - must agree. Keeping them here means one implementation to reason
 * about and one to test.
 */

/** Progress is reported at most this often, to keep persistence off the hot path. */
internal const val PROGRESS_MIN_INTERVAL_MS = 500L

/** ...and at most once per this many bytes, whichever comes first. */
internal const val PROGRESS_MIN_BYTE_DELTA = 512L * 1024L

/** The partial file is flushed at least this often so a process kill loses little. */
internal const val PARTIAL_FLUSH_BYTE_DELTA = 8L * 1024L * 1024L

internal const val MAX_DOWNLOAD_ATTEMPTS = 5

/** Why a transfer stopped, which decides whether resuming it is worth attempting. */
internal enum class DownloadFailureReason {
    /** A network blip, timeout or 5xx. Worth retrying from the partial file. */
    Transient,

    /** The remote bytes changed under us; the partial file was discarded. */
    SourceChanged,

    /** The stream ended before the expected total. The partial file is kept. */
    Incomplete,

    /**
     * The transfer completed, but what arrived is not the file.
     *
     * Debrid providers answer with a small placeholder video - "your download is
     * queued and waiting for a slot" - while they fetch the real thing. It is a
     * valid, complete, playable MP4, so nothing about the transfer looks wrong;
     * only its size gives it away. Worth retrying, but on a much longer schedule
     * than a network blip, because the wait is someone else's queue.
     */
    SourceNotReady,

    /** The source is gone or refuses us. Retrying cannot help. */
    Fatal,
}

/** The verdict on a finished byte loop. */
internal sealed interface DownloadCompletion {
    /** Every expected byte is on disk. */
    data object Complete : DownloadCompletion

    /** The stream ended early. The partial file is still valid to resume from. */
    data class Short(val downloadedBytes: Long, val expectedBytes: Long) : DownloadCompletion

    /** More bytes arrived than the file should hold, so the partial file is poisoned. */
    data class Overrun(val downloadedBytes: Long, val expectedBytes: Long) : DownloadCompletion
}

/**
 * Decides whether a finished byte loop actually produced a whole file.
 *
 * Reaching the end of the response body is not the same as finishing the download:
 * a dropped connection, an expired source URL serving a short error body, or a
 * truncated proxy response all end the stream cleanly. Only a byte count that
 * matches the authoritative total may be treated as complete.
 *
 * When no total is known there is nothing to check against, so the transfer is
 * accepted - refusing it would strand the download forever - but callers must then
 * record the real file length rather than inventing a total from it.
 */
internal fun evaluateCompletion(
    downloadedBytes: Long,
    totalBytes: Long?,
): DownloadCompletion {
    val expected = totalBytes?.takeIf { it > 0L } ?: return DownloadCompletion.Complete
    return when {
        downloadedBytes == expected -> DownloadCompletion.Complete
        downloadedBytes > expected -> DownloadCompletion.Overrun(downloadedBytes, expected)
        else -> DownloadCompletion.Short(downloadedBytes, expected)
    }
}

/** Attempts allowed while a debrid provider works through its own queue. */
internal const val MAX_SOURCE_NOT_READY_ATTEMPTS = 8

/**
 * No real episode or film is this small.
 *
 * The floor only has to be above placeholder videos and error bodies, and far
 * below any genuine download, so it does not need to be clever.
 */
internal const val MIN_PLAUSIBLE_MEDIA_BYTES = 1L * 1024L * 1024L

/** How far under the advertised size a finished file may land before it is suspect. */
internal const val MIN_EXPECTED_SIZE_RATIO = 0.25

/** Whether another attempt is worth making after [reason] on attempt number [attempt]. */
internal fun shouldRetry(reason: DownloadFailureReason, attempt: Int): Boolean = when (reason) {
    DownloadFailureReason.Fatal -> false
    DownloadFailureReason.SourceNotReady -> attempt < MAX_SOURCE_NOT_READY_ATTEMPTS
    else -> attempt < MAX_DOWNLOAD_ATTEMPTS
}

/**
 * Backoff before attempt number [attempt] (1-based), capped so the queue keeps moving.
 *
 * A source that is still preparing the file waits far longer than a network blip:
 * retrying a debrid queue every few seconds neither helps nor is polite, and the
 * whole budget would be gone inside a minute.
 */
internal fun retryBackoffMs(attempt: Int, reason: DownloadFailureReason): Long =
    if (reason == DownloadFailureReason.SourceNotReady) {
        when {
            attempt <= 1 -> 60_000L
            attempt == 2 -> 180_000L
            attempt == 3 -> 300_000L
            else -> 600_000L
        }
    } else {
        when {
            attempt <= 1 -> 2_000L
            attempt == 2 -> 5_000L
            attempt == 3 -> 15_000L
            else -> 30_000L
        }
    }

/**
 * Whether a finished transfer is too small to be the media it claims to be.
 *
 * A debrid placeholder is a complete, valid, playable file, so byte counting and
 * content types both pass it. Size against what the source advertised is the only
 * thing that catches it. When nothing was advertised, the absolute floor still
 * rules out placeholders and error bodies.
 */
internal fun isImplausiblySmallForMedia(finalBytes: Long, expectedBytes: Long?): Boolean {
    val expected = expectedBytes?.takeIf { it > 0L }
        ?: return finalBytes < MIN_PLAUSIBLE_MEDIA_BYTES
    if (finalBytes < MIN_PLAUSIBLE_MEDIA_BYTES) return true
    return finalBytes < (expected * MIN_EXPECTED_SIZE_RATIO)
}

/**
 * Classifies an HTTP status so a dead source is not retried five times over.
 *
 * Anything that means "this URL will never work again" - a signed debrid link that
 * expired, a removed file - is [DownloadFailureReason.Fatal]. Overload and server
 * faults are transient.
 */
internal fun failureReasonForHttpStatus(statusCode: Int): DownloadFailureReason = when (statusCode) {
    400, 401, 403, 404, 405, 410, 451 -> DownloadFailureReason.Fatal
    408, 425, 429 -> DownloadFailureReason.Transient
    else -> if (statusCode >= 500) DownloadFailureReason.Transient else DownloadFailureReason.Fatal
}

/**
 * Resolves the authoritative size of the whole file for the current attempt.
 *
 * `Content-Range` carries the total directly and is preferred. `Content-Length` only
 * describes the bytes still to come, so on a partial resume the bytes already on
 * disk have to be added back in.
 */
internal fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

/**
 * Reads the total size out of a `Content-Range` header.
 *
 * Handles both the satisfied form (`bytes 100-199/1000`) and the unsatisfied form a
 * 416 response carries (`bytes  * /1000`), which is what lets a complete partial file
 * be recognised instead of thrown away.
 */
internal fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}

/**
 * Picks the validator sent as `If-Range` when resuming.
 *
 * Without one, a source that changed between pause and resume answers the range
 * request with fresh bytes that get appended onto the old partial file, silently
 * corrupting it. Debrid links are re-signed often enough that this is routine. With
 * a validator the server answers `200` instead and the download restarts cleanly.
 */
internal fun resumeValidator(etag: String?, lastModified: String?): String? =
    etag?.trim()?.takeIf { it.isNotBlank() }
        ?: lastModified?.trim()?.takeIf { it.isNotBlank() }

/** Whether enough has changed since the last report to publish progress again. */
internal fun shouldReportProgress(
    downloadedBytes: Long,
    lastReportedBytes: Long,
    nowEpochMs: Long,
    lastReportedAtEpochMs: Long,
): Boolean =
    downloadedBytes - lastReportedBytes >= PROGRESS_MIN_BYTE_DELTA ||
        nowEpochMs - lastReportedAtEpochMs >= PROGRESS_MIN_INTERVAL_MS
