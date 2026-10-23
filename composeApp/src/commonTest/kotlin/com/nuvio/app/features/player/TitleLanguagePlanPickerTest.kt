package com.nuvio.app.features.player

import com.nuvio.app.core.language.LanguagePreferences
import com.nuvio.app.core.language.OtherLanguageAudio
import com.nuvio.app.core.language.SubtitleKind
import com.nuvio.app.core.language.resolveTitleLanguagePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The language plan carried through upstream's subtitle picker, as the player runs it. */
class TitleLanguagePlanPickerTest {

    /**
     * The maintainer's phone, 2026-10-23, debug 111: My language stored as "en-sa", Attack on Titan
     * S1E1 with Japanese audio and one subtitle track, `eng` "[ASS]", on by default. The pick logged
     * `subtitle_pick chosen=none applied=true pref=en-sa/null` and turned that track off.
     */
    @Test
    fun regionalMyLanguageStillPicksTheEnglishTrackOfAForeignShow() {
        val preferences = LanguagePreferences(
            myLanguage = "en-sa",
            ownSubtitles = SubtitleKind.FULL,
            otherAudio = OtherLanguageAudio.ORIGINAL,
            otherSubtitles = SubtitleKind.FULL,
        )
        val audio = AudioTrack(index = 0, id = "audio-0", label = "[FLAC 2.0]", language = "jpn", isSelected = true)
        val titlePlan = resolveTitleLanguagePlan(preferences, listOf("en-SA", "en"), "ja", "jpn")
        val settings = PlayerSettingsUiState().withTitleLanguagePlan(titlePlan)

        val subtitlePlan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audio,
                preferredAudioTargets = listOfNotNull(settings.preferredAudioLanguage),
                preferredSubtitleTargets = resolvePreferredSubtitleLanguageTargets(
                    preferredSubtitleLanguage = settings.preferredSubtitleLanguage,
                    secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage,
                    deviceLanguages = listOf("en-sa", "en"),
                ),
                useForcedSubtitles = settings.subtitleStyle.useForcedSubtitles,
            ),
        )
        val chosen = findPreferredSubtitleTrackIndex(
            tracks = listOf(SubtitleTrack(index = 0, id = "track-0", label = "[ASS]", language = "eng", isForced = false)),
            targets = subtitlePlan.targets,
            mode = subtitlePlan.mode,
            selectedAudioTrack = audio,
            preferSdh = titlePlan.preferSdh,
        )

        assertEquals(0, chosen)
    }
}
