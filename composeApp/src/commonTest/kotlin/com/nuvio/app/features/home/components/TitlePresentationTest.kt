package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class TitlePresentationTest {
    private val item = TitlePresentation(
        title = "Episode",
        poster = "poster",
        background = "background",
        episodeThumbnail = "episode",
        fallbackArtwork = "fallback",
    )

    @Test
    fun `card and wide honor episode thumbnail preference`() {
        assertEquals("episode", item.artwork(ContinueWatchingSectionStyle.Card, true))
        assertEquals("episode", item.artwork(ContinueWatchingSectionStyle.Wide, true))
        assertEquals("background", item.artwork(ContinueWatchingSectionStyle.Card, false))
        assertEquals("background", item.artwork(ContinueWatchingSectionStyle.Wide, false))
    }

    @Test
    fun `poster keeps portrait art ahead of landscape art`() {
        assertEquals("poster", item.artwork(ContinueWatchingSectionStyle.Poster, true))
    }

    @Test
    fun `blank artwork falls through consistently`() {
        assertEquals(
            "fallback",
            TitlePresentation(title = "Movie", poster = " ", fallbackArtwork = " fallback ")
                .artwork(ContinueWatchingSectionStyle.Card, true),
        )
    }
}
