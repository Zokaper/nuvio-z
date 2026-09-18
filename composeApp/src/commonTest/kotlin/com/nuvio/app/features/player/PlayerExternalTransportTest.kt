package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerExternalTransportTest {
    private var party = false
    private val events = mutableListOf<Pair<String, Double>>()
    private val seeks = mutableListOf<Long>()
    private var engineCalls = 0
    private val transport = PlayerExternalTransport(
        partyActive = { party },
        onEvent = { type, value -> events += type to value; true },
        onSeek = { seeks += it; true },
    )

    @Test fun outsideAPartyTheEngineMovesAndTheRuntimeLearnsTheIntent() {
        transport.pause { engineCalls++ }
        transport.play { engineCalls++ }
        assertEquals(2, engineCalls)
        assertEquals(listOf("setPlaybackStateQuiet" to 0.0, "setPlaybackStateQuiet" to 1.0), events)
    }

    @Test fun inAPartyOnlyThePartyMovesTheEngine() {
        party = true
        transport.pause { engineCalls++ }
        transport.play { engineCalls++ }
        assertEquals(0, engineCalls)
        assertEquals(listOf("setPlaybackStateQuiet" to 0.0, "setPlaybackStateQuiet" to 1.0), events)
    }

    @Test fun seeksAlwaysGoThroughTheRuntimeAndNeverBeforeZero() {
        transport.seekTo(-4_000L)
        party = true
        transport.seekTo(90_000L)
        assertEquals(listOf(0L, 90_000L), seeks)
    }

    @Test fun onlyAnActivePartyOwnsTheTransport() {
        assertEquals(false, PlayerControlsState().partyOwnsExternalTransport())
        listOf("idle", "starting", "connecting", "activeElsewhere", "ended").forEach { name ->
            assertEquals(false, PlayerControlsState(watchTogether = WatchTogetherBridgeState(stateName = name)).partyOwnsExternalTransport(), name)
        }
        assertEquals(true, PlayerControlsState(watchTogether = WatchTogetherBridgeState(stateName = "active")).partyOwnsExternalTransport())
    }
}
