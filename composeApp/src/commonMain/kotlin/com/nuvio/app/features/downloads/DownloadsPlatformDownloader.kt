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

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    /** Bytes already on disk for an unfinished download, or 0 when there are none. */
    fun partialFileBytes(destinationFileName: String): Long

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean

    fun freeStorageBytes(): Long

    /**
     * Whether this platform brings its own system-paused transfers back.
     *
     * A system pause is a promise that something other than the user will restart the
     * download: Android pauses the queue when the background job is stopped and iOS
     * when the app leaves the foreground, and both have a counterpart that resumes it.
     * Desktop has neither half, so an item that reaches that state there has nothing
     * to release it and the queue has to take it back itself.
     */
    val recoversSystemPauses: Boolean

    /**
     * How many downloads the queue hands to the platform at once.
     *
     * Android and desktop run their own transfers, so this is a real concurrency limit
     * there. iOS hands transfers to the background session and the system decides how
     * many actually run: a task created while the app is suspended is discretionary
     * and rate-limited, so the only way a queue keeps moving while the phone is locked
     * is to have submitted it before the app left the foreground. There this is the
     * size of that submitted window, not a concurrency cap.
     */
    val maxConcurrentTransfers: Int

    /**
     * Whether a held transfer's silence is the platform's business, not the queue's.
     *
     * On iOS a submitted task may sit untouched inside the system daemon for as long as
     * the system likes, and the session has its own request and resource timeouts. The
     * queue's silence watchdog would read that wait as a lost transfer and charge it an
     * attempt every few minutes.
     */
    val ownsTransferLiveness: Boolean

    /**
     * True while the platform, not the repository, decides what starts next.
     *
     * iOS only, and only while the app is in the background: the suspended app cannot
     * re-mint source URLs, so the native session fills freed slots itself from what the
     * repository persisted. Everywhere else this is always false.
     */
    fun schedulingDeferredToPlatform(): Boolean

    /**
     * Reports the transfers the platform really holds, or null where it keeps none
     * across process deaths. May answer on any thread.
     */
    fun requestTransferInventory(onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit)

    /** Stops a platform-held transfer, keeping its bytes. */
    fun suspendTransfer(downloadId: String)

    /** Drops a platform-held transfer outright. */
    fun cancelTransfer(downloadId: String)
}
