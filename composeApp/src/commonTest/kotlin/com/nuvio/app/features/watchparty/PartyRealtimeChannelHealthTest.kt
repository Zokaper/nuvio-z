package com.nuvio.app.features.watchparty

import io.github.jan.supabase.realtime.RealtimeChannel.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class PartyRealtimeChannelHealthTest {
    @Test
    fun eitherPlaneLossDegradesAndBothSubscriptionsNeedFreshProofToRecover() {
        for (plane in PartyRealtimePlane.entries) {
            val instance = 7L
            var health = reducePartyHealth(PartyHealthState(), PartyHealthEvent.RealtimeConnecting(instance))
            health = reducePartyHealth(health, partyPlaneHealthEvent(Status.SUBSCRIBED, Status.SUBSCRIBED, instance))
            health = reducePartyHealth(health, PartyHealthEvent.RealtimeReceived(instance, 100L))
            assertEquals(PartyRealtimeHealth.Live, health.realtime)
            for (loss in listOf(Status.UNSUBSCRIBING, Status.UNSUBSCRIBED)) {
                val a = if (plane == PartyRealtimePlane.Authority) loss else Status.SUBSCRIBED
                val p = if (plane == PartyRealtimePlane.Peer) loss else Status.SUBSCRIBED
                health = reducePartyHealth(health, partyPlaneHealthEvent(a, p, instance))
                assertEquals(PartyRealtimeHealth.Degraded, health.realtime)
                health = reducePartyHealth(health, partyLiveReceiveEvent(a, p, true, instance, 150L,
                    PartyRealtimeTrafficKind.Peer))
                assertEquals(PartyRealtimeHealth.Degraded, health.realtime)
            }
            health = reducePartyHealth(health, partyPlaneHealthEvent(Status.SUBSCRIBED, Status.SUBSCRIBED, instance))
            assertEquals(PartyRealtimeHealth.SubscribedUnverified, health.realtime)
            health = reducePartyHealth(health, PartyHealthEvent.RealtimeReceived(instance, 200L))
            assertEquals(PartyRealtimeHealth.Live, health.realtime)
        }
    }

    @Test
    fun halfSubscribedTransportNeverLooksSubscribed() {
        assertEquals(PartyRealtimeHealth.Connecting, partyPlaneHealth(Status.SUBSCRIBED, Status.SUBSCRIBING))
        assertEquals(PartyRealtimeHealth.Connecting, partyPlaneHealth(Status.SUBSCRIBING, Status.SUBSCRIBED))
        assertEquals(PartyHealthEvent.RealtimeDegraded(7), partyLiveReceiveEvent(
            Status.SUBSCRIBED, Status.SUBSCRIBED, false, 7, 100L, PartyRealtimeTrafficKind.Peer))
    }

    @Test
    fun sequencePermissionsHostAndGenerationUpdatesDoNotReplaceSocketIdentity() {
        val held = PartyAuthorityContext("party", "self", "host", WatchPartyControlMode.host_only, 1,
            PartyGenerationKey("party", 1, 1, 0))
        assertFalse(partyChannelIdentityChanged(held, held.copy(durableSequence = 2)))
        assertFalse(partyChannelIdentityChanged(held, held.copy(hostProfileId = "self")))
        assertFalse(partyChannelIdentityChanged(held, held.copy(controlMode = WatchPartyControlMode.collaborative)))
        assertFalse(partyChannelIdentityChanged(held, held.copy(generation = PartyGenerationKey("party", 2, 2, 1))))
        assertTrue(partyChannelIdentityChanged(held, held.copy(partyId = "other")))
        assertTrue(partyChannelIdentityChanged(held, held.copy(selfProfileId = "other")))
        assertTrue(partyChannelIdentityChanged(held, null))
    }

    @Test
    fun unexpectedSdkCancellationIsRetriedWhileScopeIsActive() {
        assertFalse(partyChannelFailureIsScopeCancellation(CancellationException("socket"), scopeActive = true))
        assertTrue(partyChannelFailureIsScopeCancellation(CancellationException("leaving"), scopeActive = false))
    }
}
