package com.nuvio.app.features.servers

import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerStreamSerializationTest {
    @Test
    fun aServerTargetSurvivesThePersistedStreamRoundTrip() {
        val stream = StreamItem(addonName = "Server", addonId = "server:test",
            serverTarget = ServerPlaybackTarget(ServerItemRef("connection", "item:123"), "source"))
        val decoded = Json.decodeFromString<StreamItem>(Json.encodeToString(stream))
        assertEquals(stream.serverTarget, decoded.serverTarget)
    }
}
