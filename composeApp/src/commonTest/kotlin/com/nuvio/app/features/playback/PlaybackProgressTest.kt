package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackProgressTest {

    private fun inputs(
        isLoadingSources: Boolean = false,
        hasChosenSource: Boolean = false,
        isResolvingLink: Boolean = false,
        attempt: Int = 1,
    ) = PlaybackProgressInputs(
        isLoadingSources = isLoadingSources,
        hasChosenSource = hasChosenSource,
        isResolvingLink = isResolvingLink,
        attempt = attempt,
    )

    @Test
    fun stillFetchingReadsAsFindingSources() {
        assertEquals(
            PlaybackProgressStep.FindingSources,
            PlaybackProgress.step(inputs(isLoadingSources = true)),
        )
    }

    @Test
    fun candidatesInButNothingPickedYetReadsAsChoosing() {
        assertEquals(
            PlaybackProgressStep.ChoosingSource,
            PlaybackProgress.step(inputs(isLoadingSources = false, hasChosenSource = false)),
        )
    }

    @Test
    fun aPickedSourceWithNoResolveInFlightReadsAsStarting() {
        assertEquals(
            PlaybackProgressStep.StartingPlayback,
            PlaybackProgress.step(inputs(hasChosenSource = true)),
        )
    }

    @Test
    fun resolvingWinsOverAStillLoadingAddon() {
        // A slow addon can leave isAnyLoading true long after the pick, while the debrid mint
        // is the thing actually being waited on. Reporting "looking for sources" there would
        // name the wrong wait.
        assertEquals(
            PlaybackProgressStep.ResolvingLink,
            PlaybackProgress.step(inputs(isLoadingSources = true, hasChosenSource = true, isResolvingLink = true)),
        )
    }

    // The `isVisible` cases that used to live here moved to StreamRouteSurfaceTest along
    // with the function itself. They only ever covered whether the *overlay* was drawn, which
    // left the opaque hand-off surface underneath it untested - and that surface is what the
    // user was actually left staring at after backing out of the player.
}
