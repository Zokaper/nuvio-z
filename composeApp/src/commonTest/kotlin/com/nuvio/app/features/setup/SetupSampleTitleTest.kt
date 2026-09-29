package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupSampleTitleTest {

    private val pinnedHost = "https://image.tmdb.org/t/p/w780/"

    @Test
    fun everyEpisodeASpecimenShowsIsAPinnedStill() {
        val shown = SetupSampleTitle.episodes.map { it.stillUrl } +
            SetupSampleTitle.continueWatching.mapNotNull { it.episodeThumbnail } +
            // The player previews (Sherlock S1 E1) and the Welcome still's episode card.
            SetupSampleTitle.episodeStillUrl("tt1475582", 1, 1) +
            SetupSampleTitle.episodeStillUrl(SetupSampleTitle.featuredImdbId, 1, 2)

        shown.forEach { url ->
            assertTrue(url.startsWith(pinnedHost), "not pinned to the TMDB image CDN: $url")
        }
    }

    @Test
    fun anUnpinnedEpisodeUsesTheShapeCinemetaEmits() {
        assertEquals(
            "https://episodes.metahub.space/tt0944947/1/1/w780.jpg",
            SetupSampleTitle.episodeStillUrl("tt0944947", 1, 1),
        )
    }
}
