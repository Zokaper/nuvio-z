package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A next episode and a first launch draw the same opening surface: the presentation no longer takes the
 * starting episode at all. ⚠ Regressions it guards: hardware Bug 4 (2026-09-15), a next episode printed
 * the episode title where a first launch drew the logo; and 2026-10-19, a next episode drew the episode's
 * small still where a first launch drew the show's backdrop.
 */
class PlayerOpeningPresentationTest {
    private fun presentation(
        logo: String? = "https://img/logo.png",
        background: String? = "https://img/backdrop.jpg",
    ) = playerOpeningPresentation(
        showLogo = logo,
        showTitle = "A Show",
        background = background,
        poster = "https://img/poster.jpg",
    )

    @Test fun theLogoAndTitleAreTheShows() {
        assertEquals("https://img/logo.png", presentation().logo)
        assertEquals("A Show", presentation(logo = null).title)
        assertEquals(null, presentation(logo = " ").logo)
    }

    @Test fun theBackdropIsTheShowsFallingBackToThePoster() {
        assertEquals("https://img/backdrop.jpg", presentation().artwork)
        assertEquals("https://img/poster.jpg", presentation(background = null).artwork)
    }
}
