package com.nuvio.app.features.downloads

/**
 * Progress toward finishing a whole selection - every episode of a season the user asked for -
 * rather than toward finishing whatever happens to be transferring right now.
 *
 * `.52` measured a season as `bytes / sizes` over its *unfinished* episodes whose transfer had
 * opened. Two 2 GB episodes half done out of six read "2 GB of 4 GB · 50%"; the next episode
 * opening grew the denominator, one finishing dropped out of both sides, and the bar walked
 * backwards and forwards on a season that was only ever moving forwards. The Android notification
 * and the iOS Live Activity showed the lead episode's own percent under a season's title, which
 * was the same mistake from the other side.
 *
 * Here the denominator is the selection itself and changes only when the selection does:
 *
 * - **Bytes** when every member's size is known: downloaded across completed and in-progress
 *   episodes over the expected size of all of them. A member's estimate (the size its source
 *   advertised) gives way to the server's own total once a transfer opens - a correction of a
 *   few megabytes, not a new denominator.
 * - **Episodes** when any size is unknown: each counts as one, a finished one fully and a running
 *   one by its own fraction where that is known. Honest about not knowing the total, and still
 *   anchored to the whole selection.
 *
 * One model for the Downloads screen, the Android notification and the iOS Live Activity, so the
 * three cannot disagree about how far along a season is.
 */
data class DownloadAggregateProgress(
    val doneCount: Int,
    val memberCount: Int,
    val downloadedBytes: Long,
    /** Every member's size summed, or null while any of them is unknown. */
    val expectedBytes: Long?,
    /**
     * 0..1 on whichever basis above applies; null while nothing is measurable at all - no size
     * known and nothing finished - which is an indeterminate bar, not a confident 0%.
     */
    val fraction: Float?,
) {
    val percent: Int? get() = fraction?.let { (it * 100f).toInt().coerceIn(0, 100) }
    val remainingCount: Int get() = memberCount - doneCount
    val isByteAccurate: Boolean get() = expectedBytes != null
}

object DownloadAggregate {

    /** One member's contribution: bytes so far and its size, when known. */
    private data class Member(val done: Boolean, val downloaded: Long, val expected: Long?)

    /**
     * The progress of the selection [item] belongs to: its season's batch run for an episode,
     * itself for a film. Null for a lone item, which already shows its own progress.
     */
    fun forItem(
        item: DownloadItem,
        allItems: List<DownloadItem>,
        batches: List<DownloadBatch>,
    ): DownloadAggregateProgress? {
        if (!item.isEpisode) return null
        return forSeason(item.parentMetaId, item.seasonNumber, allItems, batches)
            ?.takeIf { it.memberCount > 1 }
    }

    /**
     * Who belongs to a season's selection.
     *
     * Its unfinished episodes, always - including ones that need the user, which are not done and
     * must not leave the denominator by failing. Its finished ones only if they were part of the
     * same run: named by a batch that also names an unfinished episode, or added by hand no
     * earlier than the oldest unfinished one. Episode 1 downloaded last month is not progress
     * toward the season started today. Batch entries still being prepared count too, at the size
     * their chosen source advertised, so the total does not grow as they turn into downloads.
     *
     * Null once nothing in the season is unfinished.
     */
    fun forSeason(
        parentMetaId: String,
        season: Int?,
        allItems: List<DownloadItem>,
        batches: List<DownloadBatch>,
    ): DownloadAggregateProgress? = seasonMembers(parentMetaId, season, allItems, batches)?.let(::aggregate)

    /**
     * Everything the queue is working through, for the one ongoing notification and the Live
     * Activity: every unfinished selection's members together - each season's run as above, each
     * film as itself. Grows when the user adds something and at no other time. Null when nothing
     * is unfinished.
     */
    fun forQueue(allItems: List<DownloadItem>, batches: List<DownloadBatch>): DownloadAggregateProgress? {
        val unfinished = allItems.filter { it.status != DownloadStatus.Completed }
        if (unfinished.isEmpty()) return null
        val seasons = unfinished.filter { it.isEpisode }.map { it.parentMetaId to it.seasonNumber }.distinct()
        val members = seasons.flatMap { (parent, season) -> seasonMembers(parent, season, allItems, batches).orEmpty() } +
            unfinished.filterNot { it.isEpisode }.map(::memberOf)
        return aggregate(members)
    }

    private fun seasonMembers(
        parentMetaId: String,
        season: Int?,
        allItems: List<DownloadItem>,
        batches: List<DownloadBatch>,
    ): List<Member>? {
        val siblings = allItems.filter { it.isEpisode && it.parentMetaId == parentMetaId && it.seasonNumber == season }
        val unfinished = siblings.filter { it.status != DownloadStatus.Completed }
        if (unfinished.isEmpty()) return null

        val unfinishedVideoIds = unfinished.mapTo(mutableSetOf()) { it.videoId }
        val runEntries = batches
            .filter { it.parentMetaId == parentMetaId }
            .flatMap { batch -> batch.entries.filter { it.season == season } }
            .filter { it.state != DownloadBatchEntryState.CANCELLED }
        val runBatches = batches.filter { batch ->
            batch.parentMetaId == parentMetaId && batch.entries.any { it.season == season && it.videoId in unfinishedVideoIds }
        }
        val batchVideoIds = runBatches
            .flatMap { it.entries }
            .filter { it.season == season && it.state != DownloadBatchEntryState.CANCELLED }
            .mapTo(mutableSetOf()) { it.videoId }
        val oldestUnfinished = unfinished.minOf { it.createdAtEpochMs }
        val finishedInRun = siblings.filter {
            it.status == DownloadStatus.Completed &&
                (it.videoId in batchVideoIds || it.createdAtEpochMs >= oldestUnfinished)
        }

        val itemVideoIds = siblings.mapTo(mutableSetOf()) { it.videoId }
        val preparing = runEntries.filter { entry ->
            entry.videoId in batchVideoIds &&
                entry.videoId !in itemVideoIds &&
                entry.state in PREPARING_STATES
        }

        return unfinished.map(::memberOf) + finishedInRun.map(::memberOf) + preparing.map { entry ->
            Member(done = false, downloaded = 0L, expected = (entry.selection as? SourceSelectionResult.Selected)?.facts?.sizeBytes?.takeIf { it > 0L })
        }
    }

    private val PREPARING_STATES = setOf(
        DownloadBatchEntryState.DISCOVERING,
        DownloadBatchEntryState.READY,
        DownloadBatchEntryState.RESOLVING,
        DownloadBatchEntryState.QUEUED,
    )

    private fun memberOf(item: DownloadItem): Member {
        val downloaded = item.downloadedBytes.coerceAtLeast(0L)
        if (item.status == DownloadStatus.Completed) {
            val size = item.totalBytes?.takeIf { it > 0L } ?: downloaded
            return Member(done = true, downloaded = size, expected = size)
        }
        // The server's total once a transfer has opened; the advertised size before that. Never
        // below what has already arrived, so an estimate a little short cannot run past 100%.
        val expected = (item.totalBytes?.takeIf { it > 0L } ?: item.expectedSizeBytes?.takeIf { it > 0L })
            ?.coerceAtLeast(downloaded)
        return Member(done = false, downloaded = downloaded, expected = expected)
    }

    private fun aggregate(members: List<Member>): DownloadAggregateProgress {
        val downloaded = members.sumOf { it.downloaded }
        val allSized = members.all { it.expected != null }
        val expected = if (allSized) members.sumOf { it.expected ?: 0L } else null
        val measurable = members.any { it.done || (it.expected != null && it.expected > 0L) }
        val fraction = if (!measurable) {
            null
        } else if (expected != null && expected > 0L) {
            downloaded.toDouble() / expected.toDouble()
        } else {
            members.sumOf { member ->
                when {
                    member.done -> 1.0
                    member.expected != null && member.expected > 0L -> member.downloaded.toDouble() / member.expected.toDouble()
                    else -> 0.0
                }
            } / members.size.coerceAtLeast(1)
        }
        return DownloadAggregateProgress(
            doneCount = members.count { it.done },
            memberCount = members.size,
            downloadedBytes = downloaded,
            expectedBytes = expected,
            fraction = fraction?.toFloat()?.coerceIn(0f, 1f),
        )
    }
}
