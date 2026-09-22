package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The post-open half of "Prefer built-in subtitles": mpv's track list is the authority, a miss
 * falls back to ordinary subtitle selection, and nothing changes outside Streamlined/Instant picks.
 */
class PlayerEmbeddedSubtitlePreferenceTest {

    private fun track(
        index: Int,
        language: String?,
        external: Boolean = false,
        forced: Boolean = false,
        default: Boolean = false,
        label: String = language.orEmpty(),
        originKnown: Boolean = true,
    ) = SubtitleTrack(
        index = index,
        id = (index + 1).toString(),
        label = label,
        language = language,
        isForced = forced,
        isExternal = external,
        isDefault = default,
        isOriginKnown = originKnown,
    )

    @Test
    fun aContainerTrackInThePreferredLanguageIsConfirmed() {
        val tracks = listOf(track(0, "fre"), track(1, "eng"))
        val verification = verifyEmbeddedSubtitles(tracks, "en")

        assertEquals(EmbeddedSubtitleOutcome.CONFIRMED, verification.outcome)
        assertEquals(1, verification.trackIndex)
        assertEquals(listOf("fre", "eng"), verification.embeddedLanguages)
    }

    @Test
    fun theContainerTrackBeatsASidecarInTheSameLanguage() {
        // The sidecar comes first in the list, which is exactly what used to win.
        val tracks = listOf(track(0, "en", external = true), track(1, "eng"))
        val verification = verifyEmbeddedSubtitles(tracks, "en")

        assertEquals(EmbeddedSubtitleOutcome.CONFIRMED, verification.outcome)
        assertEquals(1, verification.trackIndex)
    }

    @Test
    fun anExternalTrackAloneIsNotConfirmationAndFallsBack() {
        val verification = verifyEmbeddedSubtitles(listOf(track(0, "en", external = true)), "en")

        assertEquals(EmbeddedSubtitleOutcome.NO_EMBEDDED_TRACKS, verification.outcome)
        assertEquals(-1, verification.trackIndex)
    }

    @Test
    fun embeddedTracksInOtherLanguagesFallBack() {
        val verification = verifyEmbeddedSubtitles(listOf(track(0, "spa"), track(1, "ger")), "en")

        assertEquals(EmbeddedSubtitleOutcome.OTHER_LANGUAGES_ONLY, verification.outcome)
        assertEquals(-1, verification.trackIndex)
        assertEquals(listOf("spa", "ger"), verification.embeddedLanguages)
    }

    @Test
    fun aFileWithNoSubtitleTracksFallsBack() {
        assertEquals(EmbeddedSubtitleOutcome.NO_EMBEDDED_TRACKS, verifyEmbeddedSubtitles(emptyList(), "en").outcome)
    }

    @Test
    fun forcedTracksAreNotFullSubtitles() {
        val verification = verifyEmbeddedSubtitles(listOf(track(0, "eng", forced = true)), "en")
        assertEquals(EmbeddedSubtitleOutcome.OTHER_LANGUAGES_ONLY, verification.outcome)
    }

    @Test
    fun theContainersDefaultFlagBreaksATie() {
        val tracks = listOf(
            track(0, "eng", label = "English SDH"),
            track(1, "eng", label = "English", default = true),
        )
        assertEquals(1, verifyEmbeddedSubtitles(tracks, "en").trackIndex)
    }

    @Test
    fun anEngineThatCannotTellOriginConfirmsNothing() {
        // A bridge built before `external` was reported. Reading its false as "inside the file"
        // would call an addon subtitle built-in.
        val tracks = listOf(track(0, "eng", originKnown = false))
        assertEquals(EmbeddedSubtitleOutcome.ORIGIN_UNKNOWN, verifyEmbeddedSubtitles(tracks, "en").outcome)
    }

    @Test
    fun activeOnlyForStreamlinedAndInstantAutomaticPicks() {
        assertTrue(active(mode = PlaybackMode.STREAMLINED))
        assertTrue(active(mode = PlaybackMode.INSTANT))
        assertFalse(active(mode = PlaybackMode.CLASSIC), "Classic is unchanged")
    }

    @Test
    fun manualChoicesAreNeverOverridden() {
        assertFalse(active(sourceAutoPicked = false), "a hand-picked source keeps its subtitle behaviour")
        assertFalse(active(userChoseSubtitle = true), "a hand-picked subtitle stands")
        assertFalse(active(enabled = false), "the preference is off by default")
    }

    private fun active(
        enabled: Boolean = true,
        mode: PlaybackMode = PlaybackMode.STREAMLINED,
        sourceAutoPicked: Boolean = true,
        userChoseSubtitle: Boolean = false,
    ) = isEmbeddedSubtitlePreferenceActive(enabled, mode, sourceAutoPicked, userChoseSubtitle)
}
