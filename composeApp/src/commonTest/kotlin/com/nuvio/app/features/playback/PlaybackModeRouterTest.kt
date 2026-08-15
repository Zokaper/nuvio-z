package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackModeRouterTest {

    private fun inputs(
        mode: PlaybackMode = PlaybackMode.CLASSIC,
        manualSelection: Boolean = false,
        hasCompletedLocalDownload: Boolean = false,
        reuseLastLinkEnabled: Boolean = false,
        hasValidCachedLink: Boolean = false,
    ) = PlaybackRouteInputs(
        mode = mode,
        manualSelection = manualSelection,
        hasCompletedLocalDownload = hasCompletedLocalDownload,
        reuseLastLinkEnabled = reuseLastLinkEnabled,
        hasValidCachedLink = hasValidCachedLink,
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
                    reuseLastLinkEnabled = true,
                    hasValidCachedLink = true,
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
                    reuseLastLinkEnabled = true,
                    hasValidCachedLink = true,
                ),
            )
            assertTrue(
                decision is PlaybackRouteDecision.PlayLocalDownload,
                "a completed download must win in $mode, got $decision",
            )
        }
    }

    /**
     * Reuse-last-link answers before the mode, in every mode.
     *
     * A sticky-pin rule used to sit between them so that a release the user pinned for a
     * season beat a cached link. It was withdrawn in `0.5.0-beta` - it could only be created
     * from the long-press escape hatch, and once created it silently stopped the quality
     * sheet appearing with nothing in the UI to say why. So a Streamlined user with reuse
     * enabled now reaches the cached link rather than the sheet for any episode they have
     * already watched, and the route says so out loud instead of skipping it silently.
     *
     * Pinned here so that re-adding the pin is a deliberate change to this table rather than
     * something that quietly reorders it.
     */
    @Test
    fun reuseLastLinkBeatsTheModeEverywhere() {
        PlaybackMode.entries.forEach { mode ->
            val decision = PlaybackModeRouter.decide(
                inputs(
                    mode = mode,
                    reuseLastLinkEnabled = true,
                    hasValidCachedLink = true,
                ),
            )
            assertTrue(
                decision is PlaybackRouteDecision.ReuseLastLink,
                "reuse-last-link must win in $mode, got $decision",
            )
        }
    }

    @Test
    fun reuseLastLinkNeedsBothTheSettingAndAValidLink() {
        assertTrue(
            PlaybackModeRouter.decide(
                inputs(mode = PlaybackMode.INSTANT, reuseLastLinkEnabled = true),
            ) is PlaybackRouteDecision.AutoPick,
        )
        assertTrue(
            PlaybackModeRouter.decide(
                inputs(mode = PlaybackMode.INSTANT, hasValidCachedLink = true),
            ) is PlaybackRouteDecision.AutoPick,
        )
        assertTrue(
            PlaybackModeRouter.decide(
                inputs(mode = PlaybackMode.STREAMLINED, reuseLastLinkEnabled = true),
            ) is PlaybackRouteDecision.ShowQualitySheet,
        )
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
            PlaybackRouteDecision.ReuseLastLink("r"),
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
