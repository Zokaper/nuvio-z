package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyPollLivenessTest {

    @Test
    fun healthyPollSaysNothing() {
        val liveness = PartyPollLiveness(reportAfterMs = 15_000)
        for (t in 0L..60_000L step 5_000L) assertNull(liveness.onSuccess(t))
    }

    @Test
    fun briefFailureIsNeitherReportedNorAnnouncedOnRecovery() {
        val liveness = PartyPollLiveness(reportAfterMs = 15_000)
        liveness.onSuccess(0)
        assertNull(liveness.onFailure(5_000))
        assertNull(liveness.onFailure(7_000))
        assertNull(liveness.onSuccess(9_000))
    }

    @Test
    fun theIncidentSilenceIsReportedOnceAndItsEndOnce() {
        // 2026-09-18: last landed heartbeat, then 71 seconds without one.
        val liveness = PartyPollLiveness(reportAfterMs = 15_000)
        liveness.onSuccess(0)
        val reports = (2_000L..70_000L step 2_000L).mapNotNull { liveness.onFailure(it) }
        assertEquals(listOf(16_000L), reports, "one line when the silence starts to matter, not one per attempt")
        assertEquals(35, liveness.failuresSinceSuccess)
        assertEquals(71_000L, liveness.onSuccess(71_000))
        assertEquals(0, liveness.failuresSinceSuccess)
        assertNull(liveness.onSuccess(76_000), "recovery is announced once")
    }

    @Test
    fun failureBeforeAnySuccessHasNoBaselineToMeasureFrom() {
        val liveness = PartyPollLiveness(reportAfterMs = 15_000)
        assertNull(liveness.onFailure(60_000))
        assertNull(liveness.onSuccess(65_000))
    }

    @Test
    fun failedAttemptIsRetriedSoonerThanTheOrdinaryInterval() {
        assertEquals(WatchPartySnapshotIntervalMs, partyPollDelayAfter(succeeded = true))
        assertTrue(partyPollDelayAfter(succeeded = false) < WatchPartySnapshotIntervalMs)
    }

    @Test
    fun oneAttemptCannotOutlastTheServerLivenessWindow() {
        // Twenty seconds unseen marks a member disconnected. An attempt plus the retry after it must
        // fit well inside that, however many requests the attempt chains.
        assertTrue(PartyPollAttemptDeadlineMs + PartyPollRetryAfterFailureMs < 20_000)
    }
}
