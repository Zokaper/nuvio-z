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
        hasNavigatedAway: Boolean = false,
        isQualitySheetRoute: Boolean = false,
        qualitySheetDismissed: Boolean = false,
        isAutoPickRoute: Boolean = false,
        isStreamlinedPlaybackStarting: Boolean = false,
        awaitingUserAnswer: Boolean = false,
    ) = StreamRouteSurfaceInputs(
        isClassic = isClassic,
        isManualLaunch = isManualLaunch,
        manualSourceListRequested = manualSourceListRequested,
        hasNavigatedAway = hasNavigatedAway,
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
    fun comingBackFromThePlayerStaysCoveredWhileTheRouteLeaves() {
        // Streamlined must not land the user on the source list - this route is the mechanism,
        // not a destination they asked for, and uncovering here also re-fetched the list
        // because `consumeAutoPlay` clears the request key. One Back put you on a "source
        // loading" screen and it took a second press to actually leave.
        //
        // So the surface stays covered and `entry<StreamRoute>` pops itself to details. The
        // guarantee that this is never a *resting* state lives in the route, not here.
        assertEquals(
            StreamRouteSurface.HandOff,
            streamRouteSurface(
                inputs(
                    isQualitySheetRoute = true,
                    qualitySheetDismissed = true,
                    isStreamlinedPlaybackStarting = true,
                    hasNavigatedAway = true,
                ),
            ),
        )
    }

    @Test
    fun theRoutesFallbackAfterAFailedPopUncoversTheList() {
        // The other half of that guarantee, and the reason the blank screen cannot come back:
        // if the pop to details no-ops, the route sets `manualSourceListRequested`, and rule 1
        // outranks everything.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isStreamlinedPlaybackStarting = true,
                    hasNavigatedAway = true,
                    manualSourceListRequested = true,
                ),
            ),
        )
    }

    @Test
    fun theQualitySheetIsNeverRedisplayedAfterAPlay() {
        // Even if the sheet's dismissal flag were somehow lost, having handed off to the
        // player means the question has been answered. Re-asking it on the way back would
        // read as the app forgetting what the user just did. The hand-off rule outranks the
        // sheet for exactly this reason - what matters is that it is not `QualitySheet`.
        assertEquals(
            StreamRouteSurface.HandOff,
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
        // A retry clears the hand-off flag before relaunching - that is what the failover
        // signal is for - so the overlay comes back and the user sees the attempt counter
        // rather than the list they are not choosing from.
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(
                inputs(
                    isStreamlinedPlaybackStarting = true,
                    hasNavigatedAway = false,
                ),
            ),
        )
    }

    @Test
    fun leavingForThePlayerDoesNotFlashTheList() {
        // The outgoing direction of the same rule: uncovering during the transition would show
        // the list for its duration, which is the exact thing the opaque surface is for.
        assertEquals(
            StreamRouteSurface.HandOff,
            streamRouteSurface(
                inputs(isStreamlinedPlaybackStarting = true, hasNavigatedAway = true),
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
