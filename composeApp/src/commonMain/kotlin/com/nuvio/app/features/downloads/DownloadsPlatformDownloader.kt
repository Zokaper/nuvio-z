package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val downloadId: String,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val destinationFileName: String,
    val allowMeteredNetwork: Boolean = false,
    /** Size learned on a previous attempt, used to recognise an already complete file. */
    val knownTotalBytes: Long? = null,
    /** `ETag` captured previously, replayed as `If-Range` so a changed source is caught. */
    val resumeEtag: String? = null,
    /** `Last-Modified` fallback for sources that send no `ETag`. */
    val resumeLastModified: String? = null,
    /** The item's rank in the global queue; iOS turns it into a task priority hint. */
    val queuePosition: Long? = null,
    /** When [sourceUrl] was minted, for diagnostics only. */
    val sourceUrlResolvedAtEpochMs: Long? = null,
)

/**
 * Reports what a transfer did.
 *
 * The distinction between [onPaused] and [onFailed] is the point of this interface:
 * a transfer stopped on purpose is not a failure, and the previous callback shape
 * could not say so, which is why pausing used to surface as a failed download.
 */
internal interface DownloadTransferListener {
    /** The response is open. Carries the size and validators learned from it. */
    fun onOpened(
        resumedFromBytes: Long,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
    )

    fun onProgress(downloadedBytes: Long, totalBytes: Long?)

    /**
     * Every expected byte is on disk and the file is in its final location.
     *
     * [totalBytes] is the verified size of that file, never a figure inferred from a
     * transfer that stopped early.
     */
    fun onCompleted(localFileUri: String, totalBytes: Long)

    /** Stopped on request. The partial file is intact and resumable. */
    fun onPaused(downloadedBytes: Long)

    fun onFailed(reason: DownloadFailureReason, message: String, downloadedBytes: Long)
}

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        listener: DownloadTransferListener,
    ): DownloadsTaskHandle

    /** Deletes a finished file, then any folder of the organized layout it leaves empty. */
    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    /** Bytes already on disk for an unfinished download, or 0 when there are none. */
    fun partialFileBytes(destinationFileName: String): Long

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    // --- The organized layout ([DownloadFileOrganizer], Phase 9 closeout) ------------------------
    // Paths below are '/'-separated and relative to the downloads folder. Every one is checked to
    // stay inside it: a path with an empty, "." or ".." segment, or one that resolves outside the
    // folder, is refused.

    /** [localFileUri] relative to the downloads folder, or null when it is not inside it. */
    fun relativePathOf(localFileUri: String?): String?

    fun existsInDownloads(relativePath: String): Boolean

    fun fileUriFor(relativePath: String): String?

    /**
     * Renames the finished file at [localFileUri] to [relativePath], creating its folders. Never
     * overwrites and never copies: false, with nothing moved, when that cannot be done.
     */
    fun moveCompletedFile(localFileUri: String, relativePath: String): Boolean

    fun openDownloadsDirectory(): Boolean

    fun freeStorageBytes(): Long

    /** Who runs the bytes here; see [TransferHost]. */
    val transferHost: TransferHost
}
