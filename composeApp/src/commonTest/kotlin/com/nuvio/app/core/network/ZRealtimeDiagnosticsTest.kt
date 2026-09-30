package com.nuvio.app.core.network

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZRealtimeDiagnosticsTest {
    @Test
    fun nativeErrorTraceClassifiesWithoutPersistingUrlsHeadersOrTokens() {
        val output = safeRealtimeFailure(IllegalStateException(
            "Error Domain=NSURLErrorDomain Code=-1001 request timed out https://example.invalid?apikey=PRIVATE_TOKEN Authorization: PRIVATE_HEADER"))
        assertTrue("reason=timeout" in output)
        assertTrue("nativeCode=-1001" in output)
        assertFalse("PRIVATE" in output)
        assertFalse("https" in output)
        assertFalse("Authorization" in output)
    }

    @Test
    fun cancellationAndConnectionLossRemainDistinctFromTimeout() {
        assertTrue("reason=cancellation" in safeRealtimeFailure(CancellationException("socket")))
        assertTrue("reason=connection-lost" in safeRealtimeFailure(IllegalStateException("Code=-1005")))
        assertTrue("reason=offline" in safeRealtimeFailure(IllegalStateException("Code=-1009")))
    }

    @Test
    fun missingWebsocketPluginIsIdentifiedWithoutPersistingTheMessage() {
        val output = safeRealtimeFailure(IllegalStateException(
            "Plugin io.ktor.client.plugins.websocket.WebSockets is not installed. PRIVATE_TOKEN"))
        assertTrue("reason=missing-websockets-plugin" in output)
        assertFalse("PRIVATE" in output)
    }
}
