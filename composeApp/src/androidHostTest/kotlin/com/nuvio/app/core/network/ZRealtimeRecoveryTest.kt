package com.nuvio.app.core.network

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.channel
import com.nuvio.app.features.watchparty.monitorPartyRealtimeRecovery
import com.nuvio.app.features.watchparty.WatchPartySync
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

/** Real pinned SDK, with a server that drops the first socket before answering a heartbeat. */
@OptIn(SupabaseInternal::class)
class ZRealtimeRecoveryTest {
    @Test
    fun interruptedHeartbeatDoesNotDisconnectAHealthyReplacementSocket() = recover(secondAnswers = true, expectedSockets = 2)

    @Test
    fun unansweredHeartbeatOnReplacementStillTriggersRealSdkTimeout() = recover(secondAnswers = false, expectedSockets = 3)

    @Test
    fun sdkCallbackResetIsReboundWithoutLosingDurableStateBroadcasts() =
        recover(secondAnswers = true, expectedSockets = 2, resetCallbacks = true)

    private fun recover(secondAnswers: Boolean, expectedSockets: Int, resetCallbacks: Boolean = false) = runBlocking {
        val sockets = CopyOnWriteArrayList<TestSocket>()
        val healthy = CompletableDeferred<Unit>()
        val receivedState = CompletableDeferred<Unit>()
        val stateCount = AtomicInteger()
        lateinit var client: SupabaseClient
        val factory = object : RealtimeWebsocketFactory {
            override suspend fun create(url: String): RealtimeWebsocket {
                if (resetCallbacks && sockets.isNotEmpty()) client.realtime.subscriptions.values.forEach { it.teardown() }
                return TestSocket(dropHeartbeat = sockets.isEmpty(), answerHeartbeat = sockets.size != 1 || secondAnswers,
                    healthy = healthy).also { sockets.add(it) }
            }
        }
        client = createSupabaseClient("https://example.invalid", "public-test-key") {
            install(Realtime) {
                websocketFactory = ZRealtimeHeartbeatRecovery(factory)
                heartbeatInterval = 100.milliseconds
                reconnectDelay = 20.milliseconds
                disconnectOnNoSubscriptions = false
            }
        }
        val authority = client.channel("party:test")
        val peer = client.channel("party_peer:test")
        var recoveries = 0
        var monitor: kotlinx.coroutines.Job? = null
        try {
            delay(100) // Let the process-owned adapter's initial empty binding settle.
            WatchPartySync.configure({}, {
                if (stateCount.incrementAndGet() == 3) receivedState.complete(Unit)
            }, {}, {})
            WatchPartySync.restartBroadcastCollectors(authority, peer)
            withTimeout(3_000) {
                authority.subscribe(blockUntilSubscribed = true)
                peer.subscribe(blockUntilSubscribed = true)
            }
            monitor = launch {
                monitorPartyRealtimeRecovery(authority.status, peer.status, client.realtime.status,
                    onDegraded = {}, onRecovered = {
                        WatchPartySync.restartBroadcastCollectors(authority, peer)
                        recoveries++
                    })
            }
            withTimeout(3_000) { healthy.await() }
            withTimeout(3_000) { receivedState.await() }
            assertEquals(expectedSockets, sockets.size, "Only genuinely unanswered heartbeats may close a replacement")
            assertFalse(sockets.last().disconnected.isCompleted)
            for (socket in sockets) {
                assertEquals(listOf("realtime:party:test", "realtime:party_peer:test").sorted(), socket.joins.sorted())
            }
            kotlin.test.assertTrue(recoveries > 0)
        } finally {
            monitor?.cancelAndJoin()
            WatchPartySync.javaClass.getDeclaredMethod("stopChannelJobs").apply { isAccessible = true }.invoke(WatchPartySync)
            WatchPartySync.configure({}, {}, {}, {})
            client.close()
        }
    }

    private class TestSocket(private val dropHeartbeat: Boolean, private val answerHeartbeat: Boolean,
        private val healthy: CompletableDeferred<Unit>) : RealtimeWebsocket {
        private val incoming = Channel<RealtimeMessage>(Channel.UNLIMITED)
        private val heartbeats = AtomicInteger()
        val disconnected = CompletableDeferred<Unit>()
        val joins = CopyOnWriteArrayList<String>()
        override val hasIncomingMessages: Boolean get() = !disconnected.isCompleted
        override suspend fun send(message: RealtimeMessage) {
            if (message.event == "phx_join") {
                joins.add(message.topic)
                incoming.send(RealtimeMessage(message.topic, "system", buildJsonObject {
                    put("status", "ok")
                    put("response", buildJsonObject {})
                }, message.ref))
            }
            if (message.topic != "phoenix" || message.event != "heartbeat") return
            if (dropHeartbeat) {
                incoming.close(IOException("Test suspension interrupted heartbeat"))
            } else if (answerHeartbeat) {
                incoming.send(RealtimeMessage("phoenix", "phx_reply", buildJsonObject {
                    put("status", "ok")
                    put("response", buildJsonObject {})
                }, message.ref))
                incoming.send(RealtimeMessage("realtime:party:test", "broadcast", buildJsonObject {
                    put("type", "broadcast")
                    put("event", "state")
                    put("payload", buildJsonObject { put("probe", true) })
                }, null))
                if (heartbeats.incrementAndGet() == 3) healthy.complete(Unit)
            }
        }
        override suspend fun receive(): RealtimeMessage = incoming.receive()
        override fun disconnect() { disconnected.complete(Unit); incoming.cancel() }
        override suspend fun blockUntilDisconnect() { disconnected.await() }
    }
}
