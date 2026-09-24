package com.nuvio.app.features.downloads

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The device's downloads: persistence, migration and the per-profile views (Phase 9, stage 4).
 *
 * **One store per device.** Every profile's downloads live here, each tagged with its
 * [DownloadItem.ownerProfileId], and one engine keeps downloading across profile switches. What a
 * profile *sees* is [view] and [batchesView]; the engine ([DownloadScheduler],
 * [SystemOwnedTransfers]) always works on [allItems]. A profile switch changes the views and
 * nothing else - it used to reload the store, which on desktop swapped the whole queue out from
 * under running transfers.
 *
 * [lock] guards every mutation in the engine, not only this object's. Transfer callbacks arrive on
 * network IO threads while the UI and the notification receiver mutate from their own, so the
 * read-modify-write cycles need serialising. Held only for state changes - never while
 * suspending, and never re-entered, since the lock is not reentrant on native targets.
 */
internal object DownloadStore {
    val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Progress used to rewrite the whole payload on every chunk. Disk writes are coalesced to
     * this interval; state transitions still persist immediately.
     */
    private const val PERSIST_MIN_INTERVAL_MS = 1_000L

    private val all = MutableStateFlow<List<DownloadItem>>(emptyList())

    /** Every download on the device, whoever owns it. The engine's list. */
    val allItems: List<DownloadItem> get() = all.value
    val allFlow: StateFlow<List<DownloadItem>> = all.asStateFlow()

    private val _view = MutableStateFlow(DownloadsUiState())

    /** The active profile's downloads. What every screen shows. */
    val view: StateFlow<DownloadsUiState> = _view.asStateFlow()

    /** Every batch on the device. Mutated directly by the engine; see [notifyBatchLiveStatusPlatform]. */
    val batches = MutableStateFlow<List<DownloadBatch>>(emptyList())
    private val _batchesView = MutableStateFlow<List<DownloadBatch>>(emptyList())
    val batchesView: StateFlow<List<DownloadBatch>> = _batchesView.asStateFlow()

    val sourcePolicy = MutableStateFlow(DownloadSourcePolicy())
    val presets = MutableStateFlow(DownloadPreset.BuiltIns)
    val deviceSettings = MutableStateFlow(DownloadDeviceSettings())

    /**
     * The profile whose downloads the screens show. A variable so tests can stand in.
     *
     * The profile state is read first: a profile pull publishes the new active profile before it
     * updates [ProfileRepository.activeProfileId].
     */
    var activeOwner: () -> Int = {
        ProfileRepository.state.value.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    }
    private var followingProfiles = false

    /**
     * Keeps the views on the active profile however it changes. `onProfileChanged` covers a
     * switch, but startup restores the last profile and a profile pull can change it without
     * calling anyone, and a view computed before that would show the wrong profile's downloads
     * until the next mutation.
     */
    fun followActiveProfile() {
        if (followingProfiles) return
        followingProfiles = true
        scope.launch {
            ProfileRepository.state
                .map { activeOwner() }
                .distinctUntilChanged()
                .collect { synchronized(lock) { refreshViewsLocked() } }
        }
    }

    var hasLoaded = false
    private var lastPersistAtEpochMs = 0L
    private var hasPendingPersist = false

    fun isInActiveView(item: DownloadItem): Boolean = item.ownerProfileId == activeOwner()

    // --- Views --------------------------------------------------------------------

    /** Re-derives both views, after a mutation or a profile switch. */
    fun refreshViewsLocked() {
        val owner = activeOwner()
        _view.value = DownloadsUiState(items = all.value.filter { it.ownerProfileId == owner })
        _batchesView.value = batches.value.filter { it.ownerProfileId == owner }
    }

    // --- Mutation -----------------------------------------------------------------

    fun mutateLocked(
        downloadId: String,
        immediate: Boolean,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        var changed = false
        val updated = all.value.map { item ->
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

    fun publishLocked(items: List<DownloadItem>, immediate: Boolean) {
        all.value = items
        batches.value = reconcileBatches(batches.value, items)
        notifyLiveStatusPlatform()
        persistLocked(immediate = immediate)
    }

    /**
     * The Android notification and the iOS Live Activity count every profile's downloads: the
     * device is downloading them, whoever is looking.
     */
    fun notifyLiveStatusPlatform() {
        refreshViewsLocked()
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(all.value)
        }
        notifyBatchLiveStatusPlatform()
    }

    /**
     * Called from every batch mutation as well as from [publishLocked].
     *
     * Preparation moves through `updateBatchEntry` and `saveBatch`, which never touch the item
     * list, so hanging this off item changes alone would leave the platform showing nothing for
     * the whole discovery pass. It is also what keeps [batchesView] current.
     */
    fun notifyBatchLiveStatusPlatform() {
        _batchesView.value = batches.value.filter { it.ownerProfileId == activeOwner() }
        runCatching {
            DownloadsLiveStatusPlatform.onBatchesChanged(batches.value)
        }
    }

    // --- Persistence --------------------------------------------------------------

    /**
     * Writes the payload, coalescing the writes that progress produces.
     *
     * Every state transition passes `immediate`, so nothing that matters waits on a timer; only
     * the byte counters in between are allowed to lag.
     */
    fun persistLocked(immediate: Boolean = true) {
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
            synchronized(lock) {
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
                items = all.value,
                sourcePolicy = sourcePolicy.value,
                batches = batches.value,
                presets = presets.value,
                deviceSettings = deviceSettings.value,
            ),
        )
    }

    fun clearLocked() {
        hasLoaded = false
        hasPendingPersist = false
        all.value = emptyList()
        sourcePolicy.value = DownloadSourcePolicy()
        batches.value = emptyList()
        presets.value = DownloadPreset.BuiltIns
        notifyLiveStatusPlatform()
    }

    // --- Load ---------------------------------------------------------------------

    /**
     * Reads the device payload, migrating what came before it.
     *
     * - A payload with no owners (Android and iOS before Phase 9) gives every item and batch to
     *   the primary profile.
     * - No device payload but per-profile ones (desktop before Phase 9) merges them, each tagged
     *   with the profile it came from. The per-profile payloads are left where they are, so the
     *   migration can be inspected, and undone by an older build.
     */
    fun loadLocked() {
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        val raw = if (payload.isNotEmpty()) DownloadsCodec.decode(payload) else null
        val migratedOwners = raw?.items?.count { it.ownerProfileId == null } ?: 0
        val stored = if (raw != null) {
            DownloadStoreMigration.assignOwners(raw)
        } else {
            val legacy = runCatching { DownloadsStorage.loadLegacyProfilePayloads() }.getOrDefault(emptyMap())
                .mapValues { (_, text) -> DownloadsCodec.decode(text) }
            if (legacy.isEmpty()) {
                all.value = emptyList()
                sourcePolicy.value = DownloadSourcePolicy()
                batches.value = emptyList()
                presets.value = DownloadPreset.BuiltIns
                notifyLiveStatusPlatform()
                return
            }
            DownloadStoreMigration.mergeProfilePayloads(legacy).also { merged ->
                DownloadDiagnostics.note(
                    "store_migrated",
                    "profiles=${legacy.keys.sorted()} items=${merged.items.size} batches=${merged.batches.size}",
                )
            }
        }
        sourcePolicy.value = stored.sourcePolicy
        deviceSettings.value = stored.deviceSettings
        batches.value = stored.batches.map { batch ->
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
        // Presets are persisted, so a build that adds one reaches only fresh
        // installs unless the stored list is reconciled with what ships now.
        presets.value = mergeStoredPresets(stored.presets)

        val now = DownloadsClock.nowEpochMs()
        val restored = stored.items.map { item ->
            val withLocalUri = normalizeCompletedLocalFileUri(item)
            when {
                // Nothing is transferring yet after a cold start, so anything recorded as
                // in flight goes back in the queue to be picked up in rank order.
                withLocalUri.status == DownloadStatus.Downloading -> withLocalUri.copy(
                    status = DownloadStatus.Queued,
                    nextRetryAtEpochMs = null,
                    activity = DownloadActivity.QUEUED_FOR_SLOT,
                    updatedAtEpochMs = now,
                )
                withLocalUri.isSystemPaused -> withLocalUri.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    activity = DownloadActivity.QUEUED_FOR_SLOT,
                    updatedAtEpochMs = now,
                )
                // Downloads the old mid-transfer cap check stopped are sitting paused
                // partway through with a size complaint, and nothing in the app would
                // ever start them again. The cap no longer stops a running transfer, so
                // they go back in the queue and carry on from their partial file.
                withLocalUri.pauseReason == DownloadPauseReason.SizeApproval -> withLocalUri.copy(
                    status = DownloadStatus.Queued,
                    pauseReason = null,
                    sizeApprovalRequired = false,
                    activity = DownloadActivity.QUEUED_FOR_SLOT,
                    sizeCapOverrideApproved = true,
                    exceedsSizeCap = true,
                    errorMessage = null,
                    attemptCount = 0,
                    nextRetryAtEpochMs = null,
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
                    DownloadDiagnostics.note(
                        "load_placeholder_requeued",
                        "id=${withLocalUri.id} bytes=${withLocalUri.totalBytes ?: withLocalUri.downloadedBytes}",
                    )
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
                        activity = DownloadActivity.QUEUED_FOR_SLOT,
                        localFileUri = null,
                        downloadedBytes = 0L,
                        totalBytes = null,
                        attemptCount = 0,
                        nextRetryAtEpochMs = null,
                        updatedAtEpochMs = now,
                    )
                }
                else -> withLocalUri.withInferredActivity(now)
            }
        }

        // Payloads written before ranks existed carry the default position for every
        // item, so they are renumbered from their stored order on first load.
        val normalized = DownloadQueuePlanner.normalized(restored)
        all.value = normalized
        // Payloads written before entries were reconciled can hold batches whose
        // downloads were deleted, which is what made deleted episodes keep showing a
        // download state on the series page. Healing here means an existing install
        // recovers on the next launch rather than on the next queue change.
        val reconciledBatches = reconcileBatches(batches.value, normalized)
        batches.value = reconciledBatches
        notifyLiveStatusPlatform()
        DownloadDiagnostics.note(
            "store_loaded",
            "items=${normalized.size} owners=${normalized.groupingBy { it.ownerProfileId }.eachCount()} " +
                "active=${activeOwner()} migratedOwners=$migratedOwners",
        )
        if (
            payload.isEmpty() ||
            migratedOwners > 0 ||
            normalized != stored.items ||
            reconciledBatches != stored.batches ||
            presets.value != stored.presets
        ) {
            persistLocked(immediate = true)
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

    private fun DownloadItem.withInferredActivity(nowEpochMs: Long): DownloadItem {
        if (activity != null) return this
        val inferred = when (status) {
            DownloadStatus.Queued -> if (isWaitingForRetry(nowEpochMs)) {
                DownloadActivity.RETRY_BACKOFF
            } else {
                DownloadActivity.QUEUED_FOR_SLOT
            }
            DownloadStatus.Downloading -> DownloadActivity.TRANSFERRING
            DownloadStatus.Paused -> when (pauseReason) {
                DownloadPauseReason.User -> DownloadActivity.USER_PAUSED
                DownloadPauseReason.SizeApproval -> DownloadActivity.SIZE_APPROVAL
                else -> DownloadActivity.SYSTEM_PAUSED
            }
            DownloadStatus.Completed, DownloadStatus.Failed -> null
        }
        return copy(activity = inferred)
    }
}

/**
 * Moving to one device-wide store (Phase 9, stage 4). Pure, so every path is tested on its own.
 */
internal object DownloadStoreMigration {
    /** Profile indices start at 1; the first profile is the account's own. */
    const val PRIMARY_PROFILE_ID = 1

    /** Gives anything with no owner to [owner]. Idempotent. */
    fun assignOwners(
        payload: StoredDownloadsPayload,
        owner: Int = PRIMARY_PROFILE_ID,
    ): StoredDownloadsPayload {
        if (payload.items.none { it.ownerProfileId == null } &&
            payload.batches.none { it.ownerProfileId == null }
        ) {
            return payload
        }
        return payload.copy(
            items = payload.items.map { if (it.ownerProfileId == null) it.copy(ownerProfileId = owner) else it },
            batches = payload.batches.map { if (it.ownerProfileId == null) it.copy(ownerProfileId = owner) else it },
        )
    }

    /**
     * Desktop kept one payload per profile. They become one, each item and batch tagged with the
     * profile it came from, queued in the order each profile had them. The device-level parts -
     * the addon filter, presets and device settings - come from the primary profile when it has a
     * payload, else the lowest profile that does. An id that collides across profiles is
     * suffixed rather than dropped.
     */
    fun mergeProfilePayloads(byProfile: Map<Int, StoredDownloadsPayload>): StoredDownloadsPayload {
        if (byProfile.isEmpty()) return StoredDownloadsPayload()
        val source = byProfile[PRIMARY_PROFILE_ID] ?: byProfile.getValue(byProfile.keys.min())
        val seenIds = mutableSetOf<String>()
        val items = mutableListOf<DownloadItem>()
        val batches = mutableListOf<DownloadBatch>()
        var nextPosition = 0L
        for (profile in byProfile.keys.sorted()) {
            val payload = byProfile.getValue(profile)
            val ordered = payload.items.sortedWith(downloadQueueComparator)
            for (item in ordered) {
                val id = if (seenIds.add(item.id)) item.id else "${item.id}_p$profile".also { seenIds.add(it) }
                items += item.copy(
                    id = id,
                    ownerProfileId = profile,
                    queuePosition = if (item.status == DownloadStatus.Completed) item.queuePosition else nextPosition++,
                )
            }
            batches += payload.batches.map { it.copy(ownerProfileId = profile) }
        }
        return StoredDownloadsPayload(
            items = items,
            sourcePolicy = source.sourcePolicy,
            batches = batches,
            presets = source.presets,
            deviceSettings = source.deviceSettings,
        )
    }
}

@Serializable
internal data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    val sourcePolicy: DownloadSourcePolicy = DownloadSourcePolicy(),
    val batches: List<DownloadBatch> = emptyList(),
    val presets: List<DownloadPreset> = DownloadPreset.BuiltIns,
    val deviceSettings: DownloadDeviceSettings = DownloadDeviceSettings(),
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
            DownloadDiagnostics.note("store_corrupt", "chars=${payload.length} error=${it::class.simpleName}")
            runCatching { DownloadsStorage.saveCorruptPayload(payload) }
            StoredDownloadsPayload()
        }

    fun encode(
        items: Collection<DownloadItem>,
        sourcePolicy: DownloadSourcePolicy,
        batches: Collection<DownloadBatch>,
        presets: Collection<DownloadPreset>,
        deviceSettings: DownloadDeviceSettings = DownloadDeviceSettings(),
    ): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
                sourcePolicy = sourcePolicy,
                batches = batches.toList(),
                presets = presets.toList(),
                deviceSettings = deviceSettings,
            ),
        )
}
