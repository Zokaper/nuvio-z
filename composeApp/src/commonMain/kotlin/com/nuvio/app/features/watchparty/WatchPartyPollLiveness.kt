package com.nuvio.app.features.watchparty

/**
 * The poll heartbeat's own view of how long it has been since the server last heard from it.
 *
 * On 2026-09-18 a desktop host's heartbeats stopped landing for 71 seconds while the machine stayed
 * online, and the release build it ran recorded nothing - so it is still unknown whether the requests
 * hung, failed, or were never sent. The server only sees `last_seen_at` stop moving, and since
 * nuvio-z-backend 202609180001 it hands the host's authority on after sixty seconds of that. This
 * makes the client's side of the same silence visible: one line when it begins to matter, one when
 * it ends, and nothing on a healthy poll.
 */
internal class PartyPollLiveness(
    private val reportAfterMs: Long = PartyPollSilenceReportMs,
) {
    private var lastSuccessMs: Long? = null
    private var failedAttempts = 0
    private var reported = false

    /** Failed attempts since the last success. */
    val failuresSinceSuccess: Int get() = failedAttempts

    /** The silence to report now that it has ended, or null when there was nothing worth a line. */
    fun onSuccess(nowMs: Long): Long? {
        val silence = lastSuccessMs?.let { nowMs - it }
        val wasReported = reported
        lastSuccessMs = nowMs
        failedAttempts = 0
        reported = false
        return silence.takeIf { wasReported }
    }

    /** The silence so far, the first time it crosses [reportAfterMs]; null otherwise. */
    fun onFailure(nowMs: Long): Long? {
        failedAttempts++
        val since = lastSuccessMs ?: return null
        val silence = nowMs - since
        if (reported || silence < reportAfterMs) return null
        reported = true
        return silence
    }
}

/**
 * How long to wait before the next heartbeat.
 *
 * A healthy poll keeps the ordinary interval. After a failure it retries sooner: the server's
 * liveness is measured from the last heartbeat that *landed*, so waiting the full interval after a
 * refused or timed-out one spends a third of the twenty-second `connected` window doing nothing.
 */
internal fun partyPollDelayAfter(succeeded: Boolean): Long =
    if (succeeded) WatchPartySnapshotIntervalMs else PartyPollRetryAfterFailureMs

/**
 * The most one heartbeat attempt may take, end to end.
 *
 * Each request is already bounded by the client's ten-second timeout, but an attempt can chain
 * several: a session renewal, the RPC, a re-exchange after a 401 and the RPC again - close to a
 * minute of silence in the worst case, which the server would read as the member leaving. Bounding
 * the attempt keeps any one failure to one interval's worth.
 */
internal const val PartyPollAttemptDeadlineMs = 10_000L

internal const val PartyPollRetryAfterFailureMs = 2_000L

/** Three missed heartbeats: well before the twenty-second `connected` window closes. */
internal const val PartyPollSilenceReportMs = 15_000L

internal class PartyPollAttemptTimeoutException :
    IllegalStateException("party heartbeat attempt exceeded ${PartyPollAttemptDeadlineMs}ms")
