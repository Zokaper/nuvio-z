package com.nuvio.app.features.watchparty

import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel.Status
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.TimeoutCancellationException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PartyRealtimeRecoveryTest {
    @Test
    fun longSuspensionDoesNotRaceSdkReconnectByReplacingChannels() = runTest {
        val authority = MutableStateFlow(Status.SUBSCRIBED)
        val peer = MutableStateFlow(Status.SUBSCRIBED)
        val socket = MutableStateFlow(Realtime.Status.DISCONNECTED)
        var recovered = 0
        val monitor = backgroundScope.async {
            monitorPartyRealtimeRecovery(authority, peer, socket, 1_000, {}, { recovered++ })
        }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(monitor.isCompleted)
        peer.value = Status.SUBSCRIBING
        socket.value = Realtime.Status.CONNECTED
        runCurrent()
        peer.value = Status.SUBSCRIBED
        runCurrent()
        assertEquals(1, recovered)
    }

    @Test
    fun socketAndEitherPlaneRecoverInPlaceWithoutDeclaringHalfSubscriptionsReady() = runTest {
        val authority = MutableStateFlow(Status.SUBSCRIBED)
        val peer = MutableStateFlow(Status.SUBSCRIBED)
        val socket = MutableStateFlow(Realtime.Status.CONNECTED)
        var degraded = 0
        var recovered = 0
        val monitor = backgroundScope.async {
            monitorPartyRealtimeRecovery(authority, peer, socket, 1_000, { degraded++ }, { recovered++ })
        }
        runCurrent()
        socket.value = Realtime.Status.DISCONNECTED
        runCurrent()
        authority.value = Status.SUBSCRIBING
        peer.value = Status.SUBSCRIBING
        socket.value = Realtime.Status.CONNECTED
        runCurrent()
        authority.value = Status.SUBSCRIBED
        runCurrent()
        assertEquals(0, recovered)
        peer.value = Status.SUBSCRIBED
        runCurrent()
        assertEquals(1, recovered)
        authority.value = Status.UNSUBSCRIBED
        runCurrent()
        authority.value = Status.SUBSCRIBED
        runCurrent()
        assertEquals(2, degraded)
        assertEquals(2, recovered)
        assertFalse(monitor.isCompleted)
    }

    @Test
    fun unrecoveredLossIsBoundedSoOwnerCanRecreateChannels() = runTest {
        val authority = MutableStateFlow(Status.UNSUBSCRIBED)
        val peer = MutableStateFlow(Status.SUBSCRIBED)
        val socket = MutableStateFlow(Realtime.Status.CONNECTED)
        val monitor = backgroundScope.async {
            monitorPartyRealtimeRecovery(authority, peer, socket, 1_000, {}, {})
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFailsWith<TimeoutCancellationException> { monitor.await() }
    }
}
