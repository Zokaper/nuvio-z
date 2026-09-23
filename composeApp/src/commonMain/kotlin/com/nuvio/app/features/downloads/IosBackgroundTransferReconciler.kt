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

    // --- Native iOS background transfer descriptor & state models -----------------

    enum class IosPreparedState {
        PREPARED,
        RUNNING,
        COMPLETED,
        FAILED,
        NEEDS_SOURCE_REFRESH,
        CANCELLED,
    }

    data class IosPreparedTransfer(
        val downloadId: String,
        val destinationFileName: String,
        val sourceUrl: String,
        val sourceHeaders: Map<String, String> = emptyMap(),
        val knownTotalBytes: Long? = null,
        val queuePosition: Long = 0L,
        val title: String = "",
        val subtitle: String = "",
        val sourceUrlResolvedAtEpochMs: Long? = null,
        val allowMeteredNetwork: Boolean = false,
        val state: IosPreparedState = IosPreparedState.PREPARED,
    ) {
        fun isSourceUrlStale(nowEpochMs: Long, freshnessMs: Long = SOURCE_URL_FRESHNESS_MS): Boolean {
            val resolvedAt = sourceUrlResolvedAtEpochMs ?: return true
            return (nowEpochMs - resolvedAt) !in 0..freshnessMs
        }
    }

    // --- Durable completion & event journal models --------------------------------

    sealed interface IosJournalEvent {
        val eventId: String
        val downloadId: String
        val epochMs: Long

        data class Progress(
            override val eventId: String,
            override val downloadId: String,
            val bytesWritten: Long,
            val totalBytes: Long?,
            override val epochMs: Long,
        ) : IosJournalEvent

        data class Completed(
            override val eventId: String,
            override val downloadId: String,
            val localFileUri: String,
            val totalBytes: Long,
            override val epochMs: Long,
        ) : IosJournalEvent

        data class Failed(
            override val eventId: String,
            override val downloadId: String,
            val reason: DownloadFailureReason,
            val message: String,
            val downloadedBytes: Long,
            override val epochMs: Long,
        ) : IosJournalEvent

        data class NeedsSourceRefresh(
            override val eventId: String,
            override val downloadId: String,
            val message: String,
            override val epochMs: Long,
        ) : IosJournalEvent

        data class TaskStarted(
            override val eventId: String,
            override val downloadId: String,
            val taskIdentifier: Long,
            override val epochMs: Long,
        ) : IosJournalEvent

        data class TaskCancelled(
            override val eventId: String,
            override val downloadId: String,
            override val epochMs: Long,
        ) : IosJournalEvent
    }

    // --- Native background queue scheduler policy ---------------------------------

    data class SchedulePlan(
        val tasksToStart: List<IosPreparedTransfer>,
        val tasksNeedingRefresh: List<IosPreparedTransfer>,
        val runningCount: Int,
        val remainingCount: Int,
    )

    fun scheduleNextTransfers(
        maxConcurrent: Int,
        activeDownloadIds: Set<String>,
        preparedQueue: List<IosPreparedTransfer>,
        nowEpochMs: Long,
        freshnessMs: Long = SOURCE_URL_FRESHNESS_MS,
        strictFifo: Boolean = true,
    ): SchedulePlan {
        val availableSlots = (maxConcurrent - activeDownloadIds.size).coerceAtLeast(0)
        val toStart = mutableListOf<IosPreparedTransfer>()
        val toRefresh = mutableListOf<IosPreparedTransfer>()

        val candidates = preparedQueue
            .filter { it.state == IosPreparedState.PREPARED && it.downloadId !in activeDownloadIds }
            .sortedWith(compareBy<IosPreparedTransfer> { it.queuePosition }.thenBy { it.downloadId })

        var slotsLeft = availableSlots
        for (candidate in candidates) {
            if (slotsLeft <= 0) break
            if (candidate.sourceUrl.isBlank() || candidate.isSourceUrlStale(nowEpochMs, freshnessMs)) {
                toRefresh.add(candidate.copy(state = IosPreparedState.NEEDS_SOURCE_REFRESH))
                if (strictFifo) {
                    // Strict FIFO ordering: stop queue progression at the unresolved boundary
                    break
                }
            } else {
                toStart.add(candidate.copy(state = IosPreparedState.RUNNING))
                slotsLeft -= 1
            }
        }

        val startedIds = toStart.map { it.downloadId }.toSet()
        val remainingCount = preparedQueue.count {
            it.state == IosPreparedState.PREPARED && it.downloadId !in activeDownloadIds && it.downloadId !in startedIds
        }
        return SchedulePlan(
            tasksToStart = toStart,
            tasksNeedingRefresh = toRefresh,
            runningCount = activeDownloadIds.size + toStart.size,
            remainingCount = remainingCount,
        )
    }

    // --- Journal reconciliation with repository state -----------------------------

    data class ReconciledItemState(
        val id: String,
        val state: RepositoryState,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val localFileUri: String? = null,
        val attemptCount: Int = 0,
        val nextRetryAtEpochMs: Long? = null,
        val isWaitingForProvider: Boolean = false,
        val isUserPaused: Boolean = false,
        val canReresolveSource: Boolean = false,
        val errorMessage: String? = null,
        val updatedAtEpochMs: Long = 0L,
    )

    data class JournalReconciliationResult(
        val updatedItems: List<ReconciledItemState>,
        val acknowledgedEventIds: Set<String>,
        val needsRefreshIds: Set<String>,
    )

    fun reconcileJournal(
        items: List<ReconciledItemState>,
        events: List<IosJournalEvent>,
    ): JournalReconciliationResult {
        if (events.isEmpty()) {
            return JournalReconciliationResult(items, emptySet(), emptySet())
        }

        val itemMap = items.associateBy { it.id }.toMutableMap()
        val acknowledged = mutableSetOf<String>()
        val needsRefresh = mutableSetOf<String>()

        val sortedEvents = events.sortedBy { it.epochMs }
        for (event in sortedEvents) {
            acknowledged.add(event.eventId)
            val current = itemMap[event.downloadId] ?: continue

            // Idempotency: completed is terminal against normal progress or retry
            if (current.state == RepositoryState.COMPLETED && event !is IosJournalEvent.Completed) {
                continue
            }
            // User paused state is sticky against background callbacks
            if (current.isUserPaused) {
                continue
            }

            when (event) {
                is IosJournalEvent.Progress -> {
                    if (current.state in setOf(RepositoryState.DOWNLOADING, RepositoryState.QUEUED)) {
                        itemMap[current.id] = current.copy(
                            state = RepositoryState.DOWNLOADING,
                            downloadedBytes = maxOf(current.downloadedBytes, event.bytesWritten),
                            totalBytes = event.totalBytes ?: current.totalBytes,
                            errorMessage = null,
                            updatedAtEpochMs = event.epochMs,
                        )
                    }
                }
                is IosJournalEvent.Completed -> {
                    itemMap[current.id] = current.copy(
                        state = RepositoryState.COMPLETED,
                        localFileUri = event.localFileUri,
                        downloadedBytes = event.totalBytes,
                        totalBytes = event.totalBytes,
                        attemptCount = 0,
                        nextRetryAtEpochMs = null,
                        isWaitingForProvider = false,
                        errorMessage = null,
                        updatedAtEpochMs = event.epochMs,
                    )
                }
                is IosJournalEvent.Failed -> {
                    if (current.state != RepositoryState.COMPLETED) {
                        val attempt = current.attemptCount + 1
                        val retry = shouldRetry(event.reason, attempt, current.canReresolveSource)
                        itemMap[current.id] = current.copy(
                            state = if (retry) RepositoryState.QUEUED else RepositoryState.FAILED,
                            downloadedBytes = event.downloadedBytes.coerceAtLeast(0L),
                            attemptCount = attempt,
                            nextRetryAtEpochMs = if (retry) event.epochMs + retryBackoffMs(attempt, event.reason) else null,
                            isWaitingForProvider = false,
                            errorMessage = event.message,
                            updatedAtEpochMs = event.epochMs,
                        )
                    }
                }
                is IosJournalEvent.NeedsSourceRefresh -> {
                    if (current.state != RepositoryState.COMPLETED) {
                        needsRefresh.add(current.id)
                        itemMap[current.id] = current.copy(
                            state = RepositoryState.QUEUED,
                            isWaitingForProvider = true,
                            errorMessage = event.message,
                            updatedAtEpochMs = event.epochMs,
                        )
                    }
                }
                is IosJournalEvent.TaskStarted -> {
                    if (current.state == RepositoryState.QUEUED) {
                        itemMap[current.id] = current.copy(
                            state = RepositoryState.DOWNLOADING,
                            updatedAtEpochMs = event.epochMs,
                        )
                    }
                }
                is IosJournalEvent.TaskCancelled -> Unit
            }
        }

        return JournalReconciliationResult(
            updatedItems = items.map { itemMap[it.id] ?: it },
            acknowledgedEventIds = acknowledged,
            needsRefreshIds = needsRefresh,
        )
    }

    // --- Pure string encoding / decoding without reflection -----------------------

    fun encodePreparedTransfer(t: IosPreparedTransfer): String = listOf(
        "nuvio-prep-v1",
        escape(t.downloadId),
        escape(t.destinationFileName),
        escape(t.sourceUrl),
        escape(t.knownTotalBytes?.toString().orEmpty()),
        escape(t.queuePosition.toString()),
        escape(t.title),
        escape(t.subtitle),
        escape(t.sourceUrlResolvedAtEpochMs?.toString().orEmpty()),
        escape(t.allowMeteredNetwork.toString()),
        escape(t.state.name),
        escape(encodeHeaders(t.sourceHeaders)),
    ).joinToString("|")

    fun decodePreparedTransfer(s: String?): IosPreparedTransfer? {
        val parts = s?.split('|') ?: return null
        if (parts.size != 12 || parts[0] != "nuvio-prep-v1") return null
        val state = runCatching { IosPreparedState.valueOf(unescape(parts[10])) }.getOrDefault(IosPreparedState.PREPARED)
        return IosPreparedTransfer(
            downloadId = unescape(parts[1]),
            destinationFileName = unescape(parts[2]),
            sourceUrl = unescape(parts[3]),
            knownTotalBytes = unescape(parts[4]).toLongOrNull(),
            queuePosition = unescape(parts[5]).toLongOrNull() ?: 0L,
            title = unescape(parts[6]),
            subtitle = unescape(parts[7]),
            sourceUrlResolvedAtEpochMs = unescape(parts[8]).toLongOrNull(),
            allowMeteredNetwork = unescape(parts[9]).toBoolean(),
            state = state,
            sourceHeaders = decodeHeaders(unescape(parts[11])),
        )
    }

    fun encodePreparedTransfers(transfers: List<IosPreparedTransfer>): String =
        transfers.joinToString("\n") { encodePreparedTransfer(it) }

    fun decodePreparedTransfers(payload: String?): List<IosPreparedTransfer> =
        payload?.lines()?.mapNotNull { decodePreparedTransfer(it) } ?: emptyList()

    fun encodeJournalEvent(e: IosJournalEvent): String = when (e) {
        is IosJournalEvent.Progress -> listOf("nuvio-je-v1", "PROGRESS", escape(e.eventId), escape(e.downloadId), e.bytesWritten.toString(), e.totalBytes?.toString().orEmpty(), "", e.epochMs.toString()).joinToString("|")
        is IosJournalEvent.Completed -> listOf("nuvio-je-v1", "COMPLETED", escape(e.eventId), escape(e.downloadId), e.totalBytes.toString(), e.totalBytes.toString(), escape(e.localFileUri), e.epochMs.toString()).joinToString("|")
        is IosJournalEvent.Failed -> listOf("nuvio-je-v1", "FAILED", escape(e.eventId), escape(e.downloadId), e.downloadedBytes.toString(), e.reason.name, escape(e.message), e.epochMs.toString()).joinToString("|")
        is IosJournalEvent.NeedsSourceRefresh -> listOf("nuvio-je-v1", "REFRESH", escape(e.eventId), escape(e.downloadId), "0", "", escape(e.message), e.epochMs.toString()).joinToString("|")
        is IosJournalEvent.TaskStarted -> listOf("nuvio-je-v1", "STARTED", escape(e.eventId), escape(e.downloadId), e.taskIdentifier.toString(), "", "", e.epochMs.toString()).joinToString("|")
        is IosJournalEvent.TaskCancelled -> listOf("nuvio-je-v1", "CANCELLED", escape(e.eventId), escape(e.downloadId), "0", "", "", e.epochMs.toString()).joinToString("|")
    }

    fun decodeJournalEvent(s: String?): IosJournalEvent? {
        val parts = s?.split('|') ?: return null
        if (parts.size != 8 || parts[0] != "nuvio-je-v1") return null
        val type = parts[1]
        val eventId = unescape(parts[2])
        val downloadId = unescape(parts[3])
        val bytes = parts[4].toLongOrNull() ?: 0L
        val epochMs = parts[7].toLongOrNull() ?: 0L

        return when (type) {
            "PROGRESS" -> IosJournalEvent.Progress(eventId, downloadId, bytes, parts[5].toLongOrNull(), epochMs)
            "COMPLETED" -> IosJournalEvent.Completed(eventId, downloadId, unescape(parts[6]), bytes, epochMs)
            "FAILED" -> {
                val reason = runCatching { DownloadFailureReason.valueOf(parts[5]) }.getOrDefault(DownloadFailureReason.Transient)
                IosJournalEvent.Failed(eventId, downloadId, reason, unescape(parts[6]), bytes, epochMs)
            }
            "REFRESH" -> IosJournalEvent.NeedsSourceRefresh(eventId, downloadId, unescape(parts[6]), epochMs)
            "STARTED" -> IosJournalEvent.TaskStarted(eventId, downloadId, bytes, epochMs)
            "CANCELLED" -> IosJournalEvent.TaskCancelled(eventId, downloadId, epochMs)
            else -> null
        }
    }

    fun encodeJournalEvents(events: List<IosJournalEvent>): String =
        events.joinToString("\n") { encodeJournalEvent(it) }

    fun decodeJournalEvents(payload: String?): List<IosJournalEvent> =
        payload?.lines()?.mapNotNull { decodeJournalEvent(it) } ?: emptyList()

    private fun escape(v: String): String = v
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace(";", "%3B")
        .replace("=", "%3D")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

    private fun unescape(v: String): String = v
        .replace("%0D", "\r")
        .replace("%0A", "\n")
        .replace("%3D", "=")
        .replace("%3B", ";")
        .replace("%7C", "|")
        .replace("%25", "%")

    private fun encodeHeaders(m: Map<String, String>): String =
        m.entries.joinToString(";") { "${escape(it.key)}=${escape(it.value)}" }

    private fun decodeHeaders(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        return s.split(';').mapNotNull { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) unescape(pair.substring(0, eqIdx)) to unescape(pair.substring(eqIdx + 1)) else null
        }.toMap()
    }
}
