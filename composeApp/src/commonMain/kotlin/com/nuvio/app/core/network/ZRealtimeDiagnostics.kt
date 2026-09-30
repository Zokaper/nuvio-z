package com.nuvio.app.core.network

import com.nuvio.app.features.watchparty.WatchPartyDiagnostics
import com.nuvio.app.features.watchparty.currentEpochMs
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.websocket.KtorRealtimeWebsocketFactory
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Wraps the pinned SDK socket without changing its heartbeat, timeout or recovery behavior. */
@OptIn(SupabaseInternal::class)
internal object ZRealtimeDiagnostics : RealtimeWebsocketFactory {
    private var serial = 0L

    override suspend fun create(url: String): RealtimeWebsocket {
        val id = ++serial
        fun trace(event: String, detail: String = "") = WatchPartyDiagnostics.transport(
            event, null, realtime = "socket", detail = "socket=$id $detail",
        )
        trace("socket-create")
        val delegate = try {
            KtorRealtimeWebsocketFactory(ZSupabaseProvider.client.httpClient.httpClient).create(url)
        } catch (error: Throwable) {
            trace("socket-open-failed", safeRealtimeFailure(error))
            throw error
        }
        trace("socket-open")
        return object : RealtimeWebsocket {
            private var heartbeatRef: String? = null
            private var heartbeatAt: Long? = null
            private var lastReceiveAt: Long? = null
            private var failed = false

            override val hasIncomingMessages: Boolean get() = delegate.hasIncomingMessages.also {
                if (!it) trace("socket-incoming-ended", "failed=$failed")
            }

            override suspend fun send(message: RealtimeMessage) {
                if (message.topic == "phoenix" && message.event == "heartbeat") {
                    heartbeatRef = message.ref
                    heartbeatAt = currentEpochMs()
                    trace("socket-heartbeat-send", "ref=${message.ref?.toIntOrNull()}")
                }
                if (message.event == "access_token") trace("socket-auth-update")
                try {
                    delegate.send(message)
                } catch (error: Throwable) {
                    failed = true
                    trace("socket-send-failed", "event=${message.event} ${safeRealtimeFailure(error)}")
                    throw error
                }
            }

            override suspend fun receive(): RealtimeMessage {
                val message = try {
                    delegate.receive()
                } catch (error: Throwable) {
                    failed = true
                    trace("socket-receive-failed", safeRealtimeFailure(error))
                    throw error
                }
                lastReceiveAt = currentEpochMs()
                if (heartbeatRef != null && message.ref == heartbeatRef && message.topic == "phoenix") {
                    trace("socket-heartbeat-ack", "rttMs=${currentEpochMs() - (heartbeatAt ?: currentEpochMs())}")
                    heartbeatRef = null
                    heartbeatAt = null
                }
                if (message.event in setOf("phx_error", "phx_close", "system", "phx_reply")) {
                    val status = message.payload["status"]?.jsonPrimitive?.contentOrNull
                    if (message.event != "phx_reply" || status == "error") {
                        // Classify known errors; never persist arbitrary SDK payloads or auth frames.
                        val payload = message.payload.toString().lowercase()
                        val reason = when {
                            "expired" in payload || "jwt" in payload || "token" in payload -> "auth"
                            "timeout" in payload -> "timeout"
                            else -> "server-event"
                        }
                        val plane = when {
                            message.topic.startsWith("realtime:party_peer:") -> "peer"
                            message.topic.startsWith("realtime:party:") -> "authority"
                            else -> "other"
                        }
                        trace("socket-server-event", "event=${message.event} plane=$plane reason=$reason")
                    }
                }
                return message
            }

            override fun disconnect() {
                trace("socket-disconnect", "failed=$failed heartbeatPending=${heartbeatRef != null} " +
                    "heartbeatAgeMs=${heartbeatAt?.let { currentEpochMs() - it }} " +
                    "receiveAgeMs=${lastReceiveAt?.let { currentEpochMs() - it }}")
                delegate.disconnect()
            }

            override suspend fun blockUntilDisconnect() = delegate.blockUntilDisconnect()
        }
    }
}

/** Native errors may embed the authenticated websocket URL. Persist only type and numeric code. */
internal fun safeRealtimeFailure(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(4).toList()
    val text = causes.joinToString { it.message.orEmpty() }
    val nativeCode = Regex("Code=(-?[0-9]+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    val reason = when {
        causes.any { it is kotlinx.coroutines.CancellationException } -> "cancellation"
        "timeout" in text.lowercase() || "timed out" in text.lowercase() || nativeCode == -1001 -> "timeout"
        nativeCode == -1009 -> "offline"
        nativeCode == -1005 -> "connection-lost"
        else -> "socket-error"
    }
    return "type=${error::class.simpleName} cause=${error.cause?.let { it::class.simpleName }} reason=$reason nativeCode=$nativeCode"
}
