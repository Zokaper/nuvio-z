package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackModeRouterTest {

    private fun inputs(
        mode: PlaybackMode = PlaybackMode.CLASSIC,
        manualSelection: Boolean = false,
        hasCompletedLocalDownload: Boolean = false,
        hasMatchingStickyPin: Boolean = false,
        reuseLastLinkEnabled: Boolean = false,
        hasValidCachedLink: Boolean = false,
    ) = PlaybackRouteInputs(
        mode = mode,
        manualSelection = manualSelection,
        hasCompletedLocalDownload = hasCompletedLocalDownload,
        hasMatchingStickyPin = hasMatchingStickyPin,
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
                    hasMatchingStickyPin = true,
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
                    hasMatchingStickyPin = true,
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
     * The regression this whole precedence table exists for.
     *
     * Reuse-last-link fires before auto-play in the live route, so without the pin sitting
     * above it a Streamlined user who has reuse enabled would never reach the quality
     * sheet or their pinned release for any episode they had already watched once.
     */
    @Test
    fun stickyPinBeatsReuseLastLinkInStreamlined() {
        val decision = PlaybackModeRouter.decide(
            inputs(
                mode = PlaybackMode.STREAMLINED,
                hasMatchingStickyPin = true,
                reuseLastLinkEnabled = true,
                hasValidCachedLink = true,
            ),
        )
        assertTrue(decision is PlaybackRouteDecision.PlayStickyPin, "got $decision")
    }

    @Test
    fun reuseLastLinkStillServesTheUnpinnedStreamlinedCase() {
        val decision = PlaybackModeRouter.decide(
            inputs(
                mode = PlaybackMode.STREAMLINED,
                hasMatchingStickyPin = false,
                reuseLastLinkEnabled = true,
                hasValidCachedLink = true,
            ),
        )
        assertTrue(decision is PlaybackRouteDecision.ReuseLastLink, "got $decision")
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
    }

    /** Classic never auto-picks, so a pin must not divert it away from the source list. */
    @Test
    fun classicIgnoresStickyPins() {
        val decision = PlaybackModeRouter.decide(
            inputs(mode = PlaybackMode.CLASSIC, hasMatchingStickyPin = true),
        )
        assertTrue(decision is PlaybackRouteDecision.ShowSourceList, "got $decision")
    }

    /** Instant answers to the connection, not to a release pinned three episodes ago. */
    @Test
    fun instantIgnoresStickyPins() {
        val decision = PlaybackModeRouter.decide(
            inputs(mode = PlaybackMode.INSTANT, hasMatchingStickyPin = true),
        )
        assertTrue(decision is PlaybackRouteDecision.AutoPick, "got $decision")
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
