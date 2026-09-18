package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.AudioPreference
import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The factory every selection context is built by, and the drift it exists to stop.
 *
 * ⚠ **Three hand-built copies of this had drifted apart.** The stream route's was complete; the
 * in-player next-episode sheet's set six of thirteen fields, silently dropping the quality ceiling,
 * the language requirement, the secondary audio language, the audio preference and the display
 * height. The user-visible shape was an episode picked under the ceiling you set followed by an
 * episode picked without it.
 *
 * **What this file can and cannot pin.** The two remaining call sites are inline in composables and
 * cannot be invoked from a test, so there is no mechanical three-way comparison to make. The
 * structural fix is that they are now *calls* rather than copies; what is pinned here is the thing
 * that makes those calls worth making - that the factory reads every setting it is supposed to
 * read. A field added to [PlaybackSelectionContext] and left unwired shows up as a value that
 * stayed at its default while the setting behind it did not.
 */
class PlaybackSelectionContextFactoryTest {

    /** Every field this factory is responsible for, set to something that is not its default. */
    private val tuned = PlayerSettingsUiState(
        playbackAllowTorrentAutopick = true,
        preferredAudioLanguage = "ja",
        secondaryPreferredAudioLanguage = "ko",
        preferredSubtitleLanguage = "en",
        secondaryPreferredSubtitleLanguage = "fr",
        playbackLanguageStrictness = LanguageStrictness.REQUIRE,
        playbackQualityCeilingMbps = 40,
        playbackCodecPreference = CodecPreference.AV1,
        playbackDynamicRangePolicy = DynamicRangePolicy.REQUIRE_DOLBY_VISION,
        playbackAudioPreference = AudioPreference.REQUIRE_LOSSLESS,
    )

    private fun contextOf(
        settings: PlayerSettingsUiState = tuned,
        isEpisode: Boolean = true,
        runtimeMinutes: Int? = 48,
        contentOriginalLanguage: String? = null,
        identity: RequestedContent? = null,
    ) = playbackSelectionContextOf(
        settings = settings,
        isEpisode = isEpisode,
        runtimeMinutes = runtimeMinutes,
        contentOriginalLanguage = contentOriginalLanguage,
        identity = identity,
        displayMaxHeight = 2160,
        deviceLanguages = listOf("de"),
    )

    @Test
    fun everySettingReachesTheContext() {
        val context = contextOf()

        assertEquals(48, context.runtimeMinutes)
        assertEquals(true, context.isEpisode)
        assertEquals(true, context.allowTorrentSources)
        assertEquals("ja", context.preferredAudioLanguage)
        assertEquals("ko", context.secondaryAudioLanguage)
        assertEquals("en", context.preferredSubtitleLanguage)
        assertEquals("fr", context.secondarySubtitleLanguage)
        assertEquals(LanguageStrictness.REQUIRE, context.languageStrictness)
        assertEquals(40.0, context.qualityCeilingMbps)
        assertEquals(CodecPreference.AV1, context.codecPreference)
        assertEquals(DynamicRangePolicy.REQUIRE_DOLBY_VISION, context.dynamicRangePolicy)
        assertEquals(AudioPreference.REQUIRE_LOSSLESS, context.audioPreference)
        assertEquals(2160, context.displayMaxHeight)
    }

    /**
     * The five the next-episode sheet used to drop. Pinned as a group and by name, because
     * "it compiles" was true of the broken version too.
     */
    @Test
    fun theFieldsTheNextEpisodeSheetUsedToDropAreNotAtTheirDefaults() {
        val context = contextOf()
        val bare = PlaybackSelectionContext(isEpisode = true)

        assertNotEquals(bare.secondaryAudioLanguage, context.secondaryAudioLanguage)
        assertNotEquals(bare.languageStrictness, context.languageStrictness)
        assertNotEquals(bare.audioPreference, context.audioPreference)
        assertNotEquals(bare.qualityCeilingMbps, context.qualityCeilingMbps)
        assertNotEquals(bare.displayMaxHeight, context.displayMaxHeight)
    }

    /** A ceiling of `0` is "no ceiling", not a ceiling of zero megabits. */
    @Test
    fun anUnsetCeilingIsNoCeilingRatherThanZero() {
        val context = contextOf(settings = tuned.copy(playbackQualityCeilingMbps = 0))
        assertNull(context.qualityCeilingMbps)
    }

    @Test
    fun sentinelsAreResolvedRatherThanPassedThrough() {
        val context = contextOf(
            settings = tuned.copy(preferredAudioLanguage = "original", preferredSubtitleLanguage = "device"),
            contentOriginalLanguage = "ja",
        )
        assertEquals("ja", context.preferredAudioLanguage)
        assertEquals("de", context.preferredSubtitleLanguage)
    }

    /**
     * ⚠ Identity is the one field the factory must never decide for itself - it is what stops a
     * source for a different episode being played, and a manual pick is the user reading the
     * release name and choosing anyway. Callers that serve Classic and every manual path pass
     * null, so null is the default.
     */
    @Test
    fun identityIsTheCallersToSetAndDefaultsToAbsent() {
        assertNull(contextOf().identity)
        val identified = contextOf(identity = RequestedContent(season = 2, episode = 6, year = 2016))
        assertEquals(2, identified.identity?.season)
        assertEquals(6, identified.identity?.episode)
    }
}
