package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stream route's covering rules.
 *
 * Every case here is a state the app could actually reach, and the ones that matter most are
 * the terminal ones: a surface the user cannot read and cannot leave is the failure this
 * function exists to make impossible.
 */
class StreamRouteSurfaceTest {

    private fun inputs(
        isClassic: Boolean = false,
        isManualLaunch: Boolean = false,
        manualSourceListRequested: Boolean = false,
        isRouteCurrent: Boolean = true,
        hasNavigatedAway: Boolean = false,
        hasArmedFailureChain: Boolean = false,
        isQualitySheetRoute: Boolean = false,
        qualitySheetDismissed: Boolean = false,
        isAutoPickRoute: Boolean = false,
        isStreamlinedPlaybackStarting: Boolean = false,
        awaitingUserAnswer: Boolean = false,
    ) = StreamRouteSurfaceInputs(
        isClassic = isClassic,
        isManualLaunch = isManualLaunch,
        manualSourceListRequested = manualSourceListRequested,
        isRouteCurrent = isRouteCurrent,
        hasNavigatedAway = hasNavigatedAway,
        hasArmedFailureChain = hasArmedFailureChain,
        isQualitySheetRoute = isQualitySheetRoute,
        qualitySheetDismissed = qualitySheetDismissed,
        isAutoPickRoute = isAutoPickRoute,
        isStreamlinedPlaybackStarting = isStreamlinedPlaybackStarting,
        awaitingUserAnswer = awaitingUserAnswer,
    )

    @Test
    fun classicNeverCoversItsList() {
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isClassic = true, isAutoPickRoute = true)),
        )
    }

    @Test
    fun streamlinedShowsTheSheetBeforeATierIsPicked() {
        assertEquals(
            StreamRouteSurface.QualitySheet,
            streamRouteSurface(inputs(isQualitySheetRoute = true)),
        )
    }

    @Test
    fun theOverlayOwnsTheScreenOnceATierIsPicked() {
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(
                inputs(
                    isQualitySheetRoute = true,
                    qualitySheetDismissed = true,
                    isStreamlinedPlaybackStarting = true,
                ),
            ),
        )
    }

    @Test
    fun instantCoversTheListFromTheFirstFrame() {
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(inputs(isAutoPickRoute = true)),
        )
    }

    @Test
    fun everyBailOutUncoversTheList() {
        // Each of these exists because the app gave up on choosing for the user. Leaving
        // anything over the list they now have to read would be worse than never covering it.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isAutoPickRoute = true,
                    isStreamlinedPlaybackStarting = true,
                    manualSourceListRequested = true,
                ),
            ),
        )
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isAutoPickRoute = true, awaitingUserAnswer = true)),
        )
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isQualitySheetRoute = true, isManualLaunch = true)),
        )
    }

    @Test
    fun backingOutOfThePlayerLandsOnSomethingTheUserCanActOn() {
        // The defect this whole function was written for. A mode with a failure chain leaves
        // StreamRoute on the back stack, so the system Back gesture pops the player straight
        // onto it. Every covering condition was false and the opaque hand-off surface was
        // still painting: a blank screen, with a fully tappable source list underneath it.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isQualitySheetRoute = true,
                    qualitySheetDismissed = true,
                    isStreamlinedPlaybackStarting = true,
                    hasNavigatedAway = true,
                    hasArmedFailureChain = false,
                    isRouteCurrent = true,
                ),
            ),
        )
    }

    @Test
    fun theQualitySheetIsNeverRedisplayedAfterAPlay() {
        // Even if the sheet's dismissal flag were somehow lost, having handed off to the
        // player means the question has been answered. Re-asking it on the way back would
        // read as the app forgetting what the user just did.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isQualitySheetRoute = true,
                    qualitySheetDismissed = false,
                    hasNavigatedAway = true,
                ),
            ),
        )
    }

    @Test
    fun aRetryStillGetsItsOverlay() {
        // The other half of the rule above: when the chain is still armed the user has *not*
        // finished, they are between two candidates. Uncovering there would show the source
        // list mid-retry, which is the thing the overlay exists to prevent.
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(
                inputs(
                    isStreamlinedPlaybackStarting = true,
                    hasNavigatedAway = false,
                    hasArmedFailureChain = true,
                ),
            ),
        )
    }

    @Test
    fun leavingForThePlayerDoesNotFlashTheList() {
        // While the hand-off is in flight this route is no longer current. Uncovering then
        // would show the list for the length of the outgoing transition - the exact thing
        // the opaque surface is for.
        assertEquals(
            StreamRouteSurface.HandOff,
            streamRouteSurface(
                inputs(
                    isStreamlinedPlaybackStarting = true,
                    isRouteCurrent = false,
                    hasNavigatedAway = true,
                    hasArmedFailureChain = false,
                ),
            ),
        )
    }

    @Test
    fun theOnlyBlankFrameIsBeforeADecisionExists() {
        // Non-Classic, nothing decided yet, nothing handed off: the route is waiting for
        // `PlaybackModeRouter` and the list underneath is not the answer.
        assertEquals(
            StreamRouteSurface.HandOff,
            streamRouteSurface(inputs()),
        )
    }
}
