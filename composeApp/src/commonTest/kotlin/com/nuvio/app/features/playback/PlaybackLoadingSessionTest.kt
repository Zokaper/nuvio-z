package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lifetime rules. Every one of these is a fault the maintainer actually reported: the screen
 * re-entering at the hand-off, the screen re-entering on a failover, and the escape hatch
 * restarting its clock each time it did.
 */
class PlaybackLoadingSessionTest {

    private fun open(token: Long = 1L) = PlaybackLoadingSessions.open(
        token = token,
        step = PlaybackProgressStep.ChoosingSource,
        artwork = "art",
        logo = "logo",
        title = "The Secret Woman",
    )

    @Test
    fun `a failover is a revision, not a new session`() {
        val opened = open()
        val retried = PlaybackLoadingSessions.revise(
            current = opened,
            token = opened.token,
            state = opened.state.copy(attempt = 2, step = PlaybackProgressStep.StartingPlayback),
        )
        assertNotNull(retried)
        assertEquals(opened.token, retried.token)
        assertEquals(2, retried.state.attempt)
        // The screen must not re-enter: this is what "it should just say attempt 2 of 3" means.
        assertFalse(PlaybackLoadingSessions.isEntering(opened, retried))
    }

    @Test
    fun `handing off to the player changes nothing the user can see`() {
        val opened = open()
        val handed = PlaybackLoadingSessions.handOff(opened, opened.token)
        assertNotNull(handed)
        assertTrue(handed.handedOff)
        assertEquals(opened.artwork, handed.artwork)
        assertEquals(opened.logo, handed.logo)
        assertEquals(opened.title, handed.title)
        assertEquals(opened.state, handed.state)
        assertFalse(PlaybackLoadingSessions.isEntering(opened, handed))
    }

    @Test
    fun `only a new token is an entrance`() {
        val first = open(token = 1L)
        assertTrue(PlaybackLoadingSessions.isEntering(null, first))
        assertTrue(PlaybackLoadingSessions.isEntering(first, open(token = 2L)))
        assertFalse(PlaybackLoadingSessions.isEntering(first, first))
        assertFalse(PlaybackLoadingSessions.isEntering(first, null))
    }

    @Test
    fun `a publish quoting a superseded token is ignored`() {
        // Both the route and the player publish, and during the hand-off both are briefly alive.
        val current = open(token = 2L)
        val stale = PlaybackLoadingSessions.revise(
            current = current,
            token = 1L,
            state = current.state.copy(step = PlaybackProgressStep.FindingSources),
        )
        assertEquals(current, stale)
        assertEquals(current, PlaybackLoadingSessions.handOff(current, 1L))
    }

    @Test
    fun `the escape clock belongs to the session and survives a retry`() {
        val opened = open()
        assertFalse(opened.offersManualEscape)
        val waited = PlaybackLoadingSessions.tick(opened, MANUAL_ESCAPE_DELAY_MS)
        assertNotNull(waited)
        assertTrue(waited.offersManualEscape)
        // The failover that used to restart this clock now leaves it exactly where it was.
        val retried = PlaybackLoadingSessions.revise(
            current = waited,
            token = waited.token,
            state = waited.state.copy(attempt = 2),
        )
        assertNotNull(retried)
        assertEquals(MANUAL_ESCAPE_DELAY_MS, retried.elapsedMs)
        assertTrue(retried.offersManualEscape)
    }

    @Test
    fun `a failed attempt opens the escape hatch before the clock does`() {
        val opened = open()
        val retried = PlaybackLoadingSessions.revise(
            current = opened,
            token = opened.token,
            state = opened.state.copy(attempt = 2),
        )
        assertNotNull(retried)
        assertTrue(retried.offersManualEscape)
        assertTrue(retried.renderedState.offerManualEscape)
    }

    @Test
    fun `the backdrop never animates and the band carries the entrance`() {
        // ⚠ The surface is at rest from the first frame, at every point of the entrance. Fading a
        // full-screen layer forced an offscreen composite per frame and was the reported stutter;
        // asserting the identity is what stops it coming back.
        for (progress in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals(1f, PlaybackLoadingMotion.surfaceAlpha(progress), "alpha at $progress")
            assertEquals(1f, PlaybackLoadingMotion.surfaceScale(progress), "scale at $progress")
        }
        assertEquals(0f, PlaybackLoadingMotion.bandAlpha(0f))
        assertEquals(1f, PlaybackLoadingMotion.bandAlpha(1f))
        assertTrue(PlaybackLoadingMotion.bandAlpha(0.5f) > 0f)
    }

    @Test
    fun `first frame needs more than the engine having opened the media`() {
        // Just dropping isLoading used to end the surface, which faded it onto a black plane.
        assertFalse(
            PlaybackHandover.hasFirstFrame(
                isLoading = false, isPlaying = false, positionMs = 0L,
                videoWidth = 0, videoHeight = 0,
            ),
        )
        assertTrue(
            PlaybackHandover.hasFirstFrame(
                isLoading = false, isPlaying = false, positionMs = 0L,
                videoWidth = 1920, videoHeight = 1080,
            ),
        )
        // Audio-only, and engines that never report dimensions, still hand over.
        assertTrue(
            PlaybackHandover.hasFirstFrame(
                isLoading = false, isPlaying = true, positionMs = 1_000L,
                videoWidth = 0, videoHeight = 0,
            ),
        )
        // A pending seek reports a position immediately; loading still means no frame.
        assertFalse(
            PlaybackHandover.hasFirstFrame(
                isLoading = true, isPlaying = true, positionMs = 600_000L,
                videoWidth = 1920, videoHeight = 1080,
            ),
        )
    }
}
