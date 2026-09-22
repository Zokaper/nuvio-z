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
}
