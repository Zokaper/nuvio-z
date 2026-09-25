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
    /**
     * How far everything unfinished is, as one - see [DownloadAggregateProgress]. The bar and the
     * percent; never the head's own figure, which read as the season's under a season's title.
     */
    val progress: DownloadAggregateProgress?,
    /** True when all of it is one title or season, so the notification can be titled after it. */
    val singleSelection: Boolean = false,
    /** Why nothing is moving, when nothing is transferring - the shared [DownloadPresenter] words. */
    val waitingReason: DownloadWaitReason?,
    /** Downloads waiting for the user (Phase 9: the four "needs you" kinds). */
    val needsYouCount: Int = 0,
    /** A batch still finding sources, when nothing is queued yet. */
    val preparingTitle: String?,
    val preparedEntries: Int,
    val preparingEntries: Int,
)


/** A title (film) or a season whose last unfinished download has just completed. */
data class CompletedDownloadGroup(
    val parentMetaId: String,
    val title: String,
    val seasonNumber: Int?,
)

object DownloadsSummaryPolicy {
    /** Null when there is nothing unfinished to report, which removes the notification. */
    fun summarize(
        items: List<DownloadItem>,
        batches: List<DownloadBatch>,
        nowEpochMs: Long = DownloadsClock.nowEpochMs(),
    ): DownloadsSummary? {
        val ordered = items.sortedWith(downloadQueueComparator)
        val downloading = ordered.filter { it.status == DownloadStatus.Downloading }
        val waiting = ordered.filter { it.status == DownloadStatus.Queued }
        val preparing = batches.filter { it.isPreparing }
        if (downloading.isEmpty() && waiting.isEmpty() && preparing.isEmpty()) return null

        val head = downloading.firstOrNull { it.activity == DownloadActivity.TRANSFERRING }
            ?: downloading.firstOrNull()
        val progress = DownloadAggregate.forQueue(items, batches)
        val selections = (downloading + waiting).map { DownloadQueueGrouping.keyOf(it) }.toSet()
        // The reason comes from the same presentation the Downloads screen and the Live
        // Activity read, so the notification cannot word a wait differently from the row.
        val reasons = waiting.mapNotNull { DownloadPresenter.item(it, nowEpochMs).waitReason }.toSet()
        val waitingReason = when {
            downloading.isNotEmpty() -> null
            DownloadWaitReason.CONNECTION in reasons -> DownloadWaitReason.CONNECTION
            DownloadWaitReason.WIFI in reasons -> DownloadWaitReason.WIFI
            DownloadWaitReason.RETRYING_SHORTLY in reasons -> DownloadWaitReason.RETRYING_SHORTLY
            DownloadWaitReason.RESUMING in reasons -> DownloadWaitReason.RESUMING
            waiting.isNotEmpty() -> DownloadWaitReason.STARTING
            else -> null
        }
        val needsYou = items.count { DownloadPresenter.item(it, nowEpochMs).phase == DownloadUserPhase.NEEDS_YOU }
        val single = preparing.singleOrNull()
        return DownloadsSummary(
            downloadingCount = downloading.size,
            waitingCount = waiting.size,
            head = head,
            progress = progress,
            singleSelection = selections.size == 1,
            waitingReason = waitingReason,
            needsYouCount = needsYou,
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
