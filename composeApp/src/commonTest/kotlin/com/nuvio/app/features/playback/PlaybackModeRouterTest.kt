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
     * A host choosing what the party will watch is routed by that host's own playback mode.
     *
     * These are the exact inputs `WatchPartyLobbyDestination` now builds for a `SELECT_SOURCE`
     * launch. It used to set `manualSelection = true` for this purpose, which is the first thing
     * [PlaybackModeRouter.decide] tests, so the host's mode never got a say and a Streamlined or
     * Instant host pressing "Choose a source" was handed Classic's release list.
     *
     * There is deliberately no party-selection input on [PlaybackRouteInputs]. The interaction
     * model *is* the mode, so the fix is to stop short-circuiting the answer rather than to give
     * Watch Together a second reading of the same three modes.
     */
    @Test
    fun aPartyHostChoosingASourceIsRoutedByTheHostsMode() {
        fun hostChoosing(mode: PlaybackMode) = PlaybackModeRouter.decide(
            inputs(
                mode = mode,
                manualSelection = false,
                // Completed downloads are consumed before StreamRoute is created, and a party
                // cannot be handed a local file anyway.
                hasCompletedLocalDownload = false,
                isPartyResolvePlayback = false,
            ),
        )
        assertTrue(
            hostChoosing(PlaybackMode.CLASSIC) is PlaybackRouteDecision.ShowSourceList,
            "a Classic host reads the release list",
        )
        assertTrue(
            hostChoosing(PlaybackMode.STREAMLINED) is PlaybackRouteDecision.ShowQualitySheet,
            "a Streamlined host gets the quality step, not Classic's list",
        )
        assertTrue(
            hostChoosing(PlaybackMode.INSTANT) is PlaybackRouteDecision.AutoPick,
            "an Instant host is asked nothing",
        )
    }

    /**
     * A guest never gets a chooser, whatever the guest's own mode is.
     *
     * The host's choice is the authority; the guest is realizing it. This is the same assertion
     * as [partyResolveBeatsLocalDownloadAndPlaybackMode] read from the product's side, and it is
     * the half that must keep holding now that the *host* side is mode-driven.
     */
    @Test
    fun aGuestRealizingTheHostSourceIsNeverAskedAQuestion() {
        PlaybackMode.entries.forEach { mode ->
            assertTrue(
                PlaybackModeRouter.decide(
                    inputs(mode = mode, isPartyResolvePlayback = true),
                ) is PlaybackRouteDecision.AutoPick,
                "a guest in $mode must realize the host source rather than choose",
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
