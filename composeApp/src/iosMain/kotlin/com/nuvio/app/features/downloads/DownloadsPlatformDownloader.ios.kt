package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.downloads_error_incomplete_transfer
import nuvio.composeapp.generated.resources.downloads_error_source_changed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.darwin.NSObject

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0

private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

fun handleDownloadsBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
    backgroundSessionCompletionHandlers[identifier] = completionHandler
    IosBackgroundDownloadManager.activate(identifier)
}

/** Retained for binary compatibility with `.42`; normal backgrounding no longer pauses anything. */
fun pauseDownloadsForAppBackground() = Unit
fun resumeDownloadsForAppForeground() = Unit

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual val recoversSystemPauses: Boolean = true
    actual fun freeStorageBytes(): Long = -1L

    actual fun start(request: DownloadPlatformRequest, listener: DownloadTransferListener): DownloadsTaskHandle =
        IosBackgroundDownloadManager.start(request, listener)

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return removePathIfExists(path)
        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        IosBackgroundDownloadManager.cancelForDestination(destinationFileName)
        return removePathIfExists("${downloadsDirectoryPath()}/$destinationFileName.part")
    }

    actual fun partialFileBytes(destinationFileName: String): Long =
        fileSizeOrNull("${downloadsDirectoryPath()}/$destinationFileName.part")?.coerceAtLeast(0L) ?: 0L

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()?.takeIf(NSFileManager.defaultManager::fileExistsAtPath)?.let {
            return NSURL.fileURLWithPath(it).absoluteString ?: "file://$it"
        }
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val path = "${downloadsDirectoryPath()}/$fileName"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        } else null
    }

    actual fun openDownloadsDirectory(): Boolean {
        UIApplication.sharedApplication.openURL(
            NSURL.fileURLWithPath(downloadsDirectoryPath()), emptyMap<Any?, Any>(), null,
        )
        return true
    }
}

private data class NativeTaskMetadata(
    val downloadId: String,
    val destinationFileName: String,
    val knownTotalBytes: Long?,
) {
    fun encode(): String = listOf(
        "nuvio-v1", escape(downloadId), escape(destinationFileName), knownTotalBytes?.toString().orEmpty(),
    ).joinToString("|")

    companion object {
        fun decode(value: String?): NativeTaskMetadata? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 4 || parts[0] != "nuvio-v1") return null
            return NativeTaskMetadata(unescape(parts[1]), unescape(parts[2]), parts[3].toLongOrNull())
        }
        private fun escape(value: String) = value.replace("%", "%25").replace("|", "%7C")
        private fun unescape(value: String) = value.replace("%7C", "|").replace("%25", "%")
    }
}

private data class NativeTaskContext(
    val metadata: NativeTaskMetadata,
    val listener: DownloadTransferListener,
    var opened: Boolean = false,
    var completed: Boolean = false,
    var lastProgressBytes: Long = -1L,
    var lastProgressAtEpochMs: Long = 0L,
)

@OptIn(ExperimentalForeignApi::class)
private object IosBackgroundDownloadManager : NSObject(), NSURLSessionDownloadDelegateProtocol {
    private val stateLock = SynchronizedObject()
    private val contexts = mutableMapOf<ULong, NativeTaskContext>()
    private val cancelledTaskIds = mutableSetOf<ULong>()
    private val sessionIdentifier = IosBackgroundTransferReconciler.sessionIdentifier(
        NSBundle.mainBundle.bundleIdentifier,
    )
    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration
            .backgroundSessionConfigurationWithIdentifier(sessionIdentifier)
            .apply {
                timeoutIntervalForRequest = DOWNLOAD_REQUEST_TIMEOUT_SECONDS
                timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
                waitsForConnectivity = true
                allowsCellularAccess = true
                allowsExpensiveNetworkAccess = true
                allowsConstrainedNetworkAccess = true
                sessionSendsLaunchEvents = true
            }
        NSURLSession.sessionWithConfiguration(
            configuration,
            this,
            NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
        )
    }

    fun activate(identifier: String) {
        if (identifier == sessionIdentifier) session
    }

    fun start(request: DownloadPlatformRequest, listener: DownloadTransferListener): DownloadsTaskHandle {
        val handle = IosBackgroundTaskHandle(request.downloadId)
        val metadata = NativeTaskMetadata(request.downloadId, request.destinationFileName, request.knownTotalBytes)
        session.getAllTasksWithCompletionHandler { tasks ->
            val existing = tasks.orEmpty()
                .filterIsInstance<NSURLSessionDownloadTask>()
                .firstOrNull { NativeTaskMetadata.decode(it.taskDescription)?.downloadId == request.downloadId }
            if (existing != null) {
                attach(existing, metadata, listener, handle)
                existing.resume()
                return@getAllTasksWithCompletionHandler
            }

            val destinationPath = "${downloadsDirectoryPath()}/${request.destinationFileName}"
            fileSizeOrNull(destinationPath)?.takeIf { it > 0L }?.let { bytes ->
                listener.onCompleted(NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath", bytes)
                return@getAllTasksWithCompletionHandler
            }

            val partPath = "$destinationPath.part"
            if (IosBackgroundTransferReconciler.shouldRestartLegacyPartial(fileSizeOrNull(partPath) ?: 0L, false)) {
                removePathIfExists(partPath)
            }
            val task = session.downloadTaskWithRequest(buildNativeRequest(request)).apply {
                taskDescription = metadata.encode()
            }
            attach(task, metadata, listener, handle)
            listener.onOpened(0L, request.knownTotalBytes, null, null)
            listener.onProgress(0L, request.knownTotalBytes)
            task.resume()
        }
        return handle
    }

    private fun attach(
        task: NSURLSessionDownloadTask,
        metadata: NativeTaskMetadata,
        listener: DownloadTransferListener,
        handle: IosBackgroundTaskHandle,
    ) {
        val context = NativeTaskContext(metadata, listener)
        synchronized(stateLock) { contexts[task.taskIdentifier] = context }
        handle.attach(task)
        val bytes = task.countOfBytesReceived.coerceAtLeast(0L)
        val total = task.countOfBytesExpectedToReceive.takeIf { it > 0L } ?: metadata.knownTotalBytes
        listener.onOpened(bytes, total, null, null)
        listener.onProgress(bytes, total)
        context.opened = true
    }

    fun suspend(downloadId: String) {
        session.getAllTasksWithCompletionHandler { tasks ->
            tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>().forEach { task ->
                if (NativeTaskMetadata.decode(task.taskDescription)?.downloadId == downloadId) {
                    task.suspend()
                    synchronized(stateLock) { contexts[task.taskIdentifier] }
                        ?.listener?.onPaused(task.countOfBytesReceived.coerceAtLeast(0L))
                }
            }
        }
    }

    fun cancelForDestination(destinationFileName: String) {
        session.getAllTasksWithCompletionHandler { tasks ->
            tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>().forEach { task ->
                if (NativeTaskMetadata.decode(task.taskDescription)?.destinationFileName == destinationFileName) {
                    synchronized(stateLock) { cancelledTaskIds += task.taskIdentifier }
                    task.cancel()
                }
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val metadata = NativeTaskMetadata.decode(downloadTask.taskDescription) ?: return
        val total = totalBytesExpectedToWrite.takeIf { it > 0L } ?: metadata.knownTotalBytes
        val context = synchronized(stateLock) { contexts[downloadTask.taskIdentifier] }
        if (context == null) {
            DownloadsRepository.reconcileIosBackgroundProgress(metadata.downloadId, totalBytesWritten, total)
            return
        }
        if (!context.opened) {
            context.listener.onOpened(0L, total, null, null)
            context.opened = true
        }
        val now = DownloadsClock.nowEpochMs()
        if (context.lastProgressBytes < 0L || shouldReportProgress(
                totalBytesWritten, context.lastProgressBytes, now, context.lastProgressAtEpochMs,
            ) || (total != null && totalBytesWritten >= total)
        ) {
            context.lastProgressBytes = totalBytesWritten
            context.lastProgressAtEpochMs = now
            context.listener.onProgress(totalBytesWritten.coerceAtLeast(0L), total)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val metadata = NativeTaskMetadata.decode(downloadTask.taskDescription) ?: return
        val response = downloadTask.response as? NSHTTPURLResponse
        val statusCode = response?.statusCode?.toInt() ?: 200
        val context = synchronized(stateLock) { contexts[downloadTask.taskIdentifier] }
        if (statusCode !in 200..299) {
            deliverFailure(metadata, context, failureReasonForHttpStatus(statusCode), runBlocking {
                getString(Res.string.network_request_failed_http, statusCode)
            }, downloadTask.countOfBytesReceived.coerceAtLeast(0L))
            return
        }
        val sourcePath = didFinishDownloadingToURL.path ?: return
        val destinationPath = "${downloadsDirectoryPath()}/${metadata.destinationFileName}"
        val partPath = "$destinationPath.part"
        removePathIfExists(partPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(sourcePath, partPath, null)) {
            deliverFailure(metadata, context, DownloadFailureReason.Transient, runBlocking {
                getString(Res.string.downloads_error_finalize_file_failed)
            }, 0L)
            return
        }
        val bytes = fileSizeOrNull(partPath) ?: 0L
        val responseTotal = response?.valueForHTTPHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        val expected = responseTotal ?: metadata.knownTotalBytes
        when (evaluateCompletion(bytes, expected)) {
            is DownloadCompletion.Short -> {
                deliverFailure(metadata, context, DownloadFailureReason.Incomplete, runBlocking {
                    getString(Res.string.downloads_error_incomplete_transfer)
                }, bytes)
                return
            }
            is DownloadCompletion.Overrun -> {
                removePathIfExists(partPath)
                deliverFailure(metadata, context, DownloadFailureReason.SourceChanged, runBlocking {
                    getString(Res.string.downloads_error_source_changed)
                }, 0L)
                return
            }
            DownloadCompletion.Complete -> Unit
        }
        removePathIfExists(destinationPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(partPath, destinationPath, null)) {
            deliverFailure(metadata, context, DownloadFailureReason.Transient, runBlocking {
                getString(Res.string.downloads_error_finalize_file_failed)
            }, bytes)
            return
        }
        val uri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
        if (context != null) context.listener.onCompleted(uri, bytes)
        else DownloadsRepository.reconcileIosBackgroundCompletion(metadata.downloadId, uri, bytes)
        context?.completed = true
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val metadata = NativeTaskMetadata.decode(task.taskDescription) ?: return
        val context = synchronized(stateLock) { contexts.remove(task.taskIdentifier) }
        val cancelled = synchronized(stateLock) { cancelledTaskIds.remove(task.taskIdentifier) }
        if (didCompleteWithError != null && !cancelled && context?.completed != true) {
            deliverFailure(
                metadata, context, DownloadFailureReason.Transient,
                didCompleteWithError.localizedDescription.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                task.countOfBytesReceived.coerceAtLeast(0L),
            )
        }
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
    }

    private fun deliverFailure(
        metadata: NativeTaskMetadata,
        context: NativeTaskContext?,
        reason: DownloadFailureReason,
        message: String,
        bytes: Long,
    ) {
        if (context != null) context.listener.onFailed(reason, message, bytes)
        else DownloadsRepository.reconcileIosBackgroundFailure(metadata.downloadId, reason, message, bytes)
    }
}

private class IosBackgroundTaskHandle(private val downloadId: String) : DownloadsTaskHandle {
    private var task: NSURLSessionDownloadTask? = null
    fun attach(task: NSURLSessionDownloadTask) { this.task = task }
    override fun cancel() { IosBackgroundDownloadManager.suspend(downloadId) }
}

@OptIn(ExperimentalForeignApi::class)
private fun buildNativeRequest(request: DownloadPlatformRequest): NSMutableURLRequest =
    NSMutableURLRequest(
        NSURL(string = request.sourceUrl), NSURLRequestReloadIgnoringLocalCacheData,
        DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    ).apply {
        setHTTPMethod("GET")
        setAllowsCellularAccess(request.allowMeteredNetwork)
        setAllowsExpensiveNetworkAccess(request.allowMeteredNetwork)
        setAllowsConstrainedNetworkAccess(request.allowMeteredNetwork)
        request.sourceHeaders.forEach { (key, value) -> setValue(value, key) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val path = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean =
    !NSFileManager.defaultManager.fileExistsAtPath(path) || NSFileManager.defaultManager.removeItemAtPath(path, null)

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val value = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get("NSFileSize")
    return (value as? Number)?.toLong()
}

private fun String.toLocalPath(): String? {
    val value = trim()
    return if (value.startsWith("file:")) NSURL(string = value).path ?: value.removePrefix("file://")
    else value.takeIf { it.isNotBlank() }
}
