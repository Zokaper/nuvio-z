package com.nuvio.app.features.downloads

/** A reorder request from the downloads screen. */
internal enum class QueueMove {
    ToTop,
    Up,
    Down,
    ToBottom,
}

/**
 * The queue's ordering rules, kept free of platform singletons so they can be tested.
 *
 * [DownloadsRepository] owns the state and the side effects; everything here is a
 * pure function over a list of items, mirroring how [DownloadBatchPlanner] and
 * [PresetSourceSelector] are already factored.
 */
internal object DownloadQueuePlanner {

    /**
     * Chooses which queued items should start now.
     *
     * Ranked order decides who goes first, transfers already running consume slots,
     * and anything still serving a retry backoff is skipped rather than starting and
     * failing again immediately.
     */
    fun startable(
        items: List<DownloadItem>,
        activeIds: Set<String>,
        maxConcurrent: Int,
        nowEpochMs: Long,
    ): List<DownloadItem> {
        val freeSlots = maxConcurrent - activeIds.size
        if (freeSlots <= 0) return emptyList()
        return items
            .asSequence()
            .filter { it.id !in activeIds }
            .filter { it.isStartable(nowEpochMs) }
            .sortedWith(downloadQueueComparator)
            .take(freeSlots)
            .toList()
    }

    /** The rank to give a newly enqueued item so it lands at the back of the queue. */
    fun nextQueuePosition(items: List<DownloadItem>): Long =
        (items.maxOfOrNull { it.queuePosition } ?: -1L) + 1L

    /**
     * Applies a reorder and renumbers the queue.
     *
     * Positions are rewritten densely from zero over the unfinished items after every
     * move, so ranks never drift apart or collide no matter how often the list is
     * shuffled. Completed items are left untouched - they are not in the queue.
     */
    fun reordered(
        items: List<DownloadItem>,
        downloadId: String,
        move: QueueMove,
    ): List<DownloadItem> {
        val pending = items
            .filter { it.status != DownloadStatus.Completed }
            .sortedWith(downloadQueueComparator)
        val currentIndex = pending.indexOfFirst { it.id == downloadId }
        if (currentIndex == -1) return items

        val targetIndex = when (move) {
            QueueMove.ToTop -> 0
            QueueMove.Up -> currentIndex - 1
            QueueMove.Down -> currentIndex + 1
            QueueMove.ToBottom -> pending.lastIndex
        }.coerceIn(0, pending.lastIndex)
        if (targetIndex == currentIndex) return items

        val rearranged = pending.toMutableList()
        rearranged.add(targetIndex, rearranged.removeAt(currentIndex))
        return applyPositions(items, rearranged)
    }

    /**
     * Renumbers ranks without changing the order.
     *
     * Used after loading a payload written before ranks existed, where every item
     * carries the default position of zero and only insertion order distinguishes
     * them.
     */
    fun normalized(items: List<DownloadItem>): List<DownloadItem> {
        val pending = items
            .filter { it.status != DownloadStatus.Completed }
            .sortedWith(downloadQueueComparator)
        return applyPositions(items, pending)
    }

    /**
     * Picks the running transfer to displace so a promoted item can start at once.
     *
     * Chosen only when every slot is busy, and always the lowest priority transfer -
     * the one furthest down the queue - so promoting an item costs the least
     * progress. Its partial file survives, so the displaced transfer resumes from
     * where it stopped rather than starting over.
     */
    fun preemptionCandidate(
        items: List<DownloadItem>,
        promotedId: String,
        activeIds: Set<String>,
        maxConcurrent: Int,
    ): DownloadItem? {
        if (activeIds.size < maxConcurrent) return null
        if (promotedId in activeIds) return null
        val promoted = items.firstOrNull { it.id == promotedId } ?: return null
        return items
            .filter { it.id in activeIds }
            .sortedWith(downloadQueueComparator)
            .lastOrNull()
            ?.takeIf { it.queuePosition > promoted.queuePosition }
    }

    private fun applyPositions(
        items: List<DownloadItem>,
        orderedPending: List<DownloadItem>,
    ): List<DownloadItem> {
        val positions = orderedPending
            .mapIndexed { index, item -> item.id to index.toLong() }
            .toMap()
        return items.map { item ->
            val position = positions[item.id] ?: return@map item
            if (item.queuePosition == position) item else item.copy(queuePosition = position)
        }
    }
}
