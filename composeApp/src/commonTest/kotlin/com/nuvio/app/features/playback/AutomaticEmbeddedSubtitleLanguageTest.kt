package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which plays "Prefer built-in subtitles" may rank for at all. */
class AutomaticEmbeddedSubtitleLanguageTest {

    private fun language(
        enabled: Boolean = true,
        mode: PlaybackMode = PlaybackMode.STREAMLINED,
        manualSelection: Boolean = false,
        downloadIntent: Boolean = false,
        target: String? = "en",
    ) = automaticEmbeddedSubtitleLanguage(enabled, mode, manualSelection, downloadIntent, target)

    @Test
    fun streamlinedAndInstantRankForThePrimarySubtitleLanguage() {
        assertEquals("en", language(mode = PlaybackMode.STREAMLINED))
        assertEquals("en", language(mode = PlaybackMode.INSTANT))
    }

    @Test
    fun classicIsUntouched() {
        assertNull(language(mode = PlaybackMode.CLASSIC))
    }

    @Test
    fun manualPicksAndDownloadsAreUntouched() {
        assertNull(language(manualSelection = true))
        assertNull(language(downloadIntent = true))
    }

    @Test
    fun offOrNoSubtitleLanguageMeansNoHint() {
        assertNull(language(enabled = false))
        assertNull(language(target = null))
        assertNull(language(target = "  "))
    }

    @Test
    fun theContextCarriesItIntoTheRankingPreferences() {
        val context = PlaybackSelectionContext(isEpisode = false, preferredEmbeddedSubtitleLanguage = "en")
        assertEquals("en", context.rankingPreferences.preferredEmbeddedSubtitleLanguage)
        assertNull(PlaybackSelectionContext(isEpisode = false).rankingPreferences.preferredEmbeddedSubtitleLanguage)
    }
}
