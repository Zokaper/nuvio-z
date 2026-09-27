package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The downloaded titles' metadata snapshots ([DownloadTitleMetadata]), device-wide and never
 * synced - they describe files on this device.
 *
 * Filled while online: from the meta already in memory when there is one (the title page the
 * download started from), otherwise by one quiet fetch per title when the library shows it. A
 * snapshot is refreshed after [RefreshAfterMs]; a title that is no longer downloaded is dropped
 * the next time the library is pruned. Offline, the library reads what is here and nothing else.
 */
object DownloadTitleMetadataStore {
    private const val RefreshAfterMs = 7L * 24 * 60 * 60 * 1000

    private val lock = SynchronizedObject()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val _titles = MutableStateFlow<Map<String, DownloadTitleMetadata>>(emptyMap())
    val titles: StateFlow<Map<String, DownloadTitleMetadata>> = _titles.asStateFlow()

    private var loaded = false

    /** Titles already fetched (or tried) this session, so a failing addon is not asked on every visit. */
    private val attempted = mutableSetOf<String>()

    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val payload = runCatching { DownloadsStorage.loadTitleMetadata() }.getOrNull().orEmpty().trim()
            if (payload.isEmpty()) return
            _titles.value = runCatching { json.decodeFromString<StoredTitleMetadata>(payload) }
                .getOrNull()
                ?.titles
                ?.associateBy { it.parentMetaId }
                .orEmpty()
        }
    }

    /** Stores [meta] for a downloaded title, keeping only the seasons that have downloads. */
    fun remember(meta: MetaDetails, downloadedSeasons: Set<Int>?) {
        ensureLoaded()
        val snapshot = DownloadTitleMetadata.from(meta, downloadedSeasons, DownloadsClock.nowEpochMs())
        synchronized(lock) {
            _titles.value = _titles.value + (snapshot.parentMetaId to snapshot)
            persistLocked()
        }
    }

    /** Drops the snapshots of titles that have nothing on this device any more. */
    fun prune(downloadedTitleIds: Set<String>) {
        ensureLoaded()
        synchronized(lock) {
            val kept = _titles.value.filterKeys { it in downloadedTitleIds }
            if (kept.size == _titles.value.size) return
            _titles.value = kept
            persistLocked()
        }
    }

    /**
     * Makes sure each of [titles] has a current snapshot: the meta in memory when there is one,
     * otherwise one fetch per title, one after another. Online only; offline it does nothing.
     */
    suspend fun refresh(titles: List<DownloadLibraryTitle>) {
        ensureLoaded()
        for (title in titles) {
            val seasons = title.completed.mapNotNull { it.seasonNumber }.toSet().takeIf { title.isSeries }
            val existing = _titles.value[title.parentMetaId]
            val stale = existing == null ||
                DownloadsClock.nowEpochMs() - existing.capturedAtEpochMs > RefreshAfterMs ||
                (seasons != null && seasons.any { season -> existing.episodes.none { it.season == season } })
            if (!stale) continue
            MetaDetailsRepository.peek(title.parentMetaType, title.parentMetaId)?.let {
                remember(it, seasons)
                continue
            }
            if (NetworkStatusRepository.uiState.value.condition != NetworkCondition.Online) return
            val first = synchronized(lock) { attempted.add(title.parentMetaId) }
            if (!first) continue
            val meta = runCatching {
                MetaDetailsRepository.fetch(title.parentMetaType, title.parentMetaId, cacheResult = false)
            }.getOrNull() ?: continue
            remember(meta, seasons)
        }
    }

    private fun persistLocked() {
        runCatching {
            DownloadsStorage.saveTitleMetadata(json.encodeToString(StoredTitleMetadata(_titles.value.values.toList())))
        }
    }
}

@Serializable
private data class StoredTitleMetadata(val titles: List<DownloadTitleMetadata> = emptyList())
