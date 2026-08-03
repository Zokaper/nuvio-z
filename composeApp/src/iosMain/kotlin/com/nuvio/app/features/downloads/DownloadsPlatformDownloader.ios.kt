package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.downloads_error_incomplete_transfer
import nuvio.composeapp.generated.resources.downloads_error_open_partial_file_failed
import nuvio.composeapp.generated.resources.downloads_error_partial_file_not_open
import nuvio.composeapp.generated.resources.downloads_error_source_changed
import nuvio.composeapp.generated.resources.downloads_error_write_partial_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSError
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0

private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

fun handleDownloadsBackgroundEvents(
    identifier: String,
    completionHandler: () -> Unit,
) {
    backgroundSessionCompletionHandlers[identifier] = completionHandler
}

fun pauseDownloadsForAppBackground() {
    DownloadsRepository.pauseActiveDownloads()
}

/**
 * Restarts the transfers that going to the background stopped.
 *
 * Without this counterpart to [pauseDownloadsForAppBackground], switching away from
 * the app once left every download paused for good, since nothing else ever resumes
 * them. Downloads the user paused by hand are left alone.
 */
fun resumeDownloadsForAppForeground() {
    DownloadsRepository.resumeSystemPausedDownloads()
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun freeStorageBytes(): Long = -1L

    actual fun start(
        request: DownloadPlatformRequest,
        listener: DownloadTransferListener,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val handle = IosDownloadsTaskHandle(job)

        scope.launch {
            val downloadsDirectory = downloadsDirectoryPath()
            val destinationPath = "$downloadsDirectory/${request.destinationFileName}"
            val tempPath = "$downloadsDirectory/${request.destinationFileName}.part"

            try {
                var resumeFromBytes = fileSizeOrNull(tempPath)?.coerceAtLeast(0L) ?: 0L

                var attemptedRangeRequest = resumeFromBytes > 0L
                var result = performDownloadRequest(
                    request = request,
                    rangeStart = if (attemptedRangeRequest) resumeFromBytes else null,
                    resumeFromBytes = resumeFromBytes,
                    tempPath = tempPath,
                    handle = handle,
                    listener = listener,
                )

                if (attemptedRangeRequest && result.statusCode == 416) {
                    // The range starts past the end of the object. When that is because
                    // the partial file already holds every byte, the download is done and
                    // fetching it again from zero would throw away a finished transfer.
                    val reportedTotal = parseContentRangeTotal(result.contentRange)
                        ?: request.knownTotalBytes
                    val partialBytes = fileSizeOrNull(tempPath) ?: 0L

                    if (reportedTotal != null && partialBytes == reportedTotal) {
                        finalizeOrFail(tempPath, destinationPath, listener, partialBytes)
                        return@launch
                    }

                    removePathIfExists(tempPath)
                    resumeFromBytes = 0L
                    attemptedRangeRequest = false
                    result = performDownloadRequest(
                        request = request,
                        rangeStart = null,
                        resumeFromBytes = 0L,
                        tempPath = tempPath,
                        handle = handle,
                        listener = listener,
                    )
                }

                if (result.statusCode !in 200..299) {
                    listener.onFailed(
                        failureReasonForHttpStatus(result.statusCode),
                        runBlocking {
                            getString(Res.string.network_request_failed_http, result.statusCode)
                        },
                        fileSizeOrNull(tempPath) ?: 0L,
                    )
                    return@launch
                }

                // Reaching the end of the body is not the same as having the whole file:
                // a dropped connection or a short error body ends the stream just as
                // cleanly as a finished download does.
                when (val completion = evaluateCompletion(result.downloadedBytes, result.totalBytes)) {
                    is DownloadCompletion.Short -> {
                        listener.onFailed(
                            DownloadFailureReason.Incomplete,
                            runBlocking { getString(Res.string.downloads_error_incomplete_transfer) },
                            completion.downloadedBytes,
                        )
                        return@launch
                    }

                    is DownloadCompletion.Overrun -> {
                        removePathIfExists(tempPath)
                        listener.onFailed(
                            DownloadFailureReason.SourceChanged,
                            runBlocking { getString(Res.string.downloads_error_source_changed) },
                            0L,
                        )
                        return@launch
                    }

                    DownloadCompletion.Complete -> Unit
                }

                finalizeOrFail(tempPath, destinationPath, listener, result.downloadedBytes)
            } catch (cancellation: CancellationException) {
                // A pause is a deliberate stop, not a failure.
                handle.cancelNativeTask()
                listener.onPaused(fileSizeOrNull(tempPath) ?: 0L)
                throw cancellation
            } catch (error: Throwable) {
                listener.onFailed(
                    DownloadFailureReason.Transient,
                    error.message ?: runBlocking { getString(Res.string.download_failed) },
                    fileSizeOrNull(tempPath) ?: 0L,
                )
            }
        }

        return handle
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path)
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempPath = "${downloadsDirectoryPath()}/$destinationFileName.part"
        return removePathIfExists(tempPath)
    }

    actual fun partialFileBytes(destinationFileName: String): Long {
        val tempPath = "${downloadsDirectoryPath()}/$destinationFileName.part"
        return fileSizeOrNull(tempPath)?.coerceAtLeast(0L) ?: 0L
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { path ->
                return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
            }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val currentPath = "${downloadsDirectoryPath()}/$fileName"
        return if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
            NSURL.fileURLWithPath(currentPath).absoluteString ?: "file://$currentPath"
        } else {
            null
        }
    }

    actual fun openDownloadsDirectory(): Boolean {
        val url = NSURL.fileURLWithPath(downloadsDirectoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }
}

/** Moves a verified partial file into place, or reports why it could not be moved. */
@OptIn(ExperimentalForeignApi::class)
private fun finalizeOrFail(
    tempPath: String,
    destinationPath: String,
    listener: DownloadTransferListener,
    downloadedBytes: Long,
) {
    removePathIfExists(destinationPath)
    val moved = NSFileManager.defaultManager.moveItemAtPath(
        srcPath = tempPath,
        toPath = destinationPath,
        error = null,
    )
    if (!moved) {
        listener.onFailed(
            DownloadFailureReason.Transient,
            runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
            downloadedBytes,
        )
        return
    }

    val localFileUri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
    val finalSize = fileSizeOrNull(destinationPath) ?: downloadedBytes
    listener.onCompleted(localFileUri, finalSize)
}

private class IosDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    private var task: NSURLSessionTask? = null
    private var session: NSURLSession? = null

    fun attach(task: NSURLSessionTask, session: NSURLSession) {
        this.task = task
        this.session = session
    }

    override fun cancel() {
        cancelNativeTask()
        job.cancel()
    }

    fun cancelNativeTask() {
        task?.cancel()
        session?.invalidateAndCancel()
        task = null
        session = null
    }
}

private data class IosDownloadResult(
    val statusCode: Int,
    val contentRange: String?,
    val contentLength: Long?,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Bytes on disk once the stream ended, used to verify the transfer finished. */
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
)

@OptIn(ExperimentalForeignApi::class)
private class IosDownloadDelegate(
    private val attemptedRangeRequest: Boolean,
    private val resumeFromBytes: Long,
    private val tempPath: String,
    private val listener: DownloadTransferListener,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val completion = CompletableDeferred<IosDownloadResult>()
    private var result: IosDownloadResult? = null
    private var fileError: Throwable? = null
    private var outputFile: CPointer<FILE>? = null
    private var startingBytesForResponse = 0L
    private var bytesWrittenForResponse = 0L
    private var totalBytesForResponse: Long? = null
    private var lastProgressBytes = -1L
    private var lastProgressAtEpochMs = 0L
    private var bytesSinceFlush = 0L

    suspend fun awaitCompletion(): IosDownloadResult = completion.await()

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (Long) -> Unit,
    ) {
        val httpResponse = didReceiveResponse as? NSHTTPURLResponse
        val statusCode = httpResponse?.statusCode?.toInt() ?: 200
        val nextResult = IosDownloadResult(
            statusCode = statusCode,
            contentRange = httpResponse?.valueForHTTPHeaderField("Content-Range"),
            contentLength = httpResponse
                ?.valueForHTTPHeaderField("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0L },
            etag = httpResponse?.valueForHTTPHeaderField("ETag"),
            lastModified = httpResponse?.valueForHTTPHeaderField("Last-Modified"),
        )
        result = nextResult

        if (statusCode in 200..299) {
            val isPartialResume = attemptedRangeRequest && statusCode == 206 && resumeFromBytes > 0L
            startingBytesForResponse = if (isPartialResume) resumeFromBytes else 0L
            bytesWrittenForResponse = 0L
            totalBytesForResponse = resolveTotalBytes(
                startingBytes = startingBytesForResponse,
                isPartialResume = isPartialResume,
                contentRangeHeader = nextResult.contentRange,
                contentLength = nextResult.contentLength,
            )

            // A 200 answer to a range request means the server either ignores ranges or
            // has told us, via If-Range, that the object changed. Either way the bytes
            // already on disk do not belong to this response, so the file restarts.
            outputFile = fopen(tempPath, if (isPartialResume) "ab" else "wb") ?: run {
                fileError = IllegalStateException(
                    runBlocking { getString(Res.string.downloads_error_open_partial_file_failed) },
                )
                null
            }

            listener.onOpened(
                resumedFromBytes = startingBytesForResponse,
                totalBytes = totalBytesForResponse,
                etag = nextResult.etag,
                lastModified = nextResult.lastModified,
            )
            reportProgress(startingBytesForResponse, totalBytesForResponse)
        }

        completionHandler(1L)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        if (fileError != null) return

        val file = outputFile ?: run {
            fileError = IllegalStateException(
                runBlocking { getString(Res.string.downloads_error_partial_file_not_open) },
            )
            return
        }

        val bytesToWrite = didReceiveData.length.toLong()
        val wrote = fwrite(
            didReceiveData.bytes,
            1.convert(),
            bytesToWrite.convert(),
            file,
        ).toLong()
        if (wrote != bytesToWrite) {
            fileError = IllegalStateException(
                runBlocking { getString(Res.string.downloads_error_write_partial_file_failed) },
            )
            return
        }

        // fwrite buffers in user space, so the partial file only reflects what has been
        // flushed. Flushing periodically keeps its length close to the reported byte
        // count, which is what a resume continues from.
        bytesSinceFlush += bytesToWrite
        if (bytesSinceFlush >= PARTIAL_FLUSH_BYTE_DELTA) {
            fflush(file)
            bytesSinceFlush = 0L
        }

        bytesWrittenForResponse += bytesToWrite
        reportProgress(
            downloadedBytes = startingBytesForResponse + bytesWrittenForResponse,
            totalBytes = totalBytesForResponse,
        )
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        closeOutputFile()

        if (didCompleteWithError != null) {
            completion.completeExceptionally(
                IllegalStateException(didCompleteWithError.localizedDescription),
            )
            return
        }

        val error = fileError
        if (error != null) {
            completion.completeExceptionally(error)
            return
        }

        val settled = result ?: task.response.toDownloadResult()
        completion.complete(
            settled.copy(
                downloadedBytes = startingBytesForResponse + bytesWrittenForResponse,
                totalBytes = totalBytesForResponse,
            ),
        )
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
    }

    private fun closeOutputFile() {
        outputFile?.let { file ->
            fflush(file)
            fclose(file)
        }
        outputFile = null
        bytesSinceFlush = 0L
    }

    private fun reportProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        val normalizedDownloadedBytes = downloadedBytes.coerceAtLeast(0L)
        val nowEpochMs = DownloadsClock.nowEpochMs()
        val reachedEnd = totalBytes != null && normalizedDownloadedBytes >= totalBytes

        if (
            lastProgressBytes >= 0L &&
            !reachedEnd &&
            !shouldReportProgress(
                downloadedBytes = normalizedDownloadedBytes,
                lastReportedBytes = lastProgressBytes,
                nowEpochMs = nowEpochMs,
                lastReportedAtEpochMs = lastProgressAtEpochMs,
            )
        ) {
            return
        }

        lastProgressBytes = normalizedDownloadedBytes
        lastProgressAtEpochMs = nowEpochMs
        listener.onProgress(normalizedDownloadedBytes, totalBytes)
    }
}

private fun NSURLResponse?.toDownloadResult(): IosDownloadResult {
    val httpResponse = this as? NSHTTPURLResponse
    return IosDownloadResult(
        statusCode = httpResponse?.statusCode?.toInt() ?: 200,
        contentRange = httpResponse?.valueForHTTPHeaderField("Content-Range"),
        contentLength = httpResponse
            ?.valueForHTTPHeaderField("Content-Length")
            ?.toLongOrNull()
            ?.takeIf { it > 0L },
        etag = httpResponse?.valueForHTTPHeaderField("ETag"),
        lastModified = httpResponse?.valueForHTTPHeaderField("Last-Modified"),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun performDownloadRequest(
    request: DownloadPlatformRequest,
    rangeStart: Long?,
    resumeFromBytes: Long,
    tempPath: String,
    handle: IosDownloadsTaskHandle,
    listener: DownloadTransferListener,
): IosDownloadResult {
    val url = NSURL(string = request.sourceUrl)
    val nativeRequest = NSMutableURLRequest(
        uRL = url,
        cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
        timeoutInterval = DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    )
    nativeRequest.setHTTPMethod("GET")
    nativeRequest.setAllowsCellularAccess(true)
    nativeRequest.setAllowsExpensiveNetworkAccess(true)
    nativeRequest.setAllowsConstrainedNetworkAccess(true)
    request.sourceHeaders.forEach { (key, value) ->
        nativeRequest.setValue(value, forHTTPHeaderField = key)
    }
    if (rangeStart != null && rangeStart > 0L) {
        nativeRequest.setValue("bytes=$rangeStart-", forHTTPHeaderField = "Range")
        // Without a validator the server cannot tell us the bytes it is about to send
        // belong to a different file than the partial one on disk.
        resumeValidator(request.resumeEtag, request.resumeLastModified)?.let {
            nativeRequest.setValue(it, forHTTPHeaderField = "If-Range")
        }
    }

    val delegate = IosDownloadDelegate(
        attemptedRangeRequest = rangeStart != null && rangeStart > 0L,
        resumeFromBytes = resumeFromBytes,
        tempPath = tempPath,
        listener = listener,
    )
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        timeoutIntervalForRequest = DOWNLOAD_REQUEST_TIMEOUT_SECONDS
        timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
        waitsForConnectivity = true
        allowsCellularAccess = true
        allowsExpensiveNetworkAccess = true
        allowsConstrainedNetworkAccess = true
    }
    val session = NSURLSession.sessionWithConfiguration(
        configuration = configuration,
        delegate = delegate,
        delegateQueue = NSOperationQueue().apply {
            maxConcurrentOperationCount = 1
        },
    )
    val task = session.dataTaskWithRequest(nativeRequest)

    handle.attach(task, session)
    listener.onProgress(resumeFromBytes.coerceAtLeast(0L), request.knownTotalBytes)
    task.resume()

    return try {
        delegate.awaitCompletion()
    } finally {
        session.finishTasksAndInvalidate()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}
