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
        hasRememberedBand: Boolean = false,
        isAutoPickRoute: Boolean = false,
        isAutoPlaybackStarting: Boolean = false,
        awaitingUserAnswer: Boolean = false,
    ) = StreamRouteSurfaceInputs(
        isClassic = isClassic,
        isManualLaunch = isManualLaunch,
        manualSourceListRequested = manualSourceListRequested,
        hasNavigatedAway = hasNavigatedAway,
        isQualitySheetRoute = isQualitySheetRoute,
        qualitySheetDismissed = qualitySheetDismissed,
        hasRememberedBand = hasRememberedBand,
        isAutoPickRoute = isAutoPickRoute,
        isAutoPlaybackStarting = isAutoPlaybackStarting,
        awaitingUserAnswer = awaitingUserAnswer,
    )

    @Test
    fun classicNeverCoversItsList() {
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isClassic = true, isAutoPlaybackStarting = true)),
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
                    isAutoPlaybackStarting = true,
                ),
            ),
        )
    }

    @Test
    fun aRememberedBandAnswersTheSheetInsteadOfDrawingIt() {
        // The sheet's own condition is still true here - the route is `ShowQualitySheet` and
        // nothing has been dismissed - so without this rule the grid appeared for the frames
        // before the auto-selection landed, then vanished. A question flashed and withdrawn is
        // worse than either asking or not.
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(inputs(isQualitySheetRoute = true, hasRememberedBand = true)),
        )
    }

    @Test
    fun aMissedBandGivesTheSheetBack() {
        // This episode has no release in the remembered band. `rememberedOption` answers null
        // rather than substituting one, the route clears the flag, and the question is live
        // again - the alternative is silently playing a band the user never picked, on a path
        // where there is nothing on screen to disagree with.
        assertEquals(
            StreamRouteSurface.QualitySheet,
            streamRouteSurface(inputs(isQualitySheetRoute = true, hasRememberedBand = false)),
        )
    }

    @Test
    fun aRememberedBandStillLosesToEveryBailOut() {
        // It is a shortcut through a question, not a new way to cover the list. Rule 1 outranks
        // it exactly as it outranks the sheet.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isQualitySheetRoute = true,
                    hasRememberedBand = true,
                    manualSourceListRequested = true,
                ),
            ),
        )
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(isQualitySheetRoute = true, hasRememberedBand = true, isManualLaunch = true),
            ),
        )
        // And a hand-off still stays covered on the way back out.
        assertEquals(
            StreamRouteSurface.HandOff,
            streamRouteSurface(
                inputs(isQualitySheetRoute = true, hasRememberedBand = true, hasNavigatedAway = true),
            ),
        )
    }

    @Test
    fun theEscapeHatchWaitsForAReasonToExist() {
        // Not offered from the first frame: the happy path resolves in well under a second, and
        // an escape hatch shown before anything has gone wrong invites the user out of a flow
        // that was about to work.
        assertEquals(false, shouldOfferManualEscape(attempt = 1, elapsedMs = 0L))
        // Either signal opens it - a failure seen...
        assertEquals(true, shouldOfferManualEscape(attempt = 2, elapsedMs = 0L))
        // ...or a wait long enough that the wait itself is the problem.
        assertEquals(true, shouldOfferManualEscape(attempt = 1, elapsedMs = MANUAL_ESCAPE_DELAY_MS))
        assertEquals(false, shouldOfferManualEscape(attempt = 1, elapsedMs = MANUAL_ESCAPE_DELAY_MS - 1))
    }

    @Test
    fun everyBailOutUncoversTheList() {
        // Each of these exists because the app gave up on choosing for the user. Leaving
        // anything over the list they now have to read would be worse than never covering it.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isAutoPlaybackStarting = true,
                    manualSourceListRequested = true,
                ),
            ),
        )
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isAutoPlaybackStarting = true, awaitingUserAnswer = true)),
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
                    isAutoPlaybackStarting = true,
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
                    isAutoPlaybackStarting = true,
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
                    isAutoPlaybackStarting = true,
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
                inputs(isAutoPlaybackStarting = true, hasNavigatedAway = true),
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

    @Test
    fun instantIsCoveredFromTheStart() {
        // Instant has no sheet, so nothing else in this table matched it: before this rule an
        // Instant play fell all the way to the final `HandOff` - an opaque, empty,
        // pointer-consuming screen resting over a fully tappable source list. That is the exact
        // fault this function was written to make impossible, and it would have come back with
        // the mode.
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(inputs(isAutoPickRoute = true)),
        )
    }

    @Test
    fun instantStaysCoveredOnceItHasChosen() {
        // Choosing sets `qualitySheetDismissed` alongside `autoPlaybackStarting`, so the rule
        // above stops matching and the overlay comes from the shared starting rule instead.
        // Both answers must be the overlay or the screen flickers at the moment of the pick.
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(
                inputs(
                    isAutoPickRoute = true,
                    qualitySheetDismissed = true,
                    isAutoPlaybackStarting = true,
                ),
            ),
        )
    }

    @Test
    fun instantsMeteredQuestionIsAskedOverTheOverlay() {
        // `awaitingUserAnswer` uncovers the list so a dialog has something usable behind it,
        // which is right for a question whose dismissal drops the play. The metered question is
        // not one of those - dismissing it answers Data saver and playback continues - so
        // uncovering would flash the Classic source list the mode exists to avoid. Instant's own
        // rule sits above `awaitingUserAnswer` for exactly this.
        assertEquals(
            StreamRouteSurface.ProgressOverlay,
            streamRouteSurface(inputs(isAutoPickRoute = true, awaitingUserAnswer = true)),
        )
    }

    @Test
    fun instantStillLosesToEveryBailOut() {
        // Same standing as the remembered band: a way of skipping a question, never a new way
        // to cover the list.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(isAutoPickRoute = true, manualSourceListRequested = true),
            ),
        )
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isAutoPickRoute = true, isManualLaunch = true)),
        )
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(inputs(isAutoPickRoute = true, isClassic = true)),
        )
        // And a hand-off outranks it in both directions, so backing out of the player does not
        // flash the list on the way to details.
        assertEquals(
            StreamRouteSurface.HandOff,
            streamRouteSurface(inputs(isAutoPickRoute = true, hasNavigatedAway = true)),
        )
    }

    @Test
    fun instantsGiveUpUncoversTheList() {
        // `giveUpToSourceList` sets both flags. The first is what stops Instant's rule matching;
        // the second is what uncovers. Asserting the pair together is the guard against someone
        // later setting only one of them.
        assertEquals(
            StreamRouteSurface.SourceList,
            streamRouteSurface(
                inputs(
                    isAutoPickRoute = true,
                    qualitySheetDismissed = true,
                    manualSourceListRequested = true,
                ),
            ),
        )
    }
}
