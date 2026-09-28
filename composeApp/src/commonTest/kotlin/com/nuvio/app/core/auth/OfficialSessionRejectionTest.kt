package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OfficialSessionRejectionTest {

    @Test
    fun onlyTheAuthServerRefusingTheRefreshTokenCountsAsAGoneSession() {
        // Revoked, already used (refresh-token reuse detection), or the user deleted.
        assertEquals(OfficialRefreshOutcome.Rejected, refreshOutcomeForStatus(400))
        assertEquals(OfficialRefreshOutcome.Rejected, refreshOutcomeForStatus(401))
        assertEquals(OfficialRefreshOutcome.Rejected, refreshOutcomeForStatus(403))
        assertEquals(OfficialRefreshOutcome.Rejected, refreshOutcomeForStatus(404))
    }

    @Test
    fun noAnswerFromTheAuthServerNeverSignsTheUserOut() {
        // Offline, a timeout, or no session in memory to refresh: there is no status code at all.
        assertEquals(OfficialRefreshOutcome.Inconclusive, refreshOutcomeForStatus(null))
        assertEquals(OfficialRefreshOutcome.Inconclusive, refreshOutcomeForStatus(500))
        assertEquals(OfficialRefreshOutcome.Inconclusive, refreshOutcomeForStatus(503))
        assertEquals(
            OfficialRefreshOutcome.Inconclusive,
            refreshOutcomeForStatus(429),
            "rate limiting is about the caller, not the session",
        )
    }

    @Test
    fun requestsThatFailTogetherShareOneRefresh() {
        val refreshedAt = Instant.fromEpochSeconds(1_800_000_000)
        assertFalse(refreshedRecently(lastRefreshedAt = null, now = refreshedAt))
        assertTrue(refreshedRecently(lastRefreshedAt = refreshedAt, now = refreshedAt + 5.seconds))
        assertFalse(refreshedRecently(lastRefreshedAt = refreshedAt, now = refreshedAt + 30.seconds))
    }
}
