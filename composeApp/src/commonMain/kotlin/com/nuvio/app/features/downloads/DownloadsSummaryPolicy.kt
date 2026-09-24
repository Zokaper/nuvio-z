package com.nuvio.app.features.downloads

/**
 * What the one ongoing downloads notification says, decided without any platform code.
 *
 * Decided at the Phase 9 opening: Android shows **one** summary notification while anything is
 * downloading, waiting or finding sources, plus a separate dismissible notification when a title
 * or season finishes. It used to post one notification per item *and* a second host notification,
 * which for a season was a shade full of near-identical rows.
 */
data class DownloadsSummary(
    val downloadingCount: Int,
    val waitingCount: Int,
    /** The item the notification leads with: the highest-ranked one that is transferring. */
    val head: DownloadItem?,
    /** 0..100 for the head, or null when its total is unknown (indeterminate bar). */
    val headProgressPercent: Int?,
    /** Why nothing is moving, when nothing is transferring. */
    val waitingReason: DownloadsWaitingReason?,
    /** A batch still finding sources, when nothing is queued yet. */
    val preparingTitle: String?,
    val preparedEntries: Int,
    val preparingEntries: Int,
)

enum class DownloadsWaitingReason { Connection, Retrying, Starting }

/** A title (film) or a season whose last unfinished download has just completed. */
data class CompletedDownloadGroup(
    val parentMetaId: String,
    val title: String,
    val seasonNumber: Int?,
)

object DownloadsSummaryPolicy {
    /** Null when there is nothing unfinished to report, which removes the notification. */
    fun summarize(items: List<DownloadItem>, batches: List<DownloadBatch>): DownloadsSummary? {
        val ordered = items.sortedWith(downloadQueueComparator)
        val downloading = ordered.filter { it.status == DownloadStatus.Downloading }
        val waiting = ordered.filter { it.status == DownloadStatus.Queued }
        val preparing = batches.filter { it.isPreparing }
        if (downloading.isEmpty() && waiting.isEmpty() && preparing.isEmpty()) return null

        val head = downloading.firstOrNull { it.activity == DownloadActivity.TRANSFERRING }
            ?: downloading.firstOrNull()
        val percent = head?.totalBytes?.takeIf { it > 0L }?.let { total ->
            ((head.downloadedBytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }
        val waitingReason = if (downloading.isNotEmpty()) {
            null
        } else if (waiting.any { it.activity == DownloadActivity.WAITING_FOR_CONNECTION }) {
            DownloadsWaitingReason.Connection
        } else if (waiting.any {
                it.activity == DownloadActivity.RETRY_BACKOFF || it.activity == DownloadActivity.WAITING_FOR_PROVIDER
            }
        ) {
            DownloadsWaitingReason.Retrying
        } else if (waiting.isNotEmpty()) {
            DownloadsWaitingReason.Starting
        } else {
            null
        }
        val single = preparing.singleOrNull()
        return DownloadsSummary(
            downloadingCount = downloading.size,
            waitingCount = waiting.size,
            head = head,
            headProgressPercent = percent,
            waitingReason = waitingReason,
            preparingTitle = if (preparing.isEmpty()) null else single?.title?.trim()?.ifBlank { null },
            preparedEntries = preparing.sumOf { it.preparedEntryCount },
            preparingEntries = preparing.sumOf { it.entries.size },
        )
    }

    /**
     * Groups that finished between two snapshots of the queue: a film that completed, or a season
     * whose last unfinished episode did. A season with an episode still queued, downloading,
     * paused or failed is not finished - it would announce itself too early.
     */
    fun newlyCompletedGroups(before: List<DownloadItem>, after: List<DownloadItem>): List<CompletedDownloadGroup> {
        val completedBefore = before.filter { it.status == DownloadStatus.Completed }.mapTo(mutableSetOf()) { it.id }
        val justCompleted = after.filter { it.status == DownloadStatus.Completed && it.id !in completedBefore }
        if (justCompleted.isEmpty()) return emptyList()
        return justCompleted
            .groupBy { it.parentMetaId to it.seasonNumber }
            .mapNotNull { (key, members) ->
                val (parentMetaId, season) = key
                val unfinishedSibling = after.any {
                    it.parentMetaId == parentMetaId &&
                        it.seasonNumber == season &&
                        it.status != DownloadStatus.Completed
                }
                if (unfinishedSibling) {
                    null
                } else {
                    CompletedDownloadGroup(parentMetaId, members.first().title, season)
                }
            }
    }
}
