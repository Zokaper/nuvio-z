package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hardware 2026-09-15: Next episode auto-selected in Streamlined. Desktop now asks Streamlined's
 * question in the native episode list; these pin what the rows are and what choosing one does.
 */
class PlayerEpisodeQualityChooserTest {
    private val context = PlaybackSelectionContext(isEpisode = true)

    @Test
    fun nothingIsOfferedWhileTheCatalogueIsStillArriving() {
        // Rows are derived from the whole catalogue; a late addon would reshuffle them under the pointer.
        val arriving = StreamsUiState(
            groups = listOf(AddonStreamGroup("Fast", "fast", listOf(stream("A.1080p.WEB-DL", "a")))),
            isAnyLoading = true,
        )
        assertEquals(emptyList(), episodeQualityChoices(arriving, context))
    }

    @Test
    fun aSettledCatalogueOffersTheQualityRowsThenTheManualEscape() {
        val settled = StreamsUiState(
            groups = listOf(
                AddonStreamGroup(
                    "Addon",
                    "addon",
                    listOf(stream("Show.S01E02.2160p.WEB-DL", "uhd"), stream("Show.S01E02.1080p.WEB-DL", "fhd")),
                ),
            ),
        )
        val choices = episodeQualityChoices(settled, context)
        assertTrue(choices.dropLast(1).isNotEmpty(), "quality rows")
        assertTrue(choices.dropLast(1).all { it is EpisodeQualityChoice.Option })
        assertEquals(EpisodeQualityChoice.ChooseManually, choices.last())
    }

    @Test
    fun anEmptySettledCatalogueStillLeavesTheManualEscape() {
        assertEquals(
            listOf<EpisodeQualityChoice>(EpisodeQualityChoice.ChooseManually),
            episodeQualityChoices(StreamsUiState(groups = emptyList()), context),
        )
    }

    @Test
    fun choosingAQualityPlaysWithinItAndTheManualRowOpensTheList() {
        val settled = StreamsUiState(
            groups = listOf(AddonStreamGroup("Addon", "addon", listOf(stream("Show.S01E02.1080p.WEB-DL", "fhd")))),
        )
        val choices = episodeQualityChoices(settled, context)
        val option = choices.first { it is EpisodeQualityChoice.Option }
        assertEquals("https://example.com/fhd.mp4", assertIs<EpisodeQualityPick.Play>(decideEpisodeQualityPick(option, context)).stream.url)
        assertEquals(EpisodeQualityPick.ShowSourceList, decideEpisodeQualityPick(EpisodeQualityChoice.ChooseManually, context))
    }

    private fun stream(name: String, id: String) = StreamItem(
        name = name,
        title = name,
        url = "https://example.com/$id.mp4",
        addonName = "Addon",
        addonId = "addon",
    )
}
