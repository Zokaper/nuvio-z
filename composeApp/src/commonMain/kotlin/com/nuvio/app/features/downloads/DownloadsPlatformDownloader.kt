package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
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

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    /** Bytes already on disk for an unfinished download, or 0 when there are none. */
    fun partialFileBytes(destinationFileName: String): Long

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean

    fun freeStorageBytes(): Long
}
