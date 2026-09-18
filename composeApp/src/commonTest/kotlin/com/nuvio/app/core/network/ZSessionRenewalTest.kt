package com.nuvio.app.core.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Regression case 8 of the 2026-09-18 host-authority incident: an expired Z token must be renewed
 * and the heartbeat must carry on, rather than every heartbeat failing until the server's stale-host
 * rule hands the host's authority to a guest.
 */
class ZSessionRenewalTest {

    private val issued = Instant.fromEpochSeconds(1_789_727_000)
    private val expiresAt = issued + 1.hours

    @Test
    fun freshTokenNeedsNothing() {
        assertEquals(ZSessionFreshness.Fresh, zSessionFreshness(expiresAt, issued))
        assertEquals(ZSessionFreshness.Fresh, zSessionFreshness(expiresAt, expiresAt - ZSessionRenewAhead - 1.seconds))
    }

    @Test
    fun tokenInsideTheRenewalWindowIsRenewedBeforeItFails() {
        assertEquals(ZSessionFreshness.RenewSoon, zSessionFreshness(expiresAt, expiresAt - ZSessionRenewAhead))
        assertEquals(ZSessionFreshness.RenewSoon, zSessionFreshness(expiresAt, expiresAt - 1.seconds))
    }

    @Test
    fun tokenAtOrPastExpiryIsExpired() {
        assertEquals(ZSessionFreshness.Expired, zSessionFreshness(expiresAt, expiresAt))
        assertEquals(ZSessionFreshness.Expired, zSessionFreshness(expiresAt, expiresAt + 10.minutes))
    }

    @Test
    fun renewalWindowOutlastsSeveralHeartbeats() {
        // A renewal that fails once must get more tries before the token dies: the heartbeat is
        // five seconds and the bridge spaces retries fifteen seconds apart.
        assertTrue(ZSessionRenewAhead >= 1.minutes)
    }

    @Test
    fun heartbeatAfterExpiryRenewsAndSucceeds() = runBlocking {
        // The hour-in heartbeat: the token has expired, ensure() renews it, the call goes through.
        var sessionValid = false
        var renewals = 0
        var calls = 0
        val result = runWithZSession(
            ensure = { renewals++; sessionValid = true; true },
            reexchange = { error("a renewed session must not need a reactive re-exchange") },
        ) {
            calls++
            check(sessionValid) { "401" }
            "snapshot"
        }
        assertEquals("snapshot", result.getOrThrow())
        assertEquals(1, renewals)
        assertEquals(1, calls)
    }

    @Test
    fun tokenRefusedAnywayIsReexchangedOnceAndRetried() = runBlocking {
        val unauthorized = IllegalStateException("401")
        var calls = 0
        var reexchanges = 0
        val result = runWithZSession(
            ensure = { true },
            reexchange = { reexchanges++; true },
            needsReexchange = { it === unauthorized },
        ) {
            calls++
            if (calls == 1) throw unauthorized
            "snapshot"
        }
        assertEquals("snapshot", result.getOrThrow())
        assertEquals(1, reexchanges)
        assertEquals(2, calls)
    }

    @Test
    fun sessionThatCannotBeEstablishedIsAnOrdinaryFailedContact() = runBlocking {
        var calls = 0
        val result = runWithZSession(
            ensure = { false },
            reexchange = { error("nothing to re-exchange") },
        ) { calls++ }
        assertIs<ZSessionUnavailableException>(result.exceptionOrNull())
        assertEquals(0, calls, "no request is sent without a session")
    }

    @Test
    fun nonAuthFailureIsReturnedWithoutReexchanging() = runBlocking {
        val serverError = IllegalStateException("500")
        var reexchanges = 0
        val result = runWithZSession(
            ensure = { true },
            reexchange = { reexchanges++; true },
            needsReexchange = { false },
        ) { throw serverError }
        assertSame(serverError, result.exceptionOrNull())
        assertEquals(0, reexchanges, "a network or server failure must not tear down the shared session")
    }

    @Test
    fun failedReexchangeReturnsTheOriginalRefusalWithoutRetrying() = runBlocking {
        val unauthorized = IllegalStateException("401")
        var calls = 0
        val result = runWithZSession(
            ensure = { true },
            reexchange = { false },
            needsReexchange = { it === unauthorized },
        ) { calls++; throw unauthorized }
        assertSame(unauthorized, result.exceptionOrNull())
        assertEquals(1, calls)
    }
}
