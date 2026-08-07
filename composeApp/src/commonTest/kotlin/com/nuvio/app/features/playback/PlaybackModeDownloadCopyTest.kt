package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mode cards tell the user what pressing Download will do. This pins those claims to
 * [PlaybackModeDownloadRouter], which is what actually happens - copy that contradicts the
 * router is worse than no copy, and nothing else would catch the two drifting apart.
 *
 * Each case names the string key whose wording it is guarding.
 */
class PlaybackModeDownloadCopyTest {

    @Test
    fun classicIsTheOnlyModeThatDependsOnTheScope() {
        // playback_mode_classic_download: "One episode or movie opens the source list; a whole
        // season uses your preset". Both halves have to be true, and only for Classic.
        assertTrue(
            PlaybackModeDownloadRouter.decide(PlaybackMode.CLASSIC, isSingleItem = true)
                is DownloadEntryDecision.ChooseSourceManually,
        )
        assertTrue(
            PlaybackModeDownloadRouter.decide(PlaybackMode.CLASSIC, isSingleItem = false)
                is DownloadEntryDecision.ShowPresetDialog,
        )

        for (mode in listOf(PlaybackMode.STREAMLINED, PlaybackMode.INSTANT)) {
            assertEquals(
                PlaybackModeDownloadRouter.decide(mode, isSingleItem = true)::class,
                PlaybackModeDownloadRouter.decide(mode, isSingleItem = false)::class,
                "$mode's card promises one behaviour regardless of scope",
            )
        }
    }

    @Test
    fun streamlinedAlwaysAsksWhichPreset() {
        // playback_mode_streamlined_download: "Always asks which preset to use".
        for (single in listOf(true, false)) {
            assertTrue(
                PlaybackModeDownloadRouter.decide(PlaybackMode.STREAMLINED, single)
                    is DownloadEntryDecision.ShowPresetDialog,
            )
        }
    }

    @Test
    fun instantNeverAsks() {
        // playback_mode_instant_download: "Starts straight away with the preset matching your
        // connection". No dialog, in either scope.
        for (single in listOf(true, false)) {
            assertTrue(
                PlaybackModeDownloadRouter.decide(PlaybackMode.INSTANT, single)
                    is DownloadEntryDecision.StartWithPreset,
            )
        }
    }
}
