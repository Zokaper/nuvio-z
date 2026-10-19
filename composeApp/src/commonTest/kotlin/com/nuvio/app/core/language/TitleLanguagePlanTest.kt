package com.nuvio.app.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleLanguagePlanTest {

    /** The maintainer's own setup: English; forced for English titles; original audio + full English otherwise. */
    private val maintainer = LanguagePreferences(
        myLanguage = "en",
        ownSubtitles = SubtitleKind.FORCED,
        otherAudio = OtherLanguageAudio.ORIGINAL,
        otherSubtitles = SubtitleKind.FULL,
    )

    private fun plan(
        preferences: LanguagePreferences = maintainer,
        original: String?,
        playing: String? = null,
        device: List<String> = listOf("en-US"),
    ) = resolveTitleLanguagePlan(preferences, device, original, playing)

    @Test
    fun englishShowGetsEnglishAudioAndForcedSubtitles() {
        val p = plan(original = "en", playing = "eng")
        assertEquals(TitleGroup.OWN, p.group)
        assertEquals("en", p.preferredAudio)
        assertEquals(SubtitleKind.FORCED, p.subtitleKind)
        assertTrue(p.forcedOnly)
        assertEquals("en", p.preferredSubtitle)
    }

    @Test
    fun koreanFilmGetsKoreanAudioThenEnglishAndFullEnglishSubtitles() {
        val p = plan(original = "ko", playing = "kor")
        assertEquals(TitleGroup.OTHER, p.group)
        assertEquals("ko", p.preferredAudio)
        assertEquals("en", p.secondaryAudio)
        assertEquals(SubtitleKind.FULL, p.subtitleKind)
        assertEquals(false, p.forcedOnly)
        assertEquals(false, p.preferSdh)
    }

    @Test
    fun foreignTitleWithOnlyMyLanguageAudioPlaysLikeAnOwnTitle() {
        // The release had no Korean track, so English plays: subtitles follow the English-title choice.
        val p = plan(original = "ko", playing = "eng")
        assertEquals(TitleGroup.OTHER, p.group)
        assertEquals(SubtitleKind.FORCED, p.subtitleKind)
    }

    @Test
    fun aChosenDubKeepsTheOtherLanguageSubtitleChoice() {
        val dubs = maintainer.copy(otherAudio = OtherLanguageAudio.MY_LANGUAGE, otherSubtitles = SubtitleKind.OFF)
        val p = plan(dubs, original = "ja", playing = "eng")
        assertEquals("en", p.preferredAudio)
        assertEquals("ja", p.secondaryAudio)
        assertEquals(SubtitleKind.OFF, p.subtitleKind)
        assertEquals(SubtitleLanguageOption.NONE, p.preferredSubtitle)
        assertNull(p.secondarySubtitle)
    }

    @Test
    fun unknownOriginLeavesTheFileDefaultAudioAndGroupsByWhatPlays() {
        val before = plan(original = null, playing = null)
        assertEquals(AudioLanguageOption.DEFAULT, before.preferredAudio)
        assertEquals(TitleGroup.OWN, before.group)

        val korean = plan(original = null, playing = "kor")
        assertEquals(AudioLanguageOption.DEFAULT, korean.preferredAudio)
        assertEquals(TitleGroup.OTHER, korean.group)
        assertEquals(SubtitleKind.FULL, korean.subtitleKind)
    }

    @Test
    fun deviceLanguageResolvesForGroupingAndIsResolvedInTheSecondSlot() {
        val device = maintainer.copy(myLanguage = AudioLanguageOption.DEVICE)
        assertEquals(TitleGroup.OWN, plan(device, original = "en", device = listOf("en-GB")).group)
        val other = plan(device, original = "fr", device = listOf("en-GB"))
        assertEquals("fr", other.preferredAudio)
        assertEquals("en-gb", other.secondaryAudio)
        // The primary slot keeps the sentinel: upstream resolves it there, with the device's whole list.
        assertEquals(AudioLanguageOption.DEVICE, plan(device, original = "en").preferredAudio)
    }

    @Test
    fun subtitleOverrideAndFallbackReachTheSubtitleFields() {
        val p = plan(maintainer.copy(subtitleLanguage = "es", fallbackLanguage = "pt-BR", ownSubtitles = SubtitleKind.SDH), original = "en")
        assertEquals("es", p.preferredSubtitle)
        assertEquals("pt-BR", p.secondarySubtitle)
        assertEquals(true, p.preferSdh)
        assertEquals("pt-BR", p.secondaryAudio)
    }

    @Test
    fun regionalVariantsCountAsMyLanguage() {
        assertEquals(TitleGroup.OWN, plan(maintainer.copy(myLanguage = "pt-BR"), original = "pt").group)
    }

    // --- legacy fields ---------------------------------------------------------------------------

    @Test
    fun legacyFieldsDescribeATitleInMyLanguage() {
        assertEquals(
            LegacyLanguageFields("en", null, "en", null, useForcedSubtitles = true),
            legacyLanguageFields(maintainer),
        )
        assertEquals(
            LegacyLanguageFields("en", null, SubtitleLanguageOption.NONE, null, useForcedSubtitles = false),
            legacyLanguageFields(maintainer.copy(ownSubtitles = SubtitleKind.OFF, fallbackLanguage = "de")).copy(secondaryAudio = null),
        )
    }

    // --- migration: nobody's playback changes on update -----------------------------------------

    private fun migrate(
        audio: String?, subtitle: String?, forced: Boolean,
        secondaryAudio: String? = null, secondarySubtitle: String? = null,
        device: List<String> = listOf("en-US"),
    ) = migrateLegacyLanguageFields(audio, secondaryAudio, subtitle, secondarySubtitle, forced, device)

    @Test
    fun freshDefaultsMigrateToNoSubtitlesAndTheDubWhenThereIsOne() {
        // Upstream's defaults: audio "device", subtitles "none", switch off.
        val m = migrate(AudioLanguageOption.DEVICE, SubtitleLanguageOption.NONE, forced = false)
        assertEquals(AudioLanguageOption.DEVICE, m.myLanguage)
        assertEquals(SubtitleKind.OFF, m.ownSubtitles)
        assertEquals(SubtitleKind.OFF, m.otherSubtitles)
        assertEquals(OtherLanguageAudio.MY_LANGUAGE, m.otherAudio)
        assertNull(m.subtitleLanguage)
    }

    @Test
    fun theMaintainersOldSettingsMigrate() {
        // English audio, English subtitles, Use Forced Subtitles on (Debug 115 logs, 2026-10-19).
        val m = migrate("en", "en", forced = true)
        assertEquals("en", m.myLanguage)
        assertNull(m.subtitleLanguage)
        assertEquals(SubtitleKind.FORCED, m.ownSubtitles)
        assertEquals(SubtitleKind.FULL, m.otherSubtitles)
        assertEquals(OtherLanguageAudio.MY_LANGUAGE, m.otherAudio)
    }

    @Test
    fun differentAudioAndSubtitleLanguagesSurviveAsTheOverride() {
        val m = migrate("ja", "en", forced = false)
        assertEquals("ja", m.myLanguage)
        assertEquals("en", m.subtitleLanguage)
        assertEquals(SubtitleKind.FULL, m.ownSubtitles)
    }

    @Test
    fun originalOrFileDefaultAudioMigratesToOriginal() {
        assertEquals(OtherLanguageAudio.ORIGINAL, migrate(AudioLanguageOption.ORIGINAL, "en", false).otherAudio)
        val fileDefault = migrate(AudioLanguageOption.DEFAULT, "de", false)
        assertEquals(OtherLanguageAudio.ORIGINAL, fileDefault.otherAudio)
        assertEquals("de", fileDefault.myLanguage)
    }

    @Test
    fun forcedAsALanguageWithoutTheSwitchWasNothingAndStaysNothing() {
        val m = migrate("en", SubtitleLanguageOption.FORCED, forced = false)
        assertEquals(SubtitleKind.OFF, m.ownSubtitles)
        assertEquals(SubtitleKind.OFF, m.otherSubtitles)
    }

    @Test
    fun deviceSubtitlesMatchingADeviceLanguageNeedNoOverride() {
        val m = migrate(AudioLanguageOption.DEVICE, SubtitleLanguageOption.DEVICE, forced = false)
        assertNull(m.subtitleLanguage)
        assertEquals(SubtitleKind.FULL, m.ownSubtitles)
    }

    @Test
    fun theFirstUsefulSecondaryBecomesTheFallback() {
        assertEquals("es", migrate("en", "en", false, secondaryAudio = "es").fallbackLanguage)
        assertEquals("fr", migrate("en", "en", false, secondaryAudio = "en", secondarySubtitle = "fr").fallbackLanguage)
        assertNull(migrate("en", "en", false, secondarySubtitle = SubtitleLanguageOption.FORCED).fallbackLanguage)
    }
}
