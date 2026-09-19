package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Watch Together means by "starved", now that the engine answers it rather than a constant.
 *
 * The case that made this necessary is [aBufferingEngineIsStarvedHoweverMuchItHasAhead]: Android's
 * load control wants five seconds before it will resume from a rebuffer, so every recovery published
 * at ~1000ms ahead was the old threshold talking over an engine that was still frozen.
 */
class PartyStarvationTest {
    private fun exo(
        readiness: PlayerEngineReadiness,
        positionMs: Long = 60_000L,
        bufferedAheadMs: Long = 0L,
        isPlaying: Boolean = false,
        isLoading: Boolean = false,
        durationMs: Long = 2_400_000L,
    ) = PlayerPlaybackSnapshot(
        isLoading = isLoading,
        isPlaying = isPlaying,
        durationMs = durationMs,
        positionMs = positionMs,
        bufferedPositionMs = positionMs + bufferedAheadMs,
        engineName = "ExoPlayer",
        engineReadiness = readiness,
    )

    private fun mpv(readiness: PlayerEngineReadiness, bufferedAheadMs: Long = 0L, isPlaying: Boolean = false) =
        exo(readiness, bufferedAheadMs = bufferedAheadMs, isPlaying = isPlaying).copy(engineName = "libmpv")

    // 1. The finding this change exists for.
    @Test fun aBufferingEngineIsStarvedHoweverMuchItHasAhead() {
        // 1200ms ahead cleared the old 1000ms threshold and published a recovery the engine had not
        // made: ExoPlayer resumes on its own rebuffer condition, which is five seconds here.
        assertTrue(partyStarvedFor(exo(PlayerEngineReadiness.Buffering, bufferedAheadMs = 1_200, isLoading = true)))
        assertTrue(partyStarvedFor(exo(PlayerEngineReadiness.Buffering, bufferedAheadMs = 4_999, isLoading = true)))
    }

    // 2. Recovery is the engine's word, not an amount of media.
    @Test fun anEngineThatSaysItIsReadyIsNotStarvedAtAnyBufferLevel() {
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.Ready, bufferedAheadMs = 120, isPlaying = true)))
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.Ready, bufferedAheadMs = 0)))
    }

    // 3. The feedback loop that cost the 2026-09-19 party its source: the host pauses a starving
    // guest, and the pause must not be what ends the starvation report.
    @Test fun aHostForcedPauseOverAnEmptyEngineIsStillStarved() {
        // Nothing here is playing or loading - the guest is obeying a pause - and it is still empty.
        val held = exo(PlayerEngineReadiness.Buffering, bufferedAheadMs = 300, isPlaying = false, isLoading = false)
        assertTrue(partyStarvedFor(held))
    }

    // 4.
    @Test fun anOrdinaryUserPauseIsHealthyRatherThanStarved() {
        // A paused ExoPlayer holding media sits in STATE_READY, which is the whole reason readiness
        // can be read while paused at all.
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.Ready, bufferedAheadMs = 30_000)))
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.Ready, bufferedAheadMs = 0)))
    }

    // 5. A correction is a decided instant, not a stall - reporting it is what held the party on
    // every seek before the hold was taken out of the signal.
    @Test fun aBarrierOrCorrectiveSeekIsNeverStarvation() {
        val seeking = exo(PlayerEngineReadiness.Buffering, bufferedAheadMs = 0, isLoading = true)
        assertTrue(partyStarvedFor(seeking, holdingForBarrier = false), "the same snapshot without a hold")
        assertFalse(partyStarvedFor(seeking, holdingForBarrier = true))
    }

    @Test fun aClientWithNoUsableSourceYetIsNotStarved() {
        // Startup: prepared, buffering, nothing playable, no duration. The start gate waits for this
        // member; the stall guard must not also hold the party open for it.
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.Buffering, durationMs = 0, isLoading = true)))
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.NoSource, isLoading = true)))
    }

    // 6.
    @Test fun libmpvPausedForCacheIsStarved() {
        assertTrue(partyStarvedFor(mpv(PlayerEngineReadiness.Buffering, bufferedAheadMs = 1_100)))
    }

    // 7.
    @Test fun libmpvWithItsCacheRecoveredIsNotStarved() {
        assertFalse(partyStarvedFor(mpv(PlayerEngineReadiness.Ready, bufferedAheadMs = 400, isPlaying = true)))
    }

    /**
     * The fallback, for an engine that cannot answer - iOS today.
     *
     * Unchanged behaviour, deliberately: this is the old rule, kept where it is the only rule
     * available and nowhere else.
     */
    @Test fun anEngineThatCannotAnswerFallsBackToBufferOccupancy() {
        assertTrue(partyStarvedFor(exo(PlayerEngineReadiness.Unknown, bufferedAheadMs = 999)))
        assertFalse(partyStarvedFor(exo(PlayerEngineReadiness.Unknown, bufferedAheadMs = 1_000)))
        // No buffer position reported at all reads as not starved: a false `true` would hold a
        // healthy party for a member that is fine.
        assertFalse(
            partyStarvedFor(
                PlayerPlaybackSnapshot(durationMs = 2_400_000L, positionMs = 0L, bufferedPositionMs = 0L),
            ),
        )
    }
}
