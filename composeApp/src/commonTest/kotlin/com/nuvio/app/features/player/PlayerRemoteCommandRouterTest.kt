package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS lock screen's route into the runtime. [PlayerExternalTransportTest] covers the transport;
 * this covers what the Swift side calls, including the commands Android does not have.
 */
class PlayerRemoteCommandRouterTest {
    /** Whether a player runtime is behind the surface at all (the trailer popup has none). */
    private var runtimeAttached = true
    /** What the runtime answers to a play/pause: `true` when a party took it. */
    private var partyOwnsTransport = false
    private var composedIntent = true
    private val events = mutableListOf<Pair<String, Double>>()
    private val runtimeSeeks = mutableListOf<Long>()
    private val engineCalls = mutableListOf<String>()
    private var enginePaused = true
    private var enginePositionMs = 60_000L

    private val router = PlayerRemoteCommandRouter(
        transport = PlayerExternalTransport(
            onEvent = { type, value ->
                events += type to value
                when {
                    !runtimeAttached -> false
                    type == "externalRestorePlaybackIntent" -> true
                    else -> partyOwnsTransport
                }
            },
            onSeek = { runtimeSeeks += it; runtimeAttached },
        ),
        engine = object : PlayerRemoteCommandRouter.Engine {
            override fun play() { engineCalls += "play" }
            override fun pause() { engineCalls += "pause" }
            override fun seekTo(positionMs: Long) { engineCalls += "seek:$positionMs" }
            override fun isPaused() = enginePaused
            override fun positionMs() = enginePositionMs
        },
        playWhenReady = { composedIntent },
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

    @Test fun toggleReadsThePauseFlagSoAPauseDuringARebufferIsAPause() {
        // Stalled for cache: not playing, but not paused either. The press means pause.
        enginePaused = false
        router.togglePlayPause()
        // Paused by the user, or by iOS entering the background behind the runtime: the press means play.
        enginePaused = true
        router.togglePlayPause()
        assertEquals(listOf("externalSetPlaybackState" to 0.0, "externalSetPlaybackState" to 1.0), events)
        assertEquals(listOf("pause", "play"), engineCalls)
    }

    @Test fun seeksAndSkipsGoThroughTheRuntimeAndNeverTouchTheEngineWhenItAnswers() {
        router.seekTo(90_000L)
        router.seekBy(10_000L)
        router.seekBy(-10_000L)
        enginePositionMs = 4_000L
        router.seekBy(-10_000L)
        assertEquals(listOf(90_000L, 70_000L, 50_000L, 0L), runtimeSeeks)
        assertTrue(engineCalls.isEmpty())
    }

    @Test fun withNoRuntimeBehindTheSurfaceSeeksStillMoveTheEngine() {
        runtimeAttached = false
        router.seekTo(90_000L)
        router.seekBy(-120_000L)
        assertEquals(listOf("seek:90000", "seek:0"), engineCalls)
    }

    @Test fun theForegroundReturnIsTheRuntimesDecisionAndNeverAPlayFromHere() {
        router.restorePlaybackIntent()
        assertEquals(listOf("externalRestorePlaybackIntent" to 0.0), events)
        assertTrue(engineCalls.isEmpty(), "the runtime restores its live intent itself")
    }

    @Test fun withNoRuntimeTheForegroundReturnFollowsTheComposedIntent() {
        runtimeAttached = false
        composedIntent = false
        router.restorePlaybackIntent()
        composedIntent = true
        router.restorePlaybackIntent()
        assertEquals(listOf("pause", "play"), engineCalls)
    }
}
