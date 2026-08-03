package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamProxyHeaders
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
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()
    private val _sourcePolicy = MutableStateFlow(DownloadSourcePolicy())
    val sourcePolicy: StateFlow<DownloadSourcePolicy> = _sourcePolicy.asStateFlow()
    private val _batches = MutableStateFlow<List<DownloadBatch>>(emptyList())
    val batches: StateFlow<List<DownloadBatch>> = _batches.asStateFlow()
    private val _presets = MutableStateFlow(DownloadPreset.BuiltIns)
    val presets: StateFlow<List<DownloadPreset>> = _presets.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        hasLoaded = false
        _uiState.value = DownloadsUiState()
        _sourcePolicy.value = DownloadSourcePolicy()
        _batches.value = emptyList()
        _presets.value = DownloadPreset.BuiltIns
        notifyLiveStatusPlatform()
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
            mutateItem(item.id) { current ->
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

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
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
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            calculatedCapBytes = calculatedCapBytes?.takeIf { it > 0L },
            allowMeteredNetwork = allowMeteredNetwork,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        startPendingTransfers()

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
        startPendingTransfers()
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return
        if (item.sizeApprovalRequired && !item.sizeCapOverrideApproved) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startPendingTransfers()
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
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

        val doomed = _uiState.value.items.filter {
            it.parentMetaId.trim() == normalizedParentMetaId && predicate(it)
        }
        if (doomed.isEmpty()) return

        doomed.forEach { item ->
            activeHandles.remove(item.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName)
        }

        val doomedIds = doomed.map { it.id }.toSet()
        publish(_uiState.value.items.filterNot { it.id in doomedIds })
        persist()
        startPendingTransfers()
    }

    fun setAddonAllowed(key: AddonSourceKey, allowed: Boolean, enabledKeys: Set<AddonSourceKey>) {
        ensureLoaded()
        val current = _sourcePolicy.value
        val explicit = (current.allowedAddons ?: enabledKeys).toMutableSet()
        if (allowed) explicit += key else explicit -= key
        _sourcePolicy.value = current.copy(allowedAddons = explicit)
        persist()
    }

    fun setAioProviderAllowed(key: AddonSourceKey, provider: String, allowed: Boolean) {
        ensureLoaded()
        val normalized = provider.trim().takeIf { it.isNotEmpty() } ?: return
        val current = _sourcePolicy.value
        val providers = (
            current.allowedAioProviders[key]
                ?: current.discoveredAioProviders[key].orEmpty()
            ).toMutableSet()
        if (allowed) providers += normalized else providers -= normalized
        _sourcePolicy.value = current.copy(
            allowedAioProviders = current.allowedAioProviders + (key to providers),
        )
        persist()
    }

    fun recordDiscoveredAioProvider(key: AddonSourceKey, facts: SourceFacts) {
        val provider = (facts.providerName ?: facts.providerId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        ensureLoaded()
        val current = _sourcePolicy.value
        val discovered = current.discoveredAioProviders[key].orEmpty()
        if (provider in discovered) return
        _sourcePolicy.value = current.copy(
            discoveredAioProviders = current.discoveredAioProviders +
                (key to (discovered + provider)),
        )
        persist()
    }

    fun setAioOverride(key: AddonSourceKey, enabled: Boolean) {
        ensureLoaded()
        val overrides = _sourcePolicy.value.aioOverrides.toMutableSet()
        if (enabled) overrides += key else overrides -= key
        _sourcePolicy.value = _sourcePolicy.value.copy(aioOverrides = overrides)
        persist()
    }

    fun approveUnexpectedSize(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (!item.sizeApprovalRequired) return
        replaceItem(
            item.copy(
                status = DownloadStatus.Downloading,
                sizeApprovalRequired = false,
                sizeCapOverrideApproved = true,
                errorMessage = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            ),
        )
        persist()
        startPendingTransfers()
    }

    fun saveBatch(batch: DownloadBatch) {
        ensureLoaded()
        _batches.value = listOf(batch) + _batches.value.filterNot { it.id == batch.id }
        persist()
    }

    fun updateBatchEntry(batchId: String, entry: DownloadBatchEntry) {
        ensureLoaded()
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
        persist()
    }

    fun removeBatch(batchId: String) {
        ensureLoaded()
        _batches.value = _batches.value.filterNot { it.id == batchId }
        persist()
    }

    fun updatePreset(preset: DownloadPreset) {
        ensureLoaded()
        _presets.value = _presets.value.map { if (it.id == preset.id) preset else it }
        persist()
    }

    fun resetPresets() {
        ensureLoaded()
        _presets.value = DownloadPreset.BuiltIns
        persist()
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
        _batches.value = _batches.value.map {
            if (it.id == batchId) it.copy(entries = updatedEntries) else it
        }
        persist()
        return queued
    }

    private fun loadFromDisk() {
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

        var shouldPersistNormalized = false
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
        val normalized = stored.items
            .map { item ->
                val localUriNormalized = normalizeCompletedLocalFileUri(item)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        startPendingTransfers()
    }

    private fun startPendingTransfers() {
        if (activeHandles.size >= MAX_CONCURRENT_TRANSFERS) return
        _uiState.value.items
            .asSequence()
            .filter { it.status == DownloadStatus.Downloading }
            .filterNot { activeHandles.containsKey(it.id) }
            .take(MAX_CONCURRENT_TRANSFERS - activeHandles.size)
            .toList()
            .forEach(::startDownload)
    }

    private fun startDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(
            sourceUrl = item.sourceUrl,
            sourceHeaders = item.sourceHeaders,
            destinationFileName = item.fileName,
            allowMeteredNetwork = item.allowMeteredNetwork,
        )

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                val actualOrReported = listOfNotNull(
                    downloadedBytes.takeIf { it > 0L },
                    totalBytes?.takeIf { it > 0L },
                ).maxOrNull()
                val current = _uiState.value.items.firstOrNull { it.id == item.id }
                val cap = current?.calculatedCapBytes
                if (
                    current != null &&
                    cap != null &&
                    !current.sizeCapOverrideApproved &&
                    actualOrReported != null &&
                    actualOrReported > cap
                ) {
                    activeHandles.remove(item.id)?.cancel()
                    mutateItem(item.id) {
                        it.copy(
                            status = DownloadStatus.Paused,
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { value -> value > 0L },
                            sizeApprovalRequired = true,
                            errorMessage = "Actual source size exceeds this preset's cap",
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                    startPendingTransfers()
                    return@start
                }
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                startPendingTransfers()
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
                startPendingTransfers()
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        _uiState.value = DownloadsUiState(
            items = items,
        )
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
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
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

    fun decode(payload: String): StoredDownloadsPayload =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload)
        }.getOrDefault(StoredDownloadsPayload())

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
                normalizedKey.equals("Range", ignoreCase = true)
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
