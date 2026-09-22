package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeTransitionTest {
    @Test
    fun automaticRequestsCountDownAndAcceptManualPromotion() {
        val automatic = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 4L,
            currentVideoId = "episode-1",
            targetVideoId = "episode-2",
            origin = PlayerNextEpisodeOrigin.AUTOMATIC,
        )
        assertTrue(automatic.shouldCountDown())
        assertTrue(automatic.canAcceptManualTap())
        val promoted = PlayerNextEpisodeTransitionPolicy.promoteToManual(automatic)
        assertFalse(promoted.shouldCountDown())
        assertFalse(promoted.canAcceptManualTap())
        assertEquals(automatic.requestId, promoted.requestId)
    }

    @Test
    fun repeatedManualRequestCannotPromoteOrRestartItself() {
        val manual = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 1L,
            currentVideoId = "episode-1",
            targetVideoId = "episode-2",
            origin = PlayerNextEpisodeOrigin.MANUAL,
        )
        assertEquals(manual, PlayerNextEpisodeTransitionPolicy.promoteToManual(manual))
        assertFalse(manual.canAcceptManualTap())
    }

    @Test
    fun staleResultsCannotUpdateTheCurrentRequest() {
        val current = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 8L,
            currentVideoId = "episode-1",
            targetVideoId = "episode-3",
            origin = PlayerNextEpisodeOrigin.MANUAL,
        )
        assertEquals(
            current,
            PlayerNextEpisodeTransitionPolicy.update(
                state = current,
                requestId = 8L,
                targetVideoId = "episode-2",
                phase = PlayerNextEpisodePhase.STARTING,
            ),
        )
    }

    @Test
    fun matchingResultAdvancesWithoutChangingIdentity() {
        val resolving = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 2L,
            currentVideoId = "episode-1",
            targetVideoId = "episode-2",
            origin = PlayerNextEpisodeOrigin.AUTOMATIC,
        )
        val countdown = PlayerNextEpisodeTransitionPolicy.update(
            state = resolving,
            requestId = resolving.requestId,
            targetVideoId = "episode-2",
            phase = PlayerNextEpisodePhase.COUNTDOWN,
            sourceName = "Addon",
            countdownSeconds = 3,
        )
        assertEquals(PlayerNextEpisodePhase.COUNTDOWN, countdown.phase)
        assertEquals("Addon", countdown.sourceName)
        assertEquals(3, countdown.countdownSeconds)
        assertEquals(resolving.requestId, countdown.requestId)
    }

    @Test
    fun playerSwapCannotBeTriggeredTwice() {
        val resolving = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 2L,
            currentVideoId = "episode-1",
            targetVideoId = "episode-2",
            origin = PlayerNextEpisodeOrigin.AUTOMATIC,
        )
        val starting = PlayerNextEpisodeTransitionPolicy.update(
            state = resolving,
            requestId = resolving.requestId,
            targetVideoId = "episode-2",
            phase = PlayerNextEpisodePhase.STARTING,
        )
        assertFalse(starting.canAcceptManualTap())
    }

    @Test
    fun cancellationInvalidatesIdentityButKeepsTheRequestSequence() {
        val resolving = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 9L,
            currentVideoId = "episode-1",
            targetVideoId = "episode-2",
            origin = PlayerNextEpisodeOrigin.AUTOMATIC,
        )
        val cancelled = PlayerNextEpisodeTransitionPolicy.cancel(resolving)
        assertEquals(10L, cancelled.requestId)
        assertEquals(PlayerNextEpisodePhase.IDLE, cancelled.phase)
        assertFalse(cancelled.isRequest(10L, "episode-2"))
        assertEquals(null, cancelled.targetVideoId)
    }

    @Test
    fun dismissalSuppressesOnlyTheEpisodeThatWasDismissed() {
        assertTrue(PlayerNextEpisodeTransitionPolicy.isPromptSuppressed("episode-1", "episode-1"))
        assertFalse(PlayerNextEpisodeTransitionPolicy.isPromptSuppressed("episode-1", "episode-2"))
        assertFalse(PlayerNextEpisodeTransitionPolicy.isPromptSuppressed(null, "episode-1"))
    }

    @Test
    fun automaticDownloadedPlaybackUsesTheSameCountdownPolicy() {
        val automatic = PlayerNextEpisodeTransitionPolicy.begin(
            previousRequestId = 0L,
            currentVideoId = "episode-1",
            targetVideoId = "downloaded-episode-2",
            origin = PlayerNextEpisodeOrigin.AUTOMATIC,
        )
        assertTrue(automatic.shouldCountDown())
        assertFalse(PlayerNextEpisodeTransitionPolicy.promoteToManual(automatic).shouldCountDown())
    }

    @Test
    fun manualSelectionResultsRetainTheExactFailureReason() {
        val timeout = PlayerNextEpisodeResolutionResult.ManualSelectionRequired(
            PlayerNextEpisodeFailureReason.TIMED_OUT,
        )
        val empty = PlayerNextEpisodeResolutionResult.ManualSelectionRequired(
            PlayerNextEpisodeFailureReason.EMPTY_RESULTS,
        )
        val unsafe = PlayerNextEpisodeResolutionResult.ManualSelectionRequired(
            PlayerNextEpisodeFailureReason.NO_SAFE_CANDIDATE,
        )
        assertEquals(PlayerNextEpisodeFailureReason.TIMED_OUT, timeout.reason)
        assertEquals(PlayerNextEpisodeFailureReason.EMPTY_RESULTS, empty.reason)
        assertEquals(PlayerNextEpisodeFailureReason.NO_SAFE_CANDIDATE, unsafe.reason)
    }
}
