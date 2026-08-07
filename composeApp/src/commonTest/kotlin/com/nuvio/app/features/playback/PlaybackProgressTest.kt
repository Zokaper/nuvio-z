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

    @Test
    fun instantCoversTheSourceList() {
        assertTrue(
            PlaybackProgress.isVisible(
                isAutoPickRoute = true,
                isStreamlinedPlaybackStarting = false,
                manualSourceListRequested = false,
                awaitingMeteredChoice = false,
                hasNavigatedAway = false,
            ),
        )
    }

    @Test
    fun streamlinedCoversTheListOnlyAfterATierIsPicked() {
        assertFalse(
            PlaybackProgress.isVisible(
                isAutoPickRoute = false,
                isStreamlinedPlaybackStarting = false,
                manualSourceListRequested = false,
                awaitingMeteredChoice = false,
                hasNavigatedAway = false,
            ),
        )
        assertTrue(
            PlaybackProgress.isVisible(
                isAutoPickRoute = false,
                isStreamlinedPlaybackStarting = true,
                manualSourceListRequested = false,
                awaitingMeteredChoice = false,
                hasNavigatedAway = false,
            ),
        )
    }

    @Test
    fun everyBailOutToTheSourceListUncoversIt() {
        // This is the regression guard that matters: each of these paths exists because the
        // app gave up on choosing for the user, and leaving a spinner over the list they now
        // have to read would be worse than never covering it.
        assertFalse(
            PlaybackProgress.isVisible(
                isAutoPickRoute = true,
                isStreamlinedPlaybackStarting = true,
                manualSourceListRequested = true,
                awaitingMeteredChoice = false,
                hasNavigatedAway = false,
            ),
        )
        assertFalse(
            PlaybackProgress.isVisible(
                isAutoPickRoute = true,
                isStreamlinedPlaybackStarting = false,
                manualSourceListRequested = false,
                awaitingMeteredChoice = true,
                hasNavigatedAway = false,
            ),
        )
        assertFalse(
            PlaybackProgress.isVisible(
                isAutoPickRoute = true,
                isStreamlinedPlaybackStarting = false,
                manualSourceListRequested = false,
                awaitingMeteredChoice = false,
                hasNavigatedAway = true,
            ),
        )
    }

    @Test
    fun classicNeverShowsTheOverlay() {
        assertFalse(
            PlaybackProgress.isVisible(
                isAutoPickRoute = false,
                isStreamlinedPlaybackStarting = false,
                manualSourceListRequested = false,
                awaitingMeteredChoice = false,
                hasNavigatedAway = false,
            ),
        )
    }

    @Test
    fun playbackHavingStartedHidesTheOverlay() {
        // The regression this guards: Instant deliberately does not pop StreamRoute when it
        // navigates to the player, so the route stays on the back stack with isAutoPickRoute
        // and hasChosenSource both still true. Without a hand-off flag, backing out of the
        // player landed on an opaque overlay with nothing to interact with.
        assertFalse(
            PlaybackProgress.isVisible(
                isAutoPickRoute = true,
                isStreamlinedPlaybackStarting = true,
                manualSourceListRequested = false,
                awaitingMeteredChoice = false,
                hasNavigatedAway = true,
            ),
        )
    }
}
