package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The downloads API every screen, notification and platform host uses.
 *
 * Phase 9, stage 4 split the engine that used to live here (2,700 lines) into:
 * - [DownloadStore]: persistence, migration, one device-wide store and the per-profile views;
 * - [DownloadScheduler]: slots, the connectivity and mobile-data gates, retries, the watchdog and
 *   the transfer callbacks;
 * - [SourceRealizer]: re-minting, size verification and freshness of a source;
 * - [SystemOwnedTransfers]: the iOS background-session model, behind [TransferHost.SystemOwned].
 *
 * This object keeps the public surface and the user's actions. What a screen sees is the active
 * profile's downloads ([uiState], [batches]); the engine works on every profile's.
 */
object DownloadsRepository {
    /** The default downloads-at-once on Android and desktop; see [DownloadDeviceSettings]. */
    const val MAX_CONCURRENT_TRANSFERS = DownloadDeviceSettings.DEFAULT_MAX_CONCURRENT

    /** The queue's slot count: the device setting on Android and desktop, the window on iOS. */
    internal val maxConcurrentTransfers: Int
        get() = DownloadScheduler.maxConcurrentTransfers

    /** The active profile's downloads. */
    val uiState: StateFlow<DownloadsUiState> = DownloadStore.view
    val sourcePolicy: StateFlow<DownloadSourcePolicy> = DownloadStore.sourcePolicy
    /** The active profile's batches. */
    val batches: StateFlow<List<DownloadBatch>> = DownloadStore.batchesView
    val presets: StateFlow<List<DownloadPreset>> = DownloadStore.presets

    /**
     * Every profile's downloads - for what acts on the device rather than a screen: the Android
     * host and its "Pause all", which must not stop while another profile's queue is running.
     */
    internal val deviceItems: StateFlow<List<DownloadItem>> = DownloadStore.allFlow

    /** Whether the Android background host may run on mobile data; see [DownloadHostPlanner]. */
    internal fun hostMayUseMeteredNetwork(): Boolean =
        DownloadHostPlanner.mayUseMeteredNetwork(DownloadStore.allItems, DownloadStore.deviceSettings.value.mobileData)

    internal fun hostQueueSummary(): String =
        DownloadHostPlanner.describe(DownloadStore.allItems, DownloadStore.deviceSettings.value.mobileData)

    /** This device's download settings (mobile data, downloads at once). Never synced. */
    val deviceSettings: StateFlow<DownloadDeviceSettings> = DownloadStore.deviceSettings

    /** See [DownloadScheduler.isMeteredNetwork]. */
    internal var isMeteredNetwork: () -> Boolean
        get() = DownloadScheduler.isMeteredNetwork
        set(value) {
            DownloadScheduler.isMeteredNetwork = value
        }

    /** See [SourceRealizer.resolvePlayableStream]. */
    internal var resolvePlayableStream: suspend (StreamItem, Int?, Int?) -> DownloadSourceResolution
        get() = SourceRealizer.resolvePlayableStream
        set(value) {
            SourceRealizer.resolvePlayableStream = value
        }

    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        synchronized(DownloadStore.lock) {
            if (DownloadStore.hasLoaded) return
            DownloadStore.loadLocked()
            DownloadScheduler.startNetworkObserverLocked()
        }
        DownloadStore.followActiveProfile()
        DownloadDiagnostics.note("engine_start", engineSummary())
        SystemOwnedTransfers.prepareUpcomingTransfers()
        SystemOwnedTransfers.adoptPlatformTransfers()
        DownloadScheduler.startPendingTransfers()
    }

    /** One line saying how this engine is set up, for the diagnostics log. */
    internal fun engineSummary(): String {
        val host = DownloadsPlatformDownloader.transferHost
        val hostName = when (host) {
            is TransferHost.InProcess -> "in_process recoversSystemPauses=${host.recoversSystemPauses}"
            is TransferHost.SystemOwned -> "system_owned window=${host.window}"
        }
        val settings = DownloadStore.deviceSettings.value
        return "host=$hostName slots=${DownloadScheduler.maxConcurrentTransfers} " +
            "mobileData=${settings.mobileData} maxConcurrent=${settings.maxConcurrent} " +
            "metered=${runCatching { DownloadScheduler.isMeteredNetwork() }.getOrNull()} " +
            "items=${DownloadStore.allItems.size} viewing=${DownloadStore.activeOwner()}"
    }

    fun updateDeviceSettings(transform: (DownloadDeviceSettings) -> DownloadDeviceSettings) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val next = transform(DownloadStore.deviceSettings.value)
            if (next == DownloadStore.deviceSettings.value) return
            DownloadStore.deviceSettings.value = next
            DownloadStore.persistLocked(immediate = true)
        }
        DownloadDiagnostics.note("device_settings", engineSummary())
        DownloadScheduler.startPendingTransfers()
    }

    /** "Download now anyway": this item may use mobile data. */
    fun allowMobileData(downloadIds: Collection<String>) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val ids = downloadIds.toSet()
            DownloadStore.publishLocked(
                DownloadStore.allItems.map { item ->
                    if (item.id !in ids || item.allowMeteredNetwork) item else item.copy(
                        allowMeteredNetwork = true,
                        activity = if (item.activity == DownloadActivity.WAITING_FOR_WIFI) {
                            DownloadActivity.QUEUED_FOR_SLOT
                        } else {
                            item.activity
                        },
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                },
                immediate = true,
            )
        }
        DownloadDiagnostics.note("mobile_data_allowed", "items=${downloadIds.size}")
        DownloadScheduler.startPendingTransfers()
    }

    /** The platform saw the network change (Wi-Fi back, mobile data gone): look again. */
    fun onNetworkChanged() {
        if (!DownloadStore.hasLoaded) return
        DownloadScheduler.startPendingTransfers()
    }

    /** Replaces the app feed without leaking the previous collector into queue tests. */
    internal fun installConnectivityFeedForTests(feed: DownloadConnectivityFeed) =
        DownloadScheduler.installConnectivityFeedForTests(feed)

    internal fun restoreConnectivityFeedAfterTests() = DownloadScheduler.restoreConnectivityFeedAfterTests()

    /**
     * The active profile changed: the screens now show that profile's downloads. The engine is
     * device-wide, so nothing starts, stops or reloads.
     */
    fun onProfileChanged() {
        ensureLoaded()
        synchronized(DownloadStore.lock) { DownloadStore.refreshViewsLocked() }
        DownloadDiagnostics.note(
            "profile_view",
            "viewing=${DownloadStore.activeOwner()} visible=${DownloadStore.view.value.items.size} " +
                "device=${DownloadStore.allItems.size}",
        )
    }

    fun clearLocalState() {
        synchronized(DownloadStore.lock) {
            DownloadScheduler.resetLocked()
            DownloadStore.clearLocked()
        }
    }

    /** This profile's current download of an episode or film, straight from the store. */
    fun currentItemFor(parentMetaId: String, seasonNumber: Int?, episodeNumber: Int?): DownloadItem? {
        ensureLoaded()
        val key = downloadLogicalKey(parentMetaId = parentMetaId, seasonNumber = seasonNumber, episodeNumber = episodeNumber)
        return synchronized(DownloadStore.lock) {
            DownloadStore.allItems.firstOrNull { it.logicalContentKey == key && DownloadStore.isInActiveView(it) }
        }
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return DownloadStore.view.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? = findDownload(parentMetaId, seasonNumber, episodeNumber, videoId) {
        it.hasPlayableLocalFile()
    }

    /**
     * The finished download for this title or episode, whether or not its file is still there.
     *
     * [findPlayableDownload] answers "can I play it"; this answers "did the user download it",
     * which is what lets an offline play request say *"the file is missing"* instead of the
     * misleading *"not downloaded"*.
     */
    fun findCompletedDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? = findDownload(parentMetaId, seasonNumber, episodeNumber, videoId) {
        it.status == DownloadStatus.Completed
    }

    private fun findDownload(
        parentMetaId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        videoId: String?,
        accept: (DownloadItem) -> Boolean,
    ): DownloadItem? {
        ensureLoaded()
        val items = DownloadStore.view.value.items
        val normalizedParentMetaId = parentMetaId.trim()
        val normalizedVideoId = videoId?.trim().orEmpty()

        if (normalizedVideoId.isNotBlank()) {
            items.firstOrNull { it.videoId == normalizedVideoId && accept(it) }?.let { return it }
        }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    accept(item)
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    accept(item)
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            synchronized(DownloadStore.lock) {
                DownloadStore.mutateLocked(item.id, immediate = true) { current ->
                    if (current.fileName == item.fileName) {
                        current.copy(
                            localFileUri = resolvedUri,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    } else {
                        current
                    }
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        calculatedCapBytes: Long? = null,
        allowMeteredNetwork: Boolean = false,
        expectedSizeBytes: Long? = stream.behaviorHints.videoSize,
        sourceOrigin: DownloadSourceOrigin? = null,
        sourceUrlResolvedAtEpochMs: Long? = null,
        /** The user already accepted this source's size, so nothing may re-ask. */
        sizeCapOverrideApproved: Boolean = false,
    ): DownloadEnqueueResult {
        ensureLoaded()
        DownloadsLiveStatusPlatform.onDownloadRequested()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (sourceUrl == null && sourceOrigin == null) return DownloadEnqueueResult.MissingUrl
        if (sourceUrl != null && !sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        val freeStorageBytes = DownloadsPlatformDownloader.freeStorageBytes()
        if (
            freeStorageBytes > 0L &&
            expectedSizeBytes != null &&
            expectedSizeBytes > freeStorageBytes
        ) {
            return DownloadEnqueueResult.InsufficientStorage
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = downloadLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        val replacedExisting = synchronized(DownloadStore.lock) {
            val currentItems = DownloadStore.allItems.toMutableList()
            // Another profile's copy of the same episode is theirs, not a duplicate of this one.
            val existing = currentItems.firstOrNull {
                it.logicalContentKey == logicalKey && DownloadStore.isInActiveView(it)
            }
            if (existing != null) {
                DownloadScheduler.activeHandles.remove(existing.id)?.cancel()
                DownloadsPlatformDownloader.removeFile(
                    DownloadsPlatformDownloader.resolveLocalFileUri(
                        localFileUri = existing.localFileUri,
                        destinationFileName = existing.fileName,
                    ) ?: existing.localFileUri,
                )
                DownloadsPlatformDownloader.removePartialFile(existing.fileName)
                currentItems.removeAll { it.id == existing.id }
            }

            val downloadId = nextDownloadId(now)
            val fileName = buildFileName(
                title = title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                fallbackTitle = stream.streamLabel,
                sourceUrl = sourceUrl,
                nowEpochMs = now,
            )

            val item = DownloadItem(
                id = downloadId,
                ownerProfileId = DownloadStore.activeOwner(),
                contentType = contentType,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                videoId = videoId,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                episodeThumbnail = episodeThumbnail,
                streamTitle = stream.streamLabel,
                streamSubtitle = stream.streamSubtitle,
                providerName = stream.addonName,
                providerAddonId = stream.addonId,
                sourceUrl = sourceUrl,
                sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
                sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                localFileUri = null,
                fileName = fileName,
                status = DownloadStatus.Queued,
                activity = DownloadActivity.QUEUED_FOR_SLOT,
                downloadedBytes = 0L,
                totalBytes = null,
                sourceOrigin = sourceOrigin,
                sourceUrlResolvedAtEpochMs = sourceUrlResolvedAtEpochMs,
                calculatedCapBytes = calculatedCapBytes?.takeIf { it > 0L },
                expectedSizeBytes = expectedSizeBytes?.takeIf { it > 0L },
                allowMeteredNetwork = allowMeteredNetwork,
                sizeCapOverrideApproved = sizeCapOverrideApproved,
                // Appended, not prepended: a season batch is enqueued in episode order
                // and prepending made it download backwards, E10 before E01.
                queuePosition = DownloadQueuePlanner.nextQueuePosition(currentItems),
                errorMessage = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )

            currentItems.add(item)
            DownloadStore.publishLocked(currentItems, immediate = true)
            existing != null
        }

        DownloadScheduler.startPendingTransfers()

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    /** Pauses on the user's behalf, which means it stays paused until they say otherwise. */
    fun pauseDownload(downloadId: String) = pauseDownloads(listOf(downloadId))

    /**
     * [pauseDownload] for several at once - a season row, the notification's "Pause all" - as one
     * step: every one is paused before the queue is asked what to start next.
     *
     * One at a time, each pause freed a slot and the queue filled it with the next item on the
     * list, which the next pause then stopped. `.52`'s "Pause all" on a season fired three real
     * requests at the provider this way, each cancelled a moment after it went out.
     */
    fun pauseDownloads(downloadIds: Collection<String>) {
        ensureLoaded()
        val paused = synchronized(DownloadStore.lock) {
            val ids = downloadIds.toSet()
            val affected = DownloadStore.allItems.filter {
                it.id in ids && (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued)
            }
            affected.forEach { item ->
                DownloadScheduler.activeHandles.remove(item.id)?.cancel()
                DownloadStore.mutateLocked(item.id, immediate = true) { current ->
                    current.copy(
                        status = DownloadStatus.Paused,
                        pauseReason = DownloadPauseReason.User,
                        activity = DownloadActivity.USER_PAUSED,
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        errorMessage = null,
                    )
                }
            }
            affected.isNotEmpty()
        }
        if (paused) DownloadScheduler.startPendingTransfers()
    }

    /**
     * Stops everything because the platform asked us to, not because the user did.
     *
     * These come back on their own via [resumeSystemPausedDownloads]; anything the
     * user paused by hand is left alone.
     */
    fun pauseActiveDownloads() {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val now = DownloadsClock.nowEpochMs()
            val affected = DownloadStore.allItems.filter {
                it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued
            }
            if (affected.isEmpty()) return
            affected.forEach { DownloadScheduler.activeHandles.remove(it.id)?.cancel() }
            val affectedIds = affected.map { it.id }.toSet()
            DownloadStore.publishLocked(
                DownloadStore.allItems.map { item ->
                    if (item.id !in affectedIds) {
                        item
                    } else {
                        item.copy(
                            status = DownloadStatus.Paused,
                            pauseReason = DownloadPauseReason.System,
                            activity = DownloadActivity.SYSTEM_PAUSED,
                            updatedAtEpochMs = now,
                        )
                    }
                },
                immediate = true,
            )
        }
    }

    /**
     * Puts system-paused transfers back in the queue.
     *
     * Called when the app returns to the foreground, when downloads are reloaded, and
     * whenever the queue is nudged. Without it a backgrounded app or a reclaimed
     * background job left the whole queue paused with nothing to ever restart it.
     */
    fun resumeSystemPausedDownloads() {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val now = DownloadsClock.nowEpochMs()
            val resumable = DownloadStore.allItems.filter { it.isSystemPaused }
            if (resumable.isEmpty()) return
            val resumableIds = resumable.map { it.id }.toSet()
            DownloadStore.publishLocked(
                DownloadStore.allItems.map { item ->
                    if (item.id !in resumableIds) {
                        item
                    } else {
                        item.copy(
                            status = DownloadStatus.Queued,
                            pauseReason = null,
                            activity = DownloadActivity.QUEUED_FOR_SLOT,
                            errorMessage = null,
                            updatedAtEpochMs = now,
                        )
                    }
                },
                immediate = true,
            )
        }
        DownloadScheduler.startPendingTransfers()
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val item = DownloadStore.allItems.firstOrNull { it.id == downloadId } ?: return
            if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

            // Trust the bytes on disk over the last figure we recorded: a process death
            // mid-transfer can leave the two disagreeing, and the partial file is what a
            // resume actually continues from.
            val partialBytes = DownloadsPlatformDownloader.partialFileBytes(item.fileName)

            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    activity = DownloadActivity.QUEUED_FOR_SLOT,
                    errorMessage = null,
                    failureKind = null,
                    localFileUri = null,
                    downloadedBytes = partialBytes,
                    // Whatever stopped this is not held against the fresh attempt, and a
                    // stale link is re-minted on the way back in rather than replayed.
                    attemptCount = 0,
                    sourceUrlResolvedAtEpochMs = null,
                    nextRetryAtEpochMs = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        DownloadScheduler.startPendingTransfers()
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val item = DownloadStore.allItems.firstOrNull { it.id == downloadId } ?: return

            DownloadScheduler.activeHandles.remove(downloadId)?.cancel()
            DownloadsPlatformDownloader.removeFile(
                DownloadsPlatformDownloader.resolveLocalFileUri(
                    localFileUri = item.localFileUri,
                    destinationFileName = item.fileName,
                ) ?: item.localFileUri,
            )
            DownloadsPlatformDownloader.removePartialFile(item.fileName)

            DownloadStore.publishLocked(
                DownloadQueuePlanner.normalized(
                    DownloadStore.allItems.filterNot { it.id == downloadId },
                ),
                immediate = true,
            )
        }
        DownloadScheduler.startPendingTransfers()
    }

    /**
     * Promotes an item to the front of the queue and starts it now.
     *
     * If every slot is busy the lowest priority transfer is put back in the queue to
     * make room. Its partial file is kept, so it carries on from where it stopped
     * once a slot frees up rather than starting over.
     */
    fun moveDownloadToTop(downloadId: String) = moveDownload(downloadId, QueueMove.ToTop)

    fun moveDownloadUp(downloadId: String) = moveDownload(downloadId, QueueMove.Up)

    fun moveDownloadDown(downloadId: String) = moveDownload(downloadId, QueueMove.Down)

    fun moveDownloadToBottom(downloadId: String) = moveDownload(downloadId, QueueMove.ToBottom)

    private fun moveDownload(downloadId: String, move: QueueMove) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val reordered = DownloadQueuePlanner.reorderedInView(
                items = DownloadStore.allItems,
                downloadId = downloadId,
                move = move,
                inView = DownloadStore::isInActiveView,
            )
            if (reordered === DownloadStore.allItems) return

            val preempted = if (move == QueueMove.ToTop) {
                DownloadQueuePlanner.preemptionCandidate(
                    items = reordered,
                    promotedId = downloadId,
                    activeIds = DownloadScheduler.activeHandles.keys.toSet(),
                    maxConcurrent = DownloadScheduler.maxConcurrentTransfers,
                )
            } else {
                null
            }

            if (preempted == null) {
                DownloadStore.publishLocked(reordered, immediate = true)
            } else {
                DownloadScheduler.activeHandles.remove(preempted.id)?.cancel()
                val now = DownloadsClock.nowEpochMs()
                DownloadStore.publishLocked(
                    reordered.map { item ->
                        if (item.id != preempted.id) {
                            item
                        } else {
                            item.copy(
                                status = DownloadStatus.Queued,
                                pauseReason = null,
                                updatedAtEpochMs = now,
                            )
                        }
                    },
                    immediate = true,
                )
            }
        }
        DownloadScheduler.startPendingTransfers()
    }

    /** A season's row in the queue: up or down past the neighbouring row (Phase 9). */
    fun moveQueueGroup(groupKey: String, up: Boolean) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val moved = DownloadQueuePlanner.movedGroup(
                items = DownloadStore.allItems,
                groupKey = groupKey,
                up = up,
                groupOf = DownloadQueueGrouping::keyOf,
                inView = DownloadStore::isInActiveView,
            )
            if (moved === DownloadStore.allItems) return
            DownloadStore.publishLocked(moved, immediate = true)
        }
        DownloadScheduler.startPendingTransfers()
    }

    fun resumeDownloads(downloadIds: Collection<String>) = downloadIds.forEach(::resumeDownload)

    /** "Cancel remaining" on a season row: the unfinished ones go, what is downloaded stays. */
    fun cancelDownloads(downloadIds: Collection<String>) = downloadIds.forEach(::cancelDownload)

    /** Drops entries that never became downloads ("Remove" on an attention card). */
    fun removeBatchEntries(batchId: String, entryIds: Set<String>) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val batch = DownloadStore.batches.value.firstOrNull { it.id == batchId } ?: return
            val kept = batch.entries.filterNot { it.id in entryIds }
            DownloadStore.batches.value = if (kept.isEmpty()) {
                DownloadStore.batches.value.filterNot { it.id == batchId }
            } else {
                DownloadStore.batches.value.map { if (it.id == batchId) it.copy(entries = kept) else it }
            }
            DownloadStore.notifyBatchLiveStatusPlatform()
            DownloadStore.persistLocked()
        }
    }

    /** Removes every download belonging to one movie or series, files included. */
    fun deleteDownloadsForTitle(parentMetaId: String) {
        deleteDownloadsMatching(parentMetaId) { true }
    }

    /** Removes every downloaded episode of one season, files included. */
    fun deleteDownloadsForSeason(parentMetaId: String, season: Int) {
        deleteDownloadsMatching(parentMetaId) { it.seasonNumber == season }
    }

    private fun deleteDownloadsMatching(
        parentMetaId: String,
        predicate: (DownloadItem) -> Boolean,
    ) {
        ensureLoaded()
        val normalizedParentMetaId = parentMetaId.trim()
        if (normalizedParentMetaId.isEmpty()) return

        synchronized(DownloadStore.lock) {
            val doomed = DownloadStore.allItems.filter {
                DownloadStore.isInActiveView(it) &&
                    it.parentMetaId.trim() == normalizedParentMetaId && predicate(it)
            }
            if (doomed.isEmpty()) return

            doomed.forEach { item ->
                DownloadScheduler.activeHandles.remove(item.id)?.cancel()
                // Resolved directly rather than through playableLocalFileUri, which takes
                // this same lock and cannot be re-entered on native targets.
                DownloadsPlatformDownloader.removeFile(
                    DownloadsPlatformDownloader.resolveLocalFileUri(
                        localFileUri = item.localFileUri,
                        destinationFileName = item.fileName,
                    ) ?: item.localFileUri,
                )
                DownloadsPlatformDownloader.removePartialFile(item.fileName)
            }

            val doomedIds = doomed.map { it.id }.toSet()
            DownloadStore.publishLocked(
                DownloadQueuePlanner.normalized(
                    DownloadStore.allItems.filterNot { it.id in doomedIds },
                ),
                immediate = true,
            )
        }
        DownloadScheduler.startPendingTransfers()
    }

    fun setAddonAllowed(key: AddonSourceKey, allowed: Boolean, enabledKeys: Set<AddonSourceKey>) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val current = DownloadStore.sourcePolicy.value
            val explicit = (current.allowedAddons ?: enabledKeys).toMutableSet()
            if (allowed) explicit += key else explicit -= key
            DownloadStore.sourcePolicy.value = current.copy(allowedAddons = explicit)
            DownloadStore.persistLocked()
        }
    }

    fun setAioProviderAllowed(key: AddonSourceKey, provider: String, allowed: Boolean) {
        ensureLoaded()
        val normalized = provider.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(DownloadStore.lock) {
            val current = DownloadStore.sourcePolicy.value
            val providers = (
                current.allowedAioProviders[key]
                    ?: current.discoveredAioProviders[key].orEmpty()
                ).toMutableSet()
            if (allowed) providers += normalized else providers -= normalized
            DownloadStore.sourcePolicy.value = current.copy(
                allowedAioProviders = current.allowedAioProviders + (key to providers),
            )
            DownloadStore.persistLocked()
        }
    }

    fun recordDiscoveredAioProvider(key: AddonSourceKey, facts: SourceFacts) {
        val provider = (facts.providerName ?: facts.providerId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val current = DownloadStore.sourcePolicy.value
            val discovered = current.discoveredAioProviders[key].orEmpty()
            if (provider in discovered) return
            DownloadStore.sourcePolicy.value = current.copy(
                discoveredAioProviders = current.discoveredAioProviders +
                    (key to (discovered + provider)),
            )
            DownloadStore.persistLocked()
        }
    }

    fun setAioOverride(key: AddonSourceKey, enabled: Boolean) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val overrides = DownloadStore.sourcePolicy.value.aioOverrides.toMutableSet()
            if (enabled) overrides += key else overrides -= key
            DownloadStore.sourcePolicy.value = DownloadStore.sourcePolicy.value.copy(aioOverrides = overrides)
            DownloadStore.persistLocked()
        }
    }

    fun approveUnexpectedSize(downloadId: String) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val item = DownloadStore.allItems.firstOrNull { it.id == downloadId } ?: return
            if (!item.sizeApprovalRequired) return
            DownloadStore.mutateLocked(downloadId, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    activity = DownloadActivity.QUEUED_FOR_SLOT,
                    sizeApprovalRequired = false,
                    sizeCapOverrideApproved = true,
                    errorMessage = null,
                    attemptCount = 0,
                    nextRetryAtEpochMs = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        DownloadScheduler.startPendingTransfers()
    }

    fun saveBatch(batch: DownloadBatch) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            val owned = if (batch.ownerProfileId == null) batch.copy(ownerProfileId = DownloadStore.activeOwner()) else batch
            DownloadStore.batches.value = listOf(owned) + DownloadStore.batches.value.filterNot { it.id == batch.id }
            DownloadStore.notifyBatchLiveStatusPlatform()
            DownloadStore.persistLocked()
        }
    }

    fun updateBatchEntry(batchId: String, entry: DownloadBatchEntry) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            DownloadStore.batches.update { batches ->
                batches.map { batch ->
                    if (batch.id != batchId) {
                        batch
                    } else {
                        batch.copy(
                            entries = batch.entries.map {
                                if (it.id == entry.id) entry else it
                            },
                        )
                    }
                }
            }
            DownloadStore.notifyBatchLiveStatusPlatform()
            DownloadStore.persistLocked()
        }
    }

    /** Changes one batch in place; nothing happens when it no longer exists. */
    fun updateBatch(batchId: String, transform: (DownloadBatch) -> DownloadBatch) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            if (DownloadStore.batches.value.none { it.id == batchId }) return
            DownloadStore.batches.value = DownloadStore.batches.value.map { if (it.id == batchId) transform(it) else it }
            DownloadStore.notifyBatchLiveStatusPlatform()
            DownloadStore.persistLocked()
        }
    }

    fun removeBatch(batchId: String) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            DownloadStore.batches.value = DownloadStore.batches.value.filterNot { it.id == batchId }
            DownloadStore.notifyBatchLiveStatusPlatform()
            DownloadStore.persistLocked()
        }
    }

    fun updatePreset(preset: DownloadPreset) {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            DownloadStore.presets.value = DownloadStore.presets.value.map { if (it.id == preset.id) preset else it }
            DownloadStore.persistLocked()
        }
    }

    fun resetPresets() {
        ensureLoaded()
        synchronized(DownloadStore.lock) {
            DownloadStore.presets.value = DownloadPreset.BuiltIns
            DownloadStore.persistLocked()
        }
    }

    /**
     * Queues the batch's ready entries (and, with [approveUnknownSizes], the ones waiting for the
     * user's OK). [onlyEntryIds] limits it to those entries - "Download what fits", "Allow" on one
     * card.
     */
    fun queueBatch(batchId: String, approveUnknownSizes: Boolean, onlyEntryIds: Set<String>? = null): Int {
        ensureLoaded()
        val batch = DownloadStore.batches.value.firstOrNull { it.id == batchId } ?: return 0
        var queued = 0
        val updatedEntries = batch.entries.map { entry ->
            if (onlyEntryIds != null && entry.id !in onlyEntryIds) return@map entry
            val selection = entry.selection
            val canQueue =
                (entry.state == DownloadBatchEntryState.READY && selection is SourceSelectionResult.Selected) ||
                    (
                        entry.canBeApproved &&
                            approveUnknownSizes &&
                            selection is SourceSelectionResult.ApprovalNeeded
                        )
            if (!canQueue) return@map entry

            val streamUrl: String?
            val sourceOrigin: DownloadSourceOrigin?
            val addonKey: AddonSourceKey
            val calculatedCapBytes: Long
            val expectedSizeBytes: Long?
            // An entry only reaches here on approval when the user asked for it, and
            // that decision has to travel with the download. Without it the transfer
            // met the cap check again mid-flight and stopped a source that had already
            // been accepted in the review dialog.
            var sizeApproved = false
            when (selection) {
                is SourceSelectionResult.Selected -> {
                    streamUrl = selection.streamUrl
                    sourceOrigin = selection.sourceOrigin ?: entry.sourceOrigin
                    addonKey = selection.addonKey
                    calculatedCapBytes = selection.calculatedCapBytes
                    expectedSizeBytes = selection.facts.sizeBytes
                }
                is SourceSelectionResult.ApprovalNeeded -> {
                    streamUrl = selection.streamUrl
                    sourceOrigin = selection.sourceOrigin ?: entry.sourceOrigin
                    addonKey = selection.addonKey
                    calculatedCapBytes = selection.calculatedCapBytes
                    expectedSizeBytes = selection.facts.sizeBytes
                    sizeApproved = true
                }
                else -> return@map entry
            }
            val stream = sourceOrigin?.stream ?: StreamItem(
                name = entry.streamTitle,
                description = entry.streamSubtitle,
                url = streamUrl,
                addonName = entry.providerName ?: addonKey.manifestId,
                addonId = entry.providerAddonId ?: addonKey.manifestId,
                addonManifestUrl = addonKey.manifestUrl,
                behaviorHints = StreamBehaviorHints(
                    proxyHeaders = StreamProxyHeaders(request = entry.sourceHeaders),
                ),
            )
            val result = enqueueFromStream(
                contentType = batch.contentType,
                videoId = entry.videoId,
                parentMetaId = batch.parentMetaId,
                parentMetaType = batch.parentMetaType,
                title = batch.title,
                logo = batch.logo,
                poster = batch.poster,
                background = batch.background,
                seasonNumber = entry.season,
                episodeNumber = entry.episode,
                episodeTitle = entry.title.takeIf { entry.season != null },
                episodeThumbnail = null,
                stream = stream,
                calculatedCapBytes = calculatedCapBytes,
                allowMeteredNetwork = batch.allowMeteredNetwork,
                expectedSizeBytes = expectedSizeBytes,
                sourceOrigin = sourceOrigin,
                // Lazy sources have never been minted. Legacy eager selections keep
                // their URL but are force-refreshed because an origin is present.
                sourceUrlResolvedAtEpochMs = null,
                sizeCapOverrideApproved = sizeApproved,
            )
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                queued += 1
                entry.copy(state = DownloadBatchEntryState.QUEUED, failureMessage = null)
            } else {
                entry.copy(state = DownloadBatchEntryState.FAILED, failureMessage = result.name)
            }
        }
        synchronized(DownloadStore.lock) {
            DownloadStore.batches.value = DownloadStore.batches.value.map {
                if (it.id == batchId) it.copy(entries = updatedEntries) else it
            }
            DownloadStore.notifyBatchLiveStatusPlatform()
            DownloadStore.persistLocked()
        }
        SystemOwnedTransfers.prepareUpcomingTransfers()
        return queued
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null

}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String?,
    nowEpochMs: Long,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = (sourceUrl ?: fallbackTitle).fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(nowEpochMs.toString(36))
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
