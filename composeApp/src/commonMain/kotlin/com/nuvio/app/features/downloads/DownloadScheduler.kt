package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkQualityRepository
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal data class TransferSample(val bytes: Long, val atEpochMs: Long)

/**
 * When downloads start, retry and stop (Phase 9, stage 4): slots from the device setting (or the
 * iOS window), the connectivity and mobile-data gates, the retry and watchdog timers, the reclaim
 * sweep, and the transfer callbacks with their generation fence. Split out of
 * `DownloadsRepository` unchanged in behaviour; the pure decisions stay in [DownloadQueuePlanner].
 */
internal object DownloadScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val activeHandles = mutableMapOf<String, ActiveTransfer>()
    internal val transferSamples = mutableMapOf<String, TransferSample>()
    private var networkObserverStarted = false
    private var networkObserverJob: Job? = null
    private var connectivityRefreshJob: Job? = null
    private var wifiRecheckJob: Job? = null
    private var retryWakeJob: Job? = null
    internal var nextTransferGeneration = 0L

    /** The device setting on Android and desktop; the submitted window on iOS. */
    internal val maxConcurrentTransfers: Int
        get() = DownloadsPlatformDownloader.transferHost.slotCount(DownloadStore.deviceSettings.value)

    /** Answered by the platform's own network state. A variable so tests can stand in. */
    internal var isMeteredNetwork: () -> Boolean = {
        runCatching { com.nuvio.app.core.network.NetworkQualityPlatform.current().isMetered }.getOrDefault(false)
    }

    /** Drops every transfer and timer; the store is cleared by its own owner. */
    internal fun resetLocked() {
        activeHandles.values.forEach(ActiveTransfer::cancel)
        activeHandles.clear()
        retryWakeJob?.cancel()
        retryWakeJob = null
        connectivityRefreshJob?.cancel()
        connectivityRefreshJob = null
        wifiRecheckJob?.cancel()
        wifiRecheckJob = null
    }

    internal var connectivityFeed: DownloadConnectivityFeed = AppDownloadConnectivityFeed

    /**
     * Brings the queue back when connectivity returns.
     *
     * Losing the network is the most common reason a transfer stops, and waiting for
     * the user to notice and tap resume on each item is not a recovery story.
     */
    internal fun startNetworkObserverLocked() {
        if (networkObserverStarted) return
        networkObserverStarted = true
        connectivityFeed.ensureStarted()
        networkObserverJob = scope.launch {
            var wasBlocked = connectivityFeed.states.value.blocksMediaDownloads()
            connectivityFeed.states.collect { state ->
                val blocked = state.blocksMediaDownloads()
                if (blocked && !wasBlocked) pauseForConnectionLoss()
                if (!blocked && wasBlocked) resumeAfterConnectivityRecovery()
                wasBlocked = blocked
            }
        }
    }

    /** Replaces the app feed without leaking the previous collector into queue tests. */
    internal fun installConnectivityFeedForTests(feed: DownloadConnectivityFeed) {
        synchronized(DownloadStore.lock) {
            networkObserverJob?.cancel()
            networkObserverJob = null
            connectivityRefreshJob?.cancel()
            connectivityRefreshJob = null
            connectivityFeed = feed
            networkObserverStarted = false
            startNetworkObserverLocked()
        }
        startPendingTransfers()
    }

    internal fun restoreConnectivityFeedAfterTests() {
        installConnectivityFeedForTests(AppDownloadConnectivityFeed)
    }

    internal fun pauseForConnectionLoss() {
        synchronized(DownloadStore.lock) {
            val affected = DownloadStore.allItems.filter {
                it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued
            }
            affected.forEach { item ->
                activeHandles.remove(item.id)?.cancel()
                DownloadDiagnostics.connectivity(item, recovered = false)
            }
            if (affected.isNotEmpty()) {
                val ids = affected.mapTo(mutableSetOf()) { it.id }
                DownloadStore.publishLocked(
                    DownloadStore.allItems.map { item ->
                        if (item.id !in ids) item else item.copy(
                            status = DownloadStatus.Queued,
                            activity = DownloadActivity.WAITING_FOR_CONNECTION,
                            nextRetryAtEpochMs = null,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    },
                    immediate = true,
                )
            }
            scheduleConnectivityRefreshLocked()
        }
    }

    private fun resumeAfterConnectivityRecovery() {
        synchronized(DownloadStore.lock) {
            connectivityRefreshJob?.cancel()
            connectivityRefreshJob = null
            val now = DownloadsClock.nowEpochMs()
            DownloadStore.publishLocked(
                DownloadStore.allItems.map { item ->
                    if (item.activity != DownloadActivity.WAITING_FOR_CONNECTION) item else {
                        DownloadDiagnostics.connectivity(item, recovered = true)
                        item.copy(
                            activity = DownloadActivity.QUEUED_FOR_SLOT,
                            updatedAtEpochMs = now,
                        )
                    }
                },
                immediate = true,
            )
        }
        startPendingTransfers()
    }

    internal fun scheduleConnectivityRefreshLocked() {
        if (connectivityRefreshJob?.isActive == true) return
        connectivityRefreshJob = scope.launch {
            while (connectivityFeed.states.value.blocksMediaDownloads()) {
                delay(DownloadsTiming.connectivityRefreshIntervalMs)
                connectivityFeed.requestRefresh()
            }
        }
    }

    // --- Transfer callbacks -------------------------------------------------------
    //
    // Every one of these is fenced on the transfer generation. A transfer that has
    // been cancelled still reports what happened to it, from its own thread and after
    // the fact, and by then the download it belongs to may already be running a newer
    // attempt. Only the attempt that currently holds the slot may speak for it.

    internal fun onTransferOpened(
        downloadId: String,
        generation: Long,
        resumedFromBytes: Long,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
    ) {
        synchronized(DownloadStore.lock) {
            if (!isCurrentTransferLocked(downloadId, generation)) return
            DownloadStore.allItems.firstOrNull { it.id == downloadId }?.let {
                DownloadDiagnostics.transferOpen(it, resumedFromBytes, totalBytes)
            }
            transferSamples[downloadId] = TransferSample(
                bytes = resumedFromBytes.coerceAtLeast(0L),
                atEpochMs = DownloadsClock.nowEpochMs(),
            )
            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                if (current.status != DownloadStatus.Downloading) {
                    current
                } else {
                    current.copy(
                        downloadedBytes = resumedFromBytes.coerceAtLeast(0L),
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        activity = DownloadActivity.TRANSFERRING,
                        // Kept so the next resume can prove, via If-Range, that the bytes
                        // on disk still belong to the file the server is serving.
                        resumeEtag = etag?.trim()?.takeIf { it.isNotBlank() } ?: current.resumeEtag,
                        resumeLastModified = lastModified?.trim()?.takeIf { it.isNotBlank() }
                            ?: current.resumeLastModified,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
            }
        }
    }

    internal fun onTransferProgress(
        downloadId: String,
        generation: Long,
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        synchronized(DownloadStore.lock) {
            if (!isCurrentTransferLocked(downloadId, generation)) return
            val now = DownloadsClock.nowEpochMs()
            val previous = transferSamples[downloadId]
            val currentItem = DownloadStore.allItems.firstOrNull { it.id == downloadId }
            if (previous != null && downloadedBytes > previous.bytes) {
                NetworkQualityRepository.recordTransfer(
                    bytes = downloadedBytes - previous.bytes,
                    elapsedMs = now - previous.atEpochMs,
                    providerId = currentItem?.sourceOrigin?.stream?.clientResolve?.service
                        ?: currentItem?.providerName,
                )
                if (now - previous.atEpochMs >= 750L) {
                    transferSamples[downloadId] = TransferSample(downloadedBytes, now)
                }
            } else if (previous == null) {
                transferSamples[downloadId] = TransferSample(downloadedBytes, now)
            }
            DownloadStore.mutateLocked(downloadId, immediate = false) { item ->
                if (item.status != DownloadStatus.Downloading) {
                    item
                } else {
                    val cap = item.calculatedCapBytes
                    val largestKnownSize = listOfNotNull(
                        downloadedBytes.takeIf { it > 0L },
                        totalBytes?.takeIf { it > 0L },
                    ).maxOrNull()
                    // Measured from where this run of bad luck began, not from the last
                    // callback: the question is whether the transfer is getting anywhere,
                    // and a few hundred KB at a time is not an answer.
                    val cycleStart = item.retryCycleStartBytes
                    val hasMeaningfulProgress = if (cycleStart == null) {
                        downloadedBytes > item.downloadedBytes
                    } else {
                        downloadedBytes - cycleStart >=
                            meaningfulProgressBytes(totalBytes ?: item.totalBytes)
                    }

                    item.copy(
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: item.totalBytes,
                        // Noted, not enforced. The cap decides which source to pick; a
                        // transfer that has already fetched most of a file is past the
                        // point where refusing it saves anything, and stopping it there
                        // was what left downloads sitting partway through with a size
                        // complaint about a source that had already been approved.
                        exceedsSizeCap = item.exceedsSizeCap ||
                            (cap != null && largestKnownSize != null && largestKnownSize > cap),
                        // Bytes arriving means the source works, so a previous run of bad
                        // luck should not count against this attempt's retry budget - but it
                        // has to be enough bytes to mean it. A source that trickles and drops
                        // used to refresh the budget every cycle, so `shouldRetry` never
                        // returned false and the row retried forever without finishing.
                        attemptCount = if (hasMeaningfulProgress) 0 else item.attemptCount,
                        retryCycleStartBytes = if (hasMeaningfulProgress) null else item.retryCycleStartBytes,
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        errorMessage = null,
                    )
                }
            }
        }
    }

    internal fun onTransferCompleted(
        downloadId: String,
        generation: Long,
        localFileUri: String,
        totalBytes: Long,
    ) {
        // A whole, valid, playable file is not proof the download worked. Debrid
        // providers answer with a small placeholder video while they queue the real
        // one, and it passes every check the transfer itself can make.
        val placeholder = synchronized(DownloadStore.lock) {
            if (!isCurrentTransferLocked(downloadId, generation)) {
                DownloadDiagnostics.note(
                    "completion_fenced",
                    "id=$downloadId generation=$generation current=${activeHandles[downloadId]?.generation} bytes=$totalBytes",
                )
                return
            }
            val current = DownloadStore.allItems.firstOrNull { it.id == downloadId }
            current != null && isImplausiblySmallForMedia(totalBytes, current.expectedSizeBytes)
        }
        if (placeholder) {
            DownloadDiagnostics.note("completion_rejected_small", "id=$downloadId bytes=$totalBytes")
            onTransferFailed(
                downloadId = downloadId,
                generation = generation,
                reason = DownloadFailureReason.SourceNotReady,
                message = runBlocking { getString(Res.string.downloads_error_source_not_ready) },
                downloadedBytes = 0L,
                discardFiles = true,
            )
            return
        }

        synchronized(DownloadStore.lock) {
            if (!isCurrentTransferLocked(downloadId, generation)) return
            transferSamples.remove(downloadId)
            activeHandles.remove(downloadId)
            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                DownloadDiagnostics.completion(current, totalBytes)
                current.copy(
                    status = DownloadStatus.Completed,
                    pauseReason = null,
                    localFileUri = localFileUri,
                    // The verified size of the file on disk, never a total inferred from a
                    // transfer that stopped early.
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes,
                    errorMessage = null,
                    attemptCount = 0,
                    nextRetryAtEpochMs = null,
                    activity = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        startPendingTransfers()
    }

    internal fun onTransferPaused(downloadId: String, generation: Long, downloadedBytes: Long) {
        synchronized(DownloadStore.lock) {
            if (!isCurrentTransferLocked(downloadId, generation)) return
            transferSamples.remove(downloadId)
            activeHandles.remove(downloadId)
            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                val recordedBytes = downloadedBytes.coerceAtLeast(0L)
                // Whoever asked for the stop has usually already recorded why. Only an
                // unattributed stop needs a status of its own, and it is never a failure.
                if (current.status != DownloadStatus.Downloading) {
                    current.copy(
                        downloadedBytes = recordedBytes,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current.copy(
                        status = DownloadStatus.Paused,
                        pauseReason = DownloadPauseReason.System,
                        downloadedBytes = recordedBytes,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
            }
        }
        startPendingTransfers()
    }

    internal fun onTransferFailed(
        downloadId: String,
        generation: Long,
        reason: DownloadFailureReason,
        message: String,
        downloadedBytes: Long,
        /** Throw away what arrived, for bytes that are not part of the real file. */
        discardFiles: Boolean = false,
    ) {
        val fallbackMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } }
        synchronized(DownloadStore.lock) {
            if (!isCurrentTransferLocked(downloadId, generation)) return
            transferSamples.remove(downloadId)
            activeHandles.remove(downloadId)
            if (connectivityFeed.states.value.blocksMediaDownloads()) {
                DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                    DownloadDiagnostics.connectivity(current, recovered = false)
                    current.copy(
                        status = DownloadStatus.Queued,
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        activity = DownloadActivity.WAITING_FOR_CONNECTION,
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                scheduleConnectivityRefreshLocked()
                return
            }
            if (discardFiles) {
                DownloadStore.allItems.firstOrNull { it.id == downloadId }?.let { item ->
                    DownloadsPlatformDownloader.removeFile(
                        DownloadsPlatformDownloader.resolveLocalFileUri(
                            localFileUri = item.localFileUri,
                            destinationFileName = item.fileName,
                        ) ?: item.localFileUri,
                    )
                    DownloadsPlatformDownloader.removePartialFile(item.fileName)
                }
            }
            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                if (current.status != DownloadStatus.Downloading) {
                    current.copy(downloadedBytes = downloadedBytes.coerceAtLeast(0L))
                } else {
                    val attempt = current.attemptCount + 1
                    DownloadDiagnostics.failure(current, reason.name, attempt, downloadedBytes)
                    val now = DownloadsClock.nowEpochMs()
                    // Entering the retry cycle. From here the budget is only refreshed by
                    // real progress measured against this mark - see `onTransferProgress`.
                    val cycleStart = current.retryCycleStartBytes ?: downloadedBytes.coerceAtLeast(0L)
                    // The budget is spent and nothing has moved. A partial file the server
                    // will not correctly resume is the likeliest explanation for a stall
                    // pinned near the end, so run the file once from the beginning on a
                    // freshly minted link before giving up. `startDownloadLocked` already
                    // force-refreshes the link on every start, so this only has to discard
                    // the bytes; `restartedFromZero` keeps it to one attempt, because a
                    // restart loop is the same fault wearing a different hat.
                    val canRestartFromZero = canRestartFromZero(
                        reason = reason,
                        alreadyRestarted = current.restartedFromZero,
                        downloadedBytes = downloadedBytes,
                    )
                    if (!shouldRetry(reason, attempt, current.canReresolveSource) && canRestartFromZero) {
                        DownloadsPlatformDownloader.removePartialFile(current.fileName)
                        val retryAt = now + retryBackoffMs(attempt, reason)
                        DownloadDiagnostics.retry(current, reason.name, attempt, retryAt)
                        return@mutateLocked current.copy(
                            status = DownloadStatus.Queued,
                            pauseReason = null,
                            downloadedBytes = 0L,
                            localFileUri = null,
                            attemptCount = 0,
                            restartedFromZero = true,
                            retryCycleStartBytes = 0L,
                            // A dead link cannot be what we start over with.
                            sourceUrlResolvedAtEpochMs = null,
                            resumeEtag = null,
                            resumeLastModified = null,
                            nextRetryAtEpochMs = retryAt,
                            activity = DownloadActivity.RETRY_BACKOFF,
                            errorMessage = fallbackMessage,
                            updatedAtEpochMs = now,
                        )
                    }
                    if (shouldRetry(reason, attempt, current.canReresolveSource)) {
                        // Backed off rather than retried on the spot: a dead network used
                        // to burn every attempt within milliseconds of the first failure.
                        val retryAt = now + retryBackoffMs(attempt, reason)
                        DownloadDiagnostics.retry(current, reason.name, attempt, retryAt)
                        current.copy(
                            status = DownloadStatus.Queued,
                            pauseReason = null,
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            localFileUri = if (discardFiles) null else current.localFileUri,
                            attemptCount = attempt,
                            retryCycleStartBytes = cycleStart,
                            // A dead link is not worth replaying; clearing the stamp is
                            // what makes the next start mint a new one.
                            sourceUrlResolvedAtEpochMs =
                                if (reason == DownloadFailureReason.SourceExpired) {
                                    null
                                } else {
                                    current.sourceUrlResolvedAtEpochMs
                                },
                            nextRetryAtEpochMs = retryAt,
                            activity = if (reason == DownloadFailureReason.SourceNotReady) {
                                DownloadActivity.WAITING_FOR_PROVIDER
                            } else {
                                DownloadActivity.RETRY_BACKOFF
                            },
                            errorMessage = fallbackMessage,
                            updatedAtEpochMs = now,
                        )
                    } else {
                        // Out of budget, and starting over has already been tried. Say what
                        // happened in words the user can act on rather than counting down to
                        // another attempt that will end the same way - a countdown that never
                        // finishes its sentence is what made this look like a hang.
                        val stalledMessage = if (reason == DownloadFailureReason.NoResponse) {
                            fallbackMessage
                        } else if (current.restartedFromZero) {
                            runBlocking { getString(Res.string.downloads_error_stalled) }
                        } else {
                            fallbackMessage
                        }
                        current.copy(
                            status = DownloadStatus.Failed,
                            failureKind = null,
                            pauseReason = null,
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            localFileUri = if (discardFiles) null else current.localFileUri,
                            attemptCount = attempt,
                            nextRetryAtEpochMs = null,
                            activity = null,
                            errorMessage = stalledMessage,
                            updatedAtEpochMs = now,
                        )
                    }
                }
            }
        }
        startPendingTransfers()
    }

    // --- Queue scheduling ---------------------------------------------------------

    /**
     * Queued items this network may not carry say so ("Waiting for Wi-Fi") instead of reading as
     * an ordinary queue position; ones it may carry again lose the label.
     */
    private fun markWifiWaitsLocked(metered: Boolean, rule: DownloadMobileDataRule, now: Long) {
        val changed = DownloadStore.allItems.any { item ->
            item.status == DownloadStatus.Queued &&
                (item.activity == DownloadActivity.WAITING_FOR_WIFI) == item.mayStartOn(metered, rule)
        }
        if (!changed) return
        DownloadStore.publishLocked(
            DownloadStore.allItems.map { item ->
                if (item.status != DownloadStatus.Queued) return@map item
                val waitsForWifi = !item.mayStartOn(metered, rule)
                when {
                    waitsForWifi && item.activity != DownloadActivity.WAITING_FOR_WIFI -> {
                        DownloadDiagnostics.wifiWait(item, waiting = true)
                        item.copy(activity = DownloadActivity.WAITING_FOR_WIFI, updatedAtEpochMs = now)
                    }
                    !waitsForWifi && item.activity == DownloadActivity.WAITING_FOR_WIFI -> {
                        DownloadDiagnostics.wifiWait(item, waiting = false)
                        item.copy(activity = DownloadActivity.QUEUED_FOR_SLOT, updatedAtEpochMs = now)
                    }
                    else -> item
                }
            },
            immediate = true,
        )
    }

    /**
     * Only Android reports network changes to the queue ([onNetworkChanged]). Elsewhere, while
     * anything waits for Wi-Fi, look again on the connectivity interval so Wi-Fi coming back
     * starts it without an unrelated event. On iOS in the background this start path is skipped
     * and the session's `allowsCellularAccess` does the waiting instead.
     */
    private fun scheduleWifiRecheckLocked() {
        if (wifiRecheckJob?.isActive == true) return
        if (DownloadStore.allItems.none { it.activity == DownloadActivity.WAITING_FOR_WIFI }) return
        wifiRecheckJob = scope.launch {
            while (DownloadStore.allItems.any { it.activity == DownloadActivity.WAITING_FOR_WIFI }) {
                delay(DownloadsTiming.connectivityRefreshIntervalMs)
                if (!isMeteredNetwork()) {
                    startPendingTransfers()
                }
            }
        }
    }

    internal fun startPendingTransfers() {
        // While iOS is in the background the session fills freed slots itself (see
        // IosBackgroundTransferReconciler). Starting here as well is how `.44` ran two
        // schedulers against one queue.
        if (DownloadsPlatformDownloader.transferHost.schedulingDeferredToPlatform) return
        synchronized(DownloadStore.lock) {
            // Until the platform has said which transfers really exist, an item recorded
            // as downloading may or may not have one: reclaiming it or starting another
            // would reorder or duplicate them.
            if (SystemOwnedTransfers.awaitingPlatformInventory) return@synchronized
            reclaimLostTransfersLocked()
            if (connectivityFeed.states.value.blocksMediaDownloads()) {
                val waiting = DownloadStore.allItems.filter { it.status == DownloadStatus.Queued }
                if (waiting.isNotEmpty()) {
                    val waitingIds = waiting.mapTo(mutableSetOf()) { it.id }
                    DownloadStore.publishLocked(
                        DownloadStore.allItems.map { item ->
                            if (item.id !in waitingIds) item else item.copy(
                                activity = DownloadActivity.WAITING_FOR_CONNECTION,
                                nextRetryAtEpochMs = null,
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            )
                        },
                        immediate = true,
                    )
                    waiting.forEach { DownloadDiagnostics.connectivity(it, recovered = false) }
                }
                scheduleConnectivityRefreshLocked()
                return@synchronized
            }
            val now = DownloadsClock.nowEpochMs()
            val metered = isMeteredNetwork()
            val rule = DownloadStore.deviceSettings.value.mobileData
            markWifiWaitsLocked(metered, rule, now)
            scheduleWifiRecheckLocked()
            val startable = DownloadQueuePlanner.startable(
                items = DownloadStore.allItems,
                activeIds = activeHandles.keys.toSet(),
                maxConcurrent = maxConcurrentTransfers,
                nowEpochMs = now,
                mayStartOnNetwork = { it.mayStartOn(metered, rule) },
            )

            if (startable.isNotEmpty()) {
                val startingIds = startable.map { it.id }.toSet()
                DownloadStore.publishLocked(
                    DownloadStore.allItems.map { item ->
                        if (item.id !in startingIds) {
                            item
                        } else {
                            item.copy(
                                status = DownloadStatus.Downloading,
                                pauseReason = null,
                                activity = if (item.sourceOrigin != null) {
                                    DownloadActivity.RESOLVING_SOURCE
                                } else {
                                    DownloadActivity.TRANSFERRING
                                },
                                nextRetryAtEpochMs = null,
                                updatedAtEpochMs = now,
                            )
                        }
                    },
                    immediate = true,
                )

                startable.forEach { queuedItem ->
                    val current = DownloadStore.allItems.firstOrNull { it.id == queuedItem.id }
                        ?: queuedItem
                    DownloadDiagnostics.slot(current)
                    startDownloadLocked(current)
                }
            }

            scheduleRetryWakeLocked()
        }
        SystemOwnedTransfers.prepareUpcomingTransfers()
    }

    /**
     * Puts transfers the queue has lost track of back in the queue.
     *
     * An item recorded as downloading with no handle behind it is invisible to
     * everything else here: the planner only ever starts queued items, and the
     * system-pause recovery only looks at paused ones. Nothing would ever touch it
     * again, so it sat at whatever percentage it had reached, holding one of the two
     * transfer slots for good - and two of them stopped the queue outright. Rather
     * than enumerate the ways a handle can go missing, notice that it has.
     *
     * A transfer that is still held but has not reported a byte in far longer than
     * the platform watchdog allows is treated the same way, since a watchdog that
     * never fired is exactly the case nothing else covers.
     *
     * On a platform with no system-pause recovery of its own, a system-paused item
     * with no transfer behind it is in the same position and comes back the same way.
     */
    private fun reclaimLostTransfersLocked() {
        val now = DownloadsClock.nowEpochMs()
        val lost = DownloadQueuePlanner.lostTransfers(
            items = DownloadStore.allItems,
            activeIds = activeHandles.keys.toSet(),
            nowEpochMs = now,
            silenceTimeoutMs = if (DownloadsPlatformDownloader.transferHost.ownsTransferLiveness) {
                Long.MAX_VALUE
            } else {
                DownloadsTiming.queueWatchdogTimeoutMs
            },
            recoverSystemPauses = !DownloadsPlatformDownloader.transferHost.recoversSystemPauses,
        )
        if (lost.isEmpty()) return

        val lostIds = lost.map { it.id }.toSet()
        lostIds.forEach { activeHandles.remove(it)?.cancel() }
        DownloadStore.publishLocked(
            DownloadStore.allItems.map { item ->
                if (item.id !in lostIds) {
                    item
                } else {
                    // Charged an attempt, like any other failure. This path does not go
                    // through `onTransferFailed`, so it used to recycle an item for free -
                    // a second unbounded loop, independent of the progress reset, in which
                    // the queue watchdog could recover the same download forever. Anything
                    // that puts a download back in the queue has to cost it something, or
                    // "no row that stops moving" is traded for a row that never finishes.
                    item.copy(
                        status = DownloadStatus.Queued,
                        pauseReason = null,
                        attemptCount = item.attemptCount + 1,
                        retryCycleStartBytes = item.retryCycleStartBytes
                            ?: item.downloadedBytes.coerceAtLeast(0L),
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = now,
                    )
                }
            },
            immediate = true,
        )
    }

    /**
     * Starts [item], minting a fresh source URL first when the one it holds is stale.
     *
     * Resolution is a network round trip, so it cannot happen under the lock. The
     * item is left marked as downloading and its slot is reserved by the [ActiveTransfer]
     * created here while that happens, which keeps the queue from starting a third
     * transfer into the same slot and keeps the reclaim sweep from deciding this
     * item has been lost.
     *
     * Every attempt gets its own [ActiveTransfer], and everything that happens after
     * this point - the resolver answering, the transfer's own callbacks - is fenced
     * against it. An attempt that has been replaced can no longer speak for the
     * download.
     */
    internal fun startDownloadLocked(item: DownloadItem) {
        val transfer = ActiveTransfer(++nextTransferGeneration)
        activeHandles[item.id] = transfer

        if (item.sourceOrigin == null) {
            if (SystemOwnedTransfers.submitsInQueueOrder) {
                SystemOwnedTransfers.parkedStarts[item.id] = SystemOwnedTransfers.ParkedStart(item, transfer)
                SystemOwnedTransfers.flushParkedStartsLocked()
            } else {
                startResolvedDownloadLocked(item, transfer)
            }
            return
        }

        if (SystemOwnedTransfers.submitsInQueueOrder) SystemOwnedTransfers.resolvingInOrder[item.id] = transfer
        DownloadDiagnostics.resolving(item)
        scope.launch {
            val refreshed = SourceRealizer.refresh(item)
            synchronized(DownloadStore.lock) {
                if (SystemOwnedTransfers.resolvingInOrder[item.id] === transfer) SystemOwnedTransfers.resolvingInOrder.remove(item.id)
                // Cancelled, paused or reordered while the provider was answering. The
                // generation is what says so: this download may well have been started
                // again in the meantime, and that newer attempt owns the slot now.
                if (activeHandles[item.id] !== transfer) return@synchronized
                val current = DownloadStore.allItems.firstOrNull { it.id == item.id }
                if (current == null || current.status != DownloadStatus.Downloading) {
                    activeHandles.remove(item.id)
                    return@synchronized
                }
                if (refreshed is RefreshedDownloadSource.Failed) {
                    val resolution = refreshed.resolution
                    activeHandles.remove(item.id)
                    val now = DownloadsClock.nowEpochMs()
                    val attempt = current.attemptCount + 1
                    val outcome = SourceRealizer.failureOutcome(
                        resolution = resolution,
                        attempt = attempt,
                        knownUncached = current.sourceOrigin.isKnownUncached(),
                        canReresolveSource = current.canReresolveSource,
                    )
                    val reason = outcome.reason
                    val uncachedForGood = outcome.uncachedForGood
                    val message = when (resolution) {
                        is DownloadSourceResolution.NotReady -> if (uncachedForGood) {
                            runBlocking { getString(Res.string.downloads_error_not_cached_choose_source) }
                        } else {
                            resolution.message
                        }
                        is DownloadSourceResolution.RetryableFailure -> resolution.message
                        is DownloadSourceResolution.FatalFailure -> resolution.message
                        is DownloadSourceResolution.SourceChanged -> resolution.message
                        is DownloadSourceResolution.Ready -> error("ready source cannot fail refresh")
                    }
                    val retryable = outcome.retryable
                    val sourceChanged = outcome.sourceChanged
                    DownloadDiagnostics.failure(current, reason.name, attempt, current.downloadedBytes)
                    if (sourceChanged) DownloadsPlatformDownloader.removePartialFile(current.fileName)
                    DownloadStore.mutateLocked(item.id, immediate = true) { latest ->
                        if (retryable) {
                            val retryAt = now + retryBackoffMs(attempt, reason)
                            DownloadDiagnostics.retry(latest, reason.name, attempt, retryAt)
                            latest.copy(
                                status = DownloadStatus.Queued,
                                pauseReason = null,
                                attemptCount = attempt,
                                nextRetryAtEpochMs = retryAt,
                                activity = outcome.retryActivity,
                                errorMessage = message,
                                updatedAtEpochMs = now,
                            )
                        } else {
                            latest.copy(
                                status = DownloadStatus.Failed,
                                failureKind = when {
                                    (resolution as? DownloadSourceResolution.FatalFailure)?.storage == true ->
                                        DownloadFailureKind.STORAGE
                                    uncachedForGood -> DownloadFailureKind.NOT_CACHED
                                    else -> null
                                },
                                pauseReason = null,
                                downloadedBytes = if (sourceChanged) 0L else latest.downloadedBytes,
                                totalBytes = if (sourceChanged) null else latest.totalBytes,
                                resumeEtag = if (sourceChanged) null else latest.resumeEtag,
                                resumeLastModified = if (sourceChanged) null else latest.resumeLastModified,
                                attemptCount = attempt,
                                nextRetryAtEpochMs = null,
                                activity = null,
                                errorMessage = message,
                                updatedAtEpochMs = now,
                            )
                        }
                    }
                } else if (refreshed is RefreshedDownloadSource.NeedsApproval) {
                    activeHandles.remove(item.id)
                    DownloadStore.mutateLocked(item.id, immediate = true) { latest ->
                        latest.copy(
                            status = DownloadStatus.Paused,
                            pauseReason = DownloadPauseReason.SizeApproval,
                            activity = DownloadActivity.SIZE_APPROVAL,
                            sizeApprovalRequired = true,
                            errorMessage = refreshed.message,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                } else if (refreshed is RefreshedDownloadSource.Ready) {
                    if (SystemOwnedTransfers.submitsInQueueOrder) {
                        SystemOwnedTransfers.parkedStarts[item.id] = SystemOwnedTransfers.ParkedStart(refreshed.item, transfer)
                    } else {
                        startResolvedDownloadLocked(refreshed.item, transfer)
                    }
                }
            }
            if (SystemOwnedTransfers.submitsInQueueOrder) synchronized(DownloadStore.lock) { SystemOwnedTransfers.flushParkedStartsLocked() }
            scheduleQueueWake()
        }
    }

    /** Wakes the queue from outside the lock, once a resolution has settled. */
    internal fun scheduleQueueWake() {
        synchronized(DownloadStore.lock) { scheduleRetryWakeLocked() }
    }

    internal fun startResolvedDownloadLocked(item: DownloadItem, transfer: ActiveTransfer) {
        val sourceUrl = item.sourceUrl ?: run {
            activeHandles.remove(item.id)
            DownloadStore.mutateLocked(item.id, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Failed,
                    failureKind = null,
                    activity = null,
                    errorMessage = runBlocking { getString(Res.string.downloads_enqueue_missing_url) },
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
            return
        }
        val request = item.toPlatformRequest(sourceUrl)
        // The item is already published as downloading by the time this runs, so a
        // platform that refuses to start would strand it there with no handle. Android
        // does exactly that when the system declines to schedule the background job.
        val handle = runCatching {
            DownloadsPlatformDownloader.start(
                request = request,
                listener = EngineTransferListener(item.id, transfer.generation),
            )
        }.getOrNull()

        if (handle == null) {
            if (activeHandles[item.id] === transfer) {
                activeHandles.remove(item.id)
            }
            val now = DownloadsClock.nowEpochMs()
            val attempt = item.attemptCount + 1
            DownloadStore.mutateLocked(item.id, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    attemptCount = attempt,
                    nextRetryAtEpochMs = now +
                        retryBackoffMs(attempt, DownloadFailureReason.Transient),
                    activity = DownloadActivity.RETRY_BACKOFF,
                    updatedAtEpochMs = now,
                )
            }
            return
        }
        DownloadStore.mutateLocked(item.id, immediate = true) { current ->
            current.copy(activity = DownloadActivity.TRANSFERRING)
        }
        transfer.attach(handle)
    }

    /**
     * Wakes the queue when the earliest backoff expires, or to check on a transfer.
     *
     * Items waiting out a retry are skipped by the planner, so without a timer they
     * would sit queued until some unrelated event nudged the queue. Transfers in
     * flight need the same timer for the opposite reason: a stalled one reports
     * nothing, so the only way to notice is to look.
     */
    internal fun scheduleRetryWakeLocked() {
        retryWakeJob?.cancel()
        retryWakeJob = null

        val now = DownloadsClock.nowEpochMs()
        val earliestRetry = if (activeHandles.size >= maxConcurrentTransfers) {
            null
        } else {
            DownloadStore.allItems
                .filter { it.status == DownloadStatus.Queued }
                .mapNotNull { it.nextRetryAtEpochMs }
                .filter { it > now }
                .minOrNull()
        }
        val earliestStallCheck = DownloadStore.allItems
            .filter { it.status == DownloadStatus.Downloading && !DownloadsPlatformDownloader.transferHost.ownsTransferLiveness }
            .minOfOrNull { it.updatedAtEpochMs + DownloadsTiming.queueWatchdogTimeoutMs }
        val earliest = listOfNotNull(earliestRetry, earliestStallCheck).minOrNull() ?: return

        retryWakeJob = scope.launch {
            delay((earliest - now).coerceAtLeast(0L))
            startPendingTransfers()
        }
    }

    /**
     * True when [generation] is still the attempt this download is running.
     *
     * Anything a transfer reports after it has been replaced describes a download
     * that no longer exists, and acting on it is how a live transfer used to lose its
     * handle to a cancelled one.
     */
    internal fun isCurrentTransferLocked(downloadId: String, generation: Long): Boolean =
        activeHandles[downloadId]?.generation == generation
}

/**
 * One attempt at transferring one download, and the slot it occupies.
 *
 * A download can be stopped and started again in the same breath - the reclaim
 * sweep and the preemption path both re-queue an item and then start it before
 * releasing the lock - and stopping a transfer does not stop it instantly. The
 * cancelled attempt gets its last word in afterwards, from its own thread.
 *
 * Callbacks used to be keyed by download id alone, so that last word landed on
 * whichever attempt was running by then: it took the live handle out of
 * [activeHandles], leaving a transfer nothing could pause or cancel and a slot
 * the queue believed was free, and stamped the item paused at the byte count the
 * *previous* attempt had reached. On desktop nothing ever resumes a system pause,
 * so that download sat there unfinished while the queue moved on to the next one.
 *
 * [generation] is what tells two attempts apart. Everything a transfer reports is
 * checked against it, and an attempt that has been replaced is ignored.
 *
 * The handle arrives after the fact, because the slot has to be held while the
 * source URL is re-minted (a network round trip that cannot happen under the
 * lock) and while the platform is starting the transfer. Cancelling before it
 * arrives is remembered, so a transfer that starts into a slot already given up
 * is stopped rather than left running.
 */
internal class ActiveTransfer(val generation: Long) : DownloadsTaskHandle {
    private var handle: DownloadsTaskHandle? = null
    private var cancelled = false
    private var abandoned = false

    fun attach(started: DownloadsTaskHandle) {
        if (abandoned) return
        if (cancelled) {
            started.cancel()
        } else {
            handle = started
        }
    }

    /**
     * Gives the slot up without stopping anything. Used when the platform hands the
     * same download's real transfer to a newer attempt: cancelling here would stop
     * that transfer, because on iOS a handle addresses the download, not the attempt.
     */
    fun abandon() {
        abandoned = true
        handle = null
    }

    override fun cancel() {
        cancelled = true
        handle?.cancel()
        handle = null
    }
}

internal class EngineTransferListener(
    private val downloadId: String,
    private val generation: Long,
) : DownloadTransferListener {
    override fun onOpened(
        resumedFromBytes: Long,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
    ) = DownloadScheduler.onTransferOpened(
        downloadId,
        generation,
        resumedFromBytes,
        totalBytes,
        etag,
        lastModified,
    )

    override fun onProgress(downloadedBytes: Long, totalBytes: Long?) =
        DownloadScheduler.onTransferProgress(downloadId, generation, downloadedBytes, totalBytes)

    override fun onCompleted(localFileUri: String, totalBytes: Long) =
        DownloadScheduler.onTransferCompleted(downloadId, generation, localFileUri, totalBytes)

    override fun onPaused(downloadedBytes: Long) =
        DownloadScheduler.onTransferPaused(downloadId, generation, downloadedBytes)

    override fun onFailed(
        reason: DownloadFailureReason,
        message: String,
        downloadedBytes: Long,
    ) = DownloadScheduler.onTransferFailed(
        downloadId,
        generation,
        reason,
        message,
        downloadedBytes,
    )
}

internal fun DownloadItem.toPlatformRequest(sourceUrl: String) = DownloadPlatformRequest(
    downloadId = id,
    sourceUrl = sourceUrl,
    sourceHeaders = sourceHeaders,
    destinationFileName = fileName,
    allowMeteredNetwork = mayUseMeteredNetwork(DownloadStore.deviceSettings.value.mobileData),
    knownTotalBytes = totalBytes,
    resumeEtag = resumeEtag,
    resumeLastModified = resumeLastModified,
    queuePosition = queuePosition,
    sourceUrlResolvedAtEpochMs = sourceUrlResolvedAtEpochMs,
    )
