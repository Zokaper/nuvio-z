package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.DownloadPreset
import com.nuvio.app.features.downloads.VideoResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlaybackModeDownloadRouterTest {

    @Test
    fun classicPicksTheReleaseForASingleItem() {
        assertIs<DownloadEntryDecision.ChooseSourceManually>(
            PlaybackModeDownloadRouter.decide(PlaybackMode.CLASSIC, isSingleItem = true),
        )
    }

    @Test
    fun classicFallsBackToPresetsForASeason() {
        // Hand-picking a release per episode is a chore, not control.
        assertIs<DownloadEntryDecision.ShowPresetDialog>(
            PlaybackModeDownloadRouter.decide(PlaybackMode.CLASSIC, isSingleItem = false),
        )
    }

    @Test
    fun streamlinedAlwaysShowsThePresetDialog() {
        assertIs<DownloadEntryDecision.ShowPresetDialog>(
            PlaybackModeDownloadRouter.decide(PlaybackMode.STREAMLINED, isSingleItem = true),
        )
        assertIs<DownloadEntryDecision.ShowPresetDialog>(
            PlaybackModeDownloadRouter.decide(PlaybackMode.STREAMLINED, isSingleItem = false),
        )
    }

    @Test
    fun instantNeverAsks() {
        assertIs<DownloadEntryDecision.StartWithPreset>(
            PlaybackModeDownloadRouter.decide(PlaybackMode.INSTANT, isSingleItem = true),
        )
        assertIs<DownloadEntryDecision.StartWithPreset>(
            PlaybackModeDownloadRouter.decide(PlaybackMode.INSTANT, isSingleItem = false),
        )
    }

    @Test
    fun presetForResolutionTakesTheHighestThatFitsUnderTheCeiling() {
        val picked = PlaybackModeDownloadRouter.presetForResolution(
            DownloadPreset.BuiltIns,
            VideoResolution.FULL_HD_1080,
        )
        assertEquals(VideoResolution.FULL_HD_1080, picked?.targetResolution)
    }

    @Test
    fun aSlowConnectionDoesNotGetA4kPreset() {
        val picked = PlaybackModeDownloadRouter.presetForResolution(
            DownloadPreset.BuiltIns,
            VideoResolution.SD,
        )
        // Every built-in exceeds 480p, so the smallest is the honest answer - never a 2160p
        // preset chosen by accident because nothing matched.
        assertEquals(
            DownloadPreset.BuiltIns.minOf { it.targetResolution.height },
            picked?.targetResolution?.height,
        )
    }

    @Test
    fun anUnknownConnectionIsTreatedConservatively() {
        val picked = PlaybackModeDownloadRouter.presetForResolution(DownloadPreset.BuiltIns, ceiling = null)
        assertEquals(
            DownloadPreset.BuiltIns.minOf { it.targetResolution.height },
            picked?.targetResolution?.height,
            "an unknown connection must not silently start a 4K download",
        )
    }

    @Test
    fun noPresetsMeansNoAutoStart() {
        assertNull(PlaybackModeDownloadRouter.presetForResolution(emptyList(), VideoResolution.UHD_2160))
    }

}
