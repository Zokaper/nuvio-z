package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerOpeningPresentationTest {
    private val nextEpisode = MetaVideo(
        id = "tt2:2:10",
        title = "The Episode After",
        season = 2,
        episode = 10,
        thumbnail = "https://img/still-2x10.jpg",
    )

    private fun presentation(startingEpisode: MetaVideo?, logo: String? = "https://img/logo.png") =
        playerOpeningPresentation(
            showLogo = logo,
            showTitle = "A Show",
            background = "https://img/backdrop.jpg",
            poster = "https://img/poster.jpg",
            startingEpisode = startingEpisode,
        )

    @Test fun aNextEpisodeDrawsTheSameLogoAsAFirstLaunch() {
        // ⚠ Regression, hardware Bug 4 (2026-09-15): the starting-episode branch passed a null logo
        // and the episode title, so every next episode printed plain text where a first launch drew
        // the logo - in ordinary playback and in Watch Together alike.
        val first = presentation(startingEpisode = null)
        val next = presentation(startingEpisode = nextEpisode)
        assertEquals("https://img/logo.png", next.logo)
        assertEquals(first.logo, next.logo)
        assertEquals(first.title, next.title)
    }

    @Test fun theTextFallbackIsTheShowTitleExactlyAsOnAFirstLaunch() {
        assertEquals("A Show", presentation(startingEpisode = nextEpisode, logo = null).title)
        assertEquals(null, presentation(startingEpisode = nextEpisode, logo = " ").logo)
    }

    @Test fun theBackdropStillPrefersTheStartingEpisodesOwnStill() {
        assertEquals("https://img/still-2x10.jpg", presentation(nextEpisode).artwork)
        assertEquals("https://img/backdrop.jpg", presentation(null).artwork)
        assertEquals("https://img/backdrop.jpg", presentation(nextEpisode.copy(thumbnail = "")).artwork)
    }
}
