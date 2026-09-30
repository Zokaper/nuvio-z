package com.nuvio.app.core.network

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Supabase 3.4.1 retains its pending heartbeat reference when a socket fails. Its first
 * heartbeat timer on a healthy replacement then disconnects it without sending anything.
 * Re-send that heartbeat on the replacement: only a real server reply clears the SDK's
 * reference. No synthetic acknowledgement or change to heartbeat/timeout policy.
 */
@OptIn(SupabaseInternal::class)
internal class ZRealtimeHeartbeatRecovery(private val factory: RealtimeWebsocketFactory) : RealtimeWebsocketFactory {
    private val lock = SynchronizedObject()
    private var pending: RealtimeMessage? = null
    private var generation = 0L

    override suspend fun create(url: String): RealtimeWebsocket {
        val delegate = factory.create(url)
        val (id, retry) = synchronized(lock) { generation += 1; generation to pending }
        try {
            retry?.let { delegate.send(it) }
        } catch (error: Throwable) {
            delegate.disconnect()
            throw error
        }
        return object : RealtimeWebsocket {
            override val hasIncomingMessages: Boolean get() = delegate.hasIncomingMessages
            override suspend fun send(message: RealtimeMessage) {
                if (message.topic == "phoenix" && message.event == "heartbeat") {
                    synchronized(lock) { if (generation == id) pending = message }
                }
                delegate.send(message)
            }
            override suspend fun receive(): RealtimeMessage = delegate.receive().also { message ->
                if (message.topic == "phoenix" && message.event == "phx_reply") {
                    synchronized(lock) {
                        if (generation == id && message.ref != null && message.ref == pending?.ref) pending = null
                    }
                }
            }
            override fun disconnect() = delegate.disconnect()
            override suspend fun blockUntilDisconnect() = delegate.blockUntilDisconnect()
        }
    }
}
