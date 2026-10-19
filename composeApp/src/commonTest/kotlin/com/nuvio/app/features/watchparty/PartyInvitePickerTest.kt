package com.nuvio.app.features.watchparty

import com.nuvio.app.features.player.PartyInviteButton
import com.nuvio.app.features.social.SocialPlaybackState
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.WatchingNowItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyInvitePickerTest {
    private fun row(name: String, watching: String? = null) =
        PartyInviteRow(key = name, name = name, avatarUrl = null, colorHex = null, watching = watching, button = PartyInviteButton.Invite)

    private fun watching(profileId: String, title: String, season: Int? = null, episode: Int? = null) = WatchingNowItem(
        profile = SocialProfileSummary(profileId = profileId, handle = profileId, displayName = profileId),
        contentId = "tt1",
        contentType = if (season != null) "series" else "movie",
        videoId = "tt1",
        title = title,
        season = season,
        episode = episode,
        positionMs = 0,
        durationMs = 1,
        state = SocialPlaybackState.playing,
        heartbeatAt = "2026-10-19T12:00:00Z",
    )

    @Test
    fun watchingNowComesFirstThenNamesIgnoringCase() {
        val ordered = partyInviteOrder(listOf(row("zed"), row("Ana"), row("bob", watching = "Futurama"), row("al", watching = "The Bear")))
        assertEquals(listOf("al", "bob", "Ana", "zed"), ordered.map { it.name })
    }

    @Test
    fun searchMatchesAnyPartOfTheNameAndBlankMatchesAll() {
        assertTrue(partyInviteMatches(row("jeremy renner"), "REN"))
        assertTrue(partyInviteMatches(row("jeremy renner"), "  "))
        assertFalse(partyInviteMatches(row("jeremy renner"), "seraph"))
    }

    @Test
    fun watchingLineNamesTheEpisodeWhenThereIsOne() {
        val now = listOf(watching("a", "The Bear", season = 3, episode = 4), watching("b", "Futurama"))
        assertEquals("The Bear · S3 E4", partyInviteWatching("a", now))
        assertEquals("Futurama", partyInviteWatching("b", now))
        assertNull(partyInviteWatching("c", now))
    }
}
