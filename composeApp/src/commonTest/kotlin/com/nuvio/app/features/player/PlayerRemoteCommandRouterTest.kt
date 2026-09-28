package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS lock screen's route into the runtime. [PlayerExternalTransportTest] covers the transport;
 * this covers what the Swift side calls, including the commands Android does not have.
 */
class PlayerRemoteCommandRouterTest {
    /** What the runtime answers to a play/pause: `true` when a party took it. */
    private var partyOwnsTransport = false
    private var intent = true
    private val events = mutableListOf<Pair<String, Double>>()
    private val runtimeSeeks = mutableListOf<Long>()
    private val engineCalls = mutableListOf<String>()
    private var enginePlaying = false
    private var enginePositionMs = 60_000L

    private val router = PlayerRemoteCommandRouter(
        transport = PlayerExternalTransport(
            onEvent = { type, value -> events += type to value; partyOwnsTransport },
            onSeek = { runtimeSeeks += it; true },
        ),
        engine = object : PlayerRemoteCommandRouter.Engine {
            override fun play() { engineCalls += "play" }
            override fun pause() { engineCalls += "pause" }
            override fun isPlaying() = enginePlaying
            override fun positionMs() = enginePositionMs
        },
        playWhenReady = { intent },
    )

    @Test fun outsideAPartyLockScreenPlayAndPauseMoveTheEngineAndTellTheRuntime() {
        router.pause()
        router.play()
        assertEquals(listOf("pause", "play"), engineCalls)
        assertEquals(listOf("externalSetPlaybackState" to 0.0, "externalSetPlaybackState" to 1.0), events)
    }

    @Test fun inAPartyLockScreenPlayAndPauseNeverMoveTheEngineFirst() {
        partyOwnsTransport = true
        router.pause()
        router.play()
        router.togglePlayPause()
        assertTrue(engineCalls.isEmpty(), "the party's own command moves the engine, not the lock screen")
        assertEquals(3, events.size)
    }

    @Test fun toggleReadsTheEngineBecauseIosPausesItInTheBackgroundBehindTheRuntime() {
        enginePlaying = true
        router.togglePlayPause()
        enginePlaying = false
        router.togglePlayPause()
        assertEquals(listOf("externalSetPlaybackState" to 0.0, "externalSetPlaybackState" to 1.0), events)
        assertEquals(listOf("pause", "play"), engineCalls)
    }

    @Test fun seeksAndSkipsAlwaysGoThroughTheRuntimeAndNeverTouchTheEngine() {
        router.seekTo(90_000L)
        router.seekBy(10_000L)
        router.seekBy(-10_000L)
        enginePositionMs = 4_000L
        router.seekBy(-10_000L)
        assertEquals(listOf(90_000L, 70_000L, 50_000L, 0L), runtimeSeeks)
        assertTrue(engineCalls.isEmpty())
    }

    @Test fun returningToTheForegroundRestoresTheRuntimesIntentRatherThanAlwaysPlaying() {
        intent = false
        router.restorePlaybackIntent()
        intent = true
        router.restorePlaybackIntent()
        assertEquals(listOf("pause", "play"), engineCalls)
        assertTrue(events.isEmpty(), "restoring an intent is not a new command")
    }
}
