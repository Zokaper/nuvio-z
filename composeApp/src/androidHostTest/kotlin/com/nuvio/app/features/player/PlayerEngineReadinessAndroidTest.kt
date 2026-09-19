package com.nuvio.app.features.player

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine-native half of Watch Together's starvation signal: what each engine's own state means.
 *
 * [PartyStarvationTest] covers what the party does with the verdict; these are the mappings that
 * produce it, kept pure so both can be tested without an engine.
 */
class PlayerEngineReadinessAndroidTest {
    @Test
    fun exoPlayerBufferingIsTheOnlyStateThatMeansStarved() {
        assertEquals(PlayerEngineReadiness.Buffering, exoPlayerEngineReadiness(Player.STATE_BUFFERING))
        // Ready is ready whether or not playWhenReady is set, which is what lets a host-forced pause
        // over a full engine read as healthy.
        assertEquals(PlayerEngineReadiness.Ready, exoPlayerEngineReadiness(Player.STATE_READY))
        // Nothing is being waited for at the end of a file.
        assertEquals(PlayerEngineReadiness.Ready, exoPlayerEngineReadiness(Player.STATE_ENDED))
        // No source at all is not an empty one.
        assertEquals(PlayerEngineReadiness.NoSource, exoPlayerEngineReadiness(Player.STATE_IDLE))
    }

    @Test
    fun exoPlayerBufferingReachesThePartyAsStarvationRegardlessOfBufferLevel() {
        val stalled = PlayerPlaybackSnapshot(
            isLoading = true,
            durationMs = 2_400_000L,
            positionMs = 60_000L,
            bufferedPositionMs = 61_200L,
            engineName = "ExoPlayer",
            engineReadiness = exoPlayerEngineReadiness(Player.STATE_BUFFERING),
        )
        assertTrue(partyStarvedFor(stalled), "1200ms ahead cleared the retired threshold")
    }

    private fun mpv(
        pausedForCache: Boolean = false,
        cacheBuffering: Boolean = false,
        paused: Boolean = false,
        seeking: Boolean = false,
        idle: Boolean = false,
        durationMs: Long = 2_400_000L,
    ) = mpvEngineReadiness(pausedForCache, cacheBuffering, paused, seeking, idle, durationMs)

    @Test
    fun libmpvPausedForCacheIsStarvedEvenWhileThePartyHasItPaused() {
        assertEquals(PlayerEngineReadiness.Buffering, mpv(pausedForCache = true))
        assertEquals(PlayerEngineReadiness.Buffering, mpv(pausedForCache = true, paused = true))
    }

    @Test
    fun libmpvCacheBufferingCountsOnlyWhilePlaybackIsIntended() {
        assertEquals(PlayerEngineReadiness.Buffering, mpv(cacheBuffering = true))
        // A deliberate pause can leave the last percentage standing; believing it would report every
        // paused member as empty.
        assertEquals(PlayerEngineReadiness.Ready, mpv(cacheBuffering = true, paused = true))
    }

    @Test
    fun libmpvWithARecoveredCacheIsReady() {
        assertEquals(PlayerEngineReadiness.Ready, mpv())
        assertFalse(
            partyStarvedFor(
                PlayerPlaybackSnapshot(
                    isPlaying = true,
                    durationMs = 2_400_000L,
                    positionMs = 60_000L,
                    bufferedPositionMs = 60_400L,
                    engineName = "libmpv",
                    engineReadiness = mpv(),
                ),
            ),
            "400ms ahead, and the engine says it is playing",
        )
    }

    @Test
    fun libmpvSeekingAndStartupAnswerNeither() {
        assertEquals(PlayerEngineReadiness.Unknown, mpv(seeking = true))
        assertEquals(PlayerEngineReadiness.NoSource, mpv(idle = true, durationMs = 0L))
    }
}
