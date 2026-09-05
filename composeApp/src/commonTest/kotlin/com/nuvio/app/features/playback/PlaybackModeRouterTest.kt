package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackModeRouterTest {

    private fun inputs(
        mode: PlaybackMode = PlaybackMode.CLASSIC,
        manualSelection: Boolean = false,
        hasCompletedLocalDownload: Boolean = false,
        isPartyResolvePlayback: Boolean = false,
    ) = PlaybackRouteInputs(
        mode = mode,
        manualSelection = manualSelection,
        hasCompletedLocalDownload = hasCompletedLocalDownload,
        isPartyResolvePlayback = isPartyResolvePlayback,
    )

    @Test
    fun classicShowsTheSourceList() {
        val decision = PlaybackModeRouter.decide(inputs(mode = PlaybackMode.CLASSIC))
        assertTrue(decision is PlaybackRouteDecision.ShowSourceList)
    }

    @Test
    fun streamlinedShowsTheQualitySheet() {
        val decision = PlaybackModeRouter.decide(inputs(mode = PlaybackMode.STREAMLINED))
        assertTrue(decision is PlaybackRouteDecision.ShowQualitySheet)
    }

    @Test
    fun instantAutoPicks() {
        val decision = PlaybackModeRouter.decide(inputs(mode = PlaybackMode.INSTANT))
        assertTrue(decision is PlaybackRouteDecision.AutoPick)
    }

    @Test
    fun manualSelectionWinsInEveryMode() {
        PlaybackMode.entries.forEach { mode ->
            val decision = PlaybackModeRouter.decide(
                inputs(
                    mode = mode,
                    manualSelection = true,
                    hasCompletedLocalDownload = true,
                ),
            )
            assertTrue(
                decision is PlaybackRouteDecision.ShowSourceList,
                "manual selection must reach the source list in $mode, got $decision",
            )
        }
    }

    @Test
    fun localDownloadBeatsEverythingBelowIt() {
        PlaybackMode.entries.forEach { mode ->
            val decision = PlaybackModeRouter.decide(
                inputs(
                    mode = mode,
                    hasCompletedLocalDownload = true,
                ),
            )
            assertTrue(
                decision is PlaybackRouteDecision.PlayLocalDownload,
                "a completed download must win in $mode, got $decision",
            )
        }
    }

    @Test
    fun partyResolveBeatsLocalDownloadAndPlaybackMode() {
        PlaybackMode.entries.forEach { mode ->
            val decision = PlaybackModeRouter.decide(
                inputs(
                    mode = mode,
                    hasCompletedLocalDownload = true,
                    isPartyResolvePlayback = true,
                ),
            )
            assertEquals(
                PlaybackRouteDecision.AutoPick("party member resolving the host's source"),
                decision,
                "party playback must resolve the host source in $mode",
            )
        }
    }

    /**
     * Every branch survives a save/restore round trip.
     *
     * The decision outlives its composition - a mode with a failure chain keeps `StreamRoute`
     * on the back stack while the player is open - and an unknown key answers null rather
     * than guessing, so a branch dropped from [PlaybackRouteDecision.fromKey] would silently
     * change which selection mechanism runs on the way back.
     */
    @Test
    fun everyDecisionSurvivesAKeyRoundTrip() {
        val decisions = listOf(
            PlaybackRouteDecision.ShowSourceList("r"),
            PlaybackRouteDecision.PlayLocalDownload("r"),
            PlaybackRouteDecision.ShowQualitySheet("r"),
            PlaybackRouteDecision.AutoPick("r"),
        )
        decisions.forEach { decision ->
            assertEquals(
                decision,
                PlaybackRouteDecision.fromKey(decision.key, decision.reason),
                "${decision.key} did not survive the round trip",
            )
        }
        assertEquals(null, PlaybackRouteDecision.fromKey("sticky_pin", "r"))
        assertEquals(null, PlaybackRouteDecision.fromKey(null, "r"))
    }

    @Test
    fun existingInstallsDefaultToClassic() {
        assertEquals(PlaybackMode.CLASSIC, PlaybackMode.Default)
        assertEquals(PlaybackMode.CLASSIC, PlaybackMode.fromStorage(null))
        assertEquals(PlaybackMode.CLASSIC, PlaybackMode.fromStorage(""))
        assertEquals(PlaybackMode.CLASSIC, PlaybackMode.fromStorage("nonsense"))
        assertEquals(PlaybackMode.INSTANT, PlaybackMode.fromStorage("instant"))
        assertEquals(PlaybackMode.STREAMLINED, PlaybackMode.fromStorage(" STREAMLINED "))
    }
}
