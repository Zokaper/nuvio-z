package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The engine half of [TransferHost.SystemOwned] - iOS (Phase 9, stage 4).
 *
 * Everything the queue does only because a system background session owns the transfers:
 * learning what the session really holds after a relaunch, letting it claim tasks it started while
 * the app was suspended, handing it a snapshot to chain from, submitting in queue order, and
 * resolving sources ahead of their slots before the app is suspended. Moved here from
 * `DownloadsRepository` **unchanged in behaviour**: this is the model that keeps a queue moving on
 * a locked iPhone (`.46`, `.47`), and it must not regress.
 *
 * On an [TransferHost.InProcess] platform nothing here runs - every entry point returns first.
 */
internal object SystemOwnedTransfers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val host: TransferHost.SystemOwned?
        get() = DownloadsPlatformDownloader.transferHost.systemOwned

    // --- Submission order (iOS) -------------------------------------------------
    //
    // iOS resolves a whole window of sources at once, and the answers come back in
    // whatever order the providers reply. Handing each to the session as it arrives
    // would create tasks out of queue order. A resolved item is parked instead, and
    // released only once everything ahead of it in the queue has been submitted or
    // has stopped resolving. Only the creation order is controlled here: which task
    // the system actually runs first is its own decision.

    internal class ParkedStart(val item: DownloadItem, val transfer: ActiveTransfer)

    internal val submitsInQueueOrder: Boolean
        get() = host != null
    internal val resolvingInOrder = mutableMapOf<String, ActiveTransfer>()
    internal val parkedStarts = mutableMapOf<String, ParkedStart>()

    internal fun flushParkedStartsLocked() {
        // An entry whose attempt has been replaced or dropped no longer holds its place.
        resolvingInOrder.entries.removeAll { (id, transfer) -> DownloadScheduler.activeHandles[id] !== transfer }
        parkedStarts.entries.removeAll { (id, parked) -> DownloadScheduler.activeHandles[id] !== parked.transfer }
        if (parkedStarts.isEmpty()) return
        val release = IosBackgroundTransferReconciler.releaseInQueueOrder(
            parkedIds = parkedStarts.keys,
            resolvingIds = resolvingInOrder.keys,
            positions = DownloadStore.allItems.associate { it.id to it.queuePosition },
        )
        for (id in release) {
            val parked = parkedStarts.remove(id) ?: continue
            val current = DownloadStore.allItems.firstOrNull { it.id == id }
            if (current == null || current.status != DownloadStatus.Downloading) {
                DownloadScheduler.activeHandles.remove(id)
                continue
            }
            DownloadScheduler.startResolvedDownloadLocked(parked.item, parked.transfer)
        }
    }

    // --- Platform-held transfers (iOS) --------------------------------------------
    //
    // A background URLSession task survives suspension and relaunch on its own, so on
    // iOS the queue has to learn what is really running rather than trust what it wrote
    // down. Ownership rules and the `.44` failure they replace are in
    // IosBackgroundTransferReconciler. Everywhere else the platform keeps no transfers
    // across a process death, reports none, and none of this does anything.

    /** Scheduling is held while this is set; see [DownloadScheduler.startPendingTransfers]. */
    internal var awaitingPlatformInventory = false
    private var platformInventoryGeneration = 0L

    /** Long enough for `getAllTasks`, short enough that a lost answer is not noticed. */
    private const val PLATFORM_INVENTORY_TIMEOUT_MS = 3_000L

    internal fun adoptPlatformTransfers() {
        // In-process platforms keep no transfers across a process death, so there is nothing to
        // learn and nothing to hold the queue for.
        if (host == null) return
        holdSchedulingForPlatformInventory()
        requestPlatformInventory()
    }

    /** Stops the queue from starting or reclaiming anything until the inventory arrives. */
    internal fun holdSchedulingForPlatformInventory() {
        synchronized(DownloadStore.lock) {
            awaitingPlatformInventory = true
            platformInventoryGeneration += 1
        }
    }

    internal fun requestPlatformInventory() {
        val coordinator = host?.coordinator ?: return
        val generation = synchronized(DownloadStore.lock) { platformInventoryGeneration }
        coordinator.requestInventory { live ->
            onPlatformInventory(generation, live)
        }
        val stillWaiting = synchronized(DownloadStore.lock) {
            awaitingPlatformInventory && platformInventoryGeneration == generation
        }
        if (!stillWaiting) return
        scope.launch {
            delay(PLATFORM_INVENTORY_TIMEOUT_MS)
            val released = synchronized(DownloadStore.lock) {
                if (awaitingPlatformInventory && platformInventoryGeneration == generation) {
                    awaitingPlatformInventory = false
                    true
                } else {
                    false
                }
            }
            // Duplicates stay impossible without the answer: the session never holds two
            // tasks for one download and a start attaches to the one it has.
            if (released) DownloadScheduler.startPendingTransfers()
        }
    }

    /**
     * Takes over the tasks the session really holds.
     *
     * Running tasks are adopted where they stand, so a relaunch keeps the order the
     * session was already working in; items recorded as downloading with nothing behind
     * them go back to the queue at the same position without being charged an attempt.
     */
    private fun onPlatformInventory(
        generation: Long,
        live: List<IosBackgroundTransferReconciler.LiveTransfer>?,
    ) {
        if (live == null) {
            synchronized(DownloadStore.lock) {
                if (platformInventoryGeneration == generation) awaitingPlatformInventory = false
            }
            return
        }
        synchronized(DownloadStore.lock) {
            if (platformInventoryGeneration != generation) return
            awaitingPlatformInventory = false
            val items = DownloadStore.allItems
            val plan = IosBackgroundTransferReconciler.planAdoption(
                items = items.map {
                    val claimed = it.id in DownloadScheduler.activeHandles
                    it.toAdoptionItem(
                        claimed = claimed,
                        transferring = claimed &&
                            it.activity != DownloadActivity.RESOLVING_SOURCE &&
                            it.id !in resolvingInOrder &&
                            it.id !in parkedStarts,
                    )
                },
                live = live,
                maxConcurrent = DownloadScheduler.maxConcurrentTransfers,
            )
            host?.coordinator?.let { coordinator ->
                if (plan.cancel.isNotEmpty() || plan.suspend.isNotEmpty() || plan.adopt.isNotEmpty()) {
                    DownloadDiagnostics.note(
                        "inventory_plan",
                        "live=${live.size} adopt=${plan.adopt.size} requeue=${plan.requeue.size} " +
                            "suspend=${plan.suspend.size} cancel=${plan.cancel.size} lost=${plan.releaseLost.size}",
                    )
                }
                plan.cancel.forEach(coordinator::cancel)
                plan.suspend.forEach(coordinator::suspend)
            }
            // Nothing will report for these again. Giving the slot up without cancelling
            // is enough: the next start either finds the finished file at its destination
            // and completes, or creates a new task.
            plan.releaseLost.forEach { downloadId ->
                DownloadDiagnostics.note("release_lost_claim", "id=$downloadId")
                DownloadScheduler.activeHandles.remove(downloadId)?.abandon()
                DownloadScheduler.transferSamples.remove(downloadId)
            }

            val requeue = plan.requeue.toSet() + plan.releaseLost
            val adopt = plan.adopt.toSet()
            if (requeue.isNotEmpty() || adopt.isNotEmpty()) {
                val liveById = live.associateBy { it.downloadId }
                val now = DownloadsClock.nowEpochMs()
                DownloadStore.publishLocked(
                    items.map { item ->
                        when (item.id) {
                            in requeue -> item.copy(
                                status = DownloadStatus.Queued,
                                pauseReason = null,
                                activity = DownloadActivity.QUEUED_FOR_SLOT,
                                nextRetryAtEpochMs = null,
                                updatedAtEpochMs = now,
                            )
                            in adopt -> {
                                val transfer = liveById.getValue(item.id)
                                item.copy(
                                    status = DownloadStatus.Downloading,
                                    pauseReason = null,
                                    activity = DownloadActivity.TRANSFERRING,
                                    downloadedBytes = maxOf(item.downloadedBytes, transfer.downloadedBytes),
                                    totalBytes = transfer.totalBytes ?: item.totalBytes,
                                    nextRetryAtEpochMs = null,
                                    errorMessage = null,
                                    // The session reported nothing while the app was
                                    // suspended; that silence is not a stall.
                                    updatedAtEpochMs = now,
                                )
                            }
                            else -> item
                        }
                    },
                    immediate = true,
                )
            }
            plan.adopt.forEach { downloadId ->
                DownloadStore.allItems.firstOrNull { it.id == downloadId }?.let(::adoptPlatformTransferLocked)
            }
        }
        DownloadScheduler.startPendingTransfers()
    }

    private fun adoptPlatformTransferLocked(item: DownloadItem) {
        val transfer = ActiveTransfer(++DownloadScheduler.nextTransferGeneration)
        DownloadScheduler.activeHandles[item.id] = transfer
        // The platform attaches to the task it already has; the URL is only a fallback
        // for a task that ended between the inventory and now.
        val handle = runCatching {
            DownloadsPlatformDownloader.start(
                request = item.toPlatformRequest(item.sourceUrl.orEmpty()),
                listener = EngineTransferListener(item.id, transfer.generation),
            )
        }.getOrNull()
        if (handle == null) {
            DownloadScheduler.activeHandles.remove(item.id)
            return
        }
        transfer.attach(handle)
    }

    /**
     * Called by the session for a task nobody is listening to: one it started in the
     * background, or one it found after a relaunch.
     *
     * [finishing] is set when the task has already ended. Its result is delivered whatever
     * the capacity, since the slot frees the moment it is, and even for a download the
     * user paused - a finished file is better recorded than thrown away.
     */
    internal fun claimNativeTransfer(
        downloadId: String,
        handle: DownloadsTaskHandle,
        finishing: Boolean,
    ): NativeTransferClaim {
        DownloadsRepository.ensureLoaded()
        synchronized(DownloadStore.lock) {
            val item = DownloadStore.allItems.firstOrNull { it.id == downloadId }
                ?: return NativeTransferClaim.Cancel
            if (item.status == DownloadStatus.Completed || item.status == DownloadStatus.Failed) {
                return NativeTransferClaim.Cancel
            }
            if (!finishing) {
                if (item.status == DownloadStatus.Paused && item.pauseReason != DownloadPauseReason.System) {
                    return NativeTransferClaim.Suspend
                }
                if (DownloadScheduler.activeHandles.keys.count { it != downloadId } >= DownloadScheduler.maxConcurrentTransfers) {
                    return NativeTransferClaim.Suspend
                }
            }
            // An attempt still resolving a URL for this download has been overtaken by
            // the real transfer. Its resolver is fenced on the generation and gives up.
            DownloadScheduler.activeHandles.remove(downloadId)?.abandon()
            val transfer = ActiveTransfer(++DownloadScheduler.nextTransferGeneration)
            transfer.attach(handle)
            DownloadScheduler.activeHandles[downloadId] = transfer
            val now = DownloadsClock.nowEpochMs()
            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Downloading,
                    pauseReason = null,
                    activity = DownloadActivity.TRANSFERRING,
                    nextRetryAtEpochMs = null,
                    errorMessage = null,
                    updatedAtEpochMs = now,
                )
            }
            return NativeTransferClaim.Adopted(EngineTransferListener(downloadId, transfer.generation))
        }
    }

    /**
     * The queue as the background scheduler may use it: queued items in order with the
     * URLs they already hold, and the slots the repository is holding.
     *
     * A source with no origin to re-mint from is a plain URL that does not expire, so it
     * is always fresh; only debrid-resolved ones age out.
     */
    internal fun nativeSchedulingSnapshot(): NativeSchedulingSnapshot {
        DownloadsRepository.ensureLoaded()
        synchronized(DownloadStore.lock) {
            val now = DownloadsClock.nowEpochMs()
            val prepared = DownloadStore.allItems
                .filter { it.status == DownloadStatus.Queued && !it.sizeApprovalRequired }
                .map { item ->
                    IosBackgroundTransferReconciler.IosPreparedTransfer(
                        downloadId = item.id,
                        destinationFileName = item.fileName,
                        sourceUrl = item.sourceUrl.orEmpty(),
                        sourceHeaders = item.sourceHeaders,
                        knownTotalBytes = item.totalBytes,
                        queuePosition = item.queuePosition,
                        sourceUrlResolvedAtEpochMs = if (item.sourceOrigin == null) {
                            now
                        } else {
                            item.sourceUrlResolvedAtEpochMs
                        },
                        allowMeteredNetwork = item.mayUseMeteredNetwork(DownloadStore.deviceSettings.value.mobileData),
                        notBeforeEpochMs = item.nextRetryAtEpochMs,
                    )
                }
            return NativeSchedulingSnapshot(prepared, DownloadScheduler.activeHandles.keys.toSet())
        }
    }

    /** The background queue reached an item whose source URL has gone stale. */
    internal fun onNativeSourceRefreshNeeded(downloadId: String) {
        DownloadStore.allItems.firstOrNull { it.id == downloadId }?.let {
            DownloadDiagnostics.resolving(it)
        }
        prepareUpcomingTransfers()
    }

    /**
     * The app is going to the background: refresh the URLs the session will need while
     * there is still time to, so it can keep advancing after the screen locks.
     */
    internal fun onPlatformBackground() {
        prepareUpcomingTransfers()
    }

    private fun DownloadItem.toAdoptionItem(claimed: Boolean, transferring: Boolean = false) =
        IosBackgroundTransferReconciler.AdoptionItem(
            id = id,
            state = when (status) {
                DownloadStatus.Downloading -> IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING
                DownloadStatus.Queued -> IosBackgroundTransferReconciler.AdoptionState.QUEUED
                DownloadStatus.Paused -> if (pauseReason == DownloadPauseReason.System) {
                    IosBackgroundTransferReconciler.AdoptionState.SYSTEM_PAUSED
                } else {
                    IosBackgroundTransferReconciler.AdoptionState.USER_PAUSED
                }
                DownloadStatus.Failed -> IosBackgroundTransferReconciler.AdoptionState.FAILED
                DownloadStatus.Completed -> IosBackgroundTransferReconciler.AdoptionState.COMPLETED
            },
            queuePosition = queuePosition,
            claimed = claimed,
            transferring = transferring,
        )


    private var isPreparingUpcomingSources = false

    /**
     * Resolves the next queued sources ahead of their slots, so the iOS background session
     * has fresh URLs to chain beyond the submitted window while the app is suspended.
     *
     * ⚠ **iOS only.** Everywhere else a queued source is resolved when it gets a slot and
     * never while offline - the desktop queue E2E suite asserts both. Running this there
     * contacted providers with no connection and resolved episodes ahead of their turn.
     */
    internal fun prepareUpcomingTransfers() {
        if (!DownloadsPlatformDownloader.transferHost.ownsTransferLiveness) return
        scope.launch {
            var shouldRun = false
            var toPrepare: List<DownloadItem> = emptyList()
            synchronized(DownloadStore.lock) {
                if (!isPreparingUpcomingSources) {
                    isPreparingUpcomingSources = true
                    shouldRun = true
                    val now = DownloadsClock.nowEpochMs()
                    toPrepare = DownloadStore.allItems
                        .filter {
                            it.status == DownloadStatus.Queued &&
                                it.sourceOrigin != null &&
                                (it.sourceUrl == null || it.isSourceUrlStale(now))
                        }
                        .sortedBy { it.queuePosition }
                        .take(5)
                }
            }
            if (!shouldRun || toPrepare.isEmpty()) {
                if (shouldRun) {
                    synchronized(DownloadStore.lock) { isPreparingUpcomingSources = false }
                }
                return@launch
            }

            try {
                for (item in toPrepare) {
                    val refreshed = SourceRealizer.refresh(item)
                    if (refreshed is RefreshedDownloadSource.Ready) {
                        synchronized(DownloadStore.lock) {
                            val current = DownloadStore.allItems.firstOrNull { it.id == item.id }
                            if (current != null && current.status == DownloadStatus.Queued) {
                                DownloadStore.mutateLocked(item.id, immediate = true) {
                                    refreshed.item
                                }
                            }
                        }
                    }
                }
            } finally {
                synchronized(DownloadStore.lock) {
                    isPreparingUpcomingSources = false
                }
            }
        }
    }
}
