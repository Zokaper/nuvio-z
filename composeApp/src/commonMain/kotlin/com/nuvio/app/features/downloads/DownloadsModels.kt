package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import nuvio.composeapp.generated.resources.downloads_enqueue_insufficient_storage
import org.jetbrains.compose.resources.getString

@Serializable
enum class DownloadStatus {
    /** Waiting for a transfer slot. Ordered by [DownloadItem.queuePosition]. */
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
}

/**
 * Why a download is paused, which decides whether it may resume on its own.
 *
 * Only [User] pauses are sticky. The system pauses transfers for reasons the user
 * never asked for - the app went to the background, the scheduler reclaimed the
 * job, a higher priority download preempted this one - and those must come back by
 * themselves, or the queue silently dies and stays dead.
 */
@Serializable
enum class DownloadPauseReason {
    User,
    System,
    SizeApproval,
}

@Serializable
data class DownloadItem(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val localFileUri: String? = null,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val calculatedCapBytes: Long? = null,
    /**
     * Size the source advertised when this was queued.
     *
     * Kept so a finished transfer can be checked against what was promised. Debrid
     * providers serve a small placeholder video while they queue the real file, and
     * that placeholder is a complete, valid download in every other respect.
     */
    val expectedSizeBytes: Long? = null,
    val allowMeteredNetwork: Boolean = false,
    val sizeApprovalRequired: Boolean = false,
    val sizeCapOverrideApproved: Boolean = false,
    val errorMessage: String? = null,
    /** Queue rank; lower runs sooner. Assigned on enqueue, rewritten by reordering. */
    val queuePosition: Long = 0L,
    val pauseReason: DownloadPauseReason? = null,
    /** `ETag` from the first response, sent back as `If-Range` when resuming. */
    val resumeEtag: String? = null,
    /** `Last-Modified` fallback validator for sources that send no `ETag`. */
    val resumeLastModified: String? = null,
    val attemptCount: Int = 0,
    /** When set, the item stays queued until this time to back off after a failure. */
    val nextRetryAtEpochMs: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank()

    val displaySubtitle: String
        get() = episodeTitle.orEmpty()

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = if (isEpisode) {
            "${parentMetaId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"
        } else {
            "${parentMetaId.trim()}|movie"
        }

    /** True while a failed attempt is waiting out its backoff before being retried. */
    fun isWaitingForRetry(nowEpochMs: Long): Boolean {
        val retryAt = nextRetryAtEpochMs ?: return false
        return retryAt > nowEpochMs
    }

    /** True when the queue may pick this item up right now. */
    fun isStartable(nowEpochMs: Long): Boolean =
        status == DownloadStatus.Queued && !isWaitingForRetry(nowEpochMs)

    /** True when the system stopped this item and is expected to restart it itself. */
    val isSystemPaused: Boolean
        get() = status == DownloadStatus.Paused && pauseReason == DownloadPauseReason.System
}

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
) {
    /** Everything not yet finished, in the order the queue will actually run it. */
    val activeItems: List<DownloadItem>
        get() = items
            .filter { it.status != DownloadStatus.Completed }
            .sortedWith(downloadQueueComparator)

    val completedItems: List<DownloadItem>
        get() = items.filter { it.status == DownloadStatus.Completed }

    /** Bytes actually written to this device, including partially transferred files. */
    val bytesOnDisk: Long
        get() = items.sumOf { it.downloadedBytes }
}

/**
 * Sorts the active list into run order.
 *
 * Rank alone decides the order - deliberately not the status. If transfers in
 * flight were bucketed to the top, "move up" would move a row that then snapped
 * back below them, and reordering would be unusable. Sorting purely by rank keeps
 * the list the user sees and the order the queue runs identical, so moving a row
 * up means it downloads sooner. A paused item holds its place in line rather than
 * being exiled to the bottom, which is also how it behaves.
 */
internal val downloadQueueComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.queuePosition }
        .thenBy { it.createdAtEpochMs }
        .thenBy { it.id }

enum class DownloadEnqueueResult {
    Started,
    Replaced,
    MissingUrl,
    UnsupportedFormat,
    InsufficientStorage;

    fun toastMessage(): String = runBlocking {
        when (this@DownloadEnqueueResult) {
            Started -> getString(Res.string.downloads_enqueue_started)
            Replaced -> getString(Res.string.downloads_enqueue_replaced)
            MissingUrl -> getString(Res.string.downloads_enqueue_missing_url)
            UnsupportedFormat -> getString(Res.string.downloads_enqueue_unsupported_format)
            InsufficientStorage -> getString(Res.string.downloads_enqueue_insufficient_storage)
        }
    }
}

internal fun List<DownloadItem>.sortedForSeriesDownloads(): List<DownloadItem> =
    sortedWith(downloadSeriesEpisodeComparator)

internal val downloadSeriesEpisodeComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeTitle?.trim().orEmpty().lowercase() }
        .thenBy { it.title.trim().lowercase() }
        .thenBy { it.id }
