package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OfficialSessionAccessTest {

    private val now = Instant.fromEpochSeconds(1_800_000_000)

    @Test
    fun aTokenWithTimeToSpareIsUsedAsItIs() {
        assertFalse(officialTokenNeedsRefresh(expiresAt = now + 30.minutes, now = now))
    }

    @Test
    fun anExpiredTokenIsRefreshedBeforeItIsExchanged() {
        // The wake-from-sleep case: the official client still reports Authenticated, but its token
        // lapsed while the machine slept and its own refresh timer has not fired yet.
        assertTrue(officialTokenNeedsRefresh(expiresAt = now - 5.minutes, now = now))
    }

    @Test
    fun aTokenAboutToExpireIsRefreshedRatherThanSentToLapseInFlight() {
        assertTrue(officialTokenNeedsRefresh(expiresAt = now + 30.seconds, now = now))
        assertTrue(officialTokenNeedsRefresh(expiresAt = now + OfficialTokenRefreshAhead, now = now))
        assertFalse(officialTokenNeedsRefresh(expiresAt = now + OfficialTokenRefreshAhead + 1.seconds, now = now))
    }
}
