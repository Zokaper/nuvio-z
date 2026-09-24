package com.nuvio.app.features.downloads

/** Pure policy for reconciling Nuvio's persisted queue with iOS background tasks. */
internal object IosBackgroundTransferReconciler {
    private const val SESSION_SUFFIX = ".downloads.background.v1"

    fun sessionIdentifier(bundleIdentifier: String?): String =
        bundleIdentifier?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "$it$SESSION_SUFFIX" }
            ?: "com.nuvio.app.z$SESSION_SUFFIX"

    enum class RepositoryState { DOWNLOADING, QUEUED, USER_PAUSED, FAILED, COMPLETED, MISSING }
    enum class NativeState { RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED, MISSING }
    enum class Action {
        ATTACH, CREATE, UPDATE_PROGRESS, COMPLETE, RETRY, KEEP_PAUSED, CANCEL_NATIVE,
        REMOVE_STALE_NATIVE, NONE,
    }

    data class Snapshot(
        val repositoryState: RepositoryState,
        val nativeState: NativeState,
        val repositoryBytes: Long = 0L,
        val nativeBytes: Long = 0L,
        val destinationExists: Boolean = false,
    )

    data class Decision(val action: Action, val reconciledBytes: Long = 0L)

    fun reconcile(snapshot: Snapshot): Decision {
        val bytes = maxOf(snapshot.repositoryBytes, snapshot.nativeBytes).coerceAtLeast(0L)
        return when {
            snapshot.repositoryState in setOf(RepositoryState.MISSING, RepositoryState.COMPLETED) &&
                snapshot.nativeState != NativeState.MISSING -> Decision(Action.REMOVE_STALE_NATIVE, bytes)
            snapshot.repositoryState == RepositoryState.USER_PAUSED &&
                snapshot.nativeState in setOf(NativeState.RUNNING, NativeState.SUSPENDED) ->
                Decision(Action.CANCEL_NATIVE, bytes)
            snapshot.repositoryState == RepositoryState.USER_PAUSED -> Decision(Action.KEEP_PAUSED, bytes)
            snapshot.nativeState == NativeState.COMPLETED && snapshot.destinationExists ->
                Decision(Action.COMPLETE, bytes)
            snapshot.nativeState in setOf(NativeState.RUNNING, NativeState.SUSPENDED) ->
                Decision(if (bytes > snapshot.repositoryBytes) Action.UPDATE_PROGRESS else Action.ATTACH, bytes)
            snapshot.nativeState in setOf(NativeState.FAILED, NativeState.CANCELLED) ->
                Decision(Action.RETRY, bytes)
            snapshot.nativeState == NativeState.MISSING &&
                snapshot.repositoryState in setOf(RepositoryState.DOWNLOADING, RepositoryState.QUEUED) ->
                Decision(Action.CREATE, snapshot.repositoryBytes.coerceAtLeast(0L))
            else -> Decision(Action.NONE, bytes)
        }
    }

    data class ReconciledResponse(
        val statusCode: Int,
        val startingBytes: Long,
        val totalBytes: Long?,
        val isSuccess: Boolean,
        val isPartialResume: Boolean,
        val isAlreadyComplete: Boolean,
        val shouldRestartFromZero: Boolean,
    )

    fun computeRangeHeader(resumeFromBytes: Long): String? =
        if (resumeFromBytes > 0L) "bytes=$resumeFromBytes-" else null

    fun reconcileResponse(
        statusCode: Int,
        attemptedRange: Boolean,
        resumeFromBytes: Long,
        contentRange: String?,
        contentLength: Long?,
        knownTotalBytes: Long?,
    ): ReconciledResponse {
        if (attemptedRange && statusCode == 416) {
            val reportedTotal = parseContentRangeTotal(contentRange) ?: knownTotalBytes
            val complete = reportedTotal != null && resumeFromBytes == reportedTotal
            return ReconciledResponse(
                statusCode, if (complete) resumeFromBytes else 0L, reportedTotal, complete,
                false, complete, !complete,
            )
        }
        if (statusCode !in 200..299) {
            return ReconciledResponse(statusCode, 0L, knownTotalBytes, false, false, false, false)
        }

        val validPartial = attemptedRange && statusCode == 206 && resumeFromBytes > 0L &&
            parseContentRangeStart(contentRange) == resumeFromBytes
        val malformedPartial = attemptedRange && statusCode == 206 && !validPartial
        val startingBytes = if (validPartial) resumeFromBytes else 0L
        val totalBytes = resolveTotalBytes(
            startingBytes, validPartial, contentRange, contentLength,
        ) ?: knownTotalBytes
        return ReconciledResponse(
            statusCode, startingBytes, totalBytes, !malformedPartial, validPartial, false,
            malformedPartial,
        )
    }

    fun parseContentRangeStart(headerValue: String?): Long? {
        val value = headerValue?.trim().orEmpty()
        if (!value.startsWith("bytes ", ignoreCase = true)) return null
        return value.substringAfter(' ').substringBefore('/').substringBefore('-')
            .trim().toLongOrNull()?.takeIf { it >= 0L }
    }

    /** `.42` partials have no durable native resume record, so they cannot be combined safely. */
    fun shouldRestartLegacyPartial(partialBytes: Long, nativeTaskExists: Boolean): Boolean =
        partialBytes > 0L && !nativeTaskExists

    // --- Background scheduling ------------------------------------------------------
    //
    // Who owns what, and why (Phase 8, `.45`):
    //
    // - The repository owns the queue: order, user intent, and every start made while
    //   the app is active. An item it records as downloading is a *claim*, never proof
    //   that anything is transferring.
    // - A live `NSURLSessionDownloadTask` is the only proof. Nothing persisted - not the
    //   repository's status and not any native record - may stand in for one, because
    //   tasks die with a force-quit and records do not.
    // - While the app is in the background the repository cannot resolve sources, so
    //   the native layer advances the queue itself when a slot frees, using only what
    //   the repository already persisted: queued items in order, with the source URLs
    //   they already hold. It never reorders, and it stops at the first item whose URL
    //   has gone stale rather than skipping past it.
    //
    // `.44` kept a second, persisted copy of the queue with its own RUNNING state and
    // pushed it on every repository publish. Items the repository had merely claimed
    // were RUNNING with no task behind them, so the native scheduler saw two free slots
    // and started #3 and #4 while #1 and #2 were still resolving; a force-quit left
    // RUNNING entries that nothing ever scheduled again.

    /** One queued download, as the repository hands it to the background scheduler. */
    data class IosPreparedTransfer(
        val downloadId: String,
        val destinationFileName: String,
        val sourceUrl: String,
        val sourceHeaders: Map<String, String> = emptyMap(),
        val knownTotalBytes: Long? = null,
        val queuePosition: Long = 0L,
        val sourceUrlResolvedAtEpochMs: Long? = null,
        val allowMeteredNetwork: Boolean = false,
        /** Set while the item is waiting out a retry backoff. */
        val notBeforeEpochMs: Long? = null,
    ) {
        fun isSourceUrlStale(nowEpochMs: Long, freshnessMs: Long = SOURCE_URL_FRESHNESS_MS): Boolean {
            val resolvedAt = sourceUrlResolvedAtEpochMs ?: return true
            return (nowEpochMs - resolvedAt) !in 0..freshnessMs
        }
    }

    data class SchedulePlan(
        /** Items to start now, in queue order. */
        val toStart: List<IosPreparedTransfer>,
        /** The subset of [toStart] that already has a suspended task to resume. */
        val toResume: Set<String>,
        /** The first item that cannot start without a foreground source refresh. */
        val refreshBoundary: IosPreparedTransfer?,
    )

    /**
     * What to start when a background slot frees.
     *
     * A slot is taken by a running task **or** by a repository claim: an item the
     * repository is still resolving has no task yet, and handing its slot to the next
     * item is exactly the `.44` fault. Suspended tasks hold no slot. A suspended task
     * for the item being started is resumed rather than duplicated, and needs no URL.
     */
    fun scheduleNextTransfers(
        maxConcurrent: Int,
        runningIds: Set<String>,
        claimedIds: Set<String>,
        suspendedIds: Set<String>,
        finishedIds: Set<String>,
        preparedQueue: List<IosPreparedTransfer>,
        nowEpochMs: Long,
        freshnessMs: Long = SOURCE_URL_FRESHNESS_MS,
    ): SchedulePlan {
        val occupied = runningIds + claimedIds
        var slots = maxConcurrent - occupied.size
        val toStart = mutableListOf<IosPreparedTransfer>()
        val toResume = mutableSetOf<String>()
        var boundary: IosPreparedTransfer? = null
        val candidates = preparedQueue
            .asSequence()
            .filter { it.downloadId !in occupied && it.downloadId !in finishedIds }
            .distinctBy { it.downloadId }
            .sortedWith(compareBy<IosPreparedTransfer> { it.queuePosition }.thenBy { it.downloadId })
        for (candidate in candidates) {
            if (slots <= 0) break
            val notBefore = candidate.notBeforeEpochMs
            if (notBefore != null && notBefore > nowEpochMs) continue
            if (candidate.downloadId in suspendedIds) {
                toStart += candidate
                toResume += candidate.downloadId
                slots -= 1
                continue
            }
            if (candidate.sourceUrl.isBlank() || candidate.isSourceUrlStale(nowEpochMs, freshnessMs)) {
                boundary = candidate
                break
            }
            toStart += candidate
            slots -= 1
        }
        return SchedulePlan(toStart, toResume, boundary)
    }

    /**
     * Which resolved downloads may be handed to the session now, in the order to hand them.
     *
     * The window's sources resolve in parallel and answer in any order. A resolved item
     * waits while anything ahead of it in the queue is still resolving, so tasks are
     * created in queue order; an item that fails to resolve simply stops holding its
     * place. An id with no known position sorts last.
     */
    fun releaseInQueueOrder(
        parkedIds: Set<String>,
        resolvingIds: Set<String>,
        positions: Map<String, Long>,
    ): List<String> {
        val release = mutableListOf<String>()
        for (id in (parkedIds + resolvingIds).sortedWith(
            compareBy<String> { positions[it] ?: Long.MAX_VALUE }.thenBy { it },
        )) {
            if (id in resolvingIds) break
            release += id
        }
        return release
    }

    /**
     * The size a finished background download should have.
     *
     * A 206 answers a range request - the session resumes that way after a dropped
     * connection - so its Content-Length is only the last range. The whole file's size is
     * the total in Content-Range, or the size known before the transfer began.
     */
    fun finishedTransferTotal(
        statusCode: Int,
        contentLength: Long?,
        contentRange: String?,
        knownTotalBytes: Long?,
    ): Long? = if (statusCode == 206) {
        parseContentRangeTotal(contentRange) ?: knownTotalBytes
    } else {
        contentLength ?: knownTotalBytes
    }

    /** How long a running task may sit with every byte received before it counts as stuck. */
    const val STALLED_AT_END_GRACE_MS = 2L * 60L * 1000L

    /**
     * A task that has received every byte it expects but has not finished.
     *
     * The response never ended - a connection or stream left open after the last byte -
     * and a background task can sit like that until its 24-hour resource timeout. The
     * queue's own silence watchdog is off on iOS, so this is the only thing that notices.
     * [fullSinceEpochMs] is when the task was first seen complete, in this process.
     */
    fun isStalledAtEnd(
        running: Boolean,
        receivedBytes: Long,
        expectedBytes: Long,
        fullSinceEpochMs: Long?,
        nowEpochMs: Long,
        graceMs: Long = STALLED_AT_END_GRACE_MS,
    ): Boolean = running && expectedBytes > 0L && receivedBytes >= expectedBytes &&
        fullSinceEpochMs != null && nowEpochMs - fullSinceEpochMs >= graceMs

    // --- Adoption: reconciling the repository with the tasks that really exist -----

    /** A task the session reports, as seen at inventory time. */
    data class LiveTransfer(
        val downloadId: String,
        val running: Boolean,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
    )

    enum class AdoptionState { DOWNLOADING, QUEUED, SYSTEM_PAUSED, USER_PAUSED, FAILED, COMPLETED }

    data class AdoptionItem(
        val id: String,
        val state: AdoptionState,
        val queuePosition: Long,
        /** The repository already holds a transfer for this id. */
        val claimed: Boolean,
        /**
         * The claim is for a transfer already handed to the session - not one still
         * resolving its source or waiting its turn to be submitted. Such a claim is only
         * as real as the task behind it.
         */
        val transferring: Boolean = false,
    )

    data class AdoptionPlan(
        /** Running tasks to take over as they are, in queue order. */
        val adopt: List<String>,
        /** Running tasks beyond capacity, or for a download the user paused. */
        val suspend: List<String>,
        /** Tasks for downloads that no longer want them. */
        val cancel: List<String>,
        /** Downloads recorded as downloading with no running task behind them. */
        val requeue: List<String>,
        /**
         * Claims whose task no longer exists at all. The repository still holds them, so
         * they are not in [requeue], but nothing will ever report for them again.
         */
        val releaseLost: List<String> = emptyList(),
    )

    /**
     * Decides what the repository does with the tasks the session actually holds.
     *
     * Real tasks are adopted where they stand, so a relaunch never reorders the queue by
     * starting #1 and #2 on top of a #3 and #4 that are already running; the ones beyond
     * capacity are suspended, not cancelled, so their bytes survive. A download recorded
     * as downloading with no running task is a stale claim - a force-quit, or `.44`'s
     * persisted RUNNING - and goes back to the queue at the same position. It is not
     * charged an attempt: nothing failed.
     */
    fun planAdoption(
        items: List<AdoptionItem>,
        live: List<LiveTransfer>,
        maxConcurrent: Int,
    ): AdoptionPlan {
        val itemsById = items.associateBy { it.id }
        val liveById = live.associateBy { it.downloadId }
        val adopt = mutableListOf<String>()
        val suspend = mutableListOf<String>()
        val cancel = mutableListOf<String>()

        live.forEach { transfer ->
            when (itemsById[transfer.downloadId]?.state) {
                null, AdoptionState.COMPLETED, AdoptionState.FAILED -> cancel += transfer.downloadId
                AdoptionState.USER_PAUSED -> if (transfer.running) suspend += transfer.downloadId
                else -> Unit
            }
        }

        // A claim for a transfer the session was given, with no task of any kind behind it
        // any more. `.46` treated every claim without a running task as "still resolving"
        // and let it hold its slot forever; with the silence watchdog off on iOS, a
        // completion that never reached the repository left the row at 100% for good.
        val releaseLost = items
            .filter {
                it.claimed && it.transferring && it.state == AdoptionState.DOWNLOADING &&
                    it.id !in liveById
            }
            .map { it.id }
        val lost = releaseLost.toSet()

        val eligibleStates = setOf(AdoptionState.DOWNLOADING, AdoptionState.QUEUED, AdoptionState.SYSTEM_PAUSED)
        // Claims with no running task (still resolving) keep their slot.
        val heldSlots = items.count { it.claimed && liveById[it.id]?.running != true && it.id !in lost }
        var capacity = maxConcurrent - heldSlots
        items
            .filter { it.state in eligibleStates && liveById[it.id]?.running == true }
            .sortedWith(compareBy<AdoptionItem> { it.queuePosition }.thenBy { it.id })
            .forEach { item ->
                when {
                    item.claimed -> capacity -= 1
                    capacity > 0 -> {
                        adopt += item.id
                        capacity -= 1
                    }
                    else -> suspend += item.id
                }
            }

        val requeue = items
            .filter {
                it.state == AdoptionState.DOWNLOADING && !it.claimed && liveById[it.id]?.running != true
            }
            .map { it.id }
        return AdoptionPlan(adopt, suspend, cancel, requeue, releaseLost)
    }
}
