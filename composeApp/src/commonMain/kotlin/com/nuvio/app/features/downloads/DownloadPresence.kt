package com.nuvio.app.features.downloads

/**
 * The single logical key for a downloadable piece of content, shared by persisted
 * downloads, batch planning, and the detail screens that show download state.
 */
fun downloadLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

/** What a content entry looks like to the rest of the app, whether or not a transfer exists yet. */
enum class DownloadPresence {
    None,
    Preparing,
    Queued,
    Downloading,
    Paused,
    NeedsApproval,
    Completed,
    Failed,
    ;

    val isActive: Boolean
        get() = this == Preparing || this == Queued || this == Downloading

    val needsAttention: Boolean
        get() = this == NeedsApproval || this == Failed

    /** Anything the user has committed to, so the entry should not offer a plain "download" action. */
    val isEngaged: Boolean
        get() = this != None
}

data class ContentDownloadState(
    val presence: DownloadPresence = DownloadPresence.None,
    val progressFraction: Float = 0f,
    val item: DownloadItem? = null,
    val batchId: String? = null,
    val entryId: String? = null,
) {
    val downloadedBytes: Long
        get() = item?.downloadedBytes ?: 0L

    val totalBytes: Long?
        get() = item?.totalBytes

    val isPlayable: Boolean
        get() = item?.isPlayable == true

    companion object {
        val None = ContentDownloadState()
    }
}

/**
 * Every download this app knows about for one movie or series, keyed by [downloadLogicalKey].
 */
data class TitleDownloadState(
    val parentMetaId: String = "",
    val byLogicalKey: Map<String, ContentDownloadState> = emptyMap(),
) {
    fun forMovie(): ContentDownloadState =
        byLogicalKey[downloadLogicalKey(parentMetaId, null, null)] ?: ContentDownloadState.None

    fun forEpisode(season: Int?, episode: Int?): ContentDownloadState {
        if (season == null || episode == null) return ContentDownloadState.None
        return byLogicalKey[downloadLogicalKey(parentMetaId, season, episode)] ?: ContentDownloadState.None
    }

    fun forSeason(season: Int): List<ContentDownloadState> =
        byLogicalKey
            .filterKeys { it.startsWith("${parentMetaId.trim()}|$season|") }
            .values
            .toList()

    val isEmpty: Boolean
        get() = byLogicalKey.isEmpty()

    val completedItems: List<DownloadItem>
        get() = byLogicalKey.values.mapNotNull { state ->
            state.item?.takeIf { state.presence == DownloadPresence.Completed }
        }

    val completedCount: Int
        get() = byLogicalKey.values.count { it.presence == DownloadPresence.Completed }

    val activeCount: Int
        get() = byLogicalKey.values.count { it.presence.isActive }

    val needsAttentionCount: Int
        get() = byLogicalKey.values.count { it.presence.needsAttention }

    val bytesOnDisk: Long
        get() = byLogicalKey.values.sumOf { it.downloadedBytes }
}

internal fun DownloadStatus.toPresence(sizeApprovalRequired: Boolean): DownloadPresence = when (this) {
    DownloadStatus.Downloading -> DownloadPresence.Downloading
    DownloadStatus.Paused -> if (sizeApprovalRequired) DownloadPresence.NeedsApproval else DownloadPresence.Paused
    DownloadStatus.Completed -> DownloadPresence.Completed
    DownloadStatus.Failed -> DownloadPresence.Failed
}

internal fun DownloadBatchEntryState.toPresence(): DownloadPresence = when (this) {
    DownloadBatchEntryState.DISCOVERING,
    DownloadBatchEntryState.RESOLVING,
    -> DownloadPresence.Preparing

    DownloadBatchEntryState.READY,
    DownloadBatchEntryState.QUEUED,
    -> DownloadPresence.Queued

    DownloadBatchEntryState.APPROVAL_NEEDED -> DownloadPresence.NeedsApproval
    DownloadBatchEntryState.DOWNLOADING -> DownloadPresence.Downloading
    DownloadBatchEntryState.PAUSED -> DownloadPresence.Paused
    DownloadBatchEntryState.FAILED -> DownloadPresence.Failed
    DownloadBatchEntryState.COMPLETED -> DownloadPresence.Completed

    DownloadBatchEntryState.SKIPPED,
    DownloadBatchEntryState.CANCELLED,
    -> DownloadPresence.None
}

/**
 * Merges persisted downloads with the batches still being planned so a detail screen can show
 * "preparing" the moment a batch is created, then follow the same entry into a real transfer.
 * A persisted [DownloadItem] always wins over a batch entry describing the same content.
 */
fun buildTitleDownloadState(
    items: List<DownloadItem>,
    batches: List<DownloadBatch>,
    parentMetaId: String,
): TitleDownloadState {
    val normalizedParentMetaId = parentMetaId.trim()
    if (normalizedParentMetaId.isEmpty()) return TitleDownloadState()

    val states = mutableMapOf<String, ContentDownloadState>()

    batches
        .filter { it.parentMetaId.trim() == normalizedParentMetaId }
        .forEach { batch ->
            batch.entries.forEach { entry ->
                val presence = entry.state.toPresence()
                if (presence == DownloadPresence.None) return@forEach
                val key = downloadLogicalKey(normalizedParentMetaId, entry.season, entry.episode)
                states[key] = ContentDownloadState(
                    presence = presence,
                    batchId = batch.id,
                    entryId = entry.id,
                )
            }
        }

    items
        .filter { it.parentMetaId.trim() == normalizedParentMetaId }
        .forEach { item ->
            states[item.logicalContentKey] = ContentDownloadState(
                presence = item.status.toPresence(item.sizeApprovalRequired),
                progressFraction = item.progressFraction,
                item = item,
            )
        }

    return TitleDownloadState(
        parentMetaId = normalizedParentMetaId,
        byLogicalKey = states,
    )
}
