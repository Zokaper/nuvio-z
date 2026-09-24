package com.nuvio.app.features.downloads

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.downloads_error_incomplete_transfer
import nuvio.composeapp.generated.resources.downloads_error_source_changed
import nuvio.composeapp.generated.resources.downloads_error_stalled
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
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskMetrics
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSURLSessionTaskTransactionMetrics
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

/**
 * How many queued downloads are resolved and handed to the background session while Nuvio
 * is in the foreground.
 *
 * This is a submission window, not a concurrency limit: the system decides how many of
 * these actually move bytes at once. It exists because a task created while the app is
 * suspended is discretionary and rate-limited - each background wake grows the delay, and
 * only returning to the foreground resets it - so whatever the queue has not submitted
 * before the phone locks mostly waits for the next unlock. Apple's guidance is to start
 * many tasks at once in one session.
 *
 * The window is one global queue: films, episodes and several shows share it in queue
 * order. Twelve covers a normal season, or a mixed queue, in one hand-off. The bound is
 * set by source links rather than by transfers: every item in the window mints a debrid
 * link now, and a link minted now is only certain to work if its request starts soon,
 * which the system does not promise for a task far down a long list.
 */
private const val IOS_SUBMISSION_WINDOW = 12

/** Task priority hints, highest for the head of the queue. Hints only: nothing relies on them. */
private const val HEAD_TASK_PRIORITY = 0.9f
private const val TASK_PRIORITY_STEP = 0.05f
private const val MIN_TASK_PRIORITY = 0.1f

/** `NSURLErrorCancelled`. */
private const val NSURL_ERROR_CANCELLED = -999L
/** `NSURLErrorBackgroundTaskCancelledReasonKey`: why the system, not Nuvio, cancelled a task. */
private const val BACKGROUND_CANCEL_REASON_KEY = "NSURLErrorBackgroundTaskCancelledReasonKey"

/** `.44` kept a second copy of the queue and an event journal under these keys. Both are retired. */
private val RETIRED_DEFAULTS_KEYS = listOf(
    "nuvio.downloads.ios_native_queue.v1",
    "nuvio.downloads.ios_native_journal.v1",
)

/** Only touched on the main thread: UIKit hands these over there and expects them called there. */
private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

fun handleDownloadsBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
    DownloadsProbeLog.event("wake")
    backgroundSessionCompletionHandlers[identifier] = completionHandler
    backgroundDownloadManager.setBackgrounded(true)
    backgroundDownloadManager.activate(identifier)
}

/** Retained for binary compatibility with `.42`; normal backgrounding no longer pauses anything. */
fun pauseDownloadsForAppBackground() = Unit
fun resumeDownloadsForAppForeground() = Unit

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    // The background session owns the transfers: the window of 12 is what keeps a queue moving
    // while locked (`.46`), a submitted task may wait inside the system as long as it likes, and
    // nothing here resumes a system pause (`.43`). See TransferHost.SystemOwned.
    actual val transferHost: TransferHost = TransferHost.SystemOwned(
        window = IOS_SUBMISSION_WINDOW,
        coordinator = IosSystemTransferCoordinator,
    )
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

}

/** The session half of the system-owned model; the engine half is [SystemOwnedTransfers]. */
private object IosSystemTransferCoordinator : SystemTransferCoordinator {
    override fun isBackgrounded(): Boolean = backgroundDownloadManager.isBackgrounded

    override fun requestInventory(onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit) =
        backgroundDownloadManager.requestInventory(onResult)

    override fun suspend(downloadId: String) = backgroundDownloadManager.suspend(downloadId, notify = false)

    override fun cancel(downloadId: String) = backgroundDownloadManager.cancel(downloadId)
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
    var reportedFirstBytes: Boolean = false,
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
    /** Queue positions of the tasks held, for the priority hint. Unknown after a relaunch until adopted. */
    private val positionsById = mutableMapOf<String, Long>()
    /** When each task was first seen with every expected byte, by task identifier. This process only. */
    private val fullSinceByTask = mutableMapOf<ULong, Long>()
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
            SystemOwnedTransfers.onPlatformBackground()
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
        val changed = isBackgrounded != backgrounded
        isBackgrounded = backgrounded
        if (changed) DownloadsLiveStatusPlatform.onAppBackgroundChanged()
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
        SystemOwnedTransfers.holdSchedulingForPlatformInventory()
        isBackgrounded = false
        DownloadsLiveStatusPlatform.onAppBackgroundChanged()
        delegateQueue.addOperationWithBlock { refreshReported.clear() }
        SystemOwnedTransfers.requestPlatformInventory()
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
            if (state != NSURLSessionTaskStateRunning &&
                state != NSURLSessionTaskStateSuspended
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
        DownloadsProbeLog.event(
            "inventory",
            "tasks" to tasks.size,
            "running" to tasksById.values.count { it.state == NSURLSessionTaskStateRunning },
            "suspended" to tasksById.values.count { it.state == NSURLSessionTaskStateSuspended },
        )
        val pending = awaitingInventory.toList()
        awaitingInventory.clear()
        pending.forEach { it() }
    }

    fun requestInventory(onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit) {
        whenReady {
            dropFinishedTasks()
            val now = DownloadsClock.nowEpochMs()
            tasksById.entries.toList().forEach { (downloadId, task) ->
                val received = task.countOfBytesReceived
                val expected = task.countOfBytesExpectedToReceive
                val running = task.state == NSURLSessionTaskStateRunning
                if (running && expected > 0L && received >= expected) {
                    fullSinceByTask.getOrPut(task.taskIdentifier) { now }
                }
                val fullSince = fullSinceByTask[task.taskIdentifier]
                DownloadsProbeLog.event(
                    "inventory_task",
                    "id" to downloadId,
                    "task" to task.taskIdentifier.toLong(),
                    "state" to task.state.toLong(),
                    "received" to received,
                    "expected" to expected,
                    "listened" to (task.taskIdentifier in contexts),
                    "fullForSec" to fullSince?.let { (now - it) / 1000L },
                )
                if (IosBackgroundTransferReconciler.isStalledAtEnd(running, received, expected, fullSince, now)) {
                    // Every byte arrived and the response never ended. Cancelling loses
                    // nothing that can be kept - the session holds the body in its own
                    // temporary file until the task finishes - and the retry starts over.
                    DownloadsProbeLog.event("stalled_at_end", "id" to downloadId, "task" to task.taskIdentifier.toLong())
                    val context = contexts[task.taskIdentifier]
                    cancelTaskLocked(downloadId, task)
                    context?.takeIf { !it.completed }?.fail(
                        DownloadFailureReason.Transient,
                        runBlocking { getString(Res.string.downloads_error_stalled) },
                        received.coerceAtLeast(0L),
                    )
                }
            }
            val live = tasksById.map { (downloadId, task) ->
                IosBackgroundTransferReconciler.LiveTransfer(
                    downloadId = downloadId,
                    running = task.state == NSURLSessionTaskStateRunning,
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
            request.queuePosition?.let { positionsById[request.downloadId] = it }
            dropFinishedTasks()
            tasksById[request.downloadId]?.let { existing ->
                attach(existing, metadata, listener)
                existing.resume()
                rankTaskPriorities()
                DownloadsProbeLog.event("resume", "id" to request.downloadId, "task" to existing.taskIdentifier.toLong())
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
            logCreate(task, request.downloadId, request.queuePosition, request.sourceUrlResolvedAtEpochMs)
        }
        return handle
    }

    /**
     * Forgets tasks that have already finished.
     *
     * Only a running or suspended task can still transfer anything. Attaching to a finished
     * one reports its last byte count as live progress and then waits for callbacks that
     * never come.
     */
    private fun dropFinishedTasks() {
        tasksById.entries
            .filter { (_, task) ->
                task.state != NSURLSessionTaskStateRunning && task.state != NSURLSessionTaskStateSuspended
            }
            .forEach { (downloadId, task) ->
                DownloadsProbeLog.event(
                    "drop_finished",
                    "id" to downloadId,
                    "task" to task.taskIdentifier.toLong(),
                    "state" to task.state.toLong(),
                )
                tasksById.remove(downloadId)
                positionsById.remove(downloadId)
            }
    }

    private fun createTask(metadata: NativeTaskMetadata, request: NSMutableURLRequest): NSURLSessionDownloadTask {
        val task = session.downloadTaskWithRequest(request).apply {
            taskDescription = metadata.encode()
        }
        tasksById[metadata.downloadId] = task
        rankTaskPriorities()
        return task
    }

    /**
     * Re-ranks every held task's priority by queue position.
     *
     * Recomputed over the whole set whenever it changes, so the hint follows the queue
     * even for tasks adopted after a relaunch. The system may still run them in any
     * order it likes.
     */
    private fun rankTaskPriorities() {
        tasksById.keys
            .sortedWith(compareBy<String> { positionsById[it] ?: Long.MAX_VALUE }.thenBy { it })
            .forEachIndexed { rank, downloadId ->
                tasksById[downloadId]?.priority =
                    (HEAD_TASK_PRIORITY - TASK_PRIORITY_STEP * rank).coerceAtLeast(MIN_TASK_PRIORITY)
            }
    }

    private fun logCreate(task: NSURLSessionDownloadTask, downloadId: String, position: Long?, resolvedAt: Long?) {
        DownloadsProbeLog.event(
            "create",
            "id" to downloadId,
            "task" to task.taskIdentifier.toLong(),
            "position" to position,
            "priority" to task.priority,
            "urlAgeSec" to resolvedAt?.let { (DownloadsClock.nowEpochMs() - it) / 1000L },
            "held" to tasksById.size,
        )
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
        return when (val claim = SystemOwnedTransfers.claimNativeTransfer(metadata.downloadId, handle, finishing)) {
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
        DownloadsProbeLog.event("cancel", "id" to downloadId, "task" to task.taskIdentifier.toLong())
        if (tasksById[downloadId]?.taskIdentifier == task.taskIdentifier) {
            tasksById.remove(downloadId)
            positionsById.remove(downloadId)
        }
        cancelledTaskIds += task.taskIdentifier
        task.cancel()
    }

    fun suspend(downloadId: String, notify: Boolean) {
        whenReady {
            val task = tasksById[downloadId] ?: return@whenReady
            task.suspend()
            DownloadsProbeLog.event("suspend", "id" to downloadId, "task" to task.taskIdentifier.toLong(), "notify" to notify)
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
        val snapshot = SystemOwnedTransfers.nativeSchedulingSnapshot()
        val running = tasksById.filterValues { it.state == NSURLSessionTaskStateRunning }.keys
        val suspended = tasksById.filterValues { it.state == NSURLSessionTaskStateSuspended }.keys
        val plan = IosBackgroundTransferReconciler.scheduleNextTransfers(
            maxConcurrent = IOS_SUBMISSION_WINDOW,
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
            val claim = SystemOwnedTransfers.claimNativeTransfer(candidate.downloadId, handle, finishing = false)
            if (claim !is NativeTransferClaim.Adopted) return@forEach
            positionsById[candidate.downloadId] = candidate.queuePosition
            val existing = tasksById[candidate.downloadId]?.takeIf { resume }
            val task = existing
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
            if (existing == null) {
                logCreate(task, candidate.downloadId, candidate.queuePosition, candidate.sourceUrlResolvedAtEpochMs)
            } else {
                rankTaskPriorities()
                DownloadsProbeLog.event("resume", "id" to candidate.downloadId, "task" to task.taskIdentifier.toLong())
            }
        }
        plan.refreshBoundary?.let { boundary ->
            DownloadsProbeLog.event("boundary", "id" to boundary.downloadId)
            if (refreshReported.add(boundary.downloadId)) {
                SystemOwnedTransfers.onNativeSourceRefreshNeeded(boundary.downloadId)
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
        if (total != null && totalBytesWritten >= total) {
            fullSinceByTask.getOrPut(downloadTask.taskIdentifier) { DownloadsClock.nowEpochMs() }
        }
        if (!context.reportedFirstBytes) {
            context.reportedFirstBytes = true
            DownloadsProbeLog.event(
                "first_progress",
                "id" to metadata.downloadId,
                "task" to downloadTask.taskIdentifier.toLong(),
                "bytes" to totalBytesWritten,
            )
        }
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
        val metadata = NativeTaskMetadata.decode(downloadTask.taskDescription) ?: run {
            DownloadsProbeLog.event("finalize", "task" to downloadTask.taskIdentifier.toLong(), "outcome" to "no_metadata")
            return
        }
        val context = contexts[downloadTask.taskIdentifier]
            ?: claim(downloadTask, metadata, finishing = true)
            ?: run {
                DownloadsProbeLog.event("finalize", "id" to metadata.downloadId, "outcome" to "not_claimed")
                return
            }
        fun finalized(outcome: String, vararg fields: Pair<String, Any?>) = DownloadsProbeLog.event(
            "finalize",
            "id" to metadata.downloadId,
            "task" to downloadTask.taskIdentifier.toLong(),
            "outcome" to outcome,
            *fields,
        )
        val response = downloadTask.response as? NSHTTPURLResponse
        val statusCode = response?.statusCode?.toInt() ?: 200
        DownloadsProbeLog.event(
            "finish",
            "id" to metadata.downloadId,
            "task" to downloadTask.taskIdentifier.toLong(),
            "http" to statusCode,
            "bytes" to downloadTask.countOfBytesReceived,
        )
        if (statusCode !in 200..299) {
            finalized("http_status", "http" to statusCode)
            context.fail(failureReasonForHttpStatus(statusCode), runBlocking {
                getString(Res.string.network_request_failed_http, statusCode)
            }, downloadTask.countOfBytesReceived.coerceAtLeast(0L))
            return
        }
        val sourcePath = didFinishDownloadingToURL.path ?: run {
            finalized("no_location")
            return
        }
        val destinationPath = "${downloadsDirectoryPath()}/${metadata.destinationFileName}"
        val partPath = "$destinationPath.part"
        removePathIfExists(partPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(sourcePath, partPath, null)) {
            finalized("move_to_part_failed")
            context.fail(DownloadFailureReason.Transient, runBlocking {
                getString(Res.string.downloads_error_finalize_file_failed)
            }, 0L)
            return
        }
        val bytes = fileSizeOrNull(partPath) ?: 0L
        val responseTotal = response?.valueForHTTPHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        val contentRange = response?.valueForHTTPHeaderField("Content-Range")
        // After a dropped connection the session resumes with a range request, and the last
        // response is a 206 whose Content-Length covers only that range. `.46` compared the
        // whole file with it, called Pilot an overrun and deleted a complete download.
        val expected = IosBackgroundTransferReconciler.finishedTransferTotal(
            statusCode = statusCode,
            contentLength = responseTotal,
            contentRange = contentRange,
            knownTotalBytes = metadata.knownTotalBytes,
        )
        val sizes = arrayOf<Pair<String, Any?>>(
            "bytes" to bytes,
            "contentLength" to responseTotal,
            "contentRangeTotal" to parseContentRangeTotal(contentRange),
            "expected" to expected,
            "known" to metadata.knownTotalBytes,
            "http" to statusCode,
        )
        when (evaluateCompletion(bytes, expected)) {
            is DownloadCompletion.Short -> {
                finalized("short", *sizes)
                context.fail(DownloadFailureReason.Incomplete, runBlocking {
                    getString(Res.string.downloads_error_incomplete_transfer)
                }, bytes)
                return
            }
            is DownloadCompletion.Overrun -> {
                finalized("overrun", *sizes)
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
            finalized("move_to_destination_failed", *sizes)
            context.fail(DownloadFailureReason.Transient, runBlocking {
                getString(Res.string.downloads_error_finalize_file_failed)
            }, bytes)
            return
        }
        val uri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
        finalized("complete", *sizes, "destinationBytes" to fileSizeOrNull(destinationPath))
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
        fullSinceByTask.remove(task.taskIdentifier)
        var context = contexts.remove(task.taskIdentifier)
        // Matched by identifier, never by reference. `.46` compared the delegate's task with
        // the stored one using `===`, and Kotlin/Native does not promise one wrapper per
        // Objective-C object: finished tasks stayed in this map, and a retry attached to
        // one and "resumed" it - a no-op on a finished task - which froze Pilot at 100%.
        if (tasksById[metadata.downloadId]?.taskIdentifier == task.taskIdentifier) {
            tasksById.remove(metadata.downloadId)
            positionsById.remove(metadata.downloadId)
        }
        val systemCancelReason = didCompleteWithError
            ?.takeIf { !cancelled && it.domain == "NSURLErrorDomain" && it.code == NSURL_ERROR_CANCELLED }
            ?.let { (it.userInfo[BACKGROUND_CANCEL_REASON_KEY] as? Number)?.toInt() ?: -1 }
        DownloadsProbeLog.event(
            "complete",
            "id" to metadata.downloadId,
            "task" to task.taskIdentifier.toLong(),
            "error" to didCompleteWithError?.code,
            "ours" to cancelled,
            "systemCancel" to systemCancelReason,
            "listened" to (context != null),
        )
        if (systemCancelReason != null) {
            // The system cancelled this task, most often because the user force-quit
            // Nuvio; the reason is delivered on the next launch. Nothing failed. A task
            // from before the relaunch has nobody listening, and the inventory has
            // already put its download back in the queue at the same place, so claiming
            // it here would charge an attempt for nothing - or duplicate a restart the
            // queue has made since. A live one is handed back as a system pause, which
            // the queue takes back itself.
            context?.takeIf { !it.completed }?.let {
                it.completed = true
                it.listener.onPaused(task.countOfBytesReceived.coerceAtLeast(0L))
            }
            finishedIds.remove(metadata.downloadId)
            advanceIfBackgrounded()
            return
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

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didFinishCollectingMetrics: NSURLSessionTaskMetrics,
    ) {
        val metadata = NativeTaskMetadata.decode(task.taskDescription)
        val transactions = didFinishCollectingMetrics.transactionMetrics
            .filterIsInstance<NSURLSessionTaskTransactionMetrics>()
        val transaction = transactions.lastOrNull()
        val interval = didFinishCollectingMetrics.taskInterval
        DownloadsProbeLog.event(
            "metrics",
            "id" to metadata?.downloadId,
            "task" to task.taskIdentifier.toLong(),
            "taskStart" to DownloadsProbeLog.epochMs(interval.startDate),
            "taskSeconds" to interval.duration,
            "transactions" to transactions.size,
            "fetchStart" to DownloadsProbeLog.epochMs(transaction?.fetchStartDate),
            "requestStart" to DownloadsProbeLog.epochMs(transaction?.requestStartDate),
            "responseStart" to DownloadsProbeLog.epochMs(transaction?.responseStartDate),
            "responseEnd" to DownloadsProbeLog.epochMs(transaction?.responseEndDate),
        )
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        DownloadsProbeLog.event("did_finish_events")
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
