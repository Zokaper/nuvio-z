package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerExternalTransportTest {
    /** What the runtime answers: `true` when a party took the request. */
    private var partyTakesIt = false
    private val events = mutableListOf<Pair<String, Double>>()
    private val seeks = mutableListOf<Long>()
    private var engineCalls = 0
    private val transport = PlayerExternalTransport(
        onEvent = { type, value -> events += type to value; partyTakesIt },
        onSeek = { seeks += it; true },
    )

    @Test fun outsideAPartyTheRuntimeLearnsTheIntentAndTheEngineMoves() {
        transport.pause { engineCalls++ }
        transport.play { engineCalls++ }
        assertEquals(2, engineCalls)
        assertEquals(listOf("externalSetPlaybackState" to 0.0, "externalSetPlaybackState" to 1.0), events)
    }

    @Test fun whenThePartyTakesItOnlyThePartyMovesTheEngine() {
        partyTakesIt = true
        transport.pause { engineCalls++ }
        transport.play { engineCalls++ }
        assertEquals(0, engineCalls)
        assertEquals(listOf("externalSetPlaybackState" to 0.0, "externalSetPlaybackState" to 1.0), events)
    }

    @Test fun seeksAlwaysGoThroughTheRuntimeAndNeverBeforeZero() {
        transport.seekTo(-4_000L)
        transport.seekTo(90_000L)
        assertEquals(listOf(0L, 90_000L), seeks)
    }
}
