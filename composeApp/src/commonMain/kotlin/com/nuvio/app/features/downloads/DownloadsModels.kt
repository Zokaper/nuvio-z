package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
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

/**
 * What a download needs in order to ask for its source URL again.
 *
 * Debrid links are signed, address-scoped and short-lived - the resolver treats a
 * cached one as good for fifteen minutes - while a queue that runs two transfers
 * at a time reaches for them hours after they were minted. Keeping the stream as
 * the addon originally served it, before any resolution, means an expired link is
 * a thing to re-mint rather than a dead download.
 *
 * Null for sources that were never resolved through a debrid provider: a plain
 * HTTP file from an addon has nothing to ask again.
 */
@Serializable
data class DownloadSourceOrigin(
    /** The stream as the addon served it, before debrid resolution. */
    val stream: StreamItem,
    val season: Int? = null,
    val episode: Int? = null,
)

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
    /** Everything needed to mint [sourceUrl] again once it expires. */
    val sourceOrigin: DownloadSourceOrigin? = null,
    /** When [sourceUrl] was minted, which is what decides whether it is still good. */
    val sourceUrlResolvedAtEpochMs: Long? = null,
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
    /**
     * The file turned out larger than the preset's cap.
     *
     * Recorded, not acted on. The cap's job is choosing a source; once bytes are on
     * disk, stopping the transfer over it throws away everything already fetched and
     * leaves a download that looks frozen partway through. This just lets the row
     * say so.
     */
    val exceedsSizeCap: Boolean = false,
    val errorMessage: String? = null,
    /** Queue rank; lower runs sooner. Assigned on enqueue, rewritten by reordering. */
    val queuePosition: Long = 0L,
    val pauseReason: DownloadPauseReason? = null,
    /** `ETag` from the first response, sent back as `If-Range` when resuming. */
    val resumeEtag: String? = null,
    /** `Last-Modified` fallback validator for sources that send no `ETag`. */
    val resumeLastModified: String? = null,
    val attemptCount: Int = 0,
    /**
     * Byte count when this run of bad luck started, or null when nothing is being retried.
     *
     * [attemptCount] used to be zeroed by *any* forward byte movement, on the reasoning that
     * bytes arriving means the source works. A source that trickles a few hundred KB and then
     * drops refreshes the budget every cycle, so `shouldRetry` never returns false and the row
     * cycles Downloading -> trickle -> drop -> Queued forever. That is the reported
     * "it says Retrying, it does retry, nothing happens".
     *
     * Nullable with a default so queues persisted before this field deserialize unchanged.
     */
    val retryCycleStartBytes: Long? = null,
    /**
     * Whether this download has already been run again from byte zero on a fresh link.
     *
     * A partial file the server will not correctly resume is the likeliest explanation for a
     * stall pinned near the end, and starting over is the only thing left to try. Recorded so
     * it happens at most once: a restart loop is the same fault wearing a different hat.
     */
    val restartedFromZero: Boolean = false,
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

    /** True when a fresh source URL can be minted for this download. */
    val canReresolveSource: Boolean
        get() = sourceOrigin != null

    /**
     * True when [sourceUrl] is old enough that it should be re-minted before use.
     *
     * A download with no origin is never stale: there is nothing to ask again, so
     * the URL it has is the only one it will ever have.
     */
    fun isSourceUrlStale(nowEpochMs: Long): Boolean {
        if (sourceOrigin == null) return false
        val resolvedAt = sourceUrlResolvedAtEpochMs ?: return true
        return nowEpochMs - resolvedAt !in 0..SOURCE_URL_FRESHNESS_MS
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
