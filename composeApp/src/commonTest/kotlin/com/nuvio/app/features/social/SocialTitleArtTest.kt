package com.nuvio.app.features.social

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SocialTitleArtTest {
    private val meta = MetaDetails(
        id = "tt0121955", type = "series", name = "South Park",
        poster = "poster.jpg", background = "backdrop.jpg",
        videos = listOf(
            MetaVideo(id = "tt0121955:2:6", title = "Conjoined Fetus Lady", season = 2, episode = 6, thumbnail = "s2e6.jpg"),
            MetaVideo(id = "tt0121955:2:7", title = "City on the Edge", season = 2, episode = 7),
        ),
    )

    @Test fun anEpisodeStillIsFoundByVideoIdFirst() {
        assertEquals("s2e6.jpg", socialTitleArt(meta).episodeThumbnail("tt0121955:2:6", null, null))
    }

    @Test fun anAddonThatNumbersDifferentlyStillFindsTheEpisodeBySeasonAndNumber() {
        assertEquals("s2e6.jpg", socialTitleArt(meta).episodeThumbnail("other-addon:42", 2, 6))
    }

    @Test fun anEpisodeWithoutAStillFallsThroughToTheBackdrop() {
        val art = socialTitleArt(meta)
        assertNull(art.episodeThumbnail("tt0121955:2:7", 2, 7))
        assertEquals("backdrop.jpg", art.background)
    }

    @Test fun noMetadataMeansNoLandscapeArt() {
        assertEquals(SocialTitleArt.None, socialTitleArt(null))
        assertNull(socialTitleArt(meta.copy(background = " ")).background)
    }
}
