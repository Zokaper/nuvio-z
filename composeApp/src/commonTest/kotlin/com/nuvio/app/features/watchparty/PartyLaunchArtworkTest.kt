package com.nuvio.app.features.watchparty

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartyLaunchArtworkTest {

    private val movie = PartyContent(
        contentId = "tt1",
        contentType = "movie",
        videoId = "tt1",
        title = "A Movie",
        poster = "party-poster",
    )

    private val episode = PartyContent(
        contentId = "tt2",
        contentType = "series",
        videoId = "tt2:2:9",
        title = "A Show",
        poster = "party-poster",
        season = 2,
        episode = 9,
        episodeTitle = "Nine",
    )

    private fun meta(videos: List<MetaVideo> = emptyList()) = MetaDetails(
        id = "tt2",
        type = "series",
        name = "A Show",
        poster = "meta-poster",
        background = "meta-background",
        logo = "meta-logo",
        videos = videos,
    )

    @Test fun aMovieTakesLogoAndBackgroundFromTheMeta() {
        val artwork = partyLaunchArtwork(movie, meta())
        assertEquals("meta-logo", artwork.logo)
        assertEquals("meta-background", artwork.background)
        assertNull(artwork.episodeThumbnail)
    }

    @Test fun thePartysOwnPosterWinsOverTheMetas() {
        // It is what the host was looking at when they started the party, and the two clients can
        // legitimately resolve different posters.
        assertEquals("party-poster", partyLaunchArtwork(movie, meta()).poster)
        assertEquals("meta-poster", partyLaunchArtwork(movie.copy(poster = null), meta()).poster)
    }

    @Test fun anEpisodeThumbnailIsFoundByTheVideoIdThePartyAgreedOn() {
        val artwork = partyLaunchArtwork(
            episode,
            meta(
                listOf(
                    MetaVideo(id = "tt2:2:8", title = "Eight", thumbnail = "wrong", season = 2, episode = 8),
                    MetaVideo(id = "tt2:2:9", title = "Nine", thumbnail = "right", season = 2, episode = 9),
                ),
            ),
        )
        assertEquals("right", artwork.episodeThumbnail)
        assertEquals("meta-logo", artwork.logo)
    }

    @Test fun aDifferentlyNumberedAddonStillFindsTheEpisodeBySeasonAndNumber() {
        // The same content addressed the way another client's addon happens to id it. Falling back
        // to season/episode is what keeps the guest's loading screen looking like the host's.
        val artwork = partyLaunchArtwork(
            episode,
            meta(listOf(MetaVideo(id = "other-scheme-9", title = "Nine", thumbnail = "right", season = 2, episode = 9))),
        )
        assertEquals("right", artwork.episodeThumbnail)
    }

    @Test fun anEpisodeThatIsNotInTheMetaSimplyHasNoThumbnail() {
        val artwork = partyLaunchArtwork(
            episode,
            meta(listOf(MetaVideo(id = "tt2:3:1", title = "One", thumbnail = "wrong", season = 3, episode = 1))),
        )
        assertNull(artwork.episodeThumbnail)
        // The rest of the artwork is still hydrated - a missing thumbnail is not a missing show.
        assertEquals("meta-logo", artwork.logo)
        assertEquals("meta-background", artwork.background)
    }

    @Test fun noMetaAtAllKeepsTheIdentityThePartyDidCarry() {
        // `PlaybackLoadingScreen`'s fall back to title text is correct when the artwork genuinely
        // does not exist, so this must degrade rather than invent anything.
        val artwork = partyLaunchArtwork(episode, null)
        assertEquals("party-poster", artwork.poster)
        assertNull(artwork.logo)
        assertNull(artwork.background)
        assertNull(artwork.episodeThumbnail)
    }
}
