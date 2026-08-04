package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    const val MAX_CONCURRENT_TRANSFERS = 2

    /**
     * Progress used to rewrite the whole payload on every chunk. Disk writes are now
     * coalesced to this interval; state transitions still persist immediately.
     */
    private const val PERSIST_MIN_INTERVAL_MS = 1_000L

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()
    private val _sourcePolicy = MutableStateFlow(DownloadSourcePolicy())
    val sourcePolicy: StateFlow<DownloadSourcePolicy> = _sourcePolicy.asStateFlow()
    private val _batches = MutableStateFlow<List<DownloadBatch>>(emptyList())
    val batches: StateFlow<List<DownloadBatch>> = _batches.asStateFlow()
    private val _presets = MutableStateFlow(DownloadPreset.BuiltIns)
    val presets: StateFlow<List<DownloadPreset>> = _presets.asStateFlow()

    /**
     * Guards every mutation below.
     *
     * Transfer callbacks arrive on network IO threads while the UI and the
     * notification receiver mutate from their own, so the read-modify-write cycles
     * here need serialising. Held only for state changes - never while suspending,
     * and never re-entered, since this lock is not reentrant on native targets.
     */
    private val stateLock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private var hasLoaded = false
    private var networkObserverStarted = false
    private var nextDownloadOrdinal = 0L
    private var lastPersistAtEpochMs = 0L
    private var hasPendingPersist = false
    private var retryWakeJob: Job? = null

    fun ensureLoaded() {
        synchronized(stateLock) {
            if (hasLoaded) return
            loadFromDiskLocked()
            startNetworkObserverLocked()
        }
        startPendingTransfers()
    }

    /**
     * Brings the queue back when connectivity returns.
     *
     * Losing the network is the most common reason a transfer stops, and waiting for
     * the user to notice and tap resume on each item is not a recovery story.
     */
    private fun startNetworkObserverLocked() {
        if (networkObserverStarted) return
        networkObserverStarted = true
        scope.launch {
            var wasOnline = NetworkStatusRepository.uiState.value.isOnline
            NetworkStatusRepository.uiState.collect { state ->
                val isOnline = state.isOnline
                if (isOnline && !wasOnline) {
                    resumeSystemPausedDownloads()
                    startPendingTransfers()
                }
                wasOnline = isOnline
            }
        }
    }

    fun onProfileChanged() {
        synchronized(stateLock) { loadFromDiskLocked() }
        startPendingTransfers()
    }

    fun clearLocalState() {
        synchronized(stateLock) {
            activeHandles.values.forEach(DownloadsTaskHandle::cancel)
            activeHandles.clear()
            retryWakeJob?.cancel()
            retryWakeJob = null
            hasLoaded = false
            hasPendingPersist = false
            _uiState.value = DownloadsUiState()
            _sourcePolicy.value = DownloadSourcePolicy()
            _batches.value = emptyList()
            _presets.value = DownloadPreset.BuiltIns
            notifyLiveStatusPlatform()
        }
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
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
            synchronized(stateLock) {
                mutateLocked(item.id, immediate = true) { current ->
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
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
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

        val replacedExisting = synchronized(stateLock) {
            val currentItems = _uiState.value.items.toMutableList()
            val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
            if (existing != null) {
                activeHandles.remove(existing.id)?.cancel()
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
                downloadedBytes = 0L,
                totalBytes = null,
                calculatedCapBytes = calculatedCapBytes?.takeIf { it > 0L },
                expectedSizeBytes = expectedSizeBytes?.takeIf { it > 0L },
                allowMeteredNetwork = allowMeteredNetwork,
                // Appended, not prepended: a season batch is enqueued in episode order
                // and prepending made it download backwards, E10 before E01.
                queuePosition = DownloadQueuePlanner.nextQueuePosition(currentItems),
                errorMessage = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )

            currentItems.add(item)
            publishLocked(currentItems, immediate = true)
            existing != null
        }

        startPendingTransfers()

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    /** Pauses on the user's behalf, which means it stays paused until they say otherwise. */
    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            if (item.status != DownloadStatus.Downloading && item.status != DownloadStatus.Queued) return

            activeHandles.remove(downloadId)?.cancel()
            mutateLocked(downloadId, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Paused,
                    pauseReason = DownloadPauseReason.User,
                    nextRetryAtEpochMs = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    errorMessage = null,
                )
            }
        }
        startPendingTransfers()
    }

    /**
     * Stops everything because the platform asked us to, not because the user did.
     *
     * These come back on their own via [resumeSystemPausedDownloads]; anything the
     * user paused by hand is left alone.
     */
    fun pauseActiveDownloads() {
        ensureLoaded()
        synchronized(stateLock) {
            val now = DownloadsClock.nowEpochMs()
            val affected = _uiState.value.items.filter {
                it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued
            }
            if (affected.isEmpty()) return
            affected.forEach { activeHandles.remove(it.id)?.cancel() }
            val affectedIds = affected.map { it.id }.toSet()
            publishLocked(
                _uiState.value.items.map { item ->
                    if (item.id !in affectedIds) {
                        item
                    } else {
                        item.copy(
                            status = DownloadStatus.Paused,
                            pauseReason = DownloadPauseReason.System,
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
        synchronized(stateLock) {
            val now = DownloadsClock.nowEpochMs()
            val resumable = _uiState.value.items.filter { it.isSystemPaused }
            if (resumable.isEmpty()) return
            val resumableIds = resumable.map { it.id }.toSet()
            publishLocked(
                _uiState.value.items.map { item ->
                    if (item.id !in resumableIds) {
                        item
                    } else {
                        item.copy(
                            status = DownloadStatus.Queued,
                            pauseReason = null,
                            errorMessage = null,
                            updatedAtEpochMs = now,
                        )
                    }
                },
                immediate = true,
            )
        }
        startPendingTransfers()
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return
            if (item.sizeApprovalRequired && !item.sizeCapOverrideApproved) return

            // Trust the bytes on disk over the last figure we recorded: a process death
            // mid-transfer can leave the two disagreeing, and the partial file is what a
            // resume actually continues from.
            val partialBytes = DownloadsPlatformDownloader.partialFileBytes(item.fileName)

            mutateLocked(downloadId, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    errorMessage = null,
                    localFileUri = null,
                    downloadedBytes = partialBytes,
                    attemptCount = 0,
                    nextRetryAtEpochMs = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        startPendingTransfers()
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

            activeHandles.remove(downloadId)?.cancel()
            DownloadsPlatformDownloader.removeFile(
                DownloadsPlatformDownloader.resolveLocalFileUri(
                    localFileUri = item.localFileUri,
                    destinationFileName = item.fileName,
                ) ?: item.localFileUri,
            )
            DownloadsPlatformDownloader.removePartialFile(item.fileName)

            publishLocked(
                DownloadQueuePlanner.normalized(
                    _uiState.value.items.filterNot { it.id == downloadId },
                ),
                immediate = true,
            )
        }
        startPendingTransfers()
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
        synchronized(stateLock) {
            val reordered = DownloadQueuePlanner.reordered(_uiState.value.items, downloadId, move)
            if (reordered === _uiState.value.items) return

            val preempted = if (move == QueueMove.ToTop) {
                DownloadQueuePlanner.preemptionCandidate(
                    items = reordered,
                    promotedId = downloadId,
                    activeIds = activeHandles.keys.toSet(),
                    maxConcurrent = MAX_CONCURRENT_TRANSFERS,
                )
            } else {
                null
            }

            if (preempted == null) {
                publishLocked(reordered, immediate = true)
            } else {
                activeHandles.remove(preempted.id)?.cancel()
                val now = DownloadsClock.nowEpochMs()
                publishLocked(
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
        startPendingTransfers()
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

        synchronized(stateLock) {
            val doomed = _uiState.value.items.filter {
                it.parentMetaId.trim() == normalizedParentMetaId && predicate(it)
            }
            if (doomed.isEmpty()) return

            doomed.forEach { item ->
                activeHandles.remove(item.id)?.cancel()
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
            publishLocked(
                DownloadQueuePlanner.normalized(
                    _uiState.value.items.filterNot { it.id in doomedIds },
                ),
                immediate = true,
            )
        }
        startPendingTransfers()
    }

    fun setAddonAllowed(key: AddonSourceKey, allowed: Boolean, enabledKeys: Set<AddonSourceKey>) {
        ensureLoaded()
        synchronized(stateLock) {
            val current = _sourcePolicy.value
            val explicit = (current.allowedAddons ?: enabledKeys).toMutableSet()
            if (allowed) explicit += key else explicit -= key
            _sourcePolicy.value = current.copy(allowedAddons = explicit)
            persistLocked()
        }
    }

    fun setAioProviderAllowed(key: AddonSourceKey, provider: String, allowed: Boolean) {
        ensureLoaded()
        val normalized = provider.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(stateLock) {
            val current = _sourcePolicy.value
            val providers = (
                current.allowedAioProviders[key]
                    ?: current.discoveredAioProviders[key].orEmpty()
                ).toMutableSet()
            if (allowed) providers += normalized else providers -= normalized
            _sourcePolicy.value = current.copy(
                allowedAioProviders = current.allowedAioProviders + (key to providers),
            )
            persistLocked()
        }
    }

    fun recordDiscoveredAioProvider(key: AddonSourceKey, facts: SourceFacts) {
        val provider = (facts.providerName ?: facts.providerId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        ensureLoaded()
        synchronized(stateLock) {
            val current = _sourcePolicy.value
            val discovered = current.discoveredAioProviders[key].orEmpty()
            if (provider in discovered) return
            _sourcePolicy.value = current.copy(
                discoveredAioProviders = current.discoveredAioProviders +
                    (key to (discovered + provider)),
            )
            persistLocked()
        }
    }

    fun setAioOverride(key: AddonSourceKey, enabled: Boolean) {
        ensureLoaded()
        synchronized(stateLock) {
            val overrides = _sourcePolicy.value.aioOverrides.toMutableSet()
            if (enabled) overrides += key else overrides -= key
            _sourcePolicy.value = _sourcePolicy.value.copy(aioOverrides = overrides)
            persistLocked()
        }
    }

    fun approveUnexpectedSize(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            if (!item.sizeApprovalRequired) return
            mutateLocked(downloadId, immediate = true) { current ->
                current.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    sizeApprovalRequired = false,
                    sizeCapOverrideApproved = true,
                    errorMessage = null,
                    attemptCount = 0,
                    nextRetryAtEpochMs = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        startPendingTransfers()
    }

    fun saveBatch(batch: DownloadBatch) {
        ensureLoaded()
        synchronized(stateLock) {
            _batches.value = listOf(batch) + _batches.value.filterNot { it.id == batch.id }
            persistLocked()
        }
    }

    fun updateBatchEntry(batchId: String, entry: DownloadBatchEntry) {
        ensureLoaded()
        synchronized(stateLock) {
            _batches.update { batches ->
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
            persistLocked()
        }
    }

    fun removeBatch(batchId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            _batches.value = _batches.value.filterNot { it.id == batchId }
            persistLocked()
        }
    }

    fun updatePreset(preset: DownloadPreset) {
        ensureLoaded()
        synchronized(stateLock) {
            _presets.value = _presets.value.map { if (it.id == preset.id) preset else it }
            persistLocked()
        }
    }

    fun resetPresets() {
        ensureLoaded()
        synchronized(stateLock) {
            _presets.value = DownloadPreset.BuiltIns
            persistLocked()
        }
    }

    fun queueBatch(batchId: String, approveUnknownSizes: Boolean): Int {
        ensureLoaded()
        val batch = _batches.value.firstOrNull { it.id == batchId } ?: return 0
        var queued = 0
        val updatedEntries = batch.entries.map { entry ->
            val selection = entry.selection
            val canQueue =
                (entry.state == DownloadBatchEntryState.READY && selection is SourceSelectionResult.Selected) ||
                    (
                        entry.state == DownloadBatchEntryState.APPROVAL_NEEDED &&
                            approveUnknownSizes &&
                            selection is SourceSelectionResult.ApprovalNeeded
                        )
            if (!canQueue) return@map entry

            val streamUrl: String
            val addonKey: AddonSourceKey
            val calculatedCapBytes: Long
            val expectedSizeBytes: Long?
            when (selection) {
                is SourceSelectionResult.Selected -> {
                    streamUrl = selection.streamUrl
                    addonKey = selection.addonKey
                    calculatedCapBytes = selection.calculatedCapBytes
                    expectedSizeBytes = selection.facts.sizeBytes
                }
                is SourceSelectionResult.ApprovalNeeded -> {
                    streamUrl = selection.streamUrl
                    addonKey = selection.addonKey
                    calculatedCapBytes = selection.calculatedCapBytes
                    expectedSizeBytes = selection.facts.sizeBytes
                }
                else -> return@map entry
            }
            val stream = StreamItem(
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
            )
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                queued += 1
                entry.copy(state = DownloadBatchEntryState.QUEUED, failureMessage = null)
            } else {
                entry.copy(state = DownloadBatchEntryState.FAILED, failureMessage = result.name)
            }
        }
        synchronized(stateLock) {
            _batches.value = _batches.value.map {
                if (it.id == batchId) it.copy(entries = updatedEntries) else it
            }
            persistLocked()
        }
        return queued
    }

    // --- Transfer callbacks -------------------------------------------------------

    private fun onTransferOpened(
        downloadId: String,
        resumedFromBytes: Long,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
    ) {
        synchronized(stateLock) {
            mutateLocked(downloadId, immediate = true) { current ->
                if (current.status != DownloadStatus.Downloading) {
                    current
                } else {
                    current.copy(
                        downloadedBytes = resumedFromBytes.coerceAtLeast(0L),
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
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

    private fun onTransferProgress(downloadId: String, downloadedBytes: Long, totalBytes: Long?) {
        val exceededCap = synchronized(stateLock) {
            val current = _uiState.value.items.firstOrNull { it.id == downloadId }
                ?: return@synchronized false
            val cap = current.calculatedCapBytes
            val actualOrReported = listOfNotNull(
                downloadedBytes.takeIf { it > 0L },
                totalBytes?.takeIf { it > 0L },
            ).maxOrNull()

            if (
                cap != null &&
                !current.sizeCapOverrideApproved &&
                actualOrReported != null &&
                actualOrReported > cap
            ) {
                activeHandles.remove(downloadId)?.cancel()
                mutateLocked(downloadId, immediate = true) {
                    it.copy(
                        status = DownloadStatus.Paused,
                        pauseReason = DownloadPauseReason.SizeApproval,
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        totalBytes = totalBytes?.takeIf { value -> value > 0L } ?: it.totalBytes,
                        sizeApprovalRequired = true,
                        errorMessage = "Actual source size exceeds this preset's cap",
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                return@synchronized true
            }

            mutateLocked(downloadId, immediate = false) { item ->
                if (item.status != DownloadStatus.Downloading) {
                    item
                } else {
                    item.copy(
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: item.totalBytes,
                        // Bytes arriving means the source works, so a previous run of bad
                        // luck should not count against this attempt's retry budget.
                        attemptCount = if (downloadedBytes > item.downloadedBytes) 0 else item.attemptCount,
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        errorMessage = null,
                    )
                }
            }
            false
        }

        if (exceededCap) startPendingTransfers()
    }

    private fun onTransferCompleted(downloadId: String, localFileUri: String, totalBytes: Long) {
        // A whole, valid, playable file is not proof the download worked. Debrid
        // providers answer with a small placeholder video while they queue the real
        // one, and it passes every check the transfer itself can make.
        val placeholder = synchronized(stateLock) {
            val current = _uiState.value.items.firstOrNull { it.id == downloadId }
            current != null && isImplausiblySmallForMedia(totalBytes, current.expectedSizeBytes)
        }
        if (placeholder) {
            onTransferFailed(
                downloadId = downloadId,
                reason = DownloadFailureReason.SourceNotReady,
                message = runBlocking { getString(Res.string.downloads_error_source_not_ready) },
                downloadedBytes = 0L,
                discardFiles = true,
            )
            return
        }

        synchronized(stateLock) {
            activeHandles.remove(downloadId)
            mutateLocked(downloadId, immediate = true) { current ->
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
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        startPendingTransfers()
    }

    private fun onTransferPaused(downloadId: String, downloadedBytes: Long) {
        synchronized(stateLock) {
            activeHandles.remove(downloadId)
            mutateLocked(downloadId, immediate = true) { current ->
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

    private fun onTransferFailed(
        downloadId: String,
        reason: DownloadFailureReason,
        message: String,
        downloadedBytes: Long,
        /** Throw away what arrived, for bytes that are not part of the real file. */
        discardFiles: Boolean = false,
    ) {
        val fallbackMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } }
        synchronized(stateLock) {
            activeHandles.remove(downloadId)
            if (discardFiles) {
                _uiState.value.items.firstOrNull { it.id == downloadId }?.let { item ->
                    DownloadsPlatformDownloader.removeFile(
                        DownloadsPlatformDownloader.resolveLocalFileUri(
                            localFileUri = item.localFileUri,
                            destinationFileName = item.fileName,
                        ) ?: item.localFileUri,
                    )
                    DownloadsPlatformDownloader.removePartialFile(item.fileName)
                }
            }
            mutateLocked(downloadId, immediate = true) { current ->
                if (current.status != DownloadStatus.Downloading) {
                    current.copy(downloadedBytes = downloadedBytes.coerceAtLeast(0L))
                } else {
                    val attempt = current.attemptCount + 1
                    val now = DownloadsClock.nowEpochMs()
                    if (shouldRetry(reason, attempt)) {
                        // Backed off rather than retried on the spot: a dead network used
                        // to burn every attempt within milliseconds of the first failure.
                        current.copy(
                            status = DownloadStatus.Queued,
                            pauseReason = null,
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            localFileUri = if (discardFiles) null else current.localFileUri,
                            attemptCount = attempt,
                            nextRetryAtEpochMs = now + retryBackoffMs(attempt, reason),
                            errorMessage = fallbackMessage,
                            updatedAtEpochMs = now,
                        )
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            pauseReason = null,
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            localFileUri = if (discardFiles) null else current.localFileUri,
                            attemptCount = attempt,
                            nextRetryAtEpochMs = null,
                            errorMessage = fallbackMessage,
                            updatedAtEpochMs = now,
                        )
                    }
                }
            }
        }
        startPendingTransfers()
    }

    // --- Queue scheduling ---------------------------------------------------------

    private fun startPendingTransfers() {
        synchronized(stateLock) {
            val now = DownloadsClock.nowEpochMs()
            val startable = DownloadQueuePlanner.startable(
                items = _uiState.value.items,
                activeIds = activeHandles.keys.toSet(),
                maxConcurrent = MAX_CONCURRENT_TRANSFERS,
                nowEpochMs = now,
            )

            if (startable.isNotEmpty()) {
                val startingIds = startable.map { it.id }.toSet()
                publishLocked(
                    _uiState.value.items.map { item ->
                        if (item.id !in startingIds) {
                            item
                        } else {
                            item.copy(
                                status = DownloadStatus.Downloading,
                                pauseReason = null,
                                nextRetryAtEpochMs = null,
                                updatedAtEpochMs = now,
                            )
                        }
                    },
                    immediate = true,
                )

                startable.forEach { queuedItem ->
                    val current = _uiState.value.items.firstOrNull { it.id == queuedItem.id }
                        ?: queuedItem
                    startDownloadLocked(current)
                }
            }

            scheduleRetryWakeLocked()
        }
    }

    private fun startDownloadLocked(item: DownloadItem) {
        val request = DownloadPlatformRequest(
            sourceUrl = item.sourceUrl,
            sourceHeaders = item.sourceHeaders,
            destinationFileName = item.fileName,
            allowMeteredNetwork = item.allowMeteredNetwork,
            knownTotalBytes = item.totalBytes,
            resumeEtag = item.resumeEtag,
            resumeLastModified = item.resumeLastModified,
        )
        activeHandles[item.id] = DownloadsPlatformDownloader.start(
            request = request,
            listener = RepositoryTransferListener(item.id),
        )
    }

    /**
     * Wakes the queue when the earliest backoff expires.
     *
     * Items waiting out a retry are skipped by the planner, so without a timer they
     * would sit queued until some unrelated event nudged the queue.
     */
    private fun scheduleRetryWakeLocked() {
        retryWakeJob?.cancel()
        retryWakeJob = null
        if (activeHandles.size >= MAX_CONCURRENT_TRANSFERS) return

        val now = DownloadsClock.nowEpochMs()
        val earliest = _uiState.value.items
            .filter { it.status == DownloadStatus.Queued }
            .mapNotNull { it.nextRetryAtEpochMs }
            .filter { it > now }
            .minOrNull()
            ?: return

        retryWakeJob = scope.launch {
            delay((earliest - now).coerceAtLeast(0L))
            startPendingTransfers()
        }
    }

    private class RepositoryTransferListener(
        private val downloadId: String,
    ) : DownloadTransferListener {
        override fun onOpened(
            resumedFromBytes: Long,
            totalBytes: Long?,
            etag: String?,
            lastModified: String?,
        ) = DownloadsRepository.onTransferOpened(
            downloadId,
            resumedFromBytes,
            totalBytes,
            etag,
            lastModified,
        )

        override fun onProgress(downloadedBytes: Long, totalBytes: Long?) =
            DownloadsRepository.onTransferProgress(downloadId, downloadedBytes, totalBytes)

        override fun onCompleted(localFileUri: String, totalBytes: Long) =
            DownloadsRepository.onTransferCompleted(downloadId, localFileUri, totalBytes)

        override fun onPaused(downloadedBytes: Long) =
            DownloadsRepository.onTransferPaused(downloadId, downloadedBytes)

        override fun onFailed(
            reason: DownloadFailureReason,
            message: String,
            downloadedBytes: Long,
        ) = DownloadsRepository.onTransferFailed(downloadId, reason, message, downloadedBytes)
    }

    // --- State plumbing -----------------------------------------------------------

    private fun loadFromDiskLocked() {
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            _sourcePolicy.value = DownloadSourcePolicy()
            _batches.value = emptyList()
            _presets.value = DownloadPreset.BuiltIns
            notifyLiveStatusPlatform()
            return
        }

        val stored = DownloadsCodec.decode(payload)
        _sourcePolicy.value = stored.sourcePolicy
        _batches.value = stored.batches.map { batch ->
            batch.copy(
                entries = batch.entries.map { entry ->
                    if (
                        entry.state == DownloadBatchEntryState.DISCOVERING ||
                        entry.state == DownloadBatchEntryState.RESOLVING
                    ) {
                        entry.copy(
                            state = DownloadBatchEntryState.FAILED,
                            failureMessage = "Preparation was interrupted; choose a source manually or start the batch again",
                        )
                    } else {
                        entry
                    }
                },
            )
        }
        _presets.value = stored.presets

        val now = DownloadsClock.nowEpochMs()
        val restored = stored.items.map { item ->
            val withLocalUri = normalizeCompletedLocalFileUri(item)
            when {
                // Nothing is transferring yet after a cold start, so anything recorded as
                // in flight goes back in the queue to be picked up in rank order.
                withLocalUri.status == DownloadStatus.Downloading -> withLocalUri.copy(
                    status = DownloadStatus.Queued,
                    nextRetryAtEpochMs = null,
                    updatedAtEpochMs = now,
                )
                withLocalUri.isSystemPaused -> withLocalUri.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    updatedAtEpochMs = now,
                )
                // Downloads that finished before placeholders were detected are still
                // recorded as complete, and look playable until the debrid provider's
                // "queued, waiting for a slot" video plays instead of the episode.
                // Re-queueing them heals a library that already has some.
                withLocalUri.status == DownloadStatus.Completed &&
                    isImplausiblySmallForMedia(
                        finalBytes = withLocalUri.totalBytes ?: withLocalUri.downloadedBytes,
                        expectedBytes = withLocalUri.expectedSizeBytes,
                    ) -> {
                    DownloadsPlatformDownloader.removeFile(
                        DownloadsPlatformDownloader.resolveLocalFileUri(
                            localFileUri = withLocalUri.localFileUri,
                            destinationFileName = withLocalUri.fileName,
                        ) ?: withLocalUri.localFileUri,
                    )
                    DownloadsPlatformDownloader.removePartialFile(withLocalUri.fileName)
                    withLocalUri.copy(
                        status = DownloadStatus.Queued,
                        pauseReason = null,
                        localFileUri = null,
                        downloadedBytes = 0L,
                        totalBytes = null,
                        attemptCount = 0,
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = now,
                    )
                }
                else -> withLocalUri
            }
        }

        // Payloads written before ranks existed carry the default position for every
        // item, so they are renumbered from their stored order on first load.
        val normalized = DownloadQueuePlanner.normalized(restored)
        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (normalized != stored.items) {
            persistLocked(immediate = true)
        }
    }

    private fun mutateLocked(
        downloadId: String,
        immediate: Boolean,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id != downloadId) {
                item
            } else {
                val next = transform(item)
                if (next != item) changed = true
                next
            }
        }

        if (changed) {
            publishLocked(updated, immediate = immediate)
        }
    }

    private fun publishLocked(items: List<DownloadItem>, immediate: Boolean) {
        _uiState.value = DownloadsUiState(items = items)
        _batches.value = _batches.value.map { batch ->
            batch.copy(
                entries = batch.entries.map { entry ->
                    val item = items.firstOrNull {
                        it.parentMetaId == batch.parentMetaId &&
                            it.videoId == entry.videoId &&
                            it.seasonNumber == entry.season &&
                            it.episodeNumber == entry.episode
                    } ?: return@map entry
                    entry.copy(
                        state = when (item.status) {
                            DownloadStatus.Queued -> DownloadBatchEntryState.QUEUED
                            DownloadStatus.Downloading -> DownloadBatchEntryState.DOWNLOADING
                            DownloadStatus.Paused -> DownloadBatchEntryState.PAUSED
                            DownloadStatus.Completed -> DownloadBatchEntryState.COMPLETED
                            DownloadStatus.Failed -> DownloadBatchEntryState.FAILED
                        },
                        failureMessage = item.errorMessage,
                    )
                },
            )
        }
        notifyLiveStatusPlatform()
        persistLocked(immediate = immediate)
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    /**
     * Writes the payload, coalescing the writes that progress produces.
     *
     * Every state transition passes `immediate`, so nothing that matters waits on a
     * timer; only the byte counters in between are allowed to lag.
     */
    private fun persistLocked(immediate: Boolean = true) {
        val now = DownloadsClock.nowEpochMs()
        if (immediate || now - lastPersistAtEpochMs >= PERSIST_MIN_INTERVAL_MS) {
            lastPersistAtEpochMs = now
            hasPendingPersist = false
            writePayloadLocked()
            return
        }

        if (hasPendingPersist) return
        hasPendingPersist = true
        scope.launch {
            delay(PERSIST_MIN_INTERVAL_MS)
            synchronized(stateLock) {
                if (!hasPendingPersist) return@synchronized
                hasPendingPersist = false
                lastPersistAtEpochMs = DownloadsClock.nowEpochMs()
                writePayloadLocked()
            }
        }
    }

    private fun writePayloadLocked() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encode(
                items = _uiState.value.items,
                sourcePolicy = _sourcePolicy.value,
                batches = _batches.value,
                presets = _presets.value,
            ),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

@Serializable
internal data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    val sourcePolicy: DownloadSourcePolicy = DownloadSourcePolicy(),
    val batches: List<DownloadBatch> = emptyList(),
    val presets: List<DownloadPreset> = DownloadPreset.BuiltIns,
)

internal object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = true
    }

    /**
     * Falling back to an empty payload discards every download, batch and preset, so
     * the unreadable text is set aside first rather than being overwritten by the
     * next save.
     */
    fun decode(payload: String): StoredDownloadsPayload =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload)
        }.getOrElse {
            runCatching { DownloadsStorage.saveCorruptPayload(payload) }
            StoredDownloadsPayload()
        }

    fun encode(
        items: Collection<DownloadItem>,
        sourcePolicy: DownloadSourcePolicy,
        batches: Collection<DownloadBatch>,
        presets: Collection<DownloadPreset>,
    ): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
                sourcePolicy = sourcePolicy,
                batches = batches.toList(),
                presets = presets.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true) ||
                normalizedKey.equals("If-Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
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

    val extension = sourceUrl.fileExtensionFromUrl()
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
