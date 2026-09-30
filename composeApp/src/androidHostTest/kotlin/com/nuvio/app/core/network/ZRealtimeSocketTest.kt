package com.nuvio.app.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.websocket.KtorRealtimeWebsocketFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the production setup and SDK serialization over an actual localhost websocket. */
@OptIn(SupabaseInternal::class)
class ZRealtimeSocketTest {
    @Test
    fun diagnosticSocketOpensAndRoundTripsHeartbeat() = roundTrip(diagnosticsEnabled = true)

    @Test
    fun standardSocketOpensAndRoundTripsHeartbeat() = roundTrip(diagnosticsEnabled = false)

    private fun roundTrip(diagnosticsEnabled: Boolean) = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"topic":"phoenix","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"1"}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
            server.start()
            lateinit var client: SupabaseClient
            client = createSupabaseClient(server.url("/").toString(), "public-test-key") {
                installZRealtime(diagnosticsEnabled) { client.httpClient.httpClient }
            }
            try {
                val factory = client.realtime.config.websocketFactory
                    ?: KtorRealtimeWebsocketFactory(client.httpClient.httpClient)
                assertTrue(factory is ZRealtimeHeartbeatRecovery)
                withTimeout(5_000) {
                    val socket = factory.create(server.url("/realtime/v1/websocket").toString().replace("http://", "ws://"))
                    try {
                        socket.send(RealtimeMessage("phoenix", "heartbeat", buildJsonObject {}, "1"))
                        val reply = socket.receive()
                        assertEquals("phoenix", reply.topic)
                        assertEquals("phx_reply", reply.event)
                        assertEquals("1", reply.ref)
                        assertTrue(socket.hasIncomingMessages)
                    } finally {
                        socket.disconnect()
                    }
                }
            } finally {
                client.close()
            }
        }
    }
}
