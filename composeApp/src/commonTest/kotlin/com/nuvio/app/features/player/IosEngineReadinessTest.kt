package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS's engine readiness, walked through the states `PlayerEngine.ios.kt` reads from the Swift
 * bridge, and what Watch Together's starvation signal makes of each.
 *
 * iOS runs libmpv, so the mapping is the one Android's libmpv uses ([mpvEngineReadiness]); these
 * cover the iOS inputs - including the bridge's defaults when there is no player - and the
 * transitions a party actually passes through. The Swift side cannot be run here, so this is the
 * contract it is held to.
 */
class IosEngineReadinessTest {
    /** One poll of the iOS bridge: its getters, in the order and with the defaults the bridge has. */
    private data class Poll(
        val pausedForCache: Boolean = false,
        val cacheBufferingState: Int = 100,
        val seeking: Boolean = false,
        val coreIdle: Boolean = false,
        val paused: Boolean = false,
        val durationMs: Long = 2_400_000L,
        val positionMs: Long = 600_000L,
        val bufferedMs: Long = 630_000L,
    ) {
        fun readiness() = mpvEngineReadiness(
            pausedForCache = pausedForCache,
            cacheBuffering = mpvCacheBuffering(cacheBufferingState.takeIf { it >= 0 }),
            paused = paused,
            seeking = seeking,
            idle = coreIdle,
            durationMs = durationMs,
        )

        fun snapshot() = PlayerPlaybackSnapshot(
            durationMs = durationMs,
            positionMs = positionMs,
            bufferedPositionMs = bufferedMs,
            engineName = "libmpv",
            engineReadiness = readiness(),
        )
    }

    /** What the Swift bridge answers with no view controller: before the first load and after destroy. */
    private val noPlayer = Poll(
        pausedForCache = false,
        cacheBufferingState = -1,
        seeking = false,
        coreIdle = true,
        paused = true,
        durationMs = 0L,
        positionMs = 0L,
        bufferedMs = 0L,
    )

    @Test fun cacheBufferingStateIsBufferingOnlyWhileFillingAndUnavailableIsNot() {
        assertFalse(mpvCacheBuffering(null))
        assertTrue(mpvCacheBuffering(0))
        assertTrue(mpvCacheBuffering(96))
        assertFalse(mpvCacheBuffering(100))
    }

    @Test fun initialPrepareIsNoSourceAndNeverStarvation() {
        assertEquals(PlayerEngineReadiness.NoSource, noPlayer.readiness())
        assertFalse(partyStarvedFor(noPlayer.snapshot()))
        // `loadfile` issued, nothing demuxed yet: still the start gate's business, not the stall guard's.
        val opening = noPlayer.copy(paused = false, cacheBufferingState = 0)
        assertEquals(PlayerEngineReadiness.NoSource, opening.readiness())
        assertFalse(partyStarvedFor(opening.snapshot()))
    }

    @Test fun openedButStillFillingIsBufferingNotReady() {
        val filling = Poll(cacheBufferingState = 40, positionMs = 0L, bufferedMs = 2_000L)
        assertEquals(PlayerEngineReadiness.Buffering, filling.readiness())
        assertTrue(partyStarvedFor(filling.snapshot()))
    }

    @Test fun aFullCacheIsReadyPlayingOrHeldPaused() {
        assertEquals(PlayerEngineReadiness.Ready, Poll().readiness())
        assertFalse(partyStarvedFor(Poll().snapshot()))
        // A party's barrier holding this member paused over a full cache is healthy.
        val held = Poll(paused = true, coreIdle = true)
        assertEquals(PlayerEngineReadiness.Ready, held.readiness())
        assertFalse(partyStarvedFor(held.snapshot()))
    }

    @Test fun aRebufferIsStarvationEvenWhenTheBufferedAheadHeuristicWouldCallItFine() {
        // Before this, iOS reported Unknown and the fallback judged by buffered-ahead alone: 1.5 s ahead
        // cleared its one-second threshold while mpv itself had stopped for cache.
        val rebuffer = Poll(pausedForCache = true, coreIdle = true, cacheBufferingState = 12, bufferedMs = 601_500L)
        assertEquals(PlayerEngineReadiness.Buffering, rebuffer.readiness())
        assertTrue(partyStarvedFor(rebuffer.snapshot()))
        assertFalse(
            partyStarvedFor(rebuffer.snapshot().copy(engineReadiness = PlayerEngineReadiness.Unknown)),
            "the fallback's answer, which is what iOS used to give",
        )
    }

    @Test fun aRebufferStaysStarvedWhenThePartyPausesTheMemberAndRecoversOnlyWhenTheCacheDoes() {
        val pausedByParty = Poll(paused = true, coreIdle = true, cacheBufferingState = 30)
        assertEquals(PlayerEngineReadiness.Buffering, pausedByParty.readiness())
        assertTrue(partyStarvedFor(pausedByParty.snapshot()))
        val recovered = pausedByParty.copy(cacheBufferingState = 100)
        assertEquals(PlayerEngineReadiness.Ready, recovered.readiness())
        assertFalse(partyStarvedFor(recovered.snapshot()))
        // And resumed.
        assertEquals(PlayerEngineReadiness.Ready, recovered.copy(paused = false, coreIdle = false).readiness())
    }

    @Test fun aSeekInFlightDefersToTheFallbackAndABarrierHoldIsNeverStarvation() {
        val seeking = Poll(seeking = true, coreIdle = true)
        assertEquals(PlayerEngineReadiness.Unknown, seeking.readiness())
        val starvedHeld = Poll(pausedForCache = true, paused = true)
        assertFalse(partyStarvedFor(starvedHeld.snapshot(), holdingForBarrier = true))
    }

    @Test fun anEpisodeChangePassesThroughNoSourceThenBufferingThenReady() {
        val playing = Poll()
        // `loadfile replace` on the same player: the old file is gone, the new one has no duration yet.
        val replaced = Poll(coreIdle = true, durationMs = 0L, positionMs = 0L, bufferedMs = 0L, cacheBufferingState = -1)
        val opened = Poll(durationMs = 1_320_000L, positionMs = 0L, bufferedMs = 500L, cacheBufferingState = 20, coreIdle = true)
        val running = Poll(durationMs = 1_320_000L, positionMs = 1_000L, bufferedMs = 25_000L)
        assertEquals(
            listOf(
                PlayerEngineReadiness.Ready,
                PlayerEngineReadiness.NoSource,
                PlayerEngineReadiness.Buffering,
                PlayerEngineReadiness.Ready,
            ),
            listOf(playing, replaced, opened, running).map { it.readiness() },
        )
        assertFalse(partyStarvedFor(replaced.snapshot()), "a new episode opening is not a stall")
    }

    @Test fun teardownReportsNoSourceSoLeavingNeverLeavesAStarvedMemberBehind() {
        val starved = Poll(pausedForCache = true)
        assertTrue(partyStarvedFor(starved.snapshot()))
        assertEquals(PlayerEngineReadiness.NoSource, noPlayer.readiness())
        assertFalse(partyStarvedFor(noPlayer.snapshot()))
    }
}
