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
import platform.Foundation.NSUserDefaults
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.darwin.NSObject

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0

private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

fun handleDownloadsBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
    backgroundSessionCompletionHandlers[identifier] = completionHandler
    backgroundDownloadManager.activate(identifier)
}

/** Retained for binary compatibility with `.42`; normal backgrounding no longer pauses anything. */
fun pauseDownloadsForAppBackground() = Unit
fun resumeDownloadsForAppForeground() = Unit

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual val recoversSystemPauses: Boolean = true
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

    actual fun syncPreparedTransfers(transfers: List<IosBackgroundTransferReconciler.IosPreparedTransfer>) {
        backgroundDownloadManager.syncPreparedTransfers(transfers)
    }

    actual fun pollJournalEvents(): List<IosBackgroundTransferReconciler.IosJournalEvent> =
        backgroundDownloadManager.pollJournalEvents()

    actual fun acknowledgeJournalEvents(eventIds: Set<String>) {
        backgroundDownloadManager.acknowledgeJournalEvents(eventIds)
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

private val backgroundDownloadManager by lazy { IosBackgroundDownloadManager() }

@OptIn(ExperimentalForeignApi::class)
private class IosBackgroundDownloadManager : NSObject(), NSURLSessionDownloadDelegateProtocol {
    private val stateLock = SynchronizedObject()
    private val contexts = mutableMapOf<ULong, NativeTaskContext>()
    private val cancelledTaskIds = mutableSetOf<ULong>()
    private var lastSelectedDownloadId: String? = null
    private var eventSeq: Long = 0L

    private val PREPARED_QUEUE_KEY = "nuvio.downloads.ios_native_queue.v1"
    private val JOURNAL_KEY = "nuvio.downloads.ios_native_journal.v1"

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

    fun syncPreparedTransfers(transfers: List<IosBackgroundTransferReconciler.IosPreparedTransfer>) {
        synchronized(stateLock) {
            val existing = loadPreparedQueue()
            val existingById = existing.associateBy { it.downloadId }
            val merged = transfers.map { incoming ->
                val current = existingById[incoming.downloadId]
                if (current != null && current.state in setOf(
                        IosBackgroundTransferReconciler.IosPreparedState.RUNNING,
                        IosBackgroundTransferReconciler.IosPreparedState.COMPLETED,
                    )
                ) {
                    incoming.copy(state = current.state)
                } else {
                    incoming
                }
            }
            savePreparedQueue(merged)
            advanceNativeQueueLocked()
        }
    }

    fun pollJournalEvents(): List<IosBackgroundTransferReconciler.IosJournalEvent> {
        return synchronized(stateLock) {
            loadJournal()
        }
    }

    fun acknowledgeJournalEvents(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        synchronized(stateLock) {
            val current = loadJournal()
            val remaining = current.filter { it.eventId !in eventIds }
            saveJournal(remaining)
        }
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
            recordPreparedState(request.downloadId, IosBackgroundTransferReconciler.IosPreparedState.RUNNING)
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
                    synchronized(stateLock) {
                        recordPreparedState(downloadId, IosBackgroundTransferReconciler.IosPreparedState.PREPARED)
                        appendJournalEvent(
                            IosBackgroundTransferReconciler.IosJournalEvent.TaskCancelled(
                                eventId = nextEventId(),
                                downloadId = downloadId,
                                epochMs = DownloadsClock.nowEpochMs(),
                            ),
                        )
                        contexts[task.taskIdentifier]
                    }?.listener?.onPaused(task.countOfBytesReceived.coerceAtLeast(0L))
                }
            }
        }
    }

    fun cancelForDestination(destinationFileName: String) {
        session.getAllTasksWithCompletionHandler { tasks ->
            tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>().forEach { task ->
                val meta = NativeTaskMetadata.decode(task.taskDescription)
                if (meta?.destinationFileName == destinationFileName) {
                    synchronized(stateLock) {
                        cancelledTaskIds += task.taskIdentifier
                        meta.downloadId.let { did ->
                            recordPreparedState(did, IosBackgroundTransferReconciler.IosPreparedState.CANCELLED)
                            appendJournalEvent(
                                IosBackgroundTransferReconciler.IosJournalEvent.TaskCancelled(
                                    eventId = nextEventId(),
                                    downloadId = did,
                                    epochMs = DownloadsClock.nowEpochMs(),
                                ),
                            )
                        }
                    }
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
        val now = DownloadsClock.nowEpochMs()

        if (context == null) {
            DownloadsRepository.reconcileIosBackgroundProgress(metadata.downloadId, totalBytesWritten, total)
        } else {
            if (!context.opened) {
                context.listener.onOpened(0L, total, null, null)
                context.opened = true
            }
            if (context.lastProgressBytes < 0L || shouldReportProgress(
                    totalBytesWritten, context.lastProgressBytes, now, context.lastProgressAtEpochMs,
                ) || (total != null && totalBytesWritten >= total)
            ) {
                context.lastProgressBytes = totalBytesWritten
                context.lastProgressAtEpochMs = now
                context.listener.onProgress(totalBytesWritten.coerceAtLeast(0L), total)
            }
        }

        // Periodically update native Live Activity while screen is locked
        synchronized(stateLock) {
            session.getAllTasksWithCompletionHandler { tasks ->
                val active = tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>()
                synchronized(stateLock) {
                    updateNativeLiveActivityLocked(active, loadPreparedQueue())
                }
            }
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
        val now = DownloadsClock.nowEpochMs()

        if (statusCode !in 200..299) {
            val failureReason = failureReasonForHttpStatus(statusCode)
            val msg = runBlocking { getString(Res.string.network_request_failed_http, statusCode) }
            synchronized(stateLock) {
                recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.FAILED)
                appendJournalEvent(
                    IosBackgroundTransferReconciler.IosJournalEvent.Failed(
                        eventId = nextEventId(),
                        downloadId = metadata.downloadId,
                        reason = failureReason,
                        message = msg,
                        downloadedBytes = downloadTask.countOfBytesReceived.coerceAtLeast(0L),
                        epochMs = now,
                    ),
                )
            }
            deliverFailure(metadata, context, failureReason, msg, downloadTask.countOfBytesReceived.coerceAtLeast(0L))
            advanceQueueAndNotify()
            return
        }

        val sourcePath = didFinishDownloadingToURL.path ?: return
        val destinationPath = "${downloadsDirectoryPath()}/${metadata.destinationFileName}"
        val partPath = "$destinationPath.part"
        removePathIfExists(partPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(sourcePath, partPath, null)) {
            val msg = runBlocking { getString(Res.string.downloads_error_finalize_file_failed) }
            synchronized(stateLock) {
                recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.FAILED)
                appendJournalEvent(
                    IosBackgroundTransferReconciler.IosJournalEvent.Failed(
                        eventId = nextEventId(),
                        downloadId = metadata.downloadId,
                        reason = DownloadFailureReason.Transient,
                        message = msg,
                        downloadedBytes = 0L,
                        epochMs = now,
                    ),
                )
            }
            deliverFailure(metadata, context, DownloadFailureReason.Transient, msg, 0L)
            advanceQueueAndNotify()
            return
        }

        val bytes = fileSizeOrNull(partPath) ?: 0L
        val responseTotal = response?.valueForHTTPHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        val expected = responseTotal ?: metadata.knownTotalBytes
        when (evaluateCompletion(bytes, expected)) {
            is DownloadCompletion.Short -> {
                val msg = runBlocking { getString(Res.string.downloads_error_incomplete_transfer) }
                synchronized(stateLock) {
                    recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.FAILED)
                    appendJournalEvent(
                        IosBackgroundTransferReconciler.IosJournalEvent.Failed(
                            eventId = nextEventId(),
                            downloadId = metadata.downloadId,
                            reason = DownloadFailureReason.Incomplete,
                            message = msg,
                            downloadedBytes = bytes,
                            epochMs = now,
                        ),
                    )
                }
                deliverFailure(metadata, context, DownloadFailureReason.Incomplete, msg, bytes)
                advanceQueueAndNotify()
                return
            }
            is DownloadCompletion.Overrun -> {
                removePathIfExists(partPath)
                val msg = runBlocking { getString(Res.string.downloads_error_source_changed) }
                synchronized(stateLock) {
                    recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.FAILED)
                    appendJournalEvent(
                        IosBackgroundTransferReconciler.IosJournalEvent.Failed(
                            eventId = nextEventId(),
                            downloadId = metadata.downloadId,
                            reason = DownloadFailureReason.SourceChanged,
                            message = msg,
                            downloadedBytes = 0L,
                            epochMs = now,
                        ),
                    )
                }
                deliverFailure(metadata, context, DownloadFailureReason.SourceChanged, msg, 0L)
                advanceQueueAndNotify()
                return
            }
            DownloadCompletion.Complete -> Unit
        }

        removePathIfExists(destinationPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(partPath, destinationPath, null)) {
            val msg = runBlocking { getString(Res.string.downloads_error_finalize_file_failed) }
            synchronized(stateLock) {
                recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.FAILED)
                appendJournalEvent(
                    IosBackgroundTransferReconciler.IosJournalEvent.Failed(
                        eventId = nextEventId(),
                        downloadId = metadata.downloadId,
                        reason = DownloadFailureReason.Transient,
                        message = msg,
                        downloadedBytes = bytes,
                        epochMs = now,
                    ),
                )
            }
            deliverFailure(metadata, context, DownloadFailureReason.Transient, msg, bytes)
            advanceQueueAndNotify()
            return
        }

        val uri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"

        // Record completion event in durable journal
        synchronized(stateLock) {
            recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.COMPLETED)
            appendJournalEvent(
                IosBackgroundTransferReconciler.IosJournalEvent.Completed(
                    eventId = nextEventId(),
                    downloadId = metadata.downloadId,
                    localFileUri = uri,
                    totalBytes = bytes,
                    epochMs = now,
                ),
            )
        }

        if (context != null) context.listener.onCompleted(uri, bytes)
        else DownloadsRepository.reconcileIosBackgroundCompletion(metadata.downloadId, uri, bytes)
        context?.completed = true

        // Advance native background queue immediately
        advanceQueueAndNotify()
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val metadata = NativeTaskMetadata.decode(task.taskDescription) ?: return
        val context = synchronized(stateLock) { contexts.remove(task.taskIdentifier) }
        val cancelled = synchronized(stateLock) { cancelledTaskIds.remove(task.taskIdentifier) }
        val now = DownloadsClock.nowEpochMs()

        if (didCompleteWithError != null && !cancelled && context?.completed != true) {
            val msg = didCompleteWithError.localizedDescription.ifBlank { runBlocking { getString(Res.string.download_failed) } }
            synchronized(stateLock) {
                recordPreparedState(metadata.downloadId, IosBackgroundTransferReconciler.IosPreparedState.FAILED)
                appendJournalEvent(
                    IosBackgroundTransferReconciler.IosJournalEvent.Failed(
                        eventId = nextEventId(),
                        downloadId = metadata.downloadId,
                        reason = DownloadFailureReason.Transient,
                        message = msg,
                        downloadedBytes = task.countOfBytesReceived.coerceAtLeast(0L),
                        epochMs = now,
                    ),
                )
            }
            deliverFailure(
                metadata, context, DownloadFailureReason.Transient,
                msg,
                task.countOfBytesReceived.coerceAtLeast(0L),
            )
            advanceQueueAndNotify()
        }
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
    }

    private fun advanceQueueAndNotify() {
        session.getAllTasksWithCompletionHandler { tasks ->
            synchronized(stateLock) {
                advanceNativeQueueLocked()
                val active = tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>()
                updateNativeLiveActivityLocked(active, loadPreparedQueue())
            }
        }
    }

    private fun advanceNativeQueueLocked() {
        val prepared = loadPreparedQueue()
        val now = DownloadsClock.nowEpochMs()
        session.getAllTasksWithCompletionHandler { tasks ->
            val activeTasks = tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>()
            val activeIds = activeTasks.mapNotNull {
                NativeTaskMetadata.decode(it.taskDescription)?.downloadId
            }.toSet()

            synchronized(stateLock) {
                val plan = IosBackgroundTransferReconciler.scheduleNextTransfers(
                    maxConcurrent = DownloadsRepository.MAX_CONCURRENT_TRANSFERS,
                    activeDownloadIds = activeIds,
                    preparedQueue = prepared,
                    nowEpochMs = now,
                    strictFifo = true,
                )

                // Start tasks
                plan.tasksToStart.forEach { candidate ->
                    val metadata = NativeTaskMetadata(candidate.downloadId, candidate.destinationFileName, candidate.knownTotalBytes)
                    val task = session.downloadTaskWithRequest(buildNativeRequestFromPrepared(candidate)).apply {
                        taskDescription = metadata.encode()
                    }
                    recordPreparedState(candidate.downloadId, IosBackgroundTransferReconciler.IosPreparedState.RUNNING)
                    appendJournalEvent(
                        IosBackgroundTransferReconciler.IosJournalEvent.TaskStarted(
                            eventId = nextEventId(),
                            downloadId = candidate.downloadId,
                            taskIdentifier = task.taskIdentifier.toLong(),
                            epochMs = now,
                        ),
                    )
                    task.resume()
                }

                // Record tasks needing refresh
                plan.tasksNeedingRefresh.forEach { candidate ->
                    recordPreparedState(candidate.downloadId, IosBackgroundTransferReconciler.IosPreparedState.NEEDS_SOURCE_REFRESH)
                    appendJournalEvent(
                        IosBackgroundTransferReconciler.IosJournalEvent.NeedsSourceRefresh(
                            eventId = nextEventId(),
                            downloadId = candidate.downloadId,
                            message = "Source expired; needs foreground refresh",
                            epochMs = now,
                        ),
                    )
                }

                updateNativeLiveActivityLocked(activeTasks, loadPreparedQueue())
            }
        }
    }

    private fun updateNativeLiveActivityLocked(
        tasks: List<NSURLSessionDownloadTask>,
        preparedQueue: List<IosBackgroundTransferReconciler.IosPreparedTransfer>,
    ) {
        val activeCandidates = tasks.mapNotNull { task ->
            val meta = NativeTaskMetadata.decode(task.taskDescription) ?: return@mapNotNull null
            val bytes = task.countOfBytesReceived.coerceAtLeast(0L)
            val expected = task.countOfBytesExpectedToReceive.takeIf { it > 0L } ?: meta.knownTotalBytes
            val prep = preparedQueue.firstOrNull { it.downloadId == meta.downloadId }
            DownloadsLiveStatusPolicy.Candidate(
                id = meta.downloadId,
                state = DownloadsLiveStatusPolicy.State.DOWNLOADING,
                downloadedBytes = bytes,
                totalBytes = expected,
                queuePosition = prep?.queuePosition ?: 0L,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
        val remainingCandidates = preparedQueue
            .filter { prep ->
                prep.state == IosBackgroundTransferReconciler.IosPreparedState.PREPARED &&
                    activeCandidates.none { it.id == prep.downloadId }
            }
            .map { prep ->
                DownloadsLiveStatusPolicy.Candidate(
                    id = prep.downloadId,
                    state = DownloadsLiveStatusPolicy.State.WAITING,
                    downloadedBytes = 0L,
                    totalBytes = prep.knownTotalBytes,
                    queuePosition = prep.queuePosition,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }

        val allCandidates = activeCandidates + remainingCandidates
        val presentation = DownloadsLiveStatusPolicy.select(
            items = allCandidates,
            currentSelectedId = lastSelectedDownloadId,
        )

        val selectedMeta = presentation?.candidate?.id?.let { selId ->
            preparedQueue.firstOrNull { it.downloadId == selId }
        }
        lastSelectedDownloadId = presentation?.candidate?.id

        val payload = when {
            presentation != null && selectedMeta != null -> {
                DownloadsLiveStatusPayload(
                    id = selectedMeta.downloadId,
                    title = selectedMeta.title.ifBlank { "Downloads" },
                    subtitle = selectedMeta.subtitle.ifBlank { "Downloading" },
                    status = presentation.candidate.state.name,
                    downloadedBytes = presentation.candidate.downloadedBytes,
                    totalBytes = presentation.candidate.totalBytes,
                    progressPercent = presentation.progressPercent ?: -1,
                    activeCount = presentation.activeCount,
                    remainingCount = presentation.remainingCount,
                    queueSummaryText = presentation.queueSummaryText,
                )
            }
            presentation != null -> {
                DownloadsLiveStatusPayload(
                    id = presentation.candidate.id,
                    title = "Downloads",
                    subtitle = "Downloading",
                    status = presentation.candidate.state.name,
                    downloadedBytes = presentation.candidate.downloadedBytes,
                    totalBytes = presentation.candidate.totalBytes,
                    progressPercent = presentation.progressPercent ?: -1,
                    activeCount = presentation.activeCount,
                    remainingCount = presentation.remainingCount,
                    queueSummaryText = presentation.queueSummaryText,
                )
            }
            else -> null
        }

        DownloadsLiveStatusPlatform.writePayloadDirect(payload)
    }

    private fun loadPreparedQueue(): MutableList<IosBackgroundTransferReconciler.IosPreparedTransfer> {
        val stored = NSUserDefaults.standardUserDefaults.stringForKey(PREPARED_QUEUE_KEY)
        return IosBackgroundTransferReconciler.decodePreparedTransfers(stored).toMutableList()
    }

    private fun savePreparedQueue(queue: List<IosBackgroundTransferReconciler.IosPreparedTransfer>) {
        val encoded = IosBackgroundTransferReconciler.encodePreparedTransfers(queue)
        NSUserDefaults.standardUserDefaults.setObject(encoded, forKey = PREPARED_QUEUE_KEY)
    }

    private fun recordPreparedState(downloadId: String, state: IosBackgroundTransferReconciler.IosPreparedState) {
        val current = loadPreparedQueue()
        val updated = current.map {
            if (it.downloadId == downloadId) it.copy(state = state) else it
        }
        savePreparedQueue(updated)
    }

    private fun loadJournal(): MutableList<IosBackgroundTransferReconciler.IosJournalEvent> {
        val stored = NSUserDefaults.standardUserDefaults.stringForKey(JOURNAL_KEY)
        return IosBackgroundTransferReconciler.decodeJournalEvents(stored).toMutableList()
    }

    private fun saveJournal(journal: List<IosBackgroundTransferReconciler.IosJournalEvent>) {
        val encoded = IosBackgroundTransferReconciler.encodeJournalEvents(journal)
        NSUserDefaults.standardUserDefaults.setObject(encoded, forKey = JOURNAL_KEY)
    }

    private fun appendJournalEvent(event: IosBackgroundTransferReconciler.IosJournalEvent) {
        val current = loadJournal()
        current.add(event)
        saveJournal(current)
    }

    private fun nextEventId(): String = "${DownloadsClock.nowEpochMs()}_${++eventSeq}"

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
    override fun cancel() { backgroundDownloadManager.suspend(downloadId) }
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
private fun buildNativeRequestFromPrepared(prepared: IosBackgroundTransferReconciler.IosPreparedTransfer): NSMutableURLRequest =
    NSMutableURLRequest(
        NSURL(string = prepared.sourceUrl), NSURLRequestReloadIgnoringLocalCacheData,
        DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    ).apply {
        setHTTPMethod("GET")
        setAllowsCellularAccess(prepared.allowMeteredNetwork)
        setAllowsExpensiveNetworkAccess(prepared.allowMeteredNetwork)
        setAllowsConstrainedNetworkAccess(prepared.allowMeteredNetwork)
        prepared.sourceHeaders.forEach { (key, value) -> setValue(value, key) }
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
