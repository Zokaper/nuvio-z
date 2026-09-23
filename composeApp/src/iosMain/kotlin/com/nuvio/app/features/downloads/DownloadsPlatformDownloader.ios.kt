package com.nuvio.app.features.downloads

import kotlin.concurrent.Volatile
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
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskState
import platform.Foundation.NSUserDefaults
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0

/**
 * How often native progress reaches the queue.
 *
 * `shouldReportProgress` reports on 512 KB *or* 500 ms, whichever comes first, which at
 * debrid speeds is dozens of reports a second per transfer - each one a repository
 * publish, a recomposition and a Live Activity write on the main queue. The session
 * delivers `didWriteData` far more often than anything can display, so iOS reports on
 * time alone.
 */
private const val NATIVE_PROGRESS_INTERVAL_MS = 1_000L

/** `.44` kept a second copy of the queue and an event journal under these keys. Both are retired. */
private val RETIRED_DEFAULTS_KEYS = listOf(
    "nuvio.downloads.ios_native_queue.v1",
    "nuvio.downloads.ios_native_journal.v1",
)

/** Only touched on the main thread: UIKit hands these over there and expects them called there. */
private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

fun handleDownloadsBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
    backgroundSessionCompletionHandlers[identifier] = completionHandler
    backgroundDownloadManager.setBackgrounded(true)
    backgroundDownloadManager.activate(identifier)
}

/** Retained for binary compatibility with `.42`; normal backgrounding no longer pauses anything. */
fun pauseDownloadsForAppBackground() = Unit
fun resumeDownloadsForAppForeground() = Unit

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    // Nothing on iOS resumes a system pause any more: `.43` made backgrounding leave
    // transfers alone and turned the foreground resume hook into a no-op. A system-paused
    // item therefore has no owner here, and the queue has to take it back itself.
    actual val recoversSystemPauses: Boolean = false
    actual fun freeStorageBytes(): Long = -1L

    actual fun start(request: DownloadPlatformRequest, listener: DownloadTransferListener): DownloadsTaskHandle =
        backgroundDownloadManager.start(request, listener)

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return removePathIfExists(path)
        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        backgroundDownloadManager.cancelForDestination(destinationFileName)
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

    actual fun schedulingDeferredToPlatform(): Boolean = backgroundDownloadManager.isBackgrounded

    actual fun requestTransferInventory(
        onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit,
    ) = backgroundDownloadManager.requestInventory(onResult)

    actual fun suspendTransfer(downloadId: String) = backgroundDownloadManager.suspend(downloadId, notify = false)

    actual fun cancelTransfer(downloadId: String) = backgroundDownloadManager.cancel(downloadId)
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

private class NativeTaskContext(
    val metadata: NativeTaskMetadata,
    val listener: DownloadTransferListener,
    var completed: Boolean = false,
    var lastProgressBytes: Long = -1L,
    var lastProgressAtEpochMs: Long = 0L,
)

private val backgroundDownloadManager by lazy { IosBackgroundDownloadManager() }

/**
 * The app's one background `URLSession`, and the only thing that knows what is really
 * transferring.
 *
 * Every piece of mutable state here is confined to [delegateQueue], the serial queue the
 * session delivers its callbacks on. Callers from any other thread only ever enqueue, so
 * there is no lock: nothing here waits on the repository, and the repository never waits
 * on this queue. `.44` shared this state under a lock across threads and called into the
 * repository from under it.
 *
 * [tasksById] is seeded from `getAllTasks` before anything may create a task, and every
 * task is created through [createTask], so there is never more than one task per
 * download. While the app is active the repository decides what starts; while it is in
 * the background, [advanceIfBackgrounded] fills a freed slot from what the repository
 * already persisted. See `IosBackgroundTransferReconciler` for the ownership rules.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosBackgroundDownloadManager : NSObject(), NSURLSessionDownloadDelegateProtocol {
    private val delegateQueue = NSOperationQueue().apply {
        maxConcurrentOperationCount = 1
        name = "com.nuvio.downloads.session"
    }

    // --- Confined to delegateQueue ------------------------------------------------
    private val tasksById = mutableMapOf<String, NSURLSessionDownloadTask>()
    private val contexts = mutableMapOf<ULong, NativeTaskContext>()
    private val cancelledTaskIds = mutableSetOf<ULong>()
    /** Delivered as finished and waiting for `didCompleteWithError`. */
    private val finishedIds = mutableSetOf<String>()
    /** Stale-source boundaries already reported during this background stay. */
    private val refreshReported = mutableSetOf<String>()
    private var inventoryLoaded = false
    private val awaitingInventory = mutableListOf<() -> Unit>()

    @Volatile
    var isBackgrounded: Boolean = false
        private set

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
        NSURLSession.sessionWithConfiguration(configuration, this, delegateQueue).also { created ->
            created.getAllTasksWithCompletionHandler { tasks ->
                val snapshot = tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>()
                delegateQueue.addOperationWithBlock { loadInventory(snapshot) }
            }
        }
    }

    init {
        RETIRED_DEFAULTS_KEYS.forEach(NSUserDefaults.standardUserDefaults::removeObjectForKey)
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            setBackgrounded(true)
            DownloadsRepository.onPlatformBackground()
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            onBecameActive()
        }
        dispatch_async(dispatch_get_main_queue()) {
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) {
                setBackgrounded(true)
            }
        }
    }

    fun activate(identifier: String) {
        if (identifier == sessionIdentifier) session
    }

    fun setBackgrounded(backgrounded: Boolean) {
        isBackgrounded = backgrounded
        if (backgrounded) whenReady { advanceIfBackgrounded() }
    }

    /**
     * Hands scheduling back to the repository.
     *
     * The order matters: the repository first stops scheduling until it has seen the
     * real tasks, then the flag flips, then it asks for them. Asking before the flip let
     * the answer arrive while scheduling was still deferred, and nothing would start.
     */
    private fun onBecameActive() {
        if (!isBackgrounded) return
        DownloadsRepository.holdSchedulingForPlatformInventory()
        isBackgrounded = false
        delegateQueue.addOperationWithBlock { refreshReported.clear() }
        DownloadsRepository.requestPlatformInventory()
    }

    private fun onQueue(block: () -> Unit) {
        delegateQueue.addOperationWithBlock(block)
    }

    /** Runs [block] on the delegate queue once the session's existing tasks are known. */
    private fun whenReady(block: () -> Unit) {
        session
        onQueue {
            if (inventoryLoaded) block() else awaitingInventory += block
        }
    }

    private fun loadInventory(tasks: List<NSURLSessionDownloadTask>) {
        tasks.forEach { task ->
            val state = task.state
            if (state != NSURLSessionTaskState.NSURLSessionTaskStateRunning &&
                state != NSURLSessionTaskState.NSURLSessionTaskStateSuspended
            ) return@forEach
            val downloadId = NativeTaskMetadata.decode(task.taskDescription)?.downloadId ?: return@forEach
            val existing = tasksById[downloadId]
            if (existing == null) {
                tasksById[downloadId] = task
                return@forEach
            }
            // Only reachable from a build that predates the one-task rule: keep the
            // transfer that has got furthest and drop the other.
            val (keep, drop) = if (task.countOfBytesReceived > existing.countOfBytesReceived) {
                task to existing
            } else {
                existing to task
            }
            tasksById[downloadId] = keep
            cancelledTaskIds += drop.taskIdentifier
            drop.cancel()
        }
        inventoryLoaded = true
        val pending = awaitingInventory.toList()
        awaitingInventory.clear()
        pending.forEach { it() }
    }

    fun requestInventory(onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit) {
        whenReady {
            val live = tasksById.map { (downloadId, task) ->
                IosBackgroundTransferReconciler.LiveTransfer(
                    downloadId = downloadId,
                    running = task.state == NSURLSessionTaskState.NSURLSessionTaskStateRunning,
                    downloadedBytes = task.countOfBytesReceived.coerceAtLeast(0L),
                    totalBytes = task.countOfBytesExpectedToReceive.takeIf { it > 0L },
                )
            }
            onResult(live)
        }
    }

    fun start(request: DownloadPlatformRequest, listener: DownloadTransferListener): DownloadsTaskHandle {
        val handle = IosBackgroundTaskHandle(request.downloadId)
        val metadata = NativeTaskMetadata(request.downloadId, request.destinationFileName, request.knownTotalBytes)
        whenReady {
            tasksById[request.downloadId]?.let { existing ->
                attach(existing, metadata, listener)
                existing.resume()
                return@whenReady
            }

            // Reported, not trusted: the repository checks it against the expected size
            // before calling it complete, so a provider placeholder cannot pass as media.
            val destinationPath = "${downloadsDirectoryPath()}/${request.destinationFileName}"
            fileSizeOrNull(destinationPath)?.takeIf { it > 0L }?.let { bytes ->
                listener.onCompleted(NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath", bytes)
                return@whenReady
            }

            val partPath = "$destinationPath.part"
            if (IosBackgroundTransferReconciler.shouldRestartLegacyPartial(fileSizeOrNull(partPath) ?: 0L, false)) {
                removePathIfExists(partPath)
            }
            val url = NSURL.URLWithString(request.sourceUrl) ?: run {
                // Only an adoption whose task ended in the meantime gets here without a
                // usable URL; failing lets the queue re-mint one.
                listener.onFailed(
                    DownloadFailureReason.SourceExpired,
                    runBlocking { getString(Res.string.download_failed) },
                    0L,
                )
                return@whenReady
            }
            val task = createTask(metadata, buildNativeRequest(url, request.sourceHeaders, request.allowMeteredNetwork))
            attach(task, metadata, listener)
            task.resume()
        }
        return handle
    }

    private fun createTask(metadata: NativeTaskMetadata, request: NSMutableURLRequest): NSURLSessionDownloadTask {
        val task = session.downloadTaskWithRequest(request).apply {
            taskDescription = metadata.encode()
        }
        tasksById[metadata.downloadId] = task
        return task
    }

    private fun attach(
        task: NSURLSessionDownloadTask,
        metadata: NativeTaskMetadata,
        listener: DownloadTransferListener,
    ): NativeTaskContext {
        val context = NativeTaskContext(metadata, listener)
        contexts[task.taskIdentifier] = context
        val bytes = task.countOfBytesReceived.coerceAtLeast(0L)
        val total = task.countOfBytesExpectedToReceive.takeIf { it > 0L } ?: metadata.knownTotalBytes
        listener.onOpened(bytes, total, null, null)
        listener.onProgress(bytes, total)
        context.lastProgressBytes = bytes
        context.lastProgressAtEpochMs = DownloadsClock.nowEpochMs()
        return context
    }

    /**
     * Gives a task nobody is listening to - one started in the background, or found at
     * launch - to the repository, which records it as a transfer it holds.
     */
    private fun claim(task: NSURLSessionDownloadTask, metadata: NativeTaskMetadata, finishing: Boolean): NativeTaskContext? {
        val handle = IosBackgroundTaskHandle(metadata.downloadId)
        return when (val claim = DownloadsRepository.claimNativeTransfer(metadata.downloadId, handle, finishing)) {
            is NativeTransferClaim.Adopted -> attach(task, metadata, claim.listener)
            NativeTransferClaim.Suspend -> {
                task.suspend()
                null
            }
            NativeTransferClaim.Cancel -> {
                cancelTaskLocked(metadata.downloadId, task)
                null
            }
        }
    }

    private fun cancelTaskLocked(downloadId: String, task: NSURLSessionDownloadTask) {
        if (tasksById[downloadId] === task) tasksById.remove(downloadId)
        cancelledTaskIds += task.taskIdentifier
        task.cancel()
    }

    fun suspend(downloadId: String, notify: Boolean) {
        whenReady {
            val task = tasksById[downloadId] ?: return@whenReady
            task.suspend()
            if (notify) {
                contexts[task.taskIdentifier]?.listener?.onPaused(task.countOfBytesReceived.coerceAtLeast(0L))
            }
        }
    }

    fun cancel(downloadId: String) {
        whenReady {
            val task = tasksById[downloadId] ?: return@whenReady
            cancelTaskLocked(downloadId, task)
        }
    }

    fun cancelForDestination(destinationFileName: String) {
        whenReady {
            tasksById.entries
                .filter { NativeTaskMetadata.decode(it.value.taskDescription)?.destinationFileName == destinationFileName }
                .forEach { (downloadId, task) -> cancelTaskLocked(downloadId, task) }
        }
    }

    /**
     * Fills freed slots while the repository cannot.
     *
     * Runs when the app goes to the background and each time a transfer ends there.
     * Only items that already hold a fresh source URL can start: re-minting one is a
     * network round trip the suspended app cannot make. The first stale item is a
     * boundary, not something to skip, so episodes never finish out of order.
     */
    private fun advanceIfBackgrounded() {
        if (!isBackgrounded || !inventoryLoaded) return
        val snapshot = DownloadsRepository.nativeSchedulingSnapshot()
        val running = tasksById.filterValues { it.state == NSURLSessionTaskState.NSURLSessionTaskStateRunning }.keys
        val suspended = tasksById.filterValues { it.state == NSURLSessionTaskState.NSURLSessionTaskStateSuspended }.keys
        val plan = IosBackgroundTransferReconciler.scheduleNextTransfers(
            maxConcurrent = DownloadsRepository.MAX_CONCURRENT_TRANSFERS,
            runningIds = running,
            claimedIds = snapshot.claimedIds,
            suspendedIds = suspended,
            finishedIds = finishedIds,
            preparedQueue = snapshot.prepared,
            nowEpochMs = DownloadsClock.nowEpochMs(),
        )
        plan.toStart.forEach { candidate ->
            val resume = candidate.downloadId in plan.toResume
            val url = if (resume) null else NSURL.URLWithString(candidate.sourceUrl) ?: return@forEach
            val metadata = NativeTaskMetadata(candidate.downloadId, candidate.destinationFileName, candidate.knownTotalBytes)
            val handle = IosBackgroundTaskHandle(candidate.downloadId)
            val claim = DownloadsRepository.claimNativeTransfer(candidate.downloadId, handle, finishing = false)
            if (claim !is NativeTransferClaim.Adopted) return@forEach
            val task = tasksById[candidate.downloadId]?.takeIf { resume }
                ?: createTask(
                    metadata,
                    buildNativeRequest(
                        url ?: return@forEach,
                        candidate.sourceHeaders,
                        candidate.allowMeteredNetwork,
                    ),
                )
            attach(task, metadata, claim.listener)
            task.resume()
        }
        plan.refreshBoundary?.let { boundary ->
            if (refreshReported.add(boundary.downloadId)) {
                DownloadsRepository.onNativeSourceRefreshNeeded(boundary.downloadId)
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
        val context = contexts[downloadTask.taskIdentifier]
            ?: claim(downloadTask, metadata, finishing = false)
            ?: return
        val total = totalBytesExpectedToWrite.takeIf { it > 0L } ?: metadata.knownTotalBytes
        val now = DownloadsClock.nowEpochMs()
        if (now - context.lastProgressAtEpochMs >= NATIVE_PROGRESS_INTERVAL_MS ||
            (total != null && totalBytesWritten >= total)
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
        val context = contexts[downloadTask.taskIdentifier]
            ?: claim(downloadTask, metadata, finishing = true)
            ?: return
        val response = downloadTask.response as? NSHTTPURLResponse
        val statusCode = response?.statusCode?.toInt() ?: 200
        if (statusCode !in 200..299) {
            context.fail(failureReasonForHttpStatus(statusCode), runBlocking {
                getString(Res.string.network_request_failed_http, statusCode)
            }, downloadTask.countOfBytesReceived.coerceAtLeast(0L))
            return
        }
        val sourcePath = didFinishDownloadingToURL.path ?: return
        val destinationPath = "${downloadsDirectoryPath()}/${metadata.destinationFileName}"
        val partPath = "$destinationPath.part"
        removePathIfExists(partPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(sourcePath, partPath, null)) {
            context.fail(DownloadFailureReason.Transient, runBlocking {
                getString(Res.string.downloads_error_finalize_file_failed)
            }, 0L)
            return
        }
        val bytes = fileSizeOrNull(partPath) ?: 0L
        val responseTotal = response?.valueForHTTPHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        val expected = responseTotal ?: metadata.knownTotalBytes
        when (evaluateCompletion(bytes, expected)) {
            is DownloadCompletion.Short -> {
                context.fail(DownloadFailureReason.Incomplete, runBlocking {
                    getString(Res.string.downloads_error_incomplete_transfer)
                }, bytes)
                return
            }
            is DownloadCompletion.Overrun -> {
                removePathIfExists(partPath)
                context.fail(DownloadFailureReason.SourceChanged, runBlocking {
                    getString(Res.string.downloads_error_source_changed)
                }, 0L)
                return
            }
            DownloadCompletion.Complete -> Unit
        }
        removePathIfExists(destinationPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(partPath, destinationPath, null)) {
            context.fail(DownloadFailureReason.Transient, runBlocking {
                getString(Res.string.downloads_error_finalize_file_failed)
            }, bytes)
            return
        }
        val uri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
        context.completed = true
        finishedIds += metadata.downloadId
        // The repository still checks the size against what was expected; a small
        // placeholder is failed and retried there, never recorded as the episode.
        context.listener.onCompleted(uri, bytes)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val metadata = NativeTaskMetadata.decode(task.taskDescription) ?: return
        val downloadTask = task as? NSURLSessionDownloadTask
        val cancelled = cancelledTaskIds.remove(task.taskIdentifier)
        var context = contexts.remove(task.taskIdentifier)
        if (downloadTask != null && tasksById[metadata.downloadId] === downloadTask) {
            tasksById.remove(metadata.downloadId)
        }
        if (didCompleteWithError != null && !cancelled && context?.completed != true) {
            if (context == null && downloadTask != null) {
                context = claim(downloadTask, metadata, finishing = true)
                contexts.remove(task.taskIdentifier)
            }
            context?.fail(
                DownloadFailureReason.Transient,
                didCompleteWithError.localizedDescription.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                task.countOfBytesReceived.coerceAtLeast(0L),
            )
        }
        finishedIds.remove(metadata.downloadId)
        advanceIfBackgrounded()
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        dispatch_async(dispatch_get_main_queue()) {
            backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
        }
    }

    private fun NativeTaskContext.fail(reason: DownloadFailureReason, message: String, bytes: Long) {
        completed = true
        listener.onFailed(reason, message, bytes)
    }
}

/** What stopping a transfer means on iOS: suspend the task so its bytes survive. */
private class IosBackgroundTaskHandle(private val downloadId: String) : DownloadsTaskHandle {
    override fun cancel() {
        backgroundDownloadManager.suspend(downloadId, notify = true)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun buildNativeRequest(
    url: NSURL,
    sourceHeaders: Map<String, String>,
    allowMeteredNetwork: Boolean,
): NSMutableURLRequest =
    NSMutableURLRequest(
        url, NSURLRequestReloadIgnoringLocalCacheData,
        DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    ).apply {
        setHTTPMethod("GET")
        setAllowsCellularAccess(allowMeteredNetwork)
        setAllowsExpensiveNetworkAccess(allowMeteredNetwork)
        setAllowsConstrainedNetworkAccess(allowMeteredNetwork)
        sourceHeaders.forEach { (key, value) -> setValue(value, key) }
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
